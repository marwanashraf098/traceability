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
 *   d4 — addLocationToGroup: exact mutation shape — id=deliveryProfileId,
 *        profile.locationGroupsToUpdate=[{id: locationGroupId, locationsToAdd: [locationGid]}]
 *   d5 — addLocationToGroup: userErrors throws ShopifyException
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

    @Test
    void d4_addLocationToGroup_sendsExactMutationShape() {
        when(shopify.executeGraphQLPublic(any(), any(), any(), any()))
            .thenReturn(json("{ \"deliveryProfileUpdate\": { \"profile\": { \"id\": \"x\" }, \"userErrors\": [] } }"));

        gateway.addLocationToGroup("shop.myshopify.com", "tok",
            "gid://shopify/DeliveryProfile/2", "gid://shopify/DeliveryLocationGroup/9",
            "gid://shopify/Location/300");

        ArgumentCaptor<JsonNode> varsCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(shopify).executeGraphQLPublic(
            eq("shop.myshopify.com"), eq("tok"), any(), varsCaptor.capture());

        JsonNode vars = varsCaptor.getValue();
        assertThat(vars.path("id").asText()).isEqualTo("gid://shopify/DeliveryProfile/2");
        JsonNode group = vars.path("profile").path("locationGroupsToUpdate").get(0);
        assertThat(group.path("id").asText()).isEqualTo("gid://shopify/DeliveryLocationGroup/9");
        assertThat(group.path("locationsToAdd")).hasSize(1);
        assertThat(group.path("locationsToAdd").get(0).asText()).isEqualTo("gid://shopify/Location/300");
    }

    @Test
    void d5_addLocationToGroup_userErrorsThrow() {
        when(shopify.executeGraphQLPublic(any(), any(), any(), any()))
            .thenReturn(json("""
                { "deliveryProfileUpdate": { "profile": null,
                    "userErrors": [ { "field": ["profile"], "message": "location already in group" } ] } }
                """));

        assertThrows(ShopifyException.class, () -> gateway.addLocationToGroup(
            "shop.myshopify.com", "tok", "gid://shopify/DeliveryProfile/2",
            "gid://shopify/DeliveryLocationGroup/9", "gid://shopify/Location/300"));
    }
}
