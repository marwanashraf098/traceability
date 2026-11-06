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
 * Returns portal fees F3 — exchange to a different product and the price difference. Flag ON (the off golden is
 * PortalExchangePricingOffGoldenTest). Every store: Linen Shirt (Sand / M 900 — the item bought, Sand / L 1100),
 * Trousers (S 700, M 1500, XL 1200 out of stock), a DRAFT Hat. The customer paid EGP 900 for Sand / M.
 *
 *   c1 — THE calculator: difference + fee → collect or owed; caps and the COD limit block; approximate price blocks
 *        another product only.
 *   d1 — any product on: lookup says so (option prices); the catalogue lists only other ACTIVE products' in-stock
 *        variants with their difference; quote = frozen (difference, collect); the drawer shows it.
 *   d2 — only today's catalogue price for the item: the catalogue answers blocked; another product refused at submit;
 *        the same product still allowed.
 *   d3 — caps: "same price or lower only" and the max difference drop variants from the catalogue and refuse them.
 *   s1 — free variant swaps ON: a pricier size changes nothing (no pricing key, no amounts); OFF: the size pays the
 *        difference (option prices, quote, frozen).
 *   o1 — customer owed: the refund method is asked (required); the merchant records the payment once the old item is
 *        back (suggested = owed); never marked refunded; an exchange owed nothing still has no refund.
 *   w1 — switched to a refund: the frozen exchange amounts are recalculated as a refund (refund fee, due), recorded.
 *   b1 — the booking description: what goes out is the replacement's own product.
 *   x1 — app_user: the catalogue never offers another tenant's products and another tenant reads no amounts owed;
 *        the same tenant does (positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalExchangePricingTest {

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

    // ── c1: the calculator ───────────────────────────────────────────────────────

    @Test
    void c1_differencePlusFee_collectOrOwed_blocks() {
        PortalAmounts.Terms fee50 = new PortalAmounts.Terms("exchange", "fixed", new BigDecimal("50"), null, null, null, true,
            "rule", null, null);
        List<PortalAmounts.Item> stored = List.of(new PortalAmounts.Item("0", UUID.randomUUID(), null, new BigDecimal("900"), "stored", "wrong_size"));
        PortalAmounts.ExchangePricing open = new PortalAmounts.ExchangePricing(true, false, null, true);

        PortalAmounts.Quote q = PortalAmounts.calculate("exchange", fee50, Set.of(), stored, diff("900", "870"));
        assertThat(q.priceDifference()).isEqualByComparingTo("-30");
        assertThat(q.collect()).as("−30 + 50 → the customer pays 20").isEqualByComparingTo("20");
        assertThat(q.customerOwed()).isEqualByComparingTo("0");
        q = PortalAmounts.calculate("exchange", fee50, Set.of(), stored, diff("900", "800"));
        assertThat(q.collect()).isEqualByComparingTo("0");
        assertThat(q.customerOwed()).as("−100 + 50 → owed 50").isEqualByComparingTo("50");
        q = PortalAmounts.calculate("exchange", null, Set.of(), stored, diff("900", "1100"));
        assertThat(q.fee()).isEqualByComparingTo("0");
        assertThat(q.collect()).as("no fee rule: the difference alone").isEqualByComparingTo("200");
        assertThat(PortalAmounts.exchangeBlock(open, false, q)).isNull();
        assertThat(PortalAmounts.exchangeBlock(new PortalAmounts.ExchangePricing(true, true, null, true), false, q))
            .isEqualTo("same_or_lower_only");
        assertThat(PortalAmounts.exchangeBlock(new PortalAmounts.ExchangePricing(true, false, new BigDecimal("199"), true), false, q))
            .isEqualTo("max_difference");
        q = PortalAmounts.calculate("exchange", null, Set.of(), stored, diff("900", "31000"));
        assertThat(PortalAmounts.exchangeBlock(open, false, q)).isEqualTo("cod_limit");
        List<PortalAmounts.Item> catalog = List.of(new PortalAmounts.Item("0", UUID.randomUUID(), null, new BigDecimal("900"), "catalog", "wrong_size"));
        q = PortalAmounts.calculate("exchange", null, Set.of(), catalog, diff("900", "1100"));
        assertThat(PortalAmounts.exchangeBlock(open, false, q)).as("another product needs the price paid").isEqualTo("approximate_price");
        assertThat(PortalAmounts.exchangeBlock(open, true, q)).as("the same product doesn't").isNull();
        assertThat(q.approximate()).isTrue();
    }

    // ── d1: a different product ──────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void d1_differentProduct_catalogueQuoteFrozen() {
        T t = tenant(true, true, false, null);
        O o = delivered(t, "#3101", true);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(lookup.get("exchangePricing")).isEqualTo(Map.of("anyProduct", true, "freeVariantSwaps", true));
        Map<String, Object> line = ((List<Map<String, Object>>) lookup.get("lines")).get(0);
        assertThat((List<Map<String, Object>>) line.get("exchangeOptions")).singleElement()
            .satisfies(opt -> assertThat(opt).containsEntry("price", "1100.00"));

        Map<String, Object> cat = catalogue(t, lookup, t.shirtM());
        assertThat(cat).containsEntry("paidPrice", "900.00");
        List<Map<String, Object>> products = (List<Map<String, Object>>) cat.get("products");
        assertThat(products).extracting(p -> p.get("title")).containsExactly("Trousers");
        assertThat((List<Map<String, Object>>) products.get(0).get("variants"))
            .extracting(v -> v.get("title") + "=" + v.get("price") + "/" + v.get("priceDifference"))
            .containsExactly("M=1500.00/600.00", "S=700.00/-200.00");

        Map<String, Object> quote = quote(t, lookup, t.trousersM());
        assertThat(quote).containsEntry("applies", true).containsEntry("priceDifference", "600.00").containsEntry("collect", "600.00")
            .containsEntry("customerOwed", "0.00").doesNotContainKey("blocked");
        UUID request = submitExchange(t, o, lookup, t.trousersM(), null).orElseThrow();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM return_request_amounts WHERE request_id = ?", request);
        assertThat((BigDecimal) row.get("original_unit_price")).isEqualByComparingTo("900");
        assertThat((BigDecimal) row.get("replacement_unit_price")).isEqualByComparingTo("1500");
        assertThat((BigDecimal) row.get("price_difference")).isEqualByComparingTo("600");
        assertThat((BigDecimal) row.get("collect_cod")).isEqualByComparingTo("600");
        assertThat(row.get("fee_kind")).isEqualTo("exchange");
        Map<String, Object> amounts = (Map<String, Object>) http(t.ownerToken(), "/api/v1/return-requests/" + request,
            HttpMethod.GET, null).getBody().get("amounts");
        assertThat(amounts).containsEntry("priceDifference", "600.00").containsEntry("collect", "600.00")
            .containsEntry("replacementPrice", "1500.00");
        verifyNoInteractions(priceGateway);
    }

    // ── d2: approximate price ────────────────────────────────────────────────────

    @Test
    void d2_approximatePrice_otherProductBlocked_sameProductAllowed() {
        T t = tenant(true, true, false, null);
        O o = delivered(t, "#3201", false);          // no price stored, store not connected → catalogue price only
        Map<String, Object> lookup = lookup(t, o);
        assertThat(catalogue(t, lookup, t.shirtM())).isEqualTo(Map.of("blocked", "approximate_price"));
        assertThat(quote(t, lookup, t.trousersS())).containsEntry("blocked", "approximate_price");
        assertThat(submitExchange(t, o, lookup, t.trousersS(), null)).isEmpty();
        assertThat(submitExchange(t, o, lookup, t.shirtL(), null)).as("same product still allowed").isPresent();
    }

    // ── d3: caps ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void d3_caps_sameOrLowerOnly_andMaxDifference() {
        T lower = tenant(true, true, true, null);
        O o = delivered(lower, "#3301", true);
        Map<String, Object> lookup = lookup(lower, o);
        assertThat((List<Map<String, Object>>) ((List<Map<String, Object>>) catalogue(lower, lookup, lower.shirtM()).get("products"))
            .get(0).get("variants")).extracting(v -> v.get("title")).containsExactly("S");
        assertThat(quote(lower, lookup, lower.trousersM())).containsEntry("blocked", "same_or_lower_only");
        assertThat(submitExchange(lower, o, lookup, lower.trousersM(), null)).isEmpty();

        T capped = tenant(true, true, false, new BigDecimal("100"));
        O o2 = delivered(capped, "#3302", true);
        Map<String, Object> lookup2 = lookup(capped, o2);
        assertThat(quote(capped, lookup2, capped.trousersM())).containsEntry("blocked", "max_difference");
        assertThat(submitExchange(capped, o2, lookup2, capped.trousersM(), null)).isEmpty();
        assertThat(submitExchange(capped, o2, lookup2, capped.trousersS(), null)).isPresent();
    }

    // ── s1: free variant swaps ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void s1_freeSwaps_onUnchanged_offPaysDifference() {
        T free = tenant(false, true, false, null);
        O o = delivered(free, "#3401", true);
        Map<String, Object> lookup = lookup(free, o);
        assertThat(lookup).doesNotContainKey("exchangePricing");
        assertThat((List<Map<String, Object>>) ((List<Map<String, Object>>) lookup.get("lines")).get(0).get("exchangeOptions"))
            .singleElement().satisfies(opt -> assertThat(opt).doesNotContainKey("price"));
        UUID r1 = submitExchange(free, o, lookup, free.shirtL(), null).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE request_id = ?", Integer.class, r1)).isZero();

        // Free swaps ON with another product allowed: a pricier size of the same product is still free.
        T anyFree = tenant(true, true, false, null);
        O oa = delivered(anyFree, "#3403", true);
        Map<String, Object> lookupA = lookup(anyFree, oa);
        assertThat(quote(anyFree, lookupA, anyFree.shirtL())).isEqualTo(Map.of("applies", false));
        UUID ra = submitExchange(anyFree, oa, lookupA, anyFree.shirtL(), null).orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE request_id = ?", Integer.class, ra)).isZero();

        T paid = tenant(false, false, false, null);
        O o2 = delivered(paid, "#3402", true);
        Map<String, Object> lookup2 = lookup(paid, o2);
        assertThat(lookup2.get("exchangePricing")).isEqualTo(Map.of("anyProduct", false, "freeVariantSwaps", false));
        assertThat(quote(paid, lookup2, paid.shirtL())).containsEntry("priceDifference", "200.00").containsEntry("collect", "200.00");
        UUID r2 = submitExchange(paid, o2, lookup2, paid.shirtL(), null).orElseThrow();
        assertThat((BigDecimal) jdbc.queryForObject("SELECT collect_cod FROM return_request_amounts WHERE request_id = ?",
            BigDecimal.class, r2)).isEqualByComparingTo("200");
        assertThat(http(paid.ownerToken(), "/api/v1/portal/" + paid.slug() + "/catalogue?variantId=" + paid.shirtM(), HttpMethod.GET,
            null, (String) lookup2.get("token")).getStatusCode().value()).as("no catalogue unless any product is on").isEqualTo(404);
    }

    // ── o1: the customer is owed ─────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void o1_owed_methodAsked_paymentRecorded_neverRefunded() {
        T t = tenant(true, true, false, null);
        jdbc.update("UPDATE tenants SET portal_refund_methods = ARRAY['cash']::text[] WHERE id = ?", t.id());
        O o = delivered(t, "#3501", true);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(quote(t, lookup, t.trousersS())).containsEntry("customerOwed", "200.00").containsEntry("collect", "0.00");
        assertThat(submitExchange(t, o, lookup, t.trousersS(), null)).as("owed → the method is required").isEmpty();
        UUID request = submitExchange(t, o, lookup, t.trousersS(), "cash").orElseThrow();
        assertThat((BigDecimal) jdbc.queryForObject("SELECT customer_owed FROM return_request_amounts WHERE request_id = ?",
            BigDecimal.class, request)).isEqualByComparingTo("200");

        String refunds = "/api/v1/return-requests/" + request + "/refunds";
        Map<String, Object> pay = Map.of("method", "cash", "amount", "200.00", "refundedOn", java.time.LocalDate.now().minusDays(1).toString());
        assertThat(http(t.ownerToken(), refunds, HttpMethod.POST, pay).getStatusCode().value()).as("not back yet").isEqualTo(409);
        jdbc.update("UPDATE return_requests SET status = 'exchanged' WHERE id = ?", request);   // the old item came back
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + request + "/refund-suggestion", HttpMethod.GET, null).getBody())
            .containsEntry("amount", "200.00").containsEntry("source", "frozen");
        assertThat(http(t.ownerToken(), refunds, HttpMethod.POST, pay).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_refunds WHERE request_id = ? AND kind = 'refund'", Integer.class, request))
            .isEqualTo(1);
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + request + "/mark-refunded", HttpMethod.POST, null)
            .getStatusCode().value()).as("an exchange never becomes refunded").isEqualTo(409);

        // An exchange that leaves the customer owed nothing: no refund, as before.
        O o2 = delivered(t, "#3502", true);
        UUID other = submitExchange(t, o2, lookup(t, o2), t.trousersM(), null).orElseThrow();
        jdbc.update("UPDATE return_requests SET status = 'exchanged' WHERE id = ?", other);
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + other + "/refunds", HttpMethod.POST, pay)
            .getStatusCode().value()).isEqualTo(409);
    }

    // ── w1: switched to a refund ─────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void w1_switchedToRefund_amountsRecalculatedAsRefund() {
        T t = tenant(false, true, false, null);
        jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount) VALUES (?, 'exchange', true, 'fixed', 40), " +
            "(?, 'refund', true, 'fixed', 75)", t.id(), t.id());
        O o = delivered(t, "#3601", true);
        Map<String, Object> lookup = lookup(t, o);
        PortalService.SubmitResult r = portal.submit(t.slug(), (String) lookup.get("token"), new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.shirtM(), 1, "wrong_size")), null, null, null, "exchange", t.shirtL(), true))
            .orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        UUID request = requestId(t, r.body());
        assertThat(jdbc.queryForObject("SELECT fee_kind FROM return_request_amounts WHERE request_id = ?", String.class, request))
            .isEqualTo("exchange");

        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + request + "/switch-to-refund", HttpMethod.POST, null)
            .getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM return_request_amounts WHERE request_id = ?", request);
        assertThat(row.get("fee_kind")).isEqualTo("refund");
        assertThat((BigDecimal) row.get("fee_amount")).isEqualByComparingTo("75");
        assertThat((BigDecimal) row.get("refund_due")).isEqualByComparingTo("825");
        assertThat((BigDecimal) row.get("collect_cod")).isEqualByComparingTo("0");
        assertThat(row.get("price_difference")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_events WHERE request_id = ? AND event_type = 'amounts_recalculated'",
            Integer.class, request)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, request)).isEqualTo("approved");
    }

    // ── b1: booking description ──────────────────────────────────────────────────

    @Test
    void b1_bookingDescription_isTheReplacementsOwnProduct() {
        T t = tenant(true, true, false, null);
        O o = delivered(t, "#3701", true);
        UUID other = submitExchange(t, o, lookup(t, o), t.trousersS(), null).orElseThrow();
        String ref = jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, other);
        assertThat(ReturnPickupBookingService.exchangeItem(jdbc, other, t.id()).outbound(ref)).isEqualTo(ref + ": Trousers / S");
        assertThat(ReturnPickupBookingService.exchangeReturnDescription("Linen Shirt", "Sand / M")).isEqualTo("Linen Shirt / Sand / M");

        O o2 = delivered(t, "#3702", true);
        UUID same = submitExchange(t, o2, lookup(t, o2), t.shirtL(), null).orElseThrow();
        String ref2 = jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, same);
        assertThat(ReturnPickupBookingService.exchangeItem(jdbc, same, t.id()).outbound(ref2))
            .as("same product: unchanged").isEqualTo(ref2 + ": Linen Shirt / Sand / L");
    }

    // ── x1: app_user isolation ───────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void x1_appUser_catalogueAndOwedAreTenantScoped_positiveControl() {
        T a = tenant(true, true, false, null), b = tenant(true, true, false, null);
        jdbc.update("UPDATE tenants SET portal_refund_methods = ARRAY['cash']::text[] WHERE id = ?", a.id());
        O o = delivered(a, "#3801", true);
        UUID request = submitExchange(a, o, lookup(a, o), a.trousersS(), "cash").orElseThrow();
        PortalAmounts.ExchangePricing open = new PortalAmounts.ExchangePricing(true, false, null, true);

        // Tenant A's catalogue on app_user: its own Trousers, never B's (B's ids, B's products).
        List<Map<String, Object>> asA = TenantContext.runAs(a.id(), () -> appTx.execute(s ->
            new ExchangeCatalogue(appJdbc).list(a.id(), a.shirtM(), new BigDecimal("900"), open, null)));
        Set<String> aVariants = new HashSet<>();
        for (Map<String, Object> p : asA) for (Map<String, Object> v : (List<Map<String, Object>>) p.get("variants")) aVariants.add((String) v.get("variantId"));
        assertThat(aVariants).contains(a.trousersS().toString()).doesNotContain(b.trousersS().toString(), b.trousersM().toString());
        // Another tenant asking for A's catalogue under its own context gets nothing of A's.
        List<Map<String, Object>> crossed = TenantContext.runAs(b.id(), () -> appTx.execute(s ->
            new ExchangeCatalogue(appJdbc).list(a.id(), a.shirtM(), new BigDecimal("900"), open, null)));
        assertThat(crossed).isEmpty();

        // Another tenant's context asking with A's OWN ids (as a buggy caller would): RLS hides A's rows.
        BigDecimal otherSees = TenantContext.runAs(b.id(), () -> appTx.execute(s -> new RequestAmounts(appJdbc).customerOwed(a.id(), request)));
        assertThat(otherSees).isEqualByComparingTo("0");
        Integer otherProducts = TenantContext.runAs(b.id(), () -> appTx.execute(s ->
            appJdbc.queryForObject("SELECT count(*) FROM products WHERE tenant_id = ?", Integer.class, a.id())));
        assertThat(otherProducts).isZero();
        BigDecimal ownSees = TenantContext.runAs(a.id(), () -> appTx.execute(s -> new RequestAmounts(appJdbc).customerOwed(a.id(), request)));
        assertThat(ownSees).isEqualByComparingTo("200");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private static PortalAmounts.Difference diff(String paid, String replacement) {
        return new PortalAmounts.Difference(new BigDecimal(paid), new BigDecimal(replacement));
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
