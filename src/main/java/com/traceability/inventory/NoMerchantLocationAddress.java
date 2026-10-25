package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyLocationGateway.LocationAddress;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Default {@link MainLocationAddressSource}: no merchant-entered address yet (feature B adds one). */
@Component
class NoMerchantLocationAddress implements MainLocationAddressSource {

    @Override
    public Optional<LocationAddress> merchantAddress(UUID tenantId) {
        return Optional.empty();
    }
}
