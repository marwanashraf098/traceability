package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
 *      the ALREADY_SHIPPED guard FulfillService.scan()/complete() use; a leg cancelled / terminated before it
 *      ever moved doesn't count ({@link #movementRowSql}, D7);
 *   2. a piece on a live packed allocation of the order is in a {@link PieceStatus#LEFT_CUSTODY} status
 *      (self-pickup has no forward leg; a courier path whose history lags);
 *   3. a piece allocated to the order has REALLY left with it: a piece_events row FOR THIS ORDER into
 *      'with_courier' or 'delivered' on a FORWARD leg of this order ({@link #pieceLeftWithOrderSql}; a NULL
 *      shipment is the self-pickup handover — self-pickup has no leg) — history, like clause 1, so the freeze
 *      stays once a returned piece is restocked (its allocation is released and the piece is 'available'
 *      again). D1 (approved 2026-11-16, BRK-47903-EG): a return-intake event alone (into return_in_transit /
 *      return_pending_inspection from packed / awaiting_pickup — a parcel taken apart before the courier had
 *      it) never freezes; a leg that really moved and then came back still does (its with_courier /
 *      delivered event).
 */
public final class OrderLineFreeze {

    private OrderLineFreeze() {}

    /** SQL list of {@link PieceStatus#LEFT_CUSTODY}, e.g. {@code 'with_courier', 'delivered', …}. */
    static String leftCustodySqlList() {
        return PieceStatus.LEFT_CUSTODY.stream().map(s -> "'" + s.db + "'").collect(Collectors.joining(", "));
    }

    /**
     * D7 (2026-11-02) — THE one definition of "this forward-leg history row is movement past 'created'", for
     * every reader that asks whether the order left (clause 1 here, so FulfillService's ALREADY_SHIPPED guard,
     * and WaybillResolver's moved state). {@code h} = the shipment_status_history alias.
     *
     * A row counts unless it ENDS a leg that never moved: 'cancelled' / 'terminated' (Bosta 49 Canceled and
     * 104 Archived map to 'cancelled', 48 Terminated to 'terminated') on a leg with no other row past 'created'
     * that isn't one of those. Such a delivery was ended before the courier had it, so the order can be packed
     * against its next waybill. A leg that moved and then ended still counts — the end row included, so
     * WaybillResolver still names it ('terminated'). Bosta's history is never changed; only this reading is.
     */
    public static String movementRowSql(String h) {
        return "(" + h + ".internal_state NOT IN ('created', 'cancelled', 'terminated') " +
               " OR (" + h + ".internal_state IN ('cancelled', 'terminated') AND EXISTS (" +
               "     SELECT 1 FROM shipment_status_history h_mv WHERE h_mv.shipment_id = " + h + ".shipment_id " +
               "       AND h_mv.internal_state NOT IN ('created', 'cancelled', 'terminated'))))";
    }

    /**
     * The order's forward leg has ever moved past 'created' (shipment_status_history — append-only,
     * never the live internal_state column a Bosta "back at hub" webhook can rewind; a movement row per
     * {@link #movementRowSql}). {@code orderIdExpr} and {@code tenantIdExpr} are SQL expressions (a column
     * or a '?' bind, order then tenant).
     */
    public static String shippedPastCreatedSql(String orderIdExpr, String tenantIdExpr) {
        return "EXISTS (" +
               "    SELECT 1 FROM shipment_status_history h " +
               "    JOIN shipments s ON s.id = h.shipment_id " +
               "    WHERE s.order_id = " + orderIdExpr + " AND s.tenant_id = " + tenantIdExpr + " " +
               "      AND s.shipment_leg = 'forward' " +
               "      AND " + movementRowSql("h") +
               ")";
    }

    /**
     * This forward shipment {@code s} (alias) has moved: its live state is past 'created' (and not an end state), or
     * its history has a movement row ({@link #movementRowSql}). A leg ended before it moved does NOT count.
     */
    public static String legMovedSql(String s) {
        return "(" + s + ".internal_state NOT IN ('created', 'cancelled', 'terminated') " +
               " OR EXISTS (SELECT 1 FROM shipment_status_history h_lm WHERE h_lm.shipment_id = " + s + ".id " +
               "            AND " + movementRowSql("h_lm") + "))";
    }

    /** {@code s} (alias) is its order's NEWEST forward leg (created_at DESC, id DESC — UUIDv4 is not time-ordered). */
    public static String newestForwardLegSql(String s) {
        return "(" + s + ".shipment_leg = 'forward' AND " + s + ".id = (SELECT x_nf.id FROM shipments x_nf " +
               "  WHERE x_nf.order_id = " + s + ".order_id AND x_nf.tenant_id = " + s + ".tenant_id " +
               "    AND x_nf.shipment_leg = 'forward' ORDER BY x_nf.created_at DESC, x_nf.id DESC LIMIT 1))";
    }

    /**
     * Schedule-pickup fix (2026-11-04) — THE one definition of "this waybill is still waiting for the courier":
     * {@code s} is the order's newest forward leg, its state is 'created', it never moved, and it wasn't deleted in
     * Bosta. A cancelled / terminated / delivered / moved / older waybill never is. Read by
     * BostaPickupService (what a scheduled pickup includes) and WaybillResolver (the "already shipped" wording).
     */
    public static String prePickupWaybillSql(String s) {
        return "(" + newestForwardLegSql(s) + " AND " + s + ".internal_state = 'created' " +
               " AND " + s + ".provider_deleted_at IS NULL AND NOT " + legMovedSql(s) + ")";
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
               "      AND " + pieceLeftWithOrderSql("pe_fh", "oi_fh.order_id") +
               "))";
    }

    /**
     * D1 (2026-11-16) — THE one definition of "this piece_events row ({@code pe}) is the piece really leaving with
     * the order {@code orderIdExpr}": into 'with_courier' / 'delivered' on a FORWARD leg of that order (a NULL
     * shipment = the self-pickup handover, which has no leg). A return-intake event (into return_in_transit /
     * return_pending_inspection) is never a departure on its own — a piece that really left already has its
     * with_courier / delivered row.
     */
    public static String pieceLeftWithOrderSql(String pe, String orderIdExpr) {
        return "(" + pe + ".to_status::text IN ('with_courier', 'delivered') AND (" + pe + ".shipment_id IS NULL " +
               "  OR EXISTS (SELECT 1 FROM shipments s_lw WHERE s_lw.id = " + pe + ".shipment_id " +
               "             AND s_lw.order_id = " + orderIdExpr + " AND s_lw.shipment_leg = 'forward')))";
    }

    /*
     * Incident 2026-11-25 — the single-order readers below. {@link #frozenSql} stays THE rule for set-based readers
     * (a list query evaluates it per row); a caller that asks about ONE order uses these instead. They return exactly
     * the same boolean (OrderFrozenSingleOrderTest compares them with frozenSql on every branch) but are written so
     * that no plan can start from the tenant:
     *
     *  - no correlated sublink — every set is a MATERIALIZED CTE keyed on the order id (shipments by order_id,
     *    order_items by order_id → allocations), so Postgres can't turn an EXISTS into a "hashed SubPlan" over the
     *    whole tenant (prod's plan: an uncorrelated scan of the tenant's shipments / pieces / piece_events, then a
     *    hash probe — 20-120 s, ~450k-670k buffers per call);
     *  - clause 1 reads "a forward leg has a history row not in created / cancelled / terminated": the same as
     *    {@link #movementRowSql} (a cancelled / terminated row counts only when the leg has such a row anyway);
     *  - executed UNPREPARED (pgjdbc prepareThreshold 0 — {@link #unprepared}), so every call is planned with its own
     *    values and the CURRENT tenant. Postgres estimates the RLS predicate tenant_id = current_setting(…) with the
     *    tenant set when the plan is made; a generic plan cached on a connection while a small tenant was current
     *    believes "the tenant has 1 row" and is then reused for the biggest tenant. That poisoning is the root cause;
     *    a custom plan can't carry it.
     */

    /** The order's forward legs (no tenant filter on the id set — {@link #pieceLeftWithOrderSql}'s s_lw has none). */
    private static final String LEGS_CTE =
        "legs AS MATERIALIZED (SELECT s.id, s.tenant_id FROM shipments s " +
        "  WHERE s.order_id = ? AND s.shipment_leg = 'forward')";

    private static final String MOVED_SQL =
        "EXISTS (SELECT 1 FROM legs l JOIN shipment_status_history h ON h.shipment_id = l.id " +
        "  WHERE l.tenant_id = ? AND h.internal_state NOT IN ('created', 'cancelled', 'terminated'))";

    /** Binds: order, tenant. Clause 1 alone ({@link #shippedPastCreatedSql} for one order). */
    static final String SHIPPED_PAST_CREATED_ONE_SQL =
        "WITH " + LEGS_CTE + " SELECT " + MOVED_SQL;

    /** The order's allocations (order_items by order_id — order_items has no tenant index to start from). */
    private static final String ALLOC_CTE =
        "alloc AS MATERIALIZED (SELECT a.piece_id, a.status FROM order_items oi " +
        "  JOIN allocations a ON a.order_item_id = oi.id WHERE oi.order_id = ? AND oi.tenant_id = ?)";

    private static String packedLeftSql() {
        return "EXISTS (SELECT 1 FROM alloc c JOIN pieces p ON p.id = c.piece_id " +
               "  WHERE c.status = 'packed' AND p.status::text IN (" + leftCustodySqlList() + "))";
    }

    /** Binds: order, order, tenant, tenant. Clauses 1 + 2 (FulfillService.unpack's "already left with the courier"). */
    static final String LEFT_CUSTODY_ONE_SQL =
        "WITH " + LEGS_CTE + ", " + ALLOC_CTE + " SELECT " + MOVED_SQL + " OR " + packedLeftSql();

    /** Clauses 1-3 over the two CTEs. Binds: tenant, order. */
    private static final String FROZEN_CLAUSES_ONE_SQL =
        "(" + MOVED_SQL + " OR " + packedLeftSql() +
        " OR EXISTS (SELECT 1 FROM alloc c JOIN piece_events pe ON pe.piece_id = c.piece_id " +
        "      WHERE pe.order_id = ? AND pe.to_status::text IN ('with_courier', 'delivered') " +
        "        AND (pe.shipment_id IS NULL OR pe.shipment_id IN (SELECT l.id FROM legs l))))";

    /**
     * True when the order's line quantities are frozen ({@link #frozenSql}, the whole rule for one order). Runs on the
     * caller's connection / tenant context. Shopify ingest only — a portal pre-connect order is not in
     * warehouse_orders and reads as not frozen.
     */
    public static boolean isFrozen(JdbcTemplate jdbc, UUID orderId, UUID tenantId) {
        // Binds: order (legs), order + tenant (alloc), order + tenant (warehouse_orders), tenant + order (clauses).
        return one(jdbc,
            "WITH " + LEGS_CTE + ", " + ALLOC_CTE + " " +
            "SELECT EXISTS (SELECT 1 FROM warehouse_orders o WHERE o.id = ? AND o.tenant_id = ?) AND " +
            FROZEN_CLAUSES_ONE_SQL,
            orderId, orderId, tenantId, orderId, tenantId, tenantId, orderId);
    }

    /** {@link #shippedPastCreatedSql} for one order — FulfillService's ALREADY_SHIPPED guard (every scan). */
    public static boolean hasShippedPastCreated(JdbcTemplate jdbc, UUID orderId, UUID tenantId) {
        return one(jdbc, SHIPPED_PAST_CREATED_ONE_SQL, orderId, tenantId);
    }

    /** Clause 1 OR clause 2 for one order: the forward leg moved, or a packed piece is out of custody. */
    public static boolean hasLeftCustody(JdbcTemplate jdbc, UUID orderId, UUID tenantId) {
        return one(jdbc, LEFT_CUSTODY_ONE_SQL, orderId, orderId, tenantId, tenantId);
    }

    private static boolean one(JdbcTemplate jdbc, String sql, Object... binds) {
        Boolean b = jdbc.query(con -> {
            PreparedStatement ps = unprepared(con, sql);
            for (int i = 0; i < binds.length; i++) ps.setObject(i + 1, binds[i]);
            return ps;
        }, rs -> rs.next() && rs.getBoolean(1));
        return Boolean.TRUE.equals(b);
    }

    /**
     * A statement pgjdbc never server-prepares (prepareThreshold 0): sent as the unnamed statement every time, so
     * Postgres plans it with the bound values and the current tenant — never a cached generic plan.
     */
    static PreparedStatement unprepared(Connection con, String sql) throws SQLException {
        PreparedStatement ps = con.prepareStatement(sql);
        if (PG_STATEMENT != null && ps.isWrapperFor(PG_STATEMENT)) {
            try {
                SET_PREPARE_THRESHOLD.invoke(ps.unwrap(PG_STATEMENT), 0);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("pgjdbc setPrepareThreshold failed", e);
            }
        }
        return ps;
    }

    // pgjdbc is a runtime dependency (pom scope runtime), so its statement interface is reached reflectively.
    private static final Class<?> PG_STATEMENT;
    private static final java.lang.reflect.Method SET_PREPARE_THRESHOLD;
    static {
        Class<?> c = null;
        java.lang.reflect.Method m = null;
        try {
            c = Class.forName("org.postgresql.PGStatement");
            m = c.getMethod("setPrepareThreshold", int.class);
        } catch (ReflectiveOperationException e) {
            c = null;
        }
        PG_STATEMENT = c;
        SET_PREPARE_THRESHOLD = m;
    }
}
