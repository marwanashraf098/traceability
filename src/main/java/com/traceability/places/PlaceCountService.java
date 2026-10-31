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
 * Warehouse places S5 — counting ONE place (V198). Someone scans a shelf / box sticker, then every piece they find
 * there. Each scan only records what was found (place_count_scans); NOTHING changes on a piece until Finish, which
 * applies the count in one transaction through {@link InventoryLedger#recordPlaceChange} (source 'place_count'):
 *   - found here, but placed elsewhere ("move here") or nowhere ("place here") → put on this place;
 *   - expected here (on this place since the count started) but not scanned → "No place yet" (place_cleared).
 * Every piece is re-read at Finish: one that left (another location / a status that holds no place), was moved to
 * another place, or was put somewhere since its scan is skipped and listed — never applied blindly. A count NEVER
 * writes a piece off, never changes a status, never touches Shopify, and is not a stock-take session.
 *
 * A count covers ONLY its own place: a box on a shelf is its own place, so counting a shelf leaves its boxes alone
 * (scanning their sticker says "count it separately", nothing recorded). One open count per place (V198): Start on
 * a place with an open count returns that count; anyone in the tenant may scan, finish or cancel it (every role —
 * workers count shelves). An open count with no scan for {@link #IDLE_HOURS} h is cancelled by
 * PlaceCountIdleCancelJob. 404 unless the tenant has places.
 */
@Service
public class PlaceCountService {

    public static final int IDLE_HOURS = 12;

    /** Scan results stored per piece (V198 CHECK). */
    static final String FOUND_HERE = "found_here", MOVE_HERE = "move_here", PLACE_HERE = "place_here",
        OTHER_LOCATION = "other_location", NOT_PLACEABLE = "not_placeable", UNTAGGED = "untagged", UNKNOWN = "unknown";

    public record CountPiece(String pieceId, String code, UUID variantId, String productTitle, String variantTitle,
                             String sku) {}
    /** One recorded scan. {@code fromPlace}: where a "move here" piece was; {@code locationName}: other location. */
    public record CountLine(CountPiece piece, String raw, String result, PutAwayService.PlaceView fromPlace,
                            String locationName, String status, boolean viaPhone, Instant scannedAt) {}
    /** Why Finish left a piece as it was: departed / moved_since / placed_since / refused. */
    public record Skip(String pieceId, String code, String reason) {}
    /** What Finish applied (null while open; {@code reason} set on a cancelled count: cancelled / idle). */
    public record Applied(int placed, int moved, int cleared, int alreadyHere, List<Skip> skipped, String reason) {}
    /**
     * {@code expected}: pieces on this place now; {@code found} scanned here; {@code movedHere} found here but placed
     * elsewhere / nowhere; {@code notFound} on this place but not scanned (expected − found).
     */
    public record Count(UUID id, String status, PutAwayService.PlaceView place, UUID locationId, String locationName,
                        String openedByName, Instant createdAt, Instant finishedAt, int expected, int found,
                        int movedHere, int notFound, List<CountLine> foundLines, List<CountLine> movedLines,
                        List<CountPiece> notFoundPieces, List<CountLine> otherLines, Applied applied) {}
    /**
     * One scan's answer. outcome: FOUND_HERE · MOVE_HERE · PLACE_HERE · OTHER_LOCATION · NOT_PLACEABLE · UNTAGGED ·
     * UNKNOWN · DUPLICATE (already scanned, ignored) · THIS_PLACE (the counted place's own sticker) ·
     * COUNT_SEPARATELY (another place's sticker — {@code place} names it; nothing recorded).
     */
    public record ScanResult(String outcome, String code, CountPiece piece, PutAwayService.PlaceView place,
                             PutAwayService.PlaceView fromPlace, String locationName, String status, boolean viaPhone,
                             Count count) {}

    private final JdbcTemplate jdbc;
    private final InventoryLedger ledger;

    public PlaceCountService(JdbcTemplate jdbc, InventoryLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    // ── start ────────────────────────────────────────────────────────────────

    /** Start counting a place, by its id (Settings › Places) or its sticker (Stock take › Count one place). */
    @Transactional
    public Count start(UUID actorUserId, UUID binId, String scan) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        UUID id = binId;
        if (id == null) {
            String sticker = scan == null ? null : ScanInputNormalizer.normalizePlace(scan);
            if (sticker == null) throw PlaceException.notAShelfOrBox();
            List<UUID> found = jdbc.queryForList("SELECT id FROM bins WHERE tenant_id = ? AND scan_code = ?",
                UUID.class, tenantId, sticker);
            if (found.isEmpty()) throw PlaceException.unknownSticker();
            id = found.get(0);
        }
        PlacePaths.Place p = PlacePaths.describe(jdbc, tenantId, id);
        if (p == null) throw PlaceException.placeNotFound();
        if (!"shelf".equals(p.kind()) && !"box".equals(p.kind())) throw PlaceException.notAShelfOrBox();
        if (p.archived()) throw PlaceException.archived();
        // One open count per place (V198): a second Start joins the open one (its idle clock restarts).
        UUID countId = jdbc.queryForObject(
            "INSERT INTO place_counts (tenant_id, location_id, bin_id, opened_by) VALUES (?, ?, ?, ?) " +
            "ON CONFLICT (tenant_id, bin_id) WHERE status = 'open' DO UPDATE SET last_activity_at = now() RETURNING id",
            UUID.class, tenantId, p.locationId(), p.id(), actorUserId);
        return view(tenantId, countId);
    }

    @Transactional(readOnly = true)
    public Count get(UUID countId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        return view(tenantId, countId);
    }

    // ── scan ─────────────────────────────────────────────────────────────────

    /** Records one scan — and changes nothing on any piece (that is Finish's job). */
    @Transactional
    public ScanResult scan(UUID countId, String raw, UUID actorUserId, boolean viaPhone) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        C c = requireCount(tenantId, countId, true);
        if (!c.open()) throw PlaceException.countClosed();
        jdbc.update("UPDATE place_counts SET last_activity_at = now() WHERE id = ? AND tenant_id = ?", countId, tenantId);
        String input = raw == null ? "" : raw.strip();

        String sticker = ScanInputNormalizer.normalizePlace(input);
        if (sticker != null) {
            List<UUID> bin = jdbc.queryForList("SELECT id FROM bins WHERE tenant_id = ? AND scan_code = ?",
                UUID.class, tenantId, sticker);
            if (bin.isEmpty()) return result(tenantId, c, "UNKNOWN", sticker, null, null, null, null, null, viaPhone);
            if (bin.get(0).equals(c.binId())) {
                return result(tenantId, c, "THIS_PLACE", sticker, null, place(tenantId, c.binId()), null, null, null, viaPhone);
            }
            // Another place — a box on this shelf included: it is counted on its own.
            return result(tenantId, c, "COUNT_SEPARATELY", sticker, null, place(tenantId, bin.get(0)), null, null, null,
                viaPhone);
        }

        String code = ScanInputNormalizer.normalizePiece(input);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT p.id, p.short_code, p.status::text AS status, p.current_location_id, p.bin_id, p.variant_id, " +
            "       pr.title AS product_title, v.title AS variant_title, v.sku " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.tenant_id = ? AND (p.barcode = ? OR p.id = ? OR p.short_code = ?) LIMIT 1",
            tenantId, code, code, code);
        if (rows.isEmpty()) {
            boolean seen = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM place_count_scans WHERE tenant_id = ? AND count_id = ? " +
                "AND piece_id IS NULL AND raw_scan = ?)", Boolean.class, tenantId, countId, input));
            if (seen) return result(tenantId, c, "DUPLICATE", code, null, null, null, null, null, viaPhone);
            record(tenantId, c, null, input, UNKNOWN, null, actorUserId, viaPhone);
            return result(tenantId, c, "UNKNOWN", code, null, null, null, null, null, viaPhone);
        }
        Map<String, Object> r = rows.get(0);
        String pieceId = (String) r.get("id");
        CountPiece piece = new CountPiece(pieceId, (String) r.get("short_code"), (UUID) r.get("variant_id"),
            (String) r.get("product_title"), (String) r.get("variant_title"), (String) r.get("sku"));
        String status = (String) r.get("status");
        UUID location = (UUID) r.get("current_location_id");
        UUID bin = (UUID) r.get("bin_id");

        String stored;
        UUID from = null;
        if (r.get("short_code") == null) stored = UNTAGGED;
        else if (!c.locationId().equals(location)) stored = OTHER_LOCATION;
        else if (!Places.PLACEABLE_STATUSES.contains(status)) stored = NOT_PLACEABLE;
        else if (c.binId().equals(bin)) stored = FOUND_HERE;
        else if (bin == null) stored = PLACE_HERE;
        else { stored = MOVE_HERE; from = bin; }

        if (!record(tenantId, c, pieceId, input, stored, from, actorUserId, viaPhone)) {
            return result(tenantId, c, "DUPLICATE", code, piece, null, null, null, status, viaPhone);
        }
        String locationName = OTHER_LOCATION.equals(stored) && location != null ? locationName(tenantId, location) : null;
        return result(tenantId, c, stored.toUpperCase(Locale.ROOT), code, piece, null,
            from == null ? null : place(tenantId, from), locationName, status, viaPhone);
    }

    /** false = this piece (or this unknown scan) was already recorded in the count — the repeat is ignored. */
    private boolean record(UUID tenantId, C c, String pieceId, String raw, String result, UUID fromBin, UUID actor,
                           boolean viaPhone) {
        return jdbc.update(
            "INSERT INTO place_count_scans (tenant_id, count_id, piece_id, raw_scan, result, from_bin_id, scanned_by, via_phone) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (count_id, piece_id) WHERE piece_id IS NOT NULL DO NOTHING",
            tenantId, c.id(), pieceId, raw, result, fromBin, actor, viaPhone) == 1;
    }

    // ── finish / cancel ──────────────────────────────────────────────────────

    /**
     * Applies the count in ONE transaction, every piece re-read first (see the class comment). Moves and puts go
     * through recordPlaceChange to this place; "not found" through recordPlaceChange(null) — "No place yet", never
     * lost, never a status change.
     */
    @Transactional
    public Count finish(UUID countId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        C c = requireCount(tenantId, countId, true);
        if (!c.open()) throw PlaceException.countClosed();
        InventoryLedger.PlaceRef ref = InventoryLedger.PlaceRef.placeCount(c.id());
        int placed = 0, moved = 0, cleared = 0, alreadyHere = 0;
        List<Skip> skipped = new ArrayList<>();

        record Scanned(String pieceId, String code, String result, UUID fromBin) {}
        List<Scanned> scans = jdbc.query(
            "SELECT s.piece_id, p.short_code, s.result, s.from_bin_id FROM place_count_scans s " +
            "JOIN pieces p ON p.id = s.piece_id " +
            "WHERE s.tenant_id = ? AND s.count_id = ? AND s.result IN (?, ?, ?) ORDER BY s.scanned_at, s.id",
            (rs, i) -> new Scanned(rs.getString("piece_id"), rs.getString("short_code"), rs.getString("result"),
                rs.getObject("from_bin_id", UUID.class)),
            tenantId, c.id(), FOUND_HERE, MOVE_HERE, PLACE_HERE);
        for (Scanned s : scans) {
            Map<String, Object> now = jdbc.queryForMap(
                "SELECT status::text AS status, current_location_id, bin_id FROM pieces WHERE id = ? AND tenant_id = ? FOR UPDATE",
                s.pieceId(), tenantId);
            UUID bin = (UUID) now.get("bin_id");
            if (!c.locationId().equals(now.get("current_location_id"))
                    || !Places.PLACEABLE_STATUSES.contains((String) now.get("status"))) {
                skipped.add(new Skip(s.pieceId(), s.code(), "departed"));
                continue;
            }
            if (c.binId().equals(bin)) { alreadyHere++; continue; }
            // Changed since its scan → left as it is now (someone put it somewhere on purpose).
            boolean unchanged = switch (s.result()) {
                case MOVE_HERE -> Objects.equals(bin, s.fromBin());
                case PLACE_HERE -> bin == null;
                default -> false;                                       // found here, then moved away
            };
            if (!unchanged) {
                skipped.add(new Skip(s.pieceId(), s.code(), bin == null ? "cleared_since"
                    : PLACE_HERE.equals(s.result()) ? "placed_since" : "moved_since"));
                continue;
            }
            try {
                InventoryLedger.PlaceChange ch = ledger.recordPlaceChange(s.pieceId(), c.binId(), actorUserId, ref, null);
                if (ch.kind() == InventoryLedger.PlaceChangeKind.MOVED) moved++;
                else if (ch.kind() == InventoryLedger.PlaceChangeKind.PUT_AWAY) placed++;
                else alreadyHere++;
            } catch (PlaceChangeException e) {
                skipped.add(new Skip(s.pieceId(), s.code(), "refused"));
            }
        }

        // Not found: on this place now, not scanned, and here since before the count started (a piece put here
        // during the count wasn't there to be found — it is left alone).
        record Missing(String pieceId, String code) {}
        List<Missing> missing = jdbc.query(
            "SELECT p.id, p.short_code FROM pieces p WHERE p.tenant_id = ? AND p.bin_id = ? " +
            "AND NOT EXISTS (SELECT 1 FROM place_count_scans s WHERE s.count_id = ? AND s.piece_id = p.id) " +
            "ORDER BY p.id FOR UPDATE",
            (rs, i) -> new Missing(rs.getString("id"), rs.getString("short_code")), tenantId, c.binId(), c.id());
        for (Missing m : missing) {
            boolean arrivedDuring = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM piece_events e WHERE e.tenant_id = ? AND e.piece_id = ? " +
                "AND e.to_bin_id = ? AND e.occurred_at >= ?)",
                Boolean.class, tenantId, m.pieceId(), c.binId(), Timestamp.from(c.createdAt())));
            if (arrivedDuring) { skipped.add(new Skip(m.pieceId(), m.code(), "placed_since")); continue; }
            try {
                ledger.recordPlaceChange(m.pieceId(), null, actorUserId, ref, null);
                cleared++;
            } catch (PlaceChangeException e) {
                skipped.add(new Skip(m.pieceId(), m.code(), "refused"));
            }
        }

        Applied applied = new Applied(placed, moved, cleared, alreadyHere, List.copyOf(skipped), null);
        jdbc.update(
            "UPDATE place_counts SET status = 'finished', finished_at = now(), finished_by = ?, last_activity_at = now(), " +
            "summary = ?::jsonb WHERE id = ? AND tenant_id = ?",
            actorUserId, json(applied), c.id(), tenantId);
        return view(tenantId, c.id());
    }

    /** Cancel: nothing changes on any piece. */
    @Transactional
    public Count cancel(UUID countId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        C c = requireCount(tenantId, countId, true);
        if (!c.open()) throw PlaceException.countClosed();
        jdbc.update(
            "UPDATE place_counts SET status = 'cancelled', finished_at = now(), finished_by = ?, " +
            "summary = '{\"reason\":\"cancelled\"}'::jsonb WHERE id = ? AND tenant_id = ?",
            actorUserId, c.id(), tenantId);
        return view(tenantId, c.id());
    }

    /** PlaceCountIdleCancelJob: cancels this tenant's counts with no scan for {@link #IDLE_HOURS} h. */
    @Transactional
    public List<UUID> cancelIdle() {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "UPDATE place_counts SET status = 'cancelled', finished_at = now(), summary = '{\"reason\":\"idle\"}'::jsonb " +
            "WHERE tenant_id = ? AND status = 'open' AND last_activity_at < now() - (interval '1 hour' * ?) RETURNING id",
            UUID.class, tenantId, IDLE_HOURS);
    }

    // ── view ─────────────────────────────────────────────────────────────────

    record C(UUID id, UUID locationId, UUID binId, boolean open, Instant createdAt) {}

    private C requireCount(UUID tenantId, UUID countId, boolean forUpdate) {
        List<C> rows = jdbc.query(
            "SELECT id, location_id, bin_id, status, created_at FROM place_counts WHERE id = ? AND tenant_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> new C(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class),
                rs.getObject("bin_id", UUID.class), "open".equals(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant()),
            countId, tenantId);
        if (rows.isEmpty()) throw PlaceException.countNotFound();
        return rows.get(0);
    }

    private ScanResult result(UUID tenantId, C c, String outcome, String code, CountPiece piece,
                              PutAwayService.PlaceView place, PutAwayService.PlaceView from, String locationName,
                              String status, boolean viaPhone) {
        return new ScanResult(outcome, code, piece, place, from, locationName, status, viaPhone, view(tenantId, c.id()));
    }

    private Count view(UUID tenantId, UUID countId) {
        List<Map<String, Object>> head = jdbc.queryForList(
            "SELECT c.id, c.status, c.location_id, c.bin_id, c.created_at, c.finished_at, c.summary::text AS summary, " +
            "       l.name AS location_name, u.name AS opened_by_name " +
            "FROM place_counts c JOIN locations l ON l.id = c.location_id LEFT JOIN users u ON u.id = c.opened_by " +
            "WHERE c.id = ? AND c.tenant_id = ?", countId, tenantId);
        if (head.isEmpty()) throw PlaceException.countNotFound();
        Map<String, Object> h = head.get(0);
        UUID binId = (UUID) h.get("bin_id");

        List<CountLine> lines = new ArrayList<>();
        Set<UUID> fromBins = new HashSet<>();
        record RawLine(CountPiece piece, String raw, String result, UUID from, UUID location, String status,
                       boolean viaPhone, Instant at) {}
        List<RawLine> raws = jdbc.query(
            "SELECT s.piece_id, s.raw_scan, s.result, s.from_bin_id, s.via_phone, s.scanned_at, p.short_code, " +
            "       p.variant_id, p.current_location_id, p.status::text AS status, pr.title AS product_title, " +
            "       v.title AS variant_title, v.sku " +
            "FROM place_count_scans s LEFT JOIN pieces p ON p.id = s.piece_id " +
            "LEFT JOIN variants v ON v.id = p.variant_id LEFT JOIN products pr ON pr.id = v.product_id " +
            "WHERE s.tenant_id = ? AND s.count_id = ? ORDER BY s.scanned_at DESC, s.id DESC",
            (rs, i) -> {
                String pid = rs.getString("piece_id");
                CountPiece p = pid == null ? null : new CountPiece(pid, rs.getString("short_code"),
                    rs.getObject("variant_id", UUID.class), rs.getString("product_title"),
                    rs.getString("variant_title"), rs.getString("sku"));
                return new RawLine(p, rs.getString("raw_scan"), rs.getString("result"),
                    rs.getObject("from_bin_id", UUID.class), rs.getObject("current_location_id", UUID.class),
                    rs.getString("status"), rs.getBoolean("via_phone"), rs.getTimestamp("scanned_at").toInstant());
            },
            tenantId, countId);
        for (RawLine r : raws) if (r.from() != null) fromBins.add(r.from());
        fromBins.add(binId);
        Map<UUID, PlacePaths.Place> places = PlacePaths.describe(jdbc, tenantId, fromBins);
        Map<UUID, String> locationNames = new HashMap<>();
        jdbc.query("SELECT id, name FROM locations WHERE tenant_id = ?",
            rs -> { locationNames.put(rs.getObject("id", UUID.class), rs.getString("name")); }, tenantId);
        Set<String> scanned = new HashSet<>();
        List<CountLine> found = new ArrayList<>(), moved = new ArrayList<>(), other = new ArrayList<>();
        for (RawLine r : raws) {
            if (r.piece() != null) scanned.add(r.piece().pieceId());
            CountLine line = new CountLine(r.piece(), r.raw(), r.result(), PutAwayService.PlaceView.of(places.get(r.from())),
                OTHER_LOCATION.equals(r.result()) ? locationNames.get(r.location()) : null, r.status(), r.viaPhone(), r.at());
            switch (r.result()) {
                case FOUND_HERE -> found.add(line);
                case MOVE_HERE, PLACE_HERE -> moved.add(line);
                default -> other.add(line);
            }
            lines.add(line);
        }

        List<CountPiece> onPlace = jdbc.query(
            "SELECT p.id, p.short_code, p.variant_id, pr.title AS product_title, v.title AS variant_title, v.sku " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.tenant_id = ? AND p.bin_id = ? ORDER BY pr.title, v.title, p.short_code",
            (rs, i) -> new CountPiece(rs.getString("id"), rs.getString("short_code"), rs.getObject("variant_id", UUID.class),
                rs.getString("product_title"), rs.getString("variant_title"), rs.getString("sku")),
            tenantId, binId);
        boolean open = "open".equals(h.get("status"));
        List<CountPiece> notFound = open ? onPlace.stream().filter(p -> !scanned.contains(p.pieceId())).toList() : List.of();
        Applied applied = open ? null : parse((String) h.get("summary"));
        int expected = open ? onPlace.size() : found.size() + (applied == null ? 0 : applied.cleared());
        int notFoundCount = open ? notFound.size() : applied == null ? 0 : applied.cleared();
        return new Count(countId, (String) h.get("status"), PutAwayService.PlaceView.of(places.get(binId)),
            (UUID) h.get("location_id"), (String) h.get("location_name"), (String) h.get("opened_by_name"),
            ((Timestamp) h.get("created_at")).toInstant(),
            h.get("finished_at") == null ? null : ((Timestamp) h.get("finished_at")).toInstant(),
            expected, found.size(), moved.size(), notFoundCount, found, moved, notFound, other, applied);
    }

    private PutAwayService.PlaceView place(UUID tenantId, UUID binId) {
        return PutAwayService.PlaceView.of(PlacePaths.describe(jdbc, tenantId, binId));
    }

    private String locationName(UUID tenantId, UUID locationId) {
        List<String> n = jdbc.queryForList("SELECT name FROM locations WHERE id = ? AND tenant_id = ?",
            String.class, locationId, tenantId);
        return n.isEmpty() ? null : n.get(0);
    }

    // ── summary json (small and flat: written and read only here) ────────────

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String json(Applied a) {
        try { return JSON.writeValueAsString(a); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static Applied parse(String s) {
        if (s == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(s);
            List<Skip> skips = new ArrayList<>();
            if (n.has("skipped")) for (com.fasterxml.jackson.databind.JsonNode k : n.get("skipped")) {
                skips.add(new Skip(k.path("pieceId").asText(null), k.path("code").asText(null), k.path("reason").asText(null)));
            }
            return new Applied(n.path("placed").asInt(), n.path("moved").asInt(), n.path("cleared").asInt(),
                n.path("alreadyHere").asInt(), List.copyOf(skips), n.hasNonNull("reason") ? n.get("reason").asText() : null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
