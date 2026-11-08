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
 * Part D P1 Fix 7 (2026-11-08) — how the REAL ShopifyHttpGateway.pushExchangeDispatch classifies its one attempt,
 * exactly like pushTransferOut (TransferOutClassificationWireTest, same harness). DEFINITE rejection
 * (ShopifyException → 'failed', re-sendable) is ONLY:
 *   x1 HTTP 4xx   x2 inventoryAdjustQuantities userErrors   x11 top-level errors that are ONLY THROTTLED, no data
 * Everything else is AMBIGUOUS (ShopifyAmbiguousException → 'failed_ambiguous', alert, never re-sent):
 *   x3 HTTP 5xx   x4 any other top-level GraphQL error   x12 THROTTLED mixed with another error
 *   x13 THROTTLED with data   x5 read timeout   x6 connection refused / reset   x7 2xx with no data / null data
 *   x8 unreadable body
 * x9 success, one request; x10 a non-negative delta is refused before ANY request.
 */
class ExchangeDispatchClassificationWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String OK = "{\"data\":{\"inventoryAdjustQuantities\":{\"inventoryAdjustmentGroup\":" +
        "{\"createdAt\":\"2026-11-08T00:00:00Z\"},\"userErrors\":[]}}}";

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
        gateway(responder).pushExchangeDispatch("s.myshopify.com", "t", "gid://shopify/InventoryItem/1",
            "gid://shopify/Location/9", -1, "traced://piece/p1", "key-1");
    }

    private void definite(Supplier<Object> responder) {
        assertThatThrownBy(() -> push(responder)).isExactlyInstanceOf(ShopifyException.class);
        assertThat(bodies).hasSize(1);
    }

    private void ambiguous(Supplier<Object> responder) {
        assertThatThrownBy(() -> push(responder)).isExactlyInstanceOf(ShopifyAmbiguousException.class);
        assertThat(bodies).hasSize(1);
    }

    @Test void x1_http4xx_definite() { definite(() -> HttpStatus.UNPROCESSABLE_ENTITY); }

    @Test void x2_userErrors_definite() {
        definite(() -> "{\"data\":{\"inventoryAdjustQuantities\":{\"userErrors\":[{\"message\":\"Item not stocked\"}]}}}");
    }

    @Test void x3_http5xx_ambiguous() { ambiguous(() -> HttpStatus.BAD_GATEWAY); }

    @Test void x3b_http500_ambiguous() { ambiguous(() -> HttpStatus.INTERNAL_SERVER_ERROR); }

    @Test void x4_otherTopLevelGraphqlError_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Internal error\",\"extensions\":{\"code\":\"INTERNAL_SERVER_ERROR\"}}]}");
    }

    @Test void x11_throttledOnly_noData_definite() {
        definite(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}");
    }

    @Test void x12_throttledMixedWithAnotherError_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}," +
            "{\"message\":\"Internal error\"}]}");
    }

    @Test void x13_throttledWithData_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]," +
            "\"data\":{\"inventoryAdjustQuantities\":null}}");
    }

    @Test void x5_readTimeout_ambiguous() { ambiguous(() -> new java.net.SocketTimeoutException("Read timed out")); }

    @Test void x6_connectionRefusedOrReset_ambiguous() {
        ambiguous(() -> new java.net.ConnectException("Connection refused"));
        bodies.clear();
        ambiguous(() -> new java.net.SocketException("Connection reset"));
    }

    @Test void x7_noData_ambiguous() {
        ambiguous(() -> "{\"extensions\":{}}");
        bodies.clear();
        ambiguous(() -> "{\"data\":null}");
    }

    @Test void x8_unreadableBody_ambiguous() { ambiguous(() -> "not json at all"); }

    @Test void x9_success_oneRequest() {
        push(() -> OK);
        assertThat(bodies).hasSize(1);
        JsonNode change = bodies.get(0).path("variables").path("input").path("changes").get(0);
        assertThat(change.path("delta").asInt()).isEqualTo(-1);
        assertThat(change.path("locationId").asText()).isEqualTo("gid://shopify/Location/9");
        assertThat(bodies.get(0).path("variables").path("idempotencyKey").asText()).isEqualTo("key-1");
    }

    @Test void x10_nonNegativeDelta_refusedBeforeAnyRequest() {
        assertThatThrownBy(() -> gateway(() -> OK).pushExchangeDispatch("s.myshopify.com", "t",
            "gid://shopify/InventoryItem/1", "gid://shopify/Location/9", 0, "traced://piece/p1", "key-1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(bodies).isEmpty();
    }
}
