package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The single gateway for all piece state changes AND piece creation.
 *
 * Two writers of piece_events — both owned by this class:
 *   transition()    – state changes on EXISTING pieces; includes race guard (WHERE status=?).
 *   batchReceive()  – piece CREATION only (and its sibling receiveFactoryLabeled() — the same creation with the
 *                     factory label codes scanned off the pieces, slice 6b); two multi-row INSERTs in one ACID transaction,
 *                     from_status=NULL→available, one received event per piece. This is NOT
 *                     a bypass of transition(): piece creation has no prior state to race on.
 *                     Do NOT refactor batchReceive() to call transition() — that breaks the
 *                     null→available path and destroys the batch performance guarantee.
 *
 * Caller contract: TenantContext must be set before calling so that
 * TenantAwareConnection fires SET LOCAL app.current_tenant inside the transaction.
 * With app_user (no BYPASSRLS) a missing context causes the UPDATE to match 0 rows
 * (RLS blocks it) and the diagnostic SELECT also returns nothing → PieceNotFoundException.
 *
 * Illegal-transition guard fires before any DB access; the allowed set encodes the
 * full piece state machine so callers cannot request an invalid (from, to) pair.
 */
@Service
public class InventoryLedger {

    // ---- piece state machine -----------------------------------------------

    private static final Set<String> ALLOWED = Set.of(
        // forward fulfillment path
        "available:reserved",
        "reserved:packed",
        "packed:awaiting_pickup",
        "awaiting_pickup:with_courier",
        "with_courier:delivered",

        // self-pickup handover: pieces go packed→delivered directly (no courier leg)
        "packed:delivered",
        // pickup session close: Bosta state-20 (route-assigned) webhook never fired before the
        // merchant closed the scan session and physically handed packages to the courier.
        // Blocking the close because Bosta hasn't caught up would produce a false custody state
        // (Traced says 'packed', package is in the courier's van). The session close is the
        // authoritative handover event.
        "packed:with_courier",
        // Bosta backfill / courier lag: delivery confirmed while piece is still at awaiting_pickup
        // (Bosta never fired the pickup state update — e.g. same-day delivery, or missed webhook).
        "awaiting_pickup:delivered",

        // cancellation / unwind
        "reserved:available",
        "packed:available",
        "awaiting_pickup:available",

        // courier return path
        "with_courier:return_in_transit",
        "return_in_transit:return_pending_inspection",
        // Bosta-lag paths: piece still at with_courier or awaiting_pickup when intake scan
        // fires (state 41 RTO never arrived / webhooks not yet configured). Unexpected flag set by caller.
        "with_courier:return_pending_inspection",
        "awaiting_pickup:return_pending_inspection",
        // Customer-initiated return after confirmed delivery (guarded by customer_return_window_days).
        "delivered:return_pending_inspection",
        "return_pending_inspection:available",
        "return_pending_inspection:damaged",

        // manual adjustments (operator-initiated)
        "available:damaged",
        "available:lost",
        "available:destroyed",
        "damaged:destroyed",
        "with_courier:lost",
        // FR-21 stock-take write-off: StockTakeReconciliationService.resolveLost() targets
        // damaged pieces whose status_at_open was 'damaged' — the same free-stock write-off
        // path as available:lost, just for pieces already recorded damaged at snapshot time.
        // No other caller uses this edge.
        "damaged:lost",

        // found (lost → back in stock)
        "lost:available",

        // FR-22 transfers: send-out and reconcile (gate G1, approved by Marawan).
        // damaged/lost were already non-terminal before this (damaged:destroyed,
        // damaged:lost, lost:available above) — out_on_transfer:damaged and
        // out_on_transfer:lost are additions to an already-open graph, not the
        // first opening of a terminal.
        "available:out_on_transfer",     // send-out scan
        // reconcile-only: legal only via TransferService, which updates
        // transfer_pieces.outcome in the same tx. Any other caller moving a piece out of
        // out_on_transfer orphans the transfer row — guard your path. (See
        // PieceAdjustService.adjustPiece()'s OUT_ON_TRANSFER guard for the enforcement.)
        "out_on_transfer:available",     // reconcile: scanned back, good condition
        "out_on_transfer:damaged",       // reconcile: scanned back, condemned
        "out_on_transfer:lost",          // reconcile: quantity-classified shortfall, unaccounted
        "out_on_transfer:sold",          // reconcile: quantity-classified shortfall, off-book sale

        // FR-22.10 — Relocate (one-way A->B, gate G2, approved by Marawan). The B1 one-shot
        // close (TransferService.closeOneWay()): no reconcile stage, straight to a terminal,
        // un-pickable status (FulfillService.scan()'s status='available' gate rejects it
        // unmodified — no pick-path change). Deliberately does NOT add the reverse edge
        // (transferred_out:out_on_transfer) here — that is FR-22.11 (B2, the B->A return
        // path), a separate approval; without it this status has no legal way out yet.
        "out_on_transfer:transferred_out",

        // FR-22.11 — Relocate Return (B2, approved by Marawan). The one legal exit from the
        // 'transferred_out' terminal — TransferService.returnScanOut() is its SOLE caller
        // (confirmed by grep in the B2 diagnosis: no other code reads or writes
        // 'transferred_out'). Opens the door back into the existing out_on_transfer graph
        // above (reconcile/shortfall paths), not a new terminal of its own.
        "transferred_out:out_on_transfer",

        // FR-13.x: Void (receiving-overcount / duplicate-entry correction — terminal, NOT a
        // loss; excluded from every loss-reporting site by construction because they all key on
        // literal status='lost', never a negated set — see PieceAdjustService.voidPiece()).
        "available:voided",

        // FR-13.x: On Hold (reversible QC/quarantine). Escalation edges reuse the SAME targets
        // already opened by the manual-adjustment block above (available:damaged/lost/destroyed
        // — this is not the first opening of a terminal, same reasoning as the FR-22 transfer
        // comment above). PieceAdjustService.unhold() guards on_hold:available separately from
        // return_pending_inspection:available (different Shopify effect — see ShopifyInventoryService).
        "available:on_hold",
        "on_hold:available",
        "on_hold:damaged",
        "on_hold:lost",
        "on_hold:destroyed",

        // D11 (approved 2026-10-10): Back to good — a damaged piece repaired, or graded damaged by
        // mistake, returns to available. PieceAdjustService.restore() is its only caller (manager+,
        // reason required); the Shopify side is decided by PieceShopifyRules.departureReached.
        // destroyed → available and voided → available stay refused (no such edge).
        "damaged:available",

        // C1 (2026-10-25): Lookup "Sold" — a piece sold outside Shopify (in person, Instagram, a
        // bazar). PieceAdjustService.markSold() is the only caller of available:sold (event
        // sold_offline; reserved/packed pieces are released first, through releaseForAdjust).
        // sold:available ("Not sold") is legal ONLY through PieceAdjustService.markNotSold(), which
        // refuses any sold piece whose sale was not a Lookup sold_offline (a transfer's off-book sale,
        // out_on_transfer:sold, has no way back). The Shopify side is decided by PieceShopifyRules.
        "available:sold",
        "sold:available"
    );

