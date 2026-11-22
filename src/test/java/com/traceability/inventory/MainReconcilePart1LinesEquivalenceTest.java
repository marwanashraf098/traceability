package com.traceability.inventory;

import com.traceability.FlywayTestSupport;
import com.traceability.TestPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disk IO fix (2026-11-22): Part 1's candidate lines and unreadable-order count from {@link MainReconcilePart1Lines}
 * (cached per order by row version) are exactly what the one-statement queries it replaced returned — same rows, same
 * order — on the same fixtures, cold and after every kind of change the cache has to notice.
 *
 * <p>OLD_LINES_SQL / OLD_UNREADABLE_SQL are the replaced MainReconcileRules.PART1_LINES_SQL / PART1_UNREADABLE_SQL,
 * expanded and frozen here as the reference.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MainReconcilePart1LinesEquivalenceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static final String FULFILLMENTS = "(CASE WHEN jsonb_typeof(o.raw->'fulfillments') = 'array' THEN o.raw->'fulfillments' ELSE '[]'::jsonb END)";
    static final String REST_SHAPE = "(f->>'location_id' IS NOT NULL AND jsonb_typeof(f->'line_items') = 'array')";

    /** The replaced PART1_LINES_SQL, verbatim after expansion. */
    static final String OLD_LINES_SQL =
        "SELECT o.id AS order_id, (COALESCE(o.is_cancelled, false) OR o.status = 'cancelled') AS order_cancelled, " +
        "       vv.id AS variant_id, f->>'id' AS fulfillment_id, li->>'id' AS line_item_id, " +
        "       lower(f->>'status') AS fulfillment_status, f->>'location_id' AS fulfilled_location_id, " +
        "       (li->>'quantity')::int AS qty, " +
        "       (CASE WHEN f->>'created_at' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}' " +
        "             THEN (f->>'created_at')::timestamptz END) AS fulfillment_created_at " +
        "FROM merchant_orders o " +
        "CROSS JOIN LATERAL jsonb_array_elements(" + FULFILLMENTS + ") f " +
        "CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(f->'line_items') = 'array' " +
        "                                             THEN f->'line_items' ELSE '[]'::jsonb END) li " +
        "LEFT JOIN variants vv ON vv.tenant_id = o.tenant_id AND ('gid://shopify/ProductVariant/' || (li->>'variant_id') = vv.external_id) " +
        "WHERE o.tenant_id = :t AND o.created_at > now() - make_interval(days => :days) " +
        "  AND o.external_id NOT LIKE 'internal:%' " +
        "  AND " + REST_SHAPE + " AND f->>'location_id' <> :mainNum " +
        "  AND f->>'id' IS NOT NULL AND li->>'id' IS NOT NULL " +
        "  AND li->>'quantity' ~ '^[0-9]+$' AND (li->>'quantity')::int > 0 " +
        "  AND (:all OR vv.id = ANY(CAST(:ids AS uuid[]))) " +
        "ORDER BY o.id, vv.id, f->>'created_at', f->>'id', li->>'id'";

    /** The replaced PART1_UNREADABLE_SQL. */
    static final String OLD_UNREADABLE_SQL =
        "SELECT COUNT(*) FROM merchant_orders o " +
        "WHERE o.tenant_id = :t AND o.created_at > now() - make_interval(days => :days) " +
        "  AND o.external_id NOT LIKE 'internal:%' " +
        "  AND EXISTS (SELECT 1 FROM jsonb_array_elements(" + FULFILLMENTS + ") f WHERE NOT " + REST_SHAPE + ")";

    static final String MAIN = "9999";
    static final int DAYS = 30;

    static JdbcTemplate jdbc;
    static NamedParameterJdbcTemplate named;
    static MainReconcilePart1Lines cache;
    static final UUID TENANT = UUID.randomUUID(), OTHER = UUID.randomUUID();
    static UUID store, otherStore, productA, productB;
    static final List<UUID> variants = new ArrayList<>();
    static final List<UUID> orders = new ArrayList<>();
    static final Random rnd = new Random(20261122L);

    @BeforeAll
    static void setUp() {
        FlywayTestSupport.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"));
        named = new NamedParameterJdbcTemplate(jdbc);
        cache = new MainReconcilePart1Lines(named);

        store = UUID.randomUUID(); otherStore = UUID.randomUUID(); productA = UUID.randomUUID(); productB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Eq'), (?, 'Other')", TENANT, OTHER);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, 'eq.myshopify.com', 'connected'), " +
            "(?, ?, 'other.myshopify.com', 'connected')", store, TENANT, otherStore, OTHER);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, 'gid://shopify/Product/a', 'A'), " +
            "(?, ?, ?, 'gid://shopify/Product/b', 'B')", productA, TENANT, store, productB, TENANT, store);
        // Variants 1..20 known to Traced (21..25 not); 7 exists twice (two products — the join yields two rows).
        for (int v = 1; v <= 20; v++) variants.add(variant(productA, v));
        variants.add(variant(productB, 7));
        // The other tenant has variant 1 too (must never join).
        UUID otherProduct = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, 'gid://shopify/Product/o', 'O')",
            otherProduct, OTHER, otherStore);
        jdbc.update("INSERT INTO variants (tenant_id, product_id, external_id, title) VALUES (?, ?, 'gid://shopify/ProductVariant/1', 'O1')",
            OTHER, otherProduct);

        for (int i = 0; i < 240; i++) orders.add(order(TENANT, store, "gid://shopify/Order/" + i, randomRaw(i), daysAgo(i)));
        for (int i = 0; i < 20; i++) order(OTHER, otherStore, "gid://shopify/Order/o" + i, randomRaw(1000 + i), 2);
        // internal orders (shadow / portal / exchange) with readable non-Main lines: never candidates.
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, origin, raw) VALUES (?, ?, 'internal:shopify-exchange:1', 'shopify_exchange', ?::jsonb)",
            TENANT, store, randomRaw(5000));
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, origin, shopify_order_gid, raw) VALUES (?, ?, 'internal:portal:1', 'portal_pre_connect', 'gid://shopify/Order/p1', ?::jsonb)",
            TENANT, store, randomRaw(5001));
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, raw) VALUES (?, ?, 'internal:exchange:1', ?::jsonb)",
            TENANT, store, randomRaw(5002));
    }

    static UUID variant(UUID product, int n) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title) VALUES (?, ?, ?, ?, ?)",
            id, TENANT, product, "gid://shopify/ProductVariant/" + n, "V" + n);
        return id;
    }

    static UUID order(UUID tenant, UUID st, String externalId, String raw, double daysAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, raw, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?::order_status, ?::jsonb, now() - make_interval(secs => ?))",
            id, tenant, st, externalId, "#" + externalId.hashCode(), rnd.nextInt(10) == 0 ? "cancelled" : "delivered",
            raw, daysAgo * 86400);
        return id;
    }

    /** 0..~40 days back; a few right at the window's edge. */
    static double daysAgo(int i) {
        if (i % 37 == 0) return DAYS - 0.001;
        if (i % 41 == 0) return DAYS + 0.001;
        return rnd.nextDouble() * 40;
    }

    /** Every shape the query has to handle (REST, GraphQL, non-array, bad quantities, missing ids, Main or not). */
    static String randomRaw(int seed) {
        Random r = new Random(seed);
        StringBuilder fs = new StringBuilder("[");
        int nf = r.nextInt(4);
        for (int f = 0; f < nf; f++) {
            if (f > 0) fs.append(',');
            int shape = r.nextInt(12);
            String fid = (seed * 10 + f) + "";
            if (shape == 0) { fs.append("{\"id\":\"gid://shopify/Fulfillment/").append(fid).append("\",\"status\":\"SUCCESS\"}"); continue; } // GraphQL
            if (shape == 1) { fs.append("{\"id\":").append(fid).append(",\"location_id\":1111,\"line_items\":\"oops\"}"); continue; }
            String status = new String[] {"success", "SUCCESS", "cancelled", "pending", "open", "failure", "error"}[r.nextInt(7)];
            String loc = r.nextInt(4) == 0 ? MAIN : (r.nextBoolean() ? "1111" : "\"2222\"");
            String created = switch (r.nextInt(6)) {
                case 0 -> "null";
                case 1 -> "\"yesterday\"";
                default -> "\"2026-11-" + (10 + r.nextInt(10)) + "T0" + r.nextInt(10) + ":1" + r.nextInt(10) + ":00+02:00\"";
            };
            fs.append("{\"id\":").append(shape == 2 ? "null" : fid).append(",\"status\":\"").append(status)
              .append("\",\"location_id\":").append(loc).append(",\"created_at\":").append(created).append(",\"line_items\":[");
            int nl = 1 + r.nextInt(3);
            for (int l = 0; l < nl; l++) {
                if (l > 0) fs.append(',');
                String q = switch (r.nextInt(8)) { case 0 -> "0"; case 1 -> "\"x\""; case 2 -> "-1"; case 3 -> "\"3\""; default -> (1 + r.nextInt(3)) + ""; };
                String vid = r.nextInt(10) == 0 ? "null" : (1 + r.nextInt(25)) + "";
                String lid = r.nextInt(15) == 0 ? "null" : (seed * 100 + f * 10 + l) + "";
                fs.append("{\"id\":").append(lid).append(",\"variant_id\":").append(vid).append(",\"quantity\":").append(q).append('}');
            }
            fs.append("]}");
        }
        fs.append(']');
        String fulfillments = switch (seed % 23) { case 0 -> "{\"not\":\"an array\"}"; case 1 -> "null"; default -> fs.toString(); };
        String cancelled = seed % 13 == 0 ? ",\"cancelled_at\":\"2026-11-01T00:00:00Z\"" : "";
        return seed % 29 == 0 ? "{\"id\":" + seed + "}" : "{\"id\":" + seed + cancelled + ",\"fulfillments\":" + fulfillments + "}";
    }

    // ── the comparison ──────────────────────────────────────────────────────────────────────────────────────────

    static List<MainReconcilePart1Lines.Row> old(List<UUID> ids, String mainNum) {
        return named.query(OLD_LINES_SQL, new MapSqlParameterSource("t", TENANT).addValue("mainNum", mainNum)
                .addValue("days", DAYS).addValue("all", ids == null)
                .addValue("ids", MainReconcileService.uuidArray(ids == null ? List.of() : ids)),
            (rs, i) -> new MainReconcilePart1Lines.Row(rs.getObject("order_id", UUID.class), rs.getBoolean("order_cancelled"),
                rs.getObject("variant_id", UUID.class), rs.getString("fulfillment_id"), rs.getString("line_item_id"),
                rs.getString("fulfillment_status"), rs.getString("fulfilled_location_id"), rs.getInt("qty"),
                rs.getTimestamp("fulfillment_created_at")));
    }

    static int oldUnreadable() {
        return named.queryForObject(OLD_UNREADABLE_SQL, new MapSqlParameterSource("t", TENANT).addValue("days", DAYS), Integer.class);
    }

    /** New == old for every variant (full run) and for several queued variant sets; returns the last queued run. */
    static MainReconcilePart1Lines.Result assertSame(String mainNum) {
        MainReconcilePart1Lines.Result last = null;
        List<List<UUID>> sets = List.of(variants.subList(0, 3), List.of(variants.get(6), variants.get(20)),
            variants, List.of(UUID.randomUUID()), List.of());
        for (List<UUID> ids : sets) {
            last = cache.lines(TENANT, mainNum, DAYS, ids);
            assertThat(last.lines()).as("queued run %s", ids).containsExactlyElementsOf(old(ids, mainNum));
            assertThat(last.unreadableOrders()).isEqualTo(oldUnreadable());
        }
        return last;
    }

    @Test @Order(1)
    void e1_cold_fullAndQueued_identical() {
        cache.clear();
        List<MainReconcilePart1Lines.Row> all = old(null, MAIN);
        assertThat(all).as("the fixture has candidates, unlinked lines and duplicate-variant rows").hasSizeGreaterThan(50)
            .anyMatch(r -> r.variantId() == null);
        MainReconcilePart1Lines.Result full = cache.lines(TENANT, MAIN, DAYS, null);
        assertThat(full.lines()).containsExactlyElementsOf(all);
        assertThat(full.unreadableOrders()).isEqualTo(oldUnreadable()).isPositive();
        assertThat(full.reread()).isEqualTo(full.windowOrders());

        MainReconcilePart1Lines.Result warm = assertSame(MAIN);
        assertThat(warm.reread()).as("nothing changed — no order re-read").isZero();
    }

    @Test @Order(2)
    void e2_afterChanges_warmCacheStillIdentical_andRereadsOnlyChangedOrders() {
        cache.lines(TENANT, MAIN, DAYS, null);
        for (int round = 0; round < 6; round++) {
            int changed = 0;
            // raw rewritten (new fulfillments / cancels / moves to Main)
            for (int k = 0; k < 4; k++) {
                UUID o = orders.get(rnd.nextInt(orders.size()));
                changed += jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ? AND created_at > now() - interval '29 days'",
                    randomRaw(10_000 + round * 10 + k), o);
            }
            // an order cancelled in Traced (status only — raw untouched)
            changed += jdbc.update("UPDATE orders SET status = 'cancelled' WHERE id = ? AND created_at > now() - interval '29 days'",
                orders.get(rnd.nextInt(orders.size())));
            // a new order, an order moved out of the window
            UUID fresh = order(TENANT, store, "gid://shopify/Order/new" + round, randomRaw(20_000 + round), 0.1);
            orders.add(fresh);
            changed++;
            jdbc.update("UPDATE orders SET created_at = now() - interval '40 days' WHERE id = ?", orders.get(rnd.nextInt(orders.size())));
            // a variant Traced didn't know is synced (21..25) — no order changes, the join still sees it
            if (round < 5) variants.add(variant(productA, 21 + round));

            MainReconcilePart1Lines.Result first = cache.lines(TENANT, MAIN, DAYS, variants.subList(0, 3));
            assertThat(first.lines()).containsExactlyElementsOf(old(variants.subList(0, 3), MAIN));
            assertThat(first.reread()).as("round %d: only the changed orders are re-read", round).isLessThanOrEqualTo(changed);
            assertSame(MAIN);
            assertSame("1111");   // another Main location: the cache doesn't depend on it
        }
    }

    @Test @Order(3)
    void e3_deletedOrder_and_windowChange() {
        cache.lines(TENANT, MAIN, DAYS, null);
        UUID gone = order(TENANT, store, "gid://shopify/Order/gone", randomRaw(30_000), 1);
        cache.lines(TENANT, MAIN, DAYS, variants);
        jdbc.update("DELETE FROM orders WHERE id = ?", gone);
        assertSame(MAIN);
        // a different window ignores the cache
        MainReconcilePart1Lines.Result other = cache.lines(TENANT, MAIN, 10, variants);
        assertThat(other.reread()).isEqualTo(other.windowOrders());
    }

    /** The extraction's order ids for TENANT (no cache) on the given template. */
    static List<UUID> extractedOrderIds(NamedParameterJdbcTemplate t, UUID tenant) {
        return t.query(MainReconcileRules.PART1_EXTRACT_SQL, new MapSqlParameterSource("t", tenant).addValue("days", DAYS)
            .addValue("cIds", "{}").addValue("cVers", "{}"), (rs, i) -> rs.getObject("order_id", UUID.class));
    }

    @Test @Order(4)
    void e4_extract_neverReturnsAnOrderMerchantOrdersHides() {
        // Rows merchant_orders hides (portal pre-connect, Shopify exchange shadow) — in the window, readable non-Main lines.
        UUID portal = UUID.randomUUID(), shadow = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, origin, shopify_order_gid, raw) " +
            "VALUES (?, ?, ?, 'internal:portal:e4', 'portal_pre_connect', 'gid://shopify/Order/pe4', ?::jsonb)", portal, TENANT, store, READABLE);
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, origin, raw) " +
            "VALUES (?, ?, ?, 'internal:shopify-exchange:e4', 'shopify_exchange', ?::jsonb)", shadow, TENANT, store, READABLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_orders WHERE id IN (?, ?)", Integer.class, portal, shadow))
            .as("merchant_orders hides both").isZero();

        List<UUID> extracted = extractedOrderIds(named, TENANT);
        assertThat(extracted).doesNotContain(portal, shadow);
        // Exactly the merchant_orders rows the old query read (the view's rows, minus its own internal:% filter).
        assertThat(extracted).containsExactlyInAnyOrderElementsOf(jdbc.queryForList(
            "SELECT id FROM merchant_orders o WHERE o.tenant_id = ? AND o.created_at > now() - make_interval(days => ?) " +
            "AND o.external_id NOT LIKE 'internal:%'", UUID.class, TENANT, DAYS));
        assertThat(cache.lines(TENANT, MAIN, DAYS, null).lines()).noneMatch(r -> r.orderId().equals(portal) || r.orderId().equals(shadow));
        assertSame(MAIN);
    }

    @Test @Order(5)
    void e5_rls_asAppUser_sameTenantScopingAsTheView() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres");
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate appNamed = new NamedParameterJdbcTemplate(appJdbc);
        tx.executeWithoutResult(st -> {
            appJdbc.execute("SET LOCAL ROLE app_user");
            // No tenant set: RLS hides every row from the table read, exactly as from the view.
            assertThat(extractedOrderIds(appNamed, TENANT)).isEmpty();
            assertThat(appJdbc.queryForObject("SELECT COUNT(*) FROM merchant_orders", Integer.class)).isZero();

            // Tenant set to OTHER: asking for TENANT's orders returns nothing; asking for OTHER's returns only OTHER's.
            appJdbc.queryForObject("SELECT set_config('app.current_tenant', ?, true)", String.class, OTHER.toString());
            assertThat(extractedOrderIds(appNamed, TENANT)).isEmpty();
            List<UUID> other = extractedOrderIds(appNamed, OTHER);
            assertThat(other).isNotEmpty().containsExactlyInAnyOrderElementsOf(appJdbc.queryForList(
                "SELECT id FROM merchant_orders o WHERE o.tenant_id = ? AND o.created_at > now() - make_interval(days => ?) " +
                "AND o.external_id NOT LIKE 'internal:%'", UUID.class, OTHER, DAYS));

            // Tenant set to TENANT: the full Part 1 result as app_user equals the old query as app_user.
            appJdbc.queryForObject("SELECT set_config('app.current_tenant', ?, true)", String.class, TENANT.toString());
            MainReconcilePart1Lines appCache = new MainReconcilePart1Lines(appNamed);
            List<MainReconcilePart1Lines.Row> oldRows = appNamed.query(OLD_LINES_SQL, new MapSqlParameterSource("t", TENANT)
                    .addValue("mainNum", MAIN).addValue("days", DAYS).addValue("all", true).addValue("ids", "{}"),
                (rs, i) -> new MainReconcilePart1Lines.Row(rs.getObject("order_id", UUID.class), rs.getBoolean("order_cancelled"),
                    rs.getObject("variant_id", UUID.class), rs.getString("fulfillment_id"), rs.getString("line_item_id"),
                    rs.getString("fulfillment_status"), rs.getString("fulfilled_location_id"), rs.getInt("qty"),
                    rs.getTimestamp("fulfillment_created_at")));
            assertThat(oldRows).isNotEmpty();
            assertThat(appCache.lines(TENANT, MAIN, DAYS, null).lines()).containsExactlyElementsOf(oldRows);
        });
    }

    /** One readable non-Main fulfillment line of a known variant. */
    static final String READABLE = "{\"fulfillments\":[{\"id\":7001,\"status\":\"success\",\"location_id\":1111," +
        "\"created_at\":\"2026-11-20T10:00:00+02:00\",\"line_items\":[{\"id\":70011,\"variant_id\":1,\"quantity\":1}]}]}";
}
