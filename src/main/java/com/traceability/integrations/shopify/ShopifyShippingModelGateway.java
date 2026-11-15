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
 * doesn't exist in 2026-04, which every other call stays pinned to ({@code shopify.api-version}). One READ-only query,
 * its own HTTP client, no retry, no write of any kind (ShippingModelVersionGuardTest).
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

    /** Logged with the cause only — never a body, a token or a shop's data. */
    private static Optional<Model> unknown(String cause) {
        log.warn("Shopify shipping model unknown: {}", cause);
        return Optional.empty();
    }
}