    // ---- SQL ---------------------------------------------------------------

    // Race guard lives in the WHERE clause: two concurrent callers both see
    // expectedStatus='available'; exactly one UPDATE wins (it locks and changes the
    // row), the loser re-evaluates WHERE after the winner commits, sees the new
    // status, and gets rows=0.
    //
    // This is correct under READ_COMMITTED (PostgreSQL's default) because the loser's
    // UPDATE re-reads the committed row state after the winner's transaction commits.
    // Under REPEATABLE READ it would instead throw a serialization error — the
    // isolation level is pinned below so the semantics never change accidentally.
    private static final String UPDATE_PIECE = """
            UPDATE pieces
            SET   status           = ?::piece_status,
                  current_order_id = ?,
                  last_event_at    = now(),
                  last_user_id     = ?
            WHERE id               = ?
              AND status           = ?::piece_status
            """;

    // tenant_id sourced from GUC — RLS WITH CHECK enforces it matches anyway.
    private static final String INSERT_EVENT = """
            INSERT INTO piece_events (
                tenant_id, piece_id, event_type, actor_user_id,
                order_id, shipment_id, location_id,
                from_status, to_status, metadata, raw_scan
            ) VALUES (
                NULLIF(current_setting('app.current_tenant', true), '')::uuid,
                ?, ?, ?,
                ?, ?, ?,
                ?::piece_status, ?::piece_status, ?::jsonb, ?
            )
            """;

    private static final String FETCH_STATUS =
            "SELECT status FROM pieces WHERE id = ?";

    // ---- constructor -------------------------------------------------------

    private final JdbcTemplate jdbc;

    public InventoryLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- public API --------------------------------------------------------

