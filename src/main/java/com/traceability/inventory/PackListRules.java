package com.traceability.inventory;

/**
 * Pick &amp; Pack S4 — the single source of the waybill-mode list / exception predicates. The two
 * manager exceptions (ExceptionService.detectPackSetAside / detectPackCancelledAfterPrint), the
 * "Printed but not packed" list, the per-batch progress counts and the session summary all read
 * these — change a rule here, never inline in one caller (same pattern as ReturnCaseRules).
 *
 * All fragments expect an orders alias {@code o}; some also a shipments alias {@code s}.
 */
public final class PackListRules {

    private PackListRules() {}

    /** Order not packed yet (the queue's pickable statuses) — `o`. */
    public static final String NOT_YET_PACKED_SQL =
        "o.status IN ('new', 'confirmed', 'ready_to_pick', 'picking')";

    /** Order cancelled — Traced or Shopify side (status, or a cancel requested after pack) — `o`. */
    public static final String CANCELLED_SQL =
        "(o.status = 'cancelled' OR o.cancel_requested_at IS NOT NULL)";

    /**
     * The order's latest pack outcome that decides it (packed / set aside — a later rejection of
     * its waybill doesn't change it): LATERAL on `o`, alias `ps`, columns id, outcome, reason,
     * session_id, created_at.
     */
    public static final String LATEST_PACK_OUTCOME_LATERAL =
        "LEFT JOIN LATERAL ( " +
        "    SELECT so.id, so.outcome, so.reason, so.session_id, so.created_at " +
        "    FROM pack_session_orders so " +
        "    WHERE so.order_id = o.id AND so.tenant_id = o.tenant_id " +
        "      AND so.outcome IN ('packed', 'set_aside') " +
        // UUIDv4 is not time-ordered — order by created_at, never id (see CLAUDE.md invariant)
        "    ORDER BY so.created_at DESC, so.id DESC " +
        "    LIMIT 1 " +
        ") ps ON true ";

    /** pack_set_aside subject key — one per set-aside event, so a new set-aside after a resolve re-opens. */
    public static final String SET_ASIDE_KEY_SQL = "'pack_set_aside:' || ps.id";

    /**
     * Open pack_set_aside: the latest deciding outcome is a set-aside, the order is still not packed
     * and not cancelled, and no manager resolved this set-aside. Needs `o` + {@link #LATEST_PACK_OUTCOME_LATERAL}.
     */
    public static final String SET_ASIDE_OPEN_SQL =
        "(ps.outcome = 'set_aside' AND " + NOT_YET_PACKED_SQL + " AND NOT " + CANCELLED_SQL +
        " AND NOT EXISTS (SELECT 1 FROM exception_resolutions er WHERE er.tenant_id = o.tenant_id " +
        "     AND er.exception_type = 'pack_set_aside' AND er.subject_key = " + SET_ASIDE_KEY_SQL + "))";

    /** pack_cancelled_after_print subject key — per printed shipment. */
    public static final String CANCELLED_AFTER_PRINT_KEY_SQL = "'pack_cancelled_after_print:' || s.id";

    /**
     * Open pack_cancelled_after_print: the shipment `s` is in a print batch, its order `o` is
     * cancelled, and nobody marked the waybill discarded (resolution). Needs `o`, `s`.
     */
    public static final String CANCELLED_AFTER_PRINT_OPEN_SQL =
        "(" + CANCELLED_SQL +
        " AND EXISTS (SELECT 1 FROM pack_print_batch_items bi2 WHERE bi2.shipment_id = s.id AND bi2.tenant_id = s.tenant_id)" +
        " AND NOT EXISTS (SELECT 1 FROM exception_resolutions er WHERE er.tenant_id = o.tenant_id " +
        "     AND er.exception_type = 'pack_cancelled_after_print' AND er.subject_key = " + CANCELLED_AFTER_PRINT_KEY_SQL + "))";

    /**
     * A printed waybill's state for batch progress: 'cancelled' (any cancelled order, resolved or
     * not), 'packed', 'set_aside' (open set-aside) or 'waiting' (everything else, incl. being
     * packed right now). Needs `o`, `s` + {@link #LATEST_PACK_OUTCOME_LATERAL}.
     */
    public static final String BATCH_ITEM_STATE_SQL =
        "CASE WHEN " + CANCELLED_SQL + " THEN 'cancelled' " +
        "     WHEN NOT " + NOT_YET_PACKED_SQL + " THEN 'packed' " +
        "     WHEN " + SET_ASIDE_OPEN_SQL + " THEN 'set_aside' " +
        "     ELSE 'waiting' END";
}
