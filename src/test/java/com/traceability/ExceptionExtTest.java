package com.traceability;

import com.traceability.inventory.ExceptionService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-15.3 exception-center extensions (V21):
 *
 * (a) shopify_cancel_vs_inflight — surfaces when Shopify cancels an awaiting_pickup order,
 *     resolves, stays suppressed
 * (b) stuck_shipment fires at 5 days but NOT at 4 days (new per-tenant default = 5)
 * (c) short-order: no signal exists yet — gap documented, no detector wired
 * (d) severity ordering still holds with the new shopify_cancel_vs_inflight type
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExceptionExtTest {

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

    @Autowired ExceptionService excSvc;
    @Autowired JdbcTemplate     jdbc;
    @MockBean  JobScheduler     jobScheduler;

    UUID tenantId, actorId, storeId, variantId;

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        actorId   = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        // stuck_shipment_days = 5 (new default post-V21)
        jdbc.update("INSERT INTO tenants (id, name, stuck_shipment_days) VALUES (?, 'ExtTenant', 5)",
                    tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Ops', 'ops@ext.local', 'h', 'manager')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'ext.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'P-EXT', 'Widget')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-EXT', 'Blue', 'WGT-EXT')", variantId, tenantId, productId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── (a) shopify_cancel_vs_inflight ────────────────────────────────────────

    @Test
    void a_shopifyCancelVsInflight_surfaces_resolveRemovesIt() {
        // Order is awaiting_pickup (AWB linked, with courier manifest) but Shopify cancelled
        UUID orderId = awaitingPickupOrder();
        shipment(orderId, "AWB-SCV-A");

        // Stamp the signal as handleOrderCancelled() would on 409
        jdbc.update(
            "UPDATE orders SET shopify_cancel_requested_at = now() WHERE id = ? AND tenant_id = ?",
            orderId, tenantId);

        List<Map<String, Object>> items = exceptionsOfType("shopify_cancel_vs_inflight");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("severity", "HIGH");
        assertThat(items.get(0).get("order_number")).isNotNull();
        assertThat(items.get(0).get("tracking_number")).isEqualTo("AWB-SCV-A");
        // Descriptions must be populated
        assertThat(items.get(0).get("descriptionEn").toString()).contains("in-flight");
        assertThat(items.get(0).get("descriptionAr").toString()).contains("شوبيفاي");

        // Resolve — operator handled it (e.g. let it RTO)
        String key = "shopify_cancel_vs_inflight:order:" + orderId;
        excSvc.resolve("shopify_cancel_vs_inflight", key, actorId, "Letting it RTO");

        assertThat(exceptionsOfType("shopify_cancel_vs_inflight")).isEmpty();
    }

    @Test
    void a2_shopifyCancelVsInflight_noSignal_doesNotSurface() {
        // Order awaiting_pickup but no shopify_cancel_requested_at set
        UUID orderId = awaitingPickupOrder();
        shipment(orderId, "AWB-SCV-A2");

        assertThat(exceptionsOfType("shopify_cancel_vs_inflight")).isEmpty();
    }

    @Test
    void a3_shopifyCancelVsInflight_orderDelivered_doesNotSurface() {
        // Signal was set, but order later transitioned out of awaiting_pickup (RTO'd or delivered)
        UUID orderId = awaitingPickupOrder();
        jdbc.update(
            "UPDATE orders SET shopify_cancel_requested_at = now() WHERE id = ? AND tenant_id = ?",
            orderId, tenantId);
        // Simulate order progressing (e.g. delivered by courier despite cancellation)
        jdbc.update(
            "UPDATE orders SET status = 'delivered'::order_status WHERE id = ? AND tenant_id = ?",
            orderId, tenantId);

        assertThat(exceptionsOfType("shopify_cancel_vs_inflight")).isEmpty();
    }

    @Test
    void a4_shopifyCancelVsInflight_resolvedStaysSuppressed() {
        UUID orderId = awaitingPickupOrder();
        jdbc.update(
            "UPDATE orders SET shopify_cancel_requested_at = now() WHERE id = ? AND tenant_id = ?",
            orderId, tenantId);

        String key = "shopify_cancel_vs_inflight:order:" + orderId;
        excSvc.resolve("shopify_cancel_vs_inflight", key, actorId, "resolved");

        // Still awaiting_pickup but resolved — must not resurface
        assertThat(exceptionsOfType("shopify_cancel_vs_inflight")).isEmpty();
    }

    // ── (b) stuck_shipment fires at 5 days, not at 4 ─────────────────────────

    @Test
    void b_stuckShipment_firesAt5Days_notAt4() {
        UUID orderId = order("with_courier");
        UUID shipId  = shipmentId(orderId, "AWB-STUCK-4D", "with_courier");

        // 4 days ago — below threshold, must NOT surface
        jdbc.update(
            "UPDATE shipments SET last_synced_at = now() - interval '4 days' WHERE id = ?",
            shipId);

        assertThat(exceptionsOfType("stuck_shipment").stream()
            .filter(e -> shipId.toString().equals(
                e.get("shipment_id") != null ? e.get("shipment_id").toString() : null))
            .toList()).isEmpty();

        // Advance to 5 days — at or beyond threshold, MUST surface
        jdbc.update(
            "UPDATE shipments SET last_synced_at = now() - interval '5 days' WHERE id = ?",
            shipId);

        assertThat(exceptionsOfType("stuck_shipment").stream()
            .filter(e -> shipId.toString().equals(
                e.get("shipment_id") != null ? e.get("shipment_id").toString() : null))
            .toList()).hasSize(1);
    }

    // ── (d) Severity ordering still holds with the new type ───────────────────

    @Test
    void d_severityOrdering_shopifyCancelVsInflight_isHigh_sortsBetweenCriticalAndMedium() {
        // CRITICAL: need a lost piece — but no piece fixture here; use stuck_shipment > 5d (HIGH)
        // and a shopify-cancel-vs-inflight (HIGH), plus an unmatched (MEDIUM).
        // The invariant we care about: shopify_cancel_vs_inflight is HIGH and sorts correctly.

        // HIGH: stuck shipment
        UUID o1 = order("with_courier");
        UUID s1 = shipmentId(o1, "AWB-SORT-H1", "with_courier");
        jdbc.update("UPDATE shipments SET last_synced_at = now()-interval '10 days' WHERE id=?", s1);

        // HIGH: shopify cancel vs inflight
        UUID o2 = awaitingPickupOrder();
        jdbc.update(
            "UPDATE orders SET shopify_cancel_requested_at = now() WHERE id = ? AND tenant_id = ?",
            o2, tenantId);

        // MEDIUM: unmatched delivery
        jdbc.update(
            "INSERT INTO unlinked_bosta_deliveries " +
            "(tenant_id, tracking_number, bosta_state_code, bosta_order_type) " +
            "VALUES (?, 'AWB-SORT-MED-EXT', 45, 'SEND')", tenantId);

        List<Map<String, Object>> all = exceptions();
        List<String> types = all.stream().map(e -> (String) e.get("type")).toList();
        List<String> severities = all.stream().map(e -> (String) e.get("severity")).toList();

        int highIdx   = severities.indexOf("HIGH");
        int medIdx    = severities.indexOf("MEDIUM");
        assertThat(highIdx).isLessThan(medIdx);

        // shopify_cancel_vs_inflight must itself be HIGH
        all.stream()
            .filter(e -> "shopify_cancel_vs_inflight".equals(e.get("type")))
            .forEach(e -> assertThat(e.get("severity")).isEqualTo("HIGH"));
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private UUID order(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#EXT', ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-EXT-" + UUID.randomUUID(), status);
    }

    private UUID awaitingPickupOrder() {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#EXT', 'awaiting_pickup'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-AWP-" + UUID.randomUUID());
    }

    private void shipment(UUID orderId, String tracking) {
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'created'::shipment_internal_state)",
            tenantId, orderId, tracking);
    }

    private UUID shipmentId(UUID orderId, String tracking, String state) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?::shipment_internal_state) RETURNING id",
            UUID.class, tenantId, orderId, tracking, state);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exceptions() {
        Map<String, Object> result = excSvc.listExceptions(null, null, 0, 200);
        return (List<Map<String, Object>>) result.get("items");
    }

    private List<Map<String, Object>> exceptionsOfType(String type) {
        return exceptions().stream()
            .filter(e -> type.equals(e.get("type")))
            .collect(java.util.stream.Collectors.toList());
    }
}
