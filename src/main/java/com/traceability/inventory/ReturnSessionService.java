package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Returns-receiving session: waybill-driven workflow for restocking/damaging
 * returned pieces (both RTO courier-returns and customer-after-delivery returns).
 *
 * Session lifecycle:
 *   createSession → getSessionPieces → recordVerdict (per piece) → finalizeSession
 *
 * Invariants:
 *   - delivered → return_pending_inspection is guarded by customer_return_window_days.
 *   - InventoryLedger is the sole writer of piece_events (4 methods on that class).
 *   - ReturnService.restock() and markDamaged() are called AS-IS within the same
 *     @Transactional(READ_COMMITTED) boundary — Spring REQUIRED propagation joins them.
 */
@Service
public class ReturnSessionService {

    private static final Set<String> VALID_SESSION_STATES = Set.of(
        "returning",   // Bosta RTO in progress — return_in_transit pieces expected
        "returned",    // Bosta confirmed full return
        "delivered",   // All pieces delivered — customer-after-delivery scenario
        "exception"    // Courier exception — pieces in various states
    );

    private final JdbcTemplate    jdbc;
    private final InventoryLedger  ledger;
    private final ReturnService    returnService;
    private final Clock            clock;

    public ReturnSessionService(JdbcTemplate jdbc, InventoryLedger ledger,
                                ReturnService returnService, Clock clock) {
        this.jdbc          = jdbc;
        this.ledger        = ledger;
        this.returnService = returnService;
        this.clock         = clock;
    }

    // ── Create session ────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createSession(String waybillNumber, UUID locationId,
                                              String note, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // Validate shipment exists and is in a state where returns make sense.
        Map<String, Object> shipment = jdbc.query(
            "SELECT s.id, s.internal_state::text AS state, " +
            "       o.id AS order_id, o.number AS order_number " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.tracking_number = ? AND s.tenant_id = ?",
            rs -> rs.next() ? Map.of(
                "shipmentId",   rs.getObject("id", UUID.class),
                "state",        rs.getString("state"),
                "orderId",      rs.getObject("order_id", UUID.class),
                "orderNumber",  rs.getString("order_number")
            ) : null,
            tenantId, waybillNumber, tenantId);

        if (shipment == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Shipment not found: " + waybillNumber);
        }

