package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.AccessTokenResponse;
import org.springframework.boot.test.system.CapturedOutput;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.ReturnRequestService;
import com.traceability.portal.PortalService;
import com.traceability.portal.PickupAreaService;
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
 * "Same address" area guess (2026-11-08). Bosta's live API needs a district on a pickup (RR-CM5NYQ was refused
 * without one), so when the original delivery has none the portal ASKS for the area (city fixed) and pre-selects its
 * guess from the original address (DistrictGuesser — exact or unique-contains); the customer confirms or changes it.
 * A submission without an area is refused. The district_required failure + drawer re-book stays as the safety net.
 *
 *   g1 exact EN (Shopify city "Zamalek") → suggestedDistrictId; the log says EXACT and carries no address
 *   g2 exact AR (address2 "الزمالك")      → suggestedDistrictId
 *   g3 contains (address1 "…, Zamalek")    → suggestedDistrictId
 *   g4 ambiguous (two districts named)     → no key; log AMBIGUOUS
 *   g5 none                                → no key; log NONE
 *   k1 an original WITH a district         → preselected as before, no suggestion key, no guess
 *   s1 submit without an area → 400 (even with a suggestion); with the suggested area → 201
 *   r1 safety net unchanged: a request without a district (submitted before this) refused by Bosta → district_required;
 *      area chosen + Retry → booked with it
 *   x1 cross-tenant: app_user under another tenant reads none of the order's address texts; the same tenant does
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class PortalAreaGuessTest {

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
    static final String ZAM = "zamalek-district";

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
        a       = tenant("Snouts Store", "snouts-ag");
        b       = tenant("Jumi Store", "jumi-ag");
        noBosta = tenant("Plain Store", "plain-ag");
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
        district(ZAM, CAIRO, "Cairo", "القاهرة", "Downtown", "وسط البلد", "Zamalek", "الزمالك", true);
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

    // ── g1–g5 ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void g1_exactEnglish_suggested_logHasNoAddress(CapturedOutput out) {
        bookingReady(a);
        Order o = deliveredOrder(a, "#7101", CAIRO, null);
        shopifyAddress(o, "8 Brazil St", null, "Zamalek");
        Map<String, Object> pickup = pickup(a, o);
        assertThat(pickup).containsEntry("suggestedDistrictId", ZAM).containsEntry("preselectedDistrictId", null)
            .doesNotContainKey("districtOptional");
        assertThat(out.getOut()).contains("Portal area guess tenant=" + a.id() + " outcome=EXACT")
            .doesNotContain("8 Brazil St").doesNotContain(STREET);
    }

    @Test
    @SuppressWarnings("unchecked")
    void g2_exactArabic() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#7201", CAIRO, null);
        shopifyAddress(o, "٨ شارع البرازيل", "الزّمالك", "القاهرة");
        assertThat(pickup(a, o)).containsEntry("suggestedDistrictId", ZAM);
    }

    @Test
    void g3_contains() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#7301", CAIRO, null);
        shopifyAddress(o, "5 Hassan Sabry St, Zamalek, 3rd floor", null, "Cairo");
        assertThat(pickup(a, o)).containsEntry("suggestedDistrictId", ZAM);
    }

    @Test
    void g4_ambiguous_noKey(CapturedOutput out) {
        bookingReady(a);
        Order o = deliveredOrder(a, "#7401", CAIRO, null);
        shopifyAddress(o, "Zamalek", "1st Settlement - District 10", "Cairo");   // two districts, both exact
        assertThat(pickup(a, o)).doesNotContainKey("suggestedDistrictId");
        assertThat(out.getOut()).contains("Portal area guess tenant=" + a.id() + " outcome=AMBIGUOUS");
    }

    @Test
    void g5_none_noKey(CapturedOutput out) {
        bookingReady(a);
        Order o = deliveredOrder(a, "#7501", CAIRO, null);
        shopifyAddress(o, "15 Tahrir St", null, "Cairo");
        assertThat(pickup(a, o)).doesNotContainKey("suggestedDistrictId");
        assertThat(out.getOut()).contains("Portal area guess tenant=" + a.id() + " outcome=NONE");
    }

    @Test
    void k1_originalWithDistrict_preselected_noGuess(CapturedOutput out) {
        bookingReady(a);
        Order o = deliveredOrder(a, "#7601", CAIRO, NASR);
        shopifyAddress(o, "8 Brazil St", null, "Zamalek");
        assertThat(pickup(a, o)).containsEntry("preselectedDistrictId", NASR).doesNotContainKey("suggestedDistrictId");
        assertThat(out.getOut()).doesNotContain("Portal area guess tenant=" + a.id());
    }

    // ── s1 ────────────────────────────────────────────────────────────────────

    @Test
    void s1_submitWithoutArea_refused_withTheSuggestion_created() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#7701", CAIRO, null);
        shopifyAddress(o, "8 Brazil St", null, "Zamalek");
        String token = (String) lookup(a, o).getBody().get("token");
        assertThat(submit(a, token, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> r = submit(a, token, ZAM);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT pickup_district_id FROM return_requests WHERE id = ?", String.class,
            requestIdOf(a, r))).isEqualTo(ZAM);
    }

    // ── r1: the safety net ────────────────────────────────────────────────────

    @Test
    void r1_requestWithoutDistrict_bostaRefuses_districtRequired_rebookWithArea() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#7801", CAIRO, null);
        // A request submitted before this change (no district, the city kept) — what RR-CM5NYQ is on prod.
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, pickup_city_id, pickup_city_name, decided_at) " +
            "VALUES (?, ?, 'approved', ?, ?, 'Cairo', now()) RETURNING id", UUID.class, a.id(), o.id(), reference(), CAIRO);
        String piece = jdbc.queryForObject("SELECT id FROM pieces WHERE current_order_id = ?", String.class, o.id());
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) VALUES (?, ?, ?, ?, 'wrong_size')",
            a.id(), id, piece, a.variant());
        book(a, id, new BostaV2Client.CreateResult(BostaV2Client.CreateOutcome.NOT_CREATED, null, null, 400,
            "pickupAddress.districtId is required", "VALIDATION_ERROR"));
        assertThat(jdbc.queryForMap("SELECT booking_status, booking_failure_reason FROM return_requests WHERE id = ?", id))
            .containsEntry("booking_status", "failed").containsEntry("booking_failure_reason", "district_required");
        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", D10), ownerA).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(post("/api/v1/return-requests/" + id + "/booking/retry", ownerA).getStatusCode().is2xxSuccessful()).isTrue();
        reset(bosta);
        assertThat(book(a, id, created()).districtId()).isEqualTo(D10);
        assertThat(jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE id = ?", String.class, id)).isEqualTo("booked");
    }

    // ── x1 ────────────────────────────────────────────────────────────────────

    @Test
    void x1_appUser_otherTenantReadsNoAddressTexts_sameTenantDoes() {
        Order o = deliveredOrder(a, "#7901", CAIRO, null);
        shopifyAddress(o, "8 Brazil St", null, "Zamalek");
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        List<String> crossed = TenantContext.runAs(b.id(), () -> tx.execute(s -> new PickupAreaService(app).originalAddressTexts(a.id(), o.id())));
        assertThat(crossed).isEmpty();
        List<String> own = TenantContext.runAs(a.id(), () -> tx.execute(s -> new PickupAreaService(app).originalAddressTexts(a.id(), o.id())));
        assertThat(own).contains("8 Brazil St", "Zamalek", STREET);
    }

    // ── fixtures (this class) ─────────────────────────────────────────────────

    private void shopifyAddress(Order o, String address1, String address2, String city) {
        Map<String, Object> m = new HashMap<>();
        m.put("address1", address1);
        if (address2 != null) m.put("address2", address2);
        m.put("city", city);
        try {
            jdbc.update("UPDATE orders SET shopify_address = ?::jsonb WHERE id = ?", mapper.writeValueAsString(m), o.id());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pickup(Tenant t, Order o) {
        ResponseEntity<Map> l = lookup(t, o);
        assertThat(l.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) l.getBody().get("pickup");
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
