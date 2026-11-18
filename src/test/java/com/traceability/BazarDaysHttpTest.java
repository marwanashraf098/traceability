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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B4 over HTTP — GET /days, GET /days/{day}/close-preview, POST /days/{day}/close: owner / manager only
 * (a worker gets 403, nothing written), the flag (404 when off), the JSON shape, idempotent close, and the seller's
 * payment-method change refused after the day is closed (403 BAZAR_DAY_CLOSED).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarDaysHttpTest {

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
    String ownerOn, managerOn, workerOn, ownerOff;
    int seq = 0, pieceSeq = 0;

    @BeforeAll
    void setup() {
        onTenant = UUID.randomUUID();
        offTenant = UUID.randomUUID();
        mainId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Days HTTP On', true)", onTenant);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Days HTTP Off')", offTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, onTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'days-http.myshopify.com', 'disconnected')", storeId, onTenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-DH', 'Cotton Dress', 'active')", productId, onTenant, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-DH', 'Red', 400.00)", variantId, onTenant, productId);
        ownerOn   = login(user(onTenant, "owner"));
        managerOn = login(user(onTenant, "manager"));
        workerOn  = login(user(onTenant, "worker"));
        ownerOff  = login(user(offTenant, "owner"));
    }

    private String user(UUID tenant, String role) {
        String email = role + "-" + UUID.randomUUID() + "@bazar-days-http.test";
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
            "VALUES (?, ?, ?, ?, ?, 'available', ?)",
            id, onTenant, variantId, "PC-" + id, String.format("P%07d", 8000000 + ++pieceSeq), mainId);
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    /** A live event (cash / card / instapay, owner + worker sell) with {@code n} pieces out: [eventId, codes…]. */
    @SuppressWarnings("rawtypes")
    private List<String> liveEvent(int n) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Days Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "Days Hall " + seq, "sellerRoles", List.of("owner", "worker"),
            "paymentMethods", List.of("cash", "card", "instapay")));
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

    @SuppressWarnings("rawtypes")
    private String sell(String token, String eventId, String code, String method) {
        ResponseEntity<Map> r = call(token, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sell",
            Map.of("code", code, "client_request_id", "s-" + UUID.randomUUID(), "method", method));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("id");
    }

    private String today() {
        return jdbc.queryForObject("SELECT ((now() AT TIME ZONE 'Africa/Cairo')::date)::text", String.class);
    }

    private static BigDecimal money(Object o) {
        return new BigDecimal(String.valueOf(o)).setScale(2);
    }

    private int closes(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bazar_day_closes WHERE event_id = ?::uuid", Integer.class, eventId);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void owner_listsPreviewsAndCloses_idempotent() {
        List<String> ev = liveEvent(2);
        String eventId = ev.get(0), day = today();
        sell(workerOn, eventId, ev.get(1), "cash");
        sell(workerOn, eventId, ev.get(2), "instapay");

        ResponseEntity<Map> list = call(ownerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/days", null);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("today")).isEqualTo(day);
        List<Map<String, Object>> days = (List<Map<String, Object>>) list.getBody().get("days");
        assertThat(days).hasSize(1);
        assertThat(days.get(0).get("status")).isEqualTo("open");
        assertThat(days.get(0).get("close")).isNull();
        assertThat(((Map) days.get(0).get("opening_float_default")).get("source")).isEqualTo("none");

        ResponseEntity<Map> preview = call(managerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/days/" + day
            + "/close-preview?cash=1350&card=0&instapay=true&float=1000", null);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> r = (Map<String, Object>) preview.getBody().get("result");
        assertThat(money(r.get("not_received_total"))).isEqualByComparingTo("50");
        assertThat(money(r.get("take_home"))).isEqualByComparingTo("350");
        assertThat(closes(eventId)).isZero();

        Map<String, Object> body = new HashMap<>(Map.of("cash_counted", 1350, "card_settlement", 0,
            "instapay_received", true, "cash_kept", 1000, "opening_float", 1000, "client_request_id", "close-" + eventId));
        ResponseEntity<Map> closed = call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/days/" + day
            + "/close", body);
        assertThat(closed.getStatusCode()).as(String.valueOf(closed.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody().get("status")).isEqualTo("closed_short");
        assertThat(closed.getBody().get("replayed")).isEqualTo(false);
        ResponseEntity<Map> again = call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/days/" + day
            + "/close", body);
        assertThat(again.getBody().get("replayed")).isEqualTo(true);
        assertThat(closes(eventId)).isEqualTo(1);
    }

    @Test
    void worker_getsForbidden_onEveryDayEndpoint_andNothingIsWritten() {
        List<String> ev = liveEvent(1);
        String eventId = ev.get(0), day = today();
        sell(workerOn, eventId, ev.get(1), "cash");
        String base = "/api/v1/bazar-events/" + eventId + "/days";
        assertThat(call(workerOn, HttpMethod.GET, base, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(workerOn, HttpMethod.GET, base + "/" + day + "/close-preview?cash=1", null).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(workerOn, HttpMethod.POST, base + "/" + day + "/close", Map.of("cash_counted", 400,
            "card_settlement", 0, "instapay_received", true, "opening_float", 0, "client_request_id", "w-" + eventId))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(closes(eventId)).isZero();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void seller_cantChangeTheMethodAfterClose_ownerCan() {
        List<String> ev = liveEvent(1);
        String eventId = ev.get(0), day = today();
        String sale = sell(workerOn, eventId, ev.get(1), "cash");
        assertThat(call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/days/" + day + "/close",
            Map.of("cash_counted", 400, "card_settlement", 0, "instapay_received", true, "opening_float", 0,
                "client_request_id", "c-" + eventId)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> refused = call(workerOn, HttpMethod.PUT,
            "/api/v1/bazar-events/" + eventId + "/sales/" + sale + "/payment", Map.of("method", "card"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo("BAZAR_DAY_CLOSED");
        assertThat(refused.getBody().get("message_ar")).isNotNull();
        assertThat(call(ownerOn, HttpMethod.PUT, "/api/v1/bazar-events/" + eventId + "/sales/" + sale + "/payment",
            Map.of("method", "card")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void flagOff_isNotFound_andABadDay_isBadRequest() {
        assertThat(call(ownerOff, HttpMethod.GET, "/api/v1/bazar-events/" + UUID.randomUUID() + "/days", null)
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        List<String> ev = liveEvent(1);
        assertThat(call(ownerOn, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/days/yesterday/close-preview",
            null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
