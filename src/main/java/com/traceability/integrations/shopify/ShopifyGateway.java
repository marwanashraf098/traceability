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

    /**
     * Exchanges an OAuth authorization code for a permanent offline access token.
     * Called once per install on the OAuth callback.
     *
     * @param shopDomain the merchant's shop domain (e.g. "store.myshopify.com")
     * @param code       the authorization code from the callback query param
     * @return the plaintext offline access token — caller must encrypt before persisting
     * @throws ShopifyException on non-200 response or missing access_token field
     */
    String exchangeCode(String shopDomain, String code);

    // ---- OAuth Day 2 additions ------------------------------------------

    /** Shop info returned by the Shopify /admin/api/{v}/shop.json resource. */
    record ShopInfo(
            String email,    // merchant's shop contact email; may be null if not set
            String name,     // store display name
            String timezone  // IANA timezone e.g. "Africa/Cairo"
    ) {}

    /**
     * Fetches the Shopify shop resource to retrieve merchant email, name, and timezone.
     * Called only on the Path-2-new provisioning branch. Shop owner email is merchant
     * metadata (the Shop resource), not customer PII — no Shopify PCD gate required.
     *
     * @param shopDomain the merchant's shop domain
     * @param token      the raw (plaintext) offline access token
     * @return ShopInfo — email may be null if absent from the shop resource
     * @throws ShopifyException on non-200 response or missing shop field
     */
    ShopInfo fetchShop(String shopDomain, String token);
}
