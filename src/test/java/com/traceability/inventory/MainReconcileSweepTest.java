package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Main reconcile sweep — the 2026-11-06 fix for the failed sweeps since V204 (fulfilled_unpicked).
 *
 * Prod: every 4-hourly sweep since V204 had 1–4 failed store runs ("canceling statement due to statement timeout",
 * Supabase's 120 s platform statement_timeout, surfaced as DataAccessResourceFailureException). The fulfilled_unpicked
 * term re-scanned and re-expanded every order's fulfillments once PER VARIANT of a 1,000-variant chunk. Here app_user
 * gets a role-level statement_timeout (as on prod, scaled down) on a Femine-sized store, so the old query fails the
 * same way.
 *
 *   s1  PERFORMANCE GUARD + same numbers: 2,000 variants, 5,000 open orders (~3 KB raw each, a tenth fulfilled from
 *       Main): each 1,000-variant EVALUATE chunk finishes under app_user's statement_timeout, and fulfilled_unpicked per
 *       variant is exactly what the rule says (packed / cancelled orders, cancelled fulfillments, other locations and
 *       orders whose waybill already moved don't count; a pre-pickup waybill does)
 *   s2  the full sweep of that store completes (attempt 1), timed
 *   g1  structural guard: with the planner forced into nested loops (prod's misestimate), no tenant-wide scan of
 *       orders runs more than once in EVALUATE (the fulfillments are expanded once per statement)
 *   r1  a store whose sweep run fails is retried once, after every other store, in the same sweep (attempt 2 row);
 *       a retry that succeeds raises nothing
 *   r2  internal alert: a store failing (both attempts) in two sweeps in a row → ONE alert naming the shop; a third
 *       failed sweep → no new alert; a sweep that succeeds ends the streak, the next two failures alert again
 *   r3  a sweep whose retry succeeded counts as a success: fail/fail, fail/ok, fail/fail → no alert
 *   x1  app_user: the failure streak of one store never reads another store's runs (positive control: its own)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainReconcileSweepTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    /** app_user's statement_timeout here (prod: 120 s on a far bigger, shared machine). The old query ran minutes. */
    static final int STATEMENT_TIMEOUT_S = 30;
    static final int VARIANTS = 2_000, ORDERS = 5_000, OTHER_LOCATION = 1234;
    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;               // postgres — fixtures only
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    MainReconcileService service;
    MainReconcileAlerter alerter;
    MainReconcileJob job;

    /** shop domain → number of fetchMainLevelsPage calls that should still fail. */
    final Map<String, Integer> failNext = new ConcurrentHashMap<>();
    final AtomicInteger seq = new AtomicInteger();

    record T(UUID tenant, UUID store, UUID main, String mainGid, String shop) {
        String mainNum() { return mainGid.replaceAll("^.*/", ""); }
    }

    @BeforeAll
    void setUp() {
        jdbc.execute("ALTER ROLE app_user SET statement_timeout = '" + STATEMENT_TIMEOUT_S + "s'");
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTxm = new DataSourceTransactionManager(appDs);
        service = new MainReconcileService(appJdbc, appTxm, shopifyGateway, tokenProvider, new StoreRepository(appJdbc, appTxm));
        service.setReportOnly(true);
        service.setEnabled(true);
        service.setShops("");
        service.setConfirmAfterMinutes(10);
        service.setPart1WindowDays(30);
        service.setDebounceSeconds(0);
        service.setQueueBatch(500);
        service.setStoreSkipRowMinutes(60);
        service.setCancelledFulfillmentWindowMinutes(60);
        alerter = mock(MainReconcileAlerter.class);
        job = new MainReconcileJob(service, new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "postgres"), alerter);
    }

    @AfterAll
    void tearDown() {
        jdbc.execute("ALTER ROLE app_user RESET statement_timeout");
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(shopifyGateway, tokenProvider, alerter);
        failNext.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopifyGateway.fetchMainLevelsPage(any(), any(), any(), any())).thenAnswer(inv -> {
            String shop = inv.getArgument(0);
            if (failNext.merge(shop, -1, Integer::sum) >= 0) throw new IllegalStateException("simulated failure for " + shop);
            failNext.remove(shop);
            List<String> items = jdbc.queryForList("SELECT v.shopify_inventory_item_id FROM variants v JOIN stores s " +
                "ON s.tenant_id = v.tenant_id WHERE s.shop_domain = ? AND v.shopify_inventory_item_id IS NOT NULL", String.class, shop);
            List<ShopifyGateway.MainLevel> levels = new ArrayList<>();
            for (String item : items) levels.add(new ShopifyGateway.MainLevel(item, true, 0, 0, 0, 0));
            return new ShopifyGateway.MainLevelsPage(levels, null);
        });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── s1 / s2: performance guard ───────────────────────────────────────────────────────────────────────────────

    T big;
    Map<Long, Integer> expected;   // shopify variant id → fulfilled_unpicked

    private void bigStore() {
        if (big != null) return;
        big = tenant("big");
        long base = 7_300_000_000L;
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ?", UUID.class, big.tenant());
        // Every order: two lines (variants g % N and 7g % N, qty 1 and 2) in ONE fulfillment — at Main for g % 10 = 0
        // (cancelled when g % 50 = 0), elsewhere otherwise — and ~3 KB of incompressible note text (prod ≈ 4 KB / order).
        String fulfillment =
            "jsonb_build_object('id', g, 'location_id', CASE WHEN g % 10 = 0 THEN ?::bigint ELSE " + OTHER_LOCATION + " END, " +
            "  'status', CASE WHEN g % 50 = 0 THEN 'cancelled' ELSE 'success' END, 'created_at', '2026-11-01T08:00:00Z', " +
            "  'line_items', jsonb_build_array(" +
            "     jsonb_build_object('id', g * 10 + 1, 'variant_id', ?::bigint + (g % " + VARIANTS + "), 'quantity', 1), " +
            "     jsonb_build_object('id', g * 10 + 2, 'variant_id', ?::bigint + ((7 * g) % " + VARIANTS + "), 'quantity', 2)))";
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "SELECT ?, ?, 'gid://shopify/Order/PERF' || g, '#PERF' || g, " +
            "       (CASE WHEN g % 70 = 0 THEN 'packed' WHEN g % 90 = 0 THEN 'cancelled' ELSE 'new' END)::order_status, " +
            "       'cod', now(), jsonb_build_object('fulfillments', jsonb_build_array(" + fulfillment + "), " +
            "       'note', (SELECT string_agg(md5(g::text || ':' || i), '') FROM generate_series(1, 100) i)) " +
            "FROM generate_series(1, ?) g",
            big.tenant(), big.store(), Long.parseLong(big.mainNum()), base, base, ORDERS);
        // Waybills: moved (with the courier) for g % 40 = 0 — no longer waiting to be picked; pre-pickup for g % 30 = 0.
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "SELECT o.tenant_id, o.id, 'bosta', (6000000000 + substr(o.number, 6)::bigint)::text, " +
            "       (CASE WHEN substr(o.number, 6)::int % 40 = 0 THEN 'with_courier' ELSE 'created' END)::shipment_internal_state, 'forward' " +
            "FROM orders o WHERE o.tenant_id = ? AND (substr(o.number, 6)::int % 40 = 0 OR substr(o.number, 6)::int % 30 = 0)",
            big.tenant());
        jdbc.update("INSERT INTO variants (tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "SELECT ?, ?, 'gid://shopify/ProductVariant/' || (?::bigint + k), 'V', 'SKU-' || (?::bigint + k), " +
            "       'gid://shopify/InventoryItem/' || (?::bigint + k) FROM generate_series(0, ? - 1) k",
            big.tenant(), product, base, base, base + 1_000_000_000L, VARIANTS);
        jdbc.execute("ANALYZE");

        expected = new HashMap<>();
        for (int g = 1; g <= ORDERS; g++) {
            boolean open = g % 70 != 0 && g % 90 != 0;
            boolean mainSuccess = g % 10 == 0 && g % 50 != 0;
            boolean moved = g % 40 == 0;
            if (!open || !mainSuccess || moved) continue;
            expected.merge(base + (g % VARIANTS), 1, Integer::sum);
            expected.merge(base + ((7L * g) % VARIANTS), 2, Integer::sum);
        }
    }

    @Test
    void s1_evaluateChunks_finishUnderStatementTimeout_andFulfilledUnpickedIsExact() {
        bigStore();
        List<UUID> ids = jdbc.queryForList("SELECT id FROM variants WHERE tenant_id = ? ORDER BY id", UUID.class, big.tenant());
        Map<Long, Integer> got = new HashMap<>();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(appJdbc);
        TransactionTemplate tx = new TransactionTemplate(appTxm);
        for (int i = 0; i < ids.size(); i += 1000) {
            List<UUID> chunk = ids.subList(i, Math.min(ids.size(), i + 1000));
            long t0 = System.nanoTime();
            List<Map<String, Object>> rows = TenantContext.runAs(big.tenant(), () -> tx.execute(s -> named.queryForList(
                MainReconcileRules.EVALUATE_SQL,
                new MapSqlParameterSource("t", big.tenant()).addValue("main", big.main()).addValue("mainGid", big.mainGid())
                    .addValue("mainNum", big.mainNum()).addValue("ids", MainReconcileService.uuidArray(chunk))
                    .addValue("cancelWindowMinutes", 60).addValue("settle", false).addValue("holdMinutes", 60))));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("MainReconcileSweepTest.s1: EVALUATE chunk %d (%d variants, %d orders) took %d ms%n",
                i / 1000 + 1, chunk.size(), ORDERS, ms);
            assertThat(rows).hasSize(chunk.size());
            Map<UUID, Long> sid = new HashMap<>();
            jdbc.query("SELECT id, external_id FROM variants WHERE tenant_id = ?", rs -> {
                sid.put(rs.getObject(1, UUID.class), Long.parseLong(rs.getString(2).replaceAll("^.*/", "")));
            }, big.tenant());
            for (Map<String, Object> r : rows) {
                int fu = ((Number) r.get("fulfilled_unpicked")).intValue();
                if (fu != 0) got.put(sid.get((UUID) r.get("variant_id")), fu);
            }
        }
        assertThat(expected).hasSizeGreaterThan(100);
        assertThat(got).isEqualTo(expected);
    }

    @Test
    void s2_fullSweepOfALargeStore_completes() {
        bigStore();
        long t0 = System.nanoTime();
        job.sweep(List.of(big.tenant()));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("MainReconcileSweepTest.s2: full sweep (%d variants, %d orders) took %d ms%n", VARIANTS, ORDERS, ms);
        List<Map<String, Object>> runs = sweepRuns(big);
        assertThat(runs).isNotEmpty();
        Map<String, Object> last = runs.get(0);
        assertThat(last).containsEntry("status", "completed").containsEntry("sweep_attempt", 1)
            .containsEntry("variants_checked", VARIANTS);
        verifyNoInteractions(alerter);
    }

    /**
     * g1 — the structural guard, independent of the planner's estimates. Prod's slow plan came from a misestimate
     * (the store's variants and orders estimated at a handful of rows) that made the planner nest the orders scan
     * inside a loop over the variants. Here the planner is forced into nested loops the same way (no hash / merge
     * joins, no materialize / memoize) and the EVALUATE plan is read back: no scan of the tenant's orders that isn't a
     * lookup by order id may run more than once — the fulfillments are expanded once per statement, whatever the plan.
     */
    @Test
    void g1_ordersAreScannedOncePerStatement_evenWhenThePlannerIsForcedIntoNestedLoops() throws Exception {
        bigStore();
        List<UUID> chunk = jdbc.queryForList("SELECT id FROM variants WHERE tenant_id = ? ORDER BY id LIMIT 1000", UUID.class, big.tenant());
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(appJdbc);
        TransactionTemplate tx = new TransactionTemplate(appTxm);
        String json = TenantContext.runAs(big.tenant(), () -> tx.execute(s -> {
            for (String off : List.of("enable_hashjoin", "enable_mergejoin", "enable_material", "enable_memoize"))
                appJdbc.execute("SET LOCAL " + off + " = off");
            return named.queryForObject("EXPLAIN (ANALYZE, TIMING OFF, FORMAT JSON) " + MainReconcileRules.EVALUATE_SQL,
                new MapSqlParameterSource("t", big.tenant()).addValue("main", big.main()).addValue("mainGid", big.mainGid())
                    .addValue("mainNum", big.mainNum()).addValue("ids", MainReconcileService.uuidArray(chunk))
                    .addValue("cancelWindowMinutes", 60).addValue("settle", false).addValue("holdMinutes", 60), String.class);
        }));
        List<String> repeated = new ArrayList<>();
        walk(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(0).get("Plan"), repeated);
        System.out.println("MainReconcileSweepTest.g1: execution time " +
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(0).get("Execution Time") + " ms");
        assertThat(repeated).as("tenant-wide scans of orders that ran more than once").isEmpty();
    }

    private static void walk(com.fasterxml.jackson.databind.JsonNode node, List<String> repeated) {
        if ("orders".equals(node.path("Relation Name").asText())) {
            String cond = node.path("Index Cond").asText("") + " " + node.path("Recheck Cond").asText("");
            boolean byId = cond.matches("(?s).*\\(id = .*");
            long loops = node.path("Actual Loops").asLong();
            if (!byId && loops > 1) repeated.add(node.path("Node Type").asText() + " " + node.path("Index Name").asText("") +
                " cond=[" + cond.trim() + "] loops=" + loops);
        }
        for (com.fasterxml.jackson.databind.JsonNode c : node.path("Plans")) walk(c, repeated);
    }

    // ── r: retry + alert ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void r1_failedStoreIsRetriedOnceAtTheEndOfTheSameSweep_successfulRetryRaisesNothing() {
        T a = smallStore("r1a"), b = smallStore("r1b");
        failNext.put(a.shop(), 1);              // only the first attempt fails
        job.sweep(List.of(a.tenant(), b.tenant()));

        List<Map<String, Object>> ra = sweepRuns(a);   // newest first
        assertThat(ra).extracting(r -> r.get("status")).containsExactly("completed", "failed");
        assertThat(ra).extracting(r -> r.get("sweep_attempt")).containsExactly(2, 1);
        assertThat((String) ra.get(1).get("error")).contains("simulated failure");
        List<Map<String, Object>> rb = sweepRuns(b);
        assertThat(rb).extracting(r -> r.get("status")).containsExactly("completed");
        // The retry ran after every other store had its turn.
        assertThat((java.sql.Timestamp) ra.get(0).get("started_at")).isAfter((java.sql.Timestamp) rb.get(0).get("started_at"));
        verifyNoInteractions(alerter);
    }

    @Test
    void r2_twoFailedSweepsInARow_alertOnce_streakEndsOnSuccess() {
        T a = smallStore("r2"), ok = smallStore("r2ok");
        sweepFailing(a, ok);                                // sweep 1: fail, retry fail — streak 1
        verifyNoInteractions(alerter);
        sweepFailing(a, ok);                                // sweep 2: streak 2 → alert
        verify(alerter, times(1)).alert(contains(a.shop()), contains("simulated failure"));
        sweepFailing(a, ok);                                // sweep 3: streak 3 → nothing new
        verify(alerter, times(1)).alert(anyString(), anyString());
        job.sweep(List.of(a.tenant(), ok.tenant()));        // sweep 4: succeeds — streak over
        sweepFailing(a, ok);                                // sweep 5: streak 1
        verify(alerter, times(1)).alert(anyString(), anyString());
        sweepFailing(a, ok);                                // sweep 6: streak 2 → alert again
        verify(alerter, times(2)).alert(contains(a.shop()), anyString());
        verify(alerter, never()).alert(contains(ok.shop()), anyString());
    }

    @Test
    void r3_aSweepWhoseRetrySucceeded_countsAsSuccess() {
        T a = smallStore("r3");
        sweepFailing(a);                                    // fail / fail
        failNext.put(a.shop(), 1);
        job.sweep(List.of(a.tenant()));                     // fail / ok
        sweepFailing(a);                                    // fail / fail
        assertThat(TenantContext.runAs(a.tenant(), () -> service.consecutiveFailedSweeps(a.tenant())).failedInARow()).isEqualTo(1);
        verifyNoInteractions(alerter);
    }

    @Test
    void x1_appUser_failureStreakNeverReadsAnotherStoresRuns_positiveControl() {
        T a = smallStore("x1a"), b = smallStore("x1b");
        sweepFailing(a);
        sweepFailing(a);
        job.sweep(List.of(b.tenant()));
        assertThat(TenantContext.runAs(a.tenant(), () -> service.consecutiveFailedSweeps(a.tenant())).failedInARow()).isEqualTo(2);
        assertThat(TenantContext.runAs(b.tenant(), () -> service.consecutiveFailedSweeps(b.tenant())).failedInARow()).isZero();
        // b's context reading a's failed run: RLS hides it.
        UUID aFailed = jdbc.queryForObject("SELECT id FROM main_reconcile_runs WHERE tenant_id = ? AND status = 'failed' LIMIT 1",
            UUID.class, a.tenant());
        assertThat(TenantContext.runAs(b.tenant(), () -> service.runFailed(b.tenant(), aFailed))).isFalse();
        assertThat(TenantContext.runAs(a.tenant(), () -> service.runFailed(a.tenant(), aFailed))).isTrue();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────────────────────

    /** One sweep over these stores in which the first store fails on both attempts. */
    private void sweepFailing(T failing, T... others) {
        failNext.put(failing.shop(), 2);
        List<UUID> ts = new ArrayList<>(List.of(failing.tenant()));
        for (T o : others) ts.add(o.tenant());
        job.sweep(ts);
        failNext.remove(failing.shop());
    }

    private List<Map<String, Object>> sweepRuns(T t) {
        return jdbc.queryForList("SELECT * FROM main_reconcile_runs WHERE tenant_id = ? AND kind = 'sweep' " +
            "ORDER BY started_at DESC, id DESC", t.tenant());
    }

    private T smallStore(String name) {
        T t = tenant(name);
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ?", UUID.class, t.tenant());
        long sid = 7_400_000_000L + seq.incrementAndGet() * 10L;
        jdbc.update("INSERT INTO variants (tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, 'V', ?, ?)", t.tenant(), product, "gid://shopify/ProductVariant/" + sid, "SKU-" + sid,
            "gid://shopify/InventoryItem/" + (sid + 1_000_000_000L));
        return t;
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        long mainNum = 81_000_000L + seq.incrementAndGet();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String mainGid = "gid://shopify/Location/" + mainNum;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", location, tenant, mainGid);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            UUID.randomUUID(), tenant, store, "gid://shopify/Product/" + shop);
        return new T(tenant, store, location, mainGid, shop);
    }
}
