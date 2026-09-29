package com.traceability.returncases;

import com.traceability.inventory.ShipmentLinkService;
import com.traceability.portal.ReturnRequestLifecycle;

/**
 * THE single source of the rules behind the "Returns & exchanges" case list AND the return
 * alerts (ExceptionService detectors). Both read these SQL fragments, so the page and the
 * alerts can never disagree about what needs a person.
 *
 * Aliases (fixed by convention): {@code rr} return request, {@code t} its tenant row, {@code e}
 * exchange, {@code s} shipment (return leg), {@code ri} return request item, {@code p} piece.
 *
 * Predicates that already had a single home elsewhere are re-exported here, never copied:
 * REFUND_OVERDUE_SQL / ITEMS_OVERDUE_SQL (ReturnRequestLifecycle), RETURN_TO_RECEIVE_OPEN_SQL,
 * RETURN_LEG_AWAITING_SCAN_SQL and RETURN_LEG_ENTERED_RETURNED_AT_SQL (ShipmentLinkService —
 * the awaiting-scan predicate is built on returnLegScanEvidenceSql, whose logic stays there).
 */
public final class ReturnCaseRules {

    private ReturnCaseRules() {}

    /** {@code NOT EXISTS} an exception_resolutions row of {@code type} for {@code keyExpr} (tenant {@code tenantExpr}). */
    public static String notResolved(String type, String keyExpr, String tenantExpr) {
        return "NOT EXISTS (SELECT 1 FROM exception_resolutions er WHERE er.tenant_id = " + tenantExpr +
               " AND er.exception_type = '" + type + "' AND er.subject_key = " + keyExpr + ") ";
    }

    // ── Portal requests (rr) ─────────────────────────────────────────────────────

    /** A Traced Bosta booking that needs a person (pickup_booking_problem). */
    public static final String BOOKING_PROBLEM_SQL =
        "rr.booking_status IN ('failed', 'failed_ambiguous', 'needs_review') ";

    /** pickup_booking_problem's key: the attempt time, so a new failure after a retry is new. */
    public static final String BOOKING_PROBLEM_KEY_SQL =
        "'pickup_booking_problem:' || rr.id || ':' || " +
        "COALESCE(floor(extract(epoch FROM rr.booking_attempted_at))::bigint::text, '0')";

    /** Re-exported from ReturnRequestLifecycle (aliases rr, t). */
    public static final String REFUND_OVERDUE_SQL = ReturnRequestLifecycle.REFUND_OVERDUE_SQL;
    public static final String REFUND_OVERDUE_KEY_SQL = ReturnRequestLifecycle.REFUND_OVERDUE_KEY_SQL;
    public static final String ITEMS_OVERDUE_SQL = ReturnRequestLifecycle.ITEMS_OVERDUE_SQL;
    public static final String ITEMS_OVERDUE_ANCHOR_SQL = ReturnRequestLifecycle.ITEMS_OVERDUE_ANCHOR_SQL;
    public static final String ITEMS_OVERDUE_KEY_SQL = "'return_items_overdue:' || rr.id";

    /**
     * An untracked request item (alias ri) marked Arrived as sellable and not yet received into
     * stock (request_item_to_receive). The resolution IS how this task ends (a manager adds the
     * item in Receiving, then resolves), so open-ness includes it.
     */
    public static final String REQUEST_ITEM_TO_RECEIVE_KEY_SQL =
        "'request_item_to_receive:' || ri.id || ':' || floor(extract(epoch FROM ri.arrived_at))::bigint::text";
    public static final String REQUEST_ITEM_TO_RECEIVE_OPEN_SQL =
        "ri.order_item_id IS NOT NULL AND ri.item_status = 'done' " +
        "AND ri.arrived_condition = 'sellable' AND ri.arrived_at IS NOT NULL " +
        "AND " + notResolved("request_item_to_receive", REQUEST_ITEM_TO_RECEIVE_KEY_SQL, "ri.tenant_id");

    // ── Dashboard exchanges (e) ──────────────────────────────────────────────────

    /** A dashboard exchange still waiting for its sizes to be chosen (exchange_needs_mapping). */
    public static final String EXCHANGE_NEEDS_MAPPING_SQL =
        "e.status = 'needs_mapping' AND e.return_request_id IS NULL ";

    // ── Courier return legs (s) ──────────────────────────────────────────────────

    /** The leg is held by a return request (by id or by the tracking number a request booked). */
    public static final String LEG_HELD_BY_REQUEST_SQL =
        "EXISTS (SELECT 1 FROM return_requests h WHERE h.tenant_id = s.tenant_id " +
        "        AND (h.return_shipment_id = s.id OR h.bosta_tracking_number = s.tracking_number)) ";

