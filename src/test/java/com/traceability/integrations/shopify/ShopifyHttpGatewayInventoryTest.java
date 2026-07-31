package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests against the real ShopifyHttpGateway impl (no Spring context, no network —
 * FR-17 v2's positive-delta/quantity validation happens BEFORE any HTTP call, so these run
 * with no mocking at all). Same package as ShopifyHttpGateway (package-private) by design.
 *
 * Matrix:
 *   g1 — adjustInventoryQuantities rejects delta <= 0
 *   g2 — moveAvailableToDamaged rejects quantity <= 0
 *   g3 — no bare "@idempotent" token on any FR-17 v2 mutation (regression guard — a
 *        keyless directive was removed 2026-07-30 as decorative/misleading; the real
 *        concurrency guard is the DB claim-row in ShopifyInventoryService.claim(). If a
 *        real idempotency key mechanism is added later, it belongs alongside a derived
 *        key, not as a bare token — see the comment above these mutation constants)
 *   g4 — buildInventoryChange() always includes the changeFromQuantity key (present, not
 *        omitted) regardless of whether a real baseline was resolved
 *   g5 — end-to-end through the REAL executeGraphQL/RestClient plumbing (a fake
 *        ClientHttpRequestInterceptor stands in for the network, so no real HTTP call is
 *        made): the actual second request body sent for adjustInventoryQuantities contains
 *        "changeFromQuantity" with the value read from the first (fetchAvailableQuantities)
 *        call. This is the exact gap that let the production bug through — every other test
 *        in this codebase mocks ShopifyGateway and never inspects the real wire payload.
 */
class ShopifyHttpGatewayInventoryTest {

    private final ShopifyHttpGateway gateway = new ShopifyHttpGateway(
        RestClient.builder(), new ObjectMapper(), "2026-04", "test-client-id", "test-client-secret");

    @Test
    void g1_adjustInventoryQuantities_rejectsNonPositiveDelta() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            gateway.adjustInventoryQuantities("shop.myshopify.com", "tok", "item", "loc", 0, "received"));
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            gateway.adjustInventoryQuantities("shop.myshopify.com", "tok", "item", "loc", -1, "received"));
    }

    @Test
    void g2_moveAvailableToDamaged_rejectsNonPositiveQuantity() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            gateway.moveAvailableToDamaged("shop.myshopify.com", "tok", "item", "loc", 0, "damaged"));
        Assertions.assertThrows(IllegalArgumentException.class, () ->
            gateway.moveAvailableToDamaged("shop.myshopify.com", "tok", "item", "loc", -1, "damaged"));
    }

    @Test
    void g3_noBareIdempotentDirectiveOnAnyMutation() throws Exception {
        assertThat(mutationText("INVENTORY_ADJUST_QUANTITIES_MUTATION")).doesNotContain("@idempotent");
        assertThat(mutationText("INVENTORY_MOVE_QUANTITIES_MUTATION")).doesNotContain("@idempotent");
        assertThat(mutationText("INVENTORY_ACTIVATE_MUTATION")).doesNotContain("@idempotent");
    }

    @Test
    void g4_buildInventoryChange_alwaysIncludesChangeFromQuantityKey() {
        ObjectNode withBaseline = gateway.buildInventoryChange("item1", "loc1", 5, 7);
        assertThat(withBaseline.has("changeFromQuantity"))
            .as("g4: the key must be present, not omitted, even when the value is a real int")
            .isTrue();
        assertThat(withBaseline.get("changeFromQuantity").asInt()).isEqualTo(7);

        ObjectNode withoutBaseline = gateway.buildInventoryChange("item1", "loc1", 5, null);
        assertThat(withoutBaseline.has("changeFromQuantity"))
            .as("g4: the key must be present even when opting out with null — this is exactly " +
                "the production bug: omitting the key entirely, not merely nulling its value")
            .isTrue();
        assertThat(withoutBaseline.get("changeFromQuantity").isNull()).isTrue();
    }

    @Test
    void g5_adjustInventoryQuantities_realWirePayloadIncludesChangeFromQuantity() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> capturedBodies = new ArrayList<>();
        AtomicInteger callCount = new AtomicInteger(0);

        // Stands in for the network: no real HTTP call is made. First call = the
        // fetchAvailableQuantities read inside adjustInventoryQuantities (returns available=7);
        // second call = the actual inventoryAdjustQuantities mutation whose payload we inspect.
        org.springframework.http.client.ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            capturedBodies.add(new String(body, StandardCharsets.UTF_8));
            int n = callCount.incrementAndGet();
            String responseJson = n == 1
                ? "{\"data\":{\"nodes\":[{\"id\":\"gid://shopify/InventoryItem/item1\"," +
                  "\"inventoryLevel\":{\"quantities\":[{\"name\":\"available\",\"quantity\":7}]}}]}}"
                : "{\"data\":{\"inventoryAdjustQuantities\":{" +
                  "\"inventoryAdjustmentGroup\":{\"createdAt\":\"2026-07-31T00:00:00Z\"},\"userErrors\":[]}}}";
            MockClientHttpResponse response = new MockClientHttpResponse(
                responseJson.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        };

        RestClient.Builder builder = RestClient.builder().requestInterceptor(interceptor);
        ShopifyHttpGateway realGateway = new ShopifyHttpGateway(
            builder, mapper, "2026-04", "test-client-id", "test-client-secret");

        realGateway.adjustInventoryQuantities("shop.myshopify.com", "tok",
            "gid://shopify/InventoryItem/item1", "gid://shopify/Location/loc1", 5, "received");

        assertThat(capturedBodies).as("g5: exactly one read call + one write call").hasSize(2);

        JsonNode writeRequest = mapper.readTree(capturedBodies.get(1));
        JsonNode changes = writeRequest.path("variables").path("input").path("changes");
        assertThat(changes.isArray()).isTrue();
        JsonNode change0 = changes.get(0);

        assertThat(change0.has("changeFromQuantity"))
            .as("g5: the REAL request body sent to Shopify must include changeFromQuantity — " +
                "this is the exact production bug (\"InventoryChangeInput must include the " +
                "following argument: changeFromQuantity\"), which no mocked-gateway test could " +
                "ever have caught")
            .isTrue();
        assertThat(change0.path("changeFromQuantity").asInt())
            .as("g5: the baseline is the value read from the first call, not guessed")
            .isEqualTo(7);
        assertThat(change0.path("delta").asInt()).isEqualTo(5);
    }

    private static String mutationText(String fieldName) throws Exception {
        Field f = ShopifyHttpGateway.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (String) f.get(null);
    }
}
