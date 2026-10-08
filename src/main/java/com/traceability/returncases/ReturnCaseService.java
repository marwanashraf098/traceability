package com.traceability.returncases;

import com.traceability.inventory.VariantStockService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;

/**
 * The "Returns & exchanges" case list (Step 1, backend): ONE row per real-world return.
 *
 * A case is exactly one of:
 *   A — a portal return request (refund or exchange). It absorbs its courier-return leg (held by
 *       return_shipment_id or by the tracking number it booked) and the exchanges row it created
 *       (exchanges.return_request_id), which therefore never appear as rows of their own;
 *   B — a dashboard exchange (exchanges.return_request_id IS NULL);
 *   C — a courier-return leg (shipments.shipment_leg = 'return') that no request holds.
 * Unlinked Bosta deliveries (D) are not cases yet.
 *
 * Every rule that decides "needs a person" comes from {@link ReturnCaseRules}, which the alert
 * detectors (ExceptionService) also use — the page and the alerts can't disagree. A resolved
 * alert clears the page's red / overdue flag (same exception_resolutions keys); it never
 * changes a case's stage.
 *
 * Per case: nextStep (a code), stage (derived from nextStep), tone (action / problem / moving /
 * done_good / done_closed), overdueDays, and alerts (the open alert types, for agreement with
 * the detectors). Sort: stage group (to_do, in_progress, done), updatedAt DESC, case key DESC;
 * keyset cursor. Built on the caller's JdbcTemplate (tests run it on a real app_user connection).
 */
@Service
public class ReturnCaseService {

    public static final int MAX_LIMIT = 50;

    /** Next steps per stage. */
    static final List<String> TO_DO = List.of("booking_problem", "choose_replacement", "approve", "inspect",
        "record_refund", "add_in_receiving", "link_order", "link_request", "scan");
    static final List<String> IN_PROGRESS = List.of("booking", "awaiting_items", "on_the_way");
    /** done: refunded, exchanged, received (good) · rejected, closed, cancelled, dismissed, returned_no_exchange (closed) · lost (problem). */
    static final List<String> DONE_GOOD = List.of("refunded", "exchanged", "received");

    /** The five shortcut tiles → the next steps they count. */
    public static final Map<String, List<String>> TILES = Map.of(
        "toApprove", List.of("approve"),
        "replacementToChoose", List.of("choose_replacement"),
        "toLinkOrder", List.of("link_order", "link_request"),
        "refundToRecord", List.of("record_refund"),
        "bookingProblem", List.of("booking_problem"));

    private final JdbcTemplate jdbc;

    public ReturnCaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── SQL ──────────────────────────────────────────────────────────────────────

    private static String days(String expr) {
        return "floor(extract(epoch FROM now() - (" + expr + ")) / 86400)::int";
    }

    private static final String LABEL_AGG =
        "SELECT array_agg(x.label ORDER BY x.label) FROM (";

