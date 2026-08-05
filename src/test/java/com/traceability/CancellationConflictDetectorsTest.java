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
 * FR-13 / Part B — the two cancellation-reconciliation detectors:
 *   detectCancelledWithLiveShipment (cancelled_live_shipment, HIGH)
 *   detectCancelledButDelivered     (cancelled_but_delivered, HIGH)
 *
 * Both scoped to the latest FORWARD shipment (shipment_leg='forward', latest by id) and
 * self-resolving like detectGuidedUnpack — no exception_resolutions row, the exception
 * disappears the moment the underlying condition clears via a normal Bosta sync.
 *
 * Fixtures are production-shaped: bare numeric tracking numbers, real order_status /
 * shipment_internal_state values, no clean-slate placeholders.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancellationConflictDetectorsTest {

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

    UUID tenantId, storeId;

    @BeforeAll
    void setupFixture() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'CancelConflictTenant')", tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'cancel-conflict.myshopify.com', 'disconnected')",
                    storeId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clear() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── cancelled_live_shipment ──────────────────────────────────────────────

    @Test
    void a_notTracedCancelled_liveShipmentCreated_surfaces_selfResolvesOnTerminal() {
        UUID orderId = cancelledOrder(true);
        UUID shipId  = forwardShipment(orderId, "9820011001", "created");

        List<Map<String, Object>> items = exceptionsOfType("cancelled_live_shipment");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("severity", "HIGH");
        assertThat(items.get(0).get("tracking_number")).isEqualTo("9820011001");
        assertThat(items.get(0).get("descriptionEn").toString())
            .contains("9820011001").contains("Cancel the AWB");
        assertThat(items.get(0).get("descriptionAr").toString()).contains("9820011001");

        // Self-resolving: no excSvc.resolve() call — a normal Bosta sync moving the
        // shipment to a terminal state must clear it on its own.
        jdbc.update("UPDATE shipments SET internal_state = 'terminated'::shipment_internal_state " +
                    "WHERE id = ?", shipId);
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
    }

    @Test
    void a2_cancelled_liveShipmentWithCourier_surfaces() {
        UUID orderId = cancelledOrder(false);
        forwardShipment(orderId, "9820011002", "with_courier");
        assertThat(exceptionsOfType("cancelled_live_shipment")).hasSize(1);
    }

    @Test
    void a3_cancelled_liveShipmentReturning_surfaces() {
        UUID orderId = cancelledOrder(false);
        forwardShipment(orderId, "9820011003", "returning");
        assertThat(exceptionsOfType("cancelled_live_shipment")).hasSize(1);
    }

    @Test
    void a4_cancelled_liveShipmentException_surfaces() {
        UUID orderId = cancelledOrder(false);
        forwardShipment(orderId, "9820011004", "exception");
        assertThat(exceptionsOfType("cancelled_live_shipment")).hasSize(1);
    }

    // ── cancelled_but_delivered ──────────────────────────────────────────────

    @Test
    void b_cancelledDelivered_surfaces_distinctCodeFromLiveShipment() {
        UUID orderId = cancelledOrder(false);
        forwardShipment(orderId, "9820011005", "delivered");

        List<Map<String, Object>> delivered = exceptionsOfType("cancelled_but_delivered");
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0)).containsEntry("severity", "HIGH");
        assertThat(delivered.get(0).get("descriptionEn").toString())
            .contains("already delivered").contains("Reconcile COD");

        // Must NOT also fire the live-shipment detector — delivered is terminal.
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
    }

    // ── clean cancels — neither detector fires ──────────────────────────────

    @Test
    void c_cancelledReturned_neitherFires() {
        UUID orderId = cancelledOrder(false);
        forwardShipment(orderId, "9820011006", "returned");
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
        assertThat(exceptionsOfType("cancelled_but_delivered")).isEmpty();
    }

    @Test
    void d_cancelledTerminated_neitherFires() {
        UUID orderId = cancelledOrder(false);
        forwardShipment(orderId, "9820011007", "terminated");
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
        assertThat(exceptionsOfType("cancelled_but_delivered")).isEmpty();
    }

    @Test
    void e_cancelledShipmentSideCancelled_neitherFires() {
        UUID orderId = cancelledOrder(false);
        forwardShipment(orderId, "9820011008", "cancelled");
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
        assertThat(exceptionsOfType("cancelled_but_delivered")).isEmpty();
    }

    @Test
    void f_cancelledLost_neitherFires() {
        UUID orderId = cancelledOrder(false);
        forwardShipment(orderId, "9820011009", "lost");
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
        assertThat(exceptionsOfType("cancelled_but_delivered")).isEmpty();
    }

    @Test
    void g_cancelledNoShipmentAtAll_neitherFires() {
        cancelledOrder(false); // no forwardShipment() call
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
        assertThat(exceptionsOfType("cancelled_but_delivered")).isEmpty();
    }

    // ── non-cancelled orders never trip either detector ─────────────────────

    @Test
    void h_nonCancelledOrder_liveShipment_neitherFires() {
        UUID orderId = order("awaiting_pickup");
        forwardShipment(orderId, "9820011010", "with_courier");
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
        assertThat(exceptionsOfType("cancelled_but_delivered")).isEmpty();
    }

    // ── mutual exclusivity with detectShopifyCancelVsInflight ───────────────

    @Test
    void i_shopifyCancelVsInflight_awaitingPickup_doesNotDoubleFireWithB1Detectors() {
        // status='awaiting_pickup' (not 'cancelled') — B1's two detectors require
        // status='cancelled' outright, so they structurally cannot fire here regardless
        // of shopify_cancel_requested_at.
        UUID orderId = order("awaiting_pickup");
        forwardShipment(orderId, "9820011011", "with_courier");
        jdbc.update("UPDATE orders SET shopify_cancel_requested_at = now() WHERE id = ?", orderId);

        assertThat(exceptionsOfType("shopify_cancel_vs_inflight")).hasSize(1);
        assertThat(exceptionsOfType("cancelled_live_shipment")).isEmpty();
        assertThat(exceptionsOfType("cancelled_but_delivered")).isEmpty();
    }

    // ── latest-forward-shipment scoping ──────────────────────────────────────

    @Test
    void j_onlyLatestForwardShipmentConsidered_notAnOlderTerminatedOne() {
        // An older forward shipment row exists in a terminal state (e.g. a prior AWB that
        // was re-issued); only the LATEST forward shipment (by id) should be evaluated.
        // gen_random_uuid() is NOT chronologically ordered, so "latest by id" is verified
        // here with explicit, deterministic UUIDs rather than relying on insertion order —
        // exactly the ordering the detector's own "ORDER BY id DESC LIMIT 1" depends on.
        UUID orderId = cancelledOrder(false);
        UUID lowId  = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID highId = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffffe");
        forwardShipmentWithId(lowId,  orderId, "9820011012", "terminated");   // older by id — would be "clean"
        forwardShipmentWithId(highId, orderId, "9820011013", "with_courier"); // latest by id — live

        List<Map<String, Object>> items = exceptionsOfType("cancelled_live_shipment");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("tracking_number")).isEqualTo("9820011013");
    }

    // ── db helpers ────────────────────────────────────────────────────────────

    private UUID order(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#CC', ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "CC-" + UUID.randomUUID(), status);
    }

    private UUID cancelledOrder(boolean notTraced) {
        UUID orderId = order("cancelled");
        if (notTraced) {
            jdbc.update("UPDATE orders SET not_traced_at = now() WHERE id = ?", orderId);
        }
        return orderId;
    }

    private UUID forwardShipment(UUID orderId, String tracking, String state) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward') RETURNING id",
            UUID.class, tenantId, orderId, tracking, state);
    }

    private void forwardShipmentWithId(UUID id, UUID orderId, String tracking, String state) {
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')",
            id, tenantId, orderId, tracking, state);
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
