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
 * Bazar U1b — the smaller fixes from the 2026-11-22 E2E test (server side).
 * <ul>
 *   <li>d — delete a DRAFT bazar (V243 soft delete): only while nothing was ever scanned out; its transfer cancelled;
 *       every bazar read leaves it out; owner / manager only; app_user isolation.</li>
 *   <li>o — the online-stock clash names the bazar in the way.</li>
 *   <li>p — a typed price equal to the rule's price keeps the rule's source (not 'override').</li>
 *   <li>t — a grid tap sells an unlabelled piece first; the catalog says how many unlabelled are left.</li>
 *   <li>c — send by count / remove from this send name the label codes moved.</li>
 *   <li>r — report: busiest hours from kept sales only, first to last hour; unsold products; has_sizes; orders CSV.</li>
 *   <li>s — price sheet: the colour column named after the Shopify option, empty columns hidden.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarU1bTest {

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
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'U1b On', true)", tenant);
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'U1b Other', true)", otherTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Other Main', 'warehouse', true, true)", UUID.randomUUID(), otherTenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'u1b.myshopify.com', 'disconnected')", storeId, tenant);
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
            tagged ? String.format("U%07d", 7100000 + ++pieceSeq) : null, main, minutesAgo);
        return id;
    }

    private String code(String pieceId) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String user(UUID id, UUID t, String role) {
        String email = role + "-" + UUID.randomUUID() + "@u1b.test";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, t, "U1b " + role, email, passwordEncoder.encode("pass123"), role);
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
        Map<String, Object> body = new HashMap<>(Map.of("name", "U1b Bazar " + (++seq), "startsOn", "2026-12-01",
            "endsOn", "2026-12-02", "sellerRoles", List.of("owner", "worker")));
        if (!extra.containsKey("locationId")) body.put("locationName", "U1b Hall " + seq);
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

    // ── d: delete a draft bazar ─────────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void d1_deleteDraft_nothingScanned_softDeleted_transferCancelled_goneFromEveryRead() {
        String e = draft();
        assertThat(call(owner, HttpMethod.GET, ev(e), null).getBody().get("deletable")).isEqualTo(true);

        ResponseEntity<Map> del = call(manager, HttpMethod.DELETE, ev(e), null);
        assertThat(del.getStatusCode()).as(String.valueOf(del.getBody())).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, deleted_at, deleted_by FROM bazar_events WHERE id = ?::uuid", e);
        assertThat(row.get("status")).isEqualTo("draft");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("deleted_by")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferOf(e)))
            .isEqualTo("cancelled");

        List<Map<String, Object>> list = (List<Map<String, Object>>) (List) rest.exchange(base() + "/api/v1/bazar-events",
            HttpMethod.GET, new HttpEntity<>(auth(owner)), List.class).getBody();
        assertThat(list).noneMatch(m -> e.equals(m.get("id")));
        for (String path : List.of("", "/prices", "/catalog", "/send-stock", "/report", "/leftovers", "/days", "/sales",
                "/price-sheet.pdf", "/report/orders.csv")) {
            assertThat(rest.exchange(base() + ev(e + path), HttpMethod.GET, new HttpEntity<>(auth(owner)), String.class)
                .getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(call(owner, HttpMethod.POST, ev(e + "/scan-out"), Map.of("barcode", "X")).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(owner, HttpMethod.DELETE, ev(e), null).getStatusCode()).as("twice").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @SuppressWarnings("rawtypes")
    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    @SuppressWarnings("rawtypes")
    void d2_cantDeleteAfterAScanOut_evenWhenTakenBackOffTheSend() {
        UUID v = variant("Delete dress", "S", "500");
        String p = piece(v, true, 10);
        String e = draft();
        scanOut(e, p);
        assertThat(call(owner, HttpMethod.GET, ev(e), null).getBody().get("deletable")).isEqualTo(false);
        ResponseEntity<Map> r = call(owner, HttpMethod.DELETE, ev(e), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("code")).isEqualTo("TRANSFER_HAS_PIECES");

        // Taken back off the send: no piece on it any more, but something WAS scanned out — still refused.
        assertThat(call(owner, HttpMethod.POST, ev(e + "/remove-from-send"),
            Map.of("code", code(p), "client_request_id", crid())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_pieces WHERE transfer_id = ?", Integer.class,
            transferOf(e))).isZero();
        assertThat(call(owner, HttpMethod.GET, ev(e), null).getBody().get("deletable")).isEqualTo(false);
        assertThat(call(owner, HttpMethod.DELETE, ev(e), null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM bazar_events WHERE id = ?::uuid", Boolean.class, e)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferOf(e)))
            .isEqualTo("preparing");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void d3_sentOrLive_refused_workerAndOtherTenantCant() {
        UUID v = variant("Sent dress", "S", "500");
        String sent = draft();
        scanOut(sent, piece(v, true, 10));
        assertThat(call(owner, HttpMethod.POST, ev(sent + "/mark-sent"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = call(owner, HttpMethod.DELETE, ev(sent), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_CANT_DELETE");
        assertThat(call(owner, HttpMethod.POST, ev(sent + "/start"), Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(owner, HttpMethod.DELETE, ev(sent), null).getBody().get("code")).isEqualTo("BAZAR_EVENT_NOT_DRAFT");

        String e = draft();
        assertThat(call(worker, HttpMethod.DELETE, ev(e), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(ownerOther, HttpMethod.DELETE, ev(e), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM bazar_events WHERE id = ?::uuid", Boolean.class, e)).isTrue();
    }

    @Test
    void d4_appUser_seesAndMarksOnlyItsOwnEvents_andStillCantDeleteRows() {
        String e = draft();
        TenantAwareDataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String mark = "UPDATE bazar_events SET deleted_at = now(), deleted_by = ? WHERE id = ?::uuid";
        try {
            TenantContext.set(otherTenant);
            assertThat((Integer) tx.execute(s -> app.queryForObject(
                "SELECT count(*) FROM bazar_events WHERE id = ?::uuid", Integer.class, e))).isZero();
            assertThat((Integer) tx.execute(s -> app.update(mark, ownerId, e))).as("another tenant").isZero();
            TenantContext.clear();
            assertThat((Integer) tx.execute(s -> app.update(mark, ownerId, e))).as("no tenant").isZero();
            TenantContext.set(tenant);
            assertThat((Integer) tx.execute(s -> app.update(mark, ownerId, e))).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('app_user', 'bazar_events', 'DELETE')", Boolean.class))
            .as("still soft delete only").isFalse();
        // A started event can't carry deleted_at (V243 CHECK).
        UUID v = variant("Check dress", "S", "500");
        String live = draft();
        scanOut(live, piece(v, true, 10));
        sendAndStart(live);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            jdbc.update(mark, ownerId, live))).isNotNull();
    }

    // ── o: online-stock clash names the bazar in the way ─────────────────────

    @Test
    @SuppressWarnings("rawtypes")
    void o1_clash_namesAndLinksTheBlockingBazar_aDeletedOneNoLongerBlocks() {
        String keep = draft(Map.of("shopifySyncMode", "leave"));
        UUID loc = locationOf(keep);
        String name = jdbc.queryForObject("SELECT name FROM bazar_events WHERE id = ?::uuid", String.class, keep);
        ResponseEntity<Map> r = call(owner, HttpMethod.POST, "/api/v1/bazar-events", Map.of("name", "U1b clash",
            "startsOn", "2026-12-01", "endsOn", "2026-12-02", "locationId", loc.toString(), "shopifySyncMode", "remove"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_ONLINE_STOCK_CONFLICT");
        assertThat(detail(r, "blocking_event_id")).isEqualTo(keep);
        assertThat(detail(r, "blocking_event_name")).isEqualTo(name);
        assertThat((String) r.getBody().get("message_en")).contains(name).contains("whole location");

        assertThat(call(owner, HttpMethod.DELETE, ev(keep), null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String next = draft(Map.of("locationId", loc.toString(), "shopifySyncMode", "remove"));
        assertThat(next).isNotNull();
    }

    // ── p: a typed price equal to the rule's price is not custom ─────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void p1_priceEqualToTheRule_keepsTheRulesSource_aSaleRecordsIt() {
        UUID oxygen = variant("Oxygen", "Default Title", "1025.00");
        UUID plain = variant("Plain", "Default Title", "300.00");
        String p = piece(oxygen, true, 10);
        String e = draft(Map.of("discountPct", 20, "roundTo", 5));
        scanOut(e, p);
        scanOut(e, piece(plain, true, 9));
        put(e, oxygen, 820);   // 1025 − 20 % = 820: the rule's own price
        assertThat(source(e, oxygen)).isEqualTo("bulk");
        put(e, oxygen, 800);
        assertThat(source(e, oxygen)).isEqualTo("override");
        put(e, oxygen, 820);
        assertThat(source(e, oxygen)).as("typed back to the rule's price").isEqualTo("bulk");

        // No rule: equal to Shopify's price → 'default'.
        assertThat(raw(HttpMethod.PUT, ev(e + "/bulk-rule"), new HashMap<>()).getStatusCode()).isEqualTo(HttpStatus.OK);
        put(e, plain, 300);
        assertThat(source(e, plain)).isEqualTo("default");

        assertThat(raw(HttpMethod.PUT, ev(e + "/bulk-rule"), Map.of("discountPct", 20, "roundTo", 5)).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        sendAndStart(e);
        Map sale = sell(e, p);
        assertThat(sale.get("price_source")).isEqualTo("bulk");
    }

    private void put(String e, UUID v, int price) {
        assertThat(raw(HttpMethod.PUT, ev(e + "/prices/" + v), Map.of("price", price)).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    /** A call whose answer is a list (prices, bulk rule): the status only. */
    private ResponseEntity<String> raw(HttpMethod m, String path, Object body) {
        HttpHeaders h = auth(owner);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), String.class);
    }

    private String source(String e, UUID v) {
        return jdbc.queryForObject("SELECT source FROM bazar_event_prices WHERE event_id = ?::uuid AND variant_id = ?",
            String.class, e, v);
    }

    // ── t: a tap sells an unlabelled piece first ─────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void t1_tap_unlabelledFirst_catalogCountsUnlabelledLeft() {
        UUID v = variant("Tap dress", "M", "500");
        String labelled = piece(v, true, 60);     // scanned out first: the oldest on the send
        String untagged = piece(v, false, 30);
        String e = draft();
        scanOut(e, labelled);
        assertThat(call(owner, HttpMethod.POST, ev(e + "/scan-out-by-count"), Map.of("variant_id", v.toString(),
            "quantity", 1, "client_request_id", crid())).getStatusCode()).isEqualTo(HttpStatus.OK);
        sendAndStart(e);

        assertThat(size(e, v).get("left")).isEqualTo(2);
        assertThat(size(e, v).get("left_unlabelled")).isEqualTo(1);
        Map first = tap(e, v);
        assertThat(first.get("piece_id")).as("the unlabelled piece, though the labelled one was sent first").isEqualTo(untagged);
        assertThat(size(e, v).get("left")).isEqualTo(1);
        assertThat(size(e, v).get("left_unlabelled")).isEqualTo(0);
        assertThat(tap(e, v).get("piece_id")).isEqualTo(labelled);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Map<String, Object> size(String e, UUID v) {
        List<Map<String, Object>> products = (List<Map<String, Object>>) call(owner, HttpMethod.GET, ev(e + "/catalog"), null)
            .getBody().get("products");
        return products.stream().flatMap(p -> ((List<Map<String, Object>>) p.get("sizes")).stream())
            .filter(s -> v.toString().equals(s.get("variant_id"))).findFirst().orElseThrow();
    }

    // ── c: label codes of what moved ─────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void c1_sendByCount_andRemove_nameTheLabelCodesMoved() {
        UUID v = variant("Count shirt", "L", "400");
        String a = piece(v, true, 50), b = piece(v, false, 40), c = piece(v, true, 30);
        String e = draft();
        ResponseEntity<Map> sent = call(owner, HttpMethod.POST, ev(e + "/scan-out-by-count"),
            Map.of("variant_id", v.toString(), "quantity", 3, "client_request_id", crid()));
        assertThat((List<String>) sent.getBody().get("labelled_codes")).containsExactlyInAnyOrder(code(a), code(c));

        ResponseEntity<Map> removed = call(owner, HttpMethod.POST, ev(e + "/remove-from-send"),
            Map.of("variant_id", v.toString(), "quantity", 1, "client_request_id", crid()));
        assertThat((List<String>) removed.getBody().get("pieceIds")).containsExactly(c);
        assertThat((List<String>) removed.getBody().get("labelled_codes")).containsExactly(code(c));

        ResponseEntity<Map> removedUntagged = call(owner, HttpMethod.POST, ev(e + "/remove-from-send"),
            Map.of("variant_id", v.toString(), "quantity", 1, "client_request_id", crid()));
        assertThat((List<String>) removedUntagged.getBody().get("pieceIds")).containsExactly(b);
        assertThat((List<String>) removedUntagged.getBody().get("labelled_codes")).isEmpty();
    }

    // ── r: report ────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void r1_busiestHours_keptSalesOnly_firstToLastHour_unsold_hasSizes_ordersCsv() {
        UUID shirt = variant("Report shirt", "Default Title", "300.00");
        UUID scarf = variant("Report scarf", "Default Title", "200.00");
        String s1 = piece(shirt, true, 10), s2 = piece(shirt, true, 9), s3 = piece(shirt, true, 8);
        String sc = piece(scarf, true, 7);
        String e = draft();
        for (String p : List.of(s1, s2, s3, sc)) scanOut(e, p);
        sendAndStart(e);
        Map kept = sell(e, s1);
        Map undone = sell(e, s2);
        Map returned = sell(e, s3);
        assertThat(call(owner, HttpMethod.POST, ev(e + "/sales/" + undone.get("id") + "/undo"), Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(owner, HttpMethod.POST, ev(e + "/return"), Map.of("code", code(s3), "condition", "good",
            "client_request_id", crid())).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Kept sale at 12:xx, returned at 15:xx (Cairo) — the hours run 12 → 12 only (the returned one is left out).
        jdbc.update("UPDATE bazar_sales SET sold_at = (current_date + time '12:10') AT TIME ZONE 'Africa/Cairo' WHERE id = ?::uuid", kept.get("id"));
        jdbc.update("UPDATE bazar_sales SET sold_at = (current_date + time '15:10') AT TIME ZONE 'Africa/Cairo' WHERE id = ?::uuid", returned.get("id"));

        Map rep = call(owner, HttpMethod.GET, ev(e + "/report"), null).getBody();
        List<Map<String, Object>> hours = (List<Map<String, Object>>) rep.get("busiest_hours");
        assertThat(hours).singleElement().satisfies(h -> {
            assertThat(h.get("hour")).isEqualTo(12);
            assertThat(h.get("sales")).isEqualTo(1);
            assertThat(h.get("pieces")).isEqualTo(1);
        });
        assertThat(rep.get("has_sizes")).isEqualTo(false);
        assertThat((List<Map<String, Object>>) rep.get("unsold_products")).singleElement().satisfies(u -> {
            assertThat(u.get("product_title")).isEqualTo("Report scarf");
            assertThat(u.get("sold")).isEqualTo(0);
            assertThat(u.get("sent")).isEqualTo(1);
        });

        // A second kept sale at 14:xx → 12, 13 (0), 14.
        Map later = sell(e, sc);
        jdbc.update("UPDATE bazar_sales SET sold_at = (current_date + time '14:40') AT TIME ZONE 'Africa/Cairo' WHERE id = ?::uuid", later.get("id"));
        hours = (List<Map<String, Object>>) call(owner, HttpMethod.GET, ev(e + "/report"), null).getBody().get("busiest_hours");
        assertThat(hours).extracting(h -> h.get("hour")).containsExactly(12, 13, 14);
        assertThat(hours).extracting(h -> h.get("sales")).containsExactly(1, 0, 1);

        ResponseEntity<byte[]> csv = bytes(owner, ev(e + "/report/orders.csv"));
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        String[] lines = text.replace("﻿", "").split("\n");
        assertThat(lines[0]).isEqualTo("number,day,time,seller,product,variant,sku,piece,price,discount,net,method,status,customer_name,customer_phone");
        assertThat(lines).hasSize(4);   // header + kept + returned + later; the undone sale never happened
        assertThat(text).contains(code(s1)).contains(code(s3)).contains(code(sc)).doesNotContain(code(s2))
            .contains(",returned,").contains(",sold,").doesNotContain("Default Title");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'bazar_orders_export'",
            Integer.class, tenant)).isEqualTo(1);
        assertThat(bytes(worker, ev(e + "/report/orders.csv")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bytes(ownerOther, ev(e + "/report/orders.csv")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void r2_hasSizes_trueWhenAProductHasSizes() {
        UUID v = variant("Sized shirt", "M", "300.00");
        String p = piece(v, true, 5);
        String e = draft();
        scanOut(e, p);
        sendAndStart(e);
        assertThat(call(owner, HttpMethod.GET, ev(e + "/report"), null).getBody().get("has_sizes")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void r3_reportPdf_page1_moneyByDay_methods_hours_belowShopify_ownerOrManagerOnly() throws Exception {
        UUID v = variant("Pdf shirt", "M", "1000.00");
        String p1 = piece(v, true, 5), p2 = piece(v, true, 4);
        String e = draft(Map.of("discountPct", 20, "paymentMethods", List.of("cash", "card")));
        scanOut(e, p1);
        scanOut(e, p2);
        sendAndStart(e);
        Map s1 = sell(e, p1);
        jdbc.update("UPDATE bazar_sales SET sold_at = (current_date + time '18:20') AT TIME ZONE 'Africa/Cairo' WHERE id = ?::uuid", s1.get("id"));

        ResponseEntity<byte[]> pdf = bytes(manager, ev(e + "/report.pdf"));
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        String text = pdfText(pdf.getBody());
        String name = jdbc.queryForObject("SELECT name FROM bazar_events WHERE id = ?::uuid", String.class, e);
        assertThat(text).contains(name).contains("Bazar event report").contains("MONEY BY DAY")
            .contains("BY PAYMENT METHOD").contains("Cash").contains("Card").contains("PIECES SOLD PER HOUR")
            .contains("18:00").contains("BELOW SHOPIFY").contains("200")   // 1000 − 800 on the one piece kept
            .contains("800").contains("Not closed").contains("Page 1 of 1");
        try (PDDocument doc = Loader.loadPDF(pdf.getBody())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
        assertThat(pdfText(bytes(owner, ev(e + "/report.pdf?lang=ar")).getBody())).contains(name);
        assertThat(bytes(worker, ev(e + "/report.pdf")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bytes(ownerOther, ev(e + "/report.pdf")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── s: price sheet columns ───────────────────────────────────────────────

    @Test
    void s1_priceSheet_colourColumnNamedAfterTheShopifyOption_emptyColumnsHidden() throws Exception {
        String raw = "{\"options\":[{\"name\":\"Material\"},{\"name\":\"Size\"}]}";
        UUID cotton = variant("Sheet shirt", "Cotton / M", "300.00", raw, "{\"option1\":\"Cotton\",\"option2\":\"M\"}");
        variantOf(productOf(cotton), "Linen / L", "300.00", "{\"option1\":\"Linen\",\"option2\":\"L\"}");
        String e = draft();
        scanOut(e, piece(cotton, true, 5));
        sendAndStart(e);
        String text = pdfText(bytes(owner, ev(e + "/price-sheet.pdf")).getBody());
        assertThat(text).contains("Material").doesNotContain("Colour").contains("Sizes at the bazar");

        // Products with no options at all: neither a colour nor a sizes column.
        UUID plain = variant("Sheet mug", "Default Title", "150.00");
        String e2 = draft();
        scanOut(e2, piece(plain, true, 5));
        sendAndStart(e2);
        String text2 = pdfText(bytes(owner, ev(e2 + "/price-sheet.pdf")).getBody());
        assertThat(text2).contains("Sheet mug").contains("150").doesNotContain("Variant").doesNotContain("Colour")
            .doesNotContain("Sizes at the bazar");

        // Colours with no stored option names → "Variant".
        UUID red = variant("Sheet scarf", "Red", "90.00");
        variantOf(productOf(red), "Blue", "90.00", "{}");
        String e3 = draft();
        scanOut(e3, piece(red, true, 5));
        sendAndStart(e3);
        assertThat(pdfText(bytes(owner, ev(e3 + "/price-sheet.pdf")).getBody())).contains("Variant").doesNotContain("Colour");
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
