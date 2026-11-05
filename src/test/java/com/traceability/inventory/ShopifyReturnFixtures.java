package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S1b test helper: a Shopify-native return of an order as the V206 mirror stores it — one return line of the variant
 * and its RESTOCKED disposition ({@code qty} units at {@code locationGid}; null = no location). Written on the test's
 * postgres connection (the mirror's only writer in production is ShopifyReturnsStore).
 */
final class ShopifyReturnFixtures {

    private static final AtomicLong IDS = new AtomicLong(91_000_000_000L);

    private ShopifyReturnFixtures() {}

    /** @return the shopify_returns id */
    static UUID restockedReturn(JdbcTemplate jdbc, UUID tenant, UUID store, UUID order, String variantGid,
                                int qty, String locationGid) {
        return returnWithDisposition(jdbc, tenant, store, order, variantGid, qty, locationGid, "RESTOCKED");
    }

    static UUID returnWithDisposition(JdbcTemplate jdbc, UUID tenant, UUID store, UUID order, String variantGid,
                                      int qty, String locationGid, String type) {
        long k = IDS.incrementAndGet();
        String orderGid = jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, order);
        UUID ret = jdbc.queryForObject(
            "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'CLOSED', '{}'::jsonb) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Return/" + k, order, orderGid, "#R" + k + "-R1");
        String fli = "gid://shopify/FulfillmentLineItem/" + k;
        jdbc.update(
            "INSERT INTO shopify_return_lines (tenant_id, return_id, line_gid, fulfillment_line_gid, variant_gid, quantity, " +
            "processed_quantity, raw) VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)",
            tenant, ret, "gid://shopify/ReturnLineItem/" + k, fli, variantGid, qty, qty);
        jdbc.update(
            "INSERT INTO shopify_rfo_dispositions (tenant_id, return_id, disposition_gid, rfo_gid, rfo_line_gid, " +
            "fulfillment_line_gid, type, quantity, location_gid, raw) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)",
            tenant, ret, "gid://shopify/ReverseFulfillmentOrderDisposition/" + k,
            "gid://shopify/ReverseFulfillmentOrder/" + k, "gid://shopify/ReverseFulfillmentOrderLineItem/" + k,
            fli, type, qty, locationGid);
        return ret;
    }
}
