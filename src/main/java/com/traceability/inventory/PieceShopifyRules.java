package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Piece sync (Lookup adjustments ↔ Shopify, approved 2026-10-10) — the ONE place the two piece-level
 * Shopify decisions live. Change a rule here, never inline in a caller.
 *
 *   {@link #countedAtMain} (D4) — does Shopify's Traced Main Warehouse count this available piece right
 *       now? Every departure (−1 or move) decides with it: void_correction, hold_enter, damage_move.
 *   {@link #departureReached} (D5) — did the piece's departure from sellable reach Shopify? Every
 *       return to available decides with it: hold_exit.
 *
 * Both run on the caller's JdbcTemplate inside the caller's transaction (tenant-scoped under RLS on
 * app_user). A "no" is never silent: it carries the skip reason the claim row records. Reasons in
 * {@link #NEEDS_CHECK} mean nobody can tell what Shopify holds — the void_hold_sync_failed alert
 * asks a person to check.
 */
public final class PieceShopifyRules {

    private PieceShopifyRules() {}

    /** Every claim the piece-sync engine sends (ShopifyInventoryService.pushPieceClaimNow). */
    public static final List<String> PIECE_TRIGGERS = List.of(
        "void_correction", "hold_enter", "hold_exit", "damage_move",
        "piece_write_off", "piece_write_off_return", "damage_restore", "damaged_restore_increment");
    public static final String PIECE_TRIGGERS_SQL =
        "('void_correction', 'hold_enter', 'hold_exit', 'damage_move', " +
        "'piece_write_off', 'piece_write_off_return', 'damage_restore', 'damaged_restore_increment')";

    /** The claims that took a piece OUT of Shopify's sellable count (a −1, or the damage move). */
    public static final Set<String> DEPARTURE_TRIGGERS =
        Set.of("void_correction", "hold_enter", "damage_move", "piece_write_off");

    /** Definite failures are re-sent by the sweep until a claim has made this many attempts. */
    public static final int MAX_ATTEMPTS = 5;

    /** A claim of the engine (not legacy): created at or after V161 ran. Alias sia. */
    public static final String LIVE_SQL = "sia.created_at >= piece_sync_cutoff()";

    // ── Skip reasons ────────────────────────────────────────────────────────────
    public static final String NO_MAIN_WAREHOUSE     = "no_main_warehouse";
    public static final String MAIN_NOT_LINKED       = "main_warehouse_not_linked";
    public static final String NOT_SEEDED            = "not_seeded";
    public static final String NOT_AT_MAIN           = "not_at_main";
    public static final String ARRIVAL_NOT_COUNTED   = "arrival_not_counted";
    public static final String ARRIVAL_UNCONFIRMED   = "arrival_unconfirmed";
    public static final String DEPARTURE_NOT_REACHED = "departure_not_reached";
    public static final String DEPARTURE_CANCELLED   = "departure_cancelled";
    public static final String DEPARTURE_AMBIGUOUS   = "departure_ambiguous";

    /** Skips nobody can resolve without looking at Shopify — surfaced by the alert. */
    public static final Set<String> NEEDS_CHECK = Set.of(DEPARTURE_AMBIGUOUS, ARRIVAL_UNCONFIRMED);
    public static final String NEEDS_CHECK_SQL = "('" + DEPARTURE_AMBIGUOUS + "', '" + ARRIVAL_UNCONFIRMED + "')";

    /** Statuses a piece leaves the sellable pool into (the "bad" side of the ledger). */
    static final Set<String> BAD = Set.of("lost", "destroyed", "damaged", "on_hold", "voided");

    /**
     * One decision. write → send a claim at {@code locationId} (always the main warehouse); otherwise
     * {@code skipReason}, recorded on a 'skipped' row at {@code locationId} (the main warehouse, or the
     * piece's own location when the tenant has none). {@code via} says how a departure reached Shopify
     * (the departure claim's trigger type, or "seed") — a return writes the reverse of it.
     */
    public record Verdict(boolean write, UUID locationId, String skipReason, String via) {
        static Verdict send(UUID mainId, String via) { return new Verdict(true, mainId, null, via); }
        static Verdict skip(UUID locationId, String reason) { return new Verdict(false, locationId, reason, null); }
    }

    /** The tenant-level preconditions: a main warehouse, linked, and an applied initial seed. */
    record Setup(UUID mainId, String skipReason, Timestamp seededAt) {}

    static Setup setup(JdbcTemplate jdbc, UUID tenantId) {
        List<Map<String, Object>> main = jdbc.queryForList(
            "SELECT id, shopify_location_id, shopify_sync_status FROM locations " +
            "WHERE tenant_id = ? AND is_fulfillment = true", tenantId);
        if (main.isEmpty()) return new Setup(null, NO_MAIN_WAREHOUSE, null);
        UUID mainId = (UUID) main.get(0).get("id");
        if (!"linked".equals(main.get(0).get("shopify_sync_status")) || main.get(0).get("shopify_location_id") == null) {
            return new Setup(mainId, MAIN_NOT_LINKED, null);
        }
        // Linked but never seeded: Shopify's Traced location holds nothing yet, and the seed will count
        // what Traced holds then — exactly like the transfer push (TransferShopifySync.SEEDED_SQL).
        Timestamp seededAt = jdbc.queryForObject(
            "SELECT MIN(created_at) FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed' AND status = 'applied'",
            Timestamp.class, tenantId);
        if (seededAt == null) return new Setup(mainId, NOT_SEEDED, null);
        return new Setup(mainId, null, seededAt);
    }

    private record Piece(UUID locationId, UUID variantId, UUID receiptId) {}

    private static Piece piece(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        return jdbc.query(
            "SELECT current_location_id, variant_id, receipt_id FROM pieces WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? new Piece(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                                        rs.getObject(3, UUID.class)) : null,
            pieceId, tenantId);
    }

    // ── D4: counted at main ─────────────────────────────────────────────────────

    /**
     * Does Shopify's Traced Main Warehouse count this AVAILABLE piece (called just before it leaves
     * available)? Main warehouse linked and seeded, or a skip. A piece at the main warehouse is
     * counted when its arrival was counted: its receiving claim applied or was superseded by the seed
     * (the seed counted it), or it was received before the seed. A receiving claim still pending or
     * still retryable (or ambiguous) → arrival_unconfirmed; anything else → arrival_not_counted.
     * A piece at another location → not_at_main.
     */
    public static Verdict countedAtMain(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        Setup s = setup(jdbc, tenantId);
        Piece p = piece(jdbc, tenantId, pieceId);
        if (p == null) throw new IllegalStateException("piece not found: " + pieceId);
        UUID rowLocation = s.mainId() != null ? s.mainId() : p.locationId();
        if (s.skipReason() != null) return Verdict.skip(rowLocation, s.skipReason());
        if (!s.mainId().equals(p.locationId())) return Verdict.skip(s.mainId(), NOT_AT_MAIN);
        return arrivalAtMain(jdbc, tenantId, pieceId, p, s);
    }

    private static Verdict arrivalAtMain(JdbcTemplate jdbc, UUID tenantId, String pieceId, Piece p, Setup s) {
        Map<String, Object> recv = p.receiptId() == null ? null : jdbc.query(
            "SELECT status, legacy, attempt_count, failure_class FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'receiving_session' AND trigger_id = ? " +
            "  AND variant_id = ? AND location_id = ?",
            rs -> rs.next() ? Map.<String, Object>of("status", rs.getString(1), "legacy", rs.getBoolean(2),
                "attempts", rs.getInt(3), "class", String.valueOf(rs.getString(4))) : null,
            tenantId, p.receiptId().toString(), p.variantId(), s.mainId());
        String status = recv == null ? null : (String) recv.get("status");
        if ("applied".equals(status) || ShopifyInventoryService.SUPERSEDED_BY_SEED.equals(status)) {
            return Verdict.send(s.mainId(), "arrival");
        }
        // The piece row is created when it is received (InventoryLedger.batchReceive).
        Timestamp receivedAt = jdbc.queryForObject(
            "SELECT created_at FROM pieces WHERE id = ? AND tenant_id = ?", Timestamp.class, pieceId, tenantId);
        if (receivedAt != null && receivedAt.before(s.seededAt())) {
            return Verdict.send(s.mainId(), "seed");
        }
        boolean unconfirmed = "pending".equals(status)
            || ("failed".equals(status) && !(Boolean) recv.get("legacy")
                && ((Integer) recv.get("attempts") < IncrementRecoveryRules.MAX_ATTEMPTS
                    || "ambiguous".equals(recv.get("class"))));
        return Verdict.skip(s.mainId(), unconfirmed ? ARRIVAL_UNCONFIRMED : ARRIVAL_NOT_COUNTED);
    }

    // ── D5: departure reached Shopify ──────────────────────────────────────────

    /** The event that took the piece out of the sellable pool: the earliest of the trailing run of
     *  events landing on a "bad" status. */
    record Departure(long eventId, String fromStatus, String toStatus, String eventType,
                     Timestamp occurredAt, String holdEventId) {}

    static Departure departure(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        List<Departure> events = jdbc.query(
            "SELECT id, from_status::text, to_status::text, event_type, occurred_at, " +
            "       metadata->>'hold_event_id' AS hold_event_id " +
            "FROM piece_events WHERE piece_id = ? AND tenant_id = ? ORDER BY occurred_at DESC, id DESC",
            (rs, i) -> new Departure(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5), rs.getString(6)),
            pieceId, tenantId);
        Departure entry = null;
        for (Departure e : events) {
            if (e.toStatus() == null || !BAD.contains(e.toStatus())) break;
            entry = e;
        }
        return entry;
    }

    /**
     * Did this piece's departure from sellable reach Shopify? Decides every return to available.
     *   - departure before the seed, piece at the main warehouse → the seed left it out (via "seed");
     *   - otherwise the departure's own claim: applied → reached (via its trigger type); still queued or
     *     failed-and-retryable → CANCELLED here (it must never be sent now) → departure_cancelled;
     *     pending / failed_ambiguous / skipped-needing-a-check → departure_ambiguous; anything else
     *     (skipped, exhausted, legacy, superseded, absent) → departure_not_reached.
     * Call it inside the transaction that moves the piece back to available — it cancels.
     */
    public static Verdict departureReached(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        Setup s = setup(jdbc, tenantId);
        Piece p = piece(jdbc, tenantId, pieceId);
        if (p == null) throw new IllegalStateException("piece not found: " + pieceId);
        UUID rowLocation = s.mainId() != null ? s.mainId() : p.locationId();
        Departure d = departure(jdbc, tenantId, pieceId);
        String claimType = d == null || !"available".equals(d.fromStatus()) ? null : switch (d.toStatus()) {
            case "on_hold" -> "hold_enter";
            case "damaged" -> "damage_move";
            case "voided"  -> "void_correction";
            default        -> null;
        };
        // An open departure claim must never be sent once the piece is back — whatever else is decided.
        String claimStatus = claimType == null ? null : departureClaimStatus(jdbc, tenantId, pieceId, d, claimType);
        if (s.skipReason() != null) return Verdict.skip(rowLocation, s.skipReason());
        if (d == null || !"available".equals(d.fromStatus())) return Verdict.skip(s.mainId(), DEPARTURE_NOT_REACHED);
        boolean atMain = s.mainId().equals(p.locationId());
        if (d.occurredAt().before(s.seededAt())) {
            return atMain ? Verdict.send(s.mainId(), "seed") : Verdict.skip(s.mainId(), NOT_AT_MAIN);
        }
        if (claimStatus == null) return Verdict.skip(s.mainId(), DEPARTURE_NOT_REACHED);
        return switch (claimStatus) {
            case "applied"   -> Verdict.send(s.mainId(), claimType);
            case "cancelled_now" -> Verdict.skip(s.mainId(), DEPARTURE_CANCELLED);
            case "ambiguous" -> Verdict.skip(s.mainId(), DEPARTURE_AMBIGUOUS);
            case ShopifyInventoryService.SUPERSEDED_BY_SEED ->
                atMain ? Verdict.send(s.mainId(), "seed") : Verdict.skip(s.mainId(), NOT_AT_MAIN);
            default          -> Verdict.skip(s.mainId(), DEPARTURE_NOT_REACHED);
        };
    }

    /**
     * The departure claim's state, normalised: applied | cancelled_now (was open, cancelled by this
     * call) | ambiguous | superseded_by_seed | not_reached. Claim key: hold_enter piece:hold_event_id;
     * void_correction piece; damage_move — the claims of this piece made at or after the departure.
     */
    private static String departureClaimStatus(JdbcTemplate jdbc, UUID tenantId, String pieceId,
                                               Departure d, String claimType) {
        List<Object> args = new ArrayList<>(List.of(tenantId, claimType));
        String keySql;
        if ("hold_enter".equals(claimType)) {
            if (d.holdEventId() == null) return "not_reached";
            keySql = "sia.trigger_id = ?";
            args.add(pieceId + ":" + d.holdEventId());
        } else {
            keySql = "(sia.trigger_id = ? OR sia.trigger_id LIKE ?) AND sia.created_at >= ?";
            args.add(pieceId);
            args.add(pieceId + ":%");
            args.add(d.occurredAt());
        }
        // Cancel what was never sent: queued, or failed definitively with attempts left (the sweep
        // would otherwise send it later). Locks the row — a concurrent push either won (pending) or waits.
        int cancelled = jdbc.update(
            "UPDATE shopify_inventory_adjustments sia SET status = 'cancelled', " +
            "    error = 'Piece back to good before this was sent — never sent' " +
            "WHERE sia.tenant_id = ? AND sia.trigger_type = ? AND " + keySql + " AND " + LIVE_SQL +
            "  AND (sia.status = 'queued' OR (sia.status = 'failed' AND sia.attempt_count < " + MAX_ATTEMPTS + "))",
            args.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT sia.status, sia.skip_reason FROM shopify_inventory_adjustments sia " +
            "WHERE sia.tenant_id = ? AND sia.trigger_type = ? AND " + keySql, args.toArray());
        boolean applied = false, ambiguous = false, superseded = false;
        for (Map<String, Object> r : rows) {
            String st = (String) r.get("status");
            if ("applied".equals(st)) applied = true;
            else if ("pending".equals(st) || "queued".equals(st) || "failed_ambiguous".equals(st)
                     || ("skipped".equals(st) && NEEDS_CHECK.contains((String) r.get("skip_reason")))) ambiguous = true;
            else if (ShopifyInventoryService.SUPERSEDED_BY_SEED.equals(st)) superseded = true;
        }
        if (applied) return "applied";
        if (ambiguous) return "ambiguous";
        if (cancelled > 0) return "cancelled_now";
        if (superseded) return ShopifyInventoryService.SUPERSEDED_BY_SEED;
        return "not_reached";
    }
}