    /**
     * Transitions a piece from expectedStatus to newStatus within one DB transaction.
     *
     * READ_COMMITTED is pinned explicitly: the race-guard UPDATE relies on the loser
     * re-reading committed row state after the winner commits. Under REPEATABLE READ
     * the loser would get a serialization error instead of rows=0, breaking the
     * StateConflictException contract.
     *
     * @param pieceId        the ULID text PK of the piece
     * @param expectedStatus the status the piece must currently have (optimistic guard)
     * @param newStatus      the target status
     * @param eventType      free-text event label written to piece_events
     * @param actorUserId    UUID of the acting user; null means system/automated
     * @param ctx            optional order/shipment/location context + metadata
     * @throws IllegalTransitionException if (expectedStatus → newStatus) is not in the state machine
     * @throws StateConflictException     if piece exists but has a different status (concurrent change)
     * @throws PieceNotFoundException     if no piece row is visible under the current RLS context
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void transition(
            String pieceId,
            PieceStatus expectedStatus,
            PieceStatus newStatus,
            String eventType,
            UUID actorUserId,
            TransitionContext ctx) {

        // 1. State machine guard — no DB access, fast path for illegal pairs.
        if (!ALLOWED.contains(expectedStatus.db + ":" + newStatus.db)) {
            throw new IllegalTransitionException(expectedStatus, newStatus);
        }

        // 2. Atomic conditional UPDATE — this is the race guard.
        int rows = jdbc.update(UPDATE_PIECE,
                newStatus.db,
                ctx.currentOrderIdToSet(),
                actorUserId,
                pieceId,
                expectedStatus.db);

        // 3. Zero rows: piece may not exist, or status differed, or RLS blocked.
        //    The diagnostic SELECT drives which exception to throw.
        //    Throw BEFORE INSERT so no event row is ever written on this path.
        if (rows == 0) {
            String currentDb = jdbc.query(FETCH_STATUS,
                    rs -> rs.next() ? rs.getString("status") : null,
                    pieceId);
            if (currentDb == null) {
                // No row visible: piece not found or RLS made it invisible.
                throw new PieceNotFoundException(pieceId);
            }
            // Row exists but status differs: concurrent change or wrong expectation.
            throw new StateConflictException(pieceId, expectedStatus, PieceStatus.fromDb(currentDb));
        }

        // 4. Exactly one row updated → append the immutable event.
        jdbc.update(INSERT_EVENT,
                pieceId, eventType, actorUserId,
                ctx.orderId(), ctx.shipmentId(), ctx.locationId(),
                expectedStatus.db, newStatus.db, ctx.metadata(), ctx.rawScan());
    }

    // ---- batchReceive ------------------------------------------------------

    /**
     * Creates N pieces and their received events inside one @Transactional boundary.
     *
     * Design contract (do NOT modify without re-reading the invariant note in
     * the class Javadoc):
     *   - Round-trip 0: atomic counter claim in piece_counters — claims N sequential
     *                   short codes for this batch; rolls back with the transaction on failure
     *   - Round-trip 1: INSERT INTO pieces  VALUES (r1),(r2),...,(rN)
     *   - Round-trip 2: INSERT INTO piece_events VALUES (e1),(e2),...,(eN)
     *   - from_status = NULL  (piece did not exist before; column is nullable in V1)
     *   - to_status   = 'available'
     *   - Total: 3 SQL statements for N ≤ RECEIVE_CHUNK → ≤10s for 1,000 pieces
     *
     * Above RECEIVE_CHUNK pieces, round-trips 1 and 2 repeat per chunk (pieces chunk, then its
     * events chunk), all in the same transaction: PgJDBC refuses a statement with more than
     * 65,535 bind parameters, which one INSERT of 8,192+ pieces exceeds. The counter claim stays
     * ONE round-trip, so a finalize still gets one contiguous short-code range.
     *
     * If any row fails (barcode UNIQUE violation, FK violation, RLS WITH CHECK)
     * the entire transaction rolls back — no partial/half-received session.
     * Counter gaps can appear when a concurrent transaction rolls back after claiming
     * a range but before committing. This is harmless — codes are not required to be
     * gapless, only non-overlapping.
     *
     * @param specs       one entry per piece to create; all must share the same tenant
     * @param actorUserId the receiving user — written to actor_user_id on every event
     */
    @Transactional
    public void batchReceive(List<ReceiveSpec> specs, UUID actorUserId) {
        batchReceive(specs, actorUserId, true);
    }

