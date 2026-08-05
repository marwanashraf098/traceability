package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.fulfillment.OrderController;
import com.traceability.fulfillment.OrderController.OrderDetail;
import com.traceability.fulfillment.OrderController.OrderSummary;
import com.traceability.fulfillment.OrderStatusDeriver.DerivedOrderStatus;
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
