package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyCasStaleException;
import com.traceability.integrations.shopify.ShopifyException;
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
 * S3a interim guard (approved 2026-11-06): Main reconcile must not write for a tenant holding a Shopify-native exchange
 * replacement — an open shadow order ('internal:shopify-exchange:'), or a Shopify-native exchange's internal:exchange:
 * replacement piece in Traced custody ({@link MainReconcileRules#EXCHANGE_HOLD_SQL}). Until S3c puts shadow pieces in
 * the target, such a write is a spurious +1 / −1 pair or a real double −1. Real service, app_user connection (RLS).
 *
 *   h1  write store + native replacement in custody: every difference is recorded skipped 'shopify_exchange_in_custody'
 *       (delta kept for the report), no reconcile write, the queued Part 1 claim is NOT sent; once the replacement leaves
 *       custody the hold clears — Part 1 is sent once and the difference is written after the usual two runs
 *   h2  an open shadow order holds; a delivered (finished) shadow doesn't
 *   h3  a dashboard exchange that is NOT Shopify-native (no -R reference) never holds — Traced-made exchanges reconcile
 *   h4  report-only store: nothing changes (its diffs are confirmed / unconfirmed exactly as before — no hold)
 *   h6  Traced's own exchanges never hold (Dolt.eg / Nuvira write today and must keep writing): a portal Bosta type 30
 *       (exchanges row WITH return_request_id — even with a -R-looking reference), a portal generic exchange
 *       (internal:exchange:rr:, named by return_requests.replacement_order_id, no exchanges row) and a Lookup exchange
 *       (origin 'lookup', same rr shape), each with its replacement piece reserved in custody → reconcile still writes
 *   h5  cross-tenant: tenant B's held replacement never holds tenant A (A writes); A's own does (same-tenant control)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainReconcileExchangeHoldTest {

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
    @Autowired ObjectMapper mapper;
    @Autowired ExceptionService exceptionService;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;

    final Map<String, ShopifyGateway.MainLevel> shopifyMain = new HashMap<>();
    /** A one-shot answer for the SECOND next fetchMainLevels call (the fresh read before a write — the run's own read comes first). */
    final Deque<ShopifyGateway.MainLevel> nextRead = new ArrayDeque<>();
    final AtomicInteger readsBeforeOverride = new AtomicInteger();
    final AtomicInteger seq = new AtomicInteger();

    record T(UUID tenant, UUID store, UUID main, String mainGid, UUID user, String shop) {
        String mainNum() { return mainGid.replaceAll("^.*/", ""); }
    }
    record V(UUID id, long shopifyId, String item) {}

    @BeforeAll
    void appUser() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTxm = new DataSourceTransactionManager(appDs);
    }

    MainReconcileService service(String writeShops, String part1Since) {
        MainReconcileService s = new MainReconcileService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            new StoreRepository(appJdbc, appTxm));
        s.setReportOnly(false);
        s.setWriteShops(writeShops);
        s.setPart1Since(part1Since);
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
        nextRead.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopifyGateway.fetchMainLevels(any(), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<ShopifyGateway.MainLevel> out = new ArrayList<>();
            if (!nextRead.isEmpty() && readsBeforeOverride.getAndDecrement() <= 0) { out.add(nextRead.poll()); return out; }
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

    static final String HOLD = MainReconcileRules.SHOPIFY_EXCHANGE_IN_CUSTODY;

    @Test
    void h1_nativeReplacementInCustody_noWrites_part1Waits_thenClears() {
        T t = tenant("h1");
        V v = variant(t), w = variant(t), u = variant(t);
        String replacement = nativeReplacement(t, v, "SHOP:#1001-R1");
        level(v, 1, 0, 1, 0);                               // Shopify: the unit is on the original's exchange line
        piece(t, w, "available", t.main());
        level(w, 3, 3, 0, 0);                               // a real difference: −2
        shipped(t, u, "9101", "91011");                     // a Part 1 claim to send
        MainReconcileService s = service("all", CUTOFF);

        run(s, t, v, w, u);
        UUID r2 = run(s, t, v, w, u);
        assertThat(runRow(t, r2)).containsEntry("mode", "write").containsEntry("writes_attempted", 0);
        assertThat(onlyDiffFor(t, r2, w)).containsEntry("status", "skipped").containsEntry("skip_reason", HOLD)
            .containsEntry("delta", -2);
        assertThat(String.valueOf(runRow(t, r2).get("skipped_by_reason"))).contains(HOLD);
        assertThat(p1Status(t, "9101")).as("Part 1 waits while held").isEqualTo("queued");
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).pushPart1MainDecrement(any(), any(), any(), any(), anyInt(), any(), any());

        // The replacement leaves custody (handed to the courier) and Shopify fulfils the exchange line: hold clears.
        jdbc.update("UPDATE pieces SET status = 'with_courier' WHERE id = ?", replacement);
        jdbc.update("UPDATE allocations SET status = 'packed' WHERE piece_id = ?", replacement);
        level(v, 0, 0, 0, 0);
        run(s, t, v, w, u);
        UUID r4 = run(s, t, v, w, u);
        assertThat(p1Status(t, "9101")).isEqualTo("applied");
        verify(shopifyGateway, times(1)).pushPart1MainDecrement(any(), any(), eq(u.item()), eq(t.mainGid()), eq(-1), any(), any());
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(w.item()), eq(t.mainGid()), eq(-2), anyInt(), any(), any());
        assertThat(diffs(t, r4).stream().map(d -> d.get("skip_reason")).toList()).doesNotContain(HOLD);
    }

    @Test
    void h2_openShadowOrderHolds_finishedShadowDoesNot() {
        T t = tenant("h2");
        V v = variant(t), w = variant(t);
        UUID shadow = shadowOrder(t, v, "new");
        piece(t, w, "available", t.main());
        level(w, 3, 3, 0, 0);
        MainReconcileService s = service("all", CUTOFF);

        run(s, t, w);
        UUID r2 = run(s, t, w);
        assertThat(onlyDiff(t, r2)).containsEntry("status", "skipped").containsEntry("skip_reason", HOLD);
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());

        jdbc.update("UPDATE orders SET status = 'delivered'::order_status WHERE id = ?", shadow);
        run(s, t, w);
        run(s, t, w);
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(w.item()), eq(t.mainGid()), eq(-2), anyInt(), any(), any());
    }

    @Test
    void h3_nonNativeDashboardExchange_neverHolds() {
        T t = tenant("h3");
        V v = variant(t), w = variant(t);
        nativeReplacement(t, v, "SHOP:#1001");              // no -R<digits>: a dashboard exchange Traced reconciles
        level(v, 1, 0, 1, 0);
        piece(t, w, "available", t.main());
        level(w, 3, 3, 0, 0);
        MainReconcileService s = service("all", CUTOFF);
        run(s, t, v, w);
        run(s, t, v, w);
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(w.item()), eq(t.mainGid()), eq(-2), anyInt(), any(), any());
    }

    @Test
    void h4_reportOnlyStore_unchanged() {
        T t = tenant("h4");
        V v = variant(t), w = variant(t);
        nativeReplacement(t, v, "SHOP:#1001-R1");
        level(v, 1, 0, 1, 0);
        piece(t, w, "available", t.main());
        level(w, 3, 3, 0, 0);
        MainReconcileService s = service("", CUTOFF);        // no write shops: report-only
        run(s, t, v, w);
        UUID r2 = run(s, t, v, w);
        assertThat(runRow(t, r2)).containsEntry("mode", "report_only");
        assertThat(onlyDiffFor(t, r2, w)).containsEntry("status", "confirmed").containsEntry("delta", -2);
        assertThat(onlyDiffFor(t, r2, w).get("skip_reason")).isNull();
    }

    @Test
    void h5_crossTenant_otherTenantsReplacementNeverHolds_appUserRls() {
        T a = tenant("h5a"), b = tenant("h5b");
        V va = variant(a), vb = variant(b);
        nativeReplacement(b, vb, "SHOP:#2001-R1");
        level(vb, 1, 0, 1, 0);
        piece(a, va, "available", a.main());
        level(va, 3, 3, 0, 0);
        MainReconcileService s = service("all", CUTOFF);
        run(s, a, va);
        run(s, a, va);
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(va.item()), eq(a.mainGid()), eq(-2), anyInt(), any(), any());

        // The hold query itself under app_user + RLS: B's own context sees it (control); A's context never sees B's rows.
        TransactionTemplate tx = new TransactionTemplate(appTxm);
        Boolean own = TenantContext.runAs(b.tenant(), () -> tx.execute(st ->
            appJdbc.queryForObject(MainReconcileRules.EXCHANGE_HOLD_SQL, Boolean.class, b.tenant(), b.tenant())));
        Boolean cross = TenantContext.runAs(a.tenant(), () -> tx.execute(st ->
            appJdbc.queryForObject(MainReconcileRules.EXCHANGE_HOLD_SQL, Boolean.class, b.tenant(), b.tenant())));
        assertThat(own).as("same tenant").isTrue();
        assertThat(cross).as("tenant A's context never sees tenant B's replacement").isFalse();
    }

    @Test
    void h6_portalAndLookupExchangesInCustody_neverHold_reconcileStillWrites() {
        T t = tenant("h6");
        V v = variant(t), w = variant(t);
        UUID original = order(t, "{}");
        orderItem(t, original, v, 1);
        // (a) portal Bosta exchange: a type-30 exchanges row owned by a return request (adversarial -R reference)
        UUID portalReq = request(t, original, "portal", "RR-HXAA");
        String a = nativeReplacement(t, v, "SHOP:#1001-R1");
        jdbc.update("UPDATE exchanges SET return_request_id = ? WHERE tenant_id = ? AND outbound_order_id = " +
            "(SELECT current_order_id FROM pieces WHERE id = ?)", portalReq, t.tenant(), a);
        // (b) portal generic exchange and (c) Lookup exchange: internal:exchange:rr:<request>, no exchanges row
        for (String origin : List.of("portal", "lookup")) {
            UUID req = request(t, original, origin, "portal".equals(origin) ? "RR-HXBB" : "RR-HXCC");
            UUID rr = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
                "VALUES (?, ?, ?, ?, 'confirmed'::order_status, 'cod', now(), '{}'::jsonb) RETURNING id",
                UUID.class, t.tenant(), t.store(), "internal:exchange:rr:" + req, "RR-" + origin);
            jdbc.update("UPDATE return_requests SET replacement_order_id = ? WHERE id = ?", rr, req);
            allocate(t, orderItem(t, rr, v, 1), piece(t, v, "reserved", t.main()), "active");
        }
        level(v, 3, 0, 3, 0);                               // three replacements, all in custody: in sync
        piece(t, w, "available", t.main());
        level(w, 3, 3, 0, 0);                               // a real difference: −2
        assertThat(jdbc.queryForObject(MainReconcileRules.EXCHANGE_HOLD_SQL, Boolean.class, t.tenant(), t.tenant()))
            .as("Traced's own exchanges are not Shopify-native").isFalse();
        MainReconcileService s = service("all", CUTOFF);
        run(s, t, v, w);
        UUID r2 = run(s, t, v, w);
        assertThat(diffs(t, r2).stream().map(d -> d.get("skip_reason")).toList()).doesNotContain(HOLD);
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(w.item()), eq(t.mainGid()), eq(-2), anyInt(), any(), any());
    }

    private UUID request(T t, UUID order, String origin, String reference) {
        return jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference, origin) " +
            "VALUES (?, ?, 'exchange', 'approved', ?, ?) RETURNING id", UUID.class, t.tenant(), order, reference, origin);
    }

    /**
     * A Shopify-native exchange Traced turned into an internal:exchange: replacement (dashboard Bosta type 30 with the
     * Shopify Return name in its reference), its piece reserved at Main. Returns the piece id.
     */
    private String nativeReplacement(T t, V v, String businessReference) {
        int k = seq.incrementAndGet();
        String tn = "88" + String.format("%08d", k);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), '{}'::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "internal:exchange:" + tn, "EXC-" + tn);
        String p = piece(t, v, "reserved", t.main());
        allocate(t, orderItem(t, order, v, 1), p, "active");
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, raw, outbound_order_id, outbound_variant_id, " +
            "    inbound_variant_id) VALUES (?, ?, 'matched', jsonb_build_object('businessReference', ?::text), ?, ?, ?)",
            t.tenant(), tn, businessReference, order, v.id(), v.id());
        return p;
    }

    private UUID shadowOrder(T t, V v, String status) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, origin) " +
            "VALUES (?, ?, ?, '#1001-X1', ?::order_status, 'cod', now(), '{}'::jsonb, 'shopify_exchange') RETURNING id",
            UUID.class, t.tenant(), t.store(), "internal:shopify-exchange:" + UUID.randomUUID(), status);
        orderItem(t, order, v, 1);
        return order;
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

    // ── fixtures (postgres) ───────────────────────────────────────────────────────────────────────────────────

    private UUID run(MainReconcileService s, T t, V... vs) {
        List<UUID> ids = Arrays.stream(vs).map(V::id).toList();
        UUID id = TenantContext.runAs(t.tenant(), () -> s.runVariants(t.tenant(), ids));
        assertThat(id).isNotNull();
        assertThat(runRow(t, id)).containsEntry("status", "completed");
        return id;
    }

    private List<UUID> alerts(T t) {
        return TenantContext.runAs(t.tenant(), () -> exceptionService.detectAllOpen()).stream()
            .filter(a -> "delivered_not_fulfilled_in_shopify".equals(a.get("type")))
            .map(a -> (UUID) a.get("order_id")).toList();
    }

    private Map<String, Object> runRow(T t, UUID run) {
        return jdbc.queryForMap("SELECT * FROM main_reconcile_runs WHERE id = ? AND tenant_id = ?", run, t.tenant());
    }

    private List<Map<String, Object>> diffs(T t, UUID run) {
        return jdbc.queryForList("SELECT * FROM main_reconcile_diffs WHERE run_id = ? AND tenant_id = ?", run, t.tenant());
    }

    private Map<String, Object> onlyDiff(T t, UUID run) {
        List<Map<String, Object>> d = diffs(t, run);
        assertThat(d).hasSize(1);
        return d.get(0);
    }

    private Map<String, Object> onlyDiffFor(T t, UUID run, V v) {
        List<Map<String, Object>> d = diffs(t, run).stream().filter(r -> v.id().equals(r.get("variant_id"))).toList();
        assertThat(d).hasSize(1);
        return d.get(0);
    }

    private Map<String, Object> onlyWrite(T t) {
        List<Map<String, Object>> w = jdbc.queryForList("SELECT * FROM main_reconcile_writes WHERE tenant_id = ?", t.tenant());
        assertThat(w).hasSize(1);
        return w.get(0);
    }

    private List<Map<String, Object>> writesFor(T t, V v) {
        return jdbc.queryForList("SELECT * FROM main_reconcile_writes WHERE tenant_id = ? AND variant_id = ? ORDER BY created_at",
            t.tenant(), v.id());
    }

    private int writeCount(T t) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_writes WHERE tenant_id = ?", Integer.class, t.tenant());
    }

    private String p1Status(T t, String fulfillmentId) {
        return jdbc.queryForObject("SELECT status FROM main_reconcile_part1_claims WHERE tenant_id = ? AND fulfillment_id = ?",
            String.class, t.tenant(), fulfillmentId);
    }

    private String claimStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE id = ?", String.class, id);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> part1(UUID run) {
        try {
            return mapper.readValue(jdbc.queryForObject("SELECT part1_summary::text FROM main_reconcile_runs WHERE id = ?",
                String.class, run), Map.class);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long ambiguousClaim(T t, V v, int minutesAgo) {
        String piece = piece(t, v, "lost", t.main());
        return jdbc.queryForObject("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, " +
            "delta, trigger_type, trigger_id, status, send_started_at, created_at) VALUES (?, ?, ?, ?, -1, 'piece_write_off', ?, " +
            "'failed_ambiguous', now() - make_interval(mins => ?), now() - make_interval(mins => ?)) RETURNING id",
            Long.class, t.tenant(), UUID.randomUUID(), v.id(), t.main(), piece + ":" + seq.incrementAndGet(), minutesAgo, minutesAgo);
    }

    /** A piece Traced shipped for a line Shopify fulfilled from another location (after the cutoff); Main still holds 1. */
    private void shipped(T t, V v, String fulfillmentId, String lineId) {
        UUID o = order(t, fulfillments(fAt(fulfillmentId, "424242", "success", "2026-11-02T08:00:00Z", line(lineId, v, 1))));
        String p = piece(t, v, "with_courier", t.main());
        allocate(t, orderItem(t, o, v, 1), p, "packed");
        leftCustody(t, p, o, "with_courier", 0);
        level(v, 1, 1, 0, 0);
    }

    /** n pieces of v delivered for the order daysAgo days ago (one order line of quantity n). */
    private void delivered(T t, V v, UUID order, int n, int daysAgo) {
        UUID line = orderItem(t, order, v, n);
        for (int i = 0; i < n; i++) {
            String p = piece(t, v, "delivered", t.main());
            allocate(t, line, p, "packed");
            leftCustody(t, p, order, "delivered", daysAgo);
        }
    }

    private void level(V v, int onHand, int available, int committed, int damaged) {
        shopifyMain.put(v.item(), new ShopifyGateway.MainLevel(v.item(), true, onHand, available, committed, damaged));
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        long mainNum = 81_000_000L + seq.incrementAndGet();
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

    private V variant(T t) {
        long sid = 7_200_000_000L + seq.incrementAndGet();
        String item = "gid://shopify/InventoryItem/" + (9_200_000_000L + sid);
        UUID id = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ? LIMIT 1", UUID.class, t.tenant());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", id, t.tenant(), product, "gid://shopify/ProductVariant/" + sid, "SKU-" + sid, item);
        return new V(id, sid, item);
    }

    private String piece(T t, V v, String status, UUID location) {
        int k = seq.incrementAndGet();
        String id = String.format("01MAINWRITE%015d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, now() - interval '30 days')",
            id, t.tenant(), v.id(), status, "MW-" + k, String.format("W%07d", k), location);
        return id;
    }

    private UUID order(T t, String raw) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), ?::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Order/MW" + k, "#MW" + k, raw);
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

    private void leftCustody(T t, String piece, UUID order, String to, int daysAgo) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, actor_user_id, occurred_at) " +
            "VALUES (?, ?, ?, ?, 'packed', ?::piece_status, ?, now() - make_interval(days => ?))",
            t.tenant(), piece, "delivered".equals(to) ? "delivered" : "handed_to_courier", order, to, t.user(), daysAgo);
    }

    // ── fulfillment JSON ──────────────────────────────────────────────────────────────────────────────────────

    private static String line(String id, V v, int qty) {
        return "{\"id\": " + id + ", \"variant_id\": " + v.shopifyId() + ", \"quantity\": " + qty + "}";
    }

    private static String f(String id, String locationNum, String status, String... lines) {
        return fAt(id, locationNum, status, "2026-11-02T08:00:00Z", lines);
    }

    private static String fAt(String id, String locationNum, String status, String createdAt, String... lines) {
        return "{\"id\": " + id + ", \"location_id\": " + locationNum + ", \"status\": \"" + status +
            "\", \"created_at\": \"" + createdAt + "\", \"line_items\": [" + String.join(", ", lines) + "]}";
    }

    private static String fulfillments(String... fs) {
        return "{\"fulfillments\": [" + String.join(", ", fs) + "]}";
    }
}
