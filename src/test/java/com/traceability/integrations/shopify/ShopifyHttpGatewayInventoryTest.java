package com.traceability.integrations.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;

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

    private static String mutationText(String fieldName) throws Exception {
        Field f = ShopifyHttpGateway.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (String) f.get(null);
    }
}
