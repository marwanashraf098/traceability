package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.shopify.ShopifyWebhookProcessorJob;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.ReturnRequestService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/**
 * V117 — the portal customer chooses the delivery address or a different pickup address.
 *
 * Covers: the lookup's pickup.cities (booking on only, never a street); the token-gated
 * GET /portal/{slug}/districts (401 without / with another tenant's token, exchange mode
 * filters to pickup + drop-off, unknown city → empty); submission validation for 'custom'
 * (street required and > 5 characters, district of the chosen city, pickup-only district
 * refused for an exchange) with the 'order' path unchanged; the merchant drawer's
 * pickupAddressSource / customAddress, cross-tenant on a real app_user connection with a
 * same-tenant positive control; GDPR customers/redact + shop/redact clearing the request's email,
 * note and custom_* (marker pii_redacted_at); and that no event / exception holds a copy.
 *
 * Booking payloads for custom addresses: ReturnPickupBookingTest (type 25 → pickupAddress)
 * and PortalExchangeBookingTest (type 30 → dropOffAddress).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalCustomAddressTest {

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
    /** NASR: pickup + drop-off. D10: pickup only. D11: not pickup-available. ALEX_D: pickup + drop-off. */
    static final String NASR = "Iy7-lFD0BE0", D10 = "wY_JL43TilR", D11 = "_jLhFsfVsLu", ALEX_D = "zoJP71_5Ca1";
    static final String STREET = "12 Customer Street, flat 4";
    static final String NEW_STREET = "7 New Street, Smouha";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper mapper;
    @Autowired ShopifyWebhookProcessorJob processorJob;

    final TestRestTemplate jdkRest = new TestRestTemplate(
        new org.springframework.boot.web.client.RestTemplateBuilder()
            .requestFactory(() -> new org.springframework.http.client.JdkClientHttpRequestFactory()));

    record Tenant(UUID id, UUID store, UUID variant, UUID replacement, UUID location, UUID owner, String slug) {}
    record Order(UUID id, String number, String phone, String externalId) {}

    Tenant a, b;
    String ownerA;
    ReturnRequestService appUserRequests;
    TransactionTemplate appUserTx;

    @BeforeAll
    void setup() {
        a = tenant("Snouts Store", "snouts-v117");
        b = tenant("Jumi Store", "jumi-v117");
        ownerA = login(a.owner());
        district(NASR, CAIRO, "Cairo", "القاهرة", "Nasr City", "مدينة نصر", "Nasr City - 7th District", "مدينة نصر - الحي السابع", true, true);
        district(D10, CAIRO, "Cairo", "القاهرة", "New Cairo", "القاهره الجديده", "1st Settlement - District 10", "التجمع الاول - الحي 10", true, false);
        district(D11, CAIRO, "Cairo", "القاهرة", "New Cairo", "القاهره الجديده", "1st Settlement - District 11", "التجمع الاول - الحي 11", false, true);
        district(ALEX_D, ALEX, "Alexandria", "الإسكندرية", "Smouha", "سموحة", "Smouha", "سموحة", true, true);
        district("GizaD1", GIZA, "Giza", "الجيزة", "Haram", "الهرم", "Haram", "الهرم", false, true);

        DataSource appUserDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appUserRequests = new ReturnRequestService(new JdbcTemplate(appUserDs));
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @BeforeEach
    void bookingOn() {
        for (Tenant t : List.of(a, b)) {
            jdbc.update("UPDATE tenants SET portal_pickup_booking = true, portal_exchanges_enabled = false, " +
                        "portal_auto_approve = false WHERE id = ?", t.id());
        }
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Tenant t : List.of(a, b)) {
            jdbc.update("DELETE FROM shopify_webhook_events WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_events WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ? AND status = 'delivered'::piece_status", t.id());
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
        }
    }

    // ── Lookup ────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void lookup_bookingOn_citiesWithAPickupDistrict_idAndNamesOnly_noStreet() throws Exception {
        Order o = deliveredOrder(a, "#1001", CAIRO, NASR);
        ResponseEntity<Map> r = lookup(a, o);

        Map<String, Object> pickup = (Map<String, Object>) r.getBody().get("pickup");
        List<Map<String, Object>> cities = (List<Map<String, Object>>) pickup.get("cities");
        assertThat(cities).extracting(c -> c.get("id")).as("Giza has no pickup-available district").containsExactly(ALEX, CAIRO);
        assertThat(cities.get(0)).containsExactlyInAnyOrderEntriesOf(Map.of("id", ALEX, "name", "Alexandria", "nameAr", "الإسكندرية"));
        assertThat(pickup.get("cityId")).as("existing fields kept").isEqualTo(CAIRO);
        assertThat(pickup.get("preselectedDistrictId")).isEqualTo(NASR);
        assertThat(mapper.writeValueAsString(r.getBody())).doesNotContain("Customer Street").doesNotContain("firstLine");
    }

    @Test
    void lookup_bookingOff_noPickupSoNoCities() {
        jdbc.update("UPDATE tenants SET portal_pickup_booking = false WHERE id = ?", a.id());
        Order o = deliveredOrder(a, "#1002", CAIRO, NASR);
        assertThat(lookup(a, o).getBody().get("pickup")).isNull();
    }

    // ── Districts endpoint ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void districts_tokenRequired_otherTenantsToken401_exchangeFilters_unknownCityEmpty_noStreet() throws Exception {
        String tokenA = token(a, deliveredOrder(a, "#2001", CAIRO, NASR));
        String tokenB = token(b, deliveredOrder(b, "#2001", CAIRO, NASR));

        assertThat(districts(a, null, CAIRO, null).getStatusCode()).as("no token").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(districts(a, "not-a-token", CAIRO, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(districts(a, tokenB, CAIRO, null).getStatusCode()).as("B's token on A's portal").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.exchange(base() + "/api/v1/portal/no-such-slug/districts?cityId=" + CAIRO, HttpMethod.GET,
            new HttpEntity<>(bearer(tokenA)), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> refund = districts(a, tokenA, CAIRO, null);
        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> ds = (List<Map<String, Object>>) refund.getBody().get("districts");
        assertThat(ds).extracting(d -> d.get("id")).as("pickup-available, by zone then name").containsExactly(NASR, D10);
        assertThat(ds.get(0).keySet()).containsExactlyInAnyOrder("id", "name", "nameAr", "zoneName", "zoneNameAr");

        List<Map<String, Object>> ex = (List<Map<String, Object>>) districts(a, tokenA, CAIRO, "exchange").getBody().get("districts");
        assertThat(ex).extracting(d -> d.get("id")).as("pickup AND drop-off").containsExactly(NASR);

        assertThat((List<?>) districts(a, tokenA, "UnknownCity", null).getBody().get("districts")).isEmpty();
        assertThat((List<?>) districts(a, tokenA, GIZA, null).getBody().get("districts")).isEmpty();
        assertThat((List<?>) districts(a, tokenA, null, null).getBody().get("districts")).isEmpty();
        assertThat(mapper.writeValueAsString(refund.getBody())).doesNotContain("Customer Street");

        jdbc.update("UPDATE tenants SET portal_pickup_booking = false WHERE id = ?", a.id());
        assertThat((List<?>) districts(a, tokenA, CAIRO, null).getBody().get("districts")).as("booking off").isEmpty();
    }

    // ── Submit ────────────────────────────────────────────────────────────────

    @Test
    void submit_custom_valid_storesAddressAndChosenAreaSnapshot_responseUnchanged() {
        String token = token(a, deliveredOrder(a, "#3001", CAIRO, NASR));

        ResponseEntity<Map> r = submit(a, token, custom(ALEX, ALEX_D, "  " + NEW_STREET + "  ", "Next to the club", "7B", "", "12"), null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().keySet()).containsExactlyInAnyOrder("reference", "status");
        Map<String, Object> rr = jdbc.queryForMap("SELECT * FROM return_requests WHERE tenant_id = ?", a.id());
        assertThat(rr).containsEntry("pickup_address_source", "custom").containsEntry("custom_first_line", NEW_STREET)
            .containsEntry("custom_second_line", "Next to the club").containsEntry("custom_building_number", "7B")
            .containsEntry("custom_apartment", "12").containsEntry("pickup_city_id", ALEX)
            .containsEntry("pickup_city_name", "Alexandria").containsEntry("pickup_district_id", ALEX_D)
            .containsEntry("pickup_district_name", "Smouha").containsEntry("pickup_district_name_ar", "سموحة");
        assertThat(rr.get("custom_floor")).as("blank → null").isNull();
    }

    @Test
    void submit_custom_invalid_400_nothingStored() {
        String token = token(a, deliveredOrder(a, "#3002", CAIRO, NASR));

        assertThat(submit(a, token, custom(ALEX, ALEX_D, null, null, null, null, null), null).getStatusCode())
            .as("missing street").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, custom(ALEX, ALEX_D, "12 St", null, null, null, null), null).getStatusCode())
            .as("5 characters is too short").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, custom(ALEX, NASR, NEW_STREET, null, null, null, null), null).getStatusCode())
            .as("district of another city").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, custom(CAIRO, D11, NEW_STREET, null, null, null, null), null).getStatusCode())
            .as("not pickup-available").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, custom(GIZA, "GizaD1", NEW_STREET, null, null, null, null), null).getStatusCode())
            .as("city without pickup").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, custom(null, ALEX_D, NEW_STREET, null, null, null, null), null).getStatusCode())
            .as("missing city").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, custom(ALEX, ALEX_D, NEW_STREET, null, "x".repeat(21), null, null), null).getStatusCode())
            .as("building too long").isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> other = custom(ALEX, ALEX_D, NEW_STREET, null, null, null, null);
        other.put("addressSource", "somewhere");
        assertThat(submit(a, token, other, null).getStatusCode()).as("unknown source").isEqualTo(HttpStatus.BAD_REQUEST);

        jdbc.update("UPDATE tenants SET portal_pickup_booking = false WHERE id = ?", a.id());
        assertThat(submit(a, token, custom(ALEX, ALEX_D, NEW_STREET, null, null, null, null), null).getStatusCode())
            .as("store doesn't book pickups").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE tenant_id = ?", Integer.class, a.id())).isZero();
    }

    @Test
    void submit_custom_exchange_pickupOnlyDistrictRefused_pickupAndDropoffAccepted() {
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = true WHERE id = ?", a.id());
        String token = token(a, deliveredOrder(a, "#3003", ALEX, ALEX_D));

        assertThat(submit(a, token, custom(CAIRO, D10, NEW_STREET, null, null, null, null), a.replacement()).getStatusCode())
            .as("pickup-only district for an exchange").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token, custom(CAIRO, NASR, NEW_STREET, null, null, null, null), a.replacement()).getStatusCode())
            .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForMap("SELECT type, pickup_address_source, pickup_district_id FROM return_requests WHERE tenant_id = ?", a.id()))
            .containsEntry("type", "exchange").containsEntry("pickup_address_source", "custom").containsEntry("pickup_district_id", NASR);
    }

    @Test
    void submit_orderSource_unchanged_explicitOrDefault() {
        String token = token(a, deliveredOrder(a, "#3004", CAIRO, NASR));
        Map<String, Object> explicit = new HashMap<>();
        explicit.put("addressSource", "order");
        explicit.put("districtId", D10);
        explicit.put("firstLine", "ignored for the order address");
        assertThat(submit(a, token, explicit, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String token2 = token(a, deliveredOrder(a, "#3005", CAIRO, NASR));
        assertThat(submit(a, token2, new HashMap<>(Map.of("districtId", ALEX_D)), null).getStatusCode())
            .as("the order address still only offers the delivery city's areas").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit(a, token2, new HashMap<>(Map.of("districtId", NASR)), null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT pickup_address_source, custom_first_line, pickup_city_id FROM return_requests WHERE tenant_id = ?", a.id());
        assertThat(rows).hasSize(2).allSatisfy(r -> assertThat(r).containsEntry("pickup_address_source", "order")
            .containsEntry("custom_first_line", null).containsEntry("pickup_city_id", CAIRO));
    }

    // ── Merchant drawer ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void drawer_customAddressShown_orderSourceHasNone() {
        UUID custom = customRequest(a, deliveredOrder(a, "#4001", CAIRO, NASR));
        UUID plain  = orderRequest(a, deliveredOrder(a, "#4002", CAIRO, NASR));

        Map<String, Object> d = get("/api/v1/return-requests/" + custom, ownerA).getBody();
        assertThat(d).containsEntry("pickupAddressSource", "custom").containsEntry("pickupCityName", "Alexandria")
            .containsEntry("pickupDistrictName", "Smouha");
        assertThat((Map<String, Object>) d.get("customAddress")).containsEntry("firstLine", NEW_STREET)
            .containsEntry("secondLine", "Next to the club").containsEntry("buildingNumber", "7B")
            .containsEntry("floor", "3").containsEntry("apartment", "12").containsEntry("redacted", false);

        Map<String, Object> p = get("/api/v1/return-requests/" + plain, ownerA).getBody();
        assertThat(p).containsEntry("pickupAddressSource", "order").doesNotContainKey("customAddress");
    }

    @Test
    @SuppressWarnings("unchecked")
    void drawer_crossTenant_onAppUser_404_withSameTenantPositiveControl() {
        UUID mine   = customRequest(a, deliveredOrder(a, "#5001", CAIRO, NASR));
        UUID theirs = customRequest(b, deliveredOrder(b, "#5001", CAIRO, NASR));

        Map<String, Object> d = TenantContext.runAs(a.id(), () -> appUserTx.execute(s -> appUserRequests.detail(mine)));
        assertThat((Map<String, Object>) d.get("customAddress")).as("positive control").containsEntry("firstLine", NEW_STREET);

        assertThatThrownBy(() -> TenantContext.runAs(a.id(), () -> appUserTx.execute(s -> appUserRequests.detail(theirs))))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    // ── Privacy ───────────────────────────────────────────────────────────────

    static final String EMAIL = "mona.customer@example.com";
    static final String NOTE = "Please call before coming, the bell is broken";

    @Test
    @SuppressWarnings("unchecked")
    void customersRedact_clearsEmailNoteAndCustomAddress_onThatOrdersRequests_only() {
        Order redacted = deliveredOrder(a, "#6001", CAIRO, NASR);
        Order kept     = deliveredOrder(a, "#6002", CAIRO, NASR);
        UUID gone      = customRequest(a, redacted);
        UUID goneOrder = orderRequest(a, redacted);          // a second, delivery-address request on the same order
        UUID stay      = customRequest(a, kept);
        jdbc.update("UPDATE return_requests SET customer_email = ?, customer_note = ? WHERE tenant_id = ?", EMAIL, NOTE, a.id());

        String numeric = redacted.externalId().substring("gid://shopify/Order/".length());
        processorJob.process(event(a, "customers/redact", "{\"orders_to_redact\":[{\"id\":" + numeric + "}]}"), a.id());

        for (UUID id : List.of(gone, goneOrder)) {
            Map<String, Object> g = jdbc.queryForMap("SELECT * FROM return_requests WHERE id = ?", id);
            for (String c : List.of("customer_email", "customer_note", "custom_first_line", "custom_second_line",
                                    "custom_building_number", "custom_floor", "custom_apartment")) {
                assertThat(g.get(c)).as(id + " " + c).isNull();
            }
            assertThat(g.get("pii_redacted_at")).isNotNull();
        }
        Map<String, Object> g = jdbc.queryForMap("SELECT * FROM return_requests WHERE id = ?", gone);
        assertThat(g).as("source and area snapshot stay").containsEntry("pickup_address_source", "custom")
            .containsEntry("pickup_district_id", ALEX_D);
        assertThat(jdbc.queryForMap("SELECT customer_email, customer_note, custom_first_line, pii_redacted_at FROM return_requests WHERE id = ?", stay))
            .as("another order's request untouched").containsEntry("customer_email", EMAIL).containsEntry("customer_note", NOTE)
            .containsEntry("custom_first_line", NEW_STREET).containsEntry("pii_redacted_at", null);

        Map<String, Object> d = get("/api/v1/return-requests/" + gone, ownerA).getBody();
        assertThat(d).containsEntry("email", null).containsEntry("note", null).containsEntry("piiRedacted", true);
        assertThat((Map<String, Object>) d.get("customAddress")).containsEntry("redacted", true).containsEntry("firstLine", null);
        assertThat(get("/api/v1/return-requests/" + stay, ownerA).getBody()).containsEntry("piiRedacted", false)
            .containsEntry("email", EMAIL);

        // A second delivery is a no-op (the marker keeps its first time).
        Object first = jdbc.queryForObject("SELECT pii_redacted_at FROM return_requests WHERE id = ?", Object.class, gone);
        processorJob.process(event(a, "customers/redact", "{\"orders_to_redact\":[{\"id\":" + numeric + "}]}"), a.id());
        assertThat(jdbc.queryForObject("SELECT pii_redacted_at FROM return_requests WHERE id = ?", Object.class, gone)).isEqualTo(first);
    }

    @Test
    void shopRedact_clearsEveryRequestsEmailNoteAndCustomAddress_ofTheTenant_notOtherTenants() {
        UUID a1 = customRequest(a, deliveredOrder(a, "#7001", CAIRO, NASR));
        UUID a2 = orderRequest(a, deliveredOrder(a, "#7002", CAIRO, NASR));
        UUID b1 = customRequest(b, deliveredOrder(b, "#7001", CAIRO, NASR));
        jdbc.update("UPDATE return_requests SET customer_email = ?, customer_note = ? WHERE id IN (?, ?, ?)", EMAIL, NOTE, a1, a2, b1);

        processorJob.process(event(a, "shop/redact", "{}"), a.id());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_requests WHERE id IN (?, ?) AND customer_email IS NULL " +
            "AND customer_note IS NULL AND custom_first_line IS NULL AND pii_redacted_at IS NOT NULL", Integer.class, a1, a2)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT customer_email, customer_note, custom_first_line FROM return_requests WHERE id = ?", b1))
            .containsEntry("customer_email", EMAIL).containsEntry("customer_note", NOTE).containsEntry("custom_first_line", NEW_STREET);
    }

    /**
     * Nothing else holds a copy: after a real portal submission with email, note and a typed address,
     * and the merchant's approve / reject path, no return_request_events metadata and no exception
     * payload contains any of them — so the redaction above is complete.
     */
    @Test
    void noCopies_inRequestEvents_orExceptions() throws Exception {
        String token = token(a, deliveredOrder(a, "#9001", CAIRO, NASR));
        Map<String, Object> body = custom(ALEX, ALEX_D, NEW_STREET, "Next to the club", "7B", "3", "12");
        body.put("email", EMAIL);
        body.put("note", NOTE);
        assertThat(submit(a, token, body, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = jdbc.queryForObject("SELECT id FROM return_requests WHERE tenant_id = ?", UUID.class, a.id());
        // Make the request show up in the exception detectors (a failed booking) and give it history.
        jdbc.update("UPDATE return_requests SET status = 'approved', decided_at = now(), booking_status = 'failed', " +
                    "booking_error = 'Invalid district for the given city (Bosta error 3004)', booking_attempted_at = now() " +
                    "WHERE id = ?", id);
        jdbc.update("INSERT INTO return_request_events (tenant_id, request_id, event_type, metadata) " +
                    "VALUES (?, ?, 'approved', '{}'::jsonb)", a.id(), id);

        String events = String.join("\n", jdbc.queryForList(
            "SELECT COALESCE(metadata::text, '') FROM return_request_events WHERE request_id = ?", String.class, id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_request_events WHERE request_id = ?", Integer.class, id))
            .as("history exists").isGreaterThanOrEqualTo(2);
        String exceptions = mapper.writeValueAsString(get("/api/v1/exceptions", ownerA).getBody());
        assertThat(exceptions).as("positive control: the request is in the exceptions list").contains("pickup_booking_problem");
        for (String pii : List.of(EMAIL, NOTE, NEW_STREET, "Next to the club")) {
            assertThat(events).as("events: " + pii).doesNotContain(pii);
            assertThat(exceptions).as("exceptions: " + pii).doesNotContain(pii);
        }
    }

    @Test
    void schema_customShape_enforced() {
        Order o = deliveredOrder(a, "#8001", CAIRO, NASR);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, pickup_address_source, custom_first_line) " +
            "VALUES (?, ?, 'requested', ?, 'custom', '12 St')", a.id(), o.id(), reference()))
            .as("custom street of 5 characters").hasMessageContaining("return_requests_custom_address_shape");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, pickup_address_source, custom_first_line) " +
            "VALUES (?, ?, 'requested', ?, 'order', ?)", a.id(), o.id(), reference(), NEW_STREET))
            .as("an order-source row carries no custom street").hasMessageContaining("return_requests_custom_address_shape");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, pickup_address_source) " +
            "VALUES (?, ?, 'requested', ?, 'elsewhere')", a.id(), o.id(), reference()))
            .as("unknown source (either CHECK may be the one reported)")
            .hasMessageMatching("(?s).*(return_requests_pickup_address_source_check|return_requests_custom_address_shape).*");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Tenant tenant(String name, String slug) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled) VALUES (?, ?, ?, true)", id, name, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            owner, id, "owner-" + owner + "@test.local", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        UUID p = UUID.randomUUID(), v = UUID.randomUUID(), r = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            p, id, store, "P-" + p);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'White / M', ?)",
            v, id, p, "V-" + v, "SKU-" + v.toString().substring(0, 6));
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, 'White / L', ?)",
            r, id, p, "V-" + r, "SKU-" + r.toString().substring(0, 6));
        for (int i = 0; i < 2; i++) {   // the replacement is in stock
            String piece = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
                piece, id, r, "PC-" + piece, piece, location);
        }
        return new Tenant(id, store, v, r, location, owner, slug);
    }

    private void district(String id, String cityId, String city, String cityAr, String zone, String zoneAr,
                          String name, String nameAr, boolean pickup, boolean dropoff) {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "    district_name, district_name_ar, pickup_available, dropoff_available) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, cityId, city, cityAr, "z-" + zone, zone, zoneAr, name, nameAr, pickup, dropoff);
    }

    /** A delivered order with one delivered piece and a forward leg whose raw carries a full address. */
    private Order deliveredOrder(Tenant t, String number, String cityId, String districtId) {
        String phone = "010" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        String externalId = "gid://shopify/Order/" + ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999_999L);
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona Customer', ?, 'bosta') RETURNING id",
            UUID.class, t.id(), t.store(), externalId, number, phone);
        Map<String, Object> drop = new LinkedHashMap<>();
        drop.put("firstLine", STREET);
        drop.put("city", Map.of("_id", cityId, "name", "City"));
        drop.put("district", Map.of("_id", districtId, "name", "District"));
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
        return new Order(order, number, phone, externalId);
    }

    private UUID customRequest(Tenant t, Order o) {
        return jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, pickup_city_id, pickup_city_name, " +
            "    pickup_district_id, pickup_district_name, pickup_district_name_ar, pickup_address_source, custom_first_line, " +
            "    custom_second_line, custom_building_number, custom_floor, custom_apartment) " +
            "VALUES (?, ?, 'requested', ?, ?, 'Alexandria', ?, 'Smouha', 'سموحة', 'custom', ?, 'Next to the club', '7B', '3', '12') RETURNING id",
            UUID.class, t.id(), o.id(), reference(), ALEX, ALEX_D, NEW_STREET);
    }

    private UUID orderRequest(Tenant t, Order o) {
        return jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, pickup_district_id) " +
            "VALUES (?, ?, 'requested', ?, ?) RETURNING id", UUID.class, t.id(), o.id(), reference(), NASR);
    }

    private static String reference() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder("RR-");
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }

    private static Map<String, Object> custom(String cityId, String districtId, String firstLine, String secondLine,
                                              String building, String floor, String apartment) {
        Map<String, Object> m = new HashMap<>();
        m.put("addressSource", "custom");
        m.put("cityId", cityId);
        m.put("districtId", districtId);
        m.put("firstLine", firstLine);
        m.put("secondLine", secondLine);
        m.put("buildingNumber", building);
        m.put("floor", floor);
        m.put("apartment", apartment);
        return m;
    }

    private UUID event(Tenant t, String topic, String payload) {
        String webhookId = "v117-" + UUID.randomUUID();
        jdbc.update("INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) VALUES (?, ?, ?, ?, ?::jsonb)",
            t.id(), topic, t.slug() + ".myshopify.com", webhookId, payload);
        return jdbc.queryForObject("SELECT id FROM shopify_webhook_events WHERE webhook_id = ?", UUID.class, webhookId);
    }

    private ResponseEntity<Map> lookup(Tenant t, Order o) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + "/api/v1/portal/" + t.slug() + "/lookup", HttpMethod.POST,
            new HttpEntity<>(Map.of("orderNumber", o.number(), "phone", o.phone()), h), Map.class);
    }

    private String token(Tenant t, Order o) {
        ResponseEntity<Map> r = lookup(t, o);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("token");
    }

    private ResponseEntity<Map> districts(Tenant t, String token, String cityId, String mode) {
        String url = base() + "/api/v1/portal/" + t.slug() + "/districts" + (cityId == null ? "" : "?cityId=" + cityId)
            + (mode == null ? "" : (cityId == null ? "?" : "&") + "mode=" + mode);
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(token == null ? new HttpHeaders() : bearer(token)), Map.class);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    /** One line of the tenant's variant; an exchange when {@code replacement} is given. */
    private ResponseEntity<Map> submit(Tenant t, String token, Map<String, Object> extra, UUID replacement) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("lines", List.of(Map.of("variantId", t.variant().toString(), "quantity", 1, "reasonCode", "wrong_size")));
        if (replacement != null) {
            body.put("mode", "exchange");
            body.put("replacementVariantId", replacement.toString());
        }
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        try {
            return jdkRest.exchange(base() + "/api/v1/portal/" + t.slug() + "/requests", HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body), h), Map.class);
        } catch (Exception e) { throw new RuntimeException(e); }
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

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
    }
}
