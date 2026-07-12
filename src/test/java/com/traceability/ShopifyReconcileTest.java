package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.shopify.*;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-3.4: Shopify reconciliation poll integration tests.
 *
 * ShopifyTokenProvider and ShopifyGateway are @MockBean so tests control what
 * Shopify "returns" without needing real credentials or token encryption.
 * ShopifyReconcileJob and ShopifySyncService use the real (DB-backed) code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "org.jobrunr.background-job-server.enabled=true")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyReconcileTest {

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

    @Autowired ShopifyReconcileJob reconcileJob;
    @Autowired ShopifySyncService  syncService;
    @Autowired JdbcTemplate        jdbc;
    @MockBean  ShopifyGateway      shopifyGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler         jobScheduler;

    UUID tenantA, tenantB, storeA, storeB;
    private static final String FAKE_TOKEN = "shpat_fake_reconcile_token";

    @BeforeAll
    void setupFixture() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        storeA  = UUID.randomUUID();
        storeB  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RecA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RecB')", tenantB);

        // storeA: connected + completed — will be included in reconcile query
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'reca.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeA, tenantA);

        // storeB: connected + completed — different tenant
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'recb.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeB, tenantB);
    }

    @BeforeEach
    void resetMocks() {
        // @MockBean stubs are reset between tests — re-apply the token stub each time.
        when(tokenProvider.getValidToken(any())).thenReturn(FAKE_TOKEN);
        // Default: both stores return empty order list (tests override as needed).
        when(shopifyGateway.fetchOrdersPage(eq("reca.myshopify.com"), eq(FAKE_TOKEN), isNull(), anyString()))
            .thenReturn(new ShopifyGateway.OrderPage(List.of(), false, null));
        when(shopifyGateway.fetchOrdersPage(eq("recb.myshopify.com"), eq(FAKE_TOKEN), isNull(), anyString()))
            .thenReturn(new ShopifyGateway.OrderPage(List.of(), false, null));
    }

    @AfterEach
    void cleanOrders() {
        jdbc.update("DELETE FROM order_items WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM orders WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    // r1: order missing locally → ingested after reconcile
    @Test
    void r1_missingOrder_isIngested() {
        ShopifyGateway.Order o = order("gid://shopify/Order/R1", "#R1001");
        stubGateway(storeA, "reca.myshopify.com", List.of(o));

        reconcileJob.reconcile();

        TenantContext.set(tenantA);
        try {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE tenant_id=? AND store_id=? AND external_id=?",
                Integer.class, tenantA, storeA, "gid://shopify/Order/R1");
            assertThat(count).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }

    // r2: existing mid-pick order → reconcile skips it; status unchanged
    @Test
    void r2_existingMidPickOrder_isUntouched() {
        // Insert order in ready_to_pick state (mid-pick)
        String gid = "gid://shopify/Order/R2";
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#R2001', 'ready_to_pick'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantA, storeA, gid);

        // Shopify returns the same order
        ShopifyGateway.Order o = order(gid, "#R2001");
        stubGateway(storeA, "reca.myshopify.com", List.of(o));

        reconcileJob.reconcile();

        // Status must remain ready_to_pick, not reset to 'new'
        String status = jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
        assertThat(status).isEqualTo("ready_to_pick");

        // Exactly one row — no duplicate inserted
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id=? AND store_id=? AND external_id=?",
            Integer.class, tenantA, storeA, gid);
        assertThat(count).isEqualTo(1);
    }

    // r3: disconnected store → skipped; no order ingested
    @Test
    void r3_disconnectedStore_skipped() {
        UUID storeDisc = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'disc.myshopify.com', 'disconnected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeDisc, tenantA);

        // Even if gateway were called it would return an order — but it must NOT be called
        ShopifyGateway.Order o = order("gid://shopify/Order/R3", "#R3001");
        stubGateway(storeA, "reca.myshopify.com", List.of()); // storeA returns nothing
        when(shopifyGateway.fetchOrdersPage(eq("disc.myshopify.com"), any(), any(), any()))
            .thenReturn(new ShopifyGateway.OrderPage(List.of(o), false, null));

        reconcileJob.reconcile();

        // disc.myshopify.com must never have been queried
        verify(shopifyGateway, never()).fetchOrdersPage(eq("disc.myshopify.com"), any(), any(), any());

        // Clean up
        jdbc.update("DELETE FROM stores WHERE id = ?", storeDisc);
    }

    // r4: simultaneous webhook+poll race → single order row (ON CONFLICT idempotency)
    @Test
    void r4_simultaneousWebhookAndPoll_singleOrderRow() {
        String gid = "gid://shopify/Order/R4";
        ShopifyGateway.Order o = order(gid, "#R4001");
        stubGateway(storeA, "reca.myshopify.com", List.of(o));

        // Simulate webhook racing the poll: ingest the order twice (once each path)
        TenantContext.set(tenantA);
        try {
            syncService.ingestMissingOrder(storeA, tenantA, o); // webhook path
            syncService.ingestMissingOrder(storeA, tenantA, o); // poll path (duplicate)
        } finally {
            TenantContext.clear();
        }

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id=? AND store_id=? AND external_id=?",
            Integer.class, tenantA, storeA, gid);
        assertThat(count).isEqualTo(1);
    }

    // r5: tenant isolation — reconcile for storeA doesn't ingest storeB orders
    @Test
    void r5_tenantIsolation_storeADoesNotIngestStoreBOrders() {
        String gidA = "gid://shopify/Order/R5A";
        String gidB = "gid://shopify/Order/R5B";

        stubGateway(storeA, "reca.myshopify.com", List.of(order(gidA, "#R5A")));
        stubGateway(storeB, "recb.myshopify.com", List.of(order(gidB, "#R5B")));

        reconcileJob.reconcile();

        // Each order must be in its own tenant's scope
        Integer countA = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id=? AND external_id=?",
            Integer.class, tenantA, gidA);
        Integer countB = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id=? AND external_id=?",
            Integer.class, tenantB, gidB);
        assertThat(countA).isEqualTo(1);
        assertThat(countB).isEqualTo(1);

        // storeA's order must NOT appear under tenantB and vice versa
        Integer crossA = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id=? AND external_id=?",
            Integer.class, tenantB, gidA);
        Integer crossB = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE tenant_id=? AND external_id=?",
            Integer.class, tenantA, gidB);
        assertThat(crossA).isZero();
        assertThat(crossB).isZero();
    }

    // r6: Shopify sync must NOT null-out a customer_name already written by Bosta.
    //     Regression for the PII-flicker bug: on Shopify Basic with PCD pending,
    //     Shopify returns null for customer name; previous UPSERT would write
    //     customer_name = NULL and erase the Bosta-sourced value every 30 minutes.
    @Test
    void r6_shopifySyncDoesNotOverwriteBostaSourcedPii() {
        // 1. Insert an order whose customer_name was populated from Bosta (pii_source='bosta').
        String gid = "gid://shopify/Order/R6";
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, '#R6001', 'new'::order_status, 'cod', now(), " +
            "    'Bosta Customer', '01099999999', 'bosta') RETURNING id",
            UUID.class, tenantA, storeA, gid);

        // 2. Shopify returns the same order but with null customer name/phone
        //    (simulating PCD-gated Shopify Basic response).
        ShopifyGateway.Order pcdBlocked = new ShopifyGateway.Order(
            gid, "#R6001",
            null, null,                 // ← null name / null phone (PCD-blocked)
            null, "pending", List.of(),
            new BigDecimal("50.00"), List.of(),
            Instant.now(), new ObjectMapper().createObjectNode());

        TenantContext.set(tenantA);
        try {
            // Directly call the UPSERT path (same path used by reconcile + webhook sync).
            syncService.ingestMissingOrder(storeA, tenantA, pcdBlocked);
        } finally {
            TenantContext.clear();
        }

        // 3. Bosta-sourced PII must be preserved — COALESCE keeps existing non-null value.
        String name  = jdbc.queryForObject(
            "SELECT customer_name  FROM orders WHERE id = ?", String.class, orderId);
        String phone = jdbc.queryForObject(
            "SELECT customer_phone FROM orders WHERE id = ?", String.class, orderId);
        assertThat(name) .as("customer_name must not be overwritten by Shopify null").isEqualTo("Bosta Customer");
        assertThat(phone).as("customer_phone must not be overwritten by Shopify null").isEqualTo("01099999999");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubGateway(UUID storeId, String domain, List<ShopifyGateway.Order> orders) {
        when(shopifyGateway.fetchOrdersPage(eq(domain), eq(FAKE_TOKEN), isNull(), anyString()))
            .thenReturn(new ShopifyGateway.OrderPage(orders, false, null));
    }

    private static ShopifyGateway.Order order(String gid, String name) {
        ObjectNode addr = new ObjectMapper().createObjectNode()
            .put("address1", "1 Test St").put("city", "Cairo");
        return new ShopifyGateway.Order(
            gid, name, "Test Customer", "+201001234567",
            addr, "pending", List.of(),
            new BigDecimal("50.00"), List.of(),
            Instant.now(), new ObjectMapper().createObjectNode());
    }
}
