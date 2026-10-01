package com.traceability;

import com.traceability.account.AuditService;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * S1 — product image on every pack line. GET /fulfill/{id} (FulfillService.getOrder(), the only
 * caller of getItemsWithAllocations()) carries each line's products.image_url as "imageUrl".
 *
 * Runs as app_user under the tenant GUC (RLS enforced on orders, order_items, variants, products).
 *
 *   a) product with image_url    → line imageUrl = that URL; line keys otherwise unchanged
 *   b) product without image_url → imageUrl present and null
 *   c) cross-tenant: tenant B cannot read tenant A's order (404) — positive control a) is same-tenant
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackLineImageTest {

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
    }

    private static final String IMAGE =
        "https://cdn.shopify.com/s/files/1/0000/0001/products/shirt.jpg?v=1";

    @Autowired JdbcTemplate    jdbc;            // postgres — fixtures only
    @Autowired InventoryLedger ledger;
    @Autowired AuditService    auditService;
    @MockBean  JobScheduler    jobScheduler;

    private TransactionTemplate appUserTx;
    private FulfillService      appUserFulfill;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserTx      = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appUserFulfill = new FulfillService(new JdbcTemplate(appDs), ledger, auditService, 30);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    @Test
    void a_productWithImage_lineCarriesImageUrl() {
        UUID tenant = tenant("PackImageSet");
        UUID store = store(tenant);
        UUID orderId = order(tenant, store);
        orderItem(tenant, orderId, variant(tenant, product(tenant, store, "Linen shirt", IMAGE), "Olive / M", "LS-OLV-M"), 1);

        List<Map<String, Object>> items = items(tenant, orderId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("imageUrl")).isEqualTo(IMAGE);
        // Shape: only imageUrl added — every pre-S1 key still there, nothing else new.
        assertThat(items.get(0).keySet()).containsExactlyInAnyOrder(
            "id", "variant_id", "sku", "variant_title", "product_title", "quantity",
            "imageUrl", "allocated", "allocatedPieces");
    }

    @Test
    void b_productWithoutImage_imageUrlNull() {
        UUID tenant = tenant("PackImageNull");
        UUID store = store(tenant);
        UUID orderId = order(tenant, store);
        orderItem(tenant, orderId, variant(tenant, product(tenant, store, "Cargo pants", IMAGE), "Black / 32", "CP-BLK-32"), 2);
        orderItem(tenant, orderId, variant(tenant, product(tenant, store, "No photo", null), "Default Title", "NP-1"), 1);

        List<Map<String, Object>> items = items(tenant, orderId);
        assertThat(items).hasSize(2);
        Map<String, Object> withImage = items.stream()
            .filter(i -> "Cargo pants".equals(i.get("product_title"))).findFirst().orElseThrow();
        Map<String, Object> without = items.stream()
            .filter(i -> "No photo".equals(i.get("product_title"))).findFirst().orElseThrow();
        assertThat(withImage.get("imageUrl")).isEqualTo(IMAGE);
        assertThat(without).containsKey("imageUrl");
        assertThat(without.get("imageUrl")).isNull();
    }

    @Test
    void c_crossTenant_orderNotVisible() {
        UUID tenantA = tenant("PackImageA");
        UUID tenantB = tenant("PackImageB");
        UUID store = store(tenantA);
        UUID orderId = order(tenantA, store);
        orderItem(tenantA, orderId, variant(tenantA, product(tenantA, store, "Secret", IMAGE), "Default Title", "S-1"), 1);

        // Same-tenant positive control.
        assertThat(items(tenantA, orderId)).hasSize(1);
        // Tenant B: RLS hides the order entirely → 404, no image URL leaks.
        assertThatThrownBy(() -> TenantContext.runAs(tenantB, () ->
                appUserTx.execute(s -> appUserFulfill.getOrder(orderId))))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Order not found");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(UUID tenant, UUID orderId) {
        Map<String, Object> order = TenantContext.runAs(tenant, () ->
            appUserTx.execute(s -> appUserFulfill.getOrder(orderId)));
        return (List<Map<String, Object>>) order.get("items");
    }

    private UUID tenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    private UUID store(UUID tenant) {
        UUID storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', ?, 'disconnected')",
                    storeId, tenant, "pi-" + storeId + ".myshopify.com");
        return storeId;
    }

    private UUID order(UUID tenant, UUID storeId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, 'ready_to_pick'::order_status) RETURNING id",
            UUID.class, tenant, storeId, "EXT-" + UUID.randomUUID());
    }

    private UUID product(UUID tenant, UUID storeId, String title, String imageUrl) {
        return jdbc.queryForObject(
            "INSERT INTO products (tenant_id, store_id, external_id, title, status, image_url) " +
            "VALUES (?, ?, ?, ?, 'active', ?) RETURNING id",
            UUID.class, tenant, storeId, "gid://shopify/Product/" + UUID.randomUUID(), title, imageUrl);
    }

    private UUID variant(UUID tenant, UUID productId, String title, String sku) {
        return jdbc.queryForObject(
            "INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenant, productId, "gid://shopify/ProductVariant/" + UUID.randomUUID(), sku, title);
    }

    private void orderItem(UUID tenant, UUID orderId, UUID variantId, int qty) {
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?)",
                    tenant, orderId, variantId, qty);
    }
}
