package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
import com.traceability.security.EncryptionService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * G4b fix — Bosta isolation, golden: the portal settings JSON (raw response bytes) and the portal's exchange
 * offer, through the real settings endpoint, for a store WITHOUT the other-couriers switch:
 *   bosta    — Bosta connected with a return location: exchanges refused before pickup booking, booking on,
 *              exchanges on, the portal config + a Bosta order's lookup + an exchange submission, booking off
 *              (switches exchanges off), the portal again
 *   no-bosta — no Bosta account: exchanges and booking refused
 * Each runs on a store that doesn't ship with other couriers (g1/g3) and on one that does but has the
 * other-couriers switch off (g2/g4) — both must equal the same golden.
 * Recorded on origin/main 0fe62fe — BEFORE any of the G4b fix — with {@code -Dgolden.write=true}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericG4bFixBostaSettingsGoldenTest {

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
    static final Path GOLDEN_BOSTA    = Path.of("src/test/resources/golden/g4b-fix-bosta-settings.json");
    static final Path GOLDEN_NO_BOSTA = Path.of("src/test/resources/golden/g4b-fix-no-bosta-settings.json");

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
    final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    record T(UUID id, String slug, UUID store, UUID location, UUID m, UUID l, UUID worker, String ownerToken) {}

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
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-golden");
    }

    @Test void g1_bosta_noOtherCouriers()           throws Exception { check(bostaRun(false), GOLDEN_BOSTA); }
    @Test void g2_bosta_otherCouriersSwitchOff()    throws Exception { check(bostaRun(true), GOLDEN_BOSTA); }
    @Test void g3_noBosta_noOtherCouriers()         throws Exception { check(noBostaRun(false), GOLDEN_NO_BOSTA); }
    @Test void g4_noBosta_otherCouriersSwitchOff()  throws Exception { check(noBostaRun(true), GOLDEN_NO_BOSTA); }

    private void check(String out, Path golden) throws Exception {
        if (Boolean.getBoolean("golden.write") && !Files.exists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, out);
        }
        assertThat(out).isEqualTo(Files.readString(golden));
    }

    private String bostaRun(boolean shipsWithOtherCouriers) throws Exception {
        T t = tenant(true, shipsWithOtherCouriers);
        String number = "#9301";
        UUID order = deliveredBostaOrder(t, number);
        piece(t, t.l(), "available", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("s0_get", getSettings(t));
        out.put("s1_exchangesBeforeBooking", putSettings(t, Map.of("exchangesEnabled", true)));
        out.put("s2_bookingOn", putSettings(t, Map.of("pickupBooking", true)));
        out.put("s3_exchangesOn", putSettings(t, Map.of("exchangesEnabled", true)));
        out.put("s4_config", portal.config(t.slug()).orElseThrow());
        Map<String, Object> lookup = new LinkedHashMap<>(portal.lookup(t.slug(), number.substring(1), phone(number)).orElseThrow().body());
        String token = (String) lookup.remove("token");
        out.put("s5_lookup", lookup);
        PortalService.SubmitResult r = portal.submit(t.slug(), token, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(t.m(), 1, "wrong_size")), null, null, NASR, "exchange", t.l(), false)).orElseThrow();
        out.put("s6_submit", r.outcome().name());
        out.put("s6_request", jdbc.queryForMap("SELECT type, status::text AS status, booking_status FROM return_requests " +
            "WHERE order_id = ?", order));
        out.put("s7_bookingOff", putSettings(t, Map.of("pickupBooking", false)));
        out.put("s8_get", getSettings(t));
        out.put("s9_config", portal.config(t.slug()).orElseThrow());
        Map<String, Object> after = new LinkedHashMap<>(portal.lookup(t.slug(), number.substring(1), phone(number)).orElseThrow().body());
        after.remove("token");
        out.put("s9_lookup", after);
        verifyNoInteractions(bostaV2);
        return stabilise(json.writeValueAsString(out), t.slug());
    }

    private String noBostaRun(boolean shipsWithOtherCouriers) throws Exception {
        T t = tenant(false, shipsWithOtherCouriers);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("s0_get", getSettings(t));
        out.put("s1_exchanges", putSettings(t, Map.of("exchangesEnabled", true)));
        out.put("s2_booking", putSettings(t, Map.of("pickupBooking", true)));
        out.put("s3_both", putSettings(t, Map.of("pickupBooking", true, "exchangesEnabled", true)));
        out.put("s4_get", getSettings(t));
        out.put("s5_config", portal.config(t.slug()).orElseThrow());
        return stabilise(json.writeValueAsString(out), t.slug());
    }

    /** UUIDs and timestamps → tokens numbered by first appearance; the store's own slug → SLUG. */
    static String stabilise(String s, String slug) {
        s = s.replace(slug, "SLUG");
        s = tokens(s, Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "U");
        return tokens(s, Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?"), "T");
    }

    private static String tokens(String s, Pattern p, String prefix) {
        Map<String, String> seen = new LinkedHashMap<>();
        Matcher m = p.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, seen.computeIfAbsent(m.group(), k -> prefix + (seen.size() + 1)));
        m.appendTail(b);
        return b.toString();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private T tenant(boolean bosta, boolean shipsWithOtherCouriers) {
        int n = SEQ.incrementAndGet();
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = "g4bfixgolden-" + n + "-" + id.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, generic_courier_enabled) VALUES (?, 'Golden Store', ?, true, ?)",
            id, slug, shipsWithOtherCouriers);
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

    private static String phone(String number) { return "0105555" + number.substring(1); }

    private UUID deliveredBostaOrder(T t, String number) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at) VALUES (?, ?, ?, ?, 'delivered', 'Youssef Adel', ?, 900, 'cod', now() - interval '8 days') " +
            "RETURNING id", UUID.class, t.id(), t.store(), "gid://shopify/Order/" + t.id() + number, number, phone(number));
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, t.id(), order, t.m());
        String piece = piece(t, t.m(), "delivered", order);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.id(), item, piece);
        String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"},\"dropOffAddress\":{\"firstLine\":\"12 Street\"," +
            "\"city\":{\"_id\":\"" + CAIRO + "\",\"name\":\"Cairo\"},\"district\":{\"_id\":\"" + NASR + "\",\"name\":\"Nasr City\"}}," +
            "\"receiver\":{\"firstName\":\"Youssef\",\"lastName\":\"Adel\",\"phone\":\"+20105555\"}}";
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw, " +
            "    delivered_at) VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', ?::jsonb, now() - interval '2 days')",
            t.id(), order, String.valueOf(4_400_000_000L + SEQ.incrementAndGet()), raw);
        return order;
    }

    /** Returns the piece id. */
    private String piece(T t, UUID variant, String status, UUID order) {
        String id = UlidGenerator.generate();
        String code = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id, " +
            "    last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, t.id(), variant, "PC-" + id, code, status, order, order == null ? t.location() : null, t.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", t.id(), id, t.worker());
        return id;
    }

    /** The raw response: status + body bytes exactly as sent. */
    private Map<String, Object> putSettings(T t, Map<String, Object> changes) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", t.slug());
        body.put("enabled", true);
        body.put("autoApprove", false);
        body.put("returnWindowDays", 14);
        body.putAll(new TreeMap<>(changes));
        ResponseEntity<String> r = rest.exchange("http://localhost:" + port + "/api/v1/tenant/portal-settings", HttpMethod.PUT,
            new HttpEntity<>(body, headers(t)), String.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.getStatusCode().value());
        m.put("body", r.getBody());
        return m;
    }

    private String getSettings(T t) {
        return rest.exchange("http://localhost:" + port + "/api/v1/tenant/portal-settings", HttpMethod.GET,
            new HttpEntity<>(null, headers(t)), String.class).getBody();
    }

    private HttpHeaders headers(T t) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(t.ownerToken());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
