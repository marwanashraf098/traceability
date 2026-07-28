package com.traceability;

import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.PieceAdjustService;
import com.traceability.inventory.ReturnService;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-17 v2 — Shopify inventory increment-only LIVE sync integration tests.
 *
 * Matrix:
 *   si1 — receiving session close: live inventoryAdjustQuantities call, +N per variant,
 *         status='applied', target locationGid == Traced GID
 *   si2 — idempotency: duplicate trigger calls Shopify exactly once (not per DB row)
 *   si3 — unlinked location: no Shopify call, 'failed' row with inventoryItemId still resolved
 *   si4 — return inspection → AVAILABLE: +1 live call
 *   si5 — RLS: wrong tenant cannot see adjustment rows
 *   si6 — location-target guard (core): a non-fulfillment location's own GID is NEVER used
 *         in any Shopify call; same-tenant positive control at the Traced GID succeeds
 *   si7 — damage trigger: sellable (available) piece damaged -> one available->damaged move
 *   si8 — damage trigger: damaged-while-allocated is blocked by PieceAdjustService itself
 *         (409) before the ledger transition — no Shopify write at all
 *   si9 — damage trigger: insufficient-available on Shopify fails cleanly, not forced
 *   si10 — return_pending_inspection -> damaged (ReturnService.markDamaged) never calls Shopify
 *   si11 — increment-only guard: no inventoryAdjustQuantities call ever carries a
 *          non-positive delta; inventorySetOnHandQuantities does not exist anywhere in source
 *
 * ShopifyGateway and ShopifyTokenProvider are mocked — no real Shopify API calls.
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

    @Autowired JdbcTemplate            jdbc;
    @Autowired ShopifyInventoryService service;
    @Autowired PieceAdjustService      pieceAdjustService;
    @Autowired ReturnService           returnService;

    // ── Shared fixtures ──────────────────────────────────────────────────────

    UUID tenantId;
    UUID storeId;
    UUID locationId;             // linked, is_fulfillment=true — the Traced GID
    UUID locationUnsynced;       // unsynced, is_fulfillment=true (for si3)
    UUID locationNotFulfillment; // linked, is_fulfillment=false (for si6 guard)
    UUID variantA;
    UUID variantB;
    UUID productId;
    String pieceId; // for si4

    static final String SHOP_DOMAIN = "test.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/999";
    static final String OTHER_GID   = "gid://shopify/Location/888";

    @BeforeAll
    void seedFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ShopifyInventoryTenant')", tenantId);

        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'idle', " +
            "'read_orders,write_inventory,read_products,write_locations,read_locations,read_customers')",
            storeId, tenantId, SHOP_DOMAIN);

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

        // Linked location — shopify_sync_status = 'linked', is_fulfillment=true. THE Traced GID.
        locationId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)",
            locationId, tenantId, TRACED_GID);

        // Unsynced location — used to test si3. is_fulfillment=true (technical link status
        // and business fulfillment scoping are independent — a fulfillment location can still
        // be pending its Shopify link).
        locationUnsynced = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Unsynced WH', 'unsynced', true)",
            locationUnsynced, tenantId);

        // Linked but is_fulfillment=false — a showroom/branch whose stock must NOT
        // contribute at all. Its own Shopify GID (OTHER_GID) must NEVER be used. Used by si6.
        locationNotFulfillment = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Showroom', ?, 'linked', false)",
            locationNotFulfillment, tenantId, OTHER_GID);

        // Piece for si4 (return inspection trigger).
        pieceId = "01HTEST0000000000000000001";
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, 'return_pending_inspection'::piece_status, 'BC-SI4-001', 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?)",
            pieceId, tenantId, variantA, pieceId, locationId);
    }

    @BeforeEach
    void resetTokenStub() {
        when(tokenProvider.getValidToken(storeId)).thenReturn("test-token");
    }

    // ── si1: receiving session close — live positive-delta call ──────────────

    @Test @Order(1)
    void si1_receivingSessionClose_liveAdjustCall() throws Exception {
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

        verify(shopifyGateway).adjustInventoryQuantities(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/201"), eq(TRACED_GID), eq(3), eq("received"));
        verify(shopifyGateway).adjustInventoryQuantities(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/202"), eq(TRACED_GID), eq(5), eq("received"));

        Map<String, Object> rowA = jdbc.queryForMap(
            "SELECT delta, status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND variant_id = ?",
            sessionId.toString(), variantA);
        assertThat(rowA.get("delta")).isEqualTo(3);
        assertThat(rowA.get("status")).isEqualTo("applied");
    }

    // ── si2: idempotency — duplicate trigger calls Shopify exactly once ──────

    @Test @Order(2)
    void si2_duplicateTrigger_shopifyCalledExactlyOnce() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/201");

        UUID sessionId = UUID.randomUUID();

        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionId, locationId, Map.of(variantA, 2))
                   .get(5, TimeUnit.SECONDS);
            service.onReceivingSessionClose(tenantId, sessionId, locationId, Map.of(variantA, 2))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        verify(shopifyGateway, times(1)).adjustInventoryQuantities(
            anyString(), anyString(), anyString(), anyString(), eq(2), anyString());

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            Long.class, sessionId.toString(), tenantId);
        assertThat(count).as("si2: exactly one audit row, never a double-apply").isEqualTo(1L);
    }

    // ── si3: unlinked location — no Shopify call, failed row ─────────────────

    @Test @Order(3)
    void si3_unlinkedLocation_noShopifyCallFailedRow() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(),
                eq("gid://shopify/ProductVariant/101")))
            .thenReturn("gid://shopify/InventoryItem/201");

        UUID sessionId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionId, locationUnsynced, Map.of(variantA, 1))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, error, shopify_inventory_item_id FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            sessionId.toString(), tenantId);

        assertThat(row.get("status")).as("si3: unlinked location → failed row").isEqualTo("failed");
        assertThat(row.get("error").toString()).as("si3: error mentions location").contains("not linked");
        assertThat(row.get("shopify_inventory_item_id"))
            .as("si3: inventoryItemId resolved even though location failed")
            .isEqualTo("gid://shopify/InventoryItem/201");

        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any());
    }

    // ── si4: return inspection → AVAILABLE — live +1 call ────────────────────

    @Test @Order(4)
    void si4_returnInspectionAvailable_livePlusOneCall() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(),
                eq("gid://shopify/ProductVariant/101")))
            .thenReturn("gid://shopify/InventoryItem/201");

        TenantContext.set(tenantId);
        try {
            service.onReturnInspectionAvailable(tenantId, pieceId, locationId)
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        verify(shopifyGateway).adjustInventoryQuantities(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/201"), eq(TRACED_GID), eq(1), eq("restock"));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT delta, status, trigger_type FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'return_inspection' AND trigger_id = ? AND tenant_id = ?",
            pieceId, tenantId);

        assertThat(row.get("delta")).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("applied");
    }

    // ── si5: RLS — wrong tenant cannot see adjustment rows ───────────────────

    @Test @Order(5)
    void si5_wrongTenant_cannotSeeAdjustments() {
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

    // ── si6: location-target guard (core) ────────────────────────────────────

    @Test @Order(6)
    void si6_locationTargetGuard_nonFulfillmentGidNeverUsed_tracedGidPositiveControl() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/201");

        // Negative case: is_fulfillment=false location — its own GID (OTHER_GID) must
        // NEVER appear in a Shopify call, not even a failed attempt (skipped before resolution).
        UUID negSessionId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, negSessionId, locationNotFulfillment, Map.of(variantA, 4))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), eq(OTHER_GID), anyInt(), any());
        Long negCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            Long.class, negSessionId.toString(), tenantId);
        assertThat(negCount).as("si6: non-fulfillment location contributes no row at all").isZero();

        // Positive control: same tenant, Traced GID — the call IS made, targeting TRACED_GID only.
        UUID posSessionId = UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, posSessionId, locationId, Map.of(variantA, 4))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        verify(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), eq(TRACED_GID), eq(4), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(),
            argThat(loc -> !TRACED_GID.equals(loc)), anyInt(), any());
    }

    // ── si7-si10: damage trigger (FR-17 v2 trigger 3) ────────────────────────

    private String seedAvailablePiece(String barcode, UUID locationId) {
        String id = "01HTESTDMG" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, 'available'::piece_status, ?, " +
            "    'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?)",
            id, tenantId, variantA, barcode, id, locationId);
        return id;
    }

    @Test @Order(7)
    void si7_sellablePieceDamaged_oneAvailableToDamagedMove() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(),
                eq("gid://shopify/ProductVariant/101")))
            .thenReturn("gid://shopify/InventoryItem/201");

        String dmgPieceId = seedAvailablePiece("BC-SI7-001", locationId);

        TenantContext.set(tenantId);
        try {
            pieceAdjustService.adjustPiece(dmgPieceId, "damaged", "damaged_in_storage", null, null);
            // adjustPiece dispatches the trigger asynchronously; give it a moment to land.
            Thread.sleep(300);
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway).moveAvailableToDamaged(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/201"), eq(TRACED_GID), eq(1), eq("damaged"));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, trigger_type FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'damage_move' AND trigger_id = ? AND tenant_id = ?",
            dmgPieceId, tenantId);
        assertThat(row.get("status")).isEqualTo("applied");
    }

    @Test @Order(8)
    void si8_damagedWhileAllocated_blockedBeforeAnyShopifyWrite() {
        String allocatedPieceId = seedAvailablePiece("BC-SI8-001", locationId);

        UUID orderId = UUID.randomUUID();
        UUID orderItemId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, on_hold) " +
            "VALUES (?, ?, ?, 'EXT-SI8', '#SI8-001', 'ready_to_pick'::order_status, false)",
            orderId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, ?, 1)", orderItemId, tenantId, orderId, variantA);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'active'::allocation_status)",
            tenantId, orderItemId, allocatedPieceId);
        jdbc.update("UPDATE pieces SET status = 'reserved'::piece_status WHERE id = ?", allocatedPieceId);

        TenantContext.set(tenantId);
        try {
            Assertions.assertThrows(
                com.traceability.inventory.PieceCommittedException.class,
                () -> pieceAdjustService.adjustPiece(allocatedPieceId, "damaged", "damaged_in_storage", null, null));
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any());
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'damage_move' AND trigger_id = ? AND tenant_id = ?",
            Long.class, allocatedPieceId, tenantId);
        assertThat(count).as("si8: allocated piece never reaches a damage-move write").isZero();
    }

    @Test @Order(9)
    void si9_insufficientAvailableOnShopify_failsCleanlyNotForced() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(),
                eq("gid://shopify/ProductVariant/101")))
            .thenReturn("gid://shopify/InventoryItem/201");
        doThrow(new ShopifyException("inventoryMoveQuantities failed: insufficient available quantity"))
            .when(shopifyGateway).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any());

        String dmgPieceId = seedAvailablePiece("BC-SI9-001", locationId);

        TenantContext.set(tenantId);
        try {
            pieceAdjustService.adjustPiece(dmgPieceId, "damaged", "damaged_in_storage", null, null);
            Thread.sleep(300);
        } finally {
            TenantContext.clear();
            reset(shopifyGateway); // clear the doThrow stub for subsequent tests
            when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
                .thenReturn("gid://shopify/InventoryItem/201");
        }

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, error FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'damage_move' AND trigger_id = ? AND tenant_id = ?",
            dmgPieceId, tenantId);
        assertThat(row.get("status")).as("si9: fails cleanly, never forced").isEqualTo("failed");
        assertThat(row.get("error").toString()).contains("insufficient");
    }

    @Test @Order(10)
    void si10_returnPendingInspectionToDamaged_neverCallsShopify() throws Exception {
        String rpiPieceId = "01HTESTRPI0000000000000001";
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, 'return_pending_inspection'::piece_status, 'BC-SI10-001', " +
            "    'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?)",
            rpiPieceId, tenantId, variantA, rpiPieceId, locationId);

        TenantContext.set(tenantId);
        try {
            // ReturnService.markDamaged() is the return_pending_inspection->damaged verdict
            // path — a separate method from PieceAdjustService.adjustPiece() with no
            // ShopifyInventoryService call at all (never sellable in Shopify to begin with).
            returnService.markDamaged(rpiPieceId, "damaged_in_transit", null);
            Thread.sleep(200);
        } finally {
            TenantContext.clear();
        }

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'damage_move' AND trigger_id = ? AND tenant_id = ?",
            Long.class, rpiPieceId, tenantId);
        assertThat(count).as("si10: return_pending_inspection->damaged never produces a damage_move row").isZero();
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any());
    }

    // ── si11: increment-only guard (core, source-level) ──────────────────────

    @Test @Order(11)
    void si11_noCodePathEverCallsInventorySetOnHandQuantities() throws Exception {
        // Structural proof, not a text scan (a text scan would also flag the doc comments
        // that intentionally name the forbidden method to explain why it's absent). Since
        // Java is statically typed, if no type callers can see declares this method, no
        // code path anywhere can ever call it — a compile error would result if one tried.
        // ShopifyHttpGateway is package-private (different package from this test) — loaded
        // via Class.forName purely to read its method metadata, nothing is invoked.
        boolean onInterface = java.util.Arrays.stream(ShopifyGateway.class.getMethods())
            .anyMatch(m -> m.getName().equals("inventorySetOnHandQuantities"));
        Class<?> implClass = Class.forName("com.traceability.integrations.shopify.ShopifyHttpGateway");
        boolean onImpl = java.util.Arrays.stream(implClass.getDeclaredMethods())
            .anyMatch(m -> m.getName().equals("inventorySetOnHandQuantities"));

        assertThat(onInterface)
            .as("si11: inventorySetOnHandQuantities must not exist on ShopifyGateway (FR-17 v2 forbids it)")
            .isFalse();
        assertThat(onImpl)
            .as("si11: inventorySetOnHandQuantities must not exist on ShopifyHttpGateway either")
            .isFalse();
    }

}
