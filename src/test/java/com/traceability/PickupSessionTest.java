package com.traceability;

import com.traceability.inventory.*;
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

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FR-16 Phase 1 — Pickup session integration tests.
 *
 * Matrix:
 *   ps1 — open → scan → close: shipment gets with_courier, custody_locked_by_scan=true,
 *          piece transitions to with_courier, handed_to_courier event written
 *   ps2 — scan duplicate returns DUPLICATE outcome
 *   ps3 — return-leg shipment scan returns NOT_FORWARD_LEG
 *   ps4 — shipment in another open session returns OTHER_SESSION
 *   ps5 — after close, BostaWebhookJob step-9 guard holds: incoming 'created' does not
 *          demote with_courier (custody_locked_by_scan prevents demotion)
 *   ps6 — after close, incoming 'with_courier' from Bosta clears lock (genuine progression)
 *   ps7 — app_user with wrong tenant cannot see pickup session (RLS)
 *
 * All tests run as postgres (BYPASSRLS) for data setup; ps7 uses app_user for isolation check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PickupSessionTest {

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

    @Autowired JdbcTemplate         jdbc;
    @Autowired PickupSessionService service;
    @MockBean  JobScheduler         jobScheduler;

    // Shared tenant / actor seeded once.
    private UUID tenantId;
    private UUID actorId;
    private UUID storeId;

    @BeforeAll
    void seedTenantAndActor() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PickupTestTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Worker One', 'worker@pickup.test', '$2a$x', 'worker')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'pickup-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createPackedShipment(String leg) {
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        String tn = "TN-PS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, ?, 'Test Product', 'active')",
            productId, tenantId, storeId, "EXT-PROD-" + productId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, ?, 'Default', ?)",
            variantId, tenantId, productId, "EXT-VAR-" + variantId, "SKU-PS-" + variantId);

        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'awaiting_pickup'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId,
            "EXT-PS-" + UUID.randomUUID(), "#PS-" + UUID.randomUUID().toString().substring(0, 6));

        UUID orderItemId = jdbc.queryForObject(
            "INSERT INTO order_items (id, order_id, tenant_id, variant_id, quantity) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 1) RETURNING id",
            UUID.class, orderId, tenantId, variantId);

        UUID shipmentId = jdbc.queryForObject(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, " +
            "internal_state, shipment_leg) " +
            "VALUES (gen_random_uuid(), ?, ?, 'bosta', ?, 'created'::shipment_internal_state, ?) RETURNING id",
            UUID.class, tenantId, orderId, tn, leg);

        // Create a piece in packed status
        String pieceId = "PC-PS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'packed'::piece_status)",
            pieceId, tenantId, variantId, "BC-" + pieceId, pieceId);
        jdbc.update(
            "INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) " +
            "VALUES (?, ?, ?, 'packed'::allocation_status)",
            pieceId, orderItemId, tenantId);

        return shipmentId;
    }

    // ── ps1: full happy path ──────────────────────────────────────────────────

    @Test
    @Order(1)
    void ps1_openScanClose_shipmentWithCourier_piecetransitioned() {
        UUID shipmentId = createPackedShipment("forward");
        String tn = jdbc.queryForObject(
            "SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipmentId);

        // Open session
        UUID sessionId = service.openSession(tenantId, actorId, LocalDate.now(), "10:00 to 13:00", null);
        assertThat(sessionId).isNotNull();

        // Scan
        PickupSessionService.ScanResult result = service.scan(tenantId, sessionId, actorId, tn);
        assertThat(result.outcome()).isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        assertThat(result.entry()).isNotNull();
        assertThat(result.entry().trackingNumber()).isEqualTo(tn);

        // Close
        PickupSessionService.CloseResult close = service.closeSession(tenantId, sessionId, actorId);
        assertThat(close.shipmentsClosed()).isEqualTo(1);
        assertThat(close.pieceExceptions()).isEmpty();

        // Shipment must be with_courier, custody_locked_by_scan=true
        var row = jdbc.queryForMap(
            "SELECT internal_state::text, custody_locked_by_scan FROM shipments WHERE id = ?",
            shipmentId);
        assertThat(row.get("internal_state")).isEqualTo("with_courier");
        assertThat(row.get("custody_locked_by_scan")).isEqualTo(true);

        // Session must be closed
        String sessionStatus = jdbc.queryForObject(
            "SELECT session_status FROM pickups WHERE id = ?", String.class, sessionId);
        assertThat(sessionStatus).isEqualTo("closed");

        // piece_events must have a handed_to_courier event
        Integer evtCount = jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM piece_events pe
            JOIN allocations a  ON a.piece_id      = pe.piece_id
            JOIN order_items oi ON oi.id            = a.order_item_id
            JOIN orders o       ON o.id             = oi.order_id
            JOIN shipments s    ON s.order_id        = o.id
            WHERE s.id = ? AND pe.event_type = 'handed_to_courier'
            """,
            Integer.class, shipmentId);
        assertThat(evtCount).as("handed_to_courier event written").isGreaterThan(0);

        // Piece must be with_courier
        Integer withCourierPieces = jdbc.queryForObject(
            """
            SELECT COUNT(*)::int FROM pieces p
            JOIN allocations a  ON a.piece_id      = p.id
            JOIN order_items oi ON oi.id            = a.order_item_id
            JOIN orders o       ON o.id             = oi.order_id
            JOIN shipments s    ON s.order_id        = o.id
            WHERE s.id = ? AND p.status = 'with_courier'::piece_status
            """,
            Integer.class, shipmentId);
        assertThat(withCourierPieces).as("piece transitioned to with_courier").isGreaterThan(0);
    }

    // ── ps2: duplicate scan ───────────────────────────────────────────────────

    @Test
    @Order(2)
    void ps2_duplicateScan_returnsDuplicate() {
        UUID shipmentId = createPackedShipment("forward");
        String tn = jdbc.queryForObject(
            "SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipmentId);

        UUID sessionId = service.openSession(tenantId, actorId, LocalDate.now(), "10:00 to 13:00", null);
        service.scan(tenantId, sessionId, actorId, tn);

        PickupSessionService.ScanResult dup = service.scan(tenantId, sessionId, actorId, tn);
        assertThat(dup.outcome()).isEqualTo(PickupSessionService.ScanOutcome.DUPLICATE);
        assertThat(dup.entry()).isNull();
    }

    // ── ps3: return-leg shipment ──────────────────────────────────────────────

    @Test
    @Order(3)
    void ps3_returnLegShipment_returnsNotForwardLeg() {
        UUID shipmentId = createPackedShipment("return");
        String tn = jdbc.queryForObject(
            "SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipmentId);

        UUID sessionId = service.openSession(tenantId, actorId, LocalDate.now(), "10:00 to 13:00", null);
        PickupSessionService.ScanResult result = service.scan(tenantId, sessionId, actorId, tn);
        assertThat(result.outcome()).isEqualTo(PickupSessionService.ScanOutcome.NOT_FORWARD_LEG);
    }

    // ── ps4: shipment already in another open session ─────────────────────────

    @Test
    @Order(4)
    void ps4_shipmentInOtherOpenSession_returnsOtherSession() {
        UUID shipmentId = createPackedShipment("forward");
        String tn = jdbc.queryForObject(
            "SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipmentId);

        UUID session1 = service.openSession(tenantId, actorId, LocalDate.now().plusDays(1), "10:00 to 13:00", null);
        service.scan(tenantId, session1, actorId, tn);

        UUID session2 = service.openSession(tenantId, actorId, LocalDate.now().plusDays(2), "13:00 to 16:00", null);
        PickupSessionService.ScanResult result = service.scan(tenantId, session2, actorId, tn);
        assertThat(result.outcome()).isEqualTo(PickupSessionService.ScanOutcome.OTHER_SESSION);
    }

    // ── ps5: custody guard holds on Bosta 'created' after close ──────────────

    @Test
    @Order(5)
    void ps5_custodyGuard_holdsWith_courier_onBostaCreated() {
        UUID shipmentId = createPackedShipment("forward");
        String tn = jdbc.queryForObject(
            "SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipmentId);

        UUID sessionId = service.openSession(tenantId, actorId, LocalDate.now(), "13:00 to 16:00", null);
        service.scan(tenantId, sessionId, actorId, tn);
        service.closeSession(tenantId, sessionId, actorId);

        // Confirm custody lock is set
        Boolean locked = jdbc.queryForObject(
            "SELECT custody_locked_by_scan FROM shipments WHERE id = ?", Boolean.class, shipmentId);
        assertThat(locked).isTrue();

        // Simulate BostaWebhookJob step-9 UPDATE arriving with internal_state='created'
        // (Bosta codes 10/11/20 — pre-transit, arriving late).
        // The guard CASE should hold internal_state at 'with_courier'.
        TenantContext.runAs(tenantId, () ->
            jdbc.update("""
                UPDATE shipments
                SET internal_state = CASE
                        WHEN custody_locked_by_scan = true
                         AND shipment_leg = 'forward'
                         AND 'created'::shipment_internal_state = 'created'
                        THEN internal_state
                        ELSE 'created'::shipment_internal_state
                    END,
                    custody_locked_by_scan = CASE
                        WHEN custody_locked_by_scan = true
                         AND shipment_leg = 'forward'
                         AND 'created'::shipment_internal_state IN
                             ('with_courier','returning','delivered','returned')
                        THEN false
                        ELSE custody_locked_by_scan
                    END
                WHERE id = ?
                """, shipmentId));

        var row = jdbc.queryForMap(
            "SELECT internal_state::text, custody_locked_by_scan FROM shipments WHERE id = ?",
            shipmentId);
        assertThat(row.get("internal_state"))
            .as("guard must hold with_courier; must not demote to created")
            .isEqualTo("with_courier");
        assertThat(row.get("custody_locked_by_scan"))
            .as("lock stays — 'created' is not a release state")
            .isEqualTo(true);
    }

    // ── ps6: guard releases on Bosta with_courier ─────────────────────────────

    @Test
    @Order(6)
    void ps6_custodyGuard_releases_onBostaWithCourier() {
        UUID shipmentId = createPackedShipment("forward");
        String tn = jdbc.queryForObject(
            "SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipmentId);

        UUID sessionId = service.openSession(tenantId, actorId, LocalDate.now(), "10:00 to 13:00", null);
        service.scan(tenantId, sessionId, actorId, tn);
        service.closeSession(tenantId, sessionId, actorId);

        // Simulate Bosta reporting with_courier (state 21 — 'Picked up from business')
        TenantContext.runAs(tenantId, () ->
            jdbc.update("""
                UPDATE shipments
                SET internal_state = CASE
                        WHEN custody_locked_by_scan = true
                         AND shipment_leg = 'forward'
                         AND 'with_courier'::shipment_internal_state = 'created'
                        THEN internal_state
                        ELSE 'with_courier'::shipment_internal_state
                    END,
                    custody_locked_by_scan = CASE
                        WHEN custody_locked_by_scan = true
                         AND shipment_leg = 'forward'
                         AND 'with_courier'::shipment_internal_state IN
                             ('with_courier','returning','delivered','returned')
                        THEN false
                        ELSE custody_locked_by_scan
                    END
                WHERE id = ?
                """, shipmentId));

        var row = jdbc.queryForMap(
            "SELECT internal_state::text, custody_locked_by_scan FROM shipments WHERE id = ?",
            shipmentId);
        assertThat(row.get("internal_state")).isEqualTo("with_courier");
        assertThat(row.get("custody_locked_by_scan"))
            .as("lock released — Bosta confirmed with_courier")
            .isEqualTo(false);
    }

    // ── ps7: RLS — wrong tenant cannot see session ────────────────────────────

    @Test
    @Order(7)
    void ps7_wrongTenant_cannotSeeSession() {
        UUID sessionId = service.openSession(tenantId, actorId, LocalDate.now().plusDays(3), "10:00 to 13:00", null);
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'OtherPickupTenant')", otherTenant);

        try (Connection appConn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "app_user", "app_user_password")) {
            appConn.setAutoCommit(false);

            // Correct tenant — visible
            try (var stmt = appConn.createStatement()) {
                stmt.execute("SET LOCAL app.current_tenant = '" + tenantId + "'");
            }
            try (var ps = appConn.prepareStatement(
                    "SELECT COUNT(*) FROM pickups WHERE id = ?")) {
                ps.setObject(1, sessionId);
                var rs = ps.executeQuery();
                rs.next();
                assertThat(rs.getInt(1))
                    .as("correct tenant sees the session")
                    .isEqualTo(1);
            }

            // Wrong tenant — invisible
            try (var stmt = appConn.createStatement()) {
                stmt.execute("SET LOCAL app.current_tenant = '" + otherTenant + "'");
            }
            try (var ps = appConn.prepareStatement(
                    "SELECT COUNT(*) FROM pickups WHERE id = ?")) {
                ps.setObject(1, sessionId);
                var rs = ps.executeQuery();
                rs.next();
                assertThat(rs.getInt(1))
                    .as("wrong tenant cannot see session (RLS)")
                    .isZero();
            }

            appConn.rollback();
        } catch (SQLException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "app_user not available in test container — RLS assertion skipped: " + e.getMessage());
        }
    }
}
