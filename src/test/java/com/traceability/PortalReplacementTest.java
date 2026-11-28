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
 * Defect replacement (V248, 2026-11-27) — "Replace with the same item": an exchange for the SAME variant, only for a
 * defect reason (ReplacementRule: damaged, wrong_item). Fixtures as PortalExchangePricingTest (another courier, fees
 * flag on; the customer bought Linen Shirt Sand / M for EGP 900).
 *
 *   r1 — lookup: config lists the replacement reasons; each line offers sameItem {inStock} from VariantStockService;
 *        exchanges off → neither key.
 *   r2 — submit: the same variant only for damaged / wrong_item (never wrong_size, not_as_pictured, changed_mind,
 *        other) → exchange_kind 'replacement' (+ event metadata); another size is still a 'swap' whatever the reason.
 *   r3 — stock: no second unit → refused at submit; sold out by approval → 409 REPLACEMENT_OUT_OF_STOCK.
 *   r4 — pricing (Q2): never a price difference under any setting (free swaps off, same-product refunds on, price up
 *        or down); the fee only with "Charge fees on defect replacements" on — and then without the merchant-fault
 *        waiver; off → nothing frozen.
 *   r5 — switched to a refund: type refund AND exchange_kind swap; the CHECK refuses a refund replacement.
 *   r6 — merchant labels: request list / detail / Returns & exchanges carry exchangeKind only on a replacement.
 *   r7 — approved (another courier): the replacement order is flagged isReplacement in the Orders list and detail;
 *        the original order and a swap's order are not.
 *   r8 — wording: Bosta "Replacement – new" / "Defective – return"; the Shopify note "replacement (same item)".
 *   r9 — settings: owner-only toggle, one audit row per real change, 400 on no value, 403 for a manager.
 *   a1 — analytics: replacements counted apart from swaps (variantRows replaced vs exchanged).
 *   x1 — app_user: exchange_kind is read and written under RLS; another tenant sees nothing (positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalReplacementTest {

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
    @Autowired com.traceability.analytics.ProductExtrasAnalyticsService extras;

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


    // ── r1: lookup ──────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void r1_lookup_replacementReasonsAndSameItemOffer() {
        T t = tenant(false, true, false, null);
        O o = delivered(t, "#9201", true);
        assertThat(portal.config(t.slug()).orElseThrow()).containsEntry("replacementReasons", List.of("damaged", "wrong_item"));
        assertThat(line(lookup(t, o))).containsEntry("sameItem", Map.of("inStock", false));
        piece(t, t.shirtM(), "available", null);
        assertThat(line(lookup(t, o))).containsEntry("sameItem", Map.of("inStock", true));

        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = false WHERE id = ?", t.id());
        assertThat(portal.config(t.slug()).orElseThrow()).doesNotContainKey("replacementReasons");
        assertThat(line(lookup(t, o))).doesNotContainKey("sameItem");
    }

    // ── r2: submit ──────────────────────────────────────────────────────────────

    @Test
    void r2_sameVariant_onlyForDefectReasons_storedAsReplacement() {
        T t = tenant(false, true, false, null);
        piece(t, t.shirtM(), "available", null);
        piece(t, t.shirtM(), "available", null);
        for (String reason : List.of("wrong_size", "not_as_pictured", "changed_mind", "other")) {
            O o = delivered(t, "#92" + (10 + SEQ.incrementAndGet() % 80), true);
            assertThat(submit(t, o, t.shirtM(), reason)).as(reason).isEmpty();
        }
        O d = delivered(t, "#9291", true);
        UUID damaged = submit(t, d, t.shirtM(), "damaged").orElseThrow();
        O w = delivered(t, "#9292", true);
        UUID wrong = submit(t, w, t.shirtM(), "wrong_item").orElseThrow();
        for (UUID r : List.of(damaged, wrong)) {
            assertThat(jdbc.queryForMap("SELECT type, exchange_kind, status::text AS status FROM return_requests WHERE id = ?", r))
                .containsEntry("type", "exchange").containsEntry("exchange_kind", "replacement").containsEntry("status", "requested");
            assertThat(jdbc.queryForObject("SELECT replacement_variant_id FROM return_request_items WHERE request_id = ?", UUID.class, r))
                .isEqualTo(t.shirtM());
            assertThat(jdbc.queryForObject("SELECT metadata->>'exchange_kind' FROM return_request_events " +
                "WHERE request_id = ? AND event_type = 'requested'", String.class, r)).isEqualTo("replacement");
        }
        O s = delivered(t, "#9293", true);
        UUID swap = submit(t, s, t.shirtL(), "damaged").orElseThrow();
        assertThat(jdbc.queryForObject("SELECT exchange_kind FROM return_requests WHERE id = ?", String.class, swap)).isEqualTo("swap");
        assertThat(jdbc.queryForObject("SELECT (metadata->>'exchange_kind') IS NOT NULL FROM return_request_events " +
            "WHERE request_id = ? AND event_type = 'requested'", Boolean.class, swap)).isFalse();
    }

    // ── r3: stock ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void r3_noSecondUnit_refused_soldOutByApproval_409() {
        T t = tenant(false, true, false, null);
        O o = delivered(t, "#9301", true);
        assertThat(submit(t, o, t.shirtM(), "damaged")).as("only the customer's own unit — none on hand").isEmpty();
        String spare = piece(t, t.shirtM(), "available", null);
        UUID r = submit(t, o, t.shirtM(), "damaged").orElseThrow();
        jdbc.update("UPDATE pieces SET status = 'damaged'::piece_status WHERE id = ?", spare);
        ResponseEntity<Map<String, Object>> res = http(t.ownerToken(), "/api/v1/return-requests/" + r + "/approve", HttpMethod.POST, Map.of());
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, r)).isEqualTo("requested");
    }

    // ── r4: pricing ─────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void r4_neverAPriceDifference_feeOnlyWithToggle_withoutTheWaiver() {
        T t = tenant(true, false, false, null);   // free swaps OFF: a same-product swap pays the difference
        jdbc.update("UPDATE tenants SET portal_same_product_swap_refunds = true WHERE id = ?", t.id());
        jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount, waive_merchant_fault) " +
            "VALUES (?, 'exchange', true, 'fixed', 50, true)", t.id());
        piece(t, t.shirtM(), "available", null);
        piece(t, t.shirtM(), "available", null);
        piece(t, t.shirtM(), "available", null);

        // Toggle OFF (default): nothing priced, nothing frozen — even with the price up since the purchase.
        jdbc.update("UPDATE variants SET price = 1500 WHERE id = ?", t.shirtM());
        O o1 = delivered(t, "#9401", true);
        Map<String, Object> l1 = lookup(t, o1);
        assertThat(quote(t, l1, t.shirtM(), "damaged")).isEqualTo(Map.of("applies", false));
        UUID r1 = submit(t, o1, l1, t.shirtM(), "damaged").orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE request_id = ?", Integer.class, r1)).isZero();
        // A swap to another size on the same store still pays its difference (unchanged).
        assertThat(quote(t, l1, t.shirtL(), "damaged")).containsEntry("priceDifference", "200.00");

        // Toggle ON: the exchange fee, NOT waived for the defect reason, and still no difference (price up or down).
        jdbc.update("UPDATE tenants SET portal_replacement_fees = true WHERE id = ?", t.id());
        for (String price : List.of("1500", "500")) {
            jdbc.update("UPDATE variants SET price = ?::numeric WHERE id = ?", price, t.shirtM());
            O o = delivered(t, "#94" + (10 + SEQ.incrementAndGet() % 80), true);
            Map<String, Object> l = lookup(t, o);
            Map<String, Object> q = quote(t, l, t.shirtM(), "damaged");
            assertThat(q).as(price).containsEntry("applies", true).containsEntry("fee", "50.00").containsEntry("feeWaived", false)
                .containsEntry("collect", "50.00").doesNotContainKeys("priceDifference", "priceDifferenceUnknown", "blocked");
            assertThat(q.get("customerOwed")).isNull();
            UUID r = submit(t, o, l, t.shirtM(), "damaged").orElseThrow();
            Map<String, Object> a = jdbc.queryForMap("SELECT fee_amount, fee_waived, collect_cod, customer_owed, price_difference, " +
                "replacement_unit_price FROM return_request_amounts WHERE request_id = ?", r);
            assertThat((BigDecimal) a.get("fee_amount")).isEqualByComparingTo("50");
            assertThat(a.get("fee_waived")).isEqualTo(false);
            assertThat((BigDecimal) a.get("collect_cod")).isEqualByComparingTo("50");
            assertThat((BigDecimal) a.get("customer_owed")).isEqualByComparingTo("0");
            assertThat(a.get("price_difference")).isNull();
            assertThat(a.get("replacement_unit_price")).isNull();
        }
        // The same item for a non-defect reason: the quote refuses it, as submit does.
        O ow = delivered(t, "#9498", true);
        assertThat(portal.quote(t.slug(), (String) lookup(t, ow).get("token"), request(t, t.shirtM(), "wrong_size"))
            .orElseThrow().outcome()).isEqualTo(PortalService.QuoteOutcome.INVALID);
        // No exchange fee rule at all → nothing, even with the toggle on.
        jdbc.update("UPDATE portal_fee_rules SET enabled = false WHERE tenant_id = ?", t.id());
        O o3 = delivered(t, "#9499", true);
        assertThat(quote(t, lookup(t, o3), t.shirtM(), "damaged")).isEqualTo(Map.of("applies", false));
    }

    // ── r5: switched to a refund ────────────────────────────────────────────────

    @Test
    void r5_switchedToRefund_isASwapAgain_checkRefusesARefundReplacement() {
        T t = tenant(false, true, false, null);
        piece(t, t.shirtM(), "available", null);
        O o = delivered(t, "#9501", true);
        Map<String, Object> l = lookup(t, o);
        PortalService.SubmitRequest req = new PortalService.SubmitRequest(List.of(new PortalService.SubmitLine(t.shirtM(), 1, "damaged")),
            null, null, null, "exchange", t.shirtM(), true, null, null, null, null);
        PortalService.SubmitResult res = portal.submit(t.slug(), (String) l.get("token"), req).orElseThrow();
        UUID r = requestId(t, res.body());
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + r + "/switch-to-refund", HttpMethod.POST, Map.of())
            .getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbc.queryForMap("SELECT type, exchange_kind FROM return_requests WHERE id = ?", r))
            .containsEntry("type", "refund").containsEntry("exchange_kind", "swap");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE return_requests SET exchange_kind = 'replacement' WHERE id = ?", r))
            .hasMessageContaining("return_requests_replacement_is_exchange");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbc.update("UPDATE return_requests SET exchange_kind = 'other' WHERE id = ?", r))
            .hasMessageContaining("return_requests_exchange_kind_check");
    }

    // ── r6: merchant labels ─────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void r6_exchangeKind_onReplacementsOnly_list_detail_cases() {
        T t = tenant(false, true, false, null);
        piece(t, t.shirtM(), "available", null);
        UUID repl = submit(t, delivered(t, "#9601", true), t.shirtM(), "damaged").orElseThrow();
        UUID swap = submit(t, delivered(t, "#9602", true), t.shirtL(), "wrong_size").orElseThrow();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) (Object) restList(t.ownerToken(), "/api/v1/return-requests");
        Map<String, Object> rr = byId(rows, repl), rs = byId(rows, swap);
        assertThat(rr).containsEntry("type", "exchange").containsEntry("exchangeKind", "replacement");
        assertThat(rs).containsEntry("type", "exchange").doesNotContainKey("exchangeKind");
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + repl, HttpMethod.GET, null).getBody())
            .containsEntry("exchangeKind", "replacement");
        assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + swap, HttpMethod.GET, null).getBody())
            .doesNotContainKey("exchangeKind");

        List<Map<String, Object>> cases = (List<Map<String, Object>>) http(t.ownerToken(), "/api/v1/returns-exchanges", HttpMethod.GET, null)
            .getBody().get("items");
        assertThat(byId(cases, repl)).containsEntry("kind", "exchange").containsEntry("exchangeKind", "replacement");
        assertThat(byId(cases, swap)).containsEntry("kind", "exchange").doesNotContainKey("exchangeKind");
    }

    // ── r7: the replacement order ───────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void r7_approved_replacementOrderFlagged_inOrdersListAndDetail() {
        T t = tenant(false, true, false, null);
        piece(t, t.shirtM(), "available", null);
        O o = delivered(t, "#9701", true);
        UUID repl = submit(t, o, t.shirtM(), "damaged").orElseThrow();
        UUID swap = submit(t, delivered(t, "#9702", true), t.shirtL(), "wrong_size").orElseThrow();
        for (UUID r : List.of(repl, swap)) {
            assertThat(http(t.ownerToken(), "/api/v1/return-requests/" + r + "/approve", HttpMethod.POST, Map.of())
                .getStatusCode().is2xxSuccessful()).isTrue();
        }
        UUID replOrder = jdbc.queryForObject("SELECT replacement_order_id FROM return_requests WHERE id = ?", UUID.class, repl);
        UUID swapOrder = jdbc.queryForObject("SELECT replacement_order_id FROM return_requests WHERE id = ?", UUID.class, swap);
        assertThat(replOrder).isNotNull();
        assertThat(swapOrder).isNotNull();

        List<Map<String, Object>> list = (List<Map<String, Object>>) http(t.ownerToken(), "/api/v1/orders?size=100", HttpMethod.GET, null)
            .getBody().get("items");
        assertThat(byId(list, replOrder)).containsEntry("isReplacement", true);
        assertThat(byId(list, swapOrder)).doesNotContainKey("isReplacement");
        assertThat(byId(list, o.id())).doesNotContainKey("isReplacement");
        assertThat(http(t.ownerToken(), "/api/v1/orders/" + replOrder, HttpMethod.GET, null).getBody()).containsEntry("isReplacement", true);
        assertThat(http(t.ownerToken(), "/api/v1/orders/" + o.id(), HttpMethod.GET, null).getBody()).doesNotContainKey("isReplacement");
    }

    // ── r8: wording ─────────────────────────────────────────────────────────────

    @Test
    void r8_bostaDescriptions_andShopifyNote() {
        T t = tenant(false, true, false, null);
        piece(t, t.shirtM(), "available", null);
        UUID repl = submit(t, delivered(t, "#9801", true), t.shirtM(), "damaged").orElseThrow();
        String ref = jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, repl);
        ReturnPickupBookingService.ExchangeItem item = ReturnPickupBookingService.exchangeItem(jdbc, repl, t.id());
        assertThat(item.replacement()).isTrue();
        assertThat(item.outbound(ref)).isEqualTo(ref + ": Replacement – new: Linen Shirt / Sand / M");
        assertThat(ReturnPickupBookingService.exchangeReturnDescription("Linen Shirt", "Sand / M", true))
            .isEqualTo("Defective – return: Linen Shirt / Sand / M");

        UUID swap = submit(t, delivered(t, "#9802", true), t.shirtL(), "wrong_size").orElseThrow();
        String ref2 = jdbc.queryForObject("SELECT reference FROM return_requests WHERE id = ?", String.class, swap);
        assertThat(ReturnPickupBookingService.exchangeItem(jdbc, swap, t.id()).outbound(ref2)).isEqualTo(ref2 + ": Linen Shirt / Sand / L");
        assertThat(ReturnPickupBookingService.exchangeReturnDescription("Linen Shirt", "Sand / M")).isEqualTo("Linen Shirt / Sand / M");

        String note = ShopifyReturnSyncRules.exchangeNote(ref, "Linen Shirt / Sand / M", null, null, false, true);
        assertThat(note).contains("replacement (same item): Linen Shirt / Sand / M").doesNotContain("exchanged for");
        assertThat(ShopifyReturnSyncRules.exchangeNote(ref, "Linen Shirt / Sand / L", null, null, false))
            .contains("exchanged for Linen Shirt / Sand / L");
    }

    // ── r9: settings ────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void r9_toggle_ownerOnly_auditedOncePerRealChange() {
        T t = tenant(false, true, false, null);
        String base = "/api/v1/tenant/portal-settings/customer-fees";
        assertThat(http(t.ownerToken(), base, HttpMethod.GET, null).getBody()).containsEntry("replacementFees", false);
        assertThat(http(t.ownerToken(), base + "/replacement-fees", HttpMethod.PUT, Map.of("charge", true)).getBody())
            .containsEntry("replacementFees", true);
        assertThat(http(t.ownerToken(), base + "/replacement-fees", HttpMethod.PUT, Map.of("charge", true)).getStatusCode().value())
            .isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = 'portal_replacement_fees_update'",
            String.class, t.id())).singleElement().satisfies(m -> assertThat(m).contains("\"from\": false").contains("\"to\": true"));
        assertThat(http(t.ownerToken(), base + "/replacement-fees", HttpMethod.PUT, Map.of()).getStatusCode().value()).isEqualTo(400);
        UUID manager = jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, 'Manager', ?, 'x', 'manager'::user_role, true) RETURNING id", UUID.class, t.id(), "m-" + UUID.randomUUID() + "@t.com");
        String managerToken = jwt.issueAccessToken(manager, t.id(), "manager");
        assertThat(http(managerToken, base + "/replacement-fees", HttpMethod.PUT, Map.of("charge", false)).getStatusCode().value()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT portal_replacement_fees FROM tenants WHERE id = ?", Boolean.class, t.id())).isTrue();
    }

    // ── a1: analytics ───────────────────────────────────────────────────────────

    @Test
    void a1_analytics_replacementsCountedApartFromSwaps() {
        T t = tenant(false, true, false, null);
        piece(t, t.shirtM(), "available", null);
        submit(t, delivered(t, "#9901", true), t.shirtM(), "damaged").orElseThrow();
        submit(t, delivered(t, "#9902", true), t.shirtL(), "wrong_size").orElseThrow();
        java.time.LocalDate today = java.time.LocalDate.now(com.traceability.analytics.AnalyticsPeriod.CAIRO);
        List<com.traceability.analytics.ProductExtrasAnalyticsService.VariantRow> rows = TenantContext.runAs(t.id(),
            () -> extras.variantRows(new com.traceability.analytics.AnalyticsPeriod(today.minusDays(29), today)));
        com.traceability.analytics.ProductExtrasAnalyticsService.VariantRow m = rows.stream()
            .filter(r -> r.variantId().equals(t.shirtM())).findFirst().orElseThrow();
        assertThat(m.replaced()).as("the replacement").isEqualTo(1);
        assertThat(m.exchanged()).as("the size swap only").isEqualTo(1);
    }

    // ── x1: app_user ────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_exchangeKindUnderRls_positiveControl() {
        T a = tenant(false, true, false, null), b = tenant(false, true, false, null);
        piece(a, a.shirtM(), "available", null);
        UUID r = submit(a, delivered(a, "#9951", true), a.shirtM(), "damaged").orElseThrow();
        String own = TenantContext.runAs(a.id(), () -> appTx.execute(s -> appJdbc.queryForList(
            "SELECT exchange_kind FROM return_requests WHERE id = ?", String.class, r).stream().findFirst().orElse(null)));
        assertThat(own).isEqualTo("replacement");
        String other = TenantContext.runAs(b.id(), () -> appTx.execute(s -> appJdbc.queryForList(
            "SELECT exchange_kind FROM return_requests WHERE id = ?", String.class, r).stream().findFirst().orElse(null)));
        assertThat(other).isNull();
        Integer otherUpdated = TenantContext.runAs(b.id(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE return_requests SET exchange_kind = 'swap' WHERE id = ?", r)));
        assertThat(otherUpdated).isZero();
        Integer ownUpdated = TenantContext.runAs(a.id(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE tenants SET portal_replacement_fees = true WHERE id = ?", a.id())));
        assertThat(ownUpdated).isEqualTo(1);
    }

    // ── r helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> line(Map<String, Object> lookup) {
        return ((List<Map<String, Object>>) lookup.get("lines")).get(0);
    }

    private static Map<String, Object> byId(List<Map<String, Object>> rows, UUID id) {
        return rows.stream().filter(m -> id.toString().equals(m.get("id"))).findFirst()
            .orElseThrow(() -> new AssertionError("no row " + id));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Object> restList(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        Object body = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), Object.class).getBody();
        if (body instanceof Map m && m.get("items") instanceof List l) return l;
        if (body instanceof Map m && m.get("requests") instanceof List l) return l;
        return (List<Object>) body;
    }

    private Map<String, Object> quote(T t, Map<String, Object> lookup, UUID replacement, String reason) {
        PortalService.QuoteResult r = portal.quote(t.slug(), (String) lookup.get("token"), request(t, replacement, reason)).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.QuoteOutcome.OK);
        return r.body();
    }

    private PortalService.SubmitRequest request(T t, UUID replacement, String reason) {
        return new PortalService.SubmitRequest(List.of(new PortalService.SubmitLine(t.shirtM(), 1, reason)), null, null, null,
            "exchange", replacement, false, null, null, null, null);
    }

    private Optional<UUID> submit(T t, O o, UUID replacement, String reason) {
        return submit(t, o, lookup(t, o), replacement, reason);
    }

    private Optional<UUID> submit(T t, O o, Map<String, Object> lookup, UUID replacement, String reason) {
        PortalService.SubmitResult r = portal.submit(t.slug(), (String) lookup.get("token"), request(t, replacement, reason)).orElseThrow();
        if (r.outcome() == PortalService.SubmitOutcome.INVALID) return Optional.empty();
        assertThat(r.outcome()).as(String.valueOf(r.body())).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return Optional.of(requestId(t, r.body()));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private static PortalAmounts.Difference diff(String paid, String replacement) {
        return new PortalAmounts.Difference(new BigDecimal(paid), new BigDecimal(replacement));
    }

    private T tenant(boolean anyProduct, boolean freeSwaps, boolean lowerOnly, BigDecimal maxDifference) {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "rpx-" + n + "-" + id.toString().substring(0, 6);
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
