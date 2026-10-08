package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shopify Admin API client backed by Spring RestClient.
 *
 * Retry strategy (two separate mechanisms):
 *   - Transient HTTP failures (connection error, 5xx): Resilience4j Retry,
 *     3 attempts, 1-second wait between each.
 *   - THROTTLED GraphQL response: manual sleep computed from Shopify's
 *     throttleStatus (dynamic wait = (requestedCost - currentlyAvailable) / restoreRate).
 *     After sleeping, the request is re-issued (up to MAX_THROTTLE_RETRIES times).
 *     Using Resilience4j for throttle would add a fixed extra wait on top of our
 *     computed sleep, doubling the delay — so we handle it separately.
 *
 * API version is pinned from config (shopify.api-version). No "latest" anywhere.
 */
@Service
class ShopifyHttpGateway implements ShopifyGateway {

    private static final Logger log = LoggerFactory.getLogger(ShopifyHttpGateway.class);

    private static final int MAX_THROTTLE_RETRIES = 5;
    // inventoryActivate: 10 (mutation) + 1 (inventoryLevel) requested points per alias.
    private static final int ACTIVATION_COST_PER_ALIAS = 11;
    private static final int ACTIVATION_THROTTLE_ATTEMPTS = 3;
    private static final int THROTTLE_MIN_WAIT_MS = 500;

    // No status filter: every product status is imported (ACTIVE, DRAFT, ARCHIVED, and
    // UNLISTED, which 2026-04 returns as its own value). The status is stored lowercase and
    // only the portal's exchange option reads it (ExchangeOptions / PortalService).
    private static final String PRODUCTS_QUERY = """
            query ProductsPage($cursor: String) {
              products(first: 50, after: $cursor) {
                pageInfo { hasNextPage endCursor }
                edges {
                  node {
                    id title status
                    featuredImage { url }
                    variants(first: 50) {
                      pageInfo { hasNextPage endCursor }
                      edges {
                        node { id sku title price inventoryItem { id } }
                      }
                    }
                  }
                }
              }
            }
            """;

    // Follow-up for a product whose first 50 variants didn't cover all of them — fetched per
    // product through the same throttle-aware executeGraphQL until its variants are exhausted.
    private static final String PRODUCT_VARIANTS_QUERY = """
            query ProductVariantsPage($id: ID!, $cursor: String) {
              product(id: $id) {
                variants(first: 250, after: $cursor) {
                  pageInfo { hasNextPage endCursor }
                  edges {
                    node { id sku title price inventoryItem { id } }
                  }
                }
              }
            }
            """;

    // Build B: how much customer PII the orders import asks for, per store (see orderPiiTier).
    //   FULL      shipping + billing address blocks AND customer { firstName lastName defaultPhoneNumber }
    //             — customer needs read_customers;
    //   ADDRESSES shipping + billing only (read_orders + approved protected customer data);
    //   NONE      no PII fields at all — the pre-Build-B query.
    // The precedence itself (shipping → customer → billing) is SQL: shopify_order_pii_* (V144). Email is
    // never requested. Validated against the 2026-04 Admin schema (Customer.phone is deprecated — hence
    // defaultPhoneNumber).
    enum OrderPiiTier { FULL, ADDRESSES, NONE;
        OrderPiiTier lower() { return this == FULL ? ADDRESSES : NONE; }
    }

    static String ordersQuery(OrderPiiTier tier) {
        String pii = switch (tier) {
            case FULL -> """
                    shippingAddress { name phone address1 address2 city province zip country }
                    billingAddress { name phone }
                    customer { firstName lastName defaultPhoneNumber { phoneNumber } }
                    """;
            case ADDRESSES -> """
                    shippingAddress { name phone address1 address2 city province zip country }
                    billingAddress { name phone }
                    """;
            case NONE -> "";
        };
        return """
            query OrdersPage($cursor: String, $queryStr: String) {
              orders(first: 50, after: $cursor, query: $queryStr, sortKey: CREATED_AT) {
                pageInfo { hasNextPage endCursor }
                edges {
                  node {
                    id name createdAt
                    lineItems(first: 100) {
                      edges {
                        node {
                          id quantity
                          variant { id }
                        }
                      }
                    }
                    currentTotalPriceSet { shopMoney { amount } }
                    displayFinancialStatus displayFulfillmentStatus
                    paymentGatewayNames tags
            """ + pii + """
                  }
                }
              }
            }
            """;
    }

    private static final String ACCESS_SCOPES_QUERY =
            "query AppAccessScopes { currentAppInstallation { accessScopes { handle } } }";

    static final Duration PII_TIER_TTL = Duration.ofHours(1);
    static final Duration PII_TIER_TTL_UNKNOWN = Duration.ofMinutes(5);

    private record CachedTier(OrderPiiTier tier, Instant until) {}

    /** Per shop domain — the token's scopes rarely change; a reconnect is picked up within the TTL. */
    private final java.util.concurrent.ConcurrentHashMap<String, CachedTier> orderPiiTiers =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final RestClient restClient;
    // Separate client for token exchange / refresh — bounded 10 s read timeout so a Shopify
    // hang doesn't hold a Hikari connection (and the SELECT FOR UPDATE row lock) indefinitely.
    // Hikari pool = 5; pilot stores ≤ 3 → at most 3 connections held ≤ 10 s each during
    // simultaneous refresh, leaving 2 connections free for other operations.
    private final RestClient tokenRestClient;
    private final ObjectMapper mapper;
    private final String apiVersion;
    private final String clientId;
    private final String clientSecret;
    private final Retry retry;

    @org.springframework.beans.factory.annotation.Autowired
    ShopifyHttpGateway(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${shopify.api-version}") String apiVersion,
            @Value("${shopify.client-id}") String clientId,
            @Value("${shopify.client-secret}") String clientSecret) {
        this(builder, null, mapper, apiVersion, clientId, clientSecret);
    }

    /**
     * Test seam: {@code tokenBuilder} replaces the token endpoint's client (null = the production one,
     * 5 s connect / 10 s read) so a test can see the exact /admin/oauth/access_token request.
     */
    ShopifyHttpGateway(RestClient.Builder builder, RestClient.Builder tokenBuilder, ObjectMapper mapper,
                       String apiVersion, String clientId, String clientSecret) {
        this.restClient   = builder.build();
        this.mapper       = mapper;
        this.apiVersion   = apiVersion;
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        this.retry = Retry.of("shopify-http", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .retryExceptions(ResourceAccessException.class)
                .ignoreExceptions(ShopifyException.class)
                .build());

        SimpleClientHttpRequestFactory tokenFactory = new SimpleClientHttpRequestFactory();
        tokenFactory.setConnectTimeout(Duration.ofSeconds(5));
        tokenFactory.setReadTimeout(Duration.ofSeconds(10));
        this.tokenRestClient = tokenBuilder != null
            ? tokenBuilder.build()
            : RestClient.builder().requestFactory(tokenFactory).build();
    }

    // ---- public API -----------------------------------------------------

    // App Store review 2.2.4 (GraphQL Admin API, not REST) — these two previously called
    // REST GET /admin/api/{v}/shop.json. Replaced with the equivalent `shop { ... }` GraphQL
    // query, each requesting only the fields it actually consumes. Routed through the shared
    // executeGraphQL() helper (retry + throttle handling), the same machinery every other
    // read in this class already uses — not a new HTTP path.
    private static final String SHOP_NAME_QUERY = """
            query ValidateShop {
              shop { name }
            }
            """;

    private static final String SHOP_INFO_QUERY = """
            query FetchShop {
              shop { name email ianaTimezone }
            }
            """;

    @Override
    public String validateShop(String shopDomain, String token) {
        JsonNode data = executeGraphQL(shopDomain, token, SHOP_NAME_QUERY, mapper.createObjectNode());
        JsonNode shop = data.path("shop");
        if (shop.isMissingNode() || shop.isNull()) {
            throw new ShopifyException("Shopify shop query returned no shop");
        }
        return shop.path("name").asText("unknown");
    }

    @Override
    public ShopInfo fetchShop(String shopDomain, String token) {
        JsonNode data = executeGraphQL(shopDomain, token, SHOP_INFO_QUERY, mapper.createObjectNode());
        JsonNode shop = data.path("shop");
        if (shop.isMissingNode() || shop.isNull()) {
            throw new ShopifyException("Shopify shop query returned no shop for fetchShop");
        }
        return new ShopInfo(
            nullableText(shop, "email"),
            shop.path("name").asText(""),
            shop.path("ianaTimezone").asText("UTC"));
    }

    @Override
    public TokenResponse exchangeCode(String shopDomain, String code) {
        String url = "https://" + shopDomain + "/admin/oauth/access_token";
        JsonNode resp = Retry.decorateSupplier(retry, () ->
            tokenRestClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(Map.of(
                    "client_id",     clientId,
                    "client_secret", clientSecret,
                    "code",          code,
                    "expiring",      "1"))
                .retrieve()
                .body(JsonNode.class)
        ).get();
        if (resp == null || !resp.has("access_token")) {
            throw new ShopifyException("Token exchange response missing access_token from " + shopDomain);
        }
        return expiringOfflineToken(shopDomain, resp);
    }

