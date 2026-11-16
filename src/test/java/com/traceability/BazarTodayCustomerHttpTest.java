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
 * Bazar POS B6 review — GET /bazar-events/{id}/today (server totals: more than one page of sales, undo leaves the
 * total, seller roles only) and PUT …/customer keeping a field that wasn't sent (a worker changes the name without
 * resending the masked phone; an explicit empty value clears).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarTodayCustomerHttpTest {

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
    int pieceSeq = 0;

    @BeforeAll
    void setup() {
        onTenant = UUID.randomUUID();
        offTenant = UUID.randomUUID();
        mainId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID(), productId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Today HTTP On', true)", onTenant);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Today HTTP Off')", offTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", mainId, onTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (gen_random_uuid(), ?, 'Main Warehouse', 'warehouse', true, true)", offTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'today-http.myshopify.com', 'disconnected')", storeId, onTenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-SH', 'Silk Scarf', 'active')", productId, onTenant, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) " +
            "VALUES (?, ?, ?, 'VAR-SH', 'Blue', 200.00)", variantId, onTenant, productId);
        ownerOn  = login(user(onTenant, "owner"));
        workerOn = login(user(onTenant, "worker"));
        ownerOff = login(user(offTenant, "owner"));
    }

    private String user(UUID tenant, String role) {
        String email = role + "-" + UUID.randomUUID() + "@bazar-today-http.test";
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
            id, onTenant, variantId, "PC-" + id, String.format("P%07d", 9000000 + ++pieceSeq), mainId);
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

    private static java.math.BigDecimal money(Object o) {
        return new java.math.BigDecimal(String.valueOf(o)).setScale(2);
    }

    @SuppressWarnings("rawtypes")
    private String sell(String token, String eventId, String code, String reqId, String method) {
        ResponseEntity<Map> r = call(token, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sell",
            Map.of("code", code, "client_request_id", reqId, "method", method));
        assertThat(r.getStatusCode()).as(r.getBody() == null ? "" : r.getBody().toString()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("id");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void today_moreThanOnePageOfSales_serverTotal_undoRemovesIt_perSeller() {
        List<String> ev = liveEvent(List.of("owner", "worker"), 60);
        String eventId = ev.get(0);
        String firstWorkerSale = null;
        for (int i = 1; i <= 60; i++) {
            boolean byWorker = i <= 45;
            String id = sell(byWorker ? workerOn : ownerOn, eventId, ev.get(i), "today-" + i, i % 4 == 0 ? "card" : "cash");
            if (firstWorkerSale == null) firstWorkerSale = id;
        }
        // The list really is more than one page.
        assertThat(call(workerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/sales?limit=50", null)
            .getBody().get("next_cursor")).isNotNull();

        ResponseEntity<Map> t = call(workerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/today", null);
        assertThat(t.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(t.getBody().get("business_day")).isNotNull();
        assertThat(t.getBody().get("sale_count")).isEqualTo(60);
        assertThat(money(t.getBody().get("total"))).isEqualByComparingTo("12000.00");
        Map byMethod = (Map) t.getBody().get("by_method");
        assertThat(money(byMethod.get("card"))).isEqualByComparingTo("3000.00");   // 15 × 200
        assertThat(money(byMethod.get("cash"))).isEqualByComparingTo("9000.00");   // 45 × 200
        List<Map> perSeller = (List<Map>) t.getBody().get("per_seller");
        assertThat(perSeller).hasSize(2);
        assertThat(perSeller.get(0).get("seller_name")).isEqualTo("Http worker");
        assertThat(perSeller.get(0).get("sale_count")).isEqualTo(45);
        assertThat(money(perSeller.get(0).get("total"))).isEqualByComparingTo("9000.00");
        assertThat(perSeller.get(1).get("seller_name")).isEqualTo("Http owner");
        assertThat(perSeller.get(1).get("sale_count")).isEqualTo(15);

        // Undo takes the sale out of every number.
        assertThat(call(ownerOn, HttpMethod.POST, "/api/v1/bazar-events/" + eventId + "/sales/" + firstWorkerSale
            + "/undo", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> after = call(ownerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/today", null);
        assertThat(after.getBody().get("sale_count")).isEqualTo(59);
        assertThat(money(after.getBody().get("total"))).isEqualByComparingTo("11800.00");
        assertThat(money(((Map) after.getBody().get("by_method")).get("cash"))).isEqualByComparingTo("8800.00");
        assertThat(((List<Map>) after.getBody().get("per_seller")).get(0).get("sale_count")).isEqualTo(44);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void today_onlySellerRolesOrManagers_flagOff404() {
        List<String> ev = liveEvent(List.of("owner"), 1);
        String eventId = ev.get(0);
        ResponseEntity<Map> worker = call(workerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/today", null);
        assertThat(worker.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(worker.getBody().get("code")).isEqualTo("BAZAR_NOT_A_SELLER");
        ResponseEntity<Map> owner = call(ownerOn, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/today", null);
        assertThat(owner.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(owner.getBody().get("sale_count")).isEqualTo(0);
        assertThat(money(owner.getBody().get("total"))).isEqualByComparingTo("0.00");

        ResponseEntity<Map> off = call(ownerOff, HttpMethod.GET, "/api/v1/bazar-events/" + eventId + "/today", null);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(off.getBody().get("code")).isEqualTo("BAZAR_NOT_ENABLED");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void customer_fieldNotSentIsKept_emptyClears_workerChangesNameWithoutThePhone() {
        List<String> ev = liveEvent(List.of("owner", "worker"), 1);
        String eventId = ev.get(0);
        String saleId = sell(workerOn, eventId, ev.get(1), "cust-1", "cash");
        String path = "/api/v1/bazar-events/" + eventId + "/sales/" + saleId + "/customer";

        assertThat(call(workerOn, HttpMethod.PUT, path, Map.of("name", "Laila", "phone", "01512345678"))
            .getStatusCode()).isEqualTo(HttpStatus.OK);

        // The worker sees 015*****678 and sends only the name: the stored number stays.
        ResponseEntity<Map> nameOnly = call(workerOn, HttpMethod.PUT, path, Map.of("name", "Laila Hassan"));
        assertThat(nameOnly.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(nameOnly.getBody().get("customer_name")).isEqualTo("Laila Hassan");
        assertThat(nameOnly.getBody().get("customer_phone")).isEqualTo("015*****678");
        assertThat(jdbc.queryForObject("SELECT customer_phone FROM bazar_sales WHERE id = ?::uuid", String.class, saleId))
            .isEqualTo("01512345678");

        // Phone only: the name stays.
        ResponseEntity<Map> phoneOnly = call(ownerOn, HttpMethod.PUT, path, Map.of("phone", "01098765432"));
        assertThat(phoneOnly.getBody().get("customer_name")).isEqualTo("Laila Hassan");
        assertThat(phoneOnly.getBody().get("customer_phone")).isEqualTo("01098765432");

        // An explicit empty value clears that field only.
        ResponseEntity<Map> clearPhone = call(workerOn, HttpMethod.PUT, path, Map.of("phone", ""));
        assertThat(clearPhone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(clearPhone.getBody().get("customer_phone")).isNull();
        assertThat(clearPhone.getBody().get("customer_name")).isEqualTo("Laila Hassan");
        Map<String, Object> nullName = new HashMap<>();
        nullName.put("name", null);
        ResponseEntity<Map> clearName = call(ownerOn, HttpMethod.PUT, path, nullName);
        assertThat(clearName.getBody().get("customer_name")).isNull();

        // Nothing sent: nothing changes.
        call(ownerOn, HttpMethod.PUT, path, Map.of("name", "Mona", "phone", "01112345678"));
        ResponseEntity<Map> empty = call(ownerOn, HttpMethod.PUT, path, Map.of());
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empty.getBody().get("customer_name")).isEqualTo("Mona");
        assertThat(empty.getBody().get("customer_phone")).isEqualTo("01112345678");
    }
}
