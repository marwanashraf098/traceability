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
 * Integration tests for ReturnSessionService.
 *
 * FR-24 rewrite (2026-08-14): cases (a)-(e) rewritten against the new session-based
 * scan()/disposition() contract (old waybill-first createSession()/recordVerdict()/
 * finalizeSession()/getSessionPieces() are gone — superseded by the new tables).
 * (d)/(e) still exercise validateAndRecordReprint() — that method is UNTOUCHED
 * (see its javadoc), so those two assertions carry over unchanged in spirit.
 * Cases (f)/(g)/(h)/(k)/(l)/(p)/(q)/(r)/(s) from the old waybill-first model had no
 * equivalent concept in the new one (single-shipment session open, waybill-state
 * validation, unresolvedRtoCount/deliveredKeptCount, non-blocking finalize,
 * actionable-first piece ordering) and are retired — their spirit (tenant isolation,
 * AWB hub-prefix normalization, foreign/unreadable scan rejection, close-blocking,
 * one-open-session) is covered by ReturnSessionRebuildTest instead.
 *
 * (a) Legal scan (return_in_transit) + restock disposition → available; return_kind=rto
 *     in the return_received event metadata.
 * (b) Legal scan (delivered, inside window) + restock → available;
 *     return_kind=customer_after_delivery in event metadata.
 * (c) Delivered outside window → illegal-state fork (unexpected=true, no transition);
 *     restock is then rejected 409 by ReturnService.restock()'s own guard.
 * (d) Damaged disposition → piece at damaged; validateAndRecordReprint() (untouched old
 *     gate) still accepts a damaged piece and writes label_reprinted with actor.
 * (e) validateAndRecordReprint() still rejects an available piece / a delivered piece
 *     not in a return flow (Change 3, untouched).
 * (i) detectReturnInTransitStuck fires after N days; not before.
 * (j) detectReturnInTransitStuck suppressed when piece has return_received event.
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
    @Autowired ReturnService        returnSvc;
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
        jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL, status = 'available'::piece_status " +
                    "WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    private UUID openSession() {
        return sessionSvc.createSession(null, actorId);
    }

    // ── (a) Legal scan (RTO) + restock ────────────────────────────────────────

    @Test
    void a_rto_scan_restock_transitions_and_writes_return_kind_rto() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "9100001", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);

        UUID sessionId = openSession();
        Map<String, Object> scanResult = sessionSvc.scan(sessionId, "PC-" + piece, locationId, actorId);
        assertThat(scanResult.get("disposition")).isEqualTo("pending");
        assertThat(scanResult.get("unexpected")).isEqualTo(false);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");

        Map<String, Object> result = sessionSvc.disposition(
                sessionId, piece, "restock", null, locationId, actorId);

        assertThat(result.get("disposition")).isEqualTo("restocked");
        assertThat(pieceStatus(piece)).isEqualTo("available");

        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events " +
            "WHERE piece_id = ? AND event_type = 'return_received'",
            String.class, piece);
        assertThat(meta).contains("return_kind");
        assertThat(meta).contains("rto");
        assertThat(meta).contains(sessionId.toString());

        int restockedEvents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'restocked'",
            Integer.class, piece);
        assertThat(restockedEvents).isEqualTo(1);
    }

    // ── (b) Customer-return inside window ─────────────────────────────────────

    @Test
    void b_delivered_inside_window_scan_restock_writes_customer_after_delivery_kind() {
        UUID orderId = createOrder("delivered");
        createShipment(orderId, "9100002", "delivered");
        String piece = createPiece("delivered", orderId);
        createAlloc(orderId, piece);
        // last_event_at defaults to now() — well inside the 30-day window

        UUID sessionId = openSession();
        Map<String, Object> scanResult = sessionSvc.scan(sessionId, "PC-" + piece, locationId, actorId);
        assertThat(scanResult.get("unexpected")).isEqualTo(false);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");

        sessionSvc.disposition(sessionId, piece, "restock", null, locationId, actorId);
        assertThat(pieceStatus(piece)).isEqualTo("available");

        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events " +
            "WHERE piece_id = ? AND event_type = 'return_received'",
            String.class, piece);
        assertThat(meta).contains("return_kind");
        assertThat(meta).contains("customer_after_delivery");
        assertThat(meta).contains(sessionId.toString());
    }

    // ── (c) Return-window guard — illegal-state fork, restock rejected ────────

    @Test
    void c_delivered_outside_window_isIllegalState_restockRejected() {
        UUID orderId = createOrder("delivered");
        createShipment(orderId, "9100003", "delivered");
        String piece = createPiece("delivered", orderId);
        createAlloc(orderId, piece);
        // Push last_event_at back 45 days — outside the 30-day default window
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '45 days' WHERE id = ?", piece);

        UUID sessionId = openSession();
        Map<String, Object> scanResult = sessionSvc.scan(sessionId, "PC-" + piece, locationId, actorId);

        // Illegal-state fork: item created, flagged unexpected, but the piece never
        // transitioned — it's still 'delivered', not 'return_pending_inspection'.
        assertThat(scanResult.get("unexpected")).isEqualTo(true);
        assertThat(scanResult.get("disposition")).isEqualTo("pending");
        assertThat(pieceStatus(piece)).isEqualTo("delivered");

        // restock()'s own return_pending_inspection guard rejects it — free enforcement,
        // no bespoke illegal-state check needed in disposition().
        ResponseStatusException ex = catchThrowableOfType(
            () -> sessionSvc.disposition(sessionId, piece, "restock", null, locationId, actorId),
            ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pieceStatus(piece)).isEqualTo("delivered");

        // mismatch is always available as the release valve.
        Map<String, Object> mismatchResult = sessionSvc.disposition(
                sessionId, piece, "mismatch", null, locationId, actorId);
        assertThat(mismatchResult.get("disposition")).isEqualTo("mismatch");
        assertThat(pieceStatus(piece)).isEqualTo("delivered");
    }

    // ── (d) Damaged disposition + old gated reprint still works ──────────────

    @Test
    void d_damaged_disposition_and_untouchedGatedReprint_writesEventWithActor() {
        UUID orderId = createOrder("returning");
        createShipment(orderId, "9100004", "returning");
        String piece = createPiece("return_in_transit", orderId);
        createAlloc(orderId, piece);

        UUID sessionId = openSession();
        sessionSvc.scan(sessionId, "PC-" + piece, locationId, actorId);
        sessionSvc.disposition(sessionId, piece, "damaged", "scratched lens", locationId, actorId);
        assertThat(pieceStatus(piece)).isEqualTo("damaged");

        // Old gated reprint (untouched, FR-12 change 3): damaged qualifies.
        Map<String, Object> reprintResult = sessionSvc.validateAndRecordReprint(piece, actorId);
        assertThat(reprintResult.get("barcode")).isEqualTo("PC-" + piece);

        Map<String, Object> event = jdbc.queryForMap(
            "SELECT * FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'",
            piece);
        assertThat(event.get("actor_user_id").toString()).isEqualTo(actorId.toString());
        assertThat(event.get("from_status").toString()).isEqualTo("damaged");
        assertThat(event.get("to_status").toString()).isEqualTo("damaged");
    }

    // ── (e) Old gated reprint still rejects pieces not in a return flow ──────

    @Test
    void e_untouchedGatedReprint_rejectedOnAvailablePieceNotInReturnFlow() {
        String piece = createPiece("available", null);

        ResponseStatusException ex = catchThrowableOfType(
            () -> sessionSvc.validateAndRecordReprint(piece, actorId),
            ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.getReason()).contains("return_pending_inspection or damaged");

        UUID orderId = createOrder("delivered");
        createShipment(orderId, "AWB-E2", "delivered");
        String deliveredPiece = createPiece("delivered", orderId);
        createAlloc(orderId, deliveredPiece);

        ResponseStatusException ex2 = catchThrowableOfType(
            () -> sessionSvc.validateAndRecordReprint(deliveredPiece, actorId),
            ResponseStatusException.class);
        assertThat(ex2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
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
            "(id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, now())",
            id, tenantId, variantId, "PC-" + id, id, status, orderId);
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
