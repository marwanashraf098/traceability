package com.traceability.integrations.bosta;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Review mode (S3) — auto-shipment on order ingest, for simulated-courier tenants (V130) only.
 *
 * A real merchant's orders get their forward shipment when Bosta's own Shopify plugin creates the
 * delivery and Traced ingests it (Mode B). A simulated tenant has no Bosta, so every ingested
 * order gets a 'created' forward shipment right away, with a reserved tracking number from
 * simulated_tracking_seq (V132, ^777\d{10}$) and the order's COD — which is what puts it in the
 * Pick &amp; Pack queue (PICKABLE_SHIPMENT_GATE) and lets it print a simulated waybill (S2).
 *
 * Runs on the caller's JdbcTemplate inside the caller's transaction (ShopifySyncService's order
 * upsert), so the shipment commits or rolls back with the order. One statement, no-op unless:
 *   - the tenant is simulated (real tenants: nothing, ever),
 *   - the order isn't cancelled (in Traced, or already in Shopify's REST payload — the GraphQL
 *     import fetches no cancel field, so a born-cancelled order is caught on the webhook path),
 *   - the order has no active forward leg — the NOT EXISTS, with ux_active_forward_shipment_per_order
 *     (V104) behind it for a race (ON CONFLICT DO NOTHING).
 * So edits, replays and reconciles never add a second shipment or change the number.
 */
public final class SimulatedShipments {

    private SimulatedShipments() {}

    /** @return true when a shipment was created. */
    public static boolean ensureForwardShipment(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        return jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, " +
            "                       shipment_leg, cod_amount) " +
            "SELECT o.tenant_id, o.id, 'bosta', nextval('simulated_tracking_seq')::text, 'created', " +
            "       'forward', o.cod_amount " +
            "FROM orders o " +
            "WHERE o.id = ? AND o.tenant_id = ? " +
            "  AND EXISTS (SELECT 1 FROM tenant_courier_simulation sim WHERE sim.tenant_id = o.tenant_id) " +
            "  AND o.status <> 'cancelled' AND o.raw ->> 'cancelled_at' IS NULL " +
            "  AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id " +
            "                    AND s.shipment_leg = 'forward' " +
            "                    AND s.internal_state NOT IN ('terminated', 'cancelled')) " +
            "ON CONFLICT DO NOTHING",
            orderId, tenantId) == 1;
    }
}
