package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build B — the orders import's REAL wire traffic: how much customer PII it asks for, per store.
 *
 *   w1 — token with read_customers → shipping + billing + customer { firstName lastName defaultPhoneNumber };
 *        the scope lookup (GraphQL currentAppInstallation) runs once and is cached across pages.
 *   w2 — token without read_customers (Jumi's custom-app token) → addresses only, never the customer block.
 *   w3 — ACCESS_DENIED → the same page is asked again with less (FULL → ADDRESSES → NONE); the import
 *        returns its orders instead of failing, and the lower tier sticks for the next page.
 *   w4 — the scope lookup fails → addresses only (never customer on a guess).
 *   Email is never requested in any tier.
 */
class ShopifyHttpGatewayOrdersPiiTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void w1_readCustomers_fullTier_scopeLookupCached() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> isScopeQuery(b)
            ? scopes("read_orders", "read_customers") : ordersPage());

        gw.fetchOrdersPage("shop.myshopify.com", "tok", null, "2026-10-01T00:00:00Z");
        gw.fetchOrdersPage("shop.myshopify.com", "tok", "c2", "2026-10-01T00:00:00Z");

        assertThat(bodies.stream().filter(this::isScopeQuery)).as("one scope lookup, cached").hasSize(1);
        String q = orderQueries(bodies).get(0);
        assertThat(q).contains("shippingAddress { name phone address1 address2 city province zip country }")
            .contains("billingAddress { name phone }")
            .contains("customer { firstName lastName defaultPhoneNumber { phoneNumber } }")
            .doesNotContain("email");
    }

    @Test
    void w2_withoutReadCustomers_addressesOnly() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> isScopeQuery(b)
            ? scopes("read_fulfillments", "read_orders", "read_products") : ordersPage());

        ShopifyGateway.OrderPage page = gw.fetchOrdersPage("jumi.myshopify.com", "tok", null, "2026-10-01T00:00:00Z");

        assertThat(page.orders()).hasSize(1);
        String q = orderQueries(bodies).get(0);
        assertThat(q).contains("shippingAddress {").contains("billingAddress {").doesNotContain("customer {")
            .doesNotContain("email");
    }

    @Test
    void w3_accessDenied_stepsDown_importStillReturnsOrders_lowerTierSticks() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> {
            if (isScopeQuery(b)) return scopes("read_orders", "read_customers");
            String q = b.path("query").asText();
            return q.contains("shippingAddress") ? denied() : ordersPage();
        });

        ShopifyGateway.OrderPage page = gw.fetchOrdersPage("shop.myshopify.com", "tok", null, "2026-10-01T00:00:00Z");
        gw.fetchOrdersPage("shop.myshopify.com", "tok", "c2", "2026-10-01T00:00:00Z");

        assertThat(page.orders()).extracting(ShopifyGateway.Order::name).containsExactly("#1001");
        List<String> qs = orderQueries(bodies);
        assertThat(qs).hasSize(4);
        assertThat(qs.get(0)).contains("customer {");                                       // FULL → denied
        assertThat(qs.get(1)).contains("shippingAddress").doesNotContain("customer {");    // ADDRESSES → denied
        assertThat(qs.get(2)).doesNotContain("shippingAddress").doesNotContain("customer {"); // NONE → ok
        assertThat(qs.get(3)).as("next page starts at the tier that worked").doesNotContain("shippingAddress");
    }

    @Test
    void w4_scopeLookupFails_addressesOnly() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> isScopeQuery(b)
            ? "{\"errors\":[{\"message\":\"Internal error\"}]}" : ordersPage());

        gw.fetchOrdersPage("shop.myshopify.com", "tok", null, "2026-10-01T00:00:00Z");

        assertThat(orderQueries(bodies).get(0)).contains("shippingAddress").doesNotContain("customer {");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isScopeQuery(JsonNode body) {
        return body.path("query").asText().contains("currentAppInstallation");
    }

    private List<String> orderQueries(List<JsonNode> bodies) {
        return bodies.stream().filter(b -> !isScopeQuery(b)).map(b -> b.path("query").asText()).toList();
    }

    private static String scopes(String... handles) {
        StringBuilder sb = new StringBuilder();
        for (String h : handles) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append("{\"handle\":\"").append(h).append("\"}");
        }
        return "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[" + sb + "]}}}";
    }

    private static String ordersPage() {
        return """
            {"data":{"orders":{"pageInfo":{"hasNextPage":false,"endCursor":null},"edges":[{"node":{
              "id":"gid://shopify/Order/1","name":"#1001","createdAt":"2026-10-02T10:00:00Z",
              "lineItems":{"edges":[]},"currentTotalPriceSet":{"shopMoney":{"amount":"100.00"}},
              "displayFinancialStatus":"PENDING","displayFulfillmentStatus":"UNFULFILLED",
              "paymentGatewayNames":[],"tags":[]}}]}}}""";
    }

    private static String denied() {
        return """
            {"errors":[{"message":"Access denied for shippingAddress field.",
              "extensions":{"code":"ACCESS_DENIED"}}],"data":null}""";
    }

    private ShopifyHttpGateway gateway(List<JsonNode> bodies, Function<JsonNode, String> responder) {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            JsonNode parsed = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            bodies.add(parsed);
            MockClientHttpResponse response = new MockClientHttpResponse(
                responder.apply(parsed).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        };
        return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(interceptor), mapper,
            "2026-04", "test-client-id", "test-client-secret");
    }
}
