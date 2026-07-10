package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaOrderReconcileJob;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for BostaOrderReconcileJob (Tier 3 reconcile).
 *
 * Matrix:
 *   r1 — no unlinked match → attempt counter incremented, last_check set
 *   r2 — attempts reach max-attempts → bosta_link_status = 'not_created'
 *   r3 — unlinked match found → order linked via manualLink, shipment created, row resolved
 *   r4 — 'not_created' flag clears when delivery is linked via createOrFindShipment
 *   r5 — order with active shipment → skipped by NOT EXISTS filter
 *   r6 — terminal status (cancelled) → skipped by status filter
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaOrderReconcileTest {

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
        // max-attempts=3 so r2 (flag test) only needs bosta_link_attempts=2 pre-seed
        r.add("bosta.reconcile.max-attempts", () -> "3");
    }

    @Autowired JdbcTemplate           jdbc;
    @Autowired EncryptionService      encryptionService;
    @Autowired BostaOrderReconcileJob reconcileJob;
    @Autowired ShipmentLinkService    shipmentLinkService;
    @MockBean  BostaGateway           bostaGateway;
    @MockBean  JobScheduler           jobScheduler;

    private UUID tenantId;
    private UUID storeId;

    @BeforeAll
    void createFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'ReconcileTestTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'reconcile_owner@test.local', 'h', 'owner')",
            UUID.randomUUID(), tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'reconcile-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
    }

    @BeforeEach
    void cleanup() {
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
        jdbc.execute("DELETE FROM shipment_status_history");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
        jdbc.execute("DELETE FROM courier_accounts");
    }

    // ── r1: no unlinked match → attempt counter incremented ──────────────────

    @Test
    void r1_noUnlinkedMatch_incrementsAttemptCounter() {
        setupCourierAccount();
        UUID orderId = insertOrder("#R-001");

        reconcileJob.reconcileAll();

        Integer attempts = jdbc.queryForObject(
            "SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, orderId);
        Object lastCheck = jdbc.queryForObject(
            "SELECT bosta_link_last_check FROM orders WHERE id = ?", Object.class, orderId);
        String status = jdbc.queryForObject(
            "SELECT bosta_link_status FROM orders WHERE id = ?", String.class, orderId);

        assertThat(attempts).as("attempt counter incremented to 1").isEqualTo(1);
        assertThat(lastCheck).as("last_check set after first run").isNotNull();
        assertThat(status).as("below max — not yet flagged").isNull();
    }

    // ── r2: attempts reach max → not_created ─────────────────────────────────

    @Test
    void r2_attemptsReachMax_flaggedAsNotCreated() {
        setupCourierAccount();
        UUID orderId = insertOrder("#R-002");
        // Pre-seed to max-1 (max is 3)
        jdbc.update("UPDATE orders SET bosta_link_attempts = 2 WHERE id = ?", orderId);

        reconcileJob.reconcileAll();

        String status = jdbc.queryForObject(
            "SELECT bosta_link_status FROM orders WHERE id = ?", String.class, orderId);
        Integer attempts = jdbc.queryForObject(
            "SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, orderId);

        assertThat(status).as("order flagged as not_created at max attempts").isEqualTo("not_created");
        assertThat(attempts).as("counter at max (3)").isEqualTo(3);
    }

    // ── r3: unlinked match found → order linked ───────────────────────────────

    @Test
    void r3_unlinkedMatchFound_orderLinkedShipmentCreated() {
        setupCourierAccount();
        UUID orderId = insertOrder("#R-003");
        String tracking = "RECONCILE-TN-003";

        // Unlinked delivery whose business_reference matches the order number (hashed variant).
        jdbc.update(
            "INSERT INTO unlinked_bosta_deliveries " +
            "  (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "   bosta_order_type, match_reason, resolved) " +
            "VALUES (?, ?, '#R-003', 45, 'SEND', 'NO_MATCH', false)",
            tenantId, tracking);

        reconcileJob.reconcileAll();

        Integer shipmentCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, orderId);
        assertThat(shipmentCount).as("shipment created after match").isEqualTo(1);

        Boolean resolved = jdbc.queryForObject(
            "SELECT resolved FROM unlinked_bosta_deliveries WHERE tracking_number = ?",
            Boolean.class, tracking);
        assertThat(resolved).as("unlinked delivery row resolved").isTrue();
    }

    // ── r4: not_created flag cleared when delivery is later linked ─────────────

    @Test
    void r4_notCreatedFlag_clearedViaManualLink() {
        setupCourierAccount();
        UUID orderId = insertOrder("#R-004");
        String tracking = "RECONCILE-TN-004";

        // Simulate a previously-flagged order
        jdbc.update(
            "UPDATE orders SET bosta_link_status = 'not_created', bosta_link_attempts = 3 " +
            "WHERE id = ?", orderId);

        // Insert unlinked delivery to link against
        Long unlinkedId = jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries " +
            "  (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "   bosta_order_type, match_reason, resolved) " +
            "VALUES (?, ?, '#R-004', 45, 'SEND', 'NO_MATCH', false) RETURNING id",
            Long.class, tenantId, tracking);

        // Link via manualLink — createOrFindShipment() calls clearReconcileFlag() internally.
        TenantContext.runAs(tenantId, () ->
            shipmentLinkService.manualLink(unlinkedId, orderId, null));

        String status = jdbc.queryForObject(
            "SELECT bosta_link_status FROM orders WHERE id = ?", String.class, orderId);
        Integer attempts = jdbc.queryForObject(
            "SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, orderId);

        assertThat(status).as("not_created flag cleared after successful link").isNull();
        assertThat(attempts).as("attempt counter reset to 0").isZero();
    }

    // ── r5: order with active shipment → skipped ─────────────────────────────

    @Test
    void r5_orderWithActiveShipment_skipped() {
        setupCourierAccount();
        UUID orderId = insertOrder("#R-005");
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state) " +
            "VALUES (?, ?, 'bosta', 'RECONCILE-TN-005', 'with_courier'::shipment_internal_state)",
            tenantId, orderId);

        reconcileJob.reconcileAll();

        Integer attempts = jdbc.queryForObject(
            "SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, orderId);
        assertThat(attempts).as("order with active shipment not processed").isZero();
    }

    // ── r6: terminal status → skipped ────────────────────────────────────────

    @Test
    void r6_terminalOrder_skipped() {
        setupCourierAccount();
        UUID cancelledId = jdbc.queryForObject(
            "INSERT INTO orders " +
            "  (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, 'EXT-R6', '#R-006', 'cancelled'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId);

        reconcileJob.reconcileAll();

        Integer attempts = jdbc.queryForObject(
            "SELECT bosta_link_attempts FROM orders WHERE id = ?", Integer.class, cancelledId);
        assertThat(attempts).as("cancelled order not processed by reconcile").isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void setupCourierAccount() {
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "  (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'test-hash', 'active')",
            tenantId, encryptionService.encrypt("reconcile-test-api-key"));
    }

    /** Creates a 'new' order placed now — placed_at must be set or the recency filter excludes it. */
    private UUID insertOrder(String number) {
        return jdbc.queryForObject(
            "INSERT INTO orders " +
            "  (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + number, number);
    }
}
