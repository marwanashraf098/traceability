package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Returns disposition primitives (FR-12.3/12.4), reused as-is by the FR-24
 * session-based rebuild's ReturnSessionService.disposition(). intakeScan() and
 * listPending() (the old three-tab UI's waybill-less intake + pending queue) were
 * retired with that UI — countPending() survives because Overview's "Awaiting
 * Inspection" tile depends on it (see ReturnController's javadoc for why it's not
 * the same number as the new analytics "unassigned pending" count).
 */
@Service
public class ReturnService {

    private final JdbcTemplate            jdbc;
    private final InventoryLedger         ledger;
    private final ShopifyInventoryService shopifyInventory;

    public ReturnService(JdbcTemplate jdbc, InventoryLedger ledger,
                         ShopifyInventoryService shopifyInventory) {
        this.jdbc             = jdbc;
        this.ledger           = ledger;
        this.shopifyInventory = shopifyInventory;
    }

    /**
     * True total count of pieces at return_pending_inspection, independent of page/size —
     * the Overview dashboard's awaiting-inspection tile reads this via GET /returns/pending.
     */
    @Transactional(readOnly = true)
    public long countPending() {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM pieces " +
            "WHERE status = 'return_pending_inspection'::piece_status AND tenant_id = ?",
            Long.class, tenantId);
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

        // Release the piece's stale allocation from its OLD order — without this, the row
        // stays 'packed' forever and FulfillService.scan()'s ALREADY_RESERVED guard (which
        // reads allocations.status by piece_id alone, no order filter) permanently blocks
        // re-allocating this piece to any new order, even though pieces.status/current_order_id
        // both correctly show it as free. Restock is the only return verdict that frees a piece
        // back to available for re-allocation — damaged/lost are terminal, their stale
        // allocations are inert and intentionally left alone.
        jdbc.update(
            "UPDATE allocations SET status = 'released' " +
            "WHERE piece_id = ? AND status IN ('active','packed')",
            pieceId);

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

        jdbc.update("UPDATE pieces SET condition = 'damaged' WHERE id = ? AND tenant_id = ?",
            pieceId, tenantId);
    }

    // ── Never-received report (FR-12.4) ──────────────────────────────────────

    @Transactional(readOnly = true)
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

    private static String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
