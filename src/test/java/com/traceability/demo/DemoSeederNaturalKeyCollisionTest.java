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
 * FR-DEMO hotfix — widened ON CONFLICT guards. {@code ON CONFLICT (id) DO NOTHING} only
 * absorbed an id collision; a stale leftover row sharing a NATURAL key (email, or a location's
 * per-tenant name/fulfillment uniqueness) but not the new fixed id still threw — confirmed prod
 * incident on {@code users_email_unique}. Fix: bare {@code ON CONFLICT DO NOTHING} on all five
 * inserts, which absorbs a violation of ANY unique constraint on that table.
 *
 * These tests call {@link DemoSeeder#insertDemoFixtureIdempotent()} directly (package-private
 * for exactly this reason) rather than through the public {@link DemoSeeder#ensureBootstrapped()}
 * gate. That gate's fast path is {@code EXISTS(SELECT 1 FROM tenants WHERE id = DEMO_TENANT_ID)}
 * — and since {@code users.tenant_id}/{@code locations.tenant_id} are
 * {@code NOT NULL REFERENCES tenants(id)}, a colliding users/locations row for DEMO_TENANT_ID
 * can only exist once the tenant row itself does, which is exactly the condition under which
 * ensureBootstrapped() would already skip calling insertDemoFixtureIdempotent() at all. Testing
 * through the public gate would either be unreachable or would pass for the wrong reason (the
 * gate skipping the call, not the ON CONFLICT clause absorbing anything) — calling the
 * package-private method directly is what actually exercises the fix.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoSeederNaturalKeyCollisionTest {

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

    // Both tests share one @TestInstance(PER_CLASS) context/database — each starts from a
    // clean slate for the demo tenant rather than seeing the previous test's leftovers.
    @BeforeEach
    void cleanDemoTenant() {
        jdbc.update("DELETE FROM users     WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", DemoSeeder.DEMO_TENANT_ID);
        jdbc.update("DELETE FROM tenants   WHERE id = ?", DemoSeeder.DEMO_TENANT_ID);
    }

    // -----------------------------------------------------------------------
    // A stale owner row — different id, SAME email as DEMO_OWNER_EMAIL, same tenant —
    // simulating the crashed-race leftover that caused the prod incident.
    // -----------------------------------------------------------------------
    @Test
    void emailCollision_sameTenantDifferentId_doesNotThrow_exactlyOneOwner() {
        jdbc.update("INSERT INTO tenants (id, name, is_demo) VALUES (?, 'Traced Demo Store', true)",
                DemoSeeder.DEMO_TENANT_ID);
        UUID staleOwnerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                "VALUES (?, ?, 'Stale Demo Owner', ?, 'x', 'owner')",
                staleOwnerId, DemoSeeder.DEMO_TENANT_ID, "demo-owner@tracedtech.invalid");

        assertThatCode(() -> demoSeeder.insertDemoFixtureIdempotent()).doesNotThrowAnyException();

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount).as("stale owner survives; no duplicate, no exception").isEqualTo(1L);

        // The two workers and the location don't collide with anything here, so this same
        // "does not throw" call must still finish creating them.
        Long workerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'worker'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(workerCount).isEqualTo(2L);
    }

    // -----------------------------------------------------------------------
    // A stale location row — different id, same tenant + name ("Main Warehouse") +
    // is_fulfillment=true — hitting BOTH locations_name_unique and
    // locations_one_fulfillment_per_tenant, neither of which the old ON CONFLICT (id)
    // targeted.
    // -----------------------------------------------------------------------
    @Test
    void locationCollision_sameTenantSameNameAndFulfillment_doesNotThrow_exactlyOneLocation() {
        jdbc.update("INSERT INTO tenants (id, name, is_demo) VALUES (?, 'Traced Demo Store', true)",
                DemoSeeder.DEMO_TENANT_ID);
        UUID staleLocationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
                staleLocationId, DemoSeeder.DEMO_TENANT_ID);

        assertThatCode(() -> demoSeeder.insertDemoFixtureIdempotent()).doesNotThrowAnyException();

        Long locationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM locations WHERE tenant_id = ?",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(locationCount).as("stale location survives; no duplicate, no exception").isEqualTo(1L);

        Long ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE tenant_id = ? AND role = 'owner'",
                Long.class, DemoSeeder.DEMO_TENANT_ID);
        assertThat(ownerCount).as("owner insert doesn't collide with anything here").isEqualTo(1L);
    }
}
