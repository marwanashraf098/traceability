package com.traceability.places;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Where a place sits, for screens that show one (put away, Lookup): each place's readable code, kind, and its
 * trail — the labels from the zone down to the place itself (Z1 › R01 › S2; a box's trail ends with the box, its
 * parent's full code is {@code parentCode}: BOX-12 on Z1-R01-S2). One recursive query for any number of places.
 * Caller's connection and tenant context.
 */
public final class PlacePaths {

    /** {@code trail}: labels top first, the place's own last. {@code parentCode}: the parent's full code, or null. */
    public record Place(UUID id, String kind, String code, String scanCode, UUID locationId, boolean archived,
                        List<String> trail, String parentCode, String parentKind) {}

    private PlacePaths() {}

    public static Map<UUID, Place> describe(JdbcTemplate jdbc, UUID tenantId, Collection<UUID> ids) {
        Map<UUID, Place> out = new HashMap<>();
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) return out;
        record Link(UUID start, String code, String kind, int level) {}
        Map<UUID, List<Link>> chains = new HashMap<>();
        jdbc.query(
            "WITH RECURSIVE up AS ( " +
            "  SELECT b.id AS start, b.id, b.parent_id, b.code, b.kind, 0 AS lvl FROM bins b " +
            "  WHERE b.tenant_id = ? AND b.id = ANY(?) " +
            "  UNION ALL " +
            "  SELECT up.start, p.id, p.parent_id, p.code, p.kind, up.lvl + 1 FROM up " +
            "  JOIN bins p ON p.id = up.parent_id AND p.tenant_id = ? " +
            ") SELECT start, code, kind, lvl FROM up ORDER BY start, lvl DESC",
            rs -> {
                UUID start = rs.getObject("start", UUID.class);
                chains.computeIfAbsent(start, k -> new ArrayList<>())
                    .add(new Link(start, rs.getString("code"), rs.getString("kind"), rs.getInt("lvl")));
            },
            tenantId, wanted.toArray(new UUID[0]), tenantId);
        Map<UUID, Object[]> self = new HashMap<>();
        jdbc.query("SELECT id, kind, code, scan_code, location_id, archived_at FROM bins WHERE tenant_id = ? AND id = ANY(?)",
            rs -> {
                self.put(rs.getObject("id", UUID.class), new Object[] {
                    rs.getString("kind"), rs.getString("code"), rs.getString("scan_code"),
                    rs.getObject("location_id", UUID.class), rs.getTimestamp("archived_at") != null });
            },
            tenantId, wanted.toArray(new UUID[0]));
        for (UUID id : wanted) {
            Object[] s = self.get(id);
            List<Link> chain = chains.get(id);
            if (s == null || chain == null) continue;
            List<String> trail = new ArrayList<>(chain.size());
            String parent = null;
            for (Link l : chain) {                      // top (highest level) first
                trail.add(PlaceCodes.shortLabel(l.code(), parent));
                parent = l.code();
            }
            Link up = chain.size() > 1 ? chain.get(chain.size() - 2) : null;
            out.put(id, new Place(id, (String) s[0], (String) s[1], (String) s[2], (UUID) s[3], (Boolean) s[4],
                List.copyOf(trail), up == null ? null : up.code(), up == null ? null : up.kind()));
        }
        return out;
    }

    public static Place describe(JdbcTemplate jdbc, UUID tenantId, UUID id) {
        return id == null ? null : describe(jdbc, tenantId, List.of(id)).get(id);
    }
}
