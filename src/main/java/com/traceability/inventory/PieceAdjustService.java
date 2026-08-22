package com.traceability.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-13: Manual piece adjustments.
 *
 * 13.1 / 13.3 — adjustPiece(): available→lost/damaged/destroyed and lost→available ("found it").
 * 13.2         — releaseForAdjust(): operator releases a reserved/packed piece from its order
 *                before adjusting. Uses the same transition+allocation-release paths as
 *                FulfillService.unscan() (reserved) and unpackPiece() (packed) — no new edges.
 */
@Service
public class PieceAdjustService {

    private static final Set<String> VALID_REASONS = Set.of(
        "cycle_count_missing", "damaged_in_storage", "sample_giveaway",
        "theft_suspected", "receiving_correction", "other"
    );

    private static final Set<String> ADJUST_TARGET_STATUSES = Set.of(
        "lost", "damaged", "destroyed", "available"
    );

    private static final Set<String> VOID_REASONS = Set.of(
        "receiving_overcount", "duplicate_entry", "other"
    );

    private static final Set<String> HOLD_REASONS = Set.of(
        "quality_check", "quarantine", "repair", "other"
    );

    private static final String FIND_PIECE_STATUS =
        "SELECT status::text, current_location_id FROM pieces WHERE id = ? AND tenant_id = ?";

    private static final String FIND_COMMITTED_ORDER =
        "SELECT o.id AS order_id, o.number AS order_number " +
        "FROM allocations a " +
        "JOIN order_items oi ON oi.id = a.order_item_id " +
        "JOIN orders o ON o.id = oi.order_id " +
        "WHERE a.piece_id = ? AND a.tenant_id = ? AND a.status IN ('active','packed') " +
        "LIMIT 1";

    private static final String FIND_ACTIVE_ALLOC =
        "SELECT a.id, a.status::text AS alloc_status, oi.order_id " +
        "FROM allocations a " +
        "JOIN order_items oi ON oi.id = a.order_item_id " +
        "WHERE a.piece_id = ? AND a.tenant_id = ? AND a.status IN ('active','packed') " +
        "LIMIT 1";

    private final JdbcTemplate   jdbc;
    private final InventoryLedger ledger;
    private final AuditService   auditService;
    private final ObjectMapper   mapper;
    private final ShopifyInventoryService shopifyInventory;

    public PieceAdjustService(JdbcTemplate jdbc, InventoryLedger ledger,
                               AuditService auditService, ObjectMapper mapper,
                               ShopifyInventoryService shopifyInventory) {
        this.jdbc         = jdbc;
        this.ledger       = ledger;
        this.auditService = auditService;
        this.mapper       = mapper;
        this.shopifyInventory = shopifyInventory;
    }

