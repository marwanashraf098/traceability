package com.traceability.places;

import com.traceability.TestPostgres;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.StockTakeService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * Places rollout (2026-11-01) — the merchant switch and "dormant until set up", over HTTP (real security chain).
 *
 *   sw1  owner and manager switch places on and off (PUT /places/settings), each change one audit_log row (who, from →
 *        to); a repeat changes nothing; a worker gets 403 (GET and PUT); no body → 400
 *   sw2  /me: off → places false and no placesDormant key (unchanged); on with no shelf or box → placesDormant true;
 *        the first shelf → false
 *   d1   on, nothing set up (only a zone): pick places 404 (variants, orders, one order), Lookup has no place key, a
 *        stock-take sticker is an unknown scan — exactly as with places off
 *   d2   after one shelf at the main warehouse: pick places answer, Lookup shows the place, the sticker sets the
 *        counting place
 *   d3   per location: the showroom set up, the main warehouse not → /me not dormant, but pick places (main) 404 and a
 *        main-warehouse piece has no place in Lookup; a showroom piece has its place
 *   off  switching off after use keeps bins and every piece's place; closes open put-away sessions
 *        ('closed_by_manager' by the switcher), cancels open place counts (reason places_off), clears a stock take's
 *        counting place; the place endpoints 404; switching on again brings it all back
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlacesRolloutTest {

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
    @Autowired StockTakeService stockTake;
    @Autowired PutAwayService   putAway;
    @Autowired PlaceCountService counts;

    record Shop(UUID tenant, UUID main, UUID showroom, UUID store, UUID variant, Map<String, UUID> users, Map<String, String> tokens) {}

    static final AtomicInteger SEQ = new AtomicInteger(980_000);

    @Test
    void sw1_ownerAndManagerSwitch_auditLogged_workerForbidden() {
        Shop s = shop("sw1", false);
        ResponseEntity<String> on = call(HttpMethod.PUT, "/api/v1/places/settings", s.tokens().get("owner"), Map.of("enabled", true));
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody()).contains("\"enabled\":true").contains("\"setUp\":false");
        assertThat(enabled(s)).isTrue();
        assertThat(call(HttpMethod.PUT, "/api/v1/places/settings", s.tokens().get("owner"), Map.of("enabled", true))
            .getStatusCode()).as("a repeat").isEqualTo(HttpStatus.OK);

        ResponseEntity<String> off = call(HttpMethod.PUT, "/api/v1/places/settings", s.tokens().get("manager"), Map.of("enabled", false));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody()).contains("\"enabled\":false");
        assertThat(enabled(s)).isFalse();
        assertThat(call(HttpMethod.GET, "/api/v1/places/settings", s.tokens().get("manager"), null).getBody())
            .contains("\"enabled\":false");

        List<Map<String, Object>> audit = jdbc.queryForList(
            "SELECT actor_user_id, metadata->>'from' AS f, metadata->>'to' AS t FROM audit_log " +
            "WHERE tenant_id = ? AND action = 'places_switch' ORDER BY created_at, id", s.tenant());
        assertThat(audit).as("one row per change — the repeat wrote none").hasSize(2);
        assertThat(audit.get(0)).containsEntry("actor_user_id", s.users().get("owner")).containsEntry("f", "false").containsEntry("t", "true");
        assertThat(audit.get(1)).containsEntry("actor_user_id", s.users().get("manager")).containsEntry("f", "true").containsEntry("t", "false");

        String worker = s.tokens().get("worker");
        assertThat(call(HttpMethod.GET, "/api/v1/places/settings", worker, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.PUT, "/api/v1/places/settings", worker, Map.of("enabled", true)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(enabled(s)).isFalse();
        assertThat(call(HttpMethod.PUT, "/api/v1/places/settings", s.tokens().get("owner"), Map.of()).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sw2_me_placesDormant() {
        Shop s = shop("sw2", false);
        String owner = s.tokens().get("owner");
        String me = call(HttpMethod.GET, "/api/v1/me", owner, null).getBody();
        assertThat(me).contains("\"places\":false").doesNotContain("placesDormant");

        call(HttpMethod.PUT, "/api/v1/places/settings", owner, Map.of("enabled", true));
        UUID zone = bin(s, s.main(), "zone", null, null, "RO-Z");
        assertThat(call(HttpMethod.GET, "/api/v1/me", owner, null).getBody())
            .contains("\"places\":true").contains("\"placesDormant\":true");
        UUID rack = bin(s, s.main(), "rack", zone, "zone", "RO-Z-R01");
        UUID shelf = bin(s, s.main(), "shelf", rack, "rack", "RO-Z-R01-S1");
        assertThat(call(HttpMethod.GET, "/api/v1/me", s.tokens().get("worker"), null).getBody())
            .contains("\"places\":true").contains("\"placesDormant\":false");
        jdbc.update("UPDATE bins SET archived_at = now() WHERE id = ?", shelf);
        assertThat(call(HttpMethod.GET, "/api/v1/me", owner, null).getBody())
            .as("an archived shelf doesn't count").contains("\"placesDormant\":true");
    }

    @Test
    void d1_onButNothingSetUp_everyPlaceFeatureActsAsOff() {
        Shop s = shop("d1", true);
        UUID zone = bin(s, s.main(), "zone", null, null, "D1-Z");
        String piece = piece(s, s.main(), null);
        String owner = s.tokens().get("owner");

        assertThat(call(HttpMethod.POST, "/api/v1/fulfill/places/variants", owner, Map.of("variantIds", List.of(s.variant())))
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/api/v1/fulfill/places/orders", owner, Map.of("orderIds", List.of(UUID.randomUUID())))
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/fulfill/" + UUID.randomUUID() + "/places", owner, null).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> lookup = call(HttpMethod.GET, "/api/v1/lookup?q=" + code(piece), owner, null);
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookup.getBody()).doesNotContain("\"place\"").doesNotContain("canPlace");
        assertThat(call(HttpMethod.GET, "/api/v1/lookup?q=" + scanCode(zone), owner, null).getBody())
            .as("a zone sticker of a location that isn't set up is not a place in Lookup").doesNotContain("\"type\":\"place\"");

        UUID session = openStockTake(s);
        Map<String, Object> scan = TenantContext.runAs(s.tenant(),
            () -> stockTake.scan(session, scanCode(zone), "good", s.users().get("owner"), false));
        assertThat(scan.get("classification")).isEqualTo("unknown");
        assertThat(jdbc.queryForObject("SELECT current_bin_id FROM stock_take_sessions WHERE id = ?", UUID.class, session)).isNull();
    }

    @Test
    void d2_afterOneShelf_theFeaturesAppear() {
        Shop s = shop("d2", true);
        String owner = s.tokens().get("owner");
        ResponseEntity<String> zone = call(HttpMethod.POST, "/api/v1/places", owner, Map.of("kind", "zone"));
        assertThat(zone.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(call(HttpMethod.POST, "/api/v1/fulfill/places/variants", owner, Map.of("variantIds", List.of(s.variant())))
            .getStatusCode()).as("a zone alone isn't set up").isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> bulk = call(HttpMethod.POST, "/api/v1/places/bulk", owner,
            Map.of("locationId", s.main(), "racks", 1, "shelvesPerRack", 1, "floorBoxes", 0));
        assertThat(bulk.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);
        UUID shelf = jdbc.queryForObject("SELECT id FROM bins WHERE tenant_id = ? AND kind = 'shelf'", UUID.class, s.tenant());
        String piece = piece(s, s.main(), shelf);

        assertThat(call(HttpMethod.GET, "/api/v1/me", owner, null).getBody()).contains("\"placesDormant\":false");
        ResponseEntity<String> variants = call(HttpMethod.POST, "/api/v1/fulfill/places/variants", owner,
            Map.of("variantIds", List.of(s.variant())));
        assertThat(variants.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(variants.getBody()).contains(shelf.toString());
        assertThat(call(HttpMethod.GET, "/api/v1/lookup?q=" + code(piece), owner, null).getBody())
            .contains("\"place\":{").contains("\"canPlace\":true");

        UUID session = openStockTake(s);
        Map<String, Object> scan = TenantContext.runAs(s.tenant(),
            () -> stockTake.scan(session, scanCode(shelf), "good", s.users().get("owner"), false));
        assertThat(scan.get("classification")).isEqualTo("place_set");
    }

    @Test
    void d3_perLocation_showroomSetUp_mainNot() {
        Shop s = shop("d3", true);
        UUID oz = bin(s, s.showroom(), "zone", null, null, "D3-OZ");
        UUID or = bin(s, s.showroom(), "rack", oz, "zone", "D3-OZ-R01");
        UUID os = bin(s, s.showroom(), "shelf", or, "rack", "D3-OZ-R01-S1");
        bin(s, s.main(), "zone", null, null, "D3-Z");
        String atMain = piece(s, s.main(), null);
        String atShowroom = piece(s, s.showroom(), os);
        String owner = s.tokens().get("owner");

        assertThat(call(HttpMethod.GET, "/api/v1/me", owner, null).getBody()).contains("\"placesDormant\":false");
        assertThat(call(HttpMethod.POST, "/api/v1/fulfill/places/variants", owner, Map.of("variantIds", List.of(s.variant())))
            .getStatusCode()).as("pick places count the main warehouse, which isn't set up").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/lookup?q=" + code(atMain), owner, null).getBody()).doesNotContain("\"place\"");
        assertThat(call(HttpMethod.GET, "/api/v1/lookup?q=" + code(atShowroom), owner, null).getBody()).contains("\"place\":{");
        assertThat(call(HttpMethod.GET, "/api/v1/lookup?q=" + scanCode(os), owner, null).getBody()).contains("\"type\":\"place\"");
    }

    @Test
    void off_afterUse_keepsPlaces_endsOpenWork_andComesBack() {
        Shop s = shop("off", true);
        UUID zone = bin(s, s.main(), "zone", null, null, "OF-Z");
        UUID rack = bin(s, s.main(), "rack", zone, "zone", "OF-Z-R01");
        UUID s1 = bin(s, s.main(), "shelf", rack, "rack", "OF-Z-R01-S1");
        UUID s2 = bin(s, s.main(), "shelf", rack, "rack", "OF-Z-R01-S2");
        String placed = piece(s, s.main(), s1);
        String waiting = piece(s, s.main(), null);
        UUID worker = s.users().get("worker");

        // Open work: a worker's put-away session that already placed a piece, an open count, a stock take on a place.
        PutAwayService.Session session = TenantContext.runAs(s.tenant(), () -> putAway.start(worker, s.main()));
        TenantContext.runAs(s.tenant(), () -> putAway.scan(session.id(), scanCode(s2), worker, false));
        TenantContext.runAs(s.tenant(), () -> putAway.scan(session.id(), code(waiting), worker, false));
        assertThat(bin(waiting)).isEqualTo(s2);
        PlaceCountService.Count count = TenantContext.runAs(s.tenant(), () -> counts.start(worker, s1, null));
        UUID stock = openStockTake(s);
        TenantContext.runAs(s.tenant(), () -> stockTake.scan(stock, scanCode(s1), "good", worker, false));
        assertThat(jdbc.queryForObject("SELECT current_bin_id FROM stock_take_sessions WHERE id = ?", UUID.class, stock)).isEqualTo(s1);
        int binsBefore = jdbc.queryForObject("SELECT count(*)::int FROM bins WHERE tenant_id = ?", Integer.class, s.tenant());

        ResponseEntity<String> off = call(HttpMethod.PUT, "/api/v1/places/settings", s.tokens().get("manager"), Map.of("enabled", false));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID manager = s.users().get("manager");

        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM bins WHERE tenant_id = ? AND archived_at IS NULL", Integer.class, s.tenant()))
            .as("nothing deleted or archived").isEqualTo(binsBefore);
        assertThat(bin(placed)).isEqualTo(s1);
        assertThat(bin(waiting)).as("placed during the session, stays placed").isEqualTo(s2);
        Map<String, Object> closed = jdbc.queryForMap(
            "SELECT status, close_reason, closed_by FROM put_away_sessions WHERE id = ?", session.id());
        assertThat(closed).containsEntry("status", "closed").containsEntry("close_reason", "closed_by_manager")
            .containsEntry("closed_by", manager);
        Map<String, Object> cancelled = jdbc.queryForMap(
            "SELECT status, finished_by, summary->>'reason' AS reason FROM place_counts WHERE id = ?", count.id());
        assertThat(cancelled).containsEntry("status", "cancelled").containsEntry("finished_by", manager)
            .containsEntry("reason", "places_off");
        assertThat(jdbc.queryForObject("SELECT current_bin_id FROM stock_take_sessions WHERE id = ?", UUID.class, stock)).isNull();
        assertThat(jdbc.queryForObject(
            "SELECT metadata->>'putAwaySessionsClosed' || '/' || (metadata->>'placeCountsCancelled') || '/' || " +
            "(metadata->>'stockTakesCleared') FROM audit_log WHERE tenant_id = ? AND action = 'places_switch'",
            String.class, s.tenant())).isEqualTo("1/1/1");

        String owner = s.tokens().get("owner");
        assertThat(call(HttpMethod.GET, "/api/v1/me", owner, null).getBody()).contains("\"places\":false").doesNotContain("placesDormant");
        assertThat(call(HttpMethod.GET, "/api/v1/places", owner, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/put-away/summary", s.tokens().get("worker"), null).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/lookup?q=" + code(placed), owner, null).getBody()).doesNotContain("\"place\"");
        Map<String, Object> st = TenantContext.runAs(s.tenant(), () -> stockTake.scan(stock, scanCode(s2), "good", worker, false));
        assertThat(st.get("classification")).as("a sticker is an unknown scan again").isEqualTo("unknown");

        assertThat(call(HttpMethod.PUT, "/api/v1/places/settings", owner, Map.of("enabled", true)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/lookup?q=" + code(placed), owner, null).getBody())
            .as("back on: the piece's place is still there").contains("\"place\":{").contains("OF-Z-R01-S1");
        assertThat(call(HttpMethod.GET, "/api/v1/places", owner, null).getBody()).contains("OF-Z-R01-S2");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Shop shop(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), main = UUID.randomUUID(), showroom = UUID.randomUUID();
        String t = name + "-" + tenant.toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "Rollout " + name, placesOn);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main', true, true)", main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Showroom')", showroom, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "ro-" + t + ".myshopify.com");
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + t);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            variant, tenant, product, "V-" + t, "RO-" + t);
        Map<String, UUID> users = new java.util.HashMap<>();
        Map<String, String> tokens = new java.util.HashMap<>();
        for (String role : List.of("owner", "manager", "worker")) {
            UUID id = UUID.randomUUID();
            String email = role + "-ro-" + id + "@test.local";
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                "VALUES (?, ?, ?, ?, ?, ?::user_role, true)", id, tenant, role, email, passwordEncoder.encode("pass123"), role);
            users.put(role, id);
            tokens.put(role, login(email));
        }
        return new Shop(tenant, main, showroom, store, variant, users, tokens);
    }

    private UUID bin(Shop s, UUID location, String kind, UUID parent, String parentKind, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, s.tenant(), location, kind, parent, parentKind, code + "-" + id.toString().substring(0, 4),
            "Q" + SEQ.incrementAndGet());
        return id;
    }

    private String piece(Shop s, UUID location, UUID bin) {
        String id = "01PRO" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, bin_id) " +
            "VALUES (?, ?, ?, 'available', ?, ?, ?, ?)", id, s.tenant(), s.variant(), "PC-" + id, "P" + SEQ.incrementAndGet(), location, bin);
        return id;
    }

    private UUID openStockTake(Shop s) {
        Map<String, Object> open = TenantContext.runAs(s.tenant(),
            () -> stockTake.openSession("all", null, s.main(), null, s.users().get("owner")));
        return (UUID) open.get("sessionId");
    }

    private boolean enabled(Shop s) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT places_enabled FROM tenants WHERE id = ?", Boolean.class, s.tenant()));
    }

    private UUID bin(String pieceId) {
        return jdbc.queryForObject("SELECT bin_id FROM pieces WHERE id = ?", UUID.class, pieceId);
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String scanCode(UUID binId) {
        return jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, binId);
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity("http://localhost:" + port + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, h), String.class);
    }
}
