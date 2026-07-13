package com.traceability.integrations.shopify;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-3.4: Reconciliation poll — runs every 15 minutes to catch Shopify orders whose
 * webhook was missed. This is a GAP-FILLER, not a re-sync:
 * <ul>
 *   <li>Fetches orders created in the last 30 minutes (slightly wider than the poll interval
 *       to absorb clock drift and startup jitter).</li>
 *   <li>For each Shopify order, checks local existence by (store_id, external_id).</li>
 *   <li>Missing orders are ingested via the same SQL path as the initial full import.</li>
 *   <li>Existing orders are skipped entirely — status, on_hold, and fulfillment state
 *       are never touched.</li>
 * </ul>
 * Disconnected stores and stores mid-initial-import are excluded by the store query.
 */
@Service
@ConditionalOnProperty(name = "org.jobrunr.background-job-server.enabled", havingValue = "true")
public class ShopifyReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyReconcileJob.class);

    private static final int LOOKBACK_MINUTES = 30;

    private static final String LIST_CONNECTED_STORES =
        "SELECT id, tenant_id, shop_domain FROM stores " +
        "WHERE status = 'connected' AND import_status = 'completed'";

    private static final String ORDER_EXISTS =
        "SELECT EXISTS(SELECT 1 FROM orders WHERE tenant_id = ? AND store_id = ? AND external_id = ?)";

    private final JdbcTemplate ownerJdbc;
    private final JdbcTemplate jdbc;
    private final ShopifyGateway shopifyGateway;
    private final ShopifyTokenProvider tokenProvider;
    private final ShopifySyncService syncService;
    private final TransactionTemplate tx;

    public ShopifyReconcileJob(
            @FlywayDataSource DataSource ownerDs,
            JdbcTemplate jdbc,
            ShopifyGateway shopifyGateway,
            ShopifyTokenProvider tokenProvider,
            ShopifySyncService syncService,
            PlatformTransactionManager txm) {
        this.ownerJdbc     = new JdbcTemplate(ownerDs);
        this.jdbc          = jdbc;
        this.shopifyGateway = shopifyGateway;
        this.tokenProvider  = tokenProvider;
        this.syncService    = syncService;
        this.tx             = new TransactionTemplate(txm);
    }

    @Recurring(id = "shopify-reconcile", cron = "*/15 * * * *")
    @Job(name = "Shopify reconciliation poll")
    public void reconcile() {
        List<Map<String, Object>> stores = ownerJdbc.queryForList(LIST_CONNECTED_STORES);
        log.debug("Reconcile: checking {} connected store(s)", stores.size());

        for (Map<String, Object> row : stores) {
            UUID storeId   = (UUID) row.get("id");
            UUID tenantId  = (UUID) row.get("tenant_id");
            String domain  = (String) row.get("shop_domain");
            try {
                reconcileStore(storeId, tenantId, domain);
            } catch (Exception e) {
                log.warn("Reconcile failed for store {} ({}): {}", storeId, domain, e.getMessage());
            }
        }
    }

    private void reconcileStore(UUID storeId, UUID tenantId, String shopDomain) {
        // TenantContext must be set BEFORE getValidToken — ShopifyTokenProvider uses the
        // app_user datasource (RLS-gated). Without the GUC, the store SELECT returns zero
        // rows and getValidToken throws "Store not found or not visible under current tenant".
        TenantContext.set(tenantId);
        try {
            String rawToken    = tokenProvider.getValidToken(storeId);
            String createdAfter = Instant.now().minus(LOOKBACK_MINUTES, ChronoUnit.MINUTES).toString();
            String cursor = null;
            int ingested = 0;
            do {
                ShopifyGateway.OrderPage page =
                    shopifyGateway.fetchOrdersPage(shopDomain, rawToken, cursor, createdAfter);

                for (ShopifyGateway.Order order : page.orders()) {
                    Boolean exists = tx.execute(s ->
                        jdbc.queryForObject(ORDER_EXISTS, Boolean.class,
                            tenantId, storeId, order.gid()));

                    if (!Boolean.TRUE.equals(exists)) {
                        syncService.ingestMissingOrder(storeId, tenantId, order);
                        ingested++;
                        log.debug("Reconcile: ingested missing order {} for store {}", order.gid(), storeId);
                    }
                }
                cursor = page.hasNextPage() ? page.endCursor() : null;
            } while (cursor != null);

            if (ingested > 0) {
                log.info("Reconcile: ingested {} missing order(s) for store {} ({})", ingested, storeId, shopDomain);
            }
        } finally {
            TenantContext.clear();
        }
    }
}
