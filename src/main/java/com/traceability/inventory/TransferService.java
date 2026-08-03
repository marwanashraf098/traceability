package com.traceability.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-22 — Transfers & External Custody.
 *
 * createTransfer() opens a transfer against a saved external destination location.
 *
 * scanOut() is the send-out scan: piece lookup → WRONG_STATUS check → CLAIM (plain
 * jdbc.update INSERT into transfer_pieces; transfer_pieces_one_active is the race
 * referee) → InventoryLedger.transition() → explicit current_location_id update → line
 * qty_out increment. Same error codes as FulfillService.scan() (PIECE_NOT_FOUND,
 * WRONG_STATUS) plus a transfer-specific ALREADY_ON_TRANSFER for the race.
 *
 * Claim-before-transition, not transition-as-the-race-guard: FulfillService.scan() calls
 * ledger.transition() first and catches StateConflictException to return a clean
 * rejection — but InventoryLedger.transition() is a SEPARATE @Transactional-proxied bean.
 * When it throws while participating in scanOut()'s own @Transactional (REQUIRED
 * propagation joins the same physical transaction), Spring's AbstractPlatformTransactionManager
 * marks the whole (non-owned, participating) transaction rollback-only before rethrowing —
 * catching the exception inside scanOut() does NOT undo that marking. scanOut() then
 * returns normally, but its own commit fails with UnexpectedRollbackException. Verified by
 * reproducing the identical failure against FulfillService.scan()'s own established
 * try/catch pattern (Day9Test's scan-race test), not just theorized.
 *
 * The fix: make the race-deciding step a PLAIN JdbcTemplate call inside scanOut()'s own
 * method body (no nested proxy boundary), not a call into another bean's @Transactional
 * method. The transfer_pieces INSERT's unique-index violation (DuplicateKeyException) is
 * exactly that — catching it here poisons nothing, because no separate transactional
 * interceptor was ever involved. transition() is then called only by the claim's winner,
 * where — given WRONG_STATUS already passed and the claim just uniquely succeeded —
 * StateConflictException is not expected to fire; it is deliberately left UNCAUGHT so
 * that if it ever does (a genuinely different, unrelated concurrent mutation), the whole
 * transaction rolls back cleanly rather than leaving an inconsistent claim behind.
 */
@Service
public class TransferService {

    private final JdbcTemplate    jdbc;
    private final InventoryLedger ledger;
    private final ObjectMapper    mapper;

    public TransferService(JdbcTemplate jdbc, InventoryLedger ledger, ObjectMapper mapper) {
        this.jdbc   = jdbc;
        this.ledger = ledger;
        this.mapper = mapper;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID createTransfer(String transferType, UUID destinationLocationId,
                               Instant expectedReturnAt, String note, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO transfers " +
            "(id, tenant_id, transfer_type, destination_location_id, note, expected_return_at, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, tenantId, transferType, destinationLocationId, note,
            expectedReturnAt != null ? Timestamp.from(expectedReturnAt) : null, actorUserId);
        return id;
    }

    // ── Send-out scan ────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanOutResult scanOut(UUID transferId, String barcode, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // 1. Transfer must exist (tenant-scoped via RLS + explicit predicate) and be open.
        List<Map<String, Object>> transferRows = jdbc.queryForList(
            "SELECT status, destination_location_id FROM transfers WHERE id = ? AND tenant_id = ?",
            transferId, tenantId);
        if (transferRows.isEmpty()) {
            return ScanOutResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found");
        }
        Map<String, Object> transfer = transferRows.get(0);
        String transferStatus = (String) transfer.get("status");
        if (!"open".equals(transferStatus)) {
            return ScanOutResult.rejected("TRANSFER_NOT_OPEN",
                "Transfer is not open for send-out (status: " + transferStatus + ")");
        }
        UUID destinationLocationId = (UUID) transfer.get("destination_location_id");

        // 2. Look up piece by barcode — same triple-match as FulfillService.scan()
        //    (new-format raw ULID, or old-format "PC-<ULID>", or short_code).
        List<Map<String, Object>> pieceRows = jdbc.queryForList(
            "SELECT p.id, p.variant_id, p.status FROM pieces p " +
            "WHERE (p.barcode = ? OR p.id = ? OR p.short_code = ?) AND p.tenant_id = ?",
            barcode, barcode, barcode, tenantId);
        if (pieceRows.isEmpty()) {
            return ScanOutResult.rejected("PIECE_NOT_FOUND", "Barcode not found in inventory");
        }
        Map<String, Object> piece   = pieceRows.get(0);
        String pieceId   = (String) piece.get("id");
        UUID   variantId = (UUID)   piece.get("variant_id");
        String status    = (String) piece.get("status");

        // 3. WRONG_STATUS: piece must be available.
        if (!"available".equals(status)) {
            return ScanOutResult.rejected("WRONG_STATUS", "Piece is not available (status: " + status + ")");
        }

        // 4. Ensure the line row exists (create-if-absent, qty_out untouched — only bumped
        //    once the claim + transition below both succeed). ON CONFLICT DO UPDATE with a
        //    harmless self-assignment is the standard get-or-create-and-RETURNING idiom.
        UUID lineId = jdbc.query(
            "INSERT INTO transfer_lines (id, tenant_id, transfer_id, variant_id) " +
            "VALUES (gen_random_uuid(), ?, ?, ?) " +
            "ON CONFLICT (transfer_id, variant_id) DO UPDATE SET transfer_id = EXCLUDED.transfer_id " +
            "RETURNING id",
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            tenantId, transferId, variantId);

        // 5. CLAIM — the race referee. A plain JdbcTemplate call in this same method body,
        //    not a call into another @Transactional bean (see class javadoc for why that
        //    distinction matters). transfer_pieces_one_active rejects a concurrent loser
        //    with a unique-constraint violation; nothing else has been mutated yet.
        try {
            jdbc.update(
                "INSERT INTO transfer_pieces (id, tenant_id, transfer_id, line_id, piece_id) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?)",
                tenantId, transferId, lineId, pieceId);
        } catch (DuplicateKeyException e) {
            return ScanOutResult.rejected("ALREADY_ON_TRANSFER", "Piece was claimed by a concurrent scan");
        }

        // 6. Transition available → out_on_transfer. The claim above already uniquely
        //    won this piece, so StateConflictException is not expected here; left
        //    uncaught deliberately — if it ever fires (an unrelated concurrent mutation),
        //    the whole transaction rolls back, undoing the claim rather than leaving it
        //    orphaned.
        ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.OUT_ON_TRANSFER,
            "transferred_out", actorUserId,
            new TransitionContext(null, null, destinationLocationId, null, transferMeta(transferId)));

        // 7. Explicit location update — transition() does not touch current_location_id
        //    (same pattern as ReturnService).
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?",
            destinationLocationId, pieceId);

        // 8. Now that the piece has genuinely moved, bump the line's running total.
        int qtyOut = jdbc.queryForObject(
            "UPDATE transfer_lines SET qty_out = qty_out + 1 WHERE id = ? RETURNING qty_out",
            Integer.class, lineId);

        return ScanOutResult.success(pieceId, barcode, variantId, lineId, qtyOut);
    }

    private String transferMeta(UUID transferId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize metadata", e);
        }
    }

    // ── Result shape ─────────────────────────────────────────────────────────

    public record ScanOutResult(
            boolean success,
            String  code,
            String  message,
            String  pieceId,
            String  barcode,
            UUID    variantId,
            UUID    lineId,
            int     qtyOut) {

        static ScanOutResult success(String pieceId, String barcode, UUID variantId, UUID lineId, int qtyOut) {
            return new ScanOutResult(true, "SCANNED", null, pieceId, barcode, variantId, lineId, qtyOut);
        }

        static ScanOutResult rejected(String code, String message) {
            return new ScanOutResult(false, code, message, null, null, null, null, 0);
        }
    }
}
