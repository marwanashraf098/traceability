package com.traceability.integrations.shopify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.ShopifyCatalogActivationService;
import com.traceability.inventory.ShopifyInventoryReconcileService;
import com.traceability.inventory.ShopifyLocationProvisioningService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.annotations.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Background job that imports a Shopify store's catalog and orders, and — as of the
 * FR-17 v2 go-live — also runs the full Traced-owned-location inventory sync bootstrap as
 * ONE automatic flow on every connect/reconnect, with no manual approval gate:
 *   Part A: ensure + link the Traced Main Warehouse (ShopifyLocationProvisioningService).
 *   Part B: inventoryActivate the catalog at that GID (ShopifyCatalogActivationService),
 *           run AFTER catalog import since it needs variants to already exist.
 *   Part C: seed on_hand — a positive-delta-only write from 0, computed live, skipping
 *           any variant already non-zero in Shopify (ShopifyInventoryReconcileService.apply(),
 *           actorUserId=null for this system-triggered call). Its own per-tenant
 *           pg_advisory_xact_lock and claim/guard logic are unchanged — this job doesn't add
 *           any new synchronization, it just calls the same guarded method automatically.
 * The manual endpoints on ShopifyInventorySyncController (activate / reconcile / reconcile/apply)
 * remain available as standalone tools (read-only report, or manual re-trigger after a
 * failure here) — this job's automatic calls don't replace them, just remove the need to use
 * them for the ordinary connect path.
 *
 * TenantContext: JobRunr workers do not go through the HTTP filter chain.
 * TenantContext.runAs(tenantId, ...) is called explicitly so TenantAwareDataSource
 * fires SET LOCAL app.current_tenant at every transaction start, enforcing RLS.
 *
 * Failure handling: exceptions are caught, import_status is set to 'failed', and
 * the exception is NOT rethrown. JobRunr would otherwise retry indefinitely on
 * generic failures (bad data, permanent Shopify error). Transient network errors
 * inside the gateway are handled by Resilience4j retry before reaching this layer.
 * Parts A/B/C failures are caught independently and are non-fatal to the import itself —
 * each is retried on the next reconnect/import since each is independently idempotent.
 *
 * Idempotency: ShopifySyncService uses ON CONFLICT DO UPDATE throughout, so
 * re-running the job is safe and produces the same result.
 */
