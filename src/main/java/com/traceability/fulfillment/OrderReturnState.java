package com.traceability.fulfillment;

/**
 * Returned pieces / units of an order (2026-10-30) — the ONE definition the order drawer (per piece / unit), the
 * Orders list rows (per order) and the list's Returns filter all read. SQL only, from piece_events and the
 * untracked-unit / request-item rows; nothing is written and OrderStatusDeriver is not involved.
 *
 * A PIECE of order O ({@link #pieceStateLateral}):
 *   rr     = the piece's latest return_received event for O;
 *   bound  = the piece's first event after rr naming ANOTHER order (it joined a new order — later events are
 *            that order's story, not O's);
 *   grade  = the first restocked / damaged event FROM return_pending_inspection after rr, before bound;
 *   state  = restocked | back_to_good (graded damaged, then a damaged → available edge — PieceAdjustService
 *            .restore(), the only writer of that edge — after the grade, before bound) | damaged
 *            | pending_inspection (no grade yet and the piece is at return_pending_inspection — also a live
 *            piece adopted from a Bosta state 46 with no scan event) | NULL (never came back).
 *   Not "the latest grade event": a piece restocked, later damaged on the shelf and repaired would read
 *   "back to good" for O, which it never was.
 * An UNTRACKED UNIT of order O ({@link #UNITS_SQL}): a live untracked_unit_intakes row (Scan returns, a parcel no
 * request holds) or an untracked return-request item marked Arrived — graded at arrival, sellable | damaged.
 *
 * Every lookup rides piece_events_piece_time (piece_id, occurred_at); the filters start from small sets
 * (pieces at return_pending_inspection, return_received events, live intakes / arrived items).
 */
public final class OrderReturnState {

    private OrderReturnState() {}

    private static final String TENANT = "NULLIF(current_setting('app.current_tenant', true), '')::uuid";

    /**
     * {@code LEFT JOIN LATERAL (...) <alias> ON true} exposing {@code <alias>.state} and {@code <alias>.rr_id}
     * (the return_received event id, NULL when the piece never came back for the order).
     */
    public static String pieceStateLateral(String alias, String pieceIdExpr, String pieceStatusExpr, String orderIdExpr) {
        return """
            LEFT JOIN LATERAL (
                SELECT rr.id AS rr_id,
                       CASE WHEN g.event_type = 'restocked'                    THEN 'restocked'
                            WHEN g.event_type = 'damaged' AND btg.ok IS TRUE    THEN 'back_to_good'
                            WHEN g.event_type = 'damaged'                       THEN 'damaged'
                            WHEN %2$s = 'return_pending_inspection'             THEN 'pending_inspection'
                       END AS state
                FROM (VALUES (1)) one
                LEFT JOIN LATERAL (
                    SELECT e.occurred_at, e.id FROM piece_events e
                    WHERE e.piece_id = %1$s AND e.order_id = %3$s AND e.event_type = 'return_received'
                    ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1
                ) rr ON true
                LEFT JOIN LATERAL (
                    SELECT nx.occurred_at, nx.id FROM piece_events nx
                    WHERE nx.piece_id = %1$s AND nx.order_id IS NOT NULL AND nx.order_id <> %3$s
                      AND (nx.occurred_at, nx.id) > (rr.occurred_at, rr.id)
                    ORDER BY nx.occurred_at, nx.id LIMIT 1
                ) nb ON rr.id IS NOT NULL
                LEFT JOIN LATERAL (
                    SELECT d.event_type, d.occurred_at, d.id FROM piece_events d
                    WHERE d.piece_id = %1$s AND d.event_type IN ('restocked', 'damaged')
                      AND d.from_status = 'return_pending_inspection'
                      AND (d.occurred_at, d.id) > (rr.occurred_at, rr.id)
                      AND (nb.id IS NULL OR (d.occurred_at, d.id) < (nb.occurred_at, nb.id))
                    ORDER BY d.occurred_at, d.id LIMIT 1
                ) g ON rr.id IS NOT NULL
                LEFT JOIN LATERAL (
                    SELECT true AS ok FROM piece_events b
                    WHERE b.piece_id = %1$s AND b.from_status = 'damaged' AND b.to_status = 'available'
                      AND (b.occurred_at, b.id) > (g.occurred_at, g.id)
                      AND (nb.id IS NULL OR (b.occurred_at, b.id) < (nb.occurred_at, nb.id))
                    LIMIT 1
                ) btg ON g.event_type = 'damaged'
            ) %4$s ON true
            """.formatted(pieceIdExpr, pieceStatusExpr, orderIdExpr, alias);
    }

