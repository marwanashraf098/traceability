package com.traceability.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V247 (approved 2026-11-27, returns dead end BRK-47010-EG) — Scan returns: "this label IS that unit".
 *
 * A parcel lists an UNTRACKED order line as unit rows ({@link UntrackedParcelUnits}); the item in the box carries a
 * Traced label of the SAME variant (e.g. it was received into stock before the return was scanned), so its scan is an
 * unexpected item. Linking says the piece is the unit: the unit's Return To Receive closes (the piece is already in
 * stock), the session item becomes 'linked' and no longer blocks anything.
 *
 * THE candidate rule ({@link #candidates}) and THE guards ({@link #refusal}) live here only. The writes are made by
 * {@link ReturnSessionService#linkUnit} / {@link ReturnSessionService#undoLinkUnit} (piece events through
 * InventoryLedger, a damaged link through PieceAdjustService). Q1: same variant only. Q4: a lost piece is refused.
 * Q5: linking never marks the order line tracked (no allocation is written).
 *
 * Not a bean (the UntrackedParcelUnits pattern): built from ReturnSessionService's JdbcTemplate, so it joins the
 * caller's transaction and works on an app_user connection. Never names the {@code orders} table
 * (OrdersTableAccessGuardTest) — order numbers come from {@code merchant_orders}.
 */
public class UntrackedUnitLinks {

    private final JdbcTemplate jdbc;
    private final UntrackedParcelUnits units;

    public UntrackedUnitLinks(JdbcTemplate jdbc, UntrackedParcelUnits units) {
        this.jdbc = jdbc;
        this.units = units;
    }

    /** A refusal: a stable reason code for the UI and tests, with the EN / AR message shown to the worker. */
    public record Refusal(String reason, String en, String ar) {}

    /** The piece side of a link: what the guards and the writes need. */
    public record Piece(String id, String status, UUID variantId, UUID locationId, UUID mainLocationId,
                        boolean committed, String committedOrderNumber, String code) {}

    /** The piece's state for the guards (null when not this tenant's). */
    public Piece piece(UUID tenantId, String pieceId) {
        List<Piece> rows = jdbc.query(
            "SELECT p.id, p.status::text AS status, p.variant_id, p.current_location_id, " +
            "       (SELECT l.id FROM locations l WHERE l.tenant_id = p.tenant_id AND l.is_fulfillment = true) AS main_id, " +
            "       COALESCE(p.short_code, p.barcode) AS code, " +
            "       (SELECT mo.number FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "          JOIN merchant_orders mo ON mo.id = oi.order_id " +
            "        WHERE a.piece_id = p.id AND a.tenant_id = p.tenant_id AND a.status IN ('active','packed') LIMIT 1) AS alloc_order, " +
            "       EXISTS (SELECT 1 FROM allocations a WHERE a.piece_id = p.id AND a.tenant_id = p.tenant_id " +
            "               AND a.status IN ('active','packed')) AS committed, " +
            "       (SELECT mo.number FROM merchant_orders mo WHERE mo.id = p.current_order_id AND mo.tenant_id = p.tenant_id) AS cur_order " +
            "FROM pieces p WHERE p.id = ? AND p.tenant_id = ?",
            (rs, i) -> new Piece(rs.getString("id"), rs.getString("status"), rs.getObject("variant_id", UUID.class),
                rs.getObject("current_location_id", UUID.class), rs.getObject("main_id", UUID.class),
                rs.getBoolean("committed"),
                rs.getString("alloc_order") != null ? rs.getString("alloc_order") : rs.getString("cur_order"),
                rs.getString("code")),
            pieceId, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Statuses of a piece that is out with another order (customer's or the courier's hands). */
    private static final Set<String> WITH_AN_ORDER = Set.of("reserved", "packed", "awaiting_pickup", "with_courier",
        "delivered", "return_in_transit");

    /**
     * Why this piece can't be linked at all (null = it can): only an AVAILABLE piece at the main warehouse with no live
     * allocation — a piece in stock that is physically the returned unit.
     */
    public static Refusal refusal(Piece p) {
        String s = p.status();
        if ("lost".equals(s)) {
            return new Refusal("piece_lost",
                "This piece is marked lost. Find it in Lookup first (Found it), then link it.",
                "هذه القطعة مسجّلة مفقودة. اعثر عليها في البحث أولًا (وجدتها)، ثم اربطها.");
        }
        if (WITH_AN_ORDER.contains(s) || p.committed()) {
            String n = p.committedOrderNumber();
            return new Refusal("piece_on_another_order",
                n != null ? "This label belongs to order " + n + "." : "This label belongs to another order.",
                n != null ? "هذا الملصق يخص الطلب " + n + "." : "هذا الملصق يخص طلبًا آخر.");
        }
        if ("return_pending_inspection".equals(s)) {
            return new Refusal("piece_awaiting_inspection",
                "This piece is a return waiting for a decision — restock it or mark it damaged instead.",
                "هذه القطعة مرتجع بانتظار قرار — أعدها للمخزون أو سجّلها تالفة بدلًا من ذلك.");
        }
        if (!"available".equals(s)) {
            return new Refusal("piece_not_available",
                "This piece is " + s.replace('_', ' ') + " — only a piece in stock can be linked.",
                "حالة هذه القطعة لا تسمح بالربط — يمكن ربط قطعة موجودة في المخزون فقط.");
        }
        if (p.mainLocationId() == null || !p.mainLocationId().equals(p.locationId())) {
            return new Refusal("piece_not_at_main",
                "This piece isn't at your main warehouse. Move it there first (Transfers), then link it.",
                "هذه القطعة ليست في مستودعك الرئيسي. انقلها إليه أولًا (التحويلات)، ثم اربطها.");
        }
        return null;
    }

    /**
     * THE candidate rule: the units of untracked lines on parcels scanned in this session whose line's variant is the
     * piece's variant (Q1), with no live link and not yet taken by a Receiving line. Each row: shipmentId, awb,
     * orderItemId, unitNo, units, orderNumber, productTitle, variantTitle, sku, intakeId (null = not marked yet),
     * condition. Empty when the piece can't be linked ({@link #refusal}).
     */
    public List<Map<String, Object>> candidates(UUID tenantId, UUID sessionId, Piece piece) {
        if (piece == null || refusal(piece) != null) return List.of();
        List<Map<String, Object>> legs = jdbc.queryForList(
            "SELECT DISTINCT s.id, s.tracking_number FROM return_session_shipments rss " +
            "JOIN shipments s ON s.tracking_number = rss.awb AND s.tenant_id = rss.tenant_id " +
            "                AND (s.provider = 'bosta' OR s.id = rss.shipment_id) " +
            "WHERE rss.session_id = ? AND rss.tenant_id = ?",
            sessionId, tenantId);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, UUID> lineVariant = new HashMap<>();
        for (Map<String, Object> leg : legs) {
            UUID shipmentId = (UUID) leg.get("id");
            List<Map<String, Object>> rows = units.units(tenantId, shipmentId);
            if (rows.isEmpty()) continue;
            for (Map<String, Object> u : rows) {
                String lineId = (String) u.get("orderItemId");
                UUID variant = lineVariant.computeIfAbsent(lineId, id -> jdbc.queryForObject(
                    "SELECT variant_id FROM order_items WHERE id = ?::uuid AND tenant_id = ?", UUID.class, id, tenantId));
                if (!piece.variantId().equals(variant)) continue;
                String intakeId = (String) u.get("intakeId");
                if (intakeId != null && intakeTaken(tenantId, UUID.fromString(intakeId))) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("shipmentId", shipmentId.toString());
                c.put("awb", leg.get("tracking_number"));
                c.put("orderItemId", lineId);
                c.put("unitNo", u.get("unitNo"));
                c.put("units", u.get("units"));
                c.put("orderNumber", jdbc.query(
                    "SELECT mo.number FROM order_items oi JOIN merchant_orders mo ON mo.id = oi.order_id " +
                    "WHERE oi.id = ?::uuid AND oi.tenant_id = ?", rs -> rs.next() ? rs.getString(1) : null, lineId, tenantId));
                c.put("productTitle", u.get("productTitle"));
                c.put("variantTitle", u.get("variantTitle"));
                c.put("sku", u.get("sku"));
                c.put("intakeId", intakeId);
                c.put("condition", u.get("condition"));
                out.add(c);
            }
        }
        return out;
    }

    /** The intake already has a live link, or a Receiving line took it (its stock entered another way). */
    public boolean intakeTaken(UUID tenantId, UUID intakeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM untracked_unit_piece_links lk WHERE lk.tenant_id = ? AND lk.intake_id = ? " +
            "               AND lk.undone_at IS NULL) " +
            "    OR EXISTS (SELECT 1 FROM receipt_lines rl WHERE rl.tenant_id = ? " +
            "               AND rl.return_source = 'untracked_unit:' || ?::text)",
            Boolean.class, tenantId, intakeId, tenantId, intakeId));
    }

    /**
     * Q3 case B: the line's order was fulfilled in Shopify FROM the Traced Main location (a successful REST-shape
     * fulfillment at Main's numeric id carrying this line — by its LineItem GID, else by variant). Shopify then counted
     * the unit out of Main when it shipped and Traced counted the piece in again only if its receiving +1 applied, so
     * Main may be 1 low. Display only — no Shopify write, no new trigger.
     */
    public boolean shopifyMayBeLow(UUID tenantId, UUID orderItemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM order_items oi " +
            "  JOIN merchant_orders o ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id " +
            "  JOIN variants v ON v.id = oi.variant_id " +
            "  JOIN locations l ON l.tenant_id = oi.tenant_id AND l.is_fulfillment = true AND l.shopify_location_id IS NOT NULL " +
            "  CROSS JOIN LATERAL jsonb_array_elements(" + MainReconcileRules.fulfillmentsSql("o") + ") f " +
            "  CROSS JOIN LATERAL jsonb_array_elements(" + MainReconcileRules.lineItemsSql("f") + ") li " +
            "  WHERE oi.id = ? AND oi.tenant_id = ? AND " + MainReconcileRules.restShapeSql("f") +
            "    AND lower(f->>'status') = 'success' " +
            "    AND f->>'location_id' = regexp_replace(l.shopify_location_id, '^gid://shopify/Location/', '') " +
            "    AND (li->>'admin_graphql_api_id' = oi.raw->>'admin_graphql_api_id' " +
            "         OR 'gid://shopify/ProductVariant/' || (li->>'variant_id') = v.external_id))",
            Boolean.class, orderItemId, tenantId));
    }

    /** The live links made in this session, for the merged rows: one per link. */
    public List<Map<String, Object>> sessionLinks(UUID tenantId, UUID sessionId) {
        return jdbc.queryForList(
            "SELECT lk.id::text AS \"linkId\", lk.piece_id AS \"pieceId\", COALESCE(p.short_code, p.barcode) AS \"pieceCode\", " +
            "       lk.intake_id::text AS \"intakeId\", u.shipment_id::text AS \"shipmentId\", " +
            "       u.order_item_id::text AS \"orderItemId\", u.unit_no AS \"unitNo\", lk.condition, " +
            "       lk.shopify_may_be_low AS \"shopifyMayBeLow\", lk.created_at AS \"linkedAt\", us.name AS \"linkedBy\", " +
            "       (SELECT rs.status = 'open' FROM return_sessions rs WHERE rs.id = lk.session_id) AND lk.condition = 'sellable' " +
            "           AS \"canUndo\" " +
            "FROM untracked_unit_piece_links lk " +
            "JOIN untracked_unit_intakes u ON u.id = lk.intake_id AND u.tenant_id = lk.tenant_id " +
            "JOIN pieces p ON p.id = lk.piece_id AND p.tenant_id = lk.tenant_id " +
            "LEFT JOIN users us ON us.id = lk.actor_user_id " +
            "WHERE lk.tenant_id = ? AND lk.session_id = ? AND lk.undone_at IS NULL " +
            "ORDER BY lk.created_at",
            tenantId, sessionId);
    }

    /** A refusal as the endpoint's 409 / 403. */
    static ReturnSessionException refused(Refusal r) {
        return refused(r, HttpStatus.CONFLICT);
    }

    static ReturnSessionException refused(Refusal r, HttpStatus status) {
        return new ReturnSessionException(ReturnSessionException.Code.LINK_REFUSED, r.en(), r.ar(), status,
            Map.of("reason", r.reason()));
    }
}
