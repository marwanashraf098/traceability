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

    // ---- token records --------------------------------------------------

    /**
     * Returned by exchangeCode and refreshAccessToken.
     * refreshToken and both expiresIn fields are present only when expiring=1 was sent.
     */
    record TokenResponse(
            String accessToken,
            String refreshToken,          // plaintext shprt_...; null if non-expiring (shouldn't happen)
            long   expiresIn,             // seconds until access token expires (typically 3600)
            long   refreshTokenExpiresIn, // seconds until refresh token expires (typically 7776000)
            String grantedScopes          // comma-separated scope list from Shopify's response; null for CC
    ) {}

    // ---- operations -----------------------------------------------------

    /** Validates credentials by fetching the shop resource. Returns the shop name. */
    String validateShop(String shopDomain, String token);

    /** Fetches one page of active products with their variants. cursor=null for first page. */
    ProductPage fetchProductsPage(String shopDomain, String token, String cursor);

    /** Fetches one page of orders created after createdAfter (ISO-8601). cursor=null for first page. */
    OrderPage fetchOrdersPage(String shopDomain, String token, String cursor, String createdAfter);

    /**
     * Exchanges an OAuth authorization code for an expiring offline access token.
     * Sends expiring=1 so Shopify returns both an access token (1 h) and a refresh token (90 d).
     *
     * @param shopDomain the merchant's shop domain (e.g. "store.myshopify.com")
     * @param code       the authorization code from the callback query param
     * @return TokenResponse with access token, refresh token, and both expiry durations
     * @throws ShopifyException on non-200 response or missing access_token field
     */
    TokenResponse exchangeCode(String shopDomain, String code);

    /**
     * Obtains a fresh access token using the single-use rotating refresh token.
     * Shopify issues a new refresh token on each call; the old one is immediately invalidated.
     * Must be called under a SELECT FOR UPDATE on the stores row to prevent concurrent rotation.
     *
     * @param shopDomain   the merchant's shop domain
     * @param refreshToken the current plaintext refresh token (shprt_...)
     * @return new TokenResponse — both tokens and expiry durations from Shopify's response
     * @throws ShopifyStoreNeedsReauthException on permanent failure (4xx / invalid_grant)
     * @throws ShopifyTransientException        on transient failure (5xx / timeout / connection reset)
     */
    TokenResponse refreshAccessToken(String shopDomain, String refreshToken);

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

    // ---- Modern install flow: session-token exchange --------------------

    /**
     * Exchanges a Shopify App Bridge session token for an offline access token using
     * Shopify's token-exchange grant type. Used by the modern embedded install flow
     * (use_legacy_install_flow=false) where no auth-code callback runs.
     *
     * POST /admin/oauth/access_token with:
     *   grant_type         = urn:ietf:params:oauth:grant-type:token-exchange
     *   subject_token      = sessionToken (the raw HS256 JWT from Authorization: Bearer)
     *   subject_token_type = urn:ietf:params:oauth:token-type:id_token
     *
     * Uses tokenRestClient (5s connect / 10s read) — never the main restClient.
     *
     * @throws ShopifySessionTokenExchangeException on 4xx from Shopify (token rejected).
     *         Does NOT imply the refresh token is invalid — do NOT mark needs_reauth.
     * @throws ShopifyTransientException            on 5xx or network timeout.
     */
    TokenResponse exchangeSessionToken(String shopDomain, String sessionToken);

    // ---- OAuth Day 3 additions ------------------------------------------

    /**
     * Registers a webhook subscription via the webhookSubscriptionCreate GraphQL mutation.
     * If the topic+callbackUrl combination is "already taken", returns silently (idempotent).
     *
     * @param shopDomain  the merchant's myshopify.com domain
     * @param token       the raw (plaintext) offline access token
     * @param topic       Shopify topic string e.g. "orders/create"
     * @param callbackUrl the HTTPS endpoint Shopify will POST to
     * @throws ShopifyException on non-recoverable errors (auth failure, bad domain, etc.)
     */
    void registerWebhook(String shopDomain, String token, String topic, String callbackUrl);

    /** A registered Shopify webhook subscription. */
    record WebhookSubscription(String gid, String topic, String callbackUrl) {}

    /**
     * Lists all webhook subscriptions for the store (up to 100).
     * Used to detect stale subscriptions owned by a different app before re-registering.
     */
    List<WebhookSubscription> listWebhookSubscriptions(String shopDomain, String token);

    /**
     * Deletes a webhook subscription by its GID.
     * Logs but does not throw on userErrors (e.g. already deleted by the time we call this).
     */
    void deleteWebhookSubscription(String shopDomain, String token, String subscriptionGid);

    /**
     * Exchanges Client ID + Client Secret for a short-lived access token via
     * Shopify's client-credentials grant (Dev Dashboard custom apps, post-Jan 2026).
     * Token lifetime is always 86399 seconds (~24 hours). No refresh_token is issued.
     *
     * @throws ShopifyStoreNeedsReauthException on 4xx (shop_not_permitted, application_cannot_be_found, etc.)
     * @throws ShopifyTransientException on 5xx or network failure
     */
    TokenResponse exchangeClientCredentials(String shopDomain, String clientId, String clientSecret);

    // ---- FR-17: inventory sync ------------------------------------------

    /**
     * Resolves the Shopify inventoryItem GID for a product variant GID.
     * Uses the already-granted read_products scope — no new scope required.
     *
     * @param shopDomain the merchant's myshopify.com domain
     * @param token      plaintext offline access token
     * @param variantGid Shopify variant GID e.g. "gid://shopify/ProductVariant/12345"
     * @return inventoryItem GID e.g. "gid://shopify/InventoryItem/67890"
     * @throws ShopifyException if the variant is not found or the field is missing
     */
    String resolveInventoryItemId(String shopDomain, String token, String variantGid);

    // ---- scope utilities -----------------------------------------------

    /**
     * Returns true if the comma-separated grantedScopeCsv satisfies the required scope.
     *
     * Shopify's implied-scope rule: write_X is issued alone and implicitly covers read_X.
     * Shopify never echoes the redundant read_X in the access_scopes response. A plain
     * containsAll check therefore treats write_inventory as NOT satisfying read_inventory,
     * making the check permanently fail once write_inventory is in the scope list.
     *
     * Rule: required is satisfied if granted contains it directly, OR if required starts
     * with "read_" and granted contains the corresponding "write_" variant.
     */
    static boolean isScopeGranted(String required, String grantedScopeCsv) {
        if (grantedScopeCsv == null || grantedScopeCsv.isBlank()) return false;
        for (String s : grantedScopeCsv.split(",")) {
            if (required.equals(s.strip())) return true;
        }
        if (required.startsWith("read_")) {
            String writeVariant = "write_" + required.substring(5);
            for (String s : grantedScopeCsv.split(",")) {
                if (writeVariant.equals(s.strip())) return true;
            }
        }
        return false;
    }
}