    /**
     * return_link_ambiguous: the candidate requests of leg {@code s}, as a LATERAL producing
     * {@code c.n}, {@code c.refs}, {@code c.first_id}. Candidates: ReturnRequestLifecycle.linkCandidateSql.
     */
    public static String linkCandidatesLateral(String alias) {
        String cand = ReturnRequestLifecycle.linkCandidateSql(
            "s.tenant_id", "s.order_id", ReturnRequestLifecycle.LEG_BOSTA_CREATED_AT_SQL);
        return "CROSS JOIN LATERAL (SELECT COUNT(*) AS n, " +
               "       string_agg(rr.reference, ', ' ORDER BY rr.created_at, rr.id) AS refs, " +
               "       (array_agg(rr.id ORDER BY rr.created_at, rr.id))[1] AS first_id " +
               "    FROM return_requests rr WHERE " + cand + ") " + alias + " ";
    }

    /** With {@link #linkCandidatesLateral} as {@code c}: two or more candidates and nobody holds the leg. */
    public static final String LINK_AMBIGUOUS_SQL =
        "s.shipment_leg = 'return' AND c.n >= 2 AND NOT " + LEG_HELD_BY_REQUEST_SQL;
    public static final String LINK_AMBIGUOUS_KEY_SQL = "'return_link_ambiguous:shipment:' || s.id";

    /** Re-exported from ShipmentLinkService. */
    public static final String RETURN_LEG_AWAITING_SCAN_SQL = ShipmentLinkService.RETURN_LEG_AWAITING_SCAN_SQL;
    public static final String RETURN_LEG_ENTERED_RETURNED_AT_SQL = ShipmentLinkService.RETURN_LEG_ENTERED_RETURNED_AT_SQL;
    public static final String RETURN_TO_RECEIVE_OPEN_SQL = ShipmentLinkService.RETURN_TO_RECEIVE_OPEN_SQL;

    /** return_leg_unscanned: awaiting its intake scan for longer than {@code windowDaysExpr} days. */
    public static String returnLegUnscannedSql(String enteredReturnedAtExpr, String windowDaysExpr) {
        return RETURN_LEG_AWAITING_SCAN_SQL +
               "AND " + enteredReturnedAtExpr + " < now() - (interval '1 day' * " + windowDaysExpr + ") ";
    }
    public static final String RETURN_LEG_UNSCANNED_KEY_SQL = "'return_leg_unscanned:shipment:' || s.id";

    /**
     * The leg's inspection state (was Java in listCrpReturns): received_untracked | needs_inspection
     * | resolved | in_transit, for leg alias {@code leg}. {@code pendingCountExpr} = the leg's pieces / request items still
     * awaiting a decision (listCrpReturns' pending_inspection_count).
     */
    public static String inspectionStateSql(String leg, String pendingCountExpr) {
        return "(CASE WHEN " + leg + ".return_intake_outcome = 'received_untracked' THEN 'received_untracked' " +
               "      WHEN " + leg + ".return_intake_outcome = 'request_items_arrived' " +
               "           THEN CASE WHEN (" + pendingCountExpr + ") > 0 THEN 'needs_inspection' ELSE 'resolved' END " +
               "      WHEN " + leg + ".internal_state <> 'returned'::shipment_internal_state THEN 'in_transit' " +
               "      WHEN " + leg + ".return_intake_completed_at IS NULL THEN 'needs_inspection' " +
               "      WHEN (" + pendingCountExpr + ") > 0 THEN 'needs_inspection' " +
               "      ELSE 'resolved' END)";
    }

    // ── Pieces (p) ───────────────────────────────────────────────────────────────

    /**
     * return_in_transit_stuck (piece-level, forward-leg returns): at return_in_transit longer than
     * {@code daysExpr} with no intake scan. Return legs never move pieces, so no courier-return
     * case carries this flag; it lives here so the alert keeps one definition.
     */
    public static String returnInTransitStuckSql(String daysExpr, String tenantExpr) {
        return "p.status = 'return_in_transit'::piece_status " +
               "AND p.last_event_at < now() - (interval '1 day' * " + daysExpr + ") " +
               "AND NOT EXISTS (SELECT 1 FROM piece_events pe WHERE pe.piece_id = p.id " +
               "                AND pe.event_type = 'return_received' AND pe.tenant_id = " + tenantExpr + ") ";
    }
}
