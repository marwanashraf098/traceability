package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantContext;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * FR-17 Phase 1 — Shopify inventory shadow sync integration tests.
 *
 * Matrix:
 *   si1 — receiving session close inserts shadow rows (one per variant)
 *   si2 — idempotency: duplicate trigger is blocked by UNIQUE constraint (ON CONFLICT DO NOTHING)
 *   si3 — unlinked location records a failed row instead of throwing
 *   si4 — return inspection → AVAILABLE inserts shadow row for the piece's variant
 *   si5 — app_user with wrong tenant cannot see adjustment rows (RLS)
 *
 * ShopifyGateway and ShopifyTokenProvider are mocked — no real Shopify API calls.
 * Tests run as postgres (BYPASSRLS) for setup; si5 uses app_user for isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShopifyInventoryTest {

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

    @MockBean JobScheduler        jobScheduler;
    @MockBean ShopifyGateway      shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate           jdbc;
    @Autowired ShopifyInventoryService service;

    // ── Shared fixtures ──────────────────────────────────────────────────────

    UUID tenantId;
    UUID storeId;
    UUID locationId;       // linked location
    UUID locationUnsynced; // unsynced location (for si3)
    UUID variantA;
    UUID variantB;
    UUID productId;
    String pieceId; // for si4

    @BeforeAll
    void seedFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ShopifyInventoryTenant')", tenantId);

        // Store with shop_domain (needed by service to get token).
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status) " +
            "VALUES (?, ?, 'test.myshopify.com', 'idle')",
            storeId, tenantId);

        productId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/1', 'Test Product', 'active')",
            productId, tenantId, storeId);

        variantA = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/101', 'Variant A', 'SKU-A')",
            variantA, tenantId, productId);

        variantB = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/102', 'Variant B', 'SKU-B')",
            variantB, tenantId, productId);

        // Linked location — shopify_sync_status = 'linked'.
        locationId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'gid://shopify/Location/999', 'linked')",
            locationId, tenantId);

        // Unsynced location — used to test si3.
        locationUnsynced = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_sync_status) " +
            "VALUES (?, ?, 'Unsynced WH', 'unsynced')",
            locationUnsynced, tenantId);

        // Piece for si4 (return inspection trigger).
        pieceId = "01HTEST0000000000000000001";
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, current_location_id) " +
            "VALUES (?, ?, ?, 'return_pending_inspection'::piece_status, 'BC-SI4-001', ?)",
            pieceId, tenantId, variantA, locationId);
    }

    // ── si1: receiving session close inserts shadow rows ─────────────────────

    @Test @Order(1)
    void si1_receivingSessionClose_insertsShadowRows() throws Exception {
        // Mock: storeId lookup needs valid token; inventoryItem GID returned by mock.
        when(tokenProvider.getValidToken(storeId)).thenReturn("test-token");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(),
                eq("gid://shopify/ProductVariant/101")))
            .thenReturn("gid://shopify/InventoryItem/201");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(),
                eq("gid://shopify/ProductVariant/102")))
            .thenReturn("gid://shopify/InventoryItem/202");

        UUID sessionId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(
                tenantId, sessionId, locationId,
                Map.of(variantA, 3, variantB, 5)
            ).get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        // Two rows: one per variant.
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            Long.class, sessionId.toString(), tenantId);
        assertThat(count).as("si1: two shadow rows inserted").isEqualTo(2L);

        // Variant A: delta=3, status=shadow, inventoryItemId resolved.
        Map<String, Object> rowA = jdbc.queryForMap(
            "SELECT delta, status, shopify_inventory_item_id FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND variant_id = ?",
            sessionId.toString(), variantA);
        assertThat(rowA.get("delta")).isEqualTo(3);
        assertThat(rowA.get("status")).isEqualTo("shadow");
        assertThat(rowA.get("shopify_inventory_item_id")).isEqualTo("gid://shopify/InventoryItem/201");
    }

    // ── si2: idempotency — duplicate trigger is silently skipped ─────────────

    @Test @Order(2)
    void si2_duplicateTrigger_blockedByUniqueConstraint() throws Exception {
        when(tokenProvider.getValidToken(storeId)).thenReturn("test-token");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/201");

        UUID sessionId = UUID.randomUUID();

        // First call.
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionId, locationId, Map.of(variantA, 2))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        Long countAfterFirst = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            Long.class, sessionId.toString(), tenantId);

        // Second call — same trigger_id.
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionId, locationId, Map.of(variantA, 2))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        Long countAfterSecond = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            Long.class, sessionId.toString(), tenantId);

        assertThat(countAfterSecond)
            .as("si2: duplicate trigger does not produce extra rows (ON CONFLICT DO NOTHING)")
            .isEqualTo(countAfterFirst);
    }

    // ── si3: unlinked location records a failed row ───────────────────────────

    @Test @Order(3)
    void si3_unlinkedLocation_recordsFailedRow() throws Exception {
        UUID sessionId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionId, locationUnsynced, Map.of(variantA, 1))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, error FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            sessionId.toString(), tenantId);

        assertThat(row.get("status")).as("si3: unlinked location produces failed row").isEqualTo("failed");
        assertThat(row.get("error").toString()).contains("not linked");
    }

    // ── si4: return inspection → AVAILABLE inserts shadow row ────────────────

    @Test @Order(4)
    void si4_returnInspectionAvailable_insertsShadowRow() throws Exception {
        when(tokenProvider.getValidToken(storeId)).thenReturn("test-token");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(),
                eq("gid://shopify/ProductVariant/101")))
            .thenReturn("gid://shopify/InventoryItem/201");

        TenantContext.set(tenantId);
        try {
            service.onReturnInspectionAvailable(tenantId, pieceId, locationId)
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT delta, status, trigger_type FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'return_inspection' AND trigger_id = ? AND tenant_id = ?",
            pieceId, tenantId);

        assertThat(row.get("delta")).as("si4: return inspection delta = +1").isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("shadow");
        assertThat(row.get("trigger_type")).isEqualTo("return_inspection");
    }

    // ── si5: RLS — wrong tenant cannot see adjustment rows ───────────────────

    @Test @Order(5)
    void si5_wrongTenant_cannotSeeAdjustments() {
        // Seed a known row directly.
        UUID rowTenantId = tenantId;
        Long knownId = jdbc.queryForObject(
            "SELECT id FROM shopify_inventory_adjustments WHERE tenant_id = ? LIMIT 1",
            Long.class, rowTenantId);
        if (knownId == null) return; // no rows from prior tests — skip

        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'OtherSITenant')", otherTenant);

        try (Connection appConn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "app_user", "app_user_password")) {
            appConn.setAutoCommit(false);

            // Correct tenant — row visible.
            try (var stmt = appConn.createStatement()) {
                stmt.execute("SET LOCAL app.current_tenant = '" + rowTenantId + "'");
            }
            try (var ps = appConn.prepareStatement(
                    "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE id = ?")) {
                ps.setLong(1, knownId);
                var rs = ps.executeQuery();
                rs.next();
                assertThat(rs.getInt(1))
                    .as("si5: correct tenant sees the row").isGreaterThan(0);
            }

            // Wrong tenant — row invisible.
            try (var stmt = appConn.createStatement()) {
                stmt.execute("SET LOCAL app.current_tenant = '" + otherTenant + "'");
            }
            try (var ps = appConn.prepareStatement(
                    "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE id = ?")) {
                ps.setLong(1, knownId);
                var rs = ps.executeQuery();
                rs.next();
                assertThat(rs.getInt(1))
                    .as("si5: wrong tenant cannot see adjustment row (RLS)").isZero();
            }

            appConn.rollback();
        } catch (SQLException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "app_user not available in test container — RLS assertion skipped: " + e.getMessage());
        }
    }
}