    /**
     * Same contract, with {@code withCodes = false} for a COUNT-ONLY receiving session (flexible
     * labeling slice 2): no counter claim, short_code NULL — the pieces go in UNTAGGED and get their
     * code when a blank label is bound to them. Everything else (rows, received events, one
     * transaction, chunking) is identical.
     */
    @Transactional
    public void batchReceive(List<ReceiveSpec> specs, UUID actorUserId, boolean withCodes) {
        if (specs.isEmpty()) return;
        int n = specs.size();
        UUID tenantId = specs.get(0).tenantId();

        // ── Round-trip 0: claim N sequential short codes atomically ──────────
        // PieceCodeCounter is the one claim (shared with blank label codes, LabelCodeService).
        // Codes for this batch: [firstCode .. firstCode+N-1]. Untagged: no claim at all.
        long firstCode = withCodes ? PieceCodeCounter.claim(jdbc, tenantId, n) : -1;

        for (int from = 0; from < n; from += RECEIVE_CHUNK) {
            int to = Math.min(n, from + RECEIVE_CHUNK);
            insertReceivedPieces(specs, from, to, firstCode, actorUserId);
            insertReceivedEvents(specs, from, to, actorUserId);
        }
    }

    /**
     * Pieces per multi-row INSERT in batchReceive. 8 bind parameters per piece row (3 per event
     * row) → 8,000 per statement, well under PgJDBC's 65,535 limit; 1,000 keeps the documented
     * 1,000-piece fast path at one statement per table.
     */
    static final int RECEIVE_CHUNK = 1000;

    // ── Round-trip 1: insert pieces [from, to) ──────────────────────────────
    private void insertReceivedPieces(List<ReceiveSpec> specs, int from, int to,
                                      long firstCode, UUID actorUserId) {
        StringBuilder pieceSql = new StringBuilder(
            "INSERT INTO pieces " +
            "(id, tenant_id, variant_id, receipt_id, barcode, short_code, status, current_location_id, last_event_at, last_user_id) " +
            "VALUES ");
        List<Object> pieceParams = new ArrayList<>((to - from) * 8);
        for (int i = from; i < to; i++) {
            if (i > from) pieceSql.append(',');
            pieceSql.append("(?,?,?,?,?,?,'available'::piece_status,?,now(),?)");
            ReceiveSpec s = specs.get(i);
            pieceParams.add(s.pieceId());
            pieceParams.add(s.tenantId());
            pieceParams.add(s.variantId());
            pieceParams.add(s.receiptId());
            pieceParams.add("PC-" + s.pieceId());
            pieceParams.add(firstCode < 0 ? null : PieceCodeCounter.format(firstCode + i));
            pieceParams.add(s.locationId());
            pieceParams.add(actorUserId);
        }
        jdbc.update(pieceSql.toString(), pieceParams.toArray());
    }

    // ── Round-trip 2: insert received events for pieces [from, to) ──────────
    // tenant_id sourced from GUC (same pattern as INSERT_EVENT in transition()).
    // from_status = NULL literal — piece genuinely did not exist.
    // actor_user_id is mandatory per accountability requirement (FR-6.3).
    private void insertReceivedEvents(List<ReceiveSpec> specs, int from, int to, UUID actorUserId) {
        StringBuilder evtSql = new StringBuilder(
            "INSERT INTO piece_events " +
            "(tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
            "VALUES ");
        List<Object> evtParams = new ArrayList<>((to - from) * 3);
        for (int i = from; i < to; i++) {
            if (i > from) evtSql.append(',');
            evtSql.append(
                "(NULLIF(current_setting('app.current_tenant', true), '')::uuid," +
                " ?, 'received', ?, ?, NULL, 'available'::piece_status)");
            ReceiveSpec s = specs.get(i);
            evtParams.add(s.pieceId());
            evtParams.add(actorUserId);
            evtParams.add(s.locationId());
        }
        jdbc.update(evtSql.toString(), evtParams.toArray());
    }

    // ---- receiveFactoryLabeled ----------------------------------------------