        String state = (String) shipment.get("state");
        if (!VALID_SESSION_STATES.contains(state)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Cannot open a returns session for a shipment in state '" + state +
                "'. Valid states: returning, returned, delivered, exception.");
        }

        UUID sessionId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts " +
            "(id, tenant_id, kind, reference, received_by, location_id, note, status) " +
            "VALUES (?, ?, 'returns', ?, ?, ?, ?, 'open')",
            sessionId, tenantId, waybillNumber, actorUserId, locationId, note);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId",   sessionId);
        result.put("waybillNumber", waybillNumber);
        result.put("orderId",     shipment.get("orderId"));
        result.put("orderNumber", shipment.get("orderNumber"));
        return result;
    }

    // ── List sessions ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> listSessions() {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT r.id, r.status, r.reference AS waybill_number, " +
            "       r.created_at, r.finalized_at, " +
            "       l.name AS location_name " +
            "FROM receipts r " +
            "LEFT JOIN locations l ON l.id = r.location_id " +
            "WHERE r.tenant_id = ? AND r.kind = 'returns' " +
            "ORDER BY r.created_at DESC",
            tenantId);
    }

    // ── Pieces eligible for return ────────────────────────────────────────────

    /**
     * Returns all pieces eligible for intake in this session (return_in_transit OR
     * delivered), annotated with whether they've already been processed.
     *
     * Change 2 note: delivered pieces that were NOT scanned are the NORMAL case
     * (customer kept the item). They are returned with processed=false but the UI
     * must NOT treat them as a problem — only un-scanned return_in_transit pieces
     * are actionable.
     */
    public Map<String, Object> getSessionPieces(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);

        String waybill = jdbc.queryForObject(
            "SELECT reference FROM receipts WHERE id = ? AND tenant_id = ?",
            String.class, sessionId, tenantId);

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT " +
            "    p.id, p.barcode, p.status::text AS status, " +
            "    v.title AS variant_title, pr.title AS product_title, v.sku, " +
            "    EXISTS ( " +
            "        SELECT 1 FROM piece_events pe " +
            "        WHERE pe.piece_id        = p.id " +
            "          AND pe.event_type      = 'return_received' " +
            "          AND pe.metadata->>'session_id' = ?::text " +
            "          AND pe.tenant_id       = ? " +
            "    ) AS processed " +
            "FROM pieces p " +
            "JOIN allocations a  ON a.piece_id      = p.id " +
            "                    AND a.status        IN ('active','packed') " +
            "JOIN order_items oi ON oi.id            = a.order_item_id " +
            "JOIN orders o       ON o.id             = oi.order_id " +
            "JOIN shipments s    ON s.order_id       = o.id " +
            "JOIN variants v     ON v.id             = p.variant_id " +
            "JOIN products pr    ON pr.id            = v.product_id " +
            "WHERE s.tracking_number = ? " +
            "  AND s.tenant_id       = ? " +
            "  AND p.status IN ('return_in_transit'::piece_status, 'delivered'::piece_status) " +
            "ORDER BY p.status DESC, p.last_event_at ASC",
            sessionId, tenantId, waybill, tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("waybillNumber", waybill);
        result.put("pieces", pieces);
        return result;
    }

    // ── Record piece verdict ──────────────────────────────────────────────────

    /**
     * Two-step atomic operation within one READ_COMMITTED transaction:
     *   1. Transition piece to return_pending_inspection (return_received event).
     *   2. Apply verdict: restock → available; damaged → damaged.
     *
     * For DELIVERED pieces the return-window guard fires before step 1.
     * RETURN_IN_TRANSIT pieces are not window-guarded (RTO is courier-initiated).
     * RETURN_PENDING_INSPECTION pieces (Bosta-lag) get only a return_received event
     * with session metadata, then the verdict is applied directly.
     *
     * ReturnService.restock() and markDamaged() participate in this transaction
     * via Spring REQUIRED propagation (their @Transactional joins the outer context).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> recordVerdict(UUID sessionId, String pieceId,
                                              String verdict, String reason,
                                              UUID locationId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);

        if (!"restock".equals(verdict) && !"damaged".equals(verdict)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "verdict must be 'restock' or 'damaged'");
        }
        if ("damaged".equals(verdict) && (reason == null || reason.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "reason is required when verdict is 'damaged'");
        }

        // Fetch piece + order/shipment context.
        Map<String, Object> piece = fetchPieceContext(pieceId, tenantId);
        PieceStatus current = PieceStatus.fromDb((String) piece.get("status"));
        UUID orderId    = (UUID) piece.get("orderId");
        UUID shipmentId = (UUID) piece.get("shipmentId");
        String metaSuffix = "\"session_id\":\"" + sessionId + "\"";

        switch (current) {
            case DELIVERED -> {
                enforceReturnWindow(pieceId, tenantId);
                String meta = "{\"return_kind\":\"customer_after_delivery\"," + metaSuffix + "}";
                ledger.transition(pieceId, PieceStatus.DELIVERED,
                        PieceStatus.RETURN_PENDING_INSPECTION, "return_received",
                        actorUserId,
                        new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
            }
            case RETURN_IN_TRANSIT -> {
                String meta = "{\"return_kind\":\"rto\"," + metaSuffix + "}";
                ledger.transition(pieceId, PieceStatus.RETURN_IN_TRANSIT,
                        PieceStatus.RETURN_PENDING_INSPECTION, "return_received",
                        actorUserId,
                        new TransitionContext(orderId, shipmentId, locationId, orderId, meta));
            }
            case RETURN_PENDING_INSPECTION -> {
                // Bosta state-46 already advanced the piece here before the session scan.
                String meta = "{\"return_kind\":\"rto\"," + metaSuffix + "}";
                ledger.recordReturnReceived(pieceId, locationId, actorUserId,
                        orderId, shipmentId, meta);
            }
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece " + pieceId + " is in status '" + current.db +
                "' and cannot be verdicted in a returns session");
        }

        // Apply verdict — both methods participate in this transaction via REQUIRED propagation.
        if ("restock".equals(verdict)) {
            returnService.restock(pieceId, locationId, actorUserId);
        } else {
            returnService.markDamaged(pieceId, reason, actorUserId);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pieceId",     pieceId);
        out.put("barcode",     piece.get("barcode"));
        out.put("finalStatus", "restock".equals(verdict) ? "available" : "damaged");
        out.put("returnKind",  current == PieceStatus.DELIVERED
                               ? "customer_after_delivery" : "rto");
        return out;
    }

    // ── Finalize session ──────────────────────────────────────────────────────

    /**
     * Marks the session finalized. Does NOT block on unresolved pieces.
     *
     * Change 2: unresolvedRtoCount counts un-scanned return_in_transit pieces only
     * (the actionable gap). deliveredKeptCount counts unscanned delivered pieces
     * (expected — customer kept the item). The UI must not treat the latter as a problem.
     */
    @Transactional
    public Map<String, Object> finalizeSession(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);

        String waybill = jdbc.queryForObject(
            "SELECT reference FROM receipts WHERE id = ? AND tenant_id = ?",
            String.class, sessionId, tenantId);

        // Count processed pieces in this session.
        Integer processedCount = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT pe.piece_id) FROM piece_events pe " +
            "WHERE pe.tenant_id = ? " +
            "  AND pe.event_type = 'return_received' " +
            "  AND pe.metadata->>'session_id' = ?::text",
            Integer.class, tenantId, sessionId);

        // Count remaining return_in_transit pieces (actionable — these are the stuck ones).
        Integer unresolvedRtoCount = jdbc.queryForObject(
            "SELECT COUNT(p.id) " +
            "FROM pieces p " +
            "JOIN allocations a  ON a.piece_id      = p.id " +
            "                    AND a.status        IN ('active','packed') " +
            "JOIN order_items oi ON oi.id            = a.order_item_id " +
            "JOIN orders o       ON o.id             = oi.order_id " +
            "JOIN shipments s    ON s.order_id       = o.id " +
            "WHERE s.tracking_number = ? AND s.tenant_id = ? " +
            "  AND p.status = 'return_in_transit'::piece_status",
            Integer.class, waybill, tenantId);

        // Count unscanned delivered pieces — normal (customer kept them).
        Integer deliveredKeptCount = jdbc.queryForObject(
            "SELECT COUNT(p.id) " +
            "FROM pieces p " +
            "JOIN allocations a  ON a.piece_id      = p.id " +
            "                    AND a.status        IN ('active','packed') " +
            "JOIN order_items oi ON oi.id            = a.order_item_id " +
            "JOIN orders o       ON o.id             = oi.order_id " +
            "JOIN shipments s    ON s.order_id       = o.id " +
            "WHERE s.tracking_number = ? AND s.tenant_id = ? " +
            "  AND p.status = 'delivered'::piece_status",
            Integer.class, waybill, tenantId);

        jdbc.update(
            "UPDATE receipts SET status = 'finalized', finalized_at = now() " +
            "WHERE id = ? AND tenant_id = ?",
            sessionId, tenantId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId",          sessionId);
        out.put("waybillNumber",      waybill);
        out.put("processedCount",     processedCount != null ? processedCount : 0);
        out.put("unresolvedRtoCount", unresolvedRtoCount != null ? unresolvedRtoCount : 0);
        out.put("deliveredKeptCount", deliveredKeptCount != null ? deliveredKeptCount : 0);
        out.put("finalizedAt",        Instant.now(clock));
        return out;
    }

    // ── Label reprint (Change 3: scoped to pieces in a return flow) ───────────

    /**
     * Validates the piece is in a return flow (return_pending_inspection or damaged),
     * then records a label_reprinted event. The controller calls LabelService for the PDF.
     *
     * Change 3: rejects reprint on arbitrary pieces not in a return flow.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> validateAndRecordReprint(String pieceId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        Map<String, Object> piece = fetchPieceContext(pieceId, tenantId);
        PieceStatus current = PieceStatus.fromDb((String) piece.get("status"));

        if (current != PieceStatus.RETURN_PENDING_INSPECTION && current != PieceStatus.DAMAGED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Label reprint in the returns flow is only available for pieces in " +
                "return_pending_inspection or damaged status (current: " + current.db + ")");
        }

        ledger.recordLabelReprinted(pieceId, actorUserId,
                (UUID) piece.get("locationId"),
                (UUID) piece.get("orderId"),
                (UUID) piece.get("shipmentId"));

        return Map.of("pieceId", pieceId, "barcode", piece.get("barcode"));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Throws 422 if piece's last_event_at is before the tenant's customer return window. */
    private void enforceReturnWindow(String pieceId, UUID tenantId) {
        int windowDays = jdbc.queryForObject(
            "SELECT customer_return_window_days FROM tenants WHERE id = ?",
            Integer.class, tenantId);

        Timestamp lastEventAt = jdbc.queryForObject(
            "SELECT last_event_at FROM pieces WHERE id = ? AND tenant_id = ?",
            Timestamp.class, pieceId, tenantId);

        Instant cutoff = clock.instant().minus(windowDays, ChronoUnit.DAYS);

        if (lastEventAt == null || lastEventAt.toInstant().isBefore(cutoff)) {
            long daysAgo = lastEventAt != null
                ? ChronoUnit.DAYS.between(lastEventAt.toInstant(), clock.instant())
                : 9999L;
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Piece was delivered " + daysAgo + " days ago, beyond the customer return " +
                "window of " + windowDays + " days. Use the waybill-less intake for " +
                "out-of-window returns.");
        }
    }

    private Map<String, Object> fetchPieceContext(String pieceId, UUID tenantId) {
        Map<String, Object> row = jdbc.query(
            "SELECT p.id, p.barcode, p.status::text AS status, " +
            "       p.current_order_id AS order_id, " +
            "       p.current_location_id AS location_id, " +
            "       s.id AS shipment_id " +
            "FROM pieces p " +
            "LEFT JOIN orders o      ON o.id  = p.current_order_id AND o.tenant_id = ? " +
            "LEFT JOIN shipments s   ON s.order_id = o.id AND s.tenant_id = ? " +
            "WHERE p.id = ? AND p.tenant_id = ?",
            rs -> {
                if (!rs.next()) return null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",         rs.getString("id"));
                m.put("barcode",    rs.getString("barcode"));
                m.put("status",     rs.getString("status"));
                m.put("orderId",    rs.getObject("order_id",    UUID.class));
                m.put("locationId", rs.getObject("location_id", UUID.class));
                m.put("shipmentId", rs.getObject("shipment_id", UUID.class));
                return m;
            },
            tenantId, tenantId, pieceId, tenantId);

        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found: " + pieceId);
        }
        return row;
    }

    private void requireOpen(UUID sessionId, UUID tenantId) {
        List<String> rows = jdbc.queryForList(
            "SELECT status FROM receipts WHERE id = ? AND tenant_id = ? AND kind = 'returns'",
            String.class, sessionId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Returns session not found");
        }
        if (!"open".equals(rows.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Session is already finalized");
        }
    }
}
