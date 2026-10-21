package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.traceability.identity.JwtService;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.portal.ReturnPickupBookingJob;
import com.traceability.portal.ReturnPickupBookingService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Non-Bosta couriers G4b — Bosta isolation, golden: the Bosta type-30 EXCHANGE flow end to end, on a tenant whose
 * main warehouse is linked to Shopify (so the Step-5a decrement really claims):
 *   X1 portal exchange request → approve (HTTP) → booking job (type-30 payload, local Bosta stub) → attachForRequest
 *      (exchanges row, internal order, forward leg via linkAtMapTime) → pick + pack → Pickups session close →
 *      exchange_dispatch claim;
 *   X2 the same, but the replacement leaves through the webhook path (type-30 'picked_up' via the real ingest);
 *   D  a dashboard-made exchange (no request): ingest → exchanges row needs_mapping → tryAutoMap → the
 *      exchange_needs_mapping alert;
 * then the exchanges rows, the exchange_dispatch claim rows, the internal orders and their legs, the drawer's
 * exchange progress, the Returns & exchanges cases list and counts, the request rows / events and the open alerts.
 * (GenericG4aIsolationGoldenTest keeps covering the refund portal, booking, Scan returns and analytics.)
 *
 * Recorded on origin/main b9ca5c2 — BEFORE any G4b code — with {@code -Dgolden.write=true}:
 *   g1 generic courier OFF → g4b-bosta-isolation-off.json; g2 ON, portal switch OFF and g3 ON + portal switch ON →
 *   g4b-bosta-isolation-on.json. Volatile values become stable tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericG4bIsolationGoldenTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    // ── Bosta stub: v2 create + v0 read (state changeable by the test) ────────────────────────────

    static final ObjectMapper M = new ObjectMapper();
    static final AtomicLong NEXT_TRACKING = new AtomicLong();
    static final List<JsonNode> POSTS = new CopyOnWriteArrayList<>();
    static final Map<String, ObjectNode> DELIVERIES = new ConcurrentHashMap<>();
    static final HttpServer BOSTA = startBosta();

    static HttpServer startBosta() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.setExecutor(Executors.newCachedThreadPool());
            s.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                try {
                    if ("POST".equals(ex.getRequestMethod()) && path.equals("/api/v2/deliveries")) {
                        JsonNode body = M.readTree(ex.getRequestBody().readAllBytes());
                        POSTS.add(body);
                        String tn = String.valueOf(NEXT_TRACKING.getAndIncrement());
                        DELIVERIES.put(tn, stored(body, tn));
                        send(ex, 200, "{\"success\":true,\"data\":{\"_id\":\"del-" + tn + "\",\"trackingNumber\":\"" + tn +
                            "\",\"state\":{\"code\":10,\"value\":\"Pickup requested\"}}}");
                        return;
                    }
                    if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/api/v0/deliveries/")) {
                        ObjectNode d = DELIVERIES.get(path.substring("/api/v0/deliveries/".length()));
                        if (d == null) { send(ex, 404, "{}"); return; }
                        send(ex, 200, "{\"data\":" + d + "}");
                        return;
                    }
                    send(ex, 404, "{}");
                } catch (Exception e) {
                    // close
                } finally {
                    ex.close();
                }
            });
            s.start();
            return s;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, int status, String json) throws java.io.IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(out); }
    }

    /** What Bosta stores for a created type-30 delivery: the customer on dropOffAddress, one item each way. */
    static ObjectNode stored(JsonNode body, String tn) {
        ObjectNode d = M.createObjectNode();
        d.put("_id", "del-" + tn);
        d.put("trackingNumber", tn);
        d.putObject("type").put("code", body.path("type").asInt()).put("value", "Exchange");
        d.putObject("state").put("code", 10).put("value", "Pickup requested");
        d.put("businessReference", body.path("businessReference").asText());
        d.put("cod", body.path("cod").asInt());
        if (body.has("specs")) d.set("specs", body.path("specs").deepCopy());
        if (body.has("returnSpecs")) d.set("returnSpecs", body.path("returnSpecs").deepCopy());
        ObjectNode c = d.putObject("dropOffAddress");
        c.put("firstLine", body.path("dropOffAddress").path("firstLine").asText());
        c.putObject("city").put("_id", CAIRO).put("name", "Cairo");
        c.putObject("district").put("_id", body.path("dropOffAddress").path("districtId").asText()).put("name", "Nasr City");
        d.putObject("pickupAddress").put("firstLine", "Merchant warehouse street");
        if (body.has("receiver")) d.set("receiver", body.path("receiver").deepCopy());
        d.put("createdAt", Instant.now().toString());
        d.put("updatedAt", Instant.now().toString());
        return d;
    }

    /** Bosta moves {@code tn} to {@code value} / {@code code} (latest completed timeline entry). */
    static void bostaState(String tn, String value, int code, Instant at) {
        ObjectNode d = DELIVERIES.get(tn);
        d.putObject("state").put("code", code).put("value", value);
        d.putArray("timeline").addObject().put("value", value).put("done", true).put("date", at.toString());
        d.put("updatedAt", at.toString());
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("bosta.base-url", () -> "http://127.0.0.1:" + BOSTA.getAddress().getPort());
    }

    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0";
    static final Path GOLDEN_OFF = Path.of("src/test/resources/golden/g4b-bosta-isolation-off.json");
    static final Path GOLDEN_ON  = Path.of("src/test/resources/golden/g4b-bosta-isolation-on.json");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired ReturnPickupBookingService booking;
    @Autowired FulfillService fulfill;
    @Autowired PickupSessionService pickups;
    @Autowired BostaIngestionHelper ingestion;
    @Autowired BostaWebhookJob webhookJob;
    @Autowired ExchangeService exchangeService;
    @Autowired ExceptionService exceptions;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  ShopifyTokenProvider tokenProvider;
    @MockBean  JobScheduler jobScheduler;

    @BeforeAll
    void districts() {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true)",
                    NASR, CAIRO);
    }

    @BeforeEach
    void stubs() {
        reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-golden");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn("gid://shopify/InventoryItem/1");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void g1_genericOff_identicalToPreG4bGolden() throws Exception {
        String out = scenario("G4bgold-off", 0L, false, false);
        check(out, GOLDEN_OFF, "off");
        assertThat(out).isEqualTo(Files.readString(GOLDEN_OFF));
    }

    @Test
    void g2_genericOn_portalSwitchOff_identicalToPreG4bGolden() throws Exception {
        String out = scenario("G4bgold-on", 1_000_000L, true, false);
        check(out, GOLDEN_ON, "on");
        assertThat(out).isEqualTo(Files.readString(GOLDEN_ON));
    }

    @Test
    void g3_genericOn_portalSwitchOn_identicalToPreG4bGolden() throws Exception {
        String out = scenario("G4bgold-on", 2_000_000L, true, true);
        check(out, null, "on-portal");
        assertThat(out).isEqualTo(Files.readString(GOLDEN_ON));
    }

    private void check(String out, Path golden, String tag) throws Exception {
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/g4b-bosta-isolation-" + tag + ".actual.json"), out);
        if (golden != null && Boolean.getBoolean("golden.write")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, out);
        }
    }

    // ── The scenario ─────────────────────────────────────────────────────────────────────────────

    record Ctx(UUID tenant, UUID store, UUID location, UUID variant, UUID variant2, UUID worker) {}
    record Ord(UUID id, String number, String piece) {}

    private String scenario(String name, long offset, boolean genericOn, boolean portalGenericOn) throws Exception {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = name.toLowerCase() + (offset == 0 ? "" : "-" + offset / 1_000_000L);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since, " +
            "    portal_exchanges_enabled, portal_exchanges_since) " +
            "VALUES (?, ?, ?, true, true, now() - interval '1 day', true, now() - interval '1 day')", tenant, name, slug);
        if (genericOn) jdbc.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", tenant);
        if (portalGenericOn) {
            jdbc.update("UPDATE tenants SET portal_generic_returns_enabled = true, portal_generic_returns_since = now() WHERE id = ?", tenant);
        }
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            store, tenant, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main', true, ?, 'linked')", location, tenant, "gid://shopify/Location/" + (900 + offset / 1_000_000L));
        jdbc.update("INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
            "    return_business_location_id, return_business_location_name) " +
            "VALUES (?, 'bosta', ?, ?, 'active', 'loc-ret', 'Maadi Warehouse')",
            tenant, encryption.encrypt("bosta-key-" + slug), "wh-" + tenant);
        UUID owner = user(tenant, "Mona", "owner"), worker = user(tenant, "Fatma", "worker");
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            product, tenant, store, "gid://shopify/Product/" + name + offset);
        UUID variant = variant(tenant, product, "Sand / M", name + offset + "-m");
        UUID variant2 = variant(tenant, product, "Sand / L", name + offset + "-l");
        Ctx c = new Ctx(tenant, store, location, variant, variant2, worker);
        long b = 8_484_805_000L + offset;
        NEXT_TRACKING.set(5_200_000_001L + offset);
        String key = "bosta-key-" + slug;

        Ord o1 = order(c, "#7001", b + 1);
        Ord o2 = order(c, "#7002", b + 2);
        String spare1 = stockPiece(c, variant2), spare2 = stockPiece(c, variant2);
        UUID x1 = exchangeRequest(c, o1, "RR-XCHNGA");
        UUID x2 = exchangeRequest(c, o2, "RR-XCHNGB");

        Map<String, Object> out = new LinkedHashMap<>();
        String token = jwt.issueAccessToken(owner, tenant, "owner");

        // X1 / X2 — approve → booking job (type 30) → attachForRequest
        out.put("01_approveX1", post(token, "/api/v1/return-requests/" + x1 + "/approve"));
        out.put("02_approveX2", post(token, "/api/v1/return-requests/" + x2 + "/approve"));
        out.put("03_enqueued", enqueued());
        booking.book(x1, tenant);
        booking.book(x2, tenant);
        out.put("04_exchangePosts", new ArrayList<>(POSTS));
        POSTS.clear();
        out.put("05_afterBooking", exchangeState(tenant));

        // pick + pack each replacement
        TenantContext.set(tenant);
        UUID io1 = internalOrder(x1), io2 = internalOrder(x2);
        out.put("06_packX1", List.of(attempt(() -> fulfill.scan(io1, spare1, worker)), attempt(() -> fulfill.complete(io1, worker))));
        out.put("07_packX2", List.of(attempt(() -> fulfill.scan(io2, spare2, worker)), attempt(() -> fulfill.complete(io2, worker))));
        out.put("08_afterPack", exchangeState(tenant));

        // X1 leaves at a Pickups session close
        String tn1 = trackingOf(x1), tn2 = trackingOf(x2);
        UUID pickup = pickups.openSession(tenant, worker, LocalDate.of(2026, 10, 22), "10:00-13:00", "g4b");
        out.put("09_pickupScanX1", attempt(() -> pickups.scan(tenant, pickup, worker, tn1)));
        out.put("10_pickupClose", attempt(() -> pickups.closeSession(tenant, pickup, worker)));
        awaitDispatch(tenant, 1);

        // X2 leaves through the webhook path
        bostaState(tn2, "picked_up", 21, Instant.parse("2026-10-22T10:00:00Z"));
        out.put("11_webhookX2", ingestAndProcess(tenant, key, tn2));
        awaitDispatch(tenant, 2);
        TenantContext.set(tenant);

        // D — a dashboard exchange (no request): ingest → needs_mapping → tryAutoMap
        String tnD = String.valueOf(5_300_000_001L + offset);
        ObjectNode dash = M.createObjectNode();
        dash.put("_id", "del-" + tnD);
        dash.put("trackingNumber", tnD);
        dash.putObject("type").put("code", 30).put("value", "Exchange");
        dash.putObject("state").put("code", 10).put("value", "Pickup requested");
        dash.putObject("specs").putObject("packageDetails").put("itemsCount", 1).put("description", "Linen Shirt Sand L");
        dash.putObject("returnSpecs").putObject("packageDetails").put("itemsCount", 1).put("description", "Linen Shirt Sand M");
        dash.put("cod", 0);
        dash.put("createdAt", "2026-10-22T09:00:00Z");
        dash.put("updatedAt", "2026-10-22T09:00:00Z");
        dash.putArray("timeline").addObject().put("value", "pickup_requested").put("done", true).put("date", "2026-10-22T09:00:00Z");
        DELIVERIES.put(tnD, dash);
        out.put("12_ingestDashboard", ingestAndProcess(tenant, key, tnD));
        TenantContext.set(tenant);
        out.put("13_tryAutoMap", attempt(() -> { exchangeService.tryAutoMap(tnD); return "ok"; }));
        // D2 — one that can't be mapped (no product by that name) → stays needs_mapping, raises exchange_needs_mapping
        String tnD2 = String.valueOf(5_300_000_002L + offset);
        ObjectNode dash2 = dash.deepCopy();
        dash2.put("_id", "del-" + tnD2);
        dash2.put("trackingNumber", tnD2);
        ((ObjectNode) dash2.path("specs").path("packageDetails")).put("description", "Velvet Bucket Hat");
        ((ObjectNode) dash2.path("returnSpecs").path("packageDetails")).put("description", "Velvet Bucket Hat red");
        DELIVERIES.put(tnD2, dash2);
        out.put("13b_ingestUnmappable", ingestAndProcess(tenant, key, tnD2));
        TenantContext.set(tenant);
        out.put("13c_tryAutoMapUnmappable", attempt(() -> { exchangeService.tryAutoMap(tnD2); return "ok"; }));
        jdbc.update("UPDATE exchanges SET created_at = now() - interval '2 days' WHERE tenant_id = ? AND tracking_number = ?", tenant, tnD2);

        // state dumps
        out.put("14_final", exchangeState(tenant));
        out.put("15_dispatchClaims", jdbc.queryForList(
            "SELECT a.trigger_type, a.delta, a.status, l.name AS location, p.status::text AS piece_status, " +
            "       o.external_id LIKE 'internal:exchange:%' AS internal_order " +
            "FROM shopify_inventory_adjustments a JOIN locations l ON l.id = a.location_id " +
            "JOIN pieces p ON p.id = a.trigger_id " +
            "LEFT JOIN allocations al ON al.piece_id = p.id LEFT JOIN order_items oi ON oi.id = al.order_item_id " +
            "LEFT JOIN orders o ON o.id = oi.order_id " +
            "WHERE a.tenant_id = ? AND a.trigger_type = 'exchange_dispatch' ORDER BY p.short_code", tenant));
        verify(shopifyGateway, times(2)).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.eq(-1), anyString(), anyString());
        TenantContext.set(tenant);
        out.put("16_alerts", exceptions.detectAllOpen().stream()
            .filter(e -> !"inventory_increment_sync_failed".equals(e.get("type")))
            .map(e -> e.get("type") + "|" + e.get("severity") + "|" + e.get("subject_type")).sorted().toList());
        TenantContext.clear();
        out.put("17_drawerX1", get(token, "/api/v1/return-requests/" + x1));
        out.put("18_drawerX2", get(token, "/api/v1/return-requests/" + x2));
        out.put("19_cases", get(token, "/api/v1/returns-exchanges"));
        out.put("20_caseCounts", get(token, "/api/v1/returns-exchanges/counts"));
        out.put("21_casesToDo", get(token, "/api/v1/returns-exchanges?stage=to_do"));
        out.put("22_casesInProgress", get(token, "/api/v1/returns-exchanges?stage=in_progress"));
        out.put("23_exchangesPage", get(token, "/api/v1/exchanges?size=50"));
        out.put("24_events", jdbc.queryForList(
            "SELECT o.number, e.event_type FROM return_request_events e JOIN return_requests rr ON rr.id = e.request_id " +
            "JOIN orders o ON o.id = rr.order_id WHERE e.tenant_id = ? ORDER BY o.number, e.occurred_at, e.event_type", tenant));

        String json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out);
        return stabilise(json, name, slug, offset);
    }

    private Map<String, Object> exchangeState(UUID tenant) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requests", jdbc.queryForList(
            "SELECT o.number, rr.type, rr.status::text AS status, rr.booking_status, rr.bosta_tracking_number IS NOT NULL AS has_tn, " +
            "       rr.booking_verified_at IS NOT NULL AS verified " +
            "FROM return_requests rr JOIN orders o ON o.id = rr.order_id WHERE rr.tenant_id = ? ORDER BY o.number", tenant));
        m.put("exchanges", jdbc.queryForList(
            "SELECT e.tracking_number, e.status, e.return_request_id IS NOT NULL AS from_request, e.auto_matched, " +
            "       e.outbound_description, e.inbound_description, o.external_id, o.number AS internal_number, " +
            "       o.status::text AS internal_status, mo.number AS matched_number " +
            "FROM exchanges e LEFT JOIN orders o ON o.id = e.outbound_order_id " +
            "LEFT JOIN orders mo ON mo.id = e.matched_order_id WHERE e.tenant_id = ? ORDER BY e.tracking_number", tenant));
        m.put("internalOrderLegs", jdbc.queryForList(
            "SELECT o.external_id, s.provider::text AS provider, s.shipment_leg, s.internal_state::text AS state, " +
            "       s.custody_locked_by_scan, " +
            "       (SELECT string_agg(h.internal_state || ':' || coalesce(h.provider_state::text, '-'), ',' ORDER BY h.occurred_at, h.id) " +
            "          FROM shipment_status_history h WHERE h.shipment_id = s.id) AS history, " +
            "       (SELECT string_agg(p.status::text, ',' ORDER BY p.status::text) FROM pieces p WHERE p.current_order_id = o.id) AS pieces " +
            "FROM orders o JOIN shipments s ON s.order_id = o.id WHERE o.tenant_id = ? AND o.external_id LIKE 'internal:exchange:%' " +
            "ORDER BY o.external_id, s.shipment_leg", tenant));
        return m;
    }

    private Object ingestAndProcess(UUID tenant, String key, String tn) {
        return attempt(() -> {
            boolean enqueued = TenantContext.runAs(tenant, () -> ingestion.ingestDelivery(tenant, key, tn, "bosta_poll"));
            Long eventId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
                "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, tenant, tn);
            webhookJob.process(eventId, tenant);
            TenantContext.clear();
            return Map.of("enqueued", enqueued, "webhook", jdbc.queryForObject(
                "SELECT status FROM webhook_events WHERE id = ?", String.class, eventId));
        });
    }

    private void awaitDispatch(UUID tenant, int n) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = 'exchange_dispatch' AND status <> 'pending'", Integer.class, tenant);
            if (done != null && done >= n) { Thread.sleep(200); return; }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + n + " exchange_dispatch claim(s)");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private UUID user(UUID tenant, String name, String role) {
        return jdbc.queryForObject("INSERT INTO users (tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, ?, 'x', ?::user_role, true) RETURNING id",
            UUID.class, tenant, name, name.toLowerCase() + "-" + UUID.randomUUID() + "@test.com", role);
    }

    private UUID variant(UUID tenant, UUID product, String title, String key) {
        return jdbc.queryForObject("INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, ?, ?) RETURNING id", UUID.class, tenant, product, "gid://shopify/ProductVariant/" + key,
            "SH-" + title.replace(" / ", ""), title);
    }

    /** A delivered order: Bosta forward leg with a full address raw, one delivered piece of the variant. */
    private Ord order(Ctx c, String number, long tn) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, customer_phone, cod_amount, " +
            "    payment_method, placed_at, created_at, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'Youssef Adel', ?, 1250.00, 'cod', now() - interval '10 days', " +
            "    now() - interval '10 days', 'bosta') RETURNING id",
            UUID.class, c.tenant(), c.store(), "gid://shopify/Order/" + c.tenant() + number, number, "0101111" + number.substring(1));
        String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"}," +
            "\"dropOffAddress\":{\"firstLine\":\"12 Placeholder Street\",\"secondLine\":\"Near the pharmacy\"," +
            "\"buildingNumber\":\"12\",\"floor\":\"3\",\"apartment\":\"7\",\"city\":{\"_id\":\"" + CAIRO + "\",\"name\":\"Cairo\"}," +
            "\"district\":{\"_id\":\"" + NASR + "\",\"name\":\"Nasr City\"}}," +
            "\"receiver\":{\"firstName\":\"Youssef\",\"lastName\":\"Adel\",\"fullName\":\"Youssef Adel\",\"phone\":\"+20101111" + number.substring(1) + "\"}}";
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, cod_amount, " +
            "    raw, delivered_at, created_at) VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', 1250.00, ?::jsonb, " +
            "    now() - interval '3 days', now() - interval '9 days')", c.tenant(), order, String.valueOf(tn), raw);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
            "VALUES (?, ?, ?, 1, '{\"price\": \"1250.00\"}'::jsonb) RETURNING id", UUID.class, c.tenant(), order, c.variant());
        String piece = piece(c, c.variant(), "delivered", order, item);
        return new Ord(order, number, piece);
    }

    private String piece(Ctx c, UUID variant, String status, UUID order, UUID item) {
        String id = UlidGenerator.generate();
        String code = "P" + String.format("%06d", Math.floorMod(id.hashCode(), 999_999) + 1);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, " +
            "    current_location_id, last_event_at, last_user_id) VALUES (?, ?, ?, ?, ?, ?::piece_status, ?, ?, now(), ?)",
            id, c.tenant(), variant, "PC-" + id, code, status, order, order == null ? c.location() : null, c.worker());
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, NULL, 'available'::piece_status)", c.tenant(), id, c.worker());
        if (item != null) {
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
                c.tenant(), item, id);
        }
        return code;
    }

    private String stockPiece(Ctx c, UUID variant) {
        return piece(c, variant, "available", null, null);
    }

    private UUID exchangeRequest(Ctx c, Ord o, String reference) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, created_at, pickup_city_id, " +
            "    pickup_city_name, pickup_district_id, pickup_district_name) " +
            "VALUES (?, ?, 'exchange', 'requested', ?, now() - interval '3 hours', ?, 'Cairo', ?, 'Nasr City - 7th District') RETURNING id",
            UUID.class, c.tenant(), o.id(), reference, CAIRO, NASR);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, replacement_variant_id) " +
            "VALUES (?, ?, (SELECT id FROM pieces WHERE short_code = ?), ?, 'wrong_size', ?)",
            c.tenant(), id, o.piece(), c.variant(), c.variant2());
        return id;
    }

    private UUID internalOrder(UUID request) {
        return jdbc.queryForObject("SELECT outbound_order_id FROM exchanges WHERE return_request_id = ?", UUID.class, request);
    }

    private String trackingOf(UUID request) {
        return jdbc.queryForObject("SELECT bosta_tracking_number FROM return_requests WHERE id = ?", String.class, request);
    }

    @SuppressWarnings("unchecked")
    private List<String> enqueued() {
        ArgumentCaptor<IocJobLambda> captor = ArgumentCaptor.forClass(IocJobLambda.class);
        verify(jobScheduler, atLeast(0)).enqueue(captor.capture());
        List<String> ids = new ArrayList<>();
        for (IocJobLambda<ReturnPickupBookingJob> l : captor.getAllValues()) {
            ReturnPickupBookingJob job = mock(ReturnPickupBookingJob.class);
            try {
                l.accept(job);
            } catch (Exception e) {
                ids.add("not-a-booking-job");
                continue;
            }
            ArgumentCaptor<UUID> req = ArgumentCaptor.forClass(UUID.class);
            verify(job, atLeast(0)).book(req.capture(), any());
            req.getAllValues().forEach(u -> ids.add(u.toString()));
        }
        clearInvocations(jobScheduler);
        return ids;
    }

    private Object get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Object> r = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), Object.class);
        return Map.of("status", r.getStatusCode().value(), "body", String.valueOf(r.getBody() == null ? "" : json(r.getBody())));
    }

    private Object post(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Object> r = rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(null, h), Object.class);
        return Map.of("status", r.getStatusCode().value(), "body", String.valueOf(r.getBody() == null ? "" : json(r.getBody())));
    }

    private String json(Object o) {
        try {
            return mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Object attempt(Supplier<Object> call) {
        try {
            return call.get();
        } catch (ResponseStatusException e) {
            return Map.of("refused", e.getStatusCode().value(), "reason", String.valueOf(e.getReason()));
        } catch (RuntimeException e) {
            return Map.of("refused", e.getClass().getSimpleName(), "reason", String.valueOf(e.getMessage()));
        }
    }

    /** Ids, references, piece codes, tracking numbers, timestamps and dates → stable tokens. */
    private static String stabilise(String json, String name, String slug, long offset) {
        Map<String, String> seen = new LinkedHashMap<>();
        String[] patterns = {
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            "PC-[0-9A-HJKMNP-TV-Z]{26}", "\\b[0-9A-HJKMNP-TV-Z]{26}\\b",
            "\\bP[0-9]{6}\\b",
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}(:\\d{2})?)?",
            "\\b1[6-9]\\d{8}\\.\\d+\\b", "\\b1[6-9]\\d{11}\\b",
            "\\b\\d{4}-\\d{2}-\\d{2}\\b"
        };
        String s = json.replace(slug, "<slug>").replace(name, "<tenant>");
        for (String p : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(s);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                String token = seen.computeIfAbsent(m.group(), k -> "<v" + seen.size() + ">");
                m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(token));
            }
            m.appendTail(b);
            s = b.toString();
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(84|52|53)[0-9]{8}\\b").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            long v = Long.parseLong(m.group());
            String pre = m.group().substring(0, 2);
            long base = "84".equals(pre) ? 8_484_805_000L : "52".equals(pre) ? 5_200_000_001L : 5_300_000_001L;
            m.appendReplacement(b, ("84".equals(pre) ? "TN+" : "52".equals(pre) ? "BTN+" : "DTN+") + (v - base - offset));
        }
        m.appendTail(b);
        return b.toString();
    }
}
