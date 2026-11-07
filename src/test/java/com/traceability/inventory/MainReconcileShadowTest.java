package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S3c (problem 7) — Main reconcile counts Shopify-native exchange SHADOW orders against the ORIGINAL order's
 * exchange line (the sx CTEs of {@link MainReconcileRules#EVALUATE_SQL}). In Shopify the replacement is an exchange
 * line of the original order; in Traced it is a piece of the shadow order ('internal:shopify-exchange:'). Real
 * service on an app_user connection (RLS), mocked Shopify levels, two runs for the two-run confirm, write store "all".
 *
 *   t1  Traced ships first: the shadow piece leaves custody while Shopify's exchange line is still unfulfilled
 *       (on_hand still holds it) → in sync, no write (before the fix: a −1, then a double −1 once Shopify fulfils);
 *       then Shopify fulfils the exchange line from Main (on_hand −1) → still in sync, no write
 *   t2  Shopify fulfils first: the exchange line fulfilled from Main while the piece is still available at Main
 *       (the shadow ended, S3b) → in sync, no spurious +1; then Traced ships it naming the shadow → still in sync
 *   t3  control: a live shadow no longer holds the store — a real difference on another variant IS written
 *   t4  cross-tenant (app_user + RLS): tenant B's shadow never changes tenant A's target; same-tenant control
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainReconcileShadowTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";
    static final String CUTOFF = "2026-11-01T00:00:00Z";
    static final Set<String> ALLOWED = Set.of("fetchMainLevels", "fetchMainLevelsPage", "resolveInventoryItemIds",
        "pushMainReconcile", "pushPart1MainDecrement");

    @Autowired JdbcTemplate jdbc;               // postgres — fixtures only
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;

    final Map<String, ShopifyGateway.MainLevel> shopifyMain = new HashMap<>();
    final AtomicInteger seq = new AtomicInteger();

    record T(UUID tenant, UUID store, UUID main, String mainGid, UUID user, String shop) {
        String mainNum() { return mainGid.replaceAll("^.*/", ""); }
    }
    record V(UUID id, long shopifyId, String item) {}
    /** A shadow: its order, its order line, the original order, the exchange line's order line item id. */
    record S(UUID shadowOrder, UUID shadowItem, UUID shadowRow, UUID shadowLine, UUID original, long lineItemId) {}

    @BeforeAll
    void appUser() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTxm = new DataSourceTransactionManager(appDs);
    }

    MainReconcileService service(String writeShops) {
        MainReconcileService s = new MainReconcileService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            new StoreRepository(appJdbc, appTxm));
        s.setReportOnly(false);
        s.setWriteShops(writeShops);
        s.setPart1Since(CUTOFF);
        s.setEnabled(true);
        s.setShops("");
        s.setConfirmAfterMinutes(0);
        s.setPart1WindowDays(30);
        s.setDebounceSeconds(0);
        s.setQueueBatch(500);
        s.setAmbiguousHoldMinutes(60);
        s.setPendingStaleMinutes(15);
        return s;
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(shopifyGateway, tokenProvider);
        shopifyMain.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopifyGateway.fetchMainLevels(any(), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<ShopifyGateway.MainLevel> out = new ArrayList<>();
            for (String id : ids) {
                ShopifyGateway.MainLevel l = shopifyMain.get(id);
                if (l != null) out.add(l);
            }
            return out;
        });
        when(shopifyGateway.fetchMainLevelsPage(any(), any(), any(), any())).thenAnswer(inv ->
            new ShopifyGateway.MainLevelsPage(shopifyMain.values().stream().filter(ShopifyGateway.MainLevel::stocked).toList(), null));
        // An applied write moves Shopify's numbers, as Shopify would.
        doAnswer(inv -> { move(inv.getArgument(2), inv.getArgument(4)); return null; })
            .when(shopifyGateway).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        doAnswer(inv -> { move(inv.getArgument(2), inv.getArgument(4)); return null; })
            .when(shopifyGateway).pushPart1MainDecrement(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private void move(String item, int delta) {
        ShopifyGateway.MainLevel l = shopifyMain.get(item);
        if (l != null) shopifyMain.put(item, new ShopifyGateway.MainLevel(item, true, l.onHand() + delta,
            l.available() + delta, l.committed(), l.damaged()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        assertWritesOnlyAtMain();
    }

    // ── t1 ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void t1_tracedShipsFirst_thenShopifyFulfilsFromMain_neverAWrite() {
        T t = tenant("t1");
        V v = variant(t);
        S s = shadow(t, v, 1, false);
        String p = piece(t, v, "reserved", t.main());
        allocate(t, s.shadowItem(), p, "active");
        level(v, 1, 0, 1, 0);                              // Shopify: the unit is committed on the original's exchange line
        MainReconcileService svc = service("all");

        // Before Traced ships: the reserved piece is in custody — in sync (unchanged by S3c).
        run(svc, t, v);
        run(svc, t, v);
        assertThat(evaluate(t, v)).containsEntry("custody_at_main", 1L).containsEntry("unfulfilled_shipped_raw", 0L);

        // Traced ships the replacement: it leaves custody naming the SHADOW; Shopify still shows the exchange line
        // unfulfilled (FO open, live quantity 1, no fulfillment on the original) and still holds the unit on_hand.
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", p);
        jdbc.update("UPDATE allocations SET status = 'packed' WHERE piece_id = ?", p);
        leftCustody(t, p, s.shadowOrder(), "with_courier");
        assertThat(evaluate(t, v)).as("shipped, Shopify still holds it")
            .containsEntry("custody_at_main", 0L).containsEntry("unfulfilled_shipped_raw", 1L)
            .containsEntry("fulfilled_unpicked", 0L);
        run(svc, t, v);
        UUID r2 = run(svc, t, v);
        assertThat(diffs(t, r2)).as("in sync: no diff row").isEmpty();
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());

        // Shopify fulfils the exchange line FROM MAIN: the original's raw gets the fulfillment, the line's live
        // quantity drops to 0, Shopify's on_hand drops by 1.
        setFulfillments(t, s.original(), originalLineFulfillment(t),
            fulfillment("7001", t.mainNum(), "success", line(s.lineItemId(), v, 1)));
        jdbc.update("UPDATE shopify_exchange_shadow_lines SET live_quantity = 0, shipped_in_shopify = 0 WHERE id = ?", s.shadowLine());
        level(v, 0, 0, 0, 0);
        assertThat(evaluate(t, v)).as("Shopify fulfilled the line Traced already shipped")
            .containsEntry("unfulfilled_shipped_raw", 0L).containsEntry("fulfilled_unpicked", 0L);
        run(svc, t, v);
        UUID r4 = run(svc, t, v);
        assertThat(diffs(t, r4)).isEmpty();
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(writeCount(t)).isZero();
    }

    // ── t2 ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void t2_shopifyFulfilsFirst_thenTracedShips_neverAWrite() {
        T t = tenant("t2");
        V v = variant(t);
        // S3b's state once Shopify fulfilled the exchange line itself while Traced still had it unpacked: the line
        // stopped ('shipped_in_shopify', live 0), the shadow ended. The piece is still available at Main.
        S s = shadow(t, v, 0, true);
        String p = piece(t, v, "available", t.main());
        setFulfillments(t, s.original(), originalLineFulfillment(t),
            fulfillment("7002", t.mainNum(), "success", line(s.lineItemId(), v, 1)));
        level(v, 0, 0, 0, 0);                              // Shopify took the unit off Main when it fulfilled
        MainReconcileService svc = service("all");

        assertThat(evaluate(t, v)).as("Shopify fulfilled from Main a unit Traced still holds")
            .containsEntry("custody_at_main", 1L).containsEntry("fulfilled_unpicked", 1L)
            .containsEntry("unfulfilled_shipped_raw", 0L);
        run(svc, t, v);
        UUID r2 = run(svc, t, v);
        assertThat(diffs(t, r2)).as("in sync: no spurious +1").isEmpty();
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());

        // Traced ships the piece naming the shadow.
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", p);
        allocate(t, s.shadowItem(), p, "packed");
        leftCustody(t, p, s.shadowOrder(), "with_courier");
        assertThat(evaluate(t, v)).containsEntry("custody_at_main", 0L).containsEntry("fulfilled_unpicked", 0L)
            .containsEntry("unfulfilled_shipped_raw", 0L);
        run(svc, t, v);
        UUID r4 = run(svc, t, v);
        assertThat(diffs(t, r4)).isEmpty();
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(writeCount(t)).isZero();
    }

    // ── t3 ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void t3_liveShadowNoLongerHolds_realDifferenceOnAnotherVariantIsWritten() {
        T t = tenant("t3");
        V v = variant(t), w = variant(t);
        S s = shadow(t, v, 1, false);
        allocate(t, s.shadowItem(), piece(t, v, "reserved", t.main()), "active");
        level(v, 1, 0, 1, 0);                              // the shadow's variant is in sync
        piece(t, w, "available", t.main());
        level(w, 3, 3, 0, 0);                              // a real difference: −2

        TransactionTemplate tx = new TransactionTemplate(appTxm);
        Boolean hold = TenantContext.runAs(t.tenant(), () -> tx.execute(st ->
            appJdbc.queryForObject(MainReconcileRules.EXCHANGE_HOLD_SQL, Boolean.class, t.tenant())));
        assertThat(hold).as("a shadow order no longer holds the store").isFalse();

        MainReconcileService svc = service("all");
        run(svc, t, v, w);
        UUID r2 = run(svc, t, v, w);
        assertThat(diffs(t, r2).stream().map(d -> d.get("skip_reason")).toList())
            .doesNotContain(MainReconcileRules.SHOPIFY_EXCHANGE_IN_CUSTODY);
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(w.item()), eq(t.mainGid()), eq(-2), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), eq(v.item()), any(), anyInt(), anyInt(), any(), any());
        assertThat(writeCount(t)).isEqualTo(1);
    }

    // ── t4 ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void t4_crossTenant_otherTenantsShadowNeverChangesTarget_appUserRls() {
        long shared = 7_400_000_000L + seq.incrementAndGet();      // the same Shopify variant id in both stores
        T a = tenant("t4a"), b = tenant("t4b");
        V va = variant(a, shared), vb = variant(b, shared);

        // Tenant B: shadow piece shipped, Shopify still holds it on B's exchange line (B's +1 term).
        S sb = shadow(b, vb, 1, false);
        String pb = piece(b, vb, "with_courier", b.main());
        allocate(b, sb.shadowItem(), pb, "packed");
        leftCustody(b, pb, sb.shadowOrder(), "with_courier");
        // ...and a Main fulfillment on B's exchange line for a unit B hasn't shipped (B's −1 term).
        S sb2 = shadow(b, vb, 0, true);
        setFulfillments(b, sb2.original(), fulfillment("7004", b.mainNum(), "success", line(sb2.lineItemId(), vb, 1)));

        // Tenant A: one piece available at Main, Shopify in sync.
        piece(a, va, "available", a.main());
        level(va, 1, 1, 0, 0);
        MainReconcileService svc = service("all");
        run(svc, a, va);
        run(svc, a, va);
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(evaluate(a, va)).as("A sees none of B's shadow terms")
            .containsEntry("custody_at_main", 1L).containsEntry("unfulfilled_shipped_raw", 0L)
            .containsEntry("fulfilled_unpicked", 0L);

        // Same-tenant control: B's own evaluation carries both terms.
        assertThat(evaluate(b, vb)).containsEntry("unfulfilled_shipped_raw", 1L).containsEntry("fulfilled_unpicked", 1L);

        // Under A's context, asking with B's tenant id sees nothing of B (RLS).
        List<Map<String, Object>> cross = TenantContext.runAs(a.tenant(), () -> new TransactionTemplate(appTxm).execute(st ->
            new NamedParameterJdbcTemplate(appJdbc).queryForList(MainReconcileRules.EVALUATE_SQL, params(b, vb))));
        assertThat(cross).as("tenant A's context never sees tenant B's rows").isEmpty();
    }

    // ── guard ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** After every test: reads and the two named writes only; every write names the tenant's Traced Main location. */
    private void assertWritesOnlyAtMain() {
        Set<String> mainGids = new HashSet<>(jdbc.queryForList(
            "SELECT shopify_location_id FROM locations WHERE is_fulfillment AND shopify_location_id IS NOT NULL", String.class));
        for (Invocation inv : Mockito.mockingDetails(shopifyGateway).getInvocations()) {
            String m = inv.getMethod().getName();
            assertThat(ALLOWED).as("Shopify call %s", m).contains(m);
            if (m.startsWith("fetchMainLevels") || m.startsWith("push")) {
                assertThat(mainGids).as("%s location", m).contains((String) inv.getArgument(m.startsWith("push") ? 3 : 2));
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────

    private UUID run(MainReconcileService s, T t, V... vs) {
        List<UUID> ids = Arrays.stream(vs).map(V::id).toList();
        UUID id = TenantContext.runAs(t.tenant(), () -> s.runVariants(t.tenant(), ids));
        assertThat(id).isNotNull();
        assertThat(jdbc.queryForMap("SELECT * FROM main_reconcile_runs WHERE id = ? AND tenant_id = ?", id, t.tenant()))
            .containsEntry("status", "completed");
        return id;
    }

    private MapSqlParameterSource params(T t, V v) {
        return new MapSqlParameterSource("t", t.tenant()).addValue("main", t.main()).addValue("mainGid", t.mainGid())
            .addValue("mainNum", t.mainNum()).addValue("ids", "{" + v.id() + "}")
            .addValue("cancelWindowMinutes", 60).addValue("settle", true).addValue("holdMinutes", 60);
    }

    /** The target's parts for one variant, as the service reads them (app_user, RLS, the tenant's context). */
    private Map<String, Object> evaluate(T t, V v) {
        List<Map<String, Object>> rows = TenantContext.runAs(t.tenant(), () -> new TransactionTemplate(appTxm).execute(st ->
            new NamedParameterJdbcTemplate(appJdbc).queryForList(MainReconcileRules.EVALUATE_SQL, params(t, v))));
        assertThat(rows).hasSize(1);
        Map<String, Object> out = new HashMap<>();
        for (String k : List.of("custody_at_main", "away_counted", "fulfilled_from_main", "fulfilled_unpicked",
                "part1_owed", "unfulfilled_shipped_raw")) {
            out.put(k, ((Number) rows.get(0).get(k)).longValue());
        }
        out.put("fulfillment_unreadable", rows.get(0).get("fulfillment_unreadable"));
        assertThat(out.get("fulfillment_unreadable")).isEqualTo(false);
        return out;
    }

    private List<Map<String, Object>> diffs(T t, UUID run) {
        return jdbc.queryForList("SELECT * FROM main_reconcile_diffs WHERE run_id = ? AND tenant_id = ?", run, t.tenant());
    }

    private int writeCount(T t) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_writes WHERE tenant_id = ?", Integer.class, t.tenant());
    }

    private void level(V v, int onHand, int available, int committed, int damaged) {
        shopifyMain.put(v.item(), new ShopifyGateway.MainLevel(v.item(), true, onHand, available, committed, damaged));
    }

    // ── fixtures (postgres) ───────────────────────────────────────────────────────────────────────────────────

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        long mainNum = 82_000_000L + seq.incrementAndGet();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String mainGid = "gid://shopify/Location/" + mainNum;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", location, tenant, mainGid);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            UUID.randomUUID(), tenant, store, "gid://shopify/Product/" + shop);
        return new T(tenant, store, location, mainGid, user, shop);
    }

    private V variant(T t) { return variant(t, 7_200_000_000L + seq.incrementAndGet()); }

    private V variant(T t, long sid) {
        String item = "gid://shopify/InventoryItem/" + (9_200_000_000L + sid) + "-" + t.tenant().toString().substring(0, 6);
        UUID id = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ? LIMIT 1", UUID.class, t.tenant());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", id, t.tenant(), product, "gid://shopify/ProductVariant/" + sid, "SKU-" + sid, item);
        return new V(id, sid, item);
    }

    private String piece(T t, V v, String status, UUID location) {
        int k = seq.incrementAndGet();
        String id = String.format("01SHADOWMR%016d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, now() - interval '30 days')",
            id, t.tenant(), v.id(), status, "SM-" + k, String.format("S%07d", k), location);
        return id;
    }

    /**
     * A Shopify-native exchange as S3b stores it: the original order (delivered, its own line fulfilled from Main
     * long ago), a Shopify Return on it with one exchange line of v (order line LineItem/&lt;id&gt; on the original),
     * the shadow order ('internal:shopify-exchange:', 'confirmed') with one line of v, its V219 rows. live = the
     * exchange line's live quantity; ended = the shadow's reservation ended (Shopify fulfilled it itself).
     */
    private S shadow(T t, V v, int live, boolean ended) {
        int k = seq.incrementAndGet();
        long lineItemId = 55_000_000_000L + k;
        UUID original = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod', now() - interval '10 days', '{}'::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Order/SX" + k, "#SX" + k);
        setFulfillments(t, original, originalLineFulfillment(t));
        String returnGid = "gid://shopify/Return/" + (66_000_000_000L + k);
        UUID ret = jdbc.queryForObject(
            "INSERT INTO shopify_returns (tenant_id, store_id, return_gid, order_id, order_gid, name, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, ?, 'OPEN', '{}'::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), returnGid, original, "gid://shopify/Order/SX" + k, "#SX" + k + "-R1");
        UUID xl = jdbc.queryForObject(
            "INSERT INTO shopify_exchange_lines (tenant_id, return_id, line_gid, variant_gid, variant_id, quantity, " +
            "processed_quantity, order_line_gids, raw) VALUES (?, ?, ?, ?, ?, 1, 1, ARRAY[?]::text[], '{}'::jsonb) RETURNING id",
            UUID.class, t.tenant(), ret, "gid://shopify/ExchangeLineItem/" + k, "gid://shopify/ProductVariant/" + v.shopifyId(),
            v.id(), "gid://shopify/LineItem/" + lineItemId);
        UUID shadowOrder = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, origin) " +
            "VALUES (?, ?, ?, ?, 'confirmed'::order_status, 'cod', now(), '{}'::jsonb, 'shopify_exchange') RETURNING id",
            UUID.class, t.tenant(), t.store(), "internal:shopify-exchange:" + returnGid, "#SX" + k + "-X1");
        UUID item = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, t.tenant(), shadowOrder, v.id(), live);
        UUID row = jdbc.queryForObject(
            "INSERT INTO shopify_exchange_shadows (tenant_id, store_id, return_id, order_id, original_order_id, x_index, " +
            "approved_at, state, end_reason, ended_at) VALUES (?, ?, ?, ?, ?, 1, now() - interval '1 day', ?, ?, " +
            (ended ? "now()" : "NULL") + ") RETURNING id",
            UUID.class, t.tenant(), t.store(), ret, shadowOrder, original, ended ? "ended" : "live",
            ended ? "all_lines_ended" : null);
        UUID line = jdbc.queryForObject(
            "INSERT INTO shopify_exchange_shadow_lines (tenant_id, shadow_id, exchange_line_id, order_item_id, variant_id, " +
            "quantity, live_quantity, shipped_in_shopify, stop_reason, stopped_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, " +
            (ended ? "now()" : "NULL") + ") RETURNING id",
            UUID.class, t.tenant(), row, xl, item, v.id(), live, ended ? 1 : 0, ended ? "shipped_in_shopify" : null);
        return new S(shadowOrder, item, row, line, original, lineItemId);
    }

    /** The original order's own line, fulfilled from Main long ago (another variant Shopify knows; not in the run). */
    private String originalLineFulfillment(T t) {
        return "{\"id\": 6" + seq.incrementAndGet() + ", \"location_id\": " + t.mainNum() + ", \"status\": \"success\", " +
            "\"created_at\": \"2026-10-20T08:00:00Z\", \"line_items\": [{\"id\": 4" + seq.incrementAndGet() +
            ", \"variant_id\": 1, \"quantity\": 1}]}";
    }

    private void setFulfillments(T t, UUID order, String... fs) {
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ? AND tenant_id = ?",
            "{\"fulfillments\": [" + String.join(", ", fs) + "]}", order, t.tenant());
    }

    private static String fulfillment(String id, String locationNum, String status, String... lines) {
        return "{\"id\": " + id + ", \"location_id\": " + locationNum + ", \"status\": \"" + status +
            "\", \"created_at\": \"2026-11-05T08:00:00Z\", \"line_items\": [" + String.join(", ", lines) + "]}";
    }

    private static String line(long id, V v, int qty) {
        return "{\"id\": " + id + ", \"variant_id\": " + v.shopifyId() + ", \"quantity\": " + qty + "}";
    }

    private void allocate(T t, UUID orderItem, String piece, String status) {
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
            t.tenant(), orderItem, piece, status);
        UUID order = jdbc.queryForObject("SELECT order_id FROM order_items WHERE id = ?", UUID.class, orderItem);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", order, piece);
    }

    private void leftCustody(T t, String piece, UUID order, String to) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, actor_user_id, occurred_at) " +
            "VALUES (?, ?, ?, ?, 'packed', ?::piece_status, ?, now())",
            t.tenant(), piece, "delivered".equals(to) ? "delivered" : "handed_to_courier", order, to, t.user());
    }
}
