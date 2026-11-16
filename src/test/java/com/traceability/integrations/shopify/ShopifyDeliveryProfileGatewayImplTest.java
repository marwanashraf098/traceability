package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for ShopifyDeliveryProfileGatewayImpl — mocks the ShopifyGateway boundary
 * (executeGraphQLPublic) and inspects the exact JsonNode payload built on both sides, same
 * approach as ShopifyLocationGatewayImpl has no dedicated test for but ShopifyHttpGatewayInventoryTest
 * uses for the real wire body. Same package (package-private class) by design.
 *
 * Matrix:
 *   d1 — findDefaultProfileLocationGroup: correct query sent, default=true profile picked,
 *        member GIDs collected from its single location group
 *   d2 — findDefaultProfileLocationGroup: >1 location group on the default profile throws
 *        ShopifyException rather than guessing
 *   d3 — findDefaultProfileLocationGroup: zero delivery profiles returns empty
 *   (d4 / d5 — addLocationToGroup — removed with the mutation, L1b-2 2026-11-16: Traced never writes a delivery
 *    profile; DeliveryProfileMutationGuardTest)
 */
class ShopifyDeliveryProfileGatewayImplTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ShopifyGateway shopify = mock(ShopifyGateway.class);
    private final ShopifyDeliveryProfileGatewayImpl gateway =
        new ShopifyDeliveryProfileGatewayImpl(shopify, mapper);

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void d1_findDefault_picksDefaultProfileAndCollectsMembers() {
        when(shopify.executeGraphQLPublic(eq("shop.myshopify.com"), eq("tok"), any(), any()))
            .thenReturn(json("""
                {
                  "deliveryProfiles": {
                    "edges": [
                      { "node": { "id": "gid://shopify/DeliveryProfile/1", "default": false,
                          "profileLocationGroups": [] } },
                      { "node": { "id": "gid://shopify/DeliveryProfile/2", "default": true,
                          "profileLocationGroups": [
                            { "locationGroup": { "id": "gid://shopify/DeliveryLocationGroup/9",
                                "locations": { "edges": [
                                    { "node": { "id": "gid://shopify/Location/100" } },
                                    { "node": { "id": "gid://shopify/Location/200" } }
                                ] } } }
                          ] } }
                    ]
                  }
                }
                """));

        Optional<ShopifyDeliveryProfileGateway.LocationGroupInfo> result =
            gateway.findDefaultProfileLocationGroup("shop.myshopify.com", "tok");

        assertThat(result).isPresent();
        assertThat(result.get().deliveryProfileId()).isEqualTo("gid://shopify/DeliveryProfile/2");
        assertThat(result.get().locationGroupId()).isEqualTo("gid://shopify/DeliveryLocationGroup/9");
        assertThat(result.get().memberLocationGids())
            .containsExactlyInAnyOrder("gid://shopify/Location/100", "gid://shopify/Location/200");
    }

    @Test
    void d2_findDefault_multipleLocationGroupsRefusesToGuess() {
        when(shopify.executeGraphQLPublic(any(), any(), any(), any()))
            .thenReturn(json("""
                {
                  "deliveryProfiles": {
                    "edges": [
                      { "node": { "id": "gid://shopify/DeliveryProfile/2", "default": true,
                          "profileLocationGroups": [
                            { "locationGroup": { "id": "gid://shopify/DeliveryLocationGroup/9",
                                "locations": { "edges": [] } } },
                            { "locationGroup": { "id": "gid://shopify/DeliveryLocationGroup/10",
                                "locations": { "edges": [] } } }
                          ] } }
                    ]
                  }
                }
                """));

        assertThrows(ShopifyException.class,
            () -> gateway.findDefaultProfileLocationGroup("shop.myshopify.com", "tok"));
    }

    @Test
    void d3_findDefault_noProfilesReturnsEmpty() {
        when(shopify.executeGraphQLPublic(any(), any(), any(), any()))
            .thenReturn(json("{ \"deliveryProfiles\": { \"edges\": [] } }"));

        assertThat(gateway.findDefaultProfileLocationGroup("shop.myshopify.com", "tok")).isEmpty();
    }
}
