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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Main reconcile rollout (V226, 2026-11-08): "everyone except" + the big-change brake. Every run goes through the real
 * service on an app_user connection (RLS enforced); fixtures are written as postgres; Shopify is a mock whose Main
 * levels the test sets (an applied write moves them).
 *
 *   e1  WRITE_SHOPS=all + exclude: the excluded store never writes (report-only, its confirmed diff recorded), the
 *       other writes
 *   e2  explicit list + exclude: exclude wins over the list
 *   e3  empty / blank exclude excludes nothing; a malformed entry stops startup; matching is case-insensitive
 *   b1  over the limit: the run writes nothing (skipped, large_change_held, totals on the row) and alerts once; a
 *       second held run doesn't re-alert; 24 h later it does; back under the limit then over again re-alerts
 *   b2  under the limit: written as before, brake evaluated, no alert
 *   b3  the limit is max(units, percent of Shopify on_hand at Main) — exact integer comparison
 *   b4  a held store doesn't trickle the change out: a per-variant run weighs the store's other standing confirmed
 *       differences (from the latest write sweep), a sweep's denominator is its own on_hand total
 *   b5  Part 1 is never braked: a held run still sends its Part 1 subtract
 *   x1  cross-tenant: one store held doesn't hold / suppress another (same-tenant positive control); app_user sees
 *       only its own run rows, none without a tenant
 *   l1  the startup description names mode, exclude list and thresholds
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainReconcileBrakeTest {

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
    static final String HELD = MainReconcileBrake.LARGE_CHANGE_HELD;

    @Autowired JdbcTemplate jdbc;               // postgres — fixtures only
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    MainReconcileAlerter alerter;

    final Map<String, ShopifyGateway.MainLevel> shopifyMain = new HashMap<>();
    final AtomicInteger seq = new AtomicInteger();

    record T(UUID tenant, UUID store, UUID main, String mainGid, UUID user, String shop) {}
    record V(UUID id, long shopifyId, String item) {}

    @BeforeAll
    void appUser() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTxm = new DataSourceTransactionManager(appDs);
    }

    MainReconcileService service(String writeShops, String exclude) {
        MainReconcileService s = new MainReconcileService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            new StoreRepository(appJdbc, appTxm));
        s.setReportOnly(false);
        s.setWriteShops(writeShops);
        s.setWriteExclude(exclude);
        s.setPart1Since(CUTOFF);
        s.setEnabled(true);
        s.setShops("");
        s.setConfirmAfterMinutes(0);
        s.setPart1WindowDays(30);
        s.setDebounceSeconds(0);
        s.setQueueBatch(500);
        s.setAmbiguousHoldMinutes(60);
        s.setPendingStaleMinutes(15);
        s.setBrakeUnits(20);
        s.setBrakePercent(10);
        s.setBrakeRealertHours(24);
        s.setAlerter(alerter);
        return s;
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(shopifyGateway, tokenProvider);
        alerter = mock(MainReconcileAlerter.class);
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
    void clear() { TenantContext.clear(); }

    // ── exclude list ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void e1_all_plusExclude_excludedStoreNeverWrites_othersDo() {
        T ex = tenant("e1x"), ok = tenant("e1o");
        V a = small(ex), b = small(ok);
        MainReconcileService s = service("all", "other-store.myshopify.com, " + ex.shop().toUpperCase(Locale.ROOT));

        runV(s, ex, a); runV(s, ok, b);
        UUID ra = runV(s, ex, a), rb = runV(s, ok, b);
        assertThat(runRow(ex, ra)).containsEntry("mode", "report_only").containsEntry("status", "completed")
            .containsEntry("confirmed", 1).containsEntry("writes_attempted", 0);
        assertThat(runRow(ex, ra).get("brake_checked_at")).isNull();   // report-only: the brake isn't involved
        assertThat(runRow(ok, rb)).containsEntry("mode", "write").containsEntry("writes_applied", 1);
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), eq(a.item()), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(b.item()), eq(ok.mainGid()), eq(-2), anyInt(), any(), any());
        assertThat(s.writeEnabled(ex.shop())).isFalse();
        assertThat(s.writeEnabled(" " + ex.shop().toUpperCase(Locale.ROOT) + " ")).isFalse();
        assertThat(s.writeEnabled(ok.shop())).isTrue();
        assertThat(s.writeEnabled("brand-new-signup.myshopify.com")).isTrue();   // new signups write under 'all'
        // Excluded Part 1 claims stay report-only too (never queued / sent).
        verify(shopifyGateway, never()).pushPart1MainDecrement(any(), any(), any(), eq(ex.mainGid()), anyInt(), any(), any());
    }

    @Test
    void e2_explicitList_plusExclude_excludeWins() {
        T both = tenant("e2b"), listed = tenant("e2l");
        V a = small(both), b = small(listed);
        MainReconcileService s = service(both.shop() + "," + listed.shop(), both.shop());
        runV(s, both, a); runV(s, listed, b);
        UUID ra = runV(s, both, a), rb = runV(s, listed, b);
        assertThat(runRow(both, ra)).containsEntry("mode", "report_only").containsEntry("writes_attempted", 0);
        assertThat(runRow(listed, rb)).containsEntry("mode", "write").containsEntry("writes_applied", 1);
        assertThat(s.writeEnabled("not-listed.myshopify.com")).isFalse();
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), eq(a.item()), any(), anyInt(), any(), any(), any());
    }

    @Test
    void e3_emptyExclude_excludesNothing_malformedStopsStartup() {
        for (String empty : new String[]{"", " ", " , ", null}) {
            MainReconcileService s = service("all", empty);
            assertThat(s.writeExclude()).isEmpty();
            assertThat(s.writeEnabled("anything.myshopify.com")).isTrue();
        }
        T t = tenant("e3");
        V v = small(t);
        MainReconcileService s = service("all", "");
        runV(s, t, v);
        assertThat(runRow(t, runV(s, t, v))).containsEntry("mode", "write").containsEntry("writes_applied", 1);

        MainReconcileService m = service("all", "");
        for (String bad : new String[]{"all", "Juno Babies", "https://x.myshopify.com", "x.myshopify.com/admin", "shop.com"}) {
            assertThatThrownBy(() -> m.setWriteExclude(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service("", "a.myshopify.com").writeEnabled("a.myshopify.com")).isFalse();   // empty list still writes nothing
    }

    // ── the brake ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void b1_overLimit_writesNothing_alertsOnce_reAlertAfter24h_orAfterBackUnder() {
        T t = tenant("b1");
        V v = big(t);   // Shopify 50, Traced 1 → −49 > max(20, 5)
        MainReconcileService s = service("all", "");

        assertThat(runRow(t, runAny(s, t, v))).containsEntry("status", "completed");   // first sight: unconfirmed, not held
        UUID held = runAny(s, t, v);
        Map<String, Object> r = runRow(t, held);
        assertThat(r).containsEntry("status", "skipped").containsEntry("skip_reason", HELD).containsEntry("mode", "write")
            .containsEntry("confirmed", 1).containsEntry("confirmed_units_down", 49)
            .containsEntry("brake_units_up", 0).containsEntry("brake_units_down", 49).containsEntry("brake_on_hand", 50)
            .containsEntry("brake_limit", 20).containsEntry("brake_alerted", true).containsEntry("writes_attempted", 0);
        assertThat(onlyDiff(t, held)).containsEntry("status", "confirmed").containsEntry("delta", -49);   // diff kept as computed
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(count("SELECT COUNT(*) FROM main_reconcile_writes WHERE tenant_id = ?", t.tenant())).isZero();
        verify(alerter, times(1)).alert(contains(t.shop()), argThat(body ->
            body.contains("+0 / -49") && body.contains("SKU-") && body.contains("Shopify 50 → Traced 1")));

        // Still over: held, not alerted again.
        UUID again = runAny(s, t, v);
        assertThat(runRow(t, again)).containsEntry("skip_reason", HELD).containsEntry("brake_alerted", false);
        verify(alerter, times(1)).alert(anyString(), anyString());

        // 24 h after the last alert: alerted again.
        jdbc.update("UPDATE main_reconcile_runs SET brake_checked_at = brake_checked_at - interval '25 hours' " +
                    "WHERE tenant_id = ? AND brake_checked_at IS NOT NULL", t.tenant());
        assertThat(runRow(t, runAny(s, t, v))).containsEntry("brake_alerted", true);
        verify(alerter, times(2)).alert(anyString(), anyString());

        // Back under the limit (a small difference, written), then over again → alerted again.
        level(v, 3, 3, 0, 0);
        runAny(s, t, v);                                     // new delta: unconfirmed, evaluated under the limit
        UUID under = runAny(s, t, v);                        // confirmed −2: written
        assertThat(runRow(t, under)).containsEntry("status", "completed").containsEntry("writes_applied", 1)
            .containsEntry("brake_units_down", 2);
        level(v, 60, 60, 0, 0);
        runAny(s, t, v);
        assertThat(runRow(t, runAny(s, t, v))).containsEntry("skip_reason", HELD).containsEntry("brake_alerted", true);
        verify(alerter, times(3)).alert(anyString(), anyString());
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(v.item()), any(), anyInt(), any(), any(), any());
    }

    @Test
    void b2_underLimit_writtenAsToday_noAlert() {
        T t = tenant("b2");
        V v = variant(t);
        for (int i = 0; i < 1; i++) piece(t, v, "available", t.main());
        level(v, 21, 21, 0, 0);   // −20: exactly at the floor → not over
        MainReconcileService s = service("all", "");
        runV(s, t, v);
        UUID r = runV(s, t, v);
        assertThat(runRow(t, r)).containsEntry("status", "completed").containsEntry("skip_reason", null)
            .containsEntry("writes_applied", 1).containsEntry("brake_units_down", 20).containsEntry("brake_limit", 20)
            .containsEntry("brake_alerted", false);
        assertThat(runRow(t, r).get("brake_checked_at")).isNotNull();
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(v.item()), eq(t.mainGid()), eq(-20), anyInt(), any(), any());
        verifyNoInteractions(alerter);
    }

    @Test
    void b3_limit_isMaxOfUnitsAndPercent() {
        assertThat(MainReconcileBrake.limit(20, 10, 50)).isEqualTo(20);
        assertThat(MainReconcileBrake.limit(20, 10, 1000)).isEqualTo(100);
        assertThat(MainReconcileBrake.limit(20, 10, -5)).isEqualTo(20);
        assertThat(MainReconcileBrake.over(20, 10, 50, 20)).isFalse();
        assertThat(MainReconcileBrake.over(20, 10, 50, 21)).isTrue();
        assertThat(MainReconcileBrake.over(20, 10, 1000, 100)).isFalse();
        assertThat(MainReconcileBrake.over(20, 10, 1000, 101)).isTrue();
        assertThat(MainReconcileBrake.over(20, 10, 1005, 101)).isTrue();    // 10% of 1005 = 100.5 → 101 is over
        assertThat(MainReconcileBrake.over(20, 10, 1010, 101)).isFalse();
    }

    @Test
    void b4_heldStore_noTrickle_perVariantRunsWeighTheStoresStandingDifferences() {
        T t = tenant("b4");
        V bigV = big(t), smallV = small(t);
        MainReconcileService s = service("all", "");

        sweep(s, t);
        UUID held = sweep(s, t);
        assertThat(runRow(t, held)).containsEntry("skip_reason", HELD).containsEntry("shopify_on_hand_total", 53)
            .containsEntry("brake_on_hand", 53).containsEntry("brake_units_down", 51);
        // A per-variant run of only the small variant: −2 alone is under the limit, but the store's standing −49 counts.
        UUID small1 = runAny(s, t, smallV);
        assertThat(runRow(t, small1)).containsEntry("skip_reason", HELD).containsEntry("brake_units_down", 51)
            .containsEntry("brake_on_hand", 53).containsEntry("brake_alerted", false);
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(alerter, times(1)).alert(anyString(), anyString());

        // The big difference goes away (merchant fixed Shopify); the next run of the big variant re-checks it.
        level(bigV, 1, 1, 0, 0);
        assertThat(runRow(t, runAny(s, t, bigV))).containsEntry("status", "completed").containsEntry("brake_units_down", 2);
        UUID small2 = runAny(s, t, smallV);
        assertThat(runRow(t, small2)).containsEntry("status", "completed").containsEntry("writes_applied", 1);
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(smallV.item()), any(), eq(-2), anyInt(), any(), any());
    }

    @Test
    void b5_heldRun_stillSendsPart1() {
        T t = tenant("b5");
        V bigV = big(t), p = variant(t);
        MainReconcileService s = service("all", "");
        runAny(s, t, bigV);
        assertThat(runRow(t, runAny(s, t, bigV))).containsEntry("skip_reason", HELD);   // the store is held

        // Now a piece Traced shipped for a line Shopify fulfilled from another location (after the cutoff).
        UUID o = order(t, "{\"fulfillments\": [{\"id\": 9501, \"location_id\": 424242, \"status\": \"success\", " +
            "\"created_at\": \"2026-11-02T08:00:00Z\", \"line_items\": [{\"id\": 95011, \"variant_id\": " + p.shopifyId() +
            ", \"quantity\": 1}]}]}");
        String piece = piece(t, p, "with_courier", t.main());
        allocate(t, orderItem(t, o, p, 1), piece, "packed");
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, actor_user_id, occurred_at) " +
            "VALUES (?, ?, 'handed_to_courier', ?, 'packed', 'with_courier'::piece_status, ?, now())", t.tenant(), piece, o, t.user());
        level(p, 1, 1, 0, 0);

        UUID held = runAny(s, t, bigV, p);
        assertThat(runRow(t, held)).containsEntry("skip_reason", HELD).containsEntry("writes_attempted", 1)
            .containsEntry("writes_applied", 1);
        verify(shopifyGateway, times(1)).pushPart1MainDecrement(any(), any(), eq(p.item()), eq(t.mainGid()), eq(-1), any(), any());
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(jdbc.queryForObject("SELECT status FROM main_reconcile_part1_claims WHERE tenant_id = ? AND fulfillment_id = '9501'",
            String.class, t.tenant())).isEqualTo("applied");
    }

    // ── cross-tenant ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void x1_crossTenant_oneStoreHeld_otherUnaffected_rlsScopesRunRows() {
        T a = tenant("x1a"), b = tenant("x1b");
        V va = big(a), vb = small(b), vb2 = big(b);
        MainReconcileService s = service("all", "");

        runAny(s, a, va); runAny(s, b, vb);
        UUID ra = runAny(s, a, va), rb = runAny(s, b, vb);
        // Same-tenant positive control: A is held and alerted.
        assertThat(runRow(a, ra)).containsEntry("skip_reason", HELD).containsEntry("brake_alerted", true);
        // B: A's standing difference never counts for B — written normally.
        assertThat(runRow(b, rb)).containsEntry("status", "completed").containsEntry("brake_units_down", 2)
            .containsEntry("writes_applied", 1);
        // A's alert doesn't suppress B's own.
        runAny(s, b, vb2);
        assertThat(runRow(b, runAny(s, b, vb2))).containsEntry("skip_reason", HELD).containsEntry("brake_alerted", true);
        verify(alerter, times(1)).alert(contains(a.shop()), anyString());
        verify(alerter, times(1)).alert(contains(b.shop()), anyString());

        // app_user: no tenant → no run rows; B sees its own, never A's.
        assertThat((Integer) new TransactionTemplate(appTxm).execute(st ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_runs", Integer.class))).isZero();
        int bSees = TenantContext.runAs(b.tenant(), () -> new TransactionTemplate(appTxm).execute(st ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_runs WHERE id = ?", Integer.class, ra)));
        int aSees = TenantContext.runAs(a.tenant(), () -> new TransactionTemplate(appTxm).execute(st ->
            appJdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_runs WHERE id = ? AND brake_alerted", Integer.class, ra)));
        assertThat(bSees).isZero();
        assertThat(aSees).isEqualTo(1);
    }

    @Test
    void l1_startupDescription_namesModeExcludeAndThresholds() {
        String d = service("all", "b.myshopify.com,a.myshopify.com").describeWriteConfig();
        assertThat(d).contains("mode=write: all stores").contains("exclude=[a.myshopify.com, b.myshopify.com]")
            .contains("max(20 units, 10% of Shopify on_hand at Main)").contains("24 h");
        MainReconcileService ro = service("all", "");
        ro.setReportOnly(true);
        assertThat(ro.describeWriteConfig()).contains("report-only");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────

    /** A variant with one Traced piece at Main and Shopify at 3 → −2 (under the brake). */
    private V small(T t) {
        V v = variant(t);
        piece(t, v, "available", t.main());
        level(v, 3, 3, 0, 0);
        return v;
    }

    /** A variant with one Traced piece at Main and Shopify at 50 → −49 (over the brake). */
    private V big(T t) {
        V v = variant(t);
        piece(t, v, "available", t.main());
        level(v, 50, 50, 0, 0);
        return v;
    }

    private UUID runV(MainReconcileService s, T t, V... vs) {
        UUID id = runAny(s, t, vs);
        assertThat(runRow(t, id)).containsEntry("status", "completed");
        return id;
    }

    private UUID runAny(MainReconcileService s, T t, V... vs) {
        List<UUID> ids = Arrays.stream(vs).map(V::id).toList();
        UUID id = TenantContext.runAs(t.tenant(), () -> s.runVariants(t.tenant(), ids));
        assertThat(id).isNotNull();
        assertThat(runRow(t, id).get("status")).isIn("completed", "skipped");
        return id;
    }

    private UUID sweep(MainReconcileService s, T t) {
        UUID id = TenantContext.runAs(t.tenant(), () -> s.runSweep(t.tenant()));
        assertThat(id).isNotNull();
        return id;
    }

    private Map<String, Object> runRow(T t, UUID run) {
        return jdbc.queryForMap("SELECT * FROM main_reconcile_runs WHERE id = ? AND tenant_id = ?", run, t.tenant());
    }

    private Map<String, Object> onlyDiff(T t, UUID run) {
        List<Map<String, Object>> d = jdbc.queryForList("SELECT * FROM main_reconcile_diffs WHERE run_id = ? AND tenant_id = ?", run, t.tenant());
        assertThat(d).hasSize(1);
        return d.get(0);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private void level(V v, int onHand, int available, int committed, int damaged) {
        shopifyMain.put(v.item(), new ShopifyGateway.MainLevel(v.item(), true, onHand, available, committed, damaged));
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        long mainNum = 83_000_000L + seq.incrementAndGet();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String mainGid = "gid://shopify/Location/" + mainNum;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", location, tenant, mainGid);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Hoodie', 'active')",
            UUID.randomUUID(), tenant, store, "gid://shopify/Product/" + shop);
        return new T(tenant, store, location, mainGid, user, shop);
    }

    private V variant(T t) {
        long sid = 7_300_000_000L + seq.incrementAndGet();
        String item = "gid://shopify/InventoryItem/" + (9_300_000_000L + sid);
        UUID id = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ? LIMIT 1", UUID.class, t.tenant());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'M', ?, ?)", id, t.tenant(), product, "gid://shopify/ProductVariant/" + sid, "SKU-" + sid, item);
        return new V(id, sid, item);
    }

    private String piece(T t, V v, String status, UUID location) {
        int k = seq.incrementAndGet();
        String id = String.format("01MAINBRAKE%015d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, now() - interval '30 days')",
            id, t.tenant(), v.id(), status, "MB-" + k, String.format("B%07d", k), location);
        return id;
    }

    private UUID order(T t, String raw) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), ?::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Order/MB" + k, "#MB" + k, raw);
    }

    private UUID orderItem(T t, UUID order, V v, int qty) {
        return jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, t.tenant(), order, v.id(), qty);
    }

    private void allocate(T t, UUID orderItem, String piece, String status) {
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
            t.tenant(), orderItem, piece, status);
        UUID order = jdbc.queryForObject("SELECT order_id FROM order_items WHERE id = ?", UUID.class, orderItem);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", order, piece);
    }
}
