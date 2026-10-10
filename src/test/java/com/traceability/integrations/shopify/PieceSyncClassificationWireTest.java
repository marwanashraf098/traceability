package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * Piece sync (2026-10-10, D2/D7) — the REAL ShopifyHttpGateway, fake network (the
 * TransferOutClassificationWireTest harness). Every piece-sync write makes exactly ONE mutation request
 * and classifies it exactly like pushTransferOut:
 *   DEFINITE (ShopifyException — nothing applied; the sweep may re-send, ≤ 5 attempts):
 *     HTTP 4xx · userErrors · top-level errors that are ONLY THROTTLED, with no data
 *   AMBIGUOUS (ShopifyAmbiguousException — failed_ambiguous, never re-sent):
 *     HTTP 5xx · any other top-level GraphQL error · THROTTLED mixed / with data · read timeout ·
 *     connection refused · no data · unreadable body
 * for pushVoidCorrection, pushHoldEnter (in place), moveAvailableToDamaged and pushPieceIncrement — and
 * (C2) pushPieceWriteOff (the sixth named decrement) and moveDamagedToAvailable (Back to good).
 * d1: the damage move now carries referenceDocumentUri (Shopify rejected a null one in production on
 * 2026-10-10 — "Expected value to not be null").
 */
class PieceSyncClassificationWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String ADJUST_OK = "{\"data\":{\"inventoryAdjustQuantities\":{\"inventoryAdjustmentGroup\":" +
        "{\"createdAt\":\"2026-10-10T00:00:00Z\"},\"userErrors\":[]}}}";
    private static final String MOVE_OK = "{\"data\":{\"inventoryMoveQuantities\":{\"inventoryAdjustmentGroup\":" +
        "{\"createdAt\":\"2026-10-10T00:00:00Z\"},\"userErrors\":[]}}}";
    private static final String LEVEL_READ = "{\"data\":{\"nodes\":[{\"id\":\"gid://shopify/InventoryItem/1\"," +
        "\"inventoryLevel\":{\"quantities\":[{\"name\":\"available\",\"quantity\":3}]}}]}}";

    enum Write { VOID, HOLD, DAMAGE_MOVE, INCREMENT, WRITE_OFF, DAMAGE_RESTORE }

    /** Mutation request bodies only (the damage move's baseline read is answered separately). */
    private final List<JsonNode> mutations = new ArrayList<>();

    private ShopifyHttpGateway gateway(Supplier<Object> responder) {
        ClientHttpRequestInterceptor i = (request, body, execution) -> {
            JsonNode json = M.readTree(new String(body, StandardCharsets.UTF_8));
            Object r;
            if (!json.path("query").asText().contains("mutation")) {
                r = LEVEL_READ;   // the damage move's compare-and-swap baseline read
            } else {
                mutations.add(json);
                r = responder.get();
            }
            if (r instanceof IOException io) throw io;
            HttpStatus status = r instanceof HttpStatus hs ? hs : HttpStatus.OK;
            String text = r instanceof String s ? s : "{}";
            MockClientHttpResponse resp = new MockClientHttpResponse(text.getBytes(StandardCharsets.UTF_8), status);
            resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return resp;
        };
        return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(i), M, "2026-04", "id", "secret");
    }

    private void send(Write w, Supplier<Object> responder) {
        ShopifyHttpGateway g = gateway(responder);
        String item = "gid://shopify/InventoryItem/1", loc = "gid://shopify/Location/9", ref = "traced://piece/P1";
        switch (w) {
            case VOID -> g.pushVoidCorrection("s.myshopify.com", "t", item, loc, -1, ref, "key-1");
            case HOLD -> g.pushHoldEnter("s.myshopify.com", "t", item, loc, -1, ref, "key-1");
            case DAMAGE_MOVE -> g.moveAvailableToDamaged("s.myshopify.com", "t", item, loc, 1, "damaged", ref, "key-1");
            case INCREMENT -> g.pushPieceIncrement("s.myshopify.com", "t", item, loc, 1, "correction", ref, "key-1");
            case WRITE_OFF -> g.pushPieceWriteOff("s.myshopify.com", "t", item, loc, -1, ref, "key-1");
            case DAMAGE_RESTORE -> g.moveDamagedToAvailable("s.myshopify.com", "t", item, loc, 1, "correction", ref, "key-1");
        }
    }

    private static boolean move(Write w) { return w == Write.DAMAGE_MOVE || w == Write.DAMAGE_RESTORE; }

    private static String ok(Write w) { return move(w) ? MOVE_OK : ADJUST_OK; }

    private static String userErrors(Write w) {
        String field = move(w) ? "inventoryMoveQuantities" : "inventoryAdjustQuantities";
        return "{\"data\":{\"" + field + "\":{\"userErrors\":[{\"message\":\"Not enough available\"}]}}}";
    }

    private void definite(Write w, Supplier<Object> responder) {
        assertThatThrownBy(() -> send(w, responder)).isExactlyInstanceOf(ShopifyException.class);
        assertThat(mutations).as("exactly one attempt").hasSize(1);
    }

    private void ambiguous(Write w, Supplier<Object> responder) {
        assertThatThrownBy(() -> send(w, responder)).isExactlyInstanceOf(ShopifyAmbiguousException.class);
        assertThat(mutations).as("exactly one attempt — never retried").hasSize(1);
    }

    @ParameterizedTest @EnumSource(Write.class)
    void c1_http4xx_definite(Write w) { definite(w, () -> HttpStatus.UNPROCESSABLE_ENTITY); }

    @ParameterizedTest @EnumSource(Write.class)
    void c2_userErrors_definite(Write w) { definite(w, () -> userErrors(w)); }

    @ParameterizedTest @EnumSource(Write.class)
    void c3_throttledOnlyNoData_definite(Write w) {
        definite(w, () -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}");
    }

    @ParameterizedTest @EnumSource(Write.class)
    void c4_http5xx_ambiguous(Write w) { ambiguous(w, () -> HttpStatus.BAD_GATEWAY); }

    @ParameterizedTest @EnumSource(Write.class)
    void c5_otherTopLevelGraphqlError_ambiguous(Write w) {
        ambiguous(w, () -> "{\"errors\":[{\"message\":\"Internal error\",\"extensions\":{\"code\":\"INTERNAL_SERVER_ERROR\"}}]}");
    }

    @ParameterizedTest @EnumSource(Write.class)
    void c6_throttledMixed_ambiguous(Write w) {
        ambiguous(w, () -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}," +
            "{\"message\":\"Internal error\"}]}");
    }

    @ParameterizedTest @EnumSource(Write.class)
    void c7_throttledWithData_ambiguous(Write w) {
        ambiguous(w, () -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]," +
            "\"data\":{\"x\":null}}");
    }

    @ParameterizedTest @EnumSource(Write.class)
    void c8_readTimeout_ambiguous(Write w) { ambiguous(w, () -> new java.net.SocketTimeoutException("Read timed out")); }

    @ParameterizedTest @EnumSource(Write.class)
    void c9_connectionRefused_ambiguous(Write w) { ambiguous(w, () -> new java.net.ConnectException("Connection refused")); }

    @ParameterizedTest @EnumSource(Write.class)
    void c10_noData_ambiguous(Write w) { ambiguous(w, () -> "{\"extensions\":{}}"); }

    @ParameterizedTest @EnumSource(Write.class)
    void c11_unreadableBody_ambiguous(Write w) { ambiguous(w, () -> "not json at all"); }

    @ParameterizedTest @EnumSource(Write.class)
    void c12_success_oneMutation_carriesPieceReference(Write w) {
        send(w, () -> ok(w));
        assertThat(mutations).hasSize(1);
        JsonNode input = mutations.get(0).path("variables").path("input");
        assertThat(input.path("referenceDocumentUri").asText()).isEqualTo("traced://piece/P1");
        assertThat(mutations.get(0).path("variables").path("idempotencyKey").asText()).isEqualTo("key-1");
    }

    @Test
    void d1_damageMove_sendsReferenceDocumentUri_availableToDamaged_sameLocation() {
        send(Write.DAMAGE_MOVE, () -> MOVE_OK);
        JsonNode input = mutations.get(0).path("variables").path("input");
        assertThat(input.has("referenceDocumentUri")).isTrue();
        assertThat(input.path("referenceDocumentUri").isNull()).isFalse();
        assertThat(input.path("referenceDocumentUri").asText()).isEqualTo("traced://piece/P1");
        JsonNode change = input.path("changes").get(0);
        assertThat(change.path("from").path("name").asText()).isEqualTo("available");
        assertThat(change.path("to").path("name").asText()).isEqualTo("damaged");
        assertThat(change.path("from").path("locationId").asText()).isEqualTo("gid://shopify/Location/9");
        assertThat(change.path("to").path("locationId").asText()).isEqualTo("gid://shopify/Location/9");
        assertThat(change.path("from").path("changeFromQuantity").asInt()).as("baseline read first").isEqualTo(3);
    }

    @Test
    void d3_damageRestore_isAPositiveMove_damagedToAvailable() {
        assertThatThrownBy(() -> gateway(() -> MOVE_OK).moveDamagedToAvailable("s.myshopify.com", "t",
            "gid://shopify/InventoryItem/1", "gid://shopify/Location/9", 0, "correction", "traced://piece/P1", "k"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(mutations).isEmpty();
        send(Write.DAMAGE_RESTORE, () -> MOVE_OK);
        JsonNode change = mutations.get(0).path("variables").path("input").path("changes").get(0);
        assertThat(change.path("from").path("name").asText()).isEqualTo("damaged");
        assertThat(change.path("to").path("name").asText()).isEqualTo("available");
        assertThat(change.path("quantity").asInt()).isEqualTo(1);
        assertThat(change.path("from").path("locationId").asText()).isEqualTo(change.path("to").path("locationId").asText());
    }

    @Test
    void d4_writeOff_negativeOnly_refusedBeforeAnyRequest() {
        assertThatThrownBy(() -> gateway(() -> ADJUST_OK).pushPieceWriteOff("s.myshopify.com", "t",
            "gid://shopify/InventoryItem/1", "gid://shopify/Location/9", 1, "traced://piece/P1", "k"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(mutations).isEmpty();
        send(Write.WRITE_OFF, () -> ADJUST_OK);
        JsonNode change = mutations.get(0).path("variables").path("input").path("changes").get(0);
        assertThat(change.path("delta").asInt()).isEqualTo(-1);
        assertThat(change.path("locationId").asText()).isEqualTo("gid://shopify/Location/9");
    }

    @Test
    void d2_increment_positiveOnly_refusedBeforeAnyRequest() {
        assertThatThrownBy(() -> gateway(() -> ADJUST_OK).pushPieceIncrement("s.myshopify.com", "t",
            "gid://shopify/InventoryItem/1", "gid://shopify/Location/9", 0, "correction", "traced://piece/P1", "k"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway(() -> ADJUST_OK).pushPieceIncrement("s.myshopify.com", "t",
            "gid://shopify/InventoryItem/1", "gid://shopify/Location/9", -1, "correction", "traced://piece/P1", "k"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(mutations).isEmpty();
    }
}
