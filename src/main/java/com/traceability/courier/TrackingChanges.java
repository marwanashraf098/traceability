package com.traceability.courier;

import com.traceability.inventory.OrderLineFreeze;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shopify tracking-number edits (V200) — what both writers share: the generic in-place switch (GenericShipments,
 * S2) and, later, the Bosta switch (S3). Not a bean: built on the caller's JdbcTemplate, so it runs on the caller's
 * connection, tenant context and transaction.
 *
 * The decision order is fixed: lock the order row → frozen (OrderLineFreeze.frozenSql, the ONE custody rule —
 * refused, HIGH) → in an open pickup session (refused, HIGH) → the writer's own checks → apply, with the relabel
 * reasons (packed / printed / claimed — MEDIUM). Every decision is one shipment_tracking_changes row, written once
 * (a repeat of the same decision adds nothing).
 */
public final class TrackingChanges {

    public static final String KIND_EDIT = "edit";
    public static final String KIND_CANCEL_REFULFILL = "cancel_refulfill";
    public static final String KIND_COSMETIC = "cosmetic";
    public static final String KIND_BLANKED = "blanked";

    public static final String REASON_FROZEN = "frozen";
    public static final String REASON_PICKUP_SESSION = "pickup_session";
    public static final String REASON_AMBIGUOUS = "ambiguous";
    public static final String REASON_KEY_TAKEN = "key_taken";

    private TrackingChanges() {}

    /** Takes the order's row lock (the decision is re-read under it), then: is the order frozen? */
    public static boolean lockAndCheckFrozen(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        jdbc.queryForList("SELECT id FROM warehouse_orders WHERE id = ? AND tenant_id = ? FOR UPDATE", orderId, tenantId);
        return OrderLineFreeze.isFrozen(jdbc, orderId, tenantId);
    }

    /** The shipment is in a pickup session that is still open (the handover is under way). */
    public static boolean inOpenPickupSession(JdbcTemplate jdbc, UUID tenantId, UUID shipmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pickup_shipments ps JOIN pickups pu ON pu.id = ps.pickup_id " +
            "  AND pu.tenant_id = ps.tenant_id WHERE ps.shipment_id = ? AND ps.tenant_id = ? AND pu.session_status = 'open')",
            Boolean.class, shipmentId, tenantId));
    }

    /**
     * Why the parcel needs a new label once its number changed: 'packed' (a packed allocation, or the order
     * packed / awaiting pickup), 'printed' (Traced printed the old waybill — a print batch or print job),
     * 'claimed' (a packer holds the order). Empty = nothing physical carries the old number.
     */
    public static List<String> relabelReasons(JdbcTemplate jdbc, UUID tenantId, UUID orderId, UUID shipmentId,
                                              String oldTrackingNumber) {
        List<String> out = new ArrayList<>();
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
                "  WHERE oi.order_id = ? AND oi.tenant_id = ? AND a.status = 'packed') " +
                "OR EXISTS (SELECT 1 FROM warehouse_orders o WHERE o.id = ? AND o.tenant_id = ? " +
                "  AND o.status IN ('packed', 'awaiting_pickup'))",
                Boolean.class, orderId, tenantId, orderId, tenantId))) out.add("packed");
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pack_print_batch_items bi WHERE bi.shipment_id = ? AND bi.tenant_id = ?) " +
                "OR EXISTS (SELECT 1 FROM pack_print_job_parts jp WHERE jp.tenant_id = ? AND ? = ANY (jp.tracking_numbers))",
                Boolean.class, shipmentId, tenantId, tenantId, oldTrackingNumber))) out.add("printed");
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM warehouse_orders o WHERE o.id = ? AND o.tenant_id = ? AND o.locked_by IS NOT NULL)",
                Boolean.class, orderId, tenantId))) out.add("claimed");
        return out;
    }

    /** One decision row (append-only; a repeat of the same decision is a no-op). */
    public static void record(JdbcTemplate jdbc, UUID tenantId, UUID orderId, UUID shipmentId, UUID newShipmentId,
                              String kind, String oldTn, String newTn, String fulfillmentId, String newFulfillmentId,
                              String fulfillmentUpdatedAt, String outcome, String reason, List<String> relabel) {
        jdbc.update(
            "INSERT INTO shipment_tracking_changes (tenant_id, order_id, shipment_id, new_shipment_id, kind, " +
            "    old_tracking_number, new_tracking_number, shopify_fulfillment_id, new_shopify_fulfillment_id, " +
            "    fulfillment_updated_at, outcome, reason, relabel) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?, ?, ?) ON CONFLICT DO NOTHING",
            tenantId, orderId, shipmentId, newShipmentId, kind, oldTn, newTn, fulfillmentId, newFulfillmentId,
            fulfillmentUpdatedAt, outcome, reason, relabel == null ? new String[0] : relabel.toArray(new String[0]));
    }
}
