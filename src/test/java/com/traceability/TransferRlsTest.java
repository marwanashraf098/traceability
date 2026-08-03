package com.traceability;

import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-22.1 — table-level RLS proof for the new `transfers` table (V64).
 *
 * No TransferService exists yet (FR-22.3+) — this test writes/reads the table
 * directly via SQL, exactly like Day10Test's (e)/(e-pos) pair, to prove the
 * tenant_isolation policy added in V64 actually fires. RlsCoverageTest cannot
 * cover this: it audits GET endpoints (none exist yet for transfers) and runs
 * BYPASSRLS, so it would never catch a missing/broken policy here.
 *
 * Pattern mirrors InventoryLedgerTest's app_user harness (PER_CLASS + static
 * container start + DynamicPropertySource) and Day10Test's cross-tenant
 * negative paired with a same-tenant positive control (without the positive
 * control, a broken policy that returns nothing for EVERYONE would still
 * pass the negative test — a false green).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferRlsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    // ---- Spring bean (postgres role, BYPASSRLS) — fixture setup only ----
    @Autowired JdbcTemplate jdbc;

    // ---- app_user infrastructure (built in @BeforeAll after TestSetup fires) ----
    JdbcTemplate         appUserJdbc;
    TransactionTemplate  appUserTx;

    UUID tenantAId, tenantBId;
    UUID ownerAId;
    UUID locationAId;
    UUID transferAId;

    @BeforeAll
    void setupFixtures() {
        tenantAId   = UUID.randomUUID();
        tenantBId   = UUID.randomUUID();
        ownerAId    = UUID.randomUUID();
        locationAId = UUID.randomUUID();
        transferAId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Transfer RLS Tenant A')", tenantAId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Transfer RLS Tenant B')", tenantBId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner A', 'ownera@transfer-rls-test.com', 'h', 'owner')",
            ownerAId, tenantAId);
        // Destination location for the transfer — external (is_fulfillment=false),
        // reusing the existing 'showroom' location_type value (no ALTER TYPE needed).
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Downtown Showroom', 'showroom', false, false)",
            locationAId, tenantAId);
        jdbc.update(
            "INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, created_by) " +
            "VALUES (?, ?, 'showroom', ?, ?)",
            transferAId, tenantAId, locationAId, ownerAId);

        // app_user datasource — TestSetup (ApplicationReadyEvent) has already set 'testpw'.
        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        appUserTx.setReadOnly(true);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // -----------------------------------------------------------------------
    // Cross-tenant negative: tenant B must not see tenant A's transfer.
    // -----------------------------------------------------------------------

    @Test
    void crossTenant_cannotSelectOtherTenantsTransfer_viaRls() {
        TenantContext.set(tenantBId);

        List<Map<String, Object>> rows = appUserTx.execute(status ->
                appUserJdbc.queryForList("SELECT id FROM transfers WHERE id = ?", transferAId));

        assertThat(rows)
                .as("tenant B must not see tenant A's transfer row under RLS")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Same-tenant positive control: tenant A must still see its own transfer.
    // Without this, a broken policy hiding the row from EVERYONE (including
    // its own tenant) would make the negative test above a false green.
    // -----------------------------------------------------------------------

    @Test
    void sameTenant_positiveControl_canSelectOwnTransfer_viaRls() {
        TenantContext.set(tenantAId);

        List<Map<String, Object>> rows = appUserTx.execute(status ->
                appUserJdbc.queryForList("SELECT id FROM transfers WHERE id = ?", transferAId));

        assertThat(rows)
                .as("tenant A must see its own transfer row under RLS")
                .hasSize(1);
    }
}
