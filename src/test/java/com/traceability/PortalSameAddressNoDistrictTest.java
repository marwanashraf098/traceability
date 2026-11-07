package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.AccessTokenResponse;
import org.springframework.boot.test.system.CapturedOutput;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.ReturnRequestService;
import com.traceability.portal.PortalService;
import com.traceability.portal.ReturnPickupBookingService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * "Same address" when the original Bosta delivery has NO district (2026-11-07 — 95% of recent prod deliveries): the
 * portal asks for no area, the request keeps the original's city, and the type 25 / 30 booking carries the city and the
 * original's lines with NO districtId. If Bosta refuses it for the missing area: a definite failure ('failed', reason
 * district_required, never re-sent by the sweeper); the merchant chooses the area and books again. "Different address"
 * still requires a district.
 *
 *   n1 — lookup flags districtOptional; no district → 201 with the city stored; type 25 payload: city + lines, no
 *        districtId; Bosta accepts → booked; read-back never flags the district Bosta set.
 *   n2 — Bosta refuses for the area → failed + district_required + the drawer message; the detail carries the reason;
 *        the sweeper never re-sends; area chosen + Retry → booked with that districtId, reason cleared. Bosta's answer
 *        is logged once with the customer's phone and street removed.
 *   n3 — any other 4xx → failed with Bosta's own message, no reason.
 *   e1 — exchange (type 30): dropOffAddress carries the city + lines, no districtId.
 *   c1 — "Different address" without a district → 400 (unchanged).
 *   k1 — an original WITH a district: lookup has no districtOptional key and a submission without one → 400 (unchanged).
 *   x1 — cross-tenant: another tenant's owner can't set the area / retry this request (404); same-tenant control can.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class PortalSameAddressNoDistrictTest {

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
        r.add("traced.portal-fees.settings-enabled", () -> "true");
        r.add("analytics.settlement.refresh-enabled", () -> "false");
    }

    static final String CAIRO = "FceDyHXwpSYYF9zGW", ALEX = "Jrb6X6ucjiYgMP4T7", GIZA = "GizaNoPickupCity";
    static final String D10 = "wY_JL43TilR", D11_UNAVAILABLE = "_jLhFsfVsLu", NASR = "Iy7-lFD0BE0", ALEX_D = "zoJP71_5Ca1";
    static final String STREET = "12 Customer Street, flat 4";
    static final String KEY_A = "bosta-key-tenant-a";

    @LocalServerPort int port;
    @Autowired TestRestTemplate  rest;
    @Autowired JdbcTemplate      jdbc;
    @Autowired PasswordEncoder   passwordEncoder;
    @Autowired ObjectMapper      mapper;
    @Autowired EncryptionService encryption;
    @MockBean  BostaV2Client     bosta;
    @MockBean  org.jobrunr.scheduling.JobScheduler jobScheduler;
    @MockBean  com.traceability.integrations.shopify.ShopifyOrderPriceGateway priceGateway;
    @MockBean  com.traceability.integrations.shopify.ShopifyTokenProvider tokenProvider;
    @Autowired PortalService portal;
    @Autowired ReturnPickupBookingService pickupBooking;

    final TestRestTemplate jdkRest = new TestRestTemplate(
        new org.springframework.boot.web.client.RestTemplateBuilder()
            .requestFactory(() -> new org.springframework.http.client.JdkClientHttpRequestFactory()));

    record Tenant(UUID id, UUID store, UUID variant, UUID owner, String slug) {}
    record Order(UUID id, String number, String phone) {}

    Tenant a, b, noBosta;
    String ownerA, managerA, workerA, ownerNoBosta;
    ReturnRequestService appUserRequests;
    TransactionTemplate appUserTx;

    @BeforeAll
    void setup() {
        a       = tenant("Snouts Store", "snouts-nd");
        b       = tenant("Jumi Store", "jumi-nd");
        noBosta = tenant("Plain Store", "plain-nd");
        ownerA       = login(a.owner());
        managerA     = login(user(a.id(), "manager"));
        workerA      = login(user(a.id(), "worker"));
        ownerNoBosta = login(noBosta.owner());
        for (Tenant t : List.of(a, b)) {
            jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                        "VALUES (gen_random_uuid(), ?, 'bosta', ?, ?, 'active')",
                        t.id(), encryption.encrypt(t == a ? KEY_A : "bosta-key-tenant-b"), "hash-" + t.slug());
        }
        district(D10, CAIRO, "Cairo", "القاهرة", "New Cairo", "القاهره الجديده", "1st Settlement - District 10", "التجمع الاول - الحي 10", true);
        district(D11_UNAVAILABLE, CAIRO, "Cairo", "القاهرة", "New Cairo", "القاهره الجديده", "1st Settlement - District 11", "التجمع الاول - الحي 11", false);
        district(NASR, CAIRO, "Cairo", "القاهرة", "Nasr City", "مدينة نصر", "Nasr City - 7th District", "مدينة نصر - الحي السابع", true);
        district(ALEX_D, ALEX, "Alexandria", "الإسكندرية", "Abu Yousef", "ابو يوسف", "Abu Yousef", "ابو يوسف", true);
        district("GizaD1", GIZA, "Giza", "الجيزة", "Haram", "الهرم", "Haram", "الهرم", false);

        DataSource appUserDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserRequests = new ReturnRequestService(new JdbcTemplate(appUserDs));
        appUserTx       = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        reset(bosta);
        for (Tenant t : List.of(a, b, noBosta)) {
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_amounts WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_events WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_amounts WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM portal_fee_area_overrides WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM portal_fee_rules WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE tenants SET portal_pickup_booking = false, portal_auto_approve = false WHERE id = ?", t.id());
        }
        jdbc.update("UPDATE courier_accounts SET return_business_location_id = NULL, return_business_location_name = NULL");
    }

    // ── n1 ────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void n1_noOriginalDistrict_noArea_bookedWithCityAndLinesOnly() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#6101", CAIRO, null);
        ResponseEntity<Map> l = lookup(a, o);
        Map<String, Object> pickup = (Map<String, Object>) l.getBody().get("pickup");
        assertThat(pickup).containsEntry("districtOptional", true).containsEntry("preselectedDistrictId", null);
        ResponseEntity<Map> r = submit(a, (String) l.getBody().get("token"), null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = requestIdOf(a, r);
        assertThat(jdbc.queryForMap("SELECT pickup_city_id, pickup_city_name, pickup_district_id FROM return_requests WHERE id = ?", id))
            .containsEntry("pickup_city_id", CAIRO).containsEntry("pickup_city_name", "Cairo").containsEntry("pickup_district_id", null);

        BostaV2Client.ReturnPickup p = book(a, id, created());
        assertThat(p.city()).isEqualTo("Cairo");
        assertThat(p.districtId()).isNull();
        assertThat(p.firstLine()).isEqualTo(STREET);
        assertThat(p.secondLine()).isEqualTo("Near the mosque");
        assertThat(BostaV2Client.returnPickupPayload(mapper, p).path("pickupAddress").has("districtId"))
            .as("no districtId key at all in the body").isFalse();
        assertThat(jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE id = ?", String.class, id)).isEqualTo("booked");
    }

    // ── n2 / n3 ───────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void n2_bostaNeedsTheArea_definiteFailure_neverResent_merchantPicksAreaAndRebooks(CapturedOutput out) {
        bookingReady(a);
        Order o = deliveredOrder(a, "#6201", CAIRO, null);
        ResponseEntity<Map> l = lookup(a, o);
        UUID id = requestIdOf(a, submit(a, (String) l.getBody().get("token"), null));
        book(a, id, new BostaV2Client.CreateResult(BostaV2Client.CreateOutcome.NOT_CREATED, null, null, 400,
            "pickupAddress.districtId is required for " + o.phone() + " at " + STREET, "VALIDATION_ERROR"));

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT booking_status, booking_failure_reason, booking_error FROM return_requests WHERE id = ?", id);
        assertThat(row).containsEntry("booking_status", "failed").containsEntry("booking_failure_reason", "district_required")
            .containsEntry("booking_error", "Bosta needs an area for this pickup. Choose the area and book again.");
        assertThat(jdbc.queryForObject("SELECT metadata->>'reason' FROM return_request_events WHERE request_id = ? " +
            "AND event_type = 'booking_failed'", String.class, id)).isEqualTo("district_required");
        assertThat(get("/api/v1/return-requests/" + id, ownerA).getBody()).containsEntry("bookingFailureReason", "district_required");
        assertThat(out.getOut()).contains("Bosta no-district booking request=" + id).contains("districtRequired=true")
            .doesNotContain(o.phone()).doesNotContain(STREET);

        // Never re-sent automatically.
        TenantContext.runAs(a.id(), () -> pickupBooking.sweepTenant(a.id()));
        verify(bosta, times(1)).createReturnPickup(any(), any());

        // The merchant chooses the area, then Retry.
        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", D10), ownerA).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(post("/api/v1/return-requests/" + id + "/booking/retry", ownerA).getStatusCode().is2xxSuccessful()).isTrue();
        reset(bosta);
        BostaV2Client.ReturnPickup again = book(a, id, created());
        assertThat(again.districtId()).isEqualTo(D10);
        assertThat(again.city()).isEqualTo("Cairo");
        assertThat(jdbc.queryForMap("SELECT booking_status, booking_failure_reason FROM return_requests WHERE id = ?", id))
            .containsEntry("booking_status", "booked").containsEntry("booking_failure_reason", null);
        assertThat(get("/api/v1/return-requests/" + id, ownerA).getBody()).doesNotContainKey("bookingFailureReason");
    }

    @Test
    void n3_otherRefusal_failedWithBostasMessage_noReason() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#6301", CAIRO, null);
        UUID id = requestIdOf(a, submit(a, (String) lookup(a, o).getBody().get("token"), null));
        book(a, id, new BostaV2Client.CreateResult(BostaV2Client.CreateOutcome.NOT_CREATED, null, null, 400,
            "Invalid receiver phone.", "VALIDATION_ERROR"));
        assertThat(jdbc.queryForMap("SELECT booking_status, booking_failure_reason, booking_error FROM return_requests WHERE id = ?", id))
            .containsEntry("booking_status", "failed").containsEntry("booking_failure_reason", null)
            .containsEntry("booking_error", "Invalid receiver phone.");
        assertThat(get("/api/v1/return-requests/" + id, ownerA).getBody()).doesNotContainKey("bookingFailureReason");
    }

    // ── e1: exchange ──────────────────────────────────────────────────────────

    @Test
    void e1_exchange_type30_cityAndLines_noDistrictId() {
        bookingReady(a);
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = true WHERE id = ?", a.id());
        UUID large = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, title, sku) " +
            "SELECT tenant_id, product_id, 'V-L-' || gen_random_uuid(), 'Sand / L', 'SKU-L-' || left(gen_random_uuid()::text, 6) " +
            "FROM variants WHERE id = ? RETURNING id", UUID.class, a.variant());
        UUID main = jdbc.queryForList("SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment", UUID.class, a.id())
            .stream().findFirst().orElseGet(() -> jdbc.queryForObject(
                "INSERT INTO locations (tenant_id, name, is_fulfillment) VALUES (?, 'Main', true) RETURNING id", UUID.class, a.id()));
        String stock = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?, now())",
                    stock, a.id(), large, "PC-" + stock, stock, main);
        Order o = deliveredOrder(a, "#6401", CAIRO, null);
        String token = (String) lookup(a, o).getBody().get("token");
        PortalService.SubmitResult r = portal.submit(a.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(a.variant(), 1, "wrong_size")), null, null, null, "exchange", large, false))
            .orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        UUID id = jdbc.queryForObject("SELECT id FROM return_requests WHERE tenant_id = ? AND reference = ?", UUID.class,
            a.id(), r.body().get("reference"));
        jdbc.update("UPDATE return_requests SET status = 'approved', decided_at = now() WHERE id = ?", id);
        when(bosta.createExchange(any(), any())).thenReturn(created());
        pickupBooking.book(id, a.id());
        org.mockito.ArgumentCaptor<BostaV2Client.Exchange> cap = org.mockito.ArgumentCaptor.forClass(BostaV2Client.Exchange.class);
        verify(bosta).createExchange(eq(KEY_A), cap.capture());
        assertThat(cap.getValue().city()).isEqualTo("Cairo");
        assertThat(cap.getValue().districtId()).isNull();
        assertThat(cap.getValue().firstLine()).isEqualTo(STREET);
        assertThat(BostaV2Client.exchangePayload(mapper, cap.getValue()).path("dropOffAddress").has("districtId")).isFalse();
    }

    // ── c1 / k1: unchanged ────────────────────────────────────────────────────

    @Test
    void c1_differentAddress_stillNeedsADistrict() throws Exception {
        bookingReady(a);
        Order o = deliveredOrder(a, "#6501", CAIRO, null);
        String token = (String) lookup(a, o).getBody().get("token");
        Map<String, Object> body = new HashMap<>();
        body.put("lines", List.of(Map.of("variantId", a.variant().toString(), "quantity", 1, "reasonCode", "wrong_size")));
        body.put("addressSource", "custom");
        body.put("cityId", CAIRO);
        body.put("firstLine", "14 Different Street");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        ResponseEntity<Map> r = jdkRest.exchange(base() + "/api/v1/portal/" + a.slug() + "/requests", HttpMethod.POST,
            new HttpEntity<>(mapper.writeValueAsString(body), h), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void k1_originalWithDistrict_unchanged_noFlag_districtStillRequired() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#6601", CAIRO, NASR);
        ResponseEntity<Map> l = lookup(a, o);
        assertThat((Map<String, Object>) l.getBody().get("pickup")).doesNotContainKey("districtOptional");
        assertThat(submit(a, (String) l.getBody().get("token"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── x1 ────────────────────────────────────────────────────────────────────

    @Test
    void x1_otherTenantCantRebook_sameTenantCan() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#6701", CAIRO, null);
        UUID id = requestIdOf(a, submit(a, (String) lookup(a, o).getBody().get("token"), null));
        book(a, id, new BostaV2Client.CreateResult(BostaV2Client.CreateOutcome.NOT_CREATED, null, null, 422,
            "district is required", null));
        String ownerB = login(b.owner());
        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", D10), ownerB).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/v1/return-requests/" + id + "/booking/retry", ownerB).getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT pickup_district_id FROM return_requests WHERE id = ?", String.class, id)).isNull();
        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", D10), ownerA).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbc.queryForObject("SELECT pickup_district_id FROM return_requests WHERE id = ?", String.class, id)).isEqualTo(D10);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void bookingReady(Tenant t) {
        booking(t, true);
        jdbc.update("UPDATE tenants SET portal_auto_approve = true WHERE id = ?", t.id());
        jdbc.update("UPDATE courier_accounts SET return_business_location_id = 'loc-return-1' WHERE tenant_id = ?", t.id());
    }

    private UUID requestIdOf(Tenant t, ResponseEntity<Map> r) {
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE tenant_id = ? AND reference = ?", UUID.class,
            t.id(), r.getBody().get("reference"));
    }

    private static BostaV2Client.CreateResult created() {
        return new BostaV2Client.CreateResult(BostaV2Client.CreateOutcome.CREATED, "dlv-" + UUID.randomUUID(),
            String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)), 200, null);
    }

    /** Runs the booking job with Bosta answering {@code answer}; returns the type 25 payload Bosta got. */
    private BostaV2Client.ReturnPickup book(Tenant t, UUID requestId, BostaV2Client.CreateResult answer) {
        when(bosta.createReturnPickup(any(), any())).thenReturn(answer);
        pickupBooking.book(requestId, t.id());
        org.mockito.ArgumentCaptor<BostaV2Client.ReturnPickup> cap = org.mockito.ArgumentCaptor.forClass(BostaV2Client.ReturnPickup.class);
        verify(bosta, atLeastOnce()).createReturnPickup(eq(KEY_A), cap.capture());
        return cap.getValue();
    }

    private Map<String, Object> quote(Tenant t, Order o, String districtId) {
        jdbc.update("UPDATE order_items SET raw = '{\"price\":\"500.00\",\"quantity\":1}'::jsonb WHERE order_id = ?", o.id());
        String token = (String) lookup(t, o).getBody().get("token");
        PortalService.QuoteResult r = portal.quote(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.variant(), 1, "wrong_size")), null, null, districtId, null, null, false)).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.QuoteOutcome.OK);
        return r.body();
    }

    private Tenant tenant(String name, String slug) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", id, name, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            owner, id, "owner-" + owner + "@test.local", passwordEncoder.encode("pass123"));
        UUID p = UUID.randomUUID(), v = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            p, id, store, "P-" + p);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'Sand / M', ?)",
            v, id, p, "V-" + v, "SKU-" + v.toString().substring(0, 6));
        return new Tenant(id, store, v, owner, slug);
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@test.local", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private void district(String id, String cityId, String city, String cityAr, String zone, String zoneAr,
                          String name, String nameAr, boolean pickup) {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "    district_name, district_name_ar, pickup_available, dropoff_available) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)",
                    id, cityId, city, cityAr, "z-" + zone, zone, zoneAr, name, nameAr, pickup);
    }

    private void booking(Tenant t, boolean on) {
        jdbc.update("UPDATE tenants SET portal_pickup_booking = ? WHERE id = ?", on, t.id());
    }

    /** A delivered order with one delivered piece and a forward leg whose raw carries a full address. */
    private Order deliveredOrder(Tenant t, String number, String cityId, String districtId) {
        String phone = "010" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona Customer', ?, 'bosta') RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(), number, phone);
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("firstLine", STREET);
        drop.put("secondLine", "Near the mosque");
        if (cityId != null) drop.put("city", Map.of("_id", cityId, "name", "City"));
        drop.put("zone", Map.of("_id", "zone-x", "name", "Zone"));
        drop.put("district", districtId != null ? Map.of("_id", districtId, "name", "District") : Map.of("name", "District"));
        String raw;
        try {
            raw = mapper.writeValueAsString(Map.of("type", Map.of("code", 10, "value", "Send"), "dropOffAddress", drop));
        } catch (Exception e) { throw new RuntimeException(e); }
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at, raw) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '2 days', ?::jsonb)",
                    t.id(), order, String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)), raw);
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                    piece, t.id(), t.variant(), "PC-" + piece, piece, order);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                    item, t.id(), order, t.variant());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                    t.id(), item, piece);
        return new Order(order, number, phone);
    }

    private UUID request(Tenant t, Order o, String status, String districtId) {
        return jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, pickup_district_id) " +
            "VALUES (?, ?, ?::return_request_status, ?, ?) RETURNING id",
            UUID.class, t.id(), o.id(), status, reference(), districtId);
    }

    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }

    private ResponseEntity<Map> lookup(Tenant t, Order o) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + "/api/v1/portal/" + t.slug() + "/lookup", HttpMethod.POST,
            new HttpEntity<>(Map.of("orderNumber", o.number(), "phone", o.phone()), h), Map.class);
    }

    private ResponseEntity<Map> submit(Tenant t, String token, String districtId) {
        Map<String, Object> body = new HashMap<>();
        body.put("lines", List.of(Map.of("variantId", t.variant().toString(), "quantity", 1, "reasonCode", "wrong_size")));
        if (districtId != null) body.put("districtId", districtId);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        try {
            return jdkRest.exchange(base() + "/api/v1/portal/" + t.slug() + "/requests", HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body), h), Map.class);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Map<String, Object> settings(String returnLocationId) {
        Map<String, Object> m = new HashMap<>();
        m.put("slug", "snouts-4c2");
        m.put("enabled", true);
        m.put("autoApprove", false);
        m.put("returnWindowDays", 30);
        if (returnLocationId != null) m.put("returnLocationId", returnLocationId);
        return m;
    }

    private <T> T asA(Supplier<T> body) {
        return TenantContext.runAs(a.id(), () -> appUserTx.execute(s -> body.get()));
    }

    private void assertNotFound(Supplier<Object> body) {
        assertThatThrownBy(() -> asA(body)).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
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

    private ResponseEntity<Map> post(String path, String token) {
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(null, auth(token)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
    }

    private ResponseEntity<Map> put(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.PUT, new HttpEntity<>(body, auth(token)), Map.class);
    }
}
