package com.traceability.integrations.shopify;

import java.util.List;

/**
 * Shopify-native returns S1 — the scopes the returns mirror needs, and which webhook topic needs which.
 * write_returns is requested now for S5 (no write call exists in S1); write_X implies read_X
 * ({@link ShopifyGateway#isScopeGranted}).
 */
public final class ShopifyReturnsScopes {

    private ShopifyReturnsScopes() {}

    public static final String READ_RETURNS = "read_returns";
    public static final String READ_MERCHANT_FULFILLMENT_ORDERS = "read_merchant_managed_fulfillment_orders";

    public static final List<String> RETURNS_TOPICS = List.of(
        "returns/request", "returns/approve", "returns/decline", "returns/cancel", "returns/close", "returns/reopen",
        "returns/process", "returns/update", "reverse_fulfillment_orders/dispose");

    public static final List<String> FULFILLMENT_ORDER_TOPICS = List.of(
        "fulfillment_orders/placed_on_hold", "fulfillment_orders/hold_released", "fulfillment_orders/cancelled",
        "fulfillment_orders/order_routing_complete", "fulfillment_orders/split", "fulfillment_orders/merged",
        "fulfillment_orders/moved");

    /** Both scopes granted — the store's returns mirror can run. */
    public static boolean granted(String grantedScopesCsv) {
        return ShopifyGateway.isScopeGranted(READ_RETURNS, grantedScopesCsv)
            && ShopifyGateway.isScopeGranted(READ_MERCHANT_FULFILLMENT_ORDERS, grantedScopesCsv);
    }

    /** The scope a topic needs before it can be subscribed, or null (no extra scope). */
    public static String requiredScope(String topic) {
        if (RETURNS_TOPICS.contains(topic)) return READ_RETURNS;
        if (FULFILLMENT_ORDER_TOPICS.contains(topic)) return READ_MERCHANT_FULFILLMENT_ORDERS;
        return null;
    }
}
