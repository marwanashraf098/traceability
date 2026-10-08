package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.ProductExtrasAnalyticsService;
import com.traceability.analytics.StockAnalyticsService;
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
 * Analytics slice 4 — stock: summary (warehouse, value at price / cost, age, excluded, pieces moved
 * 4+ times), per-variant stock health (velocity, cover, sell-through, running low, dead stock,
 * returns + exchanges), restock suggestions with the per-tenant settings (V151), piece trips, the
 * upgraded sells-out-soon alert, roles and tenant isolation. Times relative to now.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsStockTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired AnalyticsFloorOverrides floorOverrides;
    @Autowired ProductExtrasAnalyticsService extras;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    static Instant daysAgo(double d) {
        return Instant.now().minus(Duration.ofMinutes((long) (d * 24 * 60)));
    }

    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID product = UUID.randomUUID();
        final UUID main = UUID.randomUUID();
        final UUID showroom = UUID.randomUUID();
        final String ownerToken;

        T(String name) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@s4.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, "s4-" + id + ".myshopify.com", Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, 'Tee')",
                        product, id, store, "P-" + product);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", main, id);
            jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_fulfillment) VALUES (?, ?, 'Showroom', 'showroom', false)",
                        showroom, id);
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        UUID variant(String title, String price, String cost) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price, unit_cost) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric)", v, id, product, "V-" + v, "SKU-" + title, title,
                        price, cost);
            return v;
        }

        String piece(UUID variant, String status, UUID location, double ageDays) {
            String p = "S4" + SEQ.incrementAndGet();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?)", p, id, variant, "PC-" + p, p, status, location,
                        Timestamp.from(daysAgo(ageDays)));
            return p;
        }

        void pieces(UUID variant, int n, double ageDays) {
            for (int i = 0; i < n; i++) piece(variant, "available", main, ageDays);
        }

        void event(String piece, String from, String to, UUID order, UUID shipment, Instant at) {
            jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, shipment_id, from_status, to_status, occurred_at) " +
                        "VALUES (?, ?, 'courier_update', ?, ?, ?::piece_status, ?::piece_status, ?)",
                        id, piece, order, shipment, from, to, Timestamp.from(at));
        }

        UUID order(Instant placedAt) {
            UUID o = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                        "VALUES (?, ?, ?, ?, ?, 'new'::order_status, ?, '{}'::jsonb)",
                        o, id, store, "EXT-" + o, "#" + SEQ.incrementAndGet(), Timestamp.from(placedAt));
            return o;
        }

        UUID line(UUID order, UUID variant, int qty) {
            long lineId = SEQ.incrementAndGet();
            UUID oi = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)", oi, id, order, variant, qty, "gid://shopify/LineItem/" + lineId,
                        "{\"id\":" + lineId + ",\"price\":\"100.00\",\"quantity\":" + qty +
                        ",\"current_quantity\":" + qty + ",\"discount_allocations\":[]}");
            return oi;
        }

        UUID leg(UUID order, String state, String city, int quote, Instant createdAt) {
            UUID s = UUID.randomUUID();
            String raw = "{\"type\":{\"code\":10},\"cod\":100,\"shipmentFees\":" + quote +
                         ",\"dropOffAddress\":{\"city\":{\"_id\":\"id-" + city + "\",\"name\":\"" + city + "\"}}}";
            jdbc.update("INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw, created_at) " +
                        "VALUES (?, ?, ?, ?, ?::shipment_internal_state, 'forward', ?::jsonb, ?)",
                        s, id, order, "8" + SEQ.incrementAndGet(), state, raw, Timestamp.from(createdAt));
            ShipmentSettlement.apply(jdbc, s, ShipmentSettlementTest.json(raw));
            return s;
        }

        /** {@code qty} units of {@code variant} on one order placed {@code days} ago, delivered. */
        UUID delivered(UUID variant, int qty, double days) {
            UUID o = order(daysAgo(days));
            line(o, variant, qty);
            leg(o, "delivered", "Cairo", 50, daysAgo(days));
            return o;
        }

        String token(String role) {
            UUID u = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'U', ?, 'x', ?::user_role)",
                        u, id, role + "+" + u + "@s4.test", role);
            return jwt.issueAccessToken(u, id, role);
        }

        String reference() {
            String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
            long n = SEQ.incrementAndGet();
            StringBuilder sb = new StringBuilder("RR-");
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt((int) (n % alphabet.length())));
                n /= alphabet.length();
            }
            return sb.toString();
        }

        void returnItem(UUID order, UUID variant, UUID orderItem, int unitNo, String type, String reason) {
            UUID r = UUID.randomUUID();
            jdbc.update("INSERT INTO return_requests (id, tenant_id, order_id, type, status, reference) VALUES (?, ?, ?, ?, 'received', ?)",
                        r, id, order, type, reference());
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code, active, item_status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, false, 'done')", id, r, orderItem, unitNo, variant, reason);
        }
    }

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<Map> get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    private ResponseEntity<Map> put(String token, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, HttpMethod.PUT, new HttpEntity<>(body, h), Map.class);
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

    Map<String, Map<String, Object>> byVariant(Map<String, Object> body) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> v : list(body, "variants")) out.put((String) v.get("variantId"), v);
        return out;
    }

    // ── no pieces ────────────────────────────────────────────────────────────

    @Test
    void noPieces_everyStockEndpointSaysSo() {
        T t = new T("S4-Empty");
        UUID v = t.variant("A", "100", null);
        t.delivered(v, 3, 2);
        t.piece(v, "voided", t.main, 3);                         // a voided piece is not stock
        assertThat(ok(t, "/api/v1/analytics/stock/summary").get("hasPieces")).isEqualTo(false);
        Map<String, Object> vs = ok(t, "/api/v1/analytics/stock/variants");
        assertThat(vs.get("hasPieces")).isEqualTo(false);
        assertThat(list(vs, "variants")).isEmpty();
        Map<String, Object> rs = ok(t, "/api/v1/analytics/stock/restock");
        assertThat(rs.get("hasPieces")).isEqualTo(false);
        assertThat(list(rs, "items")).isEmpty();
        assertThat(list(ok(t, "/api/v1/analytics/alerts?period=30d"), "alerts"))
            .extracting(a -> a.get("key")).doesNotContain("sells_out_soon");
    }

    // ── summary ──────────────────────────────────────────────────────────────

    @Test
    void summary_warehouseValueAgeExcludedAndPiecesMovedFourTimes() {
        T t = new T("S4-Summary");
        UUID costed = t.variant("Costed", "200", "80"), plain = t.variant("Plain", "100", null);
        t.piece(costed, "available", t.main, 10);
        t.piece(costed, "available", t.main, 45);
        t.piece(plain, "available", t.showroom, 75);
        String old = t.piece(plain, "available", t.main, 120);
        t.piece(plain, "voided", t.main, 5);                     // never counted
        t.piece(costed, "on_hold", t.main, 20);
        t.piece(costed, "damaged", t.main, 20);
        t.piece(plain, "damaged", t.main, 20);
        String lostNow = t.piece(costed, "lost", t.main, 50);
        t.event(lostNow, "available", "lost", null, null, daysAgo(3));
        String destroyed = t.piece(plain, "destroyed", t.main, 50);
        t.event(destroyed, "available", "destroyed", null, null, daysAgo(4));
        String lostLongAgo = t.piece(costed, "lost", t.main, 90);
        t.event(lostLongAgo, "available", "lost", null, null, daysAgo(60));

        // Trips: 4 out-and-back trips → counted; 3 trips + a non-trip move → not.
        for (int i = 0; i < 4; i++) t.event(old, i % 2 == 0 ? "packed" : "awaiting_pickup", "with_courier", null, null, daysAgo(100 - i));
        String three = t.piece(plain, "available", t.main, 40);
        for (int i = 0; i < 3; i++) t.event(three, "packed", "delivered", null, null, daysAgo(30 - i));
        t.event(three, "with_courier", "delivered", null, null, daysAgo(20));

        Map<String, Object> s = ok(t, "/api/v1/analytics/stock/summary?period=30d");
        assertThat(s.get("hasPieces")).isEqualTo(true);
        assertThat(n(s, "inWarehouse")).isEqualTo(5);
        assertThat(list(s, "byLocation")).extracting(l -> l.get("name"), l -> ((Number) l.get("pieces")).longValue())
            .containsExactly(org.assertj.core.groups.Tuple.tuple("Main", 4L), org.assertj.core.groups.Tuple.tuple("Showroom", 1L));
        assertThat(dec(s, "valueAtPrice")).isEqualByComparingTo("700.00");      // 200 + 200 + 100 + 100 + 100
        assertThat(dec(s, "valueAtCost")).isEqualByComparingTo("160.00");       // costed variant only
        assertThat(n(s, "costedVariants")).isEqualTo(1);
        assertThat(n(s, "variantsInStock")).isEqualTo(2);
        assertThat(dec(s, "avgDaysInStock")).isEqualByComparingTo("58.0");      // (10 + 45 + 75 + 120 + 40) / 5
        assertThat(list(s, "ageBuckets")).extracting(b -> b.get("key"), b -> ((Number) b.get("pieces")).longValue())
            .containsExactly(org.assertj.core.groups.Tuple.tuple("0-30", 1L), org.assertj.core.groups.Tuple.tuple("31-60", 2L),
                             org.assertj.core.groups.Tuple.tuple("61-90", 1L), org.assertj.core.groups.Tuple.tuple("90+", 1L));
        assertThat(dec(list(s, "ageBuckets").get(1), "valueAtPrice")).isEqualByComparingTo("300.00");
        assertThat(n(s, "onHold")).isEqualTo(1);
        assertThat(n(m(s, "damaged"), "pieces")).isEqualTo(2);
        assertThat(dec(m(s, "damaged"), "valueAtCost")).isEqualByComparingTo("80.00");
        assertThat(n(m(s, "damaged"), "costedPieces")).isEqualTo(1);
        assertThat(n(m(s, "lostThisPeriod"), "pieces")).isEqualTo(2);           // the 60-day-old loss is outside
        assertThat(dec(m(s, "lostThisPeriod"), "valueAtCost")).isEqualByComparingTo("80.00");
        assertThat(n(s, "piecesMovedFourPlus")).isEqualTo(1);
    }

    // ── variants ─────────────────────────────────────────────────────────────

    @Test
    void variants_velocityCoverSellThroughRunningLowDeadStockAndReturns() {
        T t = new T("S4-Variants");
        UUID fast = t.variant("Fast", "100", "40"), dead = t.variant("Dead", "150", null),
             deadCosted = t.variant("DeadCosted", "150", "60"), returned = t.variant("Returned", "100", null);
        // fast: 30 delivered in the last 30 days (1/day), 5 on hand → 5 days of cover, running low.
        t.delivered(fast, 30, 3);
        t.pieces(fast, 5, 12);
        // dead: last sold 70 days ago, 4 on hand at price; deadCosted: never sold, 2 at cost.
        t.delivered(dead, 1, 70);
        t.pieces(dead, 4, 80);
        t.pieces(deadCosted, 2, 100);
        // returned: 10 delivered 40 days ago (velocity 0 now, last sale 40 days → not dead), 2 refund
        // returns + 1 exchange in 90 days → (2 + 1) / 10; top reason wrong_size (2) over damaged (1).
        UUID ro = t.order(daysAgo(40));
        UUID roi = t.line(ro, returned, 10);
        t.leg(ro, "delivered", "Cairo", 50, daysAgo(40));
        t.returnItem(ro, returned, roi, 1, "refund", "wrong_size");
        t.returnItem(ro, returned, roi, 2, "refund", "wrong_size");
        t.returnItem(ro, returned, roi, 3, "exchange", "damaged");
        t.pieces(returned, 3, 30);
        t.piece(returned, "return_in_transit", null, 30);
        // moving: 10 sold this week, still with the courier → sold, but no velocity (delivered only).
        UUID moving = t.variant("Moving", "100", null);
        UUID mo = t.order(daysAgo(2));
        t.line(mo, moving, 10);
        t.leg(mo, "with_courier", "Cairo", 50, daysAgo(2));
        t.pieces(moving, 10, 5);

        Map<String, Object> body = ok(t, "/api/v1/analytics/stock/variants");
        Map<String, Map<String, Object>> v = byVariant(body);
        Map<String, Object> f = v.get(fast.toString());
        assertThat(dec(f, "velocityPerDay")).isEqualByComparingTo("1.00");
        assertThat(dec(f, "daysOfCover")).isEqualByComparingTo("5.0");
        assertThat(dec(f, "sellThrough")).isEqualByComparingTo("0.8571");       // 30 / (30 + 5)
        assertThat(dec(f, "avgPieceAgeDays")).isEqualByComparingTo("12.0");
        assertThat(f.get("runningLow")).isEqualTo(true);
        assertThat(f.get("deadStock")).isEqualTo(false);
        assertThat(dec(f, "stockValue")).isEqualByComparingTo("200.00");        // 5 × cost 40
        assertThat(f.get("valueAtCost")).isEqualTo(true);

        Map<String, Object> d = v.get(dead.toString());
        assertThat(d.get("deadStock")).isEqualTo(true);
        assertThat(dec(d, "stockValue")).isEqualByComparingTo("600.00");        // 4 × price 150
        assertThat(d.get("valueAtCost")).isEqualTo(false);
        assertThat(d.get("daysOfCover")).isNull();
        Map<String, Object> dc = v.get(deadCosted.toString());
        assertThat(dc.get("deadStock")).isEqualTo(true);
        assertThat(dc.get("lastSoldAt")).isNull();
        assertThat(dec(dc, "stockValue")).isEqualByComparingTo("120.00");

        Map<String, Object> r = v.get(returned.toString());
        assertThat(r.get("deadStock")).isEqualTo(false);
        assertThat(n(r, "comingBack")).isEqualTo(1);
        assertThat(dec(r, "returnsRate")).isEqualByComparingTo("0.3");
        assertThat(n(r, "returnedUnits90")).isEqualTo(2);
        assertThat(n(r, "exchangedUnits90")).isEqualTo(1);
        assertThat(r.get("topReturnReason")).isEqualTo("wrong_size");

        Map<String, Object> mv = v.get(moving.toString());
        assertThat(n(mv, "soldUnits30")).isEqualTo(10);
        assertThat(n(mv, "deliveredUnits30")).isZero();
        assertThat(dec(mv, "velocityPerDay")).isEqualByComparingTo("0.00");
        assertThat(mv.get("daysOfCover")).isNull();
        assertThat(dec(mv, "sellThrough")).isEqualByComparingTo("0.5");          // 10 / (10 + 10)

        // Default sort = velocity desc; filters; sorts; limit.
        assertThat(list(body, "variants").get(0).get("variantId")).isEqualTo(fast.toString());
        assertThat(byVariant(ok(t, "/api/v1/analytics/stock/variants?filter=running_low")).keySet())
            .containsExactly(fast.toString());
        assertThat(byVariant(ok(t, "/api/v1/analytics/stock/variants?filter=dead_stock&sort=cash")).keySet())
            .containsExactly(dead.toString(), deadCosted.toString());
        assertThat(list(ok(t, "/api/v1/analytics/stock/variants?sort=age"), "variants").get(0).get("variantId"))
            .isEqualTo(deadCosted.toString());
        Map<String, Object> one = ok(t, "/api/v1/analytics/stock/variants?limit=1");
        assertThat(list(one, "variants")).hasSize(1);
        assertThat(n(one, "total")).isEqualTo(5);
        for (String bad : List.of("sort=nope", "filter=nope", "limit=0", "limit=501")) {
            assertThat(get(t.ownerToken, "/api/v1/analytics/stock/variants?" + bad).getStatusCode()).as(bad)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ── restock + settings ───────────────────────────────────────────────────

    @Test
    void restock_velocityTimesLeadPlusCover_minusOnHandAndComingBack_withSettings() throws Exception {
        T t = new T("S4-Restock");
        UUID a = t.variant("A", "100", "30"), b = t.variant("B", "100", null), full = t.variant("Full", "100", null);
        t.delivered(a, 31, 2);                                    // 31 / 30 a day
        t.pieces(a, 10, 5);
        t.piece(a, "return_pending_inspection", t.main, 5);
        t.piece(a, "return_in_transit", null, 5);
        t.delivered(b, 15, 2);                                    // 0.5 a day, none on hand
        t.piece(b, "damaged", t.main, 5);
        t.delivered(full, 3, 2);
        t.pieces(full, 50, 5);                                    // plenty: no suggestion

        Map<String, Object> s0 = ok(t, "/api/v1/analytics/settings");
        assertThat(s0).containsEntry("supplierLeadDays", 21).containsEntry("coverDays", 35).containsEntry("defaults", true);
        Map<String, Object> r = ok(t, "/api/v1/analytics/stock/restock");
        assertThat(r).containsEntry("supplierLeadDays", 21).containsEntry("coverDays", 35).containsEntry("velocityDays", 30);
        List<Map<String, Object>> items = list(r, "items");
        assertThat(items).extracting(i -> i.get("variantId")).containsExactly(a.toString(), b.toString());
        assertThat(n(items.get(0), "suggestedUnits")).isEqualTo(46);           // ceil(31 × 56 / 30 = 57.87) − 10 − 2
        assertThat(dec(items.get(0), "costAtUnitCost")).isEqualByComparingTo("1380.00");
        assertThat(n(items.get(1), "suggestedUnits")).isEqualTo(28);           // 15 × 56 / 30
        assertThat(items.get(1).get("costAtUnitCost")).isNull();

        ResponseEntity<Map> saved = put(t.ownerToken, "/api/v1/analytics/settings", Map.of("supplierLeadDays", 10, "coverDays", 20));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok(t, "/api/v1/analytics/settings")).containsEntry("supplierLeadDays", 10).containsEntry("defaults", false);
        List<Map<String, Object>> after = list(ok(t, "/api/v1/analytics/stock/restock"), "items");
        assertThat(n(after.get(0), "suggestedUnits")).isEqualTo(19);           // ceil(31 × 30 / 30) − 12
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'analytics_settings_update'",
            Long.class, t.id)).isEqualTo(1);

        for (Map<String, Object> bad : List.of(Map.<String, Object>of("supplierLeadDays", 10),
                                               Map.<String, Object>of("supplierLeadDays", -1, "coverDays", 20),
                                               Map.<String, Object>of("supplierLeadDays", 10, "coverDays", 0),
                                               Map.<String, Object>of("supplierLeadDays", 366, "coverDays", 20))) {
            assertThat(put(t.ownerToken, "/api/v1/analytics/settings", bad).getStatusCode()).as("%s", bad)
                .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(put(t.token("manager"), "/api/v1/analytics/settings", Map.of("supplierLeadDays", 1, "coverDays", 1))
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ok(t, "/api/v1/analytics/settings")).containsEntry("supplierLeadDays", 10);
    }

    // ── piece trips ──────────────────────────────────────────────────────────

    @Test
    void pieceHistory_eachTripWithOrderAwbCityOutcomeAndFee_variantPiecesByTrips() {
        T t = new T("S4-Trips");
        UUID v = t.variant("V", "100", null);
        String p = t.piece(v, "available", t.main, 60);
        // Trip 1: refused, Bosta settled its fee (the event names the shipment).
        UUID o1 = t.order(daysAgo(50));
        t.line(o1, v, 1);
        UUID s1 = t.leg(o1, "returned", "Giza", 50, daysAgo(50));
        jdbc.update("UPDATE shipments SET settlement_status = 'deposited', cash_cycle_id = '9', deposited_amt = -60, " +
                    "bosta_fees = 60, deposited_at = now() WHERE id = ?", s1);
        t.event(p, "packed", "with_courier", o1, s1, daysAgo(49));
        t.event(p, "with_courier", "return_in_transit", o1, s1, daysAgo(47));
        // Trip 2: delivered; the event carries no shipment → the order's forward leg booked before it.
        UUID o2 = t.order(daysAgo(20));
        t.line(o2, v, 1);
        t.leg(o2, "delivered", "Cairo", 50, daysAgo(20));
        t.event(p, "awaiting_pickup", "with_courier", o2, null, daysAgo(19));
        // Trip 3: self-pickup handover, no leg at all.
        UUID o3 = t.order(daysAgo(5));
        t.line(o3, v, 1);
        t.event(p, "packed", "delivered", o3, null, daysAgo(4));

        Map<String, Object> h = ok(t, "/api/v1/analytics/pieces/" + p + "/history");
        assertThat(h.get("pieceId")).isEqualTo(p);
        assertThat(h.get("status")).isEqualTo("available");
        List<Map<String, Object>> trips = list(h, "trips");
        assertThat(trips).hasSize(3);
        assertThat(trips.get(0)).containsEntry("outcome", "refused").containsEntry("cityName", "Giza")
            .containsEntry("feeEstimated", false);
        assertThat(dec(trips.get(0), "fee")).isEqualByComparingTo("60.00");
        assertThat(trips.get(0).get("trackingNumber")).isNotNull();
        assertThat(trips.get(1)).containsEntry("outcome", "delivered").containsEntry("cityName", "Cairo")
            .containsEntry("feeEstimated", true);
        assertThat(dec(trips.get(1), "fee")).isEqualByComparingTo("57.00");
        assertThat(trips.get(2)).containsEntry("outcome", "delivered").containsEntry("trackingNumber", null);
        assertThat(trips.get(2).get("fee")).isNull();

        String other = t.piece(v, "available", t.main, 10);
        t.event(other, "packed", "with_courier", null, null, daysAgo(3));
        t.piece(v, "voided", t.main, 1);
        Map<String, Object> vp = ok(t, "/api/v1/analytics/variants/" + v + "/pieces?minTrips=2");
        assertThat(n(vp, "total")).isEqualTo(1);
        assertThat(list(vp, "pieces").get(0)).containsEntry("pieceId", p).containsEntry("trips", 3);
        assertThat(n(ok(t, "/api/v1/analytics/variants/" + v + "/pieces"), "total")).isEqualTo(2);   // voided left out

        T stranger = new T("S4-Stranger");
        assertThat(get(stranger.ownerToken, "/api/v1/analytics/pieces/" + p + "/history").getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(n(ok(stranger, "/api/v1/analytics/variants/" + v + "/pieces"), "total")).isZero();
        assertThat(get(t.ownerToken, "/api/v1/analytics/variants/" + v + "/pieces?minTrips=-1").getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── the upgraded alert ───────────────────────────────────────────────────

    @Test
    void sellsOutSoon_bestSellersOnly_atMostFourDaysOfCover() {
        T t = new T("S4-Alert");
        List<UUID> top = new ArrayList<>();
        for (int i = 0; i < 20; i++) {                            // 20 best sellers at 2 a day, plenty in stock…
            UUID v = t.variant("Top" + i, "100", null);
            t.delivered(v, 60, 2);
            t.pieces(v, i == 0 ? 8 : i == 1 ? 9 : 50, 5);         // …except Top0 (4 days) and Top1 (4.5 days)
            top.add(v);
        }
        UUID slow = t.variant("Slow", "100", null);               // 1 a day, 3 days of cover — not a best seller
        t.delivered(slow, 30, 2);
        t.pieces(slow, 3, 5);
        UUID soldOut = t.variant("SoldOut", "100", null);         // a best seller with nothing on hand: not "soon"
        t.delivered(soldOut, 90, 2);

        Map<String, Object> a = list(ok(t, "/api/v1/analytics/alerts?period=30d"), "alerts").stream()
            .filter(x -> "sells_out_soon".equals(x.get("key"))).findFirst().orElseThrow();
        assertThat(n(a, "count")).isEqualTo(1);
        assertThat(list(a, "skus")).extracting(s -> s.get("variantId")).containsExactly(top.get(0).toString());
        assertThat(dec(list(a, "skus").get(0), "daysOfCover")).isEqualByComparingTo("4.0");
        assertThat(a.get("amount")).isNull();
    }

    // ── roles + isolation ────────────────────────────────────────────────────

    @Test
    void ownerOnly_everyStockEndpoint() {
        T t = new T("S4-Roles");
        UUID v = t.variant("V", "100", null);
        String p = t.piece(v, "available", t.main, 3);
        String manager = t.token("manager"), worker = t.token("worker");
        for (String path : List.of("/api/v1/analytics/stock/summary", "/api/v1/analytics/stock/variants",
                                   "/api/v1/analytics/stock/restock", "/api/v1/analytics/settings",
                                   "/api/v1/analytics/pieces/" + p + "/history", "/api/v1/analytics/variants/" + v + "/pieces")) {
            assertThat(get(t.ownerToken, path).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(get(manager, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(worker, path).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void appUser_rls_stockAndSettingsAreTheTenantsOwn() {
        T a = new T("S4-IsoA");
        T b = new T("S4-IsoB");
        UUID va = a.variant("A", "100", "10");
        a.pieces(va, 3, 5);
        jdbc.update("INSERT INTO analytics_settings (tenant_id, supplier_lead_days, cover_days) VALUES (?, 5, 6)", a.id);

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        JdbcTemplate appJdbc = new JdbcTemplate(appUserDs);
        StockAnalyticsService svc = new StockAnalyticsService(appJdbc, Clock.system(CAIRO), floorOverrides, extras,
            new AuditService(appJdbc, new ObjectMapper()));
        AnalyticsPeriod p = new AnalyticsPeriod(LocalDate.now(CAIRO).minusDays(29), LocalDate.now(CAIRO));

        TenantContext.set(a.id);
        assertThat(tx.execute(s -> svc.summary(p)).inWarehouse()).isEqualTo(3);
        assertThat(tx.execute(s -> svc.settings()).supplierLeadDays()).isEqualTo(5);
        TenantContext.set(b.id);
        assertThat(tx.execute(s -> svc.summary(p)).hasPieces()).isFalse();
        assertThat(tx.execute(s -> svc.settings()).defaults()).isTrue();             // A's row is invisible
        tx.execute(s -> svc.saveSettings(7, 8, b.owner));                            // B writes its own row under RLS
        assertThat(jdbc.queryForObject("SELECT cover_days FROM analytics_settings WHERE tenant_id = ?", Integer.class, b.id))
            .isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT cover_days FROM analytics_settings WHERE tenant_id = ?", Integer.class, a.id))
            .isEqualTo(6);

        TenantContext.clear();
        assertThatThrownBy(() -> tx.execute(s -> svc.summary(p))).isInstanceOf(RuntimeException.class);
        Long visible = tx.execute(s -> appJdbc.queryForObject("SELECT COUNT(*) FROM analytics_settings", Long.class));
        assertThat(visible).isZero();
        // app_user can never delete a settings row (V151 REVOKE).
        TenantContext.set(b.id);
        assertThatThrownBy(() -> tx.execute(s -> appJdbc.update("DELETE FROM analytics_settings WHERE tenant_id = ?", b.id)))
            .isInstanceOf(RuntimeException.class);
    }
}
