package com.traceability.integrations.shopify;

import java.math.BigDecimal;
import java.util.List;

/**
 * Returns Step 4d-2 — a READ of one order's line prices, for the suggested refund amount.
 * No Shopify write of any kind. Uses the pinned API version (shopify.api-version) and the
 * tenant's token; needs read_orders.
 */
public interface ShopifyOrderPriceGateway {

    /** One order line: its variant, the quantity ordered, and the unit price after all discounts. */
    record LinePrice(String variantGid, int quantity, BigDecimal unitPriceAfterDiscounts) {}

    record OrderPrices(String currency, List<LinePrice> lines) {}

    /**
     * Admin GraphQL {@code order(id:)} → {@code currencyCode} and each line's
     * {@code variant.id}, {@code quantity} and
     * {@code discountedUnitPriceAfterAllDiscountsSet.shopMoney.amount}. Throws on any failure;
     * the caller falls back. Never logs the response.
     */
    OrderPrices fetchOrderPrices(String shopDomain, String token, String orderGid);
}
