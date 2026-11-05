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
import java.util.List;
import java.util.UUID;

/**
 * Main reconcile (V201, R1, report-only) — two recurring passes, both no-ops unless traced.main-reconcile.enabled:
 *   queue (every minute): tenants with a variant queued and untouched for debounce-seconds → one per-variant run each;
 *   sweep (traced.main-reconcile.sweep-cron, default every 4 h): every tenant with a store and a main warehouse → a
 *          full run over every level at its Shopify Main location plus every variant Traced holds there; first, the
 *          retention purge (runs + diffs older than retention-days; Part 1 claims are kept).
 * Tenant lists are a cross-tenant read of tenant ids only, on the owner pool (PieceShopifySweepJob's pattern); every
 * run is under TenantContext.runAs + RLS. Report-only: nothing here writes to Shopify.
 */
@Component
@ConditionalOnProperty(name = "org.jobrunr.background-job-server.enabled", havingValue = "true", matchIfMissing = true)
public class MainReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(MainReconcileJob.class);

    private final MainReconcileService service;
    private final JdbcTemplate ownerJdbc;

    public MainReconcileJob(MainReconcileService service, @FlywayDataSource DataSource ownerDs) {
        this.service   = service;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
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
        for (UUID tenantId : tenants) {
            try {
                TenantContext.runAs(tenantId, () -> service.runSweep(tenantId));
            } catch (Exception e) {
                log.warn("Main reconcile sweep failed for tenant {}: {}", tenantId, e.toString());
            }
        }
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
