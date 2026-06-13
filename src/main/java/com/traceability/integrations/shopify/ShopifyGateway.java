package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Abstraction over the Shopify Admin API. Implementations handle HTTP,
 * retries, and throttle backoff; callers work with typed records.
 *
 * All method parameters are per-call (shop domain + token) so this bean
 * is stateless and safe to share across tenant requests.
 */
public interface ShopifyGateway {

    // ---- model records --------------------------------------------------

    record ProductPage(List<Product> products, boolean hasNextPage, String endCursor) {}

    record Product(String gid, String title, String status, List<Variant> variants) {}

    record Variant(String gid, String sku, String title, BigDecimal price) {}

    record OrderPage(List<Order> orders, boolean hasNextPage, String endCursor) {}

    /**
     * shippingAddress is kept as a raw JsonNode — column type is jsonb and the
     * structure is Shopify-specific; mapping to a record adds no value here.
     * tags and raw are stored verbatim for auditing and future webhook reconciliation.
     */
    record Order(
            String   gid,
            String   name,
            String   customerName,
            String   customerPhone,
            JsonNode shippingAddress,
            String   displayFinancialStatus,
            List<String> paymentGateways,
            BigDecimal   totalPrice,
            List<LineItem> lineItems,
            Instant  createdAt,
            JsonNode raw) {}

    record LineItem(String gid, int quantity, String variantGid) {}

    // ---- operations -----------------------------------------------------

    /** Validates credentials by fetching the shop resource. Returns the shop name. */
    String validateShop(String shopDomain, String token);

    /** Fetches one page of active products with their variants. cursor=null for first page. */
    ProductPage fetchProductsPage(String shopDomain, String token, String cursor);

    /** Fetches one page of orders created after createdAfter (ISO-8601). cursor=null for first page. */
    OrderPage fetchOrdersPage(String shopDomain, String token, String cursor, String createdAfter);
}
