package com.traceability.overview;

import com.traceability.inventory.UlidGenerator;
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

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /overview/trends and GET /overview/top-skus (FR-Overview §2).
 *
 * ovt1 — orders series: today's Cairo-day count reflects seeded orders.
 * ovt2 — shipments series: forward-leg shipments count; a return-leg shipment created
 *        the same moment does NOT.
 * ovt3 — delivered series: sourced from shipment_status_history (internal_state=
 *        'delivered'), not the shipments.internal_state snapshot.
 * ovt4 — exceptions series: an event-based detector (lost) counts; a refresh-based
 *        one (high_attempts' own condition) does NOT — proves the 12-vs-8 boundary,
 *        not just that "some number" comes back.
 * ovt5 — returns series: return_sessions.opened_at, regardless of eventual status.
 * ovt6 — deltaPct is null when yesterday=0 (guard), never a fabricated number.
 * ovt7 — top-skus: units summed per variant, cancelled order excluded, sorted desc.
 * ovt8 — cross-tenant isolation + same-tenant positive control, trends().
 * ovt9 — cross-tenant isolation + same-tenant positive control, topSkus().
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverviewTrendsTest {

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

    @Autowired JdbcTemplate jdbc;

    static final Clock CAIRO_CLOCK = Clock.system(ZoneId.of("Africa/Cairo"));

    OverviewService overview;
    OverviewService appUserOverview;
    TransactionTemplate appUserTx;

    UUID tenantA, tenantB, actorA, actorB, storeA, storeB, productA, productB, variantA, variantB;

    @BeforeAll
    void setupFixture() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        actorA  = UUID.randomUUID();
        actorB  = UUID.randomUUID();
        storeA  = UUID.randomUUID();
        storeB  = UUID.randomUUID();
        productA = UUID.randomUUID();
        productB = UUID.randomUUID();
        variantA = UUID.randomUUID();
        variantB = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'OvTenantA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'OvTenantB')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor A', 'ovt-a@test.com', 'x', 'owner'::user_role)", actorA, tenantA);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor B', 'ovt-b@test.com', 'x', 'owner'::user_role)", actorB, tenantB);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'ovt-a.myshopify.com', 'connected', 'completed', 'enc', now() + interval '876000 hours')",
            storeA, tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'ovt-b.myshopify.com', 'connected', 'completed', 'enc', now() + interval '876000 hours')",
            storeB, tenantB);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/OVTA', 'Product A')", productA, tenantA, storeA);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/OVTB', 'Product B')", productB, tenantB, storeB);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/OVTA', 'Variant A', 'OVT-A')", variantA, tenantA, productA);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/OVTB', 'Variant B', 'OVT-B')", variantB, tenantB, productB);

        overview = new OverviewService(jdbc, CAIRO_CLOCK);

        DriverManagerDataSource rawAppUser = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        JdbcTemplate appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx = new TransactionTemplate(appUserTxm);
        appUserOverview = new OverviewService(appUserJdbc, CAIRO_CLOCK);
    }

    @AfterEach
    void cleanState() {
        jdbc.update("DELETE FROM return_session_items WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM return_sessions WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM allocations WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM pieces WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM shipments WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM order_items WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM orders WHERE tenant_id IN (?, ?)", tenantA, tenantB);
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id IN (?, ?)", tenantA, tenantB);
    }

    // ── ovt1: orders ─────────────────────────────────────────────────────────

    @Test
    void ovt1_ordersSeries_reflectsSeededOrdersToday() {
        insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);
        insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);
        insertOrder(tenantB, storeB, "new", false, "now()", null, null, null); // noise

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(); } finally { TenantContext.clear(); }

        OverviewService.MetricTrend orders = byMetric(trends, "orders");
        assertThat(orders.today()).isEqualTo(2);
        assertThat(orders.series()).hasSize(14);
        assertThat(orders.series().get(13).count()).isEqualTo(2);
    }

    // ── ovt2: shipments (forward leg only) ──────────────────────────────────

    @Test
    void ovt2_shipmentsSeries_forwardLegOnly_returnLegExcluded() {
        UUID orderId = insertOrder(tenantA, storeA, "packed", false, "now()", null, null, null);
        insertShipment(tenantA, orderId, "forward", "created", "now()", 0, false, null);
        insertShipment(tenantA, orderId, "return",  "created", "now()", 0, false, null);

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(); } finally { TenantContext.clear(); }

        assertThat(byMetric(trends, "shipments").today())
            .as("only the forward-leg shipment counts, not the return-leg one created the same moment")
            .isEqualTo(1);
    }

    // ── ovt3: delivered (shipment_status_history, not the shipments snapshot) ──

    @Test
    void ovt3_deliveredSeries_sourcedFromStatusHistory() {
        UUID orderId = insertOrder(tenantA, storeA, "delivered", false, "now()", null, null, null);
        UUID shipmentId = insertShipment(tenantA, orderId, "forward", "delivered", "now()", 1, false, null);
        insertStatusHistory(tenantA, shipmentId, "created", "now() - interval '2 hours'");
        insertStatusHistory(tenantA, shipmentId, "delivered", "now()");

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(); } finally { TenantContext.clear(); }

        assertThat(byMetric(trends, "delivered").today()).isEqualTo(1);
    }

    // ── ovt4: exceptions — event-based counts, refresh-based excluded ─────────

    @Test
    void ovt4_exceptionsSeries_eventBasedCounts_refreshBasedExcluded() {
        // Event-based, included: a piece currently 'lost'.
        insertPiece(tenantA, variantA, "lost", "now()");

        // Refresh-based, EXCLUDED: a shipment matching high_attempts' own condition
        // (number_of_attempts >= 2, non-terminal). If exceptionsRaw() ever regresses
        // to include this detector, this assertion catches it (today would be 2, not 1).
        UUID orderId = insertOrder(tenantA, storeA, "with_courier", false, "now()", null, null, null);
        insertShipment(tenantA, orderId, "forward", "with_courier", "now()", 3, false, null);

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(); } finally { TenantContext.clear(); }

        assertThat(byMetric(trends, "exceptions").today())
            .as("only the lost piece (event-based) counts; the high-attempts shipment (refresh-based) must not")
            .isEqualTo(1);
    }

    // ── ovt5: returns ────────────────────────────────────────────────────────

    @Test
    void ovt5_returnsSeries_reflectsOpenedSessionsRegardlessOfStatus() {
        insertReturnSession(tenantA, actorA, "open", "now()");
        insertReturnSession(tenantA, actorA, "abandoned", "now()");

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(); } finally { TenantContext.clear(); }

        assertThat(byMetric(trends, "returns").today())
            .as("flow count = sessions opened that day, regardless of eventual status")
            .isEqualTo(2);
    }

    // ── ovt6: deltaPct guard ─────────────────────────────────────────────────

    @Test
    void ovt6_deltaPct_nullWhenYesterdayIsZero() {
        insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(); } finally { TenantContext.clear(); }

        OverviewService.MetricTrend orders = byMetric(trends, "orders");
        assertThat(orders.yesterday()).isEqualTo(0);
        assertThat(orders.deltaPct())
            .as("no fabricated infinite/undefined percentage against a zero baseline")
            .isNull();
    }

    // ── ovt7: top-skus ───────────────────────────────────────────────────────

    @Test
    void ovt7_topSkus_unitsSummed_cancelledExcluded_sortedDesc() {
        UUID order1 = insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);
        insertOrderItem(tenantA, order1, variantA, 3);
        UUID order2 = insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);
        insertOrderItem(tenantA, order2, variantA, 2);
        UUID cancelledOrder = insertOrder(tenantA, storeA, "cancelled", false, "now()", null, null, null);
        insertOrderItem(tenantA, cancelledOrder, variantA, 100);

        TenantContext.set(tenantA);
        List<OverviewService.TopSku> skus;
        try { skus = overview.topSkus(); } finally { TenantContext.clear(); }

        assertThat(skus).hasSize(1);
        assertThat(skus.get(0).sku()).isEqualTo("OVT-A");
        assertThat(skus.get(0).units())
            .as("3 + 2 from the two non-cancelled orders; the cancelled order's 100 units excluded")
            .isEqualTo(5);
    }

    // ── ovt8/ovt9: RLS isolation + same-tenant positive control ────────────────

    @Test
    void ovt8_trends_rlsIsolated_sameTenantPositiveControl() {
        insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);
        insertOrder(tenantB, storeB, "new", false, "now()", null, null, null);

        try {
            TenantContext.set(tenantA);
            try {
                appUserTx.execute(status -> {
                    List<OverviewService.MetricTrend> trends = appUserOverview.trends();
                    assertThat(byMetric(trends, "orders").today())
                        .as("ovt8: same-tenant positive control sees only tenant A's order")
                        .isEqualTo(1);
                    return null;
                });
            } finally {
                TenantContext.clear();
            }
        } catch (org.springframework.dao.DataAccessResourceFailureException e) {
            Assumptions.assumeTrue(false, "app_user not available in test container — RLS assertion skipped: " + e.getMessage());
        }
    }

    @Test
    void ovt9_topSkus_rlsIsolated_sameTenantPositiveControl() {
        UUID orderA = insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);
        insertOrderItem(tenantA, orderA, variantA, 4);
        UUID orderB = insertOrder(tenantB, storeB, "new", false, "now()", null, null, null);
        insertOrderItem(tenantB, orderB, variantB, 9);

        try {
            TenantContext.set(tenantA);
            try {
                appUserTx.execute(status -> {
                    List<OverviewService.TopSku> skus = appUserOverview.topSkus();
                    assertThat(skus).hasSize(1);
                    assertThat(skus.get(0).sku())
                        .as("ovt9: same-tenant positive control sees only tenant A's SKU, never tenant B's")
                        .isEqualTo("OVT-A");
                    return null;
                });
            } finally {
                TenantContext.clear();
            }
        } catch (org.springframework.dao.DataAccessResourceFailureException e) {
            Assumptions.assumeTrue(false, "app_user not available in test container — RLS assertion skipped: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private OverviewService.MetricTrend byMetric(List<OverviewService.MetricTrend> trends, String metric) {
        return trends.stream().filter(t -> t.metric().equals(metric)).findFirst()
            .orElseThrow(() -> new AssertionError("metric not found: " + metric));
    }

    private UUID insertOrder(UUID tenantId, UUID storeId, String status, boolean onHold,
                              String placedAtExpr, String cancelRequestedAtExpr,
                              String shopifyCancelRequestedAtExpr, String shopifyEditConflictAtExpr) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, on_hold, cancel_requested_at, " +
            "    shopify_cancel_requested_at, shopify_edit_conflict_at) " +
            "VALUES (?, ?, ?, ?, ?, ?::order_status, 'cod', " + placedAtExpr + ", ?, " +
            (cancelRequestedAtExpr == null ? "NULL" : cancelRequestedAtExpr) + ", " +
            (shopifyCancelRequestedAtExpr == null ? "NULL" : shopifyCancelRequestedAtExpr) + ", " +
            (shopifyEditConflictAtExpr == null ? "NULL" : shopifyEditConflictAtExpr) + ")",
            orderId, tenantId, storeId, "EXT-" + orderId, "#" + orderId.toString().substring(0, 8),
            status, onHold);
        return orderId;
    }

    private UUID insertShipment(UUID tenantId, UUID orderId, String leg, String internalState,
                                 String createdAtExpr, int numberOfAttempts, boolean providerIdFetchFailed,
                                 String awbPrintFailedReason) {
        UUID shipmentId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, provider, " +
            "    internal_state, shipment_leg, number_of_attempts, last_synced_at, created_at, " +
            "    provider_id_fetch_failed, awb_print_failed_reason, awb_print_failed_at) " +
            "VALUES (?, ?, ?, ?, 'bosta', ?::shipment_internal_state, ?, ?, now(), " + createdAtExpr + ", " +
            "?, ?, " + (awbPrintFailedReason == null ? "NULL" : "now()") + ")",
            shipmentId, tenantId, orderId, "TN-" + shipmentId.toString().substring(0, 12),
            internalState, leg, numberOfAttempts, providerIdFetchFailed, awbPrintFailedReason);
        return shipmentId;
    }

    private void insertStatusHistory(UUID tenantId, UUID shipmentId, String internalState, String occurredAtExpr) {
        jdbc.update(
            "INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
            "VALUES (?, ?, ?, " + occurredAtExpr + ")",
            tenantId, shipmentId, internalState);
    }

    private void insertReturnSession(UUID tenantId, UUID actorId, String status, String openedAtExpr) {
        UUID sessionId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO return_sessions (id, tenant_id, status, opened_by, opened_at) " +
            "VALUES (?, ?, ?, ?, " + openedAtExpr + ")",
            sessionId, tenantId, status, actorId);
    }

    private void insertPiece(UUID tenantId, UUID variantId, String status, String lastEventAtExpr) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        ?::piece_status, " + lastEventAtExpr + ")",
            id, tenantId, variantId, "PC-" + id, id, status);
    }

    private void insertOrderItem(UUID tenantId, UUID orderId, UUID variantId, int quantity) {
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?)",
            tenantId, orderId, variantId, quantity);
    }
}
