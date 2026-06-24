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
 *   batchReceive()  – piece CREATION only; two multi-row INSERTs in one ACID transaction,
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

        // found (lost → back in stock)
        "lost:available"
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
                from_status, to_status, metadata
            ) VALUES (
                NULLIF(current_setting('app.current_tenant', true), '')::uuid,
                ?, ?, ?,
                ?, ?, ?,
                ?::piece_status, ?::piece_status, ?::jsonb
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
                expectedStatus.db, newStatus.db, ctx.metadata());
    }

    // ---- batchReceive ------------------------------------------------------

    /**
     * Creates N pieces and their received events in two multi-row INSERTs inside
     * one @Transactional boundary.
     *
     * Design contract (do NOT modify without re-reading the invariant note in
     * the class Javadoc):
     *   - Round-trip 1: INSERT INTO pieces  VALUES (r1),(r2),...,(rN)
     *   - Round-trip 2: INSERT INTO piece_events VALUES (e1),(e2),...,(eN)
     *   - from_status = NULL  (piece did not exist before; column is nullable in V1)
     *   - to_status   = 'available'
     *   - Total: 2 SQL statements regardless of N → ≤10s for 1,000 pieces
     *
     * If any row fails (barcode UNIQUE violation, FK violation, RLS WITH CHECK)
     * the entire transaction rolls back — no partial/half-received session.
     *
     * @param specs       one entry per piece to create; all must share the same tenant
     * @param actorUserId the receiving user — written to actor_user_id on every event
     */
    @Transactional
    public void batchReceive(List<ReceiveSpec> specs, UUID actorUserId) {
        if (specs.isEmpty()) return;
        int n = specs.size();

        // ── Round-trip 1: insert all pieces ──────────────────────────────────
        StringBuilder pieceSql = new StringBuilder(
            "INSERT INTO pieces " +
            "(id, tenant_id, variant_id, receipt_id, barcode, status, current_location_id, last_event_at, last_user_id) " +
            "VALUES ");
        List<Object> pieceParams = new ArrayList<>(n * 9);
        for (int i = 0; i < n; i++) {
            if (i > 0) pieceSql.append(',');
            pieceSql.append("(?,?,?,?,?,'available'::piece_status,?,now(),?)");
            ReceiveSpec s = specs.get(i);
            pieceParams.add(s.pieceId());
            pieceParams.add(s.tenantId());
            pieceParams.add(s.variantId());
            pieceParams.add(s.receiptId());
            pieceParams.add("PC-" + s.pieceId());
            pieceParams.add(s.locationId());
            pieceParams.add(actorUserId);
        }
        jdbc.update(pieceSql.toString(), pieceParams.toArray());

        // ── Round-trip 2: insert all received events ──────────────────────────
        // tenant_id sourced from GUC (same pattern as INSERT_EVENT in transition()).
        // from_status = NULL literal — piece genuinely did not exist.
        // actor_user_id is mandatory per accountability requirement (FR-6.3).
        StringBuilder evtSql = new StringBuilder(
            "INSERT INTO piece_events " +
            "(tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
            "VALUES ");
        List<Object> evtParams = new ArrayList<>(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) evtSql.append(',');
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
                metadata);
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
                statusDb, statusDb, null);
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
