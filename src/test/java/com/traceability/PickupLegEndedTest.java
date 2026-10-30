package com.traceability;

import com.traceability.inventory.PickupSessionService;
import com.traceability.inventory.PickupSessionService.ScanOutcome;
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

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleted-in-Bosta slice 3 — the pickup session never hands over a leg that ended in Bosta.
 *
 *   pl1 — scan of a cancelled forward leg → LEG_ENDED, nothing recorded
 *   pl2 — scan of a terminated forward leg → LEG_ENDED, nothing recorded
 *   pl3 — scan of a live-looking leg the status poll found deleted in Bosta (provider_not_found_at) → LEG_ENDED
 *   pl4 — legs that ended between scan and close (one cancelled, one deleted in Bosta) are left out at close:
 *         no with_courier, no custody lock, no history row, pieces stay packed, scan row gone, listed in the
 *         close summary; the live leg closes exactly as before (with_courier + lock + history + pieces);
 *         each left-out leg gets an audit_log entry (pickup, shipment, tracking, order, reason, who closed)
 *
 * Production-shaped fixtures: bare numeric 10-digit tracking numbers, packed pieces on packed allocations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PickupLegEndedTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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

    private static final AtomicLong TN = new AtomicLong(6_979_330_000L);

    private UUID tenantId, actorId, storeId;

    @BeforeAll
    void seed() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PickupLegEndedTenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Worker One', 'worker@leg-ended.test', '$2a$x', 'worker')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'leg-ended.myshopify.com', 'disconnected')", storeId, tenantId);
    }

    @Test
    void pl1_scanCancelledLeg_legEnded_nothingRecorded() {
        assertRefused(packedShipment(), "UPDATE shipments SET internal_state = 'cancelled' WHERE id = ?");
    }

    @Test
    void pl2_scanTerminatedLeg_legEnded_nothingRecorded() {
        assertRefused(packedShipment(), "UPDATE shipments SET internal_state = 'terminated' WHERE id = ?");
    }

    @Test
    void pl3_scanLegDeletedInBosta_legEnded_nothingRecorded() {
        assertRefused(packedShipment(), "UPDATE shipments SET provider_not_found_at = now() WHERE id = ?");
    }

    @Test
    void pl4_legsEndedBetweenScanAndClose_leftOut_othersHandedOver() {
        UUID live = packedShipment(), cancelled = packedShipment(), deleted = packedShipment();
        UUID session = service.openSession(tenantId, actorId, LocalDate.now(), "10:00 to 13:00", null);
        for (UUID s : new UUID[] { live, cancelled, deleted }) {
            assertThat(service.scan(tenantId, session, actorId, tn(s)).outcome()).isEqualTo(ScanOutcome.ACCEPTED);
        }
        // Bosta reports one cancelled and the poll finds the other deleted — after the scan, before the close.
        jdbc.update("UPDATE shipments SET internal_state = 'cancelled' WHERE id = ?", cancelled);
        jdbc.update("UPDATE shipments SET provider_not_found_at = now() WHERE id = ?", deleted);

        PickupSessionService.CloseResult close = service.closeSession(tenantId, session, actorId);

        assertThat(close.shipmentsClosed()).isEqualTo(1);
        assertThat(close.pieceExceptions()).isEmpty();
        assertThat(close.leftOut()).extracting(PickupSessionService.LeftOut::trackingNumber)
            .containsExactlyInAnyOrder(tn(cancelled), tn(deleted));
        assertThat(close.leftOut()).allSatisfy(l -> assertThat(l.orderNumber()).startsWith("#"));
        assertThat(close.leftOut()).extracting(PickupSessionService.LeftOut::reason)
            .containsExactlyInAnyOrder("cancelled", "deleted_in_bosta");

        // An audit_log entry per left-out leg, written before its scan row was removed.
        var audit = jdbc.queryForList(
            "SELECT actor_user_id, target_type, target_id, metadata->>'pickup_id' AS pickup_id, " +
            "       metadata->>'shipment_id' AS shipment_id, metadata->>'tracking_number' AS tracking_number, " +
            "       metadata->>'order_number' AS order_number, metadata->>'reason' AS reason " +
            "FROM audit_log WHERE tenant_id = ? AND action = 'pickup_leg_left_out' AND target_id = ?",
            tenantId, session.toString());
        assertThat(audit).hasSize(2);
        for (var a : audit) {
            assertThat(a.get("actor_user_id")).isEqualTo(actorId);
            assertThat(a.get("target_type")).isEqualTo("pickup");
            assertThat(a.get("pickup_id")).isEqualTo(session.toString());
            UUID sid = UUID.fromString((String) a.get("shipment_id"));
            assertThat(a.get("tracking_number")).isEqualTo(tn(sid));
            assertThat((String) a.get("order_number")).startsWith("#");
            assertThat(a.get("reason")).isEqualTo(sid.equals(cancelled) ? "cancelled" : "deleted_in_bosta");
        }

        // Live leg: closed exactly as before.
        assertThat(state(live)).isEqualTo("with_courier");
        assertThat(locked(live)).isTrue();
        assertThat(historyRows(live)).isEqualTo(1);
        assertThat(pieceStatus(live)).isEqualTo("with_courier");

        // Ended legs: untouched, pieces still packed, no longer in the session.
        assertThat(state(cancelled)).isEqualTo("cancelled");
        assertThat(state(deleted)).isEqualTo("created");
        for (UUID s : new UUID[] { cancelled, deleted }) {
            assertThat(locked(s)).as("custody lock").isFalse();
            assertThat(historyRows(s)).as("history").isZero();
            assertThat(pieceStatus(s)).as("piece").isEqualTo("packed");
        }
        assertThat(jdbc.queryForList("SELECT shipment_id FROM pickup_shipments WHERE pickup_id = ?", UUID.class, session))
            .containsExactly(live);
        assertThat(jdbc.queryForMap("SELECT session_status, no_of_packages FROM pickups WHERE id = ?", session))
            .containsEntry("session_status", "closed").containsEntry("no_of_packages", 1);
        assertThat(service.getManifestData(tenantId, session).shipments())
            .extracting(PickupSessionService.ScanEntry::trackingNumber).containsExactly(tn(live));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertRefused(UUID shipment, String endIt) {
        jdbc.update(endIt, shipment);
        UUID session = service.openSession(tenantId, actorId, LocalDate.now(), "10:00 to 13:00", null);
        PickupSessionService.ScanResult r = service.scan(tenantId, session, actorId, tn(shipment));
        assertThat(r.outcome()).isEqualTo(ScanOutcome.LEG_ENDED);
        assertThat(r.entry()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pickup_shipments WHERE pickup_id = ?", Integer.class, session))
            .isZero();
        assertThat(pieceStatus(shipment)).isEqualTo("packed");
    }

    private UUID packedShipment() {
        UUID productId = UUID.randomUUID(), variantId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')", productId, tenantId, storeId, "gid://shopify/Product/" + productId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, ?, 'M', ?)", variantId, tenantId, productId, "gid://shopify/ProductVariant/" + variantId,
                    "LS-" + variantId.toString().substring(0, 8));
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'packed'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/" + UUID.randomUUID(),
            "#" + (5000 + TN.get() % 1000));
        UUID itemId = jdbc.queryForObject(
            "INSERT INTO order_items (id, order_id, tenant_id, variant_id, quantity) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 1) RETURNING id", UUID.class, orderId, tenantId, variantId);
        UUID shipmentId = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'forward') RETURNING id",
            UUID.class, tenantId, orderId, String.valueOf(TN.incrementAndGet()));
        String pieceId = "PC-LE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'packed'::piece_status)",
                    pieceId, tenantId, variantId, "BC-" + pieceId, pieceId);
        jdbc.update("INSERT INTO allocations (piece_id, order_item_id, tenant_id, status) " +
                    "VALUES (?, ?, ?, 'packed'::allocation_status)", pieceId, itemId, tenantId);
        return shipmentId;
    }

    private String tn(UUID shipment) {
        return jdbc.queryForObject("SELECT tracking_number FROM shipments WHERE id = ?", String.class, shipment);
    }

    private String state(UUID shipment) {
        return jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE id = ?", String.class, shipment);
    }

    private boolean locked(UUID shipment) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT custody_locked_by_scan FROM shipments WHERE id = ?", Boolean.class, shipment));
    }

    private int historyRows(UUID shipment) {
        return jdbc.queryForObject("SELECT count(*) FROM shipment_status_history WHERE shipment_id = ?",
            Integer.class, shipment);
    }

    private String pieceStatus(UUID shipment) {
        return jdbc.queryForObject(
            "SELECT p.status::text FROM pieces p JOIN allocations a ON a.piece_id = p.id " +
            "JOIN order_items oi ON oi.id = a.order_item_id JOIN shipments s ON s.order_id = oi.order_id " +
            "WHERE s.id = ?", String.class, shipment);
    }
}