    /**
     * Flexible labeling slice 6b — piece CREATION for a factory receiving session (the batchReceive family; it does
     * not touch batchReceive and never routes through transition()). Each spec carries the factory label code
     * scanned off that piece: the piece is created WITH that code as its short_code (no counter claim — the code was
     * claimed when the run printed it), its received event names the code and the run in its metadata, and every
     * code goes issued → bound to its piece. The bind is conditional on 'issued' and must hit every code, else the
     * whole call throws and the caller's transaction (finalize) rolls back — no piece without its code, no code
     * bound twice. pieces UNIQUE (tenant_id, short_code) stays the backstop.
     *
     * Caller contract: TenantContext set; the codes locked and re-checked issued by the caller.
     */
    @Transactional
    public void receiveFactoryLabeled(List<FactoryReceiveSpec> specs, UUID actorUserId, UUID labelRunId) {
        if (specs.isEmpty()) return;
        int n = specs.size();
        for (int from = 0; from < n; from += RECEIVE_CHUNK) {
            int to = Math.min(n, from + RECEIVE_CHUNK);
            StringBuilder pieceSql = new StringBuilder(
                "INSERT INTO pieces " +
                "(id, tenant_id, variant_id, receipt_id, barcode, short_code, status, current_location_id, last_event_at, last_user_id) " +
                "VALUES ");
            List<Object> pieceParams = new ArrayList<>((to - from) * 8);
            StringBuilder evtSql = new StringBuilder(
                "INSERT INTO piece_events " +
                "(tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status, metadata) VALUES ");
            List<Object> evtParams = new ArrayList<>((to - from) * 4);
            for (int i = from; i < to; i++) {
                FactoryReceiveSpec s = specs.get(i);
                if (i > from) { pieceSql.append(','); evtSql.append(','); }
                pieceSql.append("(?,?,?,?,?,?,'available'::piece_status,?,now(),?)");
                pieceParams.add(s.pieceId());
                pieceParams.add(s.tenantId());
                pieceParams.add(s.variantId());
                pieceParams.add(s.receiptId());
                pieceParams.add("PC-" + s.pieceId());
                pieceParams.add(s.code());
                pieceParams.add(s.locationId());
                pieceParams.add(actorUserId);
                evtSql.append(
                    "(NULLIF(current_setting('app.current_tenant', true), '')::uuid," +
                    " ?, 'received', ?, ?, NULL, 'available'::piece_status, ?::jsonb)");
                evtParams.add(s.pieceId());
                evtParams.add(actorUserId);
                evtParams.add(s.locationId());
                evtParams.add("{\"label_code_id\":\"" + s.labelCodeId() + "\",\"label_run_id\":\"" + labelRunId
                    + "\",\"code\":\"" + s.code() + "\"}");
            }
            jdbc.update(pieceSql.toString(), pieceParams.toArray());
            jdbc.update(evtSql.toString(), evtParams.toArray());

            UUID[] codeIds = new UUID[to - from];
            String[] pieceIds = new String[to - from];
            for (int i = from; i < to; i++) {
                codeIds[i - from] = specs.get(i).labelCodeId();
                pieceIds[i - from] = specs.get(i).pieceId();
            }
            int bound = jdbc.update(
                "UPDATE label_codes c SET status = 'bound', piece_id = v.piece_id, bound_by = ?, bound_at = now() " +
                "FROM unnest(?::uuid[], ?::text[]) AS v(code_id, piece_id) " +
                "WHERE c.id = v.code_id AND c.status = 'issued' " +
                "  AND c.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid",
                actorUserId, codeIds, pieceIds);
            if (bound != to - from) {
                throw new IllegalStateException("receiveFactoryLabeled bound " + bound + " of " + (to - from) + " codes");
            }
        }
    }

    /** One piece of a factory receiving: its new id, and the factory label code scanned off it. */
    public record FactoryReceiveSpec(String pieceId, UUID tenantId, UUID variantId, UUID receiptId, UUID locationId,
                                     UUID labelCodeId, String code) {}

