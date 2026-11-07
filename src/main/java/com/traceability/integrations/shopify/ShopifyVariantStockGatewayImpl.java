package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Real {@link ShopifyVariantStockGateway} over {@link ShopifyGateway#executeGraphQLResponsePublic} (read only).
 * Both queries validated against Admin GraphQL 2026-04 (productVariants sortKey INVENTORY_QUANTITY,
 * ProductVariant.inventoryQuantity, InventoryItem.inventoryLevel(locationId).quantities(names)); scopes
 * read_products, read_inventory.
 */
@Service
class ShopifyVariantStockGatewayImpl implements ShopifyVariantStockGateway {

    static final String TOP_STOCK_QUERY = """
            query TracedTopStock($first: Int!, $after: String) {
              productVariants(first: $first, after: $after, sortKey: INVENTORY_QUANTITY, reverse: true) {
                nodes { id inventoryQuantity }
                pageInfo { hasNextPage endCursor }
              }
            }
            """;

    static final String TOP_STOCK_AT_LOCATION_QUERY = """
            query TracedTopStockAt($first: Int!, $after: String, $loc: ID!) {
              productVariants(first: $first, after: $after, sortKey: INVENTORY_QUANTITY, reverse: true) {
                nodes {
                  id
                  inventoryQuantity
                  inventoryItem { inventoryLevel(locationId: $loc) { quantities(names: ["on_hand"]) { name quantity } } }
                }
                pageInfo { hasNextPage endCursor }
              }
            }
            """;

    private final ShopifyGateway shopify;
    private final ObjectMapper mapper;

    ShopifyVariantStockGatewayImpl(ShopifyGateway shopify, ObjectMapper mapper) {
        this.shopify = shopify;
        this.mapper = mapper;
    }

    @Override
    public Page topVariantsByStock(String shopDomain, String token, String locationGid, String after, int first) {
        ObjectNode vars = mapper.createObjectNode().put("first", first);
        if (after != null) vars.put("after", after);
        if (locationGid != null) vars.put("loc", locationGid);
        JsonNode response = shopify.executeGraphQLResponsePublic(shopDomain, token,
            locationGid == null ? TOP_STOCK_QUERY : TOP_STOCK_AT_LOCATION_QUERY, vars);
        JsonNode errors = response == null ? null : response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            List<String> codes = new ArrayList<>();
            for (JsonNode e : errors) {
                String c = e.path("extensions").path("code").asText("");
                if (!c.isEmpty() && !codes.contains(c)) codes.add(c);
            }
            throw new VariantStockReadException(codes.isEmpty() ? "graphql_error" : String.join(",", codes));
        }
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || data.isNull()) throw new VariantStockReadException("no_data");
        JsonNode pv = data.path("productVariants");
        List<VariantStock> out = new ArrayList<>();
        for (JsonNode n : pv.path("nodes")) {
            String id = n.path("id").asText(null);
            if (id == null || !n.path("inventoryQuantity").isNumber()) continue;
            Integer onHand = null;
            JsonNode level = n.path("inventoryItem").path("inventoryLevel");
            for (JsonNode q : level.path("quantities")) {
                if ("on_hand".equals(q.path("name").asText()) && q.path("quantity").isNumber()) onHand = q.path("quantity").asInt();
            }
            out.add(new VariantStock(id, n.path("inventoryQuantity").asInt(), onHand));
        }
        return new Page(out, pv.path("pageInfo").path("hasNextPage").asBoolean(false),
            pv.path("pageInfo").path("endCursor").asText(null));
    }
}
