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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FR-DEMO hotfix — confirmed prod incident: reseed() threw an FK violation inserting
 * transfers.destination_location_id against the demo tenant, because that tenant was
 * bootstrapped BEFORE the second location (DEMO_DESTINATION_LOCATION_ID / "Zamalek
 * Showroom") existed in {@code insertDemoFixtureIdempotent()}. ensureBootstrapped()'s
 * fast path ({@code EXISTS(tenants.id = DEMO_TENANT_ID)}) returns early for an
 * already-existing tenant, so a location added to bootstrap code AFTER that tenant was
 * first created never retroactively appears on it — reseed() then tried to load
 * transfers pointing at a location row that was never created.
 *
 * Fix: {@code loadGoldenFixture()} now idempotently INSERTs the second location itself
 * (fixed id, ON CONFLICT DO NOTHING), unconditionally on every reseed — never gated
 * behind ensureBootstrapped()'s fast path. Since {@code deleteMutableRows()} never
 * deletes {@code locations}, this self-heals a legacy tenant on its very next reseed and
 * is a no-op on every reseed after that.
 *
 * This test reproduces the incident directly: raw-insert the exact legacy shape (tenant
 * + owner + 2 workers + Main Warehouse ONLY — no second location, no fixture rows at
 * all — never calling ensureBootstrapped(), so today's already-fixed bootstrap-time
 * insert cannot mask the bug under test), then call reseed() and assert it does not
 * throw, the second location now exists, and the seeded transfers reference it.
 * REVERT-TO-CONFIRM: removing the self-heal insert from loadGoldenFixture() reproduces
 * the exact prod FK violation here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoSeederLegacyLocationBackfillTest {

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
    // Keeps the DB genuinely empty at context startup — this test seeds its own precondition
    // and must never see the real ensureBootstrapped() run automatically at boot.
    @MockBean DemoBootstrapStartupListener demoBootstrapStartupListener;

    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeeder   demoSeeder;

    @BeforeEach
    void cleanDemoTenant() {
        jdbc.update("DELETE FROM users     WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        jdbc.update("DELETE FROM tenants   WHERE id = ?", DemoSeeder.DEMO_TENANT_ID);
    }

    @Test
    void reseedOnLegacyTenant_missingSecondLocation_selfHeals_noFkViolation() {
        // The exact legacy shape: tenant + owner + 2 workers + Main Warehouse only — a tenant
        // bootstrapped before DEMO_DESTINATION_LOCATION_ID existed. Raw-inserted directly
        // (never via ensureBootstrapped()) so today's already-fixed bootstrap-time insert
        // cannot mask the bug this test targets — only loadGoldenFixture()'s own self-heal
        // is under test here.
        jdbc.update("INSERT INTO tenants (id, name, is_demo) VALUES (?, 'Traced Demo Store', true)",
                DemoSeeder.DEMO_TENANT_ID);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                "VALUES (?, ?, 'Demo Owner', 'demo-owner@tracedtech.invalid', 'x', 'owner')",
                UUID.randomUUID(), DemoSeeder.DEMO_TENANT_ID);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, pin_code, role) " +
                "VALUES (?, ?, 'Aya (Demo Worker)', 'x', 'worker')",
                UUID.randomUUID(), DemoSeeder.DEMO_TENANT_ID);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, pin_code, role) " +
                "VALUES (?, ?, 'Karim (Demo Worker)', 'x', 'worker')",
                UUID.randomUUID(), DemoSeeder.DEMO_TENANT_ID);
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
                UUID.randomUUID(), DemoSeeder.DEMO_TENANT_ID);

        Long locationsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ?",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(locationsBefore).as("legacy precondition: exactly 1 location before reseed")
                .isEqualTo(1L);

        // (a) must NOT throw an FK violation — this is the confirmed prod incident.
        assertThatCode(() -> demoSeeder.reseed())
                .as("reseed() must self-heal the missing second location, not FK-violate on it")
                .doesNotThrowAnyException();

        // (b) the second location now exists.
        Long destinationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND name = 'Zamalek Showroom'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(destinationCount).as("Zamalek Showroom self-healed on reseed").isEqualTo(1L);

        Long totalLocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ?",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(totalLocations).as("Main Warehouse survives untouched, plus the healed location")
                .isEqualTo(2L);

        // (c) the seeded transfers reference it successfully.
        Long transfersReferencingIt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers t " +
                "JOIN locations l ON l.id = t.destination_location_id " +
                "WHERE t.tenant_id = ? AND l.name = 'Zamalek Showroom'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(transfersReferencingIt)
                .as("both seeded transfers point at the self-healed destination location")
                .isEqualTo(2L);

        // Reseeding again on this now-healed tenant must stay a no-op for the location row —
        // no duplicate, no exception.
        assertThatCode(() -> demoSeeder.reseed()).doesNotThrowAnyException();
        Long totalLocationsAfterSecondReseed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ?",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(totalLocationsAfterSecondReseed)
                .as("second reseed on the now-healed tenant does not duplicate the location")
                .isEqualTo(2L);
    }
}
