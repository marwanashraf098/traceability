package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final StoreRepository        storeRepository;
    private final MainLocationAddressService addressService;
    private final MainLocationAddressSource  addressSource;
    private final TracedLocationOnlineService online;

    public LocationController(JdbcTemplate jdbc, PlatformTransactionManager txm,
                              ShopifyLocationGateway shopifyLocations,
                              ShopifyTokenProvider tokenProvider,
                              StoreRepository storeRepository,
                              MainLocationAddressService addressService,
                              MainLocationAddressSource addressSource,
                              TracedLocationOnlineService online) {
        this.online           = online;
        this.addressService   = addressService;
        this.addressSource    = addressSource;
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.shopifyLocations = shopifyLocations;
        this.tokenProvider    = tokenProvider;
        this.storeRepository  = storeRepository;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return tx.execute(status -> withMarketCheck(withLists(
            jdbc.queryForList(
                "SELECT id, name, type, is_default, is_fulfillment, " +
                "       shopify_location_id, shopify_sync_status, " +
                "       shopify_sync_error, shopify_synced_at, " +
                "       shopify_delivery_profile_status, shopify_delivery_profile_error, " +
                "       shopify_delivery_profile_activated_at, shopify_sync_mode, " +
                "       shopify_delivery_profile_checked_at, " +
                "       shopify_online_fulfillment_status, shopify_online_fulfillment_error, " +
                "       shopify_online_fulfillment_checked_at, " +
                "       shopify_shipping_model, shopify_shipping_model_checked_at, " +
                "       shopify_market_shipping_status, shopify_market_shipping_checked_at, " +
                "       shopify_market_shipping_covered_by, shopify_market_shipping_missing, " +
                "       regexp_replace(shopify_delivery_profile_id, '^.*/', '') AS shopify_delivery_profile_number, " +
                "       CASE WHEN is_fulfillment THEN " + TracedShippingReadiness.SQL + " END AS shopify_shipping_readiness " +
                "FROM locations l WHERE tenant_id = ? ORDER BY name",
                TenantContext.require()))));
    }

    /** L1b-2 fix (2026-11-17): the Traced Main row's {@code shopify_market_check} (TracedMarketCheck). */
    private List<Map<String, Object>> withMarketCheck(List<Map<String, Object>> rows) {
        String scopes = null;
        boolean loaded = false;
        for (Map<String, Object> row : rows) {
            if (!Boolean.TRUE.equals(row.get("is_fulfillment"))) continue;
            if (!loaded) {
                scopes = storeRepository.findByTenant(TenantContext.require())
                    .map(StoreRepository.Store::accessTokenScopes).orElse(null);
                loaded = true;
            }
            row.put("shopify_market_check", TracedMarketCheck.of((String) row.get("shopify_market_shipping_status"),
                row.get("shopify_market_shipping_checked_at"), scopes));
        }
        return rows;
    }

    /** L1b-2: a text[] column (shopify_market_shipping_missing) as a JSON list, never a driver object. */
    private static List<Map<String, Object>> withLists(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) row.replaceAll((k, v) -> v instanceof java.sql.Array a ? asList(a) : v);
        return rows;
    }

    private static List<Object> asList(java.sql.Array a) {
        try {
            return List.of((Object[]) a.getArray());
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    /**
     * Issue 2 — what happens to Shopify while stock sits at this (non-main) location: 'remove' (the
     * units leave the Traced Main Warehouse count when sent here) or 'leave' (Shopify unchanged).
     * A transfer snapshots the mode when it is sent, so a change applies to later transfers only.
     * Bazar B8: while a bazar at this location hasn't been sent yet, the mode is that bazar's online stock choice —
     * changing it to the other mode is refused (409 BAZAR_ONLINE_STOCK_CONFLICT, BazarOnlineStock).
     */
    @PutMapping("/{id}/shopify-sync-mode")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public Map<String, Object> setShopifySyncMode(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require();
        Object mode = body == null ? null : body.get("mode");
        if (!"remove".equals(mode) && !"leave".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be remove or leave");
        }
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT is_fulfillment, shopify_sync_mode FROM locations WHERE id = ? AND tenant_id = ? FOR UPDATE",
                id, tenantId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found");
            if (Boolean.TRUE.equals(rows.get(0).get("is_fulfillment"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The main warehouse is always counted in Shopify — this setting is for other locations.");
            }
            if (!mode.equals(rows.get(0).get("shopify_sync_mode"))
                    && com.traceability.bazar.BazarOnlineStock.unsentBazarsAt(jdbc, tenantId, id, null) > 0) {
                throw com.traceability.bazar.BazarOnlineStock.conflict();
            }
            jdbc.update("UPDATE locations SET shopify_sync_mode = ? WHERE id = ? AND tenant_id = ?", mode, id, tenantId);
            return Map.<String, Object>of("id", id.toString(), "shopify_sync_mode", mode);
        });
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
            // FR-3.1 follow-up — StoreRepository.findActiveStoreByTenant() is the single
            // canonical pick shared by every job/service (never a disconnected row).
            StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);

            if (store == null) {
                throw new IllegalStateException("No Shopify store connected");
            }
            if (!ShopifyGateway.isScopeGranted("write_locations", store.accessTokenScopes())) {
                String message = ShopifyGateway.scopeGrantMessage(
                    store.connectionType(), "write_locations", store.accessTokenScopes());
                log.warn("Location sync skipped: {}", message);
                throw new IllegalStateException(message);
            }

            String token = tokenProvider.getValidToken(store.id());
            // fulfillsOnlineOrders=true unconditionally — every location Traced creates is,
            // by the gate above, a fulfillment location; its stock must gate storefront sales.
            // Feature A: created WITH an address (the merchant's when feature B has one, else generic).
            ShopifyLocationGateway.LocationAddress address =
                addressSource.merchantAddress(tenantId).orElse(MainLocationAddressService.GENERIC);
            ShopifyLocationGateway.LocationInput input =
                ShopifyLocationGateway.LocationInput.of(name.trim(), address, true);
            ShopifyLocationGateway.LocationResult result =
                shopifyLocations.create(store.shopDomain(), token, input);

            String shopifyLocId = result.shopifyLocationId();
            tx.execute(status -> {
                jdbc.update(
                    "UPDATE locations SET shopify_location_id = ?, shopify_sync_status = 'linked', " +
                    "shopify_synced_at = now() WHERE id = ? AND tenant_id = ?",
                    shopifyLocId, id, tenantId);
                addressService.recordCreatedWith(id, tenantId, address);
                return null;
            });
            syncStatus = "linked";
            // L1: a first link from here too — fulfils online orders + delivery profile, both read back (never throws).
            online.atFirstLink(tenantId);
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

        // FR-3.1 follow-up — StoreRepository.findActiveStoreByTenant() is the single
        // canonical pick shared by every job/service (never a disconnected row).
        StoreRepository.Store store = storeRepository.findActiveStoreByTenant(tenantId).orElse(null);
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

    /**
     * L1 (2026-11-12) — "Turn back on": the owner's one click when a read found the Traced Main Warehouse not
     * fulfilling online orders. L1b-2: ONLY that location edit (read → edit only when off → read back), then the
     * shipping READ — never a delivery-profile write. Owner only. Takes no input: the target is always the tenant's own
     * is_fulfillment location.
     */
    @PostMapping("/shopify/turn-back-on")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> turnTracedLocationBackOn(@AuthenticationPrincipal CustomUserDetails principal) {
        TracedLocationOnlineService.Outcome o = online.turnBackOn(principal.userId());
        return Map.of("onlineStatus", o.onlineStatus(), "profile", o.profileOutcome());
    }

    /**
     * L1b-1 review (2026-11-15) — market-driven stores Traced can't read (no verdict / no read_markets): the owner says
     * Traced Main Warehouse is in the store's shipping rates. Owner only, no input, no Shopify call. Hides the
     * instruction ("Confirmed by you"); it is not Ready, and a verified 'not_covered' (L1b-2) beats it.
     */
    @PostMapping("/shopify/shipping-rates-confirmed")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> confirmTracedShippingRates(@AuthenticationPrincipal CustomUserDetails principal) {
        online.confirmShippingRates(principal.userId());
        return Map.of("readiness", TracedShippingReadiness.OWNER_CONFIRMED);
    }

    /**
     * L1b-2 (approved 2026-11-16) — "Check again": owner / manager, READ ONLY (fulfils online orders + the shipping
     * read — delivery-profile membership on legacy, the Markets read on market-driven). One per
     * {@value TracedLocationOnlineService#CHECK_EVERY_MINUTES} minutes per tenant: inside the window → 429
     * RATE_LIMITED with nextAllowedAt, nothing read. Takes no input.
     */
    @PostMapping("/shopify/shipping-check")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<Map<String, Object>> checkTracedShipping() {
        TracedLocationOnlineService.CheckResult r = online.checkAgain();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (!r.allowed()) {
            body.put("error", "RATE_LIMITED");
            body.put("detail", "One check every " + TracedLocationOnlineService.CHECK_EVERY_MINUTES + " minutes");
            body.put("nextAllowedAt", r.nextAllowedAt());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        }
        UUID tenantId = TenantContext.require();
        Map<String, Object> row = tx.execute(s -> jdbc.queryForMap(
            "SELECT " + TracedShippingReadiness.SQL + " AS readiness, l.shopify_shipping_model, " +
            "       l.shopify_market_shipping_covered_by, l.shopify_market_shipping_missing " +
            "FROM locations l WHERE l.tenant_id = ? AND l.is_fulfillment = true LIMIT 1", tenantId));
        body.put("readiness", row.get("readiness"));
        body.put("shippingModel", row.get("shopify_shipping_model"));
        body.put("coveredBy", row.get("shopify_market_shipping_covered_by"));
        Object missing = row.get("shopify_market_shipping_missing");
        body.put("notAddedFor", missing instanceof java.sql.Array a ? asList(a) : List.of());
        return ResponseEntity.ok(body);
    }
}
