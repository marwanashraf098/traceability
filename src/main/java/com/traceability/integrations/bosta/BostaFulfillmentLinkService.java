package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Link a Bosta delivery to an order from the order's own Shopify fulfillment (2026-10-03).
 *
 * FulfillmentTrackingCapture enqueues one job per (order, tracking number) for a 'bosta'
 * order_fulfillment_tracking row that isn't cancelled, has never been attempted and whose order
 * has no forward shipment with that number (deterministic job id → one job per pair). The job
 * fetches the delivery with the tenant's OWN Bosta key and links ONLY when:
 * <ul>
 *   <li>the type is a forward delivery ({@link ShipmentLinkService#FORWARD_LINKABLE_TYPE_CODES}:
 *       10 SEND, 20 RETURN TO ORIGIN);</li>
 *   <li>the businessReference (as sent, '#'±, external_id, both sides of a ':') resolves to THIS
 *       order, or shopifyInfo.orderId is THIS order's Shopify id — and neither points at another
 *       order;</li>
 *   <li>the order has no active forward leg, and the tracking number is not a shipment on another
 *       order.</li>
 * </ul>
 * Anything else is never linked: a reference / Shopify id pointing elsewhere, both missing, a
 * non-forward type, or the number on another order's shipment → link_status 'conflict' and the
 * fulfillment_link_problem exception. Never guesses, never moves or unlinks a shipment.
 *
 * The link itself goes through the discovery pipeline: a 'shopify_fulfillment' webhook_events
 * row carrying the order id, processed by BostaWebhookJob.process() (verify-by-fetch, pre-connect
 * filter, state mapping, shipment + status history + pieces + not-traced tagging via
 * ShipmentLinkService.linkDeliveryToOrder() and applyMappedState()) — no parallel linking code.
 *
 * Bosta 404 / "Delivery not found", 5xx or network errors → 'retry' with backoff (15 min doubling,
 * at most 2 h) for up to 24 h after the first failure, then 'gave_up' (exception). A rate limit
 * (Bosta's 429, or the shared limiter refusing a wait) never sleeps in the worker (2026-10-04): the
 * row becomes 'retry' at the retry-after — never counted toward the 24 h — and the
 * fulfillment-link-retry sweeper (every 2 minutes) runs it again. So does a link whose webhook
 * event was rescheduled on a rate limit.
 */
@Service
public class BostaFulfillmentLinkService {

    private static final Logger log = LoggerFactory.getLogger(BostaFulfillmentLinkService.class);

    public enum Verdict { WOULD_LINK, LINKED, SKIP }

    public record Result(Verdict verdict, String reason, Integer typeCode, Integer state) {
        static Result skip(String reason) { return new Result(Verdict.SKIP, reason, null, null); }
        static Result skip(String reason, BostaDelivery d) {
            return new Result(Verdict.SKIP, reason, d.typeCode(), d.stateCode());
        }
    }

    private final JdbcTemplate        jdbc;
    private final JdbcTemplate        ownerJdbc;
    private final TransactionTemplate tx;
    private final BostaGateway        bostaGateway;
    private final EncryptionService   encryptionService;
    private final ObjectMapper        mapper;
    private final BostaWebhookJob     webhookJob;
    private final JobScheduler        jobScheduler;
    private final int                 retryWindowHours;

    public BostaFulfillmentLinkService(JdbcTemplate jdbc,
                                       @FlywayDataSource DataSource ownerDs,
                                       PlatformTransactionManager txm,
                                       BostaGateway bostaGateway,
                                       EncryptionService encryptionService,
                                       ObjectMapper mapper,
                                       BostaWebhookJob webhookJob,
                                       JobScheduler jobScheduler,
                                       @Value("${bosta.fulfillment-link.retry-window-hours:24}") int retryWindowHours) {
        this.jdbc = jdbc;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.tx = new TransactionTemplate(txm);
        this.bostaGateway = bostaGateway;
        this.encryptionService = encryptionService;
        this.mapper = mapper;
        this.webhookJob = webhookJob;
        this.jobScheduler = jobScheduler;
        this.retryWindowHours = retryWindowHours;
    }

    // ---- trigger ----------------------------------------------------------------------------

    /** Deterministic JobRunr id: one link job per (tenant, order, tracking number). */
    public static UUID jobId(UUID tenantId, UUID orderId, String trackingNumber) {
        return UUID.nameUUIDFromBytes(("bosta-fulfillment-link:" + tenantId + ":" + orderId + ":" + trackingNumber)
            .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Enqueues the link job once the caller's transaction commits (now when there is none) — on a
     * virtual thread (2026-10-04, B3): the capture's DB connection goes back to the pool instead of
     * waiting while JobRunr's enqueue waits for an owner-pool connection. If the enqueue fails, the row
     * becomes 'retry' in a minute (never counted) so the retry sweeper links it.
     */
    public void enqueueAfterCommit(UUID tenantId, UUID orderId, String trackingNumber) {
        com.traceability.jobs.AfterCommit.runAsync(
            () -> jobScheduler.enqueue(jobId(tenantId, orderId, trackingNumber),
                () -> run(tenantId, orderId, trackingNumber)),
            failure -> TenantContext.runAs(tenantId, (Runnable) () ->
                markRetry(tenantId, orderId, trackingNumber, "link job enqueue failed: " + failure.getClass().getSimpleName(),
                    60, false)));
    }

    @Job(name = "Bosta link from Shopify fulfillment — %2", retries = 0)
    public void run(UUID tenantId, UUID orderId, String trackingNumber) {
        Result r = attempt(tenantId, orderId, trackingNumber, false);
        log.info("Fulfillment link tenant={} order={} tracking={}: {} {}",
            tenantId, orderId, trackingNumber, r.verdict(), r.reason() == null ? "" : r.reason());
    }

    @Recurring(id = "fulfillment-link-retry", cron = "*/2 * * * *")
    @Job(name = "Bosta fulfillment link — retry due")
    public void retryDue() {
        List<Map<String, Object>> due = ownerJdbc.queryForList(
            "SELECT tenant_id, order_id, tracking_number FROM order_fulfillment_tracking " +
            "WHERE link_status = 'retry' AND link_next_retry_at <= now() " +
            "ORDER BY link_next_retry_at LIMIT 100");
        for (Map<String, Object> row : due) {
            try {
                run((UUID) row.get("tenant_id"), (UUID) row.get("order_id"), (String) row.get("tracking_number"));
            } catch (RuntimeException e) {
                log.warn("Fulfillment link retry failed for {}: {}", row.get("tracking_number"), e.toString());
            }
        }
    }

    // ---- the one decision path (job, retry sweeper, catch-up apply and dry run) ----------------

    /**
     * Decides and (unless {@code dryRun}) links one (order, tracking number). Dry run writes
     * nothing at all — not even the tracking row's link state — but does fetch from Bosta.
     */
    public Result attempt(UUID tenantId, UUID orderId, String trackingNumber, boolean dryRun) {
        return TenantContext.runAs(tenantId, () -> attemptInTenant(tenantId, orderId, trackingNumber, dryRun));
    }

    private Result attemptInTenant(UUID tenantId, UUID orderId, String tn, boolean dryRun) {
        // 1. Preconditions — no Bosta call.
        Map<String, Object> order = tx.execute(s -> jdbc.queryForList(
            "SELECT number, external_id FROM merchant_orders WHERE id = ? AND tenant_id = ?", orderId, tenantId)
            .stream().findFirst().orElse(null));
        if (order == null) return Result.skip("order not found");

        Map<String, Object> row = tx.execute(s -> jdbc.queryForList(
            "SELECT carrier_class, fulfillment_status, link_status FROM order_fulfillment_tracking " +
            "WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?", tenantId, orderId, tn)
            .stream().findFirst().orElse(null));
        if (row != null && !"bosta".equals(row.get("carrier_class"))) return Result.skip("not a Bosta fulfillment");
        if (row != null && "cancelled".equalsIgnoreCase((String) row.get("fulfillment_status"))) {
            return Result.skip("fulfillment cancelled");
        }

        Map<String, Object> existing = tx.execute(s -> jdbc.queryForList(
            "SELECT s.order_id, o.number, s.provider_deleted_at IS NOT NULL AS deleted FROM shipments s " +
            "JOIN merchant_orders o ON o.id = s.order_id " +
            "WHERE s.tenant_id = ? AND s.tracking_number = ? AND s.provider = 'bosta'", tenantId, tn)
            .stream().findFirst().orElse(null));
        if (existing != null && Boolean.TRUE.equals(existing.get("deleted"))) {
            // Deleted-in-Bosta slice 4: the fulfillment still carries a waybill Traced confirmed deleted in Bosta.
            // Never re-linked, never a fulfillment-link alert ('skipped' is not one) — the order waits for a new one.
            String reason = "waybill deleted in Bosta — not linked";
            if (!dryRun) markStatus(tenantId, orderId, tn, "skipped", reason);
            return Result.skip(reason);
        }
        if (existing != null) {
            if (orderId.equals(existing.get("order_id"))) {
                if (!dryRun) markLinked(tenantId, orderId, tn);
                return Result.skip("already linked");
            }
            // 2026-10-05: BROEK's plugin fulfills the ORIGINAL order with its exchange's AWB, which is the
            // forward leg of the internal EXC-… replacement order. Matched to this order (or its
            // reference resolves to it) → linked via the exchange: no new shipment, no exception.
            String viaExchange = linkedViaExchange(tenantId, orderId, (UUID) existing.get("order_id"),
                (String) existing.get("number"));
            if (viaExchange != null) {
                if (dryRun) return new Result(Verdict.WOULD_LINK, viaExchange, 30, null);
                markLinked(tenantId, orderId, tn, viaExchange);
                return new Result(Verdict.LINKED, viaExchange, 30, null);
            }
            String reason = "tracking number is already a shipment on order " + existing.get("number");
            if (!dryRun) markConflict(tenantId, orderId, tn, reason);
            return Result.skip(reason);
        }
        String otherLeg = tx.execute(s -> jdbc.query(
            "SELECT tracking_number FROM shipments WHERE tenant_id = ? AND order_id = ? AND provider = 'bosta' " +
            "  AND shipment_leg = 'forward' AND internal_state NOT IN ('terminated', 'cancelled') LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null, tenantId, orderId));
        // V202: Shopify's number for this order changed (a tracking edit) — the live leg may be replaced instead.
        Supersede supersede = null;
        if (otherLeg != null) {
            // No active Bosta account (e.g. a simulated-courier tenant): never a tracking edit — as before V202.
            supersede = hasActiveAccount(tenantId) ? tx.execute(s -> supersedeCandidate(tenantId, orderId, tn)) : null;
            if (supersede == null) {   // a second parcel, or not a tracking-edit shape: exactly as before V202
                String reason = "order already has an active forward leg (" + otherLeg + ")";
                if (!dryRun) markStatus(tenantId, orderId, tn, "skipped", reason);
                return Result.skip(reason);
            }
            final Supersede sup = supersede;
            String refused = tx.execute(s -> preCheck(tenantId, orderId, tn, sup, dryRun));
            if (refused != null) {
                String reason = "tracking change not applied (" + refused + ") — order keeps " + sup.oldTn();
                if (!dryRun) markStatus(tenantId, orderId, tn, "skipped", reason);
                return Result.skip(reason);
            }
        }
        // V169 Bosta wins over a generic (non-Bosta courier) leg only while it is still cancellable — the
        // link pipeline cancels it then (GenericShipments.yieldToBosta). Past 'created': never guess.
        String blockingCarrier = tx.execute(s -> jdbc.query(
            "SELECT coalesce(s.carrier_name, 'another carrier') FROM shipments s " +
            "WHERE s.tenant_id = ? AND s.order_id = ? AND " +
            com.traceability.courier.GenericShipments.BLOCKING_GENERIC_SQL + " LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null, tenantId, orderId));
        if (blockingCarrier != null) {
            String reason = "order is already shipped with " + blockingCarrier + " — Traced won't replace that shipment";
            if (!dryRun) markConflict(tenantId, orderId, tn, reason);
            return Result.skip(reason);
        }

        String encryptedKey = tx.execute(s -> jdbc.query(
            "SELECT api_key_encrypted FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null, tenantId));
        if (encryptedKey == null) return Result.skip("no active Bosta account");
        String apiKey = encryptionService.decrypt(encryptedKey);   // this tenant's own key, only

        // V202 D2 re-checks run at the lowest Bosta priority: only when the tenant's bucket has a token free right now
        // (user-facing and normal poll calls go first); otherwise in 5 minutes, no call, not counted.
        if (supersede != null && !supersede.anchored() && hasD2Refusal(tenantId, supersede.legId(), tn)
                && !d2Headroom.test(apiKey)) {
            if (!dryRun) markRetry(tenantId, orderId, tn, "waiting for Bosta headroom (re-check of the old waybill)", 300, false);
            return Result.skip("waiting for Bosta headroom");
        }

        // 2. Fetch.
        BostaDelivery delivery;
        try {
            delivery = bostaGateway.fetchDelivery(apiKey, tn);
        } catch (DeliveryNotFoundException e) {
            delivery = null;
        } catch (BostaRateLimitException e) {
            if (!dryRun) markRetry(tenantId, orderId, tn, "rate limited", e.getRetryAfterSeconds(), false);
            return Result.skip("rate limited by Bosta");
        } catch (RuntimeException e) {
            if (!dryRun) markRetry(tenantId, orderId, tn, "Bosta error: " + e.getClass().getSimpleName(), 0, true);
            return Result.skip("Bosta error: " + e.getClass().getSimpleName());
        }
        if (delivery == null) {
            if (!dryRun) markRetry(tenantId, orderId, tn, "not found in Bosta", 0, true);
            return Result.skip("not found in Bosta");
        }

        // 3. Is it this order's forward delivery?
        if (!ShipmentLinkService.FORWARD_LINKABLE_TYPE_CODES.contains(delivery.typeCode())) {
            String reason = "not a forward delivery (Bosta type " + delivery.typeCode() + ")";
            if (!dryRun) markConflict(tenantId, orderId, tn, reason);
            return Result.skip(reason, delivery);
        }
        String identityProblem = identityProblem(tenantId, orderId, (String) order.get("external_id"), delivery);
        if (identityProblem != null) {
            if (!dryRun) markConflict(tenantId, orderId, tn, identityProblem);
            return Result.skip(identityProblem, delivery);
        }

        // V202 D2: a leg no fulfillment anchors follows only once its OLD delivery is confirmed ended in Bosta.
        if (supersede != null && !supersede.anchored()) {
            String waiting = oldDeliveryNotEnded(tenantId, orderId, tn, supersede, apiKey, dryRun);
            if (waiting != null) return Result.skip(waiting, delivery);
        }

        if (dryRun) return new Result(Verdict.WOULD_LINK, supersede != null ? "replaces " + supersede.oldTn() : null,
            delivery.typeCode(), delivery.stateCode());

        // 4. Link through the discovery pipeline.
        Long eventId = insertEvent(tenantId, orderId, tn, delivery, supersede);
        if (eventId != null) {
            try {
                webhookJob.process(eventId, tenantId);
            } catch (ShipmentLinkService.SupersedeRefusedException e) {
                // Re-checked under the lock inside the link: frozen / pickup session since the pre-check. Rolled back.
                TenantContext.set(tenantId);
                final Supersede sup = supersede;
                tx.execute(s -> {
                    jdbc.update("UPDATE webhook_events SET status = 'failed', error = ? WHERE id = ?", e.getMessage(), eventId);
                    if (sup != null && (com.traceability.courier.TrackingChanges.REASON_FROZEN.equals(e.reason())
                            || com.traceability.courier.TrackingChanges.REASON_PICKUP_SESSION.equals(e.reason()))) {
                        com.traceability.courier.TrackingChanges.record(jdbc, tenantId, orderId, sup.legId(), null, sup.kind(),
                            sup.oldTn(), tn, sup.oldFid(), sup.newFid(), null, "refused", e.reason(), List.of());
                    }
                    return null;
                });
                markStatus(tenantId, orderId, tn, "skipped", e.getMessage());
                return Result.skip(e.getMessage(), delivery);
            } catch (RuntimeException e) {
                TenantContext.set(tenantId);
                markRetry(tenantId, orderId, tn, "link pipeline error: " + e.getClass().getSimpleName(), 0, true);
                return Result.skip("link pipeline error: " + e.getClass().getSimpleName(), delivery);
            } finally {
                // Hotfix 2026-10-03: process() runs in its own TenantContext.runAs, whose finally
                // CLEARS the context instead of restoring ours. Without this, every query below ran
                // with no tenant: under app_user RLS the shipment just committed was invisible and
                // the webhook_events read threw EmptyResultDataAccessException (prod, 16:12).
                TenantContext.set(tenantId);
            }
        }
        boolean linked = Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE tenant_id = ? AND order_id = ? AND tracking_number = ? " +
            "  AND provider = 'bosta')",
            Boolean.class, tenantId, orderId, tn)));
        if (linked) {
            markLinked(tenantId, orderId, tn);
            return new Result(Verdict.LINKED, null, delivery.typeCode(), delivery.stateCode());
        }
        if (eventId != null) {
            // The link event's verify-by-fetch hit a rate limit: the event is pending, rescheduled
            // (BostaWebhookJob). Come back after it — a rate limit, not a failure, never counted.
            Long waitSeconds = tx.execute(s -> jdbc.query(
                "SELECT GREATEST(1, ceil(extract(epoch FROM rate_limited_until - now())))::bigint FROM webhook_events " +
                "WHERE id = ? AND status = 'pending' AND rate_limited_until IS NOT NULL",
                rs -> rs.next() ? rs.getLong(1) : null, eventId));
            if (waitSeconds != null) {
                markRetry(tenantId, orderId, tn, "rate limited (link event rescheduled)", waitSeconds + 30, false);
                return Result.skip("rate limited by Bosta", delivery);
            }
        }
        String note = eventId == null ? "an identical link event is already in flight"
            : tx.execute(s -> jdbc.queryForObject("SELECT coalesce(error, status::text) FROM webhook_events WHERE id = ?",
                String.class, eventId));
        markRetry(tenantId, orderId, tn, "not linked yet: " + note, 0, true);
        return Result.skip("not linked yet: " + note, delivery);
    }

    /**
     * "linked via exchange EXC-…" when {@code shipmentOrderId} is an internal exchange order whose
     * dashboard exchange is matched to {@code orderId}, or — not matched yet — whose Bosta reference
     * resolves to exactly {@code orderId} (OrderReference, the shared rule); otherwise null.
     */
    private String linkedViaExchange(UUID tenantId, UUID orderId, UUID shipmentOrderId, String shipmentOrderNumber) {
        Map<String, Object> ex = tx.execute(s -> jdbc.queryForList(
            "SELECT e.matched_order_id, e.raw->>'businessReference' AS ref, e.raw->'shopifyInfo'->>'orderId' AS shopify_id " +
            "FROM exchanges e JOIN merchant_orders o ON o.id = e.outbound_order_id AND o.tenant_id = e.tenant_id " +
            "WHERE e.tenant_id = ? AND e.outbound_order_id = ? AND o.external_id LIKE 'internal:exchange:%'",
            tenantId, shipmentOrderId).stream().findFirst().orElse(null));
        if (ex == null) return null;
        boolean ours = orderId.equals(ex.get("matched_order_id"))
            || (ex.get("matched_order_id") == null && List.of(orderId).equals(tx.execute(s ->
                OrderReference.resolve(jdbc, tenantId, (String) ex.get("ref"), (String) ex.get("shopify_id")))));
        return ours ? "linked via exchange " + shipmentOrderNumber : null;
    }

    /**
     * Null when the delivery's reference / Shopify id identify THIS order and nothing else;
     * otherwise the reason it must not be linked.
     */
    private String identityProblem(UUID tenantId, UUID orderId, String orderExternalId, BostaDelivery d) {
        String ref = d.businessReference();
        Set<String> numbers = new LinkedHashSet<>();
        Set<String> externalIds = new LinkedHashSet<>();
        for (String c : PreConnectDeliveryFilter.referenceCandidates(ref)) {
            String bare = c.startsWith("#") ? c.substring(1) : c;
            numbers.add(c);
            numbers.add(bare);
            numbers.add("#" + bare);
            externalIds.add(c);
        }
        Set<UUID> byRef = numbers.isEmpty() ? Set.of() : ordersMatching(tenantId, numbers, externalIds);

        String shopifyId = d.shopifyOrderId();
        Set<UUID> byShopify = shopifyId == null ? Set.of()
            : ordersMatching(tenantId, Set.of(), Set.of("gid://shopify/Order/" + shopifyId.trim()));

        Set<UUID> others = new LinkedHashSet<>(byRef);
        others.addAll(byShopify);
        others.remove(orderId);
        if (!others.isEmpty()) {
            String number = tx.execute(s -> jdbc.queryForObject(
                "SELECT number FROM merchant_orders WHERE id = ?", String.class, others.iterator().next()));
            return "Bosta's reference / Shopify id point at order " + number;
        }
        if (byRef.contains(orderId) || byShopify.contains(orderId)) return null;
        if ((ref == null || ref.isBlank()) && shopifyId == null) {
            return "Bosta delivery has no reference and no Shopify order id";
        }
        return "Bosta's reference '" + ref + "' / Shopify id " + shopifyId + " don't match this order";
    }

    private Set<UUID> ordersMatching(UUID tenantId, Set<String> numbers, Set<String> externalIds) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder where = new StringBuilder();
        if (!numbers.isEmpty()) {
            where.append("number IN (").append(String.join(",", java.util.Collections.nCopies(numbers.size(), "?"))).append(")");
            args.addAll(numbers);
        }
        if (!externalIds.isEmpty()) {
            if (where.length() > 0) where.append(" OR ");
            where.append("external_id IN (").append(String.join(",", java.util.Collections.nCopies(externalIds.size(), "?"))).append(")");
            args.addAll(externalIds);
        }
        List<UUID> ids = tx.execute(s -> jdbc.queryForList(
            "SELECT id FROM merchant_orders WHERE tenant_id = ? AND (" + where + ")", UUID.class, args.toArray()));
        return new LinkedHashSet<>(ids);
    }

    /**
     * The 'shopify_fulfillment' event for BostaWebhookJob: same payload shape as a poll ingest,
     * plus the order id. Its updatedAt carries the order id too, so its idem key never collides
     * with a discovery / webhook event for the same state (which may have been recorded unlinked).
     * ON CONFLICT → null: an identical event exists (a concurrent or earlier attempt).
     */
    private Long insertEvent(UUID tenantId, UUID orderId, String tn, BostaDelivery d, Supersede supersede) {
        String updatedAt = (d.raw() != null ? d.raw().path("updatedAt").asText("backfill-epoch") : "backfill-epoch")
            + "|fulfillment:" + orderId;
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", d.trackingNumber());
        payload.put("state", d.stateCode());
        payload.put("type", d.type() != null && !d.type().isBlank() ? d.type() : "SEND");
        payload.put("updatedAt", updatedAt);
        payload.put("orderId", orderId.toString());
        if (supersede != null) {   // V202: read only from this source by BostaWebhookJob (like the order hint)
            payload.put("supersedes", supersede.legId().toString());
            payload.put("supersedeKind", supersede.kind());
        }
        String idemKey = BostaWebhookJob.sha256(d.trackingNumber() + ":" + d.stateCode() + ":" + updatedAt);
        return tx.execute(s -> jdbc.query(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, external_event_id) " +
            "VALUES (?::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now(), ?) " +
            "ON CONFLICT (source, external_event_id) WHERE external_event_id IS NOT NULL DO NOTHING " +
            "RETURNING id",
            rs -> rs.next() ? rs.getLong(1) : null,
            BostaWebhookJob.FULFILLMENT_SOURCE, tenantId, payload.toString(), idemKey));
    }

    // ---- V202: Shopify tracking edits on a Bosta leg ---------------------------------------------

    /** The order's live Bosta leg Shopify replaced, and how. anchored = a fulfillment carried the old number. */
    record Supersede(UUID legId, String oldTn, String kind, String oldFid, String newFid, boolean anchored, boolean ambiguous) {}

    /**
     * Is {@code tn} a tracking edit of the order's live Bosta forward leg L (old number N)? Read from the stored
     * tracking rows (the capture keeps them current and stale-safe). Null = not that shape → the pre-V202 'skipped'.
     * <ul>
     *   <li>N's row removed from the same fulfillment that now carries tn → <b>edit</b> (ambiguous unless that
     *       fulfillment has exactly one live Bosta number);</li>
     *   <li>N's fulfillment cancelled, tn on another one → <b>cancel_refulfill</b> (ambiguous unless tn is the order's
     *       only live Bosta number);</li>
     *   <li>no row for N at all (L linked by discovery / a pack scan) and tn the order's only live Bosta number →
     *       <b>edit</b>, unanchored (D2: switched only once N's delivery is confirmed ended in Bosta);</li>
     *   <li>N still live on Shopify (a second parcel — the prod 'skipped' rows) or anything else → null.</li>
     * </ul>
     */
    private Supersede supersedeCandidate(UUID tenantId, UUID orderId, String tn) {
        Map<String, Object> leg = BostaSupersede.liveLeg(jdbc, tenantId, orderId);
        if (leg == null) return null;
        String oldTn = (String) leg.get("tracking_number");
        Map<String, Object> nw = trackingRow(tenantId, orderId, tn);
        if (nw == null || nw.get("removed_at") != null || "cancelled".equalsIgnoreCase((String) nw.get("fulfillment_status"))) return null;
        String newFid = (String) nw.get("shopify_fulfillment_id");
        Integer liveBosta = jdbc.queryForObject(
            "SELECT count(*) FROM order_fulfillment_tracking WHERE tenant_id = ? AND order_id = ? AND carrier_class = 'bosta' " +
            "  AND removed_at IS NULL AND lower(coalesce(fulfillment_status, '')) <> 'cancelled'", Integer.class, tenantId, orderId);
        Map<String, Object> old = trackingRow(tenantId, orderId, oldTn);
        UUID legId = (UUID) leg.get("id");
        if (old == null) {
            if (liveBosta == null || liveBosta != 1) return null;   // several Bosta parcels: never guess
            return new Supersede(legId, oldTn, com.traceability.courier.TrackingChanges.KIND_EDIT, null, newFid, false, false);
        }
        String oldFid = (String) old.get("shopify_fulfillment_id");
        boolean removed = old.get("removed_at") != null;
        boolean cancelled = "cancelled".equalsIgnoreCase((String) old.get("fulfillment_status"));
        if (removed && java.util.Objects.equals(oldFid, newFid)) {
            Integer inF = jdbc.queryForObject(
                "SELECT count(*) FROM order_fulfillment_tracking WHERE tenant_id = ? AND order_id = ? AND carrier_class = 'bosta' " +
                "  AND shopify_fulfillment_id = ? AND removed_at IS NULL", Integer.class, tenantId, orderId, newFid);
            return new Supersede(legId, oldTn, com.traceability.courier.TrackingChanges.KIND_EDIT, oldFid, newFid, true,
                inF == null || inF != 1);
        }
        if (cancelled && !java.util.Objects.equals(oldFid, newFid)) {
            return new Supersede(legId, oldTn, com.traceability.courier.TrackingChanges.KIND_CANCEL_REFULFILL, oldFid, newFid,
                true, liveBosta == null || liveBosta != 1);
        }
        return null;
    }

    private Map<String, Object> trackingRow(UUID tenantId, UUID orderId, String tn) {
        return jdbc.queryForList(
            "SELECT shopify_fulfillment_id, removed_at, fulfillment_status FROM order_fulfillment_tracking " +
            "WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?", tenantId, orderId, tn)
            .stream().findFirst().orElse(null);
    }

    /**
     * Before any Bosta call, under the order row lock (courier.TrackingChanges): ambiguous → refused; frozen → refused
     * (HIGH); open pickup session → refused (HIGH). Recorded once (not on a dry run). Null = go on.
     */
    private String preCheck(UUID tenantId, UUID orderId, String tn, Supersede sup, boolean dryRun) {
        String reason = null;
        if (sup.ambiguous()) reason = com.traceability.courier.TrackingChanges.REASON_AMBIGUOUS;
        else if (com.traceability.courier.TrackingChanges.lockAndCheckFrozen(jdbc, tenantId, orderId)) {
            reason = com.traceability.courier.TrackingChanges.REASON_FROZEN;
        } else if (com.traceability.courier.TrackingChanges.inOpenPickupSession(jdbc, tenantId, sup.legId())) {
            reason = com.traceability.courier.TrackingChanges.REASON_PICKUP_SESSION;
        }
        if (reason != null && !dryRun) {
            com.traceability.courier.TrackingChanges.record(jdbc, tenantId, orderId, sup.legId(), null, sup.kind(), sup.oldTn(),
                tn, sup.oldFid(), sup.newFid(), null, "refused", reason, List.of());
        }
        return reason;
    }

    /**
     * D2 — null when the OLD delivery is confirmed ended in Bosta: deletion confirmed (BostaDeletionService's rule,
     * provider_deleted_at) or Bosta shows it 48 Terminated / 49 Canceled / 104 Archived (read-only fetch with the
     * tenant's own key). Otherwise no switch: refused 'old_delivery_live' (MEDIUM "Shopify shows a different
     * waybill", clears on its own once the leg changes or ends, or Shopify shows the old number again) and the row
     * re-checks every 30 min for 2 h, then every 6 h, and gives up after 48 h (the alert stays) — re-checks at the
     * lowest Bosta priority.
     */
    private String oldDeliveryNotEnded(UUID tenantId, UUID orderId, String tn, Supersede sup, String apiKey, boolean dryRun) {
        Boolean deleted = tx.execute(s -> jdbc.queryForObject(
            "SELECT provider_deleted_at IS NOT NULL FROM shipments WHERE id = ?", Boolean.class, sup.legId()));
        if (Boolean.TRUE.equals(deleted)) return null;
        Integer oldState = null;
        try {
            BostaDelivery old = bostaGateway.fetchDelivery(apiKey, sup.oldTn());
            if (old != null) oldState = old.stateCode();
        } catch (DeliveryNotFoundException e) {
            oldState = null;   // not found is not a confirmation (BostaDeletionService needs two, with a positive control)
        } catch (BostaRateLimitException e) {
            if (!dryRun) markRetry(tenantId, orderId, tn, "rate limited", e.getRetryAfterSeconds(), false);
            return "rate limited by Bosta";
        }
        if (oldState != null && ENDED_CODES.contains(oldState)) return null;
        String reason = "Shopify shows a different waybill — the old one (" + sup.oldTn() + ") isn't ended in Bosta";
        if (dryRun) return reason;
        tx.execute(s -> {
            com.traceability.courier.TrackingChanges.record(jdbc, tenantId, orderId, sup.legId(), null, sup.kind(), sup.oldTn(),
                tn, sup.oldFid(), sup.newFid(), null, "refused", "old_delivery_live", List.of());
            return null;
        });
        // Cadence (approved 2026-11-03): every 30 min for the first 2 h after the first refusal, then every 6 h; after
        // 48 h the row gives up — the MEDIUM alert stays until resolved or the old delivery ends.
        Long ageMinutes = tx.execute(s -> jdbc.queryForObject(
            "SELECT floor(extract(epoch FROM now() - min(created_at)) / 60)::bigint FROM shipment_tracking_changes " +
            "WHERE shipment_id = ? AND reason = 'old_delivery_live' AND new_tracking_number = ?",
            Long.class, sup.legId(), tn));
        long age = ageMinutes == null ? 0 : ageMinutes;
        if (age >= D2_GIVE_UP_MINUTES) markStatus(tenantId, orderId, tn, "gave_up", reason);
        else markRetry(tenantId, orderId, tn, reason, age < D2_FAST_MINUTES ? 1800 : 21600, false);
        return reason;
    }

    /** D2 re-check cadence: every 30 min until this age, then every 6 h, gave_up at {@link #D2_GIVE_UP_MINUTES}. */
    static final long D2_FAST_MINUTES = 120;
    static final long D2_GIVE_UP_MINUTES = 48 * 60;

    /** A D2 refusal was already recorded for this (leg, new number): this attempt is a re-check. */
    private boolean hasD2Refusal(UUID tenantId, UUID legId, String tn) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipment_tracking_changes WHERE tenant_id = ? AND shipment_id = ? " +
            "  AND reason = 'old_delivery_live' AND new_tracking_number = ?)", Boolean.class, tenantId, legId, tn)));
    }

    /** "A token is free right now" for the tenant's key (BostaRateLimiter.hasHeadroom with no wait); settable for tests. */
    private java.util.function.Predicate<String> d2Headroom = key -> true;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRateLimiter(BostaRateLimiter limiter) {
        if (limiter != null) this.d2Headroom = key -> limiter.hasHeadroom(key, 0);
    }

    /** Tests only: replace the headroom check. */
    public void setD2Headroom(java.util.function.Predicate<String> headroom) { this.d2Headroom = headroom; }

    /** Bosta 48 Terminated, 49 Canceled, 104 Archived — the old delivery ended before anyone had it. */
    static final java.util.Set<Integer> ENDED_CODES = java.util.Set.of(48, 49, 104);

    private boolean hasActiveAccount(UUID tenantId) {
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active')",
            Boolean.class, tenantId)));
    }

    // ---- link state on the tracking row -------------------------------------------------------

    private void markLinked(UUID tenantId, UUID orderId, String tn) {
        markLinked(tenantId, orderId, tn, null);
    }

    private void markLinked(UUID tenantId, UUID orderId, String tn, String reason) {
        tx.execute(s -> jdbc.update(
            "UPDATE order_fulfillment_tracking SET link_status = 'linked', link_reason = ?, " +
            "    linked_at = coalesce(linked_at, now()), link_checked_at = now(), link_next_retry_at = NULL " +
            "WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?", reason, tenantId, orderId, tn));
    }

    private void markConflict(UUID tenantId, UUID orderId, String tn, String reason) {
        markStatus(tenantId, orderId, tn, "conflict", reason);
    }

    private void markStatus(UUID tenantId, UUID orderId, String tn, String status, String reason) {
        tx.execute(s -> jdbc.update(
            "UPDATE order_fulfillment_tracking SET link_status = ?, link_reason = ?, link_checked_at = now(), " +
            "    link_next_retry_at = NULL " +
            "WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?", status, reason, tenantId, orderId, tn));
    }

    /**
     * 'retry' with backoff; {@code countsTowardWindow} failures give up once the retry window has
     * passed since the first of them. A 429 never counts and waits Bosta's retry-after.
     */
    private void markRetry(UUID tenantId, UUID orderId, String tn, String reason, long retryAfterSeconds,
                           boolean countsTowardWindow) {
        tx.execute(s -> jdbc.update(
            "UPDATE order_fulfillment_tracking SET " +
            "    link_first_failed_at = CASE WHEN ? THEN coalesce(link_first_failed_at, now()) ELSE link_first_failed_at END, " +
            "    link_attempts = link_attempts + CASE WHEN ? THEN 1 ELSE 0 END, " +
            "    link_status = CASE WHEN ? AND coalesce(link_first_failed_at, now()) <= now() - (? * INTERVAL '1 hour') " +
            "                       THEN 'gave_up' ELSE 'retry' END, " +
            "    link_reason = ?, link_checked_at = now(), " +
            "    link_next_retry_at = now() + CASE WHEN ? > 0 THEN (? * INTERVAL '1 second') " +
            "                         ELSE LEAST(INTERVAL '2 hours', INTERVAL '15 minutes' * power(2, LEAST(link_attempts, 4))) END " +
            "WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?",
            countsTowardWindow, countsTowardWindow, countsTowardWindow, retryWindowHours,
            reason, retryAfterSeconds, retryAfterSeconds, tenantId, orderId, tn));
    }
}
