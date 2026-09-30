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
 * Failed-increment recovery (Part D) — every 10 minutes, ShopifyInventoryService.retryDueIncrements
 * for each tenant with a due claim. The tenant list is a cross-tenant read of tenant ids only, on the
 * owner pool (same pattern as ReturnPickupBookingSweepJob); every per-tenant read and write then runs
 * in TenantContext.runAs under RLS. Same background-server gate as the other recurring jobs (tests
 * call retryDueIncrements directly).
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class IncrementRetryJob {

    private static final Logger log = LoggerFactory.getLogger(IncrementRetryJob.class);

    private final ShopifyInventoryService inventory;
    private final JdbcTemplate ownerJdbc;

    public IncrementRetryJob(ShopifyInventoryService inventory, @FlywayDataSource DataSource ownerDs) {
        this.inventory = inventory;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "shopify-increment-retry", cron = "*/10 * * * *")
    @Job(name = "Shopify increment retry")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT sia.tenant_id FROM shopify_inventory_adjustments sia WHERE " + IncrementRecoveryRules.DUE_SQL,
            UUID.class);
        for (UUID tenantId : tenants) {
            try {
                ShopifyInventoryService.RetryResult r =
                    TenantContext.runAs(tenantId, () -> inventory.retryDueIncrements());
                log.info("Increment retry tenant={} due={} attempted={} applied={} ambiguousExpired={} blockedBy={}",
                    tenantId, r.due(), r.attempted(), r.applied(), r.skippedAmbiguousExpired(), r.blockedBy());
            } catch (Exception e) {
                log.warn("Increment retry failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }
}
