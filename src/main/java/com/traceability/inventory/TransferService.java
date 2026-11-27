package com.traceability.inventory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *
 * Transfer-row locking (V118 lifecycle). Every scan-out reads its transfer row FOR SHARE;
 * every status change (markSent, cancel, beginReconcile, closeOneWay) locks it FOR UPDATE
 * first and only then re-reads transfer_pieces in a NEW statement. Under READ COMMITTED that
 * statement sees any scan that committed while we waited for the lock, so a concurrent scan
 * either lands before the status change (and the re-check sees its piece) or waits and then
 * sees the new status and is rejected. Without this a scan could insert a piece after a
 * cancel or one-way close committed, leaving it out_on_transfer on a finished transfer with
 * outcome NULL forever. A single UPDATE ... AND NOT EXISTS (...) is NOT equivalent: its
 * re-check after a lock wait does not see rows committed by the other transaction.
 */
@Service
public class TransferService {

    private final JdbcTemplate    jdbc;
    private final InventoryLedger ledger;
    private final ObjectMapper    mapper;
    private final LabelService    labelService;
    /** Issue 2: the send-time Shopify decrement (claim in the send transaction). */
    private final TransferShopifySync shopifySync;
    /** Issue 2: the +1 when a piece comes back to the main warehouse. */
    private final ShopifyInventoryService shopifyInventory;

    public TransferService(JdbcTemplate jdbc, InventoryLedger ledger, ObjectMapper mapper,
                           LabelService labelService, TransferShopifySync shopifySync,
                           ShopifyInventoryService shopifyInventory) {
        this.jdbc         = jdbc;
        this.ledger       = ledger;
        this.mapper       = mapper;
        this.labelService = labelService;
        this.shopifySync  = shopifySync;
        this.shopifyInventory = shopifyInventory;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /** Bazar POS B1 (V231): the transfer linked to a bazar event. Created and driven ONLY by the bazar service
     *  (the *ForBazar methods below; BazarTransferLockTest guards their one caller) — every generic Transfers
     *  action refuses it with BAZAR_TRANSFER_LOCKED. */
    public static final String BAZAR_TYPE = "bazar";

    private static final Set<String> VALID_TRANSFER_TYPES =
        Set.of("showroom", "dryclean", "repair", "other", BAZAR_TYPE);

    private static final Set<String> VALID_TRANSFER_MODES =
        Set.of("round_trip", "relocate_out", "relocate_return");

    /** Modes that come back through reconcile: preparing → sent → reconciling → closed.
     *  relocate_out instead goes preparing → closed through closeOneWay(). */
    private static final Set<String> RETURNING_MODES = Set.of("round_trip", "relocate_return");

    /** Shown for every wrong-status / wrong-mode refusal of the lifecycle actions: the screen
     *  only offers an action in the right state, so reaching one means the page is stale. */
    private static final String GENERIC_UNAVAILABLE_EN =
        "This action isn't available for this transfer right now. Refresh the page.";
    private static final String GENERIC_UNAVAILABLE_AR =
        "هذا الإجراء غير متاح لعملية النقل هذه حاليًا. حدّث الصفحة.";

    /** Existing 5-arg signature, unchanged — every pre-Relocate caller keeps compiling and
     *  keeps creating ordinary round-trip transfers with no behavior change. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID createTransfer(String transferType, UUID destinationLocationId,
                               Instant expectedReturnAt, String note, UUID actorUserId) {
        return createTransfer(transferType, destinationLocationId, expectedReturnAt, note,
            actorUserId, "round_trip");
    }

    /** FR-22.10 — Relocate: same validation and destination guard as the round-trip path
     *  (a relocate destination must be a real, tenant-owned, non-fulfillment location exactly
     *  like every other transfer type — relocating to your own main warehouse is nonsensical).
     *  transferMode only decides which CLOSE path is legal later (closeTransfer() for
     *  round_trip, closeOneWay() for relocate_out) — it does not change anything about
     *  create() or scanOut() themselves. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID createTransfer(String transferType, UUID destinationLocationId,
                               Instant expectedReturnAt, String note, UUID actorUserId,
                               String transferMode) {
        return createTransfer(transferType, destinationLocationId, expectedReturnAt, note,
            actorUserId, transferMode, null);
    }

    /** FR-22.11 (B2) — Relocate Return: the 8th param, sourceLocationId, is required for
     *  transferMode='relocate_return' and ignored (stored NULL) for every other mode — a
     *  round_trip or relocate_out transfer has no notion of "origin", only destination. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID createTransfer(String transferType, UUID destinationLocationId,
                               Instant expectedReturnAt, String note, UUID actorUserId,
                               String transferMode, UUID sourceLocationId) {
        if (BAZAR_TYPE.equals(transferType)) {
            throw bazarLocked();
        }
        return createTransferCore(transferType, destinationLocationId, expectedReturnAt, note, actorUserId,
            transferMode, sourceLocationId);
    }

    /** Bazar POS B1: the event's linked transfer — round_trip, type 'bazar', main warehouse → the event location,
     *  status preparing. Same destination validation as every other transfer. ONE caller: BazarService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID createBazarTransfer(UUID destinationLocationId, UUID actorUserId) {
        return createTransferCore(BAZAR_TYPE, destinationLocationId, null, null, actorUserId, "round_trip", null);
    }

    private UUID createTransferCore(String transferType, UUID destinationLocationId,
                                    Instant expectedReturnAt, String note, UUID actorUserId,
                                    String transferMode, UUID sourceLocationId) {
        UUID tenantId = TenantContext.require();

        // transfer_type already has a DB CHECK constraint (V64) restricting it to the same
        // 4 values, so an invalid value can never persist — but without this check it would
        // surface as a raw DataIntegrityViolationException via the generic 400 handler
        // instead of a specific, bilingual TransferException.
        if (!VALID_TRANSFER_TYPES.contains(transferType)) {
            throw new TransferException(TransferException.Code.TRANSFER_TYPE_INVALID,
                "transferType must be one of: showroom, dryclean, repair, other",
                "يجب أن يكون نوع النقل أحد: showroom أو dryclean أو repair أو other",
                HttpStatus.BAD_REQUEST);
        }
        if (!VALID_TRANSFER_MODES.contains(transferMode)) {
            throw new TransferException(TransferException.Code.TRANSFER_TYPE_INVALID,
                "transferMode must be one of: round_trip, relocate_out, relocate_return",
                "يجب أن يكون نمط النقل أحد: round_trip أو relocate_out أو relocate_return",
                HttpStatus.BAD_REQUEST);
        }
        boolean isReturn = "relocate_return".equals(transferMode);

        // RLS-scoped existence + tenant check, NOT the FK alone: Postgres row security does
        // not apply to foreign-key satisfaction checks (documented Postgres behavior) — the
        // `destination_location_id uuid REFERENCES locations(id)` FK only proves a row with
        // that id exists SOMEWHERE, across every tenant. Without this explicit
        // tenant-scoped SELECT, tenant A could create a transfer whose destination is
        // tenant B's location row (any FK-satisfying uuid), a genuine cross-tenant leak,
        // not just bad input hygiene.
        List<Map<String, Object>> destRows = jdbc.queryForList(
            "SELECT is_fulfillment FROM locations WHERE id = ? AND tenant_id = ?",
            destinationLocationId, tenantId);
        if (destRows.isEmpty()) {
            throw new TransferException(TransferException.Code.TRANSFER_DESTINATION_NOT_FOUND,
                "Destination location not found",
                "لم يتم العثور على موقع الوجهة",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        boolean destIsFulfillment = Boolean.TRUE.equals(destRows.get(0).get("is_fulfillment"));
        // round_trip / relocate_out: destination must NOT be the fulfillment warehouse
        // (unaffected by this method — same guard as before B2). relocate_return: destination
        // MUST be the fulfillment warehouse — a return that lands anywhere else isn't sellable
        // at A. The "To" field is locked to A in the UI, but this is enforced server-side too.
        if (!isReturn && destIsFulfillment) {
            throw new TransferException(TransferException.Code.TRANSFER_DESTINATION_IS_FULFILLMENT,
                "Destination cannot be your own fulfillment warehouse",
                "لا يمكن أن تكون الوجهة هي مستودع التنفيذ الخاص بك",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (isReturn && !destIsFulfillment) {
            throw new TransferException(TransferException.Code.TRANSFER_RETURN_DESTINATION_NOT_FULFILLMENT,
                "A return must land at your fulfillment warehouse",
                "يجب أن تصل عملية الإرجاع إلى مستودع التنفيذ الخاص بك",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (isReturn) {
            if (sourceLocationId == null) {
                throw new TransferException(TransferException.Code.TRANSFER_SOURCE_REQUIRED,
                    "sourceLocationId is required for a relocate_return transfer",
                    "موقع المصدر مطلوب لعملية إرجاع النقل النهائي",
                    HttpStatus.BAD_REQUEST);
            }
            if (sourceLocationId.equals(destinationLocationId)) {
                throw new TransferException(TransferException.Code.TRANSFER_SOURCE_EQUALS_DESTINATION,
                    "Source and destination cannot be the same location",
                    "لا يمكن أن يكون المصدر والوجهة نفس الموقع",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            List<Map<String, Object>> sourceRows = jdbc.queryForList(
                "SELECT is_fulfillment FROM locations WHERE id = ? AND tenant_id = ?",
                sourceLocationId, tenantId);
            if (sourceRows.isEmpty()) {
                throw new TransferException(TransferException.Code.TRANSFER_SOURCE_NOT_FOUND,
                    "Source location not found",
                    "لم يتم العثور على موقع المصدر",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (Boolean.TRUE.equals(sourceRows.get(0).get("is_fulfillment"))) {
                throw new TransferException(TransferException.Code.TRANSFER_SOURCE_IS_FULFILLMENT,
                    "Source cannot be your own fulfillment warehouse",
                    "لا يمكن أن يكون المصدر هو مستودع التنفيذ الخاص بك",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO transfers " +
            "(id, tenant_id, transfer_type, destination_location_id, note, expected_return_at, created_by, transfer_mode, source_location_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, tenantId, transferType, destinationLocationId, note,
            expectedReturnAt != null ? Timestamp.from(expectedReturnAt) : null, actorUserId, transferMode,
            isReturn ? sourceLocationId : null);
        return id;
    }

    // ── Send-out scan ────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanOutResult scanOut(UUID transferId, String barcode, UUID actorUserId) {
        return scanOut(transferId, barcode, actorUserId, false);
    }

    /** Q1b: {@code viaPhone} (verified by the caller — PhoneScanSource) adds {"via":"phone"} to the event's metadata. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanOutResult scanOut(UUID transferId, String barcode, UUID actorUserId, boolean viaPhone) {
        return scanOutCore(transferId, barcode, actorUserId, viaPhone, false);
    }

    /** Bazar POS B1: the bazar event's send-out scan — the same scan as scanOut(), allowed on a bazar transfer.
     *  ONE caller: BazarService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanOutResult scanOutForBazar(UUID transferId, String barcode, UUID actorUserId, boolean viaPhone) {
        return scanOutCore(transferId, barcode, actorUserId, viaPhone, true);
    }

    private ScanOutResult scanOutCore(UUID transferId, String barcode, UUID actorUserId, boolean viaPhone,
                                      boolean bazarCaller) {
        UUID tenantId = TenantContext.require();

        // 1. Transfer must exist (tenant-scoped via RLS + explicit predicate) and be preparing.
        //    FOR SHARE: held until this scan commits, so markSent/cancel/closeOneWay (FOR UPDATE)
        //    can't change the status underneath it — see the class javadoc.
        List<Map<String, Object>> transferRows = jdbc.queryForList(
            "SELECT status, destination_location_id, transfer_type FROM transfers WHERE id = ? AND tenant_id = ? FOR SHARE",
            transferId, tenantId);
        if (transferRows.isEmpty()) {
            return ScanOutResult.rejected("TRANSFER_NOT_FOUND",
                "Transfer not found",
                "لم يتم العثور على عملية النقل");
        }
        Map<String, Object> transfer = transferRows.get(0);
        if (BAZAR_TYPE.equals(transfer.get("transfer_type")) != bazarCaller) {
            // A bazar transfer outside the bazar service, or a normal transfer through the bazar path.
            return bazarCaller ? ScanOutResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found",
                                     "لم يتم العثور على عملية النقل")
                               : ScanOutResult.rejected("BAZAR_TRANSFER_LOCKED", BAZAR_LOCKED_EN, BAZAR_LOCKED_AR);
        }
        String transferStatus = (String) transfer.get("status");
        if (!"preparing".equals(transferStatus)) {
            return ScanOutResult.rejected("TRANSFER_NOT_OPEN",
                "This transfer isn't accepting scans anymore.",
                "عملية النقل هذه لم تعد تقبل المسح.");
        }
        UUID   destinationLocationId = (UUID)   transfer.get("destination_location_id");
        String transferType          = (String) transfer.get("transfer_type");

        // 2–3. The piece, refused exactly as before (shared with the bazar top-up list — resolveSendPiece).
        SendPiece sp = resolveSendPiece(tenantId, barcode);
        if (sp.rejected() != null) return sp.rejected();
        String pieceId   = sp.pieceId();
        UUID   variantId = sp.variantId();
        // Issue 2: where the piece leaves FROM (only pieces leaving the main warehouse are decremented).
        UUID   fromLocationId = sp.fromLocationId();

        // 4–8. Claim and send (shared with the bazar's send-by-count — claimAndSend).
        Claimed claimed = claimAndSend(tenantId, transferId, destinationLocationId, pieceId, variantId, fromLocationId,
            actorUserId, transferMeta(transferId, transferType, viaPhone, null));
        if (claimed == null) {
            // No further DB access on this path: Postgres aborts the transaction on the
            // violation, and COMMIT on an aborted transaction is a silent, no-error
            // implicit ROLLBACK — any statement issued here first would fail instead.
            return ScanOutResult.rejected("ALREADY_ON_TRANSFER",
                "Piece was claimed by a concurrent scan",
                "تم حجز القطعة بواسطة عملية مسح متزامنة");
        }
        return ScanOutResult.success(pieceId, barcode, variantId, claimed.lineId(), claimed.qtyOut());
    }

    /** A piece a send-out scan may take (steps 2–3 of {@link #scanOutCore}), or the refusal. */
    private record SendPiece(ScanOutResult rejected, String pieceId, UUID variantId, UUID fromLocationId) {
        static SendPiece no(ScanOutResult r) { return new SendPiece(r, null, null, null); }
    }

    /**
     * Steps 2–3 of every send-out SCAN — ONE copy, used by {@link #scanOutCore} and the bazar top-up list
     * ({@link #addScannedToBatchForBazar}): the piece by barcode / id / short code (normalized), an unused printed label,
     * an untagged piece (PIECE_UNTAGGED), a piece that isn't available (WRONG_STATUS).
     */
    private SendPiece resolveSendPiece(UUID tenantId, String barcode) {
        // 2. Look up piece by barcode — same triple-match as FulfillService.scan()
        //    (new-format raw ULID, or old-format "PC-<ULID>", or short_code). The lookup value is
        //    normalized (Caps Lock / Arabic layout — ScanInputNormalizer); the result echoes the raw scan.
        String lookup = ScanInputNormalizer.normalizePiece(barcode);
        List<Map<String, Object>> pieceRows = jdbc.queryForList(
            "SELECT p.id, p.variant_id, p.status, p.current_location_id, p.short_code FROM pieces p " +
            "WHERE (p.barcode = ? OR p.id = ? OR p.short_code = ?) AND p.tenant_id = ?",
            lookup, lookup, lookup, tenantId);
        if (pieceRows.isEmpty()) {
            // Flexible labeling: a printed label nobody has tagged a piece with yet.
            var unused = com.traceability.labels.UnusedLabels.find(jdbc, tenantId, lookup);
            if (unused.isPresent()) {
                return SendPiece.no(ScanOutResult.rejected(com.traceability.labels.UnusedLabels.codeFor(unused.get()),
                    com.traceability.labels.UnusedLabels.messageEnFor(unused.get()),
                    com.traceability.labels.UnusedLabels.messageArFor(unused.get())));
            }
            return SendPiece.no(ScanOutResult.rejected("PIECE_NOT_FOUND",
                "Barcode not found in inventory",
                "لم يتم العثور على الباركود في المخزون"));
        }
        // Flexible labeling v1: an untagged piece (matched only by its typed internal code) can't
        // be transferred until it's tagged.
        if (pieceRows.get(0).get("short_code") == null) {
            return SendPiece.no(ScanOutResult.rejected("PIECE_UNTAGGED",
                "This piece has no barcode yet (untagged). Tag it before transferring it.",
                "هذه القطعة بدون باركود بعد (غير مُلصقة). ألصق لها ملصقًا قبل نقلها."));
        }
        Map<String, Object> piece   = pieceRows.get(0);
        String pieceId   = (String) piece.get("id");
        UUID   variantId = (UUID)   piece.get("variant_id");
        String status    = (String) piece.get("status");
        UUID   fromLocationId = (UUID) piece.get("current_location_id");

        // 3. WRONG_STATUS: piece must be available.
        if (!"available".equals(status)) {
            return SendPiece.no(ScanOutResult.rejected("WRONG_STATUS",
                "Piece is not available (status: " + status + ")",
                "القطعة غير متاحة (الحالة الحالية: " + status + ")"));
        }

        return new SendPiece(null, pieceId, variantId, fromLocationId);
    }

    /** The line and the line's running total after one piece was claimed and sent. */
    private record Claimed(UUID lineId, int qtyOut) {}

    /**
     * Steps 4–8 of every send-out: line get-or-create, the CLAIM (the race referee), the ledger move
     * available → out_on_transfer, the location, the line's qty_out. ONE copy, used by the scan ({@link #scanOutCore}
     * — which refuses an untagged piece BEFORE it gets here) and the bazar's send-by-count. Returns null when a
     * concurrent scan claimed the piece first: the transaction is then aborted — the caller must not touch the DB.
     */
    private Claimed claimAndSend(UUID tenantId, UUID transferId, UUID destinationLocationId, String pieceId,
                                 UUID variantId, UUID fromLocationId, UUID actorUserId, String meta) {
        return claimAndSend(tenantId, transferId, destinationLocationId, pieceId, variantId, fromLocationId,
            actorUserId, meta, null);
    }

    /** {@code batchId}: the bazar top-up batch the piece is sent in (V245; null = the transfer's first send). */
    private Claimed claimAndSend(UUID tenantId, UUID transferId, UUID destinationLocationId, String pieceId,
                                 UUID variantId, UUID fromLocationId, UUID actorUserId, String meta, UUID batchId) {
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
            // created_at = clock_timestamp(), not now(): a count send claims many pieces in ONE transaction, and
            // "newest added first" (remove by count) / "oldest open first" (B7a's pickPiece) must still order them.
            jdbc.update(
                "INSERT INTO transfer_pieces (id, tenant_id, transfer_id, line_id, piece_id, from_location_id, batch_id, created_at) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, clock_timestamp())",
                tenantId, transferId, lineId, pieceId, fromLocationId, batchId);
        } catch (DuplicateKeyException e) {
            return null;   // the transaction is aborted — see the caller
        }

        // 6. Transition available → out_on_transfer. The claim above already uniquely
        //    won this piece, so StateConflictException is not expected here; left
        //    uncaught deliberately — if it ever fires (an unrelated concurrent mutation),
        //    the whole transaction rolls back, undoing the claim rather than leaving it
        //    orphaned.
        ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.OUT_ON_TRANSFER,
            "transferred_out", actorUserId,
            new TransitionContext(null, null, destinationLocationId, null, meta));

