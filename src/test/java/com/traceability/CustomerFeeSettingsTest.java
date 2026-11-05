package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.CustomerFeeSettingsService;
import com.traceability.portal.PortalService;
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
 * Returns portal fees F1 — Settings → Returns portal → Customer fees + Exchange pricing (V210). Data only.
 *
 *   s1 — defaults: no rules, the three merchant-fault reasons, no overrides, exchange pricing all off.
 *   s2 — fee rules: fixed / percent saved; every invalid shape refused (400, nothing written); one audit row per
 *        real change, none for a save that changes nothing.
 *   s3 — merchant-fault reasons: saved in the portal's order; an unknown code refused; audit like s2.
 *   s4 — exchange pricing: saved; an invalid max difference refused; audit like s2.
 *   s5 — area overrides: city and district; a duplicate area 409; an unknown area / a district of another city 400;
 *        update and delete (404 once gone); one audit row each.
 *   s6 — roles: a manager reads but can't write (403); a worker can't read (403).
 *   d1 — the DB refuses what the API refuses (shape, min > max, a fraction of a pound, an unknown reason).
 *   d2 — return_request_amounts on app_user: only the override columns are writable, only while the request is
 *        'requested'; never deleted; another tenant sees and changes nothing (same-tenant positive control).
 *   x1 — app_user: the service on another tenant reads none of this tenant's rules / overrides / settings; the
 *        same tenant on the same connection reads its own (positive control).
 *   g1 — GOLDEN, settings inert: with every new setting switched on, the portal config, lookup and submit
 *        (refund and exchange) answer exactly as with everything off, and no amounts row is written.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerFeeSettingsTest {

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

    static final String CAIRO = "F1-CAIRO", NASR = "F1-NASR", GIZA = "F1-GIZA", DOKKI = "F1-DOKKI";
    static final String BASE = "/api/v1/tenant/portal-settings/customer-fees";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired PortalService portal;
    @Autowired ObjectMapper mapper;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  BostaV2Client bostaV2;

    static final AtomicInteger SEQ = new AtomicInteger();

    record T(UUID id, String slug, UUID store, UUID location, UUID m, UUID l, UUID worker,
             String ownerToken, String managerToken, String workerToken) {}
    record O(UUID id, String number, String piece, String phone) {}

    JdbcTemplate appJdbc;
    TransactionTemplate appTx;
    CustomerFeeSettingsService appFees;

    @BeforeAll
    void setup() {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, district_name, district_name_ar, " +
                    "pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'Nasr City', 'مدينة نصر', true, true), " +
                    "(?, ?, 'Giza', 'الجيزة', 'Dokki', 'الدقي', true, true) ON CONFLICT DO NOTHING",
                    NASR, CAIRO, DOKKI, GIZA);
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(appDs));
        appFees = new CustomerFeeSettingsService(appJdbc, new AuditService(appJdbc, mapper), true);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-f1");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── s: the settings API ──────────────────────────────────────────────────────

    @Test
    void s1_defaults() {
        T t = tenant();
        Map<String, Object> s = get(t, BASE).getBody();
        assertThat(s).containsEntry("visible", true);
        assertThat((Map<String, Object>) s.get("rules")).containsEntry("exchange", null).containsEntry("refund", null);
        assertThat((List<Object>) s.get("merchantFaultReasons")).containsExactly("damaged", "not_as_pictured", "wrong_item");
        assertThat((List<?>) s.get("overrides")).isEmpty();
        assertThat((Map<String, Object>) s.get("exchangePricing")).containsEntry("anyProduct", false)
            .containsEntry("sameOrLowerOnly", false).containsEntry("maxDifference", null);
    }

    @Test
    void s2_rules_savedValidatedAudited() {
        T t = tenant();
        ResponseEntity<Map> fixed = put(t, BASE + "/rules/refund", rule(true, "fixed", 50, null, null, null, true));
        assertThat(fixed.getStatusCode().value()).as(String.valueOf(fixed.getBody())).isEqualTo(200);
        assertThat(ruleOf(fixed.getBody(), "refund")).containsEntry("enabled", true).containsEntry("mode", "fixed")
            .containsEntry("amount", 50.0).containsEntry("percent", null).containsEntry("waiveMerchantFault", true);
        assertThat(audits(t, "portal_fee_rule_update")).isEqualTo(1);

        assertThat(put(t, BASE + "/rules/refund", rule(true, "fixed", 50, null, null, null, true)).getStatusCode().value())
            .isEqualTo(200);
        assertThat(audits(t, "portal_fee_rule_update")).as("a save that changes nothing writes no audit row").isEqualTo(1);

        ResponseEntity<Map> pct = put(t, BASE + "/rules/exchange", rule(false, "percent", null, "12.5", 20, 300, false));
        assertThat(pct.getStatusCode().value()).isEqualTo(200);
        assertThat(ruleOf(pct.getBody(), "exchange")).containsEntry("mode", "percent").containsEntry("percent", 12.5)
            .containsEntry("minAmount", 20.0).containsEntry("maxAmount", 300.0).containsEntry("amount", null)
            .containsEntry("waiveMerchantFault", false).containsEntry("enabled", false);
        assertThat(audits(t, "portal_fee_rule_update")).isEqualTo(2);
        Map<String, Object> meta = lastAudit(t, "portal_fee_rule_update");
        assertThat(meta).containsEntry("kind", "exchange").containsEntry("from", null);
        assertThat((Map<String, Object>) meta.get("to")).containsEntry("mode", "percent");

        // Every refusal is a 400 that writes nothing.
        record Bad(String kind, Map<String, Object> body, String code) {}
        for (Bad b : List.of(
                new Bad("refund", rule(true, "fixed", 0, null, null, null, true), "FEE_AMOUNT_INVALID"),
                new Bad("refund", rule(true, "fixed", null, null, null, null, true), "FEE_AMOUNT_INVALID"),
                new Bad("refund", Map.of("enabled", true, "mode", "fixed", "amount", 12.5), "FEE_AMOUNT_INVALID"),
                new Bad("refund", rule(true, "fixed", 30001, null, null, null, true), "FEE_AMOUNT_INVALID"),
                new Bad("refund", rule(true, "percent", null, "0", null, null, true), "FEE_PERCENT_INVALID"),
                new Bad("refund", rule(true, "percent", null, "100.01", null, null, true), "FEE_PERCENT_INVALID"),
                new Bad("refund", rule(true, "percent", null, "10.123", null, null, true), "FEE_PERCENT_INVALID"),
                new Bad("refund", rule(true, "percent", null, "10", 300, 20, true), "FEE_MIN_MAX_INVALID"),
                new Bad("refund", rule(true, "flat", 10, null, null, null, true), "FEE_MODE_INVALID"),
                new Bad("shipping", rule(true, "fixed", 10, null, null, null, true), "FEE_KIND_INVALID"))) {
            ResponseEntity<Map> r = put(t, BASE + "/rules/" + b.kind(), b.body());
            assertThat(r.getStatusCode().value()).as(b.toString()).isEqualTo(400);
            assertThat(r.getBody()).as(b.toString()).containsEntry("code", b.code());
        }
        assertThat(ruleOf(get(t, BASE).getBody(), "refund")).containsEntry("amount", 50.0);
        assertThat(audits(t, "portal_fee_rule_update")).isEqualTo(2);
    }

    @Test
    void s3_merchantFaultReasons() {
        T t = tenant();
        ResponseEntity<Map> r = put(t, BASE + "/merchant-fault-reasons", Map.of("reasons", List.of("wrong_item", "damaged", "damaged")));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat((List<Object>) r.getBody().get("merchantFaultReasons")).containsExactly("damaged", "wrong_item");
        assertThat(audits(t, "portal_merchant_fault_reasons_update")).isEqualTo(1);
        put(t, BASE + "/merchant-fault-reasons", Map.of("reasons", List.of("damaged", "wrong_item")));
        assertThat(audits(t, "portal_merchant_fault_reasons_update")).isEqualTo(1);
        assertThat(put(t, BASE + "/merchant-fault-reasons", Map.of("reasons", List.of())).getStatusCode().value()).isEqualTo(200);
        assertThat(audits(t, "portal_merchant_fault_reasons_update")).isEqualTo(2);

        ResponseEntity<Map> bad = put(t, BASE + "/merchant-fault-reasons", Map.of("reasons", List.of("late_delivery")));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(bad.getBody()).containsEntry("code", "FEE_REASON_INVALID");
        assertThat((List<Object>) get(t, BASE).getBody().get("merchantFaultReasons")).isEmpty();
    }

    @Test
    void s4_exchangePricing() {
        T t = tenant();
        Map<String, Object> on = new HashMap<>(Map.of("anyProduct", true, "sameOrLowerOnly", true, "maxDifference", 500));
        ResponseEntity<Map> r = put(t, BASE + "/exchange-pricing", on);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String, Object>) r.getBody().get("exchangePricing")).containsEntry("anyProduct", true)
            .containsEntry("sameOrLowerOnly", true).containsEntry("maxDifference", 500.0);
        put(t, BASE + "/exchange-pricing", on);
        assertThat(audits(t, "portal_exchange_pricing_update")).isEqualTo(1);

        for (Object bad : List.of(-1, 12.5, 30001)) {
            Map<String, Object> b = new HashMap<>(on);
            b.put("maxDifference", bad);
            ResponseEntity<Map> rr = put(t, BASE + "/exchange-pricing", b);
            assertThat(rr.getStatusCode().value()).as(String.valueOf(bad)).isEqualTo(400);
            assertThat(rr.getBody()).containsEntry("code", "MAX_DIFFERENCE_INVALID");
        }
        Map<String, Object> off = new HashMap<>();
        off.put("anyProduct", false);
        off.put("sameOrLowerOnly", false);
        off.put("maxDifference", null);
        assertThat((Map<String, Object>) put(t, BASE + "/exchange-pricing", off).getBody().get("exchangePricing"))
            .containsEntry("anyProduct", false).containsEntry("maxDifference", null);
        assertThat(audits(t, "portal_exchange_pricing_update")).isEqualTo(2);
    }

    @Test
    void s5_areaOverrides() {
        T t = tenant();
        ResponseEntity<Map> city = post(t, BASE + "/area-overrides",
            Map.of("kind", "refund", "cityId", CAIRO, "mode", "fixed", "amount", 0));
        assertThat(city.getStatusCode().value()).as(String.valueOf(city.getBody())).isEqualTo(200);
        ResponseEntity<Map> district = post(t, BASE + "/area-overrides",
            Map.of("kind", "refund", "cityId", CAIRO, "districtId", NASR, "mode", "percent", "percent", 5, "maxAmount", 40));
        assertThat(district.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> ov = (List<Map<String, Object>>) district.getBody().get("overrides");
        assertThat(ov).hasSize(2);
        assertThat(ov.get(0)).containsEntry("cityName", "Cairo").containsEntry("districtId", null).containsEntry("amount", 0.0);
        assertThat(ov.get(1)).containsEntry("districtName", "Nasr City").containsEntry("districtNameAr", "مدينة نصر")
            .containsEntry("percent", 5.0).containsEntry("maxAmount", 40.0);
        assertThat(audits(t, "portal_fee_override_create")).isEqualTo(2);

        ResponseEntity<Map> dup = post(t, BASE + "/area-overrides", Map.of("kind", "refund", "cityId", CAIRO, "mode", "fixed", "amount", 9));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(dup.getBody()).containsEntry("code", "FEE_OVERRIDE_EXISTS");
        assertThat(post(t, BASE + "/area-overrides", Map.of("kind", "exchange", "cityId", CAIRO, "mode", "fixed", "amount", 9))
            .getStatusCode().value()).as("same area, other kind").isEqualTo(200);
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("kind", "refund", "cityId", "NO-SUCH-CITY", "mode", "fixed", "amount", 9),
                Map.<String, Object>of("kind", "refund", "cityId", GIZA, "districtId", NASR, "mode", "fixed", "amount", 9),
                Map.<String, Object>of("kind", "refund", "mode", "fixed", "amount", 9))) {
            ResponseEntity<Map> r = post(t, BASE + "/area-overrides", bad);
            assertThat(r.getStatusCode().value()).as(bad.toString()).isEqualTo(400);
            assertThat(r.getBody()).containsEntry("code", "FEE_AREA_UNKNOWN");
        }

        String id = (String) ov.get(0).get("id");
        ResponseEntity<Map> upd = put(t, BASE + "/area-overrides/" + id, Map.of("mode", "fixed", "amount", 15));
        assertThat(upd.getStatusCode().value()).isEqualTo(200);
        assertThat(audits(t, "portal_fee_override_update")).isEqualTo(1);
        put(t, BASE + "/area-overrides/" + id, Map.of("mode", "fixed", "amount", 15));
        assertThat(audits(t, "portal_fee_override_update")).as("unchanged → no audit").isEqualTo(1);

        assertThat(exchange(t.ownerToken(), BASE + "/area-overrides/" + id, HttpMethod.DELETE, null).getStatusCode().value()).isEqualTo(200);
        assertThat(audits(t, "portal_fee_override_delete")).isEqualTo(1);
        ResponseEntity<Map> gone = exchange(t.ownerToken(), BASE + "/area-overrides/" + id, HttpMethod.DELETE, null);
        assertThat(gone.getStatusCode().value()).isEqualTo(404);
        assertThat(gone.getBody()).containsEntry("code", "FEE_OVERRIDE_NOT_FOUND");

        List<Map<String, Object>> cities = (List<Map<String, Object>>) (List<?>) exchangeList(t.ownerToken(), BASE + "/areas");
        assertThat(cities).extracting(c -> c.get("id")).contains(CAIRO, GIZA);
        List<Map<String, Object>> districts = (List<Map<String, Object>>) (List<?>) exchangeList(t.ownerToken(), BASE + "/areas?cityId=" + GIZA);
        assertThat(districts).extracting(c -> c.get("id")).containsExactly(DOKKI);
    }

    @Test
    void s6_roles() {
        T t = tenant();
        assertThat(exchange(t.managerToken(), BASE, HttpMethod.GET, null).getStatusCode().value()).isEqualTo(200);
        for (var w : List.of(
                Map.entry("/rules/refund", (Object) rule(true, "fixed", 10, null, null, null, true)),
                Map.entry("/merchant-fault-reasons", (Object) Map.of("reasons", List.of())),
                Map.entry("/exchange-pricing", (Object) Map.of("anyProduct", true)))) {
            assertThat(exchange(t.managerToken(), BASE + w.getKey(), HttpMethod.PUT, w.getValue()).getStatusCode().value())
                .as("manager PUT " + w.getKey()).isEqualTo(403);
        }
        assertThat(exchange(t.managerToken(), BASE + "/area-overrides", HttpMethod.POST,
            Map.of("kind", "refund", "cityId", CAIRO, "mode", "fixed", "amount", 1)).getStatusCode().value()).isEqualTo(403);
        assertThat(exchange(t.workerToken(), BASE, HttpMethod.GET, null).getStatusCode().value()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_fee_rules WHERE tenant_id = ?", Integer.class, t.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portal_fee_area_overrides WHERE tenant_id = ?", Integer.class, t.id())).isZero();
    }

    // ── d: the database ──────────────────────────────────────────────────────────

    @Test
    void d1_dbRefusesBadShapes() {
        T t = tenant();
        for (String values : List.of(
                "'refund', true, 'fixed', NULL, 10, NULL, NULL",      // fixed without an amount
                "'refund', true, 'fixed', 10, 5, NULL, NULL",         // fixed with a percent
                "'refund', true, 'percent', NULL, 5, 30, 20",          // min > max
                "'refund', true, 'fixed', 10.5, NULL, NULL, NULL",    // a fraction of a pound
                "'refund', true, 'percent', NULL, 0, NULL, NULL",     // a rule's percent must be above 0
                "'refund', true, 'fixed', 30001, NULL, NULL, NULL")) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount, percent, " +
                "min_amount, max_amount) VALUES (?, " + values + ")", t.id())).as(values)
                .hasMessageContaining("violates check constraint");
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE tenants SET portal_merchant_fault_reasons = ARRAY['late']::text[] WHERE id = ?", t.id()))
            .hasMessageContaining("violates check constraint");
        assertThatThrownBy(() -> jdbc.update("UPDATE tenants SET portal_exchange_max_difference = 10.5 WHERE id = ?", t.id()))
            .hasMessageContaining("violates check constraint");
        jdbc.update("INSERT INTO portal_fee_area_overrides (tenant_id, kind, city_id, mode, amount) VALUES (?, 'refund', ?, 'fixed', 0)", t.id(), CAIRO);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO portal_fee_area_overrides (tenant_id, kind, city_id, mode, amount) " +
            "VALUES (?, 'refund', ?, 'fixed', 3)", t.id(), CAIRO)).hasMessageContaining("portal_fee_area_overrides_area_uq");
    }

    @Test
    void d2_amounts_overrideColumnsOnly_beforeApprovalOnly_appUser() {
        T a = tenant(), b = tenant();
        O o = deliveredGeneric(a, "#8101");
        UUID rr = jdbc.queryForObject("INSERT INTO return_requests (tenant_id, order_id, type, status, reference) " +
            "VALUES (?, ?, 'refund', 'requested', ?) RETURNING id", UUID.class, a.id(), o.id(), "RR-FDZ" + "23456789ABCDEFGH".charAt(SEQ.incrementAndGet() % 16));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO return_request_amounts (request_id, tenant_id, item_value) VALUES (?, ?, 500)",
            rr, b.id())).as("the request's tenant only").hasMessageContaining("is not this tenant's");
        jdbc.update("INSERT INTO return_request_amounts (request_id, tenant_id, item_value, fee_kind, fee_amount, refund_due) " +
            "VALUES (?, ?, 500, 'refund', 50, 450)", rr, a.id());

        // Same tenant, still 'requested': the override columns change.
        UUID owner = jdbc.queryForObject("SELECT id FROM users WHERE tenant_id = ? AND role = 'owner'", UUID.class, a.id());
        int n = TenantContext.runAs(a.id(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE return_request_amounts SET fee_amount = 0, fee_waived = true, waive_reason = 'merchant_override', " +
            "refund_due = 500, overridden_at = now(), overridden_by = ? WHERE request_id = ?", owner, rr)));
        assertThat(n).as("positive control: own tenant, override columns, before approval").isEqualTo(1);

        // Another tenant sees and changes nothing.
        Integer seen = TenantContext.runAs(b.id(), () -> appTx.execute(s -> appJdbc.queryForObject(
            "SELECT count(*) FROM return_request_amounts WHERE request_id = ?", Integer.class, rr)));
        assertThat(seen).isZero();
        Integer changed = TenantContext.runAs(b.id(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE return_request_amounts SET fee_amount = 1 WHERE request_id = ?", rr)));
        assertThat(changed).isZero();

        // Never a frozen column, never a DELETE.
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE return_request_amounts SET item_value = 1 WHERE request_id = ?", rr)))).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appTx.execute(s -> appJdbc.update(
            "DELETE FROM return_request_amounts WHERE request_id = ?", rr)))).rootCause().hasMessageContaining("permission denied");

        // Decided: frozen, even the override columns.
        jdbc.update("UPDATE return_requests SET status = 'approved' WHERE id = ?", rr);
        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appTx.execute(s -> appJdbc.update(
            "UPDATE return_request_amounts SET fee_amount = 10 WHERE request_id = ?", rr)))).rootCause().hasMessageContaining("frozen once the request is decided");
        assertThat(jdbc.queryForObject("SELECT fee_amount FROM return_request_amounts WHERE request_id = ?", BigDecimal.class, rr))
            .isEqualByComparingTo("0");
    }

    @Test
    void x1_appUser_crossTenantSeesNothing_sameTenantPositiveControl() {
        T a = tenant(), b = tenant();
        jdbc.update("INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount) VALUES (?, 'refund', true, 'fixed', 70)", a.id());
        jdbc.update("INSERT INTO portal_fee_area_overrides (tenant_id, kind, city_id, mode, amount) VALUES (?, 'refund', ?, 'fixed', 5)", a.id(), CAIRO);
        jdbc.update("UPDATE tenants SET portal_exchange_any_product = true, portal_merchant_fault_reasons = ARRAY['other']::text[] WHERE id = ?", a.id());

        CustomerFeeSettingsService.Settings theirs = TenantContext.runAs(b.id(), () -> appTx.execute(s -> appFees.get()));
        assertThat(theirs.rules().values()).allMatch(Objects::isNull);
        assertThat(theirs.overrides()).isEmpty();
        assertThat(theirs.exchangePricing().anyProduct()).isFalse();
        assertThat(theirs.merchantFaultReasons()).containsExactly("damaged", "not_as_pictured", "wrong_item");
        // A write as tenant B never reaches tenant A's override.
        UUID ov = jdbc.queryForObject("SELECT id FROM portal_fee_area_overrides WHERE tenant_id = ?", UUID.class, a.id());
        assertThatThrownBy(() -> TenantContext.runAs(b.id(), () -> appTx.execute(s -> appFees.deleteOverride(null, ov))))
            .hasMessageContaining("FEE_OVERRIDE_NOT_FOUND");
        assertThatThrownBy(() -> TenantContext.runAs(b.id(), () -> appTx.execute(s -> appJdbc.update(
            "INSERT INTO portal_fee_rules (tenant_id, kind, enabled, mode, amount) VALUES (?, 'exchange', true, 'fixed', 1)", a.id()))))
            .rootCause().hasMessageContaining("row-level security");

        CustomerFeeSettingsService.Settings own = TenantContext.runAs(a.id(), () -> appTx.execute(s -> appFees.get()));
        assertThat(own.rules().get("refund")).as("positive control: tenant A on the same app_user connection").isNotNull();
        assertThat(own.rules().get("refund").amount()).isEqualByComparingTo("70");
        assertThat(own.overrides()).singleElement().satisfies(o -> assertThat(o.cityName()).isEqualTo("Cairo"));
        assertThat(own.exchangePricing().anyProduct()).isTrue();
        assertThat(own.merchantFaultReasons()).containsExactly("other");
    }

    // ── g: golden — the settings change nothing in F1 ─────────────────────────────

    @Test
    void g1_everySettingOn_portalAnswersExactlyAsOff_noAmountsWritten() {
        T t = tenant();
        assertThat(exchange(t.ownerToken(), "/api/v1/tenant/portal-settings/other-couriers", HttpMethod.PUT, Map.of("enabled", true))
            .getStatusCode().value()).isEqualTo(200);
        assertThat(putPortalSettings(t, Map.of("exchangesEnabled", true)).getStatusCode().value()).isEqualTo(200);
        stockPiece(t, t.l());
        stockPiece(t, t.l());

        // Everything off (the defaults).
        // (read after a generic order exists: the public config's otherCouriers depends on one being there)
        O refundOff = deliveredGeneric(t, "#8201"), exchangeOff = deliveredGeneric(t, "#8202");
        O probe = deliveredGeneric(t, "#8203");
        Map<String, Object> probeOff = new TreeMap<>(lookup(t, probe));
        probeOff.remove("token");
        Map<String, Object> configOff = portal.config(t.slug()).orElseThrow();
        Map<String, Object> settingsOff = getPortalSettings(t);
        Map<String, Object> lookupOff = lookup(t, refundOff);
        Object submitRefundOff = norm(submit(t, refundOff, lookupOff, false), refundOff);
        Object submitExchangeOff = norm(submit(t, exchangeOff, lookup(t, exchangeOff), true), exchangeOff);

        // Every new setting on, through the real endpoints.
        assertThat(put(t, BASE + "/rules/refund", rule(true, "fixed", 75, null, null, null, false)).getStatusCode().value()).isEqualTo(200);
        assertThat(put(t, BASE + "/rules/exchange", rule(true, "percent", null, "20", 10, 500, false)).getStatusCode().value()).isEqualTo(200);
        assertThat(put(t, BASE + "/merchant-fault-reasons", Map.of("reasons", List.of("wrong_size", "changed_mind"))).getStatusCode().value()).isEqualTo(200);
        assertThat(put(t, BASE + "/exchange-pricing", Map.of("anyProduct", true, "sameOrLowerOnly", true, "maxDifference", 0))
            .getStatusCode().value()).isEqualTo(200);
        assertThat(post(t, BASE + "/area-overrides", Map.of("kind", "refund", "cityId", CAIRO, "mode", "fixed", "amount", 200))
            .getStatusCode().value()).isEqualTo(200);

        assertThat(portal.config(t.slug()).orElseThrow()).as("public config").isEqualTo(configOff);
        Map<String, Object> probeOn = new TreeMap<>(lookup(t, probe));
        probeOn.remove("token");
        assertThat(probeOn).as("the same order looked up again, every setting on — exact").isEqualTo(probeOff);
        assertThat(getPortalSettings(t)).as("merchant portal settings").isEqualTo(settingsOff);
        O refundOn = deliveredGeneric(t, "#8301"), exchangeOn = deliveredGeneric(t, "#8302");
        Map<String, Object> lookupOn = lookup(t, refundOn);
        assertThat(norm(lookupOn, refundOn)).as("lookup").isEqualTo(norm(lookupOff, refundOff));
        assertThat(norm(submit(t, refundOn, lookupOn, false), refundOn)).as("refund submit").isEqualTo(submitRefundOff);
        assertThat(norm(submit(t, exchangeOn, lookup(t, exchangeOn), true), exchangeOn)).as("exchange submit").isEqualTo(submitExchangeOff);

        assertThat(jdbc.queryForList(
            "SELECT type, status::text, refund_fallback_ok, booking_status FROM return_requests WHERE tenant_id = ? " +
            "AND order_id IN (?, ?) ORDER BY type", t.id(), refundOn.id(), exchangeOn.id()))
            .isEqualTo(jdbc.queryForList(
                "SELECT type, status::text, refund_fallback_ok, booking_status FROM return_requests WHERE tenant_id = ? " +
                "AND order_id IN (?, ?) ORDER BY type", t.id(), refundOff.id(), exchangeOff.id()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_request_amounts WHERE tenant_id = ?", Integer.class, t.id()))
            .as("nothing writes frozen amounts in F1").isZero();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private T tenant() {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "f1fees-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled) VALUES (?, ?, ?, true, true)", id, "F1 fees " + n, slug);
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

    private static String phone(String number) { return "0105555" + number.substring(1); }

    /** A generic order handed over at Pickups, then marked delivered by the owner (the real endpoint). */
    private O deliveredGeneric(T t, String number) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone(number));
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, 1, '{\"price\":\"900.00\",\"quantity\":1}'::jsonb) RETURNING id", UUID.class, t.id(), order, t.m());
        String code = piece(t, t.m(), "with_courier", order);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.id(), item, jdbc.queryForObject("SELECT id FROM pieces WHERE short_code = ?", String.class, code));
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
        return new O(order, number, code, phone(number));
    }

    private String piece(T t, UUID variant, String status, UUID order) {
        String id = UlidGenerator.generate();
        String code = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), variant, "PC-" + id, code, status, order, order == null ? t.location() : null, t.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
        return code;
    }

    private void stockPiece(T t, UUID variant) { piece(t, variant, "available", null); }

    private Map<String, Object> lookup(T t, O o) {
        PortalService.LookupResult r = portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return r.body();
    }

    private Map<String, Object> submit(T t, O o, Map<String, Object> lookup, boolean exchange) {
        PortalService.SubmitResult r = portal.submit(t.slug(), (String) lookup.get("token"), new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.m(), 1, exchange ? "wrong_size" : "damaged")), null, null, null,
            exchange ? "exchange" : null, exchange ? t.l() : null, exchange ? false : null)).orElseThrow();
        assertThat(r.outcome()).as(String.valueOf(r.body())).isEqualTo(PortalService.SubmitOutcome.CREATED);
        return r.body();
    }

    /** The response with this order's own identity (ids, number, tokens, references, piece codes) blanked. */
    private Object norm(Object v, O o) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k),
                "token".equals(k) ? "<token>" : "deliveredAt".equals(k) ? "<time>" : norm(val, o)));
            return out;
        }
        if (v instanceof List<?> l) return l.stream().map(x -> norm(x, o)).toList();
        if (v instanceof UUID) return "<uuid>";
        if (v instanceof String s) {
            if (s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return "<uuid>";
            if (s.matches("RR-[A-Z0-9]+")) return "<ref>";
            return s.replace(o.number(), "<number>").replace(o.number().substring(1), "<number>")
                    .replace(o.piece(), "<piece>").replace(o.phone(), "<phone>");
        }
        return v;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ruleOf(Map<String, Object> settings, String kind) {
        return (Map<String, Object>) ((Map<String, Object>) settings.get("rules")).get(kind);
    }

    private int audits(T t, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Integer.class, t.id(), action);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastAudit(T t, String action) {
        String json = jdbc.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = ? " +
            "ORDER BY created_at DESC LIMIT 1", String.class, t.id(), action);
        try { return mapper.readValue(json, Map.class); } catch (Exception e) { throw new IllegalStateException(e); }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPortalSettings(T t) {
        return exchange(t.ownerToken(), "/api/v1/tenant/portal-settings", HttpMethod.GET, null).getBody();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> get(T t, String path) { return exchange(t.ownerToken(), path, HttpMethod.GET, null); }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> put(T t, String path, Object body) { return exchange(t.ownerToken(), path, HttpMethod.PUT, body); }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(T t, String path, Object body) { return exchange(t.ownerToken(), path, HttpMethod.POST, body); }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> exchange(String token, String path, HttpMethod method, Object body) {
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers(token)), Map.class);
    }

    private List<?> exchangeList(String token, String path) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(null, headers(token)), List.class).getBody();
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
