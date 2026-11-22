package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Part 1's candidate lines and unreadable-order count (disk IO fix, 2026-11-22). Same rows, same order, same count
 * as the one-statement query it replaces (PART1_LINES_SQL / PART1_UNREADABLE_SQL, kept verbatim in
 * MainReconcilePart1LinesEquivalenceTest), but an order's raw is read only when the order changed.
 *
 * <p>Per tenant, an in-memory cache of every window order's extracted lines, keyed by (order id, xmin). A row's xmin
 * changes with every UPDATE of the row, so an unchanged (id, xmin) is an unchanged row: its cached lines are exactly
 * what re-reading raw would give. Each run reads the window's (id, xmin) (heap only, orders_tenant_created_at_idx) and
 * detoasts raw only for orders that are new or changed; orders that left the window or were deleted drop out because
 * step 1 doesn't return them. The variant join, the Main filter, the variant filter and the ORDER BY stay in SQL
 * (step 2, {@link MainReconcileRules#PART1_RESOLVE_SQL}). A full run (the 4-hourly sweep, an empty cache, a changed
 * window) ignores the cache and refills it, so nothing cached lives longer than one sweep interval.
 *
 * <p>Runs of one tenant are serialised by the tenant advisory lock (the caller holds it); the cache entry is replaced
 * whole, and any entry is correct on its own (it only describes the row version it names), so a race could only cost
 * a re-read, never a wrong line. Not a bean: the service owns one, built on its own template (the caller's
 * transaction, tenant context and RLS).
 */
final class MainReconcilePart1Lines {

    static final String VARIANT_GID_PREFIX = "gid://shopify/ProductVariant/";

    /** One extracted line as step 2 reads it: the variant gid for the queued-run prefilter, the JSON object itself. */
    record Line(String variantGid, String json) {}

    record Order(String ver, boolean unreadable, List<Line> lines) {}

    private record TenantCache(int days, Map<UUID, Order> orders) {}

    /** The candidate lines (original columns and order) and the window's unreadable-order count. */
    record Result(List<Row> lines, int unreadableOrders, int reread, int windowOrders) {}

    record Row(UUID orderId, boolean orderCancelled, UUID variantId, String fulfillmentId, String lineItemId,
               String status, String location, int qty, Timestamp createdAt) {}

    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<UUID, TenantCache> cache = new ConcurrentHashMap<>();

    MainReconcilePart1Lines(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /**
     * @param variantIds null = every variant (and a full re-read); else only lines of these variants.
     */
    Result lines(UUID tenantId, String mainNum, int days, List<UUID> variantIds) {
        boolean full = variantIds == null;
        TenantCache old = cache.get(tenantId);
        Map<UUID, Order> known = (full || old == null || old.days() != days) ? Map.of() : old.orders();

        List<UUID> cIds = new ArrayList<>(known.size());
        List<String> cVers = new ArrayList<>(known.size());
        known.forEach((id, o) -> { cIds.add(id); cVers.add(o.ver()); });

        Map<UUID, Order> now = new HashMap<>();
        int[] reread = {0};
        named.query(MainReconcileRules.PART1_EXTRACT_SQL, new MapSqlParameterSource("t", tenantId).addValue("days", days)
                .addValue("cIds", MainReconcileService.uuidArray(cIds)).addValue("cVers", MainReconcileService.textArray(cVers)),
            rs -> {
                UUID id = rs.getObject("order_id", UUID.class);
                if (rs.getBoolean("cached")) {
                    now.put(id, known.get(id));
                } else {
                    reread[0]++;
                    now.put(id, new Order(rs.getString("ver"), rs.getBoolean("unreadable"), parse(rs.getString("lines"))));
                }
            });
        cache.put(tenantId, new TenantCache(days, Map.copyOf(now)));

        int unreadable = 0;
        Set<String> gids = full ? null : variantGids(tenantId, variantIds);
        StringJoiner json = new StringJoiner(",", "[", "]");
        int picked = 0;
        for (Order o : now.values()) {
            if (o.unreadable()) unreadable++;
            for (Line l : o.lines()) {
                if (gids == null || (l.variantGid() != null && gids.contains(VARIANT_GID_PREFIX + l.variantGid()))) {
                    json.add(l.json());
                    picked++;
                }
            }
        }
        List<Row> rows = picked == 0 ? List.of() : named.query(MainReconcileRules.PART1_RESOLVE_SQL,
            new MapSqlParameterSource("t", tenantId).addValue("mainNum", mainNum).addValue("lines", json.toString())
                .addValue("all", full)
                .addValue("ids", MainReconcileService.uuidArray(full ? List.of() : variantIds)),
            (rs, i) -> new Row(rs.getObject("order_id", UUID.class), rs.getBoolean("order_cancelled"),
                rs.getObject("variant_id", UUID.class), rs.getString("fulfillment_id"), rs.getString("line_item_id"),
                rs.getString("fulfillment_status"), rs.getString("fulfilled_location_id"), rs.getInt("qty"),
                rs.getTimestamp("fulfillment_created_at")));
        return new Result(rows, unreadable, reread[0], now.size());
    }

    /** The external ids of the queued variants — a line can match only one of these (step 2 then joins exactly). */
    private Set<String> variantGids(UUID tenantId, Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) return Set.of();
        return new HashSet<>(named.queryForList(
            "SELECT external_id FROM variants WHERE tenant_id = :t AND id = ANY(CAST(:ids AS uuid[]))",
            new MapSqlParameterSource("t", tenantId).addValue("ids", MainReconcileService.uuidArray(variantIds)),
            String.class));
    }

    private List<Line> parse(String linesJson) {
        if (linesJson == null) return List.of();
        try {
            JsonNode arr = mapper.readTree(linesJson);
            List<Line> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                JsonNode gid = n.get("variant_gid");
                out.add(new Line(gid == null || gid.isNull() ? null : gid.asText(), mapper.writeValueAsString(n)));
            }
            return List.copyOf(out);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Part 1 lines: unreadable extraction JSON", e);
        }
    }

    /** Test hook: forget every tenant's cache. */
    void clear() { cache.clear(); }
}
