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
 * Warehouse places S3 — roles and the capability over HTTP.
 *
 *   r1  every role — the worker on the station tablet included — can call all three places endpoints
 *   r2  capability off → 404 for every role on every endpoint
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PickPlacesHttpTest {

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

    record Shop(UUID tenant, UUID variant, UUID order, Map<String, String> tokens) {}
    Shop on, off;

    @BeforeAll
    void setup() {
        on = shop("on", true);
        off = shop("off", false);
    }

    @Test
    void r1_everyRole_workerIncluded_callsAllThreeEndpoints() {
        for (String role : List.of("worker", "manager", "owner")) {
            String token = on.tokens().get(role);
            ResponseEntity<String> order = call(HttpMethod.GET, "/api/v1/fulfill/" + on.order() + "/places", token, null);
            assertThat(order.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
            assertThat(order.getBody()).as(role).contains("PKH-Z1-R01-S1");
            ResponseEntity<String> variants = call(HttpMethod.POST, "/api/v1/fulfill/places/variants", token,
                Map.of("variantIds", List.of(on.variant())));
            assertThat(variants.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
            assertThat(variants.getBody()).as(role).contains("PKH-Z1-R01-S1");
            ResponseEntity<String> orders = call(HttpMethod.POST, "/api/v1/fulfill/places/orders", token,
                Map.of("orderIds", List.of(on.order())));
            assertThat(orders.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
            assertThat(orders.getBody()).as(role).contains(on.order().toString()).contains("PKH-Z1-R01-S1");
        }
    }

    @Test
    void r2_capabilityOff_404ForEveryRoleOnEveryEndpoint() {
        for (String role : List.of("worker", "manager", "owner")) {
            String token = off.tokens().get(role);
            assertThat(call(HttpMethod.GET, "/api/v1/fulfill/" + off.order() + "/places", token, null).getStatusCode())
                .as(role).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(call(HttpMethod.POST, "/api/v1/fulfill/places/variants", token,
                Map.of("variantIds", List.of(off.variant()))).getStatusCode()).as(role).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(call(HttpMethod.POST, "/api/v1/fulfill/places/orders", token,
                Map.of("orderIds", List.of(off.order()))).getStatusCode()).as(role).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** A tenant with one placed piece of one variant at its main warehouse, an order for it, and a user per role. */
    private Shop shop(String name, boolean placesOn) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        UUID main = UUID.randomUUID(), zone = UUID.randomUUID(), rack = UUID.randomUUID(), shelf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        String tag = name + "-" + tenant.toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id, name, places_enabled) VALUES (?, ?, ?)", tenant, "PickPlaces HTTP " + name, placesOn);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_default, is_fulfillment) VALUES (?, ?, 'Main', true, true)", main, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, "pkh-" + tag + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tee', 'active')",
            product, tenant, store, "P-" + tag);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'M', ?)",
            variant, tenant, product, "V-" + tag, "SKU-" + tag);
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, code, scan_code) VALUES (?, ?, ?, 'zone', 'PKH-Z1', 'Q930001')",
            zone, tenant, main);
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, 'rack', ?, 'zone', 'PKH-Z1-R01', 'Q930002')", rack, tenant, main, zone);
        jdbc.update("INSERT INTO bins (id, tenant_id, location_id, kind, parent_id, parent_kind, code, scan_code) " +
            "VALUES (?, ?, ?, 'shelf', ?, 'rack', 'PKH-Z1-R01-S1', 'Q930003')", shelf, tenant, main, rack);
        String piece = "01PKH" + UUID.randomUUID().toString().replace("-", "").substring(0, 21).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, bin_id) " +
            "VALUES (?, ?, ?, 'available', ?, 'P930001', ?, ?)", piece, tenant, variant, "PC-" + piece, main, shelf);
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status) VALUES (?, ?, ?, ?, '#PKH1', 'new')",
            order, tenant, store, "ext-" + tag);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", tenant, order, variant);
        Map<String, String> tokens = Map.of(
            "owner", login(user(tenant, "owner")), "manager", login(user(tenant, "manager")), "worker", login(user(tenant, "worker")));
        return new Shop(tenant, variant, order, tokens);
    }

    private String user(UUID tenantId, String role) {
        UUID id = UUID.randomUUID();
        String email = role + "-pkh-" + id + "@test.local";
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
