package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * L1b-1 (approved by Marawan 2026-11-15) — which shipping model a store uses: legacy delivery profiles, or
 * market-driven shipping (Shopify's rollout from 2026-10-01; every shop by 2027-07-01). On a market-driven shop the
 * merchant delivery-profile API is stale: reads may return an old snapshot and writes succeed WITHOUT changing the live
 * settings (Shopify changelog 2026-07-01), so Traced must not join a profile there or read "activated" from one.
 *
 * <p>The ONLY class that talks to Shopify at {@link #API_VERSION} (2026-07): {@code ShopFeatures.marketDrivenShipping}
 * and the Markets delivery API don't exist in 2026-04, which every other call stays pinned to
 * ({@code shopify.api-version}). READ-only queries, its own HTTP client, no retry, no write of any kind
 * (ShippingModelVersionGuardTest).
 *
 * <p>L1b-2 (2026-11-16): {@link #readMarketCoverage} — the store's markets and their shipping rates (optional scope
 * read_markets), judged by {@link MarketShippingCoverage}.
 */
@Component
public class ShopifyShippingModelGateway {

    /** The shipping-model query's API version — nothing else in Traced may use it. */
    public static final String API_VERSION = "2026-07";

    static final String MARKET_DRIVEN_QUERY = """
            query TracedShippingModel {
              shop {
                features {
                  marketDrivenShipping
                }
              }
            }
            """;

    /**
     * L1b-2 — the shop's country and its ACTIVE markets with their region countries and active shipping rates'
     * locations. Ran live on a market-driven store with only read_markets + read_locations (+ read_shipping) granted
     * (2026-11-16, fixture coverage-live.json). Carrier-calculated rates have no {@code name}.
     */
    static final String MARKET_COVERAGE_QUERY = """
            query TracedMarketCoverage($after: String) {
              shop { shopAddress { countryCodeV2 } }
              markets(first: 10, after: $after, query: "status:ACTIVE") {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id name type status
                  conditions { regionsCondition { regions(first: 250) { pageInfo { hasNextPage } nodes { ... on MarketRegionCountry { code } } } } }
                  delivery { shipping { isEnabled
                    optionDefinitions(first: 25, active: true) {
                      pageInfo { hasNextPage }
                      nodes { __typename id isActive
                        ... on DeliveryFlatRateOptionDefinition { name }
                        ... on DeliveryValueBasedOptionDefinition { name }
                        ... on DeliveryWeightBasedOptionDefinition { name }
                        includedLocations(first: 50) { pageInfo { hasNextPage } nodes { id } } } } } }
                }
              }
            }
            """;

    /** Market pages read at most (10 markets each); more → the list is "cut short" → no verdict. */
    static final int MAX_MARKET_PAGES = 10;

    /** What Shopify says. Empty = unknown (any failure, an error, or no such field): callers then do nothing. */
    public enum Model { LEGACY, MARKET_DRIVEN }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ShopifyShippingModelGateway.class);

    private final RestClient http;
    private final ObjectMapper mapper;
    private final boolean enabled;

    /**
     * {@code traced.shipping-model.read-enabled} (default true). false = no Shopify call, every store reads as LEGACY —
     * exactly L1's behaviour before this slice (the kill switch; the shared test config sets it false so no test talks
     * to Shopify — tests of this slice mock the gateway or hand-wire it).
     */
    @Autowired
    public ShopifyShippingModelGateway(RestClient.Builder builder, ObjectMapper mapper,
                                       @Value("${shopify.http.connect-timeout:5s}") Duration connectTimeout,
                                       @Value("${shopify.http.read-timeout:30s}") Duration readTimeout,
                                       @Value("${traced.shipping-model.read-enabled:true}") boolean enabled) {
        this(builder.requestFactory(ShopifyHttpGateway.timeoutFactory(connectTimeout, readTimeout)), mapper, enabled);
    }

    /** Hand-wired (tests): the builder as given. */
    ShopifyShippingModelGateway(RestClient.Builder builder, ObjectMapper mapper, boolean enabled) {
        this.http = builder.build();
        this.mapper = mapper;
        this.enabled = enabled;
    }

    /**
     * One POST of {@link #MARKET_DRIVEN_QUERY} at {@link #API_VERSION}. Never throws: a transport failure, an HTTP
     * error, a GraphQL error or a missing / non-boolean field all read as unknown (empty).
     */
    public Optional<Model> read(String shopDomain, String token) {
        if (!enabled) return Optional.of(Model.LEGACY);
        try {
            String body = http.post()
                .uri("https://" + shopDomain + "/admin/api/" + API_VERSION + "/graphql.json")
                .header("X-Shopify-Access-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(Map.of("query", MARKET_DRIVEN_QUERY)))
                .retrieve()
                .body(String.class);
            if (body == null) return unknown("empty body");
            JsonNode root = mapper.readTree(body);
            if (root.path("errors").isArray() && !root.path("errors").isEmpty()) {
                return unknown("graphql errors " + root.path("errors").findValuesAsText("code"));
            }
            JsonNode flag = root.path("data").path("shop").path("features").path("marketDrivenShipping");
            if (!flag.isBoolean()) return unknown("no marketDrivenShipping field");
            return Optional.of(flag.asBoolean() ? Model.MARKET_DRIVEN : Model.LEGACY);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return unknown("HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            return unknown(e.getClass().getSimpleName());
        }
    }

    /**
     * L1b-2 — READ ONLY: does an active shipping rate of the store's primary market list {@code locationGid}?
     * Empty = the read failed (transport, HTTP or a GraphQL error other than ACCESS_DENIED): the caller changes
     * nothing. ACCESS_DENIED / HTTP 403 → {@link MarketShippingCoverage.Verdict#NO_ACCESS}. Never throws, never logs a
     * body or shop data. Disabled ({@code traced.shipping-model.read-enabled=false}) → empty, no call.
     */
    public Optional<MarketShippingCoverage.Result> readMarketCoverage(String shopDomain, String token, String locationGid) {
        if (!enabled) return Optional.empty();
        try {
            String country = null;
            java.util.List<JsonNode> markets = new java.util.ArrayList<>();
            String after = null;
            boolean complete = false;
            for (int page = 0; page < MAX_MARKET_PAGES; page++) {
                Map<String, Object> vars = new java.util.HashMap<>();
                vars.put("after", after);
                JsonNode root = post(shopDomain, token, MARKET_COVERAGE_QUERY, vars);
                if (root == null) return failed("empty body");
                JsonNode errors = root.path("errors");
                if (errors.isArray() && !errors.isEmpty()) {
                    java.util.List<String> codes = errors.findValuesAsText("code");
                    if (codes.contains("ACCESS_DENIED")) return Optional.of(MarketShippingCoverage.Result.noAccess("access_denied"));
                    return failed("graphql errors " + codes);
                }
                JsonNode data = root.path("data");
                if (page == 0) country = data.path("shop").path("shopAddress").path("countryCodeV2").asText(null);
                JsonNode conn = data.path("markets");
                if (!conn.isObject()) return failed("no markets field");
                conn.path("nodes").forEach(markets::add);
                if (!conn.path("pageInfo").path("hasNextPage").asBoolean(false)) { complete = true; break; }
                after = conn.path("pageInfo").path("endCursor").asText(null);
                if (after == null) break;
            }
            MarketShippingCoverage.Result r = MarketShippingCoverage.evaluate(country, markets, complete, locationGid);
            log.info("Shopify market shipping read: verdict={} reason={} markets={}", r.verdict(), r.reason(), markets.size());
            return Optional.of(r);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            if (e.getStatusCode().value() == 403) return Optional.of(MarketShippingCoverage.Result.noAccess("http_403"));
            return failed("HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            return failed(e.getClass().getSimpleName());
        }
    }

    private JsonNode post(String shopDomain, String token, String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("query", query);
        if (variables != null) payload.put("variables", variables);
        String body = http.post()
            .uri("https://" + shopDomain + "/admin/api/" + API_VERSION + "/graphql.json")
            .header("X-Shopify-Access-Token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(payload))
            .retrieve()
            .body(String.class);
        return body == null ? null : mapper.readTree(body);
    }

    private static Optional<MarketShippingCoverage.Result> failed(String cause) {
        log.warn("Shopify market shipping read failed: {}", cause);
        return Optional.empty();
    }

    /** Logged with the cause only — never a body, a token or a shop's data. */
    private static Optional<Model> unknown(String cause) {
        log.warn("Shopify shipping model unknown: {}", cause);
        return Optional.empty();
    }
}
