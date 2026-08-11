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
    private static final int THROTTLE_MIN_WAIT_MS = 500;

    private static final String PRODUCTS_QUERY = """
            query ProductsPage($cursor: String) {
              products(first: 50, after: $cursor, query: "status:active") {
                pageInfo { hasNextPage endCursor }
                edges {
                  node {
                    id title status
                    featuredImage { url }
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
            resp.path("refresh_token_expires_in").asLong(7776000),
            resp.path("scope").asText(null));
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
                    "client_id",            clientId,
                    "client_secret",        clientSecret,
                    "grant_type",           "urn:ietf:params:oauth:grant-type:token-exchange",
                    "subject_token",        sessionToken,
                    "subject_token_type",   "urn:ietf:params:oauth:token-type:id_token",
                    // Request an expiring offline token (with refresh_token) — NOT the permanent
                    // custom-app token that Shopify returns by default for unauthenticated exchanges.
                    // Shopify now rejects permanent tokens (403) for all Admin API calls.
                    "requested_token_type", "urn:shopify:params:oauth:token-type:offline-access-token"))
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
                resp.path("refresh_token_expires_in").asLong(7776000),
                resp.path("scope").asText(null));
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
        Integer changeFromQuantity = currentAvailableQuantityOrNull(shopDomain, token, locationGid, inventoryItemGid);

        ObjectNode change = buildInventoryChange(inventoryItemGid, locationGid, positiveDelta, changeFromQuantity);
        ObjectNode input = mapper.createObjectNode()
            .put("reason", reason)
            .put("name", "available");
        input.set("changes", mapper.createArrayNode().add(change));
        ObjectNode vars = mapper.createObjectNode();
        vars.set("input", input);
        vars.put("idempotencyKey", idempotencyKey);

        JsonNode data = executeGraphQL(shopDomain, token, INVENTORY_ADJUST_QUANTITIES_MUTATION, vars);
        JsonNode userErrors = data.path("inventoryAdjustQuantities").path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            String msg = userErrors.get(0).path("message").asText("unknown error");
            throw new ShopifyException("inventoryAdjustQuantities failed: " + msg);
        }
    }

    /** Pure, network-free: builds one InventoryChangeInput entry. Package-private so
     *  ShopifyHttpGatewayInventoryTest can assert the exact JSON shape sent to Shopify without
     *  needing to fake an HTTP round trip for this part of the payload. */
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
        if (inventoryItemGids.isEmpty()) return out;

        ObjectNode vars = mapper.createObjectNode();
        vars.set("ids", mapper.valueToTree(inventoryItemGids));
        vars.put("locationId", locationGid);

        JsonNode data = executeGraphQL(shopDomain, token, INVENTORY_LEVELS_QUERY, vars);
        JsonNode nodes = data.path("nodes");
        if (!nodes.isArray()) return out;
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
        return out;
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
                    node.path("featuredImage").path("url").asText(null),
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
