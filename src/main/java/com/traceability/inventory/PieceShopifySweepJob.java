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
 * Piece sync (2026-10-10, D7) — every 10 minutes, ShopifyInventoryService.sweepPieceClaims for each
 * tenant with an open piece claim (queued — a crash before the after-commit push; failed — a definite
 * rejection, re-sent up to PieceShopifyRules.MAX_ATTEMPTS; pending for 15+ min → failed_ambiguous,
 * never re-sent). Legacy claims (before piece_sync_cutoff()) are never picked up. The tenant list is a
 * cross-tenant read of tenant ids only, on the owner pool (TransferShopifySweepJob's pattern); every
 * per-tenant read and write runs in TenantContext.runAs under RLS.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PieceShopifySweepJob {

    private static final Logger log = LoggerFactory.getLogger(PieceShopifySweepJob.class);

    private final ShopifyInventoryService inventory;
    private final JdbcTemplate ownerJdbc;

    public PieceShopifySweepJob(ShopifyInventoryService inventory, @FlywayDataSource DataSource ownerDs) {
        this.inventory = inventory;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "piece-shopify-sweep", cron = "*/10 * * * *")
    @Job(name = "Piece Shopify sweep")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT DISTINCT tenant_id FROM shopify_inventory_adjustments sia " +
            "WHERE sia.status IN ('queued', 'pending', 'failed') " +
            "  AND sia.trigger_type IN " + PieceShopifyRules.PIECE_TRIGGERS_SQL +
            "  AND " + PieceShopifyRules.LIVE_SQL,
            UUID.class);
        for (UUID tenantId : tenants) {
            try {
                int sent = TenantContext.runAs(tenantId, () -> inventory.sweepPieceClaims(tenantId));
                if (sent > 0) log.info("Piece Shopify sweep tenant={} attempted={}", tenantId, sent);
            } catch (Exception e) {
                log.warn("Piece Shopify sweep failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }
}