    /**
     * Parses an expiring offline token response (OAuth code exchange and session-token exchange,
     * both sent with expiring=1). No defaults: a missing refresh token or expiry means Shopify issued
     * a NON-expiring token, which the Admin API rejects — refused here, before any caller can store it.
     */
    private static TokenResponse expiringOfflineToken(String shopDomain, JsonNode resp) {
        TokenResponse t = new TokenResponse(
            resp.get("access_token").asText(),
            resp.hasNonNull("refresh_token") ? resp.get("refresh_token").asText() : null,
            resp.path("expires_in").asLong(0),
            resp.path("refresh_token_expires_in").asLong(0),
            resp.path("scope").asText(null));
        ShopifyStoredToken.requireExpiring(shopDomain, t);
        return t;
    }

    @Override
    public TokenResponse refreshAccessToken(String shopDomain, String refreshToken) {
        String url = "https://" + shopDomain + "/admin/oauth/access_token";
        try {
            JsonNode resp = tokenRestClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(Map.of(
                    "client_id",     clientId,
                    "client_secret", clientSecret,
                    "grant_type",    "refresh_token",
                    "refresh_token", refreshToken))
                .retrieve()
                .body(JsonNode.class);
            if (resp == null || !resp.has("access_token") || !resp.has("refresh_token")) {
                throw new ShopifyException("Refresh response missing required fields from " + shopDomain);
            }
            return new TokenResponse(
                resp.get("access_token").asText(),
                resp.get("refresh_token").asText(),
                resp.path("expires_in").asLong(3600),
                resp.path("refresh_token_expires_in").asLong(7776000),
                resp.path("scope").asText(null));
        } catch (HttpClientErrorException e) {
            // 4xx — refresh token invalid, expired, or revoked
            log.warn("Shopify refresh rejected ({}): shop={}", e.getStatusCode(), shopDomain);
            throw new ShopifyStoreNeedsReauthException(shopDomain,
                "Shopify rejected refresh with " + e.getStatusCode());
        } catch (HttpServerErrorException e) {
            // 5xx — Shopify-side issue; refresh token still valid
            log.warn("Shopify refresh 5xx ({}): shop={}", e.getStatusCode(), shopDomain);
            throw new ShopifyTransientException(
                "Shopify refresh server error " + e.getStatusCode() + " for " + shopDomain, e);
        } catch (ResourceAccessException e) {
            // timeout or connection reset
            log.warn("Shopify refresh timeout/connection-reset: shop={}", shopDomain);
            throw new ShopifyTransientException(
                "Shopify refresh network failure for " + shopDomain, e);
        } catch (ShopifyStoreNeedsReauthException | ShopifyTransientException e) {
            throw e; // already classified above or from response-field check
        } catch (RestClientException e) {
            // any other HTTP anomaly — treat as transient
            log.warn("Shopify refresh unexpected error: shop={}", shopDomain, e);
            throw new ShopifyTransientException(
                "Shopify refresh unexpected failure for " + shopDomain, e);
        }
    }

    @Override
    public void revokeAccessToken(String shopDomain, String token) {
        String url = "https://" + shopDomain + "/admin/api_permissions/current.json";
        try {
            restClient.delete()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .retrieve()
                .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            throw new ShopifyException(
                "Token revoke HTTP " + e.getStatusCode().value() + " for " + shopDomain, e);
        } catch (HttpServerErrorException e) {
            throw new ShopifyException(
                "Token revoke HTTP " + e.getStatusCode().value() + " for " + shopDomain, e);
        } catch (ResourceAccessException e) {
            throw new ShopifyTransientException("Token revoke network failure for " + shopDomain, e);
        }
    }

    @Override
    public ProductPage fetchProductsPage(String shopDomain, String token, String cursor) {
        ObjectNode vars = mapper.createObjectNode();
        if (cursor != null) vars.put("cursor", cursor);
        JsonNode data = executeGraphQL(shopDomain, token, PRODUCTS_QUERY, vars);
        JsonNode conn = data.path("products");
        List<Product> products = new ArrayList<>();
        for (JsonNode edge : conn.path("edges")) {
            JsonNode node = edge.path("node");
            List<Variant> variants = parseVariants(node.path("variants"));
            JsonNode variantsPageInfo = node.path("variants").path("pageInfo");
            if (variantsPageInfo.path("hasNextPage").asBoolean(false)) {
                variants.addAll(fetchRemainingVariants(shopDomain, token, node.path("id").asText(),
                        variantsPageInfo.path("endCursor").asText(null)));
            }
            products.add(new Product(
                    node.path("id").asText(),
                    node.path("title").asText(""),
                    node.path("status").asText("active").toLowerCase(),
                    node.path("featuredImage").path("url").asText(null),
                    variants));
        }
        return new ProductPage(products, conn.path("pageInfo").path("hasNextPage").asBoolean(),
                conn.path("pageInfo").path("endCursor").asText(null));
    }

    private List<Variant> fetchRemainingVariants(String shopDomain, String token, String productGid, String cursor) {
        List<Variant> out = new ArrayList<>();
        while (cursor != null) {
            ObjectNode vars = mapper.createObjectNode().put("id", productGid).put("cursor", cursor);
            JsonNode conn = executeGraphQL(shopDomain, token, PRODUCT_VARIANTS_QUERY, vars)
                    .path("product").path("variants");
            out.addAll(parseVariants(conn));
            cursor = conn.path("pageInfo").path("hasNextPage").asBoolean(false)
                    ? conn.path("pageInfo").path("endCursor").asText(null) : null;
        }
        return out;
    }

    @Override
    public OrderPage fetchOrdersPage(String shopDomain, String token, String cursor, String createdAfter) {
        ObjectNode vars = mapper.createObjectNode();
        if (cursor != null) vars.put("cursor", cursor);
        vars.put("queryStr", "created_at:>" + createdAfter);
        OrderPiiTier tier = orderPiiTier(shopDomain, token);
        JsonNode data;
        while (true) {
            JsonNode response = postGraphQL(shopDomain, token, ordersQuery(tier), vars);
            if (tier != OrderPiiTier.NONE && hasAccessDenied(response)) {
                // A missing scope / unapproved field must never fail or empty the import: ask for less.
                OrderPiiTier lower = tier.lower();
                log.warn("Shopify orders import: ACCESS_DENIED for customer data at tier {} on {} — retrying at {}",
                         tier, shopDomain, lower);
                orderPiiTiers.put(shopDomain, new CachedTier(lower, Instant.now().plus(PII_TIER_TTL)));
                tier = lower;
                continue;
            }
            JsonNode errors = response.get("errors");
            if (errors != null && errors.isArray() && errors.size() > 0) {
                throw new ShopifyException("Shopify GraphQL error: " + errors.get(0).path("message").asText());
            }
            data = response.get("data");
            if (data == null) throw new ShopifyException("Shopify GraphQL response has no data field");
            break;
        }
        JsonNode conn = data.path("orders");
        return new OrderPage(parseOrders(conn), conn.path("pageInfo").path("hasNextPage").asBoolean(),
                conn.path("pageInfo").path("endCursor").asText(null));
    }

    /**
     * The tier this store's token can read, from its live scopes (currentAppInstallation.accessScopes —
     * GraphQL, per App Store review 2.2.4), cached per shop domain for {@link #PII_TIER_TTL}. read_customers
     * → FULL, otherwise ADDRESSES. Unknown (the scope query failed) → ADDRESSES for {@link #PII_TIER_TTL_UNKNOWN}:
     * never ask for customer without knowing the scope is there. An ACCESS_DENIED at fetch time lowers the
     * cached tier further (fetchOrdersPage).
     */
    private final ShopifyStoreExistence storeExistence = new ShopifyStoreExistence(ShopifyStoreExistence.http());

    @Override
    public ShopifyStoreExistence.Result checkStoreExists(String shopDomain) {
        return storeExistence.check(shopDomain);
    }

    @Override
    public void forgetOrderPiiTier(String shopDomain) {
        if (shopDomain != null) orderPiiTiers.remove(shopDomain);
    }

    OrderPiiTier orderPiiTier(String shopDomain, String token) {
        CachedTier cached = orderPiiTiers.get(shopDomain);
        if (cached != null && cached.until().isAfter(Instant.now())) return cached.tier();
        OrderPiiTier tier;
        Duration ttl = PII_TIER_TTL;
        try {
            JsonNode data = executeGraphQL(shopDomain, token, ACCESS_SCOPES_QUERY, mapper.createObjectNode());
            List<String> handles = new ArrayList<>();
            for (JsonNode s : data.path("currentAppInstallation").path("accessScopes")) {
                handles.add(s.path("handle").asText());
            }
            tier = ShopifyGateway.isScopeGranted("read_customers", String.join(",", handles))
                ? OrderPiiTier.FULL : OrderPiiTier.ADDRESSES;
        } catch (RuntimeException e) {
            log.warn("Shopify orders import: could not read access scopes for {} ({}) — not asking for customer data",
                     shopDomain, e.getMessage());
            tier = OrderPiiTier.ADDRESSES;
            ttl = PII_TIER_TTL_UNKNOWN;
        }
        orderPiiTiers.put(shopDomain, new CachedTier(tier, Instant.now().plus(ttl)));
        return tier;
    }

    private static boolean hasAccessDenied(JsonNode response) {
        JsonNode errors = response.get("errors");
        if (errors == null || !errors.isArray()) return false;
        for (JsonNode e : errors) {
            if ("ACCESS_DENIED".equals(e.path("extensions").path("code").asText())) return true;
        }
        return false;
    }

