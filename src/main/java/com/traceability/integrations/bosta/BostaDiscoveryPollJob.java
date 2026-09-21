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
            @Value("${bosta.poll.discovery-enabled:true}") boolean discoveryEnabled) {
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
            String topOfListTracking = null;

            outer:
            for (int page = 1; page <= discoveryPages; page++) {
                List<BostaGateway.SlimDelivery> items;
                try {
                    items = bostaGateway.listDeliveriesPage(apiKey, page, pageSize);
                } catch (BostaTransientException e) {
                    log.warn("Discovery poll tenant {}: transient error on page {} — stopping: {}",
                        tenantId, page, e.getMessage());
                    transientError = true;
                    break;
                }

                if (items.isEmpty()) break;

                Set<String> linked = alreadyLinkedTrackingNumbers(tenantId,
                    items.stream().map(BostaGateway.SlimDelivery::trackingNumber).toList());

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

                    // Genuinely unresolved: brand new, or still-unlinked from an
                    // earlier cycle (Guard 3 in BostaIngestionHelper keeps deciding
                    // whether to re-enqueue it — unchanged). If this item IS the
                    // stored mark (set last cycle before it had linked yet) it falls
                    // through to here too — re-attempted exactly like any other
                    // unresolved item, not treated as "caught up".
                    attempted++;
                    try {
                        if (ingestionHelper.ingestDelivery(
                                tenantId, apiKey, slim.trackingNumber(), "bosta_poll_discovery")) {
                            enqueued++;
                        }
                    } catch (BostaTransientException e) {
                        log.warn("Discovery poll tenant {}: transient error on {} — skipping: {}",
                            tenantId, slim.trackingNumber(), e.getMessage());
                    } catch (Exception e) {
                        log.error("Discovery poll tenant {}: unexpected error on {} — skipping",
                            tenantId, slim.trackingNumber(), e);
                    }

                    try {
                        if (interFetchDelayMs > 0) Thread.sleep(interFetchDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break outer;
                    }

                    if (attempted >= maxNewItemsPerCycle) {
                        ceilingHit = true;
                        break;
                    }
                }

                if (reachedMark || ceilingHit) break;
            }

            // Advance the mark on any fully clean cycle: no transient error and the
            // ceiling wasn't hit. Deliberately NOT gated on (reachedMark || cleanEnd) —
            // those two only control whether the scan got to break out early; a cycle
            // that instead exhausts discoveryPages while every page was already-linked
            // filler (isLinked skip — doesn't count toward the ceiling) is just as
            // clean and must advance too, or a tenant whose linked history alone spans
            // more than discoveryPages×pageSize never writes a mark (the from-null,
            // page-exhaustion bug — see class javadoc and BostaPollJobTest p19).
            // Advancing is safe even when the newest item is itself still unresolved
            // (just enqueued this cycle, not yet linked) — the isLinked check above
            // means a stale mark pointing at a not-yet-linked item simply gets
            // re-attempted next cycle instead of wrongly short-circuiting, so nothing
            // gets silently buried. On ceilingHit or a transient error, the mark is
            // left exactly as it was; the next cycle re-walks the same span and
            // (thanks to the per-item shipments skip-check above) that's cheap.
            if (!transientError && !ceilingHit
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
