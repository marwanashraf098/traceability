package com.traceability.inventory;

import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.portal.PortalService;
import com.traceability.tenancy.TenantContext;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * returns-expected-back (approved 2026-10-19; prod: Nuvira #3134 / #3140, session bac24a14) —
 * a parcel coming back to sender after the merchant cancelled the order in Shopify is a normal
 * return, and the worker who unpacks it can grade it.
 *
 * Expected-back rule (ShipmentLinkService.isExpectedBack — scan and alert always agree):
 *   e1 cancelled in Shopify after ship, RTO 20 / 41 recorded, scanned BEFORE state 46 (piece still
 *      with_courier) → not unexpected, no unexpected_return alert
 *   e2 same, scanned AFTER 46 (piece already return_pending_inspection) → not unexpected (no regression)
 *   e3 partial refund after ship (current_quantity 1 of 2), tracked, both units back → not unexpected
 *   e4 a piece of a DIFFERENT order (no return evidence) scanned next to an RTO AWB → unexpected, the
 *      alert fires, and the item says it is not on a scanned AWB
 *   e5 awaiting_pickup with no return evidence, its own AWB scanned → still unexpected (a never-collected
 *      parcel); the item says it IS on a scanned AWB
 * Untracked parcel units (UntrackedParcelUnits.PARCEL_UNIT_CAP_SQL — the ordered quantity):
 *   u1 cancelled after ship (current_quantity 0) → full unit rows; the portal cap still says 0
 *   u2 partial refund after ship (current_quantity 1 of 2) → 2 rows; the portal cap still says 1
 * Grading permissions (HTTP, real JWTs):
 *   w1 worker restocks an expected item → 200, actor = the worker on the item and the ledger event
 *   w2 worker restocks an UNEXPECTED item → 200, the flag stays, the alert was raised and clears only
 *      by its existing rule (piece left return_pending_inspection) — no resolution row is written
 *   w3 worker marks damaged → 200
 *   w4 worker mismatch → 403 NEEDS_MANAGER, item untouched; owner mismatch on the same item → 200
 *   w5 worker close with a pending item → 409 SESSION_CLOSE_BLOCKED; after grading → 200
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnsExpectedBackTest {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ReturnSessionService sessions;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean JobScheduler jobScheduler;

    record T(UUID id, UUID store, UUID location, UUID owner, UUID worker, UUID variant) {}
    /** A Bosta forward leg of an order, with its AWB. */
    record Leg(UUID order, UUID shipment, String awb, UUID orderItem) {}

    final AtomicInteger seq = new AtomicInteger();

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── Expected-back rule ────────────────────────────────────────────────────

    @Test
    void e1_cancelledAfterShip_rtoRecorded_scannedBefore46_notUnexpected_noAlert() {
        T t = tenant("e1");
        Leg leg = cancelledOrderLeg(t, 1, 0);
        history(leg, "with_courier", 24);
        history(leg, "returning", 20);
        history(leg, "returning", 41);
        String piece = piece(t, leg, "with_courier");
        UUID s = session(t);
        scan(t, s, leg.awb());
        Map<String, Object> item = scan(t, s, "PC-" + piece);

        assertThat(item.get("unexpected")).isEqualTo(false);
        assertThat(pieceStatus(piece)).isEqualTo("return_pending_inspection");
        assertThat(unexpectedAlerts(t)).doesNotContain(piece);
    }

    @Test
    void e2_cancelledAfterShip_scannedAfter46_notUnexpected() {
        T t = tenant("e2");
        Leg leg = cancelledOrderLeg(t, 1, 0);
        history(leg, "with_courier", 24);
        history(leg, "returning", 41);
        history(leg, "returned", 46);
        jdbc.update("UPDATE shipments SET internal_state = 'returned'::shipment_internal_state WHERE id = ?", leg.shipment());
        String piece = piece(t, leg, "return_pending_inspection");
        UUID s = session(t);
        scan(t, s, leg.awb());
        Map<String, Object> item = scan(t, s, "PC-" + piece);

        assertThat(item.get("unexpected")).isEqualTo(false);
        assertThat(unexpectedAlerts(t)).doesNotContain(piece);
    }

    @Test
    void e3_partialRefundAfterShip_tracked_bothUnitsBack_notUnexpected() {
        T t = tenant("e3");
        Leg leg = orderLeg(t, "delivered", 2, 1, "returning");
        history(leg, "with_courier", 24);
        history(leg, "returning", 41);
        String a = piece(t, leg, "with_courier");
        String b = piece(t, leg, "with_courier");
        UUID s = session(t);
        scan(t, s, leg.awb());

        assertThat(scan(t, s, "PC-" + a).get("unexpected")).isEqualTo(false);
        assertThat(scan(t, s, "PC-" + b).get("unexpected")).isEqualTo(false);
        assertThat(unexpectedAlerts(t)).doesNotContain(a, b);
    }

    @Test
    void e4_pieceOfAnotherOrder_noReturnEvidence_unexpected_alertFires_notOnAScannedAwb() {
        T t = tenant("e4");
        Leg rto = cancelledOrderLeg(t, 1, 0);
        history(rto, "returning", 41);
        Leg other = orderLeg(t, "awaiting_pickup", 1, null, "with_courier");
        history(other, "with_courier", 24);
        String foreign = piece(t, other, "with_courier");
        UUID s = session(t);
        scan(t, s, rto.awb());
        Map<String, Object> item = scan(t, s, "PC-" + foreign);

        assertThat(item.get("unexpected")).isEqualTo(true);
        assertThat(item.get("on_scanned_awb")).isEqualTo(false);
        assertThat(unexpectedAlerts(t)).containsExactly(foreign);
    }

    @Test
    void e5_awaitingPickup_noReturnEvidence_ownAwbScanned_stillUnexpected() {
        T t = tenant("e5");
        Leg leg = orderLeg(t, "awaiting_pickup", 1, null, "created");
        history(leg, "created", 10);
        String piece = piece(t, leg, "awaiting_pickup");
        UUID s = session(t);
        scan(t, s, leg.awb());
        Map<String, Object> item = scan(t, s, "PC-" + piece);

        assertThat(item.get("unexpected")).as("an AWB scanned in the session is not evidence on its own").isEqualTo(true);
        assertThat(item.get("on_scanned_awb")).isEqualTo(true);
        assertThat(unexpectedAlerts(t)).containsExactly(piece);
    }

    // ── Untracked parcel units ────────────────────────────────────────────────

    @Test
    void u1_untrackedRto_cancelledAfterShip_fullUnitRows_portalCapUnchanged() {
        T t = tenant("u1");
        Leg leg = untrackedRto(t, 2, 0);
        UUID s = session(t);
        scan(t, s, leg.awb());

        assertThat(units(t, s, leg.awb())).hasSize(2);
        assertThat(portalCap(leg.orderItem())).as("the portal still caps by current_quantity").isEqualTo(0);
    }

    @Test
    void u2_untrackedRto_partialRefundAfterShip_fullUnitRows_portalCapUnchanged() {
        T t = tenant("u2");
        Leg leg = untrackedRto(t, 2, 1);
        UUID s = session(t);
        scan(t, s, leg.awb());

        assertThat(units(t, s, leg.awb())).hasSize(2);
        assertThat(portalCap(leg.orderItem())).isEqualTo(1);
    }

    // ── Grading permissions ───────────────────────────────────────────────────

    @Test
    void w1_workerRestocksExpectedItem_ok_actorIsTheWorker() {
        T t = tenant("w1");
        Fixture f = rtoItem(t);

        ResponseEntity<Map> r = disposition(t, f, token(t, "worker"), "restock", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("disposition")).isEqualTo("restocked");
        assertThat(pieceStatus(f.piece())).isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT disposition_by FROM return_session_items WHERE session_id = ? AND piece_id = ?",
            UUID.class, f.session(), f.piece())).isEqualTo(t.worker());
        assertThat(jdbc.queryForObject(
            "SELECT actor_user_id FROM piece_events WHERE piece_id = ? AND to_status = 'available'::piece_status " +
            "ORDER BY created_at DESC LIMIT 1", UUID.class, f.piece())).isEqualTo(t.worker());
    }

    @Test
    void w2_workerRestocksUnexpectedItem_ok_flagStays_alertClearsOnlyByItsRule() {
        T t = tenant("w2");
        Leg leg = orderLeg(t, "awaiting_pickup", 1, null, "created");
        history(leg, "created", 10);
        String piece = piece(t, leg, "awaiting_pickup");
        UUID s = session(t);
        scan(t, s, leg.awb());
        assertThat(scan(t, s, "PC-" + piece).get("unexpected")).isEqualTo(true);
        assertThat(unexpectedAlerts(t)).containsExactly(piece);

        ResponseEntity<Map> r = disposition(t, new Fixture(s, piece), token(t, "worker"), "restock", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT unexpected FROM return_session_items WHERE session_id = ? AND piece_id = ?",
            Boolean.class, s, piece)).as("grading never clears the flag").isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM exception_resolutions WHERE tenant_id = ? AND exception_type = 'unexpected_return'",
            Integer.class, t.id())).as("grading writes no resolution").isZero();
        assertThat(unexpectedAlerts(t)).as("existing rule: the piece left return_pending_inspection").isEmpty();
    }

    @Test
    void w3_workerMarksDamaged_ok() {
        T t = tenant("w3");
        Fixture f = rtoItem(t);

        ResponseEntity<Map> r = disposition(t, f, token(t, "worker"), "damaged", "torn seam");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("disposition")).isEqualTo("damaged");
        assertThat(pieceStatus(f.piece())).isEqualTo("damaged");
    }

    @Test
    void w4_workerMismatch_needsManager_itemUntouched_ownerMismatchOk() {
        T t = tenant("w4");
        Fixture f = rtoItem(t);

        ResponseEntity<Map> worker = disposition(t, f, token(t, "worker"), "mismatch", null);
        assertThat(worker.getStatusCode().value()).isEqualTo(403);
        assertThat(worker.getBody().get("code")).isEqualTo("NEEDS_MANAGER");
        assertThat(worker.getBody().get("message_en")).isEqualTo("This needs a manager or owner");
        assertThat(itemDisposition(f)).isEqualTo("pending");

        ResponseEntity<Map> owner = disposition(t, f, token(t, "owner"), "mismatch", null);
        assertThat(owner.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(itemDisposition(f)).isEqualTo("mismatch");
    }

    @Test
    void w5_workerClose_refusedWhilePending_okOnceGraded() {
        T t = tenant("w5");
        Fixture f = rtoItem(t);
        String url = base() + "/api/v1/returns/sessions/" + f.session() + "/close";

        ResponseEntity<Map> blocked = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(headers(token(t, "worker"))), Map.class);
        assertThat(blocked.getStatusCode().value()).isEqualTo(409);
        assertThat(blocked.getBody().get("code")).isEqualTo("SESSION_CLOSE_BLOCKED");

        assertThat(disposition(t, f, token(t, "worker"), "restock", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> closed = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(headers(token(t, "worker"))), Map.class);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT closed_by FROM return_sessions WHERE id = ?", UUID.class, f.session()))
            .isEqualTo(t.worker());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    record Fixture(UUID session, String piece) {}

    /** An RTO piece scanned before 46 (expected), pending a decision. */
    private Fixture rtoItem(T t) {
        Leg leg = cancelledOrderLeg(t, 1, 0);
        history(leg, "returning", 41);
        String piece = piece(t, leg, "with_courier");
        UUID s = session(t);
        scan(t, s, leg.awb());
        assertThat(scan(t, s, "PC-" + piece).get("unexpected")).isEqualTo(false);
        return new Fixture(s, piece);
    }

    private String base() { return "http://localhost:" + port; }

    private String token(T t, String role) {
        return jwt.issueAccessToken("worker".equals(role) ? t.worker() : t.owner(), t.id(), role);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<Map> disposition(T t, Fixture f, String token, String verdict, String reason) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("disposition", verdict);
        body.put("reason", reason);
        return rest.exchange(base() + "/api/v1/returns/sessions/" + f.session() + "/items/" + f.piece() + "/disposition",
            HttpMethod.POST, new HttpEntity<>(body, headers(token)), Map.class);
    }

    private String itemDisposition(Fixture f) {
        return jdbc.queryForObject("SELECT disposition FROM return_session_items WHERE session_id = ? AND piece_id = ?",
            String.class, f.session(), f.piece());
    }

    private List<String> unexpectedAlerts(T t) {
        return TenantContext.runAs(t.id(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "unexpected_return".equals(e.get("type")))
            .map(e -> (String) e.get("piece_id")).toList();
    }

    private UUID session(T t) {
        return TenantContext.runAs(t.id(), () -> sessions.createSession(null, t.owner()));
    }

    private Map<String, Object> scan(T t, UUID s, String raw) {
        return TenantContext.runAs(t.id(), () -> sessions.scan(s, raw, t.location(), t.worker()));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> units(T t, UUID s, String awb) {
        Map<String, Object> detail = TenantContext.runAs(t.id(), () -> sessions.getSession(s));
        Map<String, Object> card = ((List<Map<String, Object>>) detail.get("parcels")).stream()
            .filter(p -> awb.equals(p.get("awb"))).findFirst().orElseThrow();
        return (List<Map<String, Object>>) card.get("untrackedUnits");
    }

    private int portalCap(UUID orderItem) {
        return jdbc.queryForObject("SELECT " + PortalService.UNTRACKED_CAP_SQL + " FROM order_items oi WHERE oi.id = ?",
            Integer.class, orderItem);
    }

    private String pieceStatus(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    private void history(Leg leg, String state, int providerState) {
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, provider_state) " +
            "SELECT tenant_id, id, ?, ? FROM shipments WHERE id = ?", state, providerState, leg.shipment());
    }

    /** An order cancelled in Shopify after it shipped: its line is quantity {@code qty}, current {@code current}. */
    private Leg cancelledOrderLeg(T t, int qty, int current) {
        Leg leg = orderLeg(t, "cancelled", qty, current, "returning");
        jdbc.update("UPDATE orders SET raw = '{\"cancelled_at\": \"2026-10-17T18:20:00+03:00\"}'::jsonb WHERE id = ?", leg.order());
        return leg;
    }

    private Leg orderLeg(T t, String orderStatus, int qty, Integer current, String shipmentState) {
        int k = seq.incrementAndGet();
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'cod'::order_payment_method, now() - interval '5 days') RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/EB" + k + "-" + t.id(), "#EB" + k, orderStatus);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, raw) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
            item, t.id(), order, t.variant(), qty,
            current == null ? null : "{\"quantity\": " + qty + ", \"current_quantity\": " + current + "}");
        String awb = String.valueOf(8_100_000_000L + k);
        UUID shipment = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward', " +
            "  '{\"type\": {\"code\": 10, \"value\": \"Send\"}}'::jsonb) RETURNING id",
            UUID.class, t.id(), order, awb, shipmentState);
        return new Leg(order, shipment, awb, item);
    }

    /** An untracked order (no allocation), returned to sender, line of {@code qty}, current {@code current}. */
    private Leg untrackedRto(T t, int qty, int current) {
        Leg leg = orderLeg(t, "cancelled", qty, current, "returned");
        jdbc.update("UPDATE shipments SET raw = '{\"type\": {\"code\": 20, \"value\": \"Return to Origin\"}, " +
            "\"state\": {\"code\": 46, \"value\": \"Returned to business\"}}'::jsonb WHERE id = ?", leg.shipment());
        history(leg, "returned", 46);
        return leg;
    }

    /** A tracked piece on the leg's order line, packed allocation, in {@code status}. */
    private String piece(T t, Leg leg, String status) {
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'E' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?, now())",
            piece, t.id(), t.variant(), "PC-" + piece, piece, status, leg.order());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            t.id(), leg.orderItem(), piece);
        return piece;
    }

    private T tenant(String name) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(),
             worker = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, "ExpectedBack " + name);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, "eb-" + name + "-" + id.toString().substring(0, 6) + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            owner, id, "owner-" + owner + "@test.local");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Fatma', ?, 'h', 'worker', true)",
            worker, id, "worker-" + worker + "@test.local");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            product, id, store, "P-eb-" + id);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'White / M', 'SH-M')",
            variant, id, product, "V-" + variant);
        return new T(id, store, location, owner, worker, variant);
    }
}
