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

/**
 * Part D fix P1 (2026-11-08) — the increment adjust is ONE HTTP attempt, classified like pushTransferOut, and a
 * recovery check (resendInventoryAdjustment) never comes back REJECTED or NEVER_SENT. Same fake-network harness as
 * ShopifyAdjustClassificationWireTest: for an adjust, request 1 is the baseline read and request 2 the mutation; a
 * resend has no read.
 *
 *   o1 — read timeout on the mutation: exactly ONE mutation request (no Resilience4j re-send), AMBIGUOUS
 *   o2 — read timeout, then (on a would-be re-send) connection refused: still one request, AMBIGUOUS — never the
 *        NEVER_SENT the last of three sends would say
 *   o3 — top-level errors: a non-THROTTLED error → AMBIGUOUS; ALL THROTTLED with no data → REJECTED after one
 *        request (no throttle loop); errors next to data → AMBIGUOUS; no data → AMBIGUOUS
 *   o4 — recovery check, CHANGE_FROM_QUANTITY_STALE → AMBIGUOUS + probeStale (never REJECTED)
 *   o5 — recovery check, IDEMPOTENCY_KEY_PARAMETER_MISMATCH / any other userError → AMBIGUOUS + probeStale
 *   o6 — recovery check, a non-THROTTLED top-level error or no data → AMBIGUOUS + probeStale
 *   o7 — recovery check, 4xx / THROTTLED / connection refused / 5xx / timeout → AMBIGUOUS, NOT probeStale (the
 *        first send's fate is still unknown; the identical re-send may be tried again)
 *   o8 — a FIRST send's STALE userError stays REJECTED (Shopify refused before applying), not probeStale
 */
class AdjustOneShotWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String LEVELS = "{\"data\":{\"nodes\":[{\"id\":\"item\",\"inventoryLevel\":" +
        "{\"quantities\":[{\"name\":\"available\",\"quantity\":7}]}}]}}";
    private static final String STALE = "{\"data\":{\"inventoryAdjustQuantities\":{\"userErrors\":[{\"message\":" +
        "\"The compare quantity is stale\",\"code\":\"CHANGE_FROM_QUANTITY_STALE\"}]}}}";
    private static final String MISMATCH = "{\"data\":{\"inventoryAdjustQuantities\":{\"userErrors\":[{\"message\":" +
        "\"Key used with different parameters\",\"code\":\"IDEMPOTENCY_KEY_PARAMETER_MISMATCH\"}]}}}";
    private static final String OTHER_USER_ERROR = "{\"data\":{\"inventoryAdjustQuantities\":{\"userErrors\":[{\"message\":" +
        "\"The item is not stocked at the location\",\"code\":\"ITEM_NOT_STOCKED_AT_LOCATION\"}]}}}";
    private static final String THROTTLED = "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}";
    private static final String INTERNAL = "{\"errors\":[{\"message\":\"Internal error\",\"extensions\":{\"code\":\"INTERNAL_SERVER_ERROR\"}}]}";
    private static final String ERRORS_WITH_DATA = "{\"errors\":[{\"message\":\"Partial\"}],\"data\":{\"inventoryAdjustQuantities\":" +
        "{\"inventoryAdjustmentGroup\":{\"createdAt\":\"2026-11-08T00:00:00Z\"},\"userErrors\":[]}}}";

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

        long mutations() {
            return bodies.stream().filter(b -> b.path("query").asText().contains("inventoryAdjustQuantities")).count();
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

    private static ShopifyAdjustFailedException recoveryCheckFails(Fake fake) {
        try {
            fake.gateway().resendInventoryAdjustment("s.myshopify.com", "t", "item", "loc", 3, "received", "key-orig", 7);
        } catch (ShopifyAdjustFailedException e) {
            return e;
        }
        throw new AssertionError("expected the recovery check to fail");
    }

    /** The mutation (request 2 of an adjust) answers with {@code mutation}; the baseline read succeeds. */
    private static Fake afterRead(Object mutation) {
        return new Fake(n -> n == 1 ? LEVELS : mutation);
    }

    @Test
    void o1_readTimeout_oneMutationRequest_ambiguous() {
        Fake fake = afterRead(new java.net.SocketTimeoutException("Read timed out"));
        ShopifyAdjustFailedException e = adjustFails(fake);
        assertThat(fake.mutations()).as("no transport re-send of the mutation").isEqualTo(1);
        assertThat(e.failureClass()).isEqualTo(FailureClass.AMBIGUOUS);
        assertThat(e.probeStale()).isFalse();
        assertThat(e.changeFromQuantity()).isEqualTo(7);
    }

    @Test
    void o2_readTimeoutThenConnectionRefused_stillOneRequest_ambiguousNotNeverSent() {
        Fake fake = new Fake(n -> n == 1 ? LEVELS
            : n == 2 ? new java.net.SocketTimeoutException("Read timed out")
            : new java.net.ConnectException("Connection refused"));
        ShopifyAdjustFailedException e = adjustFails(fake);
        assertThat(fake.mutations()).isEqualTo(1);
        assertThat(e.failureClass()).isEqualTo(FailureClass.AMBIGUOUS);
    }

    @Test
    void o3_topLevelErrors_classifiedLikeTheTransferPush() {
        Fake internal = afterRead(INTERNAL);
        assertThat(adjustFails(internal).failureClass()).isEqualTo(FailureClass.AMBIGUOUS);
        assertThat(internal.mutations()).isEqualTo(1);

        Fake throttled = afterRead(THROTTLED);
        assertThat(adjustFails(throttled).failureClass()).as("THROTTLED only, no data: not executed")
            .isEqualTo(FailureClass.REJECTED);
        assertThat(throttled.mutations()).as("no throttle re-send loop").isEqualTo(1);

        assertThat(adjustFails(afterRead(ERRORS_WITH_DATA)).failureClass()).isEqualTo(FailureClass.AMBIGUOUS);
        assertThat(adjustFails(afterRead("{}")).failureClass()).as("no data").isEqualTo(FailureClass.AMBIGUOUS);
        assertThat(adjustFails(afterRead("{\"data\":null}")).failureClass()).isEqualTo(FailureClass.AMBIGUOUS);
    }

    @Test
    void o4_recoveryCheckStale_ambiguousProbeStale_neverRejected() {
        Fake fake = new Fake(n -> STALE);
        ShopifyAdjustFailedException e = recoveryCheckFails(fake);
        assertThat(fake.bodies).hasSize(1);
        assertThat(e.failureClass()).isEqualTo(FailureClass.AMBIGUOUS);
        assertThat(e.probeStale()).isTrue();
        assertThat(e.changeFromQuantity()).isEqualTo(7);
        assertThat(e.getMessage()).contains("CHANGE_FROM_QUANTITY_STALE").contains("check Shopify");
    }

    @Test
    void o5_recoveryCheckIdempotencyOrOtherUserError_ambiguousProbeStale() {
        for (String answer : List.of(MISMATCH, OTHER_USER_ERROR)) {
            ShopifyAdjustFailedException e = recoveryCheckFails(new Fake(n -> answer));
            assertThat(e.failureClass()).as(answer).isEqualTo(FailureClass.AMBIGUOUS);
            assertThat(e.probeStale()).as(answer).isTrue();
        }
    }

    @Test
    void o6_recoveryCheckTopLevelErrorOrNoData_ambiguousProbeStale() {
        for (String answer : List.of(INTERNAL, ERRORS_WITH_DATA, "{}")) {
            ShopifyAdjustFailedException e = recoveryCheckFails(new Fake(n -> answer));
            assertThat(e.failureClass()).as(answer).isEqualTo(FailureClass.AMBIGUOUS);
            assertThat(e.probeStale()).as(answer).isTrue();
        }
    }

    @Test
    void o7_recoveryCheckTransportFailures_ambiguousButNotProbeStale() {
        List<Object> answers = List.of(HttpStatus.UNPROCESSABLE_ENTITY, THROTTLED,
            new java.net.ConnectException("Connection refused"), HttpStatus.INTERNAL_SERVER_ERROR,
            new java.net.SocketTimeoutException("Read timed out"));
        for (Object answer : answers) {
            Fake fake = new Fake(n -> answer);
            ShopifyAdjustFailedException e = recoveryCheckFails(fake);
            assertThat(fake.bodies).as(String.valueOf(answer)).hasSize(1);
            assertThat(e.failureClass()).as(String.valueOf(answer)).isEqualTo(FailureClass.AMBIGUOUS);
            assertThat(e.probeStale()).as(String.valueOf(answer)).isFalse();
        }
    }

    @Test
    void o8_firstSendStale_staysRejected_notProbeStale() {
        ShopifyAdjustFailedException e = adjustFails(afterRead(STALE));
        assertThat(e.failureClass()).isEqualTo(FailureClass.REJECTED);
        assertThat(e.probeStale()).isFalse();
    }
}
