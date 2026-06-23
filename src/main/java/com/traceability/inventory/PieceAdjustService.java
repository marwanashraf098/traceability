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

    private static final String FIND_PIECE_STATUS =
        "SELECT status::text FROM pieces WHERE id = ? AND tenant_id = ?";

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

    public PieceAdjustService(JdbcTemplate jdbc, InventoryLedger ledger,
                               AuditService auditService, ObjectMapper mapper) {
        this.jdbc         = jdbc;
        this.ledger       = ledger;
        this.auditService = auditService;
        this.mapper       = mapper;
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

        String currentDb = jdbc.query(FIND_PIECE_STATUS,
            rs -> rs.next() ? rs.getString(1) : null, pieceId, tenantId);
        if (currentDb == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }
        PieceStatus current = PieceStatus.fromDb(currentDb);

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

        PieceStatus toStatus = PieceStatus.fromDb(toStatusStr);

        if (toStatus == PieceStatus.AVAILABLE
                && (current == PieceStatus.DAMAGED || current == PieceStatus.DESTROYED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Piece is in terminal status '" + currentDb + "' — cannot reverse to available");
        }

        String metadata = buildMeta(reason, note);

        ledger.transition(pieceId, current, toStatus, "adjusted", actorUserId,
            new TransitionContext(null, null, null, null, metadata));

        Map<String, Object> auditMeta = new LinkedHashMap<>();
        auditMeta.put("from",   currentDb);
        auditMeta.put("to",     toStatusStr);
        auditMeta.put("reason", reason);
        if (note != null && !note.isBlank()) auditMeta.put("note", note);
        auditService.record(actorUserId, "piece_adjust", "piece", pieceId, auditMeta);
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
