package com.traceability.integrations.shopify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.security.EncryptionService;
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
 * Background job that imports a Shopify store's catalog and orders.
 *
 * TenantContext: JobRunr workers do not go through the HTTP filter chain.
 * TenantContext.runAs(tenantId, ...) is called explicitly so TenantAwareDataSource
 * fires SET LOCAL app.current_tenant at every transaction start, enforcing RLS.
 *
 * Failure handling: exceptions are caught, import_status is set to 'failed', and
 * the exception is NOT rethrown. JobRunr would otherwise retry indefinitely on
 * generic failures (bad data, permanent Shopify error). Transient network errors
 * inside the gateway are handled by Resilience4j retry before reaching this layer.
 *
 * Idempotency: ShopifySyncService uses ON CONFLICT DO UPDATE throughout, so
 * re-running the job is safe and produces the same result.
 */
@Component
public class ShopifyImportJob {

    private static final Logger log = LoggerFactory.getLogger(ShopifyImportJob.class);

    private final JdbcTemplate jdbc;
    private final ShopifySyncService syncService;
    private final EncryptionService encryptionService;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public ShopifyImportJob(JdbcTemplate jdbc,
                             ShopifySyncService syncService,
                             EncryptionService encryptionService,
                             ObjectMapper mapper,
                             PlatformTransactionManager txm) {
        this.jdbc              = jdbc;
        this.syncService       = syncService;
        this.encryptionService = encryptionService;
        this.mapper            = mapper;
        this.tx                = new TransactionTemplate(txm);
    }

    @Job(name = "Shopify import — store %0")
    public void run(UUID storeId, UUID tenantId) {
        TenantContext.runAs(tenantId, (Runnable) () -> {
            log.info("Starting Shopify import for store {}, tenant {}", storeId, tenantId);
            updateStatus(storeId, "importing", null);

            try {
                // Load store details inside a transaction so the GUC is set
                // and the store row is visible under RLS in production.
                String[] storeInfo = tx.execute(s ->
                    jdbc.query(
                        "SELECT shop_domain, access_token_encrypted FROM stores WHERE id = ?",
                        rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null,
                        storeId));

                if (storeInfo == null) {
                    throw new IllegalStateException("Store not found or inaccessible: " + storeId);
                }

                String shopDomain   = storeInfo[0];
                String rawToken     = encryptionService.decrypt(storeInfo[1]);

                ShopifySyncService.ImportResult result =
                    syncService.runImport(storeId, tenantId, shopDomain, rawToken);

                updateStatus(storeId, "completed", toJson(Map.of(
                    "products",      result.products(),
                    "variants",      result.variants(),
                    "orders",        result.orders(),
                    "flaggedOrders", result.flaggedOrders())));

                log.info("Shopify import completed for store {}: {} products, {} orders",
                    storeId, result.products(), result.orders());

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
