package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;

/**
 * Real Shopify location gateway using the locationAdd mutation (FR-17).
 *
 * NOT a Spring component — not in the application context until explicitly activated.
 * To activate: add @Service + @Primary here and remove @Primary from StubShopifyLocationGateway.
 *
 * Requires write_locations scope. The locationAdd mutation is preferred over
 * locationCreate (deprecated) per Shopify API 2024-10+.
 */
class ShopifyLocationGatewayImpl implements ShopifyLocationGateway {

    private static final String FIND_BY_NAME_QUERY = """
            query FindLocation($name: String!) {
              locations(first: 5, query: $name) {
                edges {
                  node {
                    id
                    name
                  }
                }
              }
            }
            """;

    private static final String LOCATION_ADD_MUTATION = """
            mutation LocationAdd($input: LocationAddInput!) {
              locationAdd(input: $input) {
                location {
                  id
                  name
                }
                userErrors {
                  field
                  message
                }
              }
            }
            """;

    private final ShopifyHttpGateway http;
    private final ObjectMapper mapper;

    ShopifyLocationGatewayImpl(ShopifyHttpGateway http, ObjectMapper mapper) {
        this.http   = http;
        this.mapper = mapper;
    }

    @Override
    public Optional<LocationResult> findByName(String shopDomain, String token, String name) {
        ObjectNode vars = mapper.createObjectNode().put("name", "name:\"" + name + "\"");
        JsonNode response = http.executeGraphQLPublic(shopDomain, token, FIND_BY_NAME_QUERY, vars);
        JsonNode edges = response.path("data").path("locations").path("edges");
        for (JsonNode edge : edges) {
            String nodeName = edge.path("node").path("name").asText("");
            if (name.equals(nodeName)) {
                return Optional.of(new LocationResult(
                    edge.path("node").path("id").asText(),
                    nodeName));
            }
        }
        return Optional.empty();
    }

    @Override
    public LocationResult create(String shopDomain, String token, LocationInput input) {
        ObjectNode locationInput = mapper.createObjectNode()
            .put("name", input.name())
            .put("address1", input.address1())
            .put("city", input.city())
            .put("countryCode", input.countryCode());
        ObjectNode vars = mapper.createObjectNode().set("input", locationInput);

        JsonNode response = http.executeGraphQLPublic(shopDomain, token, LOCATION_ADD_MUTATION, vars);
        JsonNode locationAdd = response.path("data").path("locationAdd");

        JsonNode userErrors = locationAdd.path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("locationAdd failed: " + msg);
        }

        JsonNode loc = locationAdd.path("location");
        return new LocationResult(loc.path("id").asText(), loc.path("name").asText());
    }
}
