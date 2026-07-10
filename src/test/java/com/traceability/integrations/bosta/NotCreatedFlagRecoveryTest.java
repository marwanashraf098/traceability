package com.traceability.integrations.bosta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR-4.4 Bug Fix #2 — not_created flag recovery.
 *
 * Root cause: BostaOrderReconcileJob gates eligibility on bosta_link_status IS NULL,
 * so an order flagged 'not_created' is never retried even when its matching Bosta
 * delivery later lands in unlinked_bosta_deliveries.
 *
 * Fix: recordUnlinked() clears the 'not_created' flag on first delivery arrival
 * (INSERT path), but NOT on subsequent state updates (ON CONFLICT DO UPDATE path).
 * Detection uses count-before-upsert — not xmax, which returns 0 for both INSERT
 * and DO UPDATE (new tuple always starts with xmax = 0).
 *
 * Tests are in the same package as BostaWebhookJob to access package-private
 * recordUnlinked() directly, without proxy/spy complications.
 *
 * Matrix:
 *   nc1 — oscillation guard (two-phase): INSERT clears flag; ON CONFLICT DO UPDATE does NOT
 *   nc2 — full flow: flag cleared → reconcile finds order eligible → manualLink() links it
 *   nc3 — no auto-link: delivery arrival alone never creates a shipment; reconcile is required
 *
 * All assertions on bosta_link_status and unlinked row state use app_user (RLS enforced).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotCreatedFlagRecoveryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",           POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username",      POSTGRES::getUsername);
        r.add("spring.datasource.password",      POSTGRES::getPassword);
        r.add("spring.flyway.url",               POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",              POSTGRES::getUsername);
        r.add("spring.flyway.password",          POSTGRES::getPassword);
        r.add("bosta.reconcile.max-attempts",    () -> "3");
    }

    @Autowired JdbcTemplate           jdbc;
    @Autowired ObjectMapper           mapper;
    @Autowired EncryptionService      encryptionService;
    @Autowired BostaWebhookJob        bostaWebhookJob;
    @Autowired BostaOrderReconcileJob reconcileJob;
    @MockBean  BostaGateway           bostaGateway;
    @MockBean  JobScheduler           jobScheduler;

    // app_user datasource — RLS enforced (no BYPASSRLS). Used for assertions that prove
    // the flag clear and unlinked state are tenant-scoped and visible under RLS.
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    private UUID tenantId;
    private UUID storeId;

    @BeforeAll
    void setupAppUser() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @BeforeAll
    void createFixtures() {
        tenantId = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'NotCreatedFlagRecoveryTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'nc_owner@test.local', 'h', 'owner')",
            UUID.randomUUID(), tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'nc-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments              WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items            WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders                 WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM webhook_events         WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM courier_accounts       WHERE tenant_id = ?", tenantId);
    }

    // ── nc1: oscillation guard — two phases ──────────────────────────────────────────────────

    /**
     * Phase 1: first delivery arrival (INSERT) clears the 'not_created' flag.
     * Phase 2: same tracking at a new Bosta state (ON CONFLICT DO UPDATE) must NOT re-clear.
     *
     * This is the exact oscillation scenario: if DO UPDATE also cleared the flag, every
     * Bosta state update would re-open a retry loop the merchant thought was resolved.
     * The count-before-upsert guard (isFirstArrival = false on phase 2) prevents this.
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    void nc1_oscillationGuard_firstArrivalClearsFlag_doUpdateDoesNot() {
        setupCourierAccount();
        UUID orderId = insertOrderFlagged("#NC-001");
        Long wid = insertWebhookEvent("NC-TN-01");

        // Phase 1: delivery at state 24 (SEND) — first arrival, no existing unlinked row.
        // isFirstArrival = (count=0 before INSERT) = true → flag must be cleared.
        BostaDelivery delivery24 = new BostaDelivery(
            "NC-TN-01", 24, "SEND", 0, "#NC-001", null, mapper.createObjectNode());

        TenantContext.runAs(tenantId, () ->
            bostaWebhookJob.recordUnlinked(tenantId, "NC-TN-01", delivery24, wid, "NO_MATCH"));

        // Assert via app_user (RLS): flag cleared after first arrival.
        String statusAfterPhase1 = linkStatusViaAppUser(orderId);
        assertThat(statusAfterPhase1)
            .as("nc1 phase 1: not_created flag cleared on first delivery arrival (INSERT path)")
            .isNull();

        // Also verify unlinked row was created.
        assertThat(countUnlinkedViaAppUser()).as("unlinked row written").isEqualTo(1);

        // Re-flag the order to simulate the merchant manually re-setting the flag,
        // or a reconcile cycle that re-flagged before the delivery was processed.
        jdbc.update(
            "UPDATE orders SET bosta_link_status = 'not_created', bosta_link_attempts = 3 " +
            "WHERE id = ?", orderId);

        // Confirm re-flag is visible via app_user before phase 2.
        assertThat(linkStatusViaAppUser(orderId))
            .as("re-flag precondition before phase 2")
            .isEqualTo("not_created");

        // Phase 2: same tracking number at NEW Bosta state 45 (Delivered, state change).
        // Guard 3 in BostaIngestionHelper allowed re-enqueueing because state changed.
        // ON CONFLICT DO UPDATE fires — count before INSERT = 1 → isFirstArrival=false.
        // Flag must STAY 'not_created' (no re-clear).
        Long wid2 = insertWebhookEvent("NC-TN-01");
        BostaDelivery delivery45 = new BostaDelivery(
            "NC-TN-01", 45, "SEND", 1, "#NC-001", null, mapper.createObjectNode());

        TenantContext.runAs(tenantId, () ->
            bostaWebhookJob.recordUnlinked(tenantId, "NC-TN-01", delivery45, wid2, "NO_MATCH"));

        // Assert via app_user: flag STAYS not_created — ON CONFLICT path is guarded.
        String statusAfterPhase2 = linkStatusViaAppUser(orderId);
        assertThat(statusAfterPhase2)
            .as("nc1 phase 2: ON CONFLICT DO UPDATE must NOT re-clear the not_created flag")
            .isEqualTo("not_created");

        // Unlinked row still exists (state updated, not resolved).
        assertThat(countUnlinkedViaAppUser())
            .as("unlinked row persists after state update")
            .isEqualTo(1);
    }

    // ── nc2: full flow — flag cleared → reconcile picks up → links via manualLink() ──────────

    /**
     * Full happy path: the first delivery arrival clears the 'not_created' flag, making the
     * order eligible for the next reconcile cycle. The reconcile job finds the unlinked row
     * via business_reference match and calls manualLink() to create the shipment.
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    void nc2_fullFlow_flagCleared_nextReconcileCycleLinks() {
        setupCourierAccount();
        UUID orderId = insertOrderFlagged("#NC-002");
        Long wid = insertWebhookEvent("NC-TN-02");

        BostaDelivery delivery = new BostaDelivery(
            "NC-TN-02", 45, "SEND", 0, "#NC-002", null, mapper.createObjectNode());

        // First arrival: flag cleared, unlinked row created.
        TenantContext.runAs(tenantId, () ->
            bostaWebhookJob.recordUnlinked(tenantId, "NC-TN-02", delivery, wid, "NO_MATCH"));

        // Verify flag cleared via app_user before reconcile runs.
        assertThat(linkStatusViaAppUser(orderId))
            .as("nc2: flag cleared after first delivery arrival")
            .isNull();

        // No shipment yet — recordUnlinked() never auto-links.
        assertThat(shipmentExists("NC-TN-02"))
            .as("nc2: no shipment created by delivery arrival alone")
            .isFalse();

        // Reconcile cycle: order is now eligible (bosta_link_status IS NULL).
        // processOrder() finds the unlinked row via businessRef=#NC-002 match,
        // calls manualLink() → createOrFindShipment() → shipment created.
        reconcileJob.reconcileAll();

        // Shipment created by the reconcile + manualLink() path.
        assertThat(shipmentExists("NC-TN-02"))
            .as("nc2: shipment created by reconcile after flag was cleared")
            .isTrue();

        // Unlinked row resolved via manualLink().
        assertThat(unresolvedRowExists("NC-TN-02"))
            .as("nc2: unlinked row resolved after manualLink()")
            .isFalse();

        // Final flag check via app_user: cleared and stays clear after successful link.
        assertThat(linkStatusViaAppUser(orderId))
            .as("nc2: bosta_link_status remains NULL after successful reconcile link")
            .isNull();
    }

    // ── nc3: no auto-link proof ─────────────────────────────────────────────────────────────

    /**
     * Proves that recordUnlinked() never auto-links — it only clears the eligibility flag.
     * The actual shipment creation requires a reconcile cycle. This prevents an unlinked
     * delivery arrival from bypassing the operator-review path.
     */
    @Test
    @org.junit.jupiter.api.Order(3)
    void nc3_noAutoLink_deliveryArrivalAloneNeverCreatesShipment() {
        setupCourierAccount();
        UUID orderId = insertOrderFlagged("#NC-003");
        Long wid = insertWebhookEvent("NC-TN-03");

        BostaDelivery delivery = new BostaDelivery(
            "NC-TN-03", 41, "SEND", 0, "#NC-003", null, mapper.createObjectNode());

        TenantContext.runAs(tenantId, () ->
            bostaWebhookJob.recordUnlinked(tenantId, "NC-TN-03", delivery, wid, "NO_MATCH"));

        // Flag cleared (first arrival).
        assertThat(linkStatusViaAppUser(orderId))
            .as("nc3: flag cleared after delivery arrival")
            .isNull();

        // Unlinked row written (delivery is in unlinked, not directly linked).
        assertThat(countUnlinkedViaAppUser())
            .as("nc3: unlinked row exists after recordUnlinked()")
            .isEqualTo(1);

        // NO reconcile run in this test — verify no shipment was created by the delivery arrival.
        assertThat(shipmentExists("NC-TN-03"))
            .as("nc3: no shipment created without a reconcile cycle — delivery arrival alone is not enough")
            .isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void setupCourierAccount() {
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "  (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'nc-test-hash', 'active')",
            tenantId, encryptionService.encrypt("nc-test-api-key"));
    }

    private UUID insertOrderFlagged(String number) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO orders " +
            "  (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + number, number);
        // Simulate: max reconcile attempts exhausted → not_created flag set.
        jdbc.update(
            "UPDATE orders SET bosta_link_status = 'not_created', bosta_link_attempts = 3 " +
            "WHERE id = ?", id);
        return id;
    }

    private Long insertWebhookEvent(String tracking) {
        return jdbc.queryForObject(
            "INSERT INTO webhook_events " +
            "  (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenantId,
            String.format("{\"trackingNumber\":\"%s\"}", tracking));
    }

    /**
     * Reads bosta_link_status for the order via app_user (RLS enforced).
     * Returns null if the column is NULL (flag cleared), or "not_created" if still flagged.
     */
    private String linkStatusViaAppUser(UUID orderId) {
        return TenantContext.runAs(tenantId, () -> appUserTx.execute(s ->
            appUserJdbc.query(
                "SELECT bosta_link_status FROM orders WHERE id = ?",
                rs -> rs.next() ? rs.getString("bosta_link_status") : null,
                orderId)));
    }

    /** Counts unresolved unlinked rows for this tenant via app_user (RLS enforced). */
    private int countUnlinkedViaAppUser() {
        Integer n = TenantContext.runAs(tenantId, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject(
                "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE resolved = false",
                Integer.class)));
        return n != null ? n : 0;
    }

    private boolean shipmentExists(String tracking) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            Integer.class, tracking, tenantId);
        return n != null && n > 0;
    }

    private boolean unresolvedRowExists(String tracking) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries " +
            "WHERE tracking_number = ? AND tenant_id = ? AND resolved = false",
            Integer.class, tracking, tenantId);
        return n != null && n > 0;
    }
}
