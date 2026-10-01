package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaGateway;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pick &amp; Pack S3 commit 5 — waybill pack sessions over HTTP: start / resume, waybill → order
 * card or rejection, piece scans, auto complete+link in its own transaction (guarded — never a
 * Bosta call), complete_failed + retry, undo, set aside, end, ownership, mode copy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackSessionTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired JwtService       jwt;
    @MockBean  BostaGateway     bostaGateway;
    @MockBean  JobScheduler     jobScheduler;

    /** One store in waybill mode, a packer, an order with 2 units of one variant and its waybill. */
    final class Shop {
        final PackFixtures f;
        final UUID packer;
        final String token;
        final UUID variant;
        Shop(String name) {
            f = new PackFixtures(jdbc, name);
            jdbc.update("UPDATE tenants SET pick_pack_mode = 'waybill_scan' WHERE id = ?", f.tenant);
            packer = f.user("Ahmed", "worker");
            token = jwt.issueAccessToken(packer, f.tenant, "worker");
            variant = f.variant("Linen shirt", "LS-OLV-M", "https://cdn.shopify.com/s/files/shirt.jpg");
        }
        Order order(String number, int qty) {
            UUID id = f.order(number, 1);
            f.item(id, variant, qty);
            String tn = f.forward(id);
            String[] pieces = new String[qty];
            for (int i = 0; i < qty; i++) pieces[i] = f.piece(variant, packer);
            return new Order(id, tn, pieces);
        }
        String otherToken(String name) {
            return jwt.issueAccessToken(f.user(name, "worker"), f.tenant, "worker");
        }
    }

    record Order(UUID id, String tracking, String[] pieces) {}

    @BeforeEach
    void resetBosta() { reset(bostaGateway); }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void waybillOpensOrder_lastPieceAutoCompletesAndLinks_withOpenTimeRawScan_neverCallsBosta() {
        Shop s = new Shop("Flow");
        Order o = s.order("#1047", 2);
        jdbc.update("UPDATE orders SET address = '{\"city\":\"Cairo\",\"zone\":\"Nasr City\"}'::jsonb WHERE id = ?", o.id());
        UUID batch = jdbc.queryForObject(
            "INSERT INTO pack_print_batches (tenant_id, batch_no, printed_by, paper, sort, scope, waybill_count, order_guaranteed) " +
            "VALUES (?, 3, ?, 'A6', 'oldest', 'new', 1, true) RETURNING id", UUID.class, s.f.tenant, s.packer);
        jdbc.update("INSERT INTO pack_print_batch_items (batch_id, tenant_id, order_id, shipment_id, tracking_number, position) " +
                    "SELECT ?, tenant_id, order_id, id, tracking_number, 1 FROM shipments WHERE tracking_number = ?",
                    batch, o.tracking());

        UUID session = start(s.token);
        String raw = "D-07-" + o.tracking();
        Map<String, Object> opened = post(s.token, "/" + session + "/waybill", Map.of("code", raw));
        assertThat(opened.get("result")).isEqualTo("opened");
        Map<String, Object> card = map(opened.get("order"));
        assertThat(card.get("number")).isEqualTo("#1047");
        assertThat(card.get("area")).isEqualTo("Nasr City, Cairo");
        assertThat(card.get("courierType")).isEqualTo("delivery");
        assertThat(card.get("batchNo")).isEqualTo(3);
        assertThat(card.get("cod_amount")).isNotNull();
        assertThat(map(list(card.get("items")).get(0)).get("imageUrl")).isEqualTo("https://cdn.shopify.com/s/files/shirt.jpg");
        assertThat(lockedBy(o.id())).isEqualTo(s.packer);

        Map<String, Object> first = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        assertThat(first.get("status")).isEqualTo("scanned");
        Map<String, Object> last = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[1]));
        assertThat(last.get("status")).isEqualTo("completed");
        assertThat(map(last.get("packed")).get("orderNumber")).isEqualTo("#1047");

        assertThat(orderStatus(o.id())).isEqualTo("awaiting_pickup");
        assertThat(pieceStatuses(o.id())).containsOnly("awaiting_pickup");
        assertThat(count("SELECT COUNT(*) FROM piece_events WHERE order_id = ? AND event_type = 'pack'", o.id())).isEqualTo(2);
        List<Map<String, Object>> linked = jdbc.queryForList(
            "SELECT raw_scan, metadata ->> 'pack_session_id' AS sid FROM piece_events WHERE order_id = ? AND event_type = 'tracking_linked'",
            o.id());
        assertThat(linked).hasSize(2).allSatisfy(e -> {
            assertThat(e.get("raw_scan")).isEqualTo(raw);
            assertThat(e.get("sid")).isEqualTo(session.toString());
        });
        assertThat(lockedBy(o.id())).isNull();

        Map<String, Object> view = get(s.token, "/" + session);
        assertThat(view.get("openOrder")).isNull();
        assertThat(map(view.get("counters")).get("packed")).isEqualTo(1);
        assertThat(map(list(view.get("recent")).get(0)).get("outcome")).isEqualTo("packed");

        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
    }

    @Test
    void legTerminatedBeforeTheLastScan_stillCompletes_andNeverCallsBosta() {
        Shop s = new Shop("Terminated");
        Order o = s.order("#T1", 2);
        UUID session = start(s.token);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));
        post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        jdbc.update("UPDATE shipments SET internal_state = 'terminated' WHERE tracking_number = ?", o.tracking());

        Map<String, Object> last = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[1]));
        assertThat(last.get("status")).isEqualTo("completed");
        assertThat(pieceStatuses(o.id())).containsOnly("awaiting_pickup");
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
    }

    // ── Guard / complete_failed / retry ───────────────────────────────────────

    @Test
    void guardTrips_nothingCompletes_piecesUnchanged_claimHeld_thenRetrySucceeds() {
        Shop s = new Shop("Guard");
        Order o = s.order("#G1", 1);
        UUID session = start(s.token);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));
        // The waybill that opened the order is no longer on it.
        jdbc.update("UPDATE shipments SET tracking_number = ? WHERE tracking_number = ?", "9" + o.tracking(), o.tracking());

        Map<String, Object> r = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        assertThat(r.get("status")).isEqualTo("complete_failed");
        assertThat(r.get("failCode")).isEqualTo("WAYBILL_NOT_ON_ORDER");
        assertThat(r.get("order")).isNotNull();
        assertThat(orderStatus(o.id())).isEqualTo("new");
        assertThat(pieceStatuses(o.id())).containsOnly("reserved");          // the scan itself committed
        assertThat(count("SELECT COUNT(*) FROM piece_events WHERE order_id = ? AND event_type IN ('pack','tracking_linked')", o.id())).isZero();
        assertThat(lockedBy(o.id())).isEqualTo(s.packer);
        assertThat(map(get(s.token, "/" + session).get("openOrder")).get("id")).isEqualTo(o.id().toString());

        jdbc.update("UPDATE shipments SET tracking_number = ? WHERE tracking_number = ?", o.tracking(), "9" + o.tracking());
        Map<String, Object> retry = post(s.token, "/" + session + "/orders/" + o.id() + "/complete", Map.of());
        assertThat(retry.get("status")).isEqualTo("completed");
        assertThat(orderStatus(o.id())).isEqualTo("awaiting_pickup");
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
    }

    @Test
    void guardTrips_whereLinkWouldOtherwiseCreateAShipmentAndCallBosta_noBostaCall() {
        Shop s = new Shop("GuardBosta");
        Order o = s.order("#G2", 1);
        UUID session = start(s.token);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));
        // The order's only forward leg ended AND no shipment carries the opening waybill any more:
        // without the guard, linkByAwbScan would take its new-shipment branch → fetchDelivery().
        jdbc.update("UPDATE shipments SET tracking_number = ?, internal_state = 'terminated' WHERE tracking_number = ?",
            "9" + o.tracking(), o.tracking());

        Map<String, Object> r = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        assertThat(r.get("status")).isEqualTo("complete_failed");
        assertThat(r.get("failCode")).isEqualTo("WAYBILL_NOT_ON_ORDER");
        assertThat(count("SELECT COUNT(*) FROM shipments WHERE order_id = ?", o.id())).isEqualTo(1);
        verify(bostaGateway, never()).fetchDelivery(anyString(), anyString());
    }

    @Test
    void shipmentMovedAfterOpen_completeRefused_isCompleteFailed() {
        Shop s = new Shop("Moved");
        Order o = s.order("#V1", 1);
        UUID session = start(s.token);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
                    "SELECT tenant_id, id, 'with_courier' FROM shipments WHERE tracking_number = ?", o.tracking());

        // scan() itself refuses once the shipment has moved (ALREADY_SHIPPED) — nothing changes.
        Map<String, Object> r = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        assertThat(r.get("status")).isEqualTo("rejected");
        assertThat(map(r.get("scan")).get("code")).isEqualTo("ALREADY_SHIPPED");
        assertThat(pieceStatuses(o.id())).isEmpty();
    }

    // ── Undo / set aside / end ────────────────────────────────────────────────

    @Test
    void undoWhileOpen_thenRefusedOnceTheOrderCloses() {
        Shop s = new Shop("Undo");
        Order o = s.order("#U1", 2);
        UUID session = start(s.token);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));
        Map<String, Object> scanned = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        String pieceId = (String) map(scanned.get("scan")).get("pieceId");

        assertThat(exchange(s.token, HttpMethod.DELETE, "/" + session + "/orders/" + o.id() + "/scan/" + pieceId, null).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(pieceStatus(pieceId)).isEqualTo("available");

        post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        Map<String, Object> done = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[1]));
        assertThat(done.get("status")).isEqualTo("completed");
        ResponseEntity<Map> late = exchange(s.token, HttpMethod.DELETE, "/" + session + "/orders/" + o.id() + "/scan/" + pieceId, null);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("code")).isEqualTo("ORDER_NOT_OPEN");
        assertThat(pieceStatus(pieceId)).isEqualTo("awaiting_pickup");
    }

    @Test
    void setAside_returnsScannedPiecesViaUnscan_releasesClaim_recordsReason() {
        Shop s = new Shop("SetAside");
        Order o = s.order("#A1", 3);
        UUID session = start(s.token);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));
        post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[1]));

        assertThat(exchange(s.token, HttpMethod.POST, "/" + session + "/orders/" + o.id() + "/set-aside",
            Map.of("reason", "nonsense")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> r = post(s.token, "/" + session + "/orders/" + o.id() + "/set-aside", Map.of("reason", "piece_missing"));
        assertThat(r.get("piecesReturned")).isEqualTo(2);
        assertThat(pieceStatus(s.f.pieceId(o.pieces()[0]))).isEqualTo("available");
        assertThat(pieceStatus(s.f.pieceId(o.pieces()[1]))).isEqualTo("available");
        assertThat(count("SELECT COUNT(*) FROM piece_events WHERE order_id = ? AND event_type = 'unscan'", o.id())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
                         "WHERE oi.order_id = ? AND a.status = 'active'", o.id())).isZero();
        assertThat(lockedBy(o.id())).isNull();
        assertThat(orderStatus(o.id())).isEqualTo("new");

        Map<String, Object> view = get(s.token, "/" + session);
        assertThat(view.get("openOrder")).isNull();
        Map<String, Object> row = map(list(view.get("recent")).get(0));
        assertThat(row.get("outcome")).isEqualTo("set_aside");
        assertThat(row.get("reason")).isEqualTo("piece_missing");
        assertThat(map(view.get("counters")).get("setAside")).isEqualTo(1);
    }

    @Test
    void end_refusedWhileOrderOpen_thenReleasesClaimsAndEnds() {
        Shop s = new Shop("End");
        Order o = s.order("#N1", 1);
        UUID session = start(s.token);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));

        ResponseEntity<Map> refused = exchange(s.token, HttpMethod.POST, "/" + session + "/end", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("ORDER_OPEN");

        post(s.token, "/" + session + "/orders/" + o.id() + "/set-aside", Map.of("reason", "other"));
        // A stray claim this packer still holds (e.g. from an interrupted session) is released on end.
        UUID stray = s.f.order("#N2", 1);
        s.f.claim(stray, s.packer, 0);
        assertThat(exchange(s.token, HttpMethod.POST, "/" + session + "/end", null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(lockedBy(stray)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM pack_sessions WHERE id = ?", String.class, session)).isEqualTo("ended");

        ResponseEntity<Map> afterEnd = exchange(s.token, HttpMethod.POST, "/" + session + "/waybill", Map.of("code", o.tracking()));
        assertThat(afterEnd.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(afterEnd.getBody().get("code")).isEqualTo("SESSION_ENDED");
    }

    // ── Session rules ─────────────────────────────────────────────────────────

    @Test
    void onePerUser_resumeReturnsOpenOrder_crossUserAndCrossTenantRefused() {
        Shop s = new Shop("Resume");
        Order o = s.order("#R1", 1);
        UUID session = start(s.token);
        assertThat(start(s.token)).isEqualTo(session);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));

        Map<String, Object> resumed = post(s.token, "", null);
        assertThat(resumed.get("id")).isEqualTo(session.toString());
        assertThat(map(resumed.get("openOrder")).get("id")).isEqualTo(o.id().toString());

        String other = s.otherToken("Omar");
        ResponseEntity<Map> notYours = exchange(other, HttpMethod.GET, "/" + session, null);
        assertThat(notYours.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(notYours.getBody().get("code")).isEqualTo("SESSION_NOT_YOURS");
        assertThat(exchange(other, HttpMethod.POST, "/" + session + "/orders/" + o.id() + "/scan",
            Map.of("code", o.pieces()[0])).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Omar's own session can't open the order Ahmed holds.
        UUID omarSession = start(other);
        Map<String, Object> claimed = post(other, "/" + omarSession + "/waybill", Map.of("code", o.tracking()));
        assertThat(claimed.get("result")).isEqualTo("rejected");
        assertThat(claimed.get("code")).isEqualTo("CLAIMED_BY_OTHER");
        assertThat(claimed.get("who")).isEqualTo("Ahmed");

        Shop elsewhere = new Shop("Elsewhere");
        ResponseEntity<Map> foreign = exchange(elsewhere.token, HttpMethod.GET, "/" + session, null);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void modeCopiedAtStart_keptWhenTenantSwitches_newSessionRefusedInQueueMode() {
        Shop s = new Shop("ModeCopy");
        Order o = s.order("#M1", 1);
        UUID session = start(s.token);
        jdbc.update("UPDATE tenants SET pick_pack_mode = 'order_queue' WHERE id = ?", s.f.tenant);

        assertThat(get(s.token, "/" + session).get("mode")).isEqualTo("waybill_scan");
        assertThat(post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking())).get("result")).isEqualTo("opened");
        assertThat(post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0])).get("status"))
            .isEqualTo("completed");
        exchange(s.token, HttpMethod.POST, "/" + session + "/end", null);

        ResponseEntity<Map> refused = exchange(s.token, HttpMethod.POST, "", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("MODE_NOT_WAYBILL");
    }

    @Test
    void rejections_recorded_andWrongScanTypeForTheStep() {
        Shop s = new Shop("Reject");
        Order o = s.order("#J1", 2);
        UUID session = start(s.token);

        Map<String, Object> unknown = post(s.token, "/" + session + "/waybill", Map.of("code", "1234567890"));
        assertThat(unknown.get("result")).isEqualTo("rejected");
        assertThat(unknown.get("code")).isEqualTo("NOT_FOUND");
        assertThat((String) unknown.get("messageAr")).isNotBlank();
        Map<String, Object> piece = post(s.token, "/" + session + "/waybill", Map.of("code", o.pieces()[0]));
        assertThat(piece.get("code")).isEqualTo("NOT_A_WAYBILL");
        assertThat(count("SELECT COUNT(*) FROM pack_session_orders WHERE session_id = ? AND outcome = 'rejected'", session)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT raw_scan FROM pack_session_orders WHERE session_id = ?", String.class, session))
            .containsExactlyInAnyOrder("1234567890", o.pieces()[0]);

        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));
        ResponseEntity<Map> second = exchange(s.token, HttpMethod.POST, "/" + session + "/waybill", Map.of("code", o.tracking()));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("ORDER_OPEN");

        Map<String, Object> waybillAsPiece = post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.tracking()));
        assertThat(waybillAsPiece.get("status")).isEqualTo("rejected");
        assertThat(map(waybillAsPiece.get("scan")).get("code")).isEqualTo("WAYBILL_WHILE_PACKING");

        assertThat(map(get(s.token, "/" + session).get("counters")).get("rejected")).isEqualTo(2);
    }

    @Test
    void summary_packedToday_andOpenSession() {
        Shop s = new Shop("Summary");
        Order o = s.order("#Y1", 1);
        Map<String, Object> before = get(s.token, "/summary");
        assertThat(before.get("packedToday")).isEqualTo(0);
        assertThat(before.get("openSessionId")).isNull();
        UUID session = start(s.token);
        post(s.token, "/" + session + "/waybill", Map.of("code", o.tracking()));
        post(s.token, "/" + session + "/orders/" + o.id() + "/scan", Map.of("code", o.pieces()[0]));
        Map<String, Object> after = get(s.token, "/summary");
        assertThat(after.get("packedToday")).isEqualTo(1);
        assertThat(after.get("openSessionId")).isEqualTo(session.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID start(String token) {
        return UUID.fromString((String) post(token, "", null).get("id"));
    }

    private Map<String, Object> get(String token, String path) {
        ResponseEntity<Map> r = exchange(token, HttpMethod.GET, path, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private Map<String, Object> post(String token, String path, Object body) {
        ResponseEntity<Map> r = exchange(token, HttpMethod.POST, path, body);
        assertThat(r.getStatusCode()).as(path + " → " + r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private ResponseEntity<Map> exchange(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + "/api/v1/pack-sessions" + path, method,
            new HttpEntity<>(body, h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return (List<Object>) o; }

    private UUID lockedBy(UUID orderId) {
        return jdbc.queryForObject("SELECT locked_by FROM orders WHERE id = ?", UUID.class, orderId);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    /** Status of every piece with a live (active / packed) allocation on the order. */
    private List<String> pieceStatuses(UUID orderId) {
        return jdbc.queryForList(
            "SELECT p.status::text FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "JOIN pieces p ON p.id = a.piece_id WHERE oi.order_id = ? AND a.status IN ('active','packed')",
            String.class, orderId);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
