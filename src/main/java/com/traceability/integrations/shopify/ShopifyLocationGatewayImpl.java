package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import java.util.Optional;

/**
 * Real Shopify location gateway using the locationAdd mutation (FR-17).
 * Activated 2026-07-16: write_locations scope confirmed on tracedlocations dev store.
 */
@Service
@Primary
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

    private final ShopifyGateway shopify;
    private final ObjectMapper  mapper;

    ShopifyLocationGatewayImpl(ShopifyGateway shopify, ObjectMapper mapper) {
        this.shopify = shopify;
        this.mapper  = mapper;
    }

    @Override
    public Optional<LocationResult> findByName(String shopDomain, String token, String name) {
        ObjectNode vars = mapper.createObjectNode().put("name", "name:\"" + name + "\"");
        JsonNode response = shopify.executeGraphQLPublic(shopDomain, token, FIND_BY_NAME_QUERY, vars);
        // executeGraphQL already strips the outer "data" envelope
        JsonNode edges = response.path("locations").path("edges");
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
        // TODO(deferred): countryCode is hardcoded 'EG' — must become tenant/location-configurable
        //   before onboarding a non-Egyptian merchant.
        String countryCode = (input.countryCode() != null && !input.countryCode().isBlank())
            ? input.countryCode() : "EG";
        ObjectNode address = mapper.createObjectNode().put("countryCode", countryCode);
        if (input.address1() != null && !input.address1().isBlank())
            address.put("address1", input.address1());
        if (input.city() != null && !input.city().isBlank())
            address.put("city", input.city());

        ObjectNode locationInput = mapper.createObjectNode()
            .put("name", input.name());
        locationInput.set("address", address);
        ObjectNode vars = mapper.createObjectNode().set("input", locationInput);

        JsonNode response = shopify.executeGraphQLPublic(shopDomain, token, LOCATION_ADD_MUTATION, vars);
        // executeGraphQL already strips the outer "data" envelope
        JsonNode locationAdd = response.path("locationAdd");

        JsonNode userErrors = locationAdd.path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("locationAdd failed: " + msg);
        }

        JsonNode loc = locationAdd.path("location");
        return new LocationResult(loc.path("id").asText(), loc.path("name").asText());
    }
}
