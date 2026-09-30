package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyAdjustFailedException.FailureClass;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failed-increment recovery (Part D) — how the REAL gateway classifies an adjust failure (fake
 * network, same harness as ShopifyHttpGatewayInventoryTest.g5). Request 1 of an adjust is the
 * baseline read, request 2 the mutation.
 *
 *   c1 — the baseline read fails → NEVER_SENT, and the mutation is never sent
 *   c2 — userError on the mutation → REJECTED, carrying the baseline that was sent
 *   c3 — GraphQL error on the mutation → REJECTED
 *   c4 — HTTP 500 on the mutation → AMBIGUOUS
 *   c5 — HTTP 4xx on the mutation → REJECTED
 *   c6 — connection refused on the mutation → NEVER_SENT
 *   c7 — read timeout on the mutation → AMBIGUOUS
 *   c8 — resend: ONE request (no fresh read), the given key and the given changeFromQuantity;
 *        a non-positive delta is refused before any request
 */
class ShopifyAdjustClassificationWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String LEVELS = "{\"data\":{\"nodes\":[{\"id\":\"item\",\"inventoryLevel\":" +
        "{\"quantities\":[{\"name\":\"available\",\"quantity\":7}]}}]}}";
    private static final String OK = "{\"data\":{\"inventoryAdjustQuantities\":{\"inventoryAdjustmentGroup\":" +
        "{\"createdAt\":\"2026-09-30T00:00:00Z\"},\"userErrors\":[]}}}";

    /** Answers request n (1-based) with responder.apply(n); a thrown IOException simulates the network. */
    private static final class Fake {
        final List<JsonNode> bodies = new ArrayList<>();
        final Function<Integer, Object> responder;
        Fake(Function<Integer, Object> responder) { this.responder = responder; }

        ShopifyHttpGateway gateway() {
            ClientHttpRequestInterceptor i = (request, body, execution) -> {
                bodies.add(M.readTree(new String(body, StandardCharsets.UTF_8)));
                Object r = responder.apply(bodies.size());
                if (r instanceof IOException io) throw io;
                HttpStatus status = r instanceof HttpStatus hs ? hs : HttpStatus.OK;
                String json = r instanceof String s ? s : "{}";
                MockClientHttpResponse resp = new MockClientHttpResponse(json.getBytes(StandardCharsets.UTF_8), status);
                resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return resp;
            };
            return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(i), M, "2026-04", "id", "secret");
        }
    }

    private static ShopifyAdjustFailedException adjustFails(Fake fake) {
        try {
            fake.gateway().adjustInventoryQuantities("s.myshopify.com", "t", "item", "loc", 3, "received", "key-1");
        } catch (ShopifyAdjustFailedException e) {
            return e;
        }
        throw new AssertionError("expected the adjust to fail");
    }

    @Test
    void c1_baselineReadFails_neverSent_mutationNotSent() {
        Fake fake = new Fake(n -> "{\"errors\":[{\"message\":\"Internal error\"}]}");
        ShopifyAdjustFailedException e = adjustFails(fake);
        assertThat(e.failureClass()).isEqualTo(FailureClass.NEVER_SENT);
        assertThat(fake.bodies).hasSize(1);
        assertThat(fake.bodies.get(0).path("query").asText()).doesNotContain("inventoryAdjustQuantities");
    }

    @Test
    void c2_userError_rejected_carriesTheSentBaseline() {
        Fake fake = new Fake(n -> n == 1 ? LEVELS
            : "{\"data\":{\"inventoryAdjustQuantities\":{\"userErrors\":[{\"message\":\"The quantity is stale\",\"code\":\"CHANGE_FROM_QUANTITY_STALE\"}]}}}");
        ShopifyAdjustFailedException e = adjustFails(fake);
        assertThat(e.failureClass()).isEqualTo(FailureClass.REJECTED);
        assertThat(e.changeFromQuantity()).isEqualTo(7);
        assertThat(e.getMessage()).contains("inventoryAdjustQuantities failed: The quantity is stale");
    }

    @Test
    void c3_graphqlError_rejected() {
        Fake fake = new Fake(n -> n == 1 ? LEVELS : "{\"errors\":[{\"message\":\"Access denied\"}]}");
        assertThat(adjustFails(fake).failureClass()).isEqualTo(FailureClass.REJECTED);
    }

    @Test
    void c4_http500_ambiguous() {
        Fake fake = new Fake(n -> n == 1 ? LEVELS : HttpStatus.INTERNAL_SERVER_ERROR);
        ShopifyAdjustFailedException e = adjustFails(fake);
        assertThat(e.failureClass()).isEqualTo(FailureClass.AMBIGUOUS);
        assertThat(e.changeFromQuantity()).isEqualTo(7);
    }

    @Test
    void c5_http4xx_rejected() {
        Fake fake = new Fake(n -> n == 1 ? LEVELS : HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(adjustFails(fake).failureClass()).isEqualTo(FailureClass.REJECTED);
    }

    @Test
    void c6_connectionRefused_neverSent() {
        Fake fake = new Fake(n -> n == 1 ? LEVELS : new java.net.ConnectException("Connection refused"));
        assertThat(adjustFails(fake).failureClass()).isEqualTo(FailureClass.NEVER_SENT);
    }

    @Test
    void c7_readTimeout_ambiguous() {
        Fake fake = new Fake(n -> n == 1 ? LEVELS : new java.net.SocketTimeoutException("Read timed out"));
        assertThat(adjustFails(fake).failureClass()).isEqualTo(FailureClass.AMBIGUOUS);
    }

    @Test
    void c8_resend_oneRequest_givenKeyAndBaseline_positiveOnly() {
        Fake fake = new Fake(n -> OK);
        fake.gateway().resendInventoryAdjustment("s.myshopify.com", "t", "item", "loc", 3, "received", "key-orig", 7);

        assertThat(fake.bodies).as("no fresh baseline read").hasSize(1);
        JsonNode vars = fake.bodies.get(0).path("variables");
        assertThat(vars.path("idempotencyKey").asText()).isEqualTo("key-orig");
        JsonNode change = vars.path("input").path("changes").get(0);
        assertThat(change.path("changeFromQuantity").asInt()).isEqualTo(7);
        assertThat(change.path("delta").asInt()).isEqualTo(3);

        Fake none = new Fake(n -> OK);
        assertThatThrownBy(() -> none.gateway().resendInventoryAdjustment("s.myshopify.com", "t", "item", "loc", 0, "r", "k", 7))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(none.bodies).isEmpty();
    }
}
