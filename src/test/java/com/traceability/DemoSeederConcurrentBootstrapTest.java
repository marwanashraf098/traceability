package com.traceability;

import com.traceability.demo.DemoBootstrapStartupListener;
import com.traceability.demo.DemoSeeder;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-DEMO hotfix — DEFECT 1: ensureBootstrapped() was a non-atomic check-then-insert
 * (TOCTOU race between DemoBootstrapStartupListener and DemoReseedJob's cron tick, both
 * calling it on the same boot). Confirmed prod incident: both passed the EXISTS check
 * against an empty table, both INSERTed, the loser hit a duplicate-key exception on
 * tenants_pkey, uncaught, which (defect 2) aborted the whole application context.
 *
 * This class proves the fix at the DB level: fixed ids + ON CONFLICT (id) DO NOTHING make a
 * concurrent second caller no-op instead of throwing, regardless of how the race interleaves.
 *
 * DemoBootstrapStartupListener is @MockBean'd to keep the database genuinely empty at context
 * startup — these tests exercise DemoSeeder.ensureBootstrapped() directly, not the listener.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoSeederConcurrentBootstrapTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean JobScheduler                 jobScheduler;
    @MockBean ShopifyGateway               shopifyGateway;
    @MockBean ShopifyTokenProvider         tokenProvider;
    // Neutralizes the startup listener so the DB stays genuinely empty until each test drives
    // ensureBootstrapped() itself.
    @MockBean DemoBootstrapStartupListener demoBootstrapStartupListener;

    @Autowired JdbcTemplate  jdbc;
    @Autowired DemoSeeder    demoSeeder;

    // -----------------------------------------------------------------------
    // (a) two threads call ensureBootstrapped() simultaneously against an empty DB.
    //     Regardless of how the race interleaves, exactly one demo tenant must exist
    //     afterward and NEITHER call may throw.
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    void concurrentInvocations_exactlyOneDemoTenant_noExceptionPropagates() throws Exception {
        Long before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE is_demo = true", Long.class);
        assertThat(before).as("premise: DB is cold before this test").isZero();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Void>> futures = List.of(
                pool.submit(() -> raceInvoke(bothReady, go)),
                pool.submit(() -> raceInvoke(bothReady, go)));

        bothReady.await(10, TimeUnit.SECONDS);
        go.countDown();

        // get() re-throws any exception the task captured — this is the actual proof that
        // neither call let an exception escape.
        for (Future<Void> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Long tenantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE is_demo = true", Long.class);
        assertThat(tenantCount).as("exactly one demo tenant after the race").isEqualTo(1L);

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount).as("exactly one owner, not two").isEqualTo(1L);

        Long workerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'worker'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(workerCount).as("exactly two workers, not four").isEqualTo(2L);

        // 2 fixed-id locations (DEMO_LOCATION_ID + DEMO_DESTINATION_LOCATION_ID) —
        // exactly 2, not 4, proves the race didn't double either one.
        Long locationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ?",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(locationCount).as("exactly two locations, not four").isEqualTo(2L);
    }

    private Void raceInvoke(CountDownLatch bothReady, CountDownLatch go) throws Exception {
        bothReady.countDown();
        go.await();
        demoSeeder.ensureBootstrapped(); // must never throw, win or lose the race
        return null;
    }

    // -----------------------------------------------------------------------
    // (b) sequential re-run: calling it twice more (now that it exists) still leaves
    //     exactly one tenant, no error — existing idempotent behavior preserved.
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    void sequentialReRun_stillExactlyOneTenant_noError() {
        demoSeeder.ensureBootstrapped();
        demoSeeder.ensureBootstrapped();

        Long tenantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE is_demo = true", Long.class);
        assertThat(tenantCount).isEqualTo(1L);

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount).isEqualTo(1L);
    }
}
