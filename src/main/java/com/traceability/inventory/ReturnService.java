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
 * Returns disposition primitives (FR-12.3/12.4), reused as-is by the FR-24
 * session-based rebuild's ReturnSessionService.disposition(). intakeScan() and
 * listPending() (the old three-tab UI's waybill-less intake + pending queue) were
 * retired with that UI — countPending() survives because Overview's "Awaiting
 * Inspection" tile depends on it (see ReturnController's javadoc for why it's not
 * the same number as the new analytics "unassigned pending" count).
 */
@Service
public class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    private final JdbcTemplate            jdbc;
    private final InventoryLedger         ledger;
    private final ShopifyInventoryService shopifyInventory;
    private final ShipmentLinkService     shipmentLinkService;

    public ReturnService(JdbcTemplate jdbc, InventoryLedger ledger,
                         ShopifyInventoryService shopifyInventory,
                         ShipmentLinkService shipmentLinkService) {
        this.jdbc                = jdbc;
        this.ledger              = ledger;
        this.shopifyInventory    = shopifyInventory;
        this.shipmentLinkService = shipmentLinkService;
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

        PieceStatusAndOrder piece = jdbc.query(
            "SELECT status::text AS status, current_order_id FROM pieces WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? new PieceStatusAndOrder(
                rs.getString("status"), rs.getObject("current_order_id", UUID.class)) : null,
            pieceId, tenantId);

        if (piece == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        if (!"return_pending_inspection".equals(piece.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece must be in return_pending_inspection to restock (current: " + piece.status() + ")");
        }
        // Captured before the UPDATE below clears current_order_id — needed to resolve
        // the order's return leg afterward.
        UUID orderId = piece.orderId();

        // Where the piece goes back on the shelf. The screens send no location — it defaults to
        // the tenant's main warehouse here, never to NULL (a NULL current_location_id hid every
        // restocked piece from stock counts and skipped its Shopify +1 — 2026-10-08). Resolved
        // BEFORE the transition, so "no main warehouse" leaves the piece untouched.
        UUID targetLocationId = restockLocation(locationId, tenantId);

        // restock_event_id keys this restock's Shopify claim (one +1 per restock, so a piece
        // returned twice gets two); order_id lets the claim count against the order's Shopify
        // refund restocks (double-count guard) — current_order_id is cleared just below.
        UUID restockEventId = UUID.randomUUID();
        String meta = "{\"restock_event_id\":\"" + restockEventId + "\"" +
            (orderId != null ? ",\"order_id\":\"" + orderId + "\"" : "") + "}";
        TransitionContext ctx = new TransitionContext(null, null, targetLocationId, null, meta);
        ledger.transition(pieceId, PieceStatus.RETURN_PENDING_INSPECTION,
                PieceStatus.AVAILABLE, "restocked", actorUserId, ctx);

        // Clear order link and set new location
        jdbc.update(
            "UPDATE pieces SET current_order_id = NULL, current_location_id = ? WHERE id = ?",
            targetLocationId, pieceId);

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
        // Fired after commit: a rolled-back restock never reaches Shopify, and the claim never
        // predates the piece's own 'available' commit.
        ShopifyInventoryService.afterCommit(() ->
            shopifyInventory.onReturnInspectionAvailable(tenantId, pieceId, targetLocationId));

        // Close out the order's return leg if this was its last outstanding piece — see
        // ShipmentLinkService.resolveReturnLegIfComplete() javadoc for why this must run
        // after every disposition, not just this one call site's local concern.
        shipmentLinkService.resolveReturnLegIfComplete(orderId, tenantId, pieceId);
    }

    // ── Mark damaged (FR-12.3) ────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markDamaged(String pieceId, String reason, UUID actorUserId) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Reason is required when marking a piece as damaged");
        }
        UUID tenantId = TenantContext.require();

        PieceStatusAndOrder piece = jdbc.query(
            "SELECT status::text AS status, current_order_id FROM pieces WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? new PieceStatusAndOrder(
                rs.getString("status"), rs.getObject("current_order_id", UUID.class)) : null,
            pieceId, tenantId);

        if (piece == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        if (!"return_pending_inspection".equals(piece.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece must be in return_pending_inspection to mark damaged (current: " + piece.status() + ")");
        }

        String meta = "{\"reason\":" + escapeJson(reason) + "}";
        TransitionContext ctx = new TransitionContext(null, null, null, null, meta);
        ledger.transition(pieceId, PieceStatus.RETURN_PENDING_INSPECTION,
                PieceStatus.DAMAGED, "damaged", actorUserId, ctx);

        jdbc.update("UPDATE pieces SET condition = 'damaged' WHERE id = ? AND tenant_id = ?",
            pieceId, tenantId);

        // See restock()'s identical call — same reasoning.
        shipmentLinkService.resolveReturnLegIfComplete(piece.orderId(), tenantId, pieceId);
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

    /**
     * The location a restocked piece lands at: the one the caller named (it must be this
     * tenant's), else the tenant's main warehouse (is_fulfillment). No main warehouse is an
     * error, never a silent NULL location.
     */
    private UUID restockLocation(UUID requested, UUID tenantId) {
        if (requested != null) {
            Integer owned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE id = ? AND tenant_id = ?",
                Integer.class, requested, tenantId);
            if (owned == null || owned == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown locationId");
            }
            return requested;
        }
        List<UUID> main = jdbc.queryForList(
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            UUID.class, tenantId);
        if (main.isEmpty()) {
            log.error("Restock refused: tenant {} has no main warehouse (is_fulfillment location)", tenantId);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "NO_MAIN_WAREHOUSE: this account has no main warehouse to restock into — set one in Settings → Locations.");
        }
        return main.get(0);
    }

    private record PieceStatusAndOrder(String status, UUID orderId) {}

    private static String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
