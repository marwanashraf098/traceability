package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Real {@link ShopifyLocationListGateway} over {@link ShopifyGateway#executeGraphQLResponsePublic} (read only).
 * Query validated against Admin GraphQL 2026-04 (Location requires read_locations OR read_inventory OR
 * read_markets_home — Traced holds write_locations and write_inventory).
 */
@Service
class ShopifyLocationListGatewayImpl implements ShopifyLocationListGateway {

    static final String ACTIVE_LOCATIONS_QUERY = """
            query TracedAddressLocations {
              locations(first: 50, includeLegacy: false, includeInactive: false) {
                nodes {
                  id
                  name
                  isActive
                  fulfillsOnlineOrders
                  address { address1 address2 city province provinceCode zip phone countryCode }
                }
              }
            }
            """;

    private final ShopifyGateway shopify;
    private final ObjectMapper mapper;

    ShopifyLocationListGatewayImpl(ShopifyGateway shopify, ObjectMapper mapper) {
        this.shopify = shopify;
        this.mapper = mapper;
    }

    @Override
    public List<ShopifyLocationAddress> activeLocations(String shopDomain, String token) {
        JsonNode response = shopify.executeGraphQLResponsePublic(shopDomain, token, ACTIVE_LOCATIONS_QUERY,
            mapper.createObjectNode());
        JsonNode errors = response == null ? null : response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            List<String> codes = new ArrayList<>();
            for (JsonNode e : errors) {
                String c = e.path("extensions").path("code").asText("");
                if (!c.isEmpty() && !codes.contains(c)) codes.add(c);
            }
            throw new LocationListReadException(codes.isEmpty() ? "graphql_error" : String.join(",", codes));
        }
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || data.isNull()) throw new LocationListReadException("no_data");
        List<ShopifyLocationAddress> out = new ArrayList<>();
        for (JsonNode n : data.path("locations").path("nodes")) {
            String id = text(n, "id");
            if (id == null) continue;
            JsonNode a = n.path("address");
            out.add(new ShopifyLocationAddress(id, text(n, "name"), n.path("isActive").asBoolean(false),
                n.path("fulfillsOnlineOrders").asBoolean(false),
                text(a, "address1"), text(a, "address2"), text(a, "city"), text(a, "province"),
                text(a, "provinceCode"), text(a, "zip"), text(a, "phone"), text(a, "countryCode")));
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
