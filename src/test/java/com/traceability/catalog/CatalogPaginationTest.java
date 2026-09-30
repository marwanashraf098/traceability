package com.traceability.catalog;

import com.traceability.inventory.InventoryStockController;
import com.traceability.inventory.VariantStockService;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /catalog server-side filters + pagination, and the Stock tab's status filter (activation perf,
 * Part C). Controllers are built directly (no HTTP) on a statement-counting DataSource.
 *
 *   c1 — N+1 gone: a page of 1 product and a page of 40 products issue the SAME number of SQL
 *        statements.
 *   c2 — keyset pagination: (title ASC, id ASC), limit-sized pages, nextCursor null at the end,
 *        equal titles ordered by id, no product repeated or skipped.
 *   c3 — search: q matches the product title or a variant SKU, case-insensitive.
 *   c4 — status: multi-value (repeat or comma) filters; absent = every status.
 *   c5 — variantIds: only the products holding those variants.
 *   c6 — no limit: every match in one response, nextCursor null (the pre-pagination contract),
 *        variants per product ordered by title (unchanged shape).
 *   s1 — Stock tab: status filter; absent = every status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogPaginationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    final AtomicInteger statements = new AtomicInteger();
    CatalogController catalog;
    InventoryStockController stock;
    UUID tenant, store;

    @BeforeAll
    void setup() {
        DataSource counting = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws SQLException { return count(super.getConnection()); }
        };
        JdbcTemplate cj = new JdbcTemplate(counting);
        DataSourceTransactionManager ctx = new DataSourceTransactionManager(counting);
        catalog = new CatalogController(cj, ctx, new VariantStockService(cj));
        stock = new InventoryStockController(cj, ctx, new VariantStockService(cj), Clock.systemUTC());

        tenant = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Catalog pages')", tenant);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, 'cat-pages.myshopify.com', 'connected')", store, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (gen_random_uuid(), ?, 'Main', true)", tenant);
    }

    private Connection count(Connection c) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                String n = method.getName();
                if (n.equals("prepareStatement") || n.equals("createStatement") || n.equals("prepareCall")) statements.incrementAndGet();
                try {
                    return method.invoke(c, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    @AfterEach
    void clearProducts() {
        jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenant);
        jdbc.update("DELETE FROM products WHERE tenant_id = ?", tenant);
        TenantContext.clear();
    }

    private UUID product(String title, String status, String... skus) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, ?)",
            id, tenant, store, "gid://shopify/Product/" + id, title, status);
        int i = 0;
        for (String sku : skus) {
            jdbc.update("INSERT INTO variants (tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?)",
                tenant, id, "gid://shopify/ProductVariant/" + id + "-" + i, "V" + (char) ('z' - i), sku);
            i++;
        }
        return id;
    }

    private CatalogController.CatalogResponse list(String q, List<String> status, List<UUID> variantIds, String cursor, Integer limit) {
        TenantContext.set(tenant);
        try {
            return catalog.list(q, status, variantIds, cursor, limit);
        } finally {
            TenantContext.clear();
        }
    }

    private static List<String> titles(CatalogController.CatalogResponse r) {
        return r.products().stream().map(CatalogController.ProductRow::title).toList();
    }

    @Test
    void c1_statementCountIsConstantInThePageSize() {
        for (int i = 0; i < 40; i++) product(String.format("P%02d", i), "active", "S" + i + "a", "S" + i + "b");

        statements.set(0);
        CatalogController.CatalogResponse one = list(null, null, null, null, 1);
        int forOne = statements.get();
        statements.set(0);
        CatalogController.CatalogResponse forty = list(null, null, null, null, 40);
        int forForty = statements.get();

        assertThat(one.products()).hasSize(1);
        assertThat(forty.products()).hasSize(40).allSatisfy(p -> assertThat(p.variants()).hasSize(2));
        assertThat(forForty).as("no per-product query").isEqualTo(forOne);
        assertThat(forForty).isLessThanOrEqualTo(8);
    }

    @Test
    void c2_keysetPagination_titleThenId_noRepeatNoSkip() {
        List<UUID> twins = new ArrayList<>(List.of(product("Same", "active", "T1"), product("Same", "active", "T2")));
        product("Alpha", "active", "A"); product("Delta", "draft", "D"); product("Beta", "active", "B");
        twins.sort(java.util.Comparator.comparing(UUID::toString));

        List<String> seen = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            CatalogController.CatalogResponse page = list(null, null, null, cursor, 2);
            assertThat(page.products().size()).isLessThanOrEqualTo(2);
            seen.addAll(titles(page));
            page.products().forEach(p -> ids.add(p.id()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly("Alpha", "Beta", "Delta", "Same", "Same");
        assertThat(ids.subList(3, 5)).containsExactly(twins.get(0).toString(), twins.get(1).toString());
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void c3_searchMatchesTitleOrSku_caseInsensitive() {
        product("Red Shirt", "active", "RS-1");
        product("Blue Coat", "active", "BC-SHIRTLESS");
        product("Green Hat", "active", "GH-1");

        assertThat(titles(list("shirt", null, null, null, 10))).containsExactly("Blue Coat", "Red Shirt");
        assertThat(titles(list("gh-", null, null, null, 10))).containsExactly("Green Hat");
        assertThat(titles(list("nothing", null, null, null, 10))).isEmpty();
    }

    @Test
    void c4_statusFilter_multiValue_absentMeansAll() {
        product("A-active", "active", "1"); product("B-draft", "draft", "2");
        product("C-archived", "archived", "3"); product("D-unlisted", "unlisted", "4");

        assertThat(titles(list(null, List.of("active", "draft"), null, null, 10))).containsExactly("A-active", "B-draft");
        assertThat(titles(list(null, List.of("active,draft"), null, null, 10))).containsExactly("A-active", "B-draft");
        assertThat(titles(list(null, List.of("ARCHIVED"), null, null, 10))).containsExactly("C-archived");
        assertThat(titles(list(null, null, null, null, 10))).containsExactly("A-active", "B-draft", "C-archived", "D-unlisted");
    }

    @Test
    void c5_variantIds_onlyProductsHoldingThem() {
        product("One", "archived", "X1");
        UUID two = product("Two", "active", "X2");
        product("Three", "active", "X3");
        UUID v2 = jdbc.queryForObject("SELECT id FROM variants WHERE product_id = ?", UUID.class, two);

        assertThat(titles(list(null, null, List.of(v2), null, null))).containsExactly("Two");
    }

    @Test
    void c6_noLimit_everythingInOneResponse_nextCursorNull_variantsByTitle() {
        for (int i = 0; i < 7; i++) product("N" + i, "active", "a" + i, "b" + i, "c" + i);

        CatalogController.CatalogResponse all = list(null, null, null, null, null);
        assertThat(all.products()).hasSize(7);
        assertThat(all.nextCursor()).isNull();
        assertThat(all.products().get(0).variants()).extracting(CatalogController.VariantRow::title)
            .containsExactly("Vx", "Vy", "Vz");
    }

    @Test
    void s1_stockTab_statusFilter_absentMeansAll() {
        product("A-active", "active", "1"); product("B-draft", "draft", "2"); product("C-archived", "archived", "3");

        TenantContext.set(tenant);
        try {
            assertThat(stock.stock(null, null, false, List.of("active", "draft"), null, 20).items())
                .extracting(InventoryStockController.StockProduct::title).containsExactly("A-active", "B-draft");
            assertThat(stock.stock(null, null, false, null, null, 20).items())
                .extracting(InventoryStockController.StockProduct::title).containsExactly("A-active", "B-draft", "C-archived");
        } finally {
            TenantContext.clear();
        }
    }
}
