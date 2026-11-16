package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Real Shopify delivery-profile gateway: the deliveryProfiles READ only (legacy shipping membership). L1b-2: no
 * delivery-profile mutation exists in Traced (DeliveryProfileMutationGuardTest).
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
}