    /**
     * label_reprinted events for many pieces in one statement (flexible labeling slice 6b: "Print session labels" on
     * a factory receipt is a reprint of the codes the pieces already carry). Status unchanged; each event at the
     * piece's current location. Returns how many events were written.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recordLabelsReprinted(List<String> pieceIds, UUID actorUserId, String metadata) {
        if (pieceIds.isEmpty()) return 0;
        return jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status, metadata) " +
            "SELECT NULLIF(current_setting('app.current_tenant', true), '')::uuid, p.id, 'label_reprinted', ?, " +
            "       p.current_location_id, p.status, p.status, ?::jsonb " +
            "FROM pieces p WHERE p.id = ANY(?::text[]) " +
            "  AND p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid",
            actorUserId, metadata, pieceIds.toArray(new String[0]));
    }

    /**
     * Records a return_received event for a piece that is ALREADY at
     * return_pending_inspection (state-46 webhook fired before intake scan).
     *
     * No status change is made — the piece stays at return_pending_inspection.
     * from_status = to_status = return_pending_inspection so timeline rendering
     * can use the phraseKey "return_received" without needing a status transition.
     *
     * This is the third write path on InventoryLedger (alongside transition() and
     * batchReceive()). InventoryLedger is still the only class that writes
     * piece_events — the CLAUDE.md invariant is preserved.
     *
     * @param metadata optional JSON written to piece_events.metadata (e.g. session_id + return_kind)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordReturnReceived(String pieceId, UUID locationId, UUID actorUserId,
                                     UUID orderId, UUID shipmentId, String metadata) {
        jdbc.update(INSERT_EVENT,
                pieceId, "return_received", actorUserId,
                orderId, shipmentId, locationId,
                PieceStatus.RETURN_PENDING_INSPECTION.db,
                PieceStatus.RETURN_PENDING_INSPECTION.db,
                metadata, null);
    }

    /**
     * Records a label_reprinted event without changing piece status (4th write path).
     *
     * Writes a piece_events row with from_status = to_status = current piece status.
     * InventoryLedger remains the sole writer of piece_events — invariant preserved.
     *
     * @throws PieceNotFoundException if the piece is not visible under the current RLS context
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordLabelReprinted(String pieceId, UUID actorUserId,
                                     UUID locationId, UUID orderId, UUID shipmentId) {
        String statusDb = jdbc.query(FETCH_STATUS,
                rs -> rs.next() ? rs.getString("status") : null,
                pieceId);
        if (statusDb == null) {
            throw new PieceNotFoundException(pieceId);
        }
        jdbc.update(INSERT_EVENT,
                pieceId, "label_reprinted", actorUserId,
                orderId, shipmentId, locationId,
                statusDb, statusDb, null, null);
    }

    // ---- bindLabel -----------------------------------------------------------

    /**
     * Where a bind happened, for the label_bound event's metadata: a Tag stock session (slice 3) or a
     * pack scan (slice 4: {@code orderId} = the order being packed, {@code labelOrderId} = the order the label
     * was printed for, recorded only when it differs), and whether the scan came from the paired phone.
     */
    public record BindRef(UUID tagSessionId, UUID packSessionId, UUID orderId, UUID labelOrderId, boolean viaPhone) {
        public static BindRef tagSession(UUID id, boolean viaPhone) { return new BindRef(id, null, null, null, viaPhone); }
        public static BindRef packScan(UUID orderId, UUID labelOrderId) {
            return new BindRef(null, null, orderId, labelOrderId, false);
        }
    }

    /** {@code alreadyBound}: the code was bound before this call (no new event, nothing changed). */
    public record BindResult(boolean alreadyBound, String pieceId, UUID variantId, String code) {}

