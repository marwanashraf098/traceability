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
 * Returns portal fees F0 — the unit price paid, asked for by the orders import and the portal pre-connect lookup.
 *
 *   q1 — every PII tier of both queries asks for discountedUnitPriceAfterAllDiscountsSet; withPrices=false doesn't.
 *   w1 — the import parses each line's amount into LineItem.paidUnitPrice (Shopify's text); missing / not a plain
 *        decimal → null (the resolver falls back), never an error.
 *   w2 — MAX_COST_EXCEEDED on the import: the SAME page is asked again without prices and its orders come back
 *        (the field can never fail an import); the next page also goes without (remembered per shop).
 *   w3 — the portal lookup steps down the same way.
 *   s1 — a LineItem serialises the price as 'paid_unit_price', never 'price' (V149's analytics key); absent when null.
 */
class ShopifyLinePaidPriceWireTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String FIELD = "discountedUnitPriceAfterAllDiscountsSet { shopMoney { amount } }";

    @Test
    void q1_everyTierAsksForThePaidPrice_withoutPricesDoesNot() {
        for (ShopifyHttpGateway.OrderPiiTier tier : ShopifyHttpGateway.OrderPiiTier.values()) {
            assertThat(ShopifyHttpGateway.ordersQuery(tier)).as("import %s", tier).contains(FIELD);
            assertThat(ShopifyHttpGateway.portalOrderQuery(tier)).as("portal %s", tier).contains(FIELD);
            assertThat(ShopifyHttpGateway.ordersQuery(tier, false)).as("import %s, no prices", tier)
                .doesNotContain("discountedUnitPrice").contains("variant { id }");
            assertThat(ShopifyHttpGateway.portalOrderQuery(tier, false)).as("portal %s, no prices", tier)
                .doesNotContain("discountedUnitPrice").contains("variant { id }");
        }
    }

    @Test
    void w1_importParsesThePaidPrice_unreadableIsNull() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> isScopeQuery(b) ? scopes() : ordersPage(
            line("L1", "\"199.50\"") + "," + line("L2", "\"abc\"") + "," + line("L3", null) + "," + line("L4", "120")));

        ShopifyGateway.OrderPage page = gw.fetchOrdersPage("shop.myshopify.com", "tok", null, "2026-10-01T00:00:00Z");

        assertThat(page.orders().get(0).lineItems())
            .extracting(ShopifyGateway.LineItem::gid, ShopifyGateway.LineItem::paidUnitPrice)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("gid://shopify/LineItem/L1", "199.50"),
                org.assertj.core.groups.Tuple.tuple("gid://shopify/LineItem/L2", null),
                org.assertj.core.groups.Tuple.tuple("gid://shopify/LineItem/L3", null),
                org.assertj.core.groups.Tuple.tuple("gid://shopify/LineItem/L4", "120"));
    }

    @Test
    void w2_maxCostExceeded_importRetriesSamePageWithoutPrices_andRemembers() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> {
            if (isScopeQuery(b)) return scopes();
            return b.path("query").asText().contains("discountedUnitPrice") ? maxCost() : ordersPage(line("L1", null));
        });

        ShopifyGateway.OrderPage page = gw.fetchOrdersPage("shop.myshopify.com", "tok", null, "2026-10-01T00:00:00Z");
        gw.fetchOrdersPage("shop.myshopify.com", "tok", "c2", "2026-10-01T00:00:00Z");

        assertThat(page.orders()).extracting(ShopifyGateway.Order::name).containsExactly("#1001");
        List<JsonNode> orderBodies = bodies.stream().filter(b -> !isScopeQuery(b)).toList();
        assertThat(orderBodies).hasSize(3);
        assertThat(orderBodies.get(0).path("query").asText()).contains(FIELD);
        assertThat(orderBodies.get(1).path("query").asText()).doesNotContain("discountedUnitPrice");
        assertThat(orderBodies.get(1).path("variables")).as("the same page again").isEqualTo(orderBodies.get(0).path("variables"));
        assertThat(orderBodies.get(2).path("query").asText()).as("next page: still without").doesNotContain("discountedUnitPrice");
    }

    @Test
    void w3_maxCostExceeded_portalLookupStepsDown() {
        List<JsonNode> bodies = new ArrayList<>();
        ShopifyHttpGateway gw = gateway(bodies, b -> {
            if (isScopeQuery(b)) return scopes();
            return b.path("query").asText().contains("discountedUnitPrice") ? maxCost() : ordersPage(line("L1", null));
        });

        ShopifyGateway.PortalOrderSearch found = gw.findOrdersByName("shop.myshopify.com", "tok", "1001");

        assertThat(found.orders()).extracting(ShopifyGateway.Order::name).containsExactly("#1001");
        List<String> qs = bodies.stream().filter(b -> !isScopeQuery(b)).map(b -> b.path("query").asText()).toList();
        assertThat(qs).hasSize(2);
        assertThat(qs.get(0)).contains(FIELD);
        assertThat(qs.get(1)).doesNotContain("discountedUnitPrice");
    }

    @Test
    void s1_lineItemSerialisesPaidUnitPrice_neverPrice() throws Exception {
        String priced = mapper.writeValueAsString(new ShopifyGateway.LineItem("gid://shopify/LineItem/1", 2,
            "gid://shopify/ProductVariant/9", 1, "199.50"));
        JsonNode p = mapper.readTree(priced);
        assertThat(p.path("paid_unit_price").asText()).isEqualTo("199.50");
        assertThat(p.has("price")).isFalse();
        assertThat(p.path("current_quantity").asInt()).isEqualTo(1);

        JsonNode unpriced = mapper.readTree(mapper.writeValueAsString(
            new ShopifyGateway.LineItem("gid://shopify/LineItem/1", 2, "gid://shopify/ProductVariant/9", 1)));
        assertThat(unpriced.has("paid_unit_price")).isFalse();
        assertThat(unpriced.has("price")).isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean isScopeQuery(JsonNode body) {
        return body.path("query").asText().contains("currentAppInstallation");
    }

    private static String scopes() {
        return "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_orders\"},{\"handle\":\"read_customers\"}]}}}";
    }

    /** One line node; {@code amountJson} is the raw JSON of shopMoney.amount, or null for no price block. */
    private static String line(String id, String amountJson) {
        String price = amountJson == null ? ""
            : ",\"discountedUnitPriceAfterAllDiscountsSet\":{\"shopMoney\":{\"amount\":" + amountJson + "}}";
        return "{\"node\":{\"id\":\"gid://shopify/LineItem/" + id + "\",\"quantity\":1,\"currentQuantity\":1," +
               "\"variant\":{\"id\":\"gid://shopify/ProductVariant/77\"}" + price + "}}";
    }

    private static String ordersPage(String lineEdges) {
        return """
            {"data":{"orders":{"pageInfo":{"hasNextPage":false,"endCursor":null},"edges":[{"node":{
              "id":"gid://shopify/Order/1","name":"#1001","createdAt":"2026-10-02T10:00:00Z",
              "lineItems":{"edges":[""" + lineEdges + """
            ]},"currentTotalPriceSet":{"shopMoney":{"amount":"100.00"}},
              "displayFinancialStatus":"PENDING","displayFulfillmentStatus":"UNFULFILLED",
              "paymentGatewayNames":[],"tags":[]}}]}}}""";
    }

    private static String maxCost() {
        return """
            {"errors":[{"message":"Query cost is 1520, which exceeds the single query max cost limit (1000).",
              "extensions":{"code":"MAX_COST_EXCEEDED","cost":1520,"maxCost":1000}}]}""";
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
