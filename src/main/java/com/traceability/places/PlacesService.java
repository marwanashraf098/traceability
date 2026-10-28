package com.traceability.places;

import com.traceability.inventory.LabelPdfRenderer;
import com.traceability.inventory.PieceLabelLayout;
import com.traceability.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Warehouse places S1 — the tree of zones / racks / shelves / boxes inside a location, and their stickers. Owner +
 * manager (PlacesController); every method answers 404 unless the tenant has places ({@link Places}).
 *
 * Nothing here places a piece: pieces.bin_id is written only by the S2 put-away writer (InventoryLedger), and
 * cleared by the pieces_bin_guard trigger (V189). Nothing here calls Shopify (PlacesShopifyGuardTest).
 *
 * Rules: a rack goes in a zone, a shelf on a rack, a box in a zone or on a shelf (V189's composite FK enforces it,
 * checked here first for a clear error); a place never changes location; only a box moves (zone ↔ shelf, same
 * location); only a zone or a box can be the Damaged place; a place is archived, never deleted, and only while
 * it holds no piece and no live place.
 */
@Service
public class PlacesService {

    public static final int MAX_STICKERS = 2000;
    static final int MAX_BULK = 2000, MAX_RACKS = 99, MAX_SHELVES = 20, MAX_BOXES = 200;
    static final float STICKER_WIDTH_MM = 50f, STICKER_HEIGHT_MM = 25f;   // = the piece label (v1)
    private static final Set<String> KINDS = Set.of("zone", "rack", "shelf", "box");
    private static final Map<String, Set<String>> ALLOWED_PARENTS = Map.of(
        "rack", Set.of("zone"), "shelf", Set.of("rack"), "box", Set.of("zone", "shelf"));

    /** One place in a tree: {@code label} = its name inside its parent, {@code path} = its parents' labels, top first. */
    public record Node(UUID id, String kind, String code, String label, String name, String purpose, UUID parentId,
                       String scanCode, List<String> path, int depth, int pieces, int totalPieces, boolean archived,
                       Instant createdAt) {}
    public record Tree(UUID locationId, String locationName, String nextZoneCode, List<Node> nodes, int noPlaceYet) {}
    public record Content(UUID variantId, String productTitle, String variantTitle, String sku, int count) {}
    public record HistoryEntry(String eventType, String actorName, Instant occurredAt, String fromCode, String toCode,
                               String fromParent, String toParent, String purpose) {}
    public record Detail(Node node, String locationName, List<Content> contents, List<Node> boxes,
                         List<HistoryEntry> history) {}
    public record CreateRequest(UUID locationId, String kind, UUID parentId, String code, String name) {}
    /** {@code zoneId} null → a new zone Zn with these; set → these added to that zone (racks numbered from its next free R). */
    public record BulkRequest(UUID locationId, UUID zoneId, Integer racks, Integer shelvesPerRack, Integer floorBoxes,
                              String zoneName) {}
    public record BulkPlan(UUID locationId, String zoneCode, boolean newZone, int racks, int shelves, int boxes, int total,
                           String firstRack, String lastRack, String firstShelf, String lastShelf, String firstBox,
                           String lastBox, List<String> conflicts) {}
    /** {@code createdIds}: every place made, zone first, in tree order (the caller prints its stickers). */
    public record BulkResult(UUID zoneId, BulkPlan plan, List<UUID> createdIds) {}

    private final JdbcTemplate jdbc;

    public PlacesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── read ─────────────────────────────────────────────────────────────────

