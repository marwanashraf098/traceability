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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
 * at most 2 h) for up to 24 h after the first failure, then 'gave_up' (exception). A 429 backs off
 * by Bosta's retry-after and never counts toward the 24 h. The fulfillment-link-retry sweeper runs
 * every 10 minutes.
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

    /** Enqueues the link job once the caller's transaction commits (immediately when there is none). */
    public void enqueueAfterCommit(UUID tenantId, UUID orderId, String trackingNumber) {
        Runnable enqueue = () -> jobScheduler.enqueue(jobId(tenantId, orderId, trackingNumber),
            () -> run(tenantId, orderId, trackingNumber));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { enqueue.run(); }
            });
        } else {
            enqueue.run();
        }
    }

    @Job(name = "Bosta link from Shopify fulfillment — %2", retries = 0)
    public void run(UUID tenantId, UUID orderId, String trackingNumber) {
        Result r = attempt(tenantId, orderId, trackingNumber, false);
        log.info("Fulfillment link tenant={} order={} tracking={}: {} {}",
            tenantId, orderId, trackingNumber, r.verdict(), r.reason() == null ? "" : r.reason());
    }

    @Recurring(id = "fulfillment-link-retry", cron = "*/10 * * * *")
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
            "SELECT number, external_id FROM orders WHERE id = ? AND tenant_id = ?", orderId, tenantId)
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
            "SELECT s.order_id, o.number FROM shipments s JOIN orders o ON o.id = s.order_id " +
            "WHERE s.tenant_id = ? AND s.tracking_number = ?", tenantId, tn)
            .stream().findFirst().orElse(null));
        if (existing != null) {
            if (orderId.equals(existing.get("order_id"))) {
                if (!dryRun) markLinked(tenantId, orderId, tn);
                return Result.skip("already linked");
            }
            String reason = "tracking number is already a shipment on order " + existing.get("number");
            if (!dryRun) markConflict(tenantId, orderId, tn, reason);
            return Result.skip(reason);
        }
        String otherLeg = tx.execute(s -> jdbc.query(
            "SELECT tracking_number FROM shipments WHERE tenant_id = ? AND order_id = ? " +
            "  AND shipment_leg = 'forward' AND internal_state NOT IN ('terminated', 'cancelled') LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null, tenantId, orderId));
        if (otherLeg != null) {
            String reason = "order already has an active forward leg (" + otherLeg + ")";
            if (!dryRun) markStatus(tenantId, orderId, tn, "skipped", reason);
            return Result.skip(reason);
        }

        String encryptedKey = tx.execute(s -> jdbc.query(
            "SELECT api_key_encrypted FROM courier_accounts " +
            "WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active' LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null, tenantId));
        if (encryptedKey == null) return Result.skip("no active Bosta account");
        String apiKey = encryptionService.decrypt(encryptedKey);   // this tenant's own key, only

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

        if (dryRun) return new Result(Verdict.WOULD_LINK, null, delivery.typeCode(), delivery.stateCode());

        // 4. Link through the discovery pipeline.
        Long eventId = insertEvent(tenantId, orderId, tn, delivery);
        if (eventId != null) {
            try {
                webhookJob.process(eventId, tenantId);
            } catch (RuntimeException e) {
                markRetry(tenantId, orderId, tn, "link pipeline error: " + e.getClass().getSimpleName(), 0, true);
                return Result.skip("link pipeline error: " + e.getClass().getSimpleName(), delivery);
            }
        }
        boolean linked = Boolean.TRUE.equals(tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?)",
            Boolean.class, tenantId, orderId, tn)));
        if (linked) {
            markLinked(tenantId, orderId, tn);
            return new Result(Verdict.LINKED, null, delivery.typeCode(), delivery.stateCode());
        }
        String note = eventId == null ? "an identical link event is already in flight"
            : tx.execute(s -> jdbc.queryForObject("SELECT coalesce(error, status::text) FROM webhook_events WHERE id = ?",
                String.class, eventId));
        markRetry(tenantId, orderId, tn, "not linked yet: " + note, 0, true);
        return Result.skip("not linked yet: " + note, delivery);
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
                "SELECT number FROM orders WHERE id = ?", String.class, others.iterator().next()));
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
            "SELECT id FROM orders WHERE tenant_id = ? AND (" + where + ")", UUID.class, args.toArray()));
        return new LinkedHashSet<>(ids);
    }

    /**
     * The 'shopify_fulfillment' event for BostaWebhookJob: same payload shape as a poll ingest,
     * plus the order id. Its updatedAt carries the order id too, so its idem key never collides
     * with a discovery / webhook event for the same state (which may have been recorded unlinked).
     * ON CONFLICT → null: an identical event exists (a concurrent or earlier attempt).
     */
    private Long insertEvent(UUID tenantId, UUID orderId, String tn, BostaDelivery d) {
        String updatedAt = (d.raw() != null ? d.raw().path("updatedAt").asText("backfill-epoch") : "backfill-epoch")
            + "|fulfillment:" + orderId;
        ObjectNode payload = mapper.createObjectNode();
        payload.put("trackingNumber", d.trackingNumber());
        payload.put("state", d.stateCode());
        payload.put("type", d.type() != null && !d.type().isBlank() ? d.type() : "SEND");
        payload.put("updatedAt", updatedAt);
        payload.put("orderId", orderId.toString());
        String idemKey = BostaWebhookJob.sha256(d.trackingNumber() + ":" + d.stateCode() + ":" + updatedAt);
        return tx.execute(s -> jdbc.query(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at, external_event_id) " +
            "VALUES (?::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now(), ?) " +
            "ON CONFLICT (source, external_event_id) WHERE external_event_id IS NOT NULL DO NOTHING " +
            "RETURNING id",
            rs -> rs.next() ? rs.getLong(1) : null,
            BostaWebhookJob.FULFILLMENT_SOURCE, tenantId, payload.toString(), idemKey));
    }

    // ---- link state on the tracking row -------------------------------------------------------

    private void markLinked(UUID tenantId, UUID orderId, String tn) {
        tx.execute(s -> jdbc.update(
            "UPDATE order_fulfillment_tracking SET link_status = 'linked', link_reason = NULL, " +
            "    linked_at = coalesce(linked_at, now()), link_checked_at = now(), link_next_retry_at = NULL " +
            "WHERE tenant_id = ? AND order_id = ? AND tracking_number = ?", tenantId, orderId, tn));
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
