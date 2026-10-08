package com.traceability;

import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.CustomerAnalyticsService;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.ShipmentSettlement;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Analytics slice 6 — customers since connect: new / existing / returning / unknown, repeat rate,
 * days between orders, revenue and success per class, top customers, repeat rate by governorate,
 * monthly cohorts, the refused-COD watch list (blocklist read-only), V154, no phone / key in any
 * response, roles and tenant isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsCustomersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    static final Instant CONNECT = Instant.parse("2025-06-01T00:00:00Z");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired AnalyticsFloorOverrides floorOverrides;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    static Instant daysAgo(int d) {
        return Instant.now().minus(Duration.ofDays(d));
    }

    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID product = UUID.randomUUID();
        final UUID variant = UUID.randomUUID();
        final String ownerToken;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@s6.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)", store, id, "s6-" + id + ".myshopify.com", Timestamp.from(CONNECT));
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Tee')",
                        product, id, store, "P-" + product);
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) " +
                        "VALUES (?, ?, ?, ?, 'SKU', 'M', 100)", variant, id, product, "V-" + variant);
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        /** A Shopify customer (REST id + created_at). */
        C customer(String name, String phone, Instant createdAt) {
            return new C(SEQ.incrementAndGet(), name, phone, createdAt);
        }

        /**
         * An order of {@code c} (null = no customer at all) placed {@code days} ago for {@code price},
         * with outcome delivered / refused / in_transit (a Bosta leg in {@code city}, COD {@code cod}) or
         * not_shipped (no leg).
         */
        UUID order(C c, int days, String price, String outcome, String city, int cod) {
            UUID o = UUID.randomUUID();
            String raw = c == null ? "{}"
                : c.createdAt == null ? "{\"shipping_address\":{\"phone\":\"" + c.phone + "\"}}"
                : "{\"customer\":{\"id\":" + c.id + ",\"created_at\":\"" + c.createdAt + "\"}}";
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, customer_name, customer_phone) " +
                        "VALUES (?, ?, ?, ?, ?, 'new'::order_status, ?, ?::jsonb, ?, ?)",
                        o, id, store, "EXT-" + o, "#" + SEQ.incrementAndGet(), Timestamp.from(daysAgo(days)), raw,
                        c == null ? null : c.name, c == null ? null : c.phone);
            long lineId = SEQ.incrementAndGet();
            jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, 1, ?, ?::jsonb)", id, o, variant, "gid://shopify/LineItem/" + lineId,
                        "{\"id\":" + lineId + ",\"price\":\"" + price + "\",\"quantity\":1,\"current_quantity\":1,\"discount_allocations\":[]}");
            if (!"not_shipped".equals(outcome)) {
                String state = switch (outcome) { case "delivered" -> "delivered"; case "refused" -> "returned"; default -> "with_courier"; };
                UUID s = UUID.randomUUID();
                String legRaw = "{\"type\":{\"code\":10},\"cod\":" + cod + ",\"shipmentFees\":50," +
                                "\"dropOffAddress\":{\"city\":{\"_id\":\"id-" + city + "\",\"name\":\"" + city + "\"}}}";
                jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw) " +
                            "VALUES (?, ?, ?, ?, ?::shipment_internal_state, 'forward', ?::jsonb)",
                            s, id, o, "8" + SEQ.incrementAndGet(), state, legRaw);
                ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(legRaw));
            }
            return o;
        }

        UUID order(C c, int days, String outcome) {
            return order(c, days, "100.00", outcome, "Cairo", 100);
        }

        String token(String role) {
            UUID u = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'x', ?::user_role)",
                        u, id, role + "+" + u + "@s6.test", role);
            return jwt.issueAccessToken(u, id, role);
        }
    }

    record C(long id, String name, String phone, Instant createdAt) {}

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<String> raw(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private ResponseEntity<Map> get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private Map<String, Object> ok(T t, String path) {
        ResponseEntity<Map> r = get(t.ownerToken, path);
        assertThat(r.getStatusCode()).as("body: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    static long n(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    static BigDecimal dec(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) out.put((String) r.get("key"), r);
        return out;
    }

    // ── summary ──────────────────────────────────────────────────────────────

    @Test
    void summary_classesRepeatRateDaysBetweenOrdersAndRevenuePerClass_withThePreviousPeriod() {
        T t = new T("S6-Summary");
        C existing = t.customer("Eman Fathy", "01011111111", Instant.parse("2025-01-01T00:00:00Z"));  // before connect
        C newbie = t.customer("Nour Ali", "01022222222", daysAgo(10));
        C back = t.customer("Rana Samir", "01033333333", Instant.parse("2025-07-01T00:00:00Z"));
        C phoneOnly = t.customer("Unknown Person", "01044444444", null);
        t.order(existing, 40, "delivered");
        t.order(existing, 5, "delivered");
        t.order(newbie, 10, "delivered");
        t.order(newbie, 3, "refused");
        t.order(back, 50, "delivered");
        t.order(back, 6, "delivered");
        t.order(phoneOnly, 8, "not_shipped");
        t.order(null, 4, "not_shipped");                          // no customer at all

        Map<String, Object> body = ok(t, "/api/v1/analytics/customers/summary?period=30d");
        Map<String, Object> cur = m(body, "current");
        assertThat(n(cur, "customersWhoOrdered")).isEqualTo(4);
        assertThat(n(cur, "newCustomers")).isEqualTo(1);
        assertThat(n(cur, "existingCustomers")).isEqualTo(1);
        assertThat(n(cur, "returningCustomers")).isEqualTo(1);
        assertThat(n(cur, "unknownCustomers")).isEqualTo(1);
        assertThat(dec(cur, "repeatPurchaseRate")).isEqualByComparingTo("0.75");      // 3 of 4 have 2+ orders
        assertThat(dec(cur, "medianDaysBetweenOrders")).isEqualByComparingTo("35.0");  // 35, 7, 44
        assertThat(n(cur, "ordersWithoutCustomer")).isEqualTo(1);

        Map<String, Map<String, Object>> cls = byKey(list(cur, "byClass"));
        assertThat(cls.keySet()).containsExactly("new", "existing", "returning", "unknown");
        assertThat(cls.get("new")).containsEntry("customers", 1).containsEntry("orders", 1).containsEntry("delivered", 1);
        assertThat(dec(cls.get("new"), "realized")).isEqualByComparingTo("100.00");
        assertThat(cls.get("returning")).containsEntry("customers", 1).containsEntry("orders", 2)   // newbie's 2nd + back's 2nd
            .containsEntry("delivered", 1).containsEntry("failed", 1);
        assertThat(dec(cls.get("returning"), "successRate")).isEqualByComparingTo("0.5");
        assertThat(cls.get("existing")).containsEntry("orders", 1);
        assertThat(cls.get("unknown")).containsEntry("orders", 1).containsEntry("delivered", 0);

        Map<String, Object> prev = m(body, "previous");                // 31–60 days ago
        assertThat(n(prev, "customersWhoOrdered")).isEqualTo(2);
        assertThat(n(prev, "existingCustomers")).isEqualTo(1);
        assertThat(n(prev, "newCustomers")).isEqualTo(1);              // back's first order since connect
        assertThat(prev.get("medianDaysBetweenOrders")).isNull();
    }

    // ── top + no PII ─────────────────────────────────────────────────────────

    @Test
    void top_byRealizedSinceConnect_displayNameOnly_neverAPhoneOrTheKey() {
        T t = new T("S6-Top");
        C big = t.customer("Mona Adel Hassan", "01055555555", daysAgo(100));
        C small = t.customer("Omar Said", "01066666666", daysAgo(100));
        C phoneOnly = t.customer("Phone Only", "01077777777", null);
        t.order(big, 20, "500.00", "delivered", "Cairo", 500);
        t.order(big, 10, "300.00", "delivered", "Cairo", 300);
        t.order(big, 5, "200.00", "refused", "Cairo", 200);
        t.order(small, 15, "100.00", "delivered", "Giza", 100);
        t.order(phoneOnly, 3, "900.00", "in_transit", "Giza", 900);

        Map<String, Object> body = ok(t, "/api/v1/analytics/customers/top");
        assertThat(n(body, "totalCustomers")).isEqualTo(3);
        List<Map<String, Object>> top = list(body, "customers");
        assertThat(top).extracting(c -> c.get("displayName")).containsExactly("Mona H.", "Omar S.", "Phone O.");
        assertThat(dec(top.get(0), "realized")).isEqualByComparingTo("800.00");
        assertThat(top.get(0)).containsEntry("orders", 3).containsEntry("delivered", 2).containsEntry("failed", 1)
            .containsEntry("customerType", "new").containsEntry("governorate", "Cairo");
        assertThat(dec(top.get(0), "successRate")).isEqualByComparingTo("0.6667");
        assertThat(top.get(2)).containsEntry("customerType", "unknown");
        assertThat((String) top.get(0).get("customerRef")).hasSize(16).isNotEqualTo(top.get(1).get("customerRef"));
        assertThat(list(ok(t, "/api/v1/analytics/customers/top?limit=1"), "customers")).hasSize(1);
        assertThat(get(t.ownerToken, "/api/v1/analytics/customers/top?limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(t.ownerToken, "/api/v1/analytics/customers/top?limit=51").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        for (String path : List.of("/summary?period=30d", "/top", "/by-governorate", "/cohorts", "/watch")) {
            String json = raw(t.ownerToken, "/api/v1/analytics/customers" + path).getBody();
            for (C c : List.of(big, small, phoneOnly)) {
                assertThat(json).as(path).doesNotContain(c.phone().substring(1)).doesNotContain("c:" + c.id())
                    .doesNotContain(String.valueOf(c.id()));
            }
            assertThat(json).as(path).doesNotContain("Hassan").doesNotContain("Adel").doesNotContain("p:0");
        }
    }

    // ── by governorate ───────────────────────────────────────────────────────

    @Test
    void byGovernorate_repeatRate_withSmallGovernoratesMergedIntoOther() {
        T t = new T("S6-Gov");
        for (int i = 0; i < 10; i++) {                            // Cairo: 10 customers, 3 order twice
            C c = t.customer("Cairo " + i, "0101000000" + i, daysAgo(200));
            t.order(c, 20, "100.00", "delivered", "Cairo", 100);
            if (i < 3) t.order(c, 5, "100.00", "delivered", "Cairo", 100);
        }
        for (int i = 0; i < 4; i++) {                             // Giza: 4 customers → Other
            C c = t.customer("Giza " + i, "0102000000" + i, daysAgo(200));
            t.order(c, 20, "100.00", "delivered", "Giza", 100);
            if (i == 0) t.order(c, 5, "100.00", "delivered", "Giza", 100);
        }
        Map<String, Object> body = ok(t, "/api/v1/analytics/customers/by-governorate");
        assertThat(n(body, "minCustomers")).isEqualTo(10);
        List<Map<String, Object>> g = list(body, "governorates");
        assertThat(g).extracting(x -> x.get("key")).containsExactly("id-Cairo", "other");
        assertThat(g.get(0)).containsEntry("customers", 10).containsEntry("repeatCustomers", 3);
        assertThat(dec(g.get(0), "repeatRate")).isEqualByComparingTo("0.3");
        assertThat(g.get(1)).containsEntry("label", "Other").containsEntry("customers", 4).containsEntry("repeatCustomers", 1);
    }

    // ── cohorts ──────────────────────────────────────────────────────────────

    @Test
    void cohorts_byFirstOrderMonth_orderedAgainInMonths1To3_futureMonthsNull() {
        T t = new T("S6-Cohorts");
        YearMonth now = YearMonth.now(CAIRO);
        YearMonth first = now.minusMonths(3);
        List<C> cs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            C c = t.customer("Cohort " + i, "0103000000" + i, daysAgo(400));
            orderIn(t, c, first);
            cs.add(c);
        }
        orderIn(t, cs.get(0), first.plusMonths(1));               // month 1: 2 of 4
        orderIn(t, cs.get(1), first.plusMonths(1));
        orderIn(t, cs.get(0), first.plusMonths(2));               // month 2: 1 of 4
        C recent = t.customer("Recent", "01039999999", daysAgo(400));
        orderIn(t, recent, now);                                  // this month's cohort: nothing to measure yet

        List<Map<String, Object>> cohorts = list(ok(t, "/api/v1/analytics/customers/cohorts"), "cohorts");
        assertThat(cohorts).extracting(c -> c.get("month")).containsExactly(first.toString(), now.toString());
        Map<String, Object> c0 = cohorts.get(0);
        assertThat(c0).containsEntry("customers", 4).containsEntry("existingCustomers", 0);
        @SuppressWarnings("unchecked") List<Object> pct = (List<Object>) c0.get("orderedAgainPct");
        assertThat(new BigDecimal(pct.get(0).toString())).isEqualByComparingTo("0.5");
        assertThat(new BigDecimal(pct.get(1).toString())).isEqualByComparingTo("0.25");
        assertThat(new BigDecimal(pct.get(2).toString())).isEqualByComparingTo("0");       // this month, partial
        assertThat(c0.get("monthComplete")).isEqualTo(List.of(true, true, false));
        assertThat(cohorts.get(1).get("orderedAgainPct")).isEqualTo(Arrays.asList(null, null, null));
    }

    private void orderIn(T t, C c, YearMonth month) {
        LocalDate day = month.atDay(10);
        if (!day.isBefore(LocalDate.now(CAIRO))) day = LocalDate.now(CAIRO);
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(day, LocalDate.now(CAIRO));
        t.order(c, days, "delivered");
    }

    // ── watch ────────────────────────────────────────────────────────────────

    @Test
    void watch_twoOrMoreRefusedCodOrders_blocklistReadOnly() {
        T t = new T("S6-Watch");
        C repeat = t.customer("Hana Mostafa", "+20 100 123 4567", daysAgo(300));
        t.order(repeat, 20, "300.00", "refused", "Giza", 300);
        t.order(repeat, 10, "200.00", "refused", "Giza", 200);
        t.order(repeat, 5, "100.00", "delivered", "Giza", 100);
        C prepaid = t.customer("Pre Paid", "01111111111", daysAgo(300));      // refused, but nothing to collect
        t.order(prepaid, 20, "100.00", "refused", "Cairo", 0);
        t.order(prepaid, 10, "100.00", "refused", "Cairo", 0);
        C once = t.customer("Once Only", "01222222222", daysAgo(300));
        t.order(once, 20, "100.00", "refused", "Cairo", 100);
        jdbc.update("INSERT INTO blocklist (tenant_id, phone_canonical, reason) VALUES (?, '01001234567', 'refused twice')", t.id);
        long blockRows = jdbc.queryForObject("SELECT COUNT(*) FROM blocklist WHERE tenant_id = ?", Long.class, t.id);

        Map<String, Object> body = ok(t, "/api/v1/analytics/customers/watch");
        List<Map<String, Object>> w = list(body, "customers");
        assertThat(w).hasSize(1);
        assertThat(w.get(0)).containsEntry("displayName", "Hana M.").containsEntry("orders", 3)
            .containsEntry("refusedCodOrders", 2).containsEntry("deliveredOrders", 1).containsEntry("blocked", true)
            .containsEntry("suggestion", "Ask for prepayment").containsEntry("blocklistLink", "/blocklist")
            .containsEntry("governorate", "Giza");
        assertThat(dec(w.get(0), "refusedValue")).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blocklist WHERE tenant_id = ?", Long.class, t.id)).isEqualTo(blockRows);
        assertThat(raw(t.ownerToken, "/api/v1/analytics/customers/watch").getBody()).doesNotContain("1234567");
    }

    @Test
    void noFloor_connectIsTheStoresFirstIngestedOrder() {
        T t = new T("S6-NoFloor");
        jdbc.update("UPDATE stores SET orders_ingest_from = NULL WHERE id = ?", t.store);
        C before = t.customer("Old Timer", "01091111111", daysAgo(200));   // created before the store's first order
        C after = t.customer("New Comer", "01092222222", daysAgo(50));
        t.order(before, 100, "delivered");                                // the store's first order: connect = 100 days ago
        t.order(before, 5, "delivered");
        t.order(after, 4, "delivered");
        Map<String, Object> cur = m(ok(t, "/api/v1/analytics/customers/summary?period=30d"), "current");
        assertThat(n(cur, "existingCustomers")).isEqualTo(1);
        assertThat(n(cur, "newCustomers")).isEqualTo(1);
    }

    // ── V154 ─────────────────────────────────────────────────────────────────

    @Test
    void v154_customerCreatedAt_restOrGraphql_garbageIsNull_redactionClearsIt() {
        T t = new T("S6-V154");
        UUID rest = t.order(t.customer("A B", "01000000001", Instant.parse("2025-03-04T05:06:07Z")), 3, "delivered");
        UUID gql = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                    "VALUES (?, ?, ?, ?, '#G1', 'new'::order_status, now(), " +
                    "'{\"customer\":{\"id\":\"gid://shopify/Customer/9\",\"createdAt\":\"2025-08-09T10:11:12Z\"}}'::jsonb)",
                    gql, t.id, t.store, "EXT-" + gql);
        UUID bad = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                    "VALUES (?, ?, ?, ?, '#G2', 'new'::order_status, now(), '{\"customer\":{\"created_at\":\"yesterday\"}}'::jsonb)",
                    bad, t.id, t.store, "EXT-" + bad);
        assertThat(created(rest)).isEqualTo(Instant.parse("2025-03-04T05:06:07Z"));
        assertThat(created(gql)).isEqualTo(Instant.parse("2025-08-09T10:11:12Z"));
        assertThat(created(bad)).isNull();
        jdbc.update("UPDATE orders SET raw = shopify_order_raw_redacted(raw) WHERE id = ?", rest);
        assertThat(created(rest)).isNull();
    }

    Instant created(UUID order) {
        Timestamp ts = jdbc.queryForObject("SELECT customer_created_at FROM orders WHERE id = ?", Timestamp.class, order);
        return ts == null ? null : ts.toInstant();
    }

    // ── roles + isolation ────────────────────────────────────────────────────

    @Test
    void ownerOnly_everyCustomerEndpoint() {
        T t = new T("S6-Roles");
        String manager = t.token("manager"), worker = t.token("worker");
        for (String path : List.of("/summary?period=30d", "/top", "/by-governorate", "/cohorts", "/watch")) {
            String p = "/api/v1/analytics/customers" + path;
            assertThat(get(t.ownerToken, p).getStatusCode()).as(p).isEqualTo(HttpStatus.OK);
            assertThat(get(manager, p).getStatusCode()).as(p).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(worker, p).getStatusCode()).as(p).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void appUser_rls_eachTenantSeesOnlyItsOwnCustomers() {
        T a = new T("S6-IsoA");
        T b = new T("S6-IsoB");
        a.order(a.customer("Alpha One", "01088888881", daysAgo(50)), 3, "delivered");
        b.order(b.customer("Beta Two", "01088888882", daysAgo(50)), 3, "delivered");
        b.order(b.customer("Beta Three", "01088888883", daysAgo(50)), 4, "delivered");

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        CustomerAnalyticsService svc = new CustomerAnalyticsService(new JdbcTemplate(appUserDs), Clock.system(CAIRO), floorOverrides);
        AnalyticsPeriod p = new AnalyticsPeriod(LocalDate.now(CAIRO).minusDays(29), LocalDate.now(CAIRO));

        TenantContext.set(a.id);
        assertThat(tx.execute(s -> svc.summary(p, false)).current().customersWhoOrdered()).isEqualTo(1);
        assertThat(tx.execute(s -> svc.top(50)).customers()).extracting(CustomerAnalyticsService.TopCustomer::displayName)
            .containsExactly("Alpha O.");
        TenantContext.set(b.id);
        assertThat(tx.execute(s -> svc.top(50)).totalCustomers()).isEqualTo(2);
        TenantContext.clear();
        assertThatThrownBy(() -> tx.execute(s -> svc.top(50))).isInstanceOf(RuntimeException.class);
    }
}
