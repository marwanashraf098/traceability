package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 2 (approved 2026-10-09) — how the REAL ShopifyHttpGateway.pushTransferOut classifies its one
 * attempt (fake network, the ShopifyAdjustClassificationWireTest harness). DEFINITE rejection
 * (ShopifyException — nothing applied; TransferShopifySync's sweep may re-send, ≤ 5 attempts) is ONLY:
 *   w1 HTTP 4xx   w2 inventoryAdjustQuantities userErrors
 *   w11 top-level errors that are ONLY extensions.code THROTTLED, with no data (Shopify didn't execute it)
 * Everything else is AMBIGUOUS (ShopifyAmbiguousException — failed_ambiguous, never re-sent):
 *   w3 HTTP 5xx   w4 any other top-level GraphQL error   w12 THROTTLED mixed with another error
 *   w13 THROTTLED with data   w5 read timeout
 *   w6 connection refused   w7 2xx with no data / null body   w8 unreadable body
 * w9 success; w10 a non-negative delta is refused before ANY request.
 */
class TransferOutClassificationWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String OK = "{\"data\":{\"inventoryAdjustQuantities\":{\"inventoryAdjustmentGroup\":" +
        "{\"createdAt\":\"2026-10-09T00:00:00Z\"},\"userErrors\":[]}}}";

    private final List<JsonNode> bodies = new ArrayList<>();

    private ShopifyHttpGateway gateway(Supplier<Object> responder) {
        ClientHttpRequestInterceptor i = (request, body, execution) -> {
            bodies.add(M.readTree(new String(body, StandardCharsets.UTF_8)));
            Object r = responder.get();
            if (r instanceof IOException io) throw io;
            HttpStatus status = r instanceof HttpStatus hs ? hs : HttpStatus.OK;
            String text = r instanceof String s ? s : "{}";
            MockClientHttpResponse resp = new MockClientHttpResponse(text.getBytes(StandardCharsets.UTF_8), status);
            resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return resp;
        };
        return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(i), M, "2026-04", "id", "secret");
    }

    private void push(Supplier<Object> responder) {
        gateway(responder).pushTransferOut("s.myshopify.com", "t",
            List.of(new ShopifyGateway.InventoryDelta("gid://shopify/InventoryItem/1", -2),
                    new ShopifyGateway.InventoryDelta("gid://shopify/InventoryItem/2", -1)),
            "gid://shopify/Location/9", "traced://transfer/x", "key-1");
    }

    private void definite(Supplier<Object> responder) {
        assertThatThrownBy(() -> push(responder)).isExactlyInstanceOf(ShopifyException.class);
        assertThat(bodies).hasSize(1);
    }

    private void ambiguous(Supplier<Object> responder) {
        assertThatThrownBy(() -> push(responder)).isExactlyInstanceOf(ShopifyAmbiguousException.class);
        assertThat(bodies).hasSize(1);
    }

    @Test void w1_http4xx_definite() { definite(() -> HttpStatus.UNPROCESSABLE_ENTITY); }

    @Test void w2_userErrors_definite() {
        definite(() -> "{\"data\":{\"inventoryAdjustQuantities\":{\"userErrors\":[{\"message\":\"Item not stocked\"}]}}}");
    }

    @Test void w3_http5xx_ambiguous() { ambiguous(() -> HttpStatus.BAD_GATEWAY); }

    @Test void w4_otherTopLevelGraphqlError_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Internal error\",\"extensions\":{\"code\":\"INTERNAL_SERVER_ERROR\"}}]}");
    }

    @Test void w11_throttledOnly_noData_definite() {
        definite(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}");
    }

    @Test void w12_throttledMixedWithAnotherError_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}," +
            "{\"message\":\"Internal error\"}]}");
    }

    @Test void w13_throttledWithData_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]," +
            "\"data\":{\"inventoryAdjustQuantities\":null}}");
    }

    @Test void w5_readTimeout_ambiguous() { ambiguous(() -> new java.net.SocketTimeoutException("Read timed out")); }

    @Test void w6_connectionRefused_ambiguous() { ambiguous(() -> new java.net.ConnectException("Connection refused")); }

    @Test void w7_noData_ambiguous() { ambiguous(() -> "{\"extensions\":{}}"); }

    @Test void w8_unreadableBody_ambiguous() { ambiguous(() -> "not json at all"); }

    @Test void w9_success_oneRequest_allDeltasInOneMutation() {
        push(() -> OK);
        assertThat(bodies).hasSize(1);
        JsonNode changes = bodies.get(0).path("variables").path("input").path("changes");
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).path("delta").asInt()).isEqualTo(-2);
        assertThat(changes.get(0).path("locationId").asText()).isEqualTo("gid://shopify/Location/9");
        assertThat(bodies.get(0).path("variables").path("idempotencyKey").asText()).isEqualTo("key-1");
    }

    @Test void w10_nonNegativeDelta_refusedBeforeAnyRequest() {
        assertThatThrownBy(() -> gateway(() -> OK).pushTransferOut("s.myshopify.com", "t",
            List.of(new ShopifyGateway.InventoryDelta("gid://shopify/InventoryItem/1", 1)),
            "gid://shopify/Location/9", "traced://transfer/x", "key-1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(bodies).isEmpty();
    }
}