    /** A — portal requests. Param: tenant, in-stock replacement variant ids (uuid[] text). */
    private static final String CASES_A =
        "SELECT 'A' AS case_type, rr.id, rr.type AS kind, rr.reference, 'returns_page' AS source, " +
        "       CASE WHEN o.pii_redacted_at IS NULL THEN o.customer_name END AS customer_name, o.number AS order_number, " +
        "       rr.status::text AS status, rr.booking_status, rr.booking_error, " +
        "       CASE " +
        "         WHEN " + ReturnCaseRules.BOOKING_PROBLEM_SQL + " THEN 'booking_problem' " +
        "         WHEN rr.status = 'requested' AND rr.type = 'exchange' AND EXISTS (SELECT 1 FROM return_request_items i " +
        "              WHERE i.request_id = rr.id AND i.tenant_id = rr.tenant_id AND i.active AND i.replacement_variant_id IS NOT NULL " +
        "                AND NOT (i.replacement_variant_id = ANY (?::uuid[]))) THEN 'choose_replacement' " +
        "         WHEN rr.status = 'requested' THEN 'approve' " +
        "         WHEN rr.status::text IN ('approved', 'pickup_booked', 'received') AND cnt.arrived > 0 THEN 'inspect' " +
        "         WHEN rr.status = 'refund_pending' THEN 'record_refund' " +
        "         WHEN ir.open THEN 'add_in_receiving' " +
        "         WHEN rr.status = 'received' THEN 'inspect' " +
        "         WHEN rr.status = 'approved' AND (rr.booking_status = 'pending' OR t.portal_pickup_booking) THEN 'booking' " +
        "         WHEN rr.status = 'approved' THEN 'awaiting_items' " +
        "         WHEN rr.status = 'pickup_booked' THEN 'on_the_way' " +
        "         ELSE rr.status::text END AS next_step, " +
        "       cnt.arrived AS arrived_n, cnt.awaiting AS awaiting_n, cnt.total AS items_n, 0 AS candidate_n, " +
        "       NULL::text AS candidate_refs, " +
        "       CASE WHEN a.refund THEN " + days("rr.refund_pending_at") +
        "            WHEN a.items THEN " + days(ReturnCaseRules.ITEMS_OVERDUE_ANCHOR_SQL) + " END AS overdue_days, " +
        "       a.booking AS a_booking, a.refund AS a_refund, a.items AS a_items, ir.open AS a_item_receive, " +
        "       false AS a_mapping, false AS a_link, false AS a_unscanned, false AS a_to_receive, " +
        "       COALESCE((" + LABEL_AGG +
        "           SELECT pr.title || COALESCE(' ' || NULLIF(v.title, ''), '') || COALESCE(' → ' || NULLIF(rv.title, ''), '') " +
        "                  || CASE WHEN COUNT(*) > 1 THEN ' × ' || COUNT(*) ELSE '' END AS label " +
        "           FROM return_request_items i JOIN variants v ON v.id = i.variant_id JOIN products pr ON pr.id = v.product_id " +
        "           LEFT JOIN variants rv ON rv.id = i.replacement_variant_id " +
        "           WHERE i.request_id = rr.id AND i.tenant_id = rr.tenant_id GROUP BY pr.title, v.title, rv.title) x), '{}') AS items_en, " +
        "       NULL::text[] AS items_ar, false AS not_scanned, " +
        "       array_remove(ARRAY[rr.bosta_tracking_number, " +
        "         (SELECT sl.tracking_number FROM shipments sl WHERE sl.id = rr.return_shipment_id AND sl.tenant_id = rr.tenant_id), " +
        "         (SELECT ex.tracking_number FROM exchanges ex WHERE ex.return_request_id = rr.id AND ex.tenant_id = rr.tenant_id " +
        "            ORDER BY ex.created_at DESC, ex.id DESC LIMIT 1)], NULL) AS trackings, " +
        "       GREATEST(rr.created_at, (SELECT MAX(ev.occurred_at) FROM return_request_events ev " +
        "            WHERE ev.request_id = rr.id AND ev.tenant_id = rr.tenant_id)) AS updated_at, " +
        "       rr.id AS request_id, NULL::uuid AS exchange_id, rr.return_shipment_id AS shipment_id, " +
        // Step 2 (read-only display inputs): refunded total + currency, close reason, inspection state (C only).
        "       (SELECT COALESCE(SUM(rf.amount), 0) FROM return_refunds rf WHERE rf.request_id = rr.id AND rf.tenant_id = rr.tenant_id " +
        "          AND rf.kind = 'refund' AND NOT EXISTS (SELECT 1 FROM return_refunds v WHERE v.voids_refund_id = rf.id " +
        "          AND v.kind = 'void')) AS refund_total, " +
        "       COALESCE(NULLIF(o.raw->>'currency', ''), 'EGP') AS currency, rr.close_reason, NULL::text AS inspection_state " +
        "FROM return_requests rr " +
        "JOIN orders o  ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
        "JOIN tenants t ON t.id = rr.tenant_id " +
        "CROSS JOIN LATERAL (SELECT COUNT(*) FILTER (WHERE i.item_status = 'arrived') AS arrived, " +
        "       COUNT(*) FILTER (WHERE i.item_status = 'awaiting') AS awaiting, COUNT(*) AS total " +
        "       FROM return_request_items i WHERE i.request_id = rr.id AND i.tenant_id = rr.tenant_id) cnt " +
        "CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM return_request_items ri WHERE ri.request_id = rr.id " +
        "       AND ri.tenant_id = rr.tenant_id AND " + ReturnCaseRules.REQUEST_ITEM_TO_RECEIVE_OPEN_SQL + ") AS open) ir " +
        "CROSS JOIN LATERAL (SELECT " +
        "       (" + ReturnCaseRules.BOOKING_PROBLEM_SQL + " AND " +
        ReturnCaseRules.notResolved("pickup_booking_problem", ReturnCaseRules.BOOKING_PROBLEM_KEY_SQL, "rr.tenant_id") + ") AS booking, " +
        "       (" + ReturnCaseRules.REFUND_OVERDUE_SQL + " AND " +
        ReturnCaseRules.notResolved("refund_pending_overdue", ReturnCaseRules.REFUND_OVERDUE_KEY_SQL, "rr.tenant_id") + ") AS refund, " +
        "       (" + ReturnCaseRules.ITEMS_OVERDUE_SQL + " AND " +
        ReturnCaseRules.notResolved("return_items_overdue", ReturnCaseRules.ITEMS_OVERDUE_KEY_SQL, "rr.tenant_id") + ") AS items) a " +
        "WHERE rr.tenant_id = ? ";

