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
 * FR-24 — table-level RLS proof for the new return_sessions/return_session_items/
 * return_session_shipments tables (V73). Mirrors TransferRlsTest's pattern exactly:
 * raw SQL via the app_user harness (no service-level call needed to prove a table
 * policy), cross-tenant negative paired with a same-tenant positive control per
 * table (without the positive control, a broken policy hiding rows from EVERYONE
 * would still pass the negative test — a false green).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnSessionsRlsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    JdbcTemplate        appUserJdbc;
    TransactionTemplate appUserTx;

    UUID tenantAId, tenantBId, ownerAId, variantAId, storeAId, productAId;
    UUID sessionAId, itemAId, shipmentRowAId;
    String pieceAId;

    @BeforeAll
    void setupFixtures() {
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        ownerAId  = UUID.randomUUID();
        storeAId   = UUID.randomUUID();
        productAId = UUID.randomUUID();
        variantAId = UUID.randomUUID();
        sessionAId = UUID.randomUUID();
        pieceAId   = com.traceability.inventory.UlidGenerator.generate();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Returns RLS Tenant A')", tenantAId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Returns RLS Tenant B')", tenantBId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner A', 'ownera@returns-rls-test.com', 'h', 'owner')",
            ownerAId, tenantAId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'returns-rls.myshopify.com', 'disconnected')", storeAId, tenantAId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'P-RLS', 'RLS Widget', 'active')", productAId, tenantAId, storeAId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'V-RLS', 'RLS Variant', 'RLS-001')", variantAId, tenantAId, productAId);
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P900001', 'available'::piece_status)",
            pieceAId, tenantAId, variantAId, "PC-" + pieceAId);

        jdbc.update(
            "INSERT INTO return_sessions (id, tenant_id, status, opened_by) VALUES (?, ?, 'open', ?)",
            sessionAId, tenantAId, ownerAId);

        itemAId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO return_session_items (id, tenant_id, session_id, piece_id, scan_source) " +
            "VALUES (?, ?, ?, ?, 'barcode')",
            itemAId, tenantAId, sessionAId, pieceAId);

        shipmentRowAId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO return_session_shipments (id, tenant_id, session_id, awb) VALUES (?, ?, ?, '9300001')",
            shipmentRowAId, tenantAId, sessionAId);

        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        appUserTx.setReadOnly(true);
    }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    // ── return_sessions ───────────────────────────────────────────────────────

    @Test
    void crossTenant_cannotSelectOtherTenantsSession_viaRls() {
        TenantContext.set(tenantBId);
        List<Map<String, Object>> rows = appUserTx.execute(status ->
                appUserJdbc.queryForList("SELECT id FROM return_sessions WHERE id = ?", sessionAId));
        assertThat(rows).as("tenant B must not see tenant A's return_sessions row under RLS").isEmpty();
    }

    @Test
    void sameTenant_positiveControl_canSelectOwnSession_viaRls() {
        TenantContext.set(tenantAId);
        List<Map<String, Object>> rows = appUserTx.execute(status ->
                appUserJdbc.queryForList("SELECT id FROM return_sessions WHERE id = ?", sessionAId));
        assertThat(rows).as("tenant A must see its own return_sessions row under RLS").hasSize(1);
    }

    // ── return_session_items ─────────────────────────────────────────────────

    @Test
    void crossTenant_cannotSelectOtherTenantsItem_viaRls() {
        TenantContext.set(tenantBId);
        List<Map<String, Object>> rows = appUserTx.execute(status ->
                appUserJdbc.queryForList("SELECT id FROM return_session_items WHERE id = ?", itemAId));
        assertThat(rows).as("tenant B must not see tenant A's return_session_items row under RLS").isEmpty();
    }

    @Test
    void sameTenant_positiveControl_canSelectOwnItem_viaRls() {
        TenantContext.set(tenantAId);
        List<Map<String, Object>> rows = appUserTx.execute(status ->
                appUserJdbc.queryForList("SELECT id FROM return_session_items WHERE id = ?", itemAId));
        assertThat(rows).as("tenant A must see its own return_session_items row under RLS").hasSize(1);
    }

    // ── return_session_shipments ─────────────────────────────────────────────

    @Test
    void crossTenant_cannotSelectOtherTenantsShipmentRow_viaRls() {
        TenantContext.set(tenantBId);
        List<Map<String, Object>> rows = appUserTx.execute(status ->
                appUserJdbc.queryForList(
                    "SELECT id FROM return_session_shipments WHERE id = ?", shipmentRowAId));
        assertThat(rows).as("tenant B must not see tenant A's return_session_shipments row under RLS").isEmpty();
    }

    @Test
    void sameTenant_positiveControl_canSelectOwnShipmentRow_viaRls() {
        TenantContext.set(tenantAId);
        List<Map<String, Object>> rows = appUserTx.execute(status ->
                appUserJdbc.queryForList(
                    "SELECT id FROM return_session_shipments WHERE id = ?", shipmentRowAId));
        assertThat(rows).as("tenant A must see its own return_session_shipments row under RLS").hasSize(1);
    }
}
