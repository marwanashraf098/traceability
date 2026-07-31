package com.traceability;

import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.LocationController;
import com.traceability.inventory.ShopifyLocationProvisioningService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Part A — Traced Main Warehouse provisioning + fulfillment-only gate + junk-location cleanup.
 *
 * Matrix:
 *   lp1 — fulfillment-only gate: isFulfillment=false never calls the Shopify gateway
 *   lp2 — positive control: isFulfillment=true creates a Shopify location with
 *         fulfillsOnlineOrders=true
 *   lp3 — provisioning idempotency: re-running ensureTracedWarehouse does not create
 *         a duplicate Shopify location
 *   lp4 — Shopify-first case: zero locations exist -> one fulfillment location is created
 *   lp5 — standalone case: a fulfillment location already exists -> it is linked, not duplicated
 *   lp6 — junk report + guarded per-location cleanup (deactivate, never delete/auto-bulk)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyLocationProvisioningTest {

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

    @MockBean ShopifyLocationGateway shopifyLocations;
    @MockBean ShopifyTokenProvider   tokenProvider;

    @Autowired JdbcTemplate jdbc;
    @Autowired LocationController controller;
    @Autowired ShopifyLocationProvisioningService provisioningService;

    UUID tenantId;
    UUID storeId;
    static final String SHOP_DOMAIN = "lp-test.myshopify.com";

    @BeforeEach
    void setupTenant() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'LP Tenant')", tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'connected', 'read_products,write_locations,write_inventory')",
            storeId, tenantId, SHOP_DOMAIN);
        when(tokenProvider.getValidToken(storeId)).thenReturn("test-token");
        reset(shopifyLocations);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
        SecurityContextHolder.clearContext();
    }

    /** Controller methods are @PreAuthorize-guarded; calling them in-process (no HTTP/JWT
     *  filter chain) needs a manually-set SecurityContext with the right granted authority. */
    private <T> T asOwner(java.util.function.Supplier<T> action) {
        var auth = new TestingAuthenticationToken("test-owner", "n/a", "ROLE_OWNER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ── lp1: fulfillment-only gate ────────────────────────────────────────────

    @Test
    void lp1_nonFulfillmentLocation_neverCallsShopify() {
        TenantContext.set(tenantId);
        try {
            Map<String, Object> result = asOwner(() -> controller.create(Map.of(
                "name", "Showroom LP1", "type", "branch", "isFulfillment", "false")));
            assertThat(result.get("shopifySyncStatus")).isEqualTo("unsynced");
        } finally {
            TenantContext.clear();
        }
        verify(shopifyLocations, never()).create(any(), any(), any());
        verify(shopifyLocations, never()).findByName(any(), any(), any());

        Boolean isFulfillment = jdbc.queryForObject(
            "SELECT is_fulfillment FROM locations WHERE tenant_id = ? AND name = 'Showroom LP1'",
            Boolean.class, tenantId);
        assertThat(isFulfillment).isFalse();
    }

    // ── lp2: positive control — fulfillsOnlineOrders=true ────────────────────

    @Test
    void lp2_fulfillmentLocation_createsShopifyLocationWithFulfillsOnlineOrdersTrue() {
        when(shopifyLocations.findByName(eq(SHOP_DOMAIN), eq("test-token"), anyString()))
            .thenReturn(Optional.empty());
        when(shopifyLocations.create(eq(SHOP_DOMAIN), eq("test-token"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult("gid://shopify/Location/lp2", "Warehouse LP2"));

        TenantContext.set(tenantId);
        try {
            Map<String, Object> result = asOwner(() -> controller.create(Map.of(
                "name", "Warehouse LP2", "type", "warehouse", "isFulfillment", "true")));
            assertThat(result.get("shopifySyncStatus")).isEqualTo("linked");
        } finally {
            TenantContext.clear();
        }

        ArgumentCaptor<ShopifyLocationGateway.LocationInput> captor =
            ArgumentCaptor.forClass(ShopifyLocationGateway.LocationInput.class);
        verify(shopifyLocations).create(eq(SHOP_DOMAIN), eq("test-token"), captor.capture());
        assertThat(captor.getValue().fulfillsOnlineOrders())
            .as("lp2: every Shopify location Traced creates has fulfillsOnlineOrders=true")
            .isTrue();

        String shopifyLocId = jdbc.queryForObject(
            "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND name = 'Warehouse LP2'",
            String.class, tenantId);
        assertThat(shopifyLocId).isEqualTo("gid://shopify/Location/lp2");
    }

    // ── lp3: idempotency — re-running provisioning creates no duplicate ──────

    @Test
    void lp3_reRunningProvisioning_noDuplicateShopifyLocation() {
        when(shopifyLocations.findByName(eq(SHOP_DOMAIN), eq("test-token"), anyString()))
            .thenReturn(Optional.empty());
        when(shopifyLocations.create(eq(SHOP_DOMAIN), eq("test-token"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult("gid://shopify/Location/lp3", "Traced Main Warehouse"));

        provisioningService.ensureTracedWarehouse(tenantId, storeId, SHOP_DOMAIN);
        provisioningService.ensureTracedWarehouse(tenantId, storeId, SHOP_DOMAIN);
        provisioningService.ensureTracedWarehouse(tenantId, storeId, SHOP_DOMAIN);

        verify(shopifyLocations, times(1)).create(any(), any(), any());

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            Long.class, tenantId);
        assertThat(count).as("lp3: exactly one fulfillment location, never duplicated").isEqualTo(1L);

        String gid = jdbc.queryForObject(
            "SELECT shopify_location_id FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            String.class, tenantId);
        assertThat(gid).isEqualTo("gid://shopify/Location/lp3");
        String status = jdbc.queryForObject(
            "SELECT shopify_sync_status FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            String.class, tenantId);
        assertThat(status).isEqualTo("linked");
    }

    // ── lp4: Shopify-first onboarding — zero locations exist beforehand ──────

    @Test
    void lp4_shopifyFirstOnboarding_createsInternalLocationThenLinks() {
        Long before = jdbc.queryForObject(
            "SELECT COUNT(*) FROM locations WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(before).isZero();

        when(shopifyLocations.findByName(eq(SHOP_DOMAIN), eq("test-token"), anyString()))
            .thenReturn(Optional.empty());
        when(shopifyLocations.create(eq(SHOP_DOMAIN), eq("test-token"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult("gid://shopify/Location/lp4", "Traced Main Warehouse"));

        provisioningService.ensureTracedWarehouse(tenantId, storeId, SHOP_DOMAIN);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT name, is_default, is_fulfillment, shopify_location_id, shopify_sync_status " +
            "FROM locations WHERE tenant_id = ?", tenantId);
        assertThat(row.get("name")).isEqualTo("Main Warehouse");
        assertThat(row.get("is_default")).isEqualTo(true);
        assertThat(row.get("is_fulfillment")).isEqualTo(true);
        assertThat(row.get("shopify_location_id")).isEqualTo("gid://shopify/Location/lp4");
        assertThat(row.get("shopify_sync_status")).isEqualTo("linked");
    }

    // ── lp5: standalone onboarding — internal location already exists ───────

    @Test
    void lp5_standaloneOnboarding_linksExistingLocationWithoutDuplicating() {
        UUID existingId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            existingId, tenantId);

        when(shopifyLocations.findByName(eq(SHOP_DOMAIN), eq("test-token"), anyString()))
            .thenReturn(Optional.empty());
        when(shopifyLocations.create(eq(SHOP_DOMAIN), eq("test-token"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult("gid://shopify/Location/lp5", "Traced Main Warehouse"));

        provisioningService.ensureTracedWarehouse(tenantId, storeId, SHOP_DOMAIN);

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM locations WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(count).as("lp5: no second location created").isEqualTo(1L);

        String gid = jdbc.queryForObject(
            "SELECT shopify_location_id FROM locations WHERE id = ?", String.class, existingId);
        assertThat(gid).isEqualTo("gid://shopify/Location/lp5");
    }

    // ── lp6: junk report + guarded per-location cleanup ──────────────────────

    @Test
    void lp6_junkReportAndGuardedCleanup() {
        UUID junkId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, " +
            "    shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Junk Test WH', 'warehouse', false, false, " +
            "    'gid://shopify/Location/junk', 'linked')",
            junkId, tenantId);

        TenantContext.set(tenantId);
        try {
            List<Map<String, Object>> report = asOwner(controller::shopifyJunkReport);
            assertThat(report).extracting(r -> r.get("id")).contains(junkId);

            asOwner(() -> controller.shopifyCleanup(junkId));
        } finally {
            TenantContext.clear();
        }

        verify(shopifyLocations).deactivate(eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/Location/junk"), any());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT shopify_location_id, shopify_sync_status FROM locations WHERE id = ?", junkId);
        assertThat(row.get("shopify_location_id")).isNull();
        assertThat(row.get("shopify_sync_status")).isEqualTo("unsynced");
    }

    @Test
    void lp6b_cleanupRefusesFulfillmentLocation() {
        UUID fulfillmentId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, " +
            "    shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, " +
            "    'gid://shopify/Location/mainwh', 'linked')",
            fulfillmentId, tenantId);

        TenantContext.set(tenantId);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> asOwner(() -> controller.shopifyCleanup(fulfillmentId)));
        } finally {
            TenantContext.clear();
        }
        verify(shopifyLocations, never()).deactivate(any(), any(), any(), any());
    }

    // ── lp7: GENUINE concurrency — two overlapping ensureTracedWarehouse calls ──
    //
    // Deterministic, not timing-dependent: the winner is deliberately blocked inside
    // linkShopifyLocationIfNeeded (via a blocking tokenProvider stub) AFTER its claim UPDATE
    // has already committed the row to shopify_sync_status='pending' — verified by reading
    // it back — and BEFORE it ever calls Shopify. A second, concurrent ensureTracedWarehouse
    // call for the SAME tenant at that exact moment must hit the claim conflict (status not
    // in unsynced/error) and return without ever calling Shopify.

    @Test
    void lp7_concurrentEnsureTracedWarehouse_shopifyCreateCalledExactlyOnce() throws Exception {
        CountDownLatch winnerInsideLink = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        when(tokenProvider.getValidToken(storeId)).thenAnswer(invocation -> {
            winnerInsideLink.countDown();
            releaseWinner.await(5, TimeUnit.SECONDS);
            return "test-token";
        });
        when(shopifyLocations.findByName(eq(SHOP_DOMAIN), eq("test-token"), anyString()))
            .thenReturn(Optional.empty());
        when(shopifyLocations.create(eq(SHOP_DOMAIN), eq("test-token"), any()))
            .thenReturn(new ShopifyLocationGateway.LocationResult("gid://shopify/Location/lp7", "Traced Main Warehouse"));

        Thread winner = new Thread(() ->
            provisioningService.ensureTracedWarehouse(tenantId, storeId, SHOP_DOMAIN));
        winner.start();

        assertThat(winnerInsideLink.await(5, TimeUnit.SECONDS))
            .as("lp7: winner must reach the token call before the duplicate fires")
            .isTrue();

        String statusWhilePending = jdbc.queryForObject(
            "SELECT shopify_sync_status FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            String.class, tenantId);
        assertThat(statusWhilePending)
            .as("lp7: claim already committed as pending BEFORE Shopify is ever called")
            .isEqualTo("pending");

        // Concurrent duplicate call for the SAME tenant, fired while the winner is pending.
        provisioningService.ensureTracedWarehouse(tenantId, storeId, SHOP_DOMAIN);

        releaseWinner.countDown();
        winner.join(10_000);

        verify(shopifyLocations, times(1)).create(any(), any(), any());
        verify(shopifyLocations, times(1)).findByName(any(), any(), any());

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            Long.class, tenantId);
        assertThat(count).as("lp7: still exactly one fulfillment location").isEqualTo(1L);
        String finalStatus = jdbc.queryForObject(
            "SELECT shopify_sync_status FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            String.class, tenantId);
        assertThat(finalStatus).isEqualTo("linked");
    }
}
