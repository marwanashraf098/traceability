package com.traceability;

import com.traceability.inventory.FulfillService;
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
 * Queue-gating-not-traced spec — FulfillService.getQueue() send-state gate.
 *
 * Tightened 2026-07-28 (Marawan): an order qualifies iff it is self-pickup
 * (o.is_self_pickup = true), OR its latest shipment_leg='forward' shipment has
 * internal_state = 'created'. No forward shipment AND not self-pickup ⇒ excluded —
 * "Shipment not created" means the order was never handed to Bosta, so there is
 * nothing to physically pick in Traced for it. Previously this read
 * "internal_state IS NULL OR = 'created'", which wrongly kept EVERY no-shipment order
 * in the queue regardless of self-pickup status (test `e` below is the flipped
 * assertion that used to assert the opposite). This is the state-gate filter alone —
 * deliberately NOT coupled to not_traced_at (see FulfillService.getQueue() javadoc).
 *
 * Test inventory:
 *   a — forward shipment 'created' → order stays in queue
 *   b — forward shipment 'with_courier' → excluded
 *   c — forward shipment 'delivered' → excluded
 *   d — forward shipment 'returned' → excluded
 *   e — no linked shipment at all, NOT self-pickup → excluded (flipped 2026-07-28;
 *       previously asserted this stayed in the queue — that was the bug)
 *   f — self_pickup_pending order (no shipment) stays in queue — the exemption keys on
 *       is_self_pickup, not on shipment absence — Awaiting Collection regression
 *   g — on_hold=true excluded regardless of shipment state — pre-existing regression guard
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueSendStateGateTest {

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

    @Autowired FulfillService fulfillSvc;
    @Autowired JdbcTemplate   jdbc;
    @MockBean  JobScheduler   jobScheduler;

    UUID tenantId, storeId;

    @BeforeAll
    void setupFixture() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'QueueGateTenant')", tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'queuegate.myshopify.com', 'connected')",
            storeId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clean() {
        TenantContext.clear();
        jdbc.update("DELETE FROM shipments   WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders      WHERE tenant_id = ?", tenantId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertOrder(String externalId, String status, boolean onHold, boolean selfPickup) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, on_hold, is_self_pickup) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', now() - interval '1 day', ?, ?) RETURNING id",
            UUID.class, tenantId, storeId, externalId, "#" + externalId, status, onHold, selfPickup);
    }

    private void insertForwardShipment(UUID orderId, String tracking, String internalState) {
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward')",
            tenantId, orderId, tracking, internalState);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void a_forwardShipmentCreated_staysInQueue() {
        UUID orderId = insertOrder("QG-A", "new", false, false);
        insertForwardShipment(orderId, "QG-TN-A", "created");

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).anyMatch(o -> orderId.equals(o.get("id")));
    }

    @Test
    void b_forwardShipmentWithCourier_excluded() {
        UUID orderId = insertOrder("QG-B", "new", false, false);
        insertForwardShipment(orderId, "QG-TN-B", "with_courier");

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).noneMatch(o -> orderId.equals(o.get("id")));
    }

    @Test
    void c_forwardShipmentDelivered_excluded() {
        UUID orderId = insertOrder("QG-C", "new", false, false);
        insertForwardShipment(orderId, "QG-TN-C", "delivered");

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).noneMatch(o -> orderId.equals(o.get("id")));
    }

    @Test
    void d_forwardShipmentReturned_excluded() {
        UUID orderId = insertOrder("QG-D", "new", false, false);
        insertForwardShipment(orderId, "QG-TN-D", "returned");

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).noneMatch(o -> orderId.equals(o.get("id")));
    }

    @Test
    void e_noLinkedShipment_notSelfPickup_excluded() {
        // Flipped 2026-07-28: no forward shipment + NOT self-pickup used to stay in the
        // queue (the bug) — "Shipment not created" now correctly excludes it.
        UUID orderId = insertOrder("QG-E", "ready_to_pick", false, false);
        // No shipment row at all.

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).noneMatch(o -> orderId.equals(o.get("id")));
    }

    @Test
    void f_selfPickupPending_noShipment_staysInQueue() {
        UUID orderId = insertOrder("QG-F", "self_pickup_pending", false, true);
        // is_self_pickup=true exempts this order from the shipment-state check entirely —
        // Self-pickup orders never get a Bosta shipment; Awaiting Collection must be unaffected.

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).anyMatch(o -> orderId.equals(o.get("id")));
    }

    @Test
    void g_onHold_excludedRegardlessOfShipmentState() {
        UUID orderId = insertOrder("QG-G", "new", true, false);
        insertForwardShipment(orderId, "QG-TN-G", "created");

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).noneMatch(o -> orderId.equals(o.get("id")));
    }
}
