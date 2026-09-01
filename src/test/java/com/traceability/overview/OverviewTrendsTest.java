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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GET /overview/trends, GET /overview/late-to-pack, and GET /overview/top-skus
 * (FR-Overview §2, extended for the date-range picker + COD-delivered + Late-to-pack
 * behavior change).
 *
 * ovt1  — orders total: default range (no from/to) is Last-7-days and reflects seeded
 *         orders; series stays a fixed 14-day trailing window regardless.
 * ovt2  — cod_delivered total: orders.cod_amount summed once per order delivered in
 *         range, deduped per order even when the order has 2 forward-leg shipments
 *         that both independently reach 'delivered' — proves the per-order GROUP BY
 *         dedup, not just that "some number" comes back. A prepaid order (cod_amount
 *         NULL) contributes 0, not a null-poisoned sum.
 * ovt3  — delivered total: sourced from shipment_status_history (internal_state=
 *         'delivered'), not the shipments.internal_state snapshot; same per-order
 *         dedup as cod_delivered.
 * ovt4  — exceptions total: an event-based detector (lost) counts; a refresh-based
 *         one (high_attempts' own condition) does NOT — proves the 12-vs-8 boundary,
 *         not just that "some number" comes back.
 * ovt5  — returns total: return_sessions.opened_at, regardless of eventual status.
 * ovt6  — from/to range restricts `total` but never `series` — an order placed 10
 *         days ago is excluded from a Last-7-days total but still shows up in the
 *         fixed 14-day sparkline.
 * ovt7  — top-skus: units summed per variant, cancelled order excluded, sorted desc.
 * ovt8  — cross-tenant isolation + same-tenant positive control, trends().
 * ovt9  — cross-tenant isolation + same-tenant positive control, topSkus().
 * ovt10 — late-to-pack: an order placed 30h ago in a pre-pack status counts toward
 *         overdue but not over48; one placed 50h ago counts toward both; a 'packed'
 *         order past 24h does NOT count (already past pre-pack); on-hold does not
 *         exclude an otherwise-overdue pre-pack order.
 * ovt11 — trends: malformed from/to → 400, not a 500 or a silently-ignored param.
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

    // ── ovt1: orders — default range (no from/to) is Last-7-days ──────────────

    @Test
    void ovt1_ordersTotal_defaultRangeReflectsSeededOrders() {
        insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);
        insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);
        insertOrder(tenantB, storeB, "new", false, "now()", null, null, null); // noise

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(null, null); } finally { TenantContext.clear(); }

        OverviewService.MetricTrend orders = byMetric(trends, "orders");
        assertThat(orders.total()).isEqualTo(2.0);
        assertThat(orders.series()).hasSize(14);
        assertThat(orders.series().get(13).count()).isEqualTo(2);
    }

    // ── ovt2: cod_delivered — per-order dedup, prepaid contributes 0 ──────────

    @Test
    void ovt2_codDeliveredTotal_dedupsPerOrder_prepaidContributesZero() {
        // Order 1: cod=250, TWO forward-leg shipments (reship scenario) whose
        // shipment_status_history BOTH independently recorded a 'delivered' event —
        // must be summed ONCE, not twice. ship1a's own internal_state is set to
        // 'terminated' (not 'delivered') solely to satisfy
        // ux_active_shipment_per_order_leg (V43, only excludes terminated/cancelled),
        // which otherwise blocks two live forward shipments on one order — its
        // shipment_status_history 'delivered' row (append-only, never deleted) still
        // stands, modeling a shipment that was delivered then later corrected/
        // terminated before a reship, which is exactly the edge case the per-order
        // dedup in OverviewService guards against.
        UUID order1 = insertOrder(tenantA, storeA, "delivered", false, "now()", null, null, null);
        jdbc.update("UPDATE orders SET cod_amount = 250 WHERE id = ?", order1);
        UUID ship1a = insertShipment(tenantA, order1, "forward", "terminated", "now() - interval '3 hours'", 1, false, null);
        insertStatusHistory(tenantA, ship1a, "delivered", "now() - interval '3 hours'");
        UUID ship1b = insertShipment(tenantA, order1, "forward", "delivered", "now()", 1, false, null);
        insertStatusHistory(tenantA, ship1b, "delivered", "now()");

        // Order 2: prepaid (cod_amount NULL) — delivered, contributes 0, not a
        // null-poisoned sum.
        UUID order2 = insertOrder(tenantA, storeA, "delivered", false, "now()", null, null, null);
        UUID ship2 = insertShipment(tenantA, order2, "forward", "delivered", "now()", 1, false, null);
        insertStatusHistory(tenantA, ship2, "delivered", "now()");

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(null, null); } finally { TenantContext.clear(); }

        assertThat(byMetric(trends, "cod_delivered").total())
            .as("order1's 250 counted once despite 2 delivered shipments; prepaid order2 contributes 0")
            .isEqualTo(250.0);
    }

    // ── ovt3: delivered (shipment_status_history, not the shipments snapshot) ──

    @Test
    void ovt3_deliveredTotal_sourcedFromStatusHistory_dedupedPerOrder() {
        UUID orderId = insertOrder(tenantA, storeA, "delivered", false, "now()", null, null, null);
        UUID shipmentId = insertShipment(tenantA, orderId, "forward", "delivered", "now()", 1, false, null);
        insertStatusHistory(tenantA, shipmentId, "created", "now() - interval '2 hours'");
        insertStatusHistory(tenantA, shipmentId, "delivered", "now()");

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(null, null); } finally { TenantContext.clear(); }

        assertThat(byMetric(trends, "delivered").total()).isEqualTo(1.0);
    }

    // ── ovt4: exceptions — event-based counts, refresh-based excluded ─────────

    @Test
    void ovt4_exceptionsTotal_eventBasedCounts_refreshBasedExcluded() {
        // Event-based, included: a piece currently 'lost'.
        insertPiece(tenantA, variantA, "lost", "now()");

        // Refresh-based, EXCLUDED: a shipment matching high_attempts' own condition
        // (number_of_attempts >= 2, non-terminal). If exceptionsRaw() ever regresses
        // to include this detector, this assertion catches it (total would be 2, not 1).
        UUID orderId = insertOrder(tenantA, storeA, "with_courier", false, "now()", null, null, null);
        insertShipment(tenantA, orderId, "forward", "with_courier", "now()", 3, false, null);

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(null, null); } finally { TenantContext.clear(); }

        assertThat(byMetric(trends, "exceptions").total())
            .as("only the lost piece (event-based) counts; the high-attempts shipment (refresh-based) must not")
            .isEqualTo(1.0);
    }

    // ── ovt5: returns ────────────────────────────────────────────────────────

    @Test
    void ovt5_returnsTotal_reflectsOpenedSessionsRegardlessOfStatus() {
        insertReturnSession(tenantA, actorA, "open", "now()");
        insertReturnSession(tenantA, actorA, "abandoned", "now()");

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(null, null); } finally { TenantContext.clear(); }

        assertThat(byMetric(trends, "returns").total())
            .as("flow count = sessions opened that day, regardless of eventual status")
            .isEqualTo(2.0);
    }

    // ── ovt6: from/to restricts `total`, never `series` ────────────────────────

    @Test
    void ovt6_dateRange_restrictsTotalOnly_seriesStaysFixed14Days() {
        // Placed 10 days ago — outside a Last-7-days total, but still inside the
        // fixed 14-day sparkline window.
        insertOrder(tenantA, storeA, "new", false, "now() - interval '10 days'", null, null, null);
        // Placed today — inside both.
        insertOrder(tenantA, storeA, "new", false, "now()", null, null, null);

        LocalDate today = LocalDate.now(CAIRO_CLOCK.getZone());
        String from = today.minusDays(6).toString();
        String to   = today.toString();

        TenantContext.set(tenantA);
        List<OverviewService.MetricTrend> trends;
        try { trends = overview.trends(from, to); } finally { TenantContext.clear(); }

        OverviewService.MetricTrend orders = byMetric(trends, "orders");
        assertThat(orders.total())
            .as("Last-7-days total excludes the order placed 10 days ago")
            .isEqualTo(1.0);
        assertThat(orders.series().stream().mapToInt(OverviewService.TrendPoint::count).sum())
            .as("the 14-day sparkline still includes both seeded orders")
            .isEqualTo(2);
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
                    List<OverviewService.MetricTrend> trends = appUserOverview.trends(null, null);
                    assertThat(byMetric(trends, "orders").total())
                        .as("ovt8: same-tenant positive control sees only tenant A's order")
                        .isEqualTo(1.0);
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

    // ── ovt10: late-to-pack ─────────────────────────────────────────────────────

    @Test
    void ovt10_lateToPack_overdueAndOver48_onHoldStillCounts_packedExcluded() {
        // 30h old, pre-pack ('ready_to_pick') — overdue, not over48.
        insertOrder(tenantA, storeA, "ready_to_pick", false, "now() - interval '30 hours'", null, null, null);
        // 50h old, pre-pack ('new'), ON HOLD — must still count: on-hold is not a
        // carve-out, per FR-Overview §2's explicit "do NOT special-case" instruction.
        insertOrder(tenantA, storeA, "new", true, "now() - interval '50 hours'", null, null, null);
        // 30h old but already 'packed' — must NOT count, it's past pre-pack.
        insertOrder(tenantA, storeA, "packed", false, "now() - interval '30 hours'", null, null, null);
        // 1h old, pre-pack — too fresh, must NOT count.
        insertOrder(tenantA, storeA, "picking", false, "now() - interval '1 hour'", null, null, null);
        insertOrder(tenantB, storeB, "new", false, "now() - interval '50 hours'", null, null, null); // noise

        TenantContext.set(tenantA);
        OverviewService.LateToPack result;
        try { result = overview.lateToPack(); } finally { TenantContext.clear(); }

        assertThat(result.overdue())
            .as("the 30h and 50h pre-pack orders both count; 'packed' and the 1h-old one don't")
            .isEqualTo(2);
        assertThat(result.over48())
            .as("only the 50h order clears the 48h bar")
            .isEqualTo(1);
    }

    // ── ovt11: malformed from/to → 400, not a 500 or a silently-ignored param ──

    @Test
    void ovt11_trends_malformedDateParam_throws400() {
        TenantContext.set(tenantA);
        try {
            assertThatThrownBy(() -> overview.trends("not-a-date", null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
        } finally {
            TenantContext.clear();
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
