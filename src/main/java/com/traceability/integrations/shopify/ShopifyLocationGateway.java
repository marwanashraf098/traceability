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
    record LocationInput(String name, String address1, String city, String countryCode,
                          boolean fulfillsOnlineOrders) {}

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
}
