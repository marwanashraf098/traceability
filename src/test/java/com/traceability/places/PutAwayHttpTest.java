package com.traceability.places;

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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Warehouse places S2 — roles and the capability over HTTP.
 *
 *   r1  a worker can use Put away end to end (summary, waiting list, places, start, scan, close) and Lookup's Move
 *   r2  Settings › Places stays owner + manager: a worker gets 403, a manager 200
 *   r3  capability off → every put-away endpoint 404 (owner), and the Places tab too
 *   r4  open sessions: a worker can't list them or close someone else's (403 / 404); a manager lists and closes it
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PutAwayHttpTest {

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
    @Autowired PasswordEncoder  passwordEncoder;

    UUID tenant, offTenant, main, shelf, box;
    String owner, manager, worker, offOwner;
    String piece1, piece2;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        offTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, 'PutAway HTTP', true)", tenant);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, 'PutAway HTTP off', false)", offTenant);
        owner = login(user(tenant, "owner"));
        manager = login(user(tenant, "manager"));
        worker = login(user(tenant, "worker"));
        offOwner = login(user(offTenant, "owner"));

        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        main = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "pah-" + tenant.toString().substring(0, 8) + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-PAH', 'Tee', 'active')",
            product, tenant, store);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-PAH', 'M', 'PAH-1')",
            variant, tenant, product);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true, true)",
            main, tenant);
        UUID zone = UUID.randomUUID(), rack = UUID.randomUUID();
        shelf = UUID.randomUUID();
        box = UUID.randomUUID();
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, code, scan_code) VALUES (?, ?, ?, 'zone', 'Z1', 'Q800001')",
            zone, tenant, main);
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, 'rack', ?, 'zone', 'Z1-R01', 'Q800002')", rack, tenant, main, zone);
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, 'shelf', ?, 'rack', 'Z1-R01-S1', 'Q800003')", shelf, tenant, main, rack);
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, 'box', ?, 'zone', 'BOX-01', 'Q800004')", box, tenant, main, zone);
        piece1 = "01PAHTTP0000000000000000P1";
        piece2 = "01PAHTTP0000000000000000P2";
        for (String[] p : new String[][] {{piece1, "P800001"}, {piece2, "P800002"}}) {
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                "VALUES (?, ?, ?, 'available', ?, ?, ?)", p[0], tenant, variant, "PC-" + p[0], p[1], main);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void r1_worker_putAwayEndToEnd_andMove() {
        ResponseEntity<Map> summary = call(HttpMethod.GET, "/api/v1/put-away/summary", worker, null);
        assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(summary.getBody().get("waiting")).isEqualTo(2);
        assertThat(call(HttpMethod.GET, "/api/v1/put-away/waiting", worker, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(worker);
        ResponseEntity<String> places = rest.exchange("http://localhost:" + port + "/api/v1/put-away/places", HttpMethod.GET,
            new HttpEntity<>(auth), String.class);
        assertThat(places.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(places.getBody()).contains("Z1-R01-S1").contains("BOX-01").doesNotContain("\"kind\":\"rack\"").doesNotContain("\"kind\":\"zone\"");

        ResponseEntity<Map> start = call(HttpMethod.POST, "/api/v1/put-away/sessions", worker, Map.of());
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.OK);
        String id = (String) start.getBody().get("id");
        assertThat(call(HttpMethod.POST, "/api/v1/put-away/sessions/" + id + "/scan", worker, Map.of("code", "Q800003"))
            .getBody().get("outcome")).isEqualTo("PLACE_SET");
        assertThat(call(HttpMethod.POST, "/api/v1/put-away/sessions/" + id + "/scan", worker, Map.of("code", "P800001"))
            .getBody().get("outcome")).isEqualTo("PLACED");
        assertThat(call(HttpMethod.GET, "/api/v1/put-away/sessions/" + id, worker, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/put-away/sessions/" + id, owner, null).getStatusCode())
            .as("someone else's session").isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> close = call(HttpMethod.POST, "/api/v1/put-away/sessions/" + id + "/close", worker, null);
        assertThat(close.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<String, Object>) close.getBody().get("session")).get("placed")).isEqualTo(1);

        ResponseEntity<Map> move = call(HttpMethod.PUT, "/api/v1/put-away/pieces/" + piece2 + "/place", worker,
            Map.of("scan", "q800004"));
        assertThat(move.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(move.getBody().get("kind")).isEqualTo("PUT_AWAY");
        ResponseEntity<Map> refused = call(HttpMethod.PUT, "/api/v1/put-away/pieces/" + piece2 + "/place", worker,
            Map.of("scan", "Q800001"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("NOT_A_SHELF_OR_BOX");
        assertThat(refused.getBody().get("message_ar")).isNotNull();
        ResponseEntity<Map> clear = call(HttpMethod.PUT, "/api/v1/put-away/pieces/" + piece2 + "/place", worker, Map.of());
        assertThat(clear.getBody().get("kind")).isEqualTo("CLEARED");
        assertThat(jdbc.queryForObject("SELECT bin_id FROM pieces WHERE id = ?", UUID.class, piece2)).isNull();

        ResponseEntity<Map> lookup = call(HttpMethod.GET, "/api/v1/lookup?q=Q800003", worker, null);
        assertThat(lookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lookup.getBody()).containsEntry("type", "place").containsEntry("code", "Z1-R01-S1");
    }

    @Test
    void r2_placesTab_ownerAndManagerOnly() {
        assertThat(call(HttpMethod.GET, "/api/v1/places", worker, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/v1/places", manager, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(HttpMethod.GET, "/api/v1/places", owner, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void r4_openSessions_managerClosesAnyone_workerCannot() {
        String worker2 = login(user(tenant, "worker"));
        ResponseEntity<Map> start = call(HttpMethod.POST, "/api/v1/put-away/sessions", worker, Map.of());
        String id = (String) start.getBody().get("id");
        assertThat(call(HttpMethod.POST, "/api/v1/put-away/sessions/" + id + "/close", worker2, null).getStatusCode())
            .as("a worker can't close someone else's session").isEqualTo(HttpStatus.NOT_FOUND);
        HttpHeaders w = new HttpHeaders();
        w.setBearerAuth(worker);
        assertThat(rest.exchange("http://localhost:" + port + "/api/v1/put-away/sessions", HttpMethod.GET,
            new HttpEntity<>(w), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders m = new HttpHeaders();
        m.setBearerAuth(manager);
        ResponseEntity<String> list = rest.exchange("http://localhost:" + port + "/api/v1/put-away/sessions", HttpMethod.GET,
            new HttpEntity<>(m), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).contains(id);
        ResponseEntity<Map> closed = call(HttpMethod.POST, "/api/v1/put-away/sessions/" + id + "/close", manager, null);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<String, Object>) closed.getBody().get("session")).get("closeReason")).isEqualTo("closed_by_manager");
    }

    @Test
    void r3_capabilityOff_every404() {
        String s = UUID.randomUUID().toString();
        for (Object[] c : new Object[][] {
                {HttpMethod.GET, "/api/v1/put-away/summary", null},
                {HttpMethod.GET, "/api/v1/put-away/waiting", null},
                {HttpMethod.POST, "/api/v1/put-away/sessions", Map.of()},
                {HttpMethod.GET, "/api/v1/put-away/sessions/" + s, null},
                {HttpMethod.POST, "/api/v1/put-away/sessions/" + s + "/scan", Map.of("code", "Q800003")},
                {HttpMethod.POST, "/api/v1/put-away/sessions/" + s + "/close", null},
                {HttpMethod.GET, "/api/v1/put-away/sessions/" + s, null},
                {HttpMethod.PUT, "/api/v1/put-away/pieces/" + piece1 + "/place", Map.of()},
                {HttpMethod.GET, "/api/v1/places", null}}) {
            assertThat(call((HttpMethod) c[0], (String) c[1], offOwner, c[2]).getStatusCode())
                .as(c[0] + " " + c[1]).isEqualTo(HttpStatus.NOT_FOUND);
        }
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(offOwner);
        assertThat(rest.exchange("http://localhost:" + port + "/api/v1/put-away/places", HttpMethod.GET,
            new HttpEntity<>(auth), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String user(UUID tenantId, String role) {
        UUID id = UUID.randomUUID();
        String email = role + "-pah-" + id + "@test.local";
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

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, h), Map.class);
    }
}
