package com.traceability.integrations.shopify;

import com.traceability.inventory.IncrementRecoveryRules;
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
import java.time.LocalDate;
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
 *   b. ShopifyCatalogActivationService.activateAll() — every variant of an ACTIVE product (draft / archived are activated lazily before their first increment), idempotent
 *      (also covers variants that came in by webhook and were never activated);
 *   c. catalog_backfilled_at = now() once a succeeded and b didn't fail at STORE level.
 * Activation failing for a SETUP reason (Traced location not linked, or every variant rejected while
 * the token lacks inventory scope) → marker SET, one WARN naming the fix, store NOT failed (the merchant
 * fixes it; activation follows). Any other store-level activation failure (every variant rejected with
 * the setup in place, an unreachable shop) → no marker, store failed. A token/reauth failure fails the
 * import itself → no marker, store failed.
 * Only some variants rejected → those ids and reasons are logged, the marker IS set and the
 * store counts as done (one variant Shopify always rejects must not keep the job retrying).
 * It never writes import_status, last_sync_at or stores.status — nothing the merchant sees.
 *
 * One store failing never stops the rest; if any failed, the run throws at the end so JobRunr
 * retries with its normal backoff, and the marker skips the stores already done.
 */
@Component
public class CatalogBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(CatalogBackfillJob.class);

    /**
     * Deterministic JobRunr id for one Cairo calendar day — starts on the same day collapse into
     * one job; the next day's first start enqueues a new one if a store is still unmarked.
     */
    public static UUID jobIdFor(LocalDate cairoDay) {
        return UUID.nameUUIDFromBytes(("catalog-backfill-" + cairoDay).getBytes(StandardCharsets.UTF_8));
    }

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

        // Activation failing for a SETUP reason (Traced location not linked, token without inventory
        // scope) is not the backfill's problem: the catalog IS imported, activation happens once the
        // merchant fixes the setup (the next import, or lazily before an increment), and the
        // inventory_increment_sync_failed exception already names the fix. Marker set, WARN once, not
        // failed — otherwise the job retries these stores forever. A token/reauth failure (the import
        // above throws) and every variant rejected with the setup in place stay failures.
        ShopifyCatalogActivationService.ActivationOutcome activation;
        try {
            activation = activationService.activateAll();
        } catch (RuntimeException e) {
            IncrementRecoveryRules.SetupProblem problem = setupProblem(tenantId);
            if (problem == null) throw e;
            markDoneWithSetupProblem(storeId, tenantId, shopDomain, before, after, problem, e.getMessage());
            return;
        }
        if (activation.total() > 0 && activation.failed() == activation.total()) {
            IncrementRecoveryRules.SetupProblem problem = setupProblem(tenantId);
            if (problem != null) {
                markDoneWithSetupProblem(storeId, tenantId, shopDomain, before, after, problem,
                    "every variant rejected (" + activation.total() + ")");
                return;
            }
            throw new IllegalStateException("activation failed for every variant (" + activation.total()
                + ") — store-level: " + activation.failures());
        }
        if (activation.failed() > 0) {
            log.warn("Catalog backfill store {} ({}): {} of {} variant(s) rejected by Shopify — marker set anyway: {}",
                storeId, shopDomain, activation.failed(), activation.total(), activation.failures());
        }

        tx.execute(s -> jdbc.update(
            "UPDATE stores SET catalog_backfilled_at = now() WHERE id = ? AND tenant_id = ?", storeId, tenantId));
        log.info("Catalog backfill store {} ({}): products {} → {}, variants {} → {}, variants activated {}",
            storeId, shopDomain, before[0], after[0], before[1], after[1], activation.succeeded());
    }

    /** LOCATION_NOT_LINKED or MISSING_SCOPE only — the setup reasons a merchant fixes (a missing store
     *  is not one: the import above already proved the store is there). */
    private IncrementRecoveryRules.SetupProblem setupProblem(UUID tenantId) {
        IncrementRecoveryRules.SetupProblem p = tx.execute(s -> IncrementRecoveryRules.setupProblem(jdbc, tenantId));
        return p == IncrementRecoveryRules.SetupProblem.LOCATION_NOT_LINKED
            || p == IncrementRecoveryRules.SetupProblem.MISSING_SCOPE ? p : null;
    }

    private void markDoneWithSetupProblem(UUID storeId, UUID tenantId, String shopDomain, long[] before, long[] after,
                                          IncrementRecoveryRules.SetupProblem problem, String detail) {
        tx.execute(s -> jdbc.update(
            "UPDATE stores SET catalog_backfilled_at = now() WHERE id = ? AND tenant_id = ?", storeId, tenantId));
        log.warn("Catalog backfill store {} ({}): catalog imported (products {} → {}, variants {} → {}); activation " +
                "skipped — setup: {} ({}). Marker set; activation happens once the merchant fixes it: {}",
            storeId, shopDomain, before[0], after[0], before[1], after[1], problem, detail, problem.fixEn);
    }

    private long[] counts(UUID storeId) {
        return tx.execute(s -> jdbc.query(
            "SELECT (SELECT COUNT(*) FROM products WHERE store_id = ?) AS products, " +
            "       (SELECT COUNT(*) FROM variants v JOIN products p ON p.id = v.product_id WHERE p.store_id = ?) AS variants",
            rs -> { rs.next(); return new long[]{rs.getLong("products"), rs.getLong("variants")}; },
            storeId, storeId));
    }
}
