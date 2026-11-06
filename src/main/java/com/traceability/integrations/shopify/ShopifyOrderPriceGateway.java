package com.traceability.integrations.shopify;

import java.math.BigDecimal;
import java.util.List;

/**
 * Returns Step 4d-2 — a READ of one order's line prices, for the suggested refund amount.
 * No Shopify write of any kind. Uses the pinned API version (shopify.api-version) and the
 * tenant's token; needs read_orders.
 */
public interface ShopifyOrderPriceGateway {

    /**
     * One order line: its variant, the quantity ordered, the unit price after all discounts, and the line's own
     * GID ({@code gid://shopify/LineItem/…}, null when unknown) — the exact key of an order_items row.
     */
    record LinePrice(String variantGid, int quantity, BigDecimal unitPriceAfterDiscounts, String lineItemGid) {
        public LinePrice(String variantGid, int quantity, BigDecimal unitPriceAfterDiscounts) {
            this(variantGid, quantity, unitPriceAfterDiscounts, null);
        }
    }

    record OrderPrices(String currency, List<LinePrice> lines) {}

    /**
     * Admin GraphQL {@code order(id:)} → {@code currencyCode} and each line's
     * {@code id}, {@code variant.id}, {@code quantity} and
     * {@code discountedUnitPriceAfterAllDiscountsSet.shopMoney.amount}. Throws on any failure;
     * the caller falls back. Never logs the response.
     */
    OrderPrices fetchOrderPrices(String shopDomain, String token, String orderGid);

    /**
     * Shopify answered, but without the order's prices: {@code reason} is safe to log — Shopify's GraphQL error
     * codes (comma-separated), {@code order_not_found}, {@code no_data} or {@code graphql_error}; never a message,
     * a body, a price or a token.
     */
    class OrderPriceReadException extends ShopifyException {
        private final String reason;

        public OrderPriceReadException(String reason) {
            super("Shopify order price read: " + reason);
            this.reason = reason;
        }

        public String reason() { return reason; }
    }
}
