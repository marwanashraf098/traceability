package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifySyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * D2 (approved by Marawan 2026-11-16, BRK-47903-EG) — an order whose parcel never left Traced, and whose waybill is
 * dead or was taken apart, goes back to Pick &amp; Pack ('new'). Not a bean: built on the caller's JdbcTemplate, so it
 * joins the caller's transaction and tenant context.
 *
 * APPROVED BACKWARD ORDER-STATUS WRITE — the 4th entry of OrderStatusBackwardWriteGuardTest (with
 * FulfillService.unpackOrder, PickErrorReversalService.reverse and LookupExchangeService.create). Only from
 * 'packed' / 'awaiting_pickup', and only while:
 *   - no piece of the order is held packed / awaiting_pickup / with_courier (its current order, or a live allocation);
 *   - the order never left custody (OrderLineFreeze.frozenSql — D1);
 *   - the caller checked the waybill side: the old forward leg is deleted in Bosta or ended before it moved
 *     ({@link #legDeadSql}) and, on a supersede, the replacing waybill is still at Bosta state &lt;= 10 — or (D3) the
 *     forward leg never moved.
 * PICKABLE_ORDERS_FILTER is unchanged: 'new' plus a 'created' newest forward leg is what puts the order back in the
 * queue. On reopen, a line Traced stored at 0 while the order looked frozen takes Shopify's current quantity
 * (ShopifySyncService.restoreAddedLineQuantities). One audit_log row 'order_reopened' (system actor unless given).
 */
public final class OrderReopen {

    private static final Logger log = LoggerFactory.getLogger(OrderReopen.class);

    private OrderReopen() {}

    /** Piece statuses that mean "this order's parcel is still made up / with the courier". */
    static final String HELD_STATUSES = "'packed', 'awaiting_pickup', 'with_courier'";

    /** {@code o} (alias of warehouse_orders / orders): no piece of the order is held packed / awaiting_pickup / with_courier. */
    public static String noPieceHeldSql(String o) {
        return "(NOT EXISTS (SELECT 1 FROM pieces p_ro WHERE p_ro.current_order_id = " + o + ".id " +
               "   AND p_ro.tenant_id = " + o + ".tenant_id AND p_ro.status::text IN (" + HELD_STATUSES + ")) " +
               " AND NOT EXISTS (SELECT 1 FROM order_items oi_ro JOIN allocations a_ro ON a_ro.order_item_id = oi_ro.id " +
               "   JOIN pieces p_ra ON p_ra.id = a_ro.piece_id WHERE oi_ro.order_id = " + o + ".id " +
               "   AND a_ro.status IN ('active', 'packed') AND p_ra.status::text IN (" + HELD_STATUSES + ")))";
    }

    /** {@code s} (alias of a Bosta forward shipment): its waybill is dead for packing — deleted in Bosta, or ended
     *  / still waiting without ever having moved (OrderLineFreeze.legMovedSql false). */
    public static String legDeadSql(String s) {
        return "(" + s + ".provider_deleted_at IS NOT NULL OR NOT " + OrderLineFreeze.legMovedSql(s) + ")";
    }

    /**
     * Moves the order back to 'new' when the rule holds; false (nothing written) otherwise. {@code source} names the
     * caller for the audit row ('tracking_supersede' | 'return_scan_unpack').
     */
    public static boolean reopen(JdbcTemplate jdbc, UUID tenantId, UUID orderId, UUID actorUserId, String source,
                                 String trackingNumber) {
        int updated = jdbc.update(
            "UPDATE warehouse_orders o SET status = 'new'::order_status, locked_by = NULL, locked_at = NULL, " +
            "       shopify_edit_conflict_at = NULL, shopify_edit_conflict_diff = NULL " +
            "WHERE o.id = ? AND o.tenant_id = ? AND o.status::text IN ('packed', 'awaiting_pickup') " +
            "  AND " + noPieceHeldSql("o") + " AND NOT " + OrderLineFreeze.frozenSql("o.id", "o.tenant_id"),
            orderId, tenantId);
        if (updated != 1) return false;
        int restored = ShopifySyncService.restoreAddedLineQuantities(jdbc, tenantId, orderId);
        jdbc.update(
            "INSERT INTO audit_log (tenant_id, actor_user_id, action, target_type, target_id, metadata) " +
            "VALUES (?, ?, 'order_reopened', 'order', ?, jsonb_build_object('source', ?::text, 'trackingNumber', ?::text, " +
            "        'linesRestored', ?::int))",
            tenantId, actorUserId, orderId.toString(), source, trackingNumber, restored);
        log.info("Order {} reopened for picking ({}), {} line(s) restored to Shopify's current quantity",
            orderId, source, restored);
        return true;
    }
}
