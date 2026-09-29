package com.traceability.integrations.shopify;

import com.traceability.inventory.ShopifyCatalogActivationService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-time catalog re-import for stores connected before every Shopify product status was
 * imported (2026-09-29). Serial, one store at a time.
 *
 * The eligible list (connected, catalog_backfilled_at IS NULL) is ONE read on the owner pool —
 * the same cross-tenant store listing ShopifyReconcileJob does every 15 minutes, no new
 * hatch — and the connection is released before any Shopify call. Everything per store runs
 * inside TenantContext.runAs(tenantId) on the app_user pool (RLS):
 *   a. ShopifySyncService.importCatalogOnly() — products + variants only; no location setup,
 *      no on-hand seed, no order import;
 *   b. ShopifyCatalogActivationService.activateAll() — every variant of the store, idempotent
 *      (also covers variants that came in by webhook and were never activated);
 *   c. catalog_backfilled_at = now(), only after a and b both succeeded.
 * It never writes import_status, last_sync_at or stores.status — nothing the merchant sees.
 *
 * One store failing never stops the rest; if any failed, the run throws at the end so JobRunr
 * retries with its normal backoff, and the marker skips the stores already done.
 */
@Component
public class CatalogBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogBackfillJob.class);

    /** Deterministic JobRunr id — repeated application starts collapse into this one job. */
    public static final UUID JOB_ID = UUID.nameUUIDFromBytes(
        "traced:catalog-backfill:v1".getBytes(StandardCharsets.UTF_8));

    static final String ELIGIBLE_STORES =
        "SELECT id, tenant_id, shop_domain FROM stores " +
        "WHERE status = 'connected' AND catalog_backfilled_at IS NULL ORDER BY tenant_id, id";

    private final JdbcTemplate ownerJdbc;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifySyncService syncService;
    private final ShopifyTokenProvider tokenProvider;
    private final ShopifyCatalogActivationService activationService;
    private final boolean enabled;

    public CatalogBackfillJob(@FlywayDataSource DataSource ownerDs,
                              JdbcTemplate jdbc,
                              PlatformTransactionManager txm,
                              ShopifySyncService syncService,
                              ShopifyTokenProvider tokenProvider,
                              ShopifyCatalogActivationService activationService,
                              @Value("${traced.catalog-backfill.enabled:true}") boolean enabled) {
        this.ownerJdbc         = new JdbcTemplate(ownerDs);
        this.jdbc              = jdbc;
        this.tx                = new TransactionTemplate(txm);
        this.syncService       = syncService;
        this.tokenProvider     = tokenProvider;
        this.activationService = activationService;
        this.enabled           = enabled;
    }

    public record Result(int stores, int succeeded, List<UUID> failed) {}

    @Job(name = "Shopify catalog backfill (all product statuses)")
    public void run() {
        Result r = runOnce();
        if (!r.failed().isEmpty()) {
            throw new IllegalStateException("Catalog backfill failed for " + r.failed().size() + " of "
                + r.stores() + " store(s): " + r.failed() + " — JobRunr will retry; completed stores are skipped");
        }
    }

    /** One pass over the eligible stores. Package-visible for tests; run() is the JobRunr entry. */
    Result runOnce() {
        if (!enabled) {
            log.info("Catalog backfill: disabled (traced.catalog-backfill.enabled=false) — nothing done");
            return new Result(0, 0, List.of());
        }
        List<Map<String, Object>> stores = ownerJdbc.queryForList(ELIGIBLE_STORES);
        log.info("Catalog backfill: {} eligible store(s)", stores.size());

        int succeeded = 0;
        List<UUID> failed = new ArrayList<>();
        for (Map<String, Object> row : stores) {
            UUID storeId    = (UUID) row.get("id");
            UUID tenantId   = (UUID) row.get("tenant_id");
            String domain   = (String) row.get("shop_domain");
            try {
                TenantContext.runAs(tenantId, (Runnable) () -> backfillStore(storeId, tenantId, domain));
                succeeded++;
            } catch (Exception e) {
                failed.add(storeId);
                log.warn("Catalog backfill failed for store {} ({}), tenant {}: {}", storeId, domain, tenantId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
        log.info("Catalog backfill pass done: {} of {} store(s) succeeded, {} failed", succeeded, stores.size(), failed.size());
        return new Result(stores.size(), succeeded, failed);
    }

    private void backfillStore(UUID storeId, UUID tenantId, String shopDomain) {
        long[] before = counts(storeId);
        String token = tokenProvider.getValidToken(storeId);
        syncService.importCatalogOnly(storeId, tenantId, shopDomain, token);
        long[] after = counts(storeId);

        ShopifyCatalogActivationService.ActivationOutcome activation = activationService.activateAll();
        if (activation.failed() > 0) {
            throw new IllegalStateException("activation failed for " + activation.failed() + " of "
                + activation.total() + " variant(s): " + activation.failures());
        }

        tx.execute(s -> jdbc.update(
            "UPDATE stores SET catalog_backfilled_at = now() WHERE id = ? AND tenant_id = ?", storeId, tenantId));
        log.info("Catalog backfill store {} ({}): products {} → {}, variants {} → {}, variants activated {}",
            storeId, shopDomain, before[0], after[0], before[1], after[1], activation.succeeded());
    }

    private long[] counts(UUID storeId) {
        return tx.execute(s -> jdbc.query(
            "SELECT (SELECT COUNT(*) FROM products WHERE store_id = ?) AS products, " +
            "       (SELECT COUNT(*) FROM variants v JOIN products p ON p.id = v.product_id WHERE p.store_id = ?) AS variants",
            rs -> { rs.next(); return new long[]{rs.getLong("products"), rs.getLong("variants")}; },
            storeId, storeId));
    }
}
