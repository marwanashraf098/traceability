package com.traceability.integrations.shopify;

import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * One-time, tenant-scoped backfill: fetches the Shopify featured-image URL for products
 * that were synced before image capture existed and fills in products.image_url.
 *
 * NOT a general re-sync: writes ONLY image_url, via a narrow dedicated UPDATE keyed on
 * (tenant_id, store_id, external_id) — deliberately never reuses ShopifySyncService's
 * upsertProduct()/UPSERT_PRODUCT, so a page fetch here can't resurrect stale title/status/raw.
 * Going forward, new/updated products capture the image at the two live sync points
 * (GraphQL catalog import, products/create|update webhook — see ShopifySyncService), so this
 * job is a one-shot for already-synced products, not a recurring job and not run at connect.
 *
 * Idempotent: re-running produces the same end state — safe to re-trigger via
 * POST /api/v1/shopify/backfill-product-images.
 *
 * TenantContext: the ENTIRE job runs inside TenantContext.runAs(tenantId) at the run() level —
 * never call runAs inside the per-store or per-page loop.
 *
 * Rate limits: reuses the same shopifyGateway.fetchProductsPage()/PRODUCTS_QUERY as the live
 * catalog import, so it inherits the identical Resilience4j retry + THROTTLED backoff already
 * built into ShopifyHttpGateway.executeGraphQL() — no separate rate-limit handling needed here.
 */
@Component
public class ProductImageBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(ProductImageBackfillJob.class);

    private static final String UPDATE_PRODUCT_IMAGE = """
            UPDATE products SET image_url = ?
            WHERE tenant_id = ? AND store_id = ? AND external_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopifyGateway;
    private final ShopifyTokenProvider tokenProvider;

    public ProductImageBackfillJob(JdbcTemplate jdbc,
                                    PlatformTransactionManager txm,
                                    ShopifyGateway shopifyGateway,
                                    ShopifyTokenProvider tokenProvider) {
        this.jdbc           = jdbc;
        this.tx             = new TransactionTemplate(txm);
        this.shopifyGateway = shopifyGateway;
        this.tokenProvider  = tokenProvider;
    }

    @Job(name = "Product image backfill — tenant %0")
    public void run(UUID tenantId) {
        TenantContext.runAs(tenantId, (Runnable) () -> {
            List<UUID> storeIds = tx.execute(s -> jdbc.queryForList(
                    "SELECT id FROM stores WHERE tenant_id = ? AND status = 'connected'",
                    UUID.class, tenantId));

            int updated = 0;
            for (UUID storeId : storeIds) {
                updated += backfillStore(tenantId, storeId);
            }
            log.info("Product image backfill complete for tenant {}: {} product(s) updated across {} store(s)",
                tenantId, updated, storeIds.size());
        });
    }

    private int backfillStore(UUID tenantId, UUID storeId) {
        String shopDomain;
        String rawToken;
        try {
            String[] info = tx.execute(s -> jdbc.query(
                    "SELECT shop_domain FROM stores WHERE id = ?",
                    rs -> rs.next() ? new String[]{rs.getString(1)} : null, storeId));
            if (info == null) return 0;
            shopDomain = info[0];
            rawToken   = tokenProvider.getValidToken(storeId);
        } catch (Exception e) {
            log.warn("Product image backfill: could not get a valid token for store {} — skipping", storeId, e);
            return 0;
        }

        int updated = 0;
        String cursor = null;
        do {
            ShopifyGateway.ProductPage page;
            try {
                page = shopifyGateway.fetchProductsPage(shopDomain, rawToken, cursor);
            } catch (Exception e) {
                log.warn("Product image backfill: fetch failed for store {} at cursor {} — stopping early",
                    storeId, cursor, e);
                break;
            }

            for (ShopifyGateway.Product p : page.products()) {
                if (p.imageUrl() == null) continue;
                updated += tx.execute(s -> jdbc.update(
                        UPDATE_PRODUCT_IMAGE, p.imageUrl(), tenantId, storeId, p.gid()));
            }
            cursor = page.hasNextPage() ? page.endCursor() : null;
        } while (cursor != null);

        return updated;
    }
}
