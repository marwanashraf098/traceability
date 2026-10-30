package com.traceability.integrations.shopify;

import java.util.Optional;

/**
 * Abstraction over Shopify location management (FR-17).
 *
 * Requires write_locations scope. Until that scope is granted the only registered
 * implementation is {@link StubShopifyLocationGateway} which throws on every call
 * with a clear message. The real implementation is {@link ShopifyLocationGatewayImpl},
 * which is written and wired but NOT @Primary — swap @Primary to activate it once
 * the scope is confirmed on the dev store.
 */
public interface ShopifyLocationGateway {

    /**
     * fulfillsOnlineOrders gates storefront sales at this location — FR-17 v2's locked
     * decision is that every location Traced creates has this set true (it's always a
     * fulfillment-flagged location; see the is_fulfillment gate on LocationController).
     */
    record LocationInput(String name, String address1, String address2, String city, String provinceCode,
                          String zip, String countryCode, boolean fulfillsOnlineOrders) {

        /** A location created with this address (any field may be null — only countryCode is required). */
        public static LocationInput of(String name, LocationAddress address, boolean fulfillsOnlineOrders) {
            return new LocationInput(name, address.address1(), address.address2(), address.city(),
                address.provinceCode(), address.zip(), address.countryCode(), fulfillsOnlineOrders);
        }
    }

    /**
     * A Shopify location address (feature A, 2026-10-25). Blank strings are normalised to null so
     * two addresses compare equal exactly when Shopify would show the same thing.
     */
    record LocationAddress(String address1, String address2, String city, String provinceCode,
                           String zip, String countryCode) {
        public LocationAddress {
            address1     = blankToNull(address1);
            address2     = blankToNull(address2);
            city         = blankToNull(city);
            provinceCode = blankToNull(provinceCode);
            zip          = blankToNull(zip);
            countryCode  = blankToNull(countryCode);
        }

        /** "Empty" per the fill rule: address1, city and provinceCode all blank (country / zip don't count). */
        public boolean isEmpty() {
            return address1 == null && city == null && provinceCode == null;
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    /**
     * What readLocationAddress returns: the location's Shopify name, its address, and whether local
     * pickup is switched on there (localPickupSettingsV2 non-null).
     */
    record LocationAddressState(String name, LocationAddress address, boolean localPickupEnabled) {}

    record LocationResult(String shopifyLocationId, String name) {}

    /**
     * Finds a Shopify location by exact name. Returns empty if none found.
     * Used to detect whether a Traced location was already created in Shopify
     * (e.g. from a previous partially-completed sync).
     */
    Optional<LocationResult> findByName(String shopDomain, String token, String name);

    /**
     * Creates a new Shopify location via the locationAdd mutation.
     * Callers should call findByName first to avoid duplicates.
     *
     * @throws ShopifyException on Shopify userErrors or missing write_locations scope
     */
    LocationResult create(String shopDomain, String token, LocationInput input);

    /**
     * Deactivates a Shopify location via locationDeactivate — used only by the guarded,
     * operator-triggered junk-location cleanup path (Part A step 5). Never called
     * automatically; never moves inventory to another location (destinationLocationId
     * omitted — junk locations pushed before the fulfillment gate never had inventory
     * activated on them).
     *
     * @param idempotencyKey the mutation-level @idempotent key (mandatory as of API 2026-04) —
     *                       see {@link ShopifyGateway#idempotencyKey}. Must be STABLE across
     *                       retries of the same logical operation.
     * @throws ShopifyException on Shopify userErrors
     */
    void deactivate(String shopDomain, String token, String shopifyLocationGid, String idempotencyKey);

    /**
     * Reads one location's name, address and local-pickup setting (read only). Empty when Shopify
     * has no location with that id.
     *
     * @throws ShopifyException on a Shopify / transport failure
     */
    Optional<LocationAddressState> readLocationAddress(String shopDomain, String token, String shopifyLocationGid);

    /**
     * Is this location active in Shopify (read only, 2026-10-30)? true = active, false = deactivated, empty = Shopify
     * has no location with that id (deleted). Used by the periodic check of the Traced location
     * (ShopifyLocationCheckService).
     *
     * @throws ShopifyException on a Shopify / transport failure (the state is then unknown — nothing changes)
     */
    Optional<Boolean> readLocationActive(String shopDomain, String token, String shopifyLocationGid);

    /**
     * Edits ONLY the address of one location (locationEdit with input { address } — never name,
     * never fulfillsOnlineOrders, never metafields; an address edit touches no inventory quantity).
     * Callers write only to the Traced Main Warehouse GID. Safe to repeat: the same address sent
     * twice leaves the same result.
     *
     * @throws ShopifyException on Shopify userErrors or a transport failure
     */
    void editLocationAddress(String shopDomain, String token, String shopifyLocationGid, LocationAddress address);
}