    /** B — dashboard exchanges (never a request's own exchange row). Param: tenant. */
    private static final String CASES_B =
        "SELECT 'B', e.id, 'exchange', e.tracking_number, 'bosta', " +
        "       CASE WHEN mo.pii_redacted_at IS NULL THEN COALESCE(NULLIF(e.raw #>> '{receiver,fullName}', ''), " +
        "            NULLIF(trim(concat(e.raw #>> '{receiver,firstName}', ' ', e.raw #>> '{receiver,lastName}')), '')) END, " +
        "       mo.number, e.status, NULL::text, NULL::text, " +
        "       CASE WHEN " + ReturnCaseRules.EXCHANGE_NEEDS_MAPPING_SQL + " THEN 'choose_replacement' " +
        "            WHEN e.status IN ('unmatched', 'needs_confirmation') THEN 'link_order' " +
        "            WHEN e.status IN ('return_received', 'reconciled') THEN 'exchanged' " +
        "            WHEN e.status = 'bare_return' THEN 'returned_no_exchange' " +
        "            WHEN e.status IN ('dismissed', 'cancelled') THEN e.status " +
        "            ELSE 'on_the_way' END, " +
        "       0, 0, 1, 0, NULL::text, NULL::int, " +
        "       false, false, false, false, (" + ReturnCaseRules.EXCHANGE_NEEDS_MAPPING_SQL + "), false, false, false, " +
        "       ARRAY[CASE WHEN iv.id IS NOT NULL AND ov.id IS NOT NULL " +
        "                  THEN ipr.title || COALESCE(' ' || NULLIF(iv.title, ''), '') || ' → ' || COALESCE(ov.title, '') " +
        "                  ELSE COALESCE(NULLIF(e.inbound_description, ''), '?') || ' → ' || COALESCE(NULLIF(e.outbound_description, ''), '?') END], " +
        "       ARRAY[CASE WHEN iv.id IS NOT NULL AND ov.id IS NOT NULL " +
        "                  THEN ipr.title || COALESCE(' ' || NULLIF(iv.title, ''), '') || ' ← ' || COALESCE(ov.title, '') " +
        "                  ELSE COALESCE(NULLIF(e.inbound_description_ar, ''), NULLIF(e.inbound_description, ''), '?') || ' ← ' " +
        "                       || COALESCE(NULLIF(e.outbound_description, ''), '?') END], " +
        "       false, ARRAY[e.tracking_number], e.updated_at, NULL::uuid, e.id, NULL::uuid, " +
        "       NULL::numeric, NULL::text, NULL::text, NULL::text " +
        "FROM exchanges e " +
        // Labelling guard: the customer's order is matched_order_id, never outbound_order_id.
        "LEFT JOIN orders mo ON mo.id = e.matched_order_id AND mo.tenant_id = e.tenant_id " +
        "LEFT JOIN variants iv ON iv.id = e.inbound_variant_id " +
        "LEFT JOIN products ipr ON ipr.id = iv.product_id " +
        "LEFT JOIN variants ov ON ov.id = e.outbound_variant_id " +
        "WHERE e.tenant_id = ? AND e.return_request_id IS NULL ";

