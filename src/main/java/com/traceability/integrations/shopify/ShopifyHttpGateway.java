package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final int THROTTLE_MIN_WAIT_MS = 500;

    private static final String PRODUCTS_QUERY = """
            query ProductsPage($cursor: String) {
              products(first: 50, after: $cursor, query: "status:active") {
                pageInfo { hasNextPage endCursor }
                edges {
                  node {
                    id title status
                    variants(first: 50) {
                      edges {
                        node { id sku title price }
                      }
                    }
                  }
                }
              }
            }
            """;

    private static final String ORDERS_QUERY = """
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
                  }
                }
              }
            }
            """;

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

    ShopifyHttpGateway(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${shopify.api-version}") String apiVersion,
            @Value("${shopify.client-id}") String clientId,
            @Value("${shopify.client-secret}") String clientSecret) {
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
        this.tokenRestClient = RestClient.builder().requestFactory(tokenFactory).build();
    }

    // ---- public API -----------------------------------------------------

    @Override
    public String validateShop(String shopDomain, String token) {
        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/shop.json";
        JsonNode body = Retry.decorateSupplier(retry, () ->
            restClient.get()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .retrieve()
                .body(JsonNode.class)
        ).get();
        if (body == null || !body.has("shop")) {
            throw new ShopifyException("Shopify /shop.json returned unexpected response");
        }
        return body.get("shop").path("name").asText("unknown");
    }

    @Override
    public ShopInfo fetchShop(String shopDomain, String token) {
        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/shop.json";
        JsonNode body = Retry.decorateSupplier(retry, () ->
            restClient.get()
                .uri(url)
                .header("X-Shopify-Access-Token", token)
                .retrieve()
                .body(JsonNode.class)
        ).get();
        if (body == null || !body.has("shop")) {
            throw new ShopifyException("Shopify /shop.json returned unexpected response for fetchShop");
        }
        JsonNode shop = body.get("shop");
        return new ShopInfo(
            nullableText(shop, "email"),
            shop.path("name").asText(""),
            shop.path("timezone").asText("UTC"));
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
        return new TokenResponse(
            resp.get("access_token").asText(),
            resp.has("refresh_token") ? resp.get("refresh_token").asText() : null,
            resp.path("expires_in").asLong(3600),
            resp.path("refresh_token_expires_in").asLong(7776000));
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
                resp.path("refresh_token_expires_in").asLong(7776000));
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
    public ProductPage fetchProductsPage(String shopDomain, String token, String cursor) {
        ObjectNode vars = mapper.createObjectNode();
        if (cursor != null) vars.put("cursor", cursor);
        JsonNode data = executeGraphQL(shopDomain, token, PRODUCTS_QUERY, vars);
        JsonNode conn = data.path("products");
        return new ProductPage(parseProducts(conn), conn.path("pageInfo").path("hasNextPage").asBoolean(),
                conn.path("pageInfo").path("endCursor").asText(null));
    }

    @Override
    public OrderPage fetchOrdersPage(String shopDomain, String token, String cursor, String createdAfter) {
        ObjectNode vars = mapper.createObjectNode();
        if (cursor != null) vars.put("cursor", cursor);
        vars.put("queryStr", "created_at:>" + createdAfter);
        JsonNode data = executeGraphQL(shopDomain, token, ORDERS_QUERY, vars);
        JsonNode conn = data.path("orders");
        return new OrderPage(parseOrders(conn), conn.path("pageInfo").path("hasNextPage").asBoolean(),
                conn.path("pageInfo").path("endCursor").asText(null));
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
                    "client_id",          clientId,
                    "client_secret",      clientSecret,
                    "grant_type",         "urn:ietf:params:oauth:grant-type:token-exchange",
                    "subject_token",      sessionToken,
                    "subject_token_type", "urn:ietf:params:oauth:token-type:id_token"))
                .retrieve()
                .body(JsonNode.class);
            if (resp == null || !resp.has("access_token")) {
                throw new ShopifyException(
                    "Session token exchange response missing access_token from " + shopDomain);
            }
            return new TokenResponse(
                resp.get("access_token").asText(),
                resp.has("refresh_token") ? resp.get("refresh_token").asText() : null,
                resp.path("expires_in").asLong(3600),
                resp.path("refresh_token_expires_in").asLong(7776000));
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

    // ---- GraphQL execution + throttle handling --------------------------

    private JsonNode executeGraphQL(String shopDomain, String token, String query, JsonNode variables) {
        String url = "https://" + shopDomain + "/admin/api/" + apiVersion + "/graphql.json";
        ObjectNode body = mapper.createObjectNode().put("query", query).set("variables", variables);

        for (int attempt = 0; attempt <= MAX_THROTTLE_RETRIES; attempt++) {
            JsonNode response = Retry.decorateSupplier(retry, () ->
                restClient.post()
                    .uri(url)
                    .header("X-Shopify-Access-Token", token)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class)
            ).get();

            if (response == null) throw new ShopifyException("Shopify GraphQL returned null response");

            // Check for THROTTLED error before inspecting data.
            JsonNode errors = response.get("errors");
            if (errors != null && errors.isArray() && errors.size() > 0) {
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
                throw new ShopifyException("Shopify GraphQL error: " + errors.get(0).path("message").asText());
            }

            // Proactively slow down if the cost bucket is running low (< 200 units remaining).
            // Prevents hitting THROTTLED on the next request in a tight pagination loop.
            JsonNode throttle = response.path("extensions").path("cost").path("throttleStatus");
            if (!throttle.isMissingNode()) {
                double available = throttle.path("currentlyAvailable").asDouble(1000);
                double restoreRate = throttle.path("restoreRate").asDouble(50);
                if (available < 200 && restoreRate > 0) {
                    long waitMs = (long) ((200 - available) / restoreRate * 1000) + THROTTLE_MIN_WAIT_MS;
                    log.debug("Shopify cost bucket low ({} available) — sleeping {}ms", (long) available, waitMs);
                    sleep(waitMs);
                }
            }

            JsonNode data = response.get("data");
            if (data == null) throw new ShopifyException("Shopify GraphQL response has no data field");
            return data;
        }
        throw new ShopifyException("Unreachable: throttle retry loop exhausted");
    }

    // ---- response parsing -----------------------------------------------

    private List<Product> parseProducts(JsonNode conn) {
        List<Product> out = new ArrayList<>();
        for (JsonNode edge : conn.path("edges")) {
            JsonNode node = edge.path("node");
            List<Variant> variants = new ArrayList<>();
            for (JsonNode ve : node.path("variants").path("edges")) {
                JsonNode vn = ve.path("node");
                String priceStr = vn.path("price").asText(null);
                variants.add(new Variant(
                        vn.path("id").asText(),
                        nullableText(vn, "sku"),
                        vn.path("title").asText(""),
                        priceStr != null ? new BigDecimal(priceStr) : null));
            }
            out.add(new Product(
                    node.path("id").asText(),
                    node.path("title").asText(""),
                    node.path("status").asText("active").toLowerCase(),
                    variants));
        }
        return out;
    }

    private List<Order> parseOrders(JsonNode conn) {
        List<Order> out = new ArrayList<>();
        for (JsonNode edge : conn.path("edges")) {
            JsonNode node = edge.path("node");

            // shippingAddress requires Protected Customer Data access (Shopify Partner Dashboard →
            // App setup → Protected customer data). Set null until token is regenerated with PII scope;
            // the raw column preserves the full Shopify response for later backfill.
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