    /** The live places of a location (null → the main warehouse) in tree order, with piece counts. */
    @Transactional(readOnly = true)
    public Tree tree(UUID requestedLocationId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Loc loc = location(tenantId, requestedLocationId);
        Forest f = forest(tenantId, loc.id());
        List<Node> nodes = new ArrayList<>();
        for (Row zone : f.children(null)) addSubtree(f, zone, 0, nodes);
        Integer waiting = jdbc.queryForObject(
            "SELECT count(*)::int FROM pieces WHERE tenant_id = ? AND current_location_id = ? AND bin_id IS NULL " +
            "AND short_code IS NOT NULL AND status::text IN ('available', 'on_hold', 'damaged')",
            Integer.class, tenantId, loc.id());
        return new Tree(loc.id(), loc.name(), nextZoneCode(f), nodes, waiting == null ? 0 : waiting);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID binId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Row bin = bin(tenantId, binId, false);
        Loc loc = location(tenantId, bin.locationId());
        Forest f = forest(tenantId, loc.id());
        List<Content> contents = jdbc.query(
            "SELECT v.id, pr.title AS product_title, v.title AS variant_title, v.sku, count(*)::int AS n " +
            "FROM pieces p JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.tenant_id = ? AND p.bin_id = ? GROUP BY v.id, pr.title, v.title, v.sku " +
            "ORDER BY n DESC, pr.title, v.title",
            (rs, i) -> new Content(rs.getObject("id", UUID.class), rs.getString("product_title"),
                rs.getString("variant_title"), rs.getString("sku"), rs.getInt("n")),
            tenantId, binId);
        List<Node> boxes = new ArrayList<>();
        for (Row child : f.children(binId)) {
            if ("box".equals(child.kind())) boxes.add(node(f, child, depth(f, child)));
        }
        List<HistoryEntry> history = jdbc.query(
            "SELECT e.event_type, u.name AS actor, e.occurred_at, e.metadata->>'fromCode' AS from_code, " +
            "       e.metadata->>'toCode' AS to_code, fp.code AS from_parent, tp.code AS to_parent, " +
            "       e.metadata->>'purpose' AS purpose " +
            "FROM bin_events e LEFT JOIN users u ON u.id = e.actor_user_id " +
            "LEFT JOIN bins fp ON fp.id = e.from_parent_id LEFT JOIN bins tp ON tp.id = e.to_parent_id " +
            "WHERE e.tenant_id = ? AND e.bin_id = ? ORDER BY e.occurred_at DESC, e.id DESC LIMIT 20",
            (rs, i) -> new HistoryEntry(rs.getString("event_type"), rs.getString("actor"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("from_code"), rs.getString("to_code"),
                rs.getString("from_parent"), rs.getString("to_parent"), rs.getString("purpose")),
            tenantId, binId);
        return new Detail(node(f, f.get(binId), depth(f, f.get(binId))), loc.name(), contents, boxes, history);
    }

    // ── create ───────────────────────────────────────────────────────────────

    /** One place. Without a code: the next zone Zn / rack {zone}-Rnn / shelf {rack}-Sn / box BOX-nn. */
    @Transactional
    public Node create(UUID actorUserId, CreateRequest req) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        if (req == null || req.kind() == null || !KINDS.contains(req.kind())) throw PlaceException.invalidKind();
        String kind = req.kind();
        Row parent = null;
        UUID locationId;
        if ("zone".equals(kind)) {
            if (req.parentId() != null) throw PlaceException.invalidParent();
            locationId = location(tenantId, req.locationId()).id();
        } else {
            if (req.parentId() == null) throw PlaceException.invalidParent();
            parent = bin(tenantId, req.parentId(), true);
            if (!ALLOWED_PARENTS.get(kind).contains(parent.kind())) throw PlaceException.invalidParent();
            if (req.locationId() != null && !req.locationId().equals(parent.locationId())) throw PlaceException.invalidParent();
            if (parent.archived()) throw PlaceException.archived();
            locationId = parent.locationId();
        }
        String name = cleanName(req.name());
        Forest f = forest(tenantId, locationId);
        String code;
        if (req.code() != null && !req.code().isBlank()) {
            code = PlaceCodes.cleanCode(req.code());
            if (code == null) throw PlaceException.invalidCode();
        } else {
            code = switch (kind) {
                case "zone" -> nextZoneCode(f);
                case "rack" -> PlaceCodes.rack(parent.code(), PlaceCodes.lowestFree(f.codes(), parent.code() + "-R", 1).get(0));
                case "shelf" -> PlaceCodes.shelf(parent.code(), PlaceCodes.lowestFree(f.codes(), parent.code() + "-S", 1).get(0));
                default -> PlaceCodes.box(PlaceCodes.lowestFree(f.codes(), "BOX-", 1).get(0));
            };
        }
        if (f.hasCode(code)) throw PlaceException.codeTaken(code);
        UUID id = UUID.randomUUID();
        String scanCode = PlaceCodes.claimScanCodes(jdbc, tenantId, 1).get(0);
        try {
            jdbc.update(
                "INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code, name, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, locationId, kind, parent == null ? null : parent.id(), parent == null ? null : parent.kind(),
                code, scanCode, name, actorUserId);
        } catch (DuplicateKeyException e) {
            throw PlaceException.codeTaken(code);
        }
        event(tenantId, id, "created", actorUserId, null, null, "{\"toCode\":" + json(code) + "}");
        Forest after = forest(tenantId, locationId);
        return node(after, after.get(id), depth(after, after.get(id)));
    }

