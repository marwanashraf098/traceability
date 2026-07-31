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

    /**
     * Reads the real granted access scopes for a token via GET /admin/oauth/access_scopes.json.
     * This endpoint introspects the TOKEN itself, not how it was issued — it works identically
     * for OAuth-issued and client-credentials-issued (custom_app_cc) tokens. This is the only
     * way to know a CC token's granted scopes, since the client-credentials exchange response
     * body has no scope field (unlike the OAuth code exchange).
     *
     * @return comma-separated scope handles (e.g. "read_products,write_inventory"), or null if
     *         the call fails for any reason (network, invalid token). Callers must treat null as
     *         "unknown" — never persist null over a previously-known-good scope string.
     */
    String fetchGrantedScopes(String shopDomain, String token);

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

    // ---- FR-17 v2: increment-only inventory sync ------------------------

    /**
     * Activates an inventoryItem at a location via inventoryActivate. New Shopify
     * locations start with no active inventory items — this must run before any
     * quantity write lands there. Idempotent: an "already active" userError is
     * swallowed, not thrown.
     *
     * @throws ShopifyException on any other userError
     */
    void activateInventoryItem(String shopDomain, String token, String inventoryItemGid, String locationGid);

    /**
     * Adjusts on_hand at a location by a POSITIVE delta only, via inventoryAdjustQuantities.
     * This is the ONLY quantity-increasing write shape in FR-17 v2 — inventorySetOnHandQuantities
     * is forbidden everywhere (it can decrement) and must never be called.
     *
     * @param positiveDelta must be > 0
     * @param reason        Shopify inventory adjustment reason (e.g. "received", "restock")
     * @throws IllegalArgumentException if positiveDelta <= 0 — checked BEFORE any network call
     * @throws ShopifyException         on Shopify userErrors
     */
    void adjustInventoryQuantities(String shopDomain, String token, String inventoryItemGid,
                                    String locationGid, int positiveDelta, String reason);

    /**
     * Moves quantity units from the "available" state to "damaged" at a location, via
     * inventoryMoveQuantities. on_hand is unchanged — the unit leaves the sellable pool.
     * Location stays fixed (from and to are the same locationGid); only the state name changes.
     *
     * @param quantity must be > 0
     * @throws IllegalArgumentException if quantity <= 0 — checked BEFORE any network call
     * @throws ShopifyException         on Shopify userErrors, including insufficient-available
     *                                  (caller must treat this as a clean failure, never retry-forced)
     */
    void moveAvailableToDamaged(String shopDomain, String token, String inventoryItemGid,
                                 String locationGid, int quantity, String reason);

    /** One inventoryItem's current "available" quantity at a location (Part C reconcile read). */
    record InventoryLevel(String inventoryItemGid, int available) {}

    /**
     * Reads current "available" quantities for a batch of inventory items at a single
     * location. Used only for the Part C reconcile report/guard — never for a write decision
     * that would decrement (FR-17 v2 forbids that regardless of what this read shows).
     * An item with no InventoryLevel at the location yet (never activated) is reported as 0.
     */
    List<InventoryLevel> fetchAvailableQuantities(String shopDomain, String token,
                                                    String locationGid, List<String> inventoryItemGids);

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
    // Exposes the internal GraphQL transport for ShopifyLocationGatewayImpl.
    // Strips the outer "data" envelope — callers must NOT path into ".data" on the result.
    JsonNode executeGraphQLPublic(String shopDomain, String token, String query, JsonNode variables);

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

    /**
     * Builds the "Token lacks X scope" error message, phrased for how the store must actually
     * fix it — which differs by connection_type. OAuth stores fix a missing scope by
     * reconnecting through the consent screen (Shopify re-prompts for the updated scope list).
     * custom_app_cc stores have no consent screen: their scopes are configured directly on the
     * Dev Dashboard custom app (Settings > Apps > Develop apps > [app] > Configuration), and a
     * fresh client-credentials exchange picks up whatever is currently configured there — saying
     * "reconnect" for CC is actively wrong and sends the merchant down the wrong fix.
     */
    static String scopeGrantMessage(String connectionType, String scopeName, String grantedScopesCsv) {
        String granted = grantedScopesCsv != null ? grantedScopesCsv : "none";
        String howToFix = "custom_app_cc".equals(connectionType)
            ? "update the custom app's Admin API access scopes in the store admin " +
              "(Settings > Apps > Develop apps) and reissue the token"
            : "store must reconnect to grant the current scope list";
        return "Token lacks " + scopeName + " scope (granted: " + granted + ") — " + howToFix;
    }
}
