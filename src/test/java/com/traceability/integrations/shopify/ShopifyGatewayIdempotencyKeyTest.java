package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for ShopifyGateway.idempotencyKey() — no Spring context, no network.
 *
 * The requirement this covers: the key must be STABLE per logical operation, derived from
 * the same (tenantId, triggerType, triggerId, variantId, locationId) tuple as the DB claim-row
 * unique constraint — so a retry of the same claimed operation reuses the same key (Shopify
 * dedupes server-side) and a genuinely different operation always gets a different one.
 */
class ShopifyGatewayIdempotencyKeyTest {

    private final UUID tenantId  = UUID.randomUUID();
    private final UUID variantId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();

    @Test
    void sameTuple_alwaysProducesTheSameKey() {
        String key1 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", variantId, locationId);
        String key2 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", variantId, locationId);
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void differentTriggerId_producesADifferentKey() {
        String key1 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", variantId, locationId);
        String key2 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-2", variantId, locationId);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void differentVariant_producesADifferentKey() {
        String key1 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", variantId, locationId);
        String key2 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", UUID.randomUUID(), locationId);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void differentLocation_producesADifferentKey() {
        String key1 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", variantId, locationId);
        String key2 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", variantId, UUID.randomUUID());
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void differentTenant_producesADifferentKey() {
        String key1 = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", variantId, locationId);
        String key2 = ShopifyGateway.idempotencyKey(UUID.randomUUID(), "receiving_session", "session-1", variantId, locationId);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void differentTriggerType_producesADifferentKey() {
        // Same trigger_id string reused across two different trigger types (e.g. a pieceId
        // used as both a damage_move and a hypothetical other per-piece trigger) must not collide.
        String key1 = ShopifyGateway.idempotencyKey(tenantId, "damage_move", "piece-1", variantId, locationId);
        String key2 = ShopifyGateway.idempotencyKey(tenantId, "return_inspection", "piece-1", variantId, locationId);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void keyIsFormattedAsAUuid() {
        String key = ShopifyGateway.idempotencyKey(tenantId, "receiving_session", "session-1", variantId, locationId);
        assertThat(UUID.fromString(key)).isNotNull();
    }
}
