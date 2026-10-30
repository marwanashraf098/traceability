package com.traceability.places;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Warehouse places S3 — where to pick from. DISPLAY ONLY: nothing here assigns a piece (pick still assigns only the
 * scanned piece, with no place check) and nothing changes the Pick &amp; Pack payloads — getOrder, the waybill
 * session's order card and the gather rows stay byte-identical; screens fetch places from here, after their own
 * data, and work as before without them.
 *
 * <p>A count is the variant's AVAILABLE pieces at the MAIN warehouse ({@code locations.is_fulfillment}), per place
 * (largest first) plus {@code noPlaceCount} (available there, no place). Other locations and every other status
 * (reserved, damaged, on_hold, …) are left out. 404 unless the tenant has places.
 *
 * <p>All variants of a request are counted in ONE statement (no per-variant query): placed pieces by
 * pieces_bin_idx's predicate, unplaced ones beside them, each place's parents joined in (a box is at most four
 * levels deep: box → shelf → rack → zone).
 */
@Service
public class PickPlacesService {

    static final int MAX_VARIANTS = 2000;
    static final int MAX_ORDERS = 1000;

    /** {@code path}: short labels from the zone down (Z1, R01, S2 / BOX-07); {@code sortKey}: {@link PlaceSort}. */
    public record PlaceCount(UUID binId, String code, String kind, List<String> path, String zoneCode,
                             String parentCode, String parentKind, String sortKey, int count) {}
    public record VariantPlaces(UUID variantId, List<PlaceCount> places, int noPlaceCount) {}
    public record VariantsResponse(List<VariantPlaces> variants) {}
    public record LinePlaces(UUID orderItemId, UUID variantId, int quantity, List<PlaceCount> places, int noPlaceCount) {}
    public record OrderPlaces(UUID orderId, List<LinePlaces> lines) {}
    public record OrdersResponse(List<OrderPlaces> orders) {}

    static final String COUNTS_SQL =
        "WITH main AS (SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment), " +
        "c AS ( " +
        "  SELECT p.variant_id, p.bin_id, count(*)::int AS n FROM pieces p JOIN main m ON m.id = p.current_location_id " +
        "  WHERE p.tenant_id = ? AND p.bin_id IS NOT NULL AND p.status = 'available' AND p.variant_id = ANY(?) " +
        "  GROUP BY p.variant_id, p.bin_id " +
        "  UNION ALL " +
        "  SELECT p.variant_id, NULL::uuid, count(*)::int FROM pieces p JOIN main m ON m.id = p.current_location_id " +
        "  WHERE p.tenant_id = ? AND p.bin_id IS NULL AND p.status = 'available' AND p.variant_id = ANY(?) " +
        "  GROUP BY p.variant_id " +
        ") " +
        "SELECT c.variant_id, c.bin_id, c.n, " +
        "       b.kind AS k0, b.code AS c0, b1.kind AS k1, b1.code AS c1, " +
        "       b2.kind AS k2, b2.code AS c2, b3.kind AS k3, b3.code AS c3 " +
        "FROM c " +
        "LEFT JOIN bins b  ON b.id  = c.bin_id " +
        "LEFT JOIN bins b1 ON b1.id = b.parent_id " +
        "LEFT JOIN bins b2 ON b2.id = b1.parent_id " +
        "LEFT JOIN bins b3 ON b3.id = b2.parent_id";

    private final JdbcTemplate jdbc;

