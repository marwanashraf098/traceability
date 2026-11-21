package com.traceability;

import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Incident 2026-11-21 hotfix — the shipped_without_scan detector runs under its own statement cap (SET LOCAL inside a
 * savepoint), once at a time per tenant, behind exceptions.shipped-without-scan.enabled. Runs as app_user (RLS), like
 * production. The slow query is simulated by a pg_sleep inside the real capped path (real Postgres 57014).
 *
 *   t1  the query times out → no shipped_without_scan rows, every other type still returned, the transaction carries
 *       on, and statement_timeout is back to what it was
 *   t2  the query answers normally → its rows are returned (and the cap is lifted afterwards)
 *   t3  flag off → the query is never run, the other types still returned
 *   t4  a second concurrent run for the same tenant does not run the query (no rows from it), the first one still does
 *   t5  the real query (no hook) runs clean under the cap on an empty tenant
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShippedWithoutScanCapTest {

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

    @Autowired JdbcTemplate jdbc;   // postgres, fixtures only
    @MockBean  JobScheduler jobScheduler;

    ExceptionService    svc;
    JdbcTemplate        appJdbc;
    TransactionTemplate appTx;
    Function<UUID, List<Map<String, Object>>> realQuery;
    UUID tenantId, storeId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'CapTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'cap-test.myshopify.com', 'disconnected')", storeId, tenantId);
        // another exception type: a blocked-customer order (blocked_customer)
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
                    "    on_hold, hold_reason) " +
                    "VALUES (?, ?, 'CAP-HOLD-1', '#CAP-1', 'new'::order_status, 'cod', now(), true, 'Blocked customer')",
                    tenantId, storeId);

        TenantAwareDataSource ds = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(ds);
        appTx   = new TransactionTemplate(new DataSourceTransactionManager(ds));
        appTx.setReadOnly(true);
        svc = new ExceptionService(appJdbc, Clock.systemUTC(),
                new ShipmentLinkService(appJdbc, null, null, null, null, null, null, null, null));
        realQuery = hook();
    }

    @BeforeEach void ctx() {
        TenantContext.set(tenantId);
        svc.setShippedWithoutScanEnabled(true);
        svc.setShippedWithoutScanTimeoutMs(3000);
        setHook(realQuery);
    }
    @AfterEach void clear() { TenantContext.clear(); }

    @SuppressWarnings("unchecked")
    Function<UUID, List<Map<String, Object>>> hook() {
        return (Function<UUID, List<Map<String, Object>>>) ReflectionTestUtils.getField(svc, "shippedWithoutScanQuery");
    }
    void setHook(Function<UUID, List<Map<String, Object>>> f) { ReflectionTestUtils.setField(svc, "shippedWithoutScanQuery", f); }

    static Map<String, Object> fakeRow() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "shipped_without_scan"); m.put("severity", "HIGH"); m.put("subject_type", "shipment");
        m.put("order_number", "#CAP-9"); m.put("tracking_number", "697507445"); m.put("old_tracking_number", "6855433207");
        m.put("subject_key", "shipped_without_scan:x");
        return m;
    }

    static List<String> types(List<Map<String, Object>> all) {
        return all.stream().map(e -> (String) e.get("type")).toList();
    }

    @Test
    void t1_timeout_dropsOnlyItsRows_transactionCarriesOn_capLifted() {
        svc.setShippedWithoutScanTimeoutMs(200);
        setHook(tid -> {
            appJdbc.queryForList("SELECT pg_sleep(5)");
            return List.of(fakeRow());
        });
        long t0 = System.nanoTime();
        Object[] out = appTx.execute(s -> {
            String before = appJdbc.queryForObject("SHOW statement_timeout", String.class);
            List<Map<String, Object>> all = svc.detectAllOpen();
            String after = appJdbc.queryForObject("SHOW statement_timeout", String.class);
            Integer stillUsable = appJdbc.queryForObject("SELECT 1", Integer.class);
            return new Object[]{all, before, after, stillUsable};
        });
        long ms = (System.nanoTime() - t0) / 1_000_000;
        @SuppressWarnings("unchecked") List<Map<String, Object>> all = (List<Map<String, Object>>) out[0];
        assertThat(types(all)).contains("blocked_customer").doesNotContain("shipped_without_scan");
        assertThat(out[2]).isEqualTo(out[1]);
        assertThat(out[3]).isEqualTo(1);
        assertThat(ms).isLessThan(4000);   // never the 5 s sleep
    }

    @Test
    void t2_normalAnswer_rowsReturned_capLifted() {
        setHook(tid -> {
            String inside = appJdbc.queryForObject("SHOW statement_timeout", String.class);
            assertThat(inside).isEqualTo("3s");
            return new ArrayList<>(List.of(fakeRow()));
        });
        Object[] out = appTx.execute(s -> {
            String before = appJdbc.queryForObject("SHOW statement_timeout", String.class);
            List<Map<String, Object>> all = svc.detectAllOpen();
            return new Object[]{all, before, appJdbc.queryForObject("SHOW statement_timeout", String.class)};
        });
        @SuppressWarnings("unchecked") List<Map<String, Object>> all = (List<Map<String, Object>>) out[0];
        assertThat(types(all)).contains("blocked_customer", "shipped_without_scan");
        assertThat(out[2]).isEqualTo(out[1]);
    }

    @Test
    void t3_flagOff_neverRuns() {
        AtomicInteger calls = new AtomicInteger();
        setHook(tid -> { calls.incrementAndGet(); return List.of(fakeRow()); });
        svc.setShippedWithoutScanEnabled(false);
        List<Map<String, Object>> all = appTx.execute(s -> svc.detectAllOpen());
        assertThat(calls).hasValue(0);
        assertThat(types(all)).contains("blocked_customer").doesNotContain("shipped_without_scan");
    }

    @Test
    void t4_oneAtATimePerTenant() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        setHook(tid -> {
            calls.incrementAndGet();
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new ArrayList<>(List.of(fakeRow()));
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<Map<String, Object>>> first = pool.submit(() ->
                    TenantContext.runAs(tenantId,
                            (Callable<List<Map<String, Object>>>) () -> appTx.execute(s -> svc.detectAllOpen())));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            List<Map<String, Object>> second = appTx.execute(s -> svc.detectAllOpen());
            release.countDown();
            assertThat(types(second)).contains("blocked_customer").doesNotContain("shipped_without_scan");
            assertThat(types(first.get(10, TimeUnit.SECONDS))).contains("blocked_customer", "shipped_without_scan");
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        // and the guard is released afterwards
        assertThat(types(appTx.execute(s -> svc.detectAllOpen()))).contains("shipped_without_scan");
    }

    @Test
    void t5_realQuery_runsCleanUnderTheCap() {
        List<Map<String, Object>> all = appTx.execute(s -> svc.detectAllOpen());
        assertThat(types(all)).contains("blocked_customer").doesNotContain("shipped_without_scan");
    }
}
