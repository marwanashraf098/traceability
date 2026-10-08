package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix/order-payload-downgrade: every tier of the orders import asks for updatedAt — the V156
 * freshness rule compares it with the stored payload's.
 */
class OrdersQueryFieldsTest {

    @Test
    void everyTierAsksForUpdatedAt() {
        for (ShopifyHttpGateway.OrderPiiTier tier : ShopifyHttpGateway.OrderPiiTier.values()) {
            assertThat(ShopifyHttpGateway.ordersQuery(tier)).as("%s", tier).contains("id name createdAt updatedAt");
        }
    }
}
