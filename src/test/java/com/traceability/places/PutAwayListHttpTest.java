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
 * Warehouse places S4 — put-away lists over HTTP: roles and the capability.
 *
 *   r1  every role (the worker on the station tablet included) reads and starts a receipt's, a return session's and a
 *       Tag stock session's list and reads its session's list; a transfer's list and Start are owner /
 *       manager — a worker gets 403
 *   r2  capability off → 404 for every role on every endpoint (a worker's transfer list too: 404, not 403)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PutAwayListHttpTest {

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

    record Shop(UUID tenant, UUID receipt, UUID returns, UUID tagSession, UUID transfer, Map<String, String> tokens) {}
    Shop on, off;

    @BeforeAll
    void setup() {
        on = shop("on", true);
        off = shop("off", false);
    }

    @Test
    void r1_everyRole_readsAndStarts_transferIsOwnerOrManager() {
        for (String role : List.of("worker", "manager", "owner")) {
            String token = on.tokens().get(role);
            for (Map.Entry<String, UUID> flow : List.of(Map.entry("receiving", on.receipt()),
                    Map.entry("return_session", on.returns()), Map.entry("tag_session", on.tagSession()))) {
                String base = "/api/v1/put-away/lists/" + flow.getKey() + "/" + flow.getValue();
                ResponseEntity<String> list = call(HttpMethod.GET, base, token);
                assertThat(list.getStatusCode()).as(role + " " + flow.getKey()).isEqualTo(HttpStatus.OK);
                assertThat(list.getBody()).as(role + " " + flow.getKey()).contains("PAH-Z1-R01-S1");
                ResponseEntity<String> started = call(HttpMethod.POST, base + "/sessions", token);
                assertThat(started.getStatusCode()).as(role + " start " + flow.getKey()).isEqualTo(HttpStatus.OK);
                String sessionId = started.getBody().replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");
                ResponseEntity<String> sessionList = call(HttpMethod.GET, "/api/v1/put-away/sessions/" + sessionId + "/list", token);
                assertThat(sessionList.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
                assertThat(sessionList.getBody()).as(role).contains("\"scoped\":true");
            }
            String transfer = "/api/v1/put-away/lists/transfer/" + on.transfer();
            HttpStatus expected = "worker".equals(role) ? HttpStatus.FORBIDDEN : HttpStatus.OK;
            assertThat(call(HttpMethod.GET, transfer, token).getStatusCode()).as(role + " transfer list").isEqualTo(expected);
            assertThat(call(HttpMethod.POST, transfer + "/sessions", token).getStatusCode()).as(role + " transfer start").isEqualTo(expected);
        }
    }

    @Test
    void r2_capabilityOff_404ForEveryRoleOnEveryEndpoint() {
        UUID anySession = UUID.randomUUID();
        for (String role : List.of("worker", "manager", "owner")) {
            String token = off.tokens().get(role);
            for (Map.Entry<String, UUID> flow : List.of(Map.entry("receiving", off.receipt()),
                    Map.entry("return_session", off.returns()), Map.entry("tag_session", off.tagSession()),
                    Map.entry("transfer", off.transfer()))) {
                String base = "/api/v1/put-away/lists/" + flow.getKey() + "/" + flow.getValue();
                assertThat(call(HttpMethod.GET, base, token).getStatusCode()).as(role + " " + flow.getKey())
                    .isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(call(HttpMethod.POST, base + "/sessions", token).getStatusCode()).as(role + " start " + flow.getKey())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            }
            assertThat(call(HttpMethod.GET, "/api/v1/put-away/sessions/" + anySession + "/list", token).getStatusCode())
                .as(role).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM put_away_sessions WHERE tenant_id = ?", Integer.class, off.tenant()))
            .isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** A tenant: one placed piece of its variant at main, and one waiting piece per flow (receipt, return session,
     *  Tag stock session, transfer) — each suggested onto that shelf — plus a user per role. */
    private Shop shop(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        UUID main = UUID.randomUUID(), showroom = UUID.randomUUID(), zone = UUID.randomUUID(), rack = UUID.randomUUID();
        UUID shelf = UUID.randomUUID(), receipt = UUID.randomUUID(), returns = UUID.randomUUID(), tag = UUID.randomUUID();
        UUID transfer = UUID.randomUUID(), line = UUID.randomUUID();
        String t = name + "-" + tenant.toString().substring(0, 8);
        int base = placesOn ? 940_000 : 950_000;
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "PutAwayLists HTTP " + name, placesOn);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main', true, true)", main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Showroom')", showroom, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "pah-" + t + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + t);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            variant, tenant, product, "V-" + t, "SKU-" + t);
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, code, scan_code) VALUES (?, ?, ?, 'zone', 'PAH-Z1', ?)",
            zone, tenant, main, "Q" + (base + 1));
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, 'rack', ?, 'zone', 'PAH-Z1-R01', ?)", rack, tenant, main, zone, "Q" + (base + 2));
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, 'shelf', ?, 'rack', 'PAH-Z1-R01-S1', ?)", shelf, tenant, main, rack, "Q" + (base + 3));
        piece(tenant, variant, main, shelf, null, "P" + (base + 10));                       // where the variant lives
        jdbc.update("INSERT INTO receipts (id, tenant_id, location_id, status, finalized_at) VALUES (?, ?, ?, 'finalized', now())",
            receipt, tenant, main);
        piece(tenant, variant, main, null, receipt, "P" + (base + 11));

        Map<String, String> tokens = Map.of(
            "owner", login(user(tenant, "owner")), "manager", login(user(tenant, "manager")), "worker", login(user(tenant, "worker")));
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'owner' LIMIT 1", UUID.class, tenant);

        jdbc.update("INSERT INTO return_sessions (id, tenant_id, status, opened_by, closed_by, closed_at) VALUES (?, ?, 'closed', ?, ?, now())",
            returns, tenant, owner, owner);
        String restocked = piece(tenant, variant, main, null, null, "P" + (base + 12));
        jdbc.update("INSERT INTO return_session_items (tenant_id, session_id, piece_id, scan_source, disposition) " +
            "VALUES (?, ?, ?, 'barcode', 'restocked')", tenant, returns, restocked);
        jdbc.update("INSERT INTO tag_sessions (id, tenant_id, location_id, started_by, ended_at, ended_by) VALUES (?, ?, ?, ?, now(), ?)",
            tag, tenant, main, owner, owner);
        String tagged = piece(tenant, variant, main, null, null, "P" + (base + 13));
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, metadata) " +
            "VALUES (?, ?, 'label_bound', 'available', 'available', ?::jsonb)", tenant, tagged, "{\"tag_session_id\": \"" + tag + "\"}");
        jdbc.update("INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, status, created_by) " +
            "VALUES (?, ?, 'showroom', ?, 'reconciling', ?)", transfer, tenant, showroom, owner);
        jdbc.update("INSERT INTO transfer_lines (id, tenant_id, transfer_id, variant_id, qty_out, qty_returned_good) VALUES (?, ?, ?, ?, 1, 1)",
            line, tenant, transfer, variant);
        String back = piece(tenant, variant, main, null, null, "P" + (base + 14));
        jdbc.update("INSERT INTO transfer_pieces (tenant_id, transfer_id, line_id, piece_id, outcome) VALUES (?, ?, ?, ?, 'returned_good')",
            tenant, transfer, line, back);
        return new Shop(tenant, receipt, returns, tag, transfer, tokens);
    }

    private String piece(UUID tenant, UUID variant, UUID location, UUID bin, UUID receipt, String shortCode) {
        String id = "01PAH" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, bin_id, receipt_id) " +
            "VALUES (?, ?, ?, 'available', ?, ?, ?, ?, ?)", id, tenant, variant, "PC-" + id, shortCode, location, bin, receipt);
        return id;
    }

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

    private ResponseEntity<String> call(HttpMethod method, String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(null, h), String.class);
    }
}