        // 7. Explicit location update — transition() does not touch current_location_id
        //    (same pattern as ReturnService).
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?",
            destinationLocationId, pieceId);

        // 8. Now that the piece has genuinely moved, bump the line's running total.
        int qtyOut = jdbc.queryForObject(
            "UPDATE transfer_lines SET qty_out = qty_out + 1 WHERE id = ? RETURNING qty_out",
            Integer.class, lineId);
        return new Claimed(lineId, qtyOut);
    }


    private String transferMeta(UUID transferId, String transferType, boolean viaPhone, String clientRequestId) {
        return transferMeta(transferId, transferType, viaPhone, clientRequestId, "count");
    }

    /** {@code by}: how a retry-keyed send picked its pieces — "count" (B7d) or "receipt" (B9c). */
    private String transferMeta(UUID transferId, String transferType, boolean viaPhone, String clientRequestId,
                                String by) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        // "reason" the transition happened — the transfer's category (showroom/dryclean/
        // repair/other) is the only always-present, short, categorical field available;
        // the transfer's freeform note is not duplicated here (it's on the transfer row).
        m.put("reason", transferType);
        if (viaPhone) m.put("via", "phone");                 // Q1b
        if (clientRequestId != null) {                       // Bazar B7d: sent by count (the retry key)
            m.put("by", by);
            m.put("client_request_id", clientRequestId);
        }
        return writeJson(m);
    }

    // ── Bazar B7d: send by count, remove from this send ─────────────────────

    /** Most pieces one count send or one count removal may move. */
    public static final int MAX_COUNT_MOVE = 500;

    /**
     * Bazar POS B7d / U1c: send {@code quantity} pieces of a variant to the bazar without scanning them — for stock
     * WITHOUT a label only (U1c, approved 2026-11-26: a labelled piece is scanned — another label of the same size
     * wouldn't sell at the bazar). "Without a label" is {@code short_code IS NULL} (count-only receiving, never tagged).
     * ONLY a bazar transfer, ONLY while it is preparing; ONE caller: BazarService. Traced picks the pieces: available
     * AT THE MAIN WAREHOUSE (Shopify's −N at Mark sent is counted at Main), unlabelled, oldest received first,
     * {@code FOR UPDATE SKIP LOCKED} so two counts at once never take the same piece. Fewer unlabelled pieces than
     * asked → the ones there are are sent and the answer is {@code SENT_PARTIAL} (it says how many labelled ones are at
     * Main — scan them); none unlabelled → refused {@code PIECES_HAVE_LABELS} when labelled ones are there, else
     * {@code NOT_ENOUGH_AT_MAIN}. Each piece goes through the SAME claim-and-send as a scan ({@link #claimAndSend}) —
     * the scan path alone refuses an untagged piece. Retry-safe: the {@code clientRequestId} (and the quantity asked)
     * is written into every send event's metadata under a per-request lock; the same id again returns the first
     * result and sends nothing.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CountResult scanOutByCountForBazar(UUID transferId, UUID variantId, int quantity, UUID actorUserId,
                                              String clientRequestId) {
        UUID tenantId = TenantContext.require();
        requestLock(tenantId, "bazar-count-out", clientRequestId);
        List<Map<String, Object>> done = jdbc.queryForList(
            "SELECT pe.piece_id, p.variant_id, pe.metadata->>'requested' AS requested FROM piece_events pe " +
            "JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE pe.tenant_id = ? AND pe.event_type = 'transferred_out' " +
            "AND pe.metadata->>'client_request_id' = ? AND pe.metadata->>'transfer_id' = ? ORDER BY pe.id",
            tenantId, clientRequestId, transferId.toString());
        if (!done.isEmpty()) {
            UUID v = (UUID) done.get(0).get("variant_id");
            List<String> ids = done.stream().map(r -> (String) r.get("piece_id")).toList();
            Object asked = done.get(0).get("requested");
            int requested = asked == null ? ids.size() : Integer.parseInt(asked.toString());
            CountResult r = CountResult.success(v, ids, lineQtyOut(transferId, v), true);
            return requested > ids.size()
                ? r.partial(requested, labelledAtMain(tenantId, v, fulfillmentLocationId(tenantId))) : r;
        }

        Map<String, Object> transfer = shareBazarPreparing(transferId, tenantId);
        if (transfer == null) return CountResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found",
            "لم يتم العثور على عملية النقل", 0);
        if (!"preparing".equals(transfer.get("status"))) return sendClosed();
        if (quantity < 1 || quantity > MAX_COUNT_MOVE) {
            return CountResult.rejected("INVALID_QUANTITY", "Send between 1 and " + MAX_COUNT_MOVE + " pieces at a time.",
                "أرسل من 1 إلى " + MAX_COUNT_MOVE + " قطعة في المرة.", 0);
        }
        UUID main = fulfillmentLocationId(tenantId);
        UUID destination = (UUID) transfer.get("destination_location_id");

        List<String> picked = pickForCount(tenantId, variantId, main, quantity, null);
        if (picked.isEmpty()) {
            int labelled = labelledAtMain(tenantId, variantId, main);
            if (labelled > 0) return piecesHaveLabels(labelled);
            return CountResult.rejected("NOT_ENOUGH_AT_MAIN", "None of this size is available at the main warehouse.",
                "لا توجد قطع متاحة من هذا المقاس في المستودع الرئيسي.", 0);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        m.put("reason", BAZAR_TYPE);
        m.put("by", "count");
        m.put("client_request_id", clientRequestId);
        m.put("requested", quantity);
        String meta = writeJson(m);
        int qtyOut = 0;
        for (String pieceId : picked) {
            Claimed c = claimAndSend(tenantId, transferId, destination, pieceId, variantId, main, actorUserId, meta);
            if (c == null) {
                // A scan claimed one of these pieces in between (it doesn't lock the piece row). The transaction is
                // aborted — throwing rolls every piece of this count back; the person just tries again.
                throw new TransferException(TransferException.Code.TRANSFER_SEND_CONFLICT,
                    "The stock changed while sending — nothing was sent. Try again.",
                    "تغيّر المخزون أثناء الإرسال — لم يُرسل شيء. حاول مرة أخرى.", HttpStatus.CONFLICT);
            }
            qtyOut = c.qtyOut();
        }
        CountResult r = CountResult.success(variantId, picked, qtyOut, false);
        return picked.size() < quantity ? r.partial(quantity, labelledAtMain(tenantId, variantId, main)) : r;
    }

    /** U1c — none of the size without a label, {@code labelled} with one at Main: scan them (send by count and the
     *  top-up list's count alike). */
    private static CountResult piecesHaveLabels(int labelled) {
        return new CountResult(false, "PIECES_HAVE_LABELS",
            "These pieces have labels — scan them instead. If you never printed their labels, reprint them from the receipt.",
            "هذه القطع عليها ملصقات — امسحها بدلًا من ذلك. إذا لم تطبع ملصقاتها من قبل، أعد طباعتها من جلسة الاستلام.",
            null, 0, 0, List.of(), 0, false, null, null, labelled);
    }

    /** U1c — labelled pieces of a size free to send at Main (available, a label code, on no open transfer). */
    private int labelledAtMain(UUID tenantId, UUID variantId, UUID main) {
        return jdbc.queryForObject(
            "SELECT count(*)::int FROM pieces p WHERE p.tenant_id = ? AND p.variant_id = ? " +
            "AND p.status = 'available' AND p.current_location_id = ? AND p.short_code IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.piece_id = p.id AND tp.outcome IS NULL)",
            Integer.class, tenantId, variantId, main);
    }

    /** Top-up: {@link #labelledAtMain}, leaving out the pieces already on the batch's list (scanned there). */
    private int labelledAtMainNotListed(UUID tenantId, UUID variantId, UUID main, UUID batchId) {
        return jdbc.queryForObject(
            "SELECT count(*)::int FROM pieces p WHERE p.tenant_id = ? AND p.variant_id = ? " +
            "AND p.status = 'available' AND p.current_location_id = ? AND p.short_code IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.piece_id = p.id AND tp.outcome IS NULL) " +
            "AND NOT EXISTS (SELECT 1 FROM transfer_send_batch_items i WHERE i.batch_id = ? AND i.piece_id = p.id " +
            "  AND i.removed_at IS NULL)",
            Integer.class, tenantId, variantId, main, batchId);
    }

    /**
     * THE send-by-count picker (B7d) — the bazar's send by count and the top-up list's count both use it: available
     * AT THE MAIN WAREHOUSE, WITHOUT a label (U1c — labelled pieces are scanned), on no open transfer, oldest received
     * first, FOR UPDATE SKIP LOCKED. {@code exceptBatchId}
     * (top-up list): leaves out the pieces already on that batch's list.
     */
    private List<String> pickForCount(UUID tenantId, UUID variantId, UUID main, int quantity, UUID exceptBatchId) {
        List<Object> args = new ArrayList<>(List.of(tenantId, variantId, main));
        String notListed = "";
        if (exceptBatchId != null) {
            notListed = "AND NOT EXISTS (SELECT 1 FROM transfer_send_batch_items i WHERE i.batch_id = ? " +
                        "AND i.piece_id = p.id AND i.removed_at IS NULL) ";
            args.add(exceptBatchId);
        }
        args.add(quantity);
        return jdbc.queryForList(
            "SELECT p.id FROM pieces p WHERE p.tenant_id = ? AND p.variant_id = ? " +
            "AND p.status = 'available' AND p.current_location_id = ? AND p.short_code IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.piece_id = p.id AND tp.outcome IS NULL) " +
            notListed +
            "ORDER BY p.created_at, p.id LIMIT ? FOR UPDATE OF p SKIP LOCKED",
            String.class, args.toArray());
    }

    /** Most pieces one receipt send (B9c) moves per call; the rest stay at Main and a next call sends them. */
    public static final int MAX_RECEIPT_MOVE = 1000;

    /**
     * Bazar B9c — the guided setup's "put the batch on the send": EXACTLY the pieces of the given receipts (labelled
     * and count-only alike) that are available AT THE MAIN WAREHOUSE and on no open transfer, oldest first, up to
     * {@link #MAX_RECEIPT_MOVE}. Never another piece of the same size (a send by count picks the oldest at Main — a
     * different piece than the labelled one in hand). ONLY a bazar transfer, ONLY while it is preparing; ONE caller:
     * BazarService. Each piece goes through the SAME claim-and-send as a scan and a send by count
     * ({@link #claimAndSend}); nothing reaches Shopify here (the −N at Main is Mark sent's claimSend). All or
     * nothing: the rows are locked (FOR UPDATE, waiting — never skipped), and a piece a concurrent scan claimed
     * first (a scan doesn't lock the piece row) rolls the whole call back. Retry-safe: the {@code clientRequestId}
     * is written into every send event's metadata under a per-request lock; the same id again returns the first
     * result and sends nothing.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReceiptSendResult scanOutReceiptPiecesForBazar(UUID transferId, List<UUID> receiptIds, UUID actorUserId,
                                                          String clientRequestId) {
        UUID tenantId = TenantContext.require();
        requestLock(tenantId, "bazar-receipt-out", clientRequestId);
        List<UUID> receipts = receiptIds == null ? List.of() : receiptIds.stream().filter(Objects::nonNull).toList();
        List<Map<String, Object>> done = jdbc.queryForList(
            "SELECT pe.piece_id, p.variant_id FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE pe.tenant_id = ? AND pe.event_type = 'transferred_out' " +
            "AND pe.metadata->>'client_request_id' = ? AND pe.metadata->>'transfer_id' = ? ORDER BY pe.id",
            tenantId, clientRequestId, transferId.toString());
        if (!done.isEmpty()) {
            return ReceiptSendResult.success(done.stream().map(r -> (String) r.get("piece_id")).toList(),
                distinctVariants(done), receiptPiecesLeft(tenantId, receipts), true);
        }

        Map<String, Object> transfer = shareBazarPreparing(transferId, tenantId);
        if (transfer == null) return ReceiptSendResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found",
            "لم يتم العثور على عملية النقل");
        if (!"preparing".equals(transfer.get("status"))) {
            CountResult closed = sendClosed();
            return ReceiptSendResult.rejected(closed.code(), closed.messageEn(), closed.messageAr());
        }
        if (receipts.isEmpty()) return ReceiptSendResult.success(List.of(), List.of(), 0, false);
        UUID main = fulfillmentLocationId(tenantId);
        UUID destination = (UUID) transfer.get("destination_location_id");

        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(receipts);
        args.add(main);
        args.add(MAX_RECEIPT_MOVE);
        List<Map<String, Object>> picked = jdbc.queryForList(
            "SELECT p.id, p.variant_id FROM pieces p WHERE p.tenant_id = ? " +
            "AND p.receipt_id IN (" + placeholders(receipts.size()) + ") " +
            "AND p.status = 'available' AND p.current_location_id = ? " +
            "AND NOT EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.piece_id = p.id AND tp.outcome IS NULL) " +
            "ORDER BY p.created_at, p.id LIMIT ? FOR UPDATE OF p",
            args.toArray());
        String meta = transferMeta(transferId, BAZAR_TYPE, false, clientRequestId, "receipt");
        List<String> sent = new ArrayList<>();
        for (Map<String, Object> row : picked) {
            String pieceId = (String) row.get("id");
            Claimed c = claimAndSend(tenantId, transferId, destination, pieceId, (UUID) row.get("variant_id"), main,
                actorUserId, meta);
            if (c == null) {
                // A scan claimed one of these pieces in between — the transaction is aborted; roll everything back.
                throw new TransferException(TransferException.Code.TRANSFER_SEND_CONFLICT,
                    "The stock changed while sending — nothing was sent. Try again.",
                    "تغيّر المخزون أثناء الإرسال — لم يُرسل شيء. حاول مرة أخرى.", HttpStatus.CONFLICT);
            }
            sent.add(pieceId);
        }
        return ReceiptSendResult.success(sent, distinctVariants(picked), receiptPiecesLeft(tenantId, receipts), false);
    }

    /** Pieces of these receipts still available at Main and on no open transfer (what a next receipt send would move). */
    private int receiptPiecesLeft(UUID tenantId, List<UUID> receipts) {
        if (receipts == null || receipts.isEmpty()) return 0;
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(receipts);
        args.add(fulfillmentLocationId(tenantId));
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*)::int FROM pieces p WHERE p.tenant_id = ? " +
            "AND p.receipt_id IN (" + placeholders(receipts.size()) + ") " +
            "AND p.status = 'available' AND p.current_location_id = ? " +
            "AND NOT EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.piece_id = p.id AND tp.outcome IS NULL)",
            Integer.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private static List<UUID> distinctVariants(List<Map<String, Object>> rows) {
        Set<UUID> out = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) out.add((UUID) r.get("variant_id"));
        return new ArrayList<>(out);
    }

    /**
     * Bazar POS B7d: take one scanned piece back off the bazar's send (the ✕ on a scanned row) while the transfer is
     * preparing. Reverses exactly what the claim did ({@link #unsend}). ONE caller: BazarService.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CountResult removeScannedFromSendForBazar(UUID transferId, String code, UUID actorUserId,
                                                     String clientRequestId) {
        UUID tenantId = TenantContext.require();
        requestLock(tenantId, "bazar-unsend", clientRequestId);
        CountResult replay = unsendReplay(tenantId, transferId, clientRequestId);
        if (replay != null) return replay;
        Map<String, Object> transfer = shareBazarPreparing(transferId, tenantId);
        if (transfer == null) return CountResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found",
            "لم يتم العثور على عملية النقل", 0);
        if (!"preparing".equals(transfer.get("status"))) return sendClosed();
        String lookup = ScanInputNormalizer.normalizePiece(code == null ? "" : code.trim());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT tp.id, tp.piece_id, tp.line_id, tp.from_location_id, p.variant_id FROM transfer_pieces tp " +
            "JOIN pieces p ON p.id = tp.piece_id AND p.tenant_id = tp.tenant_id " +
            "WHERE tp.transfer_id = ? AND tp.tenant_id = ? AND tp.outcome IS NULL " +
            "AND (p.barcode = ? OR p.id = ? OR p.short_code = ?) FOR UPDATE OF tp",
            transferId, tenantId, lookup, lookup, lookup);
        if (rows.isEmpty()) {
            return CountResult.rejected("NOT_ON_THIS_SEND", "This piece isn't on this send.",
                "هذه القطعة ليست ضمن هذا الإرسال.", 0);
        }
        return unsend(tenantId, transferId, rows, (UUID) rows.get(0).get("variant_id"), actorUserId, clientRequestId, "scan");
    }

    /**
     * Bazar POS B7d: take {@code quantity} pieces of a variant back off the bazar's send, the newest added first,
     * while the transfer is preparing. All or nothing. ONE caller: BazarService.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CountResult removeCountFromSendForBazar(UUID transferId, UUID variantId, int quantity, UUID actorUserId,
                                                   String clientRequestId) {
        UUID tenantId = TenantContext.require();
        requestLock(tenantId, "bazar-unsend", clientRequestId);
        CountResult replay = unsendReplay(tenantId, transferId, clientRequestId);
        if (replay != null) return replay;
        Map<String, Object> transfer = shareBazarPreparing(transferId, tenantId);
        if (transfer == null) return CountResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found",
            "لم يتم العثور على عملية النقل", 0);
        if (!"preparing".equals(transfer.get("status"))) return sendClosed();
        if (quantity < 1 || quantity > MAX_COUNT_MOVE) {
            return CountResult.rejected("INVALID_QUANTITY", "Remove between 1 and " + MAX_COUNT_MOVE + " pieces at a time.",
                "احذف من 1 إلى " + MAX_COUNT_MOVE + " قطعة في المرة.", 0);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT tp.id, tp.piece_id, tp.line_id, tp.from_location_id FROM transfer_pieces tp " +
            "JOIN pieces p ON p.id = tp.piece_id AND p.tenant_id = tp.tenant_id " +
            "WHERE tp.transfer_id = ? AND tp.tenant_id = ? AND tp.outcome IS NULL AND p.variant_id = ? " +
            "ORDER BY tp.created_at DESC, tp.id DESC LIMIT ? FOR UPDATE OF tp SKIP LOCKED",
            transferId, tenantId, variantId, quantity);
        if (rows.size() < quantity) {
            int n = rows.size();
            return CountResult.rejected("NOT_ON_THIS_SEND",
                "Only " + n + " of this size " + (n == 1 ? "is" : "are") + " on this send.",
                "الموجود من هذا المقاس في هذا الإرسال " + n + " فقط.", n);
        }
        return unsend(tenantId, transferId, rows, variantId, actorUserId, clientRequestId, "count");
    }

    /**
     * Reverses a claim, piece by piece: the transfer_pieces row is deleted (nothing has been sent — Mark sent reads
     * these rows for Shopify's −N, so a removed piece is simply not part of it), the piece goes back
     * out_on_transfer → available through the ledger (event removed_from_transfer, the request id in its metadata)
     * at the place it left from, the line's qty_out goes down — and a line left with no piece is deleted, as if it
     * was never scanned. No Shopify write: nothing reaches Shopify before Mark sent.
     */
    private CountResult unsend(UUID tenantId, UUID transferId, List<Map<String, Object>> rows, UUID variantId,
                               UUID actorUserId, String clientRequestId, String by) {
        UUID main = fulfillmentLocationId(tenantId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        m.put("by", by);
        m.put("client_request_id", clientRequestId);
        String meta = writeJson(m);
        List<String> pieceIds = new ArrayList<>();
        Set<UUID> lines = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            String pieceId = (String) r.get("piece_id");
            UUID back = r.get("from_location_id") != null ? (UUID) r.get("from_location_id") : main;
            jdbc.update("DELETE FROM transfer_pieces WHERE id = ? AND tenant_id = ?", r.get("id"), tenantId);
            ledger.transition(pieceId, PieceStatus.OUT_ON_TRANSFER, PieceStatus.AVAILABLE,
                "removed_from_transfer", actorUserId, new TransitionContext(null, null, back, null, meta));
            jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?", back, pieceId);
            jdbc.update("UPDATE transfer_lines SET qty_out = qty_out - 1 WHERE id = ? AND tenant_id = ?",
                r.get("line_id"), tenantId);
            lines.add((UUID) r.get("line_id"));
            pieceIds.add(pieceId);
        }
        for (UUID line : lines) {
            jdbc.update("DELETE FROM transfer_lines tl WHERE tl.id = ? AND tl.tenant_id = ? AND tl.qty_out = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.line_id = tl.id)", line, tenantId);
        }
        return CountResult.success(variantId, pieceIds, lineQtyOut(transferId, variantId), false);
    }

    private CountResult unsendReplay(UUID tenantId, UUID transferId, String clientRequestId) {
        List<Map<String, Object>> done = jdbc.queryForList(
            "SELECT pe.piece_id, p.variant_id FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE pe.tenant_id = ? AND pe.event_type = 'removed_from_transfer' " +
            "AND pe.metadata->>'client_request_id' = ? AND pe.metadata->>'transfer_id' = ? ORDER BY pe.id",
            tenantId, clientRequestId, transferId.toString());
        if (done.isEmpty()) return null;
        UUID v = (UUID) done.get(0).get("variant_id");
        return CountResult.success(v, done.stream().map(r -> (String) r.get("piece_id")).toList(),
            lineQtyOut(transferId, v), true);
    }

    /** One request id at a time: a retry waits for the first try, then finds what it did. */
    private void requestLock(UUID tenantId, String kind, String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 100) {
            throw new IllegalArgumentException("client_request_id is required");
        }
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            tenantId + ":" + kind + ":" + clientRequestId);
    }

    /** The bazar transfer's row FOR SHARE (Mark sent's FOR UPDATE waits for us — the class javadoc); null when
     *  it doesn't exist or isn't a bazar transfer. */
    private Map<String, Object> shareBazarPreparing(UUID transferId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT status, destination_location_id, transfer_type FROM transfers WHERE id = ? AND tenant_id = ? FOR SHARE",
            transferId, tenantId);
        if (rows.isEmpty() || !BAZAR_TYPE.equals(rows.get(0).get("transfer_type"))) return null;
        return rows.get(0);
    }

    private static CountResult sendClosed() {
        return CountResult.rejected("TRANSFER_NOT_OPEN",
            "This stock is already on its way — nothing can be added or removed now.",
            "هذه البضاعة في طريقها بالفعل — لا يمكن الإضافة أو الحذف الآن.", 0);
    }

    private int lineQtyOut(UUID transferId, UUID variantId) {
        List<Integer> q = jdbc.queryForList("SELECT qty_out FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            Integer.class, transferId, variantId);
        return q.isEmpty() ? 0 : q.get(0);
    }

    // ── Bazar top-up: send batches on a SENT bazar transfer (V245) ───────────

    /**
     * Bazar top-up (approved 2026-11-27, design B) — the bazar transfer after Mark sent takes more pieces in BATCHES on
     * the SAME transfer. While open a batch is a LIST (transfer_send_batch_items): scan / count / remove change the list
     * and nothing else — no piece moves, nothing reaches Shopify, the pieces stay sellable online. {@link
     * #sendBatchForBazar} then claims every listed piece through {@link #claimAndSend} (transfer_pieces.batch_id),
     * marks the batch sent and writes the batch's one Shopify claim ({@link TransferShopifySync#claimBatchSend}) in ONE
     * transaction. ONLY a bazar transfer, ONLY while it is 'sent'; ONE caller: BazarService (BazarTransferLockTest).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID openSendBatchForBazar(UUID transferId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> transfer = shareBazar(transferId, tenantId);
        if (transfer == null) throw transferNotFound();
        if (!"sent".equals(transfer.get("status"))) throw batchTransferNotSent();
        // One opener at a time per transfer (the one-open partial unique index is the backstop).
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tenantId + ":batch-open:" + transferId);
        List<UUID> open = jdbc.queryForList(
            "SELECT id FROM transfer_send_batches WHERE transfer_id = ? AND tenant_id = ? AND status = 'open'",
            UUID.class, transferId, tenantId);
        if (!open.isEmpty()) return open.get(0);
        return jdbc.queryForObject(
            "INSERT INTO transfer_send_batches (tenant_id, transfer_id, batch_no, created_by) " +
            "VALUES (?, ?, (SELECT COALESCE(MAX(batch_no), 1) + 1 FROM transfer_send_batches WHERE transfer_id = ? AND tenant_id = ?), ?) " +
            "RETURNING id", UUID.class, tenantId, transferId, transferId, tenantId, actorUserId);
    }

    /** List one scanned piece on the open batch — the scan's own checks (resolveSendPiece), then not on any open
     *  transfer and not on this list already. Nothing moves. ONE caller: BazarService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanOutResult addScannedToBatchForBazar(UUID transferId, UUID batchId, String barcode, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        ScanOutResult closed = openBatchOrRefusal(tenantId, transferId, batchId);
        if (closed != null) return closed;
        SendPiece sp = resolveSendPiece(tenantId, barcode);
        if (sp.rejected() != null) return sp.rejected();
        if (onOpenTransfer(sp.pieceId())) {
            return ScanOutResult.rejected("ALREADY_ON_TRANSFER", "This piece is already on a transfer.",
                "هذه القطعة ضمن عملية نقل بالفعل.");
        }
        int added = jdbc.update(
            "INSERT INTO transfer_send_batch_items (tenant_id, batch_id, piece_id, variant_id, added_by, added_via) " +
            "VALUES (?, ?, ?, ?, ?, 'scan') ON CONFLICT (batch_id, piece_id) WHERE removed_at IS NULL DO NOTHING",
            tenantId, batchId, sp.pieceId(), sp.variantId(), actorUserId);
        if (added == 0) {
            return ScanOutResult.rejected("ALREADY_IN_BATCH", "This piece is already on this batch.",
                "هذه القطعة موجودة في هذه الدفعة بالفعل.");
        }
        return ScanOutResult.success(sp.pieceId(), barcode, sp.variantId(), null,
            batchVariantCount(tenantId, batchId, sp.variantId()));
    }

    /** List {@code quantity} pieces of a size on the open batch — THE send-by-count picker ({@link #pickForCount}), all
     *  or nothing. Retry-safe by {@code clientRequestId}. Nothing moves. ONE caller: BazarService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CountResult addCountToBatchForBazar(UUID transferId, UUID batchId, UUID variantId, int quantity,
                                               UUID actorUserId, String clientRequestId) {
        UUID tenantId = TenantContext.require();
        requestLock(tenantId, "bazar-batch-add", clientRequestId);
        List<String> done = jdbc.queryForList(
            "SELECT piece_id FROM transfer_send_batch_items WHERE batch_id = ? AND tenant_id = ? AND add_request_id = ? " +
            "ORDER BY added_at, id", String.class, batchId, tenantId, clientRequestId);
        if (!done.isEmpty()) {
            return CountResult.success(variantId, done, batchVariantCount(tenantId, batchId, variantId), true);
        }
        ScanOutResult closed = openBatchOrRefusal(tenantId, transferId, batchId);
        if (closed != null) return CountResult.rejected(closed.code(), closed.messageEn(), closed.messageAr(), 0);
        if (quantity < 1 || quantity > MAX_COUNT_MOVE) {
            return CountResult.rejected("INVALID_QUANTITY", "Add between 1 and " + MAX_COUNT_MOVE + " pieces at a time.",
                "أضف من 1 إلى " + MAX_COUNT_MOVE + " قطعة في المرة.", 0);
        }
        // U1c's rule (approved 2026-11-27 for batches): by count lists only pieces WITHOUT a label (the shared picker);
        // fewer than asked → the ones there are, SENT_PARTIAL "N added · M have labels — scan them"; none and labelled
        // ones there → the same PIECES_HAVE_LABELS refusal as the send by count; none at all → NOT_ENOUGH_AT_MAIN.
        UUID main = fulfillmentLocationId(tenantId);
        List<String> picked = pickForCount(tenantId, variantId, main, quantity, batchId);
        if (picked.isEmpty()) {
            int labelled = labelledAtMainNotListed(tenantId, variantId, main, batchId);
            if (labelled > 0) return piecesHaveLabels(labelled);
            return CountResult.rejected("NOT_ENOUGH_AT_MAIN", "None of this size is available at the main warehouse.",
                "لا توجد قطع متاحة من هذا المقاس في المستودع الرئيسي.", 0);
        }
        for (String pieceId : picked) {
            jdbc.update(
                "INSERT INTO transfer_send_batch_items (tenant_id, batch_id, piece_id, variant_id, added_by, added_via, add_request_id) " +
                "VALUES (?, ?, ?, ?, ?, 'count', ?)", tenantId, batchId, pieceId, variantId, actorUserId, clientRequestId);
        }
        CountResult r = CountResult.success(variantId, picked, batchVariantCount(tenantId, batchId, variantId), false);
        return picked.size() < quantity
            ? r.batchPartial(quantity, labelledAtMainNotListed(tenantId, variantId, main, batchId)) : r;
    }

    /** Take pieces off the open batch's list: one by its code, or {@code quantity} of a size, the newest listed first
     *  (all or nothing). Retry-safe by {@code clientRequestId}. ONE caller: BazarService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CountResult removeFromBatchForBazar(UUID transferId, UUID batchId, String code, UUID variantId, int quantity,
                                               UUID actorUserId, String clientRequestId) {
        UUID tenantId = TenantContext.require();
        requestLock(tenantId, "bazar-batch-remove", clientRequestId);
        List<Map<String, Object>> done = jdbc.queryForList(
            "SELECT piece_id, variant_id FROM transfer_send_batch_items WHERE batch_id = ? AND tenant_id = ? " +
            "AND remove_request_id = ? ORDER BY removed_at, id", batchId, tenantId, clientRequestId);
        if (!done.isEmpty()) {
            UUID v = (UUID) done.get(0).get("variant_id");
            return CountResult.success(v, done.stream().map(r -> (String) r.get("piece_id")).toList(),
                batchVariantCount(tenantId, batchId, v), true);
        }
        ScanOutResult closed = openBatchOrRefusal(tenantId, transferId, batchId);
        if (closed != null) return CountResult.rejected(closed.code(), closed.messageEn(), closed.messageAr(), 0);
        List<Map<String, Object>> rows;
        if (code != null && !code.isBlank()) {
            String lookup = ScanInputNormalizer.normalizePiece(code.trim());
            rows = jdbc.queryForList(
                "SELECT i.id, i.piece_id, i.variant_id FROM transfer_send_batch_items i " +
                "JOIN pieces p ON p.id = i.piece_id AND p.tenant_id = i.tenant_id " +
                "WHERE i.batch_id = ? AND i.tenant_id = ? AND i.removed_at IS NULL " +
                "AND (p.barcode = ? OR p.id = ? OR p.short_code = ?) FOR UPDATE OF i",
                batchId, tenantId, lookup, lookup, lookup);
            if (rows.isEmpty()) {
                return CountResult.rejected("NOT_ON_THIS_SEND", "This piece isn't on this batch.",
                    "هذه القطعة ليست ضمن هذه الدفعة.", 0);
            }
        } else {
            if (quantity < 1 || quantity > MAX_COUNT_MOVE) {
                return CountResult.rejected("INVALID_QUANTITY", "Remove between 1 and " + MAX_COUNT_MOVE + " pieces at a time.",
                    "احذف من 1 إلى " + MAX_COUNT_MOVE + " قطعة في المرة.", 0);
            }
            rows = jdbc.queryForList(
                "SELECT i.id, i.piece_id, i.variant_id FROM transfer_send_batch_items i " +
                "WHERE i.batch_id = ? AND i.tenant_id = ? AND i.removed_at IS NULL AND i.variant_id = ? " +
                "ORDER BY i.added_at DESC, i.id DESC LIMIT ? FOR UPDATE OF i", batchId, tenantId, variantId, quantity);
            if (rows.size() < quantity) {
                int n = rows.size();
                return CountResult.rejected("NOT_ON_THIS_SEND",
                    "Only " + n + " of this size " + (n == 1 ? "is" : "are") + " on this batch.",
                    "الموجود من هذا المقاس في هذه الدفعة " + n + " فقط.", n);
            }
        }
        for (Map<String, Object> r : rows) {
            jdbc.update("UPDATE transfer_send_batch_items SET removed_at = clock_timestamp(), removed_by = ?, " +
                "remove_request_id = ? WHERE id = ? AND tenant_id = ? AND removed_at IS NULL",
                actorUserId, clientRequestId, r.get("id"), tenantId);
        }
        UUID v = (UUID) rows.get(0).get("variant_id");
        return CountResult.success(v, rows.stream().map(r -> (String) r.get("piece_id")).toList(),
            batchVariantCount(tenantId, batchId, v), false);
    }

    /** Drop the open batch's list — nothing ever moved. ONE caller: BazarService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void discardBatchForBazar(UUID transferId, UUID batchId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        int n = jdbc.update(
            "UPDATE transfer_send_batches SET status = 'discarded', discarded_at = now(), discarded_by = ? " +
            "WHERE id = ? AND transfer_id = ? AND tenant_id = ? AND status = 'open'", actorUserId, batchId, transferId, tenantId);
        if (n == 0) throw batchNotOpen();
    }

    /**
     * "Send these to the bazar": every listed piece, ONE transaction, all or nothing. The transfer is locked FOR UPDATE
     * (it must still be 'sent' — End event's beginReconcile locks it too), the batch FOR UPDATE (must be open), every
     * listed piece FOR UPDATE: one that isn't available any more or is on an open transfer → nothing is sent and the
     * answer names it (BATCH_PIECES_CHANGED). Else each piece goes through {@link #claimAndSend} with the batch id, the
     * batch becomes 'sent', and its Shopify claim is written ({@link TransferShopifySync#claimBatchSend} — Remove: one
     * −N claim for exactly these pieces; Keep: nothing). Retry-safe: the same {@code clientRequestId} returns the first
     * answer and sends nothing more. ONE caller: BazarService.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BatchSendResult sendBatchForBazar(UUID transferId, UUID batchId, UUID actorUserId, String clientRequestId) {
        UUID tenantId = TenantContext.require();
        requestLock(tenantId, "bazar-batch-send", clientRequestId);
        List<Map<String, Object>> transferRows = jdbc.queryForList(
            "SELECT status, destination_location_id, transfer_type FROM transfers WHERE id = ? AND tenant_id = ? FOR UPDATE",
            transferId, tenantId);
        if (transferRows.isEmpty() || !BAZAR_TYPE.equals(transferRows.get(0).get("transfer_type"))) {
            return BatchSendResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found", "لم يتم العثور على عملية النقل");
        }
        List<Map<String, Object>> batchRows = jdbc.queryForList(
            "SELECT status, send_request_id FROM transfer_send_batches WHERE id = ? AND transfer_id = ? AND tenant_id = ? FOR UPDATE",
            batchId, transferId, tenantId);
        if (batchRows.isEmpty()) {
            return BatchSendResult.rejected("BATCH_NOT_FOUND", "Batch not found.", "لم يتم العثور على الدفعة.");
        }
        Map<String, Object> batch = batchRows.get(0);
        if ("sent".equals(batch.get("status")) && clientRequestId.equals(batch.get("send_request_id"))) {
            List<Map<String, Object>> sent = jdbc.queryForList(
                "SELECT tp.piece_id, p.variant_id FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id " +
                "WHERE tp.batch_id = ? AND tp.tenant_id = ? ORDER BY tp.created_at, tp.id", batchId, tenantId);
            return BatchSendResult.success(sent.stream().map(r -> (String) r.get("piece_id")).toList(),
                distinctVariants(sent), true);
        }
        if (!"open".equals(batch.get("status"))) {
            BatchSendResult.Refusal r = batchNotOpenRefusal();
            return BatchSendResult.rejected(r.code(), r.en(), r.ar());
        }
        if (!"sent".equals(transferRows.get(0).get("status"))) {
            CountResult closed = sendClosed();
            return BatchSendResult.rejected(closed.code(), closed.messageEn(), closed.messageAr());
        }
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT p.id, p.variant_id, p.status::text AS status, p.current_location_id, p.short_code, " +
            "       EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.piece_id = p.id AND tp.outcome IS NULL) AS on_transfer " +
            "FROM transfer_send_batch_items i JOIN pieces p ON p.id = i.piece_id AND p.tenant_id = i.tenant_id " +
            "WHERE i.batch_id = ? AND i.tenant_id = ? AND i.removed_at IS NULL " +
            "ORDER BY p.id FOR UPDATE OF p", batchId, tenantId);
        if (items.isEmpty()) {
            return BatchSendResult.rejected("BATCH_EMPTY", "Add at least one piece before sending.",
                "أضف قطعة واحدة على الأقل قبل الإرسال.");
        }
        List<String> blocked = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (!"available".equals(it.get("status")) || Boolean.TRUE.equals(it.get("on_transfer"))) {
                blocked.add(it.get("short_code") != null ? (String) it.get("short_code") : (String) it.get("id"));
            }
        }
        if (!blocked.isEmpty()) {
            return BatchSendResult.blocked(blocked);
        }
        UUID destination = (UUID) transferRows.get(0).get("destination_location_id");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        m.put("reason", BAZAR_TYPE);
        m.put("by", "batch");
        m.put("batch_id", batchId.toString());
        m.put("client_request_id", clientRequestId);
        String meta = writeJson(m);
        // The pieces in the order they were listed (oldest first).
        List<Map<String, Object>> ordered = jdbc.queryForList(
            "SELECT i.piece_id, i.variant_id, p.current_location_id FROM transfer_send_batch_items i " +
            "JOIN pieces p ON p.id = i.piece_id AND p.tenant_id = i.tenant_id " +
            "WHERE i.batch_id = ? AND i.tenant_id = ? AND i.removed_at IS NULL ORDER BY i.added_at, i.id",
            batchId, tenantId);
        List<String> sent = new ArrayList<>();
        for (Map<String, Object> it : ordered) {
            String pieceId = (String) it.get("piece_id");
            Claimed c = claimAndSend(tenantId, transferId, destination, pieceId, (UUID) it.get("variant_id"),
                (UUID) it.get("current_location_id"), actorUserId, meta, batchId);
            if (c == null) {
                throw new TransferException(TransferException.Code.TRANSFER_SEND_CONFLICT,
                    "The stock changed while sending — nothing was sent. Try again.",
                    "تغيّر المخزون أثناء الإرسال — لم يُرسل شيء. حاول مرة أخرى.", HttpStatus.CONFLICT);
            }
            sent.add(pieceId);
        }
        jdbc.update(
            "UPDATE transfer_send_batches SET status = 'sent', sent_at = now(), sent_by = ?, send_request_id = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'open'", actorUserId, clientRequestId, batchId, tenantId);
        shopifySync.claimBatchSend(tenantId, transferId, batchId);
        return BatchSendResult.success(sent, distinctVariants(ordered), false);
    }

    /** The bazar transfer FOR SHARE (send / End event's FOR UPDATE wait for us); null when not a bazar transfer. */
    private Map<String, Object> shareBazar(UUID transferId, UUID tenantId) {
        return shareBazarPreparing(transferId, tenantId);
    }

    /** Null when the batch is open on a 'sent' bazar transfer (both held FOR SHARE until commit), else the refusal. */
    private ScanOutResult openBatchOrRefusal(UUID tenantId, UUID transferId, UUID batchId) {
        Map<String, Object> transfer = shareBazar(transferId, tenantId);
        if (transfer == null) {
            return ScanOutResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found", "لم يتم العثور على عملية النقل");
        }
        List<String> status = jdbc.queryForList(
            "SELECT status FROM transfer_send_batches WHERE id = ? AND transfer_id = ? AND tenant_id = ? FOR SHARE",
            String.class, batchId, transferId, tenantId);
        if (status.isEmpty()) return ScanOutResult.rejected("BATCH_NOT_FOUND", "Batch not found.", "لم يتم العثور على الدفعة.");
        if (!"open".equals(status.get(0))) {
            BatchSendResult.Refusal r = batchNotOpenRefusal();
            return ScanOutResult.rejected(r.code(), r.en(), r.ar());
        }
        if (!"sent".equals(transfer.get("status"))) {
            CountResult closed = sendClosed();
            return ScanOutResult.rejected(closed.code(), closed.messageEn(), closed.messageAr());
        }
        return null;
    }

    private boolean onOpenTransfer(String pieceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.piece_id = ? AND tp.outcome IS NULL)",
            Boolean.class, pieceId));
    }

    private int batchVariantCount(UUID tenantId, UUID batchId, UUID variantId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*)::int FROM transfer_send_batch_items WHERE batch_id = ? AND tenant_id = ? AND variant_id = ? " +
            "AND removed_at IS NULL", Integer.class, batchId, tenantId, variantId);
        return n == null ? 0 : n;
    }

    private static BatchSendResult.Refusal batchNotOpenRefusal() {
        return new BatchSendResult.Refusal("BATCH_NOT_OPEN", "This batch was already sent or discarded.",
            "تم إرسال هذه الدفعة أو إلغاؤها بالفعل.");
    }

    private TransferException batchNotOpen() {
        BatchSendResult.Refusal r = batchNotOpenRefusal();
        return new TransferException(TransferException.Code.TRANSFER_SEND_CONFLICT, r.en(), r.ar(), HttpStatus.CONFLICT);
    }

    private TransferException batchTransferNotSent() {
        return new TransferException(TransferException.Code.TRANSFER_SEND_CONFLICT,
            "More pieces can be added only after the bazar's stock was sent and before it ends.",
            "يمكن إضافة قطع أخرى فقط بعد إرسال بضاعة البازار وقبل انتهائه.", HttpStatus.CONFLICT);
    }

    // ── Return send-out scan (FR-22.11 / B2) ────────────────────────────────

    /**
     * The one legal exit from the 'transferred_out' terminal (opened by this method's
     * corresponding InventoryLedger.ALLOWED edge, "transferred_out:out_on_transfer" —
     * returnScanOut() is its SOLE caller; see the B2 diagnosis's grep proof). A NEW SIBLING
     * to scanOut(), not a branch inside it — scanOut() carries load-bearing concurrency
     * javadoc (see class header) and its own precondition (status='available') is a
     * different piece of the state machine than this method's (status='transferred_out').
     *
     * Same claim-before-transition shape as scanOut() and for the identical reason: the
     * plain JdbcTemplate transfer_pieces INSERT is the race referee, so transition()'s
     * StateConflictException never needs to be caught inside this @Transactional method.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanOutResult returnScanOut(UUID transferId, String barcode, UUID actorUserId) {
        return returnScanOut(transferId, barcode, actorUserId, false);
    }

    /** Q1b: {@code viaPhone} as scanOut(). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanOutResult returnScanOut(UUID transferId, String barcode, UUID actorUserId, boolean viaPhone) {
        UUID tenantId = TenantContext.require();
        if (isBazarTransfer(transferId, tenantId)) {
            return ScanOutResult.rejected("BAZAR_TRANSFER_LOCKED", BAZAR_LOCKED_EN, BAZAR_LOCKED_AR);
        }

        // 1. Transfer must exist, be preparing, and be a relocate_return transfer. FOR SHARE,
        //    same reason as scanOut().
        List<Map<String, Object>> transferRows = jdbc.queryForList(
            "SELECT status, transfer_mode, destination_location_id, source_location_id, transfer_type " +
            "FROM transfers WHERE id = ? AND tenant_id = ? FOR SHARE",
            transferId, tenantId);
        if (transferRows.isEmpty()) {
            return ScanOutResult.rejected("TRANSFER_NOT_FOUND",
                "Transfer not found",
                "لم يتم العثور على عملية النقل");
        }
        Map<String, Object> transfer = transferRows.get(0);
        String transferStatus = (String) transfer.get("status");
        String transferMode   = (String) transfer.get("transfer_mode");
        if (!"relocate_return".equals(transferMode)) {
            return ScanOutResult.rejected("TRANSFER_NOT_RELOCATE_RETURN",
                "This transfer is not a return transfer (mode: " + transferMode + ")",
                "عملية النقل هذه ليست عملية إرجاع (النمط: " + transferMode + ")");
        }
        if (!"preparing".equals(transferStatus)) {
            return ScanOutResult.rejected("TRANSFER_NOT_OPEN",
                "This transfer isn't accepting scans anymore.",
                "عملية النقل هذه لم تعد تقبل المسح.");
        }
        UUID   destinationLocationId = (UUID)   transfer.get("destination_location_id");
        UUID   sourceLocationId      = (UUID)   transfer.get("source_location_id");
        String transferType          = (String) transfer.get("transfer_type");

        // 2. Piece lookup — same triple-match (and normalized lookup value) as scanOut().
        String lookup = ScanInputNormalizer.normalizePiece(barcode);
        List<Map<String, Object>> pieceRows = jdbc.queryForList(
            "SELECT p.id, p.variant_id, p.status, p.current_location_id, p.short_code FROM pieces p " +
            "WHERE (p.barcode = ? OR p.id = ? OR p.short_code = ?) AND p.tenant_id = ?",
            lookup, lookup, lookup, tenantId);
        if (pieceRows.isEmpty()) {
            // Flexible labeling: a printed label nobody has tagged a piece with yet.
            var unused = com.traceability.labels.UnusedLabels.find(jdbc, tenantId, lookup);
            if (unused.isPresent()) {
                return ScanOutResult.rejected(com.traceability.labels.UnusedLabels.codeFor(unused.get()),
                    com.traceability.labels.UnusedLabels.messageEnFor(unused.get()),
                    com.traceability.labels.UnusedLabels.messageArFor(unused.get()));
            }
            return ScanOutResult.rejected("PIECE_NOT_FOUND",
                "Barcode not found in inventory",
                "لم يتم العثور على الباركود في المخزون");
        }
        // Flexible labeling v1: an untagged piece (matched only by its typed internal code) can't
        // be transferred until it's tagged.
        if (pieceRows.get(0).get("short_code") == null) {
            return ScanOutResult.rejected("PIECE_UNTAGGED",
                "This piece has no barcode yet (untagged). Tag it before transferring it.",
                "هذه القطعة بدون باركود بعد (غير مُلصقة). ألصق لها ملصقًا قبل نقلها.");
        }
        Map<String, Object> piece   = pieceRows.get(0);
        String pieceId   = (String) piece.get("id");
        UUID   variantId = (UUID)   piece.get("variant_id");
        String status    = (String) piece.get("status");
        UUID   pieceLocationId = (UUID) piece.get("current_location_id");

        // 3. WRONG_STATUS: piece must be transferred_out (the terminal Relocate close leaves
        //    it in — not 'available', which is scanOut()'s precondition, not this one's).
        if (!"transferred_out".equals(status)) {
            return ScanOutResult.rejected("WRONG_STATUS",
                "Piece is not transferred_out (status: " + status + ")",
                "القطعة ليست في حالة النقل النهائي (الحالة الحالية: " + status + ")");
        }

        // 4. WRONG_ORIGIN: scope the claim to THIS return's declared source location — a
        //    piece sitting at Warehouse-B-2 must not be claimable by a return declared
        //    against Warehouse-B-1 (see the B2 diagnosis's option (a)/(c) tradeoff).
        if (!Objects.equals(pieceLocationId, sourceLocationId)) {
            return ScanOutResult.rejected("WRONG_ORIGIN",
                "Piece is not at this return's source location",
                "القطعة ليست في موقع المصدر لعملية الإرجاع هذه");
        }

        // 5. Ensure the line row exists (create-if-absent, qty_out untouched — only bumped
        //    once the claim + transition below both succeed). Same get-or-create idiom as
        //    scanOut().
        UUID lineId = jdbc.query(
            "INSERT INTO transfer_lines (id, tenant_id, transfer_id, variant_id) " +
            "VALUES (gen_random_uuid(), ?, ?, ?) " +
            "ON CONFLICT (transfer_id, variant_id) DO UPDATE SET transfer_id = EXCLUDED.transfer_id " +
            "RETURNING id",
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            tenantId, transferId, variantId);

        // 6. CLAIM — the race referee, identical shape to scanOut()'s (see class javadoc for
        //    why this must be a plain JdbcTemplate call in this method body, not a call into
        //    another @Transactional bean).
        try {
            jdbc.update(
                "INSERT INTO transfer_pieces (id, tenant_id, transfer_id, line_id, piece_id, from_location_id) " +
                "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)",
                tenantId, transferId, lineId, pieceId, pieceLocationId);
        } catch (DuplicateKeyException e) {
            return ScanOutResult.rejected("ALREADY_ON_TRANSFER",
                "Piece was claimed by a concurrent scan",
                "تم حجز القطعة بواسطة عملية مسح متزامنة");
        }

        // 7. Transition transferred_out → out_on_transfer — the one legal exit from
        //    terminal. Left uncaught by design, same reasoning as scanOut()'s step 6.
        ledger.transition(pieceId, PieceStatus.TRANSFERRED_OUT, PieceStatus.OUT_ON_TRANSFER,
            "return_transfer_out", actorUserId,
            new TransitionContext(null, null, destinationLocationId, null, transferMeta(transferId, transferType, viaPhone, null)));

        // 8. Explicit location update — transition() does not touch current_location_id
        //    (same pattern as scanOut()). The piece is physically still at B until it's
        //    scanned back at A during reconcile; this mirrors scanOut()'s own convention of
        //    moving current_location_id to the transfer's destination immediately on the
        //    send-out scan (the piece is "in transit to A" from this point).
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?",
            destinationLocationId, pieceId);

        int qtyOut = jdbc.queryForObject(
            "UPDATE transfer_lines SET qty_out = qty_out + 1 WHERE id = ? RETURNING qty_out",
            Integer.class, lineId);

        return ScanOutResult.success(pieceId, barcode, variantId, lineId, qtyOut);
    }

    // ── Return piece picker (FR-22.11 / B2) ─────────────────────────────────

    /** Pieces available to pull onto a NEW return transfer from a chosen origin location —
     *  the return create screen's picker list. Read-only, tenant-scoped explicitly (matching
     *  scanOut()'s piece lookup) alongside RLS. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listReturnablePieces(UUID sourceLocationId) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT p.id, p.barcode, p.short_code, p.variant_id, v.sku, v.title AS variant_title, " +
            "       pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.status = 'transferred_out' AND p.current_location_id = ? AND p.tenant_id = ? " +
            "ORDER BY pr.title ASC, v.title ASC",
            sourceLocationId, tenantId);
    }

    // ── Mark as sent ─────────────────────────────────────────────────────────

    /**
     * preparing → sent, returning modes only (round_trip, relocate_return), and only once at
     * least one piece has been scanned out. Locks further scan-out (scanOut() accepts only
     * preparing) and unlocks Begin Reconcile. Writes no piece events — the pieces already
     * moved to out_on_transfer when they were scanned.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markSent(UUID transferId, UUID actorUserId) {
        markSentCore(transferId, actorUserId, false);
    }

    /** Bazar POS B1: preparing → sent for the bazar's linked transfer — exactly markSent() (the existing
     *  pushTransferOut claim, mode snapshot at send). ONE caller: BazarService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markSentForBazar(UUID transferId, UUID actorUserId) {
        markSentCore(transferId, actorUserId, true);
    }

    private void markSentCore(UUID transferId, UUID actorUserId, boolean bazarCaller) {
        UUID tenantId = TenantContext.require();

        Map<String, Object> transfer = lockTransfer(transferId, tenantId);
        if (BAZAR_TYPE.equals(transfer.get("transfer_type")) != bazarCaller) {
            throw bazarCaller ? transferNotFound() : bazarLocked();
        }
        String status = (String) transfer.get("status");
        String mode   = (String) transfer.get("transfer_mode");
        if (!RETURNING_MODES.contains(mode)) {
            throw wrongMode(mode);
        }
        if (!"preparing".equals(status)) {
            throw transferNotPreparing(status);
        }
        // Fresh statement AFTER the lock — sees any scan that committed while we waited.
        if (piecesEverScanned(transferId, tenantId) == 0) {
            throw new TransferException(TransferException.Code.TRANSFER_EMPTY,
                "Scan at least one piece before marking as sent.",
                "امسح قطعة واحدة على الأقل قبل تأكيد الإرسال.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int rows = jdbc.update(
            "UPDATE transfers SET status = 'sent', sent_at = now(), sent_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'preparing'",
            actorUserId, transferId, tenantId);
        if (rows == 0) {
            throw statusRaceConflict();
        }
        // Issue 2: the pieces have left — snapshot the mode, claim the main-warehouse decrement
        // (pushed after this transaction commits).
        shopifySync.claimSend(tenantId, transferId);
    }

    // ── Cancel ───────────────────────────────────────────────────────────────

    /**
     * preparing → cancelled, any mode, only while NO piece has ever been scanned onto the
     * transfer (transfer_pieces rows are never deleted, so zero rows means never scanned).
     * Touches no pieces and writes no piece events — there are none to touch.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancel(UUID transferId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        Map<String, Object> transfer = lockTransfer(transferId, tenantId);
        refuseBazar(transfer);
        String status = (String) transfer.get("status");
        if (!"preparing".equals(status)) {
            throw transferNotPreparing(status);
        }
        // Fresh statement AFTER the lock — see markSent().
        int pieces = piecesEverScanned(transferId, tenantId);
        if (pieces > 0) {
            throw new TransferException(TransferException.Code.TRANSFER_HAS_PIECES,
                "Pieces were scanned on this transfer, so it can't be cancelled.",
                "تم مسح قطع على عملية النقل هذه، لذلك لا يمكن إلغاؤها.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int rows = jdbc.update(
            "UPDATE transfers SET status = 'cancelled', cancelled_at = now(), cancelled_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'preparing'",
            actorUserId, transferId, tenantId);
        if (rows == 0) {
            throw statusRaceConflict();
        }
    }

    /**
     * Bazar U1b: preparing → cancelled for a DRAFT bazar that is being deleted — the same "never scanned" rule as
     * {@link #cancel} (transfer_pieces is checked after the lock; a removed-from-send piece leaves a removed_from_transfer
     * piece event, so the piece history is checked too: once anything was scanned out, the bazar can't be deleted).
     * ONE caller: BazarService.delete.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelForBazar(UUID transferId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> transfer = lockTransfer(transferId, tenantId);
        if (!BAZAR_TYPE.equals(transfer.get("transfer_type"))) throw transferNotFound();
        String status = (String) transfer.get("status");
        if (!"preparing".equals(status)) throw transferNotPreparing(status);
        if (piecesEverScanned(transferId, tenantId) > 0 || everScannedOut(transferId, tenantId)) {
            throw new TransferException(TransferException.Code.TRANSFER_HAS_PIECES,
                "Pieces were scanned out for this bazar, so it can't be deleted.",
                "تم إخراج قطع لهذا البازار، لذلك لا يمكن حذفه.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int rows = jdbc.update(
            "UPDATE transfers SET status = 'cancelled', cancelled_at = now(), cancelled_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'preparing'",
            actorUserId, transferId, tenantId);
        if (rows == 0) throw statusRaceConflict();
    }

    /** Any piece ever scanned onto this transfer — including ones taken back off the send (their rows are deleted). */
    private boolean everScannedOut(UUID transferId, UUID tenantId) {
        Boolean b = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM piece_events pe WHERE pe.tenant_id = ? " +
            "  AND pe.event_type IN ('transferred_out', 'removed_from_transfer') AND pe.metadata->>'transfer_id' = ?)",
            Boolean.class, tenantId, transferId.toString());
        return Boolean.TRUE.equals(b);
    }

    // ── Reconcile: begin ─────────────────────────────────────────────────────

    /**
     * sent → reconciling, returning modes only (a relocate_out transfer never reconciles — it
     * closes through closeOneWay()). Role gate (MANAGER/OWNER) is enforced at the controller
     * (FR-22.6) — not here, matching every other role-gated action in this codebase
     * (@PreAuthorize on the endpoint, never inside the service layer).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void beginReconcile(UUID transferId, UUID actorUserId) {
        beginReconcileCore(transferId, actorUserId, false);
    }

    /** Bazar POS B5: sent → reconciling for the bazar's linked transfer when its event ends — exactly
     *  beginReconcile(). ONE caller: BazarEndService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void beginReconcileForBazar(UUID transferId, UUID actorUserId) {
        beginReconcileCore(transferId, actorUserId, true);
    }

    private void beginReconcileCore(UUID transferId, UUID actorUserId, boolean bazarCaller) {
        UUID tenantId = TenantContext.require();

        Map<String, Object> transfer = lockTransfer(transferId, tenantId);
        if (BAZAR_TYPE.equals(transfer.get("transfer_type")) != bazarCaller) {
            throw bazarCaller ? transferNotFound() : bazarLocked();
        }
        String status = (String) transfer.get("status");
        String mode   = (String) transfer.get("transfer_mode");
        if (!RETURNING_MODES.contains(mode)) {
            throw wrongMode(mode);
        }
        if (!"sent".equals(status)) {
            throw new TransferException(TransferException.Code.TRANSFER_NOT_SENT,
                GENERIC_UNAVAILABLE_EN,
                GENERIC_UNAVAILABLE_AR,
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        int rows = jdbc.update(
            "UPDATE transfers SET status = 'reconciling', reconcile_started_at = now(), reconcile_started_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'sent'",
            actorUserId, transferId, tenantId);
        if (rows == 0) {
            throw statusRaceConflict();
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
        return reconcileScanBack(transferId, barcode, condition, actorUserId, false);
    }

    /** Q1b: {@code viaPhone} (verified by the caller — PhoneScanSource) adds {"via":"phone"} to the event's metadata. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanBackResult reconcileScanBack(UUID transferId, String barcode, String condition, UUID actorUserId,
                                            boolean viaPhone) {
        return reconcileScanBackCore(transferId, barcode, condition, actorUserId, viaPhone, false, null, false);
    }

    /**
     * Bazar POS B5: a leftover scanned back GOOD at the main warehouse after the event ended — exactly
     * reconcileScanBack(…, "good", …) (available at main; the Shopify +1 through the existing onTransferReturn gate).
     * {@code clientRequestId} is written into the event's metadata (the phone's retry finds it). ONE caller:
     * BazarEndService.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanBackResult reconcileScanBackForBazar(UUID transferId, String barcode, UUID actorUserId,
                                                    String clientRequestId) {
        return reconcileScanBackCore(transferId, barcode, "good", actorUserId, false, true, clientRequestId, false);
    }

    /**
     * Bazar POS B7d-2: one untagged leftover brought back GOOD by count — exactly reconcileScanBackForBazar (the same
     * claim, ledger move, location, line counter and the unchanged onTransferReturn +1 gate), the event marked
     * {@code by: count}. {@code pieceId} is the piece's internal id (the core's lookup matches it). ONE caller:
     * BazarEndService.bringBackByCount, which picks only untagged pieces outstanding on this transfer.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanBackResult reconcileScanBackByCountForBazar(UUID transferId, String pieceId, UUID actorUserId,
                                                           String clientRequestId) {
        return reconcileScanBackCore(transferId, pieceId, "good", actorUserId, false, true, clientRequestId, true);
    }

    private ScanBackResult reconcileScanBackCore(UUID transferId, String barcode, String condition, UUID actorUserId,
                                                 boolean viaPhone, boolean bazarCaller, String clientRequestId,
                                                 boolean byCount) {
        UUID tenantId = TenantContext.require();

        if (!VALID_CONDITIONS.contains(condition)) {
            // A malformed request, not a business outcome — but kept as a rejected() result
            // rather than thrown, so every scan-back response (including this one) shares
            // the same {success,code,message} shape a caller can handle uniformly.
            return ScanBackResult.rejected("INVALID_CONDITION",
                "condition must be 'good' or 'condemned'",
                "يجب أن تكون الحالة 'good' أو 'condemned'");
        }
        if (isBazarTransfer(transferId, tenantId) != bazarCaller) {
            return bazarCaller ? ScanBackResult.rejected("TRANSFER_NOT_FOUND", "Transfer not found",
                                                         "لم يتم العثور على عملية النقل")
                               : ScanBackResult.rejected("BAZAR_TRANSFER_LOCKED", BAZAR_LOCKED_EN, BAZAR_LOCKED_AR);
        }

        // 1. Transfer must exist and be reconciling.
        List<String> transferStatusRows = jdbc.queryForList(
            "SELECT status FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        if (transferStatusRows.isEmpty()) {
            return ScanBackResult.rejected("TRANSFER_NOT_FOUND",
                "Transfer not found",
                "لم يتم العثور على عملية النقل");
        }
        if (!"reconciling".equals(transferStatusRows.get(0))) {
            return ScanBackResult.rejected("TRANSFER_NOT_RECONCILING",
                "Transfer is not in reconciling status (status: " + transferStatusRows.get(0) + ")",
                "عملية النقل ليست في حالة المطابقة (الحالة الحالية: " + transferStatusRows.get(0) + ")");
        }

        // 2. Piece lookup — same triple-match (and normalized lookup value) as scanOut().
        String lookup = ScanInputNormalizer.normalizePiece(barcode);
        List<Map<String, Object>> pieceRows = jdbc.queryForList(
            "SELECT p.id, p.variant_id FROM pieces p " +
            "WHERE (p.barcode = ? OR p.id = ? OR p.short_code = ?) AND p.tenant_id = ?",
            lookup, lookup, lookup, tenantId);
        if (pieceRows.isEmpty()) {
            var unused = com.traceability.labels.UnusedLabels.find(jdbc, tenantId, lookup);
            if (unused.isPresent()) {
                return ScanBackResult.rejected(com.traceability.labels.UnusedLabels.codeFor(unused.get()),
                    com.traceability.labels.UnusedLabels.messageEnFor(unused.get()),
                    com.traceability.labels.UnusedLabels.messageArFor(unused.get()));
            }
            return ScanBackResult.rejected("PIECE_NOT_FOUND",
                "Barcode not found in inventory",
                "لم يتم العثور على الباركود في المخزون");
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
                "Piece is not outstanding on this transfer",
                "القطعة ليست معلقة على عملية النقل هذه");
        }

        // 4. Transition. Left uncaught by design (see scanOut()) — the claim above already
        //    uniquely won resolution of this piece on this transfer.
        UUID fulfillmentLocationId = fulfillmentLocationId(tenantId);
        ledger.transition(pieceId, PieceStatus.OUT_ON_TRANSFER, targetStatus,
            good ? "returned_from_transfer" : "condemned_at_vendor", actorUserId,
            new TransitionContext(null, null, fulfillmentLocationId, null,
                reconcileScanMeta(good, transferId, viaPhone, clientRequestId, byCount)));

        // 5. Physical unit is back at the warehouse either way — move current_location_id.
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?",
            fulfillmentLocationId, pieceId);

        // Issue 2: back sellable at the main warehouse → +1 there when its departure had left
        // Shopify's count (decided by ShopifyInventoryService.onTransferReturn). Condemned → no write:
        // the unit already left Shopify at send and isn't sellable.
        if (good) {
            ShopifyInventoryService.afterCommit(() ->
                shopifyInventory.onTransferReturn(tenantId, pieceId, transferId, fulfillmentLocationId));
        }

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

    private String reconcileScanMeta(boolean good, UUID transferId, boolean viaPhone, String clientRequestId,
                                     boolean byCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        m.put("verified", true);
        m.put("reconciliation", "scan");
        if (!good) m.put("attributed_to", "vendor");
        if (viaPhone) m.put("via", "phone");                 // Q1b
        if (clientRequestId != null) m.put("client_request_id", clientRequestId);   // Bazar B5 retry key
        if (byCount) m.put("by", "count");                                           // Bazar B7d-2
        return writeJson(m);
    }

    /**
     * Bazar POS B5: a piece taken back DAMAGED at the bazar (B3: its transfer piece already 'condemned', the piece
     * damaged at the event location) is brought home — it arrives at the main warehouse still damaged. Nothing about
     * the transfer's counts changes and there is NO Shopify write (a damaged unit isn't sellable; it left Shopify at
     * send). The ledger records the arrival (status unchanged). Returns false when the piece isn't a damaged piece of
     * this transfer still away from main. ONE caller: BazarEndService.
     */
    /**
     * U1a: {@code via} (e.g. "transfer_close" — the close brings the rest home) is written into the event's metadata.
     * Keep this full-body overload FIRST, above the 4-argument one: BazarEndTest's source guard
     * (damagedBringHome_movesThePieceOnlyThroughInventoryLedger) reads the body of the first
     * {@code bringBackDamagedForBazar(} in this file and checks it moves the piece only through InventoryLedger.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean bringBackDamagedForBazar(UUID transferId, String pieceId, UUID actorUserId, String clientRequestId,
                                            String via) {
        UUID tenantId = TenantContext.require();
        if (!isBazarTransfer(transferId, tenantId)) throw transferNotFound();
        UUID main = fulfillmentLocationId(tenantId);
        // The transfer's own guard: this piece was taken back damaged ON this bazar transfer (row locked).
        boolean condemned = !jdbc.queryForList(
            "SELECT 1 FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ? AND tenant_id = ? " +
            "AND outcome = 'condemned' FOR UPDATE", Integer.class, transferId, pieceId, tenantId).isEmpty();
        if (!condemned) return false;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        if (clientRequestId != null) m.put("client_request_id", clientRequestId);
        if (via != null) m.put("via", via);
        // The location move + its event go through InventoryLedger — the one writer of piece changes.
        if (!ledger.bringBackDamaged(pieceId, main, actorUserId, writeJson(m))) return false;
        return true;
    }

    /** The scan / tap path (no {@code via}). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean bringBackDamagedForBazar(UUID transferId, String pieceId, UUID actorUserId, String clientRequestId) {
        return bringBackDamagedForBazar(transferId, pieceId, actorUserId, clientRequestId, null);
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
        classifyShortfallCore(transferId, lineId, counts, actorUserId, false, null);
    }

    /**
     * Bazar POS B5: the leftovers nobody scanned back, marked LOST — exactly classifyShortfall(…, lost = n, …)
     * (out_on_transfer → lost, quantity-based, vendor-attributed, qty_lost), but on the EXACT pieces the owner
     * listed (still oldest-first, still FOR UPDATE on outstanding rows only — a sold / returned piece can never be
     * picked). ONE caller: BazarEndService.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markLostForBazar(UUID transferId, UUID lineId, List<String> pieceIds, UUID actorUserId) {
        classifyShortfallCore(transferId, lineId, new ShortfallCounts(0, pieceIds.size(), 0), actorUserId, true, pieceIds);
    }

    private void classifyShortfallCore(UUID transferId, UUID lineId, ShortfallCounts counts, UUID actorUserId,
                                       boolean bazarCaller, List<String> exactPieces) {
        UUID tenantId = TenantContext.require();
        if (isBazarTransfer(transferId, tenantId) != bazarCaller) {
            throw bazarCaller ? transferNotFound() : bazarLocked();
        }

        if (counts.sold() < 0 || counts.lost() < 0 || counts.condemnedNotReturned() < 0) {
            throw new TransferException(TransferException.Code.SHORTFALL_INVALID_COUNTS,
                "Counts must be non-negative",
                "يجب أن تكون الأعداد غير سالبة",
                HttpStatus.BAD_REQUEST);
        }
        int totalRequested = counts.sold() + counts.lost() + counts.condemnedNotReturned();
        if (totalRequested == 0) {
            throw new TransferException(TransferException.Code.SHORTFALL_EMPTY_REQUEST,
                "At least one count must be positive",
                "يجب أن يكون عدد واحد على الأقل أكبر من صفر",
                HttpStatus.BAD_REQUEST);
        }

        List<String> transferStatusRows = jdbc.queryForList(
            "SELECT status FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        if (transferStatusRows.isEmpty()) {
            throw transferNotFound();
        }
        if (!"reconciling".equals(transferStatusRows.get(0))) {
            throw transferNotReconciling(transferStatusRows.get(0));
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
            throw new TransferException(TransferException.Code.TRANSFER_LINE_NOT_FOUND,
                "Transfer line not found",
                "لم يتم العثور على بند النقل",
                HttpStatus.NOT_FOUND);
        }

        int remainingOutstanding = (Integer) line.get("qty_out")
            - (Integer) line.get("qty_returned_good")
            - (Integer) line.get("qty_condemned")
            - (Integer) line.get("qty_sold")
            - (Integer) line.get("qty_lost");
        if (totalRequested > remainingOutstanding) {
            throw new TransferException(TransferException.Code.SHORTFALL_EXCEEDS_OUTSTANDING,
                "Requested " + totalRequested + " exceeds remaining outstanding (" +
                remainingOutstanding + ") on this line",
                "الكمية المطلوبة (" + totalRequested + ") تتجاوز عدد القطع المتبقية (" +
                remainingOutstanding + ") على هذا البند",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // FIFO claim: lock the exact pieces this call will resolve.
        List<String> pieceIds = exactPieces == null
            ? jdbc.query(
                "SELECT piece_id FROM transfer_pieces " +
                "WHERE transfer_id = ? AND line_id = ? AND tenant_id = ? AND outcome IS NULL " +
                "ORDER BY created_at ASC LIMIT ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("piece_id"),
                transferId, lineId, tenantId, totalRequested)
            : jdbc.query(
                "SELECT piece_id FROM transfer_pieces " +
                "WHERE transfer_id = ? AND line_id = ? AND tenant_id = ? AND outcome IS NULL AND piece_id = ANY(?) " +
                "ORDER BY created_at ASC LIMIT ? FOR UPDATE",
                (rs, rowNum) -> rs.getString("piece_id"),
                transferId, lineId, tenantId, exactPieces.toArray(new String[0]), totalRequested);

        if (pieceIds.size() < totalRequested) {
            // Shouldn't happen given the balance check above (same transaction — no
            // concurrent writer could have shrunk the outstanding set since the counts
            // were read) — defensive, not expected to fire.
            throw new TransferException(TransferException.Code.SHORTFALL_RACE_CONFLICT,
                "Fewer outstanding pieces than requested — reload and retry",
                "عدد القطع المتبقية أقل من المطلوب — يرجى إعادة التحميل والمحاولة مرة أخرى",
                HttpStatus.CONFLICT);
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
        closeTransferCore(transferId, actorUserId, false);
    }

    /** Bazar POS B5: reconciling → closed for the bazar's linked transfer — exactly closeTransfer() (refused while any
     *  piece is outstanding). ONE caller: BazarEndService. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void closeTransferForBazar(UUID transferId, UUID actorUserId) {
        closeTransferCore(transferId, actorUserId, true);
    }

    private void closeTransferCore(UUID transferId, UUID actorUserId, boolean bazarCaller) {
        UUID tenantId = TenantContext.require();
        if (isBazarTransfer(transferId, tenantId) != bazarCaller) {
            throw bazarCaller ? transferNotFound() : bazarLocked();
        }

        List<String> transferStatusRows = jdbc.queryForList(
            "SELECT status FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        if (transferStatusRows.isEmpty()) {
            throw transferNotFound();
        }
        if (!"reconciling".equals(transferStatusRows.get(0))) {
            throw transferNotReconciling(transferStatusRows.get(0));
        }

        Integer outstanding = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND tenant_id = ? AND outcome IS NULL",
            Integer.class, transferId, tenantId);
        if (outstanding != null && outstanding > 0) {
            throw new TransferException(TransferException.Code.TRANSFER_HAS_OUTSTANDING_PIECES,
                outstanding + " piece(s) still outstanding on this transfer — resolve before closing",
                "لا تزال هناك " + outstanding + " قطعة معلقة على عملية النقل هذه — يجب حلها قبل الإغلاق",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }

        int rows = jdbc.update(
            "UPDATE transfers SET status = 'closed', closed_by = ?, closed_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'reconciling'",
            actorUserId, transferId, tenantId);
        if (rows == 0) {
            throw new TransferException(TransferException.Code.TRANSFER_CLOSE_RACE_CONFLICT,
                "Transfer status changed concurrently — reload and retry",
                "تغيرت حالة عملية النقل في نفس الوقت — يرجى إعادة التحميل والمحاولة مرة أخرى",
                HttpStatus.CONFLICT);
        }
    }

    // ── One-shot close (Relocate, FR-22.10) ─────────────────────────────────

    /**
     * preparing → closed directly, NO reconciling stage — the structural opposite of
     * closeTransfer(): that method GATES on outcome-IS-NULL == 0 (rejects while anything is
     * outstanding); this method ACTS ON the outcome-IS-NULL rows (there is no scan-back step
     * to wait for in a one-way relocate — the send-out scan is the only physical evidence
     * this model has, by design). Structurally mirrors classifyShortfall()'s FOR-UPDATE
     * bulk-resolve shape, not closeTransfer()'s gate-and-flip shape.
     *
     * Per outstanding piece, BOTH of these writes happen in the SAME transaction — #1 is not
     * optional:
     *   1. UPDATE transfer_pieces SET outcome='relocated', ... WHERE outcome IS NULL — frees
     *      transfer_pieces_one_active's UNIQUE (piece_id) WHERE outcome IS NULL slot. Skipping
     *      this leaves outcome NULL forever, permanently blocking this piece_id from ever being
     *      claimed by a future return transfer (FR-22.11 / B2) — un-pickable (correctly) AND
     *      un-returnable (a genuine stranding bug, not a hypothetical one).
     *   2. ledger.transition(OUT_ON_TRANSFER, TRANSFERRED_OUT, 'relocated_out', ...) — the
     *      un-pickable terminal transition itself; FulfillService.scan()'s existing
     *      status='available' gate rejects this status unmodified, no pick-path change.
     *
     * outcome_verified=true (unlike the shortfall paths' false): the send-out scan IS the
     * physical verification here — there is no separate arrival-confirmation step in this
     * model, by design (no reconcile stage exists for a relocate_out transfer).
     *
     * FOR UPDATE lock on the outstanding rows gives this the same race behavior as
     * classifyShortfall(): a concurrent second call blocks on the row lock, then (after the
     * first commits) re-reads and finds outcome already set, so its own SELECT returns an
     * empty set — harmless no-op loop — and its closing UPDATE (status='preparing' guard) then
     * naturally affects 0 rows, surfacing TRANSFER_CLOSE_RACE_CONFLICT instead of double-
     * processing anything.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void closeOneWay(UUID transferId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // Transfer row FOR UPDATE first (class javadoc): a scan in flight finishes before the
        // outstanding-rows SELECT below runs, so its piece is relocated too, never stranded.
        Map<String, Object> transfer = lockTransfer(transferId, tenantId);
        refuseBazar(transfer);
        String status = (String) transfer.get("status");
        String mode   = (String) transfer.get("transfer_mode");

        if (!"relocate_out".equals(mode)) {
            throw new TransferException(TransferException.Code.TRANSFER_NOT_RELOCATE_OUT,
                "This transfer is not a relocate transfer (mode: " + mode + ") — use close instead",
                "عملية النقل هذه ليست عملية نقل نهائي (النمط: " + mode + ") — استخدم الإغلاق العادي بدلاً من ذلك",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!"preparing".equals(status)) {
            throw transferNotPreparing(status);
        }

        // FIFO claim: lock every outstanding row on this transfer — mirrors
        // classifyShortfall()'s FOR UPDATE shape.
        List<String> pieceIds = jdbc.query(
            "SELECT piece_id FROM transfer_pieces " +
            "WHERE transfer_id = ? AND tenant_id = ? AND outcome IS NULL " +
            "ORDER BY created_at ASC FOR UPDATE",
            (rs, rowNum) -> rs.getString("piece_id"),
            transferId, tenantId);

        for (String pieceId : pieceIds) {
            // Write #1 — NOT optional (see method javadoc): frees the one-active-transfer
            // slot for this piece_id before the ledger transition below.
            jdbc.update(
                "UPDATE transfer_pieces SET outcome = 'relocated', outcome_verified = true, " +
                "outcome_at = now(), outcome_by = ? " +
                "WHERE transfer_id = ? AND piece_id = ? AND outcome IS NULL",
                actorUserId, transferId, pieceId);

            // Write #2 — the un-pickable terminal transition. Left uncaught by design (same
            // reasoning as every other call site in this class): the FOR UPDATE lock above
            // already gives this call exclusive access to the piece row for the duration of
            // this transaction, so StateConflictException is not expected here.
            ledger.transition(pieceId, PieceStatus.OUT_ON_TRANSFER, PieceStatus.TRANSFERRED_OUT,
                "relocated_out", actorUserId,
                new TransitionContext(null, null, null, null, relocatedOutMeta(transferId)));
        }

        int rows = jdbc.update(
            "UPDATE transfers SET status = 'closed', closed_by = ?, closed_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND status = 'preparing'",
            actorUserId, transferId, tenantId);
        if (rows == 0) {
            throw new TransferException(TransferException.Code.TRANSFER_CLOSE_RACE_CONFLICT,
                "Transfer status changed concurrently — reload and retry",
                "تغيرت حالة عملية النقل في نفس الوقت — يرجى إعادة التحميل والمحاولة مرة أخرى",
                HttpStatus.CONFLICT);
        }
        // Issue 2: a permanent move is "sent" when it closes — same claim as markSent.
        shopifySync.claimSend(tenantId, transferId);
    }

    private String relocatedOutMeta(UUID transferId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transfer_id", transferId.toString());
        m.put("verified", true);
        m.put("reconciliation", "one_shot");
        return writeJson(m);
    }

    // ── Reprint outstanding labels ───────────────────────────────────────────

    /**
     * Reprints the barcodes of every piece still outstanding on this transfer (transfer_pieces
     * WHERE outcome IS NULL) — the physical relabel step (identity is lost at the vendor;
     * this reprints the SAME piece IDs, it never mints new ones). Deliberately does NOT reuse
     * ReturnSessionController's per-piece reprint endpoint: that one is hard-gated to pieces in
     * return_pending_inspection/damaged status and would reject an out_on_transfer piece
     * outright. LabelService.generatePieceLabels() renders every piece into ONE PDF document
     * (one page per piece, no page merging), then InventoryLedger.recordLabelReprinted() (the existing
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
            throw transferNotFound();
        }

        List<String> outstandingPieceIds = jdbc.queryForList(
            "SELECT piece_id FROM transfer_pieces WHERE transfer_id = ? AND tenant_id = ? AND outcome IS NULL " +
            "ORDER BY created_at ASC",
            String.class, transferId, tenantId);
        if (outstandingPieceIds.isEmpty()) {
            throw new TransferException(TransferException.Code.TRANSFER_NO_OUTSTANDING_PIECES,
                "No outstanding pieces on this transfer",
                "لا توجد قطع معلقة على عملية النقل هذه",
                HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // One document for every outstanding piece — no page merging (merging single-page PDFs
        // lost every barcode image once the source documents were closed).
        byte[] pdf = labelService.generatePieceLabels(outstandingPieceIds, null, null);
        for (String pieceId : outstandingPieceIds) {
            ledger.recordLabelReprinted(pieceId, actorUserId, null, null, null);
        }
        return pdf;
    }

    // ── List / get (consignment view) ───────────────────────────────────────

    private static final Set<String> VALID_STATUS_FILTERS = Set.of("open", "closed", "all");

    /** "What's outside our walls, with whom, since when" — preparing + sent + reconciling.
     *  Default view. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOpen() {
        return listOpen("open");
    }

    /** statusFilter: "open" (preparing + sent + reconciling, the default) | "closed" (closed +
     *  cancelled) | "all". The filter keeps its "open" name — it's the wire value clients send. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOpen(String statusFilter) {
        UUID tenantId = TenantContext.require();
        String filter = VALID_STATUS_FILTERS.contains(statusFilter) ? statusFilter : "open";
        String statusPredicate = switch (filter) {
            case "closed" -> "t.status IN ('closed', 'cancelled')";
            case "all"    -> "TRUE";
            default       -> "t.status IN ('preparing', 'sent', 'reconciling')";
        };
        return jdbc.queryForList(
            "SELECT t.id, t.transfer_type, t.transfer_mode, t.status, t.note, t.expected_return_at, " +
            "       t.created_by, t.created_at, " +
            "       t.destination_location_id, loc.name AS destination_location_name, " +
            // Bazar POS B1: a bazar transfer is shown read-only, tagged, with its event.
            "       be.id AS bazar_event_id, be.name AS bazar_event_name, " +
            "       (SELECT COUNT(*) FROM transfer_pieces tp " +
            "        WHERE tp.transfer_id = t.id AND tp.outcome IS NULL) AS outstanding_count " +
            "FROM transfers t " +
            "JOIN locations loc ON loc.id = t.destination_location_id " +
            "LEFT JOIN bazar_events be ON be.transfer_id = t.id AND be.tenant_id = t.tenant_id " +
            "WHERE t.tenant_id = ? AND " + statusPredicate + " " +
            "ORDER BY t.created_at DESC, t.id DESC",
            tenantId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getTransfer(UUID transferId) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT t.id, t.transfer_type, t.transfer_mode, t.status, t.note, t.expected_return_at, " +
            "       t.created_by, t.created_at, t.closed_by, t.closed_at, " +
            "       t.sent_at, t.sent_by, t.cancelled_at, t.cancelled_by, " +
            "       t.reconcile_started_at, t.reconcile_started_by, " +
            "       t.destination_location_id, loc.name AS destination_location_name, " +
            "       t.source_location_id, srcloc.name AS source_location_name, " +
            "       be.id AS bazar_event_id, be.name AS bazar_event_name " +
            "FROM transfers t " +
            "JOIN locations loc ON loc.id = t.destination_location_id " +
            "LEFT JOIN locations srcloc ON srcloc.id = t.source_location_id " +
            "LEFT JOIN bazar_events be ON be.transfer_id = t.id AND be.tenant_id = t.tenant_id " +
            "WHERE t.id = ? AND t.tenant_id = ?",
            transferId, tenantId);
        if (rows.isEmpty()) {
            throw transferNotFound();
        }
        Map<String, Object> transfer = new LinkedHashMap<>(rows.get(0));
        transfer.put("lines", jdbc.queryForList(
            "SELECT tl.id, tl.variant_id, v.sku, v.title AS variant_title, pr.title AS product_title, " +
            "       tl.qty_out, tl.qty_returned_good, tl.qty_condemned, tl.qty_sold, tl.qty_lost, " +
            // Q1b: how many of this line's scans (out and back) came from a paired phone —
            // piece_events is the history; a phone scan's event carries {"via":"phone"}.
            "       (SELECT COUNT(*) FROM piece_events pe " +
            "        JOIN transfer_pieces tp ON tp.piece_id = pe.piece_id AND tp.transfer_id = tl.transfer_id " +
            "                               AND tp.line_id = tl.id AND tp.tenant_id = tl.tenant_id " +
            "        WHERE pe.tenant_id = tl.tenant_id AND pe.metadata->>'transfer_id' = tl.transfer_id::text " +
            "          AND pe.metadata->>'via' = 'phone') AS phone_scans " +
            "FROM transfer_lines tl " +
            "JOIN variants v  ON v.id  = tl.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE tl.transfer_id = ? AND tl.tenant_id = ? " +
            "ORDER BY pr.title ASC, v.title ASC",
            transferId, tenantId));
        transfer.put("outstandingCount", jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND tenant_id = ? AND outcome IS NULL",
            Integer.class, transferId, tenantId));
        // Every piece ever scanned onto the transfer, whatever its outcome — decides whether
        // Mark as sent (>= 1) and Cancel (0) are offered.
        transfer.put("piecesEverCount", piecesEverScanned(transferId, tenantId));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) transfer.get("lines");
        transfer.put("phoneScanCount", lines.stream().mapToLong(l -> ((Number) l.get("phone_scans")).longValue()).sum());
        return transfer;
    }

    // ── Shared TransferException factories (codes reused across several methods) ────

    private TransferException transferNotFound() {
        return new TransferException(TransferException.Code.TRANSFER_NOT_FOUND,
            "Transfer not found",
            "لم يتم العثور على عملية النقل",
            HttpStatus.NOT_FOUND);
    }

    private TransferException transferNotPreparing(String status) {
        return new TransferException(TransferException.Code.TRANSFER_NOT_PREPARING,
            GENERIC_UNAVAILABLE_EN,
            GENERIC_UNAVAILABLE_AR,
            HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private TransferException wrongMode(String mode) {
        return new TransferException(TransferException.Code.TRANSFER_WRONG_MODE,
            GENERIC_UNAVAILABLE_EN,
            GENERIC_UNAVAILABLE_AR,
            HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** The conditional UPDATE matched 0 rows despite the FOR UPDATE lock — not expected;
     *  kept as a defensive, retryable answer rather than a silent no-op. */
    private TransferException statusRaceConflict() {
        return new TransferException(TransferException.Code.TRANSFER_CLOSE_RACE_CONFLICT,
            "Transfer status changed concurrently — reload and retry",
            "تغيرت حالة عملية النقل في نفس الوقت — يرجى إعادة التحميل والمحاولة مرة أخرى",
            HttpStatus.CONFLICT);
    }

    /** SELECT … FOR UPDATE on the transfer row — the first step of every status change
     *  (see the class javadoc). Throws TRANSFER_NOT_FOUND when absent. */
    private Map<String, Object> lockTransfer(UUID transferId, UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT status, transfer_mode, transfer_type FROM transfers WHERE id = ? AND tenant_id = ? FOR UPDATE",
            transferId, tenantId);
        if (rows.isEmpty()) {
            throw transferNotFound();
        }
        return rows.get(0);
    }

    /** transfer_pieces rows are never deleted (only given an outcome) — except a bazar piece taken back off the send
     *  before Mark sent (Bazar B7d, {@link #unsend}) — so this is every piece on the transfer. */
    private int piecesEverScanned(UUID transferId, UUID tenantId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ? AND tenant_id = ?",
            Integer.class, transferId, tenantId);
        return n == null ? 0 : n;
    }

    // ── Bazar lock (Bazar POS B1) ─────────────────────────────────────────────

    private static final String BAZAR_LOCKED_EN =
        "This transfer belongs to a bazar event. Manage it from the bazar.";
    private static final String BAZAR_LOCKED_AR =
        "عملية النقل هذه تابعة لبازار. أدِرها من شاشة البازار.";

    private TransferException bazarLocked() {
        return new TransferException(TransferException.Code.BAZAR_TRANSFER_LOCKED,
            BAZAR_LOCKED_EN, BAZAR_LOCKED_AR, HttpStatus.CONFLICT);
    }

    /** transfer_type never changes after create, so this needs no lock. */
    private boolean isBazarTransfer(UUID transferId, UUID tenantId) {
        List<String> t = jdbc.queryForList(
            "SELECT transfer_type FROM transfers WHERE id = ? AND tenant_id = ?", String.class, transferId, tenantId);
        return !t.isEmpty() && BAZAR_TYPE.equals(t.get(0));
    }

    private void refuseBazar(Map<String, Object> lockedTransfer) {
        if (BAZAR_TYPE.equals(lockedTransfer.get("transfer_type"))) {
            throw bazarLocked();
        }
    }

    private TransferException transferNotReconciling(String status) {
        return new TransferException(TransferException.Code.TRANSFER_NOT_RECONCILING,
            "Transfer is not in reconciling status (status: " + status + ")",
            "عملية النقل ليست في حالة المطابقة (الحالة الحالية: " + status + ")",
            HttpStatus.UNPROCESSABLE_ENTITY);
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
            @JsonProperty("message_en") String messageEn,
            @JsonProperty("message_ar") String messageAr,
            String  pieceId,
            String  barcode,
            UUID    variantId,
            UUID    lineId,
            int     qtyOut) {

        static ScanOutResult success(String pieceId, String barcode, UUID variantId, UUID lineId, int qtyOut) {
            return new ScanOutResult(true, "SCANNED", null, null, pieceId, barcode, variantId, lineId, qtyOut);
        }

        static ScanOutResult rejected(String code, String messageEn, String messageAr) {
            return new ScanOutResult(false, code, messageEn, messageAr, null, null, null, null, 0);
        }
    }

    public record ScanBackResult(
            boolean success,
            String  code,
            @JsonProperty("message_en") String messageEn,
            @JsonProperty("message_ar") String messageAr,
            String  pieceId,
            String  barcode,
            UUID    variantId,
            String  outcome) {

        static ScanBackResult success(String pieceId, String barcode, UUID variantId, String outcome) {
            return new ScanBackResult(true, "SCANNED", null, null, pieceId, barcode, variantId, outcome);
        }

        static ScanBackResult rejected(String code, String messageEn, String messageAr) {
            return new ScanBackResult(false, code, messageEn, messageAr, null, null, null, null);
        }
    }

    /** Bazar B7d — a count send / a removal: the pieces moved (or, refused, how many could be: {@code available}). */
    public record CountResult(
            boolean success,
            String  code,
            @JsonProperty("message_en") String messageEn,
            @JsonProperty("message_ar") String messageAr,
            UUID    variantId,
            int     count,
            int     available,
            List<String> pieceIds,
            int     qtyOut,
            boolean replayed,
            // Bazar U1b: the label codes (short codes) of the moved pieces that carry one, so the bazar screen can name
            // the pieces to put back after a removal. Null — left out of the JSON — everywhere else.
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @JsonProperty("labelled_codes") List<String> labelledCodes,
            // Bazar U1c: a send by count that found fewer unlabelled pieces than asked — the quantity asked, and the
            // labelled pieces of the size at Main (to scan). Null — left out — everywhere else.
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            Integer requested,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            @JsonProperty("labelled_at_main") Integer labelledAtMain) {

        static CountResult success(UUID variantId, List<String> pieceIds, int qtyOut, boolean replayed) {
            return new CountResult(true, "SENT", null, null, variantId, pieceIds.size(), 0, pieceIds, qtyOut, replayed,
                null, null, null);
        }

        static CountResult rejected(String code, String messageEn, String messageAr, int available) {
            return new CountResult(false, code, messageEn, messageAr, null, 0, available, List.of(), 0, false, null,
                null, null);
        }

        public CountResult withLabelledCodes(List<String> codes) {
            return new CountResult(success, code, messageEn, messageAr, variantId, count, available, pieceIds, qtyOut,
                replayed, codes, requested, labelledAtMain);
        }

        /**
         * U1c — fewer unlabelled pieces than asked were sent: code SENT_PARTIAL and the message (EN + AR). "The other X"
         * = the rest asked for, at most the labelled pieces at Main; none → only "Only N without labels."
         */
        CountResult partial(int asked, int labelled) {
            int n = count, x = Math.min(asked - n, labelled);
            String en = (n == 1 ? "Only 1 without a label." : "Only " + n + " without labels.")
                + (x <= 0 ? "" : x == 1 ? " The other 1 has a label — scan it." : " The other " + x + " have labels — scan them.");
            String ar = (n == 1 ? "قطعة واحدة فقط بدون ملصق." : "بدون ملصقات " + n + " فقط.")
                + (x <= 0 ? "" : x == 1 ? " القطعة الباقية عليها ملصق — امسحها." : " الـ " + x + " الباقية عليها ملصقات — امسحها.");
            return new CountResult(success, "SENT_PARTIAL", en, ar, variantId, count, available, pieceIds, qtyOut,
                replayed, labelledCodes, asked, labelled);
        }

        /** Top-up — the batch list's partial count: "N added · M have labels — scan them" (M = labelled pieces of the
         *  size at Main not on the list; none → only "N added"). Code SENT_PARTIAL, like the send by count. */
        CountResult batchPartial(int asked, int labelled) {
            int n = count;
            String en = n + " added" + (labelled <= 0 ? "" : labelled == 1 ? " · 1 has a label — scan it" : " · " + labelled + " have labels — scan them");
            String ar = "أُضيفت " + n + (labelled <= 0 ? "" : labelled == 1 ? " · 1 عليها ملصق — امسحها" : " · " + labelled + " عليها ملصقات — امسحها");
            return new CountResult(success, "SENT_PARTIAL", en, ar, variantId, count, available, pieceIds, qtyOut,
                replayed, labelledCodes, asked, labelled);
        }
    }

    /** Bazar B9c — a receipt send: the pieces moved, the sizes they belong to, and how many of the receipts are left. */
    public record ReceiptSendResult(
            boolean success,
            String  code,
            @JsonProperty("message_en") String messageEn,
            @JsonProperty("message_ar") String messageAr,
            List<String> pieceIds,
            List<UUID> variantIds,
            int     left,
            boolean replayed) {

        static ReceiptSendResult success(List<String> pieceIds, List<UUID> variantIds, int left, boolean replayed) {
            return new ReceiptSendResult(true, "SENT", null, null, pieceIds, variantIds, left, replayed);
        }

        static ReceiptSendResult rejected(String code, String messageEn, String messageAr) {
            return new ReceiptSendResult(false, code, messageEn, messageAr, List.of(), List.of(), 0, false);
        }
    }

    /** Bazar top-up — a batch send: the pieces sent and their sizes, or the listed pieces that blocked it. */
    public record BatchSendResult(
            boolean success,
            String  code,
            @JsonProperty("message_en") String messageEn,
            @JsonProperty("message_ar") String messageAr,
            List<String> pieceIds,
            List<UUID> variantIds,
            @JsonProperty("blocked_codes") List<String> blockedCodes,
            boolean replayed) {

        record Refusal(String code, String en, String ar) {}

        static BatchSendResult success(List<String> pieceIds, List<UUID> variantIds, boolean replayed) {
            return new BatchSendResult(true, "SENT", null, null, pieceIds, variantIds, List.of(), replayed);
        }

        static BatchSendResult rejected(String code, String messageEn, String messageAr) {
            return new BatchSendResult(false, code, messageEn, messageAr, List.of(), List.of(), List.of(), false);
        }

        static BatchSendResult blocked(List<String> codes) {
            int n = codes.size();
            return new BatchSendResult(false, "BATCH_PIECES_CHANGED",
                (n == 1 ? "1 piece on this batch isn't" : n + " pieces on this batch aren't") +
                    " available any more — nothing was sent. Remove " + (n == 1 ? "it" : "them") + " and send again: " +
                    String.join(", ", codes),
                (n == 1 ? "قطعة واحدة" : n + " قطع") + " في هذه الدفعة لم تعد متاحة — لم يُرسل شيء. احذفها ثم أرسل مرة أخرى: " +
                    String.join("، ", codes),
                List.of(), List.of(), codes, false);
        }
    }

    /** Shortfall quantities to classify on one transfer_lines row (classifyShortfall). */
    public record ShortfallCounts(int sold, int lost, int condemnedNotReturned) {}
}
