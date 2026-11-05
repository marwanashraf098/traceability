package com.traceability.integrations.bosta;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The instant check (2026-11-05, BROEK BRK-47264-EG). A Bosta delivery just went unlinked with
 * {@link com.traceability.inventory.ShipmentLinkService#REASON_ACTIVE_FORWARD_LEG}: its order was found but still
 * has a live Bosta forward leg with another waybill. Typical cause: the merchant deleted the old delivery in Bosta
 * and booked a new one — and the normal deletion check would only notice after the 4-hour safety net plus a
 * 30-minute second look. This job makes ONE read-only not-found check of the old leg right away (no Bosta write):
 * <ul>
 *   <li>"Delivery not found" → the leg is suspected (BostaDeletionService.observeNotFound) and
 *       {@link BostaDeletionService#confirmOnFirstCheck} confirms it on this first look — the re-booked delivery is
 *       the second piece of evidence — closes it the BostaDeletionService way and re-links the new delivery
 *       (BostaRelink). A refused fast path leaves the leg suspected: the normal two-step path decides.</li>
 *   <li>Bosta still returns the old delivery → nothing changes; the unlinked row is marked
 *       {@code old_delivery_live} and the existing MEDIUM unmatched_delivery alert says the order has two Bosta
 *       waybills.</li>
 *   <li>Anything inconclusive (404, 401 / 403, 5xx, timeout) → nothing changes ({@code check_inconclusive}).</li>
 * </ul>
 * Lowest priority: the call goes out only when the tenant's Bosta bucket has a token free right now
 * ({@link BostaRateLimiter#hasHeadroom}, user-facing and poll calls first) at BACKGROUND priority; otherwise — or on
 * a 429 — it is re-scheduled in {@code bosta.active-leg-check.retry-seconds} (60), at most
 * {@code bosta.active-leg-check.max-attempts} (10) times. No transaction is open during the Bosta call.
 * Quiet period: once a check found the order's old delivery still live ({@code old_delivery_live} — a real
 * two-parcel order, or a re-booking the merchant hasn't cleaned up), no further lookup for that order for
 * {@code bosta.active-leg-check.live-quiet-minutes} (60): every re-ingest of the unlinked delivery would otherwise
 * cost one Bosta call.
 */
@Service
public class ActiveForwardLegCheckJob {

    private static final Logger log = LoggerFactory.getLogger(ActiveForwardLegCheckJob.class);

    public static final String OLD_DELIVERY_LIVE  = "old_delivery_live";
    public static final String OLD_LEG_MOVED      = "old_leg_moved";
    public static final String CHECK_INCONCLUSIVE = "check_inconclusive";

    private final JdbcTemplate          jdbc;
    private final TransactionTemplate   tx;
    private final BostaGateway          gateway;
    private final EncryptionService     encryption;
    private final BostaDeletionService  deletion;
    private final BostaRelink           relink;
    private final BostaRateLimiter      limiter;
    private final int                   maxAttempts;
    private final long                  retrySeconds;
    private int                         liveQuietMinutes = 60;
    private JobScheduler                jobScheduler;

    @Autowired
    public void setLiveQuietMinutes(@Value("${bosta.active-leg-check.live-quiet-minutes:60}") int minutes) {
        this.liveQuietMinutes = minutes;
    }

    public ActiveForwardLegCheckJob(JdbcTemplate jdbc, PlatformTransactionManager txm, BostaGateway gateway,
                                    EncryptionService encryption, BostaDeletionService deletion, BostaRelink relink,
                                    BostaRateLimiter limiter,
                                    @Value("${bosta.active-leg-check.max-attempts:10}") int maxAttempts,
                                    @Value("${bosta.active-leg-check.retry-seconds:60}") long retrySeconds) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txm);
        this.gateway = gateway;
        this.encryption = encryption;
        this.deletion = deletion;
        this.relink = relink;
        this.limiter = limiter;
        this.maxAttempts = maxAttempts;
        this.retrySeconds = retrySeconds;
    }

    @Autowired(required = false)
    public void setJobScheduler(JobScheduler jobScheduler) {
        this.jobScheduler = jobScheduler;
    }

    /** Enqueue the check for the unlinked delivery {@code newTrackingNumber}. Never throws. */
    public void enqueue(UUID tenantId, String newTrackingNumber) {
        if (jobScheduler == null) return;
        // The common re-ingest: this same delivery's check already found the old one live within the quiet period.
        Boolean quiet = TenantContext.runAs(tenantId, () -> tx.execute(s -> jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ? " +
            "  AND resolved = false AND relink_outcome = ? AND relink_at > now() - (? * INTERVAL '1 minute'))",
            Boolean.class, tenantId, newTrackingNumber, OLD_DELIVERY_LIVE, liveQuietMinutes)));
        if (Boolean.TRUE.equals(quiet)) return;
        final ActiveForwardLegCheckJob self = this;
        try {
            jobScheduler.enqueue(() -> self.check(tenantId, newTrackingNumber, 1));
        } catch (RuntimeException e) {
            log.warn("Active-leg check: tenant {} tracking {} could not be enqueued: {}", tenantId, newTrackingNumber, e.toString());
        }
    }

    /** The check (class javadoc). {@code attempt} counts re-schedules for a busy Bosta bucket. */
    @Job(name = "Bosta: check an order's old waybill", retries = 0)
    public void check(UUID tenantId, String newTrackingNumber, int attempt) {
        TenantContext.runAs(tenantId, () -> checkInTenant(tenantId, newTrackingNumber, attempt));
    }

    private void checkInTenant(UUID tenantId, String newTn, int attempt) {
        // The unlinked delivery and its order (exactly one, by the ONE reference rule).
        List<Map<String, Object>> rows = tx.execute(s -> jdbc.queryForList(
            "SELECT business_reference, raw #>> '{shopifyInfo,orderId}' AS shopify_id FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND tracking_number = ? AND resolved = false", tenantId, newTn));
        if (rows == null || rows.isEmpty()) return;                          // linked meanwhile, or gone
        List<UUID> to = tx.execute(s -> OrderReference.resolve(jdbc, tenantId,
            (String) rows.get(0).get("business_reference"), (String) rows.get(0).get("shopify_id")));
        if (to == null || to.size() != 1) {
            mark(tenantId, newTn, BostaRelink.REFUSED + "identity");
            return;
        }
        UUID orderId = to.get(0);

        List<Map<String, Object>> legs = tx.execute(s -> jdbc.queryForList(
            "SELECT id, tracking_number, internal_state::text AS state FROM shipments " +
            "WHERE order_id = ? AND tenant_id = ? AND provider = 'bosta' AND shipment_leg = 'forward' " +
            "  AND internal_state NOT IN ('terminated', 'cancelled') AND provider_deleted_at IS NULL " +
            "  AND superseded_at IS NULL AND tracking_number <> ? ORDER BY created_at DESC, id DESC",
            orderId, tenantId, newTn));
        if (legs == null || legs.isEmpty()) {                                 // the old leg already ended
            relink.relinkStrandedUnlinked(tenantId, orderId, "active_leg_check");
            return;
        }
        Map<String, Object> leg = legs.get(0);
        UUID legId = (UUID) leg.get("id");
        String oldTn = (String) leg.get("tracking_number");
        if (!"created".equals(leg.get("state"))) {                            // the old waybill really moved
            mark(tenantId, newTn, OLD_LEG_MOVED);
            return;
        }

        if (inQuietPeriod(tenantId, orderId)) {
            log.info("Active-leg check: tenant {} order {} — old waybill found live < {} min ago, no lookup",
                tenantId, orderId, liveQuietMinutes);
            return;
        }

        List<String> keys = tx.execute(s -> jdbc.queryForList(
            "SELECT api_key_encrypted FROM courier_accounts WHERE tenant_id = ? AND provider = 'bosta' AND status = 'active'",
            String.class, tenantId));
        if (keys == null || keys.isEmpty()) return;
        String apiKey = encryption.decrypt(keys.get(0));

        if (limiter != null && !limiter.hasHeadroom(apiKey, 0)) {            // lowest priority: only a free token
            reschedule(tenantId, newTn, attempt, "no free Bosta token");
            return;
        }
        try {
            BostaDelivery d = gateway.fetchDelivery(apiKey, oldTn);          // BACKGROUND, no transaction open
            if (d != null) {
                deletion.recordKeyOk(tenantId);
                mark(tenantId, newTn, OLD_DELIVERY_LIVE);
                log.info("Active-leg check: tenant {} order {} — old waybill {} still in Bosta; new {} left unlinked (two waybills)",
                    tenantId, orderId, oldTn, newTn);
            } else {
                mark(tenantId, newTn, CHECK_INCONCLUSIVE);                     // 404: inconclusive
            }
        } catch (DeliveryNotFoundException e) {
            deletion.observeNotFound(tenantId, legId);
            String[] why = {null};
            BostaDeletionService.Confirmation c = deletion.confirmOnFirstCheck(tenantId, legId, newTn, why);
            if (c != BostaDeletionService.Confirmation.CLOSED) {
                mark(tenantId, newTn, c == BostaDeletionService.Confirmation.FIRST_CHECK_REFUSED
                    ? BostaRelink.REFUSED + why[0] : "old_not_found:" + c.name().toLowerCase(java.util.Locale.ROOT));
            }
        } catch (BostaRateLimitException e) {
            reschedule(tenantId, newTn, attempt, "rate limited");
        } catch (BostaAuthException e) {
            deletion.recordKeyAuthFailure(tenantId);
            mark(tenantId, newTn, CHECK_INCONCLUSIVE);
        } catch (RuntimeException e) {
            log.warn("Active-leg check: tenant {} tracking {} inconclusive: {}", tenantId, oldTn, e.toString());
            mark(tenantId, newTn, CHECK_INCONCLUSIVE);
        }
    }

    /** Any of the ORDER's unlinked deliveries had a check find the old delivery live within the quiet period. */
    private boolean inQuietPeriod(UUID tenantId, UUID orderId) {
        List<Map<String, Object>> recent = tx.execute(s -> jdbc.queryForList(
            "SELECT business_reference, raw #>> '{shopifyInfo,orderId}' AS shopify_id FROM unlinked_bosta_deliveries " +
            "WHERE tenant_id = ? AND resolved = false AND relink_outcome = ? AND relink_at > now() - (? * INTERVAL '1 minute')",
            tenantId, OLD_DELIVERY_LIVE, liveQuietMinutes));
        if (recent == null) return false;
        for (Map<String, Object> r : recent) {
            List<UUID> to = tx.execute(s -> OrderReference.resolve(jdbc, tenantId,
                (String) r.get("business_reference"), (String) r.get("shopify_id")));
            if (to != null && to.size() == 1 && to.get(0).equals(orderId)) return true;
        }
        return false;
    }

    private void reschedule(UUID tenantId, String newTn, int attempt, String why) {
        if (jobScheduler == null || attempt >= maxAttempts) {
            mark(tenantId, newTn, CHECK_INCONCLUSIVE);
            log.info("Active-leg check: tenant {} tracking {} — {} after {} attempt(s), left to the safety net", tenantId, newTn, why, attempt);
            return;
        }
        final ActiveForwardLegCheckJob self = this;
        final int next = attempt + 1;
        jobScheduler.schedule(Instant.now().plusSeconds(retrySeconds), () -> self.check(tenantId, newTn, next));
    }

    private void mark(UUID tenantId, String newTn, String outcome) {
        tx.execute(s -> jdbc.update("UPDATE unlinked_bosta_deliveries SET relink_outcome = ?, relink_at = now() " +
            "WHERE tenant_id = ? AND tracking_number = ? AND resolved = false", outcome, tenantId, newTn));
    }
}
