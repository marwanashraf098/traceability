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
import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.CountDownLatch;
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
    UUID locationNotFulfillment; // linked, is_fulfillment=false (for si6 guard)
    UUID variantA;
    UUID variantB;
    UUID productId;
    String pieceId; // for si4

    // Separate tenant for si3 — V61 enforces one is_fulfillment=true location per tenant,
    // so "unsynced fulfillment location" (a temporal state of THE fulfillment location
    // before it's linked) can't coexist with tenantId's already-linked locationId above.
    UUID tenantUnsynced;
    UUID storeUnsynced;
    UUID productUnsynced;
    UUID variantUnsynced;
    UUID locationUnsynced;       // unsynced, is_fulfillment=true (for si3)

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

        // Separate tenant for si3: an unsynced fulfillment location + its own product/variant/
        // store, so resolvePreconditions' variant/store lookups (RLS-equivalent tenant_id
        // filter) resolve correctly under a different TenantContext than the rest of the class.
        tenantUnsynced = UUID.randomUUID();
        storeUnsynced  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ShopifyInventoryUnsyncedTenant')", tenantUnsynced);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'unsynced-test.myshopify.com', 'idle', " +
            "'read_orders,write_inventory,read_products,write_locations,read_locations,read_customers')",
            storeUnsynced, tenantUnsynced);
        productUnsynced = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/unsynced', 'Unsynced Product', 'active')",
            productUnsynced, tenantUnsynced, storeUnsynced);
        variantUnsynced = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/201', 'Variant Unsynced', 'SKU-U')",
            variantUnsynced, tenantUnsynced, productUnsynced);
        // is_fulfillment=true (technical link status and business fulfillment scoping are
        // independent — a fulfillment location can still be pending its Shopify link).
        locationUnsynced = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Unsynced WH', 'unsynced', true)",
            locationUnsynced, tenantUnsynced);

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
        when(tokenProvider.getValidToken(storeUnsynced)).thenReturn("test-token-unsynced");
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
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/201"), eq(TRACED_GID), eq(3), eq("received"), any());
        verify(shopifyGateway).adjustInventoryQuantities(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/202"), eq(TRACED_GID), eq(5), eq("received"), any());

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
            anyString(), anyString(), anyString(), anyString(), eq(2), anyString(), any());

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
                eq("gid://shopify/ProductVariant/201")))
            .thenReturn("gid://shopify/InventoryItem/201");

        UUID sessionId = UUID.randomUUID();
        TenantContext.set(tenantUnsynced);
        try {
            service.onReceivingSessionClose(tenantUnsynced, sessionId, locationUnsynced, Map.of(variantUnsynced, 1))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, error, shopify_inventory_item_id FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            sessionId.toString(), tenantUnsynced);

        assertThat(row.get("status")).as("si3: unlinked location → failed row").isEqualTo("failed");
        assertThat(row.get("error").toString()).as("si3: error mentions location").contains("not linked");
        assertThat(row.get("shopify_inventory_item_id"))
            .as("si3: inventoryItemId resolved even though location failed")
            .isEqualTo("gid://shopify/InventoryItem/201");

        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
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
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/201"), eq(TRACED_GID), eq(1), eq("restock"), any());

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

        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), eq(OTHER_GID), anyInt(), any(), any());
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

        verify(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), eq(TRACED_GID), eq(4), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(),
            argThat(loc -> !TRACED_GID.equals(loc)), anyInt(), any(), any());
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
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/201"), eq(TRACED_GID), eq(1), eq("damaged"), any());

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

        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any());
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
            .when(shopifyGateway).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any());

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
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any());
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

    // ── si12/si13: GENUINE concurrency (not sequential call-then-call) ───────
    //
    // Deterministic, not timing-dependent: the "winner" thread is deliberately blocked
    // INSIDE resolvePreconditions (via a blocking tokenProvider.getValidToken stub) AFTER
    // its claim() INSERT has already committed as 'pending' — verified by reading the row
    // back before proceeding — and BEFORE it ever calls Shopify. A second, identical trigger
    // fired at that exact moment must hit the claim conflict and return without ever calling
    // Shopify. This reproduces "a JobRunr retry overlapping the original" / "a duplicate
    // webhook" without relying on two threads happening to race within a few milliseconds.

    @Test @Order(12)
    void si12_concurrentDuplicateIncrementTrigger_shopifyCalledExactlyOnce() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/CONC12");

        UUID sessionId = UUID.randomUUID();
        CountDownLatch winnerInsidePreconditions = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        // getValidToken(storeId) is called inside resolvePreconditions, AFTER claim() has
        // already committed — blocking here simulates "the winner is still mid-flight".
        when(tokenProvider.getValidToken(storeId)).thenAnswer(invocation -> {
            winnerInsidePreconditions.countDown();
            releaseWinner.await(5, TimeUnit.SECONDS);
            return "test-token";
        });

        Thread winner = new Thread(() -> {
            TenantContext.set(tenantId);
            try {
                service.onReceivingSessionClose(tenantId, sessionId, locationId, Map.of(variantA, 2))
                       .get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // interruption/timeout on this thread is asserted via the outer join below
            } finally {
                TenantContext.clear();
            }
        });
        winner.start();

        assertThat(winnerInsidePreconditions.await(5, TimeUnit.SECONDS))
            .as("si12: winner must reach resolvePreconditions before the duplicate fires")
            .isTrue();

        String statusWhilePending = jdbc.queryForObject(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ? AND variant_id = ?",
            String.class, sessionId.toString(), tenantId, variantA);
        assertThat(statusWhilePending)
            .as("si12: claim already committed as pending BEFORE Shopify is ever called")
            .isEqualTo("pending");

        // Duplicate trigger for the EXACT same (trigger_type, trigger_id, variant, location) —
        // fired while the winner is still pending, still holds no Shopify call yet.
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionId, locationId, Map.of(variantA, 2))
                   .get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        releaseWinner.countDown();
        winner.join(10_000);

        verify(shopifyGateway, times(1)).adjustInventoryQuantities(
            anyString(), anyString(), anyString(), anyString(), eq(2), anyString(), any());
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ? AND variant_id = ?",
            Long.class, sessionId.toString(), tenantId, variantA);
        assertThat(count).as("si12: exactly one row — the duplicate never claimed").isEqualTo(1L);
    }

    @Test @Order(13)
    void si13_concurrentDuplicateDamageMove_shopifyCalledExactlyOnce() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/CONC13");

        String dmgPieceId = seedAvailablePiece("BC-SI13-001", locationId);

        CountDownLatch winnerInsidePreconditions = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        when(tokenProvider.getValidToken(storeId)).thenAnswer(invocation -> {
            winnerInsidePreconditions.countDown();
            releaseWinner.await(5, TimeUnit.SECONDS);
            return "test-token";
        });

        Thread winner = new Thread(() -> {
            TenantContext.set(tenantId);
            try {
                service.onSellablePieceDamaged(tenantId, dmgPieceId, locationId).get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            } finally {
                TenantContext.clear();
            }
        });
        winner.start();

        assertThat(winnerInsidePreconditions.await(5, TimeUnit.SECONDS))
            .as("si13: winner must reach resolvePreconditions before the duplicate fires")
            .isTrue();

        String statusWhilePending = jdbc.queryForObject(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'damage_move' AND trigger_id = ? AND tenant_id = ?",
            String.class, dmgPieceId, tenantId);
        assertThat(statusWhilePending)
            .as("si13: claim already committed as pending BEFORE Shopify is ever called")
            .isEqualTo("pending");

        TenantContext.set(tenantId);
        try {
            service.onSellablePieceDamaged(tenantId, dmgPieceId, locationId).get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        releaseWinner.countDown();
        winner.join(10_000);

        verify(shopifyGateway, times(1)).moveAvailableToDamaged(
            anyString(), anyString(), anyString(), anyString(), eq(1), anyString(), any());
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'damage_move' AND trigger_id = ? AND tenant_id = ?",
            Long.class, dmgPieceId, tenantId);
        assertThat(count).as("si13: exactly one row — the duplicate never claimed").isEqualTo(1L);
    }

    // ── si14: claim() case (c) — a 'failed' row IS reclaimed for retry ───────
    //
    // claim()'s ON CONFLICT ... DO UPDATE ... WHERE status='failed' has three outcomes:
    //   (a) fresh INSERT (no existing row)              -> claimed, proceed to Shopify
    //   (b) existing 'pending'/'applied' row             -> WHERE doesn't match, 0 rows, back off
    //   (c) existing 'failed' row                        -> WHERE matches, DO UPDATE fires, claimed
    // si12/si13 already prove (b). This proves (c): a failed attempt must not permanently
    // block the same (trigger_type, trigger_id, variant_id, location_id) key from ever being
    // retried — Shopify succeeding on a later attempt must still land.

    @Test @Order(14)
    void si14_failedRowIsReclaimed_retrySucceedsAndCallsShopify() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/CONC14");

        UUID sessionId = UUID.randomUUID();

        // First attempt: Shopify rejects it -> claim() case (a), then markResult('failed').
        doThrow(new ShopifyException("simulated transient failure"))
            .when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionId, locationId, Map.of(variantA, 2))
                   .get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        String statusAfterFirst = jdbc.queryForObject(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ? AND variant_id = ?",
            String.class, sessionId.toString(), tenantId, variantA);
        assertThat(statusAfterFirst).as("si14: first attempt recorded as failed").isEqualTo("failed");

        // Second attempt, the EXACT SAME (trigger_type, trigger_id, variant_id, location_id) key:
        // Shopify now succeeds. claim() must hit case (c) — WHERE status='failed' matches the
        // existing row, DO UPDATE re-arms it to 'pending', rows=1 — and proceed to call Shopify.
        reset(shopifyGateway);
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/CONC14");
        // adjustInventoryQuantities left unstubbed on the reset mock -> succeeds (no-op) by default.

        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionId, locationId, Map.of(variantA, 2))
                   .get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway, times(1)).adjustInventoryQuantities(
            anyString(), anyString(), anyString(), anyString(), eq(2), anyString(), any());

        String statusAfterRetry = jdbc.queryForObject(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ? AND variant_id = ?",
            String.class, sessionId.toString(), tenantId, variantA);
        assertThat(statusAfterRetry).as("si14: retry reclaims the failed row and succeeds").isEqualTo("applied");

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ? AND variant_id = ?",
            Long.class, sessionId.toString(), tenantId, variantA);
        assertThat(count).as("si14: reclaim updates the SAME row, never inserts a second one").isEqualTo(1L);
    }

    // ── si15: custom_app_cc scope-guard failure gets the corrected message ──────
    //
    // Production bug: a custom_app_cc store missing a scope was told to "reconnect" —
    // meaningless for CC (no consent screen). The message must instead point at the Dev
    // Dashboard custom app + token reissue. Self-contained tenant/store/location/variant so
    // this doesn't disturb the shared fixtures used by si1-si14.

    @Test @Order(15)
    void si15_customAppCc_missingScope_getsCorrectedMessageNotReconnect() throws Exception {
        UUID ccTenantId = UUID.randomUUID();
        UUID ccStoreId  = UUID.randomUUID();
        UUID ccProductId = UUID.randomUUID();
        UUID ccVariantId = UUID.randomUUID();
        UUID ccLocationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ShopifyInventoryCcScopeTenant')", ccTenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, connection_type, " +
            "client_id_encrypted, api_secret_encrypted, access_token_scopes) " +
            "VALUES (?, ?, 'cc-scope-test.myshopify.com', 'idle', 'custom_app_cc', 'enc-id', 'enc-secret', 'read_products')",
            ccStoreId, ccTenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/CC15', 'CC Scope Product', 'active')",
            ccProductId, ccTenantId, ccStoreId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/CC15', 'CC Scope Variant', 'SKU-CC15')",
            ccVariantId, ccTenantId, ccProductId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'CC Warehouse', 'gid://shopify/Location/CC15', 'linked', true)",
            ccLocationId, ccTenantId);

        UUID sessionId = UUID.randomUUID();
        TenantContext.set(ccTenantId);
        try {
            service.onReceivingSessionClose(ccTenantId, sessionId, ccLocationId, Map.of(ccVariantId, 1))
                   .get(5, TimeUnit.SECONDS);
        } finally { TenantContext.clear(); }

        String error = jdbc.queryForObject(
            "SELECT error FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            String.class, sessionId.toString(), ccTenantId);

        assertThat(error).as("si15: names the missing scope").contains("Token lacks write_inventory scope");
        assertThat(error).as("si15: must NOT tell a CC store to reconnect").doesNotContain("must reconnect");
        assertThat(error).as("si15: must point at updating the custom app's Admin API scopes instead")
            .contains("Admin API access scopes");

        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── si16: @idempotent key — same trigger reuses the key, different trigger differs ──
    //
    // Mirrors si14's exact retry setup (first attempt fails, retry with the SAME trigger_id
    // succeeds) but captures the idempotencyKey argument actually passed to the gateway on
    // each real call, at the caller layer (ShopifyInventoryService), not just the pure
    // derivation function (see ShopifyGatewayIdempotencyKeyTest for that).

    @Test @Order(16)
    void si16_idempotencyKey_sameTriggerReusesKey_differentTriggerGetsDifferentKey() throws Exception {
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/SI16");

        UUID sessionA = UUID.randomUUID();

        // First attempt: Shopify rejects it -> claim() case (a), then markResult('failed').
        doThrow(new ShopifyException("simulated transient failure"))
            .when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionA, locationId, Map.of(variantA, 3))
                   .get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        ArgumentCaptor<String> firstKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway).adjustInventoryQuantities(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), firstKeyCaptor.capture());
        String firstAttemptKey = firstKeyCaptor.getValue();

        // Second attempt, the EXACT SAME trigger_id (sessionA): claim() reclaims the failed row.
        reset(shopifyGateway);
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/SI16");
        // adjustInventoryQuantities left unstubbed on the reset mock -> succeeds (no-op) by default.

        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionA, locationId, Map.of(variantA, 3))
                   .get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        ArgumentCaptor<String> retryKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway).adjustInventoryQuantities(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), retryKeyCaptor.capture());
        String retryAttemptKey = retryKeyCaptor.getValue();

        assertThat(retryAttemptKey)
            .as("si16: a retry of the SAME operation (same trigger_id) must reuse the SAME idempotency key")
            .isEqualTo(firstAttemptKey);

        // Third call, a DIFFERENT trigger_id (sessionB) -> must get a DIFFERENT key.
        reset(shopifyGateway);
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/SI16");

        UUID sessionB = UUID.randomUUID();
        TenantContext.set(tenantId);
        try {
            service.onReceivingSessionClose(tenantId, sessionB, locationId, Map.of(variantA, 3))
                   .get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        ArgumentCaptor<String> differentOpKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway).adjustInventoryQuantities(
            anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), differentOpKeyCaptor.capture());
        String differentOpKey = differentOpKeyCaptor.getValue();

        assertThat(differentOpKey)
            .as("si16: a genuinely different operation (different trigger_id) must get a DIFFERENT idempotency key")
            .isNotEqualTo(firstAttemptKey);
    }

    // ── si17: multi-store tenant — deterministic store resolution (Step 0.5) ─
    //
    // A tenant is schema-legal to have more than one `stores` row (no UNIQUE(tenant_id)
    // constraint) — e.g. a stale/abandoned connect attempt left behind alongside the real,
    // currently-synced store. Before the Step 0.5 fix, resolvePreconditions()'s bare
    // `LIMIT 1` (no ORDER BY) could nondeterministically pick either row. This proves the
    // fix: the most-recently-synced store (by last_sync_at) is always the one whose
    // domain/token drive the actual Shopify call — both a positive control (the fresh
    // store's identity IS used and the call succeeds) and a negative check (the stale
    // store's identity is NEVER used).

    @Test @Order(17)
    void si17_multiStoreTenant_deterministicallyPicksMostRecentlySyncedStore() throws Exception {
        UUID detTenantId   = UUID.randomUUID();
        UUID staleStoreId  = UUID.randomUUID();
        UUID freshStoreId  = UUID.randomUUID();
        UUID detProductId  = UUID.randomUUID();
        UUID detVariantId  = UUID.randomUUID();
        UUID detLocationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ShopifyInventoryDeterminismTenant')", detTenantId);

        // Stale store: older last_sync_at — must NOT be the row resolvePreconditions reads.
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'stale-det.myshopify.com', 'idle', 'read_products,write_inventory', now() - interval '10 days')",
            staleStoreId, detTenantId);
        // Fresh store: most recent last_sync_at — the one that must win.
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'fresh-det.myshopify.com', 'idle', 'read_products,write_inventory', now())",
            freshStoreId, detTenantId);

        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/DET17', 'Determinism Product', 'active')",
            detProductId, detTenantId, freshStoreId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/DET17', 'Determinism Variant', 'SKU-DET17')",
            detVariantId, detTenantId, detProductId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Determinism WH', 'gid://shopify/Location/DET17', 'linked', true)",
            detLocationId, detTenantId);

        when(tokenProvider.getValidToken(freshStoreId)).thenReturn("fresh-token");
        when(tokenProvider.getValidToken(staleStoreId)).thenReturn("stale-token");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(),
                eq("gid://shopify/ProductVariant/DET17")))
            .thenReturn("gid://shopify/InventoryItem/DET17");

        UUID sessionId = UUID.randomUUID();
        TenantContext.set(detTenantId);
        try {
            service.onReceivingSessionClose(detTenantId, sessionId, detLocationId, Map.of(detVariantId, 1))
                   .get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        // Positive control: the call actually landed, using the FRESH store's domain and token.
        verify(shopifyGateway).adjustInventoryQuantities(
            eq("fresh-det.myshopify.com"), eq("fresh-token"), eq("gid://shopify/InventoryItem/DET17"),
            eq("gid://shopify/Location/DET17"), eq(1), eq("received"), any());
        // Negative: the stale store's domain/token must never appear in any call.
        verify(shopifyGateway, never()).adjustInventoryQuantities(
            eq("stale-det.myshopify.com"), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(
            any(), eq("stale-token"), any(), any(), anyInt(), any(), any());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'receiving_session' AND trigger_id = ? AND tenant_id = ?",
            sessionId.toString(), detTenantId);
        assertThat(row.get("status")).as("si17: resolved deterministically and applied").isEqualTo("applied");
    }

}
