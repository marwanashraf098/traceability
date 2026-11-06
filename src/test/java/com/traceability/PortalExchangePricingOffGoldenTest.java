package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
import com.traceability.portal.ReturnPickupBookingService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Returns portal fees F3 — GOLDEN, flag OFF ({@code traced.portal-fees.settings-enabled} unset = false, as in prod). Two
 * stores, identical except that one has every exchange-pricing setting on (a different product allowed, free variant
 * swaps OFF, "same price or lower only", a max difference). Every answer is byte-identical between them (after blanking
 * each store's own ids, numbers and times): lookup (no pricing key, no option prices), the catalogue and quote endpoints
 * (404 for both), a same-product exchange to a pricier size (accepted, nothing frozen), an exchange to another product
 * (refused for both, as today), the merchant's request detail and the booking description. Shopify's price read is
 * never called.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalExchangePricingOffGoldenTest {

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
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PortalService portal;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  ShopifyOrderPriceGateway priceGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  BostaV2Client bostaV2;

    static final AtomicInteger SEQ = new AtomicInteger();

    record T(UUID id, String slug, UUID store, UUID location, UUID worker, String ownerToken, UUID shirtM, UUID shirtL, UUID trousersS) {}
    record O(UUID id, String number, String phone, List<String> idents) {}

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, priceGateway, tokenProvider, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-f3-off");
    }

    @Test
    void g1_flagOff_exchangePricingInert_everyAnswerIdentical() {
        T plain = tenant(false), priced = tenant(true);

        O po = delivered(plain, "#6101"), fo = delivered(priced, "#6101");
        Map<String, Object> pl = lookup(plain, po), fl = lookup(priced, fo);
        assertThat(fl).doesNotContainKey("exchangePricing");
        assertThat(norm(fl, priced, fo)).as("lookup").isEqualTo(norm(pl, plain, po));

        assertThat(http(null, "/api/v1/portal/" + priced.slug() + "/catalogue?variantId=" + priced.shirtM(), HttpMethod.GET, null,
            (String) fl.get("token")).getStatusCode().value()).as("no catalogue while off").isEqualTo(404);
        assertThat(portal.quote(priced.slug(), (String) fl.get("token"), exchange(priced, priced.shirtL()))).as("no quote while off").isEmpty();

        // Another product: refused for both, exactly as before F3.
        assertThat(portal.submit(priced.slug(), (String) fl.get("token"), exchange(priced, priced.trousersS())).orElseThrow().outcome())
            .isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(portal.submit(plain.slug(), (String) pl.get("token"), exchange(plain, plain.trousersS())).orElseThrow().outcome())
            .isEqualTo(PortalService.SubmitOutcome.INVALID);

        // A pricier size of the same product: accepted for both, nothing frozen.
        PortalService.SubmitResult ps = portal.submit(plain.slug(), (String) pl.get("token"), exchange(plain, plain.shirtL())).orElseThrow();
        PortalService.SubmitResult fs = portal.submit(priced.slug(), (String) fl.get("token"), exchange(priced, priced.shirtL())).orElseThrow();
        assertThat(norm(fs.body(), priced, fo)).as("submit").isEqualTo(norm(ps.body(), plain, po));
        UUID preq = request(plain, ps.body()), freq = request(priced, fs.body());
        Map<String, Object> pd = http(plain.ownerToken(), "/api/v1/return-requests/" + preq, HttpMethod.GET, null, null).getBody();
        Map<String, Object> fd = http(priced.ownerToken(), "/api/v1/return-requests/" + freq, HttpMethod.GET, null, null).getBody();
        assertThat(fd).doesNotContainKey("amounts");
        assertThat(norm(fd, priced, fo)).as("request detail").isEqualTo(norm(pd, plain, po));
        String pref = (String) ps.body().get("reference"), fref = (String) fs.body().get("reference");
        assertThat(ReturnPickupBookingService.exchangeItem(jdbc, freq, priced.id()).outbound(fref).replace(fref, "<ref>"))
            .isEqualTo(ReturnPickupBookingService.exchangeItem(jdbc, preq, plain.id()).outbound(pref).replace(pref, "<ref>"))
            .isEqualTo("<ref>: Linen Shirt / Sand / L");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts", Integer.class)).isZero();
        verifyNoInteractions(priceGateway);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private PortalService.SubmitRequest exchange(T t, UUID replacement) {
        return new PortalService.SubmitRequest(List.of(new PortalService.SubmitLine(t.shirtM(), 1, "wrong_size")), null, null, null,
            "exchange", replacement, false);
    }

    private T tenant(boolean pricingSettings) {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "f3off-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled, portal_generic_returns_enabled, " +
            "portal_exchanges_enabled, portal_exchange_any_product, portal_free_variant_swaps, portal_exchange_same_or_lower_only, " +
            "portal_exchange_max_difference) VALUES (?, 'F3 off', ?, true, true, true, true, ?, ?, ?, ?)",
            id, slug, pricingSettings, !pricingSettings, pricingSettings, pricingSettings ? new BigDecimal("50") : null);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'disconnected', 'idle', 'read_orders')", store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID owner = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Owner', ?, 'x', 'owner'::user_role, true) RETURNING id", UUID.class, id, "o-" + UUID.randomUUID() + "@t.com");
        UUID worker = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Worker', ?, 'x', 'worker'::user_role, true) RETURNING id", UUID.class, id, "w-" + UUID.randomUUID() + "@t.com");
        UUID shirt = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug + "-s");
        UUID trousers = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Trousers', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug + "-t");
        UUID m = variant(id, shirt, slug + "-m", "Sand / M", "900");
        UUID l = variant(id, shirt, slug + "-l", "Sand / L", "1100");
        UUID ts = variant(id, trousers, slug + "-ts", "S", "700");
        T t = new T(id, slug, store, location, worker, jwt.issueAccessToken(owner, id, "owner"), m, l, ts);
        piece(t, l, "available", null);
        piece(t, ts, "available", null);
        return t;
    }

    private UUID variant(UUID tenant, UUID product, String ext, String title, String price) {
        return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenant, product, "gid://shopify/ProductVariant/" + ext, ext, title, new BigDecimal(price));
    }

    private String[] piece(T t, UUID variant, String status, UUID order) {
        String id = UlidGenerator.generate();
        String code = "Y" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), variant, "PC-" + id, code, status, order, order == null ? t.location() : null, t.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
        return new String[]{id, code};
    }

    private O delivered(T t, String number) {
        String phone = "0105559" + number.substring(1);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, 1, '{\"price\":\"900.00\",\"quantity\":1}'::jsonb) RETURNING id", UUID.class, t.id(), order, t.shirtM());
        String[] piece = piece(t, t.shirtM(), "with_courier", order);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.id(), item, piece[0]);
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
        return new O(order, number, phone, List.of(piece[0], piece[1], tracking, tracking.replace("-", "")));
    }

    private Map<String, Object> lookup(T t, O o) {
        PortalService.LookupResult r = portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return r.body();
    }

    private UUID request(T t, Map<String, Object> body) {
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE tenant_id = ? AND reference = ?", UUID.class, t.id(), body.get("reference"));
    }

    /** The response with each store's own identity (ids, slug, numbers, tokens, references, pieces, times) blanked. */
    private Object norm(Object v, T t, O o) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), "token".equals(k) ? "<token>" : norm(val, t, o)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(x -> norm(x, t, o)).toList();
        if (v instanceof UUID) return "<uuid>";
        if (v instanceof String s) {
            if (s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return "<uuid>";
            if (s.matches("RR-[A-Z0-9]+")) return "<ref>";
            if (s.matches("\\d{4}-\\d{2}-\\d{2}T.*")) return "<time>";
            String out = s.replace(t.slug(), "<slug>").replace(t.id().toString(), "<tenant>");
            out = out.replace(o.number(), "<number>").replace(o.number().substring(1), "<number>").replace(o.phone(), "<phone>");
            for (String p : o.idents()) out = out.replace(p, "<piece>");
            return out;
        }
        return v;
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
