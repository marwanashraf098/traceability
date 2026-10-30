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
 *       now? Every departure (−1 or move) decides with it: void_correction, hold_enter, damage_move,
 *       piece_write_off, piece_sold (Lookup "Sold", C1 2026-10-25).
 *   {@link #departureReached} (D5) — did the piece's departure from sellable reach Shopify? Every
 *       return to available decides with it: hold_exit, piece_write_off_return (Found it), piece_sold_return
 *       (Not sold), damage_restore /
 *       damaged_restore_increment (Back to good) — and, through {@link #reach}, stock-take found.
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
        "piece_write_off", "piece_write_off_return", "damage_restore", "damaged_restore_increment",
        "piece_sold", "piece_sold_return");
    public static final String PIECE_TRIGGERS_SQL =
        "('void_correction', 'hold_enter', 'hold_exit', 'damage_move', " +
        "'piece_write_off', 'piece_write_off_return', 'damage_restore', 'damaged_restore_increment', " +
        "'piece_sold', 'piece_sold_return')";

    /** The claims that took a piece OUT of Shopify's sellable count (a −1, or the damage move). */
    public static final Set<String> DEPARTURE_TRIGGERS =
        Set.of("void_correction", "hold_enter", "damage_move", "piece_write_off", "piece_sold");

    /** The claims that bring a piece BACK to available (a +1, or the reverse move). */
    public static final Set<String> RETURN_TRIGGERS =
        Set.of("hold_exit", "piece_write_off_return", "damage_restore", "damaged_restore_increment",
               "piece_sold_return");

    /** Definite failures are re-sent by the sweep until a claim has made this many attempts. */
    public static final int MAX_ATTEMPTS = 5;

    /** A claim of the engine (not legacy): created at or after V161 ran. Alias sia. */
    public static final String LIVE_SQL = "sia.created_at >= piece_sync_cutoff()";

    // ── Skip reasons ────────────────────────────────────────────────────────────
    public static final String NO_MAIN_WAREHOUSE     = "no_main_warehouse";
    public static final String MAIN_NOT_LINKED       = "main_warehouse_not_linked";
    /** Main warehouse linked, but Shopify isn't counting there yet: no applied seed and no applied
     *  receiving claim (shopify_counting_source is NULL). Before 2026-10-14 this was 'not_seeded'. */
    public static final String NOT_COUNTING          = "not_counting";
    public static final String NOT_AT_MAIN           = "not_at_main";
    public static final String ARRIVAL_NOT_COUNTED   = "arrival_not_counted";
    public static final String ARRIVAL_UNCONFIRMED   = "arrival_unconfirmed";
    public static final String DEPARTURE_NOT_REACHED = "departure_not_reached";
    public static final String DEPARTURE_CANCELLED   = "departure_cancelled";
    public static final String DEPARTURE_AMBIGUOUS   = "departure_ambiguous";
    public static final String DEPARTURE_REMOVED     = "departure_removed";
    public static final String NOT_COUNTED_AT_MAIN   = "not_counted_at_main";
    public static final String SHOPIFY_RESTOCKED     = "shopify_restocked";
    /** The departure claim was SENT and Shopify definitely rejected it (nothing applied); the piece came
     *  back before a re-send — the return writes nothing (Shopify never lost the unit). */
    public static final String DEPARTURE_REJECTED    = "departure_rejected";
    /** Set on such a departure claim when the piece came back: it is closed, never re-sent. Its
     *  failure_class and Shopify's error stay as recorded — it was sent, so it is never "cancelled". */
    public static final String NOT_RESENT_PIECE_RETURNED = "not_resent_piece_returned";
    /** Set on a departure claim skipped as arrival_unconfirmed / arrival_not_counted when the piece came back
     *  before its arrival reached Shopify (2026-10-30): nothing was ever sent, nothing is owed — it is closed so
     *  the arrival revisit (ArrivalRevisit) never sends it. The return writes nothing (departure_not_reached). */
    public static final String NOT_SENT_PIECE_RETURNED = "not_sent_piece_returned";

    /** The per-piece claim of a stock-take write-off whose arrival had not reached Shopify (2026-10-30): a
     *  piece_write_off claim keyed piece:stock_take:session, left out of the session's push. */
    public static String stockTakeWriteOffKey(String pieceId, Object sessionId) {
        return pieceId + ":stock_take:" + sessionId;
    }

    /** Skips nobody can resolve without looking at Shopify — surfaced by the alert. */
    public static final Set<String> NEEDS_CHECK = Set.of(DEPARTURE_AMBIGUOUS, ARRIVAL_UNCONFIRMED);
    public static final String NEEDS_CHECK_SQL = "('" + DEPARTURE_AMBIGUOUS + "', '" + ARRIVAL_UNCONFIRMED + "')";

    /** Statuses a piece leaves the sellable pool into (the "bad" side of the ledger). 'sold' (C1): a
     *  Lookup Sold (available → sold) is a departure like a write-off; the only way back is Not sold.
     *  A transfer's off-book sale (out_on_transfer → sold) is a run that doesn't start from available —
     *  never reached, and Not sold refuses it anyway. */
    static final Set<String> BAD = Set.of("lost", "destroyed", "damaged", "on_hold", "voided", "sold");

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

    /** The tenant-level preconditions: a main warehouse, linked, and Shopify counting there
     *  (shopify_counting_source — an applied seed or an applied receiving claim). seededAt is the
     *  applied seed's time, or null (a tenant counting through receiving) — every seed branch is
     *  keyed on it and is a no-op without it. */
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
        // Linked, but neither a seed nor a receiving +1 ever applied: Shopify's Traced location holds
        // nothing of Traced's yet (V167 shopify_counting_source — also the transfer gate).
        String source = jdbc.queryForObject("SELECT shopify_counting_source(?)", String.class, tenantId);
        if (source == null) return new Setup(mainId, NOT_COUNTING, null);
        Timestamp seededAt = jdbc.queryForObject(
            "SELECT MIN(created_at) FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed' AND status = 'applied'",
            Timestamp.class, tenantId);
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
     * available)? Main warehouse linked and Shopify counting there, or a skip. A piece at the main
     * warehouse is counted when its arrival was counted ({@link #arrival}): its receiving claim applied
     * or was superseded by the seed (the seed counted it), or it was received before the seed. A receiving claim still pending or
     * still retryable (or ambiguous) → arrival_unconfirmed; anything else → arrival_not_counted.
     *
     * A piece at ANOTHER location is counted at main only while its departure from main did not
     * reduce Shopify — the permanent move (relocate_out) that took it there:
     *   moved before the seed → not_counted_at_main (the seed counted only the main warehouse);
     *   'leave' mode, or its transfer-out claim skipped / failed with no attempts left / never made
     *     (a pre-V157 move) → counted (−1 at MAIN — Traced never writes another location);
     *   the claim 'pushed' with this piece → departure_removed;
     *   queued / pending / failed-and-retryable / failed_ambiguous → departure_ambiguous (alert);
     *   no move from main at all (received there) → not_at_main.
     * Its arrival at main must have been counted too.
     */
    public static Verdict countedAtMain(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        Setup s = setup(jdbc, tenantId);
        Piece p = piece(jdbc, tenantId, pieceId);
        if (p == null) throw new IllegalStateException("piece not found: " + pieceId);
        UUID rowLocation = s.mainId() != null ? s.mainId() : p.locationId();
        if (s.skipReason() != null) return Verdict.skip(rowLocation, s.skipReason());
        if (s.mainId().equals(p.locationId())) return arrivalAtMain(jdbc, tenantId, pieceId, p, s);

        List<Map<String, Object>> move = jdbc.queryForList(
            "SELECT t_out.shopify_sync_mode AS mode, " + TransferShopifySync.departedAtSql("t_out") + " AS departed_at, " +
            "       y.status AS claim_status, y.attempt_count, " +
            "       COALESCE(jsonb_exists(y.piece_ids, tp_out.piece_id), false) AS claim_has_piece " +
            "FROM transfer_pieces tp_out JOIN transfers t_out ON t_out.id = tp_out.transfer_id " +
            "LEFT JOIN transfer_shopify_syncs y ON y.transfer_id = t_out.id AND y.tenant_id = t_out.tenant_id " +
            "WHERE tp_out.piece_id = ? AND tp_out.tenant_id = ? AND t_out.transfer_mode = 'relocate_out' " +
            "  AND tp_out.outcome = 'relocated' AND t_out.destination_location_id = ? " +
            "  AND (tp_out.from_location_id IS NULL OR tp_out.from_location_id = ?) " +
            "ORDER BY t_out.created_at DESC, t_out.id DESC LIMIT 1",
            pieceId, tenantId, p.locationId(), s.mainId());
        if (move.isEmpty()) return Verdict.skip(s.mainId(), NOT_AT_MAIN);
        Map<String, Object> m = move.get(0);
        Timestamp departedAt = (Timestamp) m.get("departed_at");
        if (departedAt == null || (s.seededAt() != null && departedAt.before(s.seededAt()))) {
            return Verdict.skip(s.mainId(), NOT_COUNTED_AT_MAIN);
        }
        Verdict arrival = arrivalAtMain(jdbc, tenantId, pieceId, p, s);
        if (!arrival.write()) return arrival;
        if ("leave".equals(m.get("mode"))) return Verdict.send(s.mainId(), "leave_mode");
        String claim = (String) m.get("claim_status");
        int attempts = m.get("attempt_count") == null ? 0 : ((Number) m.get("attempt_count")).intValue();
        if (claim == null || "skipped".equals(claim)
                || ("failed".equals(claim) && attempts >= TransferShopifySync.MAX_ATTEMPTS)) {
            return Verdict.send(s.mainId(), "transfer_not_decremented");
        }
        if ("pushed".equals(claim)) {
            return Boolean.TRUE.equals(m.get("claim_has_piece"))
                ? Verdict.skip(s.mainId(), DEPARTURE_REMOVED)
                : Verdict.send(s.mainId(), "transfer_not_decremented");
        }
        return Verdict.skip(s.mainId(), DEPARTURE_AMBIGUOUS);
    }

    /**
     * Did this piece's arrival at the main warehouse reach Shopify? THE per-piece arrival rule — the
     * departures of {@link #countedAtMain} and the transfer send (TransferShopifySync.claimSend, which
     * leaves a piece out of its −N when this says no) both decide with it. {@code s} must be a setup
     * with no skip reason. send(via "arrival" | "seed") or skip(arrival_unconfirmed | arrival_not_counted).
     */
    static Verdict arrival(JdbcTemplate jdbc, UUID tenantId, String pieceId, Setup s) {
        if (s.skipReason() != null) throw new IllegalArgumentException("arrival needs a counting setup");
        Piece p = piece(jdbc, tenantId, pieceId);
        if (p == null) throw new IllegalStateException("piece not found: " + pieceId);
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
        if (receivedAt != null && s.seededAt() != null && receivedAt.before(s.seededAt())) {
            return Verdict.send(s.mainId(), "seed");
        }
        boolean unconfirmed = "pending".equals(status)
            || ("failed".equals(status) && !(Boolean) recv.get("legacy")
                && ((Integer) recv.get("attempts") < IncrementRecoveryRules.MAX_ATTEMPTS
                    || "ambiguous".equals(recv.get("class"))));
        return Verdict.skip(s.mainId(), unconfirmed ? ARRIVAL_UNCONFIRMED : ARRIVAL_NOT_COUNTED);
    }

    // ── Arrival revisit: is the piece still away? ──────────────────────────────

    /**
     * For ArrivalRevisit: is the departure this skipped claim was made for still the piece's current one — so the
     * decrement is owed now that its arrival reached Shopify? The piece's trailing "bad" run ({@link #departure})
     * must have started from available with this claim's event: hold_enter piece:hold_event_id, piece_write_off
     * piece:write_off_event_id (or piece:stock_take:session for a stock-take write-off), piece_sold
     * piece:sold_event_id, void_correction voided, damage_move damaged (the claim made at / after the departure).
     * A piece back in available (or gone through it since) → false.
     * exchange_dispatch: the replacement has not come back to sellable — or it has and its restock +1 applied
     * after the dispatch (the +1 then counted it a second time, so the −1 is owed all the same).
     */
    static boolean stillAway(JdbcTemplate jdbc, UUID tenantId, String pieceId, String triggerType, String triggerId,
                             Timestamp claimCreatedAt) {
        if ("exchange_dispatch".equals(triggerType)) {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT p.status::text NOT IN ('available', 'reserved', 'packed', 'awaiting_pickup') " +
                "    OR EXISTS (SELECT 1 FROM shopify_inventory_adjustments r WHERE r.tenant_id = p.tenant_id " +
                "        AND r.trigger_type = 'return_inspection' AND r.status = 'applied' " +
                "        AND (r.trigger_id = p.id OR r.trigger_id LIKE p.id || ':%') AND r.created_at >= ?) " +
                "FROM pieces p WHERE p.id = ? AND p.tenant_id = ?",
                Boolean.class, claimCreatedAt, pieceId, tenantId));
        }
        Departure d = departure(jdbc, tenantId, pieceId);
        if (d == null || !"available".equals(d.fromStatus())) return false;
        return switch (triggerType) {
            case "hold_enter" -> "on_hold".equals(d.toStatus()) && triggerId.equals(pieceId + ":" + d.holdEventId());
            case "piece_write_off" -> triggerId.equals(stockTakeWriteOffKey(pieceId, d.sessionId()))
                ? "lost".equals(d.toStatus()) && "stock_take_missing".equals(d.reason())
                : ("lost".equals(d.toStatus()) || "destroyed".equals(d.toStatus()))
                    && triggerId.equals(pieceId + ":" + d.writeOffEventId());
            case "piece_sold" -> "sold".equals(d.toStatus()) && triggerId.equals(pieceId + ":" + d.soldEventId());
            case "void_correction" -> "voided".equals(d.toStatus());
            case "damage_move" -> "damaged".equals(d.toStatus()) && !claimCreatedAt.before(d.occurredAt());
            default -> false;
        };
    }

    // ── D5: departure reached Shopify ──────────────────────────────────────────

    /** The event that took the piece out of the sellable pool: the earliest of the trailing run of
     *  events landing on a "bad" status. */
    record Departure(long eventId, String fromStatus, String toStatus, String eventType,
                     Timestamp occurredAt, String holdEventId, String writeOffEventId,
                     String reason, String sessionId, boolean viaDamaged, String soldEventId) {}

    /** The earliest event of the trailing run of events landing on a "bad" status; viaDamaged when
     *  that run passed through 'damaged' after its entry (e.g. available → damaged → lost). */
    static Departure departure(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        List<Departure> events = jdbc.query(
            "SELECT id, from_status::text, to_status::text, event_type, occurred_at, " +
            "       metadata->>'hold_event_id', metadata->>'write_off_event_id', metadata->>'reason', " +
            "       metadata->>'session_id', metadata->>'sold_event_id' " +
            "FROM piece_events WHERE piece_id = ? AND tenant_id = ? ORDER BY occurred_at DESC, id DESC",
            (rs, i) -> new Departure(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), false,
                rs.getString(10)),
            pieceId, tenantId);
        Departure entry = null;
        boolean damagedLater = false;
        for (Departure e : events) {
            if (e.toStatus() == null || !BAD.contains(e.toStatus())) break;
            if (entry != null && "damaged".equals(entry.toStatus())) damagedLater = true;
            entry = e;
        }
        if (entry == null) return null;
        return new Departure(entry.eventId(), entry.fromStatus(), entry.toStatus(), entry.eventType(),
            entry.occurredAt(), entry.holdEventId(), entry.writeOffEventId(), entry.reason(), entry.sessionId(),
            damagedLater, entry.soldEventId());
    }

    /**
     * Did this piece's departure from sellable reach Shopify? THE rule (D5) — every return to available
     * decides with it. Main warehouse linked and Shopify counting there, or a skip; then {@link #reach}. A piece away
     * from the main warehouse gets a write only when its departure claim wrote at main.
     * Call it inside the transaction that moves the piece back to available — it cancels a departure
     * claim that was never sent.
     */
    public static Verdict departureReached(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        Setup s = setup(jdbc, tenantId);
        Piece p = piece(jdbc, tenantId, pieceId);
        if (p == null) throw new IllegalStateException("piece not found: " + pieceId);
        Reach r = reach(jdbc, tenantId, pieceId, true);
        UUID rowLocation = s.mainId() != null ? s.mainId() : p.locationId();
        if (s.skipReason() != null) return Verdict.skip(rowLocation, s.skipReason());
        if (r.reached()) return Verdict.send(s.mainId(), r.via());
        return Verdict.skip(s.mainId(), r.reason());
    }

    /** How (or why not) a departure reached Shopify. via: the departure claim's trigger type, "seed",
     *  "stock_take", or "inspection" (damaged at return inspection — never counted, D11 case a). */
    public record Reach(boolean reached, String via, String reason) {
        static Reach yes(String via) { return new Reach(true, via, null); }
        static Reach no(String reason) { return new Reach(false, null, reason); }
    }

    /**
     * The core of {@link #departureReached}, without the tenant-level gate — the stock-take found +1
     * (StockTakeReconciliationService) uses it directly, its increment has its own preconditions.
     * The departure is the entry of the piece's trailing "bad" run ({@link #departure}):
     *   from available → on_hold: hold_enter (piece:hold_event_id)
     *                  → damaged: damage_move (this piece's claims made at/after the departure)
     *                  → lost / destroyed by Lookup: piece_write_off (piece:write_off_event_id)
     *                  → sold by Lookup (sold_offline): piece_sold (piece:sold_event_id)
     *                  → lost by a stock take: that session's push — 'pushed' with the variant in its
     *                    deltas, or superseded by the seed (wholly, or the variant in payload.superseded)
     *                    with the write-off at/before the seed's snapshot
     *   from return_pending_inspection → damaged: "inspection" (never counted)
     *   anything else (courier, transfer, …): not reached.
     * A lost piece whose run went through damaged → not reached (damaged → lost writes nothing, v1).
     * Seeded tenant, piece at main, departure before the seed → reached via "seed" (the seed left the
     * piece out) — except a stock-take write-off, which the seed handles on its push row.
     * A departure claim: applied → reached; superseded_by_seed → reached via "seed" (at main);
     * queued and never sent → cancelled when {@code cancelOpen} (departure_cancelled); sent and definitely
     * rejected with attempts left → closed when {@code cancelOpen} (departure_rejected — never "cancelled":
     * a claim with send_started_at set is never cancelled); pending (sent, no outcome) / failed_ambiguous /
     * a needs-check skip → departure_ambiguous (alert, no Shopify call); else not reached.
     */
    public static Reach reach(JdbcTemplate jdbc, UUID tenantId, String pieceId, boolean cancelOpen) {
        Piece p = piece(jdbc, tenantId, pieceId);
        if (p == null) throw new IllegalStateException("piece not found: " + pieceId);
        Departure d = departure(jdbc, tenantId, pieceId);
        if (d == null) return Reach.no(DEPARTURE_NOT_REACHED);
        String currentStatus = jdbc.queryForObject(
            "SELECT status::text FROM pieces WHERE id = ? AND tenant_id = ?", String.class, pieceId, tenantId);

        if ("return_pending_inspection".equals(d.fromStatus()) && "damaged".equals(d.toStatus())
                && "damaged".equals(currentStatus)) {
            return Reach.yes("inspection");
        }
        if (!"available".equals(d.fromStatus())) return Reach.no(DEPARTURE_NOT_REACHED);
        if ("lost".equals(currentStatus) && (d.viaDamaged() || "damaged".equals(d.toStatus()))) {
            return Reach.no(DEPARTURE_NOT_REACHED);
        }

        boolean stockTake = "lost".equals(d.toStatus()) && "stock_take_missing".equals(d.reason());
        if (stockTake) {
            // A write-off left out of the session's push (its arrival hadn't reached Shopify) has its own claim.
            String own = departureClaimStatus(jdbc, tenantId, "piece_write_off", "sia.trigger_id = ?",
                List.of(stockTakeWriteOffKey(pieceId, d.sessionId())), cancelOpen);
            if (!"none".equals(own)) return claimReach(own, "piece_write_off", false);
        }
        String claimType = switch (d.toStatus()) {
            case "on_hold" -> "hold_enter";
            case "damaged" -> "damage_move";
            case "voided"  -> "void_correction";
            case "lost", "destroyed" -> !stockTake && d.writeOffEventId() != null ? "piece_write_off" : null;
            case "sold"    -> "sold_offline".equals(d.eventType()) && d.soldEventId() != null ? "piece_sold" : null;
            default -> null;
        };
        // An open departure claim must never be sent once the piece is back — whatever else is decided.
        String claimStatus = claimType == null ? null
            : departureClaimStatus(jdbc, tenantId, pieceId, d, claimType, cancelOpen);
        if (stockTake) return stockTakeWriteOffReached(jdbc, tenantId, p, d);

        Setup s = setup(jdbc, tenantId);
        boolean atMain = s.mainId() != null && s.mainId().equals(p.locationId());
        if (s.seededAt() != null && atMain && d.occurredAt().before(s.seededAt())) return Reach.yes("seed");
        if (claimStatus == null) return Reach.no(DEPARTURE_NOT_REACHED);
        return claimReach(claimStatus, claimType, atMain);
    }

    private static Reach claimReach(String claimStatus, String claimType, boolean atMain) {
        return switch (claimStatus) {
            case "applied"       -> Reach.yes(claimType);
            case "cancelled_now" -> Reach.no(DEPARTURE_CANCELLED);
            case "rejected_now"  -> Reach.no(DEPARTURE_REJECTED);
            case "ambiguous"     -> Reach.no(DEPARTURE_AMBIGUOUS);
            case ShopifyInventoryService.SUPERSEDED_BY_SEED -> atMain ? Reach.yes("seed") : Reach.no(NOT_AT_MAIN);
            default              -> Reach.no(DEPARTURE_NOT_REACHED);
        };
    }

    /** A stock-take write-off FROM available: did its session push take this unit out of Shopify? */
    private static Reach stockTakeWriteOffReached(JdbcTemplate jdbc, UUID tenantId, Piece p, Departure d) {
        Boolean reached = jdbc.query(
            "SELECT (y.status = 'pushed' AND y.pushed_at IS NOT NULL " +
            "        AND jsonb_exists(y.payload->'deltas', ?)) " +
            "    OR (y.superseded_snapshot_at IS NOT NULL AND ? <= y.superseded_snapshot_at " +
            "        AND ((y.status = 'superseded_by_seed' AND jsonb_exists(y.payload->'deltas', ?)) " +
            "             OR jsonb_exists(COALESCE(y.payload->'superseded', '{}'::jsonb), ?))) AS reached " +
            "FROM stock_take_shopify_syncs y WHERE y.tenant_id = ? AND y.session_id::text = ?",
            rs -> rs.next() && rs.getBoolean("reached"),
            p.variantId().toString(), d.occurredAt(), p.variantId().toString(), p.variantId().toString(),
            tenantId, String.valueOf(d.sessionId()));
        return Boolean.TRUE.equals(reached) ? Reach.yes("stock_take") : Reach.no(DEPARTURE_NOT_REACHED);
    }

    /**
     * The departure claim's state, normalised: applied | cancelled_now (was open, cancelled by this
     * call) | ambiguous | superseded_by_seed | not_reached. Claim key: hold_enter piece:hold_event_id;
     * piece_write_off piece:write_off_event_id; piece_sold piece:sold_event_id;
     * void_correction piece; damage_move — the claims of this piece made at or after the departure.
     */
    private static String departureClaimStatus(JdbcTemplate jdbc, UUID tenantId, String pieceId,
                                               Departure d, String claimType, boolean cancelOpen) {
        String keySql;
        List<Object> keyArgs = new ArrayList<>();
        if ("hold_enter".equals(claimType)) {
            if (d.holdEventId() == null) return "not_reached";
            keySql = "sia.trigger_id = ?";
            keyArgs.add(pieceId + ":" + d.holdEventId());
        } else if ("piece_write_off".equals(claimType)) {
            keySql = "sia.trigger_id = ?";
            keyArgs.add(pieceId + ":" + d.writeOffEventId());
        } else if ("piece_sold".equals(claimType)) {
            keySql = "sia.trigger_id = ?";
            keyArgs.add(pieceId + ":" + d.soldEventId());
        } else {
            keySql = "(sia.trigger_id = ? OR sia.trigger_id LIKE ?) AND sia.created_at >= ?";
            keyArgs.add(pieceId);
            keyArgs.add(pieceId + ":%");
            keyArgs.add(d.occurredAt());
        }
        String st = departureClaimStatus(jdbc, tenantId, claimType, keySql, keyArgs, cancelOpen);
        return "none".equals(st) ? "not_reached" : st;
    }

    /** {@link #departureClaimStatus(JdbcTemplate, UUID, String, Departure, String, boolean)} for an explicit key;
     *  "none" when no claim matches it. */
    private static String departureClaimStatus(JdbcTemplate jdbc, UUID tenantId, String claimType,
                                               String keySql, List<Object> keyArgs, boolean cancelOpen) {
        List<Object> args = new ArrayList<>(List.of(tenantId, claimType));
        args.addAll(keyArgs);
        // Skipped because its arrival hadn't reached Shopify, and the piece is back before that arrival applied:
        // nothing was sent and nothing is owed — close it, so the arrival revisit (ArrivalRevisit) never sends it.
        // Runs FIRST and on the claim row: a revisit that flipped it to 'queued' already is caught by the cancel
        // below; one in flight waits on this row and then finds it closed.
        int closedArrival = !cancelOpen ? 0 : jdbc.update(
            "UPDATE shopify_inventory_adjustments sia SET skip_reason = '" + NOT_SENT_PIECE_RETURNED + "' " +
            "WHERE sia.tenant_id = ? AND sia.trigger_type = ? AND " + keySql + " AND " + LIVE_SQL +
            "  AND sia.status = 'skipped' AND sia.skip_reason IN " + ArrivalRevisit.REVISIT_REASONS_SQL,
            args.toArray());
        // Only a claim that was NEVER SENT may be cancelled: queued with no send_started_at (a crash
        // before the after-commit push). Locks the row — a concurrent push either already took it to
        // pending (→ ambiguous below) or waits and then finds it cancelled.
        int cancelled = !cancelOpen ? 0 : jdbc.update(
            "UPDATE shopify_inventory_adjustments sia SET status = 'cancelled', " +
            "    error = 'Piece back to good before this was sent — never sent' " +
            "WHERE sia.tenant_id = ? AND sia.trigger_type = ? AND " + keySql + " AND " + LIVE_SQL +
            "  AND sia.status = 'queued' AND sia.send_started_at IS NULL",
            args.toArray());
        // A claim that WAS sent and definitely rejected (nothing applied) but still has attempts left: the
        // sweep would re-send it now that the piece is back — close it instead. It keeps its failure_class
        // and Shopify's error; it is never relabelled "cancelled" / "never sent".
        int closed = !cancelOpen ? 0 : jdbc.update(
            "UPDATE shopify_inventory_adjustments sia SET status = 'skipped', skip_reason = '" + NOT_RESENT_PIECE_RETURNED + "' " +
            "WHERE sia.tenant_id = ? AND sia.trigger_type = ? AND " + keySql + " AND " + LIVE_SQL +
            "  AND sia.status = 'failed' AND sia.attempt_count < " + MAX_ATTEMPTS,
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
        if (rows.isEmpty()) return "none";
        if (applied) return "applied";
        if (ambiguous) return "ambiguous";
        if (cancelled > 0) return "cancelled_now";
        if (closedArrival > 0) return "not_reached";   // never sent: the departure never reached Shopify
        if (closed > 0) return "rejected_now";
        if (superseded) return ShopifyInventoryService.SUPERSEDED_BY_SEED;
        return "not_reached";
    }
}
