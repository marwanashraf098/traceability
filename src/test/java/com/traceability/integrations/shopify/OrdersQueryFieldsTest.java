package com.traceability.integrations.shopify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix/order-payload-downgrade: every tier of the orders import asks for updatedAt (the V156
 * freshness rule compares it with the stored payload's), and the full tier asks for the customer's
 * id, so an imported order's customer_key is 'c:<id>', like a webhook order's — never 'p:<phone>'.
 */
class OrdersQueryFieldsTest {

    @Test
    void everyTierAsksForUpdatedAt_fullTierAsksForTheCustomerId() {
        for (ShopifyHttpGateway.OrderPiiTier tier : ShopifyHttpGateway.OrderPiiTier.values()) {
            assertThat(ShopifyHttpGateway.ordersQuery(tier)).as("%s", tier).contains("id name createdAt updatedAt");
        }
        assertThat(ShopifyHttpGateway.ordersQuery(ShopifyHttpGateway.OrderPiiTier.FULL)).contains("customer { id firstName lastName");
    }
}
