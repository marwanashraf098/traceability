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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1 (2026-10-25) — pushPieceSold (the seventh named decrement) on the REAL ShopifyHttpGateway, fake network
 * (the PieceSyncClassificationWireTest harness). Exactly ONE mutation request per call, classified exactly
 * like pushPieceWriteOff / pushTransferOut:
 *   DEFINITE (ShopifyException): HTTP 4xx · userErrors · top-level errors that are ONLY THROTTLED, with no data
 *   AMBIGUOUS (ShopifyAmbiguousException): HTTP 5xx · any other top-level error · THROTTLED mixed or with data ·
 *     read timeout · no data · null body
 * and the request: −1, "available", reason "correction", the Traced location, the reference and the key.
 */
class PieceSoldWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String OK = "{\"data\":{\"inventoryAdjustQuantities\":{\"inventoryAdjustmentGroup\":" +
        "{\"createdAt\":\"2026-10-25T00:00:00Z\"},\"userErrors\":[]}}}";

    private final List<JsonNode> requests = new ArrayList<>();

    private ShopifyHttpGateway gateway(Supplier<Object> responder) {
        ClientHttpRequestInterceptor i = (request, body, execution) -> {
            requests.add(M.readTree(new String(body, StandardCharsets.UTF_8)));
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

    private void send(Supplier<Object> responder) {
        gateway(responder).pushPieceSold("s.myshopify.com", "t", "gid://shopify/InventoryItem/1",
            "gid://shopify/Location/9", -1, "traced://piece/P1", "key-1");
    }

    private void definite(Supplier<Object> responder) {
        assertThatThrownBy(() -> send(responder)).isExactlyInstanceOf(ShopifyException.class);
        assertThat(requests).as("exactly one attempt").hasSize(1);
    }

    private void ambiguous(Supplier<Object> responder) {
        assertThatThrownBy(() -> send(responder)).isExactlyInstanceOf(ShopifyAmbiguousException.class);
        assertThat(requests).as("exactly one attempt — never retried").hasSize(1);
    }

    @Test void w1_http4xx_definite() { definite(() -> HttpStatus.UNPROCESSABLE_ENTITY); }

    @Test void w2_userErrors_definite() {
        definite(() -> "{\"data\":{\"inventoryAdjustQuantities\":{\"userErrors\":[{\"message\":\"Not enough\"}]}}}");
    }

    @Test void w3_throttledOnlyNoData_definite() {
        definite(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}");
    }

    @Test void w4_http5xx_ambiguous() { ambiguous(() -> HttpStatus.BAD_GATEWAY); }

    @Test void w5_otherTopLevelError_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Internal\",\"extensions\":{\"code\":\"INTERNAL_SERVER_ERROR\"}}]}");
    }

    @Test void w6_throttledMixed_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}," +
            "{\"message\":\"Internal\"}]}");
    }

    @Test void w7_throttledWithData_ambiguous() {
        ambiguous(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]," +
            "\"data\":{\"inventoryAdjustQuantities\":null}}");
    }

    @Test void w8_readTimeout_ambiguous() { ambiguous(() -> new SocketTimeoutException("Read timed out")); }

    @Test void w9_noData_ambiguous() { ambiguous(() -> "{\"extensions\":{}}"); }

    @Test
    void r1_request_minusOne_available_correction_atTheTracedLocation() {
        send(() -> OK);
        assertThat(requests).hasSize(1);
        JsonNode vars = requests.get(0).path("variables");
        assertThat(requests.get(0).path("query").asText()).contains("inventoryAdjustQuantities").contains("@idempotent");
        assertThat(vars.path("idempotencyKey").asText()).isEqualTo("key-1");
        JsonNode input = vars.path("input");
        assertThat(input.path("reason").asText()).isEqualTo("correction");
        assertThat(input.path("name").asText()).isEqualTo("available");
        assertThat(input.path("referenceDocumentUri").asText()).isEqualTo("traced://piece/P1");
        JsonNode change = input.path("changes").get(0);
        assertThat(change.path("delta").asInt()).isEqualTo(-1);
        assertThat(change.path("inventoryItemId").asText()).isEqualTo("gid://shopify/InventoryItem/1");
        assertThat(change.path("locationId").asText()).isEqualTo("gid://shopify/Location/9");
    }

    @Test
    void r2_nonNegativeDelta_refusedBeforeAnyRequest() {
        for (int delta : new int[] {0, 1}) {
            assertThatThrownBy(() -> gateway(() -> OK).pushPieceSold("s.myshopify.com", "t",
                "gid://shopify/InventoryItem/1", "gid://shopify/Location/9", delta, "traced://piece/P1", "k"))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(requests).isEmpty();
    }
}
