package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Real Shopify delivery-profile gateway using deliveryProfiles + deliveryProfileUpdate
 * (FR-17 v2 fulfillment activation). No @Primary/Stub split — unlike ShopifyLocationGateway's
 * historical transition period, ShopifyFulfillmentActivationService guards on write_shipping
 * scope inline before ever calling this bean (same pattern as ShopifyInventoryService's
 * write_inventory check), so a single implementation is sufficient.
 */
@Service
class ShopifyDeliveryProfileGatewayImpl implements ShopifyDeliveryProfileGateway {

    private static final String DEFAULT_PROFILE_QUERY = """
            query FindDefaultDeliveryProfile {
              deliveryProfiles(first: 5) {
                edges {
                  node {
                    id
                    default
                    profileLocationGroups {
                      locationGroup {
                        id
                        locations(first: 50) {
                          edges {
                            node {
                              id
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    private static final String DELIVERY_PROFILE_UPDATE_MUTATION = """
            mutation DeliveryProfileUpdate($id: ID!, $profile: DeliveryProfileInput!) {
              deliveryProfileUpdate(id: $id, profile: $profile) {
                profile {
                  id
                }
                userErrors {
                  field
                  message
                }
              }
            }
            """;

    private final ShopifyGateway shopify;
    private final ObjectMapper mapper;

    ShopifyDeliveryProfileGatewayImpl(ShopifyGateway shopify, ObjectMapper mapper) {
        this.shopify = shopify;
        this.mapper  = mapper;
    }

    @Override
    public Optional<LocationGroupInfo> findDefaultProfileLocationGroup(String shopDomain, String token) {
        JsonNode response = shopify.executeGraphQLPublic(
            shopDomain, token, DEFAULT_PROFILE_QUERY, mapper.createObjectNode());
        // executeGraphQL already strips the outer "data" envelope
        JsonNode edges = response.path("deliveryProfiles").path("edges");

        for (JsonNode edge : edges) {
            JsonNode node = edge.path("node");
            if (!node.path("default").asBoolean(false)) {
                continue;
            }

            JsonNode groups = node.path("profileLocationGroups");
            if (groups.size() != 1) {
                throw new ShopifyException(
                    "Default delivery profile has " + groups.size() + " location groups " +
                    "(expected exactly 1) — refusing to guess which one should receive the new location");
            }

            JsonNode group = groups.get(0).path("locationGroup");
            Set<String> members = new LinkedHashSet<>();
            for (JsonNode locEdge : group.path("locations").path("edges")) {
                members.add(locEdge.path("node").path("id").asText());
            }
            return Optional.of(new LocationGroupInfo(
                node.path("id").asText(), group.path("id").asText(), members));
        }
        return Optional.empty();
    }

    @Override
    public void addLocationToGroup(String shopDomain, String token, String deliveryProfileId,
                                    String locationGroupId, String locationGid) {
        ArrayNode locationsToAdd = mapper.createArrayNode().add(locationGid);
        ObjectNode locationGroupUpdate = mapper.createObjectNode().put("id", locationGroupId);
        locationGroupUpdate.set("locationsToAdd", locationsToAdd);
        ArrayNode locationGroupsToUpdate = mapper.createArrayNode().add(locationGroupUpdate);
        ObjectNode profileInput = mapper.createObjectNode();
        profileInput.set("locationGroupsToUpdate", locationGroupsToUpdate);

        ObjectNode vars = mapper.createObjectNode().put("id", deliveryProfileId);
        vars.set("profile", profileInput);

        JsonNode response = shopify.executeGraphQLPublic(
            shopDomain, token, DELIVERY_PROFILE_UPDATE_MUTATION, vars);
        JsonNode userErrors = response.path("deliveryProfileUpdate").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("deliveryProfileUpdate failed: " + msg);
        }
    }
}
