package com.traceability.integrations.shopify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5a fix (2026-11-08) — prod test store, order #1014, RR-ZMCAUG: returnCreate answered
 * {@code userErrors:[{field:[3 segments], code:NOT_FOUND}]} on every attempt, with no reason on the line.
 * <ul>
 *   <li>w1 the prod shape (depth-3 NOT_FOUND) → DEFINITE, ONE request, the field path + code + message logged
 *       verbatim at WARN, the stored reason stays codes-only (never the message)</li>
 *   <li>w2 the fixed payload: fulfillmentLineItemId (the FulfillmentLineItem id), quantity, returnReasonNote with the
 *       Traced marker, returnReasonDefinitionId; never the deprecated returnReason enum</li>
 *   <li>w3 no reason id → the key is absent (the pre-fix payload, unchanged)</li>
 *   <li>d1 the catch-all reason: "other" before "unknown", deleted ones never, the query asks deleted:false</li>
 *   <li>d2 "unknown" on a later page, read through the cursor</li>
 *   <li>d3 none → null; positive and negative answers cached per shop (no second request)</li>
 * </ul>
 */
class ReturnCreateReasonWireTest {

    private static final ObjectMapper M = new ObjectMapper();

    private final List<JsonNode> bodies = new ArrayList<>();
    private final Deque<String> answers = new ArrayDeque<>();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void attach() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ShopifyReturnsWriteGatewayImpl.class)).addAppender(logs);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(ShopifyReturnsWriteGatewayImpl.class)).detachAppender(logs);
    }

    private ShopifyHttpGateway http() {
        ClientHttpRequestInterceptor i = (request, body, execution) -> {
            bodies.add(M.readTree(new String(body, StandardCharsets.UTF_8)));
            String text = answers.isEmpty() ? "{}" : answers.poll();
            MockClientHttpResponse resp = new MockClientHttpResponse(text.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return resp;
        };
        return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(i), M, "2026-04", "id", "secret");
    }

    private ReturnsWriteResponse create(ShopifyReturnsWriteGateway.CreateLine line) {
        return new ShopifyReturnsWriteGatewayImpl(http(), M).returnCreate("s.myshopify.com", "t",
            "gid://shopify/Order/1014", Instant.parse("2026-11-08T11:20:00Z"), List.of(line));
    }

    private static final String PROD_NOT_FOUND = "{\"data\":{\"returnCreate\":{\"return\":null,\"userErrors\":" +
        "[{\"field\":[\"returnInput\",\"returnLineItems\",\"0\"],\"message\":\"Not found.\"," +
        "\"code\":\"NOT_FOUND\"}]}}}";

    @Test
    void w1_prodShape_depth3NotFound_definite_oneRequest_pathAndMessageLogged() {
        answers.add(PROD_NOT_FOUND);
        ReturnsWriteResponse r = create(new ShopifyReturnsWriteGateway.CreateLine(
            "gid://shopify/FulfillmentLineItem/7", 1, "Traced RR-ZMCAUG · Wrong size"));

        assertThat(r.kind()).isEqualTo(ReturnsWriteResponse.Kind.DEFINITE);
        assertThat(bodies).hasSize(1);
        assertThat(r.reason()).contains("NOT_FOUND").doesNotContain("Not found.");
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Shopify returns write returnCreate shop=s.myshopify.com userError " +
                "field=returnInput.returnLineItems.0 code=NOT_FOUND message=Not found.");
        // The shape line still carries no value.
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.INFO).extracting(ILoggingEvent::getFormattedMessage)
            .allSatisfy(m -> assertThat(m).doesNotContain("Not found.").doesNotContain("RR-ZMCAUG"));
    }

    @Test
    void w2_fixedPayload_reasonDefinitionId_fulfillmentLineItemId_markerNote_noLegacyReason() {
        answers.add("{\"data\":{\"returnCreate\":{\"return\":{\"id\":\"gid://shopify/Return/1\",\"name\":\"#1014-R1\"," +
            "\"status\":\"OPEN\"},\"userErrors\":[]}}}");
        ReturnsWriteResponse r = create(new ShopifyReturnsWriteGateway.CreateLine(
            "gid://shopify/FulfillmentLineItem/7", 1, "Traced RR-ZMCAUG · Wrong size",
            "gid://shopify/ReturnReasonDefinition/42"));

        assertThat(r.kind()).isEqualTo(ReturnsWriteResponse.Kind.OK);
        assertThat(bodies).hasSize(1);
        JsonNode line = bodies.get(0).path("variables").path("returnInput").path("returnLineItems").get(0);
        assertThat(line.path("fulfillmentLineItemId").asText()).isEqualTo("gid://shopify/FulfillmentLineItem/7");
        assertThat(line.path("quantity").asInt()).isEqualTo(1);
        assertThat(line.path("returnReasonNote").asText()).isEqualTo("Traced RR-ZMCAUG · Wrong size");
        assertThat(line.path("returnReasonDefinitionId").asText()).isEqualTo("gid://shopify/ReturnReasonDefinition/42");
        assertThat(line.has("returnReason")).isFalse();
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void w3_noReasonId_keyAbsent() {
        answers.add("{\"data\":{\"returnCreate\":{\"return\":{\"id\":\"gid://shopify/Return/1\"},\"userErrors\":[]}}}");
        create(new ShopifyReturnsWriteGateway.CreateLine("gid://shopify/FulfillmentLineItem/7", 1, "Traced RR-ZMCAUG"));
        JsonNode line = bodies.get(0).path("variables").path("returnInput").path("returnLineItems").get(0);
        assertThat(line.has("returnReasonDefinitionId")).isFalse();
        assertThat(line.has("returnReason")).isFalse();
    }

    // ── catch-all reason lookup ─────────────────────────────────────────────────────────────────

    private static String page(boolean next, String cursor, String... nodes) {
        return "{\"data\":{\"returnReasonDefinitions\":{\"pageInfo\":{\"hasNextPage\":" + next + ",\"endCursor\":" +
            (cursor == null ? "null" : "\"" + cursor + "\"") + "},\"nodes\":[" + String.join(",", nodes) + "]}}}";
    }

    private static String node(long id, String handle, boolean deleted) {
        return "{\"id\":\"gid://shopify/ReturnReasonDefinition/" + id + "\",\"handle\":\"" + handle + "\",\"deleted\":" +
            deleted + "}";
    }

    @Test
    void d1_otherBeforeUnknown_deletedNever_queryAsksNotDeleted() {
        answers.add(page(false, null, node(1, "unknown", false), node(2, "other", true), node(3, "other", false),
            node(4, "damaged", false)));
        String id = new ShopifyReturnsGatewayImpl(http(), M).fetchFallbackReturnReasonId("s.myshopify.com", "t");
        assertThat(id).isEqualTo("gid://shopify/ReturnReasonDefinition/3");
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0).path("query").asText()).contains("returnReasonDefinitions").contains("deleted:false");
    }

    @Test
    void d2_unknownOnLaterPage_readThroughCursor() {
        answers.add(page(true, "c1", node(1, "damaged", false)));
        answers.add(page(false, null, node(2, "unknown", false)));
        String id = new ShopifyReturnsGatewayImpl(http(), M).fetchFallbackReturnReasonId("s.myshopify.com", "t");
        assertThat(id).isEqualTo("gid://shopify/ReturnReasonDefinition/2");
        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(1).path("variables").path("after").asText()).isEqualTo("c1");
    }

    @Test
    void d3_none_null_andBothAnswersCachedPerShop() {
        ShopifyReturnsGatewayImpl g = new ShopifyReturnsGatewayImpl(http(), M);
        answers.add(page(false, null, node(1, "damaged", false)));
        assertThat(g.fetchFallbackReturnReasonId("a.myshopify.com", "t")).isNull();
        assertThat(g.fetchFallbackReturnReasonId("a.myshopify.com", "t")).isNull();
        assertThat(bodies).hasSize(1);

        answers.add(page(false, null, node(5, "other", false)));
        assertThat(g.fetchFallbackReturnReasonId("b.myshopify.com", "t")).isEqualTo("gid://shopify/ReturnReasonDefinition/5");
        assertThat(g.fetchFallbackReturnReasonId("b.myshopify.com", "t")).isEqualTo("gid://shopify/ReturnReasonDefinition/5");
        assertThat(bodies).hasSize(2);
    }
}
