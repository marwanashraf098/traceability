package com.traceability.fulfillment;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * The order's shipping carrier (V139): recomputes orders.shipping_carrier_class / _name from
 * shipping_carrier_of(order) — the one derivation (fulfillments tracked and in raw, live Bosta forward
 * shipment; Bosta wins; cancelled fulfillments ignored). Runs on the caller's connection, tenant context
 * and transaction.
 */
public final class OrderCarrier {

    public static final String BOSTA = "bosta";
    public static final String OTHER_KNOWN = "other_known";
    public static final String UNKNOWN = "unknown";

    private OrderCarrier() {}

    public static void recompute(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        // shipping_carrier_of returns no row when nothing decides → LEFT JOIN gives (NULL, NULL).
        jdbc.update(
            "UPDATE orders o SET shipping_carrier_class = n.cls, shipping_carrier_name = n.name " +
            "FROM (SELECT c.cls, c.name FROM (SELECT 1) one LEFT JOIN shipping_carrier_of(?) c ON true) n " +
            "WHERE o.id = ? AND o.tenant_id = ? " +
            "  AND (o.shipping_carrier_class IS DISTINCT FROM n.cls OR o.shipping_carrier_name IS DISTINCT FROM n.name)",
            orderId, orderId, tenantId);
    }
}
