package com.traceability.integrations.shopify;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Stub retained for reference. ShopifyLocationGatewayImpl is now @Primary (write_locations
 * scope confirmed on tracedlocations dev store 2026-07-16).
 */
@Service
class StubShopifyLocationGateway implements ShopifyLocationGateway {

    private static final String MSG =
        "Shopify location sync requires write_locations scope — not yet granted. " +
        "Test on dev store first, then activate ShopifyLocationGatewayImpl.";

    @Override
    public Optional<LocationResult> findByName(String shopDomain, String token, String name) {
        throw new ShopifyException(MSG);
    }

    @Override
    public LocationResult create(String shopDomain, String token, LocationInput input) {
        throw new ShopifyException(MSG);
    }
}
