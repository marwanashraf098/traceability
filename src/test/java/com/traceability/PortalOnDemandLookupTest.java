package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.traceability.integrations.bosta.BostaRateLimiter;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalBostaBudget;
import com.traceability.portal.PortalOnDemandLookup;
import com.traceability.portal.PortalService;
import com.traceability.portal.ReturnPickupBookingService;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P4c — the returns portal's on-demand lookup of an order placed before the store connected.
 * Shopify is a mock (findOrdersByName / oldestOrder); Bosta is a real local HTTP stub (v0 read, v2
 * create) behind the real BostaGateway and BostaRateLimiter. The timing pad is 600 ms here (2500 in
 * application.yml); the portal's own Bosta budget is off (interval 0) except in its unit test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
@ExtendWith(OutputCaptureExtension.class)
class PortalOnDemandLookupTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    // ── Bosta stub ────────────────────────────────────────────────────────────

    static final Map<String, ObjectNode> DELIVERIES = new ConcurrentHashMap<>();
    static final AtomicInteger GETS = new AtomicInteger();
    /** Tracking numbers Bosta answers with a 500. */
    static final Set<String> FAIL_500 = ConcurrentHashMap.newKeySet();
    static final List<JsonNode> POSTS = new CopyOnWriteArrayList<>();
    static volatile String nextTracking = "7400000001";
    static final ObjectMapper M = new ObjectMapper();
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
                        String tn = nextTracking;
                        DELIVERIES.put(tn, created(body, tn));
                        send(ex, 200, "{\"success\":true,\"data\":{\"_id\":\"del-" + tn + "\",\"trackingNumber\":\"" + tn +
                            "\",\"state\":{\"code\":10,\"value\":\"Pickup requested\"},\"creationSrc\":\"API\"}}");
                        return;
                    }
                    if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/api/v0/deliveries/")) {
                        GETS.incrementAndGet();
                        String tnPath = path.substring("/api/v0/deliveries/".length());
                        if (FAIL_500.contains(tnPath)) { send(ex, 500, "{\"message\":\"Internal Server Error\"}"); return; }
                        ObjectNode d = DELIVERIES.get(tnPath);
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

    /** What Bosta stores for a delivery Traced creates (type 25: customer on pickupAddress; 30: on dropOffAddress). */
    static ObjectNode created(JsonNode body, String tn) {
        int type = body.path("type").asInt();
        ObjectNode d = M.createObjectNode();
        d.put("_id", "del-" + tn);
        d.put("trackingNumber", tn);
        d.putObject("type").put("code", type).put("value", type == 30 ? "Exchange" : "Customer Return Pickup");
        d.putObject("state").put("code", 10).put("value", "Pickup requested");
        d.put("businessReference", body.path("businessReference").asText());
        d.put("cod", body.path("cod").asInt());
        d.set("specs", body.path("specs").deepCopy());
        d.set("returnSpecs", body.path("returnSpecs").deepCopy());
        String customerSide = type == 30 ? "dropOffAddress" : "pickupAddress";
        ObjectNode customer = d.putObject(customerSide);
        customer.put("firstLine", body.path(customerSide).path("firstLine").asText());
        customer.putObject("district").put("_id", body.path(customerSide).path("districtId").asText());
        d.putObject(type == 30 ? "pickupAddress" : "dropOffAddress").put("firstLine", "Merchant warehouse street");
        d.set("receiver", body.path("receiver").deepCopy());
        d.put("createdAt", Instant.now().toString());
        d.put("updatedAt", Instant.now().toString());
        return d;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url",          POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user",         POSTGRES::getUsername);
        r.add("spring.flyway.password",     POSTGRES::getPassword);
        r.add("bosta.base-url", () -> "http://127.0.0.1:" + BOSTA.getAddress().getPort());
        r.add("bosta.create-read-timeout", () -> "1s");
        r.add("portal.preconnect.lookup-enabled", () -> "true");
        r.add("portal.preconnect.min-duration-ms", () -> String.valueOf(PAD_MS));
        r.add("portal.preconnect.bosta-interval-ms", () -> "0");
        // Prod: pad 4000, read reserve 2000, max wait 3000 — the same ratios, smaller.
        r.add("portal.preconnect.bosta-max-wait-ms", () -> "900");
        r.add("portal.preconnect.bosta-read-reserve-ms", () -> "600");
        r.add("portal.preconnect.tenant-per-hour", () -> "8");
        r.add("portal.preconnect.ip-per-hour", () -> "5");
        // The shared Bosta limiter's own per-key / global waits would blur the timing measurement; the
        // portal's budget and the limiter's max-wait cap are what these tests exercise.
        r.add("bosta.rate-limit.per-second", () -> "100");
        r.add("bosta.rate-limit.burst", () -> "100");
        r.add("bosta.rate-limit.global-per-second", () -> "100");
        r.add("bosta.rate-limit.global-burst", () -> "100");
    }

    static final long PAD_MS = 1200;
    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0";
    static final String PHONE = "01055501234";
    static final String CUSTOMER = "Salma Placeholder";
    static final String STREET = "9 Shopify Lane";
    static final String TOKEN = "shpat-p4c-secret";
    static final String KEY = "bosta-raw-key-p4c";

    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionService encryption;
    @Autowired PortalService portal;
    @Autowired PortalOnDemandLookup onDemand;
    @Autowired PortalBostaBudget realBudget;
    @Autowired BostaRateLimiter rateLimiter;
    @Autowired ReturnPickupBookingService booking;
    @Autowired FulfillService fulfill;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean JobScheduler jobScheduler;

    record T(UUID id, String slug, UUID store, UUID original, UUID replacement, String originalGid, UUID location, UUID owner) {}
    T a, b;

    @BeforeAll
    void setup() {
        a = tenant("p4c-a", true);
        b = tenant("p4c-b", false);   // no Bosta account → UNAVAILABLE
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true) " +
                    "ON CONFLICT (district_id) DO NOTHING", NASR, CAIRO);
    }

    @BeforeEach
    void reset() {
        DELIVERIES.clear();
        FAIL_500.clear();
        GETS.set(0);
        POSTS.clear();
        nextTracking = String.valueOf(ThreadLocalRandom.current().nextLong(7_400_000_000L, 7_499_999_999L));
        Mockito.reset(shopify, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn(TOKEN);
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(), true));
        when(shopify.oldestOrder(anyString(), anyString())).thenReturn(Optional.empty());
        onDemand.clearCaches();
        for (T t : List.of(a, b)) jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t.id());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    // ── gate: zero external calls ─────────────────────────────────────────────

    @Test
    void a1_flagOff_aMiss_isTodaysGenericNotFound_withZeroExternalCalls() {
        String tn = shopifyAndBosta("#1201", true);
        ReflectionTestUtils.setField(onDemand, "enabled", false);
        try {
            assertThat(lookup(a, "1201", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        } finally {
            ReflectionTestUtils.setField(onDemand, "enabled", true);
        }
        assertNoExternalCalls();
        assertThat(DELIVERIES).containsKey(tn);
    }

    @Test
    void a2_dbHit_isAnsweredFromTheDb_withZeroExternalCalls() {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_phone) " +
            "VALUES (?, ?, 'gid://shopify/Order/88002101', '#2101', 'delivered', 'cod', now(), ?) RETURNING id", UUID.class, a.id(), a.store(), PHONE);
        jdbc.update("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)", a.id(), order, a.original());
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
                    "VALUES (?, ?, 'bosta', '5402101000', 'delivered', 'forward', now() - interval '1 day')", a.id(), order);

        long t0 = System.nanoTime();
        assertThat(lookup(a, "2101", PHONE).outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(ms(t0)).as("a DB hit is not padded").isLessThan(PAD_MS);
        assertNoExternalCalls();
    }

    @Test
    void a3_anExistingPortalRow_neverFetchesAgain_evenWithAWrongPhone() {
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, origin, shopify_order_gid, " +
                    "    customer_phone, portal_delivered_at) " +
                    "VALUES (?, ?, ?, '#1301', 'delivered', 'portal_pre_connect', 'gid://shopify/Order/88001301', ?, now())",
            a.id(), a.store(), "internal:portal:" + UUID.randomUUID(), PHONE);
        assertThat(lookup(a, "1301", "01000000009").outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertNoExternalCalls();
    }

    @Test
    void a4_aNumberAtOrAboveTheLowestIngested_isNotFound_withZeroCalls() {
        // The tenant's lowest ingested order is #2000 (setup): #2050 would be in Traced if it existed.
        assertThat(lookup(a, "2050", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertNoExternalCalls();
    }

    @Test
    void a5_ineligibleStore_noBostaKey_todaysGenericMiss_unpadded_noAttempt_throttledLikeAnyMiss() {
        long t0 = System.nanoTime();
        assertThat(lookup(b, "1201", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(ms(t0)).as("no on-demand attempt → not padded").isLessThan(PAD_MS);
        assertNoExternalCalls();
        assertThat(attemptOutcomes(b)).as("a plain miss, no on-demand outcome").isEmpty();
        for (int i = 0; i < 4; i++) lookup(b, "1201", PHONE);
        assertThat(lookup(b, "1201", PHONE).outcome()).as("counts in the per-order throttle like any miss")
            .isEqualTo(PortalService.Outcome.THROTTLED);
    }

    @Test
    void a6_ineligibleStore_nullCutoff_or_twoConnectedStores_todaysGenericMiss_zeroCalls() {
        shopifyAndBosta("#1205", true);
        jdbc.update("UPDATE stores SET orders_ingest_from = NULL WHERE id = ?", a.store());
        try {
            long t0 = System.nanoTime();
            assertThat(lookup(a, "1205", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
            assertThat(ms(t0)).isLessThan(PAD_MS);
        } finally {
            jdbc.update("UPDATE stores SET orders_ingest_from = now() - interval '10 days' WHERE id = ?", a.store());
        }
        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, orders_ingest_from) " +
                    "VALUES (?, ?, 'p4c-a-second.myshopify.com', 'connected', now() - interval '10 days')", second, a.id());
        try {
            assertThat(lookup(a, "1205", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        } finally {
            jdbc.update("DELETE FROM stores WHERE id = ?", second);
        }
        assertNoExternalCalls();
        assertThat(attemptOutcomes(a)).isEmpty();
    }

    @Test
    void a7_transientBeforeThePhoneCheck_tokenOrShopifyFailure_unavailable_padded() {
        shopifyAndBosta("#1206", true);
        when(tokenProvider.getValidToken(any())).thenThrow(new RuntimeException("token refresh failed"));
        long t0 = System.nanoTime();
        assertThat(lookup(a, "1206", PHONE).outcome()).isEqualTo(PortalService.Outcome.UNAVAILABLE);
        assertThat(ms(t0)).isGreaterThanOrEqualTo(PAD_MS);

        doReturn(TOKEN).when(tokenProvider).getValidToken(any());
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenThrow(new com.traceability.integrations.shopify.ShopifyException("HTTP 503"));
        assertThat(lookup(a, "1206", PHONE).outcome()).isEqualTo(PortalService.Outcome.UNAVAILABLE);
        assertThat(GETS.get()).isZero();
        assertThat(attemptOutcomes(a)).containsExactly("unavailable", "unavailable");
    }

    @Test
    void a8_bosta5xxAfterThePhoneCheck_paddedGenericNotFound_notCached() {
        String tn = shopifyAndBosta("#1207", true);
        FAIL_500.add(tn);
        long t0 = System.nanoTime();
        PortalService.LookupResult r = lookup(a, "1207", PHONE);
        assertThat(r.outcome()).as("never UNAVAILABLE after the phone matched").isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(ms(t0)).isGreaterThanOrEqualTo(PAD_MS);
        assertThat(GETS.get()).isGreaterThanOrEqualTo(1);
        int gets = GETS.get();
        FAIL_500.clear();
        assertThat(lookup(a, "1207", PHONE).outcome()).as("an outage isn't cached as a fact about the order")
            .isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(GETS.get()).isGreaterThan(gets);
    }

    @Test
    void a9_realV0Body_throughTheRealFetchDeliveryMapping_deliveryTimeThenTimelineCode45() throws Exception {
        Instant delivered = Instant.now().minus(Duration.ofDays(3)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        String tn = "5551" + (100000 + ThreadLocalRandom.current().nextInt(899999));
        DELIVERIES.put(tn, realV0(tn, "#1208", delivered, true));
        stubShopify(order("#1208", Instant.now().minus(Duration.ofDays(20)), PHONE, List.of(a.originalGid()), tn));
        assertThat(lookup(a, "1208", PHONE).outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT portal_delivered_at FROM orders WHERE tenant_id = ? AND number = '#1208'",
            java.sql.Timestamp.class, a.id()).toInstant()).isEqualTo(delivered);

        // No state.deliveryTime → the timeline's code-45 entry (the same instant on prod raws).
        String tn2 = "5552" + (100000 + ThreadLocalRandom.current().nextInt(899999));
        DELIVERIES.put(tn2, realV0(tn2, "#1209", delivered, false));
        stubShopify(order("#1209", Instant.now().minus(Duration.ofDays(20)), PHONE, List.of(a.originalGid()), tn2));
        assertThat(lookup(a, "1209", PHONE).outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT portal_delivered_at FROM orders WHERE tenant_id = ? AND number = '#1209'",
            java.sql.Timestamp.class, a.id()).toInstant()).isEqualTo(delivered);

        // Delivered outside the 14-day window → generic not-found.
        String tn3 = "5553" + (100000 + ThreadLocalRandom.current().nextInt(899999));
        DELIVERIES.put(tn3, realV0(tn3, "#1210", Instant.now().minus(Duration.ofDays(30)), true));
        stubShopify(order("#1210", Instant.now().minus(Duration.ofDays(40)), PHONE, List.of(a.originalGid()), tn3));
        assertThat(lookup(a, "1210", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
    }

    // ── the fetch ─────────────────────────────────────────────────────────────

    @Test
    void b1_found_fetched_storedAsAPortalRow_thenTheNormalLookupAnswers() {
        String tn = shopifyAndBosta("#1201", true);

        PortalService.LookupResult r = lookup(a, "#1201", PHONE);

        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(r.body().get("orderNumber")).isEqualTo("#1201");
        assertThat(r.body().get("token")).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.body().get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).containsEntry("tracked", false);

        Map<String, Object> o = jdbc.queryForMap(
            "SELECT id, origin, external_id, shopify_order_gid, status::text AS status, customer_name, customer_phone, " +
            "       portal_fetched_at, portal_delivered_at, portal_delivery::text AS pd, payment_method::text AS pm " +
            "FROM orders WHERE tenant_id = ? AND number = '#1201'", a.id());
        assertThat(o.get("origin")).isEqualTo("portal_pre_connect");
        assertThat((String) o.get("external_id")).startsWith("internal:portal:");
        assertThat(o.get("shopify_order_gid")).isEqualTo("gid://shopify/Order/88001201");
        assertThat(o.get("customer_phone")).isEqualTo(PHONE);
        assertThat(o.get("customer_name")).isEqualTo(CUSTOMER);
        assertThat(o.get("pm")).isEqualTo("cod");
        assertThat(o.get("portal_fetched_at")).isNotNull();
        JsonNode pd = json((String) o.get("pd"));
        assertThat(pd.path("trackingNumber").asText()).isEqualTo(tn);
        assertThat(pd.path("dropOffAddress").path("city").path("_id").asText()).as("the shape P4b reads").isEqualTo(CAIRO);
        assertThat(pd.path("dropOffAddress").path("firstLine").asText()).isEqualTo("12 Bosta Street, Block 4");
        assertThat(pd.path("receiver").path("phone").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_orders WHERE id = ?", Integer.class, o.get("id")))
            .as("invisible to merchant code (V163 view)").isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_items WHERE order_id = ?", Integer.class, o.get("id"))).isEqualTo(1);
        assertThat(attemptOutcomes(a)).containsExactly("fetched");
        assertThat(GETS.get()).isEqualTo(1);

        // A second lookup is a plain DB hit now.
        Mockito.clearInvocations(shopify);
        assertThat(lookup(a, "1201", PHONE).outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        verify(shopify, never()).findOrdersByName(anyString(), anyString(), anyString());
    }

    @Test
    void b2_theFetchedOrder_flowsIntoAReturnBooking_andAnExchange() {
        shopifyAndBosta("#1202", true);
        assertThat(lookup(a, "1202", PHONE).outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        UUID order = jdbc.queryForObject("SELECT id FROM orders WHERE tenant_id = ? AND number = '#1202'", UUID.class, a.id());
        UUID item = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", UUID.class, order);

        // Refund → a type-25 pickup from the stored Bosta address, receiver = the order's phone.
        UUID refund = request(order, item, "refund");
        booking.book(refund, a.id());
        assertThat(POSTS).hasSize(1);
        assertThat(POSTS.get(0).path("type").asInt()).isEqualTo(25);
        assertThat(POSTS.get(0).path("pickupAddress").path("firstLine").asText()).isEqualTo("12 Bosta Street, Block 4");
        assertThat(POSTS.get(0).path("receiver").path("phone").asText()).isEqualTo(PHONE);

        // Exchange (the unit is free again once the refund request is closed out of the way).
        jdbc.update("UPDATE return_request_items SET active = false, item_status = 'not_coming' WHERE request_id = ?", refund);
        jdbc.update("UPDATE return_requests SET status = 'closed', close_reason = 'other' WHERE id = ?", refund);
        String stock = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available', ?)",
            stock, a.id(), a.replacement(), "PC-" + stock, stock, a.location());
        nextTracking = String.valueOf(ThreadLocalRandom.current().nextLong(7_500_000_000L, 7_599_999_999L));
        String tn = nextTracking;
        UUID exchange = request(order, item, "exchange");
        booking.book(exchange, a.id());
        assertThat(POSTS.get(1).path("type").asInt()).isEqualTo(30);
        UUID replacement = jdbc.queryForObject("SELECT outbound_order_id FROM exchanges WHERE tenant_id = ? AND tracking_number = ?",
            UUID.class, a.id(), tn);
        assertThat(jdbc.queryForObject("SELECT external_id FROM orders WHERE id = ?", String.class, replacement))
            .isEqualTo("internal:exchange:" + tn);
        List<Map<String, Object>> queue = TenantContext.runAs(a.id(), () -> fulfill.getQueue());
        assertThat(queue).extracting(m -> m.get("id")).contains(replacement).doesNotContain(order);
    }

    @Test
    void b3_phoneMismatch_genericNotFound_bostaNeverCalled_andTheRightPhoneDoesntAskShopifyAgain() {
        shopifyAndBosta("#1203", true);

        assertThat(lookup(a, "1203", "01099990000").outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(GETS.get()).as("Bosta only after the phone matched").isZero();
        verify(shopify, times(1)).findOrdersByName(anyString(), anyString(), anyString());

        assertThat(lookup(a, "1203", PHONE).outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        verify(shopify, times(1)).findOrdersByName(anyString(), anyString(), anyString());
        assertThat(GETS.get()).isEqualTo(1);
    }

    @Test
    void c1_tooOld_lowerThanTheOldestShopifyStillReturns_sameShape_decidedBeforeThePhone() {
        Instant oldestAt = Instant.parse("2026-08-12T09:00:00Z");
        when(shopify.oldestOrder(anyString(), anyString())).thenReturn(Optional.of(order("#1500", oldestAt, PHONE, List.of(), null)));

        PortalService.LookupResult r = lookup(a, "1200", "01000000000");   // wrong phone on purpose
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.TOO_OLD);
        assertThat(r.body()).containsEntry("date", LocalDate.ofInstant(oldestAt, ZoneId.of("Africa/Cairo")).toString());
        assertThat(GETS.get()).isZero();

        assertThat(lookup(a, "1600", PHONE).outcome()).as("same shape, not lower").isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(lookup(a, "ABC-77", PHONE).outcome()).as("incomparable").isEqualTo(PortalService.Outcome.NOT_FOUND);
        verify(shopify, times(1)).oldestOrder(anyString(), anyString());   // cached per tenant
        assertThat(attemptOutcomes(a)).containsExactly("too_old", "not_found", "not_found");
    }

    @Test
    void c2_notDelivered_outsideTheWindow_referenceMismatch_allGenericNotFound_andCachedNegative() {
        String tn1 = shopifyAndBosta("#1211", true);
        DELIVERIES.get(tn1).with("state").put("code", 41);
        assertThat(lookup(a, "1211", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);

        String tn2 = shopifyAndBosta("#1212", true);
        DELIVERIES.get(tn2).with("state").put("deliveryTime", Instant.now().minus(Duration.ofDays(40)).toString());
        assertThat(lookup(a, "1212", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);

        String tn3 = shopifyAndBosta("#1213", true);
        DELIVERIES.get(tn3).put("businessReference", "#9999");
        assertThat(lookup(a, "1213", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);

        assertThat(GETS.get()).isEqualTo(3);
        int shopifyCalls = mockingDetails(shopify).getInvocations().size();
        for (String n : List.of("1211", "1212", "1213")) {
            assertThat(lookup(a, n, PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        }
        assertThat(GETS.get()).as("negative cache: no repeat Bosta call").isEqualTo(3);
        assertThat(mockingDetails(shopify).getInvocations()).as("…nor Shopify").hasSize(shopifyCalls);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND number IN ('#1211','#1212','#1213')",
            Integer.class, a.id())).isZero();
    }

    @Test
    void c3_createdAfterTheCutoff_genericNotFound() {
        shopifyAndBosta("#1221", true, Instant.now().minus(Duration.ofDays(1)));
        assertThat(lookup(a, "1221", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(GETS.get()).isZero();
    }

    @Test
    void c4_noCustomerFieldReadable_unavailable() {
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(), false));
        assertThat(lookup(a, "1231", PHONE).outcome()).isEqualTo(PortalService.Outcome.UNAVAILABLE);
    }

    // ── caps, budget ──────────────────────────────────────────────────────────

    @Test
    void d1_tenantAndIpHourlyCaps_busy_withZeroCalls_andBusyNeverThrottlesTheCustomer() {
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO portal_lookup_attempts (tenant_id, order_key, success, ip_hash, ondemand_outcome) " +
                        "VALUES (?, ?, false, ?, 'not_found')", a.id(), "x" + i, ipHash("10.0.0.9"));
        }
        assertThat(portal.lookup(a.slug(), "1241", PHONE, "10.0.0.9").orElseThrow().outcome())
            .as("per-IP cap (5/h)").isEqualTo(PortalService.Outcome.BUSY);
        assertNoExternalCalls();
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO portal_lookup_attempts (tenant_id, order_key, success, ondemand_outcome) " +
                        "VALUES (?, ?, false, 'fetched')", a.id(), "y" + i);
        }
        long t0 = System.nanoTime();
        assertThat(lookup(a, "1242", PHONE).outcome()).as("per-tenant cap (8/h)").isEqualTo(PortalService.Outcome.BUSY);
        assertThat(ms(t0)).as("BUSY is not padded").isLessThan(PAD_MS);
        // BUSY answers don't count toward the per-order failure throttle (5).
        for (int i = 0; i < 6; i++) assertThat(lookup(a, "1242", PHONE).outcome()).isEqualTo(PortalService.Outcome.BUSY);
        assertNoExternalCalls();
    }

    @Test
    void d2_portalBostaBudget_oneSlotPerInterval_overTheMaxWait_isRefusedAtOnce() {
        PortalBostaBudget budget = new PortalBostaBudget(5000);
        long t0 = System.nanoTime();
        assertThat(budget.tryAcquire(3000)).isTrue();
        assertThat(budget.tryAcquire(3000)).as("next slot is 5 s away > 3 s").isFalse();
        assertThat(ms(t0)).isLessThan(200);
        PortalBostaBudget fast = new PortalBostaBudget(300);
        assertThat(fast.tryAcquire(3000)).isTrue();
        long t1 = System.nanoTime();
        assertThat(fast.tryAcquire(3000)).isTrue();
        assertThat(ms(t1)).as("waited for its slot").isGreaterThanOrEqualTo(250);
    }

    // ── concurrency, unmapped lines, timing, logs ─────────────────────────────

    @Test
    void e1_twoConcurrentLookups_oneRow() throws Exception {
        shopifyAndBosta("#1251", true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PortalService.Outcome>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) fs.add(pool.submit(() -> { go.await(); return lookup(a, "1251", PHONE).outcome(); }));
        go.countDown();
        for (Future<PortalService.Outcome> f : fs) assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(PortalService.Outcome.SUCCESS);
        pool.shutdown();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND shopify_order_gid = 'gid://shopify/Order/88001251'",
            Integer.class, a.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id " +
            "WHERE o.tenant_id = ? AND o.shopify_order_gid = 'gid://shopify/Order/88001251'", Integer.class, a.id())).isEqualTo(1);
    }

    @Test
    void e2_unmappedLines_leftOut_andNoMappedLineAtAll_storesNothing() {
        String tn = nextTracking;
        bosta(tn, "#1261", Instant.now().minus(Duration.ofDays(2)));
        stubShopify(order("#1261", Instant.now().minus(Duration.ofDays(20)), PHONE,
            List.of(a.originalGid(), "gid://shopify/ProductVariant/not-in-catalog"), tn));
        assertThat(lookup(a, "1261", PHONE).outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items oi JOIN orders o ON o.id = oi.order_id " +
            "WHERE o.tenant_id = ? AND o.number = '#1261'", Integer.class, a.id())).isEqualTo(1);

        nextTracking = String.valueOf(ThreadLocalRandom.current().nextLong(7_600_000_000L, 7_699_999_999L));
        String tn2 = nextTracking;
        bosta(tn2, "#1262", Instant.now().minus(Duration.ofDays(2)));
        stubShopify(order("#1262", Instant.now().minus(Duration.ofDays(20)), PHONE,
            List.of("gid://shopify/ProductVariant/not-in-catalog"), tn2));
        assertThat(lookup(a, "1262", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND number = '#1262'", Integer.class, a.id())).isZero();
    }

    @Test
    void e3_timingPad_everyOutcomeTakesTheSameMinimum_exceptBusy() {
        Map<String, Long> took = new LinkedHashMap<>();
        shopifyAndBosta("#1271", true);
        took.put("fetched", time(() -> lookup(a, "1271", PHONE)));
        when(shopify.findOrdersByName(anyString(), anyString(), anyString()))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(), true));
        took.put("not_found", time(() -> lookup(a, "ZZ-1", PHONE)));
        when(shopify.oldestOrder(anyString(), anyString()))
            .thenReturn(Optional.of(order("#1500", Instant.parse("2026-08-12T09:00:00Z"), PHONE, List.of(), null)));
        took.put("too_old", time(() -> lookup(a, "1100", PHONE)));
        when(tokenProvider.getValidToken(any())).thenThrow(new RuntimeException("token"));
        took.put("unavailable", time(() -> lookup(a, "ZZ-2", PHONE)));
        doReturn(TOKEN).when(tokenProvider).getValidToken(any());
        took.put("skip", time(() -> lookup(a, "2060", PHONE)));
        shopifyAndBosta("#1272", true);
        took.put("phone_mismatch", time(() -> lookup(a, "1272", "01000000000")));
        String tn5 = shopifyAndBosta("#1273", true);
        FAIL_500.add(tn5);
        took.put("bosta_5xx", time(() -> lookup(a, "1273", PHONE)));
        for (Map.Entry<String, Long> e : took.entrySet()) {
            assertThat(e.getValue()).as(e.getKey() + " ≥ pad").isGreaterThanOrEqualTo(PAD_MS);
        }
        long spread = Collections.max(took.values()) - Collections.min(took.values());
        assertThat(spread).as("outcomes indistinguishable within tolerance: " + took).isLessThan(250);
    }

    @Test
    void e4_noPiiTokensOrBodiesInTheLogs(CapturedOutput output) {
        shopifyAndBosta("#1281", true);
        lookup(a, "1281", "01077770000");   // phone mismatch
        lookup(a, "1281", PHONE);            // fetched
        String logs = output.getAll();
        assertThat(logs).contains("Portal on-demand lookup");
        for (String secret : List.of(PHONE, "1055501234", "01077770000", CUSTOMER, STREET, "12 Bosta Street", TOKEN, KEY,
                                     "Bosta Receiver", "gid://shopify/Order/88001281")) {
            assertThat(logs).as("logs must not contain " + secret).doesNotContain(secret);
        }
    }

    // ── P4d: BUSY never depends on the phone ──────────────────────────────────

    @Test
    void y1_lostTheLimiterAfterThePhoneMatched_paddedGenericNotFound_notCached() throws Exception {
        String tn = shopifyAndBosta("#1295", true);
        ShopifyGateway.PortalOrderSearch search = shopify.findOrdersByName("x", "y", "1295");
        // The headroom is taken between the peek (before Shopify) and the acquire (after the phone).
        when(shopify.findOrdersByName(anyString(), anyString(), eq("1295"))).thenAnswer(inv -> {
            rateLimiter.onRateLimited(KEY, 2);
            return search;
        });
        long t0 = System.nanoTime();
        PortalService.LookupResult r = lookup(a, "1295", PHONE);
        assertThat(r.outcome()).as("never BUSY after the phone matched").isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(ms(t0)).isGreaterThanOrEqualTo(PAD_MS);
        assertThat(attemptOutcomes(a)).containsExactly("not_found");
        Thread.sleep(2200);   // the block is over
        assertThat(lookup(a, "1295", PHONE).outcome()).as("not cached").isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(DELIVERIES).containsKey(tn);
    }

    @Test
    void y2_lostThePortalBudgetSlotAfterThePhoneMatched_paddedGenericNotFound_notCached() {
        shopifyAndBosta("#1296", true);
        ReflectionTestUtils.setField(onDemand, "budget", new PortalBostaBudget(0) {
            @Override public synchronized boolean hasSlotWithin(long maxWaitMs) { return true; }
            @Override public boolean tryAcquire(long maxWaitMs) { return false; }
        });
        try {
            long t0 = System.nanoTime();
            assertThat(lookup(a, "1296", PHONE).outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
            assertThat(ms(t0)).isGreaterThanOrEqualTo(PAD_MS);
            assertThat(GETS.get()).isZero();
        } finally {
            ReflectionTestUtils.setField(onDemand, "budget", realBudget);
        }
        assertThat(lookup(a, "1296", PHONE).outcome()).as("not cached").isEqualTo(PortalService.Outcome.SUCCESS);
    }

    @Test
    void y3_noBudgetSlot_busy_withAWrongPhoneToo_beforeShopify() {
        shopifyAndBosta("#1297", true);
        ReflectionTestUtils.setField(onDemand, "budget", new PortalBostaBudget(0) {
            @Override public synchronized boolean hasSlotWithin(long maxWaitMs) { return false; }
        });
        try {
            long t0 = System.nanoTime();
            assertThat(lookup(a, "1297", "01000000000").outcome()).as("wrong phone").isEqualTo(PortalService.Outcome.BUSY);
            assertThat(lookup(a, "1297", PHONE).outcome()).as("right phone").isEqualTo(PortalService.Outcome.BUSY);
            assertThat(ms(t0)).as("unpadded").isLessThan(PAD_MS);
            assertNoExternalCalls();
        } finally {
            ReflectionTestUtils.setField(onDemand, "budget", realBudget);
        }
    }

    @Test
    void y4_budgetPeek_takesNothing() {
        PortalBostaBudget budget = new PortalBostaBudget(5000);
        assertThat(budget.hasSlotWithin(0)).isTrue();
        assertThat(budget.hasSlotWithin(0)).as("a peek takes no slot").isTrue();
        assertThat(budget.tryAcquire(0)).isTrue();
        assertThat(budget.hasSlotWithin(3000)).as("next slot 5 s away").isFalse();
        assertThat(budget.hasSlotWithin(6000)).isTrue();
    }

    /**
     * Last (z): the shared Bosta limiter is blocked (a 429) for 5 s — shorter than its own 60 s max wait,
     * so without the portal's cap the lookup would sit there and then answer: the cap answers BUSY at once.
     */
    @Test
    void z1_theGlobalBostaLimiterBlocked_busyAtOnce_forAnyPhone_beforeShopify() {
        shopifyAndBosta("#1291", true);
        rateLimiter.onRateLimited(KEY, 5);
        long t0 = System.nanoTime();
        assertThat(lookup(a, "1291", "01000000000").outcome()).as("wrong phone").isEqualTo(PortalService.Outcome.BUSY);
        PortalService.LookupResult r = lookup(a, "1291", PHONE);
        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.BUSY);
        assertThat(ms(t0)).as("unpadded, never the limiter's 60 s").isLessThan(PAD_MS);
        assertNoExternalCalls();
        assertThat(attemptOutcomes(a)).containsExactly("busy", "busy");
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * A body shaped like a real Bosta v0 GET /deliveries/{tn} for a delivered SEND (fields as seen on prod
     * shipment raws: state {code, value, deliveryTime, …}, timeline [{code, value, date, done}], addresses,
     * receiver, specs, wallet …). {@code withDeliveryTime} false drops state.deliveryTime (timeline fallback).
     */
    private ObjectNode realV0(String tn, String reference, Instant delivered, boolean withDeliveryTime) throws Exception {
        String at = delivered.toString();
        ObjectNode d = (ObjectNode) M.readTree("""
            {"_id":"Kq7XnY2pL0","trackingNumber":"%s","type":{"code":10,"value":"Send"},
             "state":{"code":45,"value":"Delivered","deliveryTime":"%s","waitingForBusinessAction":false,
                      "receivedAtWarehouse":{"time":"%s","warehouse":{"_id":"w1","name":"Nasr City Hub"}},
                      "delivering":{"time":"%s"}},
             "timeline":[{"code":10,"value":"new","date":"%s","done":true},
                         {"code":21,"value":"picked_up","date":"%s","done":true},
                         {"code":41,"value":"out_for_delivery","date":"%s","done":true},
                         {"code":45,"value":"delivered","date":"%s","done":true},
                         {"code":46,"value":"returned_to_business","done":false}],
             "businessReference":"%s","cod":640,"numberOfAttempts":1,"notes":"",
             "dropOffAddress":{"firstLine":"12 Bosta Street, Block 4","secondLine":"Near the pharmacy","floor":"3","apartment":"7",
                               "city":{"_id":"%s","name":"Cairo","nameAr":"القاهرة"},
                               "zone":{"_id":"z1","name":"Nasr City"},
                               "district":{"_id":"%s","name":"Nasr City - 7th District"}},
             "pickupAddress":{"firstLine":"Merchant warehouse street","city":{"_id":"%s","name":"Cairo"}},
             "receiver":{"firstName":"Bosta","lastName":"Receiver","fullName":"Bosta Receiver","phone":"+2%s"},
             "specs":{"packageType":"Parcel","size":"SMALL","packageDetails":{"itemsCount":1,"description":"Linen Shirt"}},
             "wallet":{"cashCycle":{"cod":640,"bostaFees":55}},
             "creationTimestamp":"%s","createdAt":"%s","updatedAt":"%s"}
            """.formatted(tn, at, at, at, at, at, at, at, reference, CAIRO, NASR, CAIRO, PHONE, at, at, at));
        if (!withDeliveryTime) ((ObjectNode) d.path("state")).remove("deliveryTime");
        return d;
    }

    private T tenant(String slug, boolean withBosta) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since, " +
                    "    portal_exchanges_enabled, portal_exchanges_since, customer_return_window_days) " +
                    "VALUES (?, ?, ?, true, true, now() - interval '2 days', true, now() - interval '1 day', 14)", id, "Store " + slug, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, orders_ingest_from) " +
                    "VALUES (?, ?, ?, 'connected', 'idle', now() - interval '10 days')", store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            owner, id, "owner-" + owner + "@test.local");
        if (withBosta) {
            jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
                        "    return_business_location_id, return_business_location_name) " +
                        "VALUES (gen_random_uuid(), ?, 'bosta', ?, ?, 'active', ?, 'Maadi')", id, encryption.encrypt(KEY), "h-" + slug, "loc-" + slug);
        }
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true)", location, id);
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            product, id, store, "gid://shopify/Product/" + slug);
        String originalGid = "gid://shopify/ProductVariant/" + slug + "-m";
        UUID original = variant(id, product, "White / M", originalGid);
        UUID replacement = variant(id, product, "White / L", "gid://shopify/ProductVariant/" + slug + "-l");
        // The store's lowest ingested order — anything at or above #2000 placed after the connect is in Traced.
        jdbc.update("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
                    "VALUES (?, ?, ?, '#2000', 'delivered', now() - interval '5 days')", id, store, "gid://shopify/Order/" + slug + "2000");
        return new T(id, slug, store, original, replacement, originalGid, location, owner);
    }

    private UUID variant(UUID tenant, UUID product, String title, String gid) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, product, gid, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    /** Shopify returns the order (placed 20 days ago — before the cutoff); Bosta has it delivered 2 days ago. */
    private String shopifyAndBosta(String name, boolean delivered) {
        return shopifyAndBosta(name, delivered, Instant.now().minus(Duration.ofDays(20)));
    }

    private String shopifyAndBosta(String name, boolean delivered, Instant createdAt) {
        String tn = String.valueOf(ThreadLocalRandom.current().nextLong(5_500_000_000L, 5_599_999_999L));
        bosta(tn, name, Instant.now().minus(Duration.ofDays(2)));
        if (!delivered) DELIVERIES.get(tn).with("state").put("code", 24);
        stubShopify(order(name, createdAt, PHONE, List.of(a.originalGid()), tn));
        return tn;
    }

    private void stubShopify(ShopifyGateway.Order o) {
        String bare = o.name().substring(1);
        when(shopify.findOrdersByName(anyString(), anyString(), eq(bare)))
            .thenReturn(new ShopifyGateway.PortalOrderSearch(List.of(o), true));
    }

    private void bosta(String tn, String reference, Instant deliveredAt) {
        ObjectNode d = M.createObjectNode();
        d.put("_id", "del-" + tn);
        d.put("trackingNumber", tn);
        d.putObject("type").put("code", 10).put("value", "Send");
        d.putObject("state").put("code", 45).put("value", "Delivered").put("deliveryTime", deliveredAt.toString());
        d.put("businessReference", reference);
        ObjectNode drop = d.putObject("dropOffAddress");
        drop.put("firstLine", "12 Bosta Street, Block 4");
        drop.putObject("city").put("_id", CAIRO).put("name", "Cairo");
        drop.putObject("district").put("_id", NASR).put("name", "Nasr City");
        d.putObject("receiver").put("fullName", "Bosta Receiver").put("phone", "+2" + PHONE);
        DELIVERIES.put(tn, d);
    }

    private ShopifyGateway.Order order(String name, Instant createdAt, String phone, List<String> variantGids, String tracking) {
        String gid = "gid://shopify/Order/8800" + name.replaceAll("\\D", "");
        ObjectNode raw = M.createObjectNode();
        raw.put("id", gid);
        raw.put("name", name);
        raw.put("createdAt", createdAt.toString());
        ArrayNode edges = raw.putObject("lineItems").putArray("edges");
        List<ShopifyGateway.LineItem> lines = new ArrayList<>();
        int i = 0;
        for (String v : variantGids) {
            ObjectNode n = edges.addObject().putObject("node");
            n.put("id", gid + "-line-" + i);
            n.put("quantity", 1);
            n.put("currentQuantity", 1);
            n.putObject("variant").put("id", v);
            lines.add(new ShopifyGateway.LineItem(gid + "-line-" + i++, 1, v));
        }
        if (tracking != null) {
            raw.putArray("fulfillments").addObject().put("status", "SUCCESS")
                .putArray("trackingInfo").addObject().put("number", tracking).put("company", "Bosta");
        }
        raw.putObject("shippingAddress").put("name", CUSTOMER).put("phone", phone).put("address1", STREET).put("city", "Cairo");
        raw.put("displayFinancialStatus", "PENDING");
        raw.putArray("paymentGatewayNames").add("Cash on Delivery (COD)");
        return new ShopifyGateway.Order(gid, name, null, null, null, "PENDING", List.of("Cash on Delivery (COD)"),
            new BigDecimal("640.00"), lines, createdAt, raw);
    }

    private UUID request(UUID order, UUID item, String type) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at, pickup_city_id, pickup_city_name, " +
            "    pickup_district_id, pickup_district_name, refund_fallback_ok) " +
            "VALUES (?, ?, ?, 'approved', ?, now(), ?, 'Cairo', ?, 'Nasr City - 7th District', true) RETURNING id",
            UUID.class, a.id(), order, type, "RR-" + ref6(), CAIRO, NASR);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code, replacement_variant_id) " +
                    "VALUES (?, ?, ?, 1, ?, 'wrong_size', ?)", a.id(), id, item, a.original(), "exchange".equals(type) ? a.replacement() : null);
        return id;
    }

    private PortalService.LookupResult lookup(T t, String number, String phone) {
        return portal.lookup(t.slug(), number, phone).orElseThrow();
    }

    private List<String> attemptOutcomes(T t) {
        return jdbc.queryForList("SELECT ondemand_outcome FROM portal_lookup_attempts WHERE tenant_id = ? " +
            "AND ondemand_outcome IS NOT NULL ORDER BY attempted_at, id", String.class, t.id());
    }

    private String ipHash(String ip) {
        return ReflectionTestUtils.invokeMethod(ReflectionTestUtils.getField(portal, "tokens"), "clientIpHash", ip);
    }

    private void assertNoExternalCalls() {
        verify(shopify, never()).findOrdersByName(anyString(), anyString(), anyString());
        verify(shopify, never()).oldestOrder(anyString(), anyString());
        assertThat(GETS.get()).as("no Bosta read").isZero();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static long time(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return ms(t0);
    }

    private static String ref6() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }

    private static JsonNode json(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