    /**
     * Flexible labeling slice 3 — binds an ISSUED label code to ONE untagged available piece of THE CODE'S
     * variant at the main warehouse (oldest first by created_at, id; FOR UPDATE SKIP LOCKED, so two binds
     * never take the same piece), sets pieces.short_code to the code, marks the code bound and appends a
     * {@code label_bound} piece event (status unchanged — tagging moves no stock and never calls Shopify).
     * The fifth piece_events writer here; pieces / piece_events stay ledger-only.
     *
     * Errors: UNKNOWN_CODE (no such code for this tenant), FACTORY_LABEL_NOT_RECEIVED (kind factory — slice 6), CODE_VOIDED, NO_UNTAGGED_STOCK (no untagged available
     * piece of the variant at the main warehouse), NO_MAIN_WAREHOUSE. A code already bound → {@code alreadyBound}
     * with its piece, nothing written. Caller contract: TenantContext set, flag checked by the caller.
     */
    // noRollbackFor: every refusal (UNKNOWN_CODE, FACTORY_LABEL_NOT_RECEIVED, CODE_VOIDED, NO_MAIN_WAREHOUSE, NO_UNTAGGED_STOCK) is thrown
    // before anything is written, so a caller's transaction (a scan loop) may catch it and carry on.
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = com.traceability.labels.LabelCodeException.class)
    public BindResult bindLabel(UUID codeId, UUID actorUserId, BindRef ref, String rawScan) {
        java.util.Map<String, Object> code = jdbc.query(
            "SELECT id, code, status, piece_id, variant_id, kind FROM label_codes " +
            "WHERE id = ? AND tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid FOR UPDATE",
            rs -> {
                if (!rs.next()) return null;
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("code", rs.getString("code"));
                m.put("kind", rs.getString("kind"));
                m.put("status", rs.getString("status"));
                m.put("piece_id", rs.getString("piece_id"));
                m.put("variant_id", rs.getObject("variant_id", UUID.class));
                return m;
            },
            codeId);
        if (code == null) throw com.traceability.labels.LabelCodeException.unknownCode();
        // Slice 6: a factory code is bound only by its factory run's receiving (pieces created with the code), never
        // to an existing untagged piece — checked before anything else, whatever its status.
        if ("factory".equals(code.get("kind"))) throw com.traceability.labels.LabelCodeException.factoryLabelNotReceived();
        String codeText = (String) code.get("code");
        UUID variantId = (UUID) code.get("variant_id");
        if ("voided".equals(code.get("status"))) throw com.traceability.labels.LabelCodeException.codeVoided();
        if ("bound".equals(code.get("status"))) return new BindResult(true, (String) code.get("piece_id"), variantId, codeText);

        List<UUID> main = jdbc.queryForList(
            "SELECT id FROM locations WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid " +
            "AND is_fulfillment", UUID.class);
        if (main.isEmpty()) throw com.traceability.labels.LabelCodeException.noMainWarehouse();
        UUID mainId = main.get(0);

        List<String> piece = jdbc.queryForList(
            "SELECT id FROM pieces WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid " +
            "  AND variant_id = ? AND current_location_id = ? AND short_code IS NULL AND status = 'available' " +
            "ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
            String.class, variantId, mainId);
        if (piece.isEmpty()) throw com.traceability.labels.LabelCodeException.noUntaggedStock();
        String pieceId = piece.get(0);

        int tagged = jdbc.update(
            "UPDATE pieces SET short_code = ?, last_event_at = now(), last_user_id = ? " +
            "WHERE id = ? AND short_code IS NULL AND status = 'available'",
            codeText, actorUserId, pieceId);
        int bound = jdbc.update(
            "UPDATE label_codes SET status = 'bound', piece_id = ?, bound_by = ?, bound_at = now() " +
            "WHERE id = ? AND status = 'issued'",
            pieceId, actorUserId, codeId);
        if (tagged != 1 || bound != 1) throw new IllegalStateException("bindLabel lost its claim on " + codeText);

        StringBuilder meta = new StringBuilder("{\"label_code_id\":\"").append(codeId).append("\",\"code\":\"")
            .append(codeText).append('"');
        if (ref != null && ref.tagSessionId() != null)  meta.append(",\"tag_session_id\":\"").append(ref.tagSessionId()).append('"');
        if (ref != null && ref.packSessionId() != null) meta.append(",\"pack_session_id\":\"").append(ref.packSessionId()).append('"');
        if (ref != null && ref.labelOrderId() != null && !ref.labelOrderId().equals(ref.orderId())) {
            meta.append(",\"label_order_id\":\"").append(ref.labelOrderId()).append('"');
        }
        if (ref != null && ref.viaPhone()) meta.append(",\"via_phone\":true");
        meta.append('}');
        jdbc.update(INSERT_EVENT,
                pieceId, "label_bound", actorUserId,
                ref == null ? null : ref.orderId(), null, mainId,
                "available", "available", meta.toString(), rawScan);
        return new BindResult(false, pieceId, variantId, codeText);
    }

    // ---- recordPlaceChange -----------------------------------------------------

    /** Where a place change came from, for the event's metadata: a put-away session scan or Lookup's Move. */
    public record PlaceRef(String source, UUID sessionId, boolean viaPhone) {
        public static PlaceRef putAway(UUID sessionId, boolean viaPhone) { return new PlaceRef("put_away", sessionId, viaPhone); }
        public static PlaceRef lookup() { return new PlaceRef("lookup", null, false); }
    }

    /** PUT_AWAY (no place → a place), MOVED (A → B), CLEARED (→ no place), ALREADY_HERE (nothing written). */
    public enum PlaceChangeKind { PUT_AWAY, MOVED, CLEARED, ALREADY_HERE }

    public record PlaceChange(PlaceChangeKind kind, String pieceId, UUID fromBinId, UUID toBinId, String status,
                              UUID locationId) {}

    private static final List<String> PLACEABLE = List.of("available", "on_hold", "damaged");

    /**
     * Warehouse places S2 — puts a piece on a shelf / in a box, moves it to another one, or clears its place
     * ({@code toBinId} null): ONE conditional UPDATE of pieces.bin_id and ONE piece_events row, from_status =
     * to_status (a place is not a status), event_type put_away / place_moved / place_cleared, from_bin_id /
     * to_bin_id set, metadata {source, session_id?, via_phone?}. Never touches Shopify, status or location. The
     * sixth piece_events writer here; pieces / piece_events stay ledger-only (pieces_bin_guard, V189, only ever
     * clears bin_id on a departure).
     *
     * Refused before anything is written (PlaceChangeException): PIECE_NOT_FOUND, UNTAGGED (short_code NULL —
     * the trigger doesn't stop these, so this writer must), NO_LOCATION, STATUS_NOT_PLACEABLE (not available /
     * on_hold / damaged), PLACE_NOT_FOUND, NOT_A_SHELF_OR_BOX, PLACE_ARCHIVED, OTHER_LOCATION (the place isn't in
     * the piece's location). The same place again (or clearing a piece with no place) → ALREADY_HERE, no event.
     * Caller contract: TenantContext set, capability checked by the caller.
     */
    // noRollbackFor: every refusal is thrown before anything is written, so a scan loop's transaction carries on.
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = PlaceChangeException.class)
    public PlaceChange recordPlaceChange(String pieceId, UUID toBinId, UUID actorUserId, PlaceRef ref, String rawScan) {
        java.util.Map<String, Object> p = jdbc.query(
            "SELECT status::text AS status, short_code, current_location_id, bin_id FROM pieces " +
            "WHERE id = ? AND tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid FOR UPDATE",
            rs -> {
                if (!rs.next()) return null;
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("status", rs.getString("status"));
                m.put("short_code", rs.getString("short_code"));
                m.put("location", rs.getObject("current_location_id", UUID.class));
                m.put("bin", rs.getObject("bin_id", UUID.class));
                return m;
            },
            pieceId);
        if (p == null) throw PlaceChangeException.pieceNotFound();
        String status = (String) p.get("status");
        UUID locationId = (UUID) p.get("location");
        UUID fromBinId = (UUID) p.get("bin");
        if (p.get("short_code") == null) throw PlaceChangeException.untagged();
        if (locationId == null) throw PlaceChangeException.noLocation();
        if (!PLACEABLE.contains(status)) throw PlaceChangeException.statusNotPlaceable(status);

        if (toBinId != null) {
            // FOR SHARE: an archive of this place (FOR UPDATE on the same row) waits for this transaction.
            java.util.Map<String, Object> b = jdbc.query(
                "SELECT kind, location_id, archived_at FROM bins " +
                "WHERE id = ? AND tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid FOR SHARE",
                rs -> {
                    if (!rs.next()) return null;
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("kind", rs.getString("kind"));
                    m.put("location", rs.getObject("location_id", UUID.class));
                    m.put("archived", rs.getTimestamp("archived_at") != null);
                    return m;
                },
                toBinId);
            if (b == null) throw PlaceChangeException.placeNotFound();
            if (!"shelf".equals(b.get("kind")) && !"box".equals(b.get("kind"))) throw PlaceChangeException.notAShelfOrBox();
            if (Boolean.TRUE.equals(b.get("archived"))) throw PlaceChangeException.placeArchived();
            if (!locationId.equals(b.get("location"))) throw PlaceChangeException.otherLocation();
        }

        if (java.util.Objects.equals(fromBinId, toBinId)) {
            return new PlaceChange(PlaceChangeKind.ALREADY_HERE, pieceId, fromBinId, toBinId, status, locationId);
        }

        int rows = jdbc.update(
            "UPDATE pieces SET bin_id = ?, last_event_at = now(), last_user_id = ? " +
            "WHERE id = ? AND status = ?::piece_status AND current_location_id = ? AND bin_id IS NOT DISTINCT FROM ?",
            toBinId, actorUserId, pieceId, status, locationId, fromBinId);
        if (rows != 1) throw new IllegalStateException("recordPlaceChange lost its lock on " + pieceId);

        PlaceChangeKind kind = toBinId == null ? PlaceChangeKind.CLEARED
            : fromBinId == null ? PlaceChangeKind.PUT_AWAY : PlaceChangeKind.MOVED;
        String eventType = switch (kind) {
            case PUT_AWAY -> "put_away";
            case MOVED -> "place_moved";
            default -> "place_cleared";
        };
        StringBuilder meta = new StringBuilder("{\"source\":\"").append(ref == null ? "lookup" : ref.source()).append('"');
        if (ref != null && ref.sessionId() != null) meta.append(",\"session_id\":\"").append(ref.sessionId()).append('"');
        if (ref != null && ref.viaPhone()) meta.append(",\"via_phone\":true");
        meta.append('}');
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, " +
            "                          from_status, to_status, from_bin_id, to_bin_id, metadata, raw_scan) " +
            "VALUES (NULLIF(current_setting('app.current_tenant', true), '')::uuid, ?, ?, ?, ?, " +
            "        ?::piece_status, ?::piece_status, ?, ?, ?::jsonb, ?)",
            pieceId, eventType, actorUserId, locationId, status, status, fromBinId, toBinId, meta.toString(), rawScan);
        return new PlaceChange(kind, pieceId, fromBinId, toBinId, status, locationId);
    }

    /** Specification for one piece to be created via batchReceive. */
    public record ReceiveSpec(
            String pieceId,
            UUID   tenantId,
            UUID   variantId,
            UUID   receiptId,
            UUID   locationId
    ) {}
}
