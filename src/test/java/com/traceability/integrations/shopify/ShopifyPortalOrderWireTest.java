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
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4c — the portal's on-demand lookup on the REAL wire: findOrdersByName asks for the order by name
 * (both '#'-forms, at most 2), its lines, its fulfillments' tracking numbers and the store's tier of
 * customer fields — never email; an ACCESS_DENIED steps the tier down like the import, and with no
 * customer field left the search says the phone can't be checked. oldestOrder: first 1 by creation.
 */
class ShopifyPortalOrderWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void p1_findByName_query_fields_tier_noEmail_parsesTheOrder() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> isScopeQuery(b) ? scopes("read_orders", "read_customers") : order());

        ShopifyGateway.PortalOrderSearch s = gw.findOrdersByName("shop.myshopify.com", "tok", "1047");

        JsonNode req = bodies.stream().filter(b -> !isScopeQuery(b)).findFirst().orElseThrow();
        assertThat(req.path("variables").path("queryStr").asText()).isEqualTo("name:\"#1047\" OR name:\"1047\"");
        String q = req.path("query").asText();
        assertThat(q).contains("orders(first: 2").contains("fulfillments(first: 20) { status trackingInfo(first: 5) { number company } }")
            .contains("currentQuantity").contains("customer { id firstName lastName defaultPhoneNumber { phoneNumber } }")
            .doesNotContain("email");
        assertThat(s.customerPhoneReadable()).isTrue();
        assertThat(s.orders()).hasSize(1);
        assertThat(s.orders().get(0).name()).isEqualTo("#1047");
        assertThat(s.orders().get(0).raw().path("fulfillments").get(0).path("trackingInfo").get(0).path("number").asText())
            .isEqualTo("5550001047");
    }

    @Test
    void p2_accessDenied_stepsDownToNone_searchSaysThePhoneCantBeChecked() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> {
            if (isScopeQuery(b)) return scopes("read_orders", "read_customers");
            return b.path("query").asText().contains("shippingAddress") ? denied() : order();
        });

        ShopifyGateway.PortalOrderSearch s = gw.findOrdersByName("shop.myshopify.com", "tok", "#1047");

        assertThat(s.customerPhoneReadable()).isFalse();
        assertThat(s.orders()).hasSize(1);
    }

    @Test
    void p3_oldestOrder_firstByCreation() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> """
            {"data":{"orders":{"edges":[{"node":{"id":"gid://shopify/Order/9","name":"#1500","createdAt":"2026-08-12T09:00:00Z"}}]}}}""");

        Optional<ShopifyGateway.Order> o = gw.oldestOrder("shop.myshopify.com", "tok");

        assertThat(bodies.get(0).path("query").asText()).contains("orders(first: 1, sortKey: CREATED_AT)");
        assertThat(o).isPresent();
        assertThat(o.get().name()).isEqualTo("#1500");
        assertThat(o.get().createdAt().toString()).isEqualTo("2026-08-12T09:00:00Z");
    }

    private boolean isScopeQuery(JsonNode body) {
        return body.path("query").asText().contains("currentAppInstallation");
    }

    private static String scopes(String... handles) {
        StringBuilder sb = new StringBuilder();
        for (String h : handles) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append("{\"handle\":\"").append(h).append("\"}");
        }
        return "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[" + sb + "]}}}";
    }

    private static String order() {
        return """
            {"data":{"orders":{"edges":[{"node":{
              "id":"gid://shopify/Order/1047","name":"#1047","createdAt":"2026-07-02T10:00:00Z",
              "lineItems":{"edges":[{"node":{"id":"gid://shopify/LineItem/1","quantity":1,"currentQuantity":1,
                 "variant":{"id":"gid://shopify/ProductVariant/7"}}}]},
              "fulfillments":[{"status":"SUCCESS","trackingInfo":[{"number":"5550001047","company":"Bosta"}]}],
              "currentTotalPriceSet":{"shopMoney":{"amount":"640.00","currencyCode":"EGP"}},
              "displayFinancialStatus":"PENDING","paymentGatewayNames":["Cash on Delivery (COD)"]}}]}}}""";
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
