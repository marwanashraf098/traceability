package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Real {@link ShopifyOrderPriceGateway} over {@link ShopifyGateway#executeGraphQLPublic} (read
 * only). Fields validated against Admin GraphQL 2026-04: Order.currencyCode,
 * LineItem.id, LineItem.quantity, LineItem.variant.id,
 * LineItem.discountedUnitPriceAfterAllDiscountsSet.shopMoney.amount (scope read_orders).
 */
@Service
class ShopifyOrderPriceGatewayImpl implements ShopifyOrderPriceGateway {

    static final String ORDER_PRICES_QUERY = """
            query OrderRefundPrices($id: ID!) {
              order(id: $id) {
                currencyCode
                lineItems(first: 250) {
                  nodes {
                    id
                    quantity
                    variant { id }
                    discountedUnitPriceAfterAllDiscountsSet { shopMoney { amount } }
                  }
                }
              }
            }
            """;

    private final ShopifyGateway shopify;
    private final ObjectMapper mapper;

    ShopifyOrderPriceGatewayImpl(ShopifyGateway shopify, ObjectMapper mapper) {
        this.shopify = shopify;
        this.mapper  = mapper;
    }

    @Override
    public OrderPrices fetchOrderPrices(String shopDomain, String token, String orderGid) {
        JsonNode response = shopify.executeGraphQLResponsePublic(shopDomain, token, ORDER_PRICES_QUERY,
            mapper.createObjectNode().put("id", orderGid));
        JsonNode errors = response == null ? null : response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            // Only Shopify's error CODES travel on (extensions.code, e.g. ACCESS_DENIED) — never a message or body.
            List<String> codes = new ArrayList<>();
            for (JsonNode e : errors) {
                String c = e.path("extensions").path("code").asText("");
                if (!c.isEmpty() && !codes.contains(c)) codes.add(c);
            }
            throw new OrderPriceReadException(codes.isEmpty() ? "graphql_error" : String.join(",", codes));
        }
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || data.isNull()) throw new OrderPriceReadException("no_data");
        JsonNode order = data.path("order");
        // order(id:) is null for an order Shopify won't show this token: deleted, another shop, or older than
        // 60 days without read_all_orders.
        if (order.isMissingNode() || order.isNull()) throw new OrderPriceReadException("order_not_found");
        List<LinePrice> lines = new ArrayList<>();
        for (JsonNode n : order.path("lineItems").path("nodes")) {
            // A line whose variant was deleted in Shopify has variant: null — it is still priced by its line id.
            String variant = n.path("variant").path("id").asText(null);
            String lineId = n.path("id").asText(null);
            String amount = n.path("discountedUnitPriceAfterAllDiscountsSet").path("shopMoney").path("amount").asText(null);
            if ((variant == null && lineId == null) || amount == null) continue;
            BigDecimal price;
            try {
                price = new BigDecimal(amount);
            } catch (NumberFormatException e) {
                continue;
            }
            lines.add(new LinePrice(variant, n.path("quantity").asInt(0), price, lineId));
        }
        return new OrderPrices(order.path("currencyCode").asText(null), lines);
    }
}
