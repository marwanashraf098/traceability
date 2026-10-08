package com.traceability.inventory;

import com.traceability.portal.PortalService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issue 1 (V152, design signed off 2026-10-08) — Scan returns: the untracked lines of a parcel's
 * order, one row per unit, each marked "Arrived · sellable" / "Arrived · damaged" by the worker.
 *
 * Which parcels: a courier-return leg, or a returned-to-sender forward leg
 * ({@link ShipmentLinkService#returnedToSenderSql}), that no return request holds. Which lines: the
 * order lines with no allocation of any status ({@link PortalService#LINE_UNTRACKED_SQL}); units
 * 1..{@link PortalService#UNTRACKED_CAP_SQL}. A parcel with a linked request keeps its request items.
 *
 * Partial returns are normal (signed off): the leg is HANDLED once at least one unit is marked —
 * stamped return_intake_outcome 'untracked_units_arrived' through the canonical evidence rule
 * ({@link ShipmentLinkService#returnLegScanEvidenceSql}); undoing the last mark clears it. Unmarked
 * units simply didn't come back. Never moves a piece, never touches stock or Shopify — a sellable
 * unit raises one Return To Receive ({@link ShipmentLinkService#UNIT_TO_RECEIVE_OPEN_SQL}) and enters
 * stock only through a Receiving session.
 *
 * Not a bean (the ReturnRequestLifecycle pattern): ReturnSessionService builds it from its own
 * JdbcTemplate, so it joins the caller's transaction and works on an app_user connection. No
 * transactions of its own (app_user + RLS, TenantContext set by the caller).
 */
public class UntrackedParcelUnits {

    private final JdbcTemplate jdbc;

    public UntrackedParcelUnits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when leg {@code s} can carry unit rows: a courier return or returned-to-sender leg no request holds. */
    static String eligibleLegSql() {
        return "(s.shipment_leg = 'return' OR " + ShipmentLinkService.returnedToSenderSql("s") + ") " +
               "AND NOT EXISTS (SELECT 1 FROM return_requests rq WHERE rq.tenant_id = s.tenant_id " +
               "                AND rq.return_shipment_id = s.id) ";
    }

    /**
     * The unit rows of one parcel (empty when the leg isn't eligible or the order has no untracked
     * line), each with its live mark if any. Ordered by product, variant, line, unit.
     */
    public List<Map<String, Object>> units(UUID tenantId, UUID shipmentId) {
        List<Map<String, Object>> lines = jdbc.queryForList(
            "SELECT oi.id AS order_item_id, COALESCE(pr.title, oi.raw->>'title') AS product_title, v.title AS variant_title, " +
            "       v.sku, " + PortalService.UNTRACKED_CAP_SQL + " AS cap " +
            "FROM shipments s " +
            "JOIN order_items oi ON oi.order_id = s.order_id AND oi.tenant_id = s.tenant_id " +
            "LEFT JOIN variants v ON v.id = oi.variant_id " +
            "LEFT JOIN products pr ON pr.id = v.product_id " +
            "WHERE s.id = ? AND s.tenant_id = ? AND " + eligibleLegSql() +
            "  AND " + PortalService.LINE_UNTRACKED_SQL +
            "ORDER BY COALESCE(pr.title, oi.raw->>'title'), v.title, oi.id",
            shipmentId, tenantId);
        if (lines.isEmpty()) return List.of();

        Map<String, Map<String, Object>> marks = new LinkedHashMap<>();
        jdbc.query(
            "SELECT u.id, u.order_item_id, u.unit_no, u.condition, u.via_phone, u.created_at, u.return_session_id, " +
            "       us.name AS marked_by " +
            "FROM untracked_unit_intakes u LEFT JOIN users us ON us.id = u.actor_user_id " +
            "WHERE u.tenant_id = ? AND u.shipment_id = ? AND u.undone_at IS NULL",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("intakeId", rs.getObject("id", UUID.class).toString());
                m.put("condition", rs.getString("condition"));
                m.put("viaPhone", rs.getBoolean("via_phone"));
                m.put("markedBy", rs.getString("marked_by"));
                m.put("markedAt", rs.getTimestamp("created_at").toInstant().toString());
                m.put("sessionId", rs.getObject("return_session_id", UUID.class).toString());
                marks.put(rs.getObject("order_item_id", UUID.class) + ":" + rs.getInt("unit_no"), m);
            },
            tenantId, shipmentId);

        List<Map<String, Object>> units = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            UUID itemId = (UUID) line.get("order_item_id");
            int cap = Math.max(0, ((Number) line.get("cap")).intValue());
            for (int unit = 1; unit <= cap; unit++) {
                Map<String, Object> mark = marks.get(itemId + ":" + unit);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderItemId", itemId.toString());
                row.put("unitNo", unit);
                row.put("units", cap);
                row.put("productTitle", line.get("product_title"));
                row.put("variantTitle", line.get("variant_title"));
                row.put("sku", line.get("sku"));
                row.put("intakeId", mark == null ? null : mark.get("intakeId"));
                row.put("condition", mark == null ? null : mark.get("condition"));
                row.put("viaPhone", mark != null && Boolean.TRUE.equals(mark.get("viaPhone")));
                row.put("markedBy", mark == null ? null : mark.get("markedBy"));
                row.put("markedAt", mark == null ? null : mark.get("markedAt"));
                row.put("markedInSession", mark == null ? null : mark.get("sessionId"));
                units.add(row);
            }
        }
        return units;
    }

    /** True when the shipment has at least one live (not undone) unit mark. */
    public boolean hasLiveMark(UUID tenantId, UUID shipmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM untracked_unit_intakes WHERE tenant_id = ? AND shipment_id = ? " +
            "               AND undone_at IS NULL)",
            Boolean.class, tenantId, shipmentId));
    }

    /**
     * Marks one unit Arrived. Guards (each a 409 with a specific message): the leg is eligible, the
     * line is untracked and the unit exists, the parcel wasn't marked received as a whole. A repeat of
     * the same mark (double tap) is a no-op; a different condition on a marked unit is a 409 (undo
     * first). Then stamps the leg as handled through the canonical evidence rule.
     */
    public void mark(UUID tenantId, UUID sessionId, Map<String, Object> leg, UUID orderItemId, int unitNo,
                     String condition, UUID actorUserId) {
        if (!"sellable".equals(condition) && !"damaged".equals(condition)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "condition must be sellable or damaged");
        }
        UUID shipmentId = (UUID) leg.get("id");
        if ("received_untracked".equals(leg.get("return_intake_outcome"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This parcel was marked received as a whole — undo that to mark its items one by one.");
        }
        Map<String, Object> unit = units(tenantId, shipmentId).stream()
            .filter(u -> orderItemId.toString().equals(u.get("orderItemId")) && unitNo == (int) u.get("unitNo"))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "This item isn't an untracked item of this parcel."));
        if (unit.get("intakeId") != null) {
            if (condition.equals(unit.get("condition"))) return;   // double tap: already marked so
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This item is already marked " + unit.get("condition") + " — undo it first.");
        }

        // via phone: this parcel's AWB was a verified phone scan in this session (scanAwb sets it).
        boolean viaPhone = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT COALESCE(bool_or(via_phone), false) FROM return_session_shipments " +
            "WHERE session_id = ? AND tenant_id = ? AND awb = ?",
            Boolean.class, sessionId, tenantId, leg.get("tracking_number")));
        // ON CONFLICT on the live-slot partial index (ux_untracked_unit_intakes_live): a concurrent tap
        // that won the slot leaves 0 rows here — never a unique violation (which would abort the tx).
        int inserted = jdbc.update(
            "INSERT INTO untracked_unit_intakes (tenant_id, return_session_id, shipment_id, order_id, order_item_id, " +
            "    unit_no, condition, actor_user_id, via_phone) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (shipment_id, order_item_id, unit_no) WHERE undone_at IS NULL DO NOTHING",
            tenantId, sessionId, shipmentId, leg.get("order_id"), orderItemId, unitNo, condition, actorUserId, viaPhone);
        if (inserted == 0) {
            String existing = jdbc.queryForObject(
                "SELECT condition FROM untracked_unit_intakes WHERE tenant_id = ? AND shipment_id = ? " +
                "AND order_item_id = ? AND unit_no = ? AND undone_at IS NULL",
                String.class, tenantId, shipmentId, orderItemId, unitNo);
            if (condition.equals(existing)) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item is already marked " + existing + " — undo it first.");
        }

        // Handled: stamp the leg's intake (only while unstamped) through the canonical evidence rule —
        // the live mark just written IS the evidence (its untracked-unit clause).
        jdbc.update(
            "UPDATE shipments s SET return_intake_completed_at = now(), return_intake_outcome = 'untracked_units_arrived', " +
            "    return_intake_by = ?, return_intake_session_id = cs.session_id " +
            "FROM (SELECT ?::uuid AS session_id) cs " +
            "WHERE s.id = ? AND s.tenant_id = ? AND s.return_intake_completed_at IS NULL " +
            "  AND " + ShipmentLinkService.returnLegScanEvidenceSql("cs.session_id"),
            actorUserId, sessionId, shipmentId, tenantId);
    }

    /**
     * Undoes a unit's mark — only one made in THIS session (the session is open; the caller checks).
     * When no live mark is left on the parcel, the leg's 'untracked_units_arrived' intake is cleared,
     * so the parcel is back to "waiting" and its Return To Receive exceptions are gone.
     */
    public void undo(UUID tenantId, UUID sessionId, UUID shipmentId, UUID orderItemId, int unitNo, UUID actorUserId) {
        int undone = jdbc.update(
            "UPDATE untracked_unit_intakes SET undone_at = now(), undone_by = ? " +
            "WHERE tenant_id = ? AND shipment_id = ? AND order_item_id = ? AND unit_no = ? " +
            "  AND return_session_id = ? AND undone_at IS NULL",
            actorUserId, tenantId, shipmentId, orderItemId, unitNo, sessionId);
        if (undone != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Only an item marked in this session can be undone.");
        }
        jdbc.update(
            "UPDATE shipments SET return_intake_completed_at = NULL, return_intake_outcome = NULL, " +
            "    return_intake_by = NULL, return_intake_session_id = NULL " +
            "WHERE id = ? AND tenant_id = ? AND return_intake_outcome = 'untracked_units_arrived' " +
            "  AND NOT EXISTS (SELECT 1 FROM untracked_unit_intakes u WHERE u.tenant_id = shipments.tenant_id " +
            "                  AND u.shipment_id = shipments.id AND u.undone_at IS NULL)",
            shipmentId, tenantId);
    }
}
