package com.traceability.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.*;

/**
 * Main reconcile — lock contention fixes (2026-11-15; prod: 40–97 s lock waits, 11 deadlocks since 2026-11-08 between
 * MainReconcileService.compute's per-variant upsert and MainReconcileQueue.onWebhook's multi-row upsert).
 *
 *   l1 no deadlock: a run upserting 20 variants (handed over in DESCENDING order) and a webhook mark naming the same 20
 *      run at once (rows slowed by a test trigger so they overlap) — both finish: the run completes, every variant has
 *      the webhook's last_event_at. Both lock in ascending variant_id.
 *   l2 a queued run while a sweep runs for the tenant, or while the tenant lock is held (sweep / seed / activation):
 *      returns at once, writes no run row, the variant stays queued; once free it runs and clears the queue
 *   l3 a webhook mark that can't get its row lock in time gives up after lock_timeout (never throws — the webhook event
 *      is processed as usual) and schedules ONE retry; the retry stamps the ORIGINAL event time (never moves a later
 *      stamp back)
 *   l4 compute writes its results in batches: a run over 450 variants holds no row lock while it evaluates, and every
 *      variant's state + diff is written (WRITE_BATCH 200 → 3 transactions)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainReconcileLockingTest {

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

    @Autowired JdbcTemplate jdbc;               // postgres — fixtures only
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    MainReconcileService service;

    final Map<String, ShopifyGateway.MainLevel> shopifyMain = new ConcurrentHashMap<>();
    final AtomicInteger seq = new AtomicInteger();

    record T(UUID tenant, UUID store, UUID main, String mainGid, UUID user) {}
    record V(UUID id, long shopifyId, String item) {
        String gid() { return "gid://shopify/ProductVariant/" + shopifyId; }
    }

    @BeforeAll
    void appUser() {
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
        // l1's slow-row trigger: 40 ms per main_reconcile_variant_state write of the tenant named in the setting.
        jdbc.execute("CREATE OR REPLACE FUNCTION test_slow_state() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " +
            "IF NEW.tenant_id::text = current_setting('test.slow_tenant', true) THEN PERFORM pg_sleep(0.04); END IF; " +
            "RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER test_slow_state BEFORE INSERT OR UPDATE ON main_reconcile_variant_state " +
            "FOR EACH ROW EXECUTE FUNCTION test_slow_state()");
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
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        jdbc.execute("ALTER DATABASE traceability_test RESET test.slow_tenant");
    }

    // ── l1 ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void l1_computeAndWebhookMark_overlappingVariants_noDeadlock() throws Exception {
        T t = tenant("l1");
        List<V> vs = new ArrayList<>();
        for (int i = 0; i < 20; i++) vs.add(variant(t));
        vs.sort(Comparator.comparing(v -> v.id().toString()));
        for (V v : vs) {
            level(v, 0, 0, 0, 0);
            jdbc.update("INSERT INTO main_reconcile_variant_state (tenant_id, variant_id) VALUES (?, ?)", t.tenant(), v.id());
        }
        // Slow every state write of this tenant (new sessions read the database default).
        jdbc.execute("ALTER DATABASE traceability_test SET test.slow_tenant = '" + t.tenant() + "'");

        List<UUID> descending = new ArrayList<>(vs.stream().map(V::id).toList());
        Collections.reverse(descending);
        StringBuilder lines = new StringBuilder();
        for (V v : vs) lines.append(lines.isEmpty() ? "" : ",").append("{\"variant_id\": ").append(v.shopifyId()).append("}");
        JsonNode payload = new ObjectMapper().readTree("{\"line_items\": [" + lines + "]}");
        MainReconcileQueue queue = new MainReconcileQueue(appJdbc, appTxm);
        queue.setLockTimeoutMs(10_000);   // l1 is about the ORDER, not the timeout: wait as long as needed

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> run = pool.submit(() -> TenantContext.runAs(t.tenant(), () -> service.runVariants(t.tenant(), descending)));
            Thread.sleep(300);   // the run is mid-way through its state writes
            Future<?> mark = pool.submit(() -> TenantContext.runAs(t.tenant(),
                (Runnable) () -> queue.onWebhook(t.tenant(), "orders/updated", payload)));
            UUID runId = run.get(60, TimeUnit.SECONDS);
            mark.get(60, TimeUnit.SECONDS);

            assertThat(jdbc.queryForObject("SELECT status FROM main_reconcile_runs WHERE id = ?", String.class, runId))
                .as("run error: %s", jdbc.queryForObject("SELECT error FROM main_reconcile_runs WHERE id = ?", String.class, runId))
                .isEqualTo("completed");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM main_reconcile_variant_state WHERE tenant_id = ? " +
                "AND last_event_source = 'orders/updated' AND last_event_at IS NOT NULL AND last_run_id = ?",
                Integer.class, t.tenant(), runId)).as("every variant: the run's result AND the webhook's mark").isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── l2 ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void l2_queuedRun_skipsWhileTenantBusy_variantStaysQueued_thenRuns() throws Exception {
        T t = tenant("l2");
        V v = variant(t);
        level(v, 0, 0, 0, 0);
        queueVariant(t, v);

        // (a) the tenant lock is held (a sweep / seed / activation in progress): no wait, no run row.
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(hashtext('" + t.tenant() + "')::bigint)");
            }
            long t0 = System.nanoTime();
            UUID r = CompletableFuture.supplyAsync(() -> TenantContext.runAs(t.tenant(), () -> service.runQueued(t.tenant())))
                .get(10, TimeUnit.SECONDS);
            assertThat(r).isNull();
            assertThat((System.nanoTime() - t0) / 1_000_000L).as("ms — never blocked on the lock").isLessThan(3_000);
            assertThat(runCount(t)).isZero();
            assertThat(pending(t, v)).isTrue();
            c.rollback();
        }

        // (b) a sweep of the tenant is running: same.
        UUID sweep = jdbc.queryForObject("INSERT INTO main_reconcile_runs (tenant_id, kind, mode) VALUES (?, 'sweep', 'report_only') " +
            "RETURNING id", UUID.class, t.tenant());
        assertThat(TenantContext.runAs(t.tenant(), () -> service.runQueued(t.tenant()))).isNull();
        assertThat(runCount(t)).isEqualTo(1);
        assertThat(pending(t, v)).isTrue();
        jdbc.update("UPDATE main_reconcile_runs SET status = 'completed', finished_at = now() WHERE id = ?", sweep);

        // (c) free: the queued run runs and takes the variant off the queue.
        UUID r = TenantContext.runAs(t.tenant(), () -> service.runQueued(t.tenant()));
        assertThat(r).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM main_reconcile_runs WHERE id = ?", String.class, r)).isEqualTo("completed");
        assertThat(pending(t, v)).isFalse();
    }

    // ── l3 ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l3_markTimesOut_neverThrows_retryScheduled_retryStampsOriginalEventTime() throws Exception {
        T t = tenant("l3");
        V v = variant(t);
        jdbc.update("INSERT INTO main_reconcile_variant_state (tenant_id, variant_id) VALUES (?, ?)", t.tenant(), v.id());
        JobScheduler scheduler = Mockito.mock(JobScheduler.class);
        MainReconcileQueue queue = new MainReconcileQueue(appJdbc, appTxm);
        queue.setLockTimeoutMs(300);
        queue.setJobScheduler(scheduler);
        JsonNode payload = new ObjectMapper().readTree("{\"line_items\": [{\"variant_id\": " + v.shopifyId() + "}]}");

        Instant before = Instant.now();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {   // e.g. a reconcile batch holding the row
                st.execute("SELECT 1 FROM main_reconcile_variant_state WHERE variant_id = '" + v.id() + "' FOR UPDATE");
            }
            long t0 = System.nanoTime();
            CompletableFuture.runAsync(() -> TenantContext.runAs(t.tenant(),
                (Runnable) () -> queue.onWebhook(t.tenant(), "orders/updated", payload))).get(10, TimeUnit.SECONDS);
            assertThat((System.nanoTime() - t0) / 1_000_000L).as("ms — gave up after the lock timeout").isLessThan(3_000);
            c.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT last_event_at FROM main_reconcile_variant_state WHERE variant_id = ?",
            Timestamp.class, v.id())).as("not stamped yet").isNull();
        verify(scheduler, times(1)).schedule(any(Instant.class), any(IocJobLambda.class));

        // The retry (what the scheduled job runs): the ORIGINAL event time, the variant queued.
        Instant eventAt = before.minusSeconds(120);
        queue.retryMark(t.tenant(), "orders/updated", MainReconcileQueue.KIND_ORDER, List.of(v.gid()), eventAt, 1);
        Map<String, Object> s = jdbc.queryForMap("SELECT * FROM main_reconcile_variant_state WHERE variant_id = ?", v.id());
        assertThat(((Timestamp) s.get("last_event_at")).toInstant()).isEqualTo(eventAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(s.get("pending_since")).isNotNull();

        // A later live mark, then a late retry of an older event: the later stamp stays.
        TenantContext.runAs(t.tenant(), (Runnable) () -> queue.onWebhook(t.tenant(), "orders/updated", payload));
        Timestamp live = jdbc.queryForObject("SELECT last_event_at FROM main_reconcile_variant_state WHERE variant_id = ?",
            Timestamp.class, v.id());
        queue.retryMark(t.tenant(), "orders/updated", MainReconcileQueue.KIND_ORDER, List.of(v.gid()), eventAt.minusSeconds(60), 1);
        assertThat(jdbc.queryForObject("SELECT last_event_at FROM main_reconcile_variant_state WHERE variant_id = ?",
            Timestamp.class, v.id())).isEqualTo(live);
    }

    // ── l4 ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void l4_bigRun_writesEveryVariant_inBatches() {
        T t = tenant("l4");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            V v = variant(t);
            level(v, 1, 1, 0, 0);   // Traced holds none → delta −1, an 'unconfirmed' diff for each
            ids.add(v.id());
        }
        UUID run = TenantContext.runAs(t.tenant(), () -> service.runVariants(t.tenant(), ids));
        assertThat(jdbc.queryForObject("SELECT status FROM main_reconcile_runs WHERE id = ?", String.class, run)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM main_reconcile_variant_state WHERE tenant_id = ? AND last_run_id = ?",
            Integer.class, t.tenant(), run)).isEqualTo(450);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM main_reconcile_diffs WHERE tenant_id = ? AND run_id = ? " +
            "AND status = 'unconfirmed' AND delta = -1", Integer.class, t.tenant(), run)).isEqualTo(450);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────────────────

    private void queueVariant(T t, V v) {
        jdbc.update("INSERT INTO main_reconcile_variant_state (tenant_id, variant_id, pending_since, pending_touched_at, pending_source) " +
            "VALUES (?, ?, now() - interval '5 minutes', now() - interval '5 minutes', 'test')", t.tenant(), v.id());
    }

    private boolean pending(T t, V v) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pending_since IS NOT NULL FROM main_reconcile_variant_state " +
            "WHERE tenant_id = ? AND variant_id = ?", Boolean.class, t.tenant(), v.id()));
    }

    private int runCount(T t) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_runs WHERE tenant_id = ?", Integer.class, t.tenant());
    }

    private void level(V v, int onHand, int available, int committed, int damaged) {
        shopifyMain.put(v.item(), new ShopifyGateway.MainLevel(v.item(), true, onHand, available, committed, damaged));
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        long mainNum = 80_000_000L + seq.incrementAndGet();
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
        return new T(tenant, store, location, mainGid, user);
    }

    private V variant(T t) {
        long sid = 7_100_000_000L + seq.incrementAndGet();
        String item = "gid://shopify/InventoryItem/" + (9_100_000_000L + sid);
        return insertVariant(t, sid, item);
    }

    private V insertVariant(T t, long sid, String item) {
        UUID id = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ? LIMIT 1", UUID.class, t.tenant());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", id, t.tenant(), product, "gid://shopify/ProductVariant/" + sid, "SKU-" + sid, item);
        return new V(id, sid, item);
    }

}
