package com.traceability.integrations.shopify;

import java.util.List;

/**
 * Onboarding O2b — READ ONLY: the store's active, non-legacy Shopify locations with their address, so the setup's
 * warehouse-address step can be pre-filled. One call (locations, first 50, includeLegacy false, includeInactive
 * false). Scopes: any of read_locations / read_inventory (write_locations / write_inventory imply them) — no scope
 * change. There is no write here and none may be added.
 */
public interface ShopifyLocationListGateway {

    record ShopifyLocationAddress(String id, String name, boolean active, boolean fulfillsOnlineOrders,
                                  String address1, String address2, String city, String province,
                                  String provinceCode, String zip, String phone, String countryCode) {}

    /** @throws LocationListReadException on any GraphQL error (Shopify's codes only) or an answer without data */
    List<ShopifyLocationAddress> activeLocations(String shopDomain, String token);

    /** Carries only Shopify's error codes (extensions.code) — never a message or a body. */
    class LocationListReadException extends RuntimeException {
        private final String reason;

        public LocationListReadException(String reason) {
            super("location list read failed: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
