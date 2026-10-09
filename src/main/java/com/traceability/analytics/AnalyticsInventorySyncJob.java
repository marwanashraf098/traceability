package com.traceability.analytics;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Analytics slice 10 — the daily Shopify cost + stock READ pass (05:15 Cairo) for every tenant with a
 * connected store, and the owner's one-tenant "run now" (enqueued by AnalyticsInventorySyncController). Tenants are listed on the owner pool (as ShopifyReconcileJob does);
 * each pass runs as app_user under TenantContext.runAs. A pass never throws
 * (AnalyticsInventorySyncService.run); no JobRunr retries — the next pass is the retry.
 * Kill switch: analytics.inventory-sync.enabled.
 *
 * FIRST PASS after startup: when some connected store's tenant has never finished a pass, startup
 * schedules ONE JobRunr job ~10 minutes out (analytics.inventory-sync.startup-delay-min) that runs
 * those tenants one at a time — every Shopify call goes through the gateway's GraphQL transport, which
 * honours Shopify's cost bucket (THROTTLED back-off, slow-down when low). Later restarts find nothing
 * new and schedule nothing; the daily pass covers everyone. The per-store INFO line is the pass's own
 * (AnalyticsInventorySyncService.run): cost / stock status, variants read, costed, non-EGP, duration.
 */
@Service
public class AnalyticsInventorySyncJob {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsInventorySyncJob.class);

    private final JdbcTemplate ownerJdbc;
    private final AnalyticsInventorySyncService sync;
    private final boolean enabled;
    private final JobScheduler jobScheduler;
    private final long startupDelayMin;

    /** Tenants with a connected, imported store — every pass covers these. */
    static final String CONNECTED_SQL =
        "SELECT DISTINCT tenant_id FROM stores WHERE status = 'connected' AND import_status = 'completed'";

    /** Of those, the ones that never finished a pass (the startup "first pass"). */
    static final String NEVER_SYNCED_SQL =
        "SELECT DISTINCT s.tenant_id FROM stores s WHERE s.status = 'connected' AND s.import_status = 'completed' " +
        "AND NOT EXISTS (SELECT 1 FROM analytics_inventory_sync a WHERE a.tenant_id = s.tenant_id AND a.finished_at IS NOT NULL)";

    public AnalyticsInventorySyncJob(@FlywayDataSource DataSource ownerDs, AnalyticsInventorySyncService sync,
                                     JobScheduler jobScheduler,
                                     @Value("${analytics.inventory-sync.enabled:true}") boolean enabled,
                                     @Value("${analytics.inventory-sync.startup-delay-min:10}") long startupDelayMin) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.sync = sync;
        this.jobScheduler = jobScheduler;
        this.enabled = enabled;
        this.startupDelayMin = startupDelayMin;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) return;
        try {
            List<UUID> never = ownerJdbc.queryForList(NEVER_SYNCED_SQL, UUID.class);
            if (!never.isEmpty()) {
                jobScheduler.<AnalyticsInventorySyncJob>schedule(Instant.now().plus(Duration.ofMinutes(startupDelayMin)),
                    job -> job.firstPass());
                log.info("Inventory sync: {} tenant(s) never synced — first pass scheduled in {} min", never.size(), startupDelayMin);
            }
        } catch (Exception e) {
            log.error("Could not check or schedule the first inventory sync at startup — continuing", e);
        }
    }

    @Job(name = "Analytics inventory sync (first pass after startup)", retries = 0)
    public void firstPass() {
        if (!enabled) return;
        runEach(ownerJdbc.queryForList(NEVER_SYNCED_SQL, UUID.class), "startup");
    }

    private void runEach(List<UUID> tenants, String trigger) {
        for (UUID t : tenants) {
            try {
                TenantContext.runAs(t, () -> sync.run(trigger));
            } catch (RuntimeException e) {
                log.warn("Inventory sync ({}): tenant {} — {}", trigger, t, e.getMessage());
            }
        }
    }

    @Recurring(id = "analytics-inventory-sync", cron = "15 5 * * *", zoneId = "Africa/Cairo")
    @Job(name = "Analytics inventory sync (daily)", retries = 0)
    public void daily() {
        if (!enabled) return;
        runEach(ownerJdbc.queryForList(CONNECTED_SQL, UUID.class), "daily");
    }

    @Job(name = "Analytics inventory sync — tenant %0", retries = 0)
    public void manual(UUID tenantId) {
        TenantContext.runAs(tenantId, () -> sync.run("manual"));
    }
}
