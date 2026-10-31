package com.traceability.places;

import com.traceability.TestPostgres;
import com.traceability.identity.model.AccessTokenResponse;
import org.junit.jupiter.api.BeforeAll;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warehouse places S5 over HTTP (real security filter chain).
 *
 *   r1  every role (worker included) counts a place (start by sticker, scan, finish), reads "Where is it" and moves a
 *       box by scanning
 *   r2  capability off → 404 for every role on every new endpoint, and for the Inventory place filter
 *   i1  Inventory › Stock place filter: a shelf (its boxes excluded), a box, a rack / zone (everything under it),
 *       "No place yet"; a place without a location → 400; another location's place → 404
 *   i2  Inventory /pieces with the same filter
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlacesS5HttpTest {

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

    /** zone › R01 › S1 (BOX-1 on it), zone › R02 › S2, a floor box; pieces: A on S1, B in BOX-1, C no place, D on S2. */
    record Shop(UUID tenant, UUID main, UUID other, UUID zone, UUID r01, UUID s1, UUID box, UUID s2, UUID floorBox,
                UUID otherShelf, UUID variantA, String base, Map<String, String> tokens) {}
    Shop on, off;

    @BeforeAll
    void setup() {
        on = shop("on", true, 960_000);
        off = shop("off", false, 970_000);
    }

    @Test
    void r1_everyRole_countsAPlace_readsWhereIsIt_movesABox() {
        for (String role : List.of("worker", "manager", "owner")) {
            String token = on.tokens().get(role);
            ResponseEntity<String> start = call(HttpMethod.POST, "/api/v1/place-counts", token,
                Map.of("scan", scanCode(on.s1())));
            assertThat(start.getStatusCode()).as(role + " start").isEqualTo(HttpStatus.OK);
            String countId = start.getBody().replaceAll("^\\{\"id\":\"([0-9a-f-]+)\".*", "$1");
            ResponseEntity<String> scan = call(HttpMethod.POST, "/api/v1/place-counts/" + countId + "/scan", token,
                Map.of("code", on.base() + "1"));
            assertThat(scan.getStatusCode()).as(role + " scan").isEqualTo(HttpStatus.OK);
            assertThat(scan.getBody()).contains("\"outcome\":\"FOUND_HERE\"");
            assertThat(call(HttpMethod.GET, "/api/v1/place-counts/" + countId, token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
            ResponseEntity<String> finish = call(HttpMethod.POST, "/api/v1/place-counts/" + countId + "/finish", token, null);
            assertThat(finish.getStatusCode()).as(role + " finish").isEqualTo(HttpStatus.OK);
            assertThat(finish.getBody()).contains("\"status\":\"finished\"");

            ResponseEntity<String> where = call(HttpMethod.GET, "/api/v1/places/where/" + on.variantA(), token, null);
            assertThat(where.getStatusCode()).as(role + " where").isEqualTo(HttpStatus.OK);
            assertThat(where.getBody()).contains("S5H-Z-R01-S1");

            assertThat(call(HttpMethod.POST, "/api/v1/put-away/box-moves/box", token, Map.of("box", scanCode(on.floorBox())))
                .getStatusCode()).as(role + " box check").isEqualTo(HttpStatus.OK);
            UUID target = "manager".equals(role) ? on.s2() : on.s1();
            ResponseEntity<String> move = call(HttpMethod.POST, "/api/v1/put-away/box-moves", token,
                Map.of("box", scanCode(on.floorBox()), "target", scanCode(target)));
            assertThat(move.getStatusCode()).as(role + " move").isEqualTo(HttpStatus.OK);
        }
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM bin_events WHERE bin_id = ? AND event_type = 'moved'",
            Integer.class, on.floorBox())).as("worker → S1, manager → S2, owner → S1").isEqualTo(3);
    }

    @Test
    void r2_capabilityOff_404ForEveryRoleOnEveryEndpoint() {
        UUID anyCount = UUID.randomUUID();
        for (String role : List.of("worker", "manager", "owner")) {
            String token = off.tokens().get(role);
            List<ResponseEntity<String>> answers = List.of(
                call(HttpMethod.POST, "/api/v1/place-counts", token, Map.of("binId", off.s1())),
                call(HttpMethod.GET, "/api/v1/place-counts/" + anyCount, token, null),
                call(HttpMethod.POST, "/api/v1/place-counts/" + anyCount + "/scan", token, Map.of("code", "P1")),
                call(HttpMethod.POST, "/api/v1/place-counts/" + anyCount + "/finish", token, null),
                call(HttpMethod.POST, "/api/v1/place-counts/" + anyCount + "/cancel", token, null),
                call(HttpMethod.GET, "/api/v1/places/where/" + off.variantA(), token, null),
                call(HttpMethod.POST, "/api/v1/put-away/box-moves/box", token, Map.of("box", scanCode(off.floorBox()))),
                call(HttpMethod.POST, "/api/v1/put-away/box-moves", token,
                    Map.of("box", scanCode(off.floorBox()), "target", scanCode(off.s1()))));
            for (ResponseEntity<String> a : answers) assertThat(a.getStatusCode()).as(role).isEqualTo(HttpStatus.NOT_FOUND);
        }
        String owner = off.tokens().get("owner");
        assertThat(call(HttpMethod.GET, "/api/v1/inventory/stock?locationId=" + off.main() + "&placeId=" + off.s1(), owner, null)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/inventory/pieces?status=available&locationId=" + off.main() + "&noPlace=true",
            owner, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/v1/inventory/stock?locationId=" + off.main(), owner, null).getStatusCode())
            .as("no filter: the Stock tab works as before").isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM place_counts WHERE tenant_id = ?", Integer.class, off.tenant()))
            .isZero();
    }

    @Test
    void i1_inventoryStock_placeFilter() {
        String owner = on.tokens().get("owner");
        String base = "/api/v1/inventory/stock?locationId=" + on.main();
        assertThat(skus(base + "&placeId=" + on.s1(), owner)).as("a shelf: its own pieces, not its boxes'")
            .contains("S5H-A").doesNotContain("S5H-B", "S5H-C", "S5H-D");
        assertThat(skus(base + "&placeId=" + on.box(), owner)).contains("S5H-B").doesNotContain("S5H-A", "S5H-C", "S5H-D");
        assertThat(skus(base + "&placeId=" + on.r01(), owner)).as("a rack: everything under it")
            .contains("S5H-A", "S5H-B").doesNotContain("S5H-C", "S5H-D");
        assertThat(skus(base + "&placeId=" + on.zone(), owner)).as("a zone: everything under it")
            .contains("S5H-A", "S5H-B", "S5H-D").doesNotContain("S5H-C");
        assertThat(skus(base + "&noPlace=true", owner)).as("No place yet").contains("S5H-C").doesNotContain("S5H-A", "S5H-B", "S5H-D");
        assertThat(skus(base, owner)).as("no filter: every product").contains("S5H-A", "S5H-B", "S5H-C", "S5H-D");
        assertThat(call(HttpMethod.GET, "/api/v1/inventory/stock?placeId=" + on.s1(), owner, null).getStatusCode())
            .as("a place needs a location").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.GET, base + "&placeId=" + on.otherShelf(), owner, null).getStatusCode())
            .as("another location's place").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void i2_inventoryPieces_placeFilter() {
        String owner = on.tokens().get("owner");
        String base = "/api/v1/inventory/pieces?status=available&locationId=" + on.main();
        assertThat(call(HttpMethod.GET, base + "&placeId=" + on.s1(), owner, null).getBody())
            .contains("S5H-A").doesNotContain("S5H-B", "S5H-C", "S5H-D");
        assertThat(call(HttpMethod.GET, base + "&placeId=" + on.zone(), owner, null).getBody())
            .contains("S5H-A", "S5H-B", "S5H-D").doesNotContain("S5H-C");
        assertThat(call(HttpMethod.GET, base + "&noPlace=true", owner, null).getBody())
            .contains("S5H-C").doesNotContain("S5H-A", "S5H-B", "S5H-D");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String skus(String path, String token) {
        ResponseEntity<String> r = call(HttpMethod.GET, path, token, null);
        assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private Shop shop(String name, boolean placesOn, int base) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), main = UUID.randomUUID(), other = UUID.randomUUID();
        String t = name + "-" + tenant.toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "PlacesS5 HTTP " + name, placesOn);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main', true, true)", main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Showroom')", other, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "s5h-" + t + ".myshopify.com");
        UUID zone = bin(tenant, main, "zone", null, null, "S5H-Z", base + 1);
        UUID r01 = bin(tenant, main, "rack", zone, "zone", "S5H-Z-R01", base + 2);
        UUID s1 = bin(tenant, main, "shelf", r01, "rack", "S5H-Z-R01-S1", base + 3);
        UUID box = bin(tenant, main, "box", s1, "shelf", "S5H-BOX-1", base + 4);
        UUID r02 = bin(tenant, main, "rack", zone, "zone", "S5H-Z-R02", base + 5);
        UUID s2 = bin(tenant, main, "shelf", r02, "rack", "S5H-Z-R02-S2", base + 6);
        UUID floorBox = bin(tenant, main, "box", zone, "zone", "S5H-BOX-F", base + 7);
        UUID oz = bin(tenant, other, "zone", null, null, "S5H-OZ", base + 8);
        UUID or = bin(tenant, other, "rack", oz, "zone", "S5H-OZ-R01", base + 9);
        UUID os = bin(tenant, other, "shelf", or, "rack", "S5H-OZ-R01-S1", base + 10);
        UUID a = variant(tenant, store, t, "A");
        piece(tenant, a, main, s1, "P" + base + "1");
        piece(tenant, variant(tenant, store, t, "B"), main, box, "P" + base + "2");
        piece(tenant, variant(tenant, store, t, "C"), main, null, "P" + base + "3");
        piece(tenant, variant(tenant, store, t, "D"), main, s2, "P" + base + "4");
        Map<String, String> tokens = Map.of(
            "owner", login(user(tenant, "owner")), "manager", login(user(tenant, "manager")), "worker", login(user(tenant, "worker")));
        return new Shop(tenant, main, other, zone, r01, s1, box, s2, floorBox, os, a, "P" + base, tokens);
    }

    private UUID bin(UUID tenant, UUID location, String kind, UUID parent, String parentKind, String code, int scan) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, tenant, location, kind, parent, parentKind, code, "Q" + scan);
        return id;
    }

    private UUID variant(UUID tenant, UUID store, String t, String letter) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            product, tenant, store, "P-" + letter + "-" + t, "Product " + letter);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            variant, tenant, product, "V-" + letter + "-" + t, "S5H-" + letter);
        return variant;
    }

    private void piece(UUID tenant, UUID variant, UUID location, UUID bin, String shortCode) {
        String id = "01P5H" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, bin_id) " +
            "VALUES (?, ?, ?, 'available', ?, ?, ?, ?)", id, tenant, variant, "PC-" + id, shortCode, location, bin);
    }

    private String scanCode(UUID binId) {
        return jdbc.queryForObject("SELECT scan_code FROM bins WHERE id = ?", String.class, binId);
    }

    private String user(UUID tenantId, String role) {
        UUID id = UUID.randomUUID();
        String email = role + "-s5h-" + id + "@test.local";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, ?, ?, ?::user_role, true)", id, tenantId, role, email, passwordEncoder.encode("pass123"), role);
        return email;
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
