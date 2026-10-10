package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-10-11 — piece sync on a REAL app_user connection (RLS enforced). On app_user the tenant GUC is set
 * only when a transaction begins (TenantAwareConnection); the other piece-sync tests connect as postgres
 * (BYPASSRLS) and could not catch a read made outside one. Fixtures are written as postgres.
 *
 *   u2 PieceShopifySweepJob picks up a queued piece claim and sends it, under app_user
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PieceSyncAppUserTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;               // postgres — fixtures only
    @Autowired ObjectMapper mapper;
    @Autowired @FlywayDataSource DataSource ownerDs;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    /** The code under test, wired on app_user. */
    ShopifyInventoryService appInventory;

    record T(UUID tenant, UUID location, String traced, UUID user, UUID variant, String item) {}

    @BeforeAll
    void appUser() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        appInventory = new ShopifyInventoryService(appJdbc, appTxm, shopifyGateway, tokenProvider, mapper,
            new StoreRepository(appJdbc, appTxm));
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void u2_sweepJobUnderAppUser_sendsAQueuedPieceClaim() throws Exception {
        T t = tenant("sweep", UUID.randomUUID());
        String piece = "01APPUSERSWEEP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        piece(t, piece, "on_hold");
        // A claim the after-commit push never sent (a crash) — the sweep's job; queued claims are swept
        // once a minute old, and only claims made after piece_sync_cutoff() count.
        awaitCutoffOlderThan(62);
        String triggerId = piece + ":" + UUID.randomUUID();
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, -1, 'hold_enter', ?, 'queued', " +
            "now() - interval '61 seconds')", t.tenant(), UUID.randomUUID(), t.variant(), t.location(), triggerId);

        new PieceShopifySweepJob(appInventory, ownerDs).run();

        assertThat(jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE trigger_id = ?",
            String.class, triggerId)).isEqualTo("applied");
        verify(shopifyGateway, times(1)).pushHoldEnter(any(), any(), eq(t.item()), eq(t.traced()), eq(-1),
            eq("traced://piece/" + piece), anyString());
    }

    // ── fixtures (postgres) ───────────────────────────────────────────────────────

    private void awaitCutoffOlderThan(int seconds) throws InterruptedException {
        Double age = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM now() - piece_sync_cutoff())", Double.class);
        if (age != null && age < seconds) Thread.sleep((long) ((seconds - age) * 1000) + 500);
    }

    private T tenant(String name, UUID tenant) {
        UUID store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        String item = "gid://shopify/InventoryItem/" + name + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", location, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shop, "SKU-" + name, item);
        // Seeded an hour ago (pieces below were received a day ago — before it, so Shopify counts them).
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, 1, 'initial_seed', ?, 'applied', now() - interval '1 hour')",
            tenant, UUID.randomUUID(), variant, location, "fixture-seed:" + UUID.randomUUID());
        return new T(tenant, location, traced, user, variant, item);
    }

    private void piece(T t, String id, String status) {
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, now() - interval '1 day')",
            id, t.tenant(), t.variant(), status, "AU-" + id, "U" + Math.abs(id.hashCode() % 9_000_000 + 1_000_000), t.location());
    }
}
