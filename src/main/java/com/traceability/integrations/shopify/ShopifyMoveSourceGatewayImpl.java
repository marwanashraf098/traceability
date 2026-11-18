package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real {@link ShopifyMoveSourceGateway} over {@link ShopifyGateway#executeGraphQLResponsePublic} (read only). All three
 * queries validated against Admin GraphQL 2026-04 (2026-11-18): locations(includeInactive, includeLegacy) with
 * fulfillmentService, nodes(ids) → ProductVariant.inventoryItem.inventoryLevel(locationId){isActive quantities(names)},
 * location(id).inventoryLevels{quantities item.variants.product}.
 */
@Service
class ShopifyMoveSourceGatewayImpl implements ShopifyMoveSourceGateway {

    static final int PAGE = 250;
    /** A runaway cursor never loops forever: 40 × 250 = 10,000 locations; 200 × 250 = 50,000 levels. */
    static final int MAX_PAGES = 40;
    static final int MAX_LEVEL_PAGES = 200;

    static final String LOCATIONS_QUERY = """
            query TracedMoveSources($first: Int!, $after: String) {
              locations(first: $first, after: $after, includeInactive: false, includeLegacy: false) {
                nodes { id name isActive fulfillmentService { id } }
                pageInfo { hasNextPage endCursor }
              }
            }
            """;

    static final String LEVELS_QUERY = """
            query TracedMoveSourceLevels($ids: [ID!]!, $loc: ID!) {
              nodes(ids: $ids) {
                ... on ProductVariant {
                  id
                  inventoryItem {
                    id
                    inventoryLevel(locationId: $loc) {
                      id
                      isActive
                      quantities(names: ["available", "committed", "on_hand"]) { name quantity }
                    }
                  }
                }
              }
            }
            """;

    static final String STOCK_QUERY = """
            query TracedMoveSourceStock($loc: ID!, $first: Int!, $after: String) {
              location(id: $loc) {
                id
                inventoryLevels(first: $first, after: $after) {
                  nodes {
                    quantities(names: ["available"]) { name quantity }
                    item { variants(first: 1) { nodes { product { id } } } }
                  }
                  pageInfo { hasNextPage endCursor }
                }
              }
            }
            """;

    private final ShopifyGateway shopify;
    private final ObjectMapper mapper;

    ShopifyMoveSourceGatewayImpl(ShopifyGateway shopify, ObjectMapper mapper) {
        this.shopify = shopify;
        this.mapper = mapper;
    }

    @Override
    public List<ShopifyLocation> listLocations(String shopDomain, String token) {
        List<ShopifyLocation> out = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            ObjectNode vars = mapper.createObjectNode().put("first", PAGE);
            if (after != null) vars.put("after", after);
            JsonNode locs = data(shopify.executeGraphQLResponsePublic(shopDomain, token, LOCATIONS_QUERY, vars))
                .path("locations");
            for (JsonNode n : locs.path("nodes")) {
                String id = n.path("id").asText(null);
                if (id == null) continue;
                JsonNode fs = n.path("fulfillmentService");
                out.add(new ShopifyLocation(id, n.path("name").asText(""), n.path("isActive").asBoolean(false),
                    !fs.isMissingNode() && !fs.isNull()));
            }
            if (!locs.path("pageInfo").path("hasNextPage").asBoolean(false)) return out;
            after = locs.path("pageInfo").path("endCursor").asText(null);
            if (after == null) return out;
        }
        throw new MoveSourceReadException("too_many_pages");
    }

    @Override
    public Map<String, Level> fetchLocationLevels(String shopDomain, String token, String locationGid,
                                                  List<String> variantGids) {
        Map<String, Level> out = new LinkedHashMap<>();
        for (int from = 0; from < variantGids.size(); from += PAGE) {
            List<String> chunk = variantGids.subList(from, Math.min(variantGids.size(), from + PAGE));
            ObjectNode vars = mapper.createObjectNode().put("loc", locationGid);
            ArrayNode ids = vars.putArray("ids");
            chunk.forEach(ids::add);
            JsonNode nodes = data(shopify.executeGraphQLResponsePublic(shopDomain, token, LEVELS_QUERY, vars)).path("nodes");
            for (JsonNode n : nodes) {
                String id = n.path("id").asText(null);
                if (id == null) continue;
                JsonNode level = n.path("inventoryItem").path("inventoryLevel");
                if (level.isMissingNode() || level.isNull() || !level.path("isActive").asBoolean(true)) {
                    out.put(id, new Level(id, false, null, null, null));
                    continue;
                }
                Integer available = null, committed = null, onHand = null;
                for (JsonNode q : level.path("quantities")) {
                    if (!q.path("quantity").isNumber()) continue;
                    int v = q.path("quantity").asInt();
                    switch (q.path("name").asText("")) {
                        case "available" -> available = v;
                        case "committed" -> committed = v;
                        case "on_hand" -> onHand = v;
                        default -> { }
                    }
                }
                out.put(id, new Level(id, true, available, committed, onHand));
            }
        }
        return out;
    }

    @Override
    public int countProductsWithStock(String shopDomain, String token, String locationGid) {
        Set<String> products = new HashSet<>();
        String after = null;
        for (int page = 0; page < MAX_LEVEL_PAGES; page++) {
            ObjectNode vars = mapper.createObjectNode().put("loc", locationGid).put("first", PAGE);
            if (after != null) vars.put("after", after);
            JsonNode location = data(shopify.executeGraphQLResponsePublic(shopDomain, token, STOCK_QUERY, vars))
                .path("location");
            if (location.isMissingNode() || location.isNull()) throw new MoveSourceReadException("location_not_found");
            JsonNode levels = location.path("inventoryLevels");
            for (JsonNode n : levels.path("nodes")) {
                int available = 0;
                for (JsonNode q : n.path("quantities")) {
                    if ("available".equals(q.path("name").asText()) && q.path("quantity").isNumber()) available = q.path("quantity").asInt();
                }
                if (available <= 0) continue;
                for (JsonNode v : n.path("item").path("variants").path("nodes")) {
                    String product = v.path("product").path("id").asText(null);
                    if (product != null) products.add(product);
                }
            }
            if (!levels.path("pageInfo").path("hasNextPage").asBoolean(false)) return products.size();
            after = levels.path("pageInfo").path("endCursor").asText(null);
            if (after == null) return products.size();
        }
        throw new MoveSourceReadException("too_many_pages");
    }

    /** data, or a MoveSourceReadException carrying only the GraphQL error codes. */
    private static JsonNode data(JsonNode response) {
        JsonNode errors = response == null ? null : response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            List<String> codes = new ArrayList<>();
            for (JsonNode e : errors) {
                String c = e.path("extensions").path("code").asText("");
                if (!c.isEmpty() && !codes.contains(c)) codes.add(c);
            }
            throw new MoveSourceReadException(codes.isEmpty() ? "graphql_error" : String.join(",", codes));
        }
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || data.isNull()) throw new MoveSourceReadException("no_data");
        return data;
    }
}
