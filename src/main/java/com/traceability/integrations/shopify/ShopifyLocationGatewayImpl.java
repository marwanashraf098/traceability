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
        putIfPresent(address, "address1", input.address1());
        putIfPresent(address, "address2", input.address2());
        putIfPresent(address, "city", input.city());
        putIfPresent(address, "provinceCode", input.provinceCode());
        putIfPresent(address, "zip", input.zip());

        ObjectNode locationInput = mapper.createObjectNode()
            .put("name", input.name())
            .put("fulfillsOnlineOrders", input.fulfillsOnlineOrders());
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

    private static final String LOCATION_DEACTIVATE_MUTATION = """
            mutation LocationDeactivate($locationId: ID!, $idempotencyKey: String!) {
              locationDeactivate(locationId: $locationId) @idempotent(key: $idempotencyKey) {
                locationDeactivateUserErrors {
                  field
                  message
                }
              }
            }
            """;

    @Override
    public void deactivate(String shopDomain, String token, String shopifyLocationGid, String idempotencyKey) {
        ObjectNode vars = mapper.createObjectNode()
            .put("locationId", shopifyLocationGid)
            .put("idempotencyKey", idempotencyKey);
        JsonNode response = shopify.executeGraphQLPublic(shopDomain, token, LOCATION_DEACTIVATE_MUTATION, vars);
        JsonNode userErrors = response.path("locationDeactivate").path("locationDeactivateUserErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("locationDeactivate failed: " + msg);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) node.put(field, value);
    }

    // ---- feature A (2026-10-25): read / edit a location's address ------------

    private static final String LOCATION_ADDRESS_QUERY = """
            query LocationAddress($id: ID!) {
              location(id: $id) {
                id
                name
                localPickupSettingsV2 { pickupTime }
                address { address1 address2 city provinceCode zip countryCode }
              }
            }
            """;

    /** input carries ONLY address — leaving name / fulfillsOnlineOrders out keeps them unchanged. */
    static final String LOCATION_EDIT_ADDRESS_MUTATION = """
            mutation LocationEditAddress($id: ID!, $input: LocationEditInput!) {
              locationEdit(id: $id, input: $input) {
                location { id }
                userErrors { field message code }
              }
            }
            """;

    @Override
    public Optional<LocationAddressState> readLocationAddress(String shopDomain, String token, String shopifyLocationGid) {
        ObjectNode vars = mapper.createObjectNode().put("id", shopifyLocationGid);
        JsonNode loc = shopify.executeGraphQLPublic(shopDomain, token, LOCATION_ADDRESS_QUERY, vars).path("location");
        if (loc.isMissingNode() || loc.isNull()) return Optional.empty();
        JsonNode a = loc.path("address");
        LocationAddress address = new LocationAddress(
            text(a, "address1"), text(a, "address2"), text(a, "city"),
            text(a, "provinceCode"), text(a, "zip"), text(a, "countryCode"));
        JsonNode pickup = loc.path("localPickupSettingsV2");
        boolean localPickup = !pickup.isMissingNode() && !pickup.isNull();
        return Optional.of(new LocationAddressState(loc.path("name").asText(""), address, localPickup));
    }

    @Override
    public void editLocationAddress(String shopDomain, String token, String shopifyLocationGid, LocationAddress address) {
        JsonNode edit = shopify.executeGraphQLPublic(shopDomain, token, LOCATION_EDIT_ADDRESS_MUTATION,
                editAddressVariables(mapper, shopifyLocationGid, address))
            .path("locationEdit");
        JsonNode userErrors = edit.path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("locationEdit failed: " + msg);
        }
        if (edit.path("location").path("id").asText("").isEmpty()) {
            throw new ShopifyException("locationEdit failed: no location returned");
        }
    }

    /** Variables for the address edit — { id, input: { address } } and nothing else in input. */
    static ObjectNode editAddressVariables(ObjectMapper mapper, String shopifyLocationGid, LocationAddress address) {
        ObjectNode addr = mapper.createObjectNode();
        addr.put("countryCode", address.countryCode() != null ? address.countryCode() : "EG");
        putIfPresent(addr, "address1", address.address1());
        putIfPresent(addr, "address2", address.address2());
        putIfPresent(addr, "city", address.city());
        putIfPresent(addr, "provinceCode", address.provinceCode());
        putIfPresent(addr, "zip", address.zip());
        ObjectNode input = mapper.createObjectNode();
        input.set("address", addr);
        ObjectNode vars = mapper.createObjectNode().put("id", shopifyLocationGid);
        vars.set("input", input);
        return vars;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
