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
 * Bazar POS B6c over HTTP — the cart endpoints: preview, complete (JSON body: codes, discount, method, customer,
 * client_request_id), the receipt's payment change and undo by a worker, and flag off → 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarCartHttpTest {

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
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Cart HTTP On', true)", onTenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Cart HTTP Off', false)", offTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, onTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'cart-http.myshopify.com', 'disconnected')", storeId, onTenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-CH', 'Cotton Dress', 'active')", productId, onTenant, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-CH', 'Red', 400.00)", variantId, onTenant, productId);
        ownerOn   = login(user(onTenant, "owner"));
        managerOn = login(user(onTenant, "manager"));
        workerOn  = login(user(onTenant, "worker"));
        ownerOff  = login(user(offTenant, "owner"));
    }

    private String user(UUID tenant, String role) {
        String email = role + "-" + UUID.randomUUID() + "@bazar-cart-http.test";
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
            id, onTenant, variantId, "PC-" + id, String.format("P%07d", 7100000 + ++pieceSeq), mainId);
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
    void worker_previewsCompletesRepaysAndUndoesACart() {
        List<String> ev = liveEvent(3);
        String id = ev.get(0), base = "/api/v1/bazar-events/" + id;
        ResponseEntity<Map> pv = call(workerOn, HttpMethod.POST, base + "/cart/preview",
            Map.of("codes", List.of(ev.get(1), ev.get(2), "NOPE-1")));
        assertThat(pv.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) pv.getBody().get("lines");
        assertThat(lines).hasSize(3);
        assertThat(((Map<String, Object>) lines.get(2).get("error")).get("code")).isEqualTo("BAZAR_PIECE_NOT_FOUND");
        assertThat(pv.getBody().get("subtotal")).isEqualTo(800.0);

        Map<String, Object> body = new HashMap<>();
        body.put("codes", List.of(ev.get(1), ev.get(2)));
        body.put("discount", 50);
        body.put("method", "card");
        body.put("customer", Map.of("name", "Mona", "phone", "01012345678"));
        body.put("client_request_id", "cart-" + id);
        ResponseEntity<Map> done = call(workerOn, HttpMethod.POST, base + "/cart/complete", body);
        assertThat(done.getStatusCode()).as(String.valueOf(done.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("kind")).isEqualTo("receipt");
        assertThat(done.getBody().get("total")).isEqualTo(750.0);
        assertThat(done.getBody().get("method")).isEqualTo("card");
        List<Map<String, Object>> sold = (List<Map<String, Object>>) done.getBody().get("lines");
        assertThat(sold).extracting(l -> l.get("net_price")).containsExactly(375.0, 375.0);
        assertThat(sold.get(0).get("customer_phone")).isEqualTo("010*****678");     // the worker sees it masked
        String receiptId = (String) done.getBody().get("id");
        // Same request id again → the same receipt.
        assertThat(call(workerOn, HttpMethod.POST, base + "/cart/complete", body).getBody().get("id")).isEqualTo(receiptId);

        ResponseEntity<Map> paid = call(workerOn, HttpMethod.PUT, base + "/receipts/" + receiptId + "/payment",
            Map.of("method", "cash"));
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().get("method")).isEqualTo("cash");

        Map<String, Object> list = call(ownerOn, HttpMethod.GET, base + "/sales", null).getBody();
        assertThat((List<Map<String, Object>>) list.get("items")).singleElement()
            .satisfies(r -> assertThat(r.get("kind")).isEqualTo("receipt"));

        ResponseEntity<Map> undone = call(workerOn, HttpMethod.POST, base + "/receipts/" + receiptId + "/undo", null);
        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().get("status")).isEqualTo("undone");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pieces WHERE short_code IN (?, ?) AND status = 'out_on_transfer'",
            Integer.class, ev.get(1), ev.get(2))).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void aRefusedPiece_namesTheLine_overHttp() {
        List<String> ev = liveEvent(2);
        String base = "/api/v1/bazar-events/" + ev.get(0);
        sell(ownerOn, ev.get(0), ev.get(2), "cash");
        ResponseEntity<Map> r = call(workerOn, HttpMethod.POST, base + "/cart/complete",
            Map.of("codes", List.of(ev.get(1), ev.get(2)), "discount", 0, "client_request_id", "c-" + ev.get(0)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_ALREADY_SOLD");
        assertThat(((Map<?, ?>) r.getBody().get("details")).get("line_index")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE short_code = ?", String.class, ev.get(1)))
            .isEqualTo("out_on_transfer");
    }

    @Test
    void flagOff_isNotFound() {
        List<String> ev = liveEvent(1);
        assertThat(call(ownerOff, HttpMethod.POST, "/api/v1/bazar-events/" + ev.get(0) + "/cart/preview",
            Map.of("codes", List.of(ev.get(1)))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
