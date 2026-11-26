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
 * Bazar POS B5 over HTTP — end, leftovers, scan-back, mark-lost, close-transfer, report, customers.csv: the owner's
 * whole flow; a worker in the event's seller_roles can read the leftovers and scan back, and gets 403 on end / mark-lost
 * / close-transfer / report / CSV (nothing changes); a worker NOT in seller_roles can't scan back; flag off → 404; another tenant's event → 404; the CSV
 * is an attachment, not cached.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarEndHttpTest {

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
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'End HTTP On', true)", onTenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'End HTTP Off', false)", offTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, onTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'end-http.myshopify.com', 'disconnected')", storeId, onTenant);
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
        String email = role + "-" + UUID.randomUUID() + "@bazar-end-http.test";
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
            id, onTenant, variantId, "PC-" + id, String.format("P%07d", 7000000 + ++pieceSeq), mainId);
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    /** A live event (cash / card / instapay, owner + worker sell) with {@code n} pieces out: [eventId, codes…]. */
    @SuppressWarnings("rawtypes")
    private List<String> liveEvent(int n) {
        return liveEvent(n, List.of("owner", "worker"));
    }

    @SuppressWarnings("rawtypes")
    private List<String> liveEvent(int n, List<String> sellerRoles) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "End Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "End Hall " + seq, "sellerRoles", sellerRoles,
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

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void owner_endsBringsLeftoversHomeClosesAndReads() {
        List<String> ev = liveEvent(3);
        String id = ev.get(0), base = "/api/v1/bazar-events/" + id;
        sell(ownerOn, id, ev.get(1), "cash");
        assertThat(call(ownerOn, HttpMethod.POST, base + "/end", null).getBody().get("code")).isEqualTo("BAZAR_DAYS_NOT_CLOSED");
        assertThat(call(ownerOn, HttpMethod.POST, base + "/days/" + today() + "/close", Map.of("cash_counted", 400,
            "card_settlement", 0, "instapay_received", true, "opening_float", 0, "client_request_id", "c-" + id))
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> ended = call(ownerOn, HttpMethod.POST, base + "/end", null);
        assertThat(ended.getStatusCode()).as(String.valueOf(ended.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(ended.getBody().get("event_status")).isEqualTo("ended");

        ResponseEntity<Map> back = call(ownerOn, HttpMethod.POST, base + "/scan-back",
            Map.of("code", ev.get(2), "client_request_id", "b-" + id));
        assertThat(back.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(back.getBody().get("result")).isEqualTo("back_good");
        Map<String, Object> left = call(ownerOn, HttpMethod.GET, base + "/leftovers", null).getBody();
        List<Map<String, Object>> out = (List<Map<String, Object>>) left.get("outstanding");
        assertThat(out).hasSize(1);
        assertThat(call(ownerOn, HttpMethod.POST, base + "/mark-lost",
            Map.of("piece_ids", List.of(out.get(0).get("piece_id")))).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> closed = call(ownerOn, HttpMethod.POST, base + "/close-transfer", null);
        assertThat(closed.getBody().get("transfer_status")).isEqualTo("closed");

        ResponseEntity<Map> report = call(ownerOn, HttpMethod.GET, base + "/report", null);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getBody().get("pieces_sold")).isEqualTo(1);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerOn);
        ResponseEntity<String> csv = rest.exchange(base() + base + "/report/customers.csv", HttpMethod.GET,
            new HttpEntity<>(h), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(csv.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
        assertThat(csv.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(csv.getBody()).contains("name,phone,pieces,total,last_visit");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void worker_scansBack_butCannotEndMarkLostCloseOrReadTheReport() {
        List<String> ev = liveEvent(2);
        String id = ev.get(0), base = "/api/v1/bazar-events/" + id;
        Object[][] managersOnly = {
            {HttpMethod.POST, "/end", null},
            {HttpMethod.POST, "/mark-lost", Map.of("piece_ids", List.of("x"))},
            {HttpMethod.POST, "/close-transfer", null}, {HttpMethod.GET, "/report", null},
            {HttpMethod.GET, "/report/customers.csv", null}};
        for (Object[] c : managersOnly) {
            assertThat(call(workerOn, (HttpMethod) c[0], base + c[1], c[2]).getStatusCode()).as((String) c[1])
                .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM bazar_events WHERE id = ?::uuid", String.class, id)).isEqualTo("live");

        assertThat(call(ownerOn, HttpMethod.POST, base + "/end", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> left = call(workerOn, HttpMethod.GET, base + "/leftovers", null);
        assertThat(left.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Object>) left.getBody().get("outstanding")).hasSize(2);
        ResponseEntity<Map> back = call(workerOn, HttpMethod.POST, base + "/scan-back",
            Map.of("code", ev.get(1), "client_request_id", "wb-" + id));
        assertThat(back.getStatusCode()).as(String.valueOf(back.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(back.getBody().get("result")).isEqualTo("back_good");

        // Still refused after the end: nothing marked lost, the transfer stays open.
        String outstanding = (String) ((List<Map<String, Object>>) call(ownerOn, HttpMethod.GET, base + "/leftovers", null)
            .getBody().get("outstanding")).get(0).get("piece_id");
        for (Object[] c : new Object[][] {
                {HttpMethod.POST, "/mark-lost", Map.of("piece_ids", List.of(outstanding))},
                {HttpMethod.POST, "/close-transfer", null}, {HttpMethod.POST, "/end", null}}) {
            assertThat(call(workerOn, (HttpMethod) c[0], base + c[1], c[2]).getStatusCode()).as((String) c[1])
                .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, outstanding))
            .isEqualTo("out_on_transfer");
        assertThat(jdbc.queryForObject("SELECT t.status FROM transfers t JOIN bazar_events e ON e.transfer_id = t.id " +
            "WHERE e.id = ?::uuid", String.class, id)).isNotEqualTo("closed");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void workerNotInSellerRoles_cannotScanBackOrReadLeftovers() {
        List<String> ev = liveEvent(1, List.of("owner"));
        String id = ev.get(0), base = "/api/v1/bazar-events/" + id;
        assertThat(call(ownerOn, HttpMethod.POST, base + "/end", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> back = call(workerOn, HttpMethod.POST, base + "/scan-back",
            Map.of("code", ev.get(1), "client_request_id", "wx-" + id));
        assertThat(back.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(back.getBody().get("code")).isEqualTo("BAZAR_NOT_A_SELLER");
        assertThat(call(workerOn, HttpMethod.GET, base + "/leftovers", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(ownerOn, HttpMethod.GET, base + "/leftovers", null).getBody().get("counts"))
            .extracting("back").isEqualTo(0);
    }

    @Test
    void flagOff_andAnotherTenantsEvent_areNotFound() {
        List<String> ev = liveEvent(1);
        assertThat(call(ownerOff, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/report", null).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE tenants SET bazar_enabled = true WHERE id = ?", offTenant);
        try {
            assertThat(call(ownerOff, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/report", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(call(ownerOff, HttpMethod.POST, "/api/v1/bazar-events/" + ev.get(0) + "/end", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            jdbc.update("UPDATE tenants SET bazar_enabled = false WHERE id = ?", offTenant);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM bazar_events WHERE id = ?::uuid", String.class, ev.get(0)))
            .isEqualTo("live");
    }
}
