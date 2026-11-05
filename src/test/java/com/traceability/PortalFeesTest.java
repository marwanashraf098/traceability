package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalAmounts;
import com.traceability.portal.PortalService;
import com.traceability.portal.RequestAmounts;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Returns portal fees F2 — fees calculated, shown and frozen; the refund deduction. Flag ON here (the off golden is
 * PortalFeesOffGoldenTest).
 *
 *   c1 — THE calculator: fixed charged unless every item is merchant-fault; percent waived per item, clamped to
 *        min / max; whole EGP half up; waiving switched off charges anyway; fee ≥ value → due 0; exchange → collect.
 *   q1 — lookup says which fees apply; the quote (HTTP) equals the amounts frozen at submit; a later settings change
 *        changes the next quote but never the frozen row; no Shopify read when the prices are stored.
 *   a1 — area: district override beats city override beats the rule (frozen with the source in the snapshot).
 *   o1 — override / waive: owner and manager, worker 403; recorded as amounts_overridden with from / to; a save
 *        that changes nothing records nothing; refused (409) after approval and the row is unchanged.
 *   n1 — fee ≥ item value: allowed, due 0, quote says nothingOwed; closes as nothing_owed; nothing_owed refused for
 *        a request that is owed money.
 *   r1 — refund suggestion = frozen paid prices − fee (source 'frozen'), even after the rule changes; a request
 *        without amounts keeps today's suggestion.
 *   e1 — exchange: fee frozen as collect; the drawer shows it; nothing about booking changes (manual for generic).
 *   k1 — no rule on (or the kind's rule off): no fees key, quote {applies:false}, no amounts row, no amounts in detail.
 *   x1 — app_user: another tenant reads no amounts and can't override them; the same tenant can (positive control).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalFeesTest {

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

    static final String CAIRO = "F2-CAIRO", NASR = "F2-NASR", HELIO = "F2-HELIO", GIZA = "F2-GIZA";
    static final String FEES = "/api/v1/tenant/portal-settings/customer-fees";

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

    record T(UUID id, String slug, UUID store, UUID location, UUID m, UUID l, UUID worker,
             String ownerToken, String managerToken, String workerToken) {}
    record O(UUID id, String number, String phone) {}

    JdbcTemplate appJdbc;
    TransactionTemplate appTx;

    @BeforeAll
    void setup() {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, district_name, district_name_ar, " +
                    "pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'Nasr City', 'مدينة نصر', true, true), " +
                    "(?, ?, 'Cairo', 'القاهرة', 'Heliopolis', 'مصر الجديدة', true, true), " +
                    "(?, ?, 'Giza', 'الجيزة', 'Dokki', 'الدقي', true, true) ON CONFLICT DO NOTHING",
                    NASR, CAIRO, HELIO, CAIRO, "F2-DOKKI", GIZA);
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, priceGateway, tokenProvider, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-f2");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── c1: the calculator ───────────────────────────────────────────────────────

    @Test
    void c1_calculator() {
        Set<String> fault = Set.of("damaged", "wrong_item", "not_as_pictured");
        PortalAmounts.Terms fixed = terms("refund", "fixed", "75", null, null, null, true);
        List<PortalAmounts.Item> mixed = List.of(item("a", "500", "damaged"), item("b", "300", "wrong_size"));
        List<PortalAmounts.Item> allFault = List.of(item("a", "500", "damaged"), item("b", "300", "wrong_item"));

        PortalAmounts.Quote q = PortalAmounts.calculate(fixed, fault, mixed);
        assertThat(q.fee()).isEqualByComparingTo("75");
        assertThat(q.feeWaived()).isFalse();
        assertThat(q.itemValue()).isEqualByComparingTo("800");
        assertThat(q.refundDue()).isEqualByComparingTo("725");
        assertThat(q.collect()).isEqualByComparingTo("0");
        q = PortalAmounts.calculate(fixed, fault, allFault);
        assertThat(q.fee()).as("fixed: every item merchant-fault → waived").isEqualByComparingTo("0");
        assertThat(q.feeWaived()).isTrue();
        assertThat(q.waiveReason()).isEqualTo("merchant_fault");
        assertThat(q.refundDue()).isEqualByComparingTo("800");
        q = PortalAmounts.calculate(terms("refund", "fixed", "75", null, null, null, false), fault, allFault);
        assertThat(q.fee()).as("waiving switched off → charged").isEqualByComparingTo("75");

        // Percent: 10% of the non-fault item only (300 → 30), per item.
        PortalAmounts.Terms pct = terms("refund", "percent", null, "10", null, null, true);
        q = PortalAmounts.calculate(pct, fault, mixed);
        assertThat(q.fee()).isEqualByComparingTo("30");
        assertThat(q.lines().get(0).waived()).isTrue();
        assertThat(q.lines().get(1).waived()).isFalse();
        assertThat(PortalAmounts.calculate(pct, fault, allFault).feeWaived()).isTrue();
        // min / max clamp, whole EGP half up: 12.5% of 333 = 41.625 → 42; 10% of 25 = 2.5 → 3; min 50; max 40.
        assertThat(PortalAmounts.calculate(terms("refund", "percent", null, "12.5", null, null, true), fault,
            List.of(item("a", "333", "other"))).fee()).isEqualByComparingTo("42");
        assertThat(PortalAmounts.calculate(terms("refund", "percent", null, "10", null, null, true), fault,
            List.of(item("a", "25", "other"))).fee()).isEqualByComparingTo("3");
        assertThat(PortalAmounts.calculate(terms("refund", "percent", null, "10", "50", null, true), fault,
            List.of(item("a", "100", "other"))).fee()).isEqualByComparingTo("50");
        assertThat(PortalAmounts.calculate(terms("refund", "percent", null, "10", null, "40", true), fault,
            List.of(item("a", "1000", "other"))).fee()).isEqualByComparingTo("40");

        // Fee ≥ value → due 0 (nothing owed).
        q = PortalAmounts.calculate(terms("refund", "fixed", "1000", null, null, null, true), fault, List.of(item("a", "900", "other")));
        assertThat(q.refundDue()).isEqualByComparingTo("0");
        assertThat(q.nothingOwed()).isTrue();
        // Exchange: the customer owes the fee; no refund due.
        q = PortalAmounts.calculate(terms("exchange", "fixed", "40", null, null, null, true), fault, List.of(item("a", "900", "wrong_size")));
        assertThat(q.collect()).isEqualByComparingTo("40");
        assertThat(q.refundDue()).isNull();
        // An unpriced item counts 0 and makes the quote approximate.
        q = PortalAmounts.calculate(pct, fault, List.of(new PortalAmounts.Item("a", null, null, null, "none", "other")));
        assertThat(q.approximate()).isTrue();
        assertThat(q.fee()).isEqualByComparingTo("0");
    }

    // ── q1: the quote is what gets frozen ────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void q1_quoteEqualsFrozen_settingsChangeNeverRewrites() {
        T t = tenant();
        setRule(t, "refund", rule(true, "percent", null, "10", 20, null, true));
        O o = deliveredGeneric(t, "#9101", 2);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(lookup.get("fees")).isEqualTo(Map.of("refund", true, "exchange", false));

        Map<String, Object> body = Map.of("lines", List.of(Map.of("variantId", t.m().toString(), "quantity", 2, "reasonCode", "wrong_size")));
        ResponseEntity<Map> quote = quoteHttp(t, (String) lookup.get("token"), body);
        assertThat(quote.getStatusCode().value()).isEqualTo(200);
        assertThat(quote.getBody()).containsEntry("applies", true).containsEntry("itemValue", "1800.00")
            .containsEntry("fee", "180.00").containsEntry("refundDue", "1620.00").containsEntry("nothingOwed", false)
            .containsEntry("approximate", false);
        assertThat(portal.quote(t.slug(), "bad-token", new PortalService.SubmitRequest(List.of(line(t.m(), 2, "wrong_size")),
            null, null)).orElseThrow().outcome()).isEqualTo(PortalService.QuoteOutcome.UNAUTHORIZED);

        UUID request = submit(t, o, lookup, List.of(line(t.m(), 2, "wrong_size")), false);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM return_request_amounts WHERE request_id = ?", request);
        assertThat((BigDecimal) row.get("fee_amount")).isEqualByComparingTo("180");
        assertThat((BigDecimal) row.get("refund_due")).isEqualByComparingTo("1620");
        assertThat((BigDecimal) row.get("item_value")).isEqualByComparingTo("1800");
        assertThat(row.get("fee_kind")).isEqualTo("refund");
        assertThat(row.get("tenant_id")).isEqualTo(t.id());

        // The merchant changes the rule: the next quote follows, the frozen row never moves.
        setRule(t, "refund", rule(true, "fixed", 99, null, null, null, true));
        Map<String, Object> lookup2 = lookup(t, deliveredGeneric(t, "#9102", 1));
        assertThat(quoteHttp(t, (String) lookup2.get("token"), Map.of("lines",
            List.of(Map.of("variantId", t.m().toString(), "quantity", 1, "reasonCode", "wrong_size")))).getBody())
            .containsEntry("fee", "99.00");
        assertThat((BigDecimal) jdbc.queryForObject("SELECT fee_amount FROM return_request_amounts WHERE request_id = ?",
            BigDecimal.class, request)).isEqualByComparingTo("180");
        verifyNoInteractions(priceGateway);
    }

    // ── a1: area overrides ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void a1_districtBeatsCityBeatsRule() {
        T t = tenant();
        setRule(t, "refund", rule(true, "fixed", 100, null, null, null, true));
        jdbc.update("INSERT INTO portal_fee_area_overrides (tenant_id, kind, city_id, district_id, mode, amount) VALUES " +
            "(?, 'refund', ?, NULL, 'fixed', 30), (?, 'refund', ?, ?, 'fixed', 0)", t.id(), CAIRO, t.id(), CAIRO, NASR);

        UUID inNasr = submitBosta(t, "#9201", CAIRO, NASR);
        UUID inHelio = submitBosta(t, "#9202", CAIRO, HELIO);
        UUID inGiza = submitBosta(t, "#9203", GIZA, "F2-DOKKI");
        assertThat(fee(inNasr)).as("district override (0 = no fee there)").isEqualByComparingTo("0");
        assertThat(fee(inHelio)).as("city override").isEqualByComparingTo("30");
        assertThat(fee(inGiza)).as("the rule").isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT fee_rule_snapshot->>'source' FROM return_request_amounts WHERE request_id = ?",
            String.class, inNasr)).isEqualTo("district");
        assertThat(jdbc.queryForObject("SELECT fee_waived FROM return_request_amounts WHERE request_id = ?",
            Boolean.class, inNasr)).as("a 0 override is no fee, not a waiver").isFalse();
    }

    // ── o1: override / waive before approval ─────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void o1_overrideAndWaive_beforeApprovalOnly_recorded() {
        T t = tenant();
        setRule(t, "refund", rule(true, "fixed", 75, null, null, null, true));
        O o = deliveredGeneric(t, "#9301", 1);
        UUID request = submit(t, o, lookup(t, o), List.of(line(t.m(), 1, "wrong_size")), false);
        String path = "/api/v1/return-requests/" + request + "/amounts";

        assertThat(exchange(t.workerToken(), path, HttpMethod.PUT, Map.of("waived", true)).getStatusCode().value()).isEqualTo(403);
        assertThat(exchange(t.ownerToken(), path, HttpMethod.PUT, Map.of("fee", 12.5)).getStatusCode().value()).isEqualTo(400);
        assertThat(exchange(t.ownerToken(), path, HttpMethod.PUT, Map.of("waived", true)).getStatusCode().value()).isEqualTo(204);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM return_request_amounts WHERE request_id = ?", request);
        assertThat((BigDecimal) row.get("fee_amount")).isEqualByComparingTo("0");
        assertThat(row.get("waive_reason")).isEqualTo("merchant_override");
        assertThat((BigDecimal) row.get("refund_due")).isEqualByComparingTo("900");
        assertThat(row.get("overridden_by")).isNotNull();
        assertThat(exchange(t.ownerToken(), path, HttpMethod.PUT, Map.of("waived", true)).getStatusCode().value()).isEqualTo(204);
        assertThat(events(request, "amounts_overridden")).as("no change, no event").isEqualTo(1);

        assertThat(exchange(t.managerToken(), path, HttpMethod.PUT, Map.of("fee", 25)).getStatusCode().value()).isEqualTo(204);
        assertThat((BigDecimal) jdbc.queryForObject("SELECT refund_due FROM return_request_amounts WHERE request_id = ?",
            BigDecimal.class, request)).isEqualByComparingTo("875");
        Map<String, Object> meta = lastEvent(request, "amounts_overridden");
        assertThat((Map<String, Object>) meta.get("from")).containsEntry("fee", "0.00").containsEntry("waived", true);
        assertThat((Map<String, Object>) meta.get("to")).containsEntry("fee", "25.00").containsEntry("waived", false);

        Map<String, Object> detail = exchange(t.ownerToken(), "/api/v1/return-requests/" + request, HttpMethod.GET, null).getBody();
        Map<String, Object> amounts = (Map<String, Object>) detail.get("amounts");
        assertThat(amounts).containsEntry("fee", "25.00").containsEntry("calculatedFee", "75.00").containsEntry("canOverride", true)
            .containsEntry("refundDue", "875.00");

        approve(t, request);
        assertThat(exchange(t.ownerToken(), path, HttpMethod.PUT, Map.of("fee", 5)).getStatusCode().value()).isEqualTo(409);
        assertThat((BigDecimal) jdbc.queryForObject("SELECT fee_amount FROM return_request_amounts WHERE request_id = ?",
            BigDecimal.class, request)).isEqualByComparingTo("25");
        assertThat((Map<String, Object>) exchange(t.ownerToken(), "/api/v1/return-requests/" + request, HttpMethod.GET, null)
            .getBody().get("amounts")).containsEntry("canOverride", false);
    }

    // ── n1: nothing owed ─────────────────────────────────────────────────────────

    @Test
    void n1_feeAtLeastValue_dueZero_closesAsNothingOwed() {
        T t = tenant();
        setRule(t, "refund", rule(true, "fixed", 1000, null, null, null, true));
        O o = deliveredGeneric(t, "#9401", 1);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(quoteHttp(t, (String) lookup.get("token"), Map.of("lines",
            List.of(Map.of("variantId", t.m().toString(), "quantity", 1, "reasonCode", "changed_mind")))).getBody())
            .containsEntry("refundDue", "0.00").containsEntry("nothingOwed", true);
        UUID request = submit(t, o, lookup, List.of(line(t.m(), 1, "changed_mind")), false);
        approve(t, request);
        assertThat(close(t, request, "nothing_owed").getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT close_reason FROM return_requests WHERE id = ?", String.class, request))
            .isEqualTo("nothing_owed");

        // A request that IS owed money can't be closed as nothing owed.
        setRule(t, "refund", rule(true, "fixed", 50, null, null, null, true));
        O owed = deliveredGeneric(t, "#9402", 1);
        UUID other = submit(t, owed, lookup(t, owed), List.of(line(t.m(), 1, "changed_mind")), false);
        approve(t, other);
        assertThat(close(t, other, "nothing_owed").getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, other)).isEqualTo("approved");
    }

    // ── r1: the refund suggestion ────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void r1_suggestionIsFrozenPriceMinusFee() {
        T t = tenant();
        setRule(t, "refund", rule(true, "fixed", 75, null, null, null, true));
        O o = deliveredGeneric(t, "#9501", 1);
        UUID request = submit(t, o, lookup(t, o), List.of(line(t.m(), 1, "changed_mind")), false);
        setRule(t, "refund", rule(true, "fixed", 300, null, null, null, true));   // later: never applies to this one
        jdbc.update("UPDATE variants SET price = 5 WHERE id = ?", t.m());          // nor does today's price
        Map<String, Object> s = exchange(t.ownerToken(), "/api/v1/return-requests/" + request + "/refund-suggestion",
            HttpMethod.GET, null).getBody();
        assertThat(s).containsEntry("amount", "825.00").containsEntry("source", "frozen").containsEntry("fee", "75.00")
            .containsEntry("itemValue", "900.00").containsEntry("approximate", false);
        assertThat((List<Map<String, Object>>) s.get("lines")).singleElement()
            .satisfies(l -> assertThat(l).containsEntry("unitPrice", "900.00").containsEntry("quantity", 1));
        verifyNoInteractions(priceGateway);

        // No amounts (the rule was off at submit): today's suggestion, today's sources.
        setRule(t, "refund", rule(false, "fixed", 300, null, null, null, true));
        O plain = deliveredGeneric(t, "#9502", 1);
        UUID plainRequest = submit(t, plain, lookup(t, plain), List.of(line(t.m(), 1, "changed_mind")), false);
        Map<String, Object> s2 = exchange(t.ownerToken(), "/api/v1/return-requests/" + plainRequest + "/refund-suggestion",
            HttpMethod.GET, null).getBody();
        assertThat(s2.get("source")).isNotEqualTo("frozen");
        assertThat(s2).doesNotContainKeys("fee", "itemValue", "feeWaived");
    }

    // ── e1: exchange fee ─────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void e1_exchangeFeeFrozenAsCollect_instructionOnly() {
        T t = tenant();
        assertThat(exchange(t.ownerToken(), "/api/v1/tenant/portal-settings/other-couriers", HttpMethod.PUT, Map.of("enabled", true))
            .getStatusCode().value()).isEqualTo(200);
        assertThat(putPortalSettings(t, Map.of("exchangesEnabled", true)).getStatusCode().value()).isEqualTo(200);
        stockPiece(t, t.l());
        setRule(t, "exchange", rule(true, "fixed", 40, null, null, null, true));
        O o = deliveredGeneric(t, "#9601", 1);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(lookup.get("fees")).isEqualTo(Map.of("refund", false, "exchange", true));
        UUID request = submit(t, o, lookup, List.of(line(t.m(), 1, "wrong_size")), true);
        Map<String, Object> amounts = (Map<String, Object>) exchange(t.ownerToken(), "/api/v1/return-requests/" + request,
            HttpMethod.GET, null).getBody().get("amounts");
        assertThat(amounts).containsEntry("kind", "exchange").containsEntry("fee", "40.00").containsEntry("collect", "40.00")
            .containsEntry("refundDue", null);
        approve(t, request);
        assertThat(jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE id = ?", String.class, request))
            .as("booking unchanged: the generic exchange is still the merchant's to book").isEqualTo("manual");
        verifyNoInteractions(bostaV2);
    }

    // ── k1: fees off for this kind ───────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void k1_noRuleOn_nothingChanges() {
        T t = tenant();
        setRule(t, "refund", rule(false, "fixed", 75, null, null, null, true));   // saved but switched off
        O o = deliveredGeneric(t, "#9701", 1);
        Map<String, Object> lookup = lookup(t, o);
        assertThat(lookup).doesNotContainKey("fees");
        assertThat(quoteHttp(t, (String) lookup.get("token"), Map.of("lines",
            List.of(Map.of("variantId", t.m().toString(), "quantity", 1, "reasonCode", "wrong_size")))).getBody())
            .isEqualTo(Map.of("applies", false));
        UUID request = submit(t, o, lookup, List.of(line(t.m(), 1, "wrong_size")), false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE request_id = ?", Integer.class, request)).isZero();
        assertThat(exchange(t.ownerToken(), "/api/v1/return-requests/" + request, HttpMethod.GET, null).getBody())
            .doesNotContainKey("amounts");
        verifyNoInteractions(priceGateway);
    }

    // ── x1: app_user isolation ───────────────────────────────────────────────────

    @Test
    void x1_appUser_otherTenantSeesNothing_sameTenantPositiveControl() {
        T a = tenant(), b = tenant();
        setRule(a, "refund", rule(true, "fixed", 75, null, null, null, true));
        O o = deliveredGeneric(a, "#9801", 1);
        UUID request = submit(a, o, lookup(a, o), List.of(line(a.m(), 1, "wrong_size")), false);

        // Another tenant: no row, and an override finds nothing to change.
        Boolean otherSees = TenantContext.runAs(b.id(), () -> appTx.execute(s ->
            new RequestAmounts(appJdbc).row(b.id(), request).isPresent()));
        assertThat(otherSees).isFalse();
        Integer otherRows = TenantContext.runAs(b.id(), () -> appTx.execute(s ->
            appJdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE request_id = ?", Integer.class, request)));
        assertThat(otherRows).isZero();
        assertThatThrownBy(() -> TenantContext.runAs(b.id(), () -> appTx.execute(s -> {
            new RequestAmounts(appJdbc).override(b.id(), request, true, null, UUID.randomUUID());
            return null;
        }))).hasMessageContaining("404");
        Integer otherUpdated = TenantContext.runAs(b.id(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE return_request_amounts SET fee_amount = 1 WHERE request_id = ?", request)));
        assertThat(otherUpdated).isZero();
        assertThat((BigDecimal) jdbc.queryForObject("SELECT fee_amount FROM return_request_amounts WHERE request_id = ?",
            BigDecimal.class, request)).isEqualByComparingTo("75");

        // Positive control: the same tenant, same connection, reads and overrides it.
        Boolean ownSees = TenantContext.runAs(a.id(), () -> appTx.execute(s ->
            new RequestAmounts(appJdbc).row(a.id(), request).isPresent()));
        assertThat(ownSees).isTrue();
        UUID ownerA = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'owner'", UUID.class, a.id());
        TenantContext.runAs(a.id(), () -> appTx.execute(s -> {
            new RequestAmounts(appJdbc).override(a.id(), request, true, null, ownerA);
            return null;
        }));
        assertThat((BigDecimal) jdbc.queryForObject("SELECT fee_amount FROM return_request_amounts WHERE request_id = ?",
            BigDecimal.class, request)).isEqualByComparingTo("0");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private static PortalAmounts.Terms terms(String kind, String mode, String amount, String percent, String min, String max,
                                             boolean waive) {
        return new PortalAmounts.Terms(kind, mode, amount == null ? null : new BigDecimal(amount),
            percent == null ? null : new BigDecimal(percent), min == null ? null : new BigDecimal(min),
            max == null ? null : new BigDecimal(max), waive, "rule", null, null);
    }

    private static PortalAmounts.Item item(String key, String price, String reason) {
        return new PortalAmounts.Item(key, UUID.randomUUID(), null, new BigDecimal(price), "stored", reason);
    }

    private static PortalService.SubmitLine line(UUID variant, int qty, String reason) {
        return new PortalService.SubmitLine(variant, qty, reason);
    }

    private BigDecimal fee(UUID request) {
        return jdbc.queryForObject("SELECT fee_amount FROM return_request_amounts WHERE request_id = ?", BigDecimal.class, request);
    }

    private int events(UUID request, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM return_request_events WHERE request_id = ? AND event_type = ?",
            Integer.class, request, type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastEvent(UUID request, String type) {
        String json = jdbc.queryForObject("SELECT metadata::text FROM return_request_events WHERE request_id = ? AND event_type = ? " +
            "ORDER BY occurred_at DESC, id DESC LIMIT 1", String.class, request, type);
        try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void setRule(T t, String kind, Map<String, Object> rule) {
        assertThat(exchange(t.ownerToken(), FEES + "/rules/" + kind, HttpMethod.PUT, rule).getStatusCode().value()).isEqualTo(200);
    }

    private static Map<String, Object> rule(Boolean enabled, String mode, Integer amount, String percent, Integer min, Integer max,
                                            Boolean waive) {
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", enabled);
        m.put("mode", mode);
        m.put("amount", amount);
        m.put("percent", percent == null ? null : new BigDecimal(percent));
        m.put("minAmount", min);
        m.put("maxAmount", max);
        m.put("waiveMerchantFault", waive);
        return m;
    }

    private void approve(T t, UUID request) {
        assertThat(exchange(t.ownerToken(), "/api/v1/return-requests/" + request + "/approve", HttpMethod.POST, null)
            .getStatusCode().is2xxSuccessful()).isTrue();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> close(T t, UUID request, String reason) {
        return exchange(t.ownerToken(), "/api/v1/return-requests/" + request + "/close", HttpMethod.POST, Map.of("reason", reason));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> quoteHttp(T t, String token, Object body) {
        return exchange(token, "/api/v1/portal/" + t.slug() + "/quote", HttpMethod.POST, body);
    }

    private T tenant() {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "f2fees-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled, portal_generic_returns_enabled) " +
            "VALUES (?, ?, ?, true, true, true)", id, "F2 fees " + n, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID owner = user(id, "owner"), manager = user(id, "manager"), worker = user(id, "worker");
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug);
        UUID m = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price) " +
            "VALUES (?, ?, ?, 'SH-M', 'Sand / M', 900) RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-m");
        UUID l = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title, price) " +
            "VALUES (?, ?, ?, 'SH-L', 'Sand / L', 1100) RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-l");
        return new T(id, slug, store, location, m, l, worker, jwt.issueAccessToken(owner, id, "owner"),
            jwt.issueAccessToken(manager, id, "manager"), jwt.issueAccessToken(worker, id, "worker"));
    }

    private UUID user(UUID tenant, String role) {
        return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id",
            UUID.class, tenant, role, role + "-" + UUID.randomUUID() + "@test.com", role);
    }

    private static String phone(String number) { return "0105556" + number.substring(1); }

    /** {@code qty} units of variant M at EGP 900 each (REST price stored), shipped with another courier, delivered. */
    private O deliveredGeneric(T t, String number, int qty) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone(number));
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id", UUID.class, t.id(), order, t.m(), qty,
            "{\"price\":\"900.00\",\"quantity\":" + qty + "}");
        for (int i = 0; i < qty; i++) {
            String piece = piece(t, t.m(), "with_courier", order);
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
                t.id(), item, piece);
        }
        String tracking = "AR-" + SEQ.incrementAndGet() + number.substring(1);
        UUID leg = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, internal_state, " +
            "    shipment_leg, source, handed_over_at, created_at) " +
            "VALUES (?, ?, 'generic', ?, ?, 'Aramex', 'with_courier', 'forward', 'shopify_fulfillment', now() - interval '6 days', " +
            "    now() - interval '7 days') RETURNING id",
            UUID.class, t.id(), order, tracking, tracking.replace("-", ""));
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at, source) " +
            "VALUES (?, ?, 'with_courier', now() - interval '6 days', 'pickup_scan')", t.id(), leg);
        assertThat(rest.exchange("http://localhost:" + port + "/api/v1/generic-shipments/mark-delivered", HttpMethod.POST,
            new HttpEntity<>(Map.of("orderIds", List.of(order)), headers(t.ownerToken())), Object.class)
            .getStatusCode().is2xxSuccessful()).isTrue();
        return new O(order, number, phone(number));
    }

    /**
     * One unit of M (EGP 900) delivered by Bosta to {@code cityId} / {@code districtId} (the leg's stored drop-off; the
     * store doesn't book pickups, so the fee's area is the delivery area), submitted as a refund. Returns the request.
     */
    private UUID submitBosta(T t, String number, String cityId, String districtId) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at) VALUES (?, ?, ?, ?, 'delivered'::order_status, 'Mona Samir', ?, 900, 'cod', " +
            "    now() - interval '8 days') RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone(number));
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, 1, '{\"price\":\"900.00\",\"quantity\":1}'::jsonb) RETURNING id", UUID.class, t.id(), order, t.m());
        String piece = piece(t, t.m(), "delivered", order);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.id(), item, piece);
        String tracking = String.valueOf(5_500_000_000L + SEQ.incrementAndGet() * 7L);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at, " +
            "    created_at, raw) VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', now() - interval '3 days', " +
            "    now() - interval '7 days', ?::jsonb)",
            t.id(), order, tracking, "{\"dropOffAddress\":{\"city\":{\"_id\":\"" + cityId + "\"},\"district\":{\"_id\":\"" + districtId + "\"}}}");
        O o = new O(order, number, phone(number));
        return submit(t, o, lookup(t, o), List.of(line(t.m(), 1, "changed_mind")), false);
    }

    /** Returns the new piece's id. */
    private String piece(T t, UUID variant, String status, UUID order) {
        String id = UlidGenerator.generate();
        String code = "Q" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), variant, "PC-" + id, code, status, order, order == null ? t.location() : null, t.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
        return id;
    }

    private void stockPiece(T t, UUID variant) { piece(t, variant, "available", null); }

    private Map<String, Object> lookup(T t, O o) {
        PortalService.LookupResult r = portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return r.body();
    }

    private UUID submit(T t, O o, Map<String, Object> lookup, List<PortalService.SubmitLine> lines, boolean exchange) {
        PortalService.SubmitResult r = portal.submit(t.slug(), (String) lookup.get("token"), new PortalService.SubmitRequest(
            lines, null, null, null, exchange ? "exchange" : null, exchange ? t.l() : null, exchange ? false : null)).orElseThrow();
        assertThat(r.outcome()).as(String.valueOf(r.body())).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE tenant_id = ? AND reference = ?", UUID.class,
            t.id(), r.body().get("reference"));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> putPortalSettings(T t, Map<String, Object> changes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", t.slug());
        body.put("enabled", true);
        body.put("autoApprove", false);
        body.put("returnWindowDays", 14);
        body.putAll(changes);
        return exchange(t.ownerToken(), "/api/v1/tenant/portal-settings", HttpMethod.PUT, body);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> exchange(String token, String path, HttpMethod method, Object body) {
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers(token)), Map.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
