package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.RefundSuggestionService;
import com.traceability.portal.ReturnRequestService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Returns Step 4d-2 — refunds recorded against a return request, the suggested amount, the two
 * alerts, the exact per-leg displays, roles and cross-tenant isolation.
 *
 * G* suggestion (Shopify live → stored REST payload → catalog; Shopify errors never surface),
 * R* refunds (record / validation / void once / totals / mark-refunded / events), A* append-only,
 * O* alerts, D* displays (listCrpReturns + parcel cards), W* roles over HTTP, X* app_user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnRefundsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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

    private static final String REF_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String SHOP = "refunds-4d2.myshopify.com";

    @LocalServerPort int port;
    @Autowired TestRestTemplate        rest;
    @Autowired JdbcTemplate            jdbc;
    @Autowired PasswordEncoder         passwordEncoder;
    @Autowired ObjectMapper            mapper;
    @Autowired ReturnRequestService    requests;
    @Autowired RefundSuggestionService suggestions;
    @Autowired ReturnSessionService    sessions;
    @Autowired ShipmentLinkService     links;
    @Autowired ExceptionService        exceptions;

    @MockBean ShopifyOrderPriceGateway shopifyPrices;
    @MockBean ShopifyTokenProvider     tokenProvider;
    @MockBean JobScheduler             jobScheduler;

    UUID tenantId, storeId, shirt, pants, locationId, ownerId, workerId;
    UUID tenantB, storeB, variantB, ownerB;
    String ownerToken, workerToken;

    ReturnRequestService    appUserRequests;
    RefundSuggestionService appUserSuggestions;
    JdbcTemplate            appUserJdbc;
    TransactionTemplate     appUserTx;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID(); locationId = UUID.randomUUID();
        ownerId = UUID.randomUUID(); workerId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Refunds Tenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Mohamed A.', ?, ?, 'owner', true)",
            ownerId, tenantId, "owner-" + ownerId + "@refunds.test", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Operator', ?, ?, 'worker', true)",
            workerId, tenantId, "worker-" + workerId + "@refunds.test", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'Returns Bay')", locationId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'connected')",
            storeId, tenantId, SHOP);
        shirt = variant(tenantId, storeId, "Linen Shirt", "White / M", "gid://shopify/ProductVariant/4401", "950.00");
        pants = variant(tenantId, storeId, "Flipped Pants", "Black / XL", "gid://shopify/ProductVariant/4402", "400.00");

        tenantB = UUID.randomUUID(); storeB = UUID.randomUUID(); ownerB = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Refunds Tenant B')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner B', ?, 'x', 'owner', true)",
            ownerB, tenantB, "owner-" + ownerB + "@refunds-b.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'refunds-b.myshopify.com', 'disconnected')",
            storeB, tenantB);
        variantB = variant(tenantB, storeB, "Tote", "Black", "gid://shopify/ProductVariant/9901", "300.00");

        ownerToken  = login(ownerId);
        workerToken = login(workerId);

        DataSource appUserDs = new TenantAwareDataSource(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserJdbc        = new JdbcTemplate(appUserDs);
        appUserRequests    = new ReturnRequestService(appUserJdbc);
        appUserSuggestions = new RefundSuggestionService(appUserJdbc, shopifyPrices, tokenProvider);
        appUserTx          = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @BeforeEach
    void setCtx() {
        TenantContext.set(tenantId);
        reset(shopifyPrices, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat_test");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID t : List.of(tenantId, tenantB)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", t);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
        }
        jdbc.update("UPDATE stores SET status = 'connected' WHERE id = ?", storeId);
    }

    // ── G: suggested amount ─────────────────────────────────────────────────────

    /** The stored Shopify REST payload of #1047: shirt 950 − 100 discount, pants 2 × 400 − 200 discount. */
    private static final String REST_RAW = """
        {"id": 5501047, "name": "#1047", "currency": "EGP", "total_discounts": "300.00",
         "line_items": [
           {"id": 11, "variant_id": 4401, "quantity": 1, "price": "950.00",
            "discount_allocations": [{"amount": "100.00", "discount_application_index": 0}]},
           {"id": 12, "variant_id": 4402, "quantity": 2, "price": "400.00",
            "discount_allocations": [{"amount": "150.00", "discount_application_index": 0},
                                     {"amount": "50.00",  "discount_application_index": 1}]}],
         "shipping_lines": [{"price": "60.00"}]}
        """;

    /** What the GraphQL import stores: no line prices. */
    private static final String GRAPHQL_RAW = """
        {"id": "gid://shopify/Order/5501047", "name": "#1047",
         "lineItems": {"edges": [{"node": {"id": "gid://shopify/LineItem/11", "quantity": 1,
                                           "variant": {"id": "gid://shopify/ProductVariant/4401"}}}]},
         "currentTotalPriceSet": {"shopMoney": {"amount": "1510.00"}}}
        """;

    @Test
    @SuppressWarnings("unchecked")
    void g1_shopifyLive_unitPriceAfterAllDiscounts_timesReturnedQuantity() {
        Req r = request("refund_pending", REST_RAW, new String[]{"done", "done", "done"}, shirt, pants, pants);
        when(shopifyPrices.fetchOrderPrices(eq(SHOP), eq("shpat_test"), eq(r.orderGid())))
            .thenReturn(new ShopifyOrderPriceGateway.OrderPrices("EGP", List.of(
                new ShopifyOrderPriceGateway.LinePrice("gid://shopify/ProductVariant/4401", 1, new BigDecimal("850.0")),
                new ShopifyOrderPriceGateway.LinePrice("gid://shopify/ProductVariant/4402", 2, new BigDecimal("300.0")))));

        Map<String, Object> s = suggestions.suggest(r.id());

        assertThat(s.get("source")).isEqualTo("shopify");
        assertThat(s.get("approximate")).isEqualTo(false);
        assertThat(s.get("currency")).isEqualTo("EGP");
        assertThat(s.get("amount")).isEqualTo("1450.00");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) s.get("lines");
        assertThat(lines).extracting(l -> l.get("productTitle") + "=" + l.get("quantity") + "x" + l.get("unitPrice") + "=" + l.get("lineTotal"))
            .containsExactly("Flipped Pants=2x300.00=600.00", "Linen Shirt=1x850.00=850.00");
    }

    @Test
    void g2_shopifyUnreachable_fallsBackToTheStoredRestPayload() {
        Req r = request("received", REST_RAW, new String[]{"arrived", "arrived", "arrived"}, shirt, pants, pants);
        when(shopifyPrices.fetchOrderPrices(anyString(), anyString(), anyString()))
            .thenThrow(new ShopifyException("Shopify GraphQL HTTP 503"));

        Map<String, Object> s = suggestions.suggest(r.id());

        // shirt 950 − 100 = 850; pants 400 − (150 + 50) / 2 = 300 → 850 + 2 × 300. Shipping excluded.
        assertThat(s.get("source")).isEqualTo("stored_order");
        assertThat(s.get("approximate")).isEqualTo(false);
        assertThat(s.get("amount")).isEqualTo("1450.00");
    }

    @Test
    void g3_noPricesStored_catalogPrice_flaggedApproximate() {
        Req r = request("received", GRAPHQL_RAW, new String[]{"arrived", "arrived"}, shirt, pants);
        when(tokenProvider.getValidToken(any())).thenThrow(new IllegalStateException("store needs re-auth"));

        Map<String, Object> s = suggestions.suggest(r.id());

        assertThat(s.get("source")).isEqualTo("catalog");
        assertThat(s.get("approximate")).isEqualTo(true);
        assertThat(s.get("amount")).isEqualTo("1350.00");
        assertThat(s.get("currency")).isEqualTo("EGP");
    }

    @Test
    void g4_shopifyErrorsNeverSurface_disconnectedStoreSkipsShopify_noPriceAnywhere_emptyAmount() {
        Req r = request("received", REST_RAW, new String[]{"arrived"}, shirt);
        when(shopifyPrices.fetchOrderPrices(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("reset"));
        assertThat(suggestions.suggest(r.id()).get("source")).isEqualTo("stored_order");

        jdbc.update("UPDATE stores SET status = 'disconnected' WHERE id = ?", storeId);
        reset(shopifyPrices);
        assertThat(suggestions.suggest(r.id()).get("source")).isEqualTo("stored_order");
        verifyNoInteractions(shopifyPrices);

        UUID priceless = variant(tenantId, storeId, "Gift Card", "EGP 500", "gid://shopify/ProductVariant/4499", null);
        Req r2 = request("received", GRAPHQL_RAW, new String[]{"arrived"}, priceless);
        Map<String, Object> s = suggestions.suggest(r2.id());
        assertThat(s.get("amount")).isNull();
        assertThat(s.get("source")).isNull();
    }

    // ── R: recording refunds ────────────────────────────────────────────────────

    @Test
    void r1_recordInReceivedAndRefundPending_rejectedElsewhere() {
        for (String status : List.of("received", "refund_pending")) {
            Req r = request(status, REST_RAW, new String[]{"arrived"}, shirt);
            UUID id = requests.recordRefund(r.id(), "instapay", new BigDecimal("850"), today(), "88431207", null, ownerId);
            Map<String, Object> row = jdbc.queryForMap("SELECT kind, method, amount, currency, reference, recorded_by FROM return_refunds WHERE id = ?", id);
            assertThat(row.get("kind")).isEqualTo("refund");
            assertThat(row.get("amount")).isEqualTo(new BigDecimal("850.00"));
            assertThat(row.get("currency")).isEqualTo("EGP");
            assertThat(row.get("recorded_by")).isEqualTo(ownerId);
            assertThat(eventTypes(r.id())).endsWith("refund_recorded");
        }
        for (String status : List.of("approved", "pickup_booked", "requested", "closed", "refunded", "rejected")) {
            Req r = request(status, REST_RAW, new String[]{"awaiting"}, shirt);
            assertStatus(409, () -> requests.recordRefund(r.id(), "cash", BigDecimal.TEN, today(), null, null, ownerId));
        }
    }

    @Test
    void r2_validation() {
        Req r = request("refund_pending", REST_RAW, new String[]{"done"}, shirt);
        assertStatus(400, () -> requests.recordRefund(r.id(), "cash", BigDecimal.ZERO, today(), null, null, ownerId));
        assertStatus(400, () -> requests.recordRefund(r.id(), "cash", new BigDecimal("-5"), today(), null, null, ownerId));
        assertStatus(400, () -> requests.recordRefund(r.id(), "cash", new BigDecimal("1.005"), today(), null, null, ownerId));
        assertStatus(400, () -> requests.recordRefund(r.id(), "cash", null, today(), null, null, ownerId));
        assertStatus(400, () -> requests.recordRefund(r.id(), "cash", BigDecimal.TEN, today().plusDays(1), null, null, ownerId));
        assertStatus(400, () -> requests.recordRefund(r.id(), "cash", BigDecimal.TEN, null, null, null, ownerId));
        assertStatus(400, () -> requests.recordRefund(r.id(), "cheque", BigDecimal.TEN, today(), null, null, ownerId));
        assertStatus(400, () -> requests.recordRefund(r.id(), "cash", BigDecimal.TEN, today(), "x".repeat(101), null, ownerId));
        assertStatus(400, () -> requests.recordRefund(r.id(), "cash", BigDecimal.TEN, today(), null, "x".repeat(301), ownerId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_refunds WHERE request_id = ?", Integer.class, r.id())).isZero();
        // Boundaries accepted.
        requests.recordRefund(r.id(), "bank_transfer", new BigDecimal("0.01"), today().minusDays(3), "x".repeat(100), "y".repeat(300), ownerId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void r3_voidOnce_totalsExcludeVoided_detailShowsVoidState() {
        Req r = request("refund_pending", REST_RAW, new String[]{"done"}, shirt);
        UUID a = requests.recordRefund(r.id(), "cash", new BigDecimal("500"), today(), null, null, ownerId);
        requests.recordRefund(r.id(), "wallet", new BigDecimal("350.50"), today(), null, null, ownerId);

        requests.voidRefund(r.id(), a, "Typed the wrong amount", ownerId);
        assertStatus(409, () -> requests.voidRefund(r.id(), a, null, ownerId));
        assertStatus(404, () -> requests.voidRefund(r.id(), UUID.randomUUID(), null, ownerId));

        Map<String, Object> d = requests.detail(r.id());
        assertThat(d.get("refundTotal")).isEqualTo("350.50");
        List<Map<String, Object>> refunds = (List<Map<String, Object>>) d.get("refunds");
        assertThat(refunds).extracting(x -> x.get("amount") + ":" + x.get("voided")).containsExactly("500.00:true", "350.50:false");
        assertThat(refunds.get(0).get("voidNote")).isEqualTo("Typed the wrong amount");
        assertThat(refunds.get(0).get("recordedByName")).isEqualTo("Mohamed A.");
        List<Map<String, Object>> history = (List<Map<String, Object>>) d.get("history");
        assertThat(history).extracting(e -> e.get("type")).startsWith("refund_voided", "refund_recorded", "refund_recorded");
        assertThat(history.get(0).get("actorName")).isEqualTo("Mohamed A.");
        assertThat(eventTypes(r.id())).containsSubsequence("refund_recorded", "refund_recorded", "refund_voided");
    }

    @Test
    void r4_markRefunded_rules_andEvent() {
        Req received = request("received", REST_RAW, new String[]{"arrived"}, shirt);
        requests.recordRefund(received.id(), "cash", BigDecimal.TEN, today(), null, null, ownerId);
        assertStatus(409, () -> requests.markRefunded(received.id(), ownerId));

        Req none = request("refund_pending", REST_RAW, new String[]{"done"}, shirt);
        assertStatus(409, () -> requests.markRefunded(none.id(), ownerId));
        UUID only = requests.recordRefund(none.id(), "cash", BigDecimal.TEN, today(), null, null, ownerId);
        requests.voidRefund(none.id(), only, null, ownerId);
        assertStatus(409, () -> requests.markRefunded(none.id(), ownerId));

        requests.recordRefund(none.id(), "instapay", new BigDecimal("850"), today(), null, null, ownerId);
        requests.markRefunded(none.id(), ownerId);
        Map<String, Object> row = jdbc.queryForMap("SELECT status::text AS status, refunded_at, refunded_by FROM return_requests WHERE id = ?", none.id());
        assertThat(row.get("status")).isEqualTo("refunded");
        assertThat(row.get("refunded_at")).isNotNull();
        assertThat(row.get("refunded_by")).isEqualTo(ownerId);
        assertThat(eventTypes(none.id())).endsWith("refunded");
        assertStatus(409, () -> requests.markRefunded(none.id(), ownerId));
        assertStatus(409, () -> requests.recordRefund(none.id(), "cash", BigDecimal.ONE, today(), null, null, ownerId));
        Map<String, Object> listed = listRow(none.id());
        assertThat(listed.get("refundTotal")).isEqualTo("850.00");
        assertThat(listed.get("status")).isEqualTo("refunded");
    }

    // ── A: append-only ──────────────────────────────────────────────────────────

    @Test
    void a1_noCodePathUpdatesOrDeletesARefundRow_andTheDatabaseRefusesIt() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String src = Files.readString(p).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
                    return src.contains("UPDATE RETURN_REFUNDS") || src.contains("DELETE FROM RETURN_REFUNDS");
                } catch (IOException e) { throw new RuntimeException(e); }
            }).map(Path::toString).toList();
            assertThat(offenders).as("no UPDATE / DELETE of return_refunds in application code").isEmpty();
        }
        Req r = request("refund_pending", REST_RAW, new String[]{"done"}, shirt);
        requests.recordRefund(r.id(), "cash", BigDecimal.TEN, today(), null, null, ownerId);
        assertThatThrownBy(() -> asA(() -> appUserJdbc.update("UPDATE return_refunds SET amount = 1")))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asA(() -> appUserJdbc.update("DELETE FROM return_refunds")))
            .isInstanceOf(DataAccessException.class);
    }

    // ── O: alerts ───────────────────────────────────────────────────────────────

    @Test
    void o1_refundPendingOverdue_firesAfterTheWindow_notBefore_andEndsWhenRefunded() {
        Req late = request("refund_pending", REST_RAW, new String[]{"done"}, shirt);
        Req fresh = request("refund_pending", REST_RAW, new String[]{"done"}, pants);
        jdbc.update("UPDATE return_requests SET refund_pending_at = now() - interval '6 days' WHERE id = ?", late.id());
        jdbc.update("UPDATE return_requests SET refund_pending_at = now() - interval '4 days' WHERE id = ?", fresh.id());

        List<Map<String, Object>> ex = alerts("refund_pending_overdue");
        assertThat(ex).extracting(e -> e.get("request_id")).containsExactly(late.id());
        assertThat(ex.get(0).get("severity")).isEqualTo("HIGH");
        assertThat((String) ex.get(0).get("descriptionEn")).contains(late.reference()).contains(late.number()).contains("6 days");
        assertThat((String) ex.get(0).get("descriptionAr")).contains(late.reference()).contains(late.number());
        assertThat(ex.get(0).get("actionUrl")).isEqualTo("/exchanges?tab=requests&request=" + late.id());
        assertThat(listRow(late.id()).get("refundOverdueDays")).isEqualTo(6);
        assertThat(listRow(fresh.id()).get("refundOverdueDays")).isNull();

        requests.recordRefund(late.id(), "cash", BigDecimal.TEN, today(), null, null, ownerId);
        requests.markRefunded(late.id(), ownerId);
        assertThat(alerts("refund_pending_overdue")).isEmpty();
    }

    @Test
    void o2_returnItemsOverdue_fromBookingOrApproval_endsWhenTheRestIsNotComing() {
        Req approvedLate = request("approved", REST_RAW, new String[]{"awaiting"}, shirt);
        Req approvedFresh = request("approved", REST_RAW, new String[]{"awaiting"}, shirt);
        Req bookedLate = request("pickup_booked", REST_RAW, new String[]{"arrived", "awaiting"}, shirt, pants);
        Req bookedRecently = request("pickup_booked", REST_RAW, new String[]{"awaiting"}, shirt);
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '11 days' WHERE id = ?", approvedLate.id());
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '9 days' WHERE id = ?", approvedFresh.id());
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '20 days' WHERE id IN (?, ?)", bookedLate.id(), bookedRecently.id());
        event(bookedLate.id(), "pickup_booked", "now() - interval '11 days'");
        event(bookedRecently.id(), "pickup_booked", "now() - interval '2 days'");

        List<Map<String, Object>> ex = alerts("return_items_overdue");
        assertThat(ex).extracting(e -> e.get("request_id")).containsExactlyInAnyOrder(approvedLate.id(), bookedLate.id());
        assertThat(ex.get(0).get("severity")).isEqualTo("MEDIUM");
        Map<String, Object> booked = ex.stream().filter(e -> bookedLate.id().equals(e.get("request_id"))).findFirst().orElseThrow();
        assertThat((String) booked.get("descriptionEn")).contains(bookedLate.reference()).contains("1 item hasn't come back yet");

        requests.restNotComing(bookedLate.id(), ownerId);
        assertThat(alerts("return_items_overdue")).extracting(e -> e.get("request_id")).containsExactly(approvedLate.id());
    }

    // ── D: exact per-return displays ────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void d1_requestLinkedLeg_countsAndParcelCardAreExact_legWithoutRequestUnchanged() {
        // One order, three delivered pieces: two in request RA (linked to leg A), one not requested.
        Req ra = request("pickup_booked", REST_RAW, new String[]{"awaiting", "awaiting"}, shirt, pants);
        String other = piece(ra.orderId(), shirt);
        String legA = returnLeg(ra.orderId()), legB = returnLeg(ra.orderId());
        jdbc.update("UPDATE return_requests SET return_shipment_id = (SELECT id FROM shipments WHERE tracking_number = ?), " +
                    "link_source = 'merchant_selected' WHERE id = ?", legA, ra.id());

        UUID s = sessions.createSession(null, ownerId);
        sessions.scan(s, legA, locationId, ownerId);
        sessions.scan(s, legB, locationId, ownerId);
        sessions.scan(s, "PC-" + ra.pieces().get(0), locationId, ownerId);   // RA's shirt — attributed
        sessions.scan(s, "PC-" + other, locationId, ownerId);                 // not in any request

        Map<String, Object> view = sessions.getSession(s);
        List<Map<String, Object>> parcels = (List<Map<String, Object>>) view.get("parcels");
        Map<String, Object> cardA = parcels.stream().filter(p -> legA.equals(p.get("awb"))).findFirst().orElseThrow();
        Map<String, Object> cardB = parcels.stream().filter(p -> legB.equals(p.get("awb"))).findFirst().orElseThrow();
        assertThat(cardA.get("requestReference")).isEqualTo(ra.reference());
        assertThat((List<Map<String, Object>>) cardA.get("scannedItems")).extracting(i -> i.get("piece_id"))
            .containsExactly(ra.pieces().get(0));
        assertThat((List<Map<String, Object>>) cardA.get("expectedPieces")).extracting(i -> i.get("id"))
            .containsExactly(ra.pieces().get(1));
        assertThat(cardB.get("requestReference")).isNull();
        assertThat((List<Map<String, Object>>) cardB.get("scannedItems")).extracting(i -> i.get("piece_id"))
            .containsExactly(other);
        assertThat(view.toString()).as("no money on the worker's session view").doesNotContain("amount").doesNotContain("refund");

        Map<String, Map<String, Object>> legs = new HashMap<>();
        for (Map<String, Object> row : links.listCrpReturns(0, 100)) legs.put((String) row.get("tracking_number"), row);
        assertThat(legs.get(legA).get("request_reference")).isEqualTo(ra.reference());
        // Leg A: exactly RA's one arrived item. Leg B (no request): today's order-level count (2 at inspection).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pieces WHERE current_order_id = ? AND status = 'return_pending_inspection'",
            Integer.class, ra.orderId())).isEqualTo(2);
        TenantContext.set(tenantId);
        assertThat(pendingCount(legA)).isEqualTo(1);
        assertThat(pendingCount(legB)).isEqualTo(2);
    }

    // ── W: roles over HTTP ──────────────────────────────────────────────────────

    @Test
    void w1_workersGet403OnEveryRefundEndpoint_ownerSucceeds() {
        Req r = request("refund_pending", REST_RAW, new String[]{"done"}, shirt);
        UUID refund = requests.recordRefund(r.id(), "cash", BigDecimal.TEN, today(), null, null, ownerId);
        String base = "/api/v1/return-requests/" + r.id();
        Map<String, Object> body = Map.of("method", "cash", "amount", 20, "refundedOn", today().toString());

        assertThat(call(HttpMethod.GET, base + "/refund-suggestion", null, workerToken).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, base + "/refunds", body, workerToken).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, base + "/refunds/" + refund + "/void", Map.of(), workerToken).getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, base + "/mark-refunded", null, workerToken).getStatusCode().value()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_refunds WHERE request_id = ?", Integer.class, r.id())).isEqualTo(1);

        assertThat(call(HttpMethod.GET, base + "/refund-suggestion", null, ownerToken).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> created = call(HttpMethod.POST, base + "/refunds", body, ownerToken);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).containsKey("id");
        assertThat(call(HttpMethod.POST, base + "/refunds", Map.of("method", "cash", "amount", 0, "refundedOn", today().toString()),
            ownerToken).getStatusCode().value()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, base + "/refunds/" + refund + "/void", Map.of("note", "wrong"), ownerToken).getStatusCode().value()).isEqualTo(204);
        assertThat(call(HttpMethod.POST, base + "/mark-refunded", null, ownerToken).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void w2_workerSessionDetailCarriesNoAmounts() {
        Req r = request("pickup_booked", REST_RAW, new String[]{"awaiting", "awaiting"}, shirt, pants);
        String leg = returnLeg(r.orderId());
        jdbc.update("UPDATE return_requests SET return_shipment_id = (SELECT id FROM shipments WHERE tracking_number = ?) WHERE id = ?", leg, r.id());
        UUID s = sessions.createSession(null, workerId);
        sessions.scan(s, leg, locationId, workerId);
        sessions.scan(s, "PC-" + r.pieces().get(0), locationId, workerId);
        jdbc.update("INSERT INTO return_refunds (tenant_id, request_id, kind, method, amount, refunded_on) VALUES (?, ?, 'refund', 'cash', 777.77, current_date)",
            tenantId, r.id());

        ResponseEntity<String> resp = rest.exchange(base() + "/api/v1/returns/sessions/" + s, HttpMethod.GET,
            new HttpEntity<>(auth(workerToken)), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains(r.reference()).doesNotContain("777.77").doesNotContain("\"amount\"")
            .doesNotContain("refundTotal").doesNotContain("\"price\"");
    }

    // ── X: cross-tenant on a real app_user connection ─────────────────────────

    @Test
    void x1_refundVoidMarkRefundedSuggestion_crossTenant404_sameTenantControlSucceeds() {
        Req a = request("refund_pending", REST_RAW, new String[]{"done"}, shirt);
        UUID bRequest = bRequest();
        UUID bRefund = jdbc.queryForObject(
            "INSERT INTO return_refunds (tenant_id, request_id, kind, method, amount, refunded_on) VALUES (?, ?, 'refund', 'cash', 50, current_date) RETURNING id",
            UUID.class, tenantB, bRequest);
        TenantContext.clear();
        when(shopifyPrices.fetchOrderPrices(anyString(), anyString(), anyString())).thenThrow(new ShopifyException("down"));

        assertStatusAsA(404, () -> appUserRequests.recordRefund(bRequest, "cash", BigDecimal.TEN, today(), null, null, ownerId));
        assertStatusAsA(404, () -> appUserRequests.voidRefund(bRequest, bRefund, null, ownerId));
        assertStatusAsA(404, () -> appUserRequests.voidRefund(a.id(), bRefund, null, ownerId));
        assertStatusAsA(404, () -> appUserRequests.markRefunded(bRequest, ownerId));
        assertStatusAsA(404, () -> appUserSuggestions.suggest(bRequest));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_refunds WHERE request_id = ?", Integer.class, bRequest)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, bRequest)).isEqualTo("refund_pending");

        UUID mine = asA(() -> appUserRequests.recordRefund(a.id(), "cash", new BigDecimal("850"), today(), null, null, ownerId));
        asA(() -> { appUserRequests.voidRefund(a.id(), mine, null, ownerId); return null; });
        asA(() -> appUserRequests.recordRefund(a.id(), "instapay", new BigDecimal("850"), today(), null, null, ownerId));
        asA(() -> { appUserRequests.markRefunded(a.id(), ownerId); return null; });
        assertThat(asA(() -> appUserSuggestions.suggest(a.id())).get("source")).isEqualTo("stored_order");
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, a.id())).isEqualTo("refunded");
        List<UUID> visible = asA(() -> appUserJdbc.queryForList("SELECT request_id FROM return_refunds", UUID.class));
        assertThat(visible).isNotEmpty().allMatch(a.id()::equals);
        Integer none = appUserTx.execute(st -> appUserJdbc.queryForObject("SELECT COUNT(*) FROM return_refunds", Integer.class));
        assertThat(none).as("no tenant context → no rows").isZero();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    record Req(UUID id, String reference, UUID orderId, String number, String orderGid, List<String> pieces) {}

    private static LocalDate today() { return LocalDate.now(ZoneId.of("Africa/Cairo")); }

    private UUID variant(UUID tenant, UUID store, String product, String title, String gid, String price) {
        UUID p = UUID.randomUUID(), v = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            p, tenant, store, "P-" + p, product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, price) VALUES (?, ?, ?, ?, ?, ?, ?::numeric)",
            v, tenant, p, gid, title, "SKU-" + v.toString().substring(0, 6), price);
        return v;
    }

    /** A request in {@code status} on a new delivered order, one item per variant with the given item_status. */
    private Req request(String status, String raw, String[] itemStatuses, UUID... variants) {
        String number = "#" + ThreadLocalRandom.current().nextInt(10_000, 99_999);
        String gid = "gid://shopify/Order/" + ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name, raw) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mariam Saleh', ?::jsonb) RETURNING id",
            UUID.class, tenantId, storeId, gid, number, raw);
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '1 day')",
                    tenantId, order, tracking());
        String ref = reference();
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, decided_at, refund_pending_at, received_at) " +
            "VALUES (?, ?, ?::return_request_status, ?, now(), " +
            "        CASE WHEN ? = 'refund_pending' THEN now() END, CASE WHEN ? IN ('received', 'refund_pending') THEN now() END) RETURNING id",
            UUID.class, tenantId, order, status, ref, status, status);
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < variants.length; i++) {
            String p = piece(order, variants[i]);
            String st = itemStatuses[i];
            boolean active = st.equals("awaiting") || st.equals("arrived");
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, item_status, active) " +
                        "VALUES (?, ?, ?, ?, 'wrong_size', ?, ?)", tenantId, id, p, variants[i], st, active);
            pieces.add(p);
        }
        return new Req(id, ref, order, number, gid, pieces);
    }

    private String piece(UUID order, UUID variant) {
        String id = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                    id, tenantId, variant, "PC-" + id, id, order);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)", item, tenantId, order, variant);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')", tenantId, item, id);
        return id;
    }

    private String returnLeg(UUID order) {
        String tn = tracking();
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                    "VALUES (?, ?, 'bosta', ?, 'returning'::shipment_internal_state, 'return')", tenantId, order, tn);
        return tn;
    }

    private UUID bRequest() {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, '#9001', 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, tenantB, storeB, "gid://shopify/Order/" + UUID.randomUUID());
        return jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, refund_pending_at) " +
            "VALUES (?, ?, 'refund_pending', ?, now()) RETURNING id", UUID.class, tenantB, order, reference());
    }

    private void event(UUID requestId, String type, String occurredAtSql) {
        jdbc.update("INSERT INTO return_request_events (tenant_id, request_id, event_type, occurred_at) VALUES (?, ?, ?, " + occurredAtSql + ")",
            tenantId, requestId, type);
    }

    private static String tracking() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    private static String reference() {
        StringBuilder ref = new StringBuilder("RR-");
        for (int i = 0; i < 6; i++) ref.append(REF_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(REF_ALPHABET.length())));
        return ref.toString();
    }

    private List<String> eventTypes(UUID requestId) {
        return jdbc.queryForList("SELECT event_type FROM return_request_events WHERE request_id = ? ORDER BY occurred_at, id",
            String.class, requestId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listRow(UUID requestId) {
        return ((List<Map<String, Object>>) requests.list(null, 0, 100).get("items")).stream()
            .filter(r -> requestId.toString().equals(r.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> alerts(String type) {
        return (List<Map<String, Object>>) exceptions.listExceptions(type, null, 0, 100).get("items");
    }

    private Object pendingCount(String tracking) {
        return links.listCrpReturns(0, 100).stream().filter(r -> tracking.equals(r.get("tracking_number")))
            .findFirst().orElseThrow().get("pending_inspection_count");
    }

    private void assertStatus(int status, Runnable body) {
        assertThatThrownBy(body::run).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }

    private <T> T asA(Supplier<T> body) {
        return TenantContext.runAs(tenantId, () -> appUserTx.execute(s -> body.get()));
    }

    private void assertStatusAsA(int status, Runnable body) {
        assertStatus(status, () -> asA(() -> { body.run(); return null; }));
    }

    private String base() { return "http://localhost:" + port; }

    private String login(UUID userId) {
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, Object body, String token) {
        return rest.exchange(base() + path, method, new HttpEntity<>(body, auth(token)), Map.class);
    }
}
