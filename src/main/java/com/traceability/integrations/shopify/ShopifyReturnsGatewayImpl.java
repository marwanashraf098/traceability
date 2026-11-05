package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real {@link ShopifyReturnsGateway} over {@link ShopifyGateway#executeGraphQLResponsePublic} (read only).
 * Every query was validated against the Admin GraphQL 2026-04 schema (2026-11-05). Scopes: Return, ExchangeLineItem,
 * ReverseFulfillmentOrder → read_returns; FulfillmentOrder at the merchant's own locations →
 * read_merchant_managed_fulfillment_orders. Return has no updatedAt / cancelledAt in 2026-04 (Traced stamps when it
 * first sees CANCELED); returnReason is deprecated — returnReasonDefinition.handle instead.
 */
@Service
class ShopifyReturnsGatewayImpl implements ShopifyReturnsGateway {

    static final int LINES_PAGE = 50;

    static final String RETURN_QUERY = """
            query TracedReturn($id: ID!) {
              return(id: $id) {
                id name status createdAt closedAt requestApprovedAt totalQuantity
                order { id }
                decline { reason note }
                returnLineItems(first: 50) {
                  pageInfo { hasNextPage }
                  nodes {
                    id quantity processedQuantity returnReasonNote customerNote
                    returnReasonDefinition { handle }
                    ... on ReturnLineItem { fulfillmentLineItem { id lineItem { id variant { id } } } }
                  }
                }
                exchangeLineItems(first: 50) {
                  pageInfo { hasNextPage }
                  nodes { id quantity processedQuantity variantId lineItems { id } }
                }
                reverseFulfillmentOrders(first: 10) {
                  nodes {
                    id status
                    lineItems(first: 50) {
                      nodes { id totalQuantity fulfillmentLineItem { id } dispositions { id type quantity location { id } } }
                    }
                  }
                }
              }
            }
            """;

    static final String ORDER_RETURNS_QUERY = """
            query TracedOrderReturns($ids: [ID!]!) {
              nodes(ids: $ids) { ... on Order { id returns(first: 10) { nodes { id } } } }
            }
            """;

    static final String FULFILLMENT_ORDER_QUERY = """
            query TracedFulfillmentOrder($id: ID!) {
              fulfillmentOrder(id: $id) {
                id status requestStatus createdAt updatedAt
                order { id }
                fulfillmentHolds { reason }
                lineItems(first: 50) {
                  pageInfo { hasNextPage }
                  nodes { id remainingQuantity totalQuantity lineItem { id variant { id } } }
                }
              }
            }
            """;

    static final String ORDER_FULFILLMENT_ORDERS_QUERY = """
            query TracedOrderFulfillmentOrders($id: ID!) {
              order(id: $id) { id fulfillmentOrders(first: 25) { nodes { id } } }
            }
            """;

    static final String RFO_ORDER_QUERY = """
            query TracedReverseFulfillmentOrder($id: ID!) {
              reverseFulfillmentOrder(id: $id) { id order { id } }
            }
            """;

    private final ShopifyGateway shopify;
    private final ObjectMapper mapper;

    ShopifyReturnsGatewayImpl(ShopifyGateway shopify, ObjectMapper mapper) {
        this.shopify = shopify;
        this.mapper  = mapper;
    }

    @Override
    public JsonNode fetchReturn(String shopDomain, String token, String returnGid) {
        JsonNode node = data(shopDomain, token, RETURN_QUERY, vars("id", returnGid)).path("return");
        return node.isObject() ? node : null;
    }

    @Override
    public Map<String, List<String>> fetchOrderReturnIds(String shopDomain, String token, List<String> orderGids) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (orderGids.isEmpty()) return out;
        ObjectNode v = mapper.createObjectNode();
        ArrayNode ids = v.putArray("ids");
        orderGids.forEach(ids::add);
        for (JsonNode n : data(shopDomain, token, ORDER_RETURNS_QUERY, v).path("nodes")) {
            if (!n.isObject() || n.path("id").asText("").isEmpty()) continue;
            List<String> returns = new ArrayList<>();
            for (JsonNode r : n.path("returns").path("nodes")) returns.add(r.path("id").asText());
            out.put(n.path("id").asText(), returns);
        }
        return out;
    }

    @Override
    public JsonNode fetchFulfillmentOrder(String shopDomain, String token, String fulfillmentOrderGid) {
        JsonNode node = data(shopDomain, token, FULFILLMENT_ORDER_QUERY, vars("id", fulfillmentOrderGid))
            .path("fulfillmentOrder");
        return node.isObject() ? node : null;
    }

    @Override
    public List<String> fetchOrderFulfillmentOrderIds(String shopDomain, String token, String orderGid) {
        List<String> out = new ArrayList<>();
        JsonNode order = data(shopDomain, token, ORDER_FULFILLMENT_ORDERS_QUERY, vars("id", orderGid)).path("order");
        for (JsonNode fo : order.path("fulfillmentOrders").path("nodes")) out.add(fo.path("id").asText());
        return out;
    }

    @Override
    public String fetchReverseFulfillmentOrderOrderGid(String shopDomain, String token, String rfoGid) {
        JsonNode rfo = data(shopDomain, token, RFO_ORDER_QUERY, vars("id", rfoGid)).path("reverseFulfillmentOrder");
        String gid = rfo.path("order").path("id").asText("");
        return gid.isEmpty() ? null : gid;
    }

    private ObjectNode vars(String k, String v) {
        return mapper.createObjectNode().put(k, v);
    }

    /** The data block; ACCESS_DENIED → {@link ShopifyReturnsAccessDeniedException}; any other error → ShopifyException. */
    private JsonNode data(String shopDomain, String token, String query, JsonNode variables) {
        JsonNode response = shopify.executeGraphQLResponsePublic(shopDomain, token, query, variables);
        if (response == null) throw new ShopifyException("Shopify GraphQL returned no response");
        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            List<String> codes = ShopifyHttpGateway.graphQLErrorCodes(errors);
            if (codes.contains("ACCESS_DENIED")) {
                throw new ShopifyReturnsAccessDeniedException("Shopify ACCESS_DENIED for returns / fulfillment orders on "
                    + shopDomain);
            }
            throw new ShopifyException("Shopify GraphQL error " + codes + " on " + shopDomain);
        }
        JsonNode data = response.get("data");
        if (data == null || data.isNull()) throw new ShopifyException("Shopify GraphQL response has no data field");
        return data;
    }
}
