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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Part D P3 (2026-11-08) — the read-only hidden-stock audit read on the wire (ShopifyHttpGateway.auditMainLevels).
 *
 *   a1 — one request, a QUERY (no mutation), includeInactive: true, the ids in order; every class parsed:
 *        active (quantities), inactive (its available / on_hand = hidden stock), missing (no level at Main, list
 *        complete), unknown (Main not in the first 20 and hasNextPage — nothing guessed), item_gone (no node)
 *   a2 — at most 10 items per request (more → IllegalArgumentException, nothing sent); none → no request
 *   a3 — a top-level GraphQL error → ShopifyException
 */
class MainLevelAuditWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String MAIN = "gid://shopify/Location/1";

    private static String level(String loc, boolean active, int available, int onHand) {
        return "{\"isActive\":" + active + ",\"location\":{\"id\":\"" + loc + "\"},\"quantities\":[" +
            "{\"name\":\"available\",\"quantity\":" + available + "},{\"name\":\"on_hand\",\"quantity\":" + onHand + "}]}";
    }

    private static String item(String id, String sku, boolean more, String... levels) {
        return "{\"id\":\"" + id + "\",\"sku\":\"" + sku + "\",\"inventoryLevels\":{\"pageInfo\":{\"hasNextPage\":" + more +
            "},\"nodes\":[" + String.join(",", levels) + "]}}";
    }

    private static final class Fake {
        final List<JsonNode> bodies = new ArrayList<>();
        final String answer;
        Fake(String answer) { this.answer = answer; }

        ShopifyHttpGateway gateway() {
            ClientHttpRequestInterceptor i = (request, body, execution) -> {
                bodies.add(M.readTree(new String(body, StandardCharsets.UTF_8)));
                MockClientHttpResponse resp = new MockClientHttpResponse(answer.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return resp;
            };
            return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(i), M, "2026-04", "id", "secret");
        }
    }

    @Test
    void a1_oneQuery_everyClassParsed() {
        String answer = "{\"data\":{\"nodes\":[" +
            item("i-active", "SKU-A", false, level("gid://shopify/Location/9", true, 50, 50), level(MAIN, true, 3, 4)) + "," +
            item("i-inactive", "SKU-B", false, level(MAIN, false, 2, 5)) + "," +
            item("i-missing", "SKU-C", false, level("gid://shopify/Location/9", true, 1, 1)) + "," +
            item("i-unknown", "SKU-D", true, level("gid://shopify/Location/9", true, 1, 1)) + "," +
            "null]}}";
        Fake fake = new Fake(answer);
        List<ShopifyGateway.MainLevelAudit> out = fake.gateway().auditMainLevels("s.myshopify.com", "t", MAIN,
            List.of("i-active", "i-inactive", "i-missing", "i-unknown", "i-gone"));

        assertThat(out).containsExactly(
            new ShopifyGateway.MainLevelAudit("i-active", "SKU-A", "active", 3, 4),
            new ShopifyGateway.MainLevelAudit("i-inactive", "SKU-B", "inactive", 2, 5),
            new ShopifyGateway.MainLevelAudit("i-missing", "SKU-C", "missing", null, null),
            new ShopifyGateway.MainLevelAudit("i-unknown", "SKU-D", "unknown", null, null),
            new ShopifyGateway.MainLevelAudit("i-gone", null, "item_gone", null, null));
        assertThat(fake.bodies).hasSize(1);
        String q = fake.bodies.get(0).path("query").asText();
        assertThat(q.trim()).startsWith("query TracedMainLevelAudit");
        assertThat(q).doesNotContain("mutation").contains("includeInactive: true").contains("first: 20")
            .contains("hasNextPage");
        List<String> ids = new ArrayList<>();
        fake.bodies.get(0).path("variables").path("ids").forEach(n -> ids.add(n.asText()));
        assertThat(ids).containsExactly("i-active", "i-inactive", "i-missing", "i-unknown", "i-gone");
    }

    @Test
    void a2_atMostTenPerRequest_noneSendsNothing() {
        Fake fake = new Fake("{\"data\":{\"nodes\":[]}}");
        List<String> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) eleven.add("i" + i);
        assertThatThrownBy(() -> fake.gateway().auditMainLevels("s.myshopify.com", "t", MAIN, eleven))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.gateway().auditMainLevels("s.myshopify.com", "t", MAIN, List.of())).isEmpty();
        assertThat(fake.bodies).isEmpty();
        assertThat(ShopifyGateway.MAIN_LEVEL_AUDIT_BATCH).isEqualTo(10);
    }

    @Test
    void a3_topLevelError_throws() {
        Fake fake = new Fake("{\"errors\":[{\"message\":\"Internal error\"}]}");
        assertThatThrownBy(() -> fake.gateway().auditMainLevels("s.myshopify.com", "t", MAIN, List.of("i")))
            .isInstanceOf(ShopifyException.class);
    }
}
