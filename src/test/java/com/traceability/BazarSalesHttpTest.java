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
 * Bazar POS B2 — the HTTP boundary of selling: flag off → 404 on every sales endpoint; a worker who is one of the
 * event's seller roles sells (snake_case client_request_id), a worker who isn't gets 403 BAZAR_NOT_A_SELLER;
 * BAZAR_ALREADY_SOLD carries the sale in {details}; workers see the customer's phone masked, owners in full.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarSalesHttpTest {

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
    String ownerOn, workerOn, ownerOff;
    int seq = 0;

    @BeforeAll
    void setup() {
        onTenant = UUID.randomUUID();
        offTenant = UUID.randomUUID();
        mainId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Sales HTTP On', true)", onTenant);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Sales HTTP Off')", offTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, onTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (gen_random_uuid(), ?, 'Main Warehouse', 'warehouse', true, true)", offTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'sales-http.myshopify.com', 'disconnected')", storeId, onTenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-SH', 'Silk Scarf', 'active')", productId, onTenant, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-SH', 'Blue', 200.00)", variantId, onTenant, productId);
        ownerOn  = login(user(onTenant, "owner"));
        workerOn = login(user(onTenant, "worker"));
        ownerOff = login(user(offTenant, "owner"));
    }

    private String user(UUID tenant, String role) {
        String email = role + "-" + UUID.randomUUID() + "@bazar-sales-http.test";
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
        Map<String, Object> body = new HashMap<>(Map.of("name", "Sales Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "Sales Hall " + seq, "sellerRoles", roles,
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

    @Test
    @SuppressWarnings("rawtypes")
    void flagOff_everySalesEndpointIs404() {
        String id = UUID.randomUUID().toString();
        String s = UUID.randomUUID().toString();
        Object[][] calls = {
            { HttpMethod.POST, "/api/v1/bazar-events/" + id + "/sell", Map.of("code", "P0000001", "client_request_id", "r1") },
            { HttpMethod.POST, "/api/v1/bazar-events/" + id + "/sales/" + s + "/undo", Map.of() },
            { HttpMethod.PUT,  "/api/v1/bazar-events/" + id + "/sales/" + s + "/payment", Map.of("method", "card") },
            { HttpMethod.PUT,  "/api/v1/bazar-events/" + id + "/sales/" + s + "/customer", Map.of("name", "X") },
            { HttpMethod.GET,  "/api/v1/bazar-events/" + id + "/sales", null },
        };
        for (Object[] c : calls) {
            ResponseEntity<Map> r = call(ownerOff, (HttpMethod) c[0], (String) c[1], c[2]);
            assertThat(r.getStatusCode()).as(c[0] + " " + c[1]).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody().get("code")).as(c[0] + " " + c[1]).isEqualTo("BAZAR_NOT_ENABLED");
            assertThat(r.getBody()).as("no details key when none").doesNotContainKey("details");
        }
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void workerSeller_sells_alreadySoldCarriesTheSale_phoneMaskedForWorkers() {
        List<String> ev = liveEvent(List.of("owner", "worker"), 1);
        String eventId = ev.get(0), code = ev.get(1);

        ResponseEntity<Map> sold = call(workerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sell",
            Map.of("code", code, "client_request_id", "http-req-1"));
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.OK);
        String saleId = (String) sold.getBody().get("id");
        assertThat(sold.getBody().get("method")).isEqualTo("cash");

        ResponseEntity<Map> replay = call(workerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sell",
            Map.of("code", code, "client_request_id", "http-req-1"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("id")).isEqualTo(saleId);
        assertThat(replay.getBody().get("replayed")).isEqualTo(true);

        ResponseEntity<Map> again = call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sell",
            Map.of("code", code, "client_request_id", "http-req-2"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("BAZAR_ALREADY_SOLD");
        assertThat(again.getBody().get("message_ar")).isNotNull();
        assertThat(((Map) again.getBody().get("details")).get("sale_number")).isEqualTo(sold.getBody().get("sale_number"));
        assertThat(((Map) again.getBody().get("details")).get("seller_name")).isEqualTo("Http worker");

        assertThat(call(ownerOn, HttpMethod.PUT, "/api/v1/bazar-events/" + eventId + "/sales/" + saleId + "/customer",
            Map.of("name", "Laila", "phone", "01512345678")).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map workerRow = ((List<Map>) call(workerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/sales", null)
            .getBody().get("items")).get(0);
        assertThat(workerRow.get("customer_name")).isEqualTo("Laila");
        assertThat(workerRow.get("customer_phone")).isEqualTo("015*****678");
        Map ownerRow = ((List<Map>) call(ownerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/sales?limit=5", null)
            .getBody().get("items")).get(0);
        assertThat(ownerRow.get("customer_phone")).isEqualTo("01512345678");

        ResponseEntity<Map> pay = call(workerOn, HttpMethod.PUT,
            "/api/v1/bazar-events/" + eventId + "/sales/" + saleId + "/payment", Map.of("method", "card"));
        assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pay.getBody().get("method")).isEqualTo("card");

        Map detail = call(ownerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId, null).getBody();
        assertThat(((Map) detail.get("counts")).get("sold")).isEqualTo(1);
        assertThat(((Map) ((Map) detail.get("today")).get("by_method")).get("card")).isEqualTo(200.0);

        ResponseEntity<Map> undo = call(workerOn, HttpMethod.POST,
            "/api/v1/bazar-events/" + eventId + "/sales/" + saleId + "/undo", Map.of());
        assertThat(undo.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undo.getBody().get("status")).isEqualTo("undone");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void workerNotASeller_is403() {
        List<String> ev = liveEvent(List.of("owner", "manager"), 1);
        ResponseEntity<Map> r = call(workerOn, HttpMethod.POST, "/api/v1/bazar-events/" + ev.get(0) + "/sell",
            Map.of("code", ev.get(1), "client_request_id", "http-req-x"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_NOT_A_SELLER");
        assertThat(call(workerOn, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0) + "/sales", null).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        // B1's event endpoints stay owner / manager only.
        assertThat(call(workerOn, HttpMethod.GET, "/api/v1/bazar-events/" + ev.get(0), null).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
