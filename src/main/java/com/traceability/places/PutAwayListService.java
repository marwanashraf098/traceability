package com.traceability.places;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Warehouse places S4 — put-away lists: the gather list in reverse. After a flow brings pieces into a location, the
 * list says where each one should go, grouped by zone in walking order; "Start put away" opens S2's scan screen on a
 * session scoped to that flow. Display + S2's own writer only: the session's scans, close, resume and 12 h idle close
 * are S2's ({@link PutAwayService}, unchanged); nothing here moves a piece or calls Shopify (PlacesShopifyGuardTest).
 *
 * A flow ({@code source} + id) and the pieces it brought in:
 * <ul>
 *   <li>{@code receiving} — a receipt: its pieces ({@code pieces.receipt_id}) at the receipt's location (none → the
 *       main warehouse). Untagged (count-only) pieces can't be placed, so they are never on the list.</li>
 *   <li>{@code tag_session} — a Tag stock session: the pieces it tagged (its label_bound events), at its location.</li>
 *   <li>{@code return_session} — a Scan returns session: its RESTOCKED pieces only (damaged / mismatch never), at the
 *       main warehouse (where a restock puts them).</li>
 *   <li>{@code transfer} — a transfer: its pieces scanned back GOOD (returned_good — a send-and-back or a bring-back),
 *       at the main warehouse. Owner / manager only, like every transfer screen.</li>
 * </ul>
 * On the list: the flow's pieces that are tagged, in a placeable status and still AT that location. One with a place
 * there is done (its place is where it is now); one without is waiting (its place is S2's suggestion —
 * {@link PutAwayService#suggestBins}, the same rule as the waiting list). A piece that left (sold, sent, damaged
 * away to another location, …) is dropped. Unknown flow / another tenant's → 404 SOURCE_NOT_FOUND, alike.
 *
 * Sessions: one OPEN scoped session per person per flow (V197's unique index) — Start resumes it. Starting fills
 * put_away_session_pieces with the waiting pieces and their suggested place (placed_* NULL until scanned); progress
 * is the list itself (placed / total), and a piece placed in the session that isn't on the list is "extra".
 */
@Service
public class PutAwayListService {

    public static final List<String> SOURCES = List.of("receiving", "tag_session", "return_session", "transfer");

    /** A place as the list shows it: S2's fields plus its zone and walking-order key (S3's PlaceSort). */
    public record ListPlace(UUID id, String kind, String code, List<String> trail, String parentCode, String parentKind,
                            String zoneCode, String sortKey) {
        static ListPlace of(PlacePaths.Place p) {
            return p == null ? null : new ListPlace(p.id(), p.kind(), p.code(), p.trail(), p.parentCode(), p.parentKind(),
                p.zoneCode(), p.sortKey());
        }
    }

    /**
     * {@code done}: the piece has a place at the location — {@code place} is where it is ({@code placeReason}
     * "placed"). Waiting: {@code place} is the suggestion (last_place | most_of_variant), or null (no suggestion).
     */
    public record ListPiece(String pieceId, String code, UUID variantId, String productTitle, String variantTitle,
                            String sku, String status, boolean done, ListPlace place, String placeReason) {}

    /** A scoped session on this list: how many it placed, and how many of those aren't on the list ("extra"). */
    public record ListSession(UUID id, String status, int placedInSession, int extra, Instant lastActivityAt) {}

    /**
     * {@code total} / {@code placed}: the list's progress. {@code session}: the caller's open session on this flow
     * (Start resumes it) — or, read through a session ({@link #sessionList}), that session.
     */
    public record PutAwayList(String source, UUID sourceId, String sourceRef, UUID locationId, String locationName,
                              boolean hasPlaces, int total, int placed, List<ListPiece> pieces, ListSession session) {}

    /** The list behind a put-away session: {@code scoped} false (and no list) for S2's manual sessions. */
    public record SessionList(boolean scoped, PutAwayList list) {}

    /** A piece scanned out on a transfer and the place it left from (null: it had none). */
    public record SentPiece(String pieceId, String code, String productTitle, String variantTitle, String sku,
                            ListPlace place) {}

    private final JdbcTemplate jdbc;
    private final PutAwayService putAway;

    public PutAwayListService(JdbcTemplate jdbc, PutAwayService putAway) {
        this.jdbc = jdbc;
        this.putAway = putAway;
    }

    // ── the list ──────────────────────────────────────────────────────────────

    /** {@code manager}: the caller is an owner / manager — a transfer's list needs it (403 otherwise). */
    @Transactional(readOnly = true)
    public PutAwayList list(String source, UUID sourceId, UUID actorUserId, boolean manager) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        guardTransfer(source, manager);
        Src src = resolve(tenantId, source, sourceId);
        List<UUID> open = jdbc.queryForList(
            "SELECT id FROM put_away_sessions WHERE tenant_id = ? AND opened_by = ? AND source = ? AND source_id = ? " +
            "AND status = 'open' ORDER BY created_at DESC, id DESC LIMIT 1",
            UUID.class, tenantId, actorUserId, src.source(), src.id());
        return build(tenantId, src, open.isEmpty() ? null : open.get(0));
    }

    /**
     * Starts a put-away session scoped to the flow, or resumes the caller's open one (resuming restarts the idle
     * clock, as in S2). Its pieces are the list's waiting pieces, each with its suggested place. Returns S2's session.
     */
    @Transactional
    public PutAwayService.Session start(String source, UUID sourceId, UUID actorUserId, boolean manager) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        guardTransfer(source, manager);
        Src src = resolve(tenantId, source, sourceId);
        UUID id = jdbc.queryForObject(
            "INSERT INTO put_away_sessions (tenant_id, location_id, source, source_id, opened_by) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (tenant_id, opened_by, source, source_id) WHERE status = 'open' AND source <> 'manual' " +
            "DO UPDATE SET last_activity_at = now() RETURNING id",
            UUID.class, tenantId, src.locationId(), src.source(), src.id(), actorUserId);

        List<Row> waiting = rows(tenantId, src).stream().filter(r -> r.binId() == null).toList();
        if (!waiting.isEmpty()) {
            Map<String, PutAwayService.SuggestedBin> suggested = PutAwayService.suggestBins(jdbc, tenantId, src.locationId(),
                waiting.stream().map(r -> Map.entry(r.pieceId(), r.variantId())).toList());
            String[] pieceIds = waiting.stream().map(Row::pieceId).toArray(String[]::new);
            String[] bins = waiting.stream().map(r -> {
                PutAwayService.SuggestedBin b = suggested.get(r.pieceId());
                return b == null ? null : b.binId().toString();
            }).toArray(String[]::new);
            jdbc.update(
                "INSERT INTO put_away_session_pieces (session_id, tenant_id, piece_id, suggested_bin_id) " +
                "SELECT ?, ?, x.piece_id, x.bin_id::uuid FROM unnest(?::text[], ?::text[]) AS x(piece_id, bin_id) " +
                "ON CONFLICT (session_id, piece_id) DO NOTHING",
                id, tenantId, pieceIds, bins);
        }
        return putAway.get(id, actorUserId);
    }

    /** The list of the caller's own put-away session (open or closed) — the scan screen's progress and ticks. */
    @Transactional(readOnly = true)
    public SessionList sessionList(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        List<Map<String, Object>> found = sessionId == null ? List.of() : jdbc.queryForList(
            "SELECT source, source_id FROM put_away_sessions WHERE id = ? AND tenant_id = ? AND opened_by = ?",
            sessionId, tenantId, actorUserId);
        if (found.isEmpty()) throw PlaceException.sessionNotFound();
        String source = (String) found.get(0).get("source");
        if ("manual".equals(source)) return new SessionList(false, null);
        return new SessionList(true, build(tenantId, resolve(tenantId, source, (UUID) found.get(0).get("source_id")), sessionId));
    }

    /** A transfer's list (and its sent pieces) is owner / manager only, like every transfer screen. */
    private static void guardTransfer(String source, boolean manager) {
        if ("transfer".equals(source) && !manager) {
            throw new AccessDeniedException("Transfers are for owners and managers");
        }
    }

    // ── transfer send-out: where each piece came from ────────────────────────

    /**
     * The pieces scanned out on a transfer, each with the place it left from, in walking order (no place last). The
     * departure cleared the place (V189's trigger), so it is read back from the piece's history: its latest place
     * event before the scan-out, at the location it left from, provided nothing in between took it out of a place
     * (a status change out of / into a status that holds none). Display only.
     */
    @Transactional(readOnly = true)
    public List<SentPiece> transferSentPieces(UUID transferId, boolean manager) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        guardTransfer("transfer", manager);
        Integer exists = jdbc.queryForObject("SELECT count(*)::int FROM transfers WHERE id = ? AND tenant_id = ?",
            Integer.class, transferId, tenantId);
        if (exists == null || exists == 0) throw PlaceException.sourceNotFound();
        String placeable = placeableList();
        record Raw(String pieceId, String code, String product, String variant, String sku, UUID binId) {}
        List<Raw> raws = jdbc.query(
            "SELECT tp.piece_id, p.short_code, pr.title AS product_title, v.title AS variant_title, v.sku, " +
            "       CASE WHEN lp.to_bin_id IS NOT NULL " +
            "             AND (tp.from_location_id IS NULL OR b.location_id = tp.from_location_id) " +
            "             AND NOT EXISTS (SELECT 1 FROM piece_events x " +
            "                 WHERE x.tenant_id = tp.tenant_id AND x.piece_id = tp.piece_id " +
            "                   AND x.occurred_at > lp.occurred_at AND x.occurred_at < tp.created_at " +
            "                   AND (x.to_status::text NOT IN (" + placeable + ") " +
            "                        OR (x.from_status IS NOT NULL AND x.from_status::text NOT IN (" + placeable + ")))) " +
            "            THEN lp.to_bin_id END AS bin_id " +
            "FROM transfer_pieces tp " +
            "JOIN pieces p ON p.id = tp.piece_id AND p.tenant_id = tp.tenant_id " +
            "JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "LEFT JOIN LATERAL ( " +
            "  SELECT e.to_bin_id, e.occurred_at FROM piece_events e " +
            "  WHERE e.tenant_id = tp.tenant_id AND e.piece_id = tp.piece_id " +
            "    AND e.event_type IN ('put_away', 'place_moved', 'place_cleared') AND e.occurred_at <= tp.created_at " +
            "  ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1 " +
            ") lp ON true " +
            "LEFT JOIN bins b ON b.id = lp.to_bin_id AND b.tenant_id = tp.tenant_id " +
            "WHERE tp.tenant_id = ? AND tp.transfer_id = ?",
            (rs, i) -> new Raw(rs.getString("piece_id"), rs.getString("short_code"), rs.getString("product_title"),
                rs.getString("variant_title"), rs.getString("sku"), rs.getObject("bin_id", UUID.class)),
            tenantId, transferId);
        Map<UUID, PlacePaths.Place> d = PlacePaths.describe(jdbc, tenantId,
            raws.stream().map(Raw::binId).filter(Objects::nonNull).toList());
        List<SentPiece> out = new ArrayList<>(raws.stream().map(r -> new SentPiece(r.pieceId(), r.code(), r.product(),
            r.variant(), r.sku(), ListPlace.of(d.get(r.binId())))).toList());
        out.sort(Comparator.comparing((SentPiece p) -> p.place() == null ? null : p.place().sortKey(),
                Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(SentPiece::productTitle, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(SentPiece::variantTitle, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(SentPiece::code, Comparator.nullsLast(Comparator.<String>naturalOrder())));
        return out;
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** A flow: its source, id, a short reference to show (receipt reference / transfer destination), its location. */
    record Src(String source, UUID id, String ref, UUID locationId, String locationName) {}

    /** One of the flow's pieces on the list (tagged, placeable, at the location) and its place now. */
    record Row(String pieceId, String code, UUID variantId, String product, String variant, String sku, String status,
               UUID binId) {}

    private Src resolve(UUID tenantId, String source, UUID sourceId) {
        if (source == null || !SOURCES.contains(source) || sourceId == null) throw PlaceException.sourceNotFound();
        List<Map<String, Object>> found = switch (source) {
            case "receiving" -> jdbc.queryForList(
                "SELECT r.reference AS ref, COALESCE(r.location_id, m.id) AS location_id FROM receipts r " +
                "LEFT JOIN locations m ON m.tenant_id = r.tenant_id AND m.is_fulfillment " +
                "WHERE r.id = ? AND r.tenant_id = ?", sourceId, tenantId);
            case "tag_session" -> jdbc.queryForList(
                "SELECT NULL::text AS ref, location_id FROM tag_sessions WHERE id = ? AND tenant_id = ?", sourceId, tenantId);
            case "return_session" -> jdbc.queryForList(
                "SELECT NULL::text AS ref, m.id AS location_id FROM return_sessions rs " +
                "LEFT JOIN locations m ON m.tenant_id = rs.tenant_id AND m.is_fulfillment " +
                "WHERE rs.id = ? AND rs.tenant_id = ?", sourceId, tenantId);
            default -> jdbc.queryForList(
                "SELECT d.name AS ref, m.id AS location_id FROM transfers t " +
                "LEFT JOIN locations d ON d.id = t.destination_location_id AND d.tenant_id = t.tenant_id " +
                "LEFT JOIN locations m ON m.tenant_id = t.tenant_id AND m.is_fulfillment " +
                "WHERE t.id = ? AND t.tenant_id = ?", sourceId, tenantId);
        };
        if (found.isEmpty() || found.get(0).get("location_id") == null) throw PlaceException.sourceNotFound();
        UUID locationId = (UUID) found.get(0).get("location_id");
        String locationName = jdbc.queryForObject("SELECT name FROM locations WHERE id = ? AND tenant_id = ?",
            String.class, locationId, tenantId);
        return new Src(source, sourceId, (String) found.get(0).get("ref"), locationId, locationName);
    }

    /** The flow's pieces as one SQL condition on {@code p} (+ its arguments) — restocked only for a return session. */
    private static Map.Entry<String, List<Object>> membership(UUID tenantId, Src src) {
        return switch (src.source()) {
            case "receiving" -> Map.entry("p.receipt_id = ?", List.of(src.id()));
            case "tag_session" -> Map.entry(
                "p.id IN (SELECT e.piece_id FROM piece_events e WHERE e.tenant_id = ? AND e.event_type = 'label_bound' " +
                "AND e.metadata ->> 'tag_session_id' = ?)", List.of(tenantId, src.id().toString()));
            case "return_session" -> Map.entry(
                "p.id IN (SELECT i.piece_id FROM return_session_items i WHERE i.tenant_id = ? AND i.session_id = ? " +
                "AND i.disposition = 'restocked')", List.of(tenantId, src.id()));
            default -> Map.entry(
                "p.id IN (SELECT tp.piece_id FROM transfer_pieces tp WHERE tp.tenant_id = ? AND tp.transfer_id = ? " +
                "AND tp.outcome = 'returned_good')", List.of(tenantId, src.id()));
        };
    }

    /** The list's pieces, one statement: the flow's tagged pieces in a placeable status at the flow's location. */
    private List<Row> rows(UUID tenantId, Src src) {
        Map.Entry<String, List<Object>> m = membership(tenantId, src);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(m.getValue());
        args.add(src.locationId());
        return jdbc.query(
            "SELECT p.id, p.short_code, p.variant_id, p.status::text AS status, p.bin_id, pr.title AS product_title, " +
            "       v.title AS variant_title, v.sku " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.tenant_id = ? AND " + m.getKey() + " AND p.short_code IS NOT NULL " +
            "  AND p.current_location_id = ? AND p.status::text IN (" + placeableList() + ")",
            (rs, i) -> new Row(rs.getString("id"), rs.getString("short_code"), rs.getObject("variant_id", UUID.class),
                rs.getString("product_title"), rs.getString("variant_title"), rs.getString("sku"), rs.getString("status"),
                rs.getObject("bin_id", UUID.class)),
            args.toArray());
    }

    /** The list for a flow: rows, suggestions (S2's rule) and every place described in one go — no query per piece. */
    private PutAwayList build(UUID tenantId, Src src, UUID sessionId) {
        List<Row> rows = rows(tenantId, src);
        List<Row> waiting = rows.stream().filter(r -> r.binId() == null).toList();
        Map<String, PutAwayService.SuggestedBin> suggested = PutAwayService.suggestBins(jdbc, tenantId, src.locationId(),
            waiting.stream().map(r -> Map.entry(r.pieceId(), r.variantId())).toList());
        Set<UUID> binIds = new HashSet<>();
        for (Row r : rows) if (r.binId() != null) binIds.add(r.binId());
        for (PutAwayService.SuggestedBin b : suggested.values()) binIds.add(b.binId());
        Map<UUID, PlacePaths.Place> d = PlacePaths.describe(jdbc, tenantId, binIds);

        List<ListPiece> pieces = new ArrayList<>(rows.size());
        for (Row r : rows) {
            boolean done = r.binId() != null;
            PutAwayService.SuggestedBin s = done ? null : suggested.get(r.pieceId());
            PlacePaths.Place place = done ? d.get(r.binId()) : s == null ? null : d.get(s.binId());
            String reason = done ? "placed" : place == null ? null : s.reason();
            pieces.add(new ListPiece(r.pieceId(), r.code(), r.variantId(), r.product(), r.variant(), r.sku(), r.status(),
                done, ListPlace.of(place), reason));
        }
        pieces.sort(Comparator.comparing((ListPiece p) -> p.place() == null ? null : p.place().sortKey(),
                Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(ListPiece::productTitle, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(ListPiece::variantTitle, Comparator.nullsLast(Comparator.<String>naturalOrder()))
            .thenComparing(ListPiece::code, Comparator.nullsLast(Comparator.<String>naturalOrder())));
        int placed = (int) pieces.stream().filter(ListPiece::done).count();

        ListSession session = null;
        if (sessionId != null) {
            Set<String> onList = new HashSet<>();
            for (Row r : rows) onList.add(r.pieceId());
            List<String> placedIn = jdbc.queryForList(
                "SELECT piece_id FROM put_away_session_pieces WHERE session_id = ? AND tenant_id = ? AND placed_bin_id IS NOT NULL",
                String.class, sessionId, tenantId);
            Map<String, Object> s = jdbc.queryForMap(
                "SELECT status, last_activity_at FROM put_away_sessions WHERE id = ? AND tenant_id = ?", sessionId, tenantId);
            session = new ListSession(sessionId, (String) s.get("status"), placedIn.size(),
                (int) placedIn.stream().filter(id -> !onList.contains(id)).count(),
                ((java.sql.Timestamp) s.get("last_activity_at")).toInstant());
        }
        boolean hasPlaces = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bins WHERE tenant_id = ? AND location_id = ? AND kind IN ('shelf', 'box') " +
            "AND archived_at IS NULL)", Boolean.class, tenantId, src.locationId()));
        return new PutAwayList(src.source(), src.id(), src.ref(), src.locationId(), src.locationName(), hasPlaces,
            pieces.size(), placed, List.copyOf(pieces), session);
    }

    /** Places.PLACEABLE_STATUSES as an SQL list of literals (constants, never input). */
    private static String placeableList() {
        return String.join(", ", Places.PLACEABLE_STATUSES.stream().map(s -> "'" + s + "'").toList());
    }
}