    private static final String WEBHOOK_REGISTER_MUTATION = """
            mutation WebhookSubscriptionCreate($topic: WebhookSubscriptionTopic!, $webhookSubscription: WebhookSubscriptionInput!) {
              webhookSubscriptionCreate(topic: $topic, webhookSubscription: $webhookSubscription) {
                userErrors { field message }
                webhookSubscription { id }
              }
            }
            """;

    @Override
    public TokenResponse exchangeSessionToken(String shopDomain, String sessionToken) {
        String url = "https://" + shopDomain + "/admin/oauth/access_token";
        try {
            JsonNode resp = tokenRestClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(Map.of(
                    "client_id",            clientId,
                    "client_secret",        clientSecret,
                    "grant_type",           "urn:ietf:params:oauth:grant-type:token-exchange",
                    "subject_token",        sessionToken,
                    "subject_token_type",   "urn:ietf:params:oauth:token-type:id_token",
                    "requested_token_type", "urn:shopify:params:oauth:token-type:offline-access-token",
                    // expiring=1 → an EXPIRING offline token (1 h + a 90-day refresh token). Without it
                    // Shopify defaults to expiring=0 and issues a NON-expiring token, which the Admin API
                    // rejects with 403 ("Non-expiring access tokens are no longer accepted") — the Build D
                    // prod bug (test-oaozdwro, 2026-10-08). Same flag exchangeCode() sends.
                    "expiring",             "1"))
                .retrieve()
                .body(JsonNode.class);
            if (resp == null || !resp.has("access_token")) {
                throw new ShopifyException(
                    "Session token exchange response missing access_token from " + shopDomain);
            }
            return expiringOfflineToken(shopDomain, resp);
        } catch (HttpClientErrorException e) {
            log.warn("Shopify session-token exchange rejected ({}): shop={}", e.getStatusCode(), shopDomain);
            throw new ShopifySessionTokenExchangeException(shopDomain,
                "Shopify rejected session token exchange with " + e.getStatusCode());
        } catch (HttpServerErrorException e) {
            log.warn("Shopify session-token exchange 5xx ({}): shop={}", e.getStatusCode(), shopDomain);
            throw new ShopifyTransientException(
                "Shopify session-token exchange server error " + e.getStatusCode() + " for " + shopDomain, e);
        } catch (ResourceAccessException e) {
            log.warn("Shopify session-token exchange timeout: shop={}", shopDomain);
            throw new ShopifyTransientException(
                "Shopify session-token exchange network failure for " + shopDomain, e);
        } catch (ShopifySessionTokenExchangeException | ShopifyTransientException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("Shopify session-token exchange unexpected error: shop={}", shopDomain, e);
            throw new ShopifyTransientException(
                "Shopify session-token exchange unexpected failure for " + shopDomain, e);
        }
    }

    @Override
    public void registerWebhook(String shopDomain, String token, String topic, String callbackUrl) {
        String gqlTopic = topic.replace("/", "_").toUpperCase();
        ObjectNode webhookInput = mapper.createObjectNode().put("callbackUrl", callbackUrl);
        ObjectNode vars = mapper.createObjectNode()
                .put("topic", gqlTopic)
                .set("webhookSubscription", webhookInput);

        try {
            JsonNode data = executeGraphQL(shopDomain, token, WEBHOOK_REGISTER_MUTATION, vars);
            JsonNode errors = data.path("webhookSubscriptionCreate").path("userErrors");
            if (errors.isArray() && errors.size() > 0) {
                String message = errors.get(0).path("message").asText("");
                // "already taken" means this topic+URL pair is already registered — success
                if (message.toLowerCase().contains("already taken")) {
                    log.debug("Webhook already registered for topic={} shop={}", topic, shopDomain);
                    return;
                }
                throw new ShopifyException("Webhook registration error for topic=" + topic + ": " + message);
            }
            log.info("Registered Shopify webhook topic={} url={} shop={}", topic, callbackUrl, shopDomain);
        } catch (ShopifyException e) {
            // Re-throw registration errors as-is for the job to handle
            throw e;
        }
    }

    private static final String WEBHOOK_LIST_QUERY = """
            {
              webhookSubscriptions(first: 100) {
                nodes {
                  id
                  topic
                  callbackUrl
                }
              }
            }
            """;

    private static final String WEBHOOK_DELETE_MUTATION = """
            mutation WebhookSubscriptionDelete($id: ID!) {
              webhookSubscriptionDelete(id: $id) {
                userErrors { field message }
                deletedWebhookSubscriptionId
              }
            }
            """;

