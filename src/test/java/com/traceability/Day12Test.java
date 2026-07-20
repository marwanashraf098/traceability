package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Day 12 integration tests: Returns intake + resolution (FR-12.1–12.5).
 *
 * (a) Intake scan: piece at return_in_transit → return_pending_inspection +
 *     return_received event with location_id + actor_user_id
 * (b) Unexpected return (FR-12.2): piece at with_courier, shipment not returning →
 *     intake still proceeds to return_pending_inspection; isUnexpected=true
 * (c) Restock (FR-12.3): return_pending_inspection → available, restocked event,
 *     current_location_id updated, current_order_id cleared
 * (d) Damage (FR-12.3): return_pending_inspection → damaged; reason mandatory (400 if absent)
 * (e) Never-received detector (FR-12.4): shipment returned_at 4 days ago; piece without
 *     return_received event appears in report; piece WITH event is excluded
 * (f) Continuous timeline (FR-12.5): shipped → returned → restocked all on lookup timeline
 * (g) Cross-tenant intake: different tenant context → 404 (tenant isolation enforced)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day12Test {

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

    @Autowired ReturnService  returnSvc;
    @Autowired LookupService  lookupSvc;
    @Autowired JdbcTemplate   jdbc;
    @MockBean  JobScheduler   jobScheduler;

    UUID tenantId, actorId, locationId, variantId, storeId;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'D12Tenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Nour', 'nour@d12.local', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')",
                    locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'd12.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-D12', 'Hoodie', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-D12', 'Red L', 'HOOD-RED-L')", variantId, tenantId, productId);
    }

    @BeforeEach void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── (a) Intake scan: expected path ────────────────────────────────────────

    @Test
    void a_intakeScan_returnsInTransitPiece_transitionsAndWritesEvent() {
        UUID orderId    = createOrder("returning");
        UUID shipmentId = createShipment(orderId, "AWB-INTAKE-A", "returning");
        String piece    = createPiece("return_in_transit", orderId);
        createAllocationPacked(orderId, piece);

        Map<String, Object> result = returnSvc.intakeScan("PC-" + piece, locationId, actorId);

        assertThat(result.get("isUnexpected")).isEqualTo(false);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");

        // return_received event written with correct location and actor
        Map<String, Object> event = jdbc.queryForMap(
            "SELECT * FROM piece_events WHERE piece_id = ? AND event_type = 'return_received'",
            piece);
        assertThat(event.get("location_id").toString()).isEqualTo(locationId.toString());
        assertThat(event.get("actor_user_id").toString()).isEqualTo(actorId.toString());
        assertThat(event.get("from_status").toString()).isEqualTo("return_in_transit");
        assertThat(event.get("to_status").toString()).isEqualTo("return_pending_inspection");

        // current_location_id updated on piece
        String locId = jdbc.queryForObject(
            "SELECT current_location_id::text FROM pieces WHERE id = ?", String.class, piece);
        assertThat(locId).isEqualTo(locationId.toString());
    }

    // ── (b) Unexpected return (FR-12.2) ──────────────────────────────────────

    @Test
    void b_unexpectedReturn_shipmentNotReturning_intakeProceedsWithFlag() {
        UUID orderId    = createOrder("with_courier");
        UUID shipmentId = createShipment(orderId, "AWB-UNEXPECTED", "with_courier");
        String piece    = createPiece("with_courier", orderId);
        createAllocationPacked(orderId, piece);

        Map<String, Object> result = returnSvc.intakeScan("PC-" + piece, locationId, actorId);

        // Intake proceeds despite unexpected state
        assertThat(result.get("isUnexpected")).isEqualTo(true);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");

        // Event still written
        int events = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'return_received'",
            Integer.class, piece);
        assertThat(events).isEqualTo(1);
    }

    // ── (c) Restock ───────────────────────────────────────────────────────────

    @Test
    void c_restock_returnsAvailableAndClearsOrderLink() {
        UUID orderId = createOrder("returning");
        String piece = createPiece("return_pending_inspection", orderId);

        returnSvc.restock(piece, locationId, actorId);

        assertThat(pieceStatus(piece)).isEqualTo("available");

        // restocked event written
        int events = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'restocked'",
            Integer.class, piece);
        assertThat(events).isEqualTo(1);

        // current_order_id cleared; current_location_id set to restock location
        Map<String, Object> p = jdbc.queryForMap(
            "SELECT current_order_id, current_location_id::text AS loc FROM pieces WHERE id = ?", piece);
        assertThat(p.get("current_order_id")).isNull();
        assertThat(p.get("loc")).isEqualTo(locationId.toString());
    }

    // ── (d) Damage — reason mandatory ─────────────────────────────────────────

    @Test
    void d_damage_terminalTransition_reasonMandatory() {
        String piece = createPiece("return_pending_inspection", null);

        // Null reason → 400
        ResponseStatusException ex = catchThrowableOfType(
            () -> returnSvc.markDamaged(piece, null, actorId),
            ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Valid reason → damaged (terminal)
        returnSvc.markDamaged(piece, "customer cut the label", actorId);
        assertThat(pieceStatus(piece)).isEqualTo("damaged");

        // damaged event has reason in metadata
        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events WHERE piece_id = ? AND event_type = 'damaged'",
            String.class, piece);
        assertThat(meta).contains("customer cut the label");
    }

    // ── (e) Never-received detector (FR-12.4) ─────────────────────────────────

    @Test
    void e_neverReceived_surfacesMissingPieces_excludesIntakedPieces() {
        UUID orderId    = createOrder("returned");
        UUID shipmentId = createShipment(orderId, "AWB-NR", "returned");

        // Simulate returned_at = 4 days ago (past the 3-day default window)
        jdbc.update(
            "UPDATE shipments SET returned_at = now() - interval '4 days' WHERE id = ?",
            shipmentId);

        // Piece A: never intaked (no return_received event)
        String pieceA = createPiece("return_pending_inspection", orderId);
        createAllocationPacked(orderId, pieceA);

        // Piece B: has been intaked (return_received event present) — must be excluded
        String pieceB = createPiece("return_pending_inspection", orderId);
        createAllocationPacked(orderId, pieceB);
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status) " +
            "VALUES (?, ?, 'return_received', 'return_pending_inspection'::piece_status, " +
            "        'return_pending_inspection'::piece_status)",
            tenantId, pieceB);

        List<Map<String, Object>> report = returnSvc.neverReceived(3);

        // Only piece A appears
        List<String> barcodes = report.stream()
            .map(r -> r.get("barcode").toString())
            .toList();
        assertThat(barcodes).contains("PC-" + pieceA);
        assertThat(barcodes).doesNotContain("PC-" + pieceB);
    }

    // ── (f) Continuous timeline (FR-12.5) ─────────────────────────────────────

    @Test
    void f_continuousTimeline_allReturnEventsVisibleOnLookup() {
        UUID orderId    = createOrder("returning");
        UUID shipmentId = createShipment(orderId, "AWB-TIMELINE", "returning");
        String piece    = createPiece("return_in_transit", orderId);
        createAllocationPacked(orderId, piece);

        // 1. Intake → return_pending_inspection
        returnSvc.intakeScan("PC-" + piece, locationId, actorId);
        // 2. Restock → available
        returnSvc.restock(piece, locationId, actorId);

        Map<String, Object> lookup = lookupSvc.lookupPiece("PC-" + piece, false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline =
                (List<Map<String, Object>>) lookup.get("timeline");

        List<String> phraseKeys = timeline.stream()
                .map(e -> (String) e.get("phraseKey"))
                .toList();

        // Timeline is newest-first: restocked, return_received
        assertThat(phraseKeys).contains("restocked", "return_received");
        // restocked must appear before return_received (newer first)
        assertThat(phraseKeys.indexOf("restocked"))
                .isLessThan(phraseKeys.indexOf("return_received"));
        assertThat((String) lookup.get("status")).isEqualTo("available");
    }

    // ── (g) Cross-tenant isolation ────────────────────────────────────────────

    @Test
    void g_crossTenantIntake_differentTenantContext_returnsNotFound() {
        UUID orderId = createOrder("returning");
        String piece = createPiece("return_in_transit", orderId);
        createAllocationPacked(orderId, piece);

        // Switch to a different tenant — piece should not be visible
        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'OtherD12')", otherTenant);
        TenantContext.set(otherTenant);
        try {
            ResponseStatusException ex = catchThrowableOfType(
                () -> returnSvc.intakeScan("PC-" + piece, locationId, actorId),
                ResponseStatusException.class);
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            TenantContext.set(tenantId);
            jdbc.update("DELETE FROM tenants WHERE id = ?", otherTenant);
        }
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private UUID createOrder(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, customer_phone, payment_method, placed_at) " +
            "VALUES (?, ?, ?, 'ORD-D12', ?::order_status, 'Buyer', '01000000001', 'cod', now()) " +
            "RETURNING id",
            UUID.class, tenantId, storeId, "EXT-D12-" + UUID.randomUUID(), status);
    }

    private UUID createShipment(UUID orderId, String trackingNumber, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (?, ?, ?, ?, ?::shipment_internal_state)",
            id, tenantId, orderId, trackingNumber, state);
        return id;
    }

    private String createPiece(String status, UUID orderId) {
        String id = com.traceability.inventory.UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
            id, tenantId, variantId, "PC-" + id, id, status, orderId);
        return id;
    }

    private void createAllocationPacked(UUID orderId, String pieceId) {
        UUID itemId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, ?, 1)", itemId, tenantId, orderId, variantId);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'packed')",
            tenantId, itemId, pieceId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }
}
