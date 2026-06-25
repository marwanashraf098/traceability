package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
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
import org.jobrunr.scheduling.JobScheduler;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the returns-receiving session (FR-12 extension).
 *
 * (a) RTO path: return_in_transit verdict restock → available; return_kind=rto in event.
 * (b) Customer-return path: delivered piece inside window → restock → available;
 *     return_kind=customer_after_delivery in event.
 * (c) Return-window guard: delivered piece older than window → 422 rejected.
 * (d) Damaged verdict → piece at damaged; reprint writes label_reprinted event with actor.
 * (e) Reprint rejected on available piece not in a return flow (Change 3).
 * (f) Un-scanned delivered piece NOT counted in unresolvedRtoCount (Change 2).
 * (g) Un-scanned return_in_transit piece IS counted in unresolvedRtoCount.
 * (h) Session open on invalid shipment state (e.g. with_courier) → 422.
 * (i) detectReturnInTransitStuck fires after N days; not before.
 * (j) detectReturnInTransitStuck suppressed when piece has return_received event.
 * (k) Tenant isolation: piece from another tenant is not accessible.
 * (l) finalize with unresolved return_in_transit pieces does not block.
 * (m) Dismiss 2 days ago → still suppressed (within 7-day snooze window).
 * (n) Dismiss 8 days ago, piece still stuck → re-fires (snooze expired).
 * (o) Dismissed then processed (return_received + status moved) → never re-fires regardless of dismissal age.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnSessionTest {

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

    @Autowired ReturnSessionService sessionSvc;
    @Autowired ExceptionService     exceptionSvc;
    @Autowired InventoryLedger      ledger;
    @Autowired JdbcTemplate         jdbc;
    @MockBean  JobScheduler         jobScheduler;

    UUID tenantId, actorId, locationId, variantId, storeId;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'RST-Tenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Worker', 'w@rst.test', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')",
                    locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'rst.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-RST', 'Jacket', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-RST', 'Blue M', 'JACK-BLUE-M')", variantId, tenantId, productId);
    }

    @BeforeEach void setCtx()   { TenantContext.set(tenantId); }
    @AfterEach  void clearCtx() {
        TenantContext.clear();
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM receipts WHERE tenant_id = ? AND kind = 'returns'", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL, status = 'available'::piece_status " +
                    "WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── (a) RTO path ──────────────────────────────────────────────────────────

    @Test
    void a_rto_verdict_restock_transitions_and_writes_return_kind_rto() {
        UUID orderId    = createOrder("returning");
        UUID shipmentId = createShipment(orderId, "AWB-RTO-A", "returning");
        String piece    = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);

        Map<String, Object> session = sessionSvc.createSession("AWB-RTO-A", locationId, null, actorId);
        UUID sessionId = (UUID) session.get("sessionId");

        Map<String, Object> result = sessionSvc.recordVerdict(sessionId, piece,
                "restock", null, locationId, actorId);

        assertThat(result.get("finalStatus")).isEqualTo("available");
        assertThat(result.get("returnKind")).isEqualTo("rto");
        assertThat(pieceStatus(piece)).isEqualTo("available");

        // return_received event has return_kind=rto + session_id in metadata
        // PostgreSQL jsonb normalizes to spaced format: "key": "value"
        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events " +
            "WHERE piece_id = ? AND event_type = 'return_received'",
            String.class, piece);
        assertThat(meta).contains("return_kind");
        assertThat(meta).contains("rto");
        assertThat(meta).contains(sessionId.toString());

        // restocked event also written (from returnService.restock inside same tx)
        int restockedEvents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'restocked'",
            Integer.class, piece);
        assertThat(restockedEvents).isEqualTo(1);
    }

    // ── (b) Customer-return inside window ─────────────────────────────────────

    @Test
    void b_delivered_inside_window_restock_writes_customer_after_delivery_kind() {
        UUID orderId = createOrder("delivered");
        createShipment(orderId, "AWB-CUST-B", "delivered");
        String piece = createPiece("delivered", orderId);
        createAlloc(orderId, piece);
        // last_event_at defaults to now() — well inside the 30-day window

        Map<String, Object> session = sessionSvc.createSession("AWB-CUST-B", locationId, null, actorId);
        UUID sessionId = (UUID) session.get("sessionId");

        Map<String, Object> result = sessionSvc.recordVerdict(sessionId, piece,
                "restock", null, locationId, actorId);

        assertThat(result.get("finalStatus")).isEqualTo("available");
        assertThat(result.get("returnKind")).isEqualTo("customer_after_delivery");
        assertThat(pieceStatus(piece)).isEqualTo("available");

        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events " +
            "WHERE piece_id = ? AND event_type = 'return_received'",
            String.class, piece);
        assertThat(meta).contains("return_kind");
        assertThat(meta).contains("customer_after_delivery");
        assertThat(meta).contains(sessionId.toString());
    }

    // ── (c) Return-window guard — hard reject ─────────────────────────────────

    @Test
    void c_delivered_outside_window_rejects_with_422() {
        UUID orderId = createOrder("delivered");
        createShipment(orderId, "AWB-CUST-C", "delivered");
        String piece = createPiece("delivered", orderId);
        createAlloc(orderId, piece);
        // Push last_event_at back 45 days — outside the 30-day default window
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '45 days' WHERE id = ?", piece);

        Map<String, Object> session = sessionSvc.createSession("AWB-CUST-C", locationId, null, actorId);
        UUID sessionId = (UUID) session.get("sessionId");

        ResponseStatusException ex = catchThrowableOfType(
            () -> sessionSvc.recordVerdict(sessionId, piece, "restock", null, locationId, actorId),
            ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getReason()).contains("beyond the customer return window");
        assertThat(ex.getReason()).contains("waybill-less intake");
        // Piece must NOT have moved
        assertThat(pieceStatus(piece)).isEqualTo("delivered");
    }

    // ── (d) Damaged verdict + reprint writes label_reprinted event ────────────

    @Test
    void d_damaged_verdict_and_reprint_writes_event_with_actor() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-DMG-D", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);

        Map<String, Object> session = sessionSvc.createSession("AWB-DMG-D", locationId, null, actorId);
        UUID sessionId = (UUID) session.get("sessionId");

        sessionSvc.recordVerdict(sessionId, piece, "damaged", "scratched lens", locationId, actorId);
        assertThat(pieceStatus(piece)).isEqualTo("damaged");

        // Reprint: piece is now in 'damaged' — valid for reprint (Change 3).
        Map<String, Object> reprintResult = sessionSvc.validateAndRecordReprint(piece, actorId);
        assertThat(reprintResult.get("barcode")).isEqualTo("PC-" + piece);

        // label_reprinted event written with correct actor
        Map<String, Object> event = jdbc.queryForMap(
            "SELECT * FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'",
            piece);
        assertThat(event.get("actor_user_id").toString()).isEqualTo(actorId.toString());
        assertThat(event.get("from_status").toString()).isEqualTo("damaged");
        assertThat(event.get("to_status").toString()).isEqualTo("damaged"); // no status change
    }

    // ── (e) Reprint rejected on piece not in a return flow ────────────────────

    @Test
    void e_reprint_rejected_on_available_piece_not_in_return_flow() {
        String piece = createPiece("available", null);

        ResponseStatusException ex = catchThrowableOfType(
            () -> sessionSvc.validateAndRecordReprint(piece, actorId),
            ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getReason()).contains("return_pending_inspection or damaged");

        // Also reject delivered (another common "not in return flow" status)
        UUID orderId = createOrder("delivered");
        createShipment(orderId, "AWB-E2", "delivered");
        String deliveredPiece = createPiece("delivered", orderId);
        createAlloc(orderId, deliveredPiece);

        ResponseStatusException ex2 = catchThrowableOfType(
            () -> sessionSvc.validateAndRecordReprint(deliveredPiece, actorId),
            ResponseStatusException.class);
        assertThat(ex2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── (f) Un-scanned delivered piece NOT in unresolvedRtoCount ─────────────

    @Test
    void f_unscanned_delivered_piece_not_counted_as_unresolved() {
        UUID orderId = createOrder("delivered");
        createShipment(orderId, "AWB-KEPT-F", "delivered");
        String deliveredPiece = createPiece("delivered", orderId);
        createAlloc(orderId, deliveredPiece);

        Map<String, Object> session = sessionSvc.createSession("AWB-KEPT-F", locationId, null, actorId);
        UUID sessionId = (UUID) session.get("sessionId");

        // Finalize WITHOUT scanning the delivered piece — customer kept it
        Map<String, Object> summary = sessionSvc.finalizeSession(sessionId, actorId);

        assertThat(((Number) summary.get("unresolvedRtoCount")).intValue()).isEqualTo(0);
        assertThat(((Number) summary.get("deliveredKeptCount")).intValue()).isEqualTo(1);
        assertThat(((Number) summary.get("processedCount")).intValue()).isEqualTo(0);
    }

    // ── (g) Un-scanned return_in_transit IS counted as unresolved ─────────────

    @Test
    void g_unscanned_rto_piece_counted_in_unresolvedRtoCount() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-STUCK-G", "returning");
        String rtoPiece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, rtoPiece);

        Map<String, Object> session = sessionSvc.createSession("AWB-STUCK-G", locationId, null, actorId);
        UUID sessionId = (UUID) session.get("sessionId");

        // Finalize WITHOUT scanning the RTO piece
        Map<String, Object> summary = sessionSvc.finalizeSession(sessionId, actorId);

        assertThat(((Number) summary.get("unresolvedRtoCount")).intValue()).isEqualTo(1);
        assertThat(((Number) summary.get("deliveredKeptCount")).intValue()).isEqualTo(0);
    }

    // ── (h) Session open on invalid shipment state ────────────────────────────

    @Test
    void h_session_open_on_with_courier_shipment_rejected() {
        UUID orderId = createOrder("with_courier");
        createShipment(orderId, "AWB-INFLIGHT-H", "with_courier");

        ResponseStatusException ex = catchThrowableOfType(
            () -> sessionSvc.createSession("AWB-INFLIGHT-H", locationId, null, actorId),
            ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getReason()).contains("with_courier");
    }

    // ── (i) detectReturnInTransitStuck fires after N days ─────────────────────

    @Test
    void i_detector_fires_after_threshold_days_not_before() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-STUCKDET-I", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);

        // Within threshold (1 day ago — default threshold is 3 days): should NOT fire.
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '1 day' WHERE id = ?", piece);

        List<Map<String, Object>> exceptions = listExceptionsOfType("return_in_transit_stuck");
        assertThat(exceptions).isEmpty();

        // Beyond threshold (4 days ago): should fire.
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '4 days' WHERE id = ?", piece);

        List<Map<String, Object>> excAfter = listExceptionsOfType("return_in_transit_stuck");
        assertThat(excAfter).hasSize(1);
        assertThat(excAfter.get(0).get("barcode")).isEqualTo("PC-" + piece);
        assertThat(excAfter.get(0).get("severity")).isEqualTo("HIGH");
    }

    // ── (j) Detector suppressed when piece has return_received event ───────────

    @Test
    void j_detector_suppressed_when_piece_has_return_received_event() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-RCVD-J", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '10 days' WHERE id = ?", piece);

        // Simulate a return_received event — the detector must not fire.
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status) " +
            "VALUES (?, ?, 'return_received', " +
            "    'return_in_transit'::piece_status, 'return_pending_inspection'::piece_status)",
            tenantId, piece);

        List<Map<String, Object>> exceptions = listExceptionsOfType("return_in_transit_stuck");
        assertThat(exceptions).isEmpty();
    }

    // ── (k) Tenant isolation ──────────────────────────────────────────────────

    @Test
    void k_tenant_isolation_piece_not_visible_under_different_tenant() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-ISO-K", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);

        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'OtherRST')", otherTenant);

        TenantContext.set(otherTenant);
        try {
            // createSession under other tenant — waybill not visible
            ResponseStatusException ex = catchThrowableOfType(
                () -> sessionSvc.createSession("AWB-ISO-K", locationId, null, actorId),
                ResponseStatusException.class);
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            TenantContext.set(tenantId);
            jdbc.update("DELETE FROM tenants WHERE id = ?", otherTenant);
        }
    }

    // ── (l) Finalize with unresolved return_in_transit does not block ──────────

    @Test
    void l_finalize_with_unresolved_rto_pieces_does_not_block() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-PARTIAL-L", "returning");
        String rtoPiece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, rtoPiece);

        Map<String, Object> session = sessionSvc.createSession("AWB-PARTIAL-L", locationId, null, actorId);
        UUID sessionId = (UUID) session.get("sessionId");

        // Finalize without scanning any pieces — must not throw
        assertThatCode(() -> sessionSvc.finalizeSession(sessionId, actorId))
            .doesNotThrowAnyException();

        String status = jdbc.queryForObject(
            "SELECT status FROM receipts WHERE id = ? AND tenant_id = ?",
            String.class, sessionId, tenantId);
        assertThat(status).isEqualTo("finalized");
    }

    // ── (m) Dismiss 2 days ago — inside 7-day snooze, not re-fired ──────────────

    @Test
    void m_dismiss_recent_snoozes_still_suppressed() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-SNOOZE-M", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '10 days' WHERE id = ?", piece);

        // Operator dismissed 2 days ago — within the 7-day snooze window.
        jdbc.update(
            "INSERT INTO exception_resolutions " +
            "(tenant_id, exception_type, subject_key, resolved_by, resolved_at) " +
            "VALUES (?, 'return_in_transit_stuck', " +
            "    'return_in_transit_stuck:piece:' || ?, ?, now() - interval '2 days')",
            tenantId, piece, actorId);

        List<Map<String, Object>> exceptions = listExceptionsOfType("return_in_transit_stuck");
        boolean myPiecePresent = exceptions.stream()
            .anyMatch(e -> ("PC-" + piece).equals(e.get("barcode")));
        assertThat(myPiecePresent).isFalse();
    }

    // ── (n) Dismiss 8 days ago — snooze expired, piece STILL stuck → re-fires ──

    @Test
    void n_dismiss_expired_stuck_piece_re_fires() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-RESNOOZE-N", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '10 days' WHERE id = ?", piece);

        // Operator dismissed 8 days ago — beyond the 7-day snooze.
        jdbc.update(
            "INSERT INTO exception_resolutions " +
            "(tenant_id, exception_type, subject_key, resolved_by, resolved_at) " +
            "VALUES (?, 'return_in_transit_stuck', " +
            "    'return_in_transit_stuck:piece:' || ?, ?, now() - interval '8 days')",
            tenantId, piece, actorId);

        List<Map<String, Object>> exceptions = listExceptionsOfType("return_in_transit_stuck");
        boolean myPiecePresent = exceptions.stream()
            .anyMatch(e -> ("PC-" + piece).equals(e.get("barcode")));
        assertThat(myPiecePresent).isTrue();
    }

    // ── (o) Dismissed + processed → never re-fires regardless of dismissal age ─

    @Test
    void o_dismissed_then_processed_never_re_fires() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "AWB-DONE-O", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '10 days' WHERE id = ?", piece);

        // Dismissed 8 days ago — snooze expired, would re-fire for a still-stuck piece.
        jdbc.update(
            "INSERT INTO exception_resolutions " +
            "(tenant_id, exception_type, subject_key, resolved_by, resolved_at) " +
            "VALUES (?, 'return_in_transit_stuck', " +
            "    'return_in_transit_stuck:piece:' || ?, ?, now() - interval '8 days')",
            tenantId, piece, actorId);

        // Piece was actually processed: return_received event written + status advanced.
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status) " +
            "VALUES (?, ?, 'return_received', " +
            "    'return_in_transit'::piece_status, 'return_pending_inspection'::piece_status)",
            tenantId, piece);
        jdbc.update(
            "UPDATE pieces SET status = 'return_pending_inspection'::piece_status WHERE id = ?", piece);

        List<Map<String, Object>> exceptions = listExceptionsOfType("return_in_transit_stuck");
        boolean myPiecePresent = exceptions.stream()
            .anyMatch(e -> ("PC-" + piece).equals(e.get("barcode")));
        assertThat(myPiecePresent).isFalse();
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private UUID createOrder(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, " +
            "    customer_name, customer_phone, payment_method, placed_at) " +
            "VALUES (?, ?, ?, 'ORD-RST', ?::order_status, 'Buyer', '01000000002', 'cod', now()) " +
            "RETURNING id",
            UUID.class, tenantId, storeId, "EXT-RST-" + UUID.randomUUID(), status);
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
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces " +
            "(id, tenant_id, variant_id, barcode, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, ?::piece_status, ?, now())",
            id, tenantId, variantId, "PC-" + id, status, orderId);
        return id;
    }

    private void createAlloc(UUID orderId, String pieceId) {
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

    private List<Map<String, Object>> listExceptionsOfType(String type) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
            (List<Map<String, Object>>) exceptionSvc.listExceptions(type, null, 0, 100).get("items");
        return items;
    }
}
