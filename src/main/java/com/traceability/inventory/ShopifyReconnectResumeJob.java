package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.jobrunr.scheduling.JobScheduler;
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
 * Shopify reconnect (2026-10-28) — every 5 minutes, sends what was held while a tenant's updates were held
 * (IncrementRecoveryRules.holdReason: the store needed a reconnect, or — since 2026-10-30 — the Traced location was
 * inactive / deleted in Shopify), once the hold is gone:
 *   - stock-take write-off pushes held as 'failed' + payload.heldForReconnect → released and enqueued once each
 *     (StockTakeShopifyPushJob.releaseHeld, then the normal push);
 *   - exchange_dispatch claims held as 'queued' → sent once each (ShopifyInventoryService.resumeHeldExchangeDispatches).
 * Increment retries, piece claims and transfers need nothing here: their own sweeps already wait on the hold
 * and pick the claims up after it. A tenant still disconnected is skipped (both calls check it themselves).
 * The tenant list is a cross-tenant read of tenant ids only, on the owner pool (PieceShopifySweepJob's pattern);
 * every per-tenant read and write runs in TenantContext.runAs under RLS.
 */
@Component
@ConditionalOnProperty(
    name = "org.jobrunr.background-job-server.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ShopifyReconnectResumeJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReconnectResumeJob.class);

    private final ShopifyInventoryService inventory;
    private final StockTakeShopifyPushJob stockTakePush;
    private final JobScheduler jobScheduler;
    private final JdbcTemplate ownerJdbc;

    public ShopifyReconnectResumeJob(ShopifyInventoryService inventory, StockTakeShopifyPushJob stockTakePush,
                                     JobScheduler jobScheduler, @FlywayDataSource DataSource ownerDs) {
        this.inventory = inventory;
        this.stockTakePush = stockTakePush;
        this.jobScheduler = jobScheduler;
        this.ownerJdbc = new JdbcTemplate(ownerDs);
    }

    @Recurring(id = "shopify-reconnect-resume", cron = "*/5 * * * *")
    @Job(name = "Shopify reconnect — send held updates")
    public void run() {
        List<UUID> tenants = ownerJdbc.queryForList(
            "SELECT tenant_id FROM stock_take_shopify_syncs " +
            "WHERE status = 'failed' AND jsonb_exists(payload, 'heldForReconnect') " +
            "UNION SELECT tenant_id FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'exchange_dispatch' AND status = 'queued'",
            UUID.class);
        for (UUID tenantId : tenants) {
            try {
                resume(tenantId);
            } catch (Exception e) {
                log.warn("Shopify reconnect resume failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    /** One tenant's pass (tests call it directly). */
    public void resume(UUID tenantId) {
        List<UUID> sessions = TenantContext.runAs(tenantId, () -> stockTakePush.releaseHeld(tenantId));
        for (UUID sessionId : sessions) {
            jobScheduler.enqueue(() -> stockTakePush.push(sessionId, tenantId));
        }
        int dispatched = TenantContext.runAs(tenantId, () -> inventory.resumeHeldExchangeDispatches(tenantId));
        if (!sessions.isEmpty() || dispatched > 0) {
            log.info("Shopify reconnect resume tenant={} stockTakePushes={} exchangeDispatches={}",
                tenantId, sessions.size(), dispatched);
        }
    }
}
