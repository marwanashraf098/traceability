package com.traceability.places;

import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PlaceChangeException;
import com.traceability.inventory.ScanInputNormalizer;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Warehouse places S2 — Put away: the waiting list (tagged, placeable pieces with no place, per location), a
 * suggested place for each, put-away sessions (scan a shelf / box sticker, then pieces) and Lookup's Move. Every
 * role; every method answers 404 unless the tenant has places ({@link Places}).
 *
 * The only writer of a piece's place is {@link InventoryLedger#recordPlaceChange} (one conditional UPDATE + one
 * piece event, status unchanged). Nothing here calls Shopify (PlacesShopifyGuardTest).
 *
 * A session's current place is server-side (put_away_sessions.current_bin_id): a place sticker sets it, a piece
 * goes into it. S2 opens 'manual' sessions only — any piece with no place at the session's location; S4 will open
 * sessions scoped to a receipt / return session / transfer (source + source_id, pre-filled session pieces).
 *
 * Waiting list "came from" = the piece's latest event that isn't a label, hold, put-away or in-warehouse status
 * change (WAITING_SQL): received → Receiving; restocked / return_received / inspected from a return → Returns;
 * back from or moved by a transfer → Transfers; found in a stock take → Stock take; anything else (an unreserved
 * piece, a cleared place, a Lookup fix) → Other. "Waiting since" is that event's time.
 *
 * Sessions: one open session per person per location — Start resumes it. The opener finishes it; an owner / manager
 * can close anyone's open session ('closed_by_manager'); PutAwayIdleCloseJob closes one with no scan for
 * {@link #IDLE_HOURS} h ('idle'). Closing records a summary (placed, places used, per place) and never moves a piece.
 *
 * Suggested place (SUGGEST_*): the piece's last place (its latest piece_events.to_bin_id that is a live place of
 * the same location), else the live place holding the most available pieces of its variant at that location
 * (ties: lowest code), else none. Two batch queries for the whole page, never one per piece.
 */
@Service
public class PutAwayService {

    /** Rows returned per waiting-list page; counts always cover every waiting piece. */
    static final int MAX_ROWS = 500;
    /** An open session with no scan (or resume) for this long is closed by PutAwayIdleCloseJob. */
    public static final int IDLE_HOURS = 12;
    public static final List<String> SOURCES = List.of("receiving", "returns", "transfers", "stock_take", "other");

    public record PlaceView(UUID id, String kind, String code, List<String> trail, String parentCode, String parentKind) {
        static PlaceView of(PlacePaths.Place p) {
            return p == null ? null : new PlaceView(p.id(), p.kind(), p.code(), p.trail(), p.parentCode(), p.parentKind());
        }
    }
    public record LocationCount(UUID id, String name, boolean main, int waiting) {}
    /** {@code waiting}: pieces with no place at every location that has a shelf or box (the sidebar badge). */
    public record Summary(int waiting, List<LocationCount> locations) {}
    public record Suggestion(PlaceView place, String reason) {}
    public record WaitingRow(String pieceId, String code, UUID variantId, String productTitle, String variantTitle,
                             String sku, String status, String source, String sourceRef, Instant waitingSince,
                             Suggestion suggestion) {}
    /** {@code mine}: the caller's own open session at this location (Start resumes it), or null. */
    public record Waiting(UUID locationId, String locationName, boolean hasPlaces, int total, Map<String, Integer> counts,
                          String source, List<WaitingRow> rows, boolean truncated, MySession mine) {}
    public record MySession(UUID id, int placed, Instant createdAt, Instant lastActivityAt) {}
    /** An open session, for the owner / manager list ("Open sessions"). */
    public record OpenSession(UUID id, UUID locationId, String locationName, UUID openedBy, String openedByName,
                              Instant createdAt, Instant lastActivityAt, int placed) {}
    public record CurrentPlace(PlaceView place, int putHereNow, int onThisPlace) {}
    /** {@code closeReason}: finished | closed_by_manager | idle (null while open). */
    public record Session(UUID id, UUID locationId, String locationName, String status, CurrentPlace current,
                          int placed, int placesUsed, int stillWaiting, Instant createdAt, Instant closedAt,
                          String closeReason) {}
    public record PieceView(String id, String code, String productTitle, String variantTitle) {}
    /**
     * {@code outcome}: PLACE_SET, PLACED, MOVED, ALREADY_HERE (writes nothing) · SCAN_PLACE_FIRST, SCAN_SHELF_OR_BOX,
     * UNKNOWN_STICKER, PLACE_OTHER_LOCATION, PLACE_ARCHIVED, NOT_AT_THIS_LOCATION, CANNOT_PLACE, UNTAGGED (refusals,
     * nothing written). {@code fromPlace}: the previous place (MOVED, PLACE_SET).
     */
    public record ScanResult(String outcome, String code, PieceView piece, PlaceView place, PlaceView fromPlace,
                             String locationName, String status, Session session, boolean viaPhone) {}
    public record PlaceUse(PlaceView place, int pieces) {}
    public record CloseSummary(Session session, List<PlaceUse> places) {}
    public record MoveResult(String kind, PlaceView from, PlaceView to) {}

