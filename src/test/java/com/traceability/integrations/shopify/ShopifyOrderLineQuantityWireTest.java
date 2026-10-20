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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shopify order edits (2026-10-21) — the orders import reads each line's CURRENT quantity.
 *
 *   q1 the import query asks for lineItems { id quantity currentQuantity … }
 *   q2 a removed line (quantity 1, currentQuantity 0 — Shopify keeps it in lineItems) parses to
 *      currentQuantity 0, storedQuantity 0; a line with no currentQuantity keeps quantity
 *   q3 the line serialises into order_items.raw with the REST key current_quantity (V149 current_qty),
 *      and without the key when unknown
 *   q4 REST: restLineQuantity reads current_quantity, else quantity
 */
class ShopifyOrderLineQuantityWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void q1_q2_importAsksForCurrentQuantity_andParsesIt() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies);

        ShopifyGateway.OrderPage page = gw.fetchOrdersPage("shop.myshopify.com", "tok", null, "2026-10-01T00:00:00Z");

        String q = bodies.stream().map(b -> b.path("query").asText())
            .filter(s -> s.contains("OrdersPage")).findFirst().orElseThrow();
        assertThat(q.replaceAll("\\s+", " ")).contains("node { id quantity currentQuantity variant { id } }");
        List<ShopifyGateway.LineItem> lines = page.orders().get(0).lineItems();
        assertThat(lines).extracting(ShopifyGateway.LineItem::currentQuantity).containsExactly(0, 1, null);
        assertThat(lines).extracting(ShopifyGateway.LineItem::storedQuantity).containsExactly(0, 1, 2);
    }

    @Test
    void q3_rawCarriesTheRestKey() throws Exception {
        JsonNode removed = mapper.readTree(mapper.writeValueAsString(
            new ShopifyGateway.LineItem("gid://shopify/LineItem/1", 1, "gid://shopify/ProductVariant/9", 0)));
        assertThat(removed.path("current_quantity").asInt(-1)).isZero();
        assertThat(removed.path("quantity").asInt()).isEqualTo(1);
        assertThat(removed.has("currentQuantity")).isFalse();
        JsonNode unknown = mapper.readTree(mapper.writeValueAsString(
            new ShopifyGateway.LineItem("gid://shopify/LineItem/2", 2, "gid://shopify/ProductVariant/9")));
        assertThat(unknown.has("current_quantity")).isFalse();
        assertThat(unknown.has("storedQuantity")).isFalse();
    }

    @Test
    void q4_restLineQuantity_currentElseQuantity() throws Exception {
        assertThat(ShopifySyncService.restLineQuantity(mapper.readTree("{\"quantity\":1,\"current_quantity\":0}"))).isZero();
        assertThat(ShopifySyncService.restLineQuantity(mapper.readTree("{\"quantity\":3,\"current_quantity\":2}"))).isEqualTo(2);
        assertThat(ShopifySyncService.restLineQuantity(mapper.readTree("{\"quantity\":2}"))).isEqualTo(2);
        assertThat(ShopifySyncService.restLineQuantity(mapper.readTree("{\"quantity\":2,\"current_quantity\":null}"))).isEqualTo(2);
    }

    private ShopifyHttpGateway gateway(List<JsonNode> bodies) {
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            JsonNode parsed = mapper.readTree(new String(body, StandardCharsets.UTF_8));
            bodies.add(parsed);
            String reply = parsed.path("query").asText().contains("currentAppInstallation")
                ? "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_orders\"}]}}}"
                : """
                  {"data":{"orders":{"pageInfo":{"hasNextPage":false,"endCursor":null},"edges":[{"node":{
                    "id":"gid://shopify/Order/16443","name":"#16443","createdAt":"2026-10-20T18:00:00Z",
                    "updatedAt":"2026-10-20T20:52:23Z",
                    "lineItems":{"edges":[
                      {"node":{"id":"gid://shopify/LineItem/50833358520689","quantity":1,"currentQuantity":0,
                               "variant":{"id":"gid://shopify/ProductVariant/1"}}},
                      {"node":{"id":"gid://shopify/LineItem/50833603789169","quantity":1,"currentQuantity":1,
                               "variant":{"id":"gid://shopify/ProductVariant/2"}}},
                      {"node":{"id":"gid://shopify/LineItem/3","quantity":2,
                               "variant":{"id":"gid://shopify/ProductVariant/3"}}}]},
                    "currentTotalPriceSet":{"shopMoney":{"amount":"2199.00"}},
                    "displayFinancialStatus":"PENDING","displayFulfillmentStatus":"UNFULFILLED",
                    "paymentGatewayNames":[],"tags":[]}}]}}}""";
            MockClientHttpResponse response = new MockClientHttpResponse(reply.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        };
        return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(interceptor), mapper,
            "2026-04", "test-client-id", "test-client-secret");
    }
}
