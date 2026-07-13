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

    record LocationInput(String name, String address1, String city, String countryCode) {}

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
}
