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
 * Q5 (2026-11-27) — an exchange whose price difference applies but whose PRICE PAID isn't known
 * (OrderLinePaidPrices NONE: no stored price, no Shopify read, no catalogue price): no difference is charged or paid
 * back (before: the paid price counted as 0 and the customer was charged the replacement's full price). Same fixtures
 * as PortalExchangePricingTest (another courier, flag on; the customer bought Sand / M).
 *
 *   u1 — free swaps off, paid price unknown: the quote has no priceDifference and says priceDifferenceUnknown; collect 0;
 *        the frozen row keeps the replacement price with a NULL difference; the merchant's drawer says so.
 *   u2 — with an exchange fee: the customer pays the fee only.
 *   u3 — a known paid price: unchanged (difference charged, no unknown key — quote and drawer).
 *   u4 — another product with an unknown paid price is still refused (approximate_price), as before.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalUnknownPaidPriceTest {

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

    record T(UUID id, String slug, UUID store, UUID location, UUID worker, UUID owner, String ownerToken,
             UUID shirtM, UUID shirtL, UUID trousersS, UUID trousersM, UUID trousersXL, UUID hat) {}
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
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-f3");
    }

    @AfterEach void clear() { TenantContext.clear(); }


    // ── u1–u4 ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void u1_unknownPaidPrice_noDifferenceCharged_quoteFlagged_frozenNull_drawerSaysSo() {
        T t = tenant(false, false, false, null);
        unpriced(t);
        O o = delivered(t, "#9101", false);
        Map<String, Object> lookup = lookup(t, o);
        Map<String, Object> q = quote(t, lookup, t.shirtL());
        assertThat(q).containsEntry("applies", true).containsEntry("priceDifferenceUnknown", true)
            .containsEntry("collect", "0.00").containsEntry("customerOwed", "0.00").containsEntry("approximate", true);
        assertThat(q.get("priceDifference")).isNull();
        assertThat(q).doesNotContainKey("blocked");

        UUID r = submitExchange(t, o, lookup, t.shirtL(), null).orElseThrow();
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT price_difference, replacement_unit_price, collect_cod, customer_owed, original_price_source " +
            "FROM return_request_amounts WHERE request_id = ?", r);
        assertThat(row.get("price_difference")).isNull();
        assertThat((BigDecimal) row.get("replacement_unit_price")).isEqualByComparingTo("1100");
        assertThat((BigDecimal) row.get("collect_cod")).isEqualByComparingTo("0");
        assertThat((BigDecimal) row.get("customer_owed")).isEqualByComparingTo("0");
        assertThat(row.get("original_price_source")).isEqualTo("none");

        Map<String, Object> amounts = (Map<String, Object>) http(t.ownerToken(), "/api/v1/return-requests/" + r, HttpMethod.GET, null)
            .getBody().get("amounts");
        assertThat(amounts).containsEntry("priceDifferenceUnknown", true).containsEntry("collect", "0.00");
        assertThat(amounts.get("priceDifference")).isNull();
    }

    @Test
    void u2_unknownPaidPrice_withFee_customerPaysTheFeeOnly() {
        T t = tenant(false, false, false, null);
        unpriced(t);
        jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount, waive_merchant_fault) " +
            "VALUES (?, 'exchange', true, 'fixed', 50, true)", t.id());
        O o = delivered(t, "#9102", false);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(quote(t, lookup, t.shirtL())).containsEntry("priceDifferenceUnknown", true)
            .containsEntry("fee", "50.00").containsEntry("collect", "50.00");
        UUID r = submitExchange(t, o, lookup, t.shirtL(), null).orElseThrow();
        assertThat((BigDecimal) jdbc.queryForObject("SELECT collect_cod FROM return_request_amounts WHERE request_id = ?",
            BigDecimal.class, r)).isEqualByComparingTo("50");
    }

    @Test
    @SuppressWarnings("unchecked")
    void u3_knownPaidPrice_unchanged_noUnknownKey() {
        T t = tenant(false, false, false, null);
        O o = delivered(t, "#9103", true);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(quote(t, lookup, t.shirtL())).containsEntry("priceDifference", "200.00").containsEntry("collect", "200.00")
            .doesNotContainKey("priceDifferenceUnknown");
        UUID r = submitExchange(t, o, lookup, t.shirtL(), null).orElseThrow();
        Map<String, Object> amounts = (Map<String, Object>) http(t.ownerToken(), "/api/v1/return-requests/" + r, HttpMethod.GET, null)
            .getBody().get("amounts");
        assertThat(amounts).containsEntry("priceDifference", "200.00").doesNotContainKey("priceDifferenceUnknown");
    }

    @Test
    void u4_unknownPaidPrice_anotherProduct_stillRefused() {
        T t = tenant(true, false, false, null);
        unpriced(t);
        O o = delivered(t, "#9104", false);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(quote(t, lookup, t.trousersS())).containsEntry("blocked", "approximate_price");
        assertThat(submitExchange(t, o, lookup, t.trousersS(), null)).isEmpty();
    }

    /** The item bought (Sand / M) has no catalogue price either — with no stored line price the paid price is NONE. */
    private void unpriced(T t) {
        jdbc.update("UPDATE variants SET price = NULL WHERE id = ?", t.shirtM());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private static PortalAmounts.Difference diff(String paid, String replacement) {
        return new PortalAmounts.Difference(new BigDecimal(paid), new BigDecimal(replacement));
    }

    private T tenant(boolean anyProduct, boolean freeSwaps, boolean lowerOnly, BigDecimal maxDifference) {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "q5x-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled, portal_generic_returns_enabled, " +
            "portal_exchanges_enabled, portal_exchange_any_product, portal_free_variant_swaps, portal_exchange_same_or_lower_only, " +
            "portal_exchange_max_difference) VALUES (?, ?, ?, true, true, true, true, ?, ?, ?, ?)",
            id, "F3 " + n, slug, anyProduct, freeSwaps, lowerOnly, maxDifference);
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

    /** One Sand / M (EGP 900 paid when {@code priced}; else no price stored), shipped with another courier, delivered. */
    private O delivered(T t, String number, boolean priced) {
        String phone = "0105558" + number.substring(1);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, 1, ?::jsonb) RETURNING id", UUID.class, t.id(), order, t.shirtM(),
            priced ? "{\"price\":\"900.00\",\"quantity\":1}" : "{\"quantity\":1}");
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
