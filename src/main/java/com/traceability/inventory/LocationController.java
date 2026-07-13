package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/locations")
@PreAuthorize("isAuthenticated()")
public class LocationController {

    private final JdbcTemplate           jdbc;
    private final TransactionTemplate    tx;
    private final ShopifyLocationGateway shopifyLocations;
    private final ShopifyTokenProvider   tokenProvider;

    public LocationController(JdbcTemplate jdbc, PlatformTransactionManager txm,
                              ShopifyLocationGateway shopifyLocations,
                              ShopifyTokenProvider tokenProvider) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.shopifyLocations = shopifyLocations;
        this.tokenProvider    = tokenProvider;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return tx.execute(status ->
            jdbc.queryForList(
                "SELECT id, name, type, is_default, " +
                "       shopify_location_id, shopify_sync_status, " +
                "       shopify_sync_error, shopify_synced_at " +
                "FROM locations WHERE tenant_id = ? ORDER BY name",
                TenantContext.require()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE_owner','ROLE_manager')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require();

        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String  type      = body.getOrDefault("type", "warehouse").toString();
        boolean isDefault = Boolean.parseBoolean(body.getOrDefault("isDefault", "false").toString());

        UUID id = UUID.randomUUID();
        tx.execute(status -> {
            jdbc.update(
                "INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, name.trim(), type, isDefault);
            return null;
        });

        // Attempt Shopify location sync. The stub throws immediately (scope not yet granted).
        // All exceptions are caught here — location is always created even if sync fails.
        String syncStatus = "unsynced";
        String syncError  = null;
        try {
            UUID   storeId    = resolveStoreId(tenantId);
            String shopDomain = resolveShopDomain(tenantId);
            String token      = tokenProvider.getValidToken(storeId);

            ShopifyLocationGateway.LocationInput input =
                new ShopifyLocationGateway.LocationInput(name.trim(), null, null, "EG");
            ShopifyLocationGateway.LocationResult result =
                shopifyLocations.create(shopDomain, token, input);

            String shopifyLocId = result.shopifyLocationId();
            tx.execute(status -> {
                jdbc.update(
                    "UPDATE locations SET shopify_location_id = ?, shopify_sync_status = 'linked', " +
                    "shopify_synced_at = now() WHERE id = ? AND tenant_id = ?",
                    shopifyLocId, id, tenantId);
                return null;
            });
            syncStatus = "linked";
        } catch (Exception e) {
            syncError = e.getMessage();
            String finalError = syncError;
            tx.execute(status -> {
                jdbc.update(
                    "UPDATE locations SET shopify_sync_status = 'error', shopify_sync_error = ? " +
                    "WHERE id = ? AND tenant_id = ?",
                    finalError, id, tenantId);
                return null;
            });
            syncStatus = "error";
        }

        return Map.of(
            "id",                id.toString(),
            "name",              name.trim(),
            "type",              type,
            "isDefault",         isDefault,
            "shopifySyncStatus", syncStatus,
            "shopifySyncError",  syncError != null ? syncError : ""
        );
    }

    private UUID resolveStoreId(UUID tenantId) {
        UUID storeId = jdbc.query(
            "SELECT id FROM stores WHERE tenant_id = ? LIMIT 1",
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            tenantId);
        if (storeId == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "No Shopify store connected");
        return storeId;
    }

    private String resolveShopDomain(UUID tenantId) {
        String domain = jdbc.query(
            "SELECT shop_domain FROM stores WHERE tenant_id = ? LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null,
            tenantId);
        if (domain == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "No Shopify store connected");
        return domain;
    }
}