@Component
public class ShopifyImportJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyImportJob.class);

    private final JdbcTemplate jdbc;
    private final ShopifySyncService syncService;
    private final ShopifyTokenProvider tokenProvider;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final ShopifyLocationProvisioningService locationProvisioningService;
    private final ShopifyCatalogActivationService activationService;
    private final ShopifyInventoryReconcileService reconcileService;

    public ShopifyImportJob(JdbcTemplate jdbc,
                             ShopifySyncService syncService,
                             ShopifyTokenProvider tokenProvider,
                             ObjectMapper mapper,
                             PlatformTransactionManager txm,
                             ShopifyLocationProvisioningService locationProvisioningService,
                             ShopifyCatalogActivationService activationService,
                             ShopifyInventoryReconcileService reconcileService) {
        this.jdbc          = jdbc;
        this.syncService   = syncService;
        this.tokenProvider = tokenProvider;
        this.mapper        = mapper;
        this.tx            = new TransactionTemplate(txm);
        this.locationProvisioningService = locationProvisioningService;
        this.activationService = activationService;
        this.reconcileService  = reconcileService;
    }

    @Job(name = "Shopify import — store %0")
    public void run(UUID storeId, UUID tenantId) {
        TenantContext.runAs(tenantId, (Runnable) () -> {
            log.info("Starting Shopify import for store {}, tenant {}", storeId, tenantId);
            updateStatus(storeId, "importing", null);

            try {
                // Load store metadata. Token decryption goes through tokenProvider (below)
                // which handles expiry, refresh, and needs_reauth in one place.
                String[] storeInfo = tx.execute(s ->
                    jdbc.query(
                        "SELECT shop_domain, status FROM stores WHERE id = ?",
                        rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null,
                        storeId));

                if (storeInfo == null) {
                    throw new IllegalStateException("Store not found or inaccessible: " + storeId);
                }
                String status = storeInfo[1];
                if ("disconnected".equals(status) || "needs_reauth".equals(status)) {
                    log.info("Skipping import for store {} with status {}", storeId, status);
                    return;
                }

                String shopDomain = storeInfo[0];
                String rawToken   = tokenProvider.getValidToken(storeId);

                // Part A (FR-17 v2): ensure the Traced Main Warehouse exists and is linked
                // before catalog/order import. Non-fatal — a location provisioning failure
                // (e.g. missing write_locations scope) must not block product/order import,
                // which doesn't depend on it. Runs on every connect/reconnect; idempotent.
                try {
                    locationProvisioningService.ensureTracedWarehouse(tenantId, storeId, shopDomain);
                } catch (Exception e) {
                    log.warn("Traced Main Warehouse provisioning failed for store {} — " +
                        "will retry on next import/reconnect", storeId, e);
                }

                ShopifySyncService.ImportResult result =
                    syncService.runImport(storeId, tenantId, shopDomain, rawToken);

                updateStatus(storeId, "completed", toJson(Map.of(
                    "products",      result.products(),
                    "variants",      result.variants(),
                    "orders",        result.orders(),
                    "flaggedOrders", result.flaggedOrders())));

                log.info("Shopify import completed for store {}: {} products, {} orders",
                    storeId, result.products(), result.orders());

                // Parts B+C (FR-17 v2): activate the catalog at the Traced GID, then seed
                // on_hand — fully automatic, no manual approval gate. Must run AFTER
                // syncService.runImport() above, since both need variants to already exist.
                // Both are non-fatal here (a failure just means the next reconnect/import
                // retries) and both are idempotent/re-runnable — activateInventoryItem
                // tolerates already-active; apply() recomputes Shopify's live state and only
                // ever writes a strictly-positive delta from a variant currently at zero,
                // skipping and flagging anything already non-zero (never a double-seed, never
                // a correction). actorUserId=null: a system-triggered action, not an operator
                // action — AuditService.record() accepts null for exactly this case.
                try {
                    ShopifyCatalogActivationService.ActivationOutcome activationResult = activationService.activateAll();
                    log.info("Catalog activation for store {}: total={} succeeded={} failed={}",
                        storeId, activationResult.total(), activationResult.succeeded(), activationResult.failed());
                    if (activationResult.failed() > 0) {
                        log.warn("Catalog activation had {} failing variant(s) for store {} — " +
                            "will retry on next import/reconnect: {}",
                            activationResult.failed(), storeId, activationResult.failures());
                    }
                } catch (Exception e) {
                    log.warn("Catalog activation at Traced GID failed for store {} — " +
                        "will retry on next import/reconnect", storeId, e);
                }

                try {
                    ShopifyInventoryReconcileService.ApplyResult seedResult = reconcileService.apply(null);
                    log.info("Automatic on_hand seed for store {}: seeded={} skippedNonZero={} noop={} failed={}",
                        storeId, seedResult.seeded(), seedResult.skippedNonZero(),
                        seedResult.noop(), seedResult.failed());
                } catch (Exception e) {
                    log.warn("Automatic on_hand seed failed for store {} — " +
                        "will retry on next import/reconnect", storeId, e);
                }

            } catch (ShopifyStoreNeedsReauthException e) {
                // Permanent: store is already marked needs_reauth by ShopifyTokenProvider.
                log.warn("Shopify import skipped for store {} — store requires merchant reauth: {}",
                    storeId, e.getMessage());
                updateStatus(storeId, "failed", "{\"error\":\"needs_reauth\"}");
                // Do NOT rethrow — this is a permanent condition, not retryable.
            } catch (ShopifyTransientException e) {
                // Transient: Shopify 5xx or timeout during token refresh.
                // Store status is NOT touched — refresh token is still valid.
                log.error("Shopify import failed (transient) for store {} — will need manual re-trigger: {}",
                    storeId, e.getMessage());
                updateStatus(storeId, "failed", "{\"error\":\"transient_refresh_failure\"}");
                // Do NOT rethrow — JobRunr is disabled (F1); revisit when F1 is fixed.
            } catch (Exception e) {
                log.error("Shopify import failed for store {}", storeId, e);
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                updateStatus(storeId, "failed", "{\"error\":\"" + escape(errMsg) + "\"}");
                // Do NOT rethrow — JobRunr would retry; permanent failures must stay 'failed'
            }
        });
    }

    private void updateStatus(UUID storeId, String status, String summaryJson) {
        tx.execute(s -> {
            if (summaryJson != null) {
                jdbc.update(
                    "UPDATE stores SET import_status = ?::store_import_status, " +
                    "import_summary = ?::jsonb, last_sync_at = now() WHERE id = ?",
                    status, summaryJson, storeId);
            } else {
                jdbc.update(
                    "UPDATE stores SET import_status = ?::store_import_status WHERE id = ?",
                    status, storeId);
            }
            return null;
        });
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize import summary", e);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