    private final JdbcTemplate jdbc;
    private final InventoryLedger ledger;

    public PutAwayService(JdbcTemplate jdbc, InventoryLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    // ── waiting list ─────────────────────────────────────────────────────────

    private static final String WAITING_PIECE =
        "p.tenant_id = ? AND p.bin_id IS NULL AND p.short_code IS NOT NULL " +
        "AND p.status::text IN ('available', 'on_hold', 'damaged')";

    private static final String HOLDABLE_EXISTS =
        "EXISTS (SELECT 1 FROM bins b WHERE b.tenant_id = l.tenant_id AND b.location_id = l.id " +
        "        AND b.kind IN ('shelf', 'box') AND b.archived_at IS NULL)";

    /** The pieces waiting at one location ({tenant, location}), each with its source and since. */
    static final String WAITING_SQL =
        "SELECT p.id, p.short_code, p.variant_id, p.status::text AS status, " +
        "       COALESCE(ev.occurred_at, p.created_at) AS since, ev.order_id, " +
        "       CASE WHEN ev.event_type = 'received' THEN 'receiving' " +
        "            WHEN ev.event_type IN ('restocked', 'return_received') " +
        "              OR ev.from_status = 'return_pending_inspection' THEN 'returns' " +
        "            WHEN ev.event_type IN ('returned_from_transfer', 'transferred_out', 'relocated_out') THEN 'transfers' " +
        "            WHEN ev.event_type = 'adjusted' AND ev.metadata ->> 'reason' = 'stock_take_found' THEN 'stock_take' " +
        "            ELSE 'other' END AS source " +
        "FROM pieces p " +
        "LEFT JOIN LATERAL ( " +
        "  SELECT e.event_type, e.occurred_at, e.metadata, e.order_id, e.from_status::text AS from_status " +
        "  FROM piece_events e " +
        "  WHERE e.piece_id = p.id AND e.tenant_id = p.tenant_id " +
        "    AND e.event_type NOT IN ('label_reprinted', 'label_bound', 'held', 'unheld', 'put_away', 'place_moved') " +
        "    AND NOT (e.event_type = 'adjusted' AND e.to_status::text IN ('damaged', 'on_hold')) " +
        "  ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1 " +
        ") ev ON true " +
        "WHERE " + WAITING_PIECE + " AND p.current_location_id = ?";

    @Transactional(readOnly = true)
    public Summary summary() {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        List<LocationCount> locations = jdbc.query(
            "SELECT l.id, l.name, l.is_fulfillment, " +
            "       (SELECT count(*)::int FROM pieces p WHERE " + WAITING_PIECE + " AND p.current_location_id = l.id) AS waiting " +
            "FROM locations l WHERE l.tenant_id = ? AND " + HOLDABLE_EXISTS + " " +
            "ORDER BY l.is_fulfillment DESC, l.name, l.id",
            (rs, i) -> new LocationCount(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getBoolean("is_fulfillment"), rs.getInt("waiting")),
            tenantId, tenantId);
        return new Summary(locations.stream().mapToInt(LocationCount::waiting).sum(), locations);
    }

    @Transactional(readOnly = true)
    public Waiting waiting(UUID requestedLocationId, String source, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Loc loc = location(tenantId, requestedLocationId);
        String filter = source == null || source.isBlank() || "all".equals(source) ? null : source;
        if (filter != null && !SOURCES.contains(filter)) filter = null;

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : SOURCES) counts.put(s, 0);
        jdbc.query("SELECT source, count(*)::int AS n FROM (" + WAITING_SQL + ") w GROUP BY source",
            rs -> { counts.put(rs.getString("source"), rs.getInt("n")); }, tenantId, loc.id());
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        List<Object> args = new ArrayList<>(List.of(tenantId, loc.id()));
        if (filter != null) args.add(filter);
        args.add(MAX_ROWS + 1);
        record Raw(String id, String code, UUID variantId, String product, String variant, String sku, String status,
                   String source, String ref, Instant since) {}
        List<Raw> raws = jdbc.query(
            "SELECT w.id, w.short_code, w.variant_id, pr.title AS product_title, v.title AS variant_title, v.sku, " +
            "       w.status, w.source, w.since, CASE WHEN w.source = 'returns' THEN o.number END AS ref " +
            "FROM (" + WAITING_SQL + ") w " +
            "JOIN variants v ON v.id = w.variant_id JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN merchant_orders o ON o.id = w.order_id " +
            (filter != null ? "WHERE w.source = ? " : "") +
            "ORDER BY w.since DESC, w.id DESC LIMIT ?",
            (rs, i) -> new Raw(rs.getString("id"), rs.getString("short_code"), rs.getObject("variant_id", UUID.class),
                rs.getString("product_title"), rs.getString("variant_title"), rs.getString("sku"), rs.getString("status"),
                rs.getString("source"), rs.getString("ref"), rs.getTimestamp("since").toInstant()),
            args.toArray());
        boolean truncated = raws.size() > MAX_ROWS;
        if (truncated) raws = raws.subList(0, MAX_ROWS);

        Map<String, Suggestion> suggestions = suggest(tenantId, loc.id(),
            raws.stream().map(r -> Map.entry(r.id(), r.variantId())).toList());
        List<WaitingRow> rows = raws.stream().map(r -> new WaitingRow(r.id(), r.code(), r.variantId(), r.product(),
            r.variant(), r.sku(), r.status(), r.source(), r.ref(), r.since(), suggestions.get(r.id()))).toList();
        List<MySession> mine = jdbc.query(
            "SELECT s.id, s.created_at, s.last_activity_at, " +
            "       (SELECT count(*)::int FROM put_away_session_pieces sp WHERE sp.session_id = s.id " +
            "        AND sp.placed_bin_id IS NOT NULL) AS placed " +
            "FROM put_away_sessions s WHERE s.tenant_id = ? AND s.opened_by = ? AND s.location_id = ? " +
            "AND s.status = 'open' AND s.source = 'manual'",
            (rs, i) -> new MySession(rs.getObject("id", UUID.class), rs.getInt("placed"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_activity_at").toInstant()),
            tenantId, actorUserId, loc.id());
        return new Waiting(loc.id(), loc.name(), hasPlaces(tenantId, loc.id()), total, counts, filter == null ? "all" : filter,
            rows, truncated, mine.isEmpty() ? null : mine.get(0));
    }

    static final String SUGGEST_LAST_PLACE =
        "SELECT DISTINCT ON (e.piece_id) e.piece_id, e.to_bin_id FROM piece_events e " +
        "JOIN bins b ON b.id = e.to_bin_id AND b.tenant_id = e.tenant_id " +
        "WHERE e.tenant_id = ? AND e.piece_id = ANY(?) AND e.to_bin_id IS NOT NULL " +
        "  AND b.location_id = ? AND b.archived_at IS NULL AND b.kind IN ('shelf', 'box') " +
        "ORDER BY e.piece_id, e.occurred_at DESC, e.id DESC";

    static final String SUGGEST_MOST_OF_VARIANT =
        "SELECT DISTINCT ON (p.variant_id) p.variant_id, p.bin_id FROM pieces p " +
        "JOIN bins b ON b.id = p.bin_id AND b.tenant_id = p.tenant_id " +
        "WHERE p.tenant_id = ? AND p.current_location_id = ? AND p.status = 'available' AND p.variant_id = ANY(?) " +
        "  AND b.archived_at IS NULL " +
        "GROUP BY p.variant_id, p.bin_id, b.code ORDER BY p.variant_id, count(*) DESC, upper(b.code), p.bin_id";

    /** Suggested place per piece (pieceId → suggestion; absent = no suggestion). Two queries for any number. */
    Map<String, Suggestion> suggest(UUID tenantId, UUID locationId, List<Map.Entry<String, UUID>> pieces) {
        Map<String, Suggestion> out = new HashMap<>();
        if (pieces.isEmpty()) return out;
        String[] ids = pieces.stream().map(Map.Entry::getKey).toArray(String[]::new);
        UUID[] variants = pieces.stream().map(Map.Entry::getValue).distinct().toArray(UUID[]::new);
        Map<String, UUID> last = new HashMap<>();
        jdbc.query(SUGGEST_LAST_PLACE, rs -> { last.put(rs.getString("piece_id"), rs.getObject("to_bin_id", UUID.class)); },
            tenantId, ids, locationId);
        Map<UUID, UUID> most = new HashMap<>();
        jdbc.query(SUGGEST_MOST_OF_VARIANT,
            rs -> { most.put(rs.getObject("variant_id", UUID.class), rs.getObject("bin_id", UUID.class)); },
            tenantId, locationId, variants);
        Set<UUID> binIds = new HashSet<>(last.values());
        binIds.addAll(most.values());
        Map<UUID, PlacePaths.Place> described = PlacePaths.describe(jdbc, tenantId, binIds);
        for (Map.Entry<String, UUID> p : pieces) {
            UUID lastBin = last.get(p.getKey());
            if (lastBin != null && described.containsKey(lastBin)) {
                out.put(p.getKey(), new Suggestion(PlaceView.of(described.get(lastBin)), "last_place"));
                continue;
            }
            UUID mostBin = most.get(p.getValue());
            if (mostBin != null && described.containsKey(mostBin)) {
                out.put(p.getKey(), new Suggestion(PlaceView.of(described.get(mostBin)), "most_of_variant"));
            }
        }
        return out;
    }

    /** The live shelves and boxes of a location (null → main warehouse), by code — Move's "pick from the list". */
    @Transactional(readOnly = true)
    public List<PlaceView> holdablePlaces(UUID requestedLocationId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Loc loc = location(tenantId, requestedLocationId);
        List<UUID> ids = jdbc.queryForList(
            "SELECT id FROM bins WHERE tenant_id = ? AND location_id = ? AND kind IN ('shelf', 'box') AND archived_at IS NULL",
            UUID.class, tenantId, loc.id());
        Map<UUID, PlacePaths.Place> d = PlacePaths.describe(jdbc, tenantId, ids);
        return d.values().stream().map(PlaceView::of)
            .sorted((a, b) -> PlacesService.naturalCompare(a.code(), b.code())).toList();
    }

    // ── sessions ─────────────────────────────────────────────────────────────

    /** Starts a manual session at the location (null → main warehouse), or resumes the caller's open one there. */
    @Transactional
    public Session start(UUID actorUserId, UUID requestedLocationId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Loc loc = location(tenantId, requestedLocationId);
        List<UUID> open = jdbc.queryForList(
            "SELECT id FROM put_away_sessions WHERE tenant_id = ? AND opened_by = ? AND location_id = ? " +
            "AND status = 'open' AND source = 'manual'", UUID.class, tenantId, actorUserId, loc.id());
        UUID id = open.isEmpty()
            ? jdbc.queryForObject(
                "INSERT INTO put_away_sessions (tenant_id, location_id, source, opened_by) VALUES (?, ?, 'manual', ?) " +
                "ON CONFLICT (tenant_id, opened_by, location_id) WHERE status = 'open' AND source = 'manual' " +
                "DO UPDATE SET last_activity_at = now() RETURNING id",
                UUID.class, tenantId, loc.id(), actorUserId)
            : open.get(0);
        // Resuming is activity: the idle clock restarts.
        jdbc.update("UPDATE put_away_sessions SET last_activity_at = now() WHERE id = ? AND tenant_id = ? AND status = 'open'",
            id, tenantId);
        return session(tenantId, requireSession(tenantId, id, actorUserId, false, false));
    }

    @Transactional(readOnly = true)
    public Session get(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        return session(tenantId, requireSession(tenantId, sessionId, actorUserId, false, false));
    }

    /** Every open session of the tenant, oldest activity first (owner / manager — PutAwayController). */
    @Transactional(readOnly = true)
    public List<OpenSession> openSessions() {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        return jdbc.query(
            "SELECT s.id, s.location_id, l.name AS location_name, s.opened_by, u.name AS opened_by_name, s.created_at, " +
            "       s.last_activity_at, (SELECT count(*)::int FROM put_away_session_pieces sp WHERE sp.session_id = s.id " +
            "                            AND sp.placed_bin_id IS NOT NULL) AS placed " +
            "FROM put_away_sessions s JOIN locations l ON l.id = s.location_id LEFT JOIN users u ON u.id = s.opened_by " +
            "WHERE s.tenant_id = ? AND s.status = 'open' ORDER BY s.last_activity_at, s.id",
            (rs, i) -> new OpenSession(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class),
                rs.getString("location_name"), rs.getObject("opened_by", UUID.class), rs.getString("opened_by_name"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_activity_at").toInstant(), rs.getInt("placed")),
            tenantId);
    }

    /** One scan in a session (see ScanResult for the outcomes). The raw scan is kept on the piece event. */
    @Transactional
    public ScanResult scan(UUID sessionId, String raw, UUID actorUserId, boolean viaPhone) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        S s = requireSession(tenantId, sessionId, actorUserId, true, false);
        if (!s.open()) throw PlaceException.sessionClosed();
        jdbc.update("UPDATE put_away_sessions SET last_activity_at = now() WHERE id = ? AND tenant_id = ?", s.id(), tenantId);
        String input = raw == null ? "" : raw;

        String sticker = ScanInputNormalizer.normalizePlace(input);
        if (sticker != null) {
            List<UUID> bin = jdbc.queryForList("SELECT id FROM bins WHERE tenant_id = ? AND scan_code = ?",
                UUID.class, tenantId, sticker);
            if (bin.isEmpty()) return result(tenantId, s, "UNKNOWN_STICKER", sticker, null, null, null, null, null, viaPhone);
            PlacePaths.Place p = PlacePaths.describe(jdbc, tenantId, bin.get(0));
            PlaceView view = PlaceView.of(p);
            if (!p.locationId().equals(s.locationId())) {
                return result(tenantId, s, "PLACE_OTHER_LOCATION", sticker, null, view, null, locationName(tenantId, p.locationId()),
                    null, viaPhone);
            }
            if (p.archived()) return result(tenantId, s, "PLACE_ARCHIVED", sticker, null, view, null, null, null, viaPhone);
            if (!"shelf".equals(p.kind()) && !"box".equals(p.kind())) {
                return result(tenantId, s, "SCAN_SHELF_OR_BOX", sticker, null, view, null, null, null, viaPhone);
            }
            PlaceView previous = s.currentBinId() == null || s.currentBinId().equals(p.id()) ? null
                : PlaceView.of(PlacePaths.describe(jdbc, tenantId, s.currentBinId()));
            jdbc.update("UPDATE put_away_sessions SET current_bin_id = ? WHERE id = ? AND tenant_id = ?",
                p.id(), s.id(), tenantId);
            S updated = new S(s.id(), s.locationId(), s.locationName(), true, p.id(), s.createdAt(), s.closedAt(), null);
            return result(tenantId, updated, "PLACE_SET", sticker, null, view, previous, null, null, viaPhone);
        }

        String code = ScanInputNormalizer.normalizePiece(input);
        List<Map<String, Object>> found = jdbc.queryForList(
            "SELECT p.id, p.short_code, p.current_location_id, pr.title AS product_title, v.title AS variant_title " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.tenant_id = ? AND (p.barcode = ? OR p.id = ? OR p.short_code = ?) LIMIT 1",
            tenantId, code, code, code);
        if (found.isEmpty()) return result(tenantId, s, "UNKNOWN_STICKER", code, null, null, null, null, null, viaPhone);
        Map<String, Object> row = found.get(0);
        String pieceId = (String) row.get("id");
        PieceView piece = new PieceView(pieceId, (String) row.get("short_code"), (String) row.get("product_title"),
            (String) row.get("variant_title"));
        PlaceView current = s.currentBinId() == null ? null : PlaceView.of(PlacePaths.describe(jdbc, tenantId, s.currentBinId()));
        if (current == null) return result(tenantId, s, "SCAN_PLACE_FIRST", code, piece, null, null, null, null, viaPhone);
        if (row.get("short_code") == null) return result(tenantId, s, "UNTAGGED", code, piece, current, null, null, null, viaPhone);
        UUID pieceLocation = (UUID) row.get("current_location_id");
        if (!s.locationId().equals(pieceLocation)) {
            return result(tenantId, s, "NOT_AT_THIS_LOCATION", code, piece, current, null,
                pieceLocation == null ? null : locationName(tenantId, pieceLocation), null, viaPhone);
        }

        InventoryLedger.PlaceChange change;
        try {
            change = ledger.recordPlaceChange(pieceId, s.currentBinId(), actorUserId,
                InventoryLedger.PlaceRef.putAway(s.id(), viaPhone), raw);
        } catch (PlaceChangeException e) {
            String outcome = switch (e.code()) {
                case UNTAGGED -> "UNTAGGED";
                case STATUS_NOT_PLACEABLE -> "CANNOT_PLACE";
                case PLACE_ARCHIVED -> "PLACE_ARCHIVED";
                case NO_LOCATION, OTHER_LOCATION -> "NOT_AT_THIS_LOCATION";
                case NOT_A_SHELF_OR_BOX -> "SCAN_SHELF_OR_BOX";
                default -> "UNKNOWN_STICKER";
            };
            String status = jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
            return result(tenantId, s, outcome, code, piece, current, null, null, status, viaPhone);
        }
        if (change.kind() == InventoryLedger.PlaceChangeKind.ALREADY_HERE) {
            return result(tenantId, s, "ALREADY_HERE", code, piece, current, null, null, change.status(), viaPhone);
        }
        jdbc.update(
            "INSERT INTO put_away_session_pieces (session_id, tenant_id, piece_id, placed_bin_id, placed_at) " +
            "VALUES (?, ?, ?, ?, now()) ON CONFLICT (session_id, piece_id) " +
            "DO UPDATE SET placed_bin_id = EXCLUDED.placed_bin_id, placed_at = EXCLUDED.placed_at",
            s.id(), tenantId, pieceId, change.toBinId());
        boolean moved = change.kind() == InventoryLedger.PlaceChangeKind.MOVED;
        PlaceView from = moved ? PlaceView.of(PlacePaths.describe(jdbc, tenantId, change.fromBinId())) : null;
        return result(tenantId, s, moved ? "MOVED" : "PLACED", code, piece, current, from, null, change.status(), viaPhone);
    }

    /**
     * Closes a session and records its summary: the opener's own ('finished'), or — {@code manager} true (owner /
     * manager) — anyone's open session ('closed_by_manager'). A worker can't reach someone else's session (404).
     * Pieces placed stay placed. Idempotent on a closed session.
     */
    @Transactional
    public CloseSummary close(UUID sessionId, UUID actorUserId, boolean manager) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        S s = requireSession(tenantId, sessionId, actorUserId, true, manager);
        if (s.open()) {
            closeLocked(tenantId, s.id(), actorUserId, actorUserId.equals(s.openedBy()) ? "finished" : "closed_by_manager");
        }
        s = requireSession(tenantId, sessionId, actorUserId, false, manager);
        return summaryOf(tenantId, s);
    }

    /** Backward-compatible: the opener finishing their own session. */
    @Transactional
    public CloseSummary close(UUID sessionId, UUID actorUserId) {
        return close(sessionId, actorUserId, false);
    }

    /**
     * PutAwayIdleCloseJob's pass for the current tenant: closes ('idle', closed_by NULL) every open session with no
     * scan or resume for {@link #IDLE_HOURS} h — elapsed time on timestamptz, so the time zone (Africa/Cairo, DST)
     * never matters. Row-locked with SKIP LOCKED and re-checked in the UPDATE, so a scan landing meanwhile keeps the
     * session open. Returns the sessions closed.
     */
    @Transactional
    public List<UUID> closeIdle() {
        UUID tenantId = TenantContext.require();
        List<UUID> idle = jdbc.queryForList(
            "SELECT id FROM put_away_sessions WHERE tenant_id = ? AND status = 'open' " +
            "AND last_activity_at < now() - (interval '1 hour' * ?) ORDER BY last_activity_at, id FOR UPDATE SKIP LOCKED",
            UUID.class, tenantId, IDLE_HOURS);
        List<UUID> closed = new ArrayList<>();
        for (UUID id : idle) if (closeLocked(tenantId, id, null, "idle") == 1) closed.add(id);
        return closed;
    }

    /** status → closed with the reason and the summary (placed, places used, per place); 0 if no longer closable. */
    private int closeLocked(UUID tenantId, UUID sessionId, UUID closedBy, String reason) {
        return jdbc.update(
            "UPDATE put_away_sessions s SET status = 'closed', closed_at = now(), closed_by = ?, close_reason = ?, " +
            "  summary = (SELECT jsonb_build_object( " +
            "      'placed', count(*), 'placesUsed', count(DISTINCT sp.placed_bin_id), " +
            "      'places', COALESCE((SELECT jsonb_agg(jsonb_build_object('placeId', b.id, 'code', b.code, 'pieces', x.n) " +
            "                                ORDER BY upper(b.code)) " +
            "                          FROM (SELECT placed_bin_id, count(*)::int AS n FROM put_away_session_pieces " +
            "                                WHERE session_id = s.id AND placed_bin_id IS NOT NULL GROUP BY placed_bin_id) x " +
            "                          JOIN bins b ON b.id = x.placed_bin_id), '[]'::jsonb)) " +
            "    FROM put_away_session_pieces sp WHERE sp.session_id = s.id AND sp.placed_bin_id IS NOT NULL) " +
            "WHERE s.id = ? AND s.tenant_id = ? AND s.status = 'open'" +
            ("idle".equals(reason) ? " AND s.last_activity_at < now() - (interval '1 hour' * " + IDLE_HOURS + ")" : ""),
            closedBy, reason, sessionId, tenantId);
    }

    private CloseSummary summaryOf(UUID tenantId, S s) {
        Map<UUID, Integer> perPlace = new LinkedHashMap<>();
        jdbc.query(
            "SELECT placed_bin_id, count(*)::int AS n FROM put_away_session_pieces " +
            "WHERE session_id = ? AND tenant_id = ? AND placed_bin_id IS NOT NULL GROUP BY placed_bin_id",
            rs -> { perPlace.put(rs.getObject("placed_bin_id", UUID.class), rs.getInt("n")); }, s.id(), tenantId);
        Map<UUID, PlacePaths.Place> d = PlacePaths.describe(jdbc, tenantId, perPlace.keySet());
        List<PlaceUse> places = perPlace.entrySet().stream()
            .filter(e -> d.containsKey(e.getKey()))
            .map(e -> new PlaceUse(PlaceView.of(d.get(e.getKey())), e.getValue()))
            .sorted((a, b) -> PlacesService.naturalCompare(a.place().code(), b.place().code()))
            .toList();
        return new CloseSummary(session(tenantId, s), places);
    }

    // ── Lookup: Move ─────────────────────────────────────────────────────────

    /**
     * Lookup's Move: a scanned place sticker ({@code scan}) or a picked place ({@code placeId}); neither → No place.
     * Same writer and refusals as a put-away scan (source 'lookup').
     */
    @Transactional
    public MoveResult movePiece(String pieceId, UUID placeId, String scan, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        UUID target = placeId;
        if (scan != null && !scan.isBlank()) {
            String sticker = ScanInputNormalizer.normalizePlace(scan);
            if (sticker == null) throw PlaceException.unknownSticker();
            List<UUID> bin = jdbc.queryForList("SELECT id FROM bins WHERE tenant_id = ? AND scan_code = ?",
                UUID.class, tenantId, sticker);
            if (bin.isEmpty()) throw PlaceException.unknownSticker();
            target = bin.get(0);
        }
        InventoryLedger.PlaceChange c = ledger.recordPlaceChange(pieceId, target, actorUserId,
            InventoryLedger.PlaceRef.lookup(), scan);
        Map<UUID, PlacePaths.Place> d = PlacePaths.describe(jdbc, tenantId, Arrays.asList(c.fromBinId(), c.toBinId()));
        return new MoveResult(c.kind().name(), PlaceView.of(d.get(c.fromBinId())), PlaceView.of(d.get(c.toBinId())));
    }

    // ── internals ────────────────────────────────────────────────────────────

    record Loc(UUID id, String name) {}

    record S(UUID id, UUID locationId, String locationName, boolean open, UUID currentBinId, Instant createdAt,
             Instant closedAt, String closeReason, UUID openedBy) {
        S(UUID id, UUID locationId, String locationName, boolean open, UUID currentBinId, Instant createdAt,
          Instant closedAt, String closeReason) {
            this(id, locationId, locationName, open, currentBinId, createdAt, closedAt, closeReason, null);
        }
    }

    /** The caller's own session; {@code anyOpener} (owner / manager closing) — any session of the tenant. */
    private S requireSession(UUID tenantId, UUID sessionId, UUID actorUserId, boolean forUpdate, boolean anyOpener) {
        if (sessionId == null) throw PlaceException.sessionNotFound();
        List<Object> args = new ArrayList<>(List.of(sessionId, tenantId));
        if (!anyOpener) args.add(actorUserId);
        List<S> rows = jdbc.query(
            "SELECT s.id, s.location_id, l.name AS location_name, s.status, s.current_bin_id, s.created_at, s.closed_at, " +
            "       s.close_reason, s.opened_by " +
            "FROM put_away_sessions s JOIN locations l ON l.id = s.location_id " +
            "WHERE s.id = ? AND s.tenant_id = ?" + (anyOpener ? "" : " AND s.opened_by = ?") +
            (forUpdate ? " FOR UPDATE OF s" : ""),
            (rs, i) -> {
                Timestamp closed = rs.getTimestamp("closed_at");
                return new S(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class),
                    rs.getString("location_name"), "open".equals(rs.getString("status")),
                    rs.getObject("current_bin_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                    closed == null ? null : closed.toInstant(), rs.getString("close_reason"),
                    rs.getObject("opened_by", UUID.class));
            },
            args.toArray());
        if (rows.isEmpty()) throw PlaceException.sessionNotFound();
        return rows.get(0);
    }

    private Session session(UUID tenantId, S s) {
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT count(*) FILTER (WHERE placed_bin_id IS NOT NULL)::int AS placed, " +
            "       count(DISTINCT placed_bin_id)::int AS places_used, " +
            "       count(*) FILTER (WHERE placed_bin_id IS NOT DISTINCT FROM ?::uuid AND ?::uuid IS NOT NULL)::int AS here " +
            "FROM put_away_session_pieces WHERE session_id = ? AND tenant_id = ?",
            s.currentBinId(), s.currentBinId(), s.id(), tenantId);
        CurrentPlace current = null;
        if (s.currentBinId() != null) {
            Integer on = jdbc.queryForObject("SELECT count(*)::int FROM pieces WHERE tenant_id = ? AND bin_id = ?",
                Integer.class, tenantId, s.currentBinId());
            current = new CurrentPlace(PlaceView.of(PlacePaths.describe(jdbc, tenantId, s.currentBinId())),
                (Integer) t.get("here"), on == null ? 0 : on);
        }
        Integer waiting = jdbc.queryForObject(
            "SELECT count(*)::int FROM pieces p WHERE " + WAITING_PIECE + " AND p.current_location_id = ?",
            Integer.class, tenantId, s.locationId());
        return new Session(s.id(), s.locationId(), s.locationName(), s.open() ? "open" : "closed", current,
            (Integer) t.get("placed"), (Integer) t.get("places_used"), waiting == null ? 0 : waiting, s.createdAt(),
            s.closedAt(), s.closeReason());
    }

