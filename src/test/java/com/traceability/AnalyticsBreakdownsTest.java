package com.traceability;

import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.AnalyticsSql;
import com.traceability.analytics.RevenueAnalyticsService;
import com.traceability.identity.JwtService;
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

/**
 * Analytics slice 5 — revenue & delivery breakdowns (/api/v1/analytics/revenue/*, /delivery/*,
 * /products/extras). Each test builds its own tenant through the postgres connection and reads over
 * HTTP with an OWNER token; the isolation test reads as app_user under RLS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsBreakdownsTest {

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
    static final String SEPT = "from=2026-09-01&to=2026-09-30";
    static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired AnalyticsFloorOverrides floorOverrides;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    static Instant cairo(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(CAIRO).toInstant();
    }

    static final Instant SEPT_10 = cairo(2026, 9, 10, 12, 0);

    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final String ownerToken;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@an5.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, "an5-" + id + ".myshopify.com", Timestamp.from(cairo(2026, 6, 1, 0, 0)));
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        UUID product(String title, String rawJson) {
            UUID p = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, raw) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                        p, id, store, "P-" + p, title, rawJson);
            return p;
        }

        UUID variant(UUID product, String title, String rawJson) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 100, ?::jsonb)", v, id, product, "V-" + v, "SKU-" + title + "-" + v.toString().substring(0, 4),
                        title, rawJson);
            return v;
        }

        UUID variant(String title) {
            return variant(product("Tee", "{}"), title, "{}");
        }

        UUID order(Instant placedAt, String carrierClass, String rawJson) {
            UUID o = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, " +
                        "shipping_carrier_class) VALUES (?, ?, ?, ?, ?, 'new'::order_status, ?, ?::jsonb, ?)",
                        o, id, store, "EXT-" + o, "#" + SEQ.incrementAndGet(), Timestamp.from(placedAt), rawJson, carrierClass);
            return o;
        }

        UUID order(String rawJson) {
            return order(SEPT_10, "bosta", rawJson);
        }

        /** A REST line; allocations as "amount:index" pairs. */
        UUID line(UUID order, UUID variant, long lineId, int qty, int currentQty, String price, String... allocations) {
            StringBuilder a = new StringBuilder("[");
            for (int i = 0; i < allocations.length; i++) {
                String[] p = allocations[i].split(":");
                if (i > 0) a.append(',');
                a.append("{\"amount\":\"").append(p[0]).append("\",\"discount_application_index\":").append(p[1]).append('}');
            }
            a.append(']');
            UUID oi = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)", oi, id, order, variant, qty, "gid://shopify/LineItem/" + lineId,
                        "{\"id\":" + lineId + ",\"price\":\"" + price + "\",\"quantity\":" + qty + ",\"current_quantity\":" +
                        currentQty + ",\"discount_allocations\":" + a + "}");
            return oi;
        }

        UUID line(UUID order, UUID variant, int qty) {
            return line(order, variant, SEQ.incrementAndGet(), qty, qty, "100.00");
        }

        UUID leg(UUID order, int typeCode, String state, String cityId, String cityName, String extraRaw) {
            UUID s = UUID.randomUUID();
            String city = cityId == null ? "{}" : "{\"city\":{\"_id\":\"" + cityId + "\",\"name\":\"" + cityName + "\"}}";
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw) " +
                        "VALUES (?, ?, ?, ?, ?::shipment_internal_state, 'forward', ?::jsonb)", s, id, order,
                        String.valueOf(SEQ.incrementAndGet()), state,
                        "{\"type\":{\"code\":" + typeCode + "},\"dropOffAddress\":" + city +
                        (extraRaw == null ? "" : "," + extraRaw) + "}");
            return s;
        }

        UUID leg(UUID order, int typeCode, String state) {
            return leg(order, typeCode, state, null, null, null);
        }

        void history(UUID shipment, String state, Instant at) {
            jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at) " +
                        "VALUES (?, ?, ?::shipment_internal_state, ?)", id, shipment, state, Timestamp.from(at));
        }
    }

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<Map> get(String token, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private Map<String, Object> ok(T t, String pathAndQuery) {
        ResponseEntity<Map> r = get(t.ownerToken, pathAndQuery);
        assertThat(r.getStatusCode()).as("body: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    static long n(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    static BigDecimal dec(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    static Map<String, Object> group(Map<String, Object> breakdown, String key) {
        return list(breakdown, "groups").stream().filter(g -> key.equals(g.get("key"))).findFirst()
            .orElseThrow(() -> new AssertionError("no group " + key + " in " + breakdown));
    }

    /** gross − every deduction = netRealized, to the cent. */
    static void assertWaterfallReconciles(Map<String, Object> summary) {
        Map<String, Object> w = m(summary, "waterfall");
        BigDecimal left = dec(w, "gross");
        for (String k : List.of("discounts", "inTransit", "notShipped", "otherCarrier", "refused", "otherTerminal", "returns")) {
            left = left.subtract(dec(w, k));
        }
        assertThat(left).as("waterfall %s", w).isEqualByComparingTo(dec(w, "netRealized"));
        assertThat(dec(w, "netRealized")).isEqualByComparingTo(dec(summary, "realized"));
        assertThat(dec(summary, "grossSales").subtract(dec(m(summary, "discounts"), "total")))
            .isEqualByComparingTo(dec(summary, "booked"));
    }

    static final String SETTLED_PAID = "\"wallet\":{\"cashout\":{\"transaction_id\":\"WEDCOD16SEP26\"}}";

    // ── /revenue/summary ─────────────────────────────────────────────────────

    @Test
    void summary_waterfallReconciles_discountSplit_funnel_daily_previousPeriod() {
        T t = new T("An5-Summary");
        UUID v = t.variant("M");

        long la = SEQ.incrementAndGet();
        UUID a = t.order("{\"discount_applications\":[{\"type\":\"discount_code\",\"code\":\"SAVE10\"}]," +
            "\"fulfillments\":[{\"created_at\":\"2026-09-10T12:00:00Z\",\"status\":\"success\",\"line_items\":[{\"id\":" + la + "}]}]," +
            "\"refunds\":[{\"created_at\":\"2026-09-15T12:00:00Z\",\"refund_line_items\":[{\"line_item_id\":" + la +
            ",\"quantity\":1,\"restock_type\":\"return\"}]}]}");
        t.line(a, v, la, 2, 1, "100.00", "20.00:0");                  // gross 200, code 20, booked 180; 1 returned (90)
        UUID sa = t.leg(a, 10, "delivered");
        jdbc.update("UPDATE shipments SET settlement_status = 'paid' WHERE id = ?", sa);

        UUID b = t.order("{\"discount_applications\":[{\"type\":\"automatic\",\"title\":\"B2G1\"}]}");
        t.line(b, v, SEQ.incrementAndGet(), 1, 1, "100.00", "10.00:0"); // auto 10, booked 90, refused
        t.leg(b, 20, "returned");

        UUID c = t.order("{\"discount_applications\":[{\"type\":\"manual\",\"title\":\"\"}]}");
        t.line(c, v, SEQ.incrementAndGet(), 1, 1, "100.00", "5.00:0");  // manual 5, booked 95, in transit
        UUID sc = t.leg(c, 10, "with_courier");
        t.history(sc, "with_courier", SEPT_10.plusSeconds(3600));

        UUID d = t.order(SEPT_10, null, "{}");                         // not shipped, 100
        t.line(d, v, 1);
        UUID e = t.order(SEPT_10, "other_known", "{}");                // Wijha, 100
        t.line(e, v, 1);
        UUID f = t.order("{}");                                        // lost, 100
        t.line(f, v, 1);
        t.leg(f, 10, "lost");

        UUID prev = t.order(cairo(2026, 8, 20, 12, 0), "bosta", "{}"); // previous period: delivered 100
        t.line(prev, v, 1);
        t.leg(prev, 10, "delivered");

        Map<String, Object> body = ok(t, "/api/v1/analytics/revenue/summary?" + SEPT);
        assertThat(m(body, "range").get("from")).isEqualTo("2026-09-01");
        assertThat(m(body, "previousRange").get("from")).isEqualTo("2026-08-02");
        assertThat(m(body, "previousRange").get("to")).isEqualTo("2026-08-31");

        Map<String, Object> cur = m(body, "current");
        assertThat(dec(cur, "grossSales")).isEqualByComparingTo("700.00");
        Map<String, Object> disc = m(cur, "discounts");
        assertThat(dec(disc, "code")).isEqualByComparingTo("20.00");
        assertThat(dec(disc, "automatic")).isEqualByComparingTo("10.00");
        assertThat(dec(disc, "other")).isEqualByComparingTo("5.00");
        assertThat(dec(disc, "total")).isEqualByComparingTo("35.00");
        assertThat(dec(cur, "booked")).isEqualByComparingTo("665.00");
        assertThat(dec(cur, "realized")).isEqualByComparingTo("90.00");
        assertThat(dec(cur, "realizedShare")).isEqualByComparingTo("0.1353");
        assertThat(n(cur, "orders")).isEqualTo(6);

        Map<String, Object> w = m(cur, "waterfall");
        assertThat(dec(w, "inTransit")).isEqualByComparingTo("95.00");
        assertThat(dec(w, "notShipped")).isEqualByComparingTo("100.00");
        assertThat(dec(w, "otherCarrier")).isEqualByComparingTo("100.00");
        assertThat(dec(w, "refused")).isEqualByComparingTo("90.00");
        assertThat(dec(w, "otherTerminal")).isEqualByComparingTo("100.00");
        assertThat(dec(w, "returns")).isEqualByComparingTo("90.00");
        assertThat(dec(w, "netRealized")).isEqualByComparingTo("90.00");
        assertWaterfallReconciles(cur);

        Map<String, Object> funnel = m(cur, "funnel");
        assertThat(n(funnel, "ordered")).isEqualTo(6);
        assertThat(n(funnel, "fulfilled")).isEqualTo(3);   // Shopify-fulfilled A, refused B, picked-up C
        assertThat(n(funnel, "delivered")).isEqualTo(1);
        assertThat(n(funnel, "paidToYou")).isEqualTo(1);

        List<Map<String, Object>> daily = list(cur, "daily");
        assertThat(daily).hasSize(30);
        Map<String, Object> sep10 = daily.stream().filter(x -> "2026-09-10".equals(x.get("date"))).findFirst().orElseThrow();
        assertThat(dec(sep10, "booked")).isEqualByComparingTo("665.00");
        assertThat(dec(sep10, "realized")).isEqualByComparingTo("90.00");

        Map<String, Object> pr = m(body, "previous");
        assertThat(dec(pr, "booked")).isEqualByComparingTo("100.00");
        assertThat(dec(pr, "realized")).isEqualByComparingTo("100.00");
        assertThat(n(pr, "orders")).isEqualTo(1);
        assertWaterfallReconciles(pr);
    }

    @Test
    void summary_reconcilesToTheCent_withThirdsOfACent() {
        // Each order keeps 1 of 3 units with a 1.00 discount over the original 3: booked 10 − 1/3 =
        // 9.6667. Rounded per bucket that drifts a cent (3 × 9.67 vs 29.00); rounded per order it can't.
        T t = new T("An5-Cents");
        UUID v = t.variant("M");
        for (String state : List.of("delivered", "returned", "with_courier")) {
            UUID o = t.order("{\"discount_applications\":[{\"type\":\"automatic\",\"title\":\"x\"}]}");
            t.line(o, v, SEQ.incrementAndGet(), 3, 1, "10.00", "1.00:0");
            t.leg(o, 10, state);
        }
        Map<String, Object> cur = m(ok(t, "/api/v1/analytics/revenue/summary?" + SEPT), "current");
        assertWaterfallReconciles(cur);
        assertThat(dec(cur, "booked")).isEqualByComparingTo("29.01");
        assertThat(dec(m(cur, "waterfall"), "netRealized")).isEqualByComparingTo("9.67");
    }

    @Test
    void previousPeriod_isTheSameLengthImmediatelyBefore() {
        T t = new T("An5-Prev");
        UUID v = t.variant("M");
        t.line(t.order(cairo(2026, 8, 31, 23, 30), "bosta", "{}"), v, 1);   // last minute of the previous period
        t.line(t.order(cairo(2026, 8, 1, 12, 0), "bosta", "{}"), v, 1);     // before it
        t.line(t.order(cairo(2026, 9, 1, 0, 10), "bosta", "{}"), v, 1);     // first minutes of the period
        Map<String, Object> body = ok(t, "/api/v1/analytics/revenue/summary?from=2026-09-01&to=2026-09-07");
        assertThat(m(body, "previousRange").get("from")).isEqualTo("2026-08-25");
        assertThat(m(body, "previousRange").get("to")).isEqualTo("2026-08-31");
        assertThat(n(m(body, "current"), "orders")).isEqualTo(1);
        assertThat(n(m(body, "previous"), "orders")).isEqualTo(1);
    }

    @Test
    void longPeriods_skipThePreviousPeriod_unlessCompareIsAsked() {
        T t = new T("An5-Compare");
        UUID v = t.variant("M");
        t.line(t.order(cairo(2026, 6, 15, 12, 0), "bosta", "{}"), v, 1);    // in the previous 93 days (after the floor)
        t.line(t.order(cairo(2026, 9, 1, 12, 0), "bosta", "{}"), v, 1);     // in the period
        String q93 = "from=2026-06-30&to=2026-09-30";                         // 93 days
        for (String path : List.of("revenue/summary", "revenue/breakdown?by=channel", "revenue/breakdown?by=productType",
                                   "revenue/discounts", "revenue/heatmap", "delivery/summary", "delivery/failure-reasons",
                                   "products/extras")) {
            String sep = path.contains("?") ? "&" : "?";
            Map<String, Object> off = ok(t, "/api/v1/analytics/" + path + sep + q93);
            assertThat(off.get("previous")).as(path).isNull();
            assertThat(off.get("previousRange")).as(path).isNull();
            assertThat(off.get("current")).as(path).isNotNull();
            Map<String, Object> on = ok(t, "/api/v1/analytics/" + path + sep + q93 + "&compare=true");
            assertThat(on.get("previous")).as(path + " compare=true").isNotNull();
            assertThat(m(on, "previousRange").get("from")).isEqualTo("2026-03-29");
        }
        assertThat(n(m(ok(t, "/api/v1/analytics/revenue/summary?" + q93 + "&compare=true"), "previous"), "orders")).isEqualTo(1);
        // Exactly 92 days still compares by default.
        Map<String, Object> d92 = ok(t, "/api/v1/analytics/revenue/summary?from=2026-07-01&to=2026-09-30");
        assertThat(d92.get("previous")).isNotNull();
        assertThat(m(d92, "previousRange").get("from")).isEqualTo("2026-03-31");
    }

    // ── /revenue/breakdown ───────────────────────────────────────────────────

    @Test
    void breakdown_channelAndPayment() {
        T t = new T("An5-Channel");
        UUID v = t.variant("M");
        UUID o1 = t.order("{\"source_name\":\"web\",\"referring_site\":\"https://instagram.com/\"," +
            "\"landing_site\":\"/?utm_source=facebook\",\"payment_gateway_names\":[\"Cash on Delivery (COD)\"]}");
        t.line(o1, v, 1);
        t.leg(o1, 10, "delivered");
        UUID o2 = t.order("{\"source_name\":\"web\",\"referring_site\":null,\"landing_site\":\"/?utm_source=faceb\"," +
            "\"payment_gateway_names\":[\"Paymob - Native Checkout for Debit/Credit Cards\",\"Paymob\"]}");
        t.line(o2, v, 1);
        t.leg(o2, 20, "returned");
        UUID o3 = t.order("{\"source_name\":\"shopify_draft_order\",\"payment_gateway_names\":[\"manual\"]}");
        t.line(o3, v, 1);
        t.leg(o3, 10, "delivered");
        UUID o4 = t.order("{\"paymentGatewayNames\":[\"Cash on Delivery (COD)\",\"manual\"],\"name\":\"#1\"}");   // GraphQL shape
        t.line(o4, v, 1);

        Map<String, Object> ch = m(ok(t, "/api/v1/analytics/revenue/breakdown?by=channel&" + SEPT), "current");
        assertThat(ch.get("by")).isEqualTo("channel");
        assertThat(list(ch, "groups")).extracting(g -> g.get("key"))
            .containsExactly("Instagram", "Facebook", "TikTok", "Google", "Other referral", "Direct", "Manual / DM", "Unknown");
        assertThat(n(group(ch, "Instagram"), "orders")).isEqualTo(1);
        assertThat(dec(group(ch, "Instagram"), "successRate")).isEqualByComparingTo("1");
        assertThat(n(group(ch, "Facebook"), "failedOrders")).isEqualTo(1);
        assertThat(dec(group(ch, "Facebook"), "successRate")).isEqualByComparingTo("0");
        assertThat(dec(group(ch, "Facebook"), "realized")).isEqualByComparingTo("0");
        assertThat(n(group(ch, "Manual / DM"), "orders")).isEqualTo(1);
        assertThat(n(group(ch, "Unknown"), "orders")).isEqualTo(1);
        assertThat(n(group(ch, "TikTok"), "orders")).isZero();
        assertThat(group(ch, "TikTok").get("successRate")).isNull();

        Map<String, Object> pay = m(ok(t, "/api/v1/analytics/revenue/breakdown?by=payment&" + SEPT), "current");
        assertThat(n(group(pay, "COD"), "orders")).isEqualTo(1);
        assertThat(n(group(pay, "Card"), "orders")).isEqualTo(1);
        assertThat(n(group(pay, "Manual"), "orders")).isEqualTo(1);
        assertThat(n(group(pay, "Mixed"), "orders")).isEqualTo(1);
        assertThat(dec(group(pay, "COD"), "booked")).isEqualByComparingTo("100.00");

        assertThat(get(t.ownerToken, "/api/v1/analytics/revenue/breakdown?by=city&" + SEPT).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void breakdown_governorate_bostaCity_thenProvinceFallback_thenUnknown() {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, district_name) " +
                    "VALUES ('an5-d-alx', 'an5-city-alx', 'Alexandria', 'الإسكندرية', 'Smouha'), " +
                    "       ('an5-d-giza', 'an5-city-giza', 'Giza', 'الجيزة', 'Dokki') ON CONFLICT DO NOTHING");
        String gizaId = jdbc.queryForObject("SELECT MIN(city_id) FROM bosta_districts WHERE lower(city_name) = 'giza'", String.class);
        T t = new T("An5-Gov");
        UUID v = t.variant("M");
        UUID booked = t.order("{\"shipping_address\":{\"province_code\":\"GZ\"}}");   // the Bosta city wins
        t.line(booked, v, 1);
        t.leg(booked, 10, "delivered", "an5-city-alx", "Alexandria", null);
        UUID byProvince = t.order(SEPT_10, null, "{\"shipping_address\":{\"province_code\":\"GZ\"}}");
        t.line(byProvince, v, 2);
        UUID graphql = t.order(SEPT_10, null, "{\"shippingAddress\":{\"provinceCode\":\"GZ\"}}");
        t.line(graphql, v, 1);
        UUID unknown = t.order(SEPT_10, null, "{\"shipping_address\":{\"province_code\":\"XX\"}}");
        t.line(unknown, v, 1);

        Map<String, Object> gov = m(ok(t, "/api/v1/analytics/revenue/breakdown?by=governorate&" + SEPT), "current");
        Map<String, Object> alx = group(gov, "an5-city-alx");
        assertThat(alx.get("label")).isEqualTo("Alexandria");
        assertThat(alx.get("labelAr")).isEqualTo("الإسكندرية");
        assertThat(n(alx, "orders")).isEqualTo(1);
        Map<String, Object> giza = group(gov, gizaId);
        assertThat(n(giza, "orders")).isEqualTo(2);
        assertThat(dec(giza, "booked")).isEqualByComparingTo("300.00");
        assertThat(giza.get("label")).isEqualTo("Giza");
        assertThat(n(group(gov, "unknown"), "orders")).isEqualTo(1);
        assertThat(list(gov, "groups").get(0).get("key")).isEqualTo(gizaId);   // highest booked first
    }

    @Test
    void breakdown_productType_blankIsUncategorised() {
        T t = new T("An5-Type");
        UUID jeans = t.variant(t.product("Jeans", "{\"product_type\":\"Jeans\"}"), "32", "{}");
        UUID plain = t.variant(t.product("Plain", "{\"product_type\":\"  \"}"), "M", "{}");
        UUID gql = t.variant(t.product("Gql", "{\"productType\":\"Jeans\"}"), "34", "{}");
        UUID o1 = t.order("{}");
        t.line(o1, jeans, 2);
        t.line(o1, plain, 1);
        t.leg(o1, 10, "delivered");
        UUID o2 = t.order("{}");
        t.line(o2, gql, 1);
        t.leg(o2, 20, "returned");

        Map<String, Object> pt = m(ok(t, "/api/v1/analytics/revenue/breakdown?by=productType&" + SEPT), "current");
        Map<String, Object> j = group(pt, "Jeans");
        assertThat(n(j, "orders")).isEqualTo(2);
        assertThat(dec(j, "booked")).isEqualByComparingTo("300.00");
        assertThat(dec(j, "realized")).isEqualByComparingTo("200.00");
        assertThat(dec(j, "successRate")).isEqualByComparingTo("0.5");
        Map<String, Object> u = group(pt, "uncategorised");
        assertThat(u.get("label")).isEqualTo("Uncategorised");
        assertThat(n(u, "orders")).isEqualTo(1);
    }

    // ── /revenue/discounts ───────────────────────────────────────────────────

    @Test
    void discounts_perCode_andOneAutomaticRow() {
        T t = new T("An5-Disc");
        UUID v = t.variant("M");
        UUID o1 = t.order("{\"discount_applications\":[{\"type\":\"discount_code\",\"code\":\"save10\"}]}");
        t.line(o1, v, SEQ.incrementAndGet(), 1, 1, "100.00", "10.00:0");
        t.leg(o1, 10, "delivered");
        UUID o2 = t.order("{\"discount_applications\":[{\"type\":\"discount_code\",\"code\":\"SAVE10\"}]}");
        t.line(o2, v, SEQ.incrementAndGet(), 2, 2, "100.00", "30.00:0");
        t.leg(o2, 20, "returned");
        UUID o3 = t.order("{\"discount_applications\":[{\"type\":\"automatic\",\"title\":\"B2G1\"}]}");
        t.line(o3, v, SEQ.incrementAndGet(), 2, 2, "100.00", "50.00:0");
        t.leg(o3, 10, "delivered");
        UUID o4 = t.order("{\"discount_applications\":[{\"type\":\"discount_code\",\"code\":\"FREESHIP\"," +
            "\"target_type\":\"shipping_line\"}]}");
        t.line(o4, v, 1);

        Map<String, Object> cur = m(ok(t, "/api/v1/analytics/revenue/discounts?" + SEPT), "current");
        List<Map<String, Object>> codes = list(cur, "codes");
        assertThat(codes).extracting(c -> c.get("code")).containsExactly("SAVE10", "FREESHIP");
        Map<String, Object> save = codes.get(0);
        assertThat(n(save, "orders")).isEqualTo(2);
        assertThat(dec(save, "booked")).isEqualByComparingTo("260.00");
        assertThat(dec(save, "discountCost")).isEqualByComparingTo("40.00");
        assertThat(n(save, "deliveredOrders")).isEqualTo(1);
        assertThat(dec(save, "successRate")).isEqualByComparingTo("0.5");
        assertThat(dec(save, "revenuePerCost")).isEqualByComparingTo("6.5");
        Map<String, Object> free = codes.get(1);
        assertThat(dec(free, "discountCost")).isEqualByComparingTo("0");
        assertThat(free.get("revenuePerCost")).isNull();
        Map<String, Object> auto = m(cur, "automatic");
        assertThat(auto.get("label")).isEqualTo("Automatic discounts");
        assertThat(n(auto, "orders")).isEqualTo(1);
        assertThat(dec(auto, "booked")).isEqualByComparingTo("150.00");
        assertThat(dec(auto, "discountCost")).isEqualByComparingTo("50.00");
        assertThat(dec(auto, "revenuePerCost")).isEqualByComparingTo("3");
    }

    // ── /revenue/heatmap ─────────────────────────────────────────────────────

    @Test
    void heatmap_cairoWeekdayAndHour_averagedOverThatWeekday() {
        T t = new T("An5-Heat");
        UUID v = t.variant("M");
        t.line(t.order(Instant.parse("2026-09-07T23:30:00Z"), "bosta", "{}"), v, 1);   // Tue 8 Sep 02:30 Cairo
        t.line(t.order(cairo(2026, 9, 10, 12, 15), "bosta", "{}"), v, 1);              // Thu 12:15
        t.line(t.order(cairo(2026, 9, 17, 12, 45), "bosta", "{}"), v, 1);              // Thu 12:45

        Map<String, Object> cur = m(ok(t, "/api/v1/analytics/revenue/heatmap?" + SEPT), "current");
        List<Map<String, Object>> cells = list(cur, "cells");
        assertThat(cells).hasSize(168);
        Map<String, Object> tue2 = cell(cells, 2, 2);
        assertThat(n(tue2, "orders")).isEqualTo(1);
        assertThat(dec(tue2, "avgOrders")).isEqualByComparingTo("0.20");   // five Tuesdays in Sept 2026
        Map<String, Object> thu12 = cell(cells, 4, 12);
        assertThat(n(thu12, "orders")).isEqualTo(2);
        assertThat(dec(thu12, "avgOrders")).isEqualByComparingTo("0.50");  // four Thursdays
        assertThat(n(cell(cells, 1, 23), "orders")).isZero();               // never Monday 23:30 (UTC)
        assertThat(((Number) m(cur, "weekdayOccurrences").get("2")).intValue()).isEqualTo(5);
    }

    static Map<String, Object> cell(List<Map<String, Object>> cells, int weekday, int hour) {
        return cells.stream().filter(c -> n(c, "weekday") == weekday && n(c, "hour") == hour).findFirst().orElseThrow();
    }

    // ── /delivery/summary + /delivery/failure-reasons ────────────────────────

    @Test
    void delivery_summary_times_buckets_trend_andFailureReasons() {
        T t = new T("An5-Delivery");
        UUID v = t.variant("M");
        // placed Thu 10 Sep 12:00 Cairo = 09:00Z
        UUID d1 = t.order("{}");                                     // Cairo, handed +6 h (collectedFromBusiness), delivered +24 h
        t.line(d1, v, 1);
        UUID s1 = t.leg(d1, 10, "delivered", "c-cairo", "Cairo", "\"collectedFromBusiness\":\"2026-09-10T15:00:00.000Z\"");
        jdbc.update("UPDATE shipments SET delivered_at = '2026-09-11T15:00:00Z' WHERE id = ?", s1);
        UUID d2 = t.order("{}");                                     // Alexandria, handed via history +48 h, delivered +48 h
        t.line(d2, v, 1);
        UUID s2 = t.leg(d2, 10, "delivered", "c-alx", "Alexandria", null);
        t.history(s2, "with_courier", Instant.parse("2026-09-12T09:00:00Z"));
        t.history(s2, "delivered", Instant.parse("2026-09-14T09:00:00Z"));
        UUID d3 = t.order("{}");                                     // refused, handed +24 h
        t.line(d3, v, 1);
        UUID s3 = t.leg(d3, 20, "returned", "c-cairo", "Cairo", "\"collectedFromBusiness\":\"2026-09-11T09:00:00.000Z\"");
        jdbc.update("UPDATE shipments SET last_failure_reason = 'Cancellation - the customer refuses to receive the shipment.' WHERE id = ?", s3);
        UUID d4 = t.order("{}");                                     // lost, no reason
        t.line(d4, v, 1);
        t.leg(d4, 10, "lost");
        UUID d5 = t.order("{}");                                     // refused, reason only on the exception
        t.line(d5, v, 2);
        UUID s5 = t.leg(d5, 10, "returned");
        jdbc.update("UPDATE shipments SET exception_reason = 'Postponed - the customer requested postponement for another day.' WHERE id = ?", s5);
        UUID d6 = t.order("{}");                                     // still moving: neither
        t.line(d6, v, 1);
        t.leg(d6, 10, "with_courier");

        Map<String, Object> cur = m(ok(t, "/api/v1/analytics/delivery/summary?" + SEPT), "current");
        assertThat(n(cur, "delivered")).isEqualTo(2);
        assertThat(n(cur, "failed")).isEqualTo(3);
        assertThat(dec(cur, "successRate")).isEqualByComparingTo("0.4");
        assertThat(dec(cur, "lostSalesValue")).isEqualByComparingTo("400.00");
        assertThat(dec(cur, "avgHoursOrderToHanded")).isEqualByComparingTo("26.0");    // (6 + 48 + 24) / 3
        assertThat(n(cur, "handedOrders")).isEqualTo(3);
        assertThat(dec(cur, "avgHoursHandedToDelivered")).isEqualByComparingTo("36.0");
        assertThat(dec(cur, "avgHoursHandedToDeliveredCairoGiza")).isEqualByComparingTo("24.0");
        assertThat(dec(cur, "avgHoursHandedToDeliveredOther")).isEqualByComparingTo("48.0");

        List<Map<String, Object>> speed = list(cur, "fulfillmentSpeed");
        assertThat(speed).extracting(s -> s.get("bucket")).containsExactly("same_day", "1_day", "2_days", "3_plus_days");
        assertThat(n(speed.get(0), "orders")).isEqualTo(1);
        assertThat(dec(speed.get(0), "successRate")).isEqualByComparingTo("1");
        assertThat(n(speed.get(1), "failed")).isEqualTo(1);
        assertThat(dec(speed.get(1), "successRate")).isEqualByComparingTo("0");
        assertThat(n(speed.get(2), "delivered")).isEqualTo(1);
        assertThat(speed.get(3).get("successRate")).isNull();

        List<Map<String, Object>> weeks = list(cur, "weeklyTrend");
        assertThat(weeks).hasSize(8);
        assertThat(weeks.get(0).get("weekStart")).isEqualTo("2026-08-10");
        assertThat(weeks.get(7).get("weekStart")).isEqualTo("2026-09-28");
        assertThat(weeks.get(4).get("weekStart")).isEqualTo("2026-09-07");
        assertThat(n(weeks.get(4), "delivered")).isEqualTo(2);
        assertThat(n(weeks.get(4), "failed")).isEqualTo(3);

        Map<String, Object> fr = m(ok(t, "/api/v1/analytics/delivery/failure-reasons?" + SEPT), "current");
        assertThat(n(fr, "failedLegs")).isEqualTo(3);
        assertThat(n(fr, "withReason")).isEqualTo(2);
        assertThat(dec(fr, "coverage")).isEqualByComparingTo("0.6667");
        Map<String, Object> refused = list(fr, "reasons").stream()
            .filter(r -> "Customer refused".equals(r.get("reason"))).findFirst().orElseThrow();
        assertThat(n(refused, "count")).isEqualTo(1);
        assertThat(dec(refused, "share")).isEqualByComparingTo("0.5");
        Map<String, Object> postponed = list(fr, "reasons").stream()
            .filter(r -> "Postponed / rescheduled".equals(r.get("reason"))).findFirst().orElseThrow();
        assertThat(n(postponed, "count")).isEqualTo(1);
        assertThat(list(fr, "reasons")).hasSize(6);
    }

    // ── /products/extras ─────────────────────────────────────────────────────

    @Test
    void productExtras_abc_sizeCurve_boughtTogether() {
        T t = new T("An5-Extras");
        UUID sized = t.product("Jeans", "{\"options\":[{\"name\":\"Size\",\"position\":1}]}");
        UUID m = t.variant(sized, "m", "{\"option1\":\"m\"}");
        UUID xxl = t.variant(sized, "2XL", "{\"option1\":\"2XL\"}");
        UUID range = t.variant(sized, "XL-XXL", "{\"option1\":\"XL-XXL\"}");
        UUID plain = t.product("Shoe", "{}");
        UUID shoe = t.variant(plain, "Black / 38", "{}");
        UUID nosize = t.variant(plain, "Black", "{}");

        // ABC: realized m 800, xxl 150, shoe 50 → A, B, C; range refused only → C.
        long lm = SEQ.incrementAndGet();
        UUID om = t.order("{\"fulfillments\":[{\"created_at\":\"2026-09-10T12:00:00Z\",\"status\":\"success\"," +
            "\"line_items\":[{\"id\":" + lm + "}]}],\"refunds\":[{\"created_at\":\"2026-09-15T12:00:00Z\"," +
            "\"refund_line_items\":[{\"line_item_id\":" + lm + ",\"quantity\":1,\"restock_type\":\"return\"}]}]}");
        t.line(om, m, lm, 9, 8, "100.00");          // 9 sold, 1 returned → realized 800
        t.leg(om, 10, "delivered");
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, inbound_variant_id, matched_order_id, raw) " +
                    "VALUES (?, ?, 'matched', ?, ?, '{}'::jsonb)", t.id, String.valueOf(SEQ.incrementAndGet()), m, om);
        UUID ox = t.order("{}");
        t.line(ox, xxl, SEQ.incrementAndGet(), 1, 1, "150.00");
        t.leg(ox, 10, "delivered");
        UUID os = t.order("{}");
        t.line(os, shoe, SEQ.incrementAndGet(), 1, 1, "50.00");
        t.line(os, nosize, SEQ.incrementAndGet(), 1, 1, "0.00");   // no size; realized 0 → C
        t.leg(os, 10, "delivered");
        UUID or = t.order("{}");
        t.line(or, range, 1);
        t.leg(or, 20, "returned");

        Map<String, Object> cur = m(ok(t, "/api/v1/analytics/products/extras?" + SEPT), "current");
        Map<UUID, String> classes = new HashMap<>();
        for (Map<String, Object> r : list(cur, "abc")) classes.put(UUID.fromString((String) r.get("variantId")), (String) r.get("abcClass"));
        assertThat(classes.get(m)).isEqualTo("A");
        assertThat(classes.get(xxl)).isEqualTo("B");
        assertThat(classes.get(range)).isEqualTo("C");
        assertThat(list(cur, "abc").get(0).get("variantId")).isEqualTo(m.toString());
        assertThat(dec(list(cur, "abc").get(0), "share")).isEqualByComparingTo("0.8");   // 800 / 1000
        assertThat(classes.get(shoe)).isEqualTo("C");                                     // 95 % already before it
        assertThat(classes.get(nosize)).isEqualTo("C");

        Map<String, Object> curve = m(cur, "sizeCurve");
        assertThat(list(curve, "sizes")).extracting(s -> s.get("size")).containsExactly("M", "XXL", "38");
        Map<String, Object> mRow = list(curve, "sizes").get(0);
        assertThat(n(mRow, "soldUnits")).isEqualTo(9);
        assertThat(n(mRow, "returnedUnits")).isEqualTo(1);
        assertThat(n(mRow, "exchangedUnits")).isEqualTo(1);
        assertThat(n(curve, "unparseableUnits")).isEqualTo(1);
        assertThat((List<Object>) curve.get("unparseableValues")).containsExactly("XL-XXL");
        assertThat(n(curve, "noSizeUnits")).isEqualTo(1);
    }

    @Test
    void productExtras_mostFailed_min20Orders_and_pairsMin3() {
        T t = new T("An5-Failed");
        UUID big = t.variant(t.product("Big", "{}"), "M", "{}");
        UUID small = t.variant(t.product("Small", "{}"), "M", "{}");
        UUID pa = t.variant(t.product("PairA", "{}"), "M", "{}");
        UUID pb = t.variant(t.product("PairB", "{}"), "M", "{}");
        UUID pc = t.variant(t.product("PairC", "{}"), "M", "{}");
        for (int i = 0; i < 20; i++) {
            UUID o = t.order("{}");
            t.line(o, big, 1);
            t.leg(o, i < 5 ? 20 : 10, i < 5 ? "returned" : "delivered");
        }
        UUID moving = t.order("{}");                 // still in transit: counts for the 20-order bar, not the rate
        t.line(moving, big, 1);
        t.leg(moving, 10, "with_courier");
        for (int i = 0; i < 19; i++) {
            UUID o = t.order("{}");
            t.line(o, small, 1);
            t.leg(o, 20, "returned");
        }
        for (int i = 0; i < 3; i++) {
            UUID o = t.order("{}");
            t.line(o, pa, 1);
            t.line(o, pb, 1);
            if (i < 2) t.line(o, pc, 1);
        }

        Map<String, Object> cur = m(ok(t, "/api/v1/analytics/products/extras?" + SEPT), "current");
        List<Map<String, Object>> failed = list(cur, "mostFailed");
        assertThat(failed).extracting(f -> f.get("title")).containsExactly("Big");
        assertThat(n(failed.get(0), "orders")).isEqualTo(21);
        assertThat(dec(failed.get(0), "failureRate")).isEqualByComparingTo("0.25");   // 5 / (15 + 5), not 5 / 21

        List<Map<String, Object>> pairs = list(cur, "boughtTogether");
        assertThat(pairs).hasSize(1);
        assertThat(n(pairs.get(0), "orders")).isEqualTo(3);
        assertThat(Set.of(pairs.get(0).get("variantA"), pairs.get(0).get("variantB")))
            .containsExactlyInAnyOrder(pa.toString(), pb.toString());
    }

    // ── roles + isolation ────────────────────────────────────────────────────

    static final List<String> ENDPOINTS = List.of(
        "/api/v1/analytics/revenue/summary?period=30d",
        "/api/v1/analytics/revenue/breakdown?period=30d&by=channel",
        "/api/v1/analytics/revenue/breakdown?period=30d&by=payment",
        "/api/v1/analytics/revenue/breakdown?period=30d&by=governorate",
        "/api/v1/analytics/revenue/breakdown?period=30d&by=productType",
        "/api/v1/analytics/revenue/discounts?period=30d",
        "/api/v1/analytics/revenue/heatmap?period=30d",
        "/api/v1/analytics/delivery/summary?period=30d",
        "/api/v1/analytics/delivery/failure-reasons?period=30d",
        "/api/v1/analytics/products/extras?period=30d");

    @Test
    void breakdowns_ownerOnly() {
        T t = new T("An5-Roles");
        UUID manager = UUID.randomUUID(), worker = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'M', ?, 'x', 'manager')",
                    manager, t.id, "m+" + manager + "@an5.test");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'W', ?, 'x', 'worker')",
                    worker, t.id, "w+" + worker + "@an5.test");
        String mt = jwt.issueAccessToken(manager, t.id, "manager"), wt = jwt.issueAccessToken(worker, t.id, "worker");
        for (String path : ENDPOINTS) {
            assertThat(get(t.ownerToken, path).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(get(mt, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(wt, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void breakdowns_appUser_rls_eachTenantSeesOnlyItsOwnOrders() {
        T a = new T("An5-IsoA");
        T b = new T("An5-IsoB");
        UUID va = a.variant("M"), vb = b.variant("M");
        for (int i = 0; i < 3; i++) a.line(a.order("{}"), va, 1);
        b.line(b.order("{}"), vb, 2);

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        RevenueAnalyticsService svc = new RevenueAnalyticsService(new JdbcTemplate(appUserDs), floorOverrides);
        AnalyticsPeriod sept = new AnalyticsPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        TenantContext.set(b.id);
        AnalyticsSql.Compared<RevenueAnalyticsService.Summary> asB = tx.execute(s -> svc.summary(sept, false));
        assertThat(asB.current().orders()).isEqualTo(1);
        assertThat(asB.current().booked()).isEqualByComparingTo("200.00");
        TenantContext.set(a.id);
        assertThat(tx.execute(s -> svc.summary(sept, false)).current().orders()).isEqualTo(3);
        TenantContext.set(b.id);
        AnalyticsSql.Compared<RevenueAnalyticsService.Breakdown> ch = tx.execute(s ->
            svc.breakdown(sept, RevenueAnalyticsService.By.CHANNEL, false));
        assertThat(ch.current().groups().stream().mapToLong(RevenueAnalyticsService.Group::orders).sum()).isEqualTo(1);
    }
}