    /**
     * Untracked units that came back, every order of the caller's tenant: (order_id, order_item_id, unit_no,
     * grade). Append a WHERE on {@code u.order_id} (the caller's binding).
     */
    public static final String UNITS_SQL = """
        SELECT u.order_id, u.order_item_id, u.unit_no, u.grade FROM (
            SELECT i.order_id, i.order_item_id, i.unit_no, i.condition AS grade
            FROM untracked_unit_intakes i
            WHERE i.tenant_id = %1$s AND i.undone_at IS NULL
            UNION ALL
            SELECT oi.order_id, ri.order_item_id, ri.unit_no, ri.arrived_condition AS grade
            FROM return_request_items ri
            JOIN order_items oi ON oi.id = ri.order_item_id
            WHERE ri.tenant_id = %1$s AND ri.order_item_id IS NOT NULL AND ri.arrived_condition IS NOT NULL
        ) u
        """.formatted(TENANT);

    /**
     * Per order of the bound id array (uuid[]): counts of pieces pending / restocked / damaged / back_to_good
     * and of untracked units sellable / damaged. Orders with none of them return no row.
     */
    public static final String ORDER_SUMMARY_SQL = """
        WITH p AS (
            SELECT DISTINCT ON (oi.order_id, pc.id) oi.order_id, rs.state
            FROM order_items oi
            JOIN allocations a ON a.order_item_id = oi.id
            JOIN pieces pc     ON pc.id = a.piece_id
            """ + pieceStateLateral("rs", "pc.id", "pc.status", "oi.order_id") + """
            WHERE oi.order_id = ANY(?::uuid[]) AND oi.tenant_id = """ + TENANT + """
              AND rs.state IS NOT NULL
              AND (rs.rr_id IS NOT NULL OR a.status IN ('active', 'packed'))
            ORDER BY oi.order_id, pc.id, (a.status IN ('active', 'packed')) DESC, a.allocated_at DESC
        ),
        u AS (
            """ + UNITS_SQL + """
            WHERE u.order_id = ANY(?::uuid[])
        )
        SELECT order_id,
               SUM(pending)::int AS pending, SUM(restocked)::int AS restocked, SUM(damaged)::int AS damaged,
               SUM(back_to_good)::int AS back_to_good, SUM(sellable)::int AS sellable
        FROM (
            SELECT order_id, (state = 'pending_inspection')::int AS pending, (state = 'restocked')::int AS restocked,
                   (state = 'damaged')::int AS damaged, (state = 'back_to_good')::int AS back_to_good, 0 AS sellable
            FROM p
            UNION ALL
            SELECT order_id, 0, 0, (grade = 'damaged')::int, 0, (grade = 'sellable')::int FROM u
        ) x
        GROUP BY order_id
        """;

    /** Orders (of the caller's tenant) with a piece still pending inspection after coming back. Uncorrelated. */
    static final String PENDING_ORDER_IDS_SQL = """
        SELECT oi.order_id
        FROM pieces pc
        JOIN allocations a  ON a.piece_id = pc.id
        JOIN order_items oi ON oi.id = a.order_item_id
        """ + pieceStateLateral("rs", "pc.id", "pc.status", "oi.order_id") + """
        WHERE pc.tenant_id = """ + TENANT + """
          AND pc.status = 'return_pending_inspection'
          AND rs.state = 'pending_inspection'
          AND (rs.rr_id IS NOT NULL OR a.status IN ('active', 'packed'))
        """;

    /** Orders (of the caller's tenant) with a returned unit that has a grade — a graded piece or an untracked unit. */
    static final String GRADED_ORDER_IDS_SQL = """
        SELECT e.order_id
        FROM piece_events e
        JOIN pieces pc ON pc.id = e.piece_id
        """ + pieceStateLateral("rs", "pc.id", "pc.status", "e.order_id") + """
        WHERE e.tenant_id = """ + TENANT + """
          AND e.event_type = 'return_received' AND e.order_id IS NOT NULL
          AND rs.state IN ('restocked', 'damaged', 'back_to_good')
        UNION
        SELECT u.order_id FROM (""" + UNITS_SQL + """
        ) u
        """;

    /** The Returns filter, alias {@code o}: pending = a returned piece awaits inspection; inspected = something came
     *  back with a grade and nothing awaits inspection. */
    static final String PENDING_FILTER_SQL   = " AND o.id IN (" + PENDING_ORDER_IDS_SQL + ")";
    static final String INSPECTED_FILTER_SQL = " AND o.id IN (" + GRADED_ORDER_IDS_SQL + ") AND o.id NOT IN (" + PENDING_ORDER_IDS_SQL + ")";
}
