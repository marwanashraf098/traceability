package com.traceability;

import com.traceability.demo.DemoBootstrapStartupListener;
import com.traceability.demo.DemoSeeder;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-DEMO hotfix — DemoBootstrapStartupListener closes the "never bootstrapped before the
 * first 30-min DemoReseedJob tick" gap (confirmed prod incident: fresh deploy, no demo
 * tenant, /demo/start 500s until the next cron boundary).
 *
 * Deliberately NO @BeforeAll bootstrap call anywhere in this class — the whole point is to
 * prove the REAL org.springframework.boot.context.event.ApplicationReadyEvent, fired by
 * @SpringBootTest's own context startup, is what creates the demo tenant. Manually invoking
 * the listener a second time (in the idempotency test) proves DemoSeeder.ensureBootstrapped()
 * stays safe to call repeatedly through this new call path too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoBootstrapStartupListenerTest {

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

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate                    jdbc;
    @Autowired DemoBootstrapStartupListener    listener;

    // -----------------------------------------------------------------------
    // (a) fresh DB, no demo tenant. By the time this test method runs, the real
    //     ApplicationReadyEvent has already fired during @SpringBootTest context
    //     startup — this asserts ITS effect, not a manually-triggered call.
    // -----------------------------------------------------------------------
    @Test
    void applicationReadyEvent_bootstrapsDemoTenantOnStartup() {
        Boolean isDemo = jdbc.queryForObject(
                "SELECT is_demo FROM tenants WHERE id = ?", Boolean.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(isDemo).isTrue();

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount).isEqualTo(1L);

        Long workerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'worker'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(workerCount).isEqualTo(2L);
    }

    // -----------------------------------------------------------------------
    // (b) invoked twice (context startup's real firing + one explicit manual call
    //     here) → still exactly one demo tenant, no duplicate.
    // -----------------------------------------------------------------------
    @Test
    void invokedTwice_stillExactlyOneDemoTenant_noDuplicate() {
        listener.onApplicationReady();
        listener.onApplicationReady();

        Long tenantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE is_demo = true", Long.class);
        assertThat(tenantCount).isEqualTo(1L);

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount).isEqualTo(1L);
    }
}
