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
 * The area guess's ambiguity rules through the real lookup (2026-11-08 evening): the zone ids, drop-off flags and Bosta
 * city names reach DistrictGuesser from bosta_districts.
 *
 *   z1 "Nasr City" names two districts of ONE zone served alike → SAME_ZONE, the first by name (7th District)
 *   z2 "Madinaty" names a 4-district zone → ZONE_ONLY: no suggestion, suggestedZoneDistrictIds = the zone's districts
 *   k1 a district named like the city ("Cairo") is never a match → no suggestion
 *   a1 two zones named → no suggestion (still ambiguous)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class PortalAreaGuessAmbiguityTest {

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
    static final String NASR8 = "nasr-8-district", CAIRO_D = "cairo-named-district";
    static final List<String> MADINATY = List.of("mad-1", "mad-2", "mad-3", "mad-4");

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
        a       = tenant("Snouts Store", "snouts-ab");
        b       = tenant("Jumi Store", "jumi-ab");
        noBosta = tenant("Plain Store", "plain-ab");
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
        district(NASR8, CAIRO, "Cairo", "القاهرة", "Nasr City", "مدينة نصر", "Nasr City - 8th District", "مدينة نصر - الحي الثامن", true);
        district(CAIRO_D, CAIRO, "Cairo", "القاهرة", "Downtown", "وسط البلد", "Cairo", "القاهرة", true);
        district("GizaD1", GIZA, "Giza", "الجيزة", "Haram", "الهرم", "Haram", "الهرم", false);
        for (String m : MADINATY) district(m, CAIRO, "Cairo", "القاهرة", "Madinaty", "مدينتي", "Madinaty " + m, "مدينتي " + m, true);

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

    @Test
    void z1_sameZone_suggestsTheZonesRepresentative(CapturedOutput out) {
        bookingReady(a);
        Order o = deliveredOrder(a, "#8101", CAIRO, null);
        shopifyAddress(o, "10 Abbas El Akkad", null, "Nasr City");
        assertThat(pickup(a, o)).containsEntry("suggestedDistrictId", NASR);
        assertThat(out.getOut()).contains("Portal area guess tenant=" + a.id() + " outcome=SAME_ZONE");
    }

    @Test
    void z2_bigZone_zoneOnly_pickerFilteredNoSuggestion(CapturedOutput out) {
        bookingReady(a);
        Order o = deliveredOrder(a, "#8102", CAIRO, null);
        shopifyAddress(o, "B12 Group 3", null, "Madinaty");   // 4 districts in the zone: more than SAME_ZONE takes
        Map<String, Object> pickup = pickup(a, o);
        assertThat(pickup).doesNotContainKey("suggestedDistrictId");
        assertThat(pickup.get("suggestedZoneDistrictIds")).isEqualTo(MADINATY);
        assertThat(out.getOut()).contains("Portal area guess tenant=" + a.id() + " outcome=ZONE_ONLY");
        // The other outcomes never carry the key.
        Order z1 = deliveredOrder(a, "#8103", CAIRO, null);
        shopifyAddress(z1, "10 Abbas El Akkad", null, "Nasr City");
        assertThat(pickup(a, z1)).doesNotContainKey("suggestedZoneDistrictIds");
    }

    @Test
    void k1_cityNamedDistrict_neverMatched() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#8201", CAIRO, null);
        shopifyAddress(o, "15 Tahrir St", null, "Cairo");
        assertThat(pickup(a, o)).doesNotContainKey("suggestedDistrictId");
    }

    @Test
    void a1_twoZones_noSuggestion() {
        bookingReady(a);
        Order o = deliveredOrder(a, "#8301", CAIRO, null);
        shopifyAddress(o, "Zamalek", "Nasr City", "Cairo");
        assertThat(pickup(a, o)).doesNotContainKey("suggestedDistrictId");
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