    public PickPlacesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The gather list's batch: every variant asked for, in the order asked (unknown ids read as nothing). */
    @Transactional(readOnly = true)
    public VariantsResponse variants(List<UUID> variantIds) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        List<UUID> ids = distinct(variantIds, MAX_VARIANTS);
        Map<UUID, VariantPlaces> counted = count(tenantId, ids);
        List<VariantPlaces> out = new ArrayList<>(ids.size());
        for (UUID id : ids) out.add(counted.getOrDefault(id, new VariantPlaces(id, List.of(), 0)));
        return new VariantsResponse(out);
    }

    /** One order's lines (pack screen, queue and waybill mode). 404 for an order the caller's merchant_orders lacks. */
    @Transactional(readOnly = true)
    public OrderPlaces order(UUID orderId) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        OrdersResponse r = ordersFor(tenantId, List.of(orderId));
        if (r.orders().isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return r.orders().get(0);
    }

    /** Several orders' lines (waybill mode's printed-but-not-packed list). Orders outside merchant_orders are left out. */
    @Transactional(readOnly = true)
    public OrdersResponse orders(List<UUID> orderIds) {
        UUID tenantId = TenantContext.require();
        Places.require(jdbc, tenantId);
        return ordersFor(tenantId, distinct(orderIds, MAX_ORDERS));
    }

    private OrdersResponse ordersFor(UUID tenantId, List<UUID> orderIds) {
        if (orderIds.isEmpty()) return new OrdersResponse(List.of());
        record Line(UUID orderId, UUID itemId, UUID variantId, int quantity) {}
        List<Line> lines = new ArrayList<>();
        Set<UUID> found = new LinkedHashSet<>();
        // Every order the caller may see in merchant code: merchant_orders (portal pre-connect rows excluded).
        // ORDER BY only makes the reply deterministic; nothing here picks a "latest" row.
        jdbc.query(
            "SELECT o.id AS order_id, oi.id AS item_id, oi.variant_id, oi.quantity FROM merchant_orders o " +
            "LEFT JOIN order_items oi ON oi.order_id = o.id AND oi.tenant_id = o.tenant_id " +
            "WHERE o.tenant_id = ? AND o.id = ANY(?) ORDER BY o.id, oi.variant_id, oi.id",
            rs -> {
                UUID orderId = rs.getObject("order_id", UUID.class);
                found.add(orderId);
                UUID itemId = rs.getObject("item_id", UUID.class);
                if (itemId != null) lines.add(new Line(orderId, itemId, rs.getObject("variant_id", UUID.class), rs.getInt("quantity")));
            },
            tenantId, orderIds.toArray(new UUID[0]));
        Map<UUID, VariantPlaces> counted = count(tenantId, lines.stream().map(Line::variantId).distinct().toList());
        Map<UUID, List<LinePlaces>> byOrder = new LinkedHashMap<>();
        for (UUID id : orderIds) if (found.contains(id)) byOrder.put(id, new ArrayList<>());
        for (Line l : lines) {
            VariantPlaces v = counted.get(l.variantId());
            byOrder.get(l.orderId()).add(new LinePlaces(l.itemId(), l.variantId(), l.quantity(),
                v == null ? List.of() : v.places(), v == null ? 0 : v.noPlaceCount()));
        }
        List<OrderPlaces> out = new ArrayList<>(byOrder.size());
        byOrder.forEach((id, ls) -> out.add(new OrderPlaces(id, List.copyOf(ls))));
        return new OrdersResponse(out);
    }

    /** ONE statement for every variant: per place, largest first (then walking order), and the unplaced count. */
    Map<UUID, VariantPlaces> count(UUID tenantId, List<UUID> variantIds) {
        Map<UUID, VariantPlaces> out = new HashMap<>();
        if (variantIds.isEmpty()) return out;
        UUID[] ids = variantIds.toArray(new UUID[0]);
        Map<UUID, List<PlaceCount>> places = new HashMap<>();
        Map<UUID, Integer> noPlace = new HashMap<>();
        jdbc.query(COUNTS_SQL, rs -> {
            UUID variantId = rs.getObject("variant_id", UUID.class);
            UUID binId = rs.getObject("bin_id", UUID.class);
            int n = rs.getInt("n");
            if (binId == null) {
                noPlace.merge(variantId, n, Integer::sum);
                return;
            }
            List<PlaceSort.Level> chain = new ArrayList<>(4);       // the place first, its zone last
            for (int i = 0; i < 4; i++) {
                String kind = rs.getString("k" + i);
                if (kind == null) break;
                chain.add(new PlaceSort.Level(kind, rs.getString("c" + i)));
            }
            Collections.reverse(chain);                             // zone first
            places.computeIfAbsent(variantId, k -> new ArrayList<>()).add(placeCount(binId, chain, n));
        }, tenantId, tenantId, ids, tenantId, ids);
        Comparator<PlaceCount> largestFirst = Comparator.comparingInt(PlaceCount::count).reversed()
            .thenComparing(PlaceCount::sortKey);
        for (UUID id : variantIds) {
            List<PlaceCount> ps = places.getOrDefault(id, new ArrayList<>());
            ps.sort(largestFirst);
            int none = noPlace.getOrDefault(id, 0);
            if (!ps.isEmpty() || none > 0) out.put(id, new VariantPlaces(id, List.copyOf(ps), none));
        }
        return out;
    }

    private static PlaceCount placeCount(UUID binId, List<PlaceSort.Level> chain, int n) {
        List<String> path = new ArrayList<>(chain.size());
        String parentCode = null;
        for (PlaceSort.Level l : chain) {
            path.add(PlaceCodes.shortLabel(l.code(), parentCode));
            parentCode = l.code();
        }
        PlaceSort.Level self = chain.get(chain.size() - 1);
        PlaceSort.Level parent = chain.size() > 1 ? chain.get(chain.size() - 2) : null;
        String zone = "zone".equals(chain.get(0).kind()) ? chain.get(0).code() : null;
        return new PlaceCount(binId, self.code(), self.kind(), List.copyOf(path), zone,
            parent == null ? null : parent.code(), parent == null ? null : parent.kind(), PlaceSort.key(chain), n);
    }

    private static List<UUID> distinct(List<UUID> ids, int max) {
        if (ids == null) return List.of();
        List<UUID> out = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (out.size() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return out;
    }
}