    /** C — courier-return legs no request holds. Param: tenant. */
    private static final String CASES_C =
        "SELECT 'C', s.id, 'refund', s.tracking_number, 'bosta', " +
        "       CASE WHEN o.pii_redacted_at IS NULL THEN o.customer_name END, o.number, s.internal_state::text, NULL::text, NULL::text, " +
        "       CASE WHEN s.internal_state = 'lost'::shipment_internal_state THEN 'lost' " +
        "            WHEN s.internal_state::text IN ('terminated', 'cancelled') THEN 'cancelled' " +
        "            WHEN c.n >= 2 THEN 'link_request' " +
        "            WHEN (st.state = 'received_untracked' AND aw.open_rtr) OR aw.open_units THEN 'add_in_receiving' " +
        "            WHEN st.state = 'received_untracked' THEN 'received' " +
        "            WHEN st.state = 'in_transit' THEN 'on_the_way' " +
        "            WHEN aw.awaiting THEN 'scan' " +
        "            WHEN st.state = 'needs_inspection' THEN 'inspect' " +
        "            ELSE 'received' END, " +
        "       pc.n, 0, 0, c.n, c.refs, " +
        "       CASE WHEN un.alert THEN " + days("x.entered_returned_at") + " END, " +
        "       false, false, false, false, false, " +
        "       (" + ReturnCaseRules.LINK_AMBIGUOUS_SQL + " AND " +
        ReturnCaseRules.notResolved("return_link_ambiguous", ReturnCaseRules.LINK_AMBIGUOUS_KEY_SQL, "s.tenant_id") + "), " +
        "       un.alert, rtr.open, " +
        "       COALESCE((" + LABEL_AGG +
        "           SELECT pr.title || COALESCE(' ' || NULLIF(v.title, ''), '') " +
        "                  || CASE WHEN SUM(oi.quantity) > 1 THEN ' × ' || SUM(oi.quantity) ELSE '' END AS label " +
        "           FROM order_items oi JOIN variants v ON v.id = oi.variant_id JOIN products pr ON pr.id = v.product_id " +
        "           WHERE oi.order_id = s.order_id AND oi.tenant_id = s.tenant_id GROUP BY pr.title, v.title) x), '{}'), " +
        "       NULL::text[], (s.return_intake_completed_at IS NULL AND s.internal_state::text NOT IN ('lost', 'terminated', 'cancelled')), " +
        "       ARRAY[s.tracking_number], " +
        "       GREATEST(s.created_at, s.last_synced_at, s.returned_at, s.return_intake_completed_at), " +
        "       NULL::uuid, NULL::uuid, s.id, NULL::numeric, NULL::text, NULL::text, st.state " +
        "FROM shipments s " +
        "JOIN orders o  ON o.id = s.order_id AND o.tenant_id = s.tenant_id " +
        "JOIN tenants t ON t.id = s.tenant_id " +
        ReturnCaseRules.linkCandidatesLateral("c") +
        "CROSS JOIN LATERAL (SELECT (SELECT COUNT(*) FROM pieces p WHERE p.current_order_id = s.order_id " +
        "       AND p.tenant_id = s.tenant_id AND p.status = 'return_pending_inspection'::piece_status) AS n) pc " +
        "CROSS JOIN LATERAL (SELECT " + ReturnCaseRules.inspectionStateSql("s", "pc.n") + " AS state) st " +
        "CROSS JOIN LATERAL (SELECT " + ReturnCaseRules.RETURN_LEG_ENTERED_RETURNED_AT_SQL + " AS entered_returned_at) x " +
        "CROSS JOIN LATERAL (SELECT (" + ReturnCaseRules.RETURN_LEG_AWAITING_SCAN_SQL + ") AS awaiting, " +
        "       (" + ReturnCaseRules.RETURN_TO_RECEIVE_OPEN_SQL + ") AS open_rtr, " +
        "       (" + ReturnCaseRules.LEG_HAS_UNIT_TO_RECEIVE_SQL + ") AS open_units) aw " +
        "CROSS JOIN LATERAL (SELECT aw.open_rtr OR aw.open_units AS open) rtr " +
        "CROSS JOIN LATERAL (SELECT (" + ReturnCaseRules.returnLegUnscannedSql("x.entered_returned_at", "t.return_unscanned_window_days") +
        "       AND " + ReturnCaseRules.notResolved("return_leg_unscanned", ReturnCaseRules.RETURN_LEG_UNSCANNED_KEY_SQL, "s.tenant_id") +
        "       ) AS alert) un " +
        "WHERE s.tenant_id = ? AND s.shipment_leg = 'return' AND NOT " + ReturnCaseRules.LEG_HELD_BY_REQUEST_SQL;

