package com.traceability;

import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShopifyCatalogActivationService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Part B — bulk inventoryActivate at the Traced Main Warehouse GID.
 *
 * Matrix:
 *   pb1 — activates every catalog variant, all at the Traced GID (not any other locationId)
 *   pb2 — already-active is tolerated: gateway not throwing for a repeat call doesn't
 *         distinguish new-vs-already-active, so re-running the whole batch is safe
 *   pb3 — one variant's activation failing does not abort the rest of the batch
 *   pb4 — no linked Traced location yet -> 409, no gateway calls at all
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyCatalogActivationTest {

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

    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifyCatalogActivationService activationService;

    UUID tenantId, storeId, productId, locationId;
    static final String SHOP_DOMAIN = "pb-test.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/pb-traced";

    @BeforeEach
    void setup() {
        tenantId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        productId  = UUID.randomUUID();
        locationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PB Tenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')",
            storeId, tenantId, SHOP_DOMAIN);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/pb', 'PB Product', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)",
            locationId, tenantId, TRACED_GID);

        when(tokenProvider.getValidToken(storeId)).thenReturn("test-token");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM products WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID seedVariant(String externalId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, ?, 'Variant', ?)",
            id, tenantId, productId, externalId, "SKU-" + externalId.hashCode());
        return id;
    }

    @Test
    void pb1_activatesEveryVariantAtTracedGid() {
        UUID v1 = seedVariant("gid://shopify/ProductVariant/pb1");
        UUID v2 = seedVariant("gid://shopify/ProductVariant/pb2");

        when(shopifyGateway.resolveInventoryItemId(eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/ProductVariant/pb1")))
            .thenReturn("gid://shopify/InventoryItem/pb1");
        when(shopifyGateway.resolveInventoryItemId(eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/ProductVariant/pb2")))
            .thenReturn("gid://shopify/InventoryItem/pb2");

        TenantContext.set(tenantId);
        try {
            var outcome = activationService.activateAll();
            assertThat(outcome.total()).isEqualTo(2);
            assertThat(outcome.succeeded()).isEqualTo(2);
            assertThat(outcome.failed()).isZero();
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway).activateInventoryItem(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/pb1"), eq(TRACED_GID), any());
        verify(shopifyGateway).activateInventoryItem(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/pb2"), eq(TRACED_GID), any());
        // Never any other locationId.
        verify(shopifyGateway, never())
            .activateInventoryItem(any(), any(), any(), argThat(loc -> !TRACED_GID.equals(loc)), any());
    }

    @Test
    void pb2_reRunning_isTolerated() {
        UUID v1 = seedVariant("gid://shopify/ProductVariant/pb3");
        when(shopifyGateway.resolveInventoryItemId(any(), any(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/pb3");
        // No exception stubbed for activateInventoryItem on the second call — mirrors the
        // gateway's own "already active" tolerance (void method, no throw either way).

        TenantContext.set(tenantId);
        try {
            var first  = activationService.activateAll();
            var second = activationService.activateAll();
            assertThat(first.succeeded()).isEqualTo(1);
            assertThat(second.succeeded()).as("pb2: re-running the batch is tolerated, not an error").isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
        verify(shopifyGateway, times(2)).activateInventoryItem(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/pb3"), eq(TRACED_GID), any());
    }

    @Test
    void pb3_oneFailure_doesNotAbortTheBatch() {
        UUID good = seedVariant("gid://shopify/ProductVariant/pb-good");
        UUID bad  = seedVariant("gid://shopify/ProductVariant/pb-bad");

        when(shopifyGateway.resolveInventoryItemId(eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/ProductVariant/pb-good")))
            .thenReturn("gid://shopify/InventoryItem/pb-good");
        when(shopifyGateway.resolveInventoryItemId(eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/ProductVariant/pb-bad")))
            .thenThrow(new ShopifyException("productVariant not found"));

        TenantContext.set(tenantId);
        try {
            var outcome = activationService.activateAll();
            assertThat(outcome.total()).isEqualTo(2);
            assertThat(outcome.succeeded()).isEqualTo(1);
            assertThat(outcome.failed()).isEqualTo(1);
            assertThat(outcome.failures()).hasSize(1);
        } finally {
            TenantContext.clear();
        }
        verify(shopifyGateway).activateInventoryItem(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/pb-good"), eq(TRACED_GID), any());
    }

    @Test
    void pb4_noLinkedTracedLocation_conflictNoGatewayCalls() {
        jdbc.update("UPDATE locations SET shopify_sync_status = 'unsynced', shopify_location_id = NULL WHERE id = ?", locationId);
        seedVariant("gid://shopify/ProductVariant/pb4");

        TenantContext.set(tenantId);
        try {
            Assertions.assertThrows(ResponseStatusException.class, activationService::activateAll);
        } finally {
            TenantContext.clear();
        }
        verifyNoInteractions(shopifyGateway);
    }
}
