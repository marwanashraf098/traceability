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
 * Issue 2 — every 10 minutes, TransferShopifySync.sweep for each tenant with an open transfer
 * Shopify claim (queued — repair rows or a crash before the async push; failed — a definitive
 * rejection, re-sent up to MAX_ATTEMPTS; pending for 15+ min → failed_ambiguous, never re-sent).
 * The tenant list is a cross-tenant read of tenant ids only, on the owner pool (IncrementRetryJob's
 * pattern); every per-tenant read and write runs in TenantContext.runAs under RLS.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class TransferShopifySweepJob {

    private static final Logger log = LoggerFactory.getLogger(TransferShopifySweepJob.class);

    private final TransferShopifySync sync;
    private final JdbcTemplate ownerJdbc;

    public TransferShopifySweepJob(TransferShopifySync sync, @FlywayDataSource DataSource ownerDs) {
        this.sync = sync;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "transfer-shopify-sweep", cron = "*/10 * * * *")
    @Job(name = "Transfer Shopify sweep")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM transfer_shopify_syncs WHERE status IN ('queued', 'pending', 'failed')",
            UUID.class);
        for (UUID tenantId : tenants) {
            try {
                int sent = TenantContext.runAs(tenantId, () -> sync.sweep(tenantId));
                if (sent > 0) log.info("Transfer Shopify sweep tenant={} attempted={}", tenantId, sent);
            } catch (Exception e) {
                log.warn("Transfer Shopify sweep failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }
}