    @Override
    public List<WebhookSubscription> listWebhookSubscriptions(String shopDomain, String token) {
        JsonNode data = executeGraphQL(shopDomain, token, WEBHOOK_LIST_QUERY, mapper.createObjectNode());
        JsonNode nodes = data.path("webhookSubscriptions").path("nodes");
        List<WebhookSubscription> result = new ArrayList<>();
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                result.add(new WebhookSubscription(
                    node.path("id").asText(),
                    node.path("topic").asText(),
                    node.path("callbackUrl").asText()));
            }
        }
        return result;
    }

    @Override
    public void deleteWebhookSubscription(String shopDomain, String token, String subscriptionGid) {
        ObjectNode vars = mapper.createObjectNode().put("id", subscriptionGid);
        JsonNode data = executeGraphQL(shopDomain, token, WEBHOOK_DELETE_MUTATION, vars);
        JsonNode errors = data.path("webhookSubscriptionDelete").path("userErrors");
        if (errors.isArray() && errors.size() > 0) {
            log.warn("Shopify webhook delete userError: gid={} shop={} message={}",
                subscriptionGid, shopDomain, errors.get(0).path("message").asText(""));
        }
    }

    @Override
    public TokenResponse exchangeClientCredentials(String shopDomain, String clientId, String clientSecret) {
        String url = "https://" + shopDomain + "/admin/oauth/access_token";
        try {
            JsonNode resp = tokenRestClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .body(Map.of(
                    "grant_type",    "client_credentials",
                    "client_id",     clientId,
                    "client_secret", clientSecret))
                .retrieve()
                .body(JsonNode.class);
            if (resp == null || !resp.has("access_token")) {
                throw new ShopifyException(
                    "Client-credentials exchange response missing access_token from " + shopDomain);
            }
            return new TokenResponse(
                resp.get("access_token").asText(),
                null,  // no refresh_token issued for client-credentials grant
                resp.path("expires_in").asLong(86399),
                0,     // no refresh_token_expires_in
                null); // CC exchange response has no scope field — callers must read the real
                       // granted scopes separately via fetchGrantedScopes(shopDomain, accessToken)
        } catch (HttpClientErrorException e) {
            // 4xx — app/store not permitted, wrong credentials, app not installed, etc.
            log.warn("Shopify CC exchange rejected ({}): shop={}", e.getStatusCode(), shopDomain);
            throw new ShopifyStoreNeedsReauthException(shopDomain,
                "Shopify rejected client-credentials exchange with " + e.getStatusCode()
                + ": " + e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            // 5xx — Shopify-side issue
            log.warn("Shopify CC exchange 5xx ({}): shop={}", e.getStatusCode(), shopDomain);
            throw new ShopifyTransientException(
                "Shopify CC exchange server error " + e.getStatusCode() + " for " + shopDomain, e);
        } catch (ResourceAccessException e) {
            // timeout or connection reset
            log.warn("Shopify CC exchange timeout/connection-reset: shop={}", shopDomain);
            throw new ShopifyTransientException(
                "Shopify CC exchange network failure for " + shopDomain, e);
        } catch (ShopifyStoreNeedsReauthException | ShopifyTransientException e) {
            throw e; // already classified above
        } catch (RestClientException e) {
            // any other HTTP anomaly — treat as transient
            log.warn("Shopify CC exchange unexpected error: shop={}", shopDomain, e);
            throw new ShopifyTransientException(
                "Shopify CC exchange unexpected failure for " + shopDomain, e);
        }
    }

    // ---- FR-17: inventory sync -------------------------------------------

    @Override
    public String resolveInventoryItemId(String shopDomain, String token, String variantGid) {
        String query = """
                query VariantInventoryItem($id: ID!) {
                  productVariant(id: $id) {
                    inventoryItem { id }
                  }
                }
                """;
        String endpointUrl = "https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json";
        ObjectNode vars = mapper.createObjectNode().put("id", variantGid);
        // executeGraphQL already strips the outer "data" envelope — result is {"productVariant":{...}}
        JsonNode data = executeGraphQL(shopDomain, token, query, vars);

        JsonNode productVariantNode = data.path("productVariant");

        if (productVariantNode.isMissingNode() || productVariantNode.isNull()) {
            String scopes = fetchGrantedScopes(shopDomain, token);
            log.warn("Shopify resolveInventoryItemId: productVariant=null | variant={} | url={} | scopes={} | query={} | data={}",
                     variantGid, endpointUrl, scopes, query, data);
            throw new ShopifyException(
                "productVariant not found for " + variantGid
                + " (wrong GID, store mismatch, or missing read_products scope)"
                + (scopes != null ? " | token scopes: " + scopes : ""));
        }

        JsonNode itemId = productVariantNode.path("inventoryItem").path("id");
        if (itemId.isMissingNode() || itemId.isNull()) {
            String scopes = fetchGrantedScopes(shopDomain, token);
            log.warn("Shopify resolveInventoryItemId: inventoryItem=null | variant={} | url={} | scopes={} | query={} | data={}",
                     variantGid, endpointUrl, scopes, query, data);
            throw new ShopifyException(
                "productVariant.inventoryItem is null for " + variantGid
                + " — token predates read_inventory/write_inventory scope; store must reinstall app to issue a new token"
                + (scopes != null ? " | token scopes: " + scopes : ""));
        }

        return itemId.asText();
    }

    // ---- FR-17 v2: increment-only inventory sync -------------------------
    //
    // @idempotent(key: $idempotencyKey) — mandatory as of API 2026-04 on all three mutations
    // below plus locationDeactivate (Shopify changelog "Making idempotency mandatory for
    // inventory adjustments and refund mutations", effective 2025-12-12; confirmed against
    // production 2026-08-01: "The @idempotent directive is required for this mutation but was
    // not provided"). An earlier revision added a bare "@idempotent" token with no key and was
    // removed as decorative (2026-07-30) — that removal is what THIS reinstates correctly.
    // The key is generated by ShopifyGateway.idempotencyKey() from the SAME
    // (tenantId, triggerType, triggerId, variantId, locationId) tuple as the DB claim-row
    // unique constraint in ShopifyInventoryService/ShopifyInventoryReconcileService, computed
    // by the CALLER (which has that tuple in scope) and passed in here — so a retry of the
    // same claimed operation reuses the same key (Shopify dedupes server-side) and a genuinely
    // different operation gets a different one, keeping Shopify's notion of "same operation"
    // and our DB's in agreement. This is defense in depth ON TOP OF the DB claim-row guard,
    // not instead of it.

    private static final String INVENTORY_ACTIVATE_MUTATION = """
            mutation InventoryActivate($inventoryItemId: ID!, $locationId: ID!, $idempotencyKey: String!) {
              inventoryActivate(inventoryItemId: $inventoryItemId, locationId: $locationId) @idempotent(key: $idempotencyKey) {
                inventoryLevel { id }
                userErrors { field message }
              }
            }
            """;

    @Override
    public void activateInventoryItem(String shopDomain, String token, String inventoryItemGid,
                                       String locationGid, String idempotencyKey) {
        ObjectNode vars = mapper.createObjectNode()
            .put("inventoryItemId", inventoryItemGid)
            .put("locationId", locationGid)
            .put("idempotencyKey", idempotencyKey);
        JsonNode data = executeGraphQL(shopDomain, token, INVENTORY_ACTIVATE_MUTATION, vars);
        JsonNode userErrors = data.path("inventoryActivate").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            // Idempotent: tolerate "already active" — the whole point of calling this
            // before every on_hand write is that repeats must be harmless.
            if (msg.toLowerCase().contains("already")) {
                log.debug("inventoryActivate already-active for item={} location={}", inventoryItemGid, locationGid);
                return;
            }
            throw new ShopifyException("inventoryActivate failed: " + msg);
        }
    }

    private static final String INVENTORY_ADJUST_QUANTITIES_MUTATION = """
            mutation InventoryAdjustQuantities($input: InventoryAdjustQuantitiesInput!, $idempotencyKey: String!) {
              inventoryAdjustQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { createdAt }
                userErrors { field message code }
              }
            }
            """;

    @Override
    public void adjustInventoryQuantities(String shopDomain, String token, String inventoryItemGid,
                                           String locationGid, int positiveDelta, String reason,
                                           String idempotencyKey) {
        // FR-17 v2 hard rule: increment-only. This check runs BEFORE any network call —
        // no code path may ever send a non-positive delta to Shopify.
        if (positiveDelta <= 0) {
            throw new IllegalArgumentException(
                "adjustInventoryQuantities requires a positive delta (FR-17 v2 increment-only); got " + positiveDelta);
        }

        // changeFromQuantity (Shopify API 2026-01+, InventoryChangeInput): the field is
        // nullable in the schema, but Shopify's resolver requires the argument KEY to be
        // present regardless of value — omitting it entirely fails with "InventoryChangeInput
        // must include the following argument: changeFromQuantity" (confirmed against
        // production 2026-07-31; the earlier spec's assumption that this argument had been
        // REMOVED in 2026-04 was backwards — it was ADDED in 2026-01 and is mandatory to at
        // least acknowledge). We read the CURRENT "available" quantity right before the write
        // and pass it as the compare-and-swap baseline — Shopify's own guidance is "avoid
        // opting out unless justified" — so a merchant editing stock in the admin UI between
        // our read and this write causes a clean CHANGE_FROM_QUANTITY_STALE userError (handled
        // by the existing generic error path below, recorded 'failed', retried next reconnect)
        // instead of silently layering our delta on a number we never actually observed.
        Integer changeFromQuantity;
        try {
            changeFromQuantity = currentAvailableQuantityOrNull(shopDomain, token, locationGid, inventoryItemGid);
        } catch (RuntimeException e) {
            throw new ShopifyAdjustFailedException(ShopifyAdjustFailedException.FailureClass.NEVER_SENT, null,
                "Could not read the current quantity before the adjust (adjust not sent): " + messageOf(e), e);
        }
        sendAdjust(shopDomain, token, inventoryItemGid, locationGid, positiveDelta, reason, idempotencyKey,
            changeFromQuantity);
    }

    @Override
    public void resendInventoryAdjustment(String shopDomain, String token, String inventoryItemGid,
                                          String locationGid, int positiveDelta, String reason,
                                          String idempotencyKey, Integer changeFromQuantity) {
        // Same FR-17 v2 guard as adjustInventoryQuantities — before any network call.
        if (positiveDelta <= 0) {
            throw new IllegalArgumentException(
                "resendInventoryAdjustment requires a positive delta (FR-17 v2 increment-only); got " + positiveDelta);
        }
        sendAdjust(shopDomain, token, inventoryItemGid, locationGid, positiveDelta, reason, idempotencyKey,
            changeFromQuantity);
    }

    /** The inventoryAdjustQuantities mutation itself, every failure classified (see
     *  ShopifyAdjustFailedException) and carrying the changeFromQuantity that was sent. */
    private void sendAdjust(String shopDomain, String token, String inventoryItemGid, String locationGid,
                            int positiveDelta, String reason, String idempotencyKey, Integer changeFromQuantity) {
        ObjectNode change = buildInventoryChange(inventoryItemGid, locationGid, positiveDelta, changeFromQuantity);
        ObjectNode input = mapper.createObjectNode()
            .put("reason", reason)
            .put("name", "available");
        input.set("changes", mapper.createArrayNode().add(change));
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        vars.put("idempotencyKey", idempotencyKey);

        JsonNode data;
        try {
            data = executeGraphQL(shopDomain, token, INVENTORY_ADJUST_QUANTITIES_MUTATION, vars);
        } catch (RuntimeException e) {
            throw new ShopifyAdjustFailedException(classifyAdjustFailure(e), changeFromQuantity, messageOf(e), e);
        }
        JsonNode userErrors = data.path("inventoryAdjustQuantities").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyAdjustFailedException(ShopifyAdjustFailedException.FailureClass.REJECTED,
                changeFromQuantity, "inventoryAdjustQuantities failed: " + msg, null);
        }
    }

    /** A 4xx or a GraphQL error means Shopify answered and did not apply it; a 5xx, a read
     *  timeout, a reset or anything unexpected may have been processed; a connection that was
     *  never made (refused, unknown host, connect timeout) never sent anything. */
    static ShopifyAdjustFailedException.FailureClass classifyAdjustFailure(Throwable e) {
        if (e instanceof ShopifyTransientException || e instanceof ShopifyAmbiguousException) {
            return ShopifyAdjustFailedException.FailureClass.AMBIGUOUS;
        }
        if (e instanceof ResourceAccessException) {
            for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
                if (c instanceof java.net.ConnectException || c instanceof java.net.UnknownHostException
                        || c instanceof java.net.NoRouteToHostException
                        || (c instanceof java.net.SocketTimeoutException
                            && c.getMessage() != null && c.getMessage().toLowerCase().contains("connect timed out"))) {
                    return ShopifyAdjustFailedException.FailureClass.NEVER_SENT;
                }
            }
            return ShopifyAdjustFailedException.FailureClass.AMBIGUOUS;
        }
        if (e instanceof ShopifyException) return ShopifyAdjustFailedException.FailureClass.REJECTED;
        return ShopifyAdjustFailedException.FailureClass.AMBIGUOUS;
    }

    ObjectNode buildInventoryChange(String inventoryItemGid, String locationGid,
                                     int positiveDelta, Integer changeFromQuantity) {
        ObjectNode change = mapper.createObjectNode()
            .put("delta", positiveDelta)
            .put("inventoryItemId", inventoryItemGid)
            .put("locationId", locationGid);
        if (changeFromQuantity != null) {
            change.put("changeFromQuantity", changeFromQuantity);
        } else {
            change.putNull("changeFromQuantity");
        }
        return change;
    }

    /** Reads the current "available" quantity for one inventory item at one location,
     *  immediately before a compare-and-swap write. Returns null if no matching level is
     *  found (e.g. never activated there) — the caller then explicitly opts out of the
     *  compare-and-swap check for that one call (Shopify's documented null-opt-out) rather
     *  than guessing a baseline. */
    private Integer currentAvailableQuantityOrNull(String shopDomain, String token,
                                                     String locationGid, String inventoryItemGid) {
        List<InventoryLevel> levels = fetchAvailableQuantities(shopDomain, token, locationGid,
            List.of(inventoryItemGid));
        for (InventoryLevel level : levels) {
            if (inventoryItemGid.equals(level.inventoryItemGid())) {
                return level.available();
            }
        }
        return null;
    }

    private static final String INVENTORY_MOVE_QUANTITIES_MUTATION = """
            mutation InventoryMoveQuantities($input: InventoryMoveQuantitiesInput!, $idempotencyKey: String!) {
              inventoryMoveQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { createdAt }
                userErrors { field message code }
              }
            }
            """;

    @Override
    public void moveAvailableToDamaged(String shopDomain, String token, String inventoryItemGid,
                                        String locationGid, int quantity, String reason,
                                        String idempotencyKey) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(
                "moveAvailableToDamaged requires a positive quantity; got " + quantity);
        }

        // Same changeFromQuantity argument-presence requirement as adjustInventoryQuantities
        // (see its comment) — InventoryMoveQuantityChange's "from"/"to" sub-objects each carry
        // their own optional changeFromQuantity. Proactive fix: not yet confirmed via a
        // production error for THIS mutation specifically, but Shopify's own changelog groups
        // inventoryAdjustQuantities and inventoryMoveQuantities under the same compare-and-swap
        // feature, so the same argument-presence requirement is expected here too. "from"
        // (available) gets the real current baseline, read fresh; "to" (damaged) has no
        // meaningful baseline to assert, so it explicitly opts out with null.
        Integer fromBaseline = currentAvailableQuantityOrNull(shopDomain, token, locationGid, inventoryItemGid);

        ObjectNode from = mapper.createObjectNode().put("name", "available").put("locationId", locationGid);
        if (fromBaseline != null) {
            from.put("changeFromQuantity", fromBaseline);
        } else {
            from.putNull("changeFromQuantity");
        }
        ObjectNode to = mapper.createObjectNode().put("name", "damaged").put("locationId", locationGid);
        to.putNull("changeFromQuantity");

        ObjectNode change = mapper.createObjectNode()
            .put("inventoryItemId", inventoryItemGid)
            .put("quantity", quantity);
        change.set("from", from);
        change.set("to", to);
        ObjectNode input = mapper.createObjectNode().put("reason", reason);
        input.set("changes", mapper.createArrayNode().add(change));
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        vars.put("idempotencyKey", idempotencyKey);

        JsonNode data = executeGraphQL(shopDomain, token, INVENTORY_MOVE_QUANTITIES_MUTATION, vars);
        JsonNode userErrors = data.path("inventoryMoveQuantities").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            // Insufficient-available and any other userError must fail cleanly here —
            // caller (ShopifyInventoryService) records it as a failed row, never retries forced.
            throw new ShopifyException("inventoryMoveQuantities failed: " + msg);
        }
    }

    private static final String STOCK_TAKE_WRITE_OFF_MUTATION = """
            mutation StockTakeWriteOff($input: InventoryAdjustQuantitiesInput!, $idempotencyKey: String!) {
              inventoryAdjustQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { createdAt }
                userErrors { field message code }
              }
            }
            """;

    /**
     * FR-21 Step 5. Deliberately self-contained — does NOT call the shared executeGraphQL()
     * helper (used by every other mutation in this class). executeGraphQL() wraps its call
     * in Retry.decorateSupplier(retry, ...), and `retry` is configured with
     * .retryExceptions(ResourceAccessException.class) — i.e. it silently re-sends up to 3
     * times on a connection/read timeout before any exception ever reaches the caller. That's
     * fine for every other mutation here (protected by @idempotent + a positive/move-only
     * shape), but it would defeat the whole point of this method: a caller that wants to
     * classify ONE unambiguous outcome (definitive vs. ambiguous) cannot do that if the
     * timeout it sees is already the LAST of three silent physical sends. Exactly one HTTP
     * attempt, full stop — see the interface javadoc for the retry-safety rationale.
     */
    @Override
    public void pushStockTakeWriteOff(String shopDomain, String token, List<InventoryDelta> deltas,
                                       String locationGid, String referenceDocumentUri, String idempotencyKey) {
        if (deltas == null || deltas.isEmpty()) {
            throw new IllegalArgumentException("pushStockTakeWriteOff requires at least one delta");
        }
        for (InventoryDelta d : deltas) {
            if (d.negativeDelta() >= 0) {
                throw new IllegalArgumentException(
                    "pushStockTakeWriteOff requires a negative delta per variant; got "
                    + d.negativeDelta() + " for " + d.inventoryItemGid());
            }
        }

        ArrayNode changes = mapper.createArrayNode();
        for (InventoryDelta d : deltas) {
            ObjectNode change = mapper.createObjectNode()
                .put("delta", d.negativeDelta())
                .put("inventoryItemId", d.inventoryItemGid())
                .put("locationId", locationGid);
            // One-shot batch write-off — no meaningful prior-read baseline to assert per
            // item in this call shape; explicitly opts out of the compare-and-swap check
            // (Shopify's documented null-opt-out), same as a never-activated item elsewhere.
            change.putNull("changeFromQuantity");
            changes.add(change);
        }

        ObjectNode input = mapper.createObjectNode()
            .put("reason", "shrinkage")
            .put("name", "available")
            .put("referenceDocumentUri", referenceDocumentUri);
        input.set("changes", changes);
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        vars.put("idempotencyKey", idempotencyKey);

        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json";
        ObjectNode body = mapper.createObjectNode()
            .put("query", STOCK_TAKE_WRITE_OFF_MUTATION).set("variables", vars);

        JsonNode response;
        try {
            response = restClient.post()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new ShopifyException(
                "Stock-take write-off HTTP " + e.getStatusCode().value()
                + " for " + shopDomain + ": " + e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException e) {
            // 5xx-with-a-response is still DEFINITIVE ("request rejected"), not ambiguous —
            // Shopify's edge responded. Ambiguous is reserved for no response at all (below).
            throw new ShopifyException(
                "Stock-take write-off HTTP " + e.getStatusCode().value() + " for " + shopDomain, e);
        } catch (ResourceAccessException e) {
            // No response reached this process (connect/read timeout, connection reset) —
            // genuinely unknown whether Shopify applied the decrement.
            throw new ShopifyAmbiguousException(
                "Stock-take write-off: no confirmed response from " + shopDomain, e);
        }

        if (response == null) {
            throw new ShopifyAmbiguousException(
                "Stock-take write-off: null response body from " + shopDomain);
        }

        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            // Includes THROTTLED — still a definitive "not applied" signal (Shopify explicitly
            // rejected this attempt). No inline wait-and-resend loop: the caller (job) owns
            // the retry decision, keeping "one call, one outcome" literally true here.
            String code = errors.get(0).path("extensions").path("code").asText("");
            throw new ShopifyException("Stock-take write-off GraphQL error"
                + (code.isBlank() ? "" : " (" + code + ")") + ": "
                + errors.get(0).path("message").asText());
        }

        JsonNode data = response.get("data");
        if (data == null) {
            throw new ShopifyAmbiguousException(
                "Stock-take write-off: response had no data field from " + shopDomain);
        }
        JsonNode userErrors = data.path("inventoryAdjustQuantities").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("Stock-take write-off failed: " + msg);
        }
    }

    private static final String TRANSFER_OUT_MUTATION = """
            mutation TransferOut($input: InventoryAdjustQuantitiesInput!, $idempotencyKey: String!) {
              inventoryAdjustQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { createdAt }
                userErrors { field message code }
              }
            }
            """;

    /**
     * Issue 2 — the fifth named decrement (transfer send in 'remove' mode). Deliberately
     * self-contained — see {@link #pushStockTakeWriteOff}'s javadoc for why this does not call the
     * shared executeGraphQL() helper (its silent retry on connection/read timeout would defeat
     * single-attempt-outcome classification). No code is shared with any other decrement method.
     */
    @Override
    public void pushTransferOut(String shopDomain, String token, List<InventoryDelta> deltas,
                                String locationGid, String referenceDocumentUri, String idempotencyKey) {
        if (deltas == null || deltas.isEmpty()) {
            throw new IllegalArgumentException("pushTransferOut requires at least one delta");
        }
        for (InventoryDelta d : deltas) {
            if (d.negativeDelta() >= 0) {
                throw new IllegalArgumentException(
                    "pushTransferOut requires a negative delta per variant; got "
                    + d.negativeDelta() + " for " + d.inventoryItemGid());
            }
        }

        ArrayNode changes = mapper.createArrayNode();
        for (InventoryDelta d : deltas) {
            ObjectNode change = mapper.createObjectNode()
                .put("delta", d.negativeDelta())
                .put("inventoryItemId", d.inventoryItemGid())
                .put("locationId", locationGid);
            change.putNull("changeFromQuantity");
            changes.add(change);
        }
        ObjectNode input = mapper.createObjectNode()
            .put("reason", "movement_created")
            .put("name", "available")
            .put("referenceDocumentUri", referenceDocumentUri);
        input.set("changes", changes);
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        vars.put("idempotencyKey", idempotencyKey);

        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json";
        ObjectNode body = mapper.createObjectNode()
            .put("query", TRANSFER_OUT_MUTATION).set("variables", vars);

        JsonNode response;
        try {
            response = restClient.post()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new ShopifyException(
                "Transfer out HTTP " + e.getStatusCode().value()
                + " for " + shopDomain + ": " + e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException e) {
            // Stricter than the stock-take push (approved 2026-10-09): a 5xx does not prove nothing
            // was applied — AMBIGUOUS, never re-sent automatically.
            throw new ShopifyAmbiguousException(
                "Transfer out HTTP " + e.getStatusCode().value() + " from " + shopDomain + " — not confirmed", e);
        } catch (ResourceAccessException e) {
            // Timeout, connection refused / reset — genuinely unknown.
            throw new ShopifyAmbiguousException(
                "Transfer out: no confirmed response from " + shopDomain, e);
        } catch (RestClientException e) {
            // Anything else the client couldn't classify (unreadable body, unknown status) — unknown.
            throw new ShopifyAmbiguousException(
                "Transfer out: unclassifiable response from " + shopDomain + ": " + e.getMessage(), e);
        }
        if (response == null) {
            throw new ShopifyAmbiguousException("Transfer out: null response body from " + shopDomain);
        }
        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            // THROTTLED-only with no data: Shopify did not execute the request — DEFINITE (approved
            // 2026-10-09), the sweep may re-send within its attempt limit.
            boolean onlyThrottled = true;
            for (JsonNode err : errors) {
                if (!"THROTTLED".equals(err.path("extensions").path("code").asText(""))) { onlyThrottled = false; break; }
            }
            JsonNode throttledData = response.get("data");
            if (onlyThrottled && (throttledData == null || throttledData.isNull())) {
                throw new ShopifyException("Transfer out GraphQL error (THROTTLED): not executed by Shopify");
            }
            // Any other top-level GraphQL error (or THROTTLED mixed with another error / with data) is
            // neither a 4xx nor a userError — AMBIGUOUS: a person checks Shopify, nothing re-sends.
            String code = errors.get(0).path("extensions").path("code").asText("");
            throw new ShopifyAmbiguousException("Transfer out GraphQL error"
                + (code.isBlank() ? "" : " (" + code + ")") + ": "
                + errors.get(0).path("message").asText() + " — not confirmed");
        }
        JsonNode data = response.get("data");
        if (data == null) {
            throw new ShopifyAmbiguousException("Transfer out: response had no data field from " + shopDomain);
        }
        JsonNode userErrors = data.path("inventoryAdjustQuantities").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("Transfer out failed: " + msg);
        }
    }

    private static final String VOID_CORRECTION_MUTATION = """
            mutation VoidCorrection($input: InventoryAdjustQuantitiesInput!, $idempotencyKey: String!) {
              inventoryAdjustQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { createdAt }
                userErrors { field message code }
              }
            }
            """;

    /**
     * FR-13.x. Deliberately self-contained — see {@link #pushStockTakeWriteOff}'s javadoc for
     * why this does not call the shared executeGraphQL() helper (its silent 3x retry on
     * connection/read timeout would defeat single-attempt-outcome classification here). No
     * code is shared with pushStockTakeWriteOff or pushHoldEnter — three separate methods,
     * three separate call sites, no general-purpose decrement helper (CLAUDE.md invariant).
     */
    @Override
    public void pushVoidCorrection(String shopDomain, String token, String inventoryItemGid,
                                    String locationGid, int negativeDelta, String referenceDocumentUri,
                                    String idempotencyKey) {
        if (negativeDelta >= 0) {
            throw new IllegalArgumentException(
                "pushVoidCorrection requires a negative delta; got " + negativeDelta);
        }

        ObjectNode change = mapper.createObjectNode()
            .put("delta", negativeDelta)
            .put("inventoryItemId", inventoryItemGid)
            .put("locationId", locationGid);
        // One-shot single-piece write-off — no meaningful prior-read baseline to assert;
        // explicitly opts out of the compare-and-swap check (Shopify's documented null-opt-out).
        change.putNull("changeFromQuantity");

        ObjectNode input = mapper.createObjectNode()
            .put("reason", "other")
            .put("name", "available")
            .put("referenceDocumentUri", referenceDocumentUri);
        input.set("changes", mapper.createArrayNode().add(change));
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        vars.put("idempotencyKey", idempotencyKey);

        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json";
        ObjectNode body = mapper.createObjectNode()
            .put("query", VOID_CORRECTION_MUTATION).set("variables", vars);

        JsonNode response;
        try {
            response = restClient.post()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new ShopifyException(
                "Void correction HTTP " + e.getStatusCode().value()
                + " for " + shopDomain + ": " + e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException e) {
            throw new ShopifyException(
                "Void correction HTTP " + e.getStatusCode().value() + " for " + shopDomain, e);
        } catch (ResourceAccessException e) {
            throw new ShopifyAmbiguousException(
                "Void correction: no confirmed response from " + shopDomain, e);
        }

        if (response == null) {
            throw new ShopifyAmbiguousException(
                "Void correction: null response body from " + shopDomain);
        }

        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            String code = errors.get(0).path("extensions").path("code").asText("");
            throw new ShopifyException("Void correction GraphQL error"
                + (code.isBlank() ? "" : " (" + code + ")") + ": "
                + errors.get(0).path("message").asText());
        }

        JsonNode data = response.get("data");
        if (data == null) {
            throw new ShopifyAmbiguousException(
                "Void correction: response had no data field from " + shopDomain);
        }
        JsonNode userErrors = data.path("inventoryAdjustQuantities").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("Void correction failed: " + msg);
        }
    }

    private static final String HOLD_ENTER_MUTATION = """
            mutation HoldEnter($input: InventoryAdjustQuantitiesInput!, $idempotencyKey: String!) {
              inventoryAdjustQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { createdAt }
                userErrors { field message code }
              }
            }
            """;

    /**
     * FR-13.x. Deliberately self-contained — see {@link #pushStockTakeWriteOff}'s javadoc for
     * why this does not call the shared executeGraphQL() helper. No code is shared with
     * pushStockTakeWriteOff or pushVoidCorrection — three separate methods, three separate
     * call sites, no general-purpose decrement helper (CLAUDE.md invariant).
     */
    @Override
    public void pushHoldEnter(String shopDomain, String token, String inventoryItemGid,
                               String locationGid, int negativeDelta, String referenceDocumentUri,
                               String idempotencyKey) {
        if (negativeDelta >= 0) {
            throw new IllegalArgumentException(
                "pushHoldEnter requires a negative delta; got " + negativeDelta);
        }

        ObjectNode change = mapper.createObjectNode()
            .put("delta", negativeDelta)
            .put("inventoryItemId", inventoryItemGid)
            .put("locationId", locationGid);
        change.putNull("changeFromQuantity");

        ObjectNode input = mapper.createObjectNode()
            .put("reason", "other")
            .put("name", "available")
            .put("referenceDocumentUri", referenceDocumentUri);
        input.set("changes", mapper.createArrayNode().add(change));
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        vars.put("idempotencyKey", idempotencyKey);

        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json";
        ObjectNode body = mapper.createObjectNode()
            .put("query", HOLD_ENTER_MUTATION).set("variables", vars);

        JsonNode response;
        try {
            response = restClient.post()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new ShopifyException(
                "Hold enter HTTP " + e.getStatusCode().value()
                + " for " + shopDomain + ": " + e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException e) {
            throw new ShopifyException(
                "Hold enter HTTP " + e.getStatusCode().value() + " for " + shopDomain, e);
        } catch (ResourceAccessException e) {
            throw new ShopifyAmbiguousException(
                "Hold enter: no confirmed response from " + shopDomain, e);
        }

        if (response == null) {
            throw new ShopifyAmbiguousException(
                "Hold enter: null response body from " + shopDomain);
        }

        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            String code = errors.get(0).path("extensions").path("code").asText("");
            throw new ShopifyException("Hold enter GraphQL error"
                + (code.isBlank() ? "" : " (" + code + ")") + ": "
                + errors.get(0).path("message").asText());
        }

        JsonNode data = response.get("data");
        if (data == null) {
            throw new ShopifyAmbiguousException(
                "Hold enter: response had no data field from " + shopDomain);
        }
        JsonNode userErrors = data.path("inventoryAdjustQuantities").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("Hold enter failed: " + msg);
        }
    }

    private static final String EXCHANGE_DISPATCH_MUTATION = """
            mutation ExchangeDispatch($input: InventoryAdjustQuantitiesInput!, $idempotencyKey: String!) {
              inventoryAdjustQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { createdAt }
                userErrors { field message code }
              }
            }
            """;

    /**
     * Step 5a. Deliberately self-contained — see {@link #pushStockTakeWriteOff}'s javadoc for
     * why this does not call the shared executeGraphQL() helper (its silent 3x retry would
     * defeat single-attempt outcome classification). No code is shared with
     * pushStockTakeWriteOff, pushVoidCorrection or pushHoldEnter — four separate methods, four
     * separate call sites, no general-purpose decrement helper (CLAUDE.md invariant).
     */
    @Override
    public void pushExchangeDispatch(String shopDomain, String token, String inventoryItemGid,
                                      String locationGid, int negativeDelta, String referenceDocumentUri,
                                      String idempotencyKey) {
        if (negativeDelta >= 0) {
            throw new IllegalArgumentException(
                "pushExchangeDispatch requires a negative delta; got " + negativeDelta);
        }

        ObjectNode change = mapper.createObjectNode()
            .put("delta", negativeDelta)
            .put("inventoryItemId", inventoryItemGid)
            .put("locationId", locationGid);
        // One-shot single-piece decrement — no meaningful prior-read baseline to assert;
        // explicitly opts out of the compare-and-swap check (Shopify's documented null-opt-out).
        change.putNull("changeFromQuantity");

        ObjectNode input = mapper.createObjectNode()
            .put("reason", "other")
            .put("name", "available")
            .put("referenceDocumentUri", referenceDocumentUri);
        input.set("changes", mapper.createArrayNode().add(change));
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        vars.put("idempotencyKey", idempotencyKey);

        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json";
        ObjectNode body = mapper.createObjectNode()
            .put("query", EXCHANGE_DISPATCH_MUTATION).set("variables", vars);

        JsonNode response;
        try {
            response = restClient.post()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new ShopifyException(
                "Exchange dispatch HTTP " + e.getStatusCode().value()
                + " for " + shopDomain + ": " + e.getResponseBodyAsString(), e);
        } catch (HttpServerErrorException e) {
            throw new ShopifyException(
                "Exchange dispatch HTTP " + e.getStatusCode().value() + " for " + shopDomain, e);
        } catch (ResourceAccessException e) {
            throw new ShopifyAmbiguousException(
                "Exchange dispatch: no confirmed response from " + shopDomain, e);
        }

        if (response == null) {
            throw new ShopifyAmbiguousException(
                "Exchange dispatch: null response body from " + shopDomain);
        }

        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            String code = errors.get(0).path("extensions").path("code").asText("");
            throw new ShopifyException("Exchange dispatch GraphQL error"
                + (code.isBlank() ? "" : " (" + code + ")") + ": "
                + errors.get(0).path("message").asText());
        }

        JsonNode data = response.get("data");
        if (data == null) {
            throw new ShopifyAmbiguousException(
                "Exchange dispatch: response had no data field from " + shopDomain);
        }
        JsonNode userErrors = data.path("inventoryAdjustQuantities").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("Exchange dispatch failed: " + msg);
        }
    }

    private static final String INVENTORY_LEVELS_QUERY = """
            query InventoryLevelsAtLocation($ids: [ID!]!, $locationId: ID!) {
              nodes(ids: $ids) {
                ... on InventoryItem {
                  id
                  inventoryLevel(locationId: $locationId) {
                    quantities(names: ["available"]) { name quantity }
                  }
                }
              }
            }
            """;

    @Override
    public List<InventoryLevel> fetchAvailableQuantities(String shopDomain, String token,
                                                          String locationGid, List<String> inventoryItemGids) {
        List<InventoryLevel> out = new ArrayList<>();
        // nodes(ids:) takes at most 250 ids (Shopify: every input array is capped at 250 —
        // https://shopify.dev/docs/api/usage/limits "Input limits"); one read per chunk.
        for (List<String> chunk : chunks(inventoryItemGids, MAX_INPUT_ARRAY)) {
            fetchAvailableQuantitiesChunk(shopDomain, token, locationGid, chunk, out);
        }
        return out;
    }

    private void fetchAvailableQuantitiesChunk(String shopDomain, String token, String locationGid,
                                               List<String> inventoryItemGids, List<InventoryLevel> out) {
        ObjectNode vars = mapper.createObjectNode();
        vars.set("ids", mapper.valueToTree(inventoryItemGids));
        vars.put("locationId", locationGid);

        JsonNode data = executeGraphQL(shopDomain, token, INVENTORY_LEVELS_QUERY, vars);
        JsonNode nodes = data.path("nodes");
        if (!nodes.isArray()) return;
        for (JsonNode node : nodes) {
            if (node.isNull() || node.isMissingNode()) continue;
            String itemGid = node.path("id").asText(null);
            if (itemGid == null) continue;
            int available = 0;
            JsonNode level = node.path("inventoryLevel");
            if (!level.isMissingNode() && !level.isNull()) {
                for (JsonNode q : level.path("quantities")) {
                    if ("available".equals(q.path("name").asText(""))) {
                        available = q.path("quantity").asInt(0);
                    }
                }
            }
            out.add(new InventoryLevel(itemGid, available));
        }
    }

    static <T> List<List<T>> chunks(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        return out;
    }

    // ---- batch item-id resolution + batch activation (activation perf) -----

    private static final String VARIANT_INVENTORY_ITEMS_QUERY = """
            query VariantInventoryItems($ids: [ID!]!) {
              nodes(ids: $ids) {
                ... on ProductVariant { id inventoryItem { id } }
              }
            }
            """;

    @Override
    public Map<String, String> resolveInventoryItemIds(String shopDomain, String token, List<String> variantGids) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (List<String> chunk : chunks(variantGids, MAX_INPUT_ARRAY)) {
            ObjectNode vars = mapper.createObjectNode();
            vars.set("ids", mapper.valueToTree(chunk));
            JsonNode nodes = executeGraphQL(shopDomain, token, VARIANT_INVENTORY_ITEMS_QUERY, vars).path("nodes");
            for (JsonNode node : nodes) {
                if (node == null || node.isNull()) continue;
                String variantGid = node.path("id").asText(null);
                String itemGid = nullableText(node.path("inventoryItem"), "id");
                if (variantGid != null && itemGid != null) out.put(variantGid, itemGid);
            }
        }
        return out;
    }

    /**
     * N aliased inventoryActivate fields in one mutation document. Each alias has its own item
     * variable and its own @idempotent key variable; the location is shared. No quantity argument
     * (available / onHand) is ever part of the document — activation only creates the level at 0.
     */
    static String batchActivateMutation(int n) {
        StringBuilder sb = new StringBuilder("mutation BatchInventoryActivate($locationId: ID!");
        for (int i = 0; i < n; i++) sb.append(", $i").append(i).append(": ID!, $k").append(i).append(": String!");
        sb.append(") {\n");
        for (int i = 0; i < n; i++) {
            sb.append("  a").append(i).append(": inventoryActivate(inventoryItemId: $i").append(i)
              .append(", locationId: $locationId) @idempotent(key: $k").append(i).append(") {")
              .append(" inventoryLevel { id } userErrors { field message } }\n");
        }
        return sb.append("}\n").toString();
    }

    @Override
    public List<ActivationResult> activateInventoryItems(String shopDomain, String token, String locationGid,
                                                         List<ActivationRequest> requests) {
        List<List<ActivationRequest>> batches = chunks(requests, ACTIVATION_BATCH_SIZE);
        if (batches.size() <= 1) {
            return batches.isEmpty() ? List.of() : activateBatch(shopDomain, token, locationGid, batches.get(0));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
            Math.min(ACTIVATION_CONCURRENCY, batches.size()));
        try {
            List<java.util.concurrent.Future<List<ActivationResult>>> futures = new ArrayList<>();
            for (List<ActivationRequest> batch : batches) {
                futures.add(pool.submit(() -> activateBatch(shopDomain, token, locationGid, batch)));
            }
            List<ActivationResult> out = new ArrayList<>(requests.size());
            for (int b = 0; b < futures.size(); b++) {
                try {
                    out.addAll(futures.get(b).get());
                } catch (java.util.concurrent.ExecutionException e) {
                    // activateBatch never throws; kept so an unexpected error still yields one
                    // result per request instead of losing the whole batch's accounting.
                    out.addAll(failAll(batches.get(b), messageOf(e.getCause())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ShopifyException("Interrupted during batch activation", e);
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<ActivationResult> activateBatch(String shopDomain, String token, String locationGid,
                                                 List<ActivationRequest> batch) {
        ObjectNode vars = mapper.createObjectNode().put("locationId", locationGid);
        for (int i = 0; i < batch.size(); i++) {
            vars.put("i" + i, batch.get(i).inventoryItemGid());
            vars.put("k" + i, batch.get(i).idempotencyKey());
        }
        // Reserve the batch's own cost (~10 per mutation + its selection) so consecutive batches
        // are paced to fit. Two in flight can still collide; a batch that exhausts the THROTTLED
        // retries is sent again (inventoryActivate is idempotent — same keys, same parameters).
        int reserve = ACTIVATION_COST_PER_ALIAS * batch.size();
        JsonNode response = null;
        for (int attempt = 1; response == null; attempt++) {
            try {
                response = postGraphQL(shopDomain, token, batchActivateMutation(batch.size()), vars, reserve);
            } catch (ShopifyException e) {
                boolean throttled = e.getMessage() != null && e.getMessage().startsWith("Shopify API throttled");
                if (!throttled || attempt >= ACTIVATION_THROTTLE_ATTEMPTS) return failAll(batch, messageOf(e));
                log.warn("Batch activation throttled — resending batch of {} (attempt {}/{})",
                    batch.size(), attempt + 1, ACTIVATION_THROTTLE_ATTEMPTS);
            } catch (RuntimeException e) {
                return failAll(batch, messageOf(e));
            }
        }

        // Path-scoped errors belong to one alias; an error without a path belongs to the request.
        Map<String, String> aliasErrors = new java.util.HashMap<>();
        String requestError = null;
        for (JsonNode err : response.path("errors")) {
            JsonNode path = err.path("path");
            String msg = err.path("message").asText("unknown error");
            if (path.isArray() && !path.isEmpty()) aliasErrors.putIfAbsent(path.get(0).asText(), msg);
            else if (requestError == null) requestError = msg;
        }
        JsonNode data = response.path("data");

        List<ActivationResult> out = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            String item = batch.get(i).inventoryItemGid();
            String alias = "a" + i;
            JsonNode node = data.path(alias);
            String error;
            if (aliasErrors.containsKey(alias)) {
                error = "inventoryActivate failed: " + aliasErrors.get(alias);
            } else if (node.isMissingNode() || node.isNull()) {
                error = requestError != null ? "Shopify GraphQL error: " + requestError
                                             : "inventoryActivate returned no result";
            } else {
                JsonNode userErrors = node.path("userErrors");
                String msg = userErrors.isArray() && !userErrors.isEmpty()
                    ? userErrors.get(0).path("message").asText("unknown error") : null;
                // Same tolerance as the single form: "already active" is success.
                error = msg == null || msg.toLowerCase().contains("already") ? null
                      : "inventoryActivate failed: " + msg;
            }
            out.add(new ActivationResult(item, error));
        }
        return out;
    }

    private static List<ActivationResult> failAll(List<ActivationRequest> batch, String error) {
        List<ActivationResult> out = new ArrayList<>(batch.size());
        for (ActivationRequest r : batch) out.add(new ActivationResult(r.inventoryItemGid(), error));
        return out;
    }

    private static String messageOf(Throwable e) {
        return e == null ? "unknown error" : e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    @Override
    public String fetchGrantedScopes(String shopDomain, String token) {
        try {
            String url = "https://" + shopDomain + "/admin/oauth/access_scopes.json";
            JsonNode body = restClient.get()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .retrieve()
                .body(JsonNode.class);
            if (body == null) return null;
            JsonNode scopes = body.path("access_scopes");
            if (!scopes.isArray()) return null;
            List<String> handles = new ArrayList<>();
            for (JsonNode scope : scopes) {
                String handle = scope.path("handle").asText(null);
                if (handle != null) handles.add(handle);
            }
            return String.join(",", handles);
        } catch (Exception e) {
            log.warn("Could not fetch granted scopes for {}: {} — caller must not overwrite a " +
                      "previously-known-good access_token_scopes value with this null result",
                      shopDomain, e.getMessage());
            return null;
        }
    }

    // ---- GraphQL execution + throttle handling --------------------------

    @Override
    public JsonNode executeGraphQLPublic(String shopDomain, String token, String query, JsonNode variables) {
        return executeGraphQL(shopDomain, token, query, variables);
    }

    private JsonNode executeGraphQL(String shopDomain, String token, String query, JsonNode variables) {
        JsonNode response = postGraphQL(shopDomain, token, query, variables);
        JsonNode errors = response.get("errors");
        if (errors != null && errors.isArray() && errors.size() > 0) {
            throw new ShopifyException("Shopify GraphQL error: " + errors.get(0).path("message").asText());
        }
        JsonNode data = response.get("data");
        if (data == null) throw new ShopifyException("Shopify GraphQL response has no data field");
        return data;
    }

    /**
     * The transport under executeGraphQL(): HTTP (Resilience4j retry on connection errors), 4xx/5xx
     * translation, THROTTLED sleep-and-retry, and the proactive slow-down when the cost bucket runs
     * low. Returns the WHOLE response (data + errors) so a caller that sends several aliased fields
     * can attribute a path-scoped error to its own alias instead of failing the others.
     */
    private JsonNode postGraphQL(String shopDomain, String token, String query, JsonNode variables) {
        return postGraphQL(shopDomain, token, query, variables, 200);
    }

    /**
     * @param reserveCost the proactive slow-down waits until at least this many cost points are
     *                    back in the bucket (never below 200). A caller that sends a run of
     *                    same-sized expensive requests (batch activation: ~11 points per alias)
     *                    passes that request's cost, so the next one fits instead of throttling.
     */
    private JsonNode postGraphQL(String shopDomain, String token, String query, JsonNode variables, int reserveCost) {
        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json";
        ObjectNode body = mapper.createObjectNode().put("query", query).set("variables", variables);

        for (int attempt = 0; attempt <= MAX_THROTTLE_RETRIES; attempt++) {
            // HttpClientErrorException (4xx) and HttpServerErrorException (5xx) are NOT in
            // Resilience4j retryExceptions, so they propagate from .get() on the first attempt.
            // Translate them to ShopifyException/ShopifyTransientException here so callers
            // always receive a typed Shopify exception instead of a raw Spring HTTP exception.
            // Without this translation, 4xx leaks through registerWebhook's catch(ShopifyException)
            // and reaches JobRunr as an unhandled exception, triggering the retry storm.
            JsonNode response;
            try {
                response = Retry.decorateSupplier(retry, () ->
                    restClient.post()
                        .uri(url)
                        .header("X-Shopify-Access-Token", token)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class)
                ).get();
            } catch (HttpClientErrorException e) {
                throw new ShopifyException(
                        "Shopify GraphQL HTTP " + e.getStatusCode().value()
                        + " for " + shopDomain + ": " + e.getResponseBodyAsString(), e);
            } catch (HttpServerErrorException e) {
                throw new ShopifyTransientException(
                        "Shopify GraphQL HTTP " + e.getStatusCode().value()
                        + " for " + shopDomain, e);
            }

            if (response == null) throw new ShopifyException("Shopify GraphQL returned null response");

            // Check for THROTTLED error before inspecting data.
            JsonNode errors = response.get("errors");
            boolean hasErrors = errors != null && errors.isArray() && errors.size() > 0;
            if (hasErrors) {
                String code = errors.get(0).path("extensions").path("code").asText("");
                if ("THROTTLED".equals(code)) {
                    if (attempt == MAX_THROTTLE_RETRIES) {
                        throw new ShopifyException("Shopify API throttled after " + attempt + " retries");
                    }
                    long waitMs = computeThrottleWaitMs(response);
                    log.warn("Shopify THROTTLED — sleeping {}ms before retry {}/{}", waitMs, attempt + 1, MAX_THROTTLE_RETRIES);
                    sleep(waitMs);
                    continue;
                }
                // A plain error response (no data) goes straight back to the caller, unpaced —
                // exactly as before this method was split out of executeGraphQL().
                JsonNode data = response.get("data");
                if (data == null || data.isNull()) return response;
            }

            // Proactively slow down if the cost bucket is running low (< 200 units remaining).
            // Prevents hitting THROTTLED on the next request in a tight pagination loop.
            JsonNode throttle = response.path("extensions").path("cost").path("throttleStatus");
            if (!throttle.isMissingNode()) {
                double available = throttle.path("currentlyAvailable").asDouble(1000);
                double restoreRate = throttle.path("restoreRate").asDouble(50);
                double reserve = Math.max(200, reserveCost);
                if (available < reserve && restoreRate > 0) {
                    long waitMs = (long) ((reserve - available) / restoreRate * 1000) + THROTTLE_MIN_WAIT_MS;
                    log.debug("Shopify cost bucket low ({} available) — sleeping {}ms", (long) available, waitMs);
                    sleep(waitMs);
                }
            }
            return response;
        }
        throw new ShopifyException("Unreachable: throttle retry loop exhausted");
    }

    // ---- response parsing -----------------------------------------------

    private List<Variant> parseVariants(JsonNode conn) {
        List<Variant> variants = new ArrayList<>();
        for (JsonNode ve : conn.path("edges")) {
            JsonNode vn = ve.path("node");
            String priceStr = vn.path("price").asText(null);
            variants.add(new Variant(
                    vn.path("id").asText(),
                    nullableText(vn, "sku"),
                    vn.path("title").asText(""),
                    priceStr != null ? new BigDecimal(priceStr) : null,
                    nullableText(vn.path("inventoryItem"), "id")));
        }
        return variants;
    }

    private List<Order> parseOrders(JsonNode conn) {
        List<Order> out = new ArrayList<>();
        for (JsonNode edge : conn.path("edges")) {
            JsonNode node = edge.path("node");

            // Build B: customer PII stays in the node (raw) — ShopifySyncService.UPSERT_ORDER reads it with
            // the shared SQL precedence (V144). The record's three PII fields are left null.
            String phone = null;
            String customerName = null;
            JsonNode shippingAddr = null;

            String priceStr = node.path("currentTotalPriceSet").path("shopMoney").path("amount").asText(null);
            BigDecimal totalPrice = priceStr != null ? new BigDecimal(priceStr) : BigDecimal.ZERO;

            List<String> gateways = new ArrayList<>();
            for (JsonNode gw : node.path("paymentGatewayNames")) gateways.add(gw.asText());

            List<LineItem> lines = new ArrayList<>();
            for (JsonNode le : node.path("lineItems").path("edges")) {
                JsonNode ln = le.path("node");
                String variantGid = ln.path("variant").path("id").asText(null);
                lines.add(new LineItem(ln.path("id").asText(), ln.path("quantity").asInt(1), variantGid));
            }

            Instant createdAt = Instant.parse(node.path("createdAt").asText());

            out.add(new Order(
                    node.path("id").asText(),
                    node.path("name").asText(),
                    customerName,
                    phone,
                    shippingAddr,
                    node.path("displayFinancialStatus").asText(null),
                    gateways,
                    totalPrice,
                    lines,
                    createdAt,
                    node));
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------

    private long computeThrottleWaitMs(JsonNode response) {
        JsonNode cost = response.path("extensions").path("cost");
        double requested  = cost.path("requestedQueryCost").asDouble(50);
        double available  = cost.path("throttleStatus").path("currentlyAvailable").asDouble(0);
        double restoreRate = cost.path("throttleStatus").path("restoreRate").asDouble(50);
        if (restoreRate <= 0) return 2000;
        return Math.max((long) ((requested - available) / restoreRate * 1000) + THROTTLE_MIN_WAIT_MS, 1000);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n == null || n.isNull()) ? null : n.asText(null);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ShopifyException("Interrupted during throttle wait", e); }
    }
}
