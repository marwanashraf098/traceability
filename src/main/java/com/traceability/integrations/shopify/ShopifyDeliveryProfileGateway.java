package com.traceability.integrations.shopify;

import java.util.Optional;
import java.util.Set;

/**
 * Abstraction over Shopify delivery-profile location-group membership (FR-17 v2 — fulfillment
 * activation, the step that makes the Traced Main Warehouse live to the storefront).
 *
 * Requires write_shipping scope (deliveryProfileUpdate's own docs: "Requires Any of shipping
 * access scopes or manage_delivery_settings user permission" — the latter is a merchant STAFF
 * user permission, not an app-installable scope, and does not apply to Traced's offline-token
 * flow).
 *
 * deliveryProfileUpdate does NOT support the @idempotent directive — Shopify's idempotency
 * mandate (API 2026-04) covers only inventory-adjustment, refund, and inventory-shipment/
 * transfer mutations (see ShopifyHttpGatewayInventoryTest g3 for the mutations that DO carry
 * it). Idempotency here is achieved by the caller checking
 * {@link LocationGroupInfo#memberLocationGids()} before calling addLocationToGroup, not by a
 * mutation-level key.
 */
public interface ShopifyDeliveryProfileGateway {

    /**
     * The shop's default ("General") delivery profile and the one location group inside it.
     * memberLocationGids is every location GID already in that group.
     */
    record LocationGroupInfo(String deliveryProfileId, String locationGroupId,
                              Set<String> memberLocationGids) {}

    /**
     * Finds the shop's default delivery profile and its location group.
     *
     * @return empty if the shop has zero delivery profiles (should not happen on a real store)
     * @throws ShopifyException if the default profile has more than one location group — this
     *         gateway does not guess which group a new location belongs to; the caller must
     *         surface a clear, guarded failure rather than silently joining the wrong group
     */
    Optional<LocationGroupInfo> findDefaultProfileLocationGroup(String shopDomain, String token);

    /**
     * Adds one location to one location group via deliveryProfileUpdate. Callers must check
     * {@link LocationGroupInfo#memberLocationGids()} first — this method always issues the
     * mutation and does not itself check membership.
     *
     * @throws ShopifyException on Shopify userErrors or missing write_shipping scope
     */
    void addLocationToGroup(String shopDomain, String token, String deliveryProfileId,
                             String locationGroupId, String locationGid);
}