    private static final String STAGE_SQL =
        "CASE WHEN k.next_step IN (" + quoted(TO_DO) + ") THEN 'to_do' " +
        "     WHEN k.next_step IN (" + quoted(IN_PROGRESS) + ") THEN 'in_progress' ELSE 'done' END";

    private static final String CASES =
        "WITH k0 AS (" + CASES_A + " UNION ALL " + CASES_B + " UNION ALL " + CASES_C + "), " +
        "k AS (SELECT k0.*, " + STAGE_SQL.replace("k.", "k0.") + " AS stage, " +
        "      (extract(epoch FROM k0.updated_at) * 1000000)::bigint AS updated_us, " +
        "      k0.case_type || ':' || k0.id AS case_key FROM k0) ";

    private static String quoted(List<String> codes) {
        StringJoiner j = new StringJoiner(", ");
        codes.forEach(c -> j.add("'" + c + "'"));
        return j.toString();
    }

    private static final String TONE_SQL =
        "CASE WHEN k.a_booking OR k.a_refund OR k.a_items OR k.a_unscanned OR k.next_step = 'lost' THEN 'problem' " +
        "     WHEN k.stage = 'to_do' THEN 'action' WHEN k.stage = 'in_progress' THEN 'moving' " +
        "     WHEN k.next_step IN (" + quoted(DONE_GOOD) + ") THEN 'done_good' ELSE 'done_closed' END";

    private static final String STAGE_RANK_SQL =
        "CASE k.stage WHEN 'to_do' THEN 0 WHEN 'in_progress' THEN 1 ELSE 2 END";

    // ── API ──────────────────────────────────────────────────────────────────────

    public record Filter(String stage, String type, String tile, String q) {}

