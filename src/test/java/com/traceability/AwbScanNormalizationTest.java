package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.*;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * AWB scan normalization integration tests (spec §5).
 *
 * All assertions run as app_user via appUserJdbc / appUserTx so that RLS is
 * in effect and the tests would fail if the tenant isolation policy was broken —
 * the class of bug that postgres (BYPASSRLS) cannot detect.
 *
 * Service calls use the @Autowired beans (postgres primary datasource) because
 * the service already has explicit tenant_id WHERE clauses; the app_user
 * assertions confirm the data is visible/invisible as RLS demands.
 *
 * Test n6 also fires a direct appUserJdbc query without a tenant GUC to prove
 * that RLS blocks cross-tenant data regardless of WHERE clauses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwbScanNormalizationTest {

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

    @Autowired ShipmentLinkService shipmentLinkSvc;
    @Autowired LookupService       lookupSvc;
    @Autowired JdbcTemplate        jdbc;
    @MockBean  BostaGateway        bostaGateway;
    @MockBean  JobScheduler        jobScheduler;

    // app_user connection for RLS-verified assertions and cross-tenant isolation checks.
    // TenantAwareDataSource fires SET LOCAL app.current_tenant on setAutoCommit(false),
    // so every appUserTx.execute() block is correctly GUC-scoped.
    // appUserJdbc queries outside appUserTx run in auto-commit → GUC never set → RLS blocks.
    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    UUID tenantId;    // tenantA — owner of test fixtures and all service calls
    UUID tenantBId;   // tenantB — used only in n6 cross-tenant test
    UUID tenantBStoreId;
    UUID actorId;
    UUID storeId;
    UUID variantId;

    @BeforeAll
    void setup() {
        // ── App-user datasource ──────────────────────────────────────────────────
        // "testpw" is accepted by the test container (trust auth); app_user has no
        // password set in migrations.
        DriverManagerDataSource rawDs = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appDs));

        // ── Shared fixtures (tenantA) ────────────────────────────────────────────
        tenantId  = UUID.randomUUID();
        actorId   = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AwbTenantA')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Pakker', 'pakker@awb.local', 'h', 'worker')",
                    actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'awb-a-test.myshopify.com', 'disconnected')",
                    storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-AWB', 'Jacket', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-AWB', 'Blue M', 'JACK-AWB')", variantId, tenantId, productId);

        // ── TenantB fixtures (for cross-tenant test) ─────────────────────────────
        tenantBId      = UUID.randomUUID();
        tenantBStoreId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AwbTenantB')", tenantBId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'awb-b-test.myshopify.com', 'disconnected')",
                    tenantBStoreId, tenantBId);
    }

    @BeforeEach void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        // Delete per-test rows for both tenants in FK-safe order.
        // The store rows (storeId, tenantBStoreId) are @BeforeAll fixtures — do not delete.
        for (UUID t : new UUID[]{tenantId, tenantBId}) {
            jdbc.update("DELETE FROM allocations  WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces        WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments     WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items   WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders        WHERE tenant_id = ?", t);
        }
    }

    // ── n1: Verify-match ──────────────────────────────────────────────────────
    //
    // Mode B happy path: plugin pre-ingested a forward shipment.
    // Scanning the physical label "D-07-2944282510" normalizes to "2944282510",
    // matches the stored tracking, completes without a new shipment row.

    @Test
    void n1_verifyMatch_prefixedScan_succeedsWithoutCreatingNewShipment() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String p1    = createPiece("packed");
        createAllocation(itemId, p1, "packed");
        insertForwardShipment(orderId, "2944282510");

        int shipsBefore = shipmentCount();

        Map<String, Object> result = shipmentLinkSvc.linkByAwbScan(orderId, "D-07-2944282510", actorId);

        assertThat(result.get("trackingNumber")).isEqualTo("2944282510");
        assertThat(result.get("linkedPieces")).isEqualTo(1);
        assertThat(shipmentCount()).isEqualTo(shipsBefore); // no new row

        // RLS-verified: piece transitioned and is visible to app_user with correct GUC
        String pieceStatus = appUserTxQuery(tenantId,
            "SELECT status FROM pieces WHERE id = ? AND tenant_id = ?",
            String.class, p1, tenantId);
        assertThat(pieceStatus).isEqualTo("awaiting_pickup");

        // Order advanced — visible via app_user
        String orderStatus = appUserTxQuery(tenantId,
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        assertThat(orderStatus).isEqualTo("awaiting_pickup");
    }

    // ── n2: Verify-mismatch ───────────────────────────────────────────────────
    //
    // Order has ingested forward shipment "2944282510".
    // Scanning "D-07-9111111111" normalizes to "9111111111" — a different AWB.
    // Must throw AwbMismatchException naming both values; no INSERT.

    @Test
    void n2_verifyMismatch_differentAwb_throws409NamingBothValues_noInsert() {
        UUID orderId = createOrder("packed");
        insertForwardShipment(orderId, "2944282510");

        int shipsBefore = shipmentCount();

        AwbMismatchException ex = catchThrowableOfType(
            () -> shipmentLinkSvc.linkByAwbScan(orderId, "D-07-9111111111", actorId),
            AwbMismatchException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getScannedAwb()).isEqualTo("9111111111");   // normalized
        assertThat(ex.getExistingAwb()).isEqualTo("2944282510");  // stored AWB
        assertThat(shipmentCount()).isEqualTo(shipsBefore);       // no INSERT happened
    }

    // ── n3: Idempotence ───────────────────────────────────────────────────────
    //
    // Scanning the same correct AWB twice must not create a duplicate shipment.
    // Second scan returns the same shipmentId; COUNT(shipments) stays at 1.

    @Test
    void n3_idempotence_scanSameAwbTwice_noDuplicateShipment() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String p1    = createPiece("packed");
        createAllocation(itemId, p1, "packed");
        insertForwardShipment(orderId, "2944282510");

        Map<String, Object> r1 = shipmentLinkSvc.linkByAwbScan(orderId, "D-07-2944282510", actorId);
        // After first scan order is awaiting_pickup; second scan is still valid (gate accepts it).
        Map<String, Object> r2 = shipmentLinkSvc.linkByAwbScan(orderId, "D-07-2944282510", actorId);

        assertThat(r1.get("shipmentId")).isEqualTo(r2.get("shipmentId"));

        // RLS-verified: exactly one shipment for this tenant
        Long count = appUserTxQuery(tenantId,
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(count).isEqualTo(1L);
    }

    // ── n4: Regression (reported bug) ─────────────────────────────────────────
    //
    // Before the normalizer, scanning "D-07-2944282510" when the DB held "2944282510"
    // triggered the swapped-AWB check on exact string mismatch → 409.
    // After the fix it must succeed.

    @Test
    void n4_regression_prefixedScanNoLongerTriggersSwappedCheck() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String p1    = createPiece("packed");
        createAllocation(itemId, p1, "packed");
        insertForwardShipment(orderId, "2944282510");

        // Pre-fix: this threw 409. Post-fix: must not throw.
        assertThatCode(() -> shipmentLinkSvc.linkByAwbScan(orderId, "D-07-2944282510", actorId))
            .doesNotThrowAnyException();
    }

    // ── n5: No-shipment path (new INSERT still works) ─────────────────────────
    //
    // When no forward shipment exists, the service falls through to INSERT.
    // The INSERT must store the NORMALIZED tracking number (bare digits), not the raw scan.
    // No courier_account → fetchAndStoreProviderDeliveryId returns early; shipment still created.

    @Test
    void n5_noExistingShipment_insertsNormalizedTrackingNumber() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String p1    = createPiece("packed");
        createAllocation(itemId, p1, "packed");
        // Intentionally no forward shipment — this exercises the INSERT path.

        shipmentLinkSvc.linkByAwbScan(orderId, "7777777770", actorId);

        // DB must store the normalized form (bare digits, same as input here — no prefix)
        String storedTracking = jdbc.queryForObject(
            "SELECT tracking_number FROM shipments WHERE order_id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        assertThat(storedTracking).isEqualTo("7777777770");

        // RLS-verified: shipment row is visible to app_user with correct GUC
        Long count = appUserTxQuery(tenantId,
            "SELECT COUNT(*) FROM shipments WHERE order_id = ? AND tenant_id = ?",
            Long.class, orderId, tenantId);
        assertThat(count).isEqualTo(1L);
    }

    // ── n6: Cross-tenant isolation ────────────────────────────────────────────
    //
    // TenantB has a forward shipment with tracking "2944282510".
    // TenantA scans "D-07-2944282510" for their own order (no forward shipment).
    //
    // Part A (RLS assertion): appUserJdbc WITHOUT a tenant GUC cannot see tenantB's
    //   shipment — this is the class of bug postgres (BYPASSRLS) cannot catch.
    //
    // Part B (service behavior): tenantA's scan must NOT expose tenantB's order info
    //   in the error. The 409 must come from the global UNIQUE constraint on tracking_number
    //   (not from handleSwappedAwbCheck finding tenantB's row via broken isolation).

    @Test
    void n6_crossTenant_tenantBShipmentInvisibleToAppUserWithoutGuc() {
        // Arrange: tenantB order + forward shipment with tracking "2944282510"
        String tenantBOrderNumber = "XTENANT-" + UUID.randomUUID();
        UUID tenantBOrderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status) " +
            "VALUES (?, ?, 'EXT-B-XTENANT', ?, 'awaiting_pickup'::order_status) RETURNING id",
            UUID.class, tenantBId, tenantBStoreId, tenantBOrderNumber);
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (gen_random_uuid(), ?, ?, 'bosta', '2944282510', 'created', 'forward')",
            tenantBId, tenantBOrderId);

        // Part A — RLS assertion (the test postgres cannot see).
        // app_user in auto-commit mode never fires setAutoCommit(false) → TenantAwareConnection
        // never injects the GUC → current_setting('app.current_tenant', true) = '' →
        // NULLIF('','')::uuid = null → RLS policy tenant_id = null → 0 rows.
        TenantContext.clear(); // ensure no GUC leaks into this call
        Long countWithoutGuc = appUserJdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ?",
            Long.class, "2944282510");
        assertThat(countWithoutGuc).isZero(); // RLS blocks without GUC

        // Restore tenantA context for Part B
        TenantContext.set(tenantId);

        // TenantA order (no forward shipment)
        UUID tenantAOrderId = createOrder("packed");

        // Part B — service behavior.
        // handleSwappedAwbCheck queries WHERE tenant_id = tenantA → null (tenantB's row invisible).
        // Falls to INSERT → global UNIQUE constraint on tracking_number fires → DuplicateKeyException
        // → 409 "Order already has an active shipment" (NOT the cross-tenant leak message).
        ResponseStatusException ex = catchThrowableOfType(
            () -> shipmentLinkSvc.linkByAwbScan(tenantAOrderId, "D-07-2944282510", actorId),
            ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(409);
        // Must be the DuplicateKeyException path — not the swapped-label path that would
        // expose tenantB's order number.
        assertThat(ex.getReason()).contains("active shipment");
        assertThat(ex.getReason()).doesNotContain(tenantBOrderNumber);
    }

    // ── n7: shipment_leg filter ───────────────────────────────────────────────
    //
    // V43 allows two active shipments per order: one forward leg, one return leg (CRP).
    // The verify-path query MUST filter shipment_leg='forward'; without it, a return-leg
    // row could be returned first and trigger a false AWB_MISMATCH.

    @Test
    void n7_shipmentLegFilter_orderWithForwardAndReturnLeg_forwardScanSucceeds() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String p1    = createPiece("packed");
        createAllocation(itemId, p1, "packed");

        // Forward leg: the shipment the packer will scan
        insertForwardShipment(orderId, "2944282510");

        // Return leg: a CRP return shipment that coexists under ux_active_shipment_per_order_leg
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (gen_random_uuid(), ?, ?, 'bosta', '9999999999', 'created', 'return')",
            tenantId, orderId);

        // Scanning the forward AWB must match the forward leg, not the return leg.
        // Without the shipment_leg='forward' filter in the verify query, LIMIT 1 could
        // return the return-leg row and produce a false AWB_MISMATCH.
        Map<String, Object> result = shipmentLinkSvc.linkByAwbScan(orderId, "D-07-2944282510", actorId);

        assertThat(result.get("trackingNumber")).isEqualTo("2944282510");
        assertThat(result.get("linkedPieces")).isEqualTo(1);
    }

    // ── n8: raw_scan persisted ────────────────────────────────────────────────
    //
    // After the verify-match path, piece_events.raw_scan must hold the VERBATIM
    // scanned string ("D-07-2944282510"), not the normalized form ("2944282510").
    // Queried as app_user with tenant GUC to confirm RLS allows the tenant to read
    // their own custody event.

    @Test
    void n8_rawScanPersisted_verifyMatchPath_storesVerbatimLabel() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String p1    = createPiece("packed");
        createAllocation(itemId, p1, "packed");
        insertForwardShipment(orderId, "2944282510");

        shipmentLinkSvc.linkByAwbScan(orderId, "D-07-2944282510", actorId);

        // RLS-verified: query piece_events as app_user with tenant GUC
        String rawScan = appUserTxQuery(tenantId,
            "SELECT raw_scan FROM piece_events " +
            "WHERE event_type = 'tracking_linked' AND piece_id = ? AND tenant_id = ?",
            String.class, p1, tenantId);

        // Must be the raw scanner output, NOT the normalized "2944282510"
        assertThat(rawScan).isEqualTo("D-07-2944282510");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createOrder(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, ?::order_status) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + UUID.randomUUID(), status);
    }

    private UUID createOrderItem(UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, variantId);
    }

    private String createPiece(String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status)",
            id, tenantId, variantId, "PC-" + id, id, status);
        return id;
    }

    private void createAllocation(UUID itemId, String pieceId, String status) {
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES (?, ?, ?, ?::allocation_status)",
            tenantId, itemId, pieceId, status);
    }

    private void insertForwardShipment(UUID orderId, String tracking) {
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (gen_random_uuid(), ?, ?, 'bosta', ?, 'created', 'forward')",
            tenantId, orderId, tracking);
    }

    private int shipmentCount() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ?",
            Integer.class, tenantId);
    }

    /**
     * Runs a single-row query as app_user with the given tenant GUC set.
     * TenantContext.runAs sets the context, appUserTx.execute fires setAutoCommit(false)
     * which triggers TenantAwareConnection to inject SET LOCAL app.current_tenant,
     * then runAs clears the context on exit.
     *
     * Call AFTER all service calls in the test — runAs clears TenantContext on return.
     */
    private <T> T appUserTxQuery(UUID gucTenantId, String sql, Class<T> type, Object... args) {
        return TenantContext.runAs(gucTenantId, () ->
            appUserTx.execute(s -> appUserJdbc.queryForObject(sql, type, args)));
    }
}
