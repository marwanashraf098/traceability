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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar POS B7c — returns / exchanges without a label: find the sale by product + size (+ phone / receipt), one /
 * several / none, workers see masked phones, a return by sale_id writes exactly what a return by code writes, an
 * exchange's new piece can be tapped (never the piece coming back), who may use it, RLS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarReturnByProductTest {

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
    @Autowired DataSource       dataSource;

    UUID tenant, otherTenant, main, storeId;
    UUID shirtWhiteM, shirtWhiteL, shirtBlueM, teeS, teeM, scarfGrey, scarfBlack, apron;
    String owner, worker, ownerOther;
    UUID workerId;
    int seq = 0, pieceSeq = 0;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        main = UUID.randomUUID();
        storeId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'B7 On', true)", tenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'B7 Other', true)", otherTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'b7c.myshopify.com', 'disconnected')", storeId, tenant);
        UUID shirt = product("Linen shirt", "Shirts");
        shirtWhiteM = variant(shirt, "White / M", "960.00");
        shirtWhiteL = variant(shirt, "White / L", "960.00");
        shirtBlueM  = variant(shirt, "Blue / M", "1000.00");
        UUID tee = product("Cotton tee", "Tops");
        teeS = variant(tee, "S", "360.00");
        teeM = variant(tee, "M", "360.00");
        UUID scarf = product("Knit scarf", null);
        scarfGrey  = variant(scarf, "Grey", "320.00");
        scarfBlack = variant(scarf, "Black", "320.00");
        apron = variant(product("Apron", null), "Default Title", "200.00");
        owner = login(user(UUID.randomUUID(), tenant, "owner"));
        workerId = UUID.randomUUID();
        worker = login(user(workerId, tenant, "worker"));
        ownerOther = login(user(UUID.randomUUID(), otherTenant, "owner"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private UUID product(String title, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'active', ?::jsonb)", id, tenant, storeId, "P-" + id, title,
            type == null ? "{}" : "{\"product_type\":\"" + type + "\"}");
        return id;
    }

    private UUID variant(UUID product, String title, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price) VALUES (?, ?, ?, ?, ?, ?::numeric)",
            id, tenant, product, "V-" + id, title, price);
        return id;
    }

    private String user(UUID id, UUID t, String role) {
        String email = role + "-" + UUID.randomUUID() + "@b7c.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, t, "B7 " + role, email, passwordEncoder.encode("pass123"), role);
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

    private String piece(UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available', ?)", id, tenant, variant, "PC-" + id,
            String.format("P%07d", 7800000 + ++pieceSeq), main);
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    /** A live event; {@code variants} scanned out in this order (one piece each): returns the event id. */
    @SuppressWarnings("rawtypes")
    private String liveEvent(List<String> roles, UUID... variants) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "B7c Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "locationName", "B7c Hall " + seq, "sellerRoles", roles,
            "paymentMethods", List.of("cash", "card")));
        ResponseEntity<Map> created = call(owner, HttpMethod.POST, "/api/v1/bazar-events", body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String ev = (String) created.getBody().get("id");
        for (UUID v : variants) {
            String code = piece(v);
            assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/scan-out", Map.of("barcode", code))
                .getBody().get("success")).isEqualTo(true);
        }
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/mark-sent", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/start", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        return ev;
    }

    private String liveEvent(UUID... variants) {
        return liveEvent(List.of("owner", "worker"), variants);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> tap(String token, String ev, UUID variant, String crid) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sell",
            Map.of("variant_id", variant.toString(), "client_request_id", crid, "method", "cash"));
    }

    /** Short codes of the event's pieces of a variant, in scan-out order. */
    private List<String> scanned(String ev, UUID variant) {
        return jdbc.queryForList(
            "SELECT p.short_code FROM transfer_pieces tp JOIN pieces p ON p.id = tp.piece_id " +
            "JOIN bazar_events e ON e.transfer_id = tp.transfer_id WHERE e.id = ?::uuid AND p.variant_id = ? " +
            "ORDER BY tp.created_at, tp.id", String.class, ev, variant);
    }

    private String soldCode(Object saleId) {
        return jdbc.queryForObject("SELECT p.short_code FROM bazar_sales s JOIN pieces p ON p.id = s.piece_id WHERE s.id = ?::uuid",
            String.class, String.valueOf(saleId));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> find(String token, String ev, UUID variant, String extra) {
        // POST with a JSON body since the customer-details slice; extra keeps the old "&phone=…&receipt=…" shape.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("variant_id", variant);
        for (String kv : extra.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            body.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sales/find", body);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map> found(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return (List<Map>) r.getBody().get("sales");
    }

    private String sellTap(String ev, UUID variant) {
        return String.valueOf(tap(worker, ev, variant, "t-" + UUID.randomUUID()).getBody().get("id"));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> returnBy(String token, String ev, Map<String, Object> how) {
        Map<String, Object> body = new HashMap<>(how);
        body.put("condition", "good");
        body.put("client_request_id", "r-" + UUID.randomUUID());
        return call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/return", body);
    }

    private String code(String saleId) {
        return jdbc.queryForObject("SELECT p.short_code FROM bazar_sales s JOIN pieces p ON p.id = s.piece_id WHERE s.id = ?::uuid",
            String.class, saleId);
    }

    /** What a return wrote for a sale: status, ledger events, transfer piece, refund money. */
    private Map<String, Object> returnFacts(String saleId) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("status", jdbc.queryForObject("SELECT status FROM bazar_sales WHERE id = ?::uuid", String.class, saleId));
        f.put("events", jdbc.queryForList("SELECT pe.event_type || ':' || pe.from_status || '>' || pe.to_status FROM piece_events pe " +
            "JOIN bazar_sales s ON s.piece_id = pe.piece_id WHERE s.id = ?::uuid ORDER BY pe.created_at, pe.id", String.class, saleId));
        f.put("piece_status", jdbc.queryForObject("SELECT p.status::text FROM bazar_sales s JOIN pieces p ON p.id = s.piece_id " +
            "WHERE s.id = ?::uuid", String.class, saleId));
        f.put("transfer_outcome", String.valueOf(jdbc.queryForObject("SELECT tp.outcome FROM bazar_sales s " +
            "JOIN transfer_pieces tp ON tp.id = s.transfer_piece_id WHERE s.id = ?::uuid", String.class, saleId)));
        f.put("money", jdbc.queryForList("SELECT kind || ':' || method || ':' || amount FROM bazar_payments WHERE sale_id = ?::uuid " +
            "ORDER BY created_at, id", String.class, saleId));
        f.put("returned_by_set", jdbc.queryForObject("SELECT returned_by IS NOT NULL AND returned_at IS NOT NULL FROM bazar_sales " +
            "WHERE id = ?::uuid", Boolean.class, saleId));
        return f;
    }

    // ── find ────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void find_one_several_none_newestFirst() {
        String ev = liveEvent(teeS, teeS, teeS, teeM);
        assertThat(found(find(worker, ev, teeS, ""))).as("none yet").isEmpty();
        String first = sellTap(ev, teeS);
        List<Map> one = found(find(worker, ev, teeS, ""));
        assertThat(one).hasSize(1);
        assertThat(one.get(0).get("id")).isEqualTo(first);
        String second = sellTap(ev, teeS);
        String third = sellTap(ev, teeS);
        ResponseEntity<Map> several = find(worker, ev, teeS, "");
        assertThat(found(several).stream().map(m -> m.get("id")).toList()).containsExactly(third, second, first);
        assertThat(several.getBody().get("count")).isEqualTo(3);
        // Only active sales of that variant at this event.
        returnBy(worker, ev, Map.of("code", code(second)));
        assertThat(found(find(worker, ev, teeS, "")).stream().map(m -> m.get("id")).toList()).containsExactly(third, first);
        assertThat(found(find(worker, ev, teeM, ""))).isEmpty();
        // A sale of ANOTHER event is never returned here.
        String other = liveEvent(teeS);
        String elsewhere = sellTap(other, teeS);
        ResponseEntity<Map> refused = returnBy(worker, ev, Map.of("sale_id", elsewhere));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo("BAZAR_NOT_SOLD_HERE");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void find_byPhone_normalized_andWorkersSeeItMasked() {
        String ev = liveEvent(scarfGrey, scarfGrey);
        String hers = sellTap(ev, scarfGrey);
        String other = sellTap(ev, scarfGrey);
        assertThat(call(owner, HttpMethod.PUT, "/api/v1/bazar-events/" + ev + "/sales/" + hers + "/customer",
            Map.of("name", "Mona", "phone", "01012345678")).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> byPhone = found(find(worker, ev, scarfGrey, "&phone=201012345678"));
        assertThat(byPhone).hasSize(1);
        assertThat(byPhone.get(0).get("id")).isEqualTo(hers);
        assertThat((String) byPhone.get(0).get("customer_phone")).as("worker: masked").isNotEqualTo("01012345678").startsWith("010");
        assertThat(found(find(owner, ev, scarfGrey, "&phone=01012345678")).get(0).get("customer_phone"))
            .as("owner: full").isEqualTo("01012345678");
        assertThat(found(find(worker, ev, scarfGrey, "&phone=01199999999"))).isEmpty();
        assertThat(found(find(worker, ev, scarfGrey, "")).stream().map(m -> m.get("id")).toList()).contains(other);
        ResponseEntity<Map> bad = find(worker, ev, scarfGrey, "&phone=abc");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody().get("code")).isEqualTo("BAZAR_INVALID_PHONE");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void find_byReceipt_quickSaleNumber_orCartReceiptNumber() {
        String ev = liveEvent(shirtWhiteM, shirtWhiteM, shirtWhiteM);
        ResponseEntity<Map> cart = call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/cart/complete", Map.of(
            "lines", List.of(Map.of("variant_id", shirtWhiteM.toString()), Map.of("variant_id", shirtWhiteM.toString())),
            "method", "cash", "client_request_id", "k-" + UUID.randomUUID()));
        String receiptNo = (String) cart.getBody().get("sale_number");
        String quick = sellTap(ev, shirtWhiteM);
        String quickNo = jdbc.queryForObject("SELECT 'B-' || lpad(sale_number::text, 4, '0') FROM bazar_sales WHERE id = ?::uuid",
            String.class, quick);
        assertThat(found(find(worker, ev, shirtWhiteM, "&receipt=" + receiptNo))).hasSize(2)
            .allMatch(m -> receiptNo.equals(m.get("sale_number")));
        List<Map> q = found(find(worker, ev, shirtWhiteM, "&receipt=" + quickNo.toLowerCase().replace("-", "")));
        assertThat(q).hasSize(1);
        assertThat(q.get(0).get("id")).isEqualTo(quick);
        assertThat(find(worker, ev, shirtWhiteM, "&receipt=xyz").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── return / exchange by sale ───────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void returnBySaleId_writesExactlyWhatAReturnByCodeWrites() {
        String ev = liveEvent(teeM, teeM);
        String a = sellTap(ev, teeM);
        String b = sellTap(ev, teeM);
        ResponseEntity<Map> byCode = returnBy(worker, ev, Map.of("code", code(a)));
        ResponseEntity<Map> bySale = returnBy(worker, ev, Map.of("sale_id", b));
        assertThat(byCode.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bySale.getStatusCode()).as(String.valueOf(bySale.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(returnFacts(b)).isEqualTo(returnFacts(a));
        assertThat(((Map) bySale.getBody().get("refund")).get("amount")).isEqualTo(((Map) byCode.getBody().get("refund")).get("amount"));
        // The same sale again → already returned (the existing rule).
        ResponseEntity<Map> again = returnBy(worker, ev, Map.of("sale_id", b));
        assertThat(again.getBody().get("code")).isEqualTo("BAZAR_ALREADY_RETURNED");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void exchange_oldBySale_newTapped_payTheDifference_andNeverTheReturningPiece() {
        String ev = liveEvent(teeS, teeS, shirtWhiteL);
        String old = sellTap(ev, teeS);
        String oldPiece = code(old);
        ResponseEntity<Map> r = call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/exchange", Map.of(
            "old_sale_id", old, "new_variant_id", shirtWhiteL.toString(), "method", "card",
            "client_request_id", "x-" + UUID.randomUUID()));
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        Map money = (Map) r.getBody().get("money");
        assertThat(money.get("kind")).isEqualTo("payment");
        assertThat(((Number) money.get("amount")).intValue()).isEqualTo(600);   // 960 − 360
        assertThat(jdbc.queryForObject("SELECT status FROM bazar_sales WHERE id = ?::uuid", String.class, old)).isEqualTo("returned");

        // Same size as the one coming back: the tap takes ANOTHER piece of it, never the returning one.
        String old2 = sellTap(ev, teeS);
        String returning = code(old2);
        ResponseEntity<Map> same = call(worker, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/exchange", Map.of(
            "old_sale_id", old2, "new_variant_id", teeS.toString(), "client_request_id", "x-" + UUID.randomUUID()));
        assertThat(same.getStatusCode()).as(String.valueOf(same.getBody())).isEqualTo(HttpStatus.OK);
        List<String> tees = scanned(ev, teeS);
        String otherTee = tees.get(0).equals(returning) ? tees.get(1) : tees.get(0);
        assertThat(((Map) same.getBody().get("new_sale")).get("piece_code")).isNotEqualTo(returning).isEqualTo(otherTee);
        assertThat(oldPiece).as("the first tee came back and was sold again").isEqualTo(returning);
        assertThat(same.getBody().get("money")).as("even swap").isNull();
    }

    // ── who ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void whoMayUseIt_sameAsReturns_removedRefused_otherTenant404() {
        String ev = liveEvent(scarfBlack, scarfBlack);
        String sale = sellTap(ev, scarfBlack);
        assertThat(find(ownerOther, ev, scarfBlack, "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String ownersOnly = liveEvent(List.of("owner", "manager"), scarfBlack);
        assertThat(find(worker, ownersOnly, scarfBlack, "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("INSERT INTO bazar_event_sellers (tenant_id, event_id, user_id, added_by, removed_at, removed_by) " +
            "SELECT ?, ?::uuid, ?, ?, now(), ?", tenant, ev, workerId, workerId, workerId);
        ResponseEntity<Map> f = find(worker, ev, scarfBlack, "");
        assertThat(f.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(f.getBody().get("code")).isEqualTo("BAZAR_NOT_A_SELLER");
        assertThat(returnBy(worker, ev, Map.of("sale_id", sale)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT status FROM bazar_sales WHERE id = ?::uuid", String.class, sale)).isEqualTo("active");
    }

    @Test
    void rls_appUser_seesOnlyItsOwnBazarSales() {
        String ev = liveEvent(teeM);
        sellTap(ev, teeM);
        TenantAwareDataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setReadOnly(true);
        String sql = "SELECT COUNT(*) FROM bazar_sales WHERE event_id = ?::uuid AND variant_id = ?";
        try {
            TenantContext.set(otherTenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev, teeM))).isZero();
            TenantContext.clear();
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev, teeM))).as("no tenant").isZero();
            TenantContext.set(tenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(sql, Integer.class, ev, teeM))).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
    }
}
