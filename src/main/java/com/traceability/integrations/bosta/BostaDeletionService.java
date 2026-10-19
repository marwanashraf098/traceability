package com.traceability.integrations.bosta;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deleted-in-Bosta slice 4 — a Bosta delivery deleted in Bosta's dashboard, confirmed safely and closed.
 * Read-only towards Bosta (Mode B unchanged): Traced never deletes, edits, terminates or re-books anything
 * there. Bosta rows only — a generic (non-Bosta courier) leg is never suspected, confirmed or closed.
 *
 * <b>Suspected</b> ({@link #observeNotFound}): a v0 lookup answered 400 "Delivery not found" →
 * provider_not_found_at (first, kept), last_not_found_at, not_found_count + 1. A suspect stays in the
 * re-check queue ({@link #recheckSuspects}, run by the status poll after its walk): one by-tracking lookup
 * per suspect per {@link #RECHECK_EVERY_MINUTES}, BACKGROUND priority. A lookup that returns the delivery
 * clears the suspicion ({@link #clearSuspicion}) and the result is ingested normally.
 *
 * <b>Confirmed</b> ({@link #tryConfirm}) only when ALL hold, re-read under the shipment's row lock:
 * <ol>
 *   <li>two not-founds at least {@link #CONFIRM_SPACING_MINUTES} apart (count ≥ 2, last − first ≥ 30 min);</li>
 *   <li>the positive control: the SAME tenant's key had a successful v0 lookup or v2 search page in the last
 *       {@link #POSITIVE_CONTROL_MINUTES} ({@code courier_accounts.lookup_ok_at}) and no 401 / 403 in that
 *       window ({@code lookup_auth_failed_at}) — a wrong or rotated key never confirms anything;</li>
 *   <li>the shipment is still 'created'.</li>
 * </ol>
 * 404, 401 / 403, 429, 5xx and timeouts are inconclusive: never an observation, never a confirmation
 * (401 / 403 also stamp lookup_auth_failed_at, pausing confirmation for the tenant). The print path's
 * re-check ({@link #recheckFromPrint}, user-facing) may add an observation but never confirms by itself.
 * No Bosta call is made inside a DB transaction or while holding a connection.
 *
 * <b>What confirmation does</b> (provider_deleted_at + reason 'deleted_in_bosta' in every case):
 * <ul>
 *   <li>forward leg (incl. a type-30 exchange's), no piece of the order awaiting_pickup / with_courier /
 *       delivered → internal_state 'cancelled'. NO shipment_status_history row, no piece writes — so
 *       FulfillService.hasEverShippedPastCreated and WaybillResolver.movedState still let a replacement
 *       waybill be packed. Then the order's stranded unlinked deliveries get one more matcher retry;</li>
 *   <li>forward leg with pieces awaiting_pickup / with_courier → state kept, the
 *       {@code waybill_deleted_in_bosta} alert (HIGH, CRITICAL when a piece is with_courier; recovery is
 *       slice 6); with delivered pieces only → state kept, no alert;</li>
 *   <li>return leg held by an open return request → state kept (slice 7); otherwise closed as above.</li>
 * </ul>
 */
@Service
public class BostaDeletionService {

    private static final Logger log = LoggerFactory.getLogger(BostaDeletionService.class);

    public static final int CONFIRM_SPACING_MINUTES  = 30;
    public static final int POSITIVE_CONTROL_MINUTES = 30;
    public static final int RECHECK_EVERY_MINUTES    = 30;
    /** At most this many user-facing re-checks after one print (the rest wait for the queue). */
    public static final int PRINT_RECHECK_CAP        = 8;

    public static final String REASON          = "deleted_in_bosta";
    public static final String REASON_BACKFILL = "deleted_in_bosta_backfill";

    /** A Bosta leg CONFIRMED deleted in Bosta (closed or kept). The ONE definition — alias the shipment row. */
    public static String deletedSql(String alias) {
        return alias + ".provider_deleted_at IS NOT NULL";
    }

    /** A Bosta leg suspected (one 400 seen) or confirmed deleted — never handed to a courier, never printed. */
    public static String suspectedOrDeletedSql(String alias) {
        return "(" + alias + ".provider_not_found_at IS NOT NULL OR " + alias + ".provider_deleted_at IS NOT NULL)";
    }

    /** A return request still open (not finished one way or another) — holds its leg. */
    static final String OPEN_REQUEST_STATUSES = "'requested', 'approved', 'pickup_booked', 'received', 'refund_pending'";

    /** Pieces of the order (non-released allocations) that left Traced's shelf with or towards the courier. */
    private static final String ORDER_PIECES_OUT_SQL =
        "SELECT p.id, p.status::text AS status FROM pieces p " +
        "JOIN allocations a ON a.piece_id = p.id JOIN order_items oi ON oi.id = a.order_item_id " +
        "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status <> 'released' " +
        "  AND p.status IN ('awaiting_pickup', 'with_courier', 'delivered') ORDER BY p.id";

    public enum Confirmation {
        /** Not a Bosta row of this tenant, or no row. */
        NOT_BOSTA,
        ALREADY_DELETED,
        /** Fewer than two not-founds, or not 30 min apart. */
        NOT_READY,
        AUTH_PAUSED,
        NO_POSITIVE_CONTROL,
        NOT_CREATED,
        CLOSED,
        KEPT_PIECES_OUT,
        KEPT_DELIVERED,
        KEPT_RETURN_HELD
    }

    private final JdbcTemplate         jdbc;
    private final TransactionTemplate  tx;
    private final BostaGateway         gateway;
    private final BostaIngestionHelper ingestion;
    private final int                  maxSuspectsPerCycle;

    public BostaDeletionService(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaGateway gateway,
                                BostaIngestionHelper ingestion,
                                @Value("${bosta.deletion.max-suspects-per-cycle:20}") int maxSuspectsPerCycle) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.gateway = gateway;
        this.ingestion = ingestion;
        this.maxSuspectsPerCycle = maxSuspectsPerCycle;
    }

    // ── The tenant key's positive control ─────────────────────────────────────

    /** The tenant's key just got a successful v0 lookup or v2 search page. Caller inside runAs. */
    public void recordKeyOk(UUID tenantId) {
        tx.execute(s -> jdbc.update("UPDATE courier_accounts SET lookup_ok_at = now() " +
            "WHERE tenant_id = ? AND provider = 'bosta'", tenantId));
    }

    /** The tenant's key just got a 401 / 403 — confirmation pauses. Caller inside runAs. */
    public void recordKeyAuthFailure(UUID tenantId) {
        tx.execute(s -> jdbc.update("UPDATE courier_accounts SET lookup_auth_failed_at = now() " +
            "WHERE tenant_id = ? AND provider = 'bosta'", tenantId));
        log.warn("Bosta deletion: tenant {} key refused (401/403) — confirmation paused for {} min",
            tenantId, POSITIVE_CONTROL_MINUTES);
    }

    // ── Observations ──────────────────────────────────────────────────────────

    /** One 400 "Delivery not found" for this shipment. Caller inside runAs; no Bosta call here. */
    public void observeNotFound(UUID tenantId, UUID shipmentId) {
        tx.execute(s -> jdbc.update(
            "UPDATE shipments SET " +
            "  not_found_count = CASE WHEN provider_not_found_at IS NULL THEN 1 ELSE not_found_count + 1 END, " +
            "  provider_not_found_at = COALESCE(provider_not_found_at, now()), " +
            "  last_not_found_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND provider = 'bosta' AND provider_deleted_at IS NULL",
            shipmentId, tenantId));
    }

    /** Bosta returned the delivery after all: no longer suspected. Caller inside runAs. */
    public int clearSuspicion(UUID tenantId, UUID shipmentId) {
        Integer n = tx.execute(s -> jdbc.update(
            "UPDATE shipments SET provider_not_found_at = NULL, last_not_found_at = NULL, not_found_count = 0 " +
            "WHERE id = ? AND tenant_id = ? AND provider = 'bosta' AND provider_not_found_at IS NOT NULL " +
            "  AND provider_deleted_at IS NULL", shipmentId, tenantId));
        if (n != null && n > 0) log.info("Bosta deletion: tenant {} shipment {} found again — suspicion cleared", tenantId, shipmentId);
        return n == null ? 0 : n;
    }

    /** {@link #clearSuspicion} by tracking number (the status walk saw the delivery on Bosta's list). */
    public int clearSuspicionByTracking(UUID tenantId, String trackingNumber) {
        Integer n = tx.execute(s -> jdbc.update(
            "UPDATE shipments SET provider_not_found_at = NULL, last_not_found_at = NULL, not_found_count = 0 " +
            "WHERE tenant_id = ? AND tracking_number = ? AND provider = 'bosta' AND provider_not_found_at IS NOT NULL " +
            "  AND provider_deleted_at IS NULL", tenantId, trackingNumber));
        return n == null ? 0 : n;
    }

    // ── Confirmation ──────────────────────────────────────────────────────────

    /**
     * The confirmation rule (class javadoc), decided and applied in one short transaction holding the
     * shipment's row lock. No Bosta call. Caller inside runAs.
     */
    public Confirmation tryConfirm(UUID tenantId, UUID shipmentId) {
        Confirmation c = tx.execute(s -> confirmLocked(tenantId, shipmentId));
        if (c != Confirmation.NOT_READY && c != Confirmation.NOT_BOSTA && c != Confirmation.ALREADY_DELETED) {
            log.info("Bosta deletion: tenant {} shipment {} → {}", tenantId, shipmentId, c);
        }
        return c;
    }

    private Confirmation confirmLocked(UUID tenantId, UUID shipmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.order_id, s.tracking_number, s.shipment_leg::text AS leg, s.internal_state::text AS state, " +
            "       s.provider::text AS provider, s.provider_deleted_at, s.not_found_count, " +
            "       (s.last_not_found_at - s.provider_not_found_at) >= (? * INTERVAL '1 minute') AS spaced " +
            "FROM shipments s WHERE s.id = ? AND s.tenant_id = ? FOR UPDATE",
            CONFIRM_SPACING_MINUTES, shipmentId, tenantId);
        if (rows.isEmpty() || !"bosta".equals(rows.get(0).get("provider"))) return Confirmation.NOT_BOSTA;
        Map<String, Object> r = rows.get(0);
        if (r.get("provider_deleted_at") != null) return Confirmation.ALREADY_DELETED;
        if (((Number) r.get("not_found_count")).intValue() < 2 || !Boolean.TRUE.equals(r.get("spaced"))) {
            return Confirmation.NOT_READY;
        }

        List<Map<String, Object>> key = jdbc.queryForList(
            "SELECT COALESCE(lookup_ok_at > now() - (? * INTERVAL '1 minute'), false) AS ok, " +
            "       COALESCE(lookup_auth_failed_at > now() - (? * INTERVAL '1 minute'), false) AS paused " +
            "FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active'",
            POSITIVE_CONTROL_MINUTES, POSITIVE_CONTROL_MINUTES, tenantId);
        if (key.isEmpty() || !Boolean.TRUE.equals(key.get(0).get("ok"))) return Confirmation.NO_POSITIVE_CONTROL;
        if (Boolean.TRUE.equals(key.get(0).get("paused"))) return Confirmation.AUTH_PAUSED;

        if (!"created".equals(r.get("state"))) return Confirmation.NOT_CREATED;

        UUID orderId = (UUID) r.get("order_id");
        if ("return".equals(r.get("leg"))) {
            boolean held = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM return_requests h WHERE h.tenant_id = ? " +
                "  AND (h.return_shipment_id = ? OR h.bosta_tracking_number = ?) " +
                "  AND h.status::text IN (" + OPEN_REQUEST_STATUSES + "))",
                Boolean.class, tenantId, shipmentId, r.get("tracking_number")));
            if (held) {
                stamp(tenantId, shipmentId);
                return Confirmation.KEPT_RETURN_HELD;
            }
            close(tenantId, shipmentId);
            return Confirmation.CLOSED;
        }

        List<String> out = orderId == null ? List.of() : jdbc.queryForList(
            "SELECT status FROM (" + ORDER_PIECES_OUT_SQL + ") x", String.class, orderId, tenantId);
        if (out.contains("awaiting_pickup") || out.contains("with_courier")) {
            stamp(tenantId, shipmentId);
            return Confirmation.KEPT_PIECES_OUT;
        }
        if (out.contains("delivered")) {
            stamp(tenantId, shipmentId);
            return Confirmation.KEPT_DELIVERED;
        }
        close(tenantId, shipmentId);
        if (orderId != null) retryStrandedUnlinked(tenantId, orderId);
        return Confirmation.CLOSED;
    }

    /** Confirmed, state kept (pieces out / an open request holds it). */
    private void stamp(UUID tenantId, UUID shipmentId) {
        jdbc.update("UPDATE shipments SET provider_deleted_at = now(), provider_deleted_reason = ? " +
            "WHERE id = ? AND tenant_id = ?", REASON, shipmentId, tenantId);
    }

    /** Confirmed and closed: 'cancelled', no history row, no piece writes. */
    private void close(UUID tenantId, UUID shipmentId) {
        jdbc.update("UPDATE shipments SET internal_state = 'cancelled'::shipment_internal_state, " +
            "provider_deleted_at = now(), provider_deleted_reason = ? WHERE id = ? AND tenant_id = ?",
            REASON, shipmentId, tenantId);
    }

    /**
     * One more matcher retry for the order's stranded unlinked deliveries: rows whose businessReference /
     * shopifyInfo.orderId resolve to this order through {@link OrderReference} (the ONE reference rule)
     * get matcher_version NULL — the next poll cycle retries each once (CLAUDE.md: the manual force-retry
     * path; never delete the row).
     */
    void retryStrandedUnlinked(UUID tenantId, UUID orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, business_reference, raw #>> '{shopifyInfo,orderId}' AS shopify_id " +
            "FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND resolved = false " +
            "  AND (business_reference IS NOT NULL OR raw #>> '{shopifyInfo,orderId}' IS NOT NULL)",
            tenantId);
        int n = 0;
        for (Map<String, Object> u : rows) {
            List<UUID> to = OrderReference.resolve(jdbc, tenantId, (String) u.get("business_reference"),
                (String) u.get("shopify_id"));
            if (to.size() == 1 && to.get(0).equals(orderId)) {
                n += jdbc.update("UPDATE unlinked_bosta_deliveries SET matcher_version = NULL WHERE id = ? AND tenant_id = ?",
                    u.get("id"), tenantId);
            }
        }
        if (n > 0) log.info("Bosta deletion: tenant {} order {} — {} unlinked deliver(y/ies) queued for a matcher retry",
            tenantId, orderId, n);
    }

    // ── The suspect re-check queue (status poll, BACKGROUND) ──────────────────

    /**
     * One by-tracking v0 lookup per suspect not looked at for {@link #RECHECK_EVERY_MINUTES}, oldest
     * observation first, at most {@code bosta.deletion.max-suspects-per-cycle}. Caller inside runAs, no
     * transaction open; every DB touch is its own short transaction, never around a Bosta call.
     * A rate limit propagates (the poll backs the tenant off).
     *
     * @return the number of suspects looked up
     */
    public int recheckSuspects(UUID tenantId, String apiKey) {
        List<Map<String, Object>> suspects = tx.execute(s -> jdbc.queryForList(
            "SELECT id, tracking_number, provider_state FROM shipments " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND tracking_number IS NOT NULL " +
            "  AND provider_not_found_at IS NOT NULL AND provider_deleted_at IS NULL " +
            "  AND internal_state NOT IN ('delivered','returned','lost','terminated','cancelled') " +
            "  AND (last_polled_at IS NULL OR last_polled_at <= now() - (? * INTERVAL '1 minute')) " +
            "ORDER BY last_not_found_at ASC NULLS FIRST, id LIMIT ?",
            tenantId, RECHECK_EVERY_MINUTES, maxSuspectsPerCycle));
        if (suspects == null) return 0;
        int looked = 0;
        for (Map<String, Object> row : suspects) {
            UUID shipmentId = (UUID) row.get("id");
            String tn = (String) row.get("tracking_number");
            looked++;
            try {
                BostaDelivery d = gateway.fetchDelivery(apiKey, tn);          // BACKGROUND, no transaction
                if (d != null) {
                    recordKeyOk(tenantId);
                    clearSuspicion(tenantId, shipmentId);
                    ingestion.ingestFetched(tenantId, d, "bosta_poll", null);
                } // null = 404: inconclusive
            } catch (DeliveryNotFoundException e) {
                observeNotFound(tenantId, shipmentId);
                tryConfirm(tenantId, shipmentId);
            } catch (BostaAuthException e) {
                recordKeyAuthFailure(tenantId);
                touch(tenantId, shipmentId);
                break;                                                       // the key is the problem: stop
            } catch (BostaRateLimitException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("Bosta deletion: tenant {} suspect {} re-check inconclusive: {}", tenantId, tn, e.toString());
            }
            touch(tenantId, shipmentId);
        }
        return looked;
    }

    private void touch(UUID tenantId, UUID shipmentId) {
        tx.execute(s -> jdbc.update("UPDATE shipments SET last_polled_at = now() WHERE id = ? AND tenant_id = ?",
            shipmentId, tenantId));
    }

    // ── The print path's re-check (USER-FACING) ───────────────────────────────

    /**
     * mass-awb answered "Couldn't find deliveries" and isolation named these waybills: look each up right
     * away (user-facing priority, at most {@link #PRINT_RECHECK_CAP}). A 400 is an observation — never a
     * confirmation by itself; a delivery returned clears the suspicion; 401 / 403 pause confirmation; a
     * rate limit or anything else just stops / skips (the print's result is never affected). No
     * transaction may be open in the caller.
     */
    public void recheckFromPrint(UUID tenantId, String apiKey, List<String> trackingNumbers) {
        int n = 0;
        for (String tn : trackingNumbers) {
            if (n++ >= PRINT_RECHECK_CAP) break;
            UUID shipmentId = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.query(
                "SELECT id FROM shipments WHERE tenant_id = ? AND tracking_number = ? AND provider = 'bosta' " +
                "  AND provider_deleted_at IS NULL", rs -> rs.next() ? rs.getObject(1, UUID.class) : null, tenantId, tn)));
            if (shipmentId == null) continue;
            try {
                BostaDelivery d = BostaRateLimiter.userFacing(() -> gateway.fetchDelivery(apiKey, tn));
                if (d != null) TenantContext.runAs(tenantId, () -> { recordKeyOk(tenantId); clearSuspicion(tenantId, shipmentId); });
            } catch (DeliveryNotFoundException e) {
                TenantContext.runAs(tenantId, () -> observeNotFound(tenantId, shipmentId));
            } catch (BostaAuthException e) {
                TenantContext.runAs(tenantId, () -> recordKeyAuthFailure(tenantId));
                return;
            } catch (BostaRateLimitException e) {
                return;
            } catch (RuntimeException e) {
                log.warn("Bosta deletion: tenant {} print re-check of {} inconclusive: {}", tenantId, tn, e.toString());
            }
        }
    }
}
