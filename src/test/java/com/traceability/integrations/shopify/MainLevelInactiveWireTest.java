package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyAdjustFailedException.FailureClass;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Part D P2 (2026-11-08), test 8 (gateway half) — the inactive-level guard on the wire. Dev store, 2026-11-08
 * (scripts/dev/part-d-p2, via the Shopify CLI's app): an adjust on an INACTIVE level applies with no error and shows
 * only once the level is active again (activation.py q1c / q1d), and {@code inventoryItem.inventoryLevel(locationId:)}
 * answers null for an inactive level (level_shape.py). Same fake-network harness as AdjustOneShotWireTest.
 *
 *   w1 — adjust, the baseline read finds no active level (inventoryLevel null) → ONE request (the read), NO mutation,
 *        ShopifyMainLevelInactiveException (NEVER_SENT, error main_level_inactive:…). Before P2 the missing level read
 *        as available 0 and the +N went out with baseline 0 — onto the hidden level.
 *   w2 — the item itself gone (its node null / absent) → the same: nothing sent
 *   w3 — positive control: an active level → the mutation carries the read baseline (an int, never null)
 *   w4 — damage move, no active level → ONE request (the read), no move sent
 *   w5 — inactiveMainLevels: one read (no mutation), inventoryLevel(locationId:) per item; returns the items whose
 *        level is null and the items Shopify didn't return; blank / duplicate ids are not sent
 *   w6 — inactiveMainLevels: a failed read throws ShopifyException ("nothing sent")
 */
class MainLevelInactiveWireTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String NO_LEVEL = "{\"data\":{\"nodes\":[{\"id\":\"item\",\"inventoryLevel\":null}]}}";
    private static final String ITEM_GONE = "{\"data\":{\"nodes\":[null]}}";
    private static final String LEVEL_7 = "{\"data\":{\"nodes\":[{\"id\":\"item\",\"inventoryLevel\":" +
        "{\"quantities\":[{\"name\":\"available\",\"quantity\":7}]}}]}}";
    private static final String ADJUST_OK = "{\"data\":{\"inventoryAdjustQuantities\":{\"inventoryAdjustmentGroup\":" +
        "{\"createdAt\":\"2026-11-08T00:00:00Z\"},\"userErrors\":[]}}}";

    private static final class Fake {
        final List<JsonNode> bodies = new ArrayList<>();
        final Function<Integer, String> responder;
        Fake(Function<Integer, String> responder) { this.responder = responder; }

        ShopifyHttpGateway gateway() {
            ClientHttpRequestInterceptor i = (request, body, execution) -> {
                bodies.add(M.readTree(new String(body, StandardCharsets.UTF_8)));
                MockClientHttpResponse resp = new MockClientHttpResponse(
                    responder.apply(bodies.size()).getBytes(StandardCharsets.UTF_8), org.springframework.http.HttpStatus.OK);
                resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return resp;
            };
            return new ShopifyHttpGateway(RestClient.builder().requestInterceptor(i), M, "2026-04", "id", "secret");
        }

        long mutations() {
            return bodies.stream().filter(b -> b.path("query").asText().trim().startsWith("mutation")).count();
        }
    }

    @Test
    void w1_adjust_noActiveLevel_nothingSent_neverSent() {
        Fake fake = new Fake(n -> n == 1 ? NO_LEVEL : ADJUST_OK);
        assertThatThrownBy(() -> fake.gateway().adjustInventoryQuantities("s.myshopify.com", "t", "item", "loc", 3,
                "received", "key-1"))
            .isInstanceOfSatisfying(ShopifyMainLevelInactiveException.class, e -> {
                assertThat(e.failureClass()).isEqualTo(FailureClass.NEVER_SENT);
                assertThat(e.changeFromQuantity()).isNull();
                assertThat(e.getMessage()).startsWith(ShopifyMainLevelInactiveException.REASON + ": ");
            });
        assertThat(fake.bodies).as("only the baseline read").hasSize(1);
        assertThat(fake.mutations()).isZero();
    }

    @Test
    void w2_adjust_itemGone_nothingSent() {
        for (String answer : List.of(ITEM_GONE, "{\"data\":{\"nodes\":[]}}")) {
            Fake fake = new Fake(n -> n == 1 ? answer : ADJUST_OK);
            assertThatThrownBy(() -> fake.gateway().adjustInventoryQuantities("s.myshopify.com", "t", "item", "loc", 3,
                    "received", "key-1"))
                .as(answer).isInstanceOf(ShopifyMainLevelInactiveException.class);
            assertThat(fake.mutations()).as(answer).isZero();
        }
    }

    @Test
    void w3_adjust_activeLevel_sentWithTheReadBaseline() {
        Fake fake = new Fake(n -> n == 1 ? LEVEL_7 : ADJUST_OK);
        fake.gateway().adjustInventoryQuantities("s.myshopify.com", "t", "item", "loc", 3, "received", "key-1");
        assertThat(fake.mutations()).isEqualTo(1);
        JsonNode cfq = fake.bodies.get(1).path("variables").path("input").path("changes").get(0).path("changeFromQuantity");
        assertThat(cfq.isInt()).as("never an increment without a baseline").isTrue();
        assertThat(cfq.asInt()).isEqualTo(7);
    }

    @Test
    void w4_damageMove_noActiveLevel_nothingSent() {
        Fake fake = new Fake(n -> n == 1 ? NO_LEVEL : "{\"data\":{\"inventoryMoveQuantities\":{\"userErrors\":[]}}}");
        assertThatThrownBy(() -> fake.gateway().moveAvailableToDamaged("s.myshopify.com", "t", "item", "loc", 1,
                "damaged", "traced://piece/P", "key-1"))
            .isInstanceOf(ShopifyMainLevelInactiveException.class);
        assertThat(fake.bodies).hasSize(1);
        assertThat(fake.mutations()).isZero();
    }

    @Test
    void w5_inactiveMainLevels_oneRead_returnsInactiveAndMissing() {
        Fake fake = new Fake(n -> "{\"data\":{\"nodes\":[" +
            "{\"id\":\"a\",\"inventoryLevel\":{\"id\":\"gid://shopify/InventoryLevel/1\"}}," +
            "{\"id\":\"b\",\"inventoryLevel\":null},null]}}");
        var inactive = fake.gateway().inactiveMainLevels("s.myshopify.com", "t", "loc",
            java.util.Arrays.asList("a", "b", "c", "a", "", null));
        assertThat(inactive).containsExactlyInAnyOrder("b", "c");
        assertThat(fake.bodies).hasSize(1);
        assertThat(fake.mutations()).isZero();
        JsonNode req = fake.bodies.get(0);
        assertThat(req.path("query").asText()).contains("inventoryLevel(locationId: $locationId)");
        assertThat(req.path("variables").path("locationId").asText()).isEqualTo("loc");
        List<String> ids = new ArrayList<>();
        req.path("variables").path("ids").forEach(n -> ids.add(n.asText()));
        assertThat(ids).containsExactly("a", "b", "c");
    }

    @Test
    void w6_inactiveMainLevels_failedRead_throws() {
        Fake fake = new Fake(n -> "{\"errors\":[{\"message\":\"Internal error\"}]}");
        assertThatThrownBy(() -> fake.gateway().inactiveMainLevels("s.myshopify.com", "t", "loc", List.of("a")))
            .isInstanceOf(ShopifyException.class).hasMessageContaining("nothing sent");
        assertThat(fake.mutations()).isZero();
    }
}
