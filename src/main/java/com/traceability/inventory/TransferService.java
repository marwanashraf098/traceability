package com.traceability.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final LabelService    labelService;

    public TransferService(JdbcTemplate jdbc, InventoryLedger ledger, ObjectMapper mapper,
                           LabelService labelService) {
        this.jdbc         = jdbc;
        this.ledger       = ledger;
        this.mapper       = mapper;
        this.labelService = labelService;
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
            "SELECT status, destination_location_id, transfer_type FROM transfers WHERE id = ? AND tenant_id = ?",
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
        UUID   destinationLocationId = (UUID)   transfer.get("destination_location_id");
        String transferType          = (String) transfer.get("transfer_type");

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
            // No further DB access on this path: Postgres aborts the transaction on the
            // violation above, and COMMIT on an aborted transaction is a silent, no-error
            // implicit ROLLBACK — any statement issued here first would fail instead.
            return ScanOutResult.rejected("ALREADY_ON_TRANSFER", "Piece was claimed by a concurrent scan");
        }

        // 6. Transition available → out_on_transfer. The claim above already uniquely
        //    won this piece, so StateConflictException is not expected here; left
        //    uncaught deliberately — if it ever fires (an unrelated concurrent mutation),
        //    the whole transaction rolls back, undoing the claim rather than leaving it
        //    orphaned.
        ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.OUT_ON_TRANSFER,
            "transferred_out", actorUserId,
            new TransitionContext(null, null, destinationLocationId, null, transferMeta(transferId, transferType)));

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

    private String transferMeta(UUID transferId, String transferType) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        // "reason" the transition happened — the transfer's category (showroom/dryclean/
        // repair/other) is the only always-present, short, categorical field available;
        // the transfer's freeform note is not duplicated here (it's on the transfer row).
        m.put("reason", transferType);
        return writeJson(m);
    }

    // ── Reconcile: begin ─────────────────────────────────────────────────────

    /**
     * open → reconciling. Role gate (MANAGER/OWNER) is enforced at the controller
     * (FR-22.6) — not here, matching every other role-gated action in this codebase
     * (@PreAuthorize on the endpoint, never inside the service layer).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void beginReconcile(UUID transferId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // Atomic conditional UPDATE doubles as its own race guard (two concurrent
        // beginReconcile calls on the same transfer → exactly one wins), same pattern
        // as InventoryLedger.transition()'s race-guard UPDATE.
        int rows = jdbc.update(
            "UPDATE transfers SET status = 'reconciling' WHERE id = ? AND tenant_id = ? AND status = 'open'",
            transferId, tenantId);
        if (rows == 0) {
            List<String> statusRows = jdbc.queryForList(
                "SELECT status FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
            if (statusRows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found");
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Transfer is not open (status: " + statusRows.get(0) + ")");
        }
    }

    // ── Reconcile: scan back ─────────────────────────────────────────────────

    private static final Set<String> VALID_CONDITIONS = Set.of("good", "condemned");

    /**
     * Scan a physically-returned unit. condition = "good" -> available (verified=true,
     * reconciliation=scan); condition = "condemned" -> damaged (verified=true,
     * reconciliation=scan, attributed_to=vendor). Both are physical returns (this method
     * only fires on a scan, so the unit is, by definition, back in hand) — current_location_id
     * moves to the tenant's fulfillment location either way; a condemned-but-not-returned
     * unit never reaches this method, it goes through classifyShortfall's quantity path
     * instead, and that path leaves current_location_id untouched (never physically arrived).
     *
     * Same claim-before-transition shape as scanOut() (FR-22.3) and for the identical
     * reason: the plain JdbcTemplate UPDATE ... WHERE outcome IS NULL is the race referee
     * (0 rows covers both "never outstanding on this transfer" and "a concurrent resolve
     * just won" — both are the same clean rejection to the caller), so transition() never
     * needs its exception caught inside this @Transactional method.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanBackResult reconcileScanBack(UUID transferId, String barcode, String condition, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        if (!VALID_CONDITIONS.contains(condition)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "condition must be 'good' or 'condemned'");
        }

        // 1. Transfer must exist and be reconciling.
        List<String> transferStatusRows = jdbc.queryForList(
            "SELECT status FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        if (transferStatusRows.isEmpty()) {
            return ScanBackResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found");
        }
        if (!"reconciling".equals(transferStatusRows.get(0))) {
            return ScanBackResult.rejected("TRANSFER_NOT_RECONCILING",
                "Transfer is not in reconciling status (status: " + transferStatusRows.get(0) + ")");
        }

        // 2. Piece lookup — same triple-match as scanOut().
        List<Map<String, Object>> pieceRows = jdbc.queryForList(
            "SELECT p.id, p.variant_id FROM pieces p " +
            "WHERE (p.barcode = ? OR p.id = ? OR p.short_code = ?) AND p.tenant_id = ?",
            barcode, barcode, barcode, tenantId);
        if (pieceRows.isEmpty()) {
            return ScanBackResult.rejected("PIECE_NOT_FOUND", "Barcode not found in inventory");
        }
        String pieceId   = (String) pieceRows.get(0).get("id");
        UUID   variantId = (UUID)   pieceRows.get(0).get("variant_id");

        boolean good = "good".equals(condition);
        String outcome = good ? "returned_good" : "condemned";
        PieceStatus targetStatus = good ? PieceStatus.AVAILABLE : PieceStatus.DAMAGED;

        // 3. CLAIM — resolve the outstanding transfer_pieces row for THIS transfer+piece.
        int resolved = jdbc.update(
            "UPDATE transfer_pieces SET outcome = ?, outcome_verified = true, outcome_at = now(), outcome_by = ? " +
            "WHERE transfer_id = ? AND piece_id = ? AND outcome IS NULL",
            outcome, actorUserId, transferId, pieceId);
        if (resolved == 0) {
            return ScanBackResult.rejected("NOT_OUTSTANDING_ON_TRANSFER",
                "Piece is not outstanding on this transfer");
        }

        // 4. Transition. Left uncaught by design (see scanOut()) — the claim above already
        //    uniquely won resolution of this piece on this transfer.
        UUID fulfillmentLocationId = fulfillmentLocationId(tenantId);
        ledger.transition(pieceId, PieceStatus.OUT_ON_TRANSFER, targetStatus,
            good ? "returned_from_transfer" : "condemned_at_vendor", actorUserId,
            new TransitionContext(null, null, fulfillmentLocationId, null, reconcileScanMeta(good, transferId)));

        // 5. Physical unit is back at the warehouse either way — move current_location_id.
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?",
            fulfillmentLocationId, pieceId);

        // 6. Bump the matching line counter.
        if (good) {
            jdbc.update("UPDATE transfer_lines SET qty_returned_good = qty_returned_good + 1 " +
                "WHERE transfer_id = ? AND variant_id = ?", transferId, variantId);
        } else {
            jdbc.update("UPDATE transfer_lines SET qty_condemned = qty_condemned + 1 " +
                "WHERE transfer_id = ? AND variant_id = ?", transferId, variantId);
        }

        return ScanBackResult.success(pieceId, barcode, variantId, outcome);
    }

    /** Exactly one is_fulfillment=true row per tenant, guaranteed by V61's partial unique index. */
    private UUID fulfillmentLocationId(UUID tenantId) {
        return jdbc.queryForObject(
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true", UUID.class, tenantId);
    }

    private String reconcileScanMeta(boolean good, UUID transferId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        m.put("verified", true);
        m.put("reconciliation", "scan");
        if (!good) m.put("attributed_to", "vendor");
        return writeJson(m);
    }

    // ── Reconcile: classify shortfall ────────────────────────────────────────

    /**
     * FIFO-pick (oldest transferred-out first, transfer_pieces.created_at) that many
     * still-outstanding pieces on the line and close each to its terminal — verified=false,
     * reconciliation=quantity_based, attributed_to=vendor (spec's "quantity-asserted,
     * vendor-attributed" shortfall). Balance enforced: the requested total must not exceed
     * qty_out minus what's already resolved on the line.
     *
     * The FIFO SELECT ... FOR UPDATE locks the exact N rows this call will resolve, for the
     * duration of this transaction — no separate claim-before-transition step is needed here
     * (unlike scanOut()/reconcileScanBack(), a concurrent second classifyShortfall call on
     * the same line would block on the row lock, not race transition() concurrently), so
     * transition() is called directly per piece, still left uncaught on failure by the same
     * design as every other call site in this class.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void classifyShortfall(UUID transferId, UUID lineId, ShortfallCounts counts, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        if (counts.sold() < 0 || counts.lost() < 0 || counts.condemnedNotReturned() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "counts must be non-negative");
        }
        int totalRequested = counts.sold() + counts.lost() + counts.condemnedNotReturned();
        if (totalRequested == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one count must be positive");
        }

        List<String> transferStatusRows = jdbc.queryForList(
            "SELECT status FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        if (transferStatusRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found");
        }
        if (!"reconciling".equals(transferStatusRows.get(0))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Transfer is not in reconciling status (status: " + transferStatusRows.get(0) + ")");
        }

        Map<String, Object> line = jdbc.query(
            "SELECT qty_out, qty_returned_good, qty_condemned, qty_sold, qty_lost " +
            "FROM transfer_lines WHERE id = ? AND transfer_id = ? AND tenant_id = ?",
            rs -> rs.next() ? Map.of(
                "qty_out",           rs.getInt("qty_out"),
                "qty_returned_good", rs.getInt("qty_returned_good"),
                "qty_condemned",     rs.getInt("qty_condemned"),
                "qty_sold",          rs.getInt("qty_sold"),
                "qty_lost",          rs.getInt("qty_lost")
            ) : null,
            lineId, transferId, tenantId);
        if (line == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer line not found");
        }

        int remainingOutstanding = (Integer) line.get("qty_out")
            - (Integer) line.get("qty_returned_good")
            - (Integer) line.get("qty_condemned")
            - (Integer) line.get("qty_sold")
            - (Integer) line.get("qty_lost");
        if (totalRequested > remainingOutstanding) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Requested " + totalRequested + " exceeds remaining outstanding (" +
                remainingOutstanding + ") on this line");
        }

        // FIFO claim: lock the exact pieces this call will resolve.
        List<String> pieceIds = jdbc.query(
            "SELECT piece_id FROM transfer_pieces " +
            "WHERE transfer_id = ? AND line_id = ? AND tenant_id = ? AND outcome IS NULL " +
            "ORDER BY created_at ASC LIMIT ? FOR UPDATE",
            (rs, rowNum) -> rs.getString("piece_id"),
            transferId, lineId, tenantId, totalRequested);

        if (pieceIds.size() < totalRequested) {
            // Shouldn't happen given the balance check above (same transaction — no
            // concurrent writer could have shrunk the outstanding set since the counts
            // were read) — defensive, not expected to fire.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Fewer outstanding pieces than requested — reload and retry");
        }

        int idx = 0;
        for (int i = 0; i < counts.sold(); i++) {
            // A sale is not a vendor loss — omit attributed_to so it never pollutes the
            // Phase-3 vendor-loss report (unlike lost/condemned_not_returned below).
            closeShortfallPiece(pieceIds.get(idx++), transferId, lineId,
                "sold", PieceStatus.SOLD, "sold_offbook", actorUserId, false);
        }
        for (int i = 0; i < counts.lost(); i++) {
            closeShortfallPiece(pieceIds.get(idx++), transferId, lineId,
                "lost", PieceStatus.LOST, "lost_at_vendor", actorUserId, true);
        }
        for (int i = 0; i < counts.condemnedNotReturned(); i++) {
            closeShortfallPiece(pieceIds.get(idx++), transferId, lineId,
                "condemned", PieceStatus.DAMAGED, "condemned_at_vendor", actorUserId, true);
        }

        jdbc.update(
            "UPDATE transfer_lines SET qty_sold = qty_sold + ?, qty_lost = qty_lost + ?, " +
            "qty_condemned = qty_condemned + ? WHERE id = ?",
            counts.sold(), counts.lost(), counts.condemnedNotReturned(), lineId);
    }

    private void closeShortfallPiece(String pieceId, UUID transferId, UUID lineId,
                                     String outcome, PieceStatus target, String eventType,
                                     UUID actorUserId, boolean vendorAttributed) {
        jdbc.update(
            "UPDATE transfer_pieces SET outcome = ?, outcome_verified = false, outcome_at = now(), outcome_by = ? " +
            "WHERE transfer_id = ? AND line_id = ? AND piece_id = ? AND outcome IS NULL",
            outcome, actorUserId, transferId, lineId, pieceId);

        // Left uncaught by design (see class javadoc) — the FOR UPDATE lock in
        // classifyShortfall() already gives this call exclusive access to the piece row
        // for the duration of this transaction.
        ledger.transition(pieceId, PieceStatus.OUT_ON_TRANSFER, target, eventType, actorUserId,
            new TransitionContext(null, null, null, null, shortfallMeta(transferId, vendorAttributed)));
    }

    private String shortfallMeta(UUID transferId, boolean vendorAttributed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        m.put("verified", false);
        m.put("reconciliation", "quantity_based");
        // Sold is an off-book sale, not a vendor loss — attributed_to is omitted for it
        // (see the "sold" call site in classifyShortfall) so it never pollutes the Phase-3
        // vendor-loss report; lost/condemned_not_returned are genuine vendor-attributed loss.
        if (vendorAttributed) m.put("attributed_to", "vendor");
        return writeJson(m);
    }

    // ── Reconcile: close ─────────────────────────────────────────────────────

    /**
     * reconciling → closed. Rejects if any transfer_pieces row for this transfer still has
     * outcome IS NULL — the authoritative check (not the derived qty_* counters): zero
     * outstanding rows implies, by construction, qty_out == returned_good + condemned +
     * sold + lost on every line (every mutation path increments its counter in the same
     * transaction it resolves the row). One query, one transaction — no half-closed lines.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void closeTransfer(UUID transferId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        List<String> transferStatusRows = jdbc.queryForList(
            "SELECT status FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        if (transferStatusRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found");
        }
        if (!"reconciling".equals(transferStatusRows.get(0))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Transfer is not in reconciling status (status: " + transferStatusRows.get(0) + ")");
        }

        Integer outstanding = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND tenant_id = ? AND outcome IS NULL",
            Integer.class, transferId, tenantId);
        if (outstanding != null && outstanding > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                outstanding + " piece(s) still outstanding on this transfer — resolve before closing");
        }

        int rows = jdbc.update(
            "UPDATE transfers SET status = 'closed', closed_by = ?, closed_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'reconciling'",
            actorUserId, transferId, tenantId);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Transfer status changed concurrently — reload and retry");
        }
    }

    // ── Reprint outstanding labels ───────────────────────────────────────────

    /**
     * Reprints the barcodes of every piece still outstanding on this transfer (transfer_pieces
     * WHERE outcome IS NULL) — the physical relabel step (identity is lost at the vendor;
     * this reprints the SAME piece IDs, it never mints new ones). Deliberately does NOT reuse
     * ReturnSessionController's per-piece reprint endpoint: that one is hard-gated to pieces in
     * return_pending_inspection/damaged status and would reject an out_on_transfer piece
     * outright. Loops LabelService.generatePieceLabel() (one page per piece) merged into a
     * single multi-page PDF, and InventoryLedger.recordLabelReprinted() (the existing
     * no-status-change event writer — 4th piece_events write path, already established) per
     * piece, all under one @Transactional so all N reprint events are written under the same
     * tenant GUC in one transaction.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public byte[] reprintOutstandingLabels(UUID transferId, UUID actorUserId) throws IOException {
        UUID tenantId = TenantContext.require();

        List<String> transferStatusRows = jdbc.queryForList(
            "SELECT status FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        if (transferStatusRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found");
        }

        List<String> outstandingPieceIds = jdbc.queryForList(
            "SELECT piece_id FROM transfer_pieces WHERE transfer_id = ? AND tenant_id = ? AND outcome IS NULL " +
            "ORDER BY created_at ASC",
            String.class, transferId, tenantId);
        if (outstandingPieceIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No outstanding pieces on this transfer");
        }

        try (PDDocument merged = new PDDocument()) {
            for (String pieceId : outstandingPieceIds) {
                byte[] pagePdf = labelService.generatePieceLabel(pieceId, null, null);
                try (PDDocument single = Loader.loadPDF(pagePdf)) {
                    for (PDPage page : single.getPages()) {
                        merged.importPage(page);
                    }
                }
                ledger.recordLabelReprinted(pieceId, actorUserId, null, null, null);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            merged.save(out);
            return out.toByteArray();
        }
    }

    private String writeJson(Map<String, Object> m) {
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize metadata", e);
        }
    }

    // ── Result shapes ────────────────────────────────────────────────────────

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

    public record ScanBackResult(
            boolean success,
            String  code,
            String  message,
            String  pieceId,
            String  barcode,
            UUID    variantId,
            String  outcome) {

        static ScanBackResult success(String pieceId, String barcode, UUID variantId, String outcome) {
            return new ScanBackResult(true, "SCANNED", null, pieceId, barcode, variantId, outcome);
        }

        static ScanBackResult rejected(String code, String message) {
            return new ScanBackResult(false, code, message, null, null, null, null);
        }
    }

    /** Shortfall quantities to classify on one transfer_lines row (classifyShortfall). */
    public record ShortfallCounts(int sold, int lost, int condemnedNotReturned) {}
}