    @Transactional(readOnly = true)
    public Map<String, Object> list(Filter f, String cursor, int limit) {
        UUID tenantId = TenantContext.require();
        int size = Math.max(1, Math.min(limit <= 0 ? 25 : limit, MAX_LIMIT));
        List<Object> params = baseParams(tenantId);
        StringBuilder where = new StringBuilder(" WHERE true ");
        applyFilters(f, where, params, true);
        if (cursor != null && !cursor.isBlank()) {
            Cursor c = Cursor.decode(cursor);
            where.append(" AND (" + STAGE_RANK_SQL + " > ? OR (" + STAGE_RANK_SQL + " = ? AND " +
                "(k.updated_us < ? OR (k.updated_us = ? AND k.case_key < ?)))) ");
            params.addAll(List.of(c.rank(), c.rank(), c.updatedUs(), c.updatedUs(), c.caseKey()));
        }
        params.add(size + 1);
        List<Map<String, Object>> rows = jdbc.queryForList(
            CASES + "SELECT k.*, " + TONE_SQL + " AS tone, " + STAGE_RANK_SQL + " AS stage_rank FROM k" + where +
            " ORDER BY stage_rank, k.updated_us DESC, k.case_key DESC LIMIT ?", params.toArray());

        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            Map<String, Object> last = rows.get(size - 1);
            next = new Cursor(((Number) last.get("stage_rank")).intValue(), ((Number) last.get("updated_us")).longValue(),
                (String) last.get("case_key")).encode();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) items.add(toItem(r));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", next);
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> counts(String type, String q) {
        UUID tenantId = TenantContext.require();
        List<Object> params = baseParams(tenantId);
        StringBuilder where = new StringBuilder(" WHERE true ");
        applyFilters(new Filter(null, type, null, q), where, params, false);
        Map<String, Integer> byStep = new HashMap<>();
        Map<String, Integer> byStage = new HashMap<>();
        jdbc.query(CASES + "SELECT k.stage, k.next_step, COUNT(*) AS n FROM k" + where + " GROUP BY k.stage, k.next_step",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                byStep.merge(rs.getString("next_step"), rs.getInt("n"), Integer::sum);
                byStage.merge(rs.getString("stage"), rs.getInt("n"), Integer::sum);
            }, params.toArray());
        Map<String, Object> stages = new LinkedHashMap<>();
        int all = byStage.values().stream().mapToInt(Integer::intValue).sum();
        stages.put("all", all);
        for (String st : List.of("to_do", "in_progress", "done")) stages.put(st, byStage.getOrDefault(st, 0));
        Map<String, Object> tiles = new LinkedHashMap<>();
        for (String tile : List.of("toApprove", "replacementToChoose", "toLinkOrder", "refundToRecord", "bookingProblem")) {
            tiles.put(tile, TILES.get(tile).stream().mapToInt(s -> byStep.getOrDefault(s, 0)).sum());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stages", stages);
        body.put("tiles", tiles);
        return body;
    }

