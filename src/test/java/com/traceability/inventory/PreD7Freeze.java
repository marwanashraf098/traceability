package com.traceability.inventory;

/**
 * OrderLineFreeze.frozenSql("o.id", "o.tenant_id") as it was BEFORE D7 (2026-11-02) — clause 1 read any forward
 * history row other than 'created'. The two repair scripts that inline the freeze
 * (scripts/ops/2026-10-21-order-edit-quantity-repair.sql, 2026-11-01-generic-cancel-history-repair.sql) ran on prod
 * with this rule and are kept unchanged as the record of what ran; their copy tests compare against this text.
 * Never use it in production code — the live rule is OrderLineFreeze.frozenSql.
 */
public final class PreD7Freeze {

    private PreD7Freeze() {}

    public static final String FROZEN_SQL_O =
        "(EXISTS (" +
        "    SELECT 1 FROM shipment_status_history h " +
        "    JOIN shipments s ON s.id = h.shipment_id " +
        "    WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id " +
        "      AND s.shipment_leg = 'forward' " +
        "      AND h.internal_state <> 'created'" +
        ")" +
        " OR EXISTS (" +
        "    SELECT 1 FROM order_items oi_fz " +
        "    JOIN allocations a_fz ON a_fz.order_item_id = oi_fz.id " +
        "    JOIN pieces p_fz ON p_fz.id = a_fz.piece_id " +
        "    WHERE oi_fz.order_id = o.id AND oi_fz.tenant_id = o.tenant_id " +
        "      AND a_fz.status = 'packed' AND p_fz.status::text IN ('with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection')" +
        ")" +
        " OR EXISTS (" +
        "    SELECT 1 FROM order_items oi_fh " +
        "    JOIN allocations a_fh ON a_fh.order_item_id = oi_fh.id " +
        "    JOIN piece_events pe_fh ON pe_fh.piece_id = a_fh.piece_id AND pe_fh.order_id = oi_fh.order_id " +
        "    WHERE oi_fh.order_id = o.id AND oi_fh.tenant_id = o.tenant_id " +
        "      AND pe_fh.to_status::text IN ('with_courier', 'delivered', 'return_in_transit', 'return_pending_inspection')" +
        "))";
}
