package com.traceability.analytics;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * Analytics slice 10 — the daily Shopify cost + stock READ pass (05:15 Cairo) for every tenant with a
 * connected store, and the owner's one-tenant "run now" (enqueued by AnalyticsInventorySyncController). Tenants are listed on the owner pool (as ShopifyReconcileJob does);
 * each pass runs as app_user under TenantContext.runAs. A pass never throws
 * (AnalyticsInventorySyncService.run); no JobRunr retries — the next pass is the retry.
 * Kill switch: analytics.inventory-sync.enabled.
 */
@Service
public class AnalyticsInventorySyncJob {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsInventorySyncJob.class);

    private final JdbcTemplate ownerJdbc;
    private final AnalyticsInventorySyncService sync;
    private final boolean enabled;

    public AnalyticsInventorySyncJob(@FlywayDataSource DataSource ownerDs, AnalyticsInventorySyncService sync,
                                     @Value("${analytics.inventory-sync.enabled:true}") boolean enabled) {
        this.ownerJdbc = new JdbcTemplate(ownerDs);
        this.sync = sync;
        this.enabled = enabled;
    }

    @Recurring(id = "analytics-inventory-sync", cron = "15 5 * * *", zoneId = "Africa/Cairo")
    @Job(name = "Analytics inventory sync (daily)", retries = 0)
    public void daily() {
        if (!enabled) return;
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM stores WHERE status = 'connected' AND import_status = 'completed'", UUID.class);
        for (UUID t : tenants) {
            try {
                TenantContext.runAs(t, () -> sync.run("daily"));
            } catch (RuntimeException e) {
                log.warn("Inventory sync (daily): tenant {} — {}", t, e.getMessage());
            }
        }
    }

    @Job(name = "Analytics inventory sync — tenant %0", retries = 0)
    public void manual(UUID tenantId) {
        TenantContext.runAs(tenantId, () -> sync.run("manual"));
    }
}
