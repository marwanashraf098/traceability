package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddress;

import java.util.Optional;
import java.util.UUID;

/**
 * Feature A — where the merchant's own address for the main warehouse comes from, when Traced
 * creates (or fills) its Shopify location. Empty → {@link MainLocationAddressService#GENERIC}.
 *
 * Today the only implementation is {@link NoMerchantLocationAddress} (always empty): the
 * merchant-entered address columns belong to feature B (onboarding gate), which supplies its own
 * implementation (a {@code @Primary} bean reading its columns). Called with the tenant context set.
 */
public interface MainLocationAddressSource {

    Optional<LocationAddress> merchantAddress(UUID tenantId);
}
