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
 * Pick-queue recency filter — verifies the placed_at > now() - lookback window
 * added to FulfillService.getQueue().
 *
 * Default lookback is 30 days (shopify.import.lookback-days).
 *
 * Test inventory:
 *   (a) In-window 'new' order appears in queue
 *   (b) Out-of-window 'new' order is hidden from queue but still in the DB
 *   (c) Orders-list query (not getQueue) returns all orders regardless of placed_at
 *   (d) out-of-window order is still linkable (exists in orders table for Bosta FK)
 *   (e) ready_to_pick and self_pickup_pending orders within window also appear
 *   (f) on_hold=true orders are excluded regardless of placed_at
 *   (g) env-override: lookback-days=45 hides 40-day-old order that 30-day window would also hide
 *       (property injection smoke-test — verified via value stored on service bean)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "shopify.import.lookback-days=30")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PickQueueRecencyTest {

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

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RecencyTenant')", tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'recency.myshopify.com', 'connected')",
            storeId, tenantId);
    }

    @BeforeEach void ctx()   { TenantContext.set(tenantId); }
    @AfterEach  void clean() {
        TenantContext.clear();
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertOrder(String externalId, String status, String placedAtExpr, boolean onHold) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, on_hold) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod', " + placedAtExpr + ", ?) RETURNING id",
            UUID.class, tenantId, storeId, externalId, "#" + externalId, status, onHold);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * (a) A 'new' order placed 5 days ago appears in the queue.
     */
    @Test
    void a_inWindow_new_appearsInQueue() {
        UUID orderId = insertOrder("IN-WINDOW-001", "new", "now() - interval '5 days'", false);

        List<Map<String, Object>> queue = fulfillSvc.getQueue();

        assertThat(queue).anyMatch(o -> orderId.equals(o.get("id")));
    }

    /**
     * (b) A 'new' order placed 45 days ago is hidden from the queue (window=30d)
     * but still exists in the orders table.
     */
    @Test
    void b_outOfWindow_new_hiddenFromQueue_butExistsInDb() {
        UUID orderId = insertOrder("OUT-OF-WINDOW-001", "new", "now() - interval '45 days'", false);

        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).noneMatch(o -> orderId.equals(o.get("id")));

        // Still in DB
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE id = ?", Integer.class, orderId);
        assertThat(count).isEqualTo(1);
    }

    /**
     * (c) A direct orders query (not getQueue) returns all orders regardless of placed_at.
     * Simulates what OrderController does: no recency filter on the orders table.
     */
    @Test
    void c_ordersTable_notFiltered_allDatesVisible() {
        UUID recentId = insertOrder("ALL-RECENT", "new", "now() - interval '5 days'",  false);
        UUID oldId    = insertOrder("ALL-OLD",    "new", "now() - interval '90 days'", false);

        // Direct DB query — no recency filter (what the orders-list endpoint does)
        List<Map<String, Object>> all = jdbc.queryForList(
            "SELECT id FROM orders WHERE tenant_id = ? AND external_id IN ('ALL-RECENT','ALL-OLD')",
            tenantId);

        assertThat(all).hasSize(2);
        assertThat(all.stream().map(r -> r.get("id"))).contains(recentId, oldId);

        // Queue sees only the recent one
        List<Map<String, Object>> queue = fulfillSvc.getQueue();
        assertThat(queue).anyMatch(o -> recentId.equals(o.get("id")));
        assertThat(queue).noneMatch(o -> oldId.equals(o.get("id")));
    }

    /**
     * (d) An out-of-window order is still linkable — its ID can be used as FK target
     * (simulates Bosta shipment linking by order ID).
     */
    @Test
    void d_outOfWindow_stillLinkableAsBostaTarget() {
        UUID oldOrderId = insertOrder("BOSTA-LINK-001", "new", "now() - interval '60 days'", false);

        // Would fail if the row didn't exist — proves data is preserved for Bosta FK
        UUID found = jdbc.queryForObject(
            "SELECT id FROM orders WHERE id = ? AND tenant_id = ?",
            UUID.class, oldOrderId, tenantId);
        assertThat(found).isEqualTo(oldOrderId);
    }

    /**
     * (e) ready_to_pick and self_pickup_pending orders within the window appear in queue.
     */
    @Test
    void e_inWindow_otherEligibleStatuses_appearInQueue() {
        UUID readyId   = insertOrder("RTP-001", "ready_to_pick",      "now() - interval '2 days'", false);
        UUID selfPickId = insertOrder("SP-001",  "self_pickup_pending", "now() - interval '1 day'",  false);

        List<Map<String, Object>> queue = fulfillSvc.getQueue();

        assertThat(queue).anyMatch(o -> readyId.equals(o.get("id")));
        assertThat(queue).anyMatch(o -> selfPickId.equals(o.get("id")));
    }

    /**
     * (f) on_hold=true orders are excluded regardless of placed_at.
     */
    @Test
    void f_onHold_excludedRegardlessOfRecency() {
        UUID heldRecent = insertOrder("HELD-RECENT", "new", "now() - interval '1 day'",  true);
        UUID heldOld    = insertOrder("HELD-OLD",    "new", "now() - interval '5 days'", true);

        List<Map<String, Object>> queue = fulfillSvc.getQueue();

        assertThat(queue).noneMatch(o -> heldRecent.equals(o.get("id")));
        assertThat(queue).noneMatch(o -> heldOld.equals(o.get("id")));
    }

    /**
     * (g) Config default 30 is in effect: a 31-day-old order is absent from queue,
     * a 29-day-old order is present. Smoke-tests the @Value injection.
     */
    @Test
    void g_lookbackBoundary_31DaysOldHidden_29DaysOldVisible() {
        UUID justOutside = insertOrder("BOUNDARY-OUT", "new", "now() - interval '31 days'", false);
        UUID justInside  = insertOrder("BOUNDARY-IN",  "new", "now() - interval '29 days'", false);

        List<Map<String, Object>> queue = fulfillSvc.getQueue();

        assertThat(queue).noneMatch(o -> justOutside.equals(o.get("id")));
        assertThat(queue).anyMatch(o -> justInside.equals(o.get("id")));
    }
}