    private ScanResult result(UUID tenantId, S s, String outcome, String code, PieceView piece, PlaceView place,
                              PlaceView from, String locationName, String status, boolean viaPhone) {
        return new ScanResult(outcome, code, piece, place, from, locationName, status, session(tenantId, s), viaPhone);
    }

    private String locationName(UUID tenantId, UUID locationId) {
        List<String> n = jdbc.queryForList("SELECT name FROM locations WHERE tenant_id = ? AND id = ?",
            String.class, tenantId, locationId);
        return n.isEmpty() ? null : n.get(0);
    }

    private boolean hasPlaces(UUID tenantId, UUID locationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bins WHERE tenant_id = ? AND location_id = ? AND kind IN ('shelf', 'box') " +
            "AND archived_at IS NULL)", Boolean.class, tenantId, locationId));
    }

    /** null → the main warehouse if it has a shelf or box, else the first location that does, else the main one. */
    private Loc location(UUID tenantId, UUID requested) {
        List<Loc> found = requested != null
            ? jdbc.query("SELECT id, name FROM locations WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Loc(rs.getObject("id", UUID.class), rs.getString("name")), tenantId, requested)
            : jdbc.query(
                "SELECT l.id, l.name FROM locations l WHERE l.tenant_id = ? " +
                "AND (" + HOLDABLE_EXISTS + " OR l.is_fulfillment) " +
                "ORDER BY " + HOLDABLE_EXISTS + " DESC, l.is_fulfillment DESC, l.name, l.id LIMIT 1",
                (rs, i) -> new Loc(rs.getObject("id", UUID.class), rs.getString("name")), tenantId);
        if (found.isEmpty()) throw PlaceException.locationNotFound();
        return found.get(0);
    }
}
