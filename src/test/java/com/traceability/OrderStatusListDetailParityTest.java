package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderController.OrderDetail;
import com.traceability.fulfillment.OrderController.OrderSummary;
import com.traceability.fulfillment.OrderController.ShipmentDetail;
import com.traceability.fulfillment.OrderStatusDeriver.DerivedOrderStatus;
import com.traceability.fulfillment.OrderStatusDeriver.Tone;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-7/FR-11 A2 — proves OrderController.list() and OrderController.detail() can never
 * derive a different DerivedOrderStatus for the same order (the exact divergence A1/A2
 * exists to kill), and that the detail path's RLS still holds under app_user.
 *
 * OrderController is constructed directly (new, not @Autowired) — matches the pattern in
 * PickQueueRecencyTest of testing business/query logic without going through the HTTP +
 * @PreAuthorize layer, which needs a Spring Security auth context this test doesn't set up.
 *
 * Fixtures are production-shaped: bare numeric tracking numbers, a regressed
 * created-after-with_courier history, a delivered+failed shipment, and a cancelled order
 * with a still-live shipment — not clean-slate rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderStatusListDetailParityTest {

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

    @Autowired JdbcTemplate              jdbc;
    @Autowired ObjectMapper              mapper;
    @Autowired PlatformTransactionManager txm;

    // app_user infrastructure for the RLS test — mirrors DeliveryStatusTest's appUserJdbc/Tx.
    private JdbcTemplate        appUserJdbc;
    private PlatformTransactionManager appUserTxm;

    private OrderController controller;

    UUID tenantId, otherTenantId, storeId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTxm  = new DataSourceTransactionManager(appDs);
    }

    @BeforeAll
    void setupFixture() {
        controller = new OrderController(jdbc, mapper, txm);

        tenantId      = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        storeId       = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ParityTenant')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ParityOtherTenant')", otherTenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'parity-test.myshopify.com', 'connected')",
                    storeId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clean() { TenantContext.clear(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertOrder(String extId, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, extId, "#" + extId, status);
    }

    private UUID insertForwardShipment(UUID orderId, String tracking, String state,
                                        int numberOfAttempts, int failedDeliveryAttempts) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg, number_of_attempts, failed_delivery_attempts) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', ?, ?) RETURNING id",
            UUID.class, tenantId, orderId, tracking, state, numberOfAttempts, failedDeliveryAttempts);
    }

    private UUID insertReturnShipment(UUID orderId, String tracking, String state) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'return') RETURNING id",
            UUID.class, tenantId, orderId, tracking, state);
    }

    private void insertHistory(UUID shipmentId, String state) {
        jdbc.update(
            "INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
            "VALUES (?, ?, ?::shipment_internal_state)", tenantId, shipmentId, state);
    }

    // ── list vs detail parity ────────────────────────────────────────────────

    @Test
    void parity_regressedCreatedAfterWithCourier_listAndDetailAgree() {
        UUID orderId = insertOrder("PARITY-REGRESSED", "awaiting_pickup");
        UUID shipmentId = insertForwardShipment(orderId, "9810234561", "created", 3, 3);
        insertHistory(shipmentId, "created");
        insertHistory(shipmentId, "with_courier");
        insertHistory(shipmentId, "created"); // regressed

        assertParity(orderId);
    }

    @Test
    void parity_regressedHistory_zeroFailedAttempts_maxRankDrivesPrimaryKey_listAndDetailAgree() {
        // Unlike parity_regressedCreatedAfterWithCourier_listAndDetailAgree above (which
        // has failed_delivery_attempts=3 and short-circuits derive() BEFORE
        // maxProgressRank is ever consulted for primaryKey), this fixture has
        // failedDeliveryAttempts=0 so primaryKey genuinely flows through the
        // furthest-progress branch — the one place list()'s SQL CASE and detail()'s Java
        // reducer could silently diverge without any other branch masking it.
        UUID orderId = insertOrder("PARITY-REGRESSED-NOFAIL", "awaiting_pickup");
        UUID shipmentId = insertForwardShipment(orderId, "9810234565", "created", 0, 0);
        insertHistory(shipmentId, "created");
        insertHistory(shipmentId, "with_courier");
        insertHistory(shipmentId, "created"); // regressed, no failed attempts to mask it

        assertParity(orderId);

        // Not just agreement — agreement on the CORRECT answer. Two paths silently computing
        // the same wrong maxRank would still pass assertParity alone.
        OrderDetail detail = controller.detail(orderId);
        assertThat(detail.derivedStatus().primaryKey())
            .as("maxRank=2 (from history's with_courier row) must win over the regressed 'created' latest state")
            .isEqualTo("status.in_transit");
    }

    @Test
    void parity_deliveredPlusFailedAttempt_listAndDetailAgree() {
        UUID orderId = insertOrder("PARITY-DELIVERED-FAIL", "new");
        UUID shipmentId = insertForwardShipment(orderId, "9810234562", "delivered", 2, 1);
        insertHistory(shipmentId, "with_courier");
        insertHistory(shipmentId, "delivered");

        assertParity(orderId);
    }

    @Test
    void parity_cancelledOrderWithLiveShipment_listAndDetailAgree() {
        UUID orderId = insertOrder("PARITY-CANCELLED-LIVE", "cancelled");
        UUID shipmentId = insertForwardShipment(orderId, "9810234563", "with_courier", 1, 0);
        insertHistory(shipmentId, "created");
        insertHistory(shipmentId, "with_courier");

        assertParity(orderId);
    }

    @Test
    void parity_noShipmentLinked_listAndDetailAgree() {
        UUID orderId = insertOrder("PARITY-NO-SHIPMENT", "new");
        assertParity(orderId);
    }

    // ── A3: cancelled-order conflict flag, through the real DB-wired path ──────

    @Test
    void a3_cancelledOrder_deliveredShipment_conflictFlag_noHealthChips() {
        UUID orderId = insertOrder("A3-CANCELLED-DELIVERED", "cancelled");
        insertForwardShipment(orderId, "9810234571", "delivered", 2, 1);

        DerivedOrderStatus detailDerived = controller.detail(orderId).derivedStatus();
        assertThat(detailDerived.primaryKey()).isEqualTo("status.cancelled");
        assertThat(detailDerived.conflictKey()).isEqualTo("status.conflict.cancelled_but_delivered");
        assertThat(detailDerived.healthChips()).isEmpty();

        assertParity(orderId);
    }

    @Test
    void a3_cancelledOrder_liveShipment_conflictFlag_noHealthChips() {
        UUID orderId = insertOrder("A3-CANCELLED-LIVE", "cancelled");
        // isDelayed=true would normally raise chip.delayed on a non-cancelled order — A3
        // must suppress it here since the order itself is cancelled.
        UUID shipmentId = insertForwardShipment(orderId, "9810234572", "created", 0, 0);
        jdbc.update("UPDATE shipments SET is_delayed = true WHERE id = ?", shipmentId);

        DerivedOrderStatus detailDerived = controller.detail(orderId).derivedStatus();
        assertThat(detailDerived.primaryKey()).isEqualTo("status.cancelled");
        assertThat(detailDerived.conflictKey()).isEqualTo("status.conflict.live_shipment");
        assertThat(detailDerived.healthChips()).isEmpty();

        assertParity(orderId);
    }

    @Test
    void a3_cancelledOrder_returnedShipment_noConflict_cleanCancel() {
        UUID orderId = insertOrder("A3-CANCELLED-RETURNED", "cancelled");
        insertForwardShipment(orderId, "9810234573", "returned", 0, 0);

        DerivedOrderStatus detailDerived = controller.detail(orderId).derivedStatus();
        assertThat(detailDerived.primaryKey()).isEqualTo("status.cancelled");
        assertThat(detailDerived.conflictKey()).as("returned shipment is a clean cancel").isNull();
        assertThat(detailDerived.healthChips()).isEmpty();

        assertParity(orderId);
    }

    // ── A3.1: return-leg leg-scoped badge, through the real DB-wired path ──────

    @Test
    void a31_returnLeg_returning_getsCorrectLegBadge_headerUnaffected() {
        UUID orderId = insertOrder("A31-RETURN-RETURNING", "returning");
        insertForwardShipment(orderId, "9810234581", "returned", 0, 0);
        insertReturnShipment(orderId, "9810234582", "returning");

        OrderDetail detail = controller.detail(orderId);
        ShipmentDetail returnLeg = detail.shipments().stream()
            .filter(s -> "return".equals(s.shipmentLeg())).findFirst().orElseThrow();
        assertThat(returnLeg.legStatus().primaryKey()).isEqualTo("status.returning");
        assertThat(returnLeg.legStatus().tone()).isEqualTo(Tone.WARN);

        // Header is driven by the FORWARD leg only — the return leg's state must not
        // leak into the order-level derivation.
        assertThat(detail.derivedStatus().primaryKey()).isEqualTo("status.returned");
        assertParity(orderId);
    }

    @Test
    void a31_returnLeg_returned_getsCorrectLegBadge() {
        UUID orderId = insertOrder("A31-RETURN-RETURNED", "returned");
        insertForwardShipment(orderId, "9810234583", "returned", 0, 0);
        insertReturnShipment(orderId, "9810234584", "returned");

        ShipmentDetail returnLeg = controller.detail(orderId).shipments().stream()
            .filter(s -> "return".equals(s.shipmentLeg())).findFirst().orElseThrow();
        assertThat(returnLeg.legStatus().primaryKey()).isEqualTo("status.returned");
        assertThat(returnLeg.legStatus().tone()).isEqualTo(Tone.WARN);
    }

    @Test
    void a31_returnLeg_exception_getsCorrectLegBadge() {
        UUID orderId = insertOrder("A31-RETURN-EXCEPTION", "returning");
        insertForwardShipment(orderId, "9810234585", "returning", 0, 0);
        insertReturnShipment(orderId, "9810234586", "exception");

        ShipmentDetail returnLeg = controller.detail(orderId).shipments().stream()
            .filter(s -> "return".equals(s.shipmentLeg())).findFirst().orElseThrow();
        assertThat(returnLeg.legStatus().primaryKey()).isEqualTo("status.needs_attention");
        assertThat(returnLeg.legStatus().tone()).isEqualTo(Tone.WARN);
    }

    @Test
    void a31_forwardLeg_carriesLegStatus_butOrderHeaderIsWhatUltimatelyRenders() {
        // The backend computes legStatus uniformly for every shipment row (cheap, pure) —
        // it's the FRONTEND that gates rendering to the return leg only. This test just
        // confirms the forward leg's own legStatus is internally consistent with its state,
        // not that the API omits it (it doesn't need to — no UI ever renders it for forward).
        UUID orderId = insertOrder("A31-FORWARD-CONSISTENCY", "awaiting_pickup");
        insertForwardShipment(orderId, "9810234587", "with_courier", 0, 0);

        ShipmentDetail forwardLeg = controller.detail(orderId).shipments().get(0);
        assertThat(forwardLeg.shipmentLeg()).isEqualTo("forward");
        assertThat(forwardLeg.legStatus().primaryKey()).isEqualTo("status.in_transit");
    }

    private void assertParity(UUID orderId) {
        OrderSummary fromList = controller.list(null, null, null, 0, 100).items().stream()
            .filter(o -> o.id().equals(orderId.toString()))
            .findFirst().orElseThrow();
        OrderDetail fromDetail = controller.detail(orderId);

        DerivedOrderStatus listDerived   = fromList.derivedStatus();
        DerivedOrderStatus detailDerived = fromDetail.derivedStatus();

        assertThat(listDerived).as("list vs detail derived status for order " + orderId)
            .isEqualTo(detailDerived);
    }

    // ── RLS on the detail path ───────────────────────────────────────────────

    @Test
    void rls_detail_sameTenantPositiveControl_crossTenantNegativeControl() {
        UUID orderId = insertOrder("PARITY-RLS", "new");
        insertForwardShipment(orderId, "9810234564", "created", 0, 0);

        OrderController appUserController = new OrderController(appUserJdbc, mapper, appUserTxm);

        // Positive control: app_user WITH the correct tenant GUC can fetch its own order.
        OrderDetail found = TenantContext.runAs(tenantId, () -> appUserController.detail(orderId));
        assertThat(found.id()).isEqualTo(orderId.toString());
        assertThat(found.derivedStatus()).isNotNull();

        // Negative control: app_user under a DIFFERENT tenant's GUC cannot see it — RLS
        // makes the row invisible, so detail() throws 404, not a security-bypass 200.
        assertThatThrownBy(() -> TenantContext.runAs(otherTenantId, () -> appUserController.detail(orderId)))
            .as("app_user under a different tenant's GUC must not see this order")
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }
}
