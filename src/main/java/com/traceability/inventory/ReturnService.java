package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Returns intake (FR-12.1–12.4): worker scans arriving RTO pieces,
 * Manager resolves pending-inspection pieces, never-received report surfaces
 * pieces Bosta confirmed returned but that were never physically scanned back in.
 */
@Service
public class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    private static final Set<String> RETURNING_SHIPMENT_STATES =
            Set.of("returning", "returned");

    private final JdbcTemplate            jdbc;
    private final InventoryLedger         ledger;
    private final ShopifyInventoryService shopifyInventory;

    public ReturnService(JdbcTemplate jdbc, InventoryLedger ledger,
                         ShopifyInventoryService shopifyInventory) {
        this.jdbc             = jdbc;
        this.ledger           = ledger;
        this.shopifyInventory = shopifyInventory;
    }

    // ── Intake scan (FR-12.1 + FR-12.2) ──────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> intakeScan(String barcode, UUID locationId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // 1. Fetch piece with context
        Map<String, Object> row = jdbc.query(
            "SELECT p.id, p.status::text AS status, p.current_order_id, " +
            "       v.title AS variant_title, pr.title AS product_title, v.sku, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.internal_state AS shipment_state " +
            "FROM pieces p " +
            "JOIN variants v   ON v.id  = p.variant_id " +
            "JOIN products pr  ON pr.id = v.product_id " +
            "LEFT JOIN orders o     ON o.id  = p.current_order_id " +
            "LEFT JOIN shipments s  ON s.order_id = o.id AND s.shipment_leg = 'forward' " +
            "WHERE (p.barcode = ? OR p.id = ? OR p.short_code = ?) AND p.tenant_id = ?",
            rs -> rs.next() ? mapRow(rs) : null,
            barcode, barcode, barcode, tenantId);

        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found: " + barcode);
        }

        String pieceId       = (String) row.get("id");
        String status        = (String) row.get("status");
        UUID   orderId       = row.get("order_id") != null ? (UUID) row.get("order_id") : null;
        UUID   shipmentId    = row.get("shipment_id") != null ? (UUID) row.get("shipment_id") : null;
        String shipmentState = (String) row.get("shipmentState");

        // 2. Determine if this is an unexpected return (FR-12.2)
        boolean isUnexpected = (shipmentState == null)
                || !RETURNING_SHIPMENT_STATES.contains(shipmentState);

        // 3. Transition based on current piece status
        PieceStatus current = PieceStatus.fromDb(status);
        TransitionContext ctx = new TransitionContext(
                orderId, shipmentId, locationId, orderId, null);

        switch (current) {
            case RETURN_IN_TRANSIT -> {
                // Expected full path: return_in_transit → return_pending_inspection
                ledger.transition(pieceId, PieceStatus.RETURN_IN_TRANSIT,
                        PieceStatus.RETURN_PENDING_INSPECTION, "return_received", actorUserId, ctx);
            }
            case WITH_COURIER -> {
                // Bosta lag: state 41 RTO never arrived; piece still with_courier.
                isUnexpected = true;
                ledger.transition(pieceId, PieceStatus.WITH_COURIER,
                        PieceStatus.RETURN_PENDING_INSPECTION, "return_received", actorUserId, ctx);
            }
            case AWAITING_PICKUP -> {
                // Webhook lag: courier-pickup event (state 21) never arrived so piece
                // never advanced to with_courier. Physical piece arrived back at warehouse.
                isUnexpected = true;
                ledger.transition(pieceId, PieceStatus.AWAITING_PICKUP,
                        PieceStatus.RETURN_PENDING_INSPECTION, "return_received", actorUserId, ctx);
            }
            case RETURN_PENDING_INSPECTION -> {
                // State-46 webhook already moved the piece here before the intake scan.
                // Record physical intake as an event without a status change.
                ledger.recordReturnReceived(pieceId, locationId, actorUserId, orderId, shipmentId, null);
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot intake piece in status '" + status + "' — expected return_in_transit or with_courier");
        }

        // 4. Update piece location to the scan location
        jdbc.update(
            "UPDATE pieces SET current_location_id = ? WHERE id = ?",
            locationId, pieceId);

        if (isUnexpected) {
            log.warn("Unexpected return intake: piece={} barcode={} shipmentState={}",
                    pieceId, barcode, shipmentState);
        }

        // 5. Build response
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("intakedAt",    new java.util.Date());
        result.put("isUnexpected", isUnexpected);
        result.put("locationId",   locationId != null ? locationId.toString() : null);
        return result;
    }

    // ── List pending inspection (FR-12.3 queue) ───────────────────────────────

    public List<Map<String, Object>> listPending(int page, int size) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT p.id, p.barcode, p.status::text AS status, p.last_event_at, " +
            "       v.title AS variant_title, pr.title AS product_title, v.sku, " +
            "       o.number AS order_number, s.tracking_number, " +
            "       loc.name AS location_name " +
            "FROM pieces p " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN orders o      ON o.id  = p.current_order_id " +
            "LEFT JOIN shipments s   ON s.order_id = o.id AND s.shipment_leg = 'forward' " +
            "LEFT JOIN locations loc ON loc.id = p.current_location_id " +
            "WHERE p.status = 'return_pending_inspection'::piece_status " +
            "  AND p.tenant_id = ? " +
            "ORDER BY p.last_event_at ASC LIMIT ? OFFSET ?",
            tenantId, size, (long) page * size);
    }

    // ── Restock (FR-12.3) ─────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void restock(String pieceId, UUID locationId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        String status = jdbc.query(
            "SELECT status::text FROM pieces WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            pieceId, tenantId);

        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        if (!"return_pending_inspection".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece must be in return_pending_inspection to restock (current: " + status + ")");
        }

        TransitionContext ctx = new TransitionContext(null, null, locationId, null, null);
        ledger.transition(pieceId, PieceStatus.RETURN_PENDING_INSPECTION,
                PieceStatus.AVAILABLE, "restocked", actorUserId, ctx);

        // Clear order link and set new location
        jdbc.update(
            "UPDATE pieces SET current_order_id = NULL, current_location_id = ? WHERE id = ?",
            locationId, pieceId);

        // Async Shopify shadow sync — Trigger 2 (return_inspection → AVAILABLE).
        // Damaged pieces are NOT routed here; markDamaged() has no sync call — invariant preserved.
        shopifyInventory.onReturnInspectionAvailable(tenantId, pieceId, locationId);
    }

    // ── Mark damaged (FR-12.3) ────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markDamaged(String pieceId, String reason, UUID actorUserId) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Reason is required when marking a piece as damaged");
        }
        UUID tenantId = TenantContext.require();

        String status = jdbc.query(
            "SELECT status::text FROM pieces WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            pieceId, tenantId);

        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        if (!"return_pending_inspection".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece must be in return_pending_inspection to mark damaged (current: " + status + ")");
        }

        String meta = "{\"reason\":" + escapeJson(reason) + "}";
        TransitionContext ctx = new TransitionContext(null, null, null, null, meta);
        ledger.transition(pieceId, PieceStatus.RETURN_PENDING_INSPECTION,
                PieceStatus.DAMAGED, "damaged", actorUserId, ctx);
    }

    // ── Never-received report (FR-12.4) ──────────────────────────────────────

    public List<Map<String, Object>> neverReceived(int windowDays) {
        UUID tenantId = TenantContext.require();

        return jdbc.queryForList(
            "SELECT p.id, p.barcode, p.status::text AS status, " +
            "       v.title AS variant_title, pr.title AS product_title, v.sku, " +
            "       o.number AS order_number, o.id AS order_id, " +
            "       s.tracking_number, s.returned_at " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "JOIN order_items oi ON oi.order_id = o.id " +
            "JOIN allocations a  ON a.order_item_id = oi.id " +
            "                    AND a.status IN ('packed','active') " +
            "JOIN pieces p ON p.id = a.piece_id AND p.tenant_id = ? " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE s.shipment_leg = 'forward' " +
            "  AND s.internal_state = 'returned' " +
            "  AND s.returned_at IS NOT NULL " +
            "  AND s.returned_at < now() - (interval '1 day' * ?) " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM piece_events pe " +
            "      WHERE pe.piece_id   = p.id " +
            "        AND pe.event_type = 'return_received' " +
            "        AND pe.tenant_id  = ? " +
            "  ) " +
            "ORDER BY s.returned_at ASC",
            tenantId, tenantId, windowDays, tenantId, tenantId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",             rs.getString("id"));
        m.put("status",         rs.getString("status"));
        m.put("variantTitle",   rs.getString("variant_title"));
        m.put("productTitle",   rs.getString("product_title"));
        m.put("sku",            rs.getString("sku"));
        m.put("order_id",       rs.getObject("order_id", UUID.class));
        m.put("orderNumber",    rs.getString("order_number"));
        m.put("shipment_id",    rs.getObject("shipment_id", UUID.class));
        m.put("trackingNumber", rs.getString("tracking_number"));
        m.put("shipmentState",  rs.getString("shipment_state"));
        return m;
    }

    private static String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
