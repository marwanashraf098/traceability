package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Shopify-native returns S1 — READ-ONLY GraphQL for the returns mirror (V205). No mutation exists here, and no
 * customer / address / email field is ever requested.
 *
 * Every method throws {@link ShopifyReturnsAccessDeniedException} when Shopify answers ACCESS_DENIED (the store
 * hasn't approved read_returns / read_merchant_managed_fulfillment_orders yet) — the caller marks the store
 * "returns access: not granted" and stops quietly. Any other failure is a {@link ShopifyException}
 * (ShopifyTransientException for 5xx).
 */
public interface ShopifyReturnsGateway {

    /** One Return with its lines, exchange lines and reverse fulfillment orders; null when Shopify has no such return. */
    JsonNode fetchReturn(String shopDomain, String token, String returnGid);

    /** Order GID → the GIDs of its returns (first 10 each), for up to 50 orders in one call. Missing orders are absent. */
    Map<String, List<String>> fetchOrderReturnIds(String shopDomain, String token, List<String> orderGids);

    /** One FulfillmentOrder (status, holds, line items); null when Shopify has no such fulfillment order. */
    JsonNode fetchFulfillmentOrder(String shopDomain, String token, String fulfillmentOrderGid);

    /** The GIDs of an order's fulfillment orders (first 25). Empty when the order isn't found. */
    List<String> fetchOrderFulfillmentOrderIds(String shopDomain, String token, String orderGid);

    /** The order GID a reverse fulfillment order belongs to; null when not found. */
    String fetchReverseFulfillmentOrderOrderGid(String shopDomain, String token, String rfoGid);
}
