package com.traceability.integrations.bosta;

import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tier 2 — Discovery Poll: ingests NEW Bosta deliveries not yet known to Traced.
 *
 * Runs infrequently (default 20 minutes, config-wired via bosta.poll.discovery-cron —
 * see the constant default below; not yet changed by this class). Pages the Bosta List
 * API newest-first and routes each not-yet-linked delivery through BostaIngestionHelper
 * with source='bosta_poll_discovery'. Idempotency ensures already-processed deliveries
 * are a no-op; truly new ones get ingested and matched via ShipmentLinkService, then
 * Tier 1 keeps their status current.
 *
 * The full backfill (all pages) remains as the on-connect + manual Sync button trigger.
 * Tier 2 is the lightweight ongoing discovery pass covering the most recently created
 * deliveries that may have arrived since the last cycle.
 *
 * High-water-mark cursor (courier_accounts.discovery_high_water_tracking, V96):
 * each cycle pages from the top and stops as soon as it reaches a tracking number that
 * (a) equals the mark stored from a previous cycle AND (b) already has a shipments row
 * (is linked). A per-page batched check against `shipments` additionally skips (no
 * Bosta call, doesn't count toward the per-cycle ceiling) any item that's already
 * linked — Tier 1 owns its ongoing state from here on. Only genuinely unresolved items
 * (brand new, or still-unlinked from an earlier cycle — Guard 3 in BostaIngestionHelper
 * keeps governing those exactly as before) are actually fetched from Bosta.
 *
 * The mark advances to the newest item seen (page 1's first item, topOfListTracking)
 * at the end of ANY cycle that hit no transient error and didn't run into the
 * per-cycle ceiling — regardless of whether the scan actually reached the old mark
 * or the true end of the list. Reaching the mark or an empty page lets the scan
 * break out early (cheaper), but neither is required for the write: a cycle that
 * simply runs out of discoveryPages while every page it saw was already-linked
 * filler (isLinked skip, doesn't touch the ceiling) is just as "clean" as one that
 * hit an empty page, and must advance too — otherwise a tenant whose linked history
 * alone exceeds discoveryPages×pageSize never writes a mark at all (confirmed in
 * prod 2026-09-21: discovery_high_water_tracking stayed null forever for a tenant
 * with 205 linked shipments, even though the job "succeeded" every cycle). See
 * BostaPollJobTest p19 for the from-null, page-exhaustion regression case.
 *
 * This advances the mark even when the newest item is itself still unresolved (its
 * ingest was only just enqueued this cycle; BostaWebhookJob decides whether it
 * links, asynchronously, later). That's why requirement (b) above matters: if the
 * mark's own item still hasn't linked by the next cycle, the equality match alone is
 * NOT treated as "caught up" — it falls through to the unresolved branch and gets
 * retried, exactly like any other still-unlinked delivery, instead of the scan
 * wrongly stopping there and burying it. A tenant with a persistently-unlinked
 * delivery therefore keeps re-fetching it every cycle indefinitely — matching today's
 * behavior for anything within the scanned window — rather than the mark silently
 * making it invisible to future cycles. See BostaPollJobTest p15/p17 for the worked
 * trace of both the steady-state fast path and this retry-until-linked behavior.
 *
 * Per-cycle ceiling (bosta.poll.discovery-max-items-per-cycle, default 150 — the
 * current effective discoveryPages×pageSize bound): caps how many genuinely unresolved
 * items get a real Bosta fetchDelivery call in one cycle. If a burst is larger than
 * the ceiling, the cycle processes what it can and does NOT advance the mark — the
 * next cycle resumes, cheaply skipping everything already linked via the batched
 * shipments check, and picks up exactly where this one left off. Nothing is dropped.
 * discoveryPages remains a hard page-count safety valve (unchanged default) — a
 * circuit breaker against a runaway/buggy page walk, independent of the ceiling.
 *
 * Per-item failures (V128 retry list, 2026-10-02): a delivery whose fetch fails (5xx / IO,
 * 429, "Delivery not found", anything unexpected) is written to bosta_discovery_failures and
 * retried by tracking number at the start of every cycle — never just skipped while the mark
 * moves past it. A 429 also stops the cycle and holds the mark. After
 * bosta.poll.discovery-max-item-failures (10) counted failures the row escalates: the
 * bosta_discovery_failed exception is raised and the row is retried only every
 * bosta.poll.discovery-slow-retry-minutes (60), until bosta.poll.discovery-retry-cap-hours (48)
 * after its first failure. A success at any point deletes the row and clears the exception. The
 * mark is never held for a failed item.
 *
 * Per-tenant advisory lock: discoverTenant() runs many short-lived transactions
 * internally (one tx.execute() per DB touch inside BostaIngestionHelper), so a single
 * pg_advisory_xact_lock would release after the first of those commits — long before
 * the cycle finishes. tryDiscoverTenant() instead pins ONE Connection for the whole
 * cycle and uses the session-level pg_try_advisory_lock/pg_advisory_unlock pair on it.
 * Non-blocking (try, not block-and-wait): if a previous cycle for this tenant is still
 * running when the next scheduled fire happens, waiting would just queue up an
 * ever-growing backlog of blocked runs once cadence rises. Skipping is self-healing —
 * the skipped cycle's mark is untouched, so the next fire retries from the same place.
 *
 * TenantContext: same pattern as BostaBackfillJob and BostaStatusPollJob — all per-tenant
 * work runs inside TenantContext.runAs(tenantId).
 */
@Service
public class BostaDiscoveryPollJob {

    private static final Logger log = LoggerFactory.getLogger(BostaDiscoveryPollJob.class);

    private static final String ACTIVE_BOSTA_TENANTS =
        "SELECT ca.tenant_id, ca.api_key_encrypted, ca.discovery_high_water_tracking " +
        "FROM courier_accounts ca " +
        "WHERE ca.provider = 'bosta' AND ca.status = 'active'";

    // Advisory-lock namespace: a fixed, arbitrary hash so this lock space never
    // collides with any other pg_advisory_lock use elsewhere in the codebase.
    private static final int LOCK_NAMESPACE = "bosta-discovery-lock".hashCode();

    private final JdbcTemplate        ownerJdbc;
    private final JdbcTemplate        jdbc;
    private final TransactionTemplate  tx;
    private final BostaGateway         bostaGateway;
    private final EncryptionService    encryptionService;
    private final BostaIngestionHelper ingestionHelper;
    private final int                  discoveryPages;
    private final int                  pageSize;
    private final int                  maxNewItemsPerCycle;
    private final long                 interFetchDelayMs;
    private final boolean              discoveryEnabled;
    private final int                  maxItemFailures;
    private final int                  slowRetryMinutes;
    private final int                  retryCapHours;

    public BostaDiscoveryPollJob(
            @FlywayDataSource DataSource ownerDs,
            JdbcTemplate jdbc,
            PlatformTransactionManager txm,
            BostaGateway bostaGateway,
            EncryptionService encryptionService,
            BostaIngestionHelper ingestionHelper,
            @Value("${bosta.poll.discovery-pages:3}") int discoveryPages,
            @Value("${bosta.backfill.page-size:50}") int pageSize,
            @Value("${bosta.poll.discovery-max-items-per-cycle:150}") int maxNewItemsPerCycle,
            @Value("${bosta.poll.inter-fetch-delay-ms:100}") long interFetchDelayMs,
            @Value("${bosta.poll.discovery-enabled:true}") boolean discoveryEnabled,
            @Value("${bosta.poll.discovery-max-item-failures:10}") int maxItemFailures,
            @Value("${bosta.poll.discovery-slow-retry-minutes:60}") int slowRetryMinutes,
            @Value("${bosta.poll.discovery-retry-cap-hours:48}") int retryCapHours) {
        this.ownerJdbc           = new JdbcTemplate(ownerDs);
        this.jdbc                = jdbc;
        this.tx                  = new TransactionTemplate(txm);
        this.bostaGateway        = bostaGateway;
        this.encryptionService   = encryptionService;
        this.ingestionHelper     = ingestionHelper;
        this.discoveryPages      = discoveryPages;
        this.pageSize            = pageSize;
        this.maxNewItemsPerCycle = maxNewItemsPerCycle;
        this.interFetchDelayMs   = interFetchDelayMs;
        this.discoveryEnabled    = discoveryEnabled;
        this.maxItemFailures     = maxItemFailures;
        this.slowRetryMinutes    = slowRetryMinutes;
        this.retryCapHours       = retryCapHours;
    }

    // Cron is config-wired (bosta.poll.discovery-cron) but defaults to the same */20 as
    // before — this change does NOT flip the effective cadence; that's a follow-up once
    // the high-water mark + lock above have proven themselves at the current interval.
    @Recurring(id = "bosta-discovery-poll", cron = "${bosta.poll.discovery-cron:*/20 * * * *}")
    @Job(name = "Bosta discovery poll")
    public void discoverAll() {
        // In-method guard, not @ConditionalOnProperty at class level (bed889d): a
        // class-level conditional means the bean doesn't exist when the flag is false,
        // but JobRunr's persistent recurring-job entry still fires on schedule, can't
        // resolve the bean, marks the run failed, and immediately reschedules — rapid
        // re-fire (~5s) that hammers the DB until someone notices. Keeping the bean
        // always present and only short-circuiting the method body avoids that entirely.
        if (!discoveryEnabled) {
            log.info("Discovery poll disabled via bosta.poll.discovery-enabled=false — skipping");
            return;
        }

        List<Map<String, Object>> accounts = ownerJdbc.queryForList(ACTIVE_BOSTA_TENANTS);
        if (accounts.isEmpty()) {
            log.debug("Discovery poll: no active Bosta accounts");
            return;
        }

        for (Map<String, Object> row : accounts) {
            UUID tenantId        = (UUID) row.get("tenant_id");
            String encryptedKey  = (String) row.get("api_key_encrypted");
            String highWaterMark = (String) row.get("discovery_high_water_tracking");
            try {
                String apiKey = encryptionService.decrypt(encryptedKey);
                tryDiscoverTenant(tenantId, apiKey, highWaterMark);
            } catch (Exception e) {
                log.warn("Discovery poll failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    /**
     * Acquires the per-tenant advisory lock on a single pinned Connection, runs
     * discoverTenant() if acquired, and always releases on the same Connection before
     * returning it to the pool. See the class javadoc for why this is session-level
     * try-lock rather than pg_advisory_xact_lock, and why it skips rather than blocks.
     */
    private void tryDiscoverTenant(UUID tenantId, String apiKey, String highWaterMark) {
        int tenantKey = tenantId.hashCode();
        try (Connection lockConn = jdbc.getDataSource().getConnection()) {
            boolean acquired;
            try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                ps.setInt(1, LOCK_NAMESPACE);
                ps.setInt(2, tenantKey);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    acquired = rs.getBoolean(1);
                }
            }

            if (!acquired) {
                log.info("Discovery poll tenant {}: previous cycle still running — skipping this run",
                    tenantId);
                return;
            }

            try {
                discoverTenant(tenantId, apiKey, highWaterMark);
            } finally {
                try (PreparedStatement ps = lockConn.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                    ps.setInt(1, LOCK_NAMESPACE);
                    ps.setInt(2, tenantKey);
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Discovery advisory lock error for tenant " + tenantId, e);
        }
    }

    private void discoverTenant(UUID tenantId, String apiKey, String storedMark) {
        TenantContext.runAs(tenantId, (Runnable) () -> {

            int total = 0, enqueued = 0, attempted = 0;
            boolean ceilingHit      = false;
            boolean reachedMark     = false;
            boolean transientError  = false;
            boolean rateLimited     = false;
            String topOfListTracking = null;

            // Retry pass (V128): every delivery whose fetch failed in an earlier cycle is
            // retried here by tracking number, BEFORE the list walk and independent of the
            // newest-first window — a failed item can sit any distance below the mark or
            // past discoveryPages×pageSize and is still retried. Counts toward the ceiling.
            Set<String> handledThisCycle = new HashSet<>();
            stopExpiredRetries(tenantId);
            List<String> retries = tx.execute(s -> jdbc.query(
                "SELECT tracking_number FROM bosta_discovery_failures " +
                "WHERE tenant_id = ? AND retries_stopped_at IS NULL " +
                "  AND (escalated_at IS NULL OR next_retry_at IS NULL OR next_retry_at <= now()) " +
                "ORDER BY first_failed_at, id LIMIT ?",
                (rs, i) -> rs.getString(1), tenantId, maxNewItemsPerCycle));
            for (String tn : retries) {
                attempted++;
                handledThisCycle.add(tn);
                ItemOutcome outcome = ingestTracked(tenantId, apiKey, tn);
                if (outcome == ItemOutcome.ENQUEUED) enqueued++;
                if (outcome == ItemOutcome.RATE_LIMITED) { rateLimited = true; break; }
                if (!pause()) { transientError = true; break; }
                if (attempted >= maxNewItemsPerCycle) { ceilingHit = true; break; }
            }

            outer:
            for (int page = 1; page <= discoveryPages && !rateLimited && !ceilingHit && !transientError; page++) {
                List<BostaGateway.SlimDelivery> items;
                try {
                    items = bostaGateway.listDeliveriesPage(apiKey, page, pageSize);
                } catch (BostaTransientException | BostaRateLimitException e) {
                    log.warn("Discovery poll tenant {}: transient error on page {} — stopping: {}",
                        tenantId, page, e.getMessage());
                    transientError = true;
                    break;
                }

                if (items.isEmpty()) break;

                List<String> pageTracking = items.stream().map(BostaGateway.SlimDelivery::trackingNumber).toList();
                Set<String> linked   = alreadyLinkedTrackingNumbers(tenantId, pageTracking);
                Set<String> recorded = failureRowTrackingNumbers(tenantId, pageTracking);

                for (BostaGateway.SlimDelivery slim : items) {
                    total++;
                    if (topOfListTracking == null) topOfListTracking = slim.trackingNumber();

                    boolean isLinked = linked.contains(slim.trackingNumber());

                    // The mark advances to "the newest item this cycle" unconditionally
                    // (see the advance-check below) — including when that item is still
                    // unresolved at the moment the mark is set (ingest was just enqueued
                    // this cycle; whether it ends up linked is decided later, async, by
                    // BostaWebhookJob). So a plain string match against the mark is NOT
                    // proof of "caught up" by itself: requiring isLinked too means that
                    // if the mark's own item still hasn't resolved by the next cycle, it
                    // falls through to the unresolved branch below and gets retried —
                    // exactly like any other still-unlinked delivery — instead of the
                    // scan wrongly treating "same tracking number as last time" as done
                    // and burying it. See BostaPollJobTest p15/p17 for the worked trace.
                    if (slim.trackingNumber().equals(storedMark) && isLinked) {
                        reachedMark = true;
                        break;
                    }

                    if (isLinked) {
                        // Already linked (shipments row exists) in an earlier cycle —
                        // Tier 1 owns its ongoing state from here on. Cheap skip: no
                        // Bosta call, doesn't count toward the per-cycle ceiling.
                        continue;
                    }

                    // Already handled by the retry pass this cycle, or on the retry list
                    // (the retry pass owns it, at its own pace — fast, slow, or stopped).
                    // Never fetched twice in one cycle.
                    if (handledThisCycle.contains(slim.trackingNumber())
                            || recorded.contains(slim.trackingNumber())) {
                        continue;
                    }

                    // Genuinely unresolved: brand new, or still-unlinked from an
                    // earlier cycle (Guard 3 in BostaIngestionHelper keeps deciding
                    // whether to re-enqueue it — unchanged). If this item IS the
                    // stored mark (set last cycle before it had linked yet) it falls
                    // through to here too — re-attempted exactly like any other
                    // unresolved item, not treated as "caught up".
                    attempted++;
                    ItemOutcome outcome = ingestTracked(tenantId, apiKey, slim.trackingNumber());
                    if (outcome == ItemOutcome.ENQUEUED) enqueued++;
                    if (outcome == ItemOutcome.RATE_LIMITED) {
                        // Stop the cycle; the item is on the retry list and the mark is held,
                        // so nothing after it on this page is lost either.
                        rateLimited = true;
                        break outer;
                    }

                    if (!pause()) { transientError = true; break outer; }

                    if (attempted >= maxNewItemsPerCycle) {
                        ceilingHit = true;
                        break;
                    }
                }

                if (reachedMark || ceilingHit) break;
            }

            // Advance the mark on any fully clean cycle: no transient error, no rate limit
            // and the ceiling wasn't hit. Deliberately NOT gated on (reachedMark || cleanEnd) —
            // those two only control whether the scan got to break out early; a cycle
            // that instead exhausts discoveryPages while every page was already-linked
            // filler (isLinked skip — doesn't count toward the ceiling) is just as
            // clean and must advance too, or a tenant whose linked history alone spans
            // more than discoveryPages×pageSize never writes a mark (the from-null,
            // page-exhaustion bug — see class javadoc and BostaPollJobTest p19).
            // A per-item fetch failure does NOT block the advance any more: the failed
            // tracking number is on the retry list (V128) and is retried by tracking number
            // until it ingests or the retry cap passes — the mark passing it loses nothing.
            if (!transientError && !ceilingHit && !rateLimited
                    && topOfListTracking != null && !topOfListTracking.equals(storedMark)) {
                advanceHighWaterMark(tenantId, topOfListTracking);
            }

            if (enqueued > 0) {
                log.info("Discovery poll tenant {}: {} seen, {} new deliveries enqueued{}",
                    tenantId, total, enqueued,
                    ceilingHit ? " (ceiling reached — resumes next cycle)" : "");
            } else {
                log.debug("Discovery poll tenant {}: {} seen, nothing new", tenantId, total);
            }
        });
    }

    private enum ItemOutcome { ENQUEUED, SKIPPED, FAILED, RATE_LIMITED }

    /**
     * One delivery through BostaIngestionHelper. Success (enqueued or a legitimate skip)
     * clears any retry-list row; a 429 records the item without counting an attempt; any
     * other exception (5xx / IO, "Delivery not found", unexpected) counts one attempt.
     */
    private ItemOutcome ingestTracked(UUID tenantId, String apiKey, String trackingNumber) {
        try {
            boolean enq = ingestionHelper.ingestDelivery(
                tenantId, apiKey, trackingNumber, "bosta_poll_discovery");
            clearFailure(tenantId, trackingNumber);
            return enq ? ItemOutcome.ENQUEUED : ItemOutcome.SKIPPED;
        } catch (BostaRateLimitException e) {
            log.warn("Discovery poll tenant {}: rate limited on {} — stopping this cycle",
                tenantId, trackingNumber);
            recordFailure(tenantId, trackingNumber, true, "rate limited (retry after "
                + e.getRetryAfterSeconds() + "s)");
            return ItemOutcome.RATE_LIMITED;
        } catch (Exception e) {
            log.warn("Discovery poll tenant {}: fetch failed for {} — on the retry list: {}",
                tenantId, trackingNumber, e.toString());
            recordFailure(tenantId, trackingNumber, false, e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            return ItemOutcome.FAILED;
        }
    }

    /** Sleeps the inter-fetch delay; false if interrupted. */
    private boolean pause() {
        try {
            if (interFetchDelayMs > 0) Thread.sleep(interFetchDelayMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void recordFailure(UUID tenantId, String trackingNumber, boolean rateLimited, String error) {
        String err = error.length() > 500 ? error.substring(0, 500) : error;
        Boolean escalated = tx.execute(s -> {
            jdbc.update(
                "INSERT INTO bosta_discovery_failures " +
                "    (tenant_id, tracking_number, attempts, rate_limited_count, last_error) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (tenant_id, tracking_number) DO UPDATE SET " +
                "    attempts           = bosta_discovery_failures.attempts + EXCLUDED.attempts, " +
                "    rate_limited_count = bosta_discovery_failures.rate_limited_count + EXCLUDED.rate_limited_count, " +
                "    last_failed_at     = now(), " +
                "    last_error         = EXCLUDED.last_error",
                tenantId, trackingNumber, rateLimited ? 0 : 1, rateLimited ? 1 : 0, err);
            // An escalated row waits the slow interval before its next retry.
            jdbc.update(
                "UPDATE bosta_discovery_failures " +
                "SET next_retry_at = now() + (? * INTERVAL '1 minute') " +
                "WHERE tenant_id = ? AND tracking_number = ? AND escalated_at IS NOT NULL",
                slowRetryMinutes, tenantId, trackingNumber);
            // Escalate at N counted failures: raises bosta_discovery_failed; retries continue
            // at the slow interval until the cap.
            return jdbc.update(
                "UPDATE bosta_discovery_failures " +
                "SET escalated_at = now(), next_retry_at = now() + (? * INTERVAL '1 minute') " +
                "WHERE tenant_id = ? AND tracking_number = ? AND escalated_at IS NULL AND attempts >= ?",
                slowRetryMinutes, tenantId, trackingNumber, maxItemFailures) > 0;
        });
        if (Boolean.TRUE.equals(escalated)) {
            log.warn("Discovery poll tenant {}: {} failed {} times — raised as bosta_discovery_failed; " +
                "retrying every {} min until {} h after the first failure",
                tenantId, trackingNumber, maxItemFailures, slowRetryMinutes, retryCapHours);
        }
    }

    /**
     * Rows whose first failure is older than the cap stop being retried (escalated too if they
     * never reached N — e.g. only 429s — so the exception is raised either way).
     */
    private void stopExpiredRetries(UUID tenantId) {
        Integer stopped = tx.execute(s -> jdbc.update(
            "UPDATE bosta_discovery_failures " +
            "SET retries_stopped_at = now(), escalated_at = COALESCE(escalated_at, now()) " +
            "WHERE tenant_id = ? AND retries_stopped_at IS NULL " +
            "  AND first_failed_at <= now() - (? * INTERVAL '1 hour')",
            tenantId, retryCapHours));
        if (stopped != null && stopped > 0) {
            log.warn("Discovery poll tenant {}: stopped retrying {} delivery(ies) {} h after their first " +
                "failed fetch — bosta_discovery_failed stays open", tenantId, stopped, retryCapHours);
        }
    }

    private void clearFailure(UUID tenantId, String trackingNumber) {
        tx.execute(s -> jdbc.update(
            "DELETE FROM bosta_discovery_failures WHERE tenant_id = ? AND tracking_number = ?",
            tenantId, trackingNumber));
    }

    /** Of these tracking numbers, which have a retry-list row (any state)? */
    private Set<String> failureRowTrackingNumbers(UUID tenantId, List<String> trackingNumbers) {
        if (trackingNumbers.isEmpty()) return Set.of();
        String placeholders = trackingNumbers.stream().map(t -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(trackingNumbers);
        List<String> found = tx.execute(s -> jdbc.query(
            "SELECT tracking_number FROM bosta_discovery_failures " +
            "WHERE tenant_id = ? AND tracking_number IN (" + placeholders + ")",
            (rs, i) -> rs.getString(1),
            args.toArray()));
        return new HashSet<>(found);
    }

    /**
     * Batched per-page check: of these tracking numbers, which already have a
     * shipments row (i.e. are already linked)? One indexed query per page
     * (shipments.tracking_number is UNIQUE, V1) instead of one Bosta API call per item.
     */
    private Set<String> alreadyLinkedTrackingNumbers(UUID tenantId, List<String> trackingNumbers) {
        if (trackingNumbers.isEmpty()) return Set.of();

        String placeholders = trackingNumbers.stream().map(t -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(trackingNumbers);

        List<String> found = tx.execute(s -> jdbc.query(
            "SELECT tracking_number FROM shipments " +
            "WHERE tenant_id = ? AND tracking_number IN (" + placeholders + ")",
            (rs, i) -> rs.getString(1),
            args.toArray()));
        return new HashSet<>(found);
    }

    private void advanceHighWaterMark(UUID tenantId, String newMark) {
        tx.execute(s -> {
            jdbc.update(
                "UPDATE courier_accounts SET discovery_high_water_tracking = ? " +
                "WHERE tenant_id = ? AND provider = 'bosta'",
                newMark, tenantId);
            return null;
        });
    }

    /**
     * Exposed for tests only: lets a test acquire/release the exact same advisory
     * lock this job uses, without duplicating the key derivation.
     */
    public static int[] advisoryLockKeys(UUID tenantId) {
        return new int[]{LOCK_NAMESPACE, tenantId.hashCode()};
    }
}