    /**
     * Params for CASES: the in-stock replacement variants (A's sold-out check), then the tenant
     * for A, B and C. Stock is VariantStockService.computeAll() — the same derivation approve()
     * re-checks with — computed at most once per call, and only when the tenant has a requested
     * exchange (otherwise the list is irrelevant and an empty array is passed).
     */
    private List<Object> baseParams(UUID tenantId) {
        Boolean anyRequestedExchange = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests WHERE tenant_id = ? AND type = 'exchange' AND status = 'requested')",
            Boolean.class, tenantId);
        String inStock = "{}";
        if (Boolean.TRUE.equals(anyRequestedExchange)) {
            StringJoiner j = new StringJoiner(",", "{", "}");
            new VariantStockService(jdbc).computeAll().forEach((variantId, stock) -> {
                if (stock.available() > 0) j.add(variantId.toString());
            });
            inStock = j.toString();
        }
        return new ArrayList<>(List.of(inStock, tenantId, tenantId, tenantId));
    }

    private static void applyFilters(Filter f, StringBuilder where, List<Object> params, boolean withStageAndTile) {
        if (withStageAndTile && f.stage() != null && !f.stage().isBlank() && !"all".equals(f.stage())) {
            if (!List.of("to_do", "in_progress", "done").contains(f.stage())) throw bad("stage");
            where.append(" AND k.stage = ? ");
            params.add(f.stage());
        }
        if (f.type() != null && !f.type().isBlank()) {
            if (!List.of("refund", "exchange").contains(f.type())) throw bad("type");
            where.append(" AND k.kind = ? ");
            params.add(f.type());
        }
        if (withStageAndTile && f.tile() != null && !f.tile().isBlank()) {
            List<String> steps = TILES.get(f.tile());
            if (steps == null) throw bad("tile");
            where.append(" AND k.next_step IN (" + quoted(steps) + ") ");
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim();
            String compact = q.replaceAll("\\s+", "");
            String like = "%" + q.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (upper(k.reference) = upper(?) OR ? = ANY (k.trackings) " +
                "OR lower(k.order_number) LIKE ? OR lower(k.customer_name) LIKE ?) ");
            params.addAll(List.of(compact, compact, like, like));
        }
    }

    private static ResponseStatusException bad(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown " + field);
    }

    private static Map<String, Object> toItem(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseType", r.get("case_type"));
        m.put("id", r.get("id").toString());
        m.put("kind", r.get("kind"));
        m.put("reference", r.get("reference"));
        m.put("source", r.get("source"));
        m.put("customerName", r.get("customer_name"));
        m.put("orderNumber", r.get("order_number"));
        m.put("stage", r.get("stage"));
        m.put("nextStep", r.get("next_step"));
        m.put("tone", r.get("tone"));
        m.put("overdueDays", r.get("overdue_days"));
        m.put("status", r.get("status"));
        // C only: the courier-state badge the courier-return drawer shows (same derivation as listCrpReturns).
        m.put("legStatus", "C".equals(r.get("case_type"))
            ? com.traceability.fulfillment.OrderStatusDeriver.deriveLegStatus((String) r.get("status")) : null);
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("bookingStatus", r.get("booking_status"));
        reason.put("bookingError", r.get("booking_error"));
        reason.put("itemsCount", r.get("items_n"));
        reason.put("arrivedCount", r.get("arrived_n"));
        reason.put("awaitingCount", r.get("awaiting_n"));
        reason.put("candidateCount", r.get("candidate_n"));
        reason.put("candidateReferences", r.get("candidate_refs"));
        // Step 2 display inputs (read-only).
        List<String> trackings = labels(r.get("trackings"));
        reason.put("trackingNumber", trackings.isEmpty() ? null : trackings.get(0));
        Object total = r.get("refund_total");
        reason.put("refundTotal", total == null ? null
            : ((java.math.BigDecimal) total).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        reason.put("currency", r.get("currency"));
        reason.put("closeReason", r.get("close_reason"));
        reason.put("inspectionState", r.get("inspection_state"));
        m.put("reason", reason);
        List<String> alerts = new ArrayList<>();
        if (Boolean.TRUE.equals(r.get("a_booking"))) alerts.add("pickup_booking_problem");
        if (Boolean.TRUE.equals(r.get("a_refund"))) alerts.add("refund_pending_overdue");
        if (Boolean.TRUE.equals(r.get("a_items"))) alerts.add("return_items_overdue");
        if (Boolean.TRUE.equals(r.get("a_item_receive"))) alerts.add("request_item_to_receive");
        if (Boolean.TRUE.equals(r.get("a_mapping"))) alerts.add("exchange_needs_mapping");
        if (Boolean.TRUE.equals(r.get("a_link"))) alerts.add("return_link_ambiguous");
        if (Boolean.TRUE.equals(r.get("a_unscanned"))) alerts.add("return_leg_unscanned");
        if (Boolean.TRUE.equals(r.get("a_to_receive"))) alerts.add("return_to_receive");
        m.put("alerts", alerts);
        List<String> en = labels(r.get("items_en"));
        List<String> ar = r.get("items_ar") == null ? en : labels(r.get("items_ar"));
        boolean notScanned = Boolean.TRUE.equals(r.get("not_scanned"));
        m.put("itemsSummary", String.join(", ", en) + (notScanned ? " · not scanned yet" : ""));
        m.put("itemsSummaryAr", String.join("، ", ar) + (notScanned ? " · لم تُمسح بعد" : ""));
        m.put("notScanned", notScanned);
        m.put("updatedAt", ((Timestamp) r.get("updated_at")).toInstant().toString());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("requestId", str(r.get("request_id")));
        target.put("exchangeId", str(r.get("exchange_id")));
        target.put("shipmentId", str(r.get("shipment_id")));
        m.put("target", target);
        return m;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static List<String> labels(Object sqlArray) {
        if (sqlArray == null) return List.of();
        try {
            Object arr = sqlArray instanceof java.sql.Array a ? a.getArray() : sqlArray;
            return Arrays.stream((Object[]) arr).map(String::valueOf).toList();
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    // ── Cursor ───────────────────────────────────────────────────────────────────

    record Cursor(int rank, long updatedUs, String caseKey) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((rank + "|" + updatedUs + "|" + caseKey).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String s) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).split("\\|", 3);
                return new Cursor(Integer.parseInt(p[0]), Long.parseLong(p[1]), p[2]);
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bad cursor");
            }
        }
    }
}
