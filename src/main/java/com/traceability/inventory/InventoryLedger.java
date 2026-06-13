package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * The single gateway for all piece state changes.
 *
 * Every state change in the system routes through transition(). No caller may
 * mutate pieces.status or insert piece_events directly.
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

        // cancellation / unwind
        "reserved:available",
        "packed:available",
        "awaiting_pickup:available",

        // courier return path
        "with_courier:return_in_transit",
        "return_in_transit:return_pending_inspection",
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
}
