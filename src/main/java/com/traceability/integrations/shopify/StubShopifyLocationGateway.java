package com.traceability.integrations.shopify;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Stub that blocks all Shopify location operations until write_locations scope is granted.
 *
 * This is @Primary so it wins over ShopifyLocationGatewayImpl. To activate the real
 * implementation: (1) confirm write_locations scope works on the dev store, (2) add
 * @Primary to ShopifyLocationGatewayImpl, (3) remove @Primary from this class.
 */
@Service
@Primary
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
