package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
import org.junit.jupiter.api.*;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B6 — GET /me's {@code bazar} / {@code bazarLive}: absent when the tenant's flag is off; {@code bazar}
 * without {@code bazarLive} while nothing is live; {@code bazarLive} only for a caller whose role is in the live
 * event's seller_roles (role not in it → absent).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarMeTest {

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

    UUID onTenant, offTenant, mainId, variantId;
    String ownerOn, workerOn, managerOn, ownerOff, workerOff;
    int seq = 0;

    @BeforeAll
    void setup() {
        onTenant = UUID.randomUUID();
        offTenant = UUID.randomUUID();
        mainId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Me On', true)", onTenant);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Me Off')", offTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, onTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (gen_random_uuid(), ?, 'Main Warehouse', 'warehouse', true, true)", offTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'bazar-me.myshopify.com', 'disconnected')", storeId, onTenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-ME', 'Silk Scarf', 'active')", productId, onTenant, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-ME', 'Blue', 200.00)", variantId, onTenant, productId);
        ownerOn  = login(user(onTenant, "owner"));
        workerOn = login(user(onTenant, "worker"));
        managerOn = login(user(onTenant, "manager"));
        ownerOff = login(user(offTenant, "owner"));
        workerOff = login(user(offTenant, "worker"));
    }

    private String user(UUID tenant, String role) {
        String email = role + "-" + UUID.randomUUID() + "@bazar-me.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::user_role, true)",
            tenant, "Http " + role, email, passwordEncoder.encode("pass123"), role);
        return email;
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod method, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, method, new HttpEntity<>(body, h), Map.class);
    }

    private String piece() {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 7, '0'), 'available', ?)",
            id, onTenant, variantId, "PC-" + id, id, mainId);
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    /** A live event (owner-driven over HTTP) with {@code n} pieces out; returns {eventId, codes…}. */
    @SuppressWarnings("rawtypes")
    private List<String> liveEvent(List<String> roles, int n) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Sales Bazar " + (++seq), "startsOn", cairoToday().minusDays(1).toString(),
            "endsOn", cairoToday().plusDays(1).toString(), "locationName", "Sales Hall " + seq, "sellerRoles", roles,
            "paymentMethods", List.of("cash", "card")));
        ResponseEntity<Map> created = call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String eventId = (String) created.getBody().get("id");
        List<String> out = new java.util.ArrayList<>(List.of(eventId));
        for (int i = 0; i < n; i++) {
            String code = piece();
            assertThat(call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/scan-out",
                Map.of("barcode", code)).getBody().get("success")).isEqualTo(true);
            out.add(code);
        }
        assertThat(call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/mark-sent", Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/start", Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        return out;
    }

    private java.time.LocalDate cairoToday() {
        return java.time.LocalDate.now(java.time.ZoneId.of("Africa/Cairo"));
    }

    @SuppressWarnings("rawtypes")
    private Map me(String token) {
        ResponseEntity<Map> r = call(token, HttpMethod.GET, "/api/v1/me", null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void a_flagOff_noBazarKeysAtAll() {
        for (String token : List.of(ownerOff, workerOff)) {
            Map m = me(token);
            assertThat(m).doesNotContainKey("bazar").doesNotContainKey("bazarLive");
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void b_liveEvent_onlyForTheSellerRoles() {
        // Nothing live yet (an event in draft doesn't count): bazar, no bazarLive.
        Map before = me(ownerOn);
        assertThat(before.get("bazar")).isEqualTo(true);
        assertThat(before).doesNotContainKey("bazarLive");

        List<String> ev = liveEvent(List.of("owner", "worker"), 1);
        String eventId = ev.get(0);

        Map owner = me(ownerOn);
        Map live = (Map) owner.get("bazarLive");
        assertThat(live).isNotNull();
        assertThat(live.get("eventId")).isEqualTo(eventId);
        assertThat((String) live.get("name")).startsWith("Sales Bazar ");
        assertThat(((Number) live.get("day")).intValue()).isEqualTo(2);
        assertThat(((Number) live.get("dayCount")).intValue()).isEqualTo(3);
        assertThat(live.get("businessDay")).isEqualTo(cairoToday().toString());
        assertThat((List<String>) live.get("paymentMethods")).containsExactly("cash", "card");

        assertThat(((Map) me(workerOn).get("bazarLive")).get("eventId")).isEqualTo(eventId);

        // A manager isn't one of this event's seller roles → no bazarLive (but still the sidebar's bazar).
        Map manager = me(managerOn);
        assertThat(manager.get("bazar")).isEqualTo(true);
        assertThat(manager).doesNotContainKey("bazarLive");

        // Another tenant's live event never shows.
        assertThat(me(ownerOff)).doesNotContainKey("bazarLive");
    }
}
