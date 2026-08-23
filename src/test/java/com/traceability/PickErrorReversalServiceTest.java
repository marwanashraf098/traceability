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

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Revert-to-confirm test for the 2026-08-23 pick-error one-shot (PickErrorReversalService).
 *
 * rev1 — happy path: piece awaiting_pickup + packed allocation + order awaiting_pickup,
 *        mirroring the incident's deliberate lockstep break (allocation stayed 'packed'
 *        while the piece advanced past it to awaiting_pickup via tracking_linked).
 *        Asserts piece→available, current_order_id→null, allocation→released, order→new,
 *        a new pick_error_reversed piece_events row, and the pre-existing events untouched.
 * rev2 — negative control: piece seeded at PACKED (not AWAITING_PICKUP) instead. The
 *        service's hardcoded expectedStatus=AWAITING_PICKUP no longer matches reality —
 *        ledger.transition() must throw StateConflictException and the whole transaction
 *        must roll back: piece still packed, allocation still packed, order still
 *        awaiting_pickup, no pick_error_reversed event written.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PickErrorReversalServiceTest {

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

    @Autowired PickErrorReversalService reversalSvc;
    @Autowired InventoryLedger          ledger;
    @Autowired JdbcTemplate             jdbc;
    @MockBean  JobScheduler             jobScheduler;

    // Must match PickErrorReversalService's hardcoded constants exactly.
    static final UUID   TENANT_ID     = UUID.fromString("e785e5e4-2c5c-428e-afdd-d26d90754229");
    static final String PIECE_ID      = "01KZ1JSQRY1JSKZPTNB126AP7M";
    static final UUID   ORDER_ID      = UUID.fromString("ebe1c608-d184-4cd5-a55c-e2ffd5c55291");
    static final UUID   ALLOCATION_ID = UUID.fromString("5cf96ab2-29f6-46a4-8677-719aac1a4330");

    UUID actorId, storeId, variantId, locationId, orderItemId;

    @BeforeAll
    void setupFixture() {
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'The Snouts')", TENANT_ID);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 'rev@test.com', 'x', 'owner'::user_role)",
            actorId, TENANT_ID);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'RevLoc')",
            locationId, TENANT_ID);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'rev.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, TENANT_ID);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/REV', 'Rev Product')",
            productId, TENANT_ID, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/REV', 'Default', 'REV-SKU')",
            variantId, TENANT_ID, productId);
    }

    @AfterEach
    void cleanIncidentRows() {
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", TENANT_ID);
    }

    // rev1: happy path — awaiting_pickup piece / packed allocation / awaiting_pickup order
    @Test
    void rev1_reversesPickErrorAndReleasesAllocation() {
        seedIncident(PieceStatus.AWAITING_PICKUP, "awaiting_pickup");

        Integer eventsBefore = countPieceEvents();
        assertThat(eventsBefore).isEqualTo(3); // scan, pack, tracking_linked — seeded by seedIncident()

        TenantContext.set(TENANT_ID);
        try {
            reversalSvc.reverse(actorId);
        } finally {
            TenantContext.clear();
        }

        // Piece reversed
        var piece = jdbc.queryForMap(
            "SELECT status::text AS status, current_order_id FROM pieces WHERE id = ?", PIECE_ID);
        assertThat(piece.get("status")).isEqualTo("available");
        assertThat(piece.get("current_order_id")).isNull();

        // Allocation released
        String allocStatus = jdbc.queryForObject(
            "SELECT status::text FROM allocations WHERE id = ?", String.class, ALLOCATION_ID);
        assertThat(allocStatus).isEqualTo("released");

        // Order restored to not-traced resting status; not_traced_at untouched
        var order = jdbc.queryForMap(
            "SELECT status::text AS status, not_traced_at FROM orders WHERE id = ?", ORDER_ID);
        assertThat(order.get("status")).isEqualTo("new");
        assertThat(order.get("not_traced_at")).isNotNull();

        // Append-only: original events untouched, exactly one new event appended
        Integer eventsAfter = countPieceEvents();
        assertThat(eventsAfter).isEqualTo(eventsBefore + 1);

        var reversalEvent = jdbc.queryForMap(
            "SELECT from_status::text AS from_status, to_status::text AS to_status, " +
            "       order_id, metadata::text AS metadata " +
            "FROM piece_events WHERE piece_id = ? AND event_type = 'pick_error_reversed'",
            PIECE_ID);
        assertThat(reversalEvent.get("from_status")).isEqualTo("awaiting_pickup");
        assertThat(reversalEvent.get("to_status")).isEqualTo("available");
        assertThat(reversalEvent.get("order_id")).isEqualTo(ORDER_ID);
        assertThat((String) reversalEvent.get("metadata")).contains("erroneous_pick_reversal");

        // Original scan/pack/tracking_linked rows still present, unchanged
        Integer preExisting = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? " +
            "AND event_type IN ('scan','pack','tracking_linked')",
            Integer.class, PIECE_ID);
        assertThat(preExisting).isEqualTo(3);

        // Audit trail written
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'pick_error_reversal' " +
            "AND target_id = ?",
            Integer.class, TENANT_ID, ORDER_ID.toString());
        assertThat(auditCount).isEqualTo(1);
    }

    // rev2: negative control — piece actually PACKED, not AWAITING_PICKUP. Guard must abort
    // the whole transaction; nothing partially applies.
    @Test
    void rev2_wrongActualStatus_throwsAndRollsBackEverything() {
        seedIncident(PieceStatus.PACKED, "awaiting_pickup");

        TenantContext.set(TENANT_ID);
        try {
            assertThatThrownBy(() -> reversalSvc.reverse(actorId))
                .isInstanceOf(StateConflictException.class);
        } finally {
            TenantContext.clear();
        }

        // Nothing changed: piece still packed
        String pieceStatus = jdbc.queryForObject(
            "SELECT status::text FROM pieces WHERE id = ?", String.class, PIECE_ID);
        assertThat(pieceStatus).isEqualTo("packed");

        // Allocation still packed (not released)
        String allocStatus = jdbc.queryForObject(
            "SELECT status::text FROM allocations WHERE id = ?", String.class, ALLOCATION_ID);
        assertThat(allocStatus).isEqualTo("packed");

        // Order still awaiting_pickup (not reset to new)
        String orderStatus = jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, ORDER_ID);
        assertThat(orderStatus).isEqualTo("awaiting_pickup");

        // No reversal event written
        Integer reversalEvents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'pick_error_reversed'",
            Integer.class, PIECE_ID);
        assertThat(reversalEvents).isZero();

        // No audit row written
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'pick_error_reversal'",
            Integer.class, TENANT_ID);
        assertThat(auditCount).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Seeds an order at {@code orderStatus}, a piece at {@code pieceActualStatus} with
     * current_order_id pointed at that order, a 'packed' allocation linking them (the
     * incident's lockstep break: allocation stays 'packed' regardless of how far the piece
     * itself has advanced), and three pre-existing piece_events rows (scan/pack/
     * tracking_linked) standing in for the real 2960/2961/2962.
     */
    private void seedIncident(PieceStatus pieceActualStatus, String orderStatus) {
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, " +
            "    payment_method, placed_at, not_traced_at) " +
            "VALUES (?, ?, ?, 'gid://shopify/Order/2212102474', '2212102474', " +
            "    ?::order_status, 'cod', now() - interval '10 days', now())",
            ORDER_ID, TENANT_ID, storeId, orderStatus);

        orderItemId = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, TENANT_ID, ORDER_ID, variantId);

        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, " +
            "    current_location_id, current_order_id, last_event_at, last_user_id) " +
            "VALUES (?, ?, ?, 'PC-' || ?, 'P001943', ?::piece_status, ?, ?, now(), ?)",
            PIECE_ID, TENANT_ID, variantId, PIECE_ID, pieceActualStatus.db,
            locationId, ORDER_ID, actorId);

        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status, " +
            "    allocated_by, allocated_at) " +
            "VALUES (?, ?, ?, ?, 'packed', ?, now())",
            ALLOCATION_ID, TENANT_ID, orderItemId, PIECE_ID, actorId);

        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, " +
            "    order_id, from_status, to_status) " +
            "VALUES (?, ?, 'scan', ?, ?, 'available'::piece_status, 'reserved'::piece_status)",
            TENANT_ID, PIECE_ID, actorId, ORDER_ID);
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, " +
            "    order_id, from_status, to_status) " +
            "VALUES (?, ?, 'pack', ?, ?, 'reserved'::piece_status, 'packed'::piece_status)",
            TENANT_ID, PIECE_ID, actorId, ORDER_ID);
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, " +
            "    order_id, from_status, to_status) " +
            "VALUES (?, ?, 'tracking_linked', ?, ?, 'packed'::piece_status, 'awaiting_pickup'::piece_status)",
            TENANT_ID, PIECE_ID, actorId, ORDER_ID);
    }

    private Integer countPieceEvents() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, PIECE_ID);
    }
}
