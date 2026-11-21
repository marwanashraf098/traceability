package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.TransferService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B7d-1 — send by count and "remove from this send" on a bazar's linked transfer.
 * <ul>
 *   <li>Count send: available pieces AT MAIN only, labelled and untagged, oldest received first, the scan's own
 *       writes (transfer piece, ledger event, location, line count, price row); all or nothing; two counts at once
 *       never take the same piece; the same request id sends once; refused once sent; no path on a normal
 *       transfer.</li>
 *   <li>Every non-bazar transfer still refuses an untagged piece (scan-out AND send-back); so does the bazar's own
 *       scan.</li>
 *   <li>Remove: a scanned piece by its code, or N of a size newest-added first — each piece available at Main again,
 *       the counts right (an emptied line gone), retry-safe, refused once sent; no Shopify claim of any kind.</li>
 *   <li>Stock at Main for the panel: labelled + untagged at Main only, committed demand exactly as Inventory.</li>
 *   <li>Owner / manager only; another tenant's event 404; flag off 404; app_user isolation.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSendByCountTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;
    @Autowired PasswordEncoder  passwordEncoder;
    @Autowired TransferService  transfers;
    @Autowired com.traceability.inventory.VariantStockService stockService;

    UUID tenant, otherTenant, offTenant, main, side, storeId, ownerId;
    String owner, manager, worker, ownerOther, ownerOff;
    int seq = 0, pieceSeq = 0;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        offTenant = UUID.randomUUID();
        main = UUID.randomUUID();
        side = UUID.randomUUID();
        storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'B7d On', true)", tenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'B7d Other', true)", otherTenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'B7d Off', false)", offTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES " +
            "(?, ?, 'Main Warehouse', 'warehouse', true, true), (?, ?, 'Showroom', 'showroom', false, false)",
            main, tenant, side, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Other Main', 'warehouse', true, true)", UUID.randomUUID(), otherTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'b7d.myshopify.com', 'disconnected')", storeId, tenant);
        ownerId = UUID.randomUUID();
        owner = login(user(ownerId, tenant, "owner"));
        manager = login(user(UUID.randomUUID(), tenant, "manager"));
        worker = login(user(UUID.randomUUID(), tenant, "worker"));
        ownerOther = login(user(UUID.randomUUID(), otherTenant, "owner"));
        ownerOff = login(user(UUID.randomUUID(), offTenant, "owner"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private UUID variant(String productTitle, String title) {
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'active', '{}'::jsonb)", product, tenant, storeId, "P-" + product, productTitle);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) VALUES (?, ?, ?, ?, ?, 500.00)",
            id, tenant, product, "V-" + id, title);
        return id;
    }

    /** A piece at {@code location}, received {@code minutesAgo}; {@code tagged=false} → untagged (no short code). */
    private String piece(UUID variant, boolean tagged, int minutesAgo, UUID location, String status) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, now() - (? * interval '1 minute'))", id, tenant, variant, "PC-" + id,
            tagged ? String.format("D%07d", 7800000 + ++pieceSeq) : null, status, location, minutesAgo);
        return id;
    }

    private String piece(UUID variant, boolean tagged, int minutesAgo) {
        return piece(variant, tagged, minutesAgo, main, "available");
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String user(UUID id, UUID t, String role) {
        String email = role + "-" + UUID.randomUUID() + "@b7d.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, t, "B7d " + role, email, passwordEncoder.encode("pass123"), role);
        return email;
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> r = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().accessToken();
    }

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), Map.class);
    }

    /** A draft bazar event (its transfer preparing): the event id. */
    @SuppressWarnings("rawtypes")
    private String draft() {
        ResponseEntity<Map> created = call(owner, HttpMethod.POST, "/api/v1/bazar-events", Map.of(
            "name", "B7d Bazar " + (++seq), "startsOn", "2026-12-01", "endsOn", "2026-12-02",
            "locationName", "B7d Hall " + seq));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private UUID transferOf(String ev) {
        return jdbc.queryForObject("SELECT transfer_id FROM bazar_events WHERE id = ?::uuid", UUID.class, ev);
    }

    private UUID eventLocation(String ev) {
        return jdbc.queryForObject("SELECT location_id FROM bazar_events WHERE id = ?::uuid", UUID.class, ev);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> send(String token, String ev, UUID variant, int qty, String crid) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/scan-out-by-count",
            Map.of("variant_id", variant.toString(), "quantity", qty, "client_request_id", crid));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> removeCode(String token, String ev, String code, String crid) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/remove-from-send",
            Map.of("code", code, "client_request_id", crid));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> removeCount(String token, String ev, UUID variant, int qty, String crid) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/remove-from-send",
            Map.of("variant_id", variant.toString(), "quantity", qty, "client_request_id", crid));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> scan(String token, String ev, String code) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/scan-out", Map.of("barcode", code));
    }

    private static String crid() { return "c-" + UUID.randomUUID(); }

    /** Piece ids on the event's transfer (outstanding), in claim order. */
    private List<String> onSend(String ev) {
        return jdbc.queryForList("SELECT piece_id FROM transfer_pieces WHERE transfer_id = ? AND outcome IS NULL " +
            "ORDER BY created_at, id", String.class, transferOf(ev));
    }

    private Integer qtyOut(String ev, UUID variant) {
        List<Integer> q = jdbc.queryForList("SELECT qty_out FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            Integer.class, transferOf(ev), variant);
        return q.isEmpty() ? null : q.get(0);
    }

    private String status(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private UUID location(String pieceId) {
        return jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    /** Nothing of this event's send reached Shopify (no claim row of any kind). */
    private void assertNoShopifyClaims(String ev) {
        assertThat(count("SELECT count(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", transferOf(ev))).isZero();
        assertThat(count("SELECT count(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenant)).isZero();
    }

    @SuppressWarnings("rawtypes")
    private static Object detail(ResponseEntity<Map> r, String key) {
        return ((Map) r.getBody().get("details")).get(key);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<String> pieceIds(ResponseEntity<Map> r) {
        return (List<String>) r.getBody().get("pieceIds");
    }

    // ── send by count ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void c1_picksTheOldestAtMain_labelledAndUntagged_throughTheScansOwnWrites() {
        UUID v = variant("Count dress", "S");
        String oldestTagged = piece(v, true, 50);
        String untagged = piece(v, false, 40);
        String newest = piece(v, true, 10);
        String olderAtSide = piece(v, true, 90, side, "available");
        String olderOnHold = piece(v, true, 95, main, "on_hold");
        String ev = draft();

        ResponseEntity<Map> r = send(owner, ev, v, 2, crid());
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("count")).isEqualTo(2);
        assertThat(r.getBody().get("qtyOut")).isEqualTo(2);
        assertThat(pieceIds(r)).containsExactly(oldestTagged, untagged);
        assertThat(onSend(ev)).containsExactly(oldestTagged, untagged);

        UUID hall = eventLocation(ev);
        for (String p : List.of(oldestTagged, untagged)) {
            assertThat(status(p)).isEqualTo("out_on_transfer");
            assertThat(location(p)).isEqualTo(hall);
            assertThat(jdbc.queryForObject("SELECT from_location_id FROM transfer_pieces WHERE piece_id = ? AND outcome IS NULL",
                UUID.class, p)).as("left from main").isEqualTo(main);
            Map<String, Object> e = jdbc.queryForMap("SELECT event_type, from_status::text AS f, to_status::text AS t, " +
                "metadata->>'by' AS by, metadata->>'transfer_id' AS tr FROM piece_events WHERE piece_id = ? " +
                "ORDER BY created_at DESC, id DESC LIMIT 1", p);
            assertThat(e).containsEntry("event_type", "transferred_out").containsEntry("f", "available")
                .containsEntry("t", "out_on_transfer").containsEntry("by", "count")
                .containsEntry("tr", transferOf(ev).toString());
        }
        for (String p : List.of(newest, olderAtSide)) assertThat(status(p)).isEqualTo("available");
        assertThat(status(olderOnHold)).isEqualTo("on_hold");
        assertThat(qtyOut(ev, v)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM bazar_event_prices WHERE event_id = ?::uuid AND variant_id = ?", ev, v))
            .as("the variant joins the event like a scanned one").isEqualTo(1);
        // A scan after it adds to the same line.
        assertThat(scan(owner, ev, code(newest)).getBody().get("qtyOut")).isEqualTo(3);
        assertNoShopifyClaims(ev);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void c2_notEnoughAtMain_sendsNothing_andSaysHowMany() {
        UUID v = variant("Short dress", "M");
        piece(v, true, 30);
        piece(v, false, 20);
        piece(v, true, 10, side, "available");   // not at main: doesn't count
        String ev = draft();
        ResponseEntity<Map> r = send(owner, ev, v, 3, crid());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_NOT_ENOUGH_AT_MAIN");
        assertThat(detail(r, "available")).isEqualTo(2);
        assertThat(String.valueOf(r.getBody().get("message_en"))).contains("Only 2");
        assertThat(onSend(ev)).isEmpty();
        assertThat(qtyOut(ev, v)).isNull();
        assertThat(count("SELECT count(*) FROM pieces WHERE variant_id = ? AND status = 'available'", v)).isEqualTo(3);

        UUID none = variant("Empty dress", "M");
        ResponseEntity<Map> zero = send(owner, ev, none, 1, crid());
        assertThat(zero.getBody().get("code")).isEqualTo("BAZAR_NOT_ENOUGH_AT_MAIN");
        assertThat(detail(zero, "available")).isEqualTo(0);
        for (int bad : List.of(0, -1, TransferService.MAX_COUNT_MOVE + 1)) {
            assertThat(send(owner, ev, v, bad, crid()).getStatusCode()).as("qty " + bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    void c3_twoCountsAtOnce_neverTakeTheSamePiece() throws Exception {
        UUID v = variant("Busy dress", "L");
        Set<String> all = new HashSet<>();
        for (int i = 0; i < 8; i++) all.add(piece(v, i % 2 == 0, 100 - i));
        String ev = draft();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
            for (String token : List.of(owner, manager)) {
                fs.add(pool.submit(() -> { go.await(); return send(token, ev, v, 4, crid()); }));
            }
            go.countDown();
            List<String> sent = new ArrayList<>();
            for (Future<ResponseEntity<Map>> f : fs) {
                ResponseEntity<Map> r = f.get(30, TimeUnit.SECONDS);
                assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
                sent.addAll(pieceIds(r));
            }
            assertThat(sent).hasSize(8).doesNotHaveDuplicates();
            assertThat(new HashSet<>(sent)).isEqualTo(all);
        } finally {
            pool.shutdownNow();
        }
        assertThat(qtyOut(ev, v)).isEqualTo(8);
        assertThat(count("SELECT count(*) FROM pieces WHERE variant_id = ? AND status = 'out_on_transfer'", v)).isEqualTo(8);
        assertThat(send(owner, ev, v, 1, crid()).getBody().get("code")).isEqualTo("BAZAR_NOT_ENOUGH_AT_MAIN");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void c4_sameRequestIdTwice_sendsOnce() {
        UUID v = variant("Retry dress", "S");
        for (int i = 0; i < 4; i++) piece(v, false, 40 - i);
        String ev = draft();
        String id = crid();
        ResponseEntity<Map> first = send(owner, ev, v, 2, id);
        ResponseEntity<Map> again = send(owner, ev, v, 2, id);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("replayed")).isEqualTo(true);
        assertThat(pieceIds(again)).isEqualTo(pieceIds(first));
        assertThat(qtyOut(ev, v)).isEqualTo(2);
        assertThat(onSend(ev)).hasSize(2);
        assertThat(count("SELECT count(*) FROM piece_events WHERE metadata->>'client_request_id' = ?", id)).isEqualTo(2);
        // A missing request id is refused before anything moves.
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/scan-out-by-count",
            Map.of("variant_id", v.toString(), "quantity", 1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(onSend(ev)).hasSize(2);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void c5_refusedOnceSent_andThereIsNoCountPathForANormalTransfer() {
        UUID v = variant("Sent dress", "S");
        String a = piece(v, true, 30);
        piece(v, false, 20);
        String ev = draft();
        assertThat(scan(owner, ev, code(a)).getBody().get("success")).isEqualTo(true);
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/mark-sent", Map.of()).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = send(owner, ev, v, 1, crid());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_SEND_CLOSED");
        assertThat(onSend(ev)).containsExactly(a);

        // A normal transfer: the only route is the bazar's (registry-checked), and the service refuses it.
        UUID normal = normalTransfer();
        TenantContext.set(tenant);
        try {
            TransferService.CountResult c = transfers.scanOutByCountForBazar(normal, v, 1, ownerId, crid());
            assertThat(c.success()).isFalse();
            assertThat(c.code()).isEqualTo("TRANSFER_NOT_FOUND");
            TransferService.CountResult rm = transfers.removeCountFromSendForBazar(normal, v, 1, ownerId, crid());
            assertThat(rm.code()).isEqualTo("TRANSFER_NOT_FOUND");
        } finally {
            TenantContext.clear();
        }
        assertThat(count("SELECT count(*) FROM transfer_pieces WHERE transfer_id = ?", normal)).isZero();
    }

    @SuppressWarnings("rawtypes")
    private UUID normalTransfer() {
        ResponseEntity<Map> r = call(owner, HttpMethod.POST, "/api/v1/transfers",
            Map.of("transferType", "showroom", "destinationLocationId", side.toString()));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    // ── untagged pieces stay refused everywhere else ────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void u1_everyNonBazarTransferStillRefusesAnUntaggedPiece_scanOutAndSendBack() {
        UUID v = variant("Untagged dress", "S");
        String untagged = piece(v, false, 30);

        UUID normal = normalTransfer();
        ResponseEntity<Map> out = call(owner, HttpMethod.POST, "/api/v1/transfers/" + normal + "/scan-out",
            Map.of("barcode", untagged));
        assertThat(out.getBody().get("success")).isEqualTo(false);
        assertThat(out.getBody().get("code")).isEqualTo("PIECE_UNTAGGED");

        // Send-back (relocate_return) of an untagged piece sitting at the showroom.
        String away = piece(v, false, 25, side, "transferred_out");
        ResponseEntity<Map> created = call(owner, HttpMethod.POST, "/api/v1/transfers", Map.of("transferType", "showroom",
            "destinationLocationId", main.toString(), "transferMode", "relocate_return", "sourceLocationId", side.toString()));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> back = call(owner, HttpMethod.POST,
            "/api/v1/transfers/" + created.getBody().get("id") + "/return-scan-out", Map.of("barcode", away));
        assertThat(back.getBody().get("code")).isEqualTo("PIECE_UNTAGGED");

        // The bazar's own scan path refuses it too — only the count path takes untagged stock.
        String ev = draft();
        assertThat(scan(owner, ev, untagged).getBody().get("code")).isEqualTo("PIECE_UNTAGGED");

        assertThat(status(untagged)).isEqualTo("available");
        assertThat(status(away)).isEqualTo("transferred_out");
        assertThat(count("SELECT count(*) FROM transfer_pieces WHERE piece_id IN (?, ?)", untagged, away)).isZero();
    }

    // ── remove from this send ───────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void r1_removeAScannedPieceByItsCode_isAvailableAtMainAgain() {
        UUID v = variant("Scanned dress", "S");
        String a = piece(v, true, 30), b = piece(v, true, 20);
        String ev = draft();
        scan(owner, ev, code(a));
        scan(owner, ev, code(b));
        assertThat(qtyOut(ev, v)).isEqualTo(2);

        ResponseEntity<Map> r = removeCode(manager, ev, code(a), crid());
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(pieceIds(r)).containsExactly(a);
        assertThat(r.getBody().get("qtyOut")).isEqualTo(1);
        assertThat(status(a)).isEqualTo("available");
        assertThat(location(a)).isEqualTo(main);
        assertThat(onSend(ev)).containsExactly(b);
        assertThat(qtyOut(ev, v)).isEqualTo(1);
        Map<String, Object> e = jdbc.queryForMap("SELECT event_type, from_status::text AS f, to_status::text AS t, " +
            "location_id FROM piece_events WHERE piece_id = ? ORDER BY created_at DESC, id DESC LIMIT 1", a);
        assertThat(e).containsEntry("event_type", "removed_from_transfer").containsEntry("f", "out_on_transfer")
            .containsEntry("t", "available").containsEntry("location_id", main);

        // Not on the send (any more) → refused, nothing moves.
        ResponseEntity<Map> again = removeCode(owner, ev, code(a), crid());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("BAZAR_NOT_ON_SEND");
        // It can be scanned out again.
        assertThat(scan(owner, ev, code(a)).getBody().get("success")).isEqualTo(true);
        assertThat(qtyOut(ev, v)).isEqualTo(2);
        assertNoShopifyClaims(ev);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void r2_removeNOfASize_newestAddedFirst_andAnEmptiedLineIsGone() {
        UUID v = variant("Removed dress", "M");
        String t1 = piece(v, true, 50), u1 = piece(v, false, 40), u2 = piece(v, false, 30);
        String ev = draft();
        scan(owner, ev, code(t1));                       // added first
        send(owner, ev, v, 2, crid());                   // then u1, u2 by count
        assertThat(onSend(ev)).containsExactly(t1, u1, u2);

        ResponseEntity<Map> r = removeCount(owner, ev, v, 2, crid());
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(pieceIds(r)).containsExactlyInAnyOrder(u1, u2);
        assertThat(onSend(ev)).containsExactly(t1);
        assertThat(qtyOut(ev, v)).isEqualTo(1);
        for (String p : List.of(u1, u2)) {
            assertThat(status(p)).isEqualTo("available");
            assertThat(location(p)).isEqualTo(main);
        }
        // More than is on the send → refused (all or nothing), says how many.
        ResponseEntity<Map> tooMany = removeCount(owner, ev, v, 2, crid());
        assertThat(tooMany.getBody().get("code")).isEqualTo("BAZAR_NOT_ON_SEND");
        assertThat(detail(tooMany, "on_send")).isEqualTo(1);
        assertThat(onSend(ev)).containsExactly(t1);

        assertThat(removeCount(owner, ev, v, 1, crid()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(onSend(ev)).isEmpty();
        assertThat(qtyOut(ev, v)).as("the emptied line is deleted, as if never scanned").isNull();
        assertThat(count("SELECT count(*) FROM pieces WHERE variant_id = ? AND status = 'available' AND current_location_id = ?",
            v, main)).isEqualTo(3);
        // Removed stock can be counted out again.
        assertThat(send(owner, ev, v, 3, crid()).getBody().get("count")).isEqualTo(3);
        assertNoShopifyClaims(ev);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void r3_removeIsRetrySafe_byRequestId() {
        UUID v = variant("Retry remove dress", "L");
        for (int i = 0; i < 3; i++) piece(v, false, 30 - i);
        String ev = draft();
        send(owner, ev, v, 3, crid());
        String id = crid();
        ResponseEntity<Map> first = removeCount(owner, ev, v, 1, id);
        ResponseEntity<Map> again = removeCount(owner, ev, v, 1, id);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("replayed")).isEqualTo(true);
        assertThat(pieceIds(again)).isEqualTo(pieceIds(first));
        assertThat(onSend(ev)).hasSize(2);
        assertThat(qtyOut(ev, v)).isEqualTo(2);

        String tagged = piece(v, true, 5);
        scan(owner, ev, code(tagged));
        String byCode = crid();
        removeCode(owner, ev, code(tagged), byCode);
        ResponseEntity<Map> codeAgain = removeCode(owner, ev, code(tagged), byCode);
        assertThat(codeAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(codeAgain.getBody().get("replayed")).isEqualTo(true);
        assertThat(count("SELECT count(*) FROM piece_events WHERE piece_id = ? AND event_type = 'removed_from_transfer'",
            tagged)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void r4_removeIsRefusedOnceSent() {
        UUID v = variant("Gone dress", "S");
        String a = piece(v, true, 30);
        piece(v, false, 20);
        String ev = draft();
        scan(owner, ev, code(a));
        send(owner, ev, v, 1, crid());
        call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/mark-sent", Map.of());
        for (ResponseEntity<Map> r : List.of(removeCode(owner, ev, code(a), crid()), removeCount(owner, ev, v, 1, crid()))) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("code")).isEqualTo("BAZAR_SEND_CLOSED");
        }
        assertThat(onSend(ev)).hasSize(2);
        assertThat(status(a)).isEqualTo("out_on_transfer");
        // Exactly one of a code / a size.
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/remove-from-send",
            Map.of("code", code(a), "variant_id", v.toString(), "quantity", 1, "client_request_id", crid()))
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── stock at Main for the panel ─────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void s1_sendStock_countsLabelledAndUntaggedAtMain_andCommittedLikeInventory() {
        UUID v = variant("Stock dress", "M");
        piece(v, true, 30);
        piece(v, false, 20);
        piece(v, false, 10);
        piece(v, true, 5, side, "available");     // not at main
        piece(v, true, 4, main, "on_hold");       // not available
        UUID order = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, status, is_self_pickup) " +
            "VALUES (?, ?, ?, 'new', true) RETURNING id", UUID.class, tenant, storeId, "ORD-" + UUID.randomUUID());
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 2)", tenant, order, v);
        String ev = draft();

        ResponseEntity<Map> r = call(manager, HttpMethod.GET, "/api/v1/bazar-events/" + ev + "/send-stock", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map size = ((List<Map>) r.getBody().get("products")).stream()
            .flatMap(p -> ((List<Map>) p.get("sizes")).stream())
            .filter(s -> v.toString().equals(s.get("variant_id"))).findFirst().orElseThrow();
        assertThat(size.get("left")).as("labelled + untagged available at Main").isEqualTo(3);
        assertThat(((Number) size.get("committed")).intValue()).isEqualTo(2);
        // The same committed number Inventory shows (VariantStockService — Inventory's own rule).
        TenantContext.set(tenant);
        try {
            assertThat(stockService.forVariant(stockService.computeAll(), v).committed()).isEqualTo(2);
        } finally {
            TenantContext.clear();
        }

        // Counted out → no longer at Main.
        send(owner, ev, v, 3, crid());
        Map after = ((List<Map>) call(owner, HttpMethod.GET, "/api/v1/bazar-events/" + ev + "/send-stock", null)
            .getBody().get("products")).stream().flatMap(p -> ((List<Map>) p.get("sizes")).stream())
            .filter(s -> v.toString().equals(s.get("variant_id"))).findFirst().orElse(null);
        assertThat(after).as("nothing left at Main for this size").isNull();
    }

    // ── who may use it ──────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void p1_ownerOrManagerOnly_anotherTenant404_flagOff404() {
        UUID v = variant("Guard dress", "S");
        String a = piece(v, true, 30);
        String ev = draft();
        scan(owner, ev, code(a));
        assertThat(send(worker, ev, v, 1, crid()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(removeCode(worker, ev, code(a), crid()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(worker, HttpMethod.GET, "/api/v1/bazar-events/" + ev + "/send-stock", null).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        for (String token : List.of(ownerOther, ownerOff)) {
            assertThat(send(token, ev, v, 1, crid()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(removeCode(token, ev, code(a), crid()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(call(token, HttpMethod.GET, "/api/v1/bazar-events/" + ev + "/send-stock", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(onSend(ev)).containsExactly(a);
        assertThat(status(a)).isEqualTo("out_on_transfer");
    }

    // ── RLS ─────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_seesOnlyItsOwnPiecesAndTransferRows_andMayDeleteAnUnsentClaim() {
        UUID v = variant("RLS dress", "S");
        piece(v, false, 20);
        String ev = draft();
        send(owner, ev, v, 1, crid());
        TenantAwareDataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        String pieces = "SELECT COUNT(*) FROM pieces WHERE variant_id = ?";
        String claims = "SELECT COUNT(*) FROM transfer_pieces WHERE transfer_id = ?";
        try {
            TenantContext.set(otherTenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(pieces, Integer.class, v))).isZero();
            assertThat((Integer) tx.execute(s -> app.queryForObject(claims, Integer.class, transferOf(ev)))).isZero();
            TenantContext.clear();
            assertThat((Integer) tx.execute(s -> app.queryForObject(pieces, Integer.class, v))).as("no tenant").isZero();
            TenantContext.set(tenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(pieces, Integer.class, v))).isEqualTo(1);
            assertThat((Integer) tx.execute(s -> app.queryForObject(claims, Integer.class, transferOf(ev)))).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
        // Remove deletes the unsent claim and an emptied line — app_user (prod) must be allowed to.
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('app_user', 'transfer_pieces', 'DELETE') " +
            "AND has_table_privilege('app_user', 'transfer_lines', 'DELETE')", Boolean.class)).isTrue();
    }
}
