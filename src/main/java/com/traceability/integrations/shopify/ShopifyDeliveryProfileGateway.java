package com.traceability.integrations.shopify;

import java.util.Optional;
import java.util.Set;

/**
 * READ ONLY — the shop's default ("General") delivery profile and the Traced Main Warehouse's membership of it (legacy
 * shipping; read_shipping is enough — verified live 2026-11-16 on a token without write_shipping).
 *
 * <p>L1b-2 (approved by Marawan 2026-11-16): Traced never writes a delivery profile. There is no mutation here or
 * anywhere in Traced (DeliveryProfileMutationGuardTest) — the owner adds the location in Shopify.
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
     * @throws ShopifyException if the default profile has more than one location group — membership is then
     *         not decided by guessing a group
     */
    Optional<LocationGroupInfo> findDefaultProfileLocationGroup(String shopDomain, String token);
}
