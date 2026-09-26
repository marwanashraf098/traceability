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
 * LineItem.quantity, LineItem.variant.id,
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
        JsonNode data = shopify.executeGraphQLPublic(shopDomain, token, ORDER_PRICES_QUERY,
            mapper.createObjectNode().put("id", orderGid));
        JsonNode order = data.path("order");
        if (order.isMissingNode() || order.isNull()) throw new ShopifyException("Order not found in Shopify");
        List<LinePrice> lines = new ArrayList<>();
        for (JsonNode n : order.path("lineItems").path("nodes")) {
            String variant = n.path("variant").path("id").asText(null);
            String amount = n.path("discountedUnitPriceAfterAllDiscountsSet").path("shopMoney").path("amount").asText(null);
            if (variant == null || amount == null) continue;
            lines.add(new LinePrice(variant, n.path("quantity").asInt(0), new BigDecimal(amount)));
        }
        return new OrderPrices(order.path("currencyCode").asText(null), lines);
    }
}
