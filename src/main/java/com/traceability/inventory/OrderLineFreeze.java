package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shopify order edits (2026-10-21): line quantities follow Shopify's CURRENT quantity only until
 * the order has left Traced custody. After that the parcel's contents are fixed — a lower Shopify
 * quantity is a refund / post-ship cancel, never a pick change — so the stored quantity is kept
 * (no diff, no edit-conflict alert, no release) and keeps meaning "what was shipped"
 * (UntrackedParcelUnits.PARCEL_UNIT_CAP_SQL).
 *
 * THE single definition, used by ShopifyWebhookProcessorJob.handleOrderUpdated (the diff),
 * ShopifySyncService (both line upserts) and the repair script (inlined verbatim — see
 * OrderLineFreezeTest). Never re-derive it.
 *
 * A line that first arrives on a frozen order is stored with quantity 0 (it was never in the parcel);
 * nothing is raised.
 *
 * Frozen when ANY of:
 *   1. the forward leg has a shipment_status_history row past 'created' — {@link #shippedPastCreatedSql},
 *      the ALREADY_SHIPPED guard FulfillService.scan()/complete() use;
 *   2. a piece on a live packed allocation of the order is in a {@link PieceStatus#LEFT_CUSTODY} status
 *      (self-pickup has no forward leg; a courier path whose history lags);
 *   3. a piece allocated to the order has a piece_events row FOR THIS ORDER into a LEFT_CUSTODY status —
 *      history, like clause 1, so the freeze stays once a returned piece is restocked (its allocation is
 *      released and the piece is 'available' again).
 */
public final class OrderLineFreeze {

    private OrderLineFreeze() {}

    /** SQL list of {@link PieceStatus#LEFT_CUSTODY}, e.g. {@code 'with_courier', 'delivered', …}. */
    static String leftCustodySqlList() {
        return PieceStatus.LEFT_CUSTODY.stream().map(s -> "'" + s.db + "'").collect(Collectors.joining(", "));
    }

    /**
     * The order's forward leg has ever moved past 'created' (shipment_status_history — append-only,
     * never the live internal_state column a Bosta "back at hub" webhook can rewind). {@code orderIdExpr}
     * and {@code tenantIdExpr} are SQL expressions (a column or a '?' bind, order then tenant).
     */
    public static String shippedPastCreatedSql(String orderIdExpr, String tenantIdExpr) {
        return "EXISTS (" +
               "    SELECT 1 FROM shipment_status_history h " +
               "    JOIN shipments s ON s.id = h.shipment_id " +
               "    WHERE s.order_id = " + orderIdExpr + " AND s.tenant_id = " + tenantIdExpr + " " +
               "      AND s.shipment_leg = 'forward' " +
               "      AND h.internal_state <> 'created'" +
               ")";
    }

    /** The full freeze predicate (a boolean SQL expression); expressions as {@link #shippedPastCreatedSql}. */
    public static String frozenSql(String orderIdExpr, String tenantIdExpr) {
        String left = leftCustodySqlList();
        return "(" + shippedPastCreatedSql(orderIdExpr, tenantIdExpr) +
               " OR EXISTS (" +
               "    SELECT 1 FROM order_items oi_fz " +
               "    JOIN allocations a_fz ON a_fz.order_item_id = oi_fz.id " +
               "    JOIN pieces p_fz ON p_fz.id = a_fz.piece_id " +
               "    WHERE oi_fz.order_id = " + orderIdExpr + " AND oi_fz.tenant_id = " + tenantIdExpr + " " +
               "      AND a_fz.status = 'packed' AND p_fz.status::text IN (" + left + ")" +
               ")" +
               " OR EXISTS (" +
               "    SELECT 1 FROM order_items oi_fh " +
               "    JOIN allocations a_fh ON a_fh.order_item_id = oi_fh.id " +
               "    JOIN piece_events pe_fh ON pe_fh.piece_id = a_fh.piece_id AND pe_fh.order_id = oi_fh.order_id " +
               "    WHERE oi_fh.order_id = " + orderIdExpr + " AND oi_fh.tenant_id = " + tenantIdExpr + " " +
               "      AND pe_fh.to_status::text IN (" + left + ")" +
               "))";
    }

    /**
     * True when the order's line quantities are frozen. Runs on the caller's connection / tenant context.
     * Shopify ingest only — a portal pre-connect order is not in merchant_orders and reads as not frozen.
     */
    public static boolean isFrozen(JdbcTemplate jdbc, UUID orderId, UUID tenantId) {
        List<Boolean> frozen = jdbc.queryForList(
            "SELECT " + frozenSql("o.id", "o.tenant_id") + " FROM merchant_orders o WHERE o.id = ? AND o.tenant_id = ?",
            Boolean.class, orderId, tenantId);
        return !frozen.isEmpty() && Boolean.TRUE.equals(frozen.get(0));
    }
}
