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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shopify-native returns S5a — how the REAL one-attempt transport ({@link ShopifyHttpGateway#executeReturnsWriteOnce},
 * through ShopifyReturnsWriteGatewayImpl) classifies a returns write. Exactly ONE request every time (a re-sent
 * returnCreate would make a second return). DEFINITE (nothing applied): HTTP 4xx, userErrors, THROTTLED-only with no
 * data. ACCESS_DENIED: top-level ACCESS_DENIED, HTTP 403. AMBIGUOUS: everything else. The reason never carries a
 * message or a body. Also: what returnCreate / returnProcess send (no restock, no refund, no exchange line, no fee).
 */
class ReturnsWriteClassificationWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String OK = "{\"data\":{\"returnCreate\":{\"return\":{\"id\":\"gid://shopify/Return/1\"," +
        "\"name\":\"#1001-R1\",\"status\":\"OPEN\"},\"userErrors\":[]}}}";

    private final List<JsonNode> bodies = new ArrayList<>();

    private ShopifyReturnsWriteGatewayImpl gateway(Supplier<Object> responder) {
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
        ShopifyHttpGateway http = new ShopifyHttpGateway(RestClient.builder().requestInterceptor(i), M, "2026-04", "id", "secret");
        return new ShopifyReturnsWriteGatewayImpl(http, M);
    }

    private ReturnsWriteResponse create(Supplier<Object> responder) {
        return gateway(responder).returnCreate("s.myshopify.com", "t", "gid://shopify/Order/1",
            Instant.parse("2026-11-07T10:00:00Z"),
            List.of(new ShopifyReturnsWriteGateway.CreateLine("gid://shopify/FulfillmentLineItem/7", 2, "Traced RR-ABC234 · Wrong size")));
    }

    private void is(Supplier<Object> responder, ReturnsWriteResponse.Kind kind) {
        ReturnsWriteResponse r = create(responder);
        assertThat(r.kind()).isEqualTo(kind);
        assertThat(bodies).hasSize(1);
        if (r.reason() != null) assertThat(r.reason()).doesNotContain("secret message").doesNotContain("{");
    }

    @Test void r1_http4xx_definite() { is(() -> HttpStatus.UNPROCESSABLE_ENTITY, ReturnsWriteResponse.Kind.DEFINITE); }

    @Test void r2_http403_accessDenied() { is(() -> HttpStatus.FORBIDDEN, ReturnsWriteResponse.Kind.ACCESS_DENIED); }

    @Test void r3_userErrors_definite_codesOnly() {
        ReturnsWriteResponse r = create(() -> "{\"data\":{\"returnCreate\":{\"return\":null,\"userErrors\":" +
            "[{\"field\":[\"returnInput\"],\"message\":\"secret message\",\"code\":\"INVALID\"}]}}}");
        assertThat(r.kind()).isEqualTo(ReturnsWriteResponse.Kind.DEFINITE);
        assertThat(r.userErrorCodes()).containsExactly("INVALID");
        assertThat(r.reason()).doesNotContain("secret message");
        assertThat(bodies).hasSize(1);
    }

    @Test void r4_throttledOnly_noData_definite() {
        is(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}",
            ReturnsWriteResponse.Kind.DEFINITE);
    }

    @Test void r5_accessDenied_topLevel() {
        is(() -> "{\"errors\":[{\"message\":\"Access denied\",\"extensions\":{\"code\":\"ACCESS_DENIED\"}}]," +
            "\"data\":{\"returnCreate\":null}}", ReturnsWriteResponse.Kind.ACCESS_DENIED);
    }

    @Test void r6_http5xx_ambiguous() { is(() -> HttpStatus.BAD_GATEWAY, ReturnsWriteResponse.Kind.AMBIGUOUS); }

    @Test void r7_otherTopLevelError_ambiguous() {
        is(() -> "{\"errors\":[{\"message\":\"Internal\",\"extensions\":{\"code\":\"INTERNAL_SERVER_ERROR\"}}]}",
            ReturnsWriteResponse.Kind.AMBIGUOUS);
    }

    @Test void r8_throttledWithData_ambiguous() {
        is(() -> "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]," +
            "\"data\":{\"returnCreate\":{\"return\":{\"id\":\"x\"},\"userErrors\":[]}}}", ReturnsWriteResponse.Kind.AMBIGUOUS);
    }

    @Test void r9_timeout_ambiguous() {
        is(() -> new java.net.SocketTimeoutException("Read timed out"), ReturnsWriteResponse.Kind.AMBIGUOUS);
    }

    @Test void r10_noPayload_or_unreadable_ambiguous() {
        is(() -> "{\"data\":{}}", ReturnsWriteResponse.Kind.AMBIGUOUS);
        bodies.clear();
        is(() -> "not json", ReturnsWriteResponse.Kind.AMBIGUOUS);
    }

    @Test void r11_ok_sendsLinesNoteRequestedAt_andNothingElse() {
        ReturnsWriteResponse r = create(() -> OK);
        assertThat(r.ok()).isTrue();
        assertThat(r.payload().path("return").path("id").asText()).isEqualTo("gid://shopify/Return/1");
        JsonNode input = bodies.get(0).path("variables").path("returnInput");
        assertThat(input.path("orderId").asText()).isEqualTo("gid://shopify/Order/1");
        assertThat(input.path("requestedAt").asText()).isEqualTo("2026-11-07T10:00:00Z");
        assertThat(input.path("returnLineItems").get(0).path("returnReasonNote").asText()).isEqualTo("Traced RR-ABC234 · Wrong size");
        List<String> keys = new ArrayList<>();
        input.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("orderId", "requestedAt", "returnLineItems");
    }

    @Test void r12_process_notRestocked_withAndWithoutLocation_noRefund() {
        ShopifyReturnsWriteGatewayImpl g = gateway(() -> "{\"data\":{\"returnProcess\":{\"return\":{\"id\":\"r\"},\"userErrors\":[]}}}");
        assertThat(g.returnProcessNotRestocked("s.myshopify.com", "t", "gid://shopify/Return/1",
            "gid://shopify/ReturnLineItem/2", "gid://shopify/ReverseFulfillmentOrderLineItem/3", 1, null).ok()).isTrue();
        assertThat(g.returnProcessNotRestocked("s.myshopify.com", "t", "gid://shopify/Return/1",
            "gid://shopify/ReturnLineItem/2", "gid://shopify/ReverseFulfillmentOrderLineItem/3", 1, "gid://shopify/Location/9").ok()).isTrue();
        JsonNode first = bodies.get(0).path("variables").path("input");
        JsonNode d1 = first.path("returnLineItems").get(0).path("dispositions").get(0);
        assertThat(d1.path("dispositionType").asText()).isEqualTo("NOT_RESTOCKED");
        assertThat(d1.has("locationId")).isFalse();
        assertThat(bodies.get(1).path("variables").path("input").path("returnLineItems").get(0).path("dispositions").get(0)
            .path("locationId").asText()).isEqualTo("gid://shopify/Location/9");
        assertThat(first.path("notifyCustomer").asBoolean(true)).isFalse();
        assertThat(first.has("financialTransfer")).isFalse();
        assertThat(first.has("refundShipping")).isFalse();
        assertThat(first.has("exchangeLineItems")).isFalse();
    }

    @Test void r13_shape_logsNoValues() {
        String shape = ShopifyReturnsWriteGatewayImpl.shape(new ObjectMapper().createObjectNode()
            .put("status", "OPEN").put("name", "#1001-R1").put("note", "Ahmed's parcel"));
        assertThat(shape).contains("status:OPEN").doesNotContain("1001").doesNotContain("Ahmed");
    }
}