    /**
     * Adjust a piece's status: available→lost/damaged/destroyed or lost→available (found it).
     *
     * Guards:
     *  - reserved/packed pieces → 409 PIECE_COMMITTED (caller must release first via releaseForAdjust)
     *  - damaged/destroyed → available → 409 (terminal, cannot reverse)
     *  - reason=other without note → 400
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void adjustPiece(String pieceId, String toStatusStr, String reason,
                             String note, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        if (!ADJUST_TARGET_STATUSES.contains(toStatusStr)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "toStatus must be one of: lost, damaged, destroyed, available");
        }
        if (!VALID_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid reason: " + reason);
        }
        if ("other".equals(reason) && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "note is required when reason is 'other'");
        }

        record PieceRow(String status, UUID currentLocationId) {}
        PieceRow pieceRow = jdbc.query(FIND_PIECE_STATUS,
            rs -> rs.next() ? new PieceRow(rs.getString(1), rs.getObject(2, UUID.class)) : null,
            pieceId, tenantId);
        if (pieceRow == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        PieceStatus current = PieceStatus.fromDb(pieceRow.status());
        UUID currentLocationId = pieceRow.currentLocationId();

        if (current == PieceStatus.RESERVED || current == PieceStatus.PACKED) {
            Map<String, Object> order = jdbc.query(FIND_COMMITTED_ORDER,
                rs -> rs.next()
                    ? Map.of("orderId", rs.getObject("order_id"),
                             "orderNumber", rs.getString("order_number"))
                    : null,
                pieceId, tenantId);
            throw new PieceCommittedException(
                order != null ? (UUID) order.get("orderId") : null,
                order != null ? (String) order.get("orderNumber") : null);
        }

        // The reconcile-only ALLOWED edges (out_on_transfer:available/damaged/lost — see
        // InventoryLedger.ALLOWED) are legal ONLY through TransferService.reconcileScanBack()/
        // classifyShortfall(), which resolve the matching transfer_pieces row (outcome, line
        // counters) in the SAME transaction as the ledger transition. Reaching this method
        // with current == OUT_ON_TRANSFER would pass every other guard here and every ALLOWED
        // check, but silently leave that transfer_pieces row orphaned (outcome IS NULL
        // forever), permanently blocking closeTransfer() for that transfer. Reject before
        // transition() is ever called — no partial state to unwind.
        if (current == PieceStatus.OUT_ON_TRANSFER) {
            UUID blockingTransferId = jdbc.query(
                "SELECT transfer_id FROM transfer_pieces WHERE piece_id = ? AND tenant_id = ? AND outcome IS NULL LIMIT 1",
                rs -> rs.next() ? rs.getObject("transfer_id", UUID.class) : null,
                pieceId, tenantId);
            throw new PieceOutOnTransferException(blockingTransferId);
        }

        PieceStatus toStatus = PieceStatus.fromDb(toStatusStr);

        if (toStatus == PieceStatus.AVAILABLE
                && (current == PieceStatus.DAMAGED || current == PieceStatus.DESTROYED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece is in terminal status '" + current.db + "' — cannot reverse to available");
        }

        String metadata = buildMeta(reason, note);

        ledger.transition(pieceId, current, toStatus, "adjusted", actorUserId,
            new TransitionContext(null, null, null, null, metadata));

        // FR-17 v2 trigger 3: a currently-sellable piece damaged in the warehouse. Fires ONLY
        // from AVAILABLE — a piece already left the sellable pool once at hold_enter (FR-13.x),
        // so on_hold:damaged escalation (also legal per InventoryLedger.ALLOWED as of FR-13.x)
        // must NOT call this a second time, or on_hand would be double-decremented. Damaged
        // pieces reaching DAMAGED via return_pending_inspection go through ReturnService.
        // markDamaged() instead — a separate method with no call here.
        if (current == PieceStatus.AVAILABLE && toStatus == PieceStatus.DAMAGED) {
            shopifyInventory.onSellablePieceDamaged(tenantId, pieceId, currentLocationId);
        }

        if (toStatus == PieceStatus.DAMAGED) {
            jdbc.update("UPDATE pieces SET condition = 'damaged' WHERE id = ? AND tenant_id = ?",
                pieceId, tenantId);
        }

        Map<String, Object> auditMeta = new LinkedHashMap<>();
        auditMeta.put("from",   current.db);
        auditMeta.put("to",     toStatusStr);
        auditMeta.put("reason", reason);
        if (note != null && !note.isBlank()) auditMeta.put("note", note);
        auditService.record(actorUserId, "piece_adjust", "piece", pieceId, auditMeta);
    }

    /**
     * FR-13.x — Void: a receiving-overcount / duplicate-entry correction, NOT a loss. Source is
     * available ONLY (unlike adjustPiece(), which also accepts lost→available). Reserved/packed
     * → PieceCommittedException (release-first, same guard as adjustPiece()). out_on_transfer →
     * PieceOutOnTransferException, same reasoning as adjustPiece(). Any other non-available
     * status → 409. Terminal: no reverse edge exists (voided is a dead end in ALLOWED).
     *
     * Shopify: the decrement is conditional — see ShopifyInventoryService.processVoidCorrection()
     * — it only fires if the piece's originating receiving increment actually applied; otherwise
     * the on_hand count is already correct and the call is skipped (still recorded for audit).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void voidPiece(String pieceId, String reason, String note, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        if (!VOID_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "reason must be one of: receiving_overcount, duplicate_entry, other");
        }
        if ("other".equals(reason) && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "note is required when reason is 'other'");
        }

        record PieceRow(String status, UUID currentLocationId) {}
        PieceRow pieceRow = jdbc.query(FIND_PIECE_STATUS,
            rs -> rs.next() ? new PieceRow(rs.getString(1), rs.getObject(2, UUID.class)) : null,
            pieceId, tenantId);
        if (pieceRow == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        PieceStatus current = PieceStatus.fromDb(pieceRow.status());
        UUID currentLocationId = pieceRow.currentLocationId();

        if (current == PieceStatus.RESERVED || current == PieceStatus.PACKED) {
            Map<String, Object> order = jdbc.query(FIND_COMMITTED_ORDER,
                rs -> rs.next()
                    ? Map.of("orderId", rs.getObject("order_id"),
                             "orderNumber", rs.getString("order_number"))
                    : null,
                pieceId, tenantId);
            throw new PieceCommittedException(
                order != null ? (UUID) order.get("orderId") : null,
                order != null ? (String) order.get("orderNumber") : null);
        }

        if (current == PieceStatus.OUT_ON_TRANSFER) {
            UUID blockingTransferId = jdbc.query(
                "SELECT transfer_id FROM transfer_pieces WHERE piece_id = ? AND tenant_id = ? AND outcome IS NULL LIMIT 1",
                rs -> rs.next() ? rs.getObject("transfer_id", UUID.class) : null,
                pieceId, tenantId);
            throw new PieceOutOnTransferException(blockingTransferId);
        }

        if (current != PieceStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece must be available to void (current: " + current.db + ")");
        }

        String metadata = buildMeta(reason, note);

        ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.VOIDED, "voided", actorUserId,
            new TransitionContext(null, null, null, null, metadata));

        shopifyInventory.onPieceVoided(tenantId, pieceId, currentLocationId);

        Map<String, Object> auditMeta = new LinkedHashMap<>();
        auditMeta.put("from",   current.db);
        auditMeta.put("to",     "voided");
        auditMeta.put("reason", reason);
        if (note != null && !note.isBlank()) auditMeta.put("note", note);
        auditService.record(actorUserId, "piece_void", "piece", pieceId, auditMeta);
    }

    /**
     * FR-13.x — On Hold (enter): reversible QC/quarantine. Source is available ONLY —
     * reserved/packed → PieceCommittedException (release-first), out_on_transfer →
     * PieceOutOnTransferException, same guards as voidPiece()/adjustPiece().
     *
     * Generates a fresh holdEventId per call and stores it in the 'held' piece_events row's
     * metadata — unhold() reads it back to scope the Shopify hold_exit claim to the SAME hold
     * cycle (a piece can be held/released/held again; piece_id alone would collide with a
     * prior cycle's already-'applied' claim row).
     *
     * @return the generated hold event id (also embedded in the piece_events metadata)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID hold(String pieceId, String reason, String note, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        if (!HOLD_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "reason must be one of: quality_check, quarantine, repair, other");
        }
        if ("other".equals(reason) && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "note is required when reason is 'other'");
        }

        record PieceRow(String status, UUID currentLocationId) {}
        PieceRow pieceRow = jdbc.query(FIND_PIECE_STATUS,
            rs -> rs.next() ? new PieceRow(rs.getString(1), rs.getObject(2, UUID.class)) : null,
            pieceId, tenantId);
        if (pieceRow == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        PieceStatus current = PieceStatus.fromDb(pieceRow.status());
        UUID currentLocationId = pieceRow.currentLocationId();

        if (current == PieceStatus.RESERVED || current == PieceStatus.PACKED) {
            Map<String, Object> order = jdbc.query(FIND_COMMITTED_ORDER,
                rs -> rs.next()
                    ? Map.of("orderId", rs.getObject("order_id"),
                             "orderNumber", rs.getString("order_number"))
                    : null,
                pieceId, tenantId);
            throw new PieceCommittedException(
                order != null ? (UUID) order.get("orderId") : null,
                order != null ? (String) order.get("orderNumber") : null);
        }

        if (current == PieceStatus.OUT_ON_TRANSFER) {
            UUID blockingTransferId = jdbc.query(
                "SELECT transfer_id FROM transfer_pieces WHERE piece_id = ? AND tenant_id = ? AND outcome IS NULL LIMIT 1",
                rs -> rs.next() ? rs.getObject("transfer_id", UUID.class) : null,
                pieceId, tenantId);
            throw new PieceOutOnTransferException(blockingTransferId);
        }

        if (current != PieceStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece must be available to hold (current: " + current.db + ")");
        }

        UUID holdEventId = UUID.randomUUID();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        if (note != null && !note.isBlank()) m.put("note", note);
        m.put("hold_event_id", holdEventId.toString());
        String metadata;
        try {
            metadata = mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize metadata", e);
        }

        ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.ON_HOLD, "held", actorUserId,
            new TransitionContext(null, null, null, null, metadata));

        shopifyInventory.onHoldEnter(tenantId, pieceId, currentLocationId, holdEventId);

        Map<String, Object> auditMeta = new LinkedHashMap<>();
        auditMeta.put("from",        current.db);
        auditMeta.put("to",          "on_hold");
        auditMeta.put("reason",      reason);
        auditMeta.put("holdEventId", holdEventId.toString());
        if (note != null && !note.isBlank()) auditMeta.put("note", note);
        auditService.record(actorUserId, "piece_hold", "piece", pieceId, auditMeta);

        return holdEventId;
    }

    /**
     * FR-13.x — On Hold (exit): on_hold → available. Reads back the hold_event_id from the most
     * recent 'held' piece_events row landing on on_hold for this piece, so the Shopify hold_exit
     * claim is scoped to the SAME cycle hold() opened — see hold()'s javadoc.
     *
     * Shopify: +1 via the EXISTING positive/increment path (ShopifyInventoryService.onHoldExit,
     * which reuses applyIncrementAdjustment) — NOT the negative-delta gateway. This is an
     * increment, not part of the named decrement set.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unhold(String pieceId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        record PieceRow(String status, UUID currentLocationId) {}
        PieceRow pieceRow = jdbc.query(FIND_PIECE_STATUS,
            rs -> rs.next() ? new PieceRow(rs.getString(1), rs.getObject(2, UUID.class)) : null,
            pieceId, tenantId);
        if (pieceRow == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        PieceStatus current = PieceStatus.fromDb(pieceRow.status());
        if (current != PieceStatus.ON_HOLD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece must be on_hold to unhold (current: " + current.db + ")");
        }
        UUID currentLocationId = pieceRow.currentLocationId();

        String holdEventIdRaw = jdbc.query(
            "SELECT metadata->>'hold_event_id' FROM piece_events " +
            "WHERE piece_id = ? AND tenant_id = ? AND event_type = 'held' " +
            "  AND to_status = 'on_hold'::piece_status " +
            "ORDER BY occurred_at DESC, id DESC LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null,
            pieceId, tenantId);
        if (holdEventIdRaw == null) {
            throw new IllegalStateException(
                "on_hold piece " + pieceId + " has no matching 'held' event with hold_event_id");
        }
        UUID holdEventId = UUID.fromString(holdEventIdRaw);

        ledger.transition(pieceId, PieceStatus.ON_HOLD, PieceStatus.AVAILABLE, "unheld", actorUserId,
            new TransitionContext(null, null, null, null, null));

        shopifyInventory.onHoldExit(tenantId, pieceId, currentLocationId, holdEventId);

        auditService.record(actorUserId, "piece_unhold", "piece", pieceId,
            Map.of("holdEventId", holdEventId.toString()));
    }

    /**
     * Release a reserved/packed piece from its order so it can subsequently be adjusted.
     *
     * Uses the same transition+allocation-release steps as FulfillService.unscan()
     * (active/reserved) and FulfillService.unpackPiece() (packed), but without the
     * order-level guards those methods enforce (lock ownership, cancel_requested_at).
     * Emits "unreserved" or "unpacked" event to maintain phraseKey consistency.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseForAdjust(String pieceId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        List<Map<String, Object>> rows = jdbc.queryForList(FIND_ACTIVE_ALLOC, pieceId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece has no active or packed allocation — nothing to release");
        }

        Map<String, Object> alloc = rows.get(0);
        UUID   allocId     = (UUID) alloc.get("id");
        UUID   orderId     = (UUID) alloc.get("order_id");
        String allocStatus = (String) alloc.get("alloc_status");

        boolean isActive = "active".equals(allocStatus);
        PieceStatus from      = isActive ? PieceStatus.RESERVED : PieceStatus.PACKED;
        String      eventType = isActive ? "unreserved"          : "unpacked";

        ledger.transition(pieceId, from, PieceStatus.AVAILABLE, eventType, actorUserId,
            new TransitionContext(orderId, null, null, null, null));

        jdbc.update("UPDATE allocations SET status = 'released' WHERE id = ?", allocId);

        auditService.record(actorUserId, "piece_release_for_adjust", "piece", pieceId,
            Map.of("from", from.db, "orderId", orderId.toString()));
    }

    private String buildMeta(String reason, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        if (note != null && !note.isBlank()) m.put("note", note);
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize metadata", e);
        }
    }
}
