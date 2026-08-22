package com.traceability;

import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-13.x — a failed void/hold Shopify decrement must not sit silent (approved by Marawan
 * 2026-08-23): a new 'void_hold_sync_failed' exception detector + the EXISTING resolve flow
 * make it visible; a manual (not auto) repush lets an operator re-attempt the exact same call.
 *
 * ve1 — a 'failed' void_correction row surfaces as a CRITICAL exception
 * ve2 — a 'skipped' row (the correct outcome for a never-applied receiving increment) never
 *       surfaces — it is not a divergence
 * ve3 — resolving via the existing ExceptionService.resolve() removes it from the open list
 * ve4 — repushFailedVoidOrHold() re-attempts the same call; success flips status to 'applied'
 * ve5 — repushFailedVoidOrHold() on a non-existent / non-failed row -> 404 / 409
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VoidHoldSyncExceptionTest {

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

    @Autowired JdbcTemplate            jdbc;
    @Autowired ExceptionService        exceptionService;
    @Autowired ShopifyInventoryService shopifyInventoryService;

    UUID tenantId, storeId, locationId, productId, variantA, actorId;
    static final String SHOP_DOMAIN = "ve-test.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/555";

    @BeforeAll
    void seedFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'VoidHoldSyncTenant')", tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations,read_customers')",
            storeId, tenantId, SHOP_DOMAIN);
        productId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/VE1', 'VE Product', 'active')",
            productId, tenantId, storeId);
        variantA = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/VE1', 'Default', 'VE-SKU')",
            variantA, tenantId, productId);
        locationId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'VE Warehouse', ?, 'linked', true)",
            locationId, tenantId, TRACED_GID);
        actorId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 've@test.com', 'x', 'owner'::user_role)",
            actorId, tenantId);
    }

    @BeforeEach
    void resetStubs() {
        reset(shopifyGateway);
        when(tokenProvider.getValidToken(storeId)).thenReturn("ve-token");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/VE1");
    }

    @Test @Order(1)
    void ve1_failedVoidCorrection_surfacesAsCriticalException() {
        String pieceId = seedAvailablePiece("VE1-001");
        seedFailedAdjustment("void_correction", pieceId);

        Map<String, Object> result = withTenant(() ->
            exceptionService.listExceptions("void_hold_sync_failed", null, 0, 50));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("severity")).isEqualTo("CRITICAL");
        assertThat(items.get(0).get("piece_id")).isEqualTo(pieceId);
    }

    @Test @Order(2)
    void ve2_skippedRow_neverSurfaces() {
        String pieceId = seedAvailablePiece("VE2-001");
        jdbc.update(
            "INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, status) " +
            "VALUES (?, ?, ?, ?, -1, 'void_correction', ?, 'skipped')",
            tenantId, UUID.randomUUID(), variantA, locationId, pieceId);

        Map<String, Object> result = withTenant(() ->
            exceptionService.listExceptions("void_hold_sync_failed", null, 0, 50));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).as("a skipped void (correct outcome) is never an exception")
            .noneMatch(i -> pieceId.equals(i.get("piece_id")));
    }

    @Test @Order(3)
    void ve3_resolve_removesFromOpenList() {
        String pieceId = seedAvailablePiece("VE3-001");
        seedFailedAdjustment("void_correction", pieceId);

        String subjectKey = "void_hold_sync_failed:void_correction:" + pieceId;
        withTenantVoid(() -> exceptionService.resolve("void_hold_sync_failed", subjectKey, actorId, "fixed manually"));

        Map<String, Object> result = withTenant(() ->
            exceptionService.listExceptions("void_hold_sync_failed", null, 0, 50));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).noneMatch(i -> pieceId.equals(i.get("piece_id")));
    }

    @Test @Order(4)
    void ve4_repush_reattemptsAndSucceeds() throws Exception {
        // hold_enter, not void_correction: void_correction's own conditional (does the
        // originating receiving increment show 'applied'?) would need extra fixture setup
        // unrelated to what this test verifies. hold_enter has no such gate — a cleaner,
        // more direct proof that repush reuses claim()'s existing 'failed' reclaim branch.
        String pieceId = seedAvailablePiece("VE4-001");
        UUID holdEventId = UUID.randomUUID();
        String triggerId = pieceId + ":" + holdEventId;
        seedFailedAdjustment("hold_enter", triggerId);
        // First attempt (seeded) failed; this repush should succeed against the (now
        // default-stubbed, non-throwing) mock.

        withTenantVoid(() -> shopifyInventoryService.repushFailedVoidOrHold("hold_enter", triggerId));
        Thread.sleep(200);

        verify(shopifyGateway, times(1)).pushHoldEnter(
            eq(SHOP_DOMAIN), eq("ve-token"), eq("gid://shopify/InventoryItem/VE1"),
            eq(TRACED_GID), eq(-1), anyString(), anyString());

        String status = jdbc.queryForObject(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'hold_enter' AND trigger_id = ?",
            String.class, tenantId, triggerId);
        assertThat(status).isEqualTo("applied");
    }

    @Test @Order(5)
    void ve5_repush_notFoundOrNotFailed() {
        withTenant(() -> {
            assertThatThrownBy(() -> shopifyInventoryService.repushFailedVoidOrHold("void_correction", "NO-SUCH-PIECE"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            return null;
        });

        String pieceId = seedAvailablePiece("VE5-001");
        jdbc.update(
            "INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, status) " +
            "VALUES (?, ?, ?, ?, -1, 'void_correction', ?, 'applied')",
            tenantId, UUID.randomUUID(), variantA, locationId, pieceId);

        withTenant(() -> {
            assertThatThrownBy(() -> shopifyInventoryService.repushFailedVoidOrHold("void_correction", pieceId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            return null;
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private <T> T withTenant(java.util.function.Supplier<T> body) {
        TenantContext.set(tenantId);
        try {
            return body.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void withTenantVoid(Runnable body) {
        TenantContext.set(tenantId);
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }

    private String seedAvailablePiece(String barcode) {
        String id = "01HVE" + UUID.randomUUID().toString().replace("-", "").substring(0, 19).toUpperCase();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, 'voided'::piece_status, ?, " +
            "    'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?)",
            id, tenantId, variantA, barcode, id, locationId);
        return id;
    }

    private void seedFailedAdjustment(String triggerType, String triggerId) {
        jdbc.update(
            "INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, status, error) " +
            "VALUES (?, ?, ?, ?, -1, ?, ?, 'failed', 'simulated failure')",
            tenantId, UUID.randomUUID(), variantA, locationId, triggerType, triggerId);
    }
}
