package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.courier.TrackingChanges;
import com.traceability.inventory.NotTracedTagger;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Re-link an order's stranded Bosta deliveries once its old forward leg has ended (2026-11-05, BROEK BRK-47264-EG:
 * the merchant deleted the delivery in Bosta, booked a new one for the same order, and the new one stayed unlinked
 * because the dead leg still held the order's one live forward slot).
 *
 * <p>{@link #relinkStrandedUnlinked} — the ONE re-match path. Called after commit from: every confirmed forward-leg
 * deletion branch (BostaDeletionService), a Bosta 48 / 49 / 104 on a forward leg (BostaWebhookJob, next to
 * BostaSupersede.requeueAfterLegEnded), and ActiveForwardLegCheckJob. For each of the order's unresolved
 * SEND / RETURN TO ORIGIN rows in unlinked_bosta_deliveries, ONCE per call, from the row's STORED raw — no Bosta
 * call and no re-ingest, so the webhook_events step-4 dedup (an 'unlinked:' event at the current matcher version)
 * never applies. Every attempt records its outcome on the row (relink_outcome / relink_at, V209).
 *
 * <p>Rules, in this order, all re-read under the order's row lock in one transaction:
 * <ol>
 *   <li>identity — the row's businessReference / shopifyInfo.orderId resolves (OrderReference) to exactly this
 *       order; two orders → refused 'identity' (never guess);</li>
 *   <li>the order is not closed (delivered / returned / lost / cancelled) → else refused 'order_closed';</li>
 *   <li>not frozen (OrderLineFreeze — the order's goods left custody) → else 'frozen';</li>
 *   <li>no forward leg of the order in an open pickup session → else 'pickup_session';</li>
 *   <li>no piece of the order packed (a packed allocation — the parcel carries the old label) → else 'packed';</li>
 *   <li>no live Bosta forward leg any more → else 'active_forward_leg' (the old one hasn't ended);</li>
 *   <li>then ShipmentLinkService.tryMatchDelivery — the normal matcher (strong reference, the V169 generic-leg
 *       rule, the V104 one-live-forward-leg rule); linked → the row is resolved there; any other result → refused
 *       with that reason.</li>
 * </ol>
 * Runs with no Bosta call, so it never needs a rate-limit slot.
 */
@Service
public class BostaRelink {

    private static final Logger log = LoggerFactory.getLogger(BostaRelink.class);

    public static final String LINKED = "linked";
    public static final String REFUSED = "refused:";

    static final String CLOSED_ORDER_STATUSES = "'delivered', 'returned', 'lost', 'cancelled'";

    private final JdbcTemplate        jdbc;
    private final TransactionTemplate tx;
    private final ShipmentLinkService links;
    private final BostaStateMapper    stateMapper;
    private final ObjectMapper        mapper;
    private final NotTracedTagger     notTraced;

    public BostaRelink(JdbcTemplate jdbc, PlatformTransactionManager txm, ShipmentLinkService links,
                       BostaStateMapper stateMapper, ObjectMapper mapper, NotTracedTagger notTraced) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.links = links;
        this.stateMapper = stateMapper;
        this.mapper = mapper;
        this.notTraced = notTraced;
    }

    /** One row's outcome. */
    public record Outcome(long unlinkedId, String trackingNumber, String outcome) {}

    /**
     * {@link #relinkStrandedUnlinked} once the caller's transaction commits (a virtual thread, AfterCommit); right
     * away when there is none. Never throws.
     */
    public void relinkAfterCommit(UUID tenantId, UUID orderId, String trigger) {
        if (orderId == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            com.traceability.jobs.AfterCommit.runAsync(() -> relinkStrandedUnlinked(tenantId, orderId, trigger),
                t -> log.warn("Bosta relink: tenant {} order {} ({}) failed after commit: {}", tenantId, orderId, trigger, t.toString()));
        } else {
            try {
                relinkStrandedUnlinked(tenantId, orderId, trigger);
            } catch (RuntimeException e) {
                log.warn("Bosta relink: tenant {} order {} ({}) failed: {}", tenantId, orderId, trigger, e.toString());
            }
        }
    }

    /** The re-match (class javadoc). Must not run inside a transaction. Returns each row's outcome. */
    public List<Outcome> relinkStrandedUnlinked(UUID tenantId, UUID orderId, String trigger) {
        return TenantContext.runAs(tenantId, () -> {
            List<Map<String, Object>> rows = tx.execute(s -> jdbc.queryForList(
                "SELECT u.id, u.tracking_number, u.business_reference, u.bosta_state_code, u.bosta_order_type, " +
                "       u.raw::text AS raw, u.raw #>> '{shopifyInfo,orderId}' AS shopify_id " +
                "FROM unlinked_bosta_deliveries u WHERE u.tenant_id = ? AND u.resolved = false AND u.raw IS NOT NULL " +
                "  AND " + ShipmentLinkService.forwardLinkableTypeSql("u") + " " +
                "  AND (u.business_reference IS NOT NULL OR u.raw #>> '{shopifyInfo,orderId}' IS NOT NULL) " +
                "ORDER BY u.first_seen_at, u.id", tenantId));
            List<Outcome> out = new ArrayList<>();
            if (rows == null) return out;
            for (Map<String, Object> u : rows) {
                long id = ((Number) u.get("id")).longValue();
                String tn = (String) u.get("tracking_number");
                List<UUID> to = tx.execute(s -> OrderReference.resolve(jdbc, tenantId,
                    (String) u.get("business_reference"), (String) u.get("shopify_id")));
                if (to == null || !to.contains(orderId)) continue;             // another order's delivery
                String outcome;
                try {
                    outcome = to.size() != 1 ? REFUSED + "identity" : tx.execute(s -> attempt(tenantId, orderId, u));
                } catch (RuntimeException e) {
                    log.warn("Bosta relink: tenant {} order {} tracking {} failed: {}", tenantId, orderId, tn, e.toString());
                    outcome = REFUSED + "error";
                }
                final String fOutcome = outcome;
                if (!LINKED.equals(outcome)) {
                    tx.execute(s -> jdbc.update("UPDATE unlinked_bosta_deliveries SET relink_outcome = ?, relink_at = now() " +
                        "WHERE id = ? AND tenant_id = ?", fOutcome, id, tenantId));
                }
                log.info("Bosta relink: tenant {} order {} tracking {} ({}) → {}", tenantId, orderId, tn, trigger, outcome);
                out.add(new Outcome(id, tn, outcome));
            }
            return out;
        });
    }

    /** One row, in the caller's transaction (rules 2–7). */
    private String attempt(UUID tenantId, UUID orderId, Map<String, Object> u) {
        boolean frozen = TrackingChanges.lockAndCheckFrozen(jdbc, tenantId, orderId);
        List<String> status = jdbc.queryForList("SELECT status::text FROM warehouse_orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        if (status.isEmpty()) return REFUSED + "identity";
        if (CLOSED_ORDER_STATUSES.contains("'" + status.get(0) + "'")) return REFUSED + "order_closed";
        if (frozen) return REFUSED + TrackingChanges.REASON_FROZEN;
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pickup_shipments ps JOIN pickups pu ON pu.id = ps.pickup_id AND pu.tenant_id = ps.tenant_id " +
                "  JOIN shipments s ON s.id = ps.shipment_id AND s.tenant_id = ps.tenant_id " +
                "  WHERE s.order_id = ? AND s.tenant_id = ? AND s.shipment_leg = 'forward' AND pu.session_status = 'open')",
                Boolean.class, orderId, tenantId))) {
            return REFUSED + TrackingChanges.REASON_PICKUP_SESSION;
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
                "  WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status = 'packed')",
                Boolean.class, orderId, tenantId))) {
            return REFUSED + "packed";
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM shipments WHERE order_id = ? AND tenant_id = ? AND provider = 'bosta' " +
                "  AND shipment_leg = 'forward' AND internal_state NOT IN ('terminated', 'cancelled'))",
                Boolean.class, orderId, tenantId))) {
            return REFUSED + "active_forward_leg";
        }
        String tn = (String) u.get("tracking_number");
        JsonNode raw;
        try {
            raw = mapper.readTree((String) u.get("raw"));
        } catch (Exception e) {
            return REFUSED + "unreadable_raw";
        }
        BostaDelivery delivery = BostaDelivery.fromRaw(tn, raw);
        BostaStateMapper.MappedState mapped = stateMapper.map(delivery.stateCode(), delivery.type());
        if (mapped.unknownCode()) return REFUSED + "unknown_state";
        ShipmentLinkService.LinkResult r = links.tryMatchDelivery(tenantId, tn, delivery, mapped);
        if (r.orderId() == null) return REFUSED + r.unlinkedReason();
        if (!orderId.equals(r.orderId())) {
            // Can't happen (identity above = exactly this order); never commit a link to another order.
            throw new IllegalStateException("relink matched order " + r.orderId() + ", expected " + orderId);
        }
        jdbc.update("UPDATE unlinked_bosta_deliveries SET relink_outcome = ?, relink_at = now() " +
            "WHERE id = ? AND tenant_id = ?", LINKED, ((Number) u.get("id")).longValue(), tenantId);
        notTraced.maybeTagNotTraced(orderId, tenantId);
        return LINKED;
    }
}
