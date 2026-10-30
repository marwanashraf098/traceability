package com.traceability;

import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
import com.traceability.security.EncryptionService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * G4b fix — "Allow exchanges" for a store that accepts returns for orders shipped with other couriers, switched on
 * through the REAL settings endpoints (never by fixture):
 *   e1  no Bosta: refused (EXCHANGES_NEEDS_BOSTA) until the other-couriers switch is on; then on → the portal
 *       offers Exchange for a delivered generic order → submit → approve → 'manual', replacement order, no Bosta
 *   e2  other couriers switched off afterwards: "Allow exchanges" stays on, the generic order is no longer
 *       eligible; switching exchanges on again needs Bosta again
 *   e3  mixed store (Bosta connected, pickup booking off): exchanges on for other couriers only — a Bosta order is
 *       not offered an exchange (exchangesOffered:false, submit refused) until Bosta pickup booking is on; turning
 *       pickup booking off then keeps exchanges on
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericG4bExchangeSettingsTest {

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
    }

    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionService encryption;
    @Autowired PortalService portal;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler jobScheduler;
    @MockBean  BostaV2Client bostaV2;

    static final AtomicInteger SEQ = new AtomicInteger();

    record T(UUID id, String slug, UUID store, UUID location, UUID m, UUID l, UUID worker, String ownerToken) {}
    record O(UUID id, String number, String piece, String phone) {}

    @BeforeAll
    void districts() {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true) " +
                    "ON CONFLICT DO NOTHING", NASR, CAIRO);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider, bostaV2);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-g4bfix");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void e1_noBosta_exchangesOnThroughSettings_portalOffersExchangeForGenericOrder() {
        T t = tenant(false);
        ResponseEntity<Map> refused = putSettings(t, Map.of("exchangesEnabled", true));
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody()).containsEntry("error", "EXCHANGES_NEEDS_BOSTA");

        assertThat(otherCouriers(t, true).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> on = putSettings(t, Map.of("exchangesEnabled", true));
        assertThat(on.getStatusCode().value()).as(String.valueOf(on.getBody())).isEqualTo(200);
        assertThat(on.getBody()).containsEntry("exchangesEnabled", true).containsEntry("bostaConnected", false)
            .containsEntry("pickupBooking", false);
        assertThat(jdbc.queryForObject("SELECT portal_exchanges_since IS NOT NULL FROM tenants WHERE id = ?", Boolean.class, t.id()))
            .isTrue();

        O o = deliveredGeneric(t, "#9101");
        stockPiece(t, t.l());
        assertThat(portal.config(t.slug()).orElseThrow()).containsEntry("exchangesEnabled", true);
        Map<String, Object> body = lookup(t, o);
        assertThat(body).doesNotContainKey("exchangesOffered").containsKey("otherCourier");
        assertThat(firstLine(body)).containsKey("exchangeOptions");

        PortalService.SubmitResult r = submitExchange(t, o, (String) body.get("token"));
        assertThat(r.outcome()).as(String.valueOf(r.body())).isEqualTo(PortalService.SubmitOutcome.CREATED);
        UUID rr = jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ?", UUID.class, o.id());
        assertThat(post(t, "/api/v1/return-requests/" + rr + "/approve", null).getStatusCode().value()).isEqualTo(204);
        Map<String, Object> row = jdbc.queryForMap("SELECT type, status::text AS status, booking_status, replacement_order_id " +
            "FROM return_requests WHERE id = ?", rr);
        assertThat(row).containsEntry("type", "exchange").containsEntry("status", "approved").containsEntry("booking_status", "manual");
        assertThat(row.get("replacement_order_id")).isNotNull();
        verifyNoInteractions(bostaV2);
    }

    @Test
    void e2_otherCouriersOff_exchangesStayOn_genericOrderNoLongerEligible() {
        T t = tenant(false);
        otherCouriers(t, true);
        assertThat(putSettings(t, Map.of("exchangesEnabled", true)).getStatusCode().value()).isEqualTo(200);
        O o = deliveredGeneric(t, "#9102");
        stockPiece(t, t.l());
        assertThat(portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow().outcome())
            .isEqualTo(PortalService.Outcome.SUCCESS);

        assertThat(otherCouriers(t, false).getStatusCode().value()).isEqualTo(200);
        assertThat(getSettings(t)).containsEntry("exchangesEnabled", true);
        assertThat(portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow().outcome())
            .isNotEqualTo(PortalService.Outcome.SUCCESS);

        assertThat(putSettings(t, Map.of("exchangesEnabled", false)).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> again = putSettings(t, Map.of("exchangesEnabled", true));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody()).containsEntry("error", "EXCHANGES_NEEDS_BOSTA");
    }

    @Test
    void e3_mixedStore_bostaOrdersNeedPickupBooking_bookingOffKeepsExchangesOn() {
        T t = tenant(true);
        otherCouriers(t, true);
        assertThat(putSettings(t, Map.of("exchangesEnabled", true)).getStatusCode().value()).isEqualTo(200);
        O bosta = deliveredBosta(t, "#9103");
        O generic = deliveredGeneric(t, "#9104");
        stockPiece(t, t.l());

        Map<String, Object> b = lookup(t, bosta);
        assertThat(b).containsEntry("exchangesOffered", false);
        assertThat(firstLine(b)).doesNotContainKey("exchangeOptions");
        PortalService.SubmitResult refused = submitExchange(t, bosta, (String) b.get("token"));
        assertThat(refused.outcome()).isEqualTo(PortalService.SubmitOutcome.INVALID);
        Map<String, Object> g = lookup(t, generic);
        assertThat(g).doesNotContainKey("exchangesOffered");
        assertThat(firstLine(g)).containsKey("exchangeOptions");

        assertThat(putSettings(t, Map.of("pickupBooking", true)).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> b2 = lookup(t, bosta);
        assertThat(b2).doesNotContainKey("exchangesOffered");
        assertThat(firstLine(b2)).containsKey("exchangeOptions");

        ResponseEntity<Map> off = putSettings(t, Map.of("pickupBooking", false));
        assertThat(off.getBody()).containsEntry("pickupBooking", false).containsEntry("exchangesEnabled", true);
        assertThat(lookup(t, bosta)).containsEntry("exchangesOffered", false);
        verifyNoInteractions(bostaV2);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /** A store that ships with other couriers (G3 on), other-couriers returns OFF, pickup booking and exchanges OFF. */
    private T tenant(boolean bosta) {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "g4bfix-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled) " +
            "VALUES (?, ?, ?, true, true)", id, "G4b fix " + n, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, id);
        if (bosta) {
            jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
                "    return_business_location_id, return_business_location_name) VALUES (?, 'bosta', ?, ?, 'active', 'loc', 'Maadi')",
                id, encryption.encrypt("key-" + slug), "wh-" + id);
        }
        UUID owner = user(id, "owner"), worker = user(id, "worker");
        UUID product = jdbc.queryForObject("INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'Linen Shirt', 'active') RETURNING id", UUID.class, id, store, "gid://shopify/Product/" + slug);
        UUID m = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'SH-M', 'Sand / M') RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-m");
        UUID l = jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, 'SH-L', 'Sand / L') RETURNING id", UUID.class, id, product, "gid://shopify/ProductVariant/" + slug + "-l");
        return new T(id, slug, store, location, m, l, worker, jwt.issueAccessToken(owner, id, "owner"));
    }

    private UUID user(UUID tenant, String role) {
        return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id",
            UUID.class, tenant, role, role + "-" + UUID.randomUUID() + "@test.com", role);
    }

    private static String phone(String number) { return "0104444" + number.substring(1); }

    private UUID order(T t, String number, String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, shopify_address) " +
            "VALUES (?, ?, ?, ?, ?::order_status, 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days', " +
            "    '{\"address1\": \"5 Tahrir St\", \"city\": \"Cairo\"}'::jsonb) RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, status, phone(number));
    }

    /** A generic order handed over at Pickups, then marked delivered by the owner (the real endpoint). */
    private O deliveredGeneric(T t, String number) {
        UUID order = order(t, number, "with_courier");
        String piece = allocated(t, order, "with_courier");
        String tracking = "AR-" + number.substring(1);
        UUID leg = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, tracking_key, carrier_name, internal_state, " +
            "    shipment_leg, source, handed_over_at, created_at) " +
            "VALUES (?, ?, 'generic', ?, ?, 'Aramex', 'with_courier', 'forward', 'shopify_fulfillment', now() - interval '6 days', " +
            "    now() - interval '7 days') RETURNING id",
            UUID.class, t.id(), order, tracking, tracking.replace("-", ""));
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at, source) " +
            "VALUES (?, ?, 'with_courier', now() - interval '6 days', 'pickup_scan')", t.id(), leg);
        assertThat(post(t, "/api/v1/generic-shipments/mark-delivered", Map.of("orderIds", List.of(order)))
            .getStatusCode().is2xxSuccessful()).isTrue();
        return new O(order, number, piece, phone(number));
    }

    private O deliveredBosta(T t, String number) {
        UUID order = order(t, number, "delivered");
        String piece = allocated(t, order, "delivered");
        String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"},\"dropOffAddress\":{\"firstLine\":\"12 Street\"," +
            "\"city\":{\"_id\":\"" + CAIRO + "\",\"name\":\"Cairo\"},\"district\":{\"_id\":\"" + NASR + "\",\"name\":\"Nasr City\"}}," +
            "\"receiver\":{\"firstName\":\"Youssef\",\"lastName\":\"Adel\",\"phone\":\"+20104444\"}}";
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw, " +
            "    delivered_at) VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', ?::jsonb, now() - interval '2 days')",
            t.id(), order, String.valueOf(4_300_000_000L + SEQ.incrementAndGet()), raw);
        return new O(order, number, piece, phone(number));
    }

    private String allocated(T t, UUID order, String status) {
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, t.id(), order, t.m());
        String code = piece(t, t.m(), status, order);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.id(), item, pieceId(code));
        return code;
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

    private String pieceId(String code) {
        return jdbc.queryForObject("SELECT id FROM pieces WHERE short_code = ?", String.class, code);
    }

    private Map<String, Object> lookup(T t, O o) {
        PortalService.LookupResult r = portal.lookup(t.slug(), o.number().substring(1), o.phone()).orElseThrow();
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return r.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstLine(Map<String, Object> body) {
        return ((List<Map<String, Object>>) body.get("lines")).get(0);
    }

    /** The customer asks for size L instead of M. */
    private PortalService.SubmitResult submitExchange(T t, O o, String token) {
        // A Bosta order with pickup booking on is offered a pickup area — the district is then required.
        boolean pickup = jdbc.queryForObject("SELECT portal_pickup_booking FROM tenants WHERE id = ?", Boolean.class, t.id())
            && jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM shipments WHERE order_id = ? AND provider = 'bosta')",
                Boolean.class, o.id());
        return portal.submit(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.m(), 1, "wrong_size")), null, null, pickup ? NASR : null,
            "exchange", t.l(), false)).orElseThrow();
    }

    /** PUT /tenant/portal-settings — the merchant's "Save changes" (required fields + the changed ones). */
    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> putSettings(T t, Map<String, Object> changes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", t.slug());
        body.put("enabled", true);
        body.put("autoApprove", false);
        body.put("returnWindowDays", 14);
        body.putAll(changes);
        return rest.exchange("http://localhost:" + port + "/api/v1/tenant/portal-settings", HttpMethod.PUT,
            new HttpEntity<>(body, headers(t)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getSettings(T t) {
        return rest.exchange("http://localhost:" + port + "/api/v1/tenant/portal-settings", HttpMethod.GET,
            new HttpEntity<>(null, headers(t)), Map.class).getBody();
    }

    private ResponseEntity<Object> otherCouriers(T t, boolean enabled) {
        return rest.exchange("http://localhost:" + port + "/api/v1/tenant/portal-settings/other-couriers", HttpMethod.PUT,
            new HttpEntity<>(Map.of("enabled", enabled), headers(t)), Object.class);
    }

    private ResponseEntity<Object> post(T t, String path, Object body) {
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, headers(t)), Object.class);
    }

    private HttpHeaders headers(T t) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t.ownerToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
