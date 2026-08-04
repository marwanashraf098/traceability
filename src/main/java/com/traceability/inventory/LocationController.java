package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private static final Logger log = LoggerFactory.getLogger(LocationController.class);

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
                "SELECT id, name, type, is_default, is_fulfillment, " +
                "       shopify_location_id, shopify_sync_status, " +
                "       shopify_sync_error, shopify_synced_at " +
                "FROM locations WHERE tenant_id = ? ORDER BY name",
                TenantContext.require()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require();

        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (name.trim().length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Location name must be at least 3 characters");
        }
        String  type          = body.getOrDefault("type", "warehouse").toString();
        boolean isDefault     = Boolean.parseBoolean(body.getOrDefault("isDefault", "false").toString());
        boolean isFulfillment = Boolean.parseBoolean(body.getOrDefault("isFulfillment", "false").toString());

        // UPSERT: if a row with the same name already exists in error/unsynced state, reuse it
        // (reset sync fields and re-attempt Shopify). If the existing row is already 'linked',
        // the WHERE condition in DO UPDATE fails → DO NOTHING → RETURNING returns no row → 409.
        // This prevents orphan duplicates when Shopify sync fails and the user retries.
        UUID proposedId = UUID.randomUUID();
        final UUID[] effectiveId = {null};
        tx.execute(status -> {
            effectiveId[0] = jdbc.query("""
                    INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment)
                    VALUES (?, ?, ?, ?::location_type, ?, ?)
                    ON CONFLICT (tenant_id, lower(trim(name))) DO UPDATE
                      SET type                = EXCLUDED.type,
                          is_default          = EXCLUDED.is_default,
                          is_fulfillment      = EXCLUDED.is_fulfillment,
                          shopify_sync_status = 'unsynced',
                          shopify_sync_error  = NULL,
                          shopify_location_id = NULL,
                          shopify_synced_at   = NULL
                      WHERE locations.shopify_sync_status IN ('error', 'unsynced')
                    RETURNING id
                    """,
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                proposedId, tenantId, name.trim(), type, isDefault, isFulfillment);
            return null;
        });
        if (effectiveId[0] == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "A location with this name is already linked to Shopify");
        }
        UUID id = effectiveId[0];

        // FR-17 v2 gate: only a fulfillment location may ever create a Shopify location.
        // Showroom/branch/junk locations stay unsynced permanently — no Shopify call at all.
        if (!isFulfillment) {
            return Map.of(
                "id",                id.toString(),
                "name",              name.trim(),
                "type",              type,
                "isDefault",         isDefault,
                "isFulfillment",     false,
                "shopifySyncStatus", "unsynced",
                "shopifySyncError",  ""
            );
        }

        // Attempt Shopify location sync. All exceptions are caught — location is always created.
        String syncStatus = "unsynced";
        String syncError  = null;
        try {
            record StoreSnap(UUID id, String shopDomain, String grantedScopes, String connectionType) {}
            StoreSnap store = tx.execute(status ->
                jdbc.query(
                    "SELECT id, shop_domain, access_token_scopes, connection_type FROM stores WHERE tenant_id = ? LIMIT 1",
                    rs -> rs.next() ? new StoreSnap(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)) : null,
                    tenantId));

            if (store == null) {
                throw new IllegalStateException("No Shopify store connected");
            }
            if (!ShopifyGateway.isScopeGranted("write_locations", store.grantedScopes())) {
                String message = ShopifyGateway.scopeGrantMessage(
                    store.connectionType(), "write_locations", store.grantedScopes());
                log.warn("Location sync skipped: {}", message);
                throw new IllegalStateException(message);
            }

            String token = tokenProvider.getValidToken(store.id());
            // fulfillsOnlineOrders=true unconditionally — every location Traced creates is,
            // by the gate above, a fulfillment location; its stock must gate storefront sales.
            ShopifyLocationGateway.LocationInput input =
                new ShopifyLocationGateway.LocationInput(name.trim(), null, null, "EG", true);
            ShopifyLocationGateway.LocationResult result =
                shopifyLocations.create(store.shopDomain(), token, input);

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
            "isFulfillment",     true,
            "shopifySyncStatus", syncStatus,
            "shopifySyncError",  syncError != null ? syncError : ""
        );
    }

    // ── Part A step 5: junk-location cleanup (report + guarded removal) ─────

    /**
     * Reports locations that hold a live Shopify link but are NOT the fulfillment
     * location — i.e. locations pushed to Shopify before the is_fulfillment gate existed
     * (junk test locations, showrooms someone manually synced, etc). Read-only; surfaces
     * the list for a human decision. Nothing is deleted or deactivated by this endpoint.
     */
    @GetMapping("/shopify-junk-report")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public List<Map<String, Object>> shopifyJunkReport() {
        UUID tenantId = TenantContext.require();
        return tx.execute(status -> jdbc.queryForList(
            "SELECT id, name, type, shopify_location_id, shopify_sync_status, shopify_synced_at " +
            "FROM locations " +
            "WHERE tenant_id = ? AND is_fulfillment = false AND shopify_location_id IS NOT NULL " +
            "ORDER BY name",
            tenantId));
    }

    /**
     * Guarded, per-location, operator-triggered cleanup: deactivates ONE junk location in
     * Shopify (never deletes — Shopify doesn't allow deleting a location with inventory
     * history) and clears the local Shopify link so the row goes back to 'unsynced'.
     * Only ever acts on a row already surfaced by shopifyJunkReport() (is_fulfillment=false
     * AND currently linked) — never automatic, never bulk.
     */
    @PostMapping("/{id}/shopify-cleanup")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> shopifyCleanup(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();

        record JunkRow(String shopifyLocationId, boolean isFulfillment) {}
        JunkRow row = tx.execute(status -> jdbc.query(
            "SELECT shopify_location_id, is_fulfillment FROM locations WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? new JunkRow(rs.getString(1), rs.getBoolean(2)) : null,
            id, tenantId));

        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found");
        }
        if (row.isFulfillment()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Refusing to deactivate the fulfillment location");
        }
        if (row.shopifyLocationId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Location has no Shopify link to clean up");
        }

        record StoreSnap(UUID id, String shopDomain) {}
        StoreSnap store = tx.execute(status ->
            jdbc.query(
                "SELECT id, shop_domain FROM stores WHERE tenant_id = ? LIMIT 1",
                rs -> rs.next() ? new StoreSnap(rs.getObject(1, UUID.class), rs.getString(2)) : null,
                tenantId));
        if (store == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No Shopify store connected");
        }

        String token = tokenProvider.getValidToken(store.id());
        // No claim-row/trigger tuple exists for this manual one-shot cleanup path (unlike the
        // three FR-17 v2 write triggers) — keyed by (tenantId, shopifyLocationGid) instead, which
        // is a safe, permanent 1:1: a deactivated GID is never reused (a location needed again
        // later would be a fresh locationAdd with a new GID), so this key never collides across
        // two genuinely different logical deactivations.
        String idempotencyKey = ShopifyGateway.idempotencyKey(
            tenantId, "location_cleanup", row.shopifyLocationId(), id, row.shopifyLocationId());
        shopifyLocations.deactivate(store.shopDomain(), token, row.shopifyLocationId(), idempotencyKey);

        tx.execute(status -> {
            jdbc.update(
                "UPDATE locations SET shopify_location_id = NULL, shopify_sync_status = 'unsynced', " +
                "shopify_synced_at = NULL, shopify_sync_error = NULL WHERE id = ? AND tenant_id = ?",
                id, tenantId);
            return null;
        });

        return Map.of("id", id.toString(), "shopifySyncStatus", "unsynced");
    }

}
