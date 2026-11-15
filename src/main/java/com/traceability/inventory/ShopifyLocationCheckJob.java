package com.traceability.inventory;

import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * The periodic check of the Traced location in Shopify (2026-10-30) — ShopifyLocationCheckService per tenant:
 *   - every 10 minutes, tenants whose Traced location is inactive / deleted (to notice the reactivation and release
 *     the held updates promptly);
 *   - hourly, every tenant with a linked main warehouse and a connected store (to notice a deactivation even when no
 *     update failed on it yet) — plus, since L1b-1, a read of the store's shipping model and of fulfils online orders
 *     (TracedLocationOnlineService.refreshQuietly; read only, never a delivery-profile call).
 * The tenant lists are cross-tenant reads of tenant ids only, on the owner pool (PieceShopifySweepJob's pattern);
 * every per-tenant read and write runs in TenantContext.runAs under RLS.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ShopifyLocationCheckJob {

    private final ShopifyLocationCheckService checks;
    private final TracedLocationOnlineService online;
    private final JdbcTemplate ownerJdbc;

    public ShopifyLocationCheckJob(ShopifyLocationCheckService checks, TracedLocationOnlineService online,
                                   @FlywayDataSource DataSource ownerDs) {
        this.checks = checks;
        this.online = online;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "shopify-location-check-held", cron = "*/10 * * * *", zoneId = "UTC")
    @Job(name = "Traced location in Shopify — check held tenants")
    public void checkHeld() {
        run("SELECT DISTINCT tenant_id FROM locations WHERE is_fulfillment = true AND shopify_sync_status = 'linked' " +
            "AND shopify_location_state <> 'active'");
    }

    @Recurring(id = "shopify-location-check-all", cron = "17 * * * *", zoneId = "UTC")
    @Job(name = "Traced location in Shopify — hourly check")
    public void checkAll() {
        run("SELECT DISTINCT l.tenant_id FROM locations l WHERE l.is_fulfillment = true AND l.shopify_sync_status = 'linked' " +
            "AND l.shopify_location_id IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM stores s WHERE s.tenant_id = l.tenant_id AND s.status = 'connected')", true);
    }

    private void run(String tenantsSql) {
        run(tenantsSql, false);
    }

    /** {@code shipping}: L1b-1 — the hourly pass also re-reads the shipping model + fulfils online (read only). */
    private void run(String tenantsSql, boolean shipping) {
        List<UUID> tenants = ownerJdbc.queryForList(tenantsSql, UUID.class);
        for (UUID tenantId : tenants) {
            checks.checkQuietly(tenantId);
            if (shipping) online.refreshQuietly(tenantId);
        }
    }
}
