package com.traceability;

import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway.LinePrice;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway.OrderPrices;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.portal.OrderLinePaidPrices;
import com.traceability.portal.OrderLinePaidPrices.Source;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Returns portal fees F0 — the unit price paid per order line: V208's order_line_paid_unit_price (one stored
 * definition), then Shopify live (outside any transaction), then the catalogue price (approximate).
 *
 *   d1 — the SQL definition: REST net (price − allocations / quantity) · paid_unit_price key · REST wins over the
 *        key · floored at 0 · NULL (never 0) when neither exists · the key never reaches V149's unit_price.
 *   r1 — every line stored → STORED, Shopify never called.
 *   r2 — a line with no stored price → Shopify live (lines of one variant averaged by quantity), the token read
 *        under the tenant (RLS) — the other line keeps its stored price.
 *   r3 — Shopify fails → CATALOG (approximate); no catalogue price either → NONE with a null price.
 *   r4 — no Shopify call when the store is disconnected or the order isn't a Shopify order (internal:exchange:).
 *   r5 — refuses to run inside a transaction (it may call Shopify), before any Shopify call.
 *   r6 — a portal pre-connect order (merchant_orders hides it) is priced from its stored key.
 *   r7 — a Shopify-native exchange shadow order (internal:shopify-exchange:) returns nothing, even with prices.
 *   i1 — the GraphQL import path (ShopifySyncService) stores Shopify's paid price under 'paid_unit_price' and the
 *        resolver reads it back as STORED; V149's unit_price stays NULL (analytics unchanged).
 *   x1 — app_user: tenant B gets nothing for tenant A's order and never reaches Shopify; tenant A (positive
 *        control) gets its prices on the same connection.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderLinePaidPricesTest {

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

    private static final String SHOP = "paid-prices-f0.myshopify.com";

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderLinePaidPrices prices;
    @Autowired PlatformTransactionManager txm;
    @Autowired com.traceability.integrations.shopify.ShopifySyncService sync;

    @MockBean ShopifyOrderPriceGateway shopify;
    @MockBean ShopifyTokenProvider     tokens;
    @MockBean JobScheduler             jobScheduler;

    UUID tenantA, storeA, shirt, pants, tenantB, storeB, disconnectedStore;
    OrderLinePaidPrices appUserPrices;

    @BeforeAll
    void setup() {
        tenantA = UUID.randomUUID(); storeA = UUID.randomUUID(); disconnectedStore = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Paid prices A')", tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            storeA, tenantA, SHOP);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'paid-off.myshopify.com', 'disconnected')",
            disconnectedStore, tenantA);
        shirt = variant(tenantA, storeA, "gid://shopify/ProductVariant/5501", "950.00");
        pants = variant(tenantA, storeA, "gid://shopify/ProductVariant/5502", "400.00");

        tenantB = UUID.randomUUID(); storeB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Paid prices B')", tenantB);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'paid-b.myshopify.com', 'connected')",
            storeB, tenantB);

        DataSource appUserDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserPrices = new OrderLinePaidPrices(new JdbcTemplate(appUserDs),
            new DataSourceTransactionManager(appUserDs), shopify, tokens);
    }

    @BeforeEach
    void resetMocks() {
        TenantContext.clear();
        reset(shopify, tokens);
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    // ── d1: the SQL definition ──────────────────────────────────────────────────

    @Test
    void d1_sqlDefinition() {
        UUID order = order(storeA, "gid://shopify/Order/" + rnd());
        UUID rest = line(order, shirt, 2, "{\"price\":\"500.00\",\"quantity\":2,\"discount_allocations\":[{\"amount\":\"100.00\"}]}");
        UUID imported = line(order, pants, 1, "{\"current_quantity\":1,\"paid_unit_price\":\"199.50\"}");
        UUID both = line(order, shirt, 1, "{\"price\":\"300.00\",\"quantity\":1,\"paid_unit_price\":\"10.00\"}");
        UUID negative = line(order, pants, 1, "{\"price\":\"50.00\",\"quantity\":1,\"discount_allocations\":[{\"amount\":\"80.00\"}]}");
        UUID none = line(order, pants, 1, "{\"current_quantity\":1}");
        UUID nullRaw = line(order, pants, 1, null);

        assertThat(paidSql(rest)).isEqualByComparingTo("450.00");
        assertThat(paidSql(imported)).isEqualByComparingTo("199.50");
        assertThat(paidSql(both)).as("the REST net price wins over the key").isEqualByComparingTo("300.00");
        assertThat(paidSql(negative)).as("floored at 0").isEqualByComparingTo("0");
        assertThat(paidSql(none)).as("nothing stored → NULL, never 0").isNull();
        assertThat(paidSql(nullRaw)).isNull();
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE id = ?", BigDecimal.class, imported))
            .as("the paid key never feeds V149's analytics price").isNull();
    }

    // ── r: the resolver ─────────────────────────────────────────────────────────

    @Test
    void r1_allStored_noShopifyCall() {
        UUID order = order(storeA, "gid://shopify/Order/" + rnd());
        UUID a = line(order, shirt, 1, "{\"price\":\"950.00\",\"quantity\":1,\"discount_allocations\":[{\"amount\":\"95.00\"}]}");
        UUID b = line(order, pants, 1, "{\"paid_unit_price\":\"360.00\"}");

        List<OrderLinePaidPrices.LinePrice> out = prices.resolve(tenantA, order);

        assertThat(out).extracting(OrderLinePaidPrices.LinePrice::orderItemId).containsExactlyInAnyOrder(a, b);
        assertThat(byItem(out, a).unitPrice()).isEqualByComparingTo("855.00");
        assertThat(byItem(out, a).source()).isEqualTo(Source.STORED);
        assertThat(byItem(out, b).unitPrice()).isEqualByComparingTo("360.00");
        assertThat(out).noneMatch(OrderLinePaidPrices.LinePrice::approximate);
        verifyNoInteractions(shopify, tokens);
    }

    @Test
    void r2_missingLine_readsShopifyLive_tokenUnderTheTenant() {
        String gid = "gid://shopify/Order/" + rnd();
        UUID order = order(storeA, gid);
        UUID stored = line(order, shirt, 1, "{\"paid_unit_price\":\"900.00\"}");
        UUID missing = line(order, pants, 4, "{\"current_quantity\":4}");
        AtomicReference<UUID> tenantAtTokenRead = new AtomicReference<>();
        when(tokens.getValidToken(storeA)).thenAnswer(inv -> { tenantAtTokenRead.set(TenantContext.get()); return "tok"; });
        when(shopify.fetchOrderPrices(SHOP, "tok", gid)).thenReturn(new OrderPrices("EGP", List.of(
            new LinePrice("gid://shopify/ProductVariant/5502", 1, new BigDecimal("100.00")),
            new LinePrice("gid://shopify/ProductVariant/5502", 3, new BigDecimal("200.00")),
            new LinePrice("gid://shopify/ProductVariant/5501", 1, new BigDecimal("1.00")))));

        List<OrderLinePaidPrices.LinePrice> out = prices.resolve(tenantA, order);

        assertThat(byItem(out, missing).source()).isEqualTo(Source.SHOPIFY);
        assertThat(byItem(out, missing).unitPrice()).as("(100 + 3 × 200) / 4").isEqualByComparingTo("175.00");
        assertThat(byItem(out, stored).source()).as("a stored price is never replaced by Shopify's").isEqualTo(Source.STORED);
        assertThat(byItem(out, stored).unitPrice()).isEqualByComparingTo("900.00");
        assertThat(tenantAtTokenRead.get()).as("the stores row is read under the tenant").isEqualTo(tenantA);
        verify(shopify, times(1)).fetchOrderPrices(anyString(), anyString(), anyString());
    }

    @Test
    void r3_shopifyFails_catalogApproximate_thenNone() {
        String gid = "gid://shopify/Order/" + rnd();
        UUID order = order(storeA, gid);
        UUID priced = line(order, pants, 1, "{}");
        UUID noPrice = line(order, variant(tenantA, storeA, "gid://shopify/ProductVariant/" + rnd(), null), 1, "{}");
        when(tokens.getValidToken(storeA)).thenReturn("tok");
        when(shopify.fetchOrderPrices(any(), any(), any())).thenThrow(new ShopifyException("boom"));

        List<OrderLinePaidPrices.LinePrice> out = prices.resolve(tenantA, order);

        assertThat(byItem(out, priced).source()).isEqualTo(Source.CATALOG);
        assertThat(byItem(out, priced).approximate()).isTrue();
        assertThat(byItem(out, priced).unitPrice()).isEqualByComparingTo("400.00");
        assertThat(byItem(out, noPrice).source()).isEqualTo(Source.NONE);
        assertThat(byItem(out, noPrice).unitPrice()).isNull();
    }

    @Test
    void r4_noShopifyCall_disconnectedStore_orInternalOrder() {
        UUID offStore = order(disconnectedStore, "gid://shopify/Order/" + rnd());
        line(offStore, pants, 1, "{}");
        UUID internal = order(storeA, "internal:exchange:" + rnd());
        line(internal, pants, 1, null);

        assertThat(prices.resolve(tenantA, offStore)).extracting(OrderLinePaidPrices.LinePrice::source).containsExactly(Source.CATALOG);
        assertThat(prices.resolve(tenantA, internal)).extracting(OrderLinePaidPrices.LinePrice::source).containsExactly(Source.CATALOG);
        verifyNoInteractions(shopify, tokens);
    }

    @Test
    void r5_refusesInsideATransaction() {
        UUID order = order(storeA, "gid://shopify/Order/" + rnd());
        line(order, pants, 1, "{}");
        TransactionTemplate tx = new TransactionTemplate(txm);

        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> tx.execute(s -> prices.resolve(tenantA, order))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no transaction open");
        verifyNoInteractions(shopify, tokens);
    }

    @Test
    void r6_portalPreConnectOrder_pricedFromItsKey() {
        String shopifyGid = "gid://shopify/Order/" + rnd();
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, origin, shopify_order_gid) " +
            "VALUES (?, ?, 'internal:portal:' || gen_random_uuid(), ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), '{}'::jsonb, " +
            "        'portal_pre_connect', ?) RETURNING id",
            UUID.class, tenantA, storeA, "#P" + rnd(), shopifyGid);
        UUID item = line(order, shirt, 2, "{\"current_quantity\":2,\"paid_unit_price\":\"812.50\"}");

        List<OrderLinePaidPrices.LinePrice> out = prices.resolve(tenantA, order);

        assertThat(out).singleElement().satisfies(p -> {
            assertThat(p.orderItemId()).isEqualTo(item);
            assertThat(p.source()).isEqualTo(Source.STORED);
            assertThat(p.unitPrice()).isEqualByComparingTo("812.50");
        });
        verifyNoInteractions(shopify, tokens);
    }

    @Test
    void r7_shopifyExchangeShadowOrder_neverPriced() {
        // V216: a shadow order carries origin 'shopify_exchange' (identity CHECK with the prefix).
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw, origin) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), '{}'::jsonb, 'shopify_exchange') RETURNING id",
            UUID.class, tenantA, storeA, "internal:shopify-exchange:" + rnd(), "#" + rnd() + "-X1");
        line(order, shirt, 1, "{\"paid_unit_price\":\"500.00\"}");
        assertThat(prices.resolve(tenantA, order)).isEmpty();
        verifyNoInteractions(shopify, tokens);
    }

    @Test
    void i1_importStoresThePaidPrice_resolverReadsIt() {
        String gid = "gid://shopify/Order/" + rnd();
        String lineGid = "gid://shopify/LineItem/" + rnd();
        com.traceability.integrations.shopify.ShopifyGateway.Order o = new com.traceability.integrations.shopify.ShopifyGateway.Order(
            gid, "#I" + rnd(), null, null, null, "PAID", List.of(), new BigDecimal("737.00"),
            List.of(new com.traceability.integrations.shopify.ShopifyGateway.LineItem(lineGid, 1, "gid://shopify/ProductVariant/5501", 1, "737.00")),
            java.time.Instant.now(), new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("id", gid));

        TenantContext.runAs(tenantA, () -> sync.ingestMissingOrder(storeA, tenantA, o));

        UUID order = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND external_id = ?", UUID.class, tenantA, gid);
        assertThat(jdbc.queryForObject("SELECT raw ->> 'paid_unit_price' FROM order_items WHERE order_id = ?", String.class, order))
            .isEqualTo("737.00");
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id = ?", BigDecimal.class, order)).isNull();
        assertThat(prices.resolve(tenantA, order)).singleElement().satisfies(p -> {
            assertThat(p.source()).isEqualTo(Source.STORED);
            assertThat(p.unitPrice()).isEqualByComparingTo("737.00");
        });
        verifyNoInteractions(shopify, tokens);
    }

    // ── x: app_user isolation ───────────────────────────────────────────────────

    @Test
    void x1_appUser_crossTenantSeesNothing_sameTenantPositiveControl() {
        String gid = "gid://shopify/Order/" + rnd();
        UUID order = order(storeA, gid);
        line(order, pants, 1, "{}");                                   // no stored price → would call Shopify
        when(tokens.getValidToken(any())).thenReturn("tok");
        when(shopify.fetchOrderPrices(any(), any(), any())).thenReturn(new OrderPrices("EGP", List.of(
            new LinePrice("gid://shopify/ProductVariant/5502", 1, new BigDecimal("380.00")))));

        assertThat(appUserPrices.resolve(tenantB, order)).as("tenant B, tenant A's order id").isEmpty();
        verifyNoInteractions(shopify, tokens);

        List<OrderLinePaidPrices.LinePrice> own = appUserPrices.resolve(tenantA, order);
        assertThat(own).as("positive control: tenant A on the same app_user connection").singleElement()
            .satisfies(p -> {
                assertThat(p.source()).isEqualTo(Source.SHOPIFY);
                assertThat(p.unitPrice()).isEqualByComparingTo("380.00");
            });
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private BigDecimal paidSql(UUID item) {
        return jdbc.queryForObject("SELECT order_line_paid_unit_price(oi) FROM order_items oi WHERE oi.id = ?", BigDecimal.class, item);
    }

    private static OrderLinePaidPrices.LinePrice byItem(List<OrderLinePaidPrices.LinePrice> out, UUID item) {
        return out.stream().filter(p -> p.orderItemId().equals(item)).findFirst().orElseThrow();
    }

    private static long rnd() { return ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999); }

    private UUID variant(UUID tenant, UUID store, String gid, String price) {
        UUID p = UUID.randomUUID(), v = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Product', 'active')",
            p, tenant, store, "P-" + p);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, price) VALUES (?, ?, ?, ?, 'V', ?, ?::numeric)",
            v, tenant, p, gid, "SKU-" + v.toString().substring(0, 6), price);
        return v;
    }

    private UUID order(UUID store, String externalId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), '{}'::jsonb) RETURNING id",
            UUID.class, tenantA, store, externalId, "#" + rnd());
    }

    private UUID line(UUID order, UUID variant, int qty, String raw) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
            "VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id",
            UUID.class, tenantA, order, variant, qty, "gid://shopify/LineItem/" + rnd(), raw);
    }
}
