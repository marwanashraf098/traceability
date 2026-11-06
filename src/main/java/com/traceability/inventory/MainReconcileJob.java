package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Main reconcile (V201, R1, report-only) — two recurring passes, both no-ops unless traced.main-reconcile.enabled:
 *   queue (every minute): tenants with a variant queued and untouched for debounce-seconds → one per-variant run each;
 *   sweep (traced.main-reconcile.sweep-cron, default every 4 h): every tenant with a store and a main warehouse → a
 *          full run over every level at its Shopify Main location plus every variant Traced holds there; first, the
 *          retention purge (runs + diffs older than retention-days; Part 1 claims are kept). A store whose run
 *          fails is retried once at the end of the same sweep; failing two sweeps in a row raises one internal
 *          alert (MainReconcileAlerter, operators only).
 * Tenant lists are a cross-tenant read of tenant ids only, on the owner pool (PieceShopifySweepJob's pattern); every
 * run is under TenantContext.runAs + RLS. Report-only: nothing here writes to Shopify.
 */
@Component
@ConditionalOnProperty(name = "org.jobrunr.background-job-server.enabled", havingValue = "true", matchIfMissing = true)
public class MainReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(MainReconcileJob.class);

    private final MainReconcileService service;
    private final JdbcTemplate ownerJdbc;
    private final MainReconcileAlerter alerter;

    public MainReconcileJob(MainReconcileService service, @FlywayDataSource DataSource ownerDs, MainReconcileAlerter alerter) {
        this.service   = service;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.alerter   = alerter;
    }

    @Recurring(id = "main-reconcile-queue", cron = "${traced.main-reconcile.queue-cron:* * * * *}", zoneId = "UTC")
    @Job(name = "Main reconcile — queued variants (report-only)")
    public void runQueue() {
        if (!service.enabled()) return;
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM main_reconcile_variant_state WHERE pending_since IS NOT NULL " +
            "  AND pending_touched_at < now() - ? * interval '1 second'", UUID.class, service.debounceSeconds());
        for (UUID tenantId : tenants) {
            try {
                TenantContext.runAs(tenantId, () -> service.runQueued(tenantId));
            } catch (Exception e) {
                log.warn("Main reconcile queue failed for tenant {}: {}", tenantId, e.toString());
            }
        }
    }

    @Recurring(id = "main-reconcile-sweep", cron = "${traced.main-reconcile.sweep-cron:15 */4 * * *}", zoneId = "UTC")
    @Job(name = "Main reconcile — full sweep (report-only)")
    public void runSweep() {
        purgeOld();
        if (!service.enabled()) return;
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT s.tenant_id FROM stores s " +
            "JOIN locations l ON l.tenant_id = s.tenant_id AND l.is_fulfillment = true " +
            "WHERE s.status <> 'disconnected'", UUID.class);
        sweep(tenants);
    }

    /**
     * One sweep over these stores: each store once (attempt 1); then every store whose run failed is run once more
     * (attempt 2) — later in the same sweep, after every other store had its turn; then a store whose sweep failed
     * (its last attempt) for the second time in a row raises ONE internal alert (MainReconcileAlerter — at exactly two,
     * so a long outage alerts once, not every 4 hours). Package-private for tests.
     */
    void sweep(List<UUID> tenants) {
        List<UUID> failed = new ArrayList<>();
        for (UUID tenantId : tenants) {
            if (!runOnce(tenantId, 1)) failed.add(tenantId);
        }
        for (UUID tenantId : failed) {
            if (!service.enabled()) return;
            if (runOnce(tenantId, 2)) {
                log.info("Main reconcile sweep: tenant {} succeeded on retry", tenantId);
            } else {
                alertIfRepeated(tenantId);
            }
        }
    }

    /** True when the run did not fail (completed, skipped, or nothing to run). */
    private boolean runOnce(UUID tenantId, int attempt) {
        try {
            return TenantContext.runAs(tenantId, () -> {
                UUID runId = service.runSweep(tenantId, attempt);
                return !service.runFailed(tenantId, runId);
            });
        } catch (Exception e) {
            log.warn("Main reconcile sweep failed for tenant {} (attempt {}): {}", tenantId, attempt, e.toString());
            return false;
        }
    }

    private void alertIfRepeated(UUID tenantId) {
        try {
            TenantContext.runAs(tenantId, () -> {
                MainReconcileService.SweepStreak streak = service.consecutiveFailedSweeps(tenantId);
                if (streak.failedInARow() != 2) return null;
                String shop = service.shopDomain(tenantId);
                String who = shop != null ? shop : tenantId.toString();
                String error = streak.lastError() == null ? "(no error recorded)" : streak.lastError();
                alerter.alert("Traced: Main reconcile sweep failed twice in a row — " + who,
                    "<p>The Main-location reconcile sweep failed for <b>" + html(who) + "</b> (tenant " + tenantId +
                    ") in two sweeps in a row, each after one retry. Shopify's Main stock for this store is not being " +
                    "checked until a sweep succeeds.</p><p>Last error:</p><pre>" + html(error) + "</pre>");
                return null;
            });
        } catch (Exception e) {
            log.warn("Main reconcile sweep: failure-streak check failed for tenant {}: {}", tenantId, e.toString());
        }
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Retention: runs + diffs older than retention-days, per tenant under RLS (runs even while disabled). */
    void purgeOld() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM main_reconcile_runs WHERE started_at < now() - make_interval(days => ?)",
            UUID.class, service.retentionDays());
        for (UUID tenantId : tenants) {
            try {
                int n = TenantContext.runAs(tenantId, () -> service.purgeOld(tenantId));
                if (n > 0) log.info("Main reconcile retention: deleted {} runs for tenant {}", n, tenantId);
            } catch (Exception e) {
                log.warn("Main reconcile retention failed for tenant {}: {}", tenantId, e.toString());
            }
        }
    }
}
