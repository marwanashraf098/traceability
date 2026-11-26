package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bazar customer details on request — POST /api/v1/bazar-customers/{search, export, delete-preview, delete}, with the
 * WHOLE app running as app_user (RLS on, the real HTTP path).
 *
 * Fixture, tenant A: bazar 1 — D1 quick sale (Mona Ali, 01012345678), a cart D2 + D3 (customer typed as
 * "+20 10 1234 5678", 50 off), D4 quick sale with Mona's phone then undone; bazar 2 — D5 (name only "Mona Ali") and
 * D6 (Karim, 01198765432 — never touched). Tenant B: one sale with the SAME phone and name.
 *
 * Tests run in name order (a search, b who, c export, d another store, e delete — the delete last).
 *
 * Revert checks (each turns the named test red): normalizePhone not applied in search (a1); undone rows left out of
 * export (c1) / of the delete (e1–e3); the UPDATE also zeroing discount (e3 totals); a key put in the export audit
 * metadata (c2); the expected-rows check removed (e2–e3); delete without {@code @Transactional} (d2, e2, e3). Dropping
 * the explicit tenant filter from the delete's lookup and UPDATE stays GREEN — RLS on app_user still confines it to
 * the caller's store (d2 is that proof).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class BazarCustomerDataHttpAppUserTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
        try (var c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres");
             var st = c.createStatement()) {
            st.execute("CREATE ROLE app_user LOGIN PASSWORD 'testpw'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "app_user");
        r.add("spring.datasource.password", () -> "testpw");
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.shopify-returns.backfill.enabled", () -> "false");
        r.add("shopify.webhook-topics-backfill.enabled", () -> "false");
        r.add("traced.catalog-backfill.enabled", () -> "false");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyOrderPriceGateway prices;
    @MockBean JobScheduler jobScheduler;
    @MockBean BostaV2Client bostaV2;

    static final String MONA_PHONE = "01012345678";
    static final String MONA_NAME_KEY = "name:mona ali";
    static final String KARIM_PHONE = "01198765432";

    /** Fixtures and assertions only (postgres); the app never uses it. */
    JdbcTemplate pg;
    UUID tenantA, tenantB, tenantOff;
    String owner, manager, worker, ownerB, ownerOff, ev1, ev2, evB;
    String saleD6;
    Map<String, Object> report1Before, report2Before;
    String orders1Before, orders2Before;
    List<Map<String, Object>> moneyBefore;

    @BeforeAll
    void setup() {
        pg = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        tenantOff = UUID.randomUUID();
        UUID mainA = tenant(tenantA, "Cd A", true), mainB = tenant(tenantB, "Cd B", true);
        tenant(tenantOff, "Cd Off", false);
        owner = login(user(tenantA, "owner"));
        manager = login(user(tenantA, "manager"));
        worker = login(user(tenantA, "worker"));
        ownerB = login(user(tenantB, "owner"));
        ownerOff = login(user(tenantOff, "owner"));

        UUID variantA = catalog(tenantA, mainA, "cd-a", "Linen Dress", "D", 6);
        UUID variantB = catalog(tenantB, mainB, "cd-b", "Wool Coat", "E", 1);
        assertThat(variantA).isNotNull();
        assertThat(variantB).isNotNull();

        ev1 = liveEvent(owner, "Zamalek Bazar", "Zamalek Hall", "D0000001", "D0000002", "D0000003", "D0000004");
        ev2 = liveEvent(owner, "Maadi Bazar", "Maadi Hall", "D0000005", "D0000006");
        evB = liveEvent(ownerB, "Other Bazar", "Other Hall", "E0000001");

        String d1 = sell(owner, ev1, "D0000001");
        customer(owner, ev1, d1, Map.of("name", "Mona Ali", "phone", MONA_PHONE));
        ResponseEntity<Map> cart = call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev1 + "/cart/complete", Map.of(
            "codes", List.of("D0000002", "D0000003"), "discount", 50, "method", "card",
            "customer", Map.of("name", "Mona Ali", "phone", "+20 10 1234 5678"), "client_request_id", "c-" + UUID.randomUUID()));
        assertThat(cart.getStatusCode()).as(String.valueOf(cart.getBody())).isEqualTo(HttpStatus.OK);
        String d4 = sell(owner, ev1, "D0000004");
        customer(owner, ev1, d4, Map.of("phone", MONA_PHONE));
        assertThat(call(owner, HttpMethod.POST, "/api/v1/bazar-events/" + ev1 + "/sales/" + d4 + "/undo", Map.of())
            .getStatusCode()).isEqualTo(HttpStatus.OK);

        String d5 = sell(owner, ev2, "D0000005");
        customer(owner, ev2, d5, Map.of("name", " Mona Ali "));
        saleD6 = sell(owner, ev2, "D0000006");
        customer(owner, ev2, saleD6, Map.of("name", "Karim", "phone", KARIM_PHONE));

        String e1 = sell(ownerB, evB, "E0000001");
        customer(ownerB, evB, e1, Map.of("name", "Mona Ali", "phone", MONA_PHONE));

        report1Before = report(ev1);
        report2Before = report(ev2);
        orders1Before = text(owner, "/api/v1/bazar-events/" + ev1 + "/report/orders.csv");
        orders2Before = text(owner, "/api/v1/bazar-events/" + ev2 + "/report/orders.csv");
        moneyBefore = money();
    }

    // ── search ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void a1_phoneInEveryUsualShape_findsTheSameCustomer() {
        for (String q : List.of("01012345678", "+20 10 1234 5678", "0020 101-234-5678", "201012345678", "1012345678",
                                "٠١٠١٢٣٤٥٦٧٨")) {
            List<Map<String, Object>> found = customers(owner, q);
            assertThat(found).as(q).hasSize(1);
            Map<String, Object> c = found.get(0);
            assertThat(c.get("key")).as(q).isEqualTo(MONA_PHONE);
            assertThat(c.get("phone")).isEqualTo(MONA_PHONE);
            assertThat(c.get("name")).isEqualTo("Mona Ali");
            assertThat(c.get("sales")).as("D1, D2, D3 and the undone D4").isEqualTo(4);
            assertThat(c.get("pieces")).as("undone D4 not kept").isEqualTo(3);
            assertThat(c.get("bazars")).isEqualTo(1);
        }
    }

    @Test
    void a2_nameSearch_findsPhoneAndNameOnlyCustomers_caseInsensitive() {
        List<Map<String, Object>> found = customers(owner, "MONA");
        assertThat(found).extracting(c -> c.get("key")).containsExactlyInAnyOrder(MONA_PHONE, MONA_NAME_KEY);
        Map<String, Object> nameOnly = found.stream().filter(c -> MONA_NAME_KEY.equals(c.get("key"))).findFirst().orElseThrow();
        assertThat(nameOnly.get("phone")).isNull();
        assertThat(nameOnly.get("sales")).isEqualTo(1);
        assertThat(customers(owner, "kar")).extracting(c -> c.get("key")).containsExactly(KARIM_PHONE);
        assertThat(customers(owner, "%%")).as("LIKE wildcards are literal").isEmpty();
        assertThat(customers(owner, "a_i")).as("_ is literal too").isEmpty();
    }

    @Test
    void a3_badQueries_areRefused() {
        assertThat(post(owner, "/search", Map.of("q", "010123")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(owner, "/search", Map.of("q", "m")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(owner, "/search", Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── who ──────────────────────────────────────────────────────────────────

    @Test
    void b1_manager_searchesAndExports_butCantDelete_workerGetsNothing() {
        assertThat(customers(manager, MONA_PHONE)).hasSize(1);
        assertThat(bytes(manager, "/export", Map.of("keys", List.of(KARIM_PHONE))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(manager, "/delete-preview", Map.of("keys", List.of(MONA_PHONE))).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post(manager, "/delete", Map.of("keys", List.of(MONA_PHONE), "expected_rows", 4)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        for (String path : List.of("/search", "/export", "/delete-preview", "/delete")) {
            assertThat(bytes(worker, path, Map.of("q", MONA_PHONE, "keys", List.of(MONA_PHONE), "expected_rows", 4))
                .getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(post(ownerOff, "/search", Map.of("q", MONA_PHONE)).getStatusCode())
            .as("bazar off for the store").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cleared(tenantA)).isZero();
    }

    // ── export ───────────────────────────────────────────────────────────────

    @Test
    void c1_export_everyRowOfTheTickedCustomers_includingUndone() {
        ResponseEntity<byte[]> r = bytes(owner, "/export", Map.of("keys", List.of(MONA_PHONE, MONA_NAME_KEY)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/csv");
        String[] lines = new String(r.getBody(), StandardCharsets.UTF_8).split("\n");
        assertThat(lines[0]).isEqualTo("bazar,day,time,number,product,variant,sku,piece,price,discount,net,method,status," +
            "customer_name,customer_phone");
        List<String> rows = Arrays.asList(lines).subList(1, lines.length);
        assertThat(rows).hasSize(5);
        assertThat(rows).noneMatch(l -> l.contains("Karim") || l.contains(KARIM_PHONE));
        assertThat(rows).filteredOn(l -> l.contains("D0000004")).singleElement()
            .satisfies(l -> assertThat(l).contains(",undone,").contains(MONA_PHONE));
        assertThat(rows).filteredOn(l -> l.contains("D0000005")).singleElement()
            .satisfies(l -> assertThat(l).startsWith("Maadi Bazar,").contains(",sold,Mona Ali,"));
        assertThat(rows).filteredOn(l -> l.contains("D0000002") || l.contains("D0000003")).hasSize(2)
            .allSatisfy(l -> assertThat(l).startsWith("Zamalek Bazar,").contains(",card,sold,Mona Ali," + MONA_PHONE));
    }

    @Test
    void c2_exportAudit_countsOnly_noPersonalData() {
        int before = audits("bazar_customer_export");
        assertThat(bytes(owner, "/export", Map.of("keys", List.of(MONA_PHONE, MONA_NAME_KEY))).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(audits("bazar_customer_export")).isEqualTo(before + 1);
        String meta = pg.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = " +
            "'bazar_customer_export' ORDER BY created_at DESC LIMIT 1", String.class, tenantA);
        assertThat(meta).contains("\"rows\": 5").contains("\"bazars\": 2").contains("\"customers\": 2");
        assertThat(meta.toLowerCase()).doesNotContain("mona").doesNotContain("1012345678");
    }

    // ── another store ────────────────────────────────────────────────────────

    @Test
    void d1_anotherStore_findsOnlyItsOwn_andExportsOnlyItsOwn() {
        List<Map<String, Object>> found = customers(ownerB, MONA_PHONE);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("sales")).as("B's one sale, never A's four").isEqualTo(1);
        assertThat(customers(ownerB, "karim")).isEmpty();
        assertThat(bytes(ownerB, "/export", Map.of("keys", List.of(KARIM_PHONE))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bytes(ownerB, "/export", Map.of("keys", List.of(MONA_NAME_KEY))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String csv = new String(bytes(ownerB, "/export", Map.of("keys", List.of(MONA_PHONE))).getBody(), StandardCharsets.UTF_8);
        assertThat(csv.split("\n")).hasSize(2);
        assertThat(csv).contains("Other Bazar").doesNotContain("Zamalek").doesNotContain("D0000001");
    }

    @Test
    void d2_anotherStoresDelete_clearsOnlyItsOwnRow() {
        assertThat(post(ownerB, "/delete-preview", Map.of("keys", List.of(KARIM_PHONE))).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(ownerB, "/delete", Map.of("keys", List.of(KARIM_PHONE), "expected_rows", 1)).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> d = post(ownerB, "/delete", Map.of("keys", List.of(MONA_PHONE), "expected_rows", 1));
        assertThat(d.getStatusCode()).as(String.valueOf(d.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(d.getBody().get("rows")).isEqualTo(1);
        assertThat(cleared(tenantB)).isEqualTo(1);
        assertThat(cleared(tenantA)).as("A's rows with the same phone untouched").isZero();
        assertThat(pg.queryForObject("SELECT count(*) FROM bazar_sales WHERE tenant_id = ? AND customer_phone = ?",
            Integer.class, tenantA, MONA_PHONE)).isEqualTo(4);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void e1_preview_countsWithoutChangingAnything() {
        ResponseEntity<Map> p = post(owner, "/delete-preview", Map.of("keys", List.of(MONA_PHONE, MONA_NAME_KEY)));
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(p.getBody()).containsEntry("rows", 5).containsEntry("receipts", 1).containsEntry("bazars", 2);
        assertThat(cleared(tenantA)).isZero();
    }

    @Test
    void e2_aStaleCount_isRefused_andChangesNothing() {
        ResponseEntity<Map> r = post(owner, "/delete", Map.of("keys", List.of(MONA_PHONE, MONA_NAME_KEY), "expected_rows", 4));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("BAZAR_CUSTOMER_CHANGED");
        assertThat(post(owner, "/delete", Map.of("keys", List.of(MONA_PHONE))).getStatusCode())
            .as("no preview count").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(owner, "/delete", Map.of("keys", List.of("Mona"), "expected_rows", 1)).getStatusCode())
            .as("not a key").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cleared(tenantA)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void e3_delete_clearsNameAndPhoneEverywhere_keepsEveryTotal() {
        int customers1 = (Integer) report1Before.get("customers_saved");
        int customers2 = (Integer) report2Before.get("customers_saved");
        assertThat(customers1).isEqualTo(1);
        assertThat(customers2).isEqualTo(2);

        ResponseEntity<Map> d = post(owner, "/delete", Map.of("keys", List.of(MONA_PHONE, MONA_NAME_KEY), "expected_rows", 5));
        assertThat(d.getStatusCode()).as(String.valueOf(d.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(d.getBody()).containsEntry("rows", 5).containsEntry("receipts", 1).containsEntry("bazars", 2);

        // Every Mona row cleared — undone D4 included; Karim and the other store's rows as they were.
        assertThat(cleared(tenantA)).isEqualTo(5);
        assertThat(pg.queryForObject("SELECT count(*) FROM bazar_sales WHERE tenant_id = ? AND (customer_phone = ? " +
            "OR lower(customer_name) LIKE '%mona%')", Integer.class, tenantA, MONA_PHONE)).isZero();
        assertThat(pg.queryForMap("SELECT customer_name, customer_phone FROM bazar_sales WHERE id = ?::uuid", saleD6))
            .containsEntry("customer_name", "Karim").containsEntry("customer_phone", KARIM_PHONE);

        // Money and every report figure the same; only the Customers card goes down.
        assertThat(money()).isEqualTo(moneyBefore);
        Map<String, Object> r1 = report(ev1), r2 = report(ev2);
        assertThat(r1.get("customers_saved")).isEqualTo(0);
        assertThat(r2.get("customers_saved")).isEqualTo(1);
        assertThat(without(r1)).isEqualTo(without(report1Before));
        assertThat(without(r2)).isEqualTo(without(report2Before));

        // customers.csv: header only for bazar 1, Karim alone for bazar 2.
        assertThat(text(owner, "/api/v1/bazar-events/" + ev1 + "/report/customers.csv").replace("\uFEFF", "").trim())
            .isEqualTo("name,phone,pieces,total,last_visit");
        String c2 = text(owner, "/api/v1/bazar-events/" + ev2 + "/report/customers.csv");
        assertThat(c2).contains("Karim").doesNotContain("Mona");
        // orders.csv: the same rows, without the customer columns' content.
        String o1 = text(owner, "/api/v1/bazar-events/" + ev1 + "/report/orders.csv");
        String o2 = text(owner, "/api/v1/bazar-events/" + ev2 + "/report/orders.csv");
        assertThat(o1).doesNotContain("Mona").doesNotContain(MONA_PHONE);
        assertThat(o2).doesNotContain("Mona").contains("Karim");
        assertThat(o1).isEqualTo(orders1Before.replace("Mona Ali", "").replace(MONA_PHONE, ""));
        assertThat(o2).isEqualTo(orders2Before.replace("Mona Ali", "").replace(MONA_PHONE, ""));
        // The sales list and the receipt carry no customer any more.
        String list = String.valueOf(call(owner, HttpMethod.GET, "/api/v1/bazar-events/" + ev1 + "/sales?limit=50", null).getBody());
        assertThat(list).doesNotContain("Mona").doesNotContain(MONA_PHONE);

        // The search finds nobody; a second delete has nothing to delete.
        assertThat(customers(owner, "mona")).isEmpty();
        assertThat(customers(owner, MONA_PHONE)).isEmpty();
        assertThat(post(owner, "/delete-preview", Map.of("keys", List.of(MONA_PHONE))).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        // Logged: who, when, how many — never the name or phone.
        Map<String, Object> a = pg.queryForMap("SELECT actor_user_id, created_at, metadata::text AS meta FROM audit_log " +
            "WHERE tenant_id = ? AND action = 'bazar_customer_delete'", tenantA);
        assertThat(a.get("actor_user_id")).isNotNull();
        assertThat(a.get("created_at")).isNotNull();
        assertThat((String) a.get("meta")).contains("\"rows\": 5").contains("\"receipts\": 1").contains("\"bazars\": 2");
        assertThat(((String) a.get("meta")).toLowerCase()).doesNotContain("mona").doesNotContain("1012345678");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private int cleared(UUID t) {
        return pg.queryForObject("SELECT count(*) FROM bazar_sales WHERE tenant_id = ? AND customer_name IS NULL " +
            "AND customer_phone IS NULL", Integer.class, t);
    }

    private int audits(String action) {
        return pg.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Integer.class, tenantA, action);
    }

    /** Every money-bearing column of A's bazar sales, receipts and payments. */
    private List<Map<String, Object>> money() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(pg.queryForList("SELECT id, price, discount, net_price, status, receipt_id, line_no, sale_number, " +
            "event_id, piece_id FROM bazar_sales WHERE tenant_id = ? ORDER BY id", tenantA));
        out.addAll(pg.queryForList("SELECT id, subtotal, discount, total, status FROM bazar_receipts WHERE tenant_id = ? " +
            "ORDER BY id", tenantA));
        out.addAll(pg.queryForList("SELECT id, method, amount, kind FROM bazar_payments WHERE tenant_id = ? ORDER BY id", tenantA));
        return out;
    }

    private static Map<String, Object> without(Map<String, Object> report) {
        Map<String, Object> m = new LinkedHashMap<>(report);
        m.remove("customers_saved");
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> report(String ev) {
        ResponseEntity<Map> r = call(owner, HttpMethod.GET, "/api/v1/bazar-events/" + ev + "/report", null);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> customers(String token, String q) {
        ResponseEntity<Map> r = post(token, "/search", Map.of("q", q));
        assertThat(r.getStatusCode()).as(q + " " + r.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("customers");
    }

    private UUID tenant(UUID id, String name, boolean bazar) {
        pg.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, ?, ?)", id, name, bazar);
        UUID main = UUID.randomUUID();
        pg.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", main, id);
        return main;
    }

    private UUID catalog(UUID t, UUID main, String shop, String title, String prefix, int pieces) {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        pg.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', ?, 'disconnected')", store, t, shop + ".myshopify.com");
        pg.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'active', '{}'::jsonb)", product, t, store, "P-" + product, title);
        pg.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, price, raw) " +
            "VALUES (?, ?, ?, ?, 'M', 500::numeric, '{}'::jsonb)", variant, t, product, "V-" + variant);
        for (int i = 1; i <= pieces; i++) {
            String piece = UlidGenerator.generate();
            pg.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, ?, 'available', ?)", piece, t, variant, "PC-" + piece,
                String.format("%s%07d", prefix, i), main);
        }
        return variant;
    }

    @SuppressWarnings("rawtypes")
    private String liveEvent(String token, String name, String location, String... codes) {
        ResponseEntity<Map> created = call(token, HttpMethod.POST, "/api/v1/bazar-events", Map.of("name", name,
            "startsOn", "2026-12-01", "endsOn", "2026-12-02", "sellerRoles", List.of("owner"), "locationName", location,
            "paymentMethods", List.of("cash", "card")));
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        for (String c : codes) {
            ResponseEntity<Map> out = call(token, HttpMethod.POST, "/api/v1/bazar-events/" + id + "/scan-out", Map.of("barcode", c));
            assertThat(out.getBody().get("success")).as(String.valueOf(out.getBody())).isEqualTo(true);
        }
        assertThat(call(token, HttpMethod.POST, "/api/v1/bazar-events/" + id + "/mark-sent", Map.of()).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(call(token, HttpMethod.POST, "/api/v1/bazar-events/" + id + "/start", Map.of()).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        return id;
    }

    @SuppressWarnings("rawtypes")
    private String sell(String token, String ev, String code) {
        ResponseEntity<Map> sold = call(token, HttpMethod.POST, "/api/v1/bazar-events/" + ev + "/sell",
            Map.of("code", code, "client_request_id", "c-" + UUID.randomUUID(), "method", "cash"));
        assertThat(sold.getStatusCode()).as(String.valueOf(sold.getBody())).isEqualTo(HttpStatus.OK);
        return String.valueOf(sold.getBody().get("id"));
    }

    private void customer(String token, String ev, String sale, Map<String, Object> body) {
        ResponseEntity<Map> r = call(token, HttpMethod.PUT, "/api/v1/bazar-events/" + ev + "/sales/" + sale + "/customer", body);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
    }

    private String user(UUID t, String role) {
        String email = role + "-" + UUID.randomUUID() + "@cd-appuser.test";
        pg.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            UUID.randomUUID(), t, "Cd " + role, email, passwordEncoder.encode("pass123"), role);
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
    private ResponseEntity<Map> post(String token, String path, Object body) {
        return call(token, HttpMethod.POST, "/api/v1/bazar-customers" + path, body);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<byte[]> bytes(String token, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + "/api/v1/bazar-customers" + path, HttpMethod.POST, new HttpEntity<>(body, h), byte[].class);
    }

    private String text(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<byte[]> r = rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return new String(r.getBody(), StandardCharsets.UTF_8);
    }
}
