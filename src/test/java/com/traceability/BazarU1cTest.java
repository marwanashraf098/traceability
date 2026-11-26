package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar U1c — the small fixes from the 2026-11-26 E2E test (server side; the send by count rule is in
 * BazarSendByCountTest, the PDF wording in BazarReportPdfWordingTest).
 * <ul>
 *   <li>r1 — "What sold" (top_items): one row per variant, the variant named only when it is a real one (never
 *       "Default Title"); top_products / top_sizes unchanged.</li>
 *   <li>r2 — orders CSV: a piece that came back damaged reads "returned (damaged)" / "exchanged (damaged)"; good
 *       ones stay "returned" / "exchanged".</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarU1cTest {

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

    UUID tenant, otherTenant, main, storeId, ownerId;
    String owner, manager, worker, ownerOther;
    int seq = 0, pieceSeq = 0;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        main = UUID.randomUUID();
        storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'U1c On', true)", tenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'U1c Other', true)", otherTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Other Main', 'warehouse', true, true)", UUID.randomUUID(), otherTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'u1c.myshopify.com', 'disconnected')", storeId, tenant);
        ownerId = UUID.randomUUID();
        owner = login(user(ownerId, tenant, "owner"));
        manager = login(user(UUID.randomUUID(), tenant, "manager"));
        worker = login(user(UUID.randomUUID(), tenant, "worker"));
        ownerOther = login(user(UUID.randomUUID(), otherTenant, "owner"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private UUID variant(String productTitle, String title, String price) {
        return variant(productTitle, title, price, "{}", "{}");
    }

    private UUID variant(String productTitle, String title, String price, String productRaw, String variantRaw) {
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'active', ?::jsonb)", product, tenant, storeId, "P-" + product, productTitle, productRaw);
        return variantOf(product, title, price, variantRaw);
    }

    private UUID variantOf(UUID product, String title, String price, String variantRaw) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price, raw) " +
            "VALUES (?, ?, ?, ?, ?, ?::numeric, ?::jsonb)", id, tenant, product, "V-" + id, title, price, variantRaw);
        return id;
    }

    private UUID productOf(UUID variant) {
        return jdbc.queryForObject("SELECT product_id FROM variants WHERE id = ?", UUID.class, variant);
    }

    /** A piece available at Main, received {@code minutesAgo}; untagged → no short code. Returns its id. */
    private String piece(UUID variant, boolean tagged, int minutesAgo) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?, ?, 'available', ?, now() - (? * interval '1 minute'))", id, tenant, variant, "PC-" + id,
            tagged ? String.format("U%07d", 7200000 + ++pieceSeq) : null, main, minutesAgo);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String user(UUID id, UUID t, String role) {
        String email = role + "-" + UUID.randomUUID() + "@u1c.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, t, "U1c " + role, email, passwordEncoder.encode("pass123"), role);
        return email;
    }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> r = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().accessToken();
    }

    private String base() { return "http://localhost:" + port; }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<byte[]> bytes(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    private static String crid() { return "c-" + UUID.randomUUID(); }

    private String ev(String path) { return "/api/v1/bazar-events/" + path; }

    @SuppressWarnings("rawtypes")
    private String draft(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "U1c Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "sellerRoles", List.of("owner", "worker")));
        if (!extra.containsKey("locationId")) body.put("locationName", "U1c Hall " + seq);
        body.putAll(extra);
        ResponseEntity<Map> created = call(owner, HttpMethod.POST, "/api/v1/bazar-events", body);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        return (String) created.getBody().get("id");
    }

    private String draft() { return draft(Map.of()); }

    @SuppressWarnings("rawtypes")
    private void scanOut(String e, String pieceId) {
        ResponseEntity<Map> r = call(owner, HttpMethod.POST, ev(e + "/scan-out"), Map.of("barcode", code(pieceId)));
        assertThat(r.getBody().get("success")).as(String.valueOf(r.getBody())).isEqualTo(true);
    }

    private void sendAndStart(String e) {
        assertThat(call(owner, HttpMethod.POST, ev(e + "/mark-sent"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(owner, HttpMethod.POST, ev(e + "/start"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings("rawtypes")
    private Map sell(String e, String pieceId) {
        ResponseEntity<Map> r = call(owner, HttpMethod.POST, ev(e + "/sell"),
            Map.of("code", code(pieceId), "client_request_id", crid(), "method", "cash"));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("rawtypes")
    private Map tap(String e, UUID variant) {
        ResponseEntity<Map> r = call(owner, HttpMethod.POST, ev(e + "/sell"),
            Map.of("variant_id", variant.toString(), "client_request_id", crid(), "method", "cash"));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("rawtypes")
    private static Object detail(ResponseEntity<Map> r, String key) {
        return ((Map) r.getBody().get("details")).get(key);
    }

    private UUID transferOf(String e) {
        return jdbc.queryForObject("SELECT transfer_id FROM bazar_events WHERE id = ?::uuid", UUID.class, e);
    }

    private UUID locationOf(String e) {
        return jdbc.queryForObject("SELECT location_id FROM bazar_events WHERE id = ?::uuid", UUID.class, e);
    }

    // ── r: report ────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void r1_whatSold_onePerVariant_variantNamedOnlyWhenReal() {
        UUID electric = variant("The Complete Snowboard", "Electric", "700.00");
        UUID sunset = variantOf(productOf(electric), "Sunset", "700.00", "{}");
        UUID scarf = variant("Plain scarf", "Default Title", "200.00");
        String e1 = piece(electric, true, 9), e2 = piece(electric, true, 8), e3 = piece(electric, true, 7);
        String s1 = piece(sunset, true, 6), sc = piece(scarf, true, 5);
        String e = draft();
        for (String p : List.of(e1, e2, e3, s1, sc)) scanOut(e, p);
        sendAndStart(e);
        sell(e, e1);
        sell(e, e2);
        sell(e, s1);
        sell(e, sc);

        Map<String, Object> rep = call(owner, HttpMethod.GET, ev(e + "/report"), null).getBody();
        List<Map<String, Object>> items = (List<Map<String, Object>>) rep.get("top_items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).containsEntry("product_title", "The Complete Snowboard")
            .containsEntry("variant_title", "Electric").containsEntry("sold", 2).containsEntry("sent", 3);
        assertThat(items).filteredOn(i -> "Sunset".equals(i.get("variant_title"))).singleElement()
            .satisfies(i -> assertThat(i).containsEntry("sold", 1).containsEntry("sent", 1));
        assertThat(items).filteredOn(i -> "Plain scarf".equals(i.get("product_title"))).singleElement()
            .satisfies(i -> assertThat(i).doesNotContainKey("variant_title").containsEntry("sold", 1));
        // Unchanged: per product, and the sizes list without Default Title.
        assertThat((List<Map<String, Object>>) rep.get("top_products")).first()
            .satisfies(p -> assertThat(p).containsEntry("product_title", "The Complete Snowboard").containsEntry("sold", 3)
                .doesNotContainKey("variant_title"));
        assertThat((List<Map<String, Object>>) rep.get("top_sizes")).extracting(x -> x.get("variant_title"))
            .containsExactly("Electric", "Sunset");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void r2_ordersCsv_damagedReturnsAndExchangesSaySo() {
        UUID v = variant("Csv shirt", "M", "300.00");
        String kept = piece(v, true, 9), good = piece(v, true, 8), bad = piece(v, true, 7);
        String exOld = piece(v, true, 6), exNew = piece(v, true, 5), exGoodOld = piece(v, true, 4), exGoodNew = piece(v, true, 3);
        String e = draft();
        for (String p : List.of(kept, good, bad, exOld, exNew, exGoodOld, exGoodNew)) scanOut(e, p);
        sendAndStart(e);
        for (String p : List.of(kept, good, bad, exOld, exGoodOld)) sell(e, p);
        for (Object[] r : new Object[][] {{good, "good"}, {bad, "damaged"}}) {
            assertThat(call(owner, HttpMethod.POST, ev(e + "/return"), Map.of("code", code((String) r[0]),
                "condition", r[1], "client_request_id", crid())).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        for (Object[] x : new Object[][] {{exOld, exNew, "damaged"}, {exGoodOld, exGoodNew, "good"}}) {
            ResponseEntity<Map> r = call(owner, HttpMethod.POST, ev(e + "/exchange"), Map.of("old_code", code((String) x[0]),
                "new_code", code((String) x[1]), "method", "cash", "condition", x[2], "client_request_id", crid()));
            assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        }

        String text = new String(bytes(owner, ev(e + "/report/orders.csv")).getBody(), StandardCharsets.UTF_8);
        Map<String, String> statusByPiece = new HashMap<>();
        for (String line : text.replace("\uFEFF", "").split("\n")) {
            String[] c = line.split(",", -1);
            if (c.length > 12 && !"number".equals(c[0])) statusByPiece.put(c[7], c[12]);
        }
        assertThat(statusByPiece).containsEntry(code(kept), "sold")
            .containsEntry(code(good), "returned")
            .containsEntry(code(bad), "returned (damaged)")
            .containsEntry(code(exOld), "exchanged (damaged)")
            .containsEntry(code(exGoodOld), "exchanged")
            .containsEntry(code(exNew), "sold")
            .containsEntry(code(exGoodNew), "sold");
    }
}
