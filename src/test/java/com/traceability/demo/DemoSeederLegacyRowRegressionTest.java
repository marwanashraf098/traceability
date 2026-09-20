package com.traceability.demo;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-DEMO — confirmed prod incident: /demo/start 500s ("Incorrect result size: expected 1,
 * actual 0") because DemoSeeder.resolveOwnerId() finds no row for the demo tenant. Root cause
 * traced to a tenant row created by the ORIGINAL (pre-idempotency) ensureBootstrapped(), which
 * wrote {@code is_demo} via a SEPARATE UPDATE after the tenant INSERT rather than in the same
 * statement — a crash between those two round-trips leaves {@code is_demo=false} permanently,
 * since nothing ever revisits an existing tenant row.
 *
 * Two things this class proves:
 *   (a) against a genuinely empty DB, three back-to-back ensureBootstrapped() calls converge
 *       on exactly one correct row set — the current fixed-id + bare ON CONFLICT DO NOTHING
 *       design is sound for the happy path, repeated any number of times.
 *   (b) against a PRE-EXISTING malformed tenant row (the legacy shape: is_demo=false, no
 *       owner/workers/location under it — exactly what a crash-mid-sequence under the OLD code
 *       would leave), ensureBootstrapped() does NOT repair it and does NOT throw. It is a
 *       silent, permanent no-op: the outer EXISTS(tenants.id=...) gate returns true for ANY
 *       existing tenant row regardless of correctness, so insertDemoFixtureIdempotent() (and
 *       therefore its ON CONFLICT DO NOTHING statements) never even runs. This is the test that
 *       proves delete+rebuild is the ONLY repair path for a row already in this state — calling
 *       ensureBootstrapped() again, any number of times, changes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoSeederLegacyRowRegressionTest {

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
    // Keeps the DB genuinely empty at context startup — each test seeds its own precondition.
    @MockBean DemoBootstrapStartupListener demoBootstrapStartupListener;

    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeeder   demoSeeder;

    @BeforeEach
    void cleanDemoTenant() {
        jdbc.update("DELETE FROM users     WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        jdbc.update("DELETE FROM tenants   WHERE id = ?", DemoSeeder.DEMO_TENANT_ID);
    }

    // -----------------------------------------------------------------------
    // (a) three calls against an empty DB converge on exactly one correct row set.
    // -----------------------------------------------------------------------
    @Test
    void calledThreeTimesAgainstEmptyDb_exactlyOneCorrectRowSet() {
        assertThatCode(() -> {
            demoSeeder.ensureBootstrapped();
            demoSeeder.ensureBootstrapped();
            demoSeeder.ensureBootstrapped();
        }).doesNotThrowAnyException();

        Boolean isDemo = jdbc.queryForObject(
                "SELECT is_demo FROM tenants WHERE id = ?", Boolean.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(isDemo).as("tenant.is_demo").isTrue();

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount).as("owner count").isEqualTo(1L);

        Long workerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'worker'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(workerCount).as("worker count").isEqualTo(2L);

        Long locationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ?",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(locationCount).as("location count").isEqualTo(1L);
    }

    // -----------------------------------------------------------------------
    // (b) a pre-existing malformed legacy row (is_demo=false, nothing underneath) is NOT
    //     repaired by ensureBootstrapped() — proving delete+rebuild is mandatory, not optional.
    // -----------------------------------------------------------------------
    @Test
    void preExistingMalformedTenant_isNotRepaired_remainsBrokenForever() {
        // The exact legacy shape: tenant row exists (is_demo defaulted false, as the OLD
        // two-step create-then-UPDATE code would leave it after a crash between the two
        // round-trips), with no owner/workers/location ever created under it.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Traced Demo Store')",
                DemoSeeder.DEMO_TENANT_ID);

        assertThatCode(() -> demoSeeder.ensureBootstrapped()).doesNotThrowAnyException();

        // Calling it again (and again) changes nothing — the outer EXISTS(tenants.id=...) gate
        // returns true for this row regardless of correctness, so insertDemoFixtureIdempotent()
        // — and therefore every ON CONFLICT DO NOTHING statement in it — never runs at all.
        demoSeeder.ensureBootstrapped();
        demoSeeder.ensureBootstrapped();

        Boolean isDemo = jdbc.queryForObject(
                "SELECT is_demo FROM tenants WHERE id = ?", Boolean.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(isDemo)
                .as("is_demo stays false forever — ensureBootstrapped() cannot repair an existing "
                        + "row, no matter how many times it's called; delete+rebuild is the only fix")
                .isFalse();

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount).as("no owner is ever created for a tenant row that already exists")
                .isZero();

        Long workerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'worker'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(workerCount).as("no workers are ever created either").isZero();

        Long locationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ?",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(locationCount).as("no location is ever created either").isZero();

        // This is exactly the shape that would make resolveOwnerId() throw
        // IncorrectResultSizeDataAccessException("expected 1, actual 0") — the confirmed prod 500.
        assertThatThrownBy(demoSeeder::resolveOwnerId)
                .as("resolveOwnerId() throws against this row — matches the confirmed prod 500")
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
    }
}
