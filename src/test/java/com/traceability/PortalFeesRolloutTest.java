package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
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

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Returns portal fees — per-store rollout ({@code TRACED_PORTAL_FEES_TENANTS}). {@code traced.portal-fees.settings-enabled}
 * unset (false, as in prod); {@code traced.portal-fees.tenants} = one LISTED tenant (plus spaces, an empty entry and a
 * tenant id that doesn't exist).
 *
 *   u1 — an UNLISTED store with fee rules, any-product and paid swaps saved is byte-identical to a plain store: config,
 *        lookup, quote / catalogue 404, refund and exchange submit, request detail, refund suggestion; settings
 *        visible:false, writes 404; no amounts row.
 *   l1 — the LISTED store: settings visible and writable, lookup offers fees, quote = frozen, refund deduction from
 *        the frozen amounts (F2), the price difference on a paid swap and the catalogue (F3).
 *   x1 — cross-tenant: the listed store's portal token never opens the unlisted store's fees and vice versa; each
 *        owner sees its own store's answer (same-tenant controls).
 * Empty list = today: PortalFeesOffGoldenTest / PortalExchangePricingOffGoldenTest / CustomerFeeSettingsHiddenTest
 * run with the list empty, unchanged; PortalFeesRolloutUnitTest covers the parsing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalFeesRolloutTest {

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
        r.add("traced.portal-fees.tenants", () -> " " + LISTED + " ,, " + LISTED_X + "," + UUID.randomUUID() + " ");
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
    static final UUID LISTED = UUID.randomUUID(), LISTED_X = UUID.randomUUID();
    static final String FEES = "/api/v1/tenant/portal-settings/customer-fees";

    record T(UUID id, String slug, UUID store, UUID location, UUID m, UUID l, UUID worker, String ownerToken) {}
    record O(UUID id, String number, String phone, List<String> pieces) {}

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, priceGateway, tokenProvider, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-rollout");
    }

    @Test
    void u1_unlistedStore_feeSettingsSaved_everyAnswerIdenticalToAPlainStore() {
        T plain = tenant(UUID.randomUUID()), fees = tenant(UUID.randomUUID());
        configureFees(fees);

        assertThat(http(fees.ownerToken(), FEES, HttpMethod.GET, null, null).getBody()).containsEntry("visible", false);
        assertThat(http(fees.ownerToken(), FEES + "/rules/refund", HttpMethod.PUT, rule(75), null).getStatusCode().value())
            .as("writes 404 for an unlisted store").isEqualTo(404);
        assertThat(norm(portal.config(fees.slug()).orElseThrow(), fees, null))
            .isEqualTo(norm(portal.config(plain.slug()).orElseThrow(), plain, null));

        for (boolean exchange : new boolean[]{false, true}) {
            String n = exchange ? "#7402" : "#7401";
            O po = deliveredGeneric(plain, n), fo = deliveredGeneric(fees, n);
            Map<String, Object> pl = lookup(plain, po), fl = lookup(fees, fo);
            assertThat(fl).doesNotContainKey("fees").doesNotContainKey("exchangePricing");
            assertThat(norm(fl, fees, fo)).as("lookup").isEqualTo(norm(pl, plain, po));
            assertThat(quoteStatus(fees, fl)).as("no quote").isEqualTo(404);
            assertThat(catalogueStatus(fees, fl)).as("no catalogue").isEqualTo(404);

            Map<String, Object> ps = submit(plain, po, pl, exchange), fs = submit(fees, fo, fl, exchange);
            assertThat(norm(fs, fees, fo)).as("submit").isEqualTo(norm(ps, plain, po));
            UUID preq = request(plain, ps), freq = request(fees, fs);
            Map<String, Object> pd = http(plain.ownerToken(), "/api/v1/return-requests/" + preq, HttpMethod.GET, null, null).getBody();
            Map<String, Object> fd = http(fees.ownerToken(), "/api/v1/return-requests/" + freq, HttpMethod.GET, null, null).getBody();
            assertThat(fd).doesNotContainKey("amounts");
            assertThat(norm(fd, fees, fo)).as("request detail").isEqualTo(norm(pd, plain, po));
            if (!exchange) {
                assertThat(norm(suggestion(fees, freq), fees, fo)).as("refund suggestion")
                    .isEqualTo(norm(suggestion(plain, preq), plain, po));
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE tenant_id IN (?, ?)", Integer.class,
            plain.id(), fees.id())).isZero();
    }

    @Test
    void l1_listedStore_settingsQuoteFreezeRefundDeductionAndF3Pricing_on() {
        T t = tenant(LISTED);
        assertThat(http(t.ownerToken(), FEES, HttpMethod.GET, null, null).getBody()).containsEntry("visible", true);
        assertThat(http(t.ownerToken(), FEES + "/rules/refund", HttpMethod.PUT, rule(75), null).getStatusCode().value())
            .as("settings writable").isEqualTo(200);
        jdbc.update("UPDATE tenants SET portal_free_variant_swaps = false, portal_exchange_any_product = true WHERE id = ?", t.id());

        // F2 refund: lookup offers the fee, the quote is what is frozen, the refund suggestion deducts it.
        O o = deliveredGeneric(t, "#7501");
        Map<String, Object> lookup = lookup(t, o);
        assertThat(lookup.get("fees")).isEqualTo(Map.of("refund", true, "exchange", false));
        ResponseEntity<Map<String, Object>> quote = http(null, "/api/v1/portal/" + t.slug() + "/quote", HttpMethod.POST,
            quoteBody(t), (String) lookup.get("token"));
        assertThat(quote.getStatusCode().value()).isEqualTo(200);
        assertThat(quote.getBody()).containsEntry("applies", true).containsEntry("fee", "75.00").containsEntry("refundDue", "825.00");
        UUID refund = request(t, submit(t, o, lookup, false));
        assertThat(jdbc.queryForObject("SELECT fee_amount FROM return_request_amounts WHERE request_id = ?", java.math.BigDecimal.class, refund))
            .isEqualByComparingTo("75");
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + refund, HttpMethod.GET, null, null).getBody()).containsKey("amounts");
        assertThat(suggestion(t, refund)).containsEntry("source", "frozen").containsEntry("fee", "75.00").containsEntry("amount", "825.00");

        // F3: paid same-product swap → the difference is frozen as the collect; the catalogue answers.
        O e = deliveredGeneric(t, "#7502");
        Map<String, Object> el = lookup(t, e);
        assertThat(el.get("exchangePricing")).isEqualTo(Map.of("anyProduct", true, "freeVariantSwaps", false));
        assertThat(catalogueStatus(t, el)).isEqualTo(200);
        UUID exchange = request(t, submit(t, e, el, true));
        assertThat(jdbc.queryForObject("SELECT collect_cod FROM return_request_amounts WHERE request_id = ?",
            java.math.BigDecimal.class, exchange)).isEqualByComparingTo("200");
    }

    @Test
    void x1_crossTenant_tokensAndOwners_eachStoreItsOwnAnswer_withSameTenantControls() {
        T on = tenant(LISTED_X), off = tenant(UUID.randomUUID());
        configureFees(off);
        jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount) VALUES (?, 'refund', true, 'fixed', 75)", on.id());
        O oo = deliveredGeneric(on, "#7601"), fo = deliveredGeneric(off, "#7601");
        Map<String, Object> onLookup = lookup(on, oo), offLookup = lookup(off, fo);

        assertThat(quoteStatusWith(off, (String) onLookup.get("token"))).as("listed token on the unlisted store").isEqualTo(404);
        assertThat(quoteStatusWith(on, (String) offLookup.get("token"))).as("unlisted token on the listed store")
            .isNotEqualTo(200);
        assertThat(quoteStatusWith(on, (String) onLookup.get("token"))).as("same-tenant control").isEqualTo(200);
        assertThat(quoteStatusWith(off, (String) offLookup.get("token"))).as("unlisted own token: still off").isEqualTo(404);

        assertThat(http(off.ownerToken(), FEES, HttpMethod.GET, null, null).getBody()).containsEntry("visible", false);
        assertThat(http(on.ownerToken(), FEES, HttpMethod.GET, null, null).getBody()).as("control").containsEntry("visible", true);

        UUID onReq = request(on, submit(on, oo, onLookup, false)), offReq = request(off, submit(off, fo, offLookup, false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE request_id = ?", Integer.class, onReq)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE request_id = ?", Integer.class, offReq)).isZero();
        assertThat(http(on.ownerToken(), "/api/v1/return-requests/" + offReq, HttpMethod.GET, null, null).getStatusCode().value())
            .as("listed owner can't open the other store's request").isEqualTo(404);
    }

    // ── rollout helpers ──────────────────────────────────────────────────────────

    private void configureFees(T t) {
        jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount) VALUES (?, 'refund', true, 'fixed', 75)", t.id());
        jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, percent, min_amount) " +
            "VALUES (?, 'exchange', true, 'percent', 20, 10)", t.id());
        jdbc.update("INSERT INTO portal_fee_area_overrides (tenant_id, kind, city_id, mode, amount) VALUES (?, 'refund', 'X-CITY', 'fixed', 5)", t.id());
        jdbc.update("UPDATE tenants SET portal_merchant_fault_reasons = ARRAY['wrong_size']::text[], portal_free_variant_swaps = false, " +
            "portal_exchange_any_product = true WHERE id = ?", t.id());
    }

    private static Map<String, Object> rule(int amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", true);
        m.put("mode", "fixed");
        m.put("amount", amount);
        m.put("waiveMerchantFault", true);
        return m;
    }

    private Map<String, Object> quoteBody(T t) {
        return Map.of("lines", List.of(Map.of("variantId", t.m().toString(), "quantity", 1, "reasonCode", "wrong_size")));
    }

    private int quoteStatus(T t, Map<String, Object> lookup) {
        return quoteStatusWith(t, (String) lookup.get("token"));
    }

    private int quoteStatusWith(T t, String token) {
        try {
            return http(null, "/api/v1/portal/" + t.slug() + "/quote", HttpMethod.POST, quoteBody(t), token).getStatusCode().value();
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // The JDK client can't read a 401 to a streamed POST; it throws instead (the token was refused).
            if (e.getCause() instanceof java.net.HttpRetryException) return 401;
            throw e;
        }
    }

    private int catalogueStatus(T t, Map<String, Object> lookup) {
        return http(null, "/api/v1/portal/" + t.slug() + "/catalogue?variantId=" + t.m(), HttpMethod.GET, null,
            (String) lookup.get("token")).getStatusCode().value();
    }

    private Map<String, Object> suggestion(T t, UUID request) {
        return http(t.ownerToken(), "/api/v1/return-requests/" + request + "/refund-suggestion", HttpMethod.GET, null, null).getBody();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private T tenant(UUID id) {
        int n = SEQ.incrementAndGet();
        UUID store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "rollout-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled, portal_exchanges_enabled, " +
            "portal_generic_returns_enabled) VALUES (?, 'Rollout', ?, true, true, true, true)", id, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID owner = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Owner', ?, 'x', 'owner'::user_role, true) RETURNING id", UUID.class, id, "o-" + UUID.randomUUID() + "@test.com");
        UUID worker = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Worker', ?, 'x', 'worker'::user_role, true) RETURNING id", UUID.class, id, "w-" + UUID.randomUUID() + "@test.com");
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug);
        UUID m = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price) " +
            "VALUES (?, ?, ?, 'SH-M', 'Sand / M', 900) RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-m");
        UUID l = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price) " +
            "VALUES (?, ?, ?, 'SH-L', 'Sand / L', 1100) RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-l");
        T t = new T(id, slug, store, location, m, l, worker, jwt.issueAccessToken(owner, id, "owner"));
        piece(t, l, "available", null);
        piece(t, l, "available", null);
        return t;
    }

    private O deliveredGeneric(T t, String number) {
        String phone = "0105557" + number.substring(1);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, 1, '{\"price\":\"900.00\",\"paid_unit_price\":\"900.00\",\"quantity\":1}'::jsonb) RETURNING id", UUID.class, t.id(), order, t.m());
        String[] piece = piece(t, t.m(), "with_courier", order);
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

    /** {id, short code}. */
    private String[] piece(T t, UUID variant, String status, UUID order) {
        String id = UlidGenerator.generate();
        String code = "G" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), variant, "PC-" + id, code, status, order, order == null ? t.location() : null, t.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
        return new String[]{id, code};
    }

    private Map<String, Object> lookup(T t, O o) {
        PortalService.LookupResult r = portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return r.body();
    }

    private Map<String, Object> submit(T t, O o, Map<String, Object> lookup, boolean exchange) {
        PortalService.SubmitResult r = portal.submit(t.slug(), (String) lookup.get("token"), new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.m(), 1, "wrong_size")), null, null, null,
            exchange ? "exchange" : null, exchange ? t.l() : null, exchange ? false : null)).orElseThrow();
        assertThat(r.outcome()).as(String.valueOf(r.body())).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return r.body();
    }

    private UUID request(T t, Map<String, Object> submitBody) {
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE tenant_id = ? AND reference = ?", UUID.class,
            t.id(), submitBody.get("reference"));
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
            if (o != null) {
                out = out.replace(o.number(), "<number>").replace(o.number().substring(1), "<number>").replace(o.phone(), "<phone>");
                for (String p : o.pieces()) out = out.replace(p, "<piece>");
            }
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
