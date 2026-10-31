package com.traceability.places;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Warehouse places S5 — "Where is it" for one variant (Inventory variant row, Lookup): every place holding its
 * pieces that can hold a place (available / on_hold / damaged — {@link Places#PLACEABLE_STATUSES}), per location,
 * with counts per status, in walking order ({@link PlaceSort}), plus each location's "No place yet" count. The counts
 * are ONE statement (each place's parents joined in — a box is at most four levels deep), whatever the number of
 * places or locations. Every role (Lookup is). 404 unless the tenant has places, or for a variant it can't see.
 */
@Service
public class WhereIsItService {

    public record StatusCounts(int available, int onHold, int damaged, int total) {}
    public record PlaceStock(UUID binId, String code, String kind, List<String> path, String zoneCode,
                             String parentCode, String parentKind, String sortKey, StatusCounts counts) {}
    public record LocationStock(UUID locationId, String locationName, boolean main, int total,
                                List<PlaceStock> places, StatusCounts noPlace) {}
    public record Where(UUID variantId, String productTitle, String variantTitle, String sku,
                        List<LocationStock> locations) {}

    static final String COUNTS_SQL =
        "SELECT p.current_location_id AS location_id, l.name AS location_name, l.is_fulfillment, p.bin_id, " +
        "       p.status::text AS status, count(*)::int AS n, " +
        "       b.kind AS k0, b.code AS c0, b1.kind AS k1, b1.code AS c1, b2.kind AS k2, b2.code AS c2, " +
        "       b3.kind AS k3, b3.code AS c3 " +
        "FROM pieces p " +
        "JOIN locations l ON l.id = p.current_location_id " +
        "LEFT JOIN bins b  ON b.id  = p.bin_id " +
        "LEFT JOIN bins b1 ON b1.id = b.parent_id " +
        "LEFT JOIN bins b2 ON b2.id = b1.parent_id " +
        "LEFT JOIN bins b3 ON b3.id = b2.parent_id " +
        "WHERE p.tenant_id = ? AND p.variant_id = ? AND p.status::text IN ('available', 'on_hold', 'damaged') " +
        "GROUP BY p.current_location_id, l.name, l.is_fulfillment, p.bin_id, p.status, " +
        "         b.kind, b.code, b1.kind, b1.code, b2.kind, b2.code, b3.kind, b3.code";

    private final JdbcTemplate jdbc;

    public WhereIsItService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Where where(UUID variantId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        List<Map<String, Object>> v = jdbc.queryForList(
            "SELECT pr.title AS product_title, v.title AS variant_title, v.sku FROM variants v " +
            "JOIN products pr ON pr.id = v.product_id WHERE v.id = ? AND v.tenant_id = ?", variantId, tenantId);
        if (v.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        record Loc(UUID id, String name, boolean main, Map<UUID, int[]> byBin, Map<UUID, List<PlaceSort.Level>> chains,
                   int[] none) {}
        Map<UUID, Loc> locs = new LinkedHashMap<>();
        jdbc.query(COUNTS_SQL, rs -> {
            UUID locId = rs.getObject("location_id", UUID.class);
            Loc loc = locs.computeIfAbsent(locId, k -> {
                try {
                    return new Loc(k, rs.getString("location_name"), rs.getBoolean("is_fulfillment"),
                        new HashMap<>(), new HashMap<>(), new int[3]);
                } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
            });
            int idx = switch (rs.getString("status")) { case "available" -> 0; case "on_hold" -> 1; default -> 2; };
            int n = rs.getInt("n");
            UUID binId = rs.getObject("bin_id", UUID.class);
            if (binId == null) { loc.none()[idx] += n; return; }
            loc.byBin().computeIfAbsent(binId, k -> new int[3])[idx] += n;
            if (!loc.chains().containsKey(binId)) {
                List<PlaceSort.Level> chain = new ArrayList<>(4);       // the place first, its zone last
                for (int i = 0; i < 4; i++) {
                    String kind = rs.getString("k" + i);
                    if (kind == null) break;
                    chain.add(new PlaceSort.Level(kind, rs.getString("c" + i)));
                }
                Collections.reverse(chain);                             // zone first
                loc.chains().put(binId, chain);
            }
        }, tenantId, variantId);

        List<LocationStock> out = new ArrayList<>();
        for (Loc loc : locs.values()) {
            List<PlaceStock> placesHere = new ArrayList<>();
            int total = sum(loc.none());
            for (Map.Entry<UUID, int[]> e : loc.byBin().entrySet()) {
                placesHere.add(place(e.getKey(), loc.chains().get(e.getKey()), e.getValue()));
                total += sum(e.getValue());
            }
            placesHere.sort(Comparator.comparing(PlaceStock::sortKey));
            out.add(new LocationStock(loc.id(), loc.name(), loc.main(), total, List.copyOf(placesHere), counts(loc.none())));
        }
        out.sort(Comparator.comparing((LocationStock l) -> !l.main()).thenComparing(LocationStock::locationName,
            String.CASE_INSENSITIVE_ORDER));
        Map<String, Object> h = v.get(0);
        return new Where(variantId, (String) h.get("product_title"), (String) h.get("variant_title"), (String) h.get("sku"),
            List.copyOf(out));
    }

    private static PlaceStock place(UUID binId, List<PlaceSort.Level> chain, int[] n) {
        List<String> path = new ArrayList<>(chain.size());
        String parentCode = null;
        for (PlaceSort.Level l : chain) {
            path.add(PlaceCodes.shortLabel(l.code(), parentCode));
            parentCode = l.code();
        }
        PlaceSort.Level self = chain.get(chain.size() - 1);
        PlaceSort.Level parent = chain.size() > 1 ? chain.get(chain.size() - 2) : null;
        String zone = "zone".equals(chain.get(0).kind()) ? chain.get(0).code() : null;
        return new PlaceStock(binId, self.code(), self.kind(), List.copyOf(path), zone,
            parent == null ? null : parent.code(), parent == null ? null : parent.kind(), PlaceSort.key(chain), counts(n));
    }

    private static StatusCounts counts(int[] n) {
        return new StatusCounts(n[0], n[1], n[2], sum(n));
    }

    private static int sum(int[] n) {
        return n[0] + n[1] + n[2];
    }
}
