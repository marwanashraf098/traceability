package com.traceability;

import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.SalesAnalyticsService;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.PinRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Analytics slice 1 — GET /api/v1/analytics/sales/variants and /products.
 *
 * Every test builds its own tenant (fixtures via the postgres connection), then reads through the
 * real HTTP endpoint with an OWNER token — except the isolation test, which reads as app_user under
 * RLS (the default test datasource is BYPASSRLS, so a green HTTP test alone proves nothing about RLS).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AnalyticsSalesTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static final String OVERRIDE_DOMAIN = "analytics-null-cutoff.myshopify.com";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.floor-overrides",  () -> OVERRIDE_DOMAIN + "=2026-07-02");
    }

    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired AnalyticsFloorOverrides floorOverrides;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** A tenant with an owner, one store (cutoff + domain as given) and one product. */
    final class T {
        final UUID id = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();
        final UUID store = UUID.randomUUID();
        final UUID product = UUID.randomUUID();
        final String ownerToken;

        T(String name, Instant ingestFrom, String shopDomain) {
            jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", id, name);
            jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                        "VALUES (?, ?, 'Owner', ?, 'x', 'owner')", owner, id, "owner+" + owner + "@an.test");
            jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                        "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                        store, id, shopDomain, ingestFrom == null ? null : Timestamp.from(ingestFrom));
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, image_url) " +
                        "VALUES (?, ?, ?, ?, 'Hoodie', 'https://cdn.test/hoodie.webp')",
                        product, id, store, "P-" + product);
            ownerToken = jwt.issueAccessToken(owner, id, "owner");
        }

        T(String name) {
            this(name, cairo(2026, 6, 1, 0, 0), "an-" + UUID.randomUUID() + ".myshopify.com");
        }

        UUID variant(String title, String price) {
            return variant(product, title, price);
        }

        UUID variant(UUID productId, String title, String price) {
            UUID v = UUID.randomUUID();
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        v, id, productId, "V-" + v, "SKU-" + title, title,
                        price == null ? null : new BigDecimal(price));
            return v;
        }

        UUID product(String title) {
            UUID p = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, ?)",
                        p, id, store, "P-" + p, title);
            return p;
        }

        UUID order(Instant placedAt) {
            return order(placedAt, "new", "{}", "EXT-" + UUID.randomUUID());
        }

        UUID order(Instant placedAt, String status, String rawJson, String externalId) {
            UUID o = UUID.randomUUID();
            jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                        "VALUES (?, ?, ?, ?, ?, ?::order_status, ?, ?::jsonb)",
                        o, id, store, externalId, "#" + externalId, status, Timestamp.from(placedAt), rawJson);
            return o;
        }

        /** A REST-shaped line: price, quantity, current_quantity, discount allocations. */
        void restLine(UUID order, UUID variant, int quantity, Integer currentQty, String price, String... allocations) {
            StringBuilder alloc = new StringBuilder("[");
            for (int i = 0; i < allocations.length; i++) {
                if (i > 0) alloc.append(',');
                alloc.append("{\"amount\":\"").append(allocations[i]).append("\"}");
            }
            alloc.append(']');
            String raw = "{\"price\":\"" + price + "\",\"quantity\":" + quantity +
                         (currentQty == null ? "" : ",\"current_quantity\":" + currentQty) +
                         ",\"discount_allocations\":" + alloc + "}";
            line(order, variant, quantity, raw);
        }

        void line(UUID order, UUID variant, int quantity, String rawJson) {
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, raw) " +
                        "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::jsonb)",
                        id, order, variant, quantity, rawJson);
        }
    }

    static Instant cairo(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(CAIRO).toInstant();
    }

    private String base() { return "http://localhost:" + port; }

    private ResponseEntity<Map> get(String token, String pathAndQuery) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(base() + pathAndQuery, HttpMethod.GET, new HttpEntity<>(h), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> variants(T t, String query) {
        ResponseEntity<Map> r = get(t.ownerToken, "/api/v1/analytics/sales/variants?" + query);
        assertThat(r.getStatusCode()).as("body: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> body) {
        return (Map<String, Object>) body.get("totals");
    }

    private static Map<String, Object> rowFor(Map<String, Object> body, UUID variant) {
        return rows(body, "variants").stream()
                .filter(r -> variant.toString().equals(r.get("variantId")))
                .findFirst().orElse(null);
    }

    private static BigDecimal dec(Object o) {
        return new BigDecimal(o.toString());
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }

    // ── period boundaries ────────────────────────────────────────────────────

    @Test
    void cairoDayBoundaries_lastDay2330Included_nextDay0010Excluded_firstDay0000Included() {
        T t = new T("An-Boundaries");
        UUID v = t.variant("M", "100.00");
        t.restLine(t.order(cairo(2026, 9, 1, 0, 0)),   v, 1, 1, "100.00");   // first instant of from-day
        t.restLine(t.order(cairo(2026, 9, 15, 23, 30)), v, 1, 1, "100.00");  // last day, 23:30 Cairo
        t.restLine(t.order(cairo(2026, 9, 16, 0, 10)),  v, 5, 5, "100.00");  // next day, 00:10 Cairo
        t.restLine(t.order(cairo(2026, 8, 31, 23, 59)), v, 7, 7, "100.00");  // day before from

        Map<String, Object> body = variants(t, "from=2026-09-01&to=2026-09-15");
        assertThat(num(rowFor(body, v).get("soldUnits"))).isEqualTo(2);
        assertThat(num(totals(body).get("orders"))).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) body.get("range");
        assertThat(range).containsEntry("from", "2026-09-01").containsEntry("to", "2026-09-15")
                         .containsEntry("tz", "Africa/Cairo");
    }

    @Test
    void dstChange_dayIs25HoursLong_secondOccurrenceOf2330Included_0010NextDayExcluded() {
        // Egypt's DST ends at 24:00 on the last Thursday of October (2026-10-29): 23:00–24:00 happens
        // twice, so Oct 29 ends at 22:00Z, not 21:00Z. A fixed +03 boundary would drop the second 23:30.
        assertThat(CAIRO.getRules().isDaylightSavings(Instant.parse("2026-10-29T12:00:00Z")))
                .as("JVM tzdata must know Egypt's 2026 DST for this test to mean anything").isTrue();
        Instant secondOccurrence = LocalDateTime.of(2026, 10, 29, 23, 30).atZone(CAIRO)
                .withLaterOffsetAtOverlap().toInstant();
        assertThat(secondOccurrence).isEqualTo(Instant.parse("2026-10-29T21:30:00Z"));

        T t = new T("An-DST");
        UUID v = t.variant("L", "100.00");
        t.restLine(t.order(secondOccurrence), v, 1, 1, "100.00");
        t.restLine(t.order(cairo(2026, 10, 30, 0, 10)), v, 4, 4, "100.00");   // 22:10Z — next Cairo day

        Map<String, Object> body = variants(t, "from=2026-10-29&to=2026-10-29");
        assertThat(num(rowFor(body, v).get("soldUnits"))).isEqualTo(1);

        Map<String, Object> next = variants(t, "from=2026-10-30&to=2026-10-30");
        assertThat(num(rowFor(next, v).get("soldUnits"))).isEqualTo(4);
    }

    // ── which lines are sold ─────────────────────────────────────────────────

    @Test
    void exclusions_cancelledByStatusOrRaw_internalExchange_preFloor() {
        Instant ingestFrom = cairo(2026, 9, 10, 12, 0);
        T t = new T("An-Exclusions", ingestFrom, "an-excl-" + UUID.randomUUID() + ".myshopify.com");
        UUID v = t.variant("S", "100.00");
        Instant day = cairo(2026, 9, 20, 12, 0);

        t.restLine(t.order(day), v, 1, 1, "100.00");                                            // counted
        t.restLine(t.order(day, "cancelled", "{}", "EXT-C1"), v, 10, 10, "100.00");             // status
        t.restLine(t.order(day, "new", "{\"cancelled_at\":\"2026-09-21T10:00:00+03:00\"}", "EXT-C2"),
                   v, 20, 20, "100.00");                                                         // raw only
        t.restLine(t.order(day, "new", "{}", "internal:exchange:777123"), v, 40, null, "100.00"); // internal
        t.restLine(t.order(ingestFrom.minusSeconds(60)), v, 80, 80, "100.00");                   // pre-floor
        t.restLine(t.order(ingestFrom), v, 2, 2, "100.00");                                       // at floor

        Map<String, Object> body = variants(t, "from=2026-09-01&to=2026-09-30");
        assertThat(num(rowFor(body, v).get("soldUnits"))).isEqualTo(3);
        assertThat(dec(rowFor(body, v).get("grossRevenue"))).isEqualByComparingTo("300.00");
        assertThat(num(totals(body).get("orders"))).isEqualTo(2);
    }

    @Test
    void nullCutoff_storeUsesConfiguredFloorDay_cutoffColumnStaysNull() {
        assertThat(floorOverrides.asMap()).containsEntry(OVERRIDE_DOMAIN, LocalDate.of(2026, 7, 2));
        T t = new T("An-NullCutoff", null, OVERRIDE_DOMAIN);
        UUID v = t.variant("XL", "100.00");
        t.restLine(t.order(cairo(2026, 7, 1, 23, 59)), v, 50, 50, "100.00");  // before the floor day
        t.restLine(t.order(cairo(2026, 7, 2, 0, 0)),   v, 1, 1, "100.00");    // floor = 00:00 Cairo
        t.restLine(t.order(cairo(2026, 7, 5, 9, 0)),   v, 2, 2, "100.00");

        Map<String, Object> body = variants(t, "from=2026-06-01&to=2026-07-31");
        assertThat(num(rowFor(body, v).get("soldUnits"))).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT orders_ingest_from FROM stores WHERE id = ?", Timestamp.class, t.store))
                .as("analytics never writes the ingest cutoff").isNull();

        // A NULL-cutoff store with no override has no floor at all.
        T noOverride = new T("An-NoFloor", null, "an-nofloor-" + UUID.randomUUID() + ".myshopify.com");
        UUID w = noOverride.variant("XL", "100.00");
        noOverride.restLine(noOverride.order(cairo(2026, 5, 4, 10, 0)), w, 6, 6, "100.00");
        assertThat(num(rowFor(variants(noOverride, "from=2026-05-01&to=2026-05-31"), w).get("soldUnits"))).isEqualTo(6);
    }

    @Test
    void currentQuantity_editTwoToOne_countsOne_oneToZero_dropped() {
        T t = new T("An-Edits");
        UUID kept = t.variant("Kept", "200.00");
        UUID removed = t.variant("Removed", "200.00");
        UUID o = t.order(cairo(2026, 9, 5, 10, 0));
        t.restLine(o, kept, 2, 1, "200.00");
        t.restLine(o, removed, 1, 0, "200.00");

        Map<String, Object> body = variants(t, "from=2026-09-01&to=2026-09-30");
        assertThat(num(rowFor(body, kept).get("soldUnits"))).isEqualTo(1);
        assertThat(dec(rowFor(body, kept).get("grossRevenue"))).isEqualByComparingTo("200.00");
        assertThat(rowFor(body, removed)).as("a fully removed line is not a sale").isNull();
        assertThat(num(totals(body).get("soldUnits"))).isEqualTo(1);
    }

    @Test
    void discounts_lineAllocationsApplied_orderLevelShippingDiscountNot() {
        T t = new T("An-Discounts");
        UUID v = t.variant("Disc", "500.00");
        UUID edited = t.variant("DiscEdited", "500.00");
        // The order carries an 85 EGP free-shipping discount at order level only.
        UUID o = t.order(cairo(2026, 9, 6, 10, 0), "new",
                "{\"total_discounts\":\"85.00\",\"total_shipping_price_set\":{\"shop_money\":{\"amount\":\"85.00\"}}}",
                "EXT-DISC");
        t.restLine(o, v, 2, 2, "500.00", "60.00", "40.00");     // 100 off the line → 450/unit
        t.restLine(o, edited, 2, 1, "500.00", "100.00");        // 100 over 2 original units → 450 for the 1 left

        Map<String, Object> body = variants(t, "from=2026-09-01&to=2026-09-30");
        assertThat(dec(rowFor(body, v).get("grossRevenue"))).isEqualByComparingTo("900.00");
        assertThat(dec(rowFor(body, edited).get("grossRevenue"))).isEqualByComparingTo("450.00");
        assertThat(dec(totals(body).get("grossRevenue"))).isEqualByComparingTo("1350.00");
        assertThat(num(totals(body).get("approximateLines"))).isZero();
    }

    @Test
    void missingRawPrice_usesVariantPrice_andCountsApproximate() {
        T t = new T("An-Approx");
        UUID v = t.variant("Approx", "300.00");
        UUID noPrice = t.variant("NoPriceAnywhere", null);
        UUID o = t.order(cairo(2026, 9, 7, 10, 0));
        t.line(o, v, 2, "{\"gid\":\"gid://shopify/LineItem/1\",\"quantity\":2,\"variantGid\":\"gid://shopify/ProductVariant/1\"}");
        t.line(o, noPrice, 1, null);
        t.restLine(t.order(cairo(2026, 9, 8, 10, 0)), v, 1, 1, "280.00");

        Map<String, Object> body = variants(t, "from=2026-09-01&to=2026-09-30");
        Map<String, Object> row = rowFor(body, v);
        assertThat(num(row.get("soldUnits"))).isEqualTo(3);
        assertThat(dec(row.get("grossRevenue"))).isEqualByComparingTo("880.00");   // 2 × 300 + 280
        assertThat(num(row.get("approximateLines"))).isEqualTo(1);
        assertThat(dec(rowFor(body, noPrice).get("grossRevenue"))).isEqualByComparingTo("0.00");
        assertThat(num(totals(body).get("approximateLines"))).isEqualTo(2);
    }

    @Test
    void lastSoldAt_isAllTimePostFloor_notJustThePeriod() {
        Instant ingestFrom = cairo(2026, 8, 1, 0, 0);
        T t = new T("An-LastSold", ingestFrom, "an-last-" + UUID.randomUUID() + ".myshopify.com");
        UUID v = t.variant("Last", "100.00");
        t.restLine(t.order(cairo(2026, 7, 30, 10, 0)), v, 1, 1, "100.00");          // pre-floor
        t.restLine(t.order(cairo(2026, 9, 10, 10, 0)), v, 1, 1, "100.00");          // in period
        Instant later = cairo(2026, 9, 25, 18, 45);
        t.restLine(t.order(later), v, 1, 1, "100.00");                               // after the period
        t.restLine(t.order(cairo(2026, 9, 28, 10, 0), "cancelled", "{}", "EXT-LC"), v, 1, 1, "100.00");

        Map<String, Object> body = variants(t, "from=2026-09-01&to=2026-09-15");
        Map<String, Object> row = rowFor(body, v);
        assertThat(num(row.get("soldUnits"))).isEqualTo(1);
        assertThat(Instant.parse(row.get("lastSoldAt").toString())).isEqualTo(later);
    }

    // ── products endpoint ────────────────────────────────────────────────────

    @Test
    void products_sortByUnitsOrRevenue_variantCount_topThreeVariants_limit() {
        T t = new T("An-Products");
        UUID shirt = t.product("Shirt");
        UUID s1 = t.variant(shirt, "S", "100.00");
        UUID s2 = t.variant(shirt, "M", "100.00");
        UUID s3 = t.variant(shirt, "L", "100.00");
        UUID s4 = t.variant(shirt, "XL", "100.00");
        UUID coat = t.product("Coat");
        UUID c1 = t.variant(coat, "One", "2000.00");
        UUID o = t.order(cairo(2026, 9, 9, 10, 0));
        t.restLine(o, s1, 5, 5, "100.00");
        t.restLine(o, s2, 4, 4, "100.00");
        t.restLine(o, s3, 3, 3, "100.00");
        t.restLine(o, s4, 1, 1, "100.00");
        t.restLine(o, c1, 1, 1, "2000.00");
        t.restLine(t.order(cairo(2026, 8, 31, 23, 59)), s4, 50, 50, "100.00");   // outside the period
        t.restLine(t.order(cairo(2026, 10, 1, 0, 0)), c1, 9, 9, "2000.00");      // outside the period

        ResponseEntity<Map> byUnits = get(t.ownerToken, "/api/v1/analytics/sales/products?from=2026-09-01&to=2026-09-30&sort=units");
        assertThat(byUnits.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> products = rows(byUnits.getBody(), "products");
        assertThat(products).extracting(p -> p.get("title")).containsExactly("Shirt", "Coat");
        Map<String, Object> shirtRow = products.get(0);
        assertThat(num(shirtRow.get("soldUnits"))).isEqualTo(13);
        assertThat(dec(shirtRow.get("grossRevenue"))).isEqualByComparingTo("1300.00");
        assertThat(num(shirtRow.get("variantCount"))).isEqualTo(4);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) shirtRow.get("topVariants");
        assertThat(top).extracting(x -> x.get("variantTitle")).containsExactly("S", "M", "L");
        assertThat(top).extracting(x -> num(x.get("soldUnits"))).containsExactly(5L, 4L, 3L);

        ResponseEntity<Map> byRevenue = get(t.ownerToken, "/api/v1/analytics/sales/products?from=2026-09-01&to=2026-09-30&sort=revenue&limit=1");
        List<Map<String, Object>> revenueRows = rows(byRevenue.getBody(), "products");
        assertThat(revenueRows).extracting(p -> p.get("title")).containsExactly("Coat");
        assertThat(byRevenue.getBody().get("sort")).isEqualTo("revenue");
    }

    // ── isolation (app_user + RLS) ───────────────────────────────────────────

    @Test
    void tenantIsolation_asAppUser_otherTenantsLinesNeverAppear_withSameTenantPositiveControl() {
        T a = new T("An-IsoA");
        T b = new T("An-IsoB");
        UUID va = a.variant("A-only", "100.00");
        UUID vb = b.variant("B-only", "100.00");
        a.restLine(a.order(cairo(2026, 9, 3, 10, 0)), va, 2, 2, "100.00");
        b.restLine(b.order(cairo(2026, 9, 3, 10, 0)), vb, 9, 9, "100.00");

        TenantAwareDataSource appUserDs = new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        SalesAnalyticsService svc = new SalesAnalyticsService(
                new JdbcTemplate(appUserDs), Clock.system(CAIRO), floorOverrides);
        AnalyticsPeriod sept = new AnalyticsPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        TenantContext.set(a.id);
        SalesAnalyticsService.VariantSalesResponse asA = tx.execute(s -> svc.variants(sept));
        assertThat(asA.variants()).extracting(SalesAnalyticsService.VariantSales::variantId).containsExactly(va);
        assertThat(asA.totals().soldUnits()).isEqualTo(2);
        SalesAnalyticsService.ProductSalesResponse productsA = tx.execute(s ->
                svc.products(sept, SalesAnalyticsService.Sort.UNITS, 10));
        assertThat(productsA.products()).extracting(SalesAnalyticsService.ProductSales::productId).containsExactly(a.product);

        TenantContext.set(b.id);
        SalesAnalyticsService.VariantSalesResponse asB = tx.execute(s -> svc.variants(sept));
        assertThat(asB.variants()).extracting(SalesAnalyticsService.VariantSales::variantId).containsExactly(vb);

        // Over HTTP too: tenant A's owner never sees B's variant.
        assertThat(rowFor(variants(a, "from=2026-09-01&to=2026-09-30"), vb)).isNull();
    }

    // ── access ───────────────────────────────────────────────────────────────

    @Test
    void ownerOnly_ownerOk_managerWorkerAndStationWorker403() {
        T t = new T("An-Roles");
        UUID manager = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Mgr', ?, 'x', 'manager')", manager, t.id, "mgr+" + manager + "@an.test");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, pin_code, active) " +
                    "VALUES (?, ?, 'Wkr', ?, 'x', 'worker', ?, true)",
                    worker, t.id, "wkr+" + worker + "@an.test", encoder.encode("2468"));

        // A station worker: the tablet (owner session) switches to the worker through the real PIN flow.
        HttpHeaders ownerHeaders = new HttpHeaders();
        ownerHeaders.setBearerAuth(t.ownerToken);
        ownerHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> pin = rest.postForEntity(base() + "/api/v1/auth/pin",
                new HttpEntity<>(new PinRequest(worker.toString(), "2468"), ownerHeaders), Map.class);
        assertThat(pin.getStatusCode()).as("pin body: %s", pin.getBody()).isEqualTo(HttpStatus.OK);
        String stationWorkerToken = (String) pin.getBody().get("accessToken");

        String managerToken = jwt.issueAccessToken(manager, t.id, "manager");
        String workerToken = jwt.issueAccessToken(worker, t.id, "worker");

        for (String path : List.of("/api/v1/analytics/sales/variants?period=7d",
                                   "/api/v1/analytics/sales/products?period=7d")) {
            assertThat(get(t.ownerToken, path).getStatusCode()).as("owner %s", path).isEqualTo(HttpStatus.OK);
            assertThat(get(managerToken, path).getStatusCode()).as("manager %s", path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(workerToken, path).getStatusCode()).as("worker %s", path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get(stationWorkerToken, path).getStatusCode()).as("station worker %s", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ── edges ────────────────────────────────────────────────────────────────

    @Test
    void emptyTenant_returnsZerosNotErrors() {
        T t = new T("An-Empty");
        Map<String, Object> body = variants(t, "period=30d");
        assertThat(rows(body, "variants")).isEmpty();
        assertThat(num(totals(body).get("soldUnits"))).isZero();
        assertThat(dec(totals(body).get("grossRevenue"))).isEqualByComparingTo("0.00");
        assertThat(num(totals(body).get("orders"))).isZero();
        assertThat(num(totals(body).get("approximateLines"))).isZero();

        ResponseEntity<Map> products = get(t.ownerToken, "/api/v1/analytics/sales/products?period=today");
        assertThat(products.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rows(products.getBody(), "products")).isEmpty();
    }

    @Test
    void badParameters_400() {
        T t = new T("An-BadParams");
        for (String q : List.of("variants?period=7d&from=2026-09-01&to=2026-09-02",
                                "variants?from=2026-09-01",
                                "variants?from=2026-09-10&to=2026-09-01",
                                "variants?from=2025-01-01&to=2026-01-02",     // 367 days
                                "variants?period=90d",
                                "variants?from=2026-13-01&to=2026-13-02",
                                "products?period=7d&sort=price",
                                "products?period=7d&limit=0",
                                "products?period=7d&limit=101")) {
            assertThat(get(t.ownerToken, "/api/v1/analytics/sales/" + q).getStatusCode())
                    .as(q).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(get(t.ownerToken, "/api/v1/analytics/sales/variants?from=2025-01-01&to=2026-01-01")
                .getStatusCode()).as("exactly 366 days").isEqualTo(HttpStatus.OK);
    }

    @Test
    void presets_endTodayInclusive() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        assertThat(AnalyticsPeriod.resolve("today", null, null, today))
                .isEqualTo(new AnalyticsPeriod(today, today));
        assertThat(AnalyticsPeriod.resolve("7d", null, null, today))
                .isEqualTo(new AnalyticsPeriod(LocalDate.of(2026, 9, 30), today));
        assertThat(AnalyticsPeriod.resolve("30d", null, null, today))
                .isEqualTo(new AnalyticsPeriod(LocalDate.of(2026, 9, 7), today));
        assertThat(AnalyticsPeriod.resolve(null, null, null, today))
                .as("no parameters = 30d").isEqualTo(new AnalyticsPeriod(LocalDate.of(2026, 9, 7), today));
        assertThatThrownBy(() -> AnalyticsFloorOverrides.class.getConstructor(String.class)
                .newInstance("not-a-pair")).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AnalyticsPeriod.resolve("week", null, null, today))
                .isInstanceOf(ResponseStatusException.class);
    }
}
