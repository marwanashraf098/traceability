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
 * (p) Matched active CRP return leg (shipments row, shipment_leg='return', non-terminal state)
 *     → scan() reports unexpected=false AND no HIGH unexpected_return exception — the two
 *     classification sites (ReturnSessionService.scanPiece(), ExceptionService.
 *     detectUnexpectedReturn()) now share ShipmentLinkService.hasActiveReturnLeg().
 * (q) Negative control, same tenant, no return-leg row → scan() still reports
 *     unexpected=true AND the HIGH exception still fires — proves the predicate
 *     discriminates rather than globally suppressing unexpected_return.
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
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", tenantId);
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

    // ── (p) Matched active CRP return leg suppresses unexpected — positive ────

    /**
     * Reproduces the Jumi ~75-CRP false-positive: the piece is still WITH_COURIER
     * (Bosta hasn't caught the piece's own status up yet) but the order already has a
     * matched shipments row, shipment_leg='return', in a non-terminal state — this is
     * an EXPECTED CRP return, not an anomaly.
     */
    @Test
    void p_activeCrpReturnLeg_scanAndException_bothSuppressUnexpected() {
        UUID orderId = createOrder("with_courier");
        createShipment(orderId, "AWB-CRP-P-FWD", "delivered");
        createReturnLegShipment(orderId, "AWB-CRP-P-RET", "delivered");
        String piece = createPiece("with_courier", orderId);
        createAlloc(orderId, piece);

        UUID sessionId = openSession();
        Map<String, Object> scanResult = sessionSvc.scan(sessionId, "PC-" + piece, locationId, actorId);

        assertThat(scanResult.get("unexpected"))
            .as("matched active CRP return leg → scan must not be flagged unexpected")
            .isEqualTo(false);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");

        boolean present = listExceptionsOfType("unexpected_return").stream()
            .anyMatch(e -> ("PC-" + piece).equals(e.get("barcode")));
        assertThat(present)
            .as("matched active CRP return leg → no HIGH unexpected_return exception")
            .isFalse();
    }

    // ── (q) Negative control, same tenant, no return leg — unexpected still flagged ──

    @Test
    void q_noReturnLeg_sameTenant_unexpectedStillFlagged_exceptionStillFires() {
        UUID orderId = createOrder("with_courier");
        createShipment(orderId, "AWB-CRP-Q-FWD", "with_courier");
        String piece = createPiece("with_courier", orderId);
        createAlloc(orderId, piece);

        UUID sessionId = openSession();
        Map<String, Object> scanResult = sessionSvc.scan(sessionId, "PC-" + piece, locationId, actorId);

        assertThat(scanResult.get("unexpected"))
            .as("no return leg in flight → genuine unexpected return must still be flagged")
            .isEqualTo(true);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");

        boolean present = listExceptionsOfType("unexpected_return").stream()
            .anyMatch(e -> ("PC-" + piece).equals(e.get("barcode")));
        assertThat(present)
            .as("no return leg in flight → HIGH unexpected_return exception must still fire")
            .isTrue();
    }

    // ── (r) Return leg resolves to terminal once fully dispositioned ─────────────

    /**
     * Step 2 Part C's "loose thread" from Step 1: without resolving the return-leg
     * shipment to a terminal state once its piece is dispositioned, hasActiveReturnLeg()
     * would suppress unexpected_return FOREVER for this order — including a completely
     * unrelated LATER genuine unexpected return. restock()/markDamaged() now call
     * ShipmentLinkService.resolveReturnLegIfComplete() to close the leg out once no piece
     * remains at return_pending_inspection for the order.
     */
    @Test
    void r_returnLegResolvedAfterDisposition_laterUnrelatedReturnStillFlagged() {
        UUID orderId = createOrder("with_courier");
        createShipment(orderId, "AWB-CRP-R-FWD", "delivered");
        UUID returnLegId = createReturnLegShipment(orderId, "AWB-CRP-R-RET", "delivered");
        String piece1 = createPiece("with_courier", orderId);
        createAlloc(orderId, piece1);

        UUID sessionId = openSession();
        Map<String, Object> scan1 = sessionSvc.scan(sessionId, "PC-" + piece1, locationId, actorId);
        assertThat(scan1.get("unexpected")).isEqualTo(false);

        // Disposition the only piece from this return leg.
        sessionSvc.disposition(sessionId, piece1, "restock", null, locationId, actorId);

        String returnLegState = jdbc.queryForObject(
            "SELECT internal_state::text FROM shipments WHERE id = ?", String.class, returnLegId);
        assertThat(returnLegState)
            .as("return leg must resolve to a terminal state once its piece is dispositioned")
            .isEqualTo("returned");

        // A completely unrelated, later, genuine unexpected return on the SAME order.
        String piece2 = createPiece("with_courier", orderId);
        createAlloc(orderId, piece2);
        Map<String, Object> scan2 = sessionSvc.scan(sessionId, "PC-" + piece2, locationId, actorId);

        assertThat(scan2.get("unexpected"))
            .as("resolved return leg must not suppress a later unrelated genuine unexpected return")
            .isEqualTo(true);

        boolean present = listExceptionsOfType("unexpected_return").stream()
            .anyMatch(e -> ("PC-" + piece2).equals(e.get("barcode")));
        assertThat(present)
            .as("HIGH exception must fire for the later unrelated unexpected return")
            .isTrue();
    }

    // ── (s) Matched exchange suppresses unexpected — positive (Step 3 Part C) ────

    /**
     * Step 3 Part C: hasActiveReturnLeg() now also returns true when the order has an
     * exchange with matched_order_id=order AND status='matched' — proven here through
     * the SAME two existing call sites (scanPiece()'s WITH_COURIER branch,
     * detectUnexpectedReturn()), no third classifier. No CRP return-leg shipment exists
     * for this order at all — the exchange match is the only signal.
     */
    @Test
    void s_matchedExchange_scanAndException_bothSuppressUnexpected() {
        UUID orderId = createOrder("with_courier");
        createShipment(orderId, "AWB-EXC-S-FWD", "with_courier");
        createExchange("EXC-S-TRACK", "matched", orderId);
        String piece = createPiece("with_courier", orderId);
        createAlloc(orderId, piece);

        UUID sessionId = openSession();
        Map<String, Object> scanResult = sessionSvc.scan(sessionId, "PC-" + piece, locationId, actorId);

        assertThat(scanResult.get("unexpected"))
            .as("matched exchange → scan must not be flagged unexpected")
            .isEqualTo(false);

        boolean present = listExceptionsOfType("unexpected_return").stream()
            .anyMatch(e -> ("PC-" + piece).equals(e.get("barcode")));
        assertThat(present)
            .as("matched exchange → no HIGH unexpected_return exception")
            .isFalse();
    }

    // ── (t) Unmatched-exchange order, same tenant — unexpected still flagged ─────

    /**
     * Negative control, same tenant as (s): an exchange EXISTS for this order but its
     * status is 'unmatched' (no matched_order_id) — must NOT suppress. Proves the
     * predicate discriminates on status, not merely "an exchanges row exists".
     */
    @Test
    void t_unmatchedExchange_sameTenant_unexpectedStillFlagged_exceptionStillFires() {
        UUID orderId = createOrder("with_courier");
        createShipment(orderId, "AWB-EXC-T-FWD", "with_courier");
        createExchangeUnmatched("EXC-T-TRACK");
        String piece = createPiece("with_courier", orderId);
        createAlloc(orderId, piece);

        UUID sessionId = openSession();
        Map<String, Object> scanResult = sessionSvc.scan(sessionId, "PC-" + piece, locationId, actorId);

        assertThat(scanResult.get("unexpected"))
            .as("unmatched exchange (no link to THIS order) → must still be flagged unexpected")
            .isEqualTo(true);

        boolean present = listExceptionsOfType("unexpected_return").stream()
            .anyMatch(e -> ("PC-" + piece).equals(e.get("barcode")));
        assertThat(present)
            .as("unmatched exchange → HIGH exception must still fire")
            .isTrue();
    }

    // ── (u) Matched exchange resolves to return_received once dispositioned ──────

    @Test
    void u_matchedExchange_resolvedAfterDisposition_laterUnrelatedReturnStillFlagged() {
        UUID orderId = createOrder("with_courier");
        createShipment(orderId, "AWB-EXC-U-FWD", "with_courier");
        UUID exchangeId = createExchange("EXC-U-TRACK", "matched", orderId);
        String piece1 = createPiece("with_courier", orderId);
        createAlloc(orderId, piece1);

        UUID sessionId = openSession();
        Map<String, Object> scan1 = sessionSvc.scan(sessionId, "PC-" + piece1, locationId, actorId);
        assertThat(scan1.get("unexpected")).isEqualTo(false);

        sessionSvc.disposition(sessionId, piece1, "restock", null, locationId, actorId);

        String exchangeStatus = jdbc.queryForObject(
            "SELECT status FROM exchanges WHERE id = ?", String.class, exchangeId);
        assertThat(exchangeStatus)
            .as("matched exchange must resolve to return_received once its piece is dispositioned")
            .isEqualTo("return_received");

        String piece2 = createPiece("with_courier", orderId);
        createAlloc(orderId, piece2);
        Map<String, Object> scan2 = sessionSvc.scan(sessionId, "PC-" + piece2, locationId, actorId);

        assertThat(scan2.get("unexpected"))
            .as("resolved exchange must not suppress a later unrelated genuine unexpected return")
            .isEqualTo(true);
    }

    // ── (v) Step 3C: out-of-window matched-exchange scan accepted, bypass closes ─

    /**
     * Step 3C Test 1. The old item is DELIVERED and OUTSIDE the customer return window
     * (last_event_at pushed back 45 days, default window is 30) — before this build such
     * a scan hits the illegal-state fork (legal=false, unexpected=true, no transition).
     * A matched exchange for this order now bypasses the window check: scan is accepted,
     * legal, unexpected=false, return_kind='exchange_match' in the event metadata. Once
     * the piece is dispositioned, the exchange resolves to return_received (Step 3
     * Part C's resolution flip) and the bypass closes — proven by scanning a SECOND
     * out-of-window delivered piece on the SAME order afterward, which must now be
     * correctly rejected again.
     */
    @Test
    void v_matchedExchange_outOfWindowScanAccepted_bypassClosesAfterResolution() {
        UUID orderId = createOrder("delivered");
        createShipment(orderId, "AWB-EXC-V-FWD", "delivered");
        UUID exchangeId = createExchange("EXC-V-TRACK", "matched", orderId);

        String piece1 = createPiece("delivered", orderId);
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '45 days' WHERE id = ?", piece1);
        createAlloc(orderId, piece1);

        UUID sessionId = openSession();
        Map<String, Object> scan1 = sessionSvc.scan(sessionId, "PC-" + piece1, locationId, actorId);

        assertThat(scan1.get("unexpected"))
            .as("matched exchange must bypass the out-of-window rejection")
            .isEqualTo(false);
        assertThat(pieceStatus(piece1)).isEqualTo("return_pending_inspection");

        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events " +
            "WHERE piece_id = ? AND event_type = 'return_received'",
            String.class, piece1);
        assertThat(meta)
            .as("out-of-window acceptance via the exchange bypass must be labeled exchange_match")
            .contains("return_kind")
            .contains("exchange_match");

        // Resolve it — Step 3 Part C's resolution flip must fire.
        sessionSvc.disposition(sessionId, piece1, "restock", null, locationId, actorId);
        String exchangeStatus = jdbc.queryForObject(
            "SELECT status FROM exchanges WHERE id = ?", String.class, exchangeId);
        assertThat(exchangeStatus).isEqualTo("return_received");

        // A SECOND out-of-window delivered piece on the SAME order, after resolution —
        // the bypass must be closed now that the exchange is no longer 'matched'.
        String piece2 = createPiece("delivered", orderId);
        jdbc.update("UPDATE pieces SET last_event_at = now() - interval '45 days' WHERE id = ?", piece2);
        createAlloc(orderId, piece2);
        Map<String, Object> scan2 = sessionSvc.scan(sessionId, "PC-" + piece2, locationId, actorId);

        assertThat(scan2.get("unexpected"))
            .as("bypass must be closed once the exchange is resolved — a second out-of-window " +
                "delivered piece must be rejected again, not silently waved through")
            .isEqualTo(true);
        assertThat(pieceStatus(piece2))
            .as("rejected piece must not transition")
            .isEqualTo("delivered");
    }

    // ── (w) Step 3C-fix Part 1: resolution flip guarded to the matched variant ───

    /**
     * Fixes the Step 3C Test-2 gap (formerly documented here as CURRENT BEHAVIOR — now
     * corrected). hasActiveReturnLeg() stays order-scoped (unchanged — any delivered
     * piece on a matched-exchange order still clears the scan-acceptance bypass), but
     * resolveReturnLegIfComplete()'s exchange branch now re-derives, via
     * ExchangeMatchService.resolveIfDispositionedPieceMatches(), whether the piece it is
     * ACTUALLY resolving is the one the matcher would currently call "the" match — same
     * variant-title-over-product-title precedence attemptMatch() uses.
     *
     * Sequence: dispose the wrong-variant piece first (order-scoped cover still accepts
     * the scan, unchanged) → it restocks normally, but the exchange must NOT flip to
     * return_received. Only when the REAL matched piece is later scanned and resolved
     * does the exchange flip.
     */
    @Test
    void w_multiItemMatchedOrder_wrongVariantDisposal_doesNotResolveExchange_realMatchDoes() {
        UUID orderId = createOrder("delivered");
        createShipment(orderId, "AWB-EXC-W-FWD", "delivered");
        // Exchange's inbound description matches the class-level variant's title ("Blue M")
        // — see setupFixture() — not the unrelated second variant created below.
        UUID exchangeId = createExchangeMatched("EXC-W-TRACK", orderId, "01000000002", "Blue M");

        // A second variant on the SAME order — unrelated to the exchange.
        UUID productB = UUID.randomUUID();
        UUID variantB = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-RST-W', 'Other Product', 'active')", productB, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-RST-W', 'Unrelated Item', 'OTHER-SKU')", variantB, tenantId, productB);

        // realPiece stays WITHIN the return window (unlike wrongPiece) — it must remain a
        // visible 'delivered' candidate to findCandidates() while wrongPiece is being
        // resolved, or narrowing has nothing to disambiguate against and trivially
        // "matches" whatever the sole remaining candidate is. Its own scan/resolve below
        // doesn't need the exchange bypass either way — the normal in-window path accepts it.
        String wrongPiece = createPieceForVariant(variantB, orderId, "delivered", 45);
        String realPiece  = createPiece("delivered", orderId);
        createAllocForVariant(orderId, variantB, wrongPiece);
        createAlloc(orderId, realPiece);

        UUID sessionId = openSession();

        // Dispose the WRONG variant first.
        Map<String, Object> scanWrong = sessionSvc.scan(sessionId, "PC-" + wrongPiece, locationId, actorId);
        assertThat(scanWrong.get("unexpected"))
            .as("order-scoped bypass still accepts any delivered piece — unchanged")
            .isEqualTo(false);
        sessionSvc.disposition(sessionId, wrongPiece, "restock", null, locationId, actorId);
        assertThat(pieceStatus(wrongPiece))
            .as("the wrong-variant piece itself still restocks correctly — no inventory corruption")
            .isEqualTo("available");

        assertThat(exchangeStatusById(exchangeId))
            .as("GUARD: resolving the wrong variant must NOT flip the exchange — it isn't the matched item")
            .isEqualTo("matched");

        // Now the REAL matched item.
        Map<String, Object> scanReal = sessionSvc.scan(sessionId, "PC-" + realPiece, locationId, actorId);
        assertThat(scanReal.get("unexpected"))
            .as("cover is still open — exchange hasn't resolved yet")
            .isEqualTo(false);
        sessionSvc.disposition(sessionId, realPiece, "restock", null, locationId, actorId);

        assertThat(exchangeStatusById(exchangeId))
            .as("resolving the ACTUAL matched piece must flip the exchange")
            .isEqualTo("return_received");
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

    /** CRP return-leg shipment (shipment_leg='return') — coexists with a forward shipment
     *  for the same order under ux_active_shipment_per_order_leg (V43). */
    private UUID createReturnLegShipment(UUID orderId, String trackingNumber, String state) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, ?, ?, ?::shipment_internal_state, 'return')",
            id, tenantId, orderId, trackingNumber, state);
        return id;
    }

    /** Exchange matched to orderId (matched_order_id/match_method/matched_at all set). */
    private UUID createExchange(String trackingNumber, String status, UUID matchedOrderId) {
        return jdbc.queryForObject(
            "INSERT INTO exchanges " +
            "(tenant_id, tracking_number, status, matched_order_id, match_method, matched_at, raw) " +
            "VALUES (?, ?, ?, ?, 'phone', now(), '{}'::jsonb) RETURNING id",
            UUID.class, tenantId, trackingNumber, status, matchedOrderId);
    }

    /** Exchange with no matched_order_id at all — status='unmatched'. */
    private UUID createExchangeUnmatched(String trackingNumber) {
        return jdbc.queryForObject(
            "INSERT INTO exchanges (tenant_id, tracking_number, status, raw) " +
            "VALUES (?, ?, 'unmatched', '{}'::jsonb) RETURNING id",
            UUID.class, tenantId, trackingNumber);
    }

    /**
     * Matched exchange with a REAL raw payload (receiver.phone + returnSpecs.description)
     * — needed so ExchangeMatchService.resolveIfDispositionedPieceMatches() can actually
     * re-derive a candidate pool and narrow by description, instead of falling back to
     * the trivial single-candidate case createExchange()'s raw='{}' always hits.
     */
    private UUID createExchangeMatched(String trackingNumber, UUID matchedOrderId,
                                        String phone, String inboundDescription) {
        String raw = "{\"receiver\":{\"phone\":\"" + phone + "\"}," +
            "\"returnSpecs\":{\"packageDetails\":{\"description\":\"" + inboundDescription + "\"}}}";
        return jdbc.queryForObject(
            "INSERT INTO exchanges " +
            "(tenant_id, tracking_number, status, matched_order_id, match_method, matched_at, " +
            "    inbound_description, raw) " +
            "VALUES (?, ?, 'matched', ?, 'phone', now(), ?, ?::jsonb) RETURNING id",
            UUID.class, tenantId, trackingNumber, matchedOrderId, inboundDescription, raw);
    }

    private String exchangeStatusById(UUID exchangeId) {
        return jdbc.queryForObject("SELECT status FROM exchanges WHERE id = ?", String.class, exchangeId);
    }

    private String createPiece(String status, UUID orderId) {
        return createPieceForVariant(variantId, orderId, status, 0);
    }

    /** Like createPiece(), but for an explicit variant and with last_event_at backdated. */
    private String createPieceForVariant(UUID variant, UUID orderId, String status, int daysAgo) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces " +
            "(id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        ?::piece_status, ?, now() - (interval '1 day' * ?))",
            id, tenantId, variant, "PC-" + id, id, status, orderId, daysAgo);
        return id;
    }

    private void createAlloc(UUID orderId, String pieceId) {
        createAllocForVariant(orderId, variantId, pieceId);
    }

    private void createAllocForVariant(UUID orderId, UUID variant, String pieceId) {
        UUID itemId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, ?, 1)", itemId, tenantId, orderId, variant);
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
