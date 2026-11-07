package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Returns portal exchange pricing, decision C (V223, 2026-11-07): "Same-product swaps can refund the customer" — and
 * the refund-method rule (asked only when the store owes the customer). Every store: fees on, exchange fee fixed EGP 10,
 * free variant swaps OFF, another product allowed. Linen Shirt Sand / M (bought, EGP 720 paid), Sand / L 550 (cheaper,
 * same product), Sand / XL 800 (pricier, same product), Trousers S 550 (another product). The Snouts case RR-9RTKVJ:
 * paid 720, new 550, fee 10.
 *
 *   k1 — setting OFF (the default): same product, cheaper → difference 0, collect 10, owed 0; frozen so; the refund
 *        method is not asked (a method sent is refused).
 *   k2 — golden, setting ON = today: owed 160 (−170 + 10); the quote equals the pre-V223 calculator's exactly; frozen
 *        so; the refund method is asked (required) because the store owes.
 *   k3 — different product, setting OFF: the real difference both ways (owed 160).
 *   k4 — same product, pricier, setting OFF: the customer still pays the difference + fee (collect 90).
 *   k5 — the merchant's fee override keeps the floor (frozen difference 0 → collect = new fee, never owed).
 *   s1 — settings: GET carries the value (default false); PUT changes it (one audit row); left out = kept.
 *   r1 — refund method (RR-LR48WZ): an exchange the customer pays for, with "refund me instead" ticked, is NOT asked a
 *        method (a method sent is refused; none → stored without one); a refund still asks.
 *   x1 — cross-tenant: one tenant's setting never changes another's quote; app_user reads only its own tenant's value
 *        (positive control: the same tenant does).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalSameProductSwapRefundsTest {

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
    @Autowired PortalService portal;
    @Autowired ObjectMapper mapper;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  ShopifyOrderPriceGateway priceGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  BostaV2Client bostaV2;

    static final AtomicInteger SEQ = new AtomicInteger();
    static final String BASE = "/api/v1/tenant/portal-settings/customer-fees";

    record T(UUID id, String slug, UUID store, UUID location, UUID worker, UUID owner, String ownerToken,
             UUID shirtM, UUID shirtL, UUID trousersS, UUID trousersM, UUID shirtXL, UUID hat) {}
    record O(UUID id, String number, String phone) {}

    JdbcTemplate appJdbc;
    TransactionTemplate appTx;

    @BeforeAll
    void setup() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, priceGateway, tokenProvider, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-spr");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── k1 ───────────────────────────────────────────────────────────────────────

    @Test
    void k1_off_sameProductCheaper_collectsFee_owesNothing_noRefundMethod() {
        T t = tenant(false);
        O o = delivered(t, "#4101", true);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(quote(t, lookup, t.shirtL())).containsEntry("paidPrice", "720.00").containsEntry("replacementPrice", "550.00")
            .containsEntry("priceDifference", "0.00").containsEntry("fee", "10.00")
            .containsEntry("collect", "10.00").containsEntry("customerOwed", "0.00");
        assertThat(submitExchange(t, o, lookup, t.shirtL(), "instapay")).as("store owes nothing → a method sent is refused").isEmpty();
        UUID r = submitExchange(t, o, lookup, t.shirtL(), null).orElseThrow();
        Map<String, Object> a = amounts(r);
        assertThat((BigDecimal) a.get("collect_cod")).isEqualByComparingTo("10");
        assertThat((BigDecimal) a.get("customer_owed")).isEqualByComparingTo("0");
        assertThat((BigDecimal) a.get("price_difference")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT refund_method FROM return_requests WHERE id = ?", String.class, r)).isNull();
    }

    // ── k2: golden ───────────────────────────────────────────────────────────────

    @Test
    void k2_on_isTodaysBehaviour_owed160_methodRequired() {
        T t = tenant(true);
        O o = delivered(t, "#4201", true);
        Map<String, Object> lookup = lookup(t, o);
        Map<String, Object> q = quote(t, lookup, t.shirtL());
        assertThat(q).containsEntry("priceDifference", "-170.00").containsEntry("collect", "0.00").containsEntry("customerOwed", "160.00");

        // Golden: the quote equals exactly what the pre-V223 calculator (an unfloored difference) gives.
        PortalAmounts.Terms fee10 = new PortalAmounts.Terms("exchange", "fixed", new BigDecimal("10"), null, null, null, true,
            "rule", null, null);
        List<PortalAmounts.Item> items = List.of(new PortalAmounts.Item("0", t.shirtM(), null, new BigDecimal("720.00"), "stored", "wrong_size"));
        PortalAmounts.Quote before = PortalAmounts.calculate("exchange", fee10, Set.of(), items,
            new PortalAmounts.Difference(new BigDecimal("720.00"), new BigDecimal("550.00")));
        Map<String, Object> expected = new LinkedHashMap<>(Map.of("applies", true));
        expected.putAll(before.toPortalJson());
        assertThat(mapper.convertValue(q, Map.class)).isEqualTo(mapper.convertValue(expected, Map.class));
        assertThat(new PortalAmounts.ExchangePricing(true, false, null, false, true)
            .difference(true, new BigDecimal("720.00"), new BigDecimal("550"))).isEqualTo(
            new PortalAmounts.Difference(new BigDecimal("720.00"), new BigDecimal("550")));

        assertThat(submitExchange(t, o, lookup, t.shirtL(), null)).as("store owes → the method is required").isEmpty();
        UUID r = submitExchange(t, o, lookup, t.shirtL(), "instapay").orElseThrow();
        Map<String, Object> a = amounts(r);
        assertThat((BigDecimal) a.get("customer_owed")).isEqualByComparingTo("160");
        assertThat((BigDecimal) a.get("collect_cod")).isEqualByComparingTo("0");
        assertThat((BigDecimal) a.get("price_difference")).isEqualByComparingTo("-170");
        assertThat(jdbc.queryForObject("SELECT refund_method FROM return_requests WHERE id = ?", String.class, r)).isEqualTo("instapay");
    }

    // ── k3 / k4 ──────────────────────────────────────────────────────────────────

    @Test
    void k3_off_differentProduct_realDifferenceBothWays() {
        T t = tenant(false);
        O o = delivered(t, "#4301", true);
        assertThat(quote(t, lookup(t, o), t.trousersS())).containsEntry("priceDifference", "-170.00")
            .containsEntry("collect", "0.00").containsEntry("customerOwed", "160.00");
        O o2 = delivered(t, "#4302", true);
        assertThat(quote(t, lookup(t, o2), t.trousersM())).as("another product, pricier: pays it")
            .containsEntry("priceDifference", "780.00").containsEntry("collect", "790.00").containsEntry("customerOwed", "0.00");
    }

    @Test
    void k4_off_sameProductPricier_stillPaysDifferencePlusFee() {
        T t = tenant(false);
        O o = delivered(t, "#4401", true);
        assertThat(quote(t, lookup(t, o), t.shirtXL())).containsEntry("priceDifference", "80.00")
            .containsEntry("collect", "90.00").containsEntry("customerOwed", "0.00");
    }

    // ── k5: override ─────────────────────────────────────────────────────────────

    @Test
    void k5_feeOverride_keepsTheFloor() {
        T t = tenant(false);
        O o = delivered(t, "#4501", true);
        UUID r = submitExchange(t, o, lookup(t, o), t.shirtL(), null).orElseThrow();
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + r + "/amounts", HttpMethod.PUT, Map.of("fee", 25))
            .getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> a = amounts(r);
        assertThat((BigDecimal) a.get("collect_cod")).isEqualByComparingTo("25");
        assertThat((BigDecimal) a.get("customer_owed")).isEqualByComparingTo("0");
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + r + "/amounts", HttpMethod.PUT, Map.of("waived", true))
            .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat((BigDecimal) amounts(r).get("customer_owed")).as("waived: still never owed").isEqualByComparingTo("0");
    }

    // ── s1: settings ─────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void s1_settings_getPutKeepAudit() {
        T t = tenant(false);
        Map<String, Object> pricing = (Map<String, Object>) http(t.ownerToken(), BASE, HttpMethod.GET, null).getBody().get("exchangePricing");
        assertThat(pricing).containsEntry("sameProductSwapRefunds", false);
        Map<String, Object> on = new HashMap<>(Map.of("anyProduct", true, "sameOrLowerOnly", false, "freeVariantSwaps", false,
            "sameProductSwapRefunds", true));
        on.put("maxDifference", null);
        assertThat((Map<String, Object>) http(t.ownerToken(), BASE + "/exchange-pricing", HttpMethod.PUT, on).getBody().get("exchangePricing"))
            .containsEntry("sameProductSwapRefunds", true);
        assertThat(jdbc.queryForObject("SELECT portal_same_product_swap_refunds FROM tenants WHERE id = ?", Boolean.class, t.id())).isTrue();
        assertThat(audits(t)).isEqualTo(1);
        Map<String, Object> left = new HashMap<>(on);
        left.remove("sameProductSwapRefunds");
        assertThat((Map<String, Object>) http(t.ownerToken(), BASE + "/exchange-pricing", HttpMethod.PUT, left).getBody().get("exchangePricing"))
            .as("left out = kept").containsEntry("sameProductSwapRefunds", true);
        assertThat(audits(t)).as("nothing changed → no audit").isEqualTo(1);
        on.put("sameProductSwapRefunds", false);
        http(t.ownerToken(), BASE + "/exchange-pricing", HttpMethod.PUT, on);
        assertThat(jdbc.queryForObject("SELECT portal_same_product_swap_refunds FROM tenants WHERE id = ?", Boolean.class, t.id())).isFalse();
        assertThat(audits(t)).isEqualTo(2);
    }

    // ── r1: refund method only when the store owes ───────────────────────────────

    @Test
    void r1_fallbackExchangeThatCollects_notAskedForMethod_refundStillAsked() {
        T t = tenant(false);
        O o = delivered(t, "#4601", true);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(quote(t, lookup, t.shirtXL())).containsEntry("collect", "90.00");
        assertThat(submitExchange(t, lookup, exchangeRequest(t, t.shirtXL(), "instapay", true)))
            .as("collects, refund fallback ticked: a method sent is refused").isEmpty();
        UUID r = submitExchange(t, lookup, exchangeRequest(t, t.shirtXL(), null, true)).orElseThrow();
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT refund_method, refund_details_encrypted, refund_details_hint, refund_fallback_ok FROM return_requests WHERE id = ?", r);
        assertThat(row.get("refund_method")).isNull();
        assertThat(row.get("refund_details_encrypted")).isNull();
        assertThat(row.get("refund_details_hint")).isNull();
        assertThat(row).containsEntry("refund_fallback_ok", true);

        // A refund still asks (required).
        O o2 = delivered(t, "#4602", true);
        Map<String, Object> l2 = lookup(t, o2);
        PortalService.SubmitRequest refundNoMethod = new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.shirtM(), 1, "wrong_size")), null, null, null, null, null, false, null, null, null, null);
        assertThat(portal.submit(t.slug(), (String) l2.get("token"), refundNoMethod).orElseThrow().outcome())
            .isEqualTo(PortalService.SubmitOutcome.INVALID);
        PortalService.SubmitRequest refundCash = new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.shirtM(), 1, "wrong_size")), null, null, null, null, null, false, null, null, "cash", null);
        assertThat(portal.submit(t.slug(), (String) l2.get("token"), refundCash).orElseThrow().outcome())
            .isEqualTo(PortalService.SubmitOutcome.CREATED);
    }

    // ── x1: cross-tenant ─────────────────────────────────────────────────────────

    @Test
    void x1_settingIsPerTenant_appUserSeesOnlyItsOwn_positiveControl() {
        T on = tenant(true), off = tenant(false);
        O oOn = delivered(on, "#4701", true), oOff = delivered(off, "#4702", true);
        assertThat(quote(on, lookup(on, oOn), on.shirtL())).containsEntry("customerOwed", "160.00");
        assertThat(quote(off, lookup(off, oOff), off.shirtL())).as("the other tenant's ON never leaks").containsEntry("customerOwed", "0.00");

        // Owner of OFF switches it on — ON's tenant row is untouched, and vice versa.
        Map<String, Object> body = new HashMap<>(Map.of("anyProduct", true, "sameOrLowerOnly", false, "freeVariantSwaps", false,
            "sameProductSwapRefunds", false));
        body.put("maxDifference", null);
        http(off.ownerToken(), BASE + "/exchange-pricing", HttpMethod.PUT, body);
        assertThat(jdbc.queryForObject("SELECT portal_same_product_swap_refunds FROM tenants WHERE id = ?", Boolean.class, on.id())).isTrue();

        String sql = "SELECT portal_same_product_swap_refunds FROM tenants WHERE id = ?";
        List<Boolean> crossed = TenantContext.runAs(off.id(), () -> appTx.execute(s -> appJdbc.queryForList(sql, Boolean.class, on.id())));
        assertThat(crossed).as("app_user under another tenant: no row").isEmpty();
        List<Boolean> own = TenantContext.runAs(on.id(), () -> appTx.execute(s -> appJdbc.queryForList(sql, Boolean.class, on.id())));
        assertThat(own).as("positive control").containsExactly(true);
        PortalAmounts.ExchangePricing p = TenantContext.runAs(on.id(), () -> appTx.execute(s -> PortalAmounts.exchangePricing(appJdbc, on.id())));
        assertThat(p.sameProductRefunds()).isTrue();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private Map<String, Object> amounts(UUID request) {
        return jdbc.queryForMap("SELECT * FROM return_request_amounts WHERE request_id = ?", request);
    }

    private int audits(T t) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'portal_exchange_pricing_update'",
            Integer.class, t.id());
    }

    private T tenant(boolean sameProductRefunds) {
        boolean anyProduct = true, freeSwaps = false, lowerOnly = false; BigDecimal maxDifference = null;
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "spr-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled, portal_generic_returns_enabled, " +
            "portal_exchanges_enabled, portal_exchange_any_product, portal_free_variant_swaps, portal_exchange_same_or_lower_only, " +
            "portal_exchange_max_difference, portal_same_product_swap_refunds, portal_refund_methods) " +
            "VALUES (?, ?, ?, true, true, true, true, ?, ?, ?, ?, ?, ARRAY['instapay','cash']::text[])",
            id, "SPR " + n, slug, anyProduct, freeSwaps, lowerOnly, maxDifference, sameProductRefunds);
        jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount) VALUES (?, 'exchange', true, 'fixed', 10)", id);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'disconnected', 'idle', 'read_orders')", store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID owner = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Owner', ?, 'x', 'owner'::user_role, true) RETURNING id", UUID.class, id, "o-" + UUID.randomUUID() + "@t.com");
        UUID worker = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Worker', ?, 'x', 'worker'::user_role, true) RETURNING id", UUID.class, id, "w-" + UUID.randomUUID() + "@t.com");
        UUID shirt = product(id, store, slug + "-shirt", "Linen Shirt", "active");
        UUID trousers = product(id, store, slug + "-trousers", "Trousers", "active");
        UUID hat = product(id, store, slug + "-hat", "Hat", "draft");
        UUID shirtM = variant(id, shirt, slug + "-sm", "Sand / M", "720");
        UUID shirtL = variant(id, shirt, slug + "-sl", "Sand / L", "550");
        UUID tS = variant(id, trousers, slug + "-ts", "S", "550");
        UUID tM = variant(id, trousers, slug + "-tm", "M", "1500");
        UUID tXL = variant(id, shirt, slug + "-txl", "Sand / XL", "800");
        UUID hatV = variant(id, hat, slug + "-h", "One size", "300");
        T t = new T(id, slug, store, location, worker, owner, jwt.issueAccessToken(owner, id, "owner"), shirtM, shirtL, tS, tM, tXL, hatV);
        for (UUID v : List.of(shirtL, shirtL, tS, tS, tS, tM, tM, tXL, tXL, hatV)) piece(t, v, "available", null);
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

    /** One Sand / M (EGP 900 paid when {@code priced}; else no price stored), shipped with another courier, delivered. */
    private O delivered(T t, String number, boolean priced) {
        String phone = "0105558" + number.substring(1);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::order_status, 'Youssef Adel', ?, 720, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, 1, ?::jsonb) RETURNING id", UUID.class, t.id(), order, t.shirtM(),
            priced ? "{\"price\":\"720.00\",\"quantity\":1}" : "{\"quantity\":1}");
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
        return new O(order, number, phone);
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
        return exchangeRequest(t, replacement, refundMethod, false);
    }

    private PortalService.SubmitRequest exchangeRequest(T t, UUID replacement, String refundMethod, boolean fallback) {
        return new PortalService.SubmitRequest(List.of(new PortalService.SubmitLine(t.shirtM(), 1, "wrong_size")), null, null, null,
            "exchange", replacement, fallback, null, null, refundMethod,
            refundMethod == null || "cash".equals(refundMethod) ? null : instapay());
    }

    private com.fasterxml.jackson.databind.JsonNode instapay() {
        return mapper.createObjectNode().put("instapay", "ahmed.k@instapay");
    }

    /** The request id, or empty when the submission was refused (400). */
    private Optional<UUID> submitExchange(T t, O o, Map<String, Object> lookup, UUID replacement, String refundMethod) {
        return submitExchange(t, lookup, exchangeRequest(t, replacement, refundMethod));
    }

    private Optional<UUID> submitExchange(T t, Map<String, Object> lookup, PortalService.SubmitRequest req) {
        PortalService.SubmitResult r = portal.submit(t.slug(), (String) lookup.get("token"), req).orElseThrow();
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
