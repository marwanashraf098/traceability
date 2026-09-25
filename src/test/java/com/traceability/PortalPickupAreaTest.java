package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.ReturnRequestService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Returns portal Step 4c-2 — the pickup area (portal lookup / submit, merchant drawer "Change
 * area") and the return warehouse (Bosta pickup locations, settings save), over HTTP.
 *
 * bosta_districts is seeded directly (Cairo: two pickup-available districts in two zones plus
 * one Bosta marks unavailable; Alexandria: one). Forward legs carry a realistic
 * raw.dropOffAddress with a street line that must never reach the portal. BostaV2Client is
 * mocked here; its HTTP behaviour is BostaV2ClientTest. Cross-tenant proof for the drawer
 * endpoints runs on a real app_user connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalPickupAreaTest {

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
        a       = tenant("Snouts Store", "snouts-4c2");
        b       = tenant("Jumi Store", "jumi-4c2");
        noBosta = tenant("Plain Store", "plain-4c2");
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
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE tenants SET portal_pickup_booking = false WHERE id = ?", t.id());
        }
        jdbc.update("UPDATE courier_accounts SET return_business_location_id = NULL, return_business_location_name = NULL");
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    @Test
    void lookup_bookingOff_pickupNull_evenWithAKnownCity() {
        Order o = deliveredOrder(a, "#1001", CAIRO, NASR);
        ResponseEntity<Map> r = lookup(a, o);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKey("pickup");
        assertThat(r.getBody().get("pickup")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookup_bookingOn_knownCity_pickupAvailableDistrictsOfThatCityOnly_preselected_noStreet() throws Exception {
        booking(a, true);
        Order o = deliveredOrder(a, "#1002", CAIRO, NASR);

        ResponseEntity<Map> r = lookup(a, o);

        Map<String, Object> pickup = (Map<String, Object>) r.getBody().get("pickup");
        assertThat(pickup.keySet()).containsExactlyInAnyOrder("cityId", "cityName", "cityNameAr", "districts", "preselectedDistrictId");
        assertThat(pickup.get("cityId")).isEqualTo(CAIRO);
        assertThat(pickup.get("cityName")).isEqualTo("Cairo");
        assertThat(pickup.get("cityNameAr")).isEqualTo("القاهرة");
        List<Map<String, Object>> ds = (List<Map<String, Object>>) pickup.get("districts");
        assertThat(ds).extracting(d -> d.get("id")).containsExactly(NASR, D10);
        assertThat(ds.get(0)).as("ordered by zone, then district").containsEntry("name", "Nasr City - 7th District").containsEntry("nameAr", "مدينة نصر - الحي السابع")
            .containsEntry("zoneName", "Nasr City").containsEntry("zoneNameAr", "مدينة نصر");
        assertThat(ds.get(0).keySet()).containsExactlyInAnyOrder("id", "name", "nameAr", "zoneName", "zoneNameAr");
        assertThat(pickup.get("preselectedDistrictId")).isEqualTo(NASR);

        String json = mapper.writeValueAsString(r.getBody());
        assertThat(json).doesNotContain("Customer Street").doesNotContain("firstLine").doesNotContain("address");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookup_preselectedOnlyWhenForwardDistrictIsInTheList() {
        booking(a, true);
        Order unavailable = deliveredOrder(a, "#1003", CAIRO, D11_UNAVAILABLE);
        Order noDistrict  = deliveredOrder(a, "#1004", CAIRO, null);
        assertThat(((Map<String, Object>) lookup(a, unavailable).getBody().get("pickup")).get("preselectedDistrictId")).isNull();
        assertThat(((Map<String, Object>) lookup(a, noDistrict).getBody().get("pickup")).get("preselectedDistrictId")).isNull();
    }

    @Test
    void lookup_bookingOn_cityUnknownOrWithoutPickupDistricts_pickupNull() {
        booking(a, true);
        Order noCity = deliveredOrder(a, "#1005", null, null);
        Order giza   = deliveredOrder(a, "#1006", GIZA, "GizaD1");
        Order other  = deliveredOrder(a, "#1007", "UnknownCityId", null);
        assertThat(lookup(a, noCity).getBody().get("pickup")).isNull();
        assertThat(lookup(a, giza).getBody().get("pickup")).isNull();
        assertThat(lookup(a, other).getBody().get("pickup")).isNull();
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    @Test
    void submit_pickupOffered_districtRequired_wrongCityAndUnavailableRejected_validStoresSnapshot() {
        booking(a, true);
        Order o = deliveredOrder(a, "#2001", CAIRO, NASR);
        String token = (String) lookup(a, o).getBody().get("token");

        assertThat(submit(a, token, null).getStatusCode()).as("missing").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, ALEX_D).getStatusCode()).as("other city").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, D11_UNAVAILABLE).getStatusCode()).as("not pickup-available").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, "no-such-district").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE tenant_id = ?", Integer.class, a.id())).isZero();

        assertThat(submit(a, token, D10).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> rr = jdbc.queryForMap(
            "SELECT pickup_city_id, pickup_city_name, pickup_district_id, pickup_district_name, pickup_district_name_ar " +
            "FROM return_requests WHERE tenant_id = ?", a.id());
        assertThat(rr).containsEntry("pickup_city_id", CAIRO).containsEntry("pickup_city_name", "Cairo")
            .containsEntry("pickup_district_id", D10).containsEntry("pickup_district_name", "1st Settlement - District 10")
            .containsEntry("pickup_district_name_ar", "التجمع الاول - الحي 10");
    }

    @Test
    void submit_pickupNotOffered_districtIgnored() {
        Order o = deliveredOrder(a, "#2002", CAIRO, NASR);   // booking off
        String token = (String) lookup(a, o).getBody().get("token");

        assertThat(submit(a, token, "anything-at-all").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> rr = jdbc.queryForMap("SELECT pickup_city_id, pickup_district_id FROM return_requests WHERE tenant_id = ?", a.id());
        assertThat(rr.get("pickup_city_id")).isNull();
        assertThat(rr.get("pickup_district_id")).isNull();
    }

    // ── Merchant drawer: change area ──────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void drawer_areas_change_validation_statusLimits_roles() {
        UUID id = request(a, deliveredOrder(a, "#3001", CAIRO, NASR), "requested", null);

        Map<String, Object> areas = get("/api/v1/return-requests/" + id + "/pickup-areas", managerA).getBody();
        assertThat(areas.get("cityName")).isEqualTo("Cairo");
        assertThat((List<Map<String, Object>>) areas.get("districts")).extracting(d -> d.get("id")).containsExactly(NASR, D10);
        assertThat(areas.get("selectedDistrictId")).isNull();
        assertThat(areas.get("editable")).isEqualTo(true);
        assertThat(get("/api/v1/return-requests/" + id, ownerA).getBody().get("pickupDistrictName")).as("no snapshot yet").isNull();

        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", NASR), managerA).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> detail = get("/api/v1/return-requests/" + id, ownerA).getBody();
        assertThat(detail).containsEntry("pickupDistrictId", NASR).containsEntry("pickupDistrictName", "Nasr City - 7th District")
            .containsEntry("pickupDistrictNameAr", "مدينة نصر - الحي السابع").containsEntry("pickupCityName", "Cairo");

        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", D11_UNAVAILABLE), ownerA)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", ALEX_D), ownerA)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of(), ownerA).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", D10), workerA).getStatusCode())
            .as("worker").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/v1/return-requests/" + id + "/pickup-areas", workerA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE return_requests SET status = 'approved' WHERE id = ?", id);
        assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", D10), ownerA).getStatusCode())
            .as("approved is still editable").isEqualTo(HttpStatus.NO_CONTENT);

        for (String status : List.of("rejected", "pickup_booked", "received")) {
            jdbc.update("UPDATE return_requests SET status = ?::return_request_status WHERE id = ?", status, id);
            assertThat(put("/api/v1/return-requests/" + id + "/pickup-area", Map.of("districtId", NASR), ownerA).getStatusCode())
                .as(status).isEqualTo(HttpStatus.CONFLICT);
        }
        assertThat(jdbc.queryForObject("SELECT pickup_district_id FROM return_requests WHERE id = ?", String.class, id)).isEqualTo(D10);
    }

    @Test
    void drawer_crossTenant_onAppUser_404_withSameTenantPositiveControl() {
        UUID mine   = request(a, deliveredOrder(a, "#4001", CAIRO, NASR), "requested", null);
        UUID theirs = request(b, deliveredOrder(b, "#4001", CAIRO, NASR), "requested", null);

        assertThat(asA(() -> appUserRequests.pickupAreas(mine)).get("cityName")).as("positive control").isEqualTo("Cairo");
        asA(() -> { appUserRequests.setPickupArea(mine, D10); return null; });
        assertThat(jdbc.queryForObject("SELECT pickup_district_id FROM return_requests WHERE id = ?", String.class, mine)).isEqualTo(D10);

        assertNotFound(() -> appUserRequests.pickupAreas(theirs));
        assertNotFound(() -> { appUserRequests.setPickupArea(theirs, D10); return null; });
        assertThat(jdbc.queryForObject("SELECT pickup_district_id FROM return_requests WHERE id = ?", String.class, theirs))
            .as("B's request untouched").isNull();
    }

    // ── Return warehouse ──────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void returnLocations_list_withTenantKey_andRoles() {
        when(bosta.listPickupLocations(KEY_A)).thenReturn(List.of(
            new BostaV2Client.PickupLocation("yfWPU0tP2", "Maadi Warehouse", true, "Cairo"),
            new BostaV2Client.PickupLocation("5f0a7def4a839b00139d6203", "Original Business Location", false, null)));

        ResponseEntity<List> r = rest.exchange(base() + "/api/v1/tenant/bosta/return-locations", HttpMethod.GET,
            new HttpEntity<>(auth(managerA)), List.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> list = r.getBody();
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).containsExactlyInAnyOrderEntriesOf(
            Map.of("id", "yfWPU0tP2", "name", "Maadi Warehouse", "isDefault", true, "cityName", "Cairo"));
        verify(bosta).listPickupLocations(KEY_A);
        assertThat(get("/api/v1/tenant/bosta/return-locations", workerA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void returnLocations_keyRefused_422WithMessage_noAccount_409() {
        when(bosta.listPickupLocations(anyString())).thenThrow(new BostaV2Client.KeyRefusedException(401));
        ResponseEntity<Map> refused = get("/api/v1/tenant/bosta/return-locations", ownerA);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(refused.getBody()).containsEntry("error", "BOSTA_KEY_REFUSED")
            .containsEntry("message", "Bosta didn't accept the connected API key for locations");

        ResponseEntity<Map> none = get("/api/v1/tenant/bosta/return-locations", ownerNoBosta);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(none.getBody()).containsEntry("error", "NO_BOSTA_ACCOUNT");
    }

    @Test
    void settings_returnLocation_validatedOnSave_savedIdAndName_resaveSkipsBosta_getIncludesBooking() {
        when(bosta.listPickupLocations(KEY_A)).thenReturn(List.of(
            new BostaV2Client.PickupLocation("yfWPU0tP2", "Maadi Warehouse", true, "Cairo")));

        ResponseEntity<Map> unknown = put("/api/v1/tenant/portal-settings", settings("not-in-the-list"), ownerA);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).containsEntry("field", "returnLocationId").containsEntry("error", "RETURN_LOCATION_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT return_business_location_id FROM courier_accounts WHERE tenant_id = ?",
            String.class, a.id())).isNull();

        ResponseEntity<Map> ok = put("/api/v1/tenant/portal-settings", settings("yfWPU0tP2"), ownerA);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("returnLocationId", "yfWPU0tP2").containsEntry("returnLocationName", "Maadi Warehouse")
            .containsEntry("portalPickupBooking", false).containsEntry("pickupBooking", false);

        booking(a, true);
        assertThat(put("/api/v1/tenant/portal-settings", settings("yfWPU0tP2"), ownerA).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(bosta, times(2)).listPickupLocations(KEY_A);   // the unknown id and the first save only
        assertThat(get("/api/v1/tenant/portal-settings", managerA).getBody())
            .containsEntry("portalPickupBooking", true).containsEntry("returnLocationName", "Maadi Warehouse");

        // Absent returnLocationId leaves the saved location as it is (no Bosta call).
        assertThat(put("/api/v1/tenant/portal-settings", settings(null), ownerA).getBody())
            .containsEntry("returnLocationId", "yfWPU0tP2");
        verify(bosta, times(2)).listPickupLocations(KEY_A);
    }

    @Test
    void settings_returnLocation_keyRefusedOnSave_422_onTheField() {
        when(bosta.listPickupLocations(eq(KEY_A))).thenThrow(new BostaV2Client.KeyRefusedException(403));
        ResponseEntity<Map> r = put("/api/v1/tenant/portal-settings", settings("yfWPU0tP2"), ownerA);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody()).containsEntry("field", "returnLocationId").containsEntry("error", "BOSTA_KEY_REFUSED");
    }

    @Test
    void publicConfig_pickupBooking_readsTheTenantSwitch() {
        assertThat(rest.getForEntity(base() + "/api/v1/portal/snouts-4c2/config", Map.class).getBody())
            .containsEntry("pickupBooking", false);
        booking(a, true);
        assertThat(rest.getForEntity(base() + "/api/v1/portal/snouts-4c2/config", Map.class).getBody())
            .containsEntry("pickupBooking", true);
        assertThat(rest.getForEntity(base() + "/api/v1/portal/jumi-4c2/config", Map.class).getBody())
            .as("per tenant").containsEntry("pickupBooking", false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
    }

    private ResponseEntity<Map> put(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.PUT, new HttpEntity<>(body, auth(token)), Map.class);
    }
}
