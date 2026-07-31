package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Part B — activates every catalog inventory item at the Traced Main Warehouse GID.
 * Required before any on_hand write lands there (new Shopify locations start with no
 * active inventory items). inventoryActivate itself tolerates "already active" (see
 * ShopifyGateway.activateInventoryItem) — calling this repeatedly, or against a catalog
 * that's partially already active, is safe.
 */
@Service
public class ShopifyCatalogActivationService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyCatalogActivationService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ShopifyGateway shopify;
    private final ShopifyTokenProvider tokenProvider;

    public ShopifyCatalogActivationService(JdbcTemplate jdbc, PlatformTransactionManager txm,
                                            ShopifyGateway shopify, ShopifyTokenProvider tokenProvider) {
        this.jdbc          = jdbc;
        this.tx            = new TransactionTemplate(txm);
        this.shopify       = shopify;
        this.tokenProvider = tokenProvider;
    }

    public record ActivationOutcome(int total, int succeeded, int failed, List<Map<String, String>> failures) {}

    public ActivationOutcome activateAll() {
        UUID tenantId = TenantContext.require();

        record StoreSnap(UUID id, String shopDomain) {}
        StoreSnap store = tx.execute(s -> jdbc.query(
            "SELECT id, shop_domain FROM stores WHERE tenant_id = ? LIMIT 1",
            rs -> rs.next() ? new StoreSnap(rs.getObject(1, UUID.class), rs.getString(2)) : null,
            tenantId));
        if (store == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No Shopify store connected");
        }

        String tracedGid = tx.execute(s -> jdbc.query(
            "SELECT shopify_location_id FROM locations " +
            "WHERE tenant_id = ? AND is_fulfillment = true AND shopify_sync_status = 'linked' LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null,
            tenantId));
        if (tracedGid == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Traced Main Warehouse is not linked to Shopify yet");
        }

        String token = tokenProvider.getValidToken(store.id());

        List<Map<String, Object>> variants = tx.execute(s -> jdbc.queryForList(
            "SELECT id, external_id FROM variants WHERE tenant_id = ?", tenantId));

        int succeeded = 0;
        int failed = 0;
        List<Map<String, String>> failures = new ArrayList<>();

        for (Map<String, Object> v : variants) {
            UUID   variantId  = (UUID) v.get("id");
            String variantGid = (String) v.get("external_id");
            try {
                String itemGid = shopify.resolveInventoryItemId(store.shopDomain(), token, variantGid);
                String idempotencyKey = ShopifyGateway.idempotencyKey(
                    tenantId, "catalog_activation", variantId.toString(), variantId, tracedGid);
                shopify.activateInventoryItem(store.shopDomain(), token, itemGid, tracedGid, idempotencyKey);
                succeeded++;
            } catch (Exception e) {
                failed++;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Map<String, String> failure = new LinkedHashMap<>();
                failure.put("variantId", variantId.toString());
                failure.put("error", msg);
                failures.add(failure);
                log.warn("Activation failed: tenant={} variant={} error={}", tenantId, variantId, msg);
            }
        }

        return new ActivationOutcome(variants.size(), succeeded, failed, failures);
    }
}
