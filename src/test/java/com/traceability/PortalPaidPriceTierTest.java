package com.traceability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway.LinePrice;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway.OrderPrices;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Returns portal fees prod fix (2026-11-06, The Snouts): an order whose line was imported through GraphQL before F0
 * has no stored paid price, so "exchange for another product" was blocked as approximate.
 *
 *   l1 — the live Shopify read (tier b) prices the line by its own LineItem GID (the variant may be gone in Shopify):
 *        the catalogue offers other products with the right difference, the quote agrees, the price is written back
 *        to the line's paid_unit_price key and the next call makes no Shopify read. (Revert-checked: tier b off →
 *        blocked approximate_price.)
 *   v1 — V218: the ORDER's REST line (orders.raw.line_items, same math as net_unit_price) is a stored tier — no live
 *        call; precedence (the line's REST net, then its paid key, then the order's line) and a line the order raw
 *        doesn't name stays NULL; V149's analytics columns are untouched.
 *   w1 — a failed live read is logged at WARN with its cause (Shopify's error code) and never the token; the line
 *        stays approximate and nothing is written.
 *   b1 — the one-off backfill: dry run counts and makes no Shopify call; apply prices and stores, rate-limited,
 *        resumable (max-orders, re-run continues); a tenant with fees off is skipped; an undelivered order is not
 *        eligible.
 *   x1 — app_user: another tenant neither reads nor writes the line (and the backfill never sees it); the same
 *        tenant does (positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalPaidPriceTierTest {

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
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("traced.portal-fees.settings-enabled", () -> "true");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired PortalService portal;
    @Autowired OrderLinePaidPrices prices;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  ShopifyOrderPriceGateway priceGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  BostaV2Client bostaV2;

    static final AtomicInteger SEQ = new AtomicInteger();
    static final String TOKEN = "shpat-paidprice-secret";

    record T(UUID id, String slug, UUID store, UUID location, UUID worker, UUID owner, String ownerToken,
             UUID shirtM, UUID shirtL, UUID trousersS, UUID trousersM, UUID trousersXL, UUID hat) {}
    record O(UUID id, String number, String phone, UUID item) {}

    DataSource appDs;
    OrderLinePaidPrices appPrices;

    @BeforeAll
    void setup() {
        appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appPrices = new OrderLinePaidPrices(new JdbcTemplate(appDs), new DataSourceTransactionManager(appDs),
            priceGateway, tokenProvider);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, priceGateway, tokenProvider, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn(TOKEN);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    /** Shopify's answer for one order: the line by its own GID, its variant deleted in Shopify (variant null). */
    private void shopifyPrices(T t, O o, String price) {
        when(priceGateway.fetchOrderPrices(eqStr(t.slug() + ".myshopify.com"), eqStr(TOKEN),
                eqStr("gid://shopify/Order/" + t.id() + o.number())))
            .thenReturn(new OrderPrices("EGP", List.of(new LinePrice(null, 1, new BigDecimal(price), lineGid(o.number())))));
    }

    private static String eqStr(String s) { return org.mockito.ArgumentMatchers.eq(s); }

    // ── l1: the live read in the portal ──────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_importedLineNoStoredPrice_liveReadOffersOtherProducts_andIsStored() {
        T t = tenant();
        O o = delivered(t, "#4101", "{}");
        shopifyPrices(t, o, "850.00");
        Map<String, Object> lookup = lookup(t, o);

        Map<String, Object> cat = catalogue(t, lookup, t.shirtM());
        assertThat(cat).as("tier b priced the line — not blocked").doesNotContainKey("blocked").containsEntry("paidPrice", "850.00");
        List<Map<String, Object>> products = (List<Map<String, Object>>) cat.get("products");
        assertThat(products).extracting(p -> p.get("title")).containsExactly("Trousers");
        assertThat((List<Map<String, Object>>) products.get(0).get("variants"))
            .extracting(v -> v.get("title") + "=" + v.get("price") + "/" + v.get("priceDifference"))
            .containsExactly("M=1500.00/650.00", "S=700.00/-150.00");

        assertThat(jdbc.queryForObject("SELECT raw ->> 'paid_unit_price' FROM order_items WHERE id = ?", String.class, o.item()))
            .as("the live price is written back to the paid key").isEqualTo("850.00");
        assertThat(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE id = ?", BigDecimal.class, o.item()))
            .as("never raw->>'price' — V149's analytics price stays NULL").isNull();

        Map<String, Object> quote = quote(t, lookup, t.trousersM());
        assertThat(quote).containsEntry("priceDifference", "650.00").containsEntry("collect", "650.00").doesNotContainKey("blocked");
        assertThat(catalogue(t, lookup, t.shirtM())).containsEntry("paidPrice", "850.00");
        verify(priceGateway, times(1)).fetchOrderPrices(anyString(), anyString(), anyString());
        assertThat(prices.resolve(t.id(), o.id())).singleElement()
            .satisfies(lp -> assertThat(lp.source()).isEqualTo(OrderLinePaidPrices.Source.STORED));
    }

    // ── v1: V218, the order's REST line ──────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void v1_orderRawRestLine_isAStoredTier_noLiveCall() {
        T t = tenant();
        String orderRaw = "{\"line_items\":[{\"id\":774102,\"admin_graphql_api_id\":\"" + lineGid("#4102") + "\"," +
            "\"price\":\"1000.00\",\"quantity\":2,\"discount_allocations\":[{\"amount\":\"300.00\"}]}," +
            "{\"admin_graphql_api_id\":\"gid://shopify/LineItem/1\",\"price\":\"5.00\",\"quantity\":1}]}";
        O o = delivered(t, "#4102", orderRaw);
        Map<String, Object> lookup = lookup(t, o);

        assertThat(catalogue(t, lookup, t.shirtM())).as("1000 − 300 / 2").containsEntry("paidPrice", "850.00");
        verifyNoInteractions(priceGateway, tokenProvider);
        assertThat(jdbc.queryForMap("SELECT unit_price, net_unit_price, jsonb_exists(raw, 'paid_unit_price') AS key " +
            "FROM order_items WHERE id = ?", o.item()))
            .as("analytics columns untouched, nothing written").containsEntry("unit_price", null)
            .containsEntry("net_unit_price", null).containsEntry("key", false);

        // Precedence: the line's own REST net, then its paid key, then the order's line.
        jdbc.update("UPDATE order_items SET raw = raw || '{\"paid_unit_price\":\"777.00\"}'::jsonb WHERE id = ?", o.item());
        assertThat(paidSql(o.item())).isEqualByComparingTo("777.00");
        jdbc.update("UPDATE order_items SET raw = '{\"price\":\"600.00\",\"quantity\":1,\"paid_unit_price\":\"777.00\"}'::jsonb WHERE id = ?", o.item());
        assertThat(paidSql(o.item())).isEqualByComparingTo("600.00");
        // A line the order raw doesn't name (another LineItem GID) stays NULL; a negative net floors at 0.
        jdbc.update("UPDATE order_items SET raw = '{}'::jsonb, external_id = 'gid://shopify/LineItem/999' WHERE id = ?", o.item());
        assertThat(paidSql(o.item())).isNull();
        jdbc.update("UPDATE orders SET raw = jsonb_build_object('line_items', jsonb_build_array(jsonb_build_object(" +
            "'admin_graphql_api_id', 'gid://shopify/LineItem/999', 'price', '50.00', 'quantity', 1, " +
            "'discount_allocations', jsonb_build_array(jsonb_build_object('amount', '80.00'))))) WHERE id = ?", o.id());
        assertThat(paidSql(o.item())).isEqualByComparingTo("0");
        jdbc.update("UPDATE orders SET raw = '{\"line_items\": \"not an array\"}'::jsonb WHERE id = ?", o.id());
        assertThat(paidSql(o.item())).as("a malformed order raw is NULL, never an error").isNull();
    }

    // ── w1: a failed live read says why ──────────────────────────────────────────

    @Test
    void w1_failedLiveRead_warnsWithCause_neverTheToken() {
        T t = tenant();
        O o = delivered(t, "#4103", "{}");
        when(priceGateway.fetchOrderPrices(any(), any(), any()))
            .thenThrow(new ShopifyOrderPriceGateway.OrderPriceReadException("ACCESS_DENIED"));
        Logger logger = (Logger) LoggerFactory.getLogger(OrderLinePaidPrices.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(catalogue(t, lookup(t, o), t.shirtM())).isEqualTo(Map.of("blocked", "approximate_price"));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anySatisfy(m -> assertThat(m).contains("ACCESS_DENIED").contains(t.id().toString()))
            .allSatisfy(m -> assertThat(m).doesNotContain(TOKEN).doesNotContain(o.phone()));
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(raw, 'paid_unit_price') FROM order_items WHERE id = ?", Boolean.class, o.item()))
            .isFalse();
    }

    // ── b1: the backfill ─────────────────────────────────────────────────────────

    @Test
    void b1_backfill_dryRunCounts_applyStores_resumable_feesOffSkipped() {
        T t = tenant();
        O a = delivered(t, "#4201", "{}");
        O b = delivered(t, "#4202", "{}");
        O restPriced = delivered(t, "#4203", "{\"line_items\":[{\"admin_graphql_api_id\":\"" + lineGid("#4203") +
            "\",\"price\":\"900.00\",\"quantity\":1}]}");
        UUID undelivered = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#4204', 'with_courier'::order_status, 'cod', now()) RETURNING id", UUID.class, t.id(), t.store(),
            "gid://shopify/Order/" + t.id() + "#4204");
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) VALUES (?, ?, ?, 1, ?, '{}')",
            t.id(), undelivered, t.shirtM(), lineGid("#4204"));
        shopifyPrices(t, a, "810.00");
        shopifyPrices(t, b, "820.00");
        List<Long> sleeps = new ArrayList<>();

        PaidPriceBackfill dry = backfill(PortalFeesRollout.of(true), 10, sleeps);
        assertThat(dry.run(t.id().toString(), false)).singleElement().satisfies(r -> {
            assertThat(r.orders()).as("a and b — restPriced is V218-priced, #4204 undelivered").isEqualTo(2);
            assertThat(r.unpricedLines()).isEqualTo(2);
            assertThat(r.ordersRead()).isZero();
        });
        verifyNoInteractions(priceGateway, tokenProvider);

        PaidPriceBackfill one = backfill(PortalFeesRollout.of(true), 1, sleeps);
        assertThat(one.run(t.id().toString(), true)).singleElement().satisfies(r -> {
            assertThat(r.ordersRead()).isEqualTo(1);
            assertThat(r.linesPriced()).isEqualTo(1);
        });
        PaidPriceBackfill rest2 = backfill(PortalFeesRollout.of(true), 10, sleeps);
        assertThat(rest2.run(t.id().toString(), true)).singleElement().satisfies(r -> {
            assertThat(r.orders()).as("resumed: only what is left").isEqualTo(1);
            assertThat(r.linesPriced()).isEqualTo(1);
        });
        assertThat(paidSql(a.item())).isEqualByComparingTo("810.00");
        assertThat(paidSql(b.item())).isEqualByComparingTo("820.00");
        assertThat(paidSql(restPriced.item())).isEqualByComparingTo("900.00");
        verify(priceGateway, times(2)).fetchOrderPrices(anyString(), anyString(), anyString());
        assertThat(rest2.run(t.id().toString(), true)).singleElement()
            .satisfies(r -> assertThat(r.orders()).as("nothing left").isZero());

        T off = tenant();
        delivered(off, "#4205", "{}");
        assertThat(backfill(new PortalFeesRollout(false, t.id().toString()), 10, sleeps).run(off.id() + "," + t.id(), false))
            .as("a tenant with fees off is skipped").extracting(PaidPriceBackfill.TenantReport::tenantId).containsExactly(t.id());
        assertThat(backfill(new PortalFeesRollout(false, off.id().toString()), 10, sleeps).run("fees", false))
            .extracting(PaidPriceBackfill.TenantReport::tenantId).containsExactly(off.id());
    }

    @Test
    void b2_backfill_rateLimited_betweenShopifyReads() {
        T t = tenant();
        for (String n : List.of("#4301", "#4302", "#4303")) {
            O o = delivered(t, n, "{}");
            shopifyPrices(t, o, "800.00");
        }
        List<Long> sleeps = new ArrayList<>();
        backfill(PortalFeesRollout.of(true), 10, sleeps).run(t.id().toString(), true);
        assertThat(sleeps).as("a pause between reads, none before the first").containsExactly(1500L, 1500L);
    }

    // ── x1: app_user, cross-tenant ───────────────────────────────────────────────

    @Test
    void x1_appUser_otherTenantNeitherReadsNorWrites_sameTenantDoes() {
        T a = tenant();
        T b = tenant();
        O o = delivered(a, "#4401", "{}");
        shopifyPrices(a, o, "830.00");

        assertThat(appPrices.resolve(b.id(), o.id())).as("tenant B: nothing").isEmpty();
        verifyNoInteractions(priceGateway);
        PaidPriceBackfill bAsAppUser = new PaidPriceBackfill(new JdbcTemplate(appDs), new DataSourceTransactionManager(appDs),
            appPrices, PortalFeesRollout.of(true), "", false, 0, 10);
        assertThat(bAsAppUser.run(b.id().toString(), true)).singleElement()
            .satisfies(r -> assertThat(r.orders()).as("B's backfill never sees A's order").isZero());
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(raw, 'paid_unit_price') FROM order_items WHERE id = ?", Boolean.class, o.item()))
            .isFalse();

        assertThat(appPrices.resolve(a.id(), o.id())).as("tenant A (positive control)").singleElement()
            .satisfies(lp -> assertThat(lp.source()).isEqualTo(OrderLinePaidPrices.Source.SHOPIFY));
        assertThat(paidSql(o.item())).as("written back under app_user + RLS").isEqualByComparingTo("830.00");
        assertThat(bAsAppUser.run(a.id().toString(), false)).singleElement()
            .satisfies(r -> assertThat(r.orders()).isZero());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private PaidPriceBackfill backfill(PortalFeesRollout rollout, int maxOrders, List<Long> sleeps) {
        PaidPriceBackfill bf = new PaidPriceBackfill(jdbc, txm, prices, rollout, "", false, 1500, maxOrders);
        bf.setSleeperForTest(sleeps::add);
        return bf;
    }

    private BigDecimal paidSql(UUID item) {
        return jdbc.queryForObject("SELECT order_line_paid_unit_price(oi) FROM order_items oi WHERE oi.id = ?", BigDecimal.class, item);
    }

    private T tenant() {
        return tenant(true, true, false, null);
    }

    private T tenant(boolean anyProduct, boolean freeSwaps, boolean lowerOnly, BigDecimal maxDifference) {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "f3x-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled, portal_generic_returns_enabled, " +
            "portal_exchanges_enabled, portal_exchange_any_product, portal_free_variant_swaps, portal_exchange_same_or_lower_only, " +
            "portal_exchange_max_difference) VALUES (?, ?, ?, true, true, true, true, ?, ?, ?, ?)",
            id, "F3 " + n, slug, anyProduct, freeSwaps, lowerOnly, maxDifference);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'idle', 'read_orders')", store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID owner = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Owner', ?, 'x', 'owner'::user_role, true) RETURNING id", UUID.class, id, "o-" + UUID.randomUUID() + "@t.com");
        UUID worker = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Worker', ?, 'x', 'worker'::user_role, true) RETURNING id", UUID.class, id, "w-" + UUID.randomUUID() + "@t.com");
        UUID shirt = product(id, store, slug + "-shirt", "Linen Shirt", "active");
        UUID trousers = product(id, store, slug + "-trousers", "Trousers", "active");
        UUID hat = product(id, store, slug + "-hat", "Hat", "draft");
        UUID shirtM = variant(id, shirt, slug + "-sm", "Sand / M", "900");
        UUID shirtL = variant(id, shirt, slug + "-sl", "Sand / L", "1100");
        UUID tS = variant(id, trousers, slug + "-ts", "S", "700");
        UUID tM = variant(id, trousers, slug + "-tm", "M", "1500");
        UUID tXL = variant(id, trousers, slug + "-txl", "XL", "1200");
        UUID hatV = variant(id, hat, slug + "-h", "One size", "300");
        T t = new T(id, slug, store, location, worker, owner, jwt.issueAccessToken(owner, id, "owner"), shirtM, shirtL, tS, tM, tXL, hatV);
        for (UUID v : List.of(shirtL, shirtL, tS, tS, tS, tM, tM, hatV)) piece(t, v, "available", null);
        return t;
    }

    private UUID product(UUID tenant, UUID store, String ext, String title, String status) {
        return jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Product/" + ext, title, status);
    }

    private UUID variant(UUID tenant, UUID product, String ext, String title, String price) {
        return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenant, product, "gid://shopify/ProductVariant/" + ext, ext, title, new BigDecimal(price));
    }

    private String piece(T t, UUID variant, String status, UUID order) {
        String id = UlidGenerator.generate();
        String code = "X" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), variant, "PC-" + id, code, status, order, order == null ? t.location() : null, t.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
        return id;
    }

    /**
     * One Sand / M bought, shipped with another courier, delivered. The line is GraphQL-imported (pre-F0 shape: gid,
     * quantity, variantGid — no price) with its LineItem GID; {@code orderRaw} is the order's stored raw.
     */
    private O delivered(T t, String number, String orderRaw) {
        String phone = "0105559" + number.substring(1);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address, raw) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb, ?::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone, orderRaw);
        String lineGid = lineGid(number);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id, raw) " +
            "VALUES (?, ?, ?, 1, ?, ?::jsonb) RETURNING id", UUID.class, t.id(), order, t.shirtM(), lineGid,
            "{\"gid\":\"" + lineGid + "\",\"quantity\":1,\"variantGid\":\"gid://shopify/ProductVariant/" + t.slug() + "-sm\"}");
        String piece = piece(t, t.shirtM(), "with_courier", order);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.id(), item, piece);
        String tracking = "AR-" + SEQ.incrementAndGet() + number.substring(1);
        UUID leg = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, internal_state, " +
            "    shipment_leg, source, handed_over_at, created_at) " +
            "VALUES (?, ?, 'generic', ?, ?, 'Aramex', 'with_courier', 'forward', 'shopify_fulfillment', now() - interval '6 days', " +
            "    now() - interval '7 days') RETURNING id",
            UUID.class, t.id(), order, tracking, tracking.replace("-", ""));
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at, source) " +
            "VALUES (?, ?, 'with_courier', now() - interval '6 days', 'pickup_scan')", t.id(), leg);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t.ownerToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("http://localhost:" + port + "/api/v1/generic-shipments/mark-delivered", HttpMethod.POST,
            new HttpEntity<>(Map.of("orderIds", List.of(order)), h), Object.class).getStatusCode().is2xxSuccessful()).isTrue();
        return new O(order, number, phone, item);
    }

    private static String lineGid(String number) {
        return "gid://shopify/LineItem/77" + number.substring(1);
    }

    private Map<String, Object> lookup(T t, O o) {
        PortalService.LookupResult r = portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return r.body();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> catalogue(T t, Map<String, Object> lookup, UUID lineVariant) {
        ResponseEntity<Map<String, Object>> r = http(null, "/api/v1/portal/" + t.slug() + "/catalogue?variantId=" + lineVariant,
            HttpMethod.GET, null, (String) lookup.get("token"));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return r.getBody();
    }

    private Map<String, Object> quote(T t, Map<String, Object> lookup, UUID replacement) {
        PortalService.QuoteResult r = portal.quote(t.slug(), (String) lookup.get("token"), exchangeRequest(t, replacement, null)).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.QuoteOutcome.OK);
        return r.body();
    }

    private PortalService.SubmitRequest exchangeRequest(T t, UUID replacement, String refundMethod) {
        return new PortalService.SubmitRequest(List.of(new PortalService.SubmitLine(t.shirtM(), 1, "wrong_size")), null, null, null,
            "exchange", replacement, false, null, null, refundMethod, null);
    }

    /** The request id, or empty when the submission was refused (400). */
    private Optional<UUID> submitExchange(T t, O o, Map<String, Object> lookup, UUID replacement, String refundMethod) {
        PortalService.SubmitResult r = portal.submit(t.slug(), (String) lookup.get("token"), exchangeRequest(t, replacement, refundMethod))
            .orElseThrow();
        if (r.outcome() == PortalService.SubmitOutcome.INVALID) return Optional.empty();
        assertThat(r.outcome()).as(String.valueOf(r.body())).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return Optional.of(requestId(t, r.body()));
    }

    private UUID requestId(T t, Map<String, Object> body) {
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE tenant_id = ? AND reference = ?", UUID.class, t.id(), body.get("reference"));
    }

    private ResponseEntity<Map<String, Object>> http(String token, String path, HttpMethod method, Object body) {
        return http(token, path, method, body, null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResponseEntity<Map<String, Object>> http(String token, String path, HttpMethod method, Object body, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(bearer != null ? bearer : token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity r = rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, h), Map.class);
        return (ResponseEntity<Map<String, Object>>) r;
    }
}