    /** What a bulk create would make — nothing is written. */
    @Transactional(readOnly = true)
    public BulkPlan bulkPreview(BulkRequest req) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Row zone = bulkZone(tenantId, req, false);
        Loc loc = location(tenantId, zone != null ? zone.locationId() : req == null ? null : req.locationId());
        return plan(req, loc, forest(tenantId, loc.id()), zone).plan();
    }

    /** The existing zone a bulk request adds to (null → a new zone); refused unless a live zone of this tenant. */
    private Row bulkZone(UUID tenantId, BulkRequest req, boolean forUpdate) {
        if (req == null || req.zoneId() == null) return null;
        Row zone = bin(tenantId, req.zoneId(), forUpdate);
        if (!"zone".equals(zone.kind())) throw PlaceException.invalidParent();
        if (req.locationId() != null && !req.locationId().equals(zone.locationId())) throw PlaceException.invalidParent();
        if (zone.archived()) throw PlaceException.archived();
        return zone;
    }

    /** Zone Zn + racks × shelves + floor boxes, in ONE transaction — all of it or nothing. */
    @Transactional
    public BulkResult bulkCreate(UUID actorUserId, BulkRequest req) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Row zone = bulkZone(tenantId, req, true);
        Loc loc = location(tenantId, zone != null ? zone.locationId() : req == null ? null : req.locationId());
        Planned p = plan(req, loc, forest(tenantId, loc.id()), zone);
        if (!p.plan().conflicts().isEmpty()) throw PlaceException.codeTaken(p.plan().conflicts().get(0));
        String zoneName = zone == null ? cleanName(req.zoneName()) : null;

        List<String> scanCodes = PlaceCodes.claimScanCodes(jdbc, tenantId, p.plan().total());
        Iterator<String> scan = scanCodes.iterator();
        List<Object[]> rows = new ArrayList<>(p.plan().total());
        UUID zoneId = zone == null ? UUID.randomUUID() : zone.id();
        if (zone == null) {
            rows.add(new Object[]{zoneId, tenantId, loc.id(), "zone", null, null, p.plan().zoneCode(), scan.next(), zoneName, actorUserId});
        }
        for (Map.Entry<String, List<String>> rack : p.racks().entrySet()) {
            UUID rackId = UUID.randomUUID();
            rows.add(new Object[]{rackId, tenantId, loc.id(), "rack", zoneId, "zone", rack.getKey(), scan.next(), null, actorUserId});
            for (String shelf : rack.getValue()) {
                rows.add(new Object[]{UUID.randomUUID(), tenantId, loc.id(), "shelf", rackId, "rack", shelf, scan.next(), null, actorUserId});
            }
        }
        for (String box : p.boxes()) {
            rows.add(new Object[]{UUID.randomUUID(), tenantId, loc.id(), "box", zoneId, "zone", box, scan.next(), null, actorUserId});
        }
        try {
            jdbc.batchUpdate(
                "INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code, name, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        } catch (DuplicateKeyException e) {
            throw PlaceException.codeTaken(p.plan().zoneCode());
        }
        UUID[] ids = rows.stream().map(r -> (UUID) r[0]).toArray(UUID[]::new);
        jdbc.update(
            "INSERT INTO bin_events (tenant_id, bin_id, event_type, actor_user_id, metadata) " +
            "SELECT tenant_id, id, 'created', ?, jsonb_build_object('toCode', code, 'bulkZone', ?::text) " +
            "FROM bins WHERE tenant_id = ? AND id = ANY(?::uuid[])",
            actorUserId, p.plan().zoneCode(), tenantId, ids);
        return new BulkResult(zoneId, p.plan(), List.of(ids));
    }

    // ── change ───────────────────────────────────────────────────────────────

    /** New readable code and / or name. {@code name} "" clears it, null leaves it. */
    @Transactional
    public Node rename(UUID actorUserId, UUID binId, String rawCode, String rawName) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Row bin = bin(tenantId, binId, true);
        if (bin.archived()) throw PlaceException.archived();
        String code = bin.code();
        if (rawCode != null && !rawCode.isBlank()) {
            code = PlaceCodes.cleanCode(rawCode);
            if (code == null) throw PlaceException.invalidCode();
        }
        String name = rawName == null ? bin.name() : cleanName(rawName);
        if (code.equals(bin.code()) && Objects.equals(name, bin.name())) return node(binId);
        if (!code.equalsIgnoreCase(bin.code()) && forest(tenantId, bin.locationId()).hasCode(code)) {
            throw PlaceException.codeTaken(code);
        }
        try {
            jdbc.update("UPDATE bins SET code = ?, name = ? WHERE id = ? AND tenant_id = ?", code, name, binId, tenantId);
        } catch (DuplicateKeyException e) {
            throw PlaceException.codeTaken(code);
        }
        event(tenantId, binId, "renamed", actorUserId, null, null,
            "{\"fromCode\":" + json(bin.code()) + ",\"toCode\":" + json(code) +
            ",\"fromName\":" + json(bin.name()) + ",\"toName\":" + json(name) + "}");
        return node(binId);
    }

    /** A box moves zone ↔ shelf inside its location; everything in it moves with it (pieces keep the box). */
    @Transactional
    public Node move(UUID actorUserId, UUID binId, UUID parentId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Row bin = bin(tenantId, binId, true);
        if (!"box".equals(bin.kind())) throw PlaceException.notABox();
        if (bin.archived()) throw PlaceException.archived();
        if (parentId == null) throw PlaceException.invalidParent();
        Row parent = bin(tenantId, parentId, false);
        if (!ALLOWED_PARENTS.get("box").contains(parent.kind()) || !parent.locationId().equals(bin.locationId())) {
            throw PlaceException.invalidParent();
        }
        if (parent.archived()) throw PlaceException.archived();
        if (parentId.equals(bin.parentId())) return node(binId);
        jdbc.update("UPDATE bins SET parent_id = ?, parent_kind = ? WHERE id = ? AND tenant_id = ?",
            parentId, parent.kind(), binId, tenantId);
        event(tenantId, binId, "moved", actorUserId, bin.parentId(), parentId, null);
        return node(binId);
    }

    /** {@code purpose} 'damaged' or null — a zone or a box only. */
    @Transactional
    public Node setPurpose(UUID actorUserId, UUID binId, String purpose) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Row bin = bin(tenantId, binId, true);
        if (bin.archived()) throw PlaceException.archived();
        String value = purpose == null || purpose.isBlank() ? null : purpose.strip();
        if (value != null && !"damaged".equals(value)) throw PlaceException.purposeNotAllowed();
        if (!"zone".equals(bin.kind()) && !"box".equals(bin.kind())) throw PlaceException.purposeNotAllowed();
        if (Objects.equals(value, bin.purpose())) return node(binId);
        jdbc.update("UPDATE bins SET purpose = ? WHERE id = ? AND tenant_id = ?", value, binId, tenantId);
        event(tenantId, binId, "purpose_changed", actorUserId, null, null, "{\"purpose\":" + json(value) + "}");
        return node(binId);
    }

    /**
     * Refused while a piece is here or a live place is inside it. The row lock pairs with pieces_bin_guard's FOR
     * SHARE: a piece being placed here right now either finishes first (and is counted below) or sees the archive.
     */
    @Transactional
    public Node archive(UUID actorUserId, UUID binId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        Row bin = bin(tenantId, binId, true);
        if (bin.archived()) return node(binId);
        Integer pieces = jdbc.queryForObject(
            "SELECT count(*)::int FROM pieces WHERE tenant_id = ? AND bin_id = ?", Integer.class, tenantId, binId);
        if (pieces != null && pieces > 0) throw PlaceException.notEmpty(pieces);
        Integer children = jdbc.queryForObject(
            "SELECT count(*)::int FROM bins WHERE tenant_id = ? AND parent_id = ? AND archived_at IS NULL",
            Integer.class, tenantId, binId);
        if (children != null && children > 0) throw PlaceException.hasChildren(children);
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ? AND tenant_id = ?", binId, tenantId);
        event(tenantId, binId, "archived", actorUserId, null, null, null);
        return node(binId);
    }

    // ── stickers ─────────────────────────────────────────────────────────────

    /**
     * Stickers for the shelves and boxes in / under the chosen places (a zone → all of its shelves and boxes), live
     * places only, in tree order. More than one sticker → a separator page before each zone's stickers.
     */
    @Transactional(readOnly = true)
    public byte[] stickers(List<UUID> binIds) throws IOException {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        if (binIds == null || binIds.isEmpty() || binIds.size() > MAX_STICKERS) throw PlaceException.noStickers();
        Map<UUID, Forest> forests = new LinkedHashMap<>();
        Map<UUID, Loc> locs = new HashMap<>();
        Set<UUID> chosen = new LinkedHashSet<>();
        for (UUID id : new LinkedHashSet<>(binIds)) {
            Row bin = bin(tenantId, id, false);
            Forest f = forests.computeIfAbsent(bin.locationId(), l -> forest(tenantId, l));
            locs.computeIfAbsent(bin.locationId(), l -> location(tenantId, l));
            collectHoldable(f, bin, chosen);
        }
        record Sticker(UUID zoneId, LabelPdfRenderer.Location page) {}
        List<Sticker> stickers = new ArrayList<>();
        for (Map.Entry<UUID, Forest> e : forests.entrySet()) {
            Forest f = e.getValue();
            String locName = locs.get(e.getKey()).name();
            List<Node> ordered = new ArrayList<>();
            for (Row zone : f.children(null)) addSubtree(f, zone, 0, ordered);
            for (Node n : ordered) {
                if (!chosen.contains(n.id())) continue;
                String path = n.path().isEmpty() ? locName : locName + " · " + String.join(" › ", n.path());
                stickers.add(new Sticker(zoneOf(f, f.get(n.id())).id(),
                    new LabelPdfRenderer.Location(n.scanCode(), n.code(), path)));
            }
        }
        if (stickers.isEmpty() || stickers.size() > MAX_STICKERS) throw PlaceException.noStickers();
        List<LabelPdfRenderer.Page> pages = new ArrayList<>();
        UUID lastZone = null;
        for (Sticker s : stickers) {
            if (stickers.size() > 1 && !s.zoneId().equals(lastZone)) {
                Row zone = null;
                for (Forest f : forests.values()) if (f.get(s.zoneId()) != null) zone = f.get(s.zoneId());
                String line1 = zone.name() == null ? zone.code() : zone.code() + " · " + zone.name();
                pages.add(new LabelPdfRenderer.Separator(line1, locs.get(zone.locationId()).name()));
                lastZone = s.zoneId();
            }
            pages.add(s.page());
        }
        return LabelPdfRenderer.renderPages(pages, PieceLabelLayout.Spec.of(STICKER_WIDTH_MM, STICKER_HEIGHT_MM));
    }

    // ── internals ────────────────────────────────────────────────────────────

    record Loc(UUID id, String name) {}

    record Row(UUID id, UUID locationId, String kind, String code, String name, String purpose, UUID parentId,
               String scanCode, boolean archived, Instant createdAt) {}

    /** Every place of one location (archived too — codes, paths), with its own piece count. */
    record Forest(Map<UUID, Row> rows, Map<UUID, List<Row>> byParent, Map<UUID, Integer> pieces) {
        Row get(UUID id) { return rows.get(id); }
        List<Row> children(UUID parentId) { return byParent.getOrDefault(parentId, List.of()); }
        Collection<String> codes() { return rows.values().stream().map(Row::code).toList(); }
        boolean hasCode(String code) { return rows.values().stream().anyMatch(r -> r.code().equalsIgnoreCase(code)); }
    }

    private Loc location(UUID tenantId, UUID requested) {
        List<Loc> found = requested == null
            ? jdbc.query("SELECT id, name FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
                (rs, i) -> new Loc(rs.getObject("id", UUID.class), rs.getString("name")), tenantId)
            : jdbc.query("SELECT id, name FROM locations WHERE tenant_id = ? AND id = ?",
                (rs, i) -> new Loc(rs.getObject("id", UUID.class), rs.getString("name")), tenantId, requested);
        if (found.isEmpty()) throw PlaceException.locationNotFound();
        return found.get(0);
    }

    private Row bin(UUID tenantId, UUID binId, boolean forUpdate) {
        if (binId == null) throw PlaceException.placeNotFound();
        List<Row> rows = jdbc.query(
            "SELECT id, location_id, kind, code, name, purpose, parent_id, scan_code, archived_at, created_at " +
            "FROM bins WHERE tenant_id = ? AND id = ?" + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) -> row(rs), tenantId, binId);
        if (rows.isEmpty()) throw PlaceException.placeNotFound();
        return rows.get(0);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("location_id", UUID.class), rs.getString("kind"),
            rs.getString("code"), rs.getString("name"), rs.getString("purpose"), rs.getObject("parent_id", UUID.class),
            rs.getString("scan_code"), rs.getTimestamp("archived_at") != null, created == null ? null : created.toInstant());
    }

    private Forest forest(UUID tenantId, UUID locationId) {
        List<Row> rows = jdbc.query(
            "SELECT id, location_id, kind, code, name, purpose, parent_id, scan_code, archived_at, created_at " +
            "FROM bins WHERE tenant_id = ? AND location_id = ?", (rs, i) -> row(rs), tenantId, locationId);
        Map<UUID, Row> byId = new LinkedHashMap<>();
        Map<UUID, List<Row>> byParent = new HashMap<>();
        for (Row r : rows) {
            byId.put(r.id(), r);
            byParent.computeIfAbsent(r.parentId(), k -> new ArrayList<>()).add(r);
        }
        for (List<Row> siblings : byParent.values()) siblings.sort(TREE_ORDER);
        Map<UUID, Integer> pieces = new HashMap<>();
        jdbc.query(
            "SELECT bin_id, count(*)::int AS n FROM pieces WHERE tenant_id = ? AND current_location_id = ? " +
            "AND bin_id IS NOT NULL GROUP BY bin_id",
            rs -> { pieces.put(rs.getObject("bin_id", UUID.class), rs.getInt("n")); }, tenantId, locationId);
        return new Forest(byId, byParent, pieces);
    }

    /** Racks before boxes inside a zone; otherwise by code, numbers compared as numbers (R2 before R10). */
    static final Comparator<Row> TREE_ORDER = Comparator
        .comparingInt((Row r) -> "box".equals(r.kind()) ? 1 : 0)
        .thenComparing(Row::code, PlacesService::naturalCompare);

    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                int c = new java.math.BigInteger(a.substring(si, i)).compareTo(new java.math.BigInteger(b.substring(sj, j)));
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toUpperCase(ca), Character.toUpperCase(cb));
                if (c != 0) return c;
                i++; j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private void addSubtree(Forest f, Row r, int depth, List<Node> out) {
        if (r.archived()) return;
        out.add(node(f, r, depth));
        for (Row child : f.children(r.id())) addSubtree(f, child, depth + 1, out);
    }

    private Node node(UUID binId) {
        UUID tenantId = TenantContext.require();
        Row r = bin(tenantId, binId, false);
        Forest f = forest(tenantId, r.locationId());
        return node(f, f.get(binId), depth(f, f.get(binId)));
    }

    private Node node(Forest f, Row r, int depth) {
        List<String> path = new ArrayList<>();
        for (Row p = f.get(r.parentId()); p != null; p = f.get(p.parentId())) path.add(0, label(f, p));
        return new Node(r.id(), r.kind(), r.code(), label(f, r), r.name(), r.purpose(), r.parentId(), r.scanCode(),
            path, depth, f.pieces().getOrDefault(r.id(), 0), total(f, r), r.archived(), r.createdAt());
    }

    private static String label(Forest f, Row r) {
        Row parent = f.get(r.parentId());
        return PlaceCodes.shortLabel(r.code(), parent == null ? null : parent.code());
    }

    private static int total(Forest f, Row r) {
        int n = f.pieces().getOrDefault(r.id(), 0);
        for (Row child : f.children(r.id())) n += total(f, child);
        return n;
    }

    private static int depth(Forest f, Row r) {
        int d = 0;
        for (Row p = f.get(r.parentId()); p != null; p = f.get(p.parentId())) d++;
        return d;
    }

    private static Row zoneOf(Forest f, Row r) {
        Row z = r;
        while (z.parentId() != null) z = f.get(z.parentId());
        return z;
    }

    private static void collectHoldable(Forest f, Row r, Set<UUID> out) {
        if (r.archived()) return;
        if ("shelf".equals(r.kind()) || "box".equals(r.kind())) out.add(r.id());
        for (Row child : f.children(r.id())) collectHoldable(f, child, out);
    }

    private static String nextZoneCode(Forest f) {
        return PlaceCodes.zone(PlaceCodes.lowestFree(f.codes(), "Z", 1).get(0));
    }

    record Planned(BulkPlan plan, LinkedHashMap<String, List<String>> racks, List<String> boxes) {}

    private Planned plan(BulkRequest req, Loc loc, Forest f, Row existingZone) {
        int racks = req == null || req.racks() == null ? 0 : req.racks();
        int shelves = racks == 0 ? 0 : (req.shelvesPerRack() == null ? 0 : req.shelvesPerRack());
        int boxes = req == null || req.floorBoxes() == null ? 0 : req.floorBoxes();
        if (racks < 0 || racks > MAX_RACKS || boxes < 0 || boxes > MAX_BOXES
                || (racks > 0 && (shelves < 1 || shelves > MAX_SHELVES))) {
            throw PlaceException.invalidBulk();
        }
        boolean newZone = existingZone == null;
        int total = (newZone ? 1 : 0) + racks + racks * shelves + boxes;
        if (total < 1 || total > MAX_BULK) throw PlaceException.invalidBulk();
        if (req != null && req.zoneName() != null && !req.zoneName().isBlank()) cleanName(req.zoneName());

        String zone = newZone ? nextZoneCode(f) : existingZone.code();
        List<Integer> rackNumbers = newZone
            ? java.util.stream.IntStream.rangeClosed(1, racks).boxed().toList()
            : PlaceCodes.lowestFree(f.codes(), zone + "-R", racks);
        LinkedHashMap<String, List<String>> rackCodes = new LinkedHashMap<>();
        for (int r : rackNumbers) {
            String rack = PlaceCodes.rack(zone, r);
            List<String> shelfCodes = new ArrayList<>(shelves);
            for (int s = 1; s <= shelves; s++) shelfCodes.add(PlaceCodes.shelf(rack, s));
            rackCodes.put(rack, shelfCodes);
        }
        List<String> boxCodes = PlaceCodes.lowestFree(f.codes(), "BOX-", boxes).stream().map(PlaceCodes::box).toList();

        List<String> conflicts = new ArrayList<>();
        List<String> all = new ArrayList<>();
        if (newZone) all.add(zone);
        rackCodes.forEach((rack, shelfCodes) -> { all.add(rack); all.addAll(shelfCodes); });
        all.addAll(boxCodes);
        for (String c : all) if (f.hasCode(c)) conflicts.add(c);

        String firstShelf = null, lastShelf = null;
        if (racks > 0 && shelves > 0) {
            firstShelf = rackCodes.values().iterator().next().get(0);
            List<String> lastRack = new ArrayList<>(rackCodes.values()).get(racks - 1);
            lastShelf = lastRack.get(lastRack.size() - 1);
        }
        List<String> rackList = new ArrayList<>(rackCodes.keySet());
        BulkPlan plan = new BulkPlan(loc.id(), zone, newZone, racks, racks * shelves, boxes, total,
            rackList.isEmpty() ? null : rackList.get(0), rackList.isEmpty() ? null : rackList.get(rackList.size() - 1),
            firstShelf, lastShelf,
            boxCodes.isEmpty() ? null : boxCodes.get(0), boxCodes.isEmpty() ? null : boxCodes.get(boxCodes.size() - 1),
            conflicts);
        return new Planned(plan, rackCodes, boxCodes);
    }

    private static String cleanName(String raw) {
        if (raw == null) return null;
        String n = raw.strip();
        if (n.isEmpty()) return null;
        if (n.length() > 60) throw PlaceException.invalidName();
        return n;
    }

    private void event(UUID tenantId, UUID binId, String type, UUID actorUserId, UUID fromParent, UUID toParent,
                       String metadata) {
        jdbc.update(
            "INSERT INTO bin_events (tenant_id, bin_id, event_type, actor_user_id, from_parent_id, to_parent_id, metadata) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
            tenantId, binId, type, actorUserId, fromParent, toParent, metadata);
    }

    private static String json(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
}
