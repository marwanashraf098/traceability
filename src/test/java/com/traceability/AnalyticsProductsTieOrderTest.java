package com.traceability;

import com.traceability.analytics.AnalyticsFloorOverrides;
import com.traceability.analytics.AnalyticsPeriod;
import com.traceability.analytics.SalesAnalyticsService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /analytics/sales/products with exact ties (2026-10-21). The product ranking ended on the product title and the
 * per-product top-3 variants on the variant title — neither is unique, so tied rows came back in any order and the
 * LIMIT / [1:3] cut depended on it. Both now end on the id.
 *
 * pt1 — thirty products tied on units, revenue AND title, limit 5 → the five lowest product ids, same every call,
 *       both sorts, under the default plan AND a hash-aggregate plan (enable_sort off) — the default plan groups in
 *       product-id order and a top-N sort keeps the first of equal rows, which hid the missing tie-break.
 * pt2 — one product with six variants tied on units, revenue AND title → its top 3 are the three lowest variant ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AnalyticsProductsTieOrderTest {

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
    }

    static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    static final AnalyticsPeriod SEPT = new AnalyticsPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    @Autowired JdbcTemplate jdbc;
    @Autowired AnalyticsFloorOverrides floorOverrides;
    @Autowired PlatformTransactionManager txm;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void pt1_productsTiedOnEverything_lowestProductIdsAtTheLimit_sameEveryCall() {
        UUID tenant = tenant();
        UUID store = store(tenant);
        List<UUID> products = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            UUID p = product(tenant, store, "Same");
            products.add(p);
            sell(tenant, store, variant(tenant, p, "One"), 2);
        }
        List<UUID> expected = products.stream().map(UUID::toString).sorted().limit(5).map(UUID::fromString).toList();

        SalesAnalyticsService svc = new SalesAnalyticsService(jdbc, Clock.system(CAIRO), floorOverrides);
        TenantContext.set(tenant);
        TransactionTemplate tx = new TransactionTemplate(txm);
        for (String plan : List.of("default", "enable_sort off")) {
            for (SalesAnalyticsService.Sort sort : SalesAnalyticsService.Sort.values()) {
                for (int i = 0; i < 3; i++) {
                    List<UUID> got = tx.execute(s -> {
                        if (!plan.equals("default")) jdbc.execute("SET LOCAL enable_sort = off");
                        return svc.products(SEPT, sort, 5).products().stream()
                            .map(SalesAnalyticsService.ProductSales::productId).toList();
                    });
                    assertThat(got).as("%s plan, %s, call %d", plan, sort, i).containsExactlyElementsOf(expected);
                }
            }
        }
    }

    @Test
    void pt2_variantsTiedOnEverything_topThreeAreTheLowestVariantIds() {
        UUID tenant = tenant();
        UUID store = store(tenant);
        UUID product = product(tenant, store, "Same");
        List<UUID> variants = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID v = variant(tenant, product, "Same");
            variants.add(v);
            sell(tenant, store, v, 1);
        }
        List<UUID> expected = variants.stream().map(UUID::toString).sorted().limit(3).map(UUID::fromString).toList();

        SalesAnalyticsService svc = new SalesAnalyticsService(jdbc, Clock.system(CAIRO), floorOverrides);
        TenantContext.set(tenant);
        for (SalesAnalyticsService.Sort sort : SalesAnalyticsService.Sort.values()) {
            List<SalesAnalyticsService.ProductSales> rows = svc.products(SEPT, sort, 10).products();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).topVariants())
                .extracting(SalesAnalyticsService.TopVariant::variantId)
                .as("%s", sort)
                .containsExactlyElementsOf(expected);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private UUID tenant() {
        UUID t = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TieOrder')", t);
        return t;
    }

    private UUID store(UUID tenant) {
        UUID s = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from) " +
                    "VALUES (?, ?, 'shopify', ?, 'connected', ?)",
                    s, tenant, "tie-" + s + ".myshopify.com",
                    Timestamp.from(LocalDateTime.of(2026, 6, 1, 0, 0).atZone(CAIRO).toInstant()));
        return s;
    }

    private UUID product(UUID tenant, UUID store, String title) {
        UUID p = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) VALUES (?, ?, ?, ?, ?)",
                    p, tenant, store, "P-" + p, title);
        return p;
    }

    private UUID variant(UUID tenant, UUID product, String title) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    v, tenant, product, "V-" + v, "SKU-" + v, title, new BigDecimal("100.00"));
        return v;
    }

    /** One order on 2026-09-03 with a REST-shaped line of {@code qty} at 100.00. */
    private void sell(UUID tenant, UUID store, UUID variant, int qty) {
        UUID o = UUID.randomUUID();
        String ext = "EXT-" + o;
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw) " +
                    "VALUES (?, ?, ?, ?, ?, 'new'::order_status, ?, '{}'::jsonb)",
                    o, tenant, store, ext, "#" + ext,
                    Timestamp.from(LocalDateTime.of(2026, 9, 3, 10, 0).atZone(CAIRO).toInstant()));
        String raw = "{\"price\":\"100.00\",\"quantity\":" + qty + ",\"current_quantity\":" + qty +
                     ",\"discount_allocations\":[]}";
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, raw) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::jsonb)",
                    tenant, o, variant, qty, raw);
    }
}
