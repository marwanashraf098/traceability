package com.traceability.portal;

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
 * Shopify-native returns S5a — every 2 minutes (UTC), {@link ShopifyReturnSyncService#sweepTenant} for each LISTED
 * tenant that switched "Create returns in Shopify" on. The tenant list is a cross-tenant read of tenant ids only on
 * the owner pool (like ReturnPickupBookingSweepJob); all per-tenant work runs under RLS. Without a listed tenant it
 * reads nothing.
 */
@Component
@ConditionalOnProperty(name = "org.jobrunr.background-job-server.enabled", havingValue = "true", matchIfMissing = true)
public class ShopifyReturnSyncSweepJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReturnSyncSweepJob.class);

    private final ShopifyReturnSyncService service;
    private final ShopifyReturnSyncRollout rollout;
    private final JdbcTemplate ownerJdbc;

    public ShopifyReturnSyncSweepJob(ShopifyReturnSyncService service, ShopifyReturnSyncRollout rollout,
                                     @FlywayDataSource DataSource ownerDs) {
        this.service = service;
        this.rollout = rollout;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "shopify-return-sync-sweep", cron = "*/2 * * * *", zoneId = "UTC")
    @Job(name = "Shopify return sync sweep", retries = 0)
    public void run() {
        if (!rollout.anyListed()) return;
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT id FROM tenants WHERE shopify_returns_sync_enabled", UUID.class);
        for (UUID tenantId : tenants) {
            if (!rollout.listed(tenantId)) continue;
            try {
                int n = service.sweepTenant(tenantId);
                if (n > 0) log.info("Shopify return sync sweep tenant={} requests={}", tenantId, n);
            } catch (Exception e) {
                log.warn("Shopify return sync sweep failed for tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
    }
}
