package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.portal.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Step 5c — Traced books the Bosta EXCHANGE (type 30) for an approved exchange request
 * (MODE B AMENDMENT #3), creates the exchange in Traced on CREATED (exchanges row + internal
 * replacement order + forward leg), and the request finishes as 'exchanged'.
 *
 * Bosta is a REAL local HTTP stub (as in ReturnPickupBookingTest): the v2 create is counted and
 * kept, the v0 read serves the read-back, confirm-by-tracking, linkAtMapTime's provider-id
 * fetch and the webhook ingest. Shopify is mocked (the 5a decrement is observed through it).
 *
 * P payload · I idempotency / no retry · C preconditions · Q enqueue, since guard, Book now ·
 * K CREATED → exchange in Traced · R races and the dashboard lane · V read-back ·
 * E end to end (5a decrement once, restock / damaged → exchanged, failed swap) ·
 * X cross-tenant on app_user · W portal areas · S settings switch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalExchangeBookingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    // ── Bosta stub ────────────────────────────────────────────────────────────

    enum Create { CREATED, BAD_REQUEST, SERVER_ERROR, TIMEOUT, DROPPED }

    static final AtomicReference<Create> CREATE = new AtomicReference<>(Create.CREATED);
    static final AtomicReference<String> NEXT_TRACKING = new AtomicReference<>("7100000001");
    static final List<JsonNode> POSTS = new CopyOnWriteArrayList<>();
    static final List<String> POST_AUTH = new CopyOnWriteArrayList<>();
    static final Map<String, ObjectNode> DELIVERIES = new ConcurrentHashMap<>();
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
                        POST_AUTH.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
                        switch (CREATE.get()) {
                            case CREATED -> {
                                String tn = NEXT_TRACKING.get();
                                DELIVERIES.put(tn, stored(body, tn));
                                send(ex, 200, "{\"success\":true,\"data\":{\"_id\":\"del-" + tn + "\",\"trackingNumber\":\"" + tn +
                                    "\",\"state\":{\"code\":10,\"value\":\"Pickup requested\"},\"creationSrc\":\"API\"}}");
                            }
                            case BAD_REQUEST -> send(ex, 400, "{\"success\":false,\"message\":\"Invalid district for the given city\"}");
                            case SERVER_ERROR -> send(ex, 500, "{\"success\":false,\"message\":\"Internal Server Error\"}");
                            case TIMEOUT -> { Thread.sleep(2500); send(ex, 200, "{}"); }
                            case DROPPED -> { /* close without answering */ }
                        }
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
                    // fall through to close
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

    /** What Bosta stores for an exchange created from {@code body}: customer on dropOffAddress, merchant on pickupAddress. */
    static ObjectNode stored(JsonNode body, String tn) {
        ObjectNode d = M.createObjectNode();
        d.put("_id", "del-" + tn);
        d.put("trackingNumber", tn);
        d.putObject("type").put("code", body.path("type").asInt()).put("value", body.path("type").asInt() == 30 ? "Exchange" : "Other");
        d.putObject("state").put("code", 10).put("value", "Pickup requested");
        d.put("businessReference", body.path("businessReference").asText());
        d.put("cod", body.path("cod").asInt());
        d.set("specs", body.path("specs").deepCopy());
        d.set("returnSpecs", body.path("returnSpecs").deepCopy());
        ObjectNode drop = d.putObject("dropOffAddress");
        drop.put("firstLine", body.path("dropOffAddress").path("firstLine").asText());
        drop.putObject("district").put("_id", body.path("dropOffAddress").path("districtId").asText()).put("name", "Nasr City");
        ObjectNode pickup = d.putObject("pickupAddress");
        pickup.put("firstLine", "Merchant warehouse street");
        pickup.put("businessLocationId", body.path("businessLocationId").asText());
        pickup.putObject("district").put("_id", "MERCHANT-DISTRICT");
        d.set("receiver", body.path("receiver").deepCopy());
        Instant now = Instant.now();
        d.put("createdAt", now.toString());
        d.put("updatedAt", now.toString());
        d.putArray("timeline").addObject().put("value", "new").put("done", true).put("date", now.toString());
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
    }

    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0", PICKUP_ONLY = "PickupOnly01";
    static final String KEY_A = "bosta-raw-key-5c-a", KEY_B = "bosta-raw-key-5c-b";
    static final String TRACED_GID = "gid://shopify/Location/5500";
    static final String ITEM_GID = "gid://shopify/InventoryItem/5501";

    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionService encryption;
    @Autowired ReturnPickupBookingService booking;
    @Autowired ReturnRequestService requests;
    @Autowired ExceptionService exceptions;
    @Autowired BostaIngestionHelper ingestionHelper;
    @Autowired BostaWebhookJob webhookJob;
    @Autowired BostaV2Client bostaV2;
    @Autowired BostaGateway gateway;
    @Autowired PickupBookingScheduler scheduler;
    @Autowired PlatformTransactionManager txm;
    @Autowired FulfillService fulfill;
    @Autowired PickupSessionService pickups;
    @Autowired ReturnSessionService returnSessions;
    @Autowired PortalService portal;
    @Autowired PortalTokenService tokens;
    @Autowired PortalSettingsService settings;
    @SpyBean ExchangeService exchangeService;
    @SpyBean ExchangeMatchService exchangeMatchService;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record Tenant(UUID id, UUID store, UUID original, UUID replacement, UUID other, UUID owner, UUID location, String slug) {}

    Tenant a, b;

    @BeforeAll
    void setup() {
        a = tenant("Nour Studio 5c", "nour-5c", KEY_A);
        b = tenant("Other 5c", "other-5c", KEY_B);
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true), " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Pickup Only', 'استلام فقط', true, false) " +
                    "ON CONFLICT (district_id) DO NOTHING",
                    NASR, CAIRO, PICKUP_ONLY, CAIRO);
    }

    @BeforeEach
    void resetStub() {
        CREATE.set(Create.CREATED);
        NEXT_TRACKING.set(String.valueOf(ThreadLocalRandom.current().nextLong(7_000_000_000L, 7_999_999_999L)));
        POSTS.clear();
        POST_AUTH.clear();
        DELIVERIES.clear();
        clearInvocations(jobScheduler, exchangeService, exchangeMatchService);
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-test");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn(ITEM_GID);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Tenant t : List.of(a, b)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pickup_shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pickups WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE exchanges SET outbound_order_id = NULL, matched_order_id = NULL, return_request_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t.id());
            jdbc.update("UPDATE tenants SET portal_pickup_booking = true, portal_pickup_booking_since = now() - interval '2 days', " +
                        "portal_exchanges_enabled = true, portal_exchanges_since = now() - interval '1 day' WHERE id = ?", t.id());
            jdbc.update("UPDATE courier_accounts SET status = 'active', return_business_location_id = ? WHERE tenant_id = ?",
                "loc-" + t.slug(), t.id());
        }
    }

    // ── P: payload ────────────────────────────────────────────────────────────

    @Test
    void p1_payload_exactFieldSetAndValues_forbiddenFieldsAbsent() {
        Req r = approved(a, "#1047", 2);

        booking.book(r.id(), a.id());

        assertThat(POSTS).hasSize(1);
        assertThat(POST_AUTH).containsExactly(KEY_A);
        JsonNode p = POSTS.get(0);
        assertThat(names(p)).containsExactlyInAnyOrder("type", "cod", "dropOffAddress", "businessLocationId",
            "receiver", "businessReference", "uniqueBusinessReference", "specs", "returnSpecs", "returnNotes");
        assertThat(p.path("type").asInt()).isEqualTo(30);
        assertThat(p.path("cod").isInt() && p.path("cod").asInt() == 0).isTrue();
        assertThat(names(p.path("dropOffAddress"))).containsExactlyInAnyOrder(
            "firstLine", "secondLine", "buildingNumber", "floor", "apartment", "city", "districtId");
        assertThat(p.path("dropOffAddress").path("firstLine").asText()).isEqualTo("12 Placeholder Street, Block 4");
        assertThat(p.path("dropOffAddress").path("city").asText()).isEqualTo("Cairo");
        assertThat(p.path("dropOffAddress").path("districtId").asText()).isEqualTo(NASR);
        assertThat(p.path("businessLocationId").asText()).isEqualTo("loc-nour-5c");
        assertThat(names(p.path("receiver"))).containsExactlyInAnyOrder("firstName", "lastName", "phone");
        assertThat(p.path("receiver").path("phone").asText()).isEqualTo("01000000001");
        assertThat(p.path("businessReference").asText()).isEqualTo("#1047");
        assertThat(p.path("uniqueBusinessReference").asText()).isEqualTo(r.id().toString());
        assertThat(names(p.path("specs"))).containsExactly("packageDetails");
        assertThat(p.path("specs").path("packageDetails").path("itemsCount").asInt()).isEqualTo(1);
        assertThat(p.path("specs").path("packageDetails").path("description").asText())
            .isEqualTo(r.reference() + ": Linen Shirt / White / L");
        assertThat(p.path("returnSpecs").path("packageDetails").path("itemsCount").asInt()).isEqualTo(1);
        assertThat(p.path("returnSpecs").path("packageDetails").path("description").asText()).isEqualTo("Linen Shirt / White / M");
        assertThat(p.path("returnNotes").asText()).isEqualTo("Traced exchange request " + r.reference());
        for (String forbidden : List.of("pickupAddress", "returnAddress", "allowToOpenPackage", "webhookUrl", "goodsInfo", "productInfo")) {
            assertThat(p.has(forbidden)).as(forbidden).isFalse();
        }
    }

    @Test
    void p1b_customAddress_sentAsDropOffAddress_restUnchanged() {
        // V117: the customer typed a different address — on an exchange it is the drop-off (and
        // collection) address; pickupAddress is still never sent.
        Req r = approved(a, "#1048", 2);
        jdbc.update("UPDATE return_requests SET pickup_address_source = 'custom', custom_first_line = '7 New Street, Heliopolis', " +
                    "custom_second_line = NULL, custom_building_number = '7B', custom_floor = '2', custom_apartment = NULL " +
                    "WHERE id = ?", r.id());

        booking.book(r.id(), a.id());

        assertThat(POSTS).hasSize(1);
        JsonNode p = POSTS.get(0);
        assertThat(names(p)).containsExactlyInAnyOrder("type", "cod", "dropOffAddress", "businessLocationId",
            "receiver", "businessReference", "uniqueBusinessReference", "specs", "returnSpecs", "returnNotes");
        assertThat(p.path("type").asInt()).isEqualTo(30);
        assertThat(names(p.path("dropOffAddress")))
            .containsExactlyInAnyOrder("firstLine", "buildingNumber", "floor", "city", "districtId");
        assertThat(p.path("dropOffAddress").path("firstLine").asText()).isEqualTo("7 New Street, Heliopolis");
        assertThat(p.path("dropOffAddress").path("buildingNumber").asText()).isEqualTo("7B");
        assertThat(p.path("dropOffAddress").path("floor").asText()).isEqualTo("2");
        assertThat(p.path("dropOffAddress").path("city").asText()).isEqualTo("Cairo");
        assertThat(p.path("dropOffAddress").path("districtId").asText()).isEqualTo(NASR);
        assertThat(p.toString()).doesNotContain("Placeholder Street");
        assertThat(p.has("pickupAddress")).isFalse();
        assertThat(p.path("receiver").path("phone").asText()).isEqualTo("01000000001");
        assertThat(row(r.id())).containsEntry("booking_status", "booked");
        assertThat(row(r.id()).get("booking_verified_at")).as("read-back: customer district on dropOffAddress").isNotNull();
    }

    @Test
    void p2_theTwoBuildersNeverProduceEachOthersType() {
        ObjectNode crp = BostaV2Client.returnPickupPayload(M, new BostaV2Client.ReturnPickup("u", "#1", "loc", "12 Street, Block", null,
            null, null, null, "Cairo", NASR, "Mona", null, "01000000001", 3, "d", "n"));
        ObjectNode exc = BostaV2Client.exchangePayload(M, new BostaV2Client.Exchange("u", "#1", "loc", "12 Street, Block", null,
            null, null, null, "Cairo", NASR, "Mona", null, "01000000001", "out", "back", "n"));
        assertThat(crp.path("type").asInt()).isEqualTo(25);
        assertThat(crp.has("specs")).isFalse();
        assertThat(exc.path("type").asInt()).isEqualTo(30);
        assertThat(exc.path("specs").path("packageDetails").path("itemsCount").asInt()).isEqualTo(1);
        assertThat(exc.path("returnSpecs").path("packageDetails").path("itemsCount").asInt()).isEqualTo(1);
    }

    // ── K: CREATED → the exchange exists in Traced ───────────────────────────

    @Test
    void k1_created_oneExchangeRow_oneInternalOrder_forwardLeg_pickableAndCommitted_pickupBooked_verified() {
        Req r = approved(a, "#2001", 3);
        String tn = NEXT_TRACKING.get();

        booking.book(r.id(), a.id());

        Map<String, Object> req = row(r.id());
        assertThat(req).containsEntry("status", "pickup_booked").containsEntry("booking_status", "booked")
            .containsEntry("bosta_tracking_number", tn);
        assertThat(req.get("booking_verified_at")).as("read-back matched (customer on dropOffAddress)").isNotNull();

        Map<String, Object> ex = jdbc.queryForMap("SELECT * FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", a.id(), tn);
        assertThat(ex.get("return_request_id")).isEqualTo(r.id());
        assertThat(ex.get("status")).isEqualTo("matched");
        assertThat(ex.get("match_method")).isEqualTo("reference");
        assertThat(ex.get("matched_order_id")).isEqualTo(r.orderId());
        assertThat(ex.get("inbound_variant_id")).isEqualTo(a.original());
        assertThat(ex.get("outbound_variant_id")).isEqualTo(a.replacement());
        UUID orderId = (UUID) ex.get("outbound_order_id");
        Map<String, Object> order = jdbc.queryForMap("SELECT external_id, number, status::text AS status, customer_phone FROM orders WHERE id = ?", orderId);
        assertThat(order).containsEntry("external_id", "internal:exchange:" + tn).containsEntry("number", "EXC-" + tn)
            .containsEntry("status", "new").containsEntry("customer_phone", "01000000001");
        assertThat(jdbc.queryForObject("SELECT variant_id FROM order_items WHERE order_id = ?", UUID.class, orderId)).isEqualTo(a.replacement());
        assertThat(jdbc.queryForObject("SELECT internal_state::text FROM shipments WHERE order_id = ? AND shipment_leg = 'forward' AND tracking_number = ?",
            String.class, orderId, tn)).isEqualTo("created");

        VariantStockService.VariantStock stock = stockOf(a, a.replacement());
        assertThat(stock.committed()).as("the replacement is committed").isEqualTo(1);
        assertThat(stock.available()).isEqualTo(2);
        assertThat(eventTypes(r.id())).containsSubsequence("pickup_booked", "booking_verified");
    }

    // ── I: idempotency and no retry ─────────────────────────────────────────

    @Test
    void i1_twoConcurrentJobs_onePost_oneRow_oneOrder_andRerunsCreateNothing() throws Exception {
        Req r = approved(a, "#3001", 1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) fs.add(pool.submit(() -> { go.await(); booking.book(r.id(), a.id()); return null; }));
        go.countDown();
        for (Future<?> f : fs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(POSTS).as("exactly one POST for two concurrent jobs").hasSize(1);
        booking.book(r.id(), a.id());
        booking.sweepTenant(a.id());
        TenantContext.runAs(a.id(), () -> exchangeService.attachForRequest(a.id(), r.id(), NEXT_TRACKING.get()));
        assertThat(POSTS).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", a.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id LIKE 'internal:exchange:%'", a.id())).isEqualTo(1);
    }

    @Test
    void i2_timeout_5xx_dropped_onePostEach_failedAmbiguous_neverRePosted_noExchangeCreated() {
        for (Create c : List.of(Create.TIMEOUT, Create.SERVER_ERROR, Create.DROPPED)) {
            POSTS.clear();
            CREATE.set(c);
            Req r = approved(a, "#31" + c.ordinal(), 1);

            booking.book(r.id(), a.id());

            assertThat(POSTS).as(c + ": exactly one POST").hasSize(1);
            assertThat(row(r.id()).get("booking_status")).as(c.name()).isEqualTo("failed_ambiguous");
            booking.book(r.id(), a.id());
            booking.sweepTenant(a.id());
            assertThat(POSTS).as(c + ": never re-POSTed automatically").hasSize(1);
        }
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", a.id())).isZero();
    }

    // ── C: preconditions ──────────────────────────────────────────────────────

    @Test
    void c1_eachFailingPrecondition_failedWithReason_noPost() {
        record Case(String expect, java.util.function.Consumer<Req> breaker, Runnable restore) {}
        List<Case> cases = List.of(
            new Case("isn't approved", r -> jdbc.update("UPDATE return_requests SET status = 'requested' WHERE id = ?", r.id()), () -> {}),
            new Case("Exchanges are switched off", r -> jdbc.update("UPDATE tenants SET portal_exchanges_enabled = false WHERE id = ?", a.id()),
                () -> jdbc.update("UPDATE tenants SET portal_exchanges_enabled = true WHERE id = ?", a.id())),
            new Case("pickup booking is switched off", r -> jdbc.update("UPDATE tenants SET portal_pickup_booking = false WHERE id = ?", a.id()),
                () -> jdbc.update("UPDATE tenants SET portal_pickup_booking = true WHERE id = ?", a.id())),
            new Case("no active Bosta account", r -> jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", a.id()),
                () -> jdbc.update("UPDATE courier_accounts SET status = 'active' WHERE tenant_id = ?", a.id())),
            new Case("where returns go back to", r -> jdbc.update("UPDATE courier_accounts SET return_business_location_id = NULL WHERE tenant_id = ?", a.id()),
                () -> jdbc.update("UPDATE courier_accounts SET return_business_location_id = 'loc-nour-5c' WHERE tenant_id = ?", a.id())),
            new Case("customer's area", r -> jdbc.update("UPDATE return_requests SET pickup_district_id = NULL WHERE id = ?", r.id()), () -> {}),
            new Case("both deliver to and collect from", r -> jdbc.update("UPDATE return_requests SET pickup_district_id = ? WHERE id = ?", PICKUP_ONLY, r.id()), () -> {}),
            new Case("out of stock", r -> jdbc.update("UPDATE pieces SET status = 'damaged' WHERE variant_id = ? AND status = 'available'", a.replacement()), () -> {}),
            new Case("privacy request", r -> jdbc.update("UPDATE orders SET pii_redacted_at = now() WHERE id = ?", r.orderId()), () -> {}),
            new Case("missing or too short", r -> jdbc.update(
                "UPDATE shipments SET raw = jsonb_set(raw, '{dropOffAddress,firstLine}', '\"12 St\"') WHERE order_id = ?", r.orderId()), () -> {}),
            new Case("name or phone", r -> {
                jdbc.update("UPDATE shipments SET raw = raw - 'receiver' WHERE order_id = ?", r.orderId());
                jdbc.update("UPDATE orders SET customer_phone = NULL WHERE id = ?", r.orderId());
            }, () -> {}));
        int i = 0;
        for (Case c : cases) {
            Req r = approved(a, "#4" + (i++), 1);
            c.breaker().accept(r);
            try {
                booking.book(r.id(), a.id());
            } finally {
                c.restore().run();
            }
            assertThat(row(r.id()).get("booking_status")).as(c.expect()).isEqualTo("failed");
            assertThat((String) row(r.id()).get("booking_error")).as(c.expect()).contains(c.expect());
        }
        assertThat(POSTS).as("no precondition failure ever reaches Bosta").isEmpty();
    }

    // ── Q: enqueue after commit, since guard, Book now ──────────────────────

    @Test
    void q1_approveExchange_rolledBack_noJob_committed_oneJob_exchangesOff_noJob() throws Exception {
        Req r = requested(a, "#5001");
        TenantContext.set(a.id());
        new TransactionTemplate(txm).execute(s -> { requests.approve(r.id(), a.owner()); s.setRollbackOnly(); return null; });
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));
        requests.approve(r.id(), a.owner());
        TenantContext.clear();
        assertEnqueuedFor(r.id(), a.id());

        clearInvocations(jobScheduler);
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = false WHERE id = ?", a.id());
        Req r2 = requested(a, "#5002");
        TenantContext.runAs(a.id(), () -> requests.approve(r2.id(), a.owner()));
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));
    }

    @Test
    void q2_sweeper_booksOnlyExchangesDecidedAfterSince_bookNowForOlder_409Otherwise() throws Exception {
        Req old = approved(a, "#5101", 1);
        Req fresh = approved(a, "#5102", 1);
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '3 days' WHERE id = ?", old.id());
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '5 minutes' WHERE id = ?", fresh.id());

        booking.sweepTenant(a.id());
        assertEnqueuedFor(fresh.id(), a.id());   // exactly one enqueue: the old approval is never booked by itself

        clearInvocations(jobScheduler);
        TenantContext.runAs(a.id(), () -> booking.bookNow(old.id()));
        assertEnqueuedFor(old.id(), a.id());
        booking.book(old.id(), a.id());
        assertThat(row(old.id()).get("booking_status")).isEqualTo("booked");
        assertStatus(409, () -> TenantContext.runAs(a.id(), () -> booking.bookNow(old.id())));   // already booked

        TenantContext.set(a.id());
        Map<String, Object> d = requests.detail(fresh.id());
        TenantContext.clear();
        assertThat(d.get("bookNowAvailable")).as("decided after since — the sweeper books it").isEqualTo(false);
    }

    // ── R: races, and the dashboard lane unchanged ──────────────────────────

    @Test
    void r1_webhookFirst_rowKeptAndLinked_noGuessing_thenBookingAttachesIt_noDuplicates() {
        Req r = approved(a, "#6001", 1);
        String tn = NEXT_TRACKING.get();
        // The booking is in flight (claimed, tracking not saved yet) when Bosta's webhook lands.
        jdbc.update("UPDATE return_requests SET booking_status = 'pending', booking_attempted_at = now() WHERE id = ?", r.id());
        ObjectNode d = stored(M.valueToTree(Map.of("type", 30, "cod", 0, "businessReference", "#6001",
            "dropOffAddress", Map.of("firstLine", "12 Placeholder Street, Block 4", "districtId", NASR),
            "specs", Map.of("packageDetails", Map.of("itemsCount", 1, "description", "x")),
            "returnSpecs", Map.of("packageDetails", Map.of("itemsCount", 1, "description", "y")))), tn);
        d.put("uniqueBusinessReference", r.id().toString());
        DELIVERIES.put(tn, d);
        ingest(a, tn);

        Map<String, Object> ex = jdbc.queryForMap("SELECT return_request_id, status, outbound_order_id FROM exchanges WHERE tracking_number = ?", tn);
        assertThat(ex.get("return_request_id")).isEqualTo(r.id());
        assertThat(ex.get("outbound_order_id")).isNull();
        verify(exchangeService, never()).tryAutoMap(tn);
        verify(exchangeMatchService, never()).attemptMatch(tn);
        assertThat(problems(a, "exchange_needs_mapping")).as("ours is never an unmapped dashboard exchange").isEmpty();

        // The booking result is saved: the existing row is attached, never duplicated.
        jdbc.update("UPDATE return_requests SET booking_status = NULL WHERE id = ?", r.id());
        booking.book(r.id(), a.id());
        booking.sweepTenant(a.id());
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE tenant_id = ?", a.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE tenant_id = ? AND external_id = ?", a.id(), "internal:exchange:" + tn)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM exchanges WHERE tracking_number = ?", String.class, tn)).isEqualTo("matched");
    }

    @Test
    void r2_dashboardExchange_noBusinessReference_goesThroughTheExistingLane() {
        String tn = "7999000001";
        ObjectNode d = stored(M.valueToTree(Map.of("type", 30, "cod", 0,
            "dropOffAddress", Map.of("firstLine", "12 Placeholder Street, Block 4", "districtId", NASR),
            "specs", Map.of("packageDetails", Map.of("itemsCount", 1, "description", "Something else entirely")),
            "returnSpecs", Map.of("packageDetails", Map.of("itemsCount", 1, "description", "y")))), tn);
        d.remove("businessReference");
        DELIVERIES.put(tn, d);
        ingest(a, tn);

        assertThat(jdbc.queryForObject("SELECT return_request_id FROM exchanges WHERE tracking_number = ?", UUID.class, tn)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM exchanges WHERE tracking_number = ?", String.class, tn)).isEqualTo("needs_mapping");
        verify(exchangeService, times(1)).tryAutoMap(tn);
        verify(exchangeMatchService, times(1)).attemptMatch(tn);
    }

    // ── V: read-back ─────────────────────────────────────────────────────────

    @Test
    void v1_readBack_dropOffDistrictMismatch_needsReview_withExchangeWording() {
        Req r = approved(a, "#7001", 1);
        String tn = NEXT_TRACKING.get();
        booking.book(r.id(), a.id());
        assertThat(row(r.id()).get("booking_verified_at")).isNotNull();

        ((ObjectNode) DELIVERIES.get(tn).path("dropOffAddress").path("district")).put("_id", "SomewhereElse");
        // The merchant district on pickupAddress must never be read as the customer's.
        ((ObjectNode) DELIVERIES.get(tn).path("pickupAddress").path("district")).put("_id", NASR);
        jdbc.update("UPDATE return_requests SET booking_verified_at = NULL WHERE id = ?", r.id());
        booking.sweepTenant(a.id());

        Map<String, Object> row = row(r.id());
        assertThat(row.get("booking_status")).isEqualTo("needs_review");
        assertThat((String) row.get("booking_error")).isEqualTo("Bosta's delivery differs from the request: district.");
        Map<String, Object> ex = problems(a, "pickup_booking_problem").stream()
            .filter(e -> r.reference().equals(e.get("reference"))).findFirst().orElseThrow();
        assertThat((String) ex.get("descriptionEn")).startsWith("Exchange request " + r.reference()).contains("Bosta exchange");
        assertThat((String) ex.get("descriptionAr")).contains("طلب الاستبدال");
    }

    // ── E: end to end ─────────────────────────────────────────────────────────

    @Test
    void e1_replacementLeaves_decrementOnce_oldItemRestocked_exchanged_neverRefundPending() throws Exception {
        Req r = approved(a, "#8001", 1);
        String tn = NEXT_TRACKING.get();
        booking.book(r.id(), a.id());
        String replacementPiece = pickAndHandOver(a, tn);
        awaitTrigger("exchange_dispatch", replacementPiece);
        verify(shopifyGateway, times(1)).pushExchangeDispatch(anyString(), anyString(), anyString(), eq(TRACED_GID), eq(-1), anyString(), anyString());

        scanBackAndDisposition(a, r.originalPiece(), "restock");

        assertThat(row(r.id()).get("status")).isEqualTo("exchanged");
        assertThat(eventTypes(r.id())).contains("received", "item_done", "exchanged").doesNotContain("refund_pending");
        assertThat(jdbc.queryForObject("SELECT status FROM exchanges WHERE tracking_number = ?", String.class, tn)).isEqualTo("return_received");
        assertThat(jdbc.queryForObject("SELECT active FROM return_request_items WHERE request_id = ?", Boolean.class, r.id())).isFalse();
        TenantContext.set(a.id());
        assertStatus(409, () -> requests.recordRefund(r.id(), "cash", new java.math.BigDecimal("100"), LocalDate.now(), null, null, a.owner()));
        assertStatus(409, () -> requests.markRefunded(r.id(), a.owner()));
        TenantContext.clear();
        verify(shopifyGateway, times(1)).pushExchangeDispatch(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void e2_oldItemDamaged_stillExchanged() {
        Req r = approved(a, "#8002", 1);
        booking.book(r.id(), a.id());
        scanBackAndDisposition(a, r.originalPiece(), "damaged");
        assertThat(row(r.id()).get("status")).isEqualTo("exchanged");
    }

    @Test
    void e3_failedSwap_replacementBackAndRestocked_netZero_closeExchangeFailed() throws Exception {
        Req r = approved(a, "#8003", 1);
        String tn = NEXT_TRACKING.get();
        booking.book(r.id(), a.id());
        String replacementPiece = pickAndHandOver(a, tn);
        awaitTrigger("exchange_dispatch", replacementPiece);

        // The customer refused at the door: the replacement comes back and is restocked (+1).
        scanBackAndDisposition(a, replacementPiece, "restock");
        awaitTrigger("return_inspection", replacementPiece);
        assertThat(count("SELECT COALESCE(SUM(delta), 0) FROM shopify_inventory_adjustments WHERE status = 'applied' " +
            "AND ((trigger_type = 'exchange_dispatch' AND trigger_id = ?) " +
            "  OR (trigger_type = 'return_inspection' AND split_part(trigger_id, ':', 1) = ?))",
            replacementPiece, replacementPiece)).as("-1 on departure, +1 on restock").isZero();

        TenantContext.set(a.id());
        requests.close(r.id(), "exchange_failed", "Customer refused the new size", a.owner());
        TenantContext.clear();
        assertThat(jdbc.queryForMap("SELECT status::text AS status, close_reason FROM return_requests WHERE id = ?", r.id()))
            .containsEntry("status", "closed").containsEntry("close_reason", "exchange_failed");

        Req refund = approvedRefund(a, "#8004");
        TenantContext.set(a.id());
        assertStatus(400, () -> requests.close(refund.id(), "exchange_failed", null, a.owner()));
        TenantContext.clear();
    }

    // ── X: cross-tenant on a real app_user connection ─────────────────────────

    @Test
    void x1_crossTenant_bookBookNowConfirm_withSameTenantPositiveControls() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        ReturnPickupBookingService appUser = new ReturnPickupBookingService(new JdbcTemplate(ds),
            new DataSourceTransactionManager(ds), bostaV2, gateway, encryption, scheduler, M, exchangeService);

        Req mine = approved(a, "#9001", 1);
        Req theirs = approved(b, "#9001", 1);
        appUser.book(theirs.id(), a.id());
        assertThat(POSTS).as("B's request is invisible to a tenant-A job").isEmpty();
        assertThat(row(theirs.id()).get("booking_status")).isNull();
        appUser.book(mine.id(), a.id());
        assertThat(POSTS).hasSize(1);
        assertThat(POST_AUTH).containsExactly(KEY_A);
        assertThat(row(mine.id()).get("booking_status")).isEqualTo("booked");

        Req mineOld = approved(a, "#9002", 1);
        Req theirsOld = approved(b, "#9002", 1);
        clearInvocations(jobScheduler);
        assertStatus(404, () -> TenantContext.runAs(a.id(), () -> appUser.bookNow(theirsOld.id())));
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));
        TenantContext.runAs(a.id(), () -> appUser.bookNow(mineOld.id()));
        verify(jobScheduler).enqueue(any(IocJobLambda.class));

        jdbc.update("UPDATE return_requests SET booking_status = 'failed_ambiguous', booking_attempted_at = now() WHERE id IN (?, ?)",
            mineOld.id(), theirsOld.id());
        ObjectNode d = stored(M.valueToTree(Map.of("type", 30, "cod", 0, "businessReference", "#9002",
            "dropOffAddress", Map.of("firstLine", "12 Placeholder Street, Block 4", "districtId", NASR),
            "specs", Map.of("packageDetails", Map.of("itemsCount", 1, "description", "x")),
            "returnSpecs", Map.of("packageDetails", Map.of("itemsCount", 1, "description", "y")))), "7550000001");
        DELIVERIES.put("7550000001", d);
        assertStatus(404, () -> TenantContext.runAs(a.id(), () -> appUser.confirmBooked(theirsOld.id(), "7550000001")));
        assertThat(row(theirsOld.id()).get("booking_status")).isEqualTo("failed_ambiguous");
        TenantContext.runAs(a.id(), () -> appUser.confirmBooked(mineOld.id(), "7550000001"));
        assertThat(row(mineOld.id()).get("booking_status")).isEqualTo("booked");
        assertThat(count("SELECT COUNT(*) FROM exchanges WHERE return_request_id = ?", mineOld.id())).isEqualTo(1);
    }

    @Test
    void x2_confirmByTracking_rejectsATypeOtherThan30ForAnExchange() {
        Req r = approved(a, "#9101", 1);
        jdbc.update("UPDATE return_requests SET booking_status = 'failed_ambiguous', booking_attempted_at = now() WHERE id = ?", r.id());
        ObjectNode crp = stored(M.valueToTree(Map.of("type", 25, "cod", 0, "businessReference", "#9101",
            "dropOffAddress", Map.of("firstLine", "12 Placeholder Street, Block 4", "districtId", NASR))), "7560000001");
        DELIVERIES.put("7560000001", crp);
        TenantContext.set(a.id());
        assertThatThrownBy(() -> booking.confirmBooked(r.id(), "7560000001"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getReason()).isEqualTo("That delivery isn't a Bosta exchange."));
        TenantContext.clear();
    }

    // ── W: portal areas ───────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void w1_lookupOffersExchangeDistrictIds_submitRejectsAPickupOnlyDistrict() {
        Order o = deliveredOrder(a, "#10001", 1);
        PortalService.LookupResult res = portal.lookup(a.slug(), "10001", "01000000001").orElseThrow();
        Map<String, Object> pickup = (Map<String, Object>) res.body().get("pickup");
        assertThat((List<Map<String, Object>>) pickup.get("districts")).extracting(m -> m.get("id")).contains(NASR, PICKUP_ONLY);
        assertThat((List<String>) pickup.get("exchangeDistrictIds")).containsExactly(NASR);

        String token = tokens.issue(a.id(), o.id());
        PortalService.SubmitLine line = new PortalService.SubmitLine(a.original(), 1, "wrong_size");
        assertThat(portal.submit(a.slug(), token, new PortalService.SubmitRequest(List.of(line), null, null, PICKUP_ONLY,
            "exchange", a.replacement(), true)).orElseThrow().outcome()).isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(portal.submit(a.slug(), token, new PortalService.SubmitRequest(List.of(line), null, null, NASR,
            "exchange", a.replacement(), true)).orElseThrow().outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void w2_changeArea_exchangeOffersAndAcceptsOnlyPickupAndDropoff_refundUnchanged() {
        Req exchange = approved(a, "#10101", 1);
        Req refund = approvedRefund(a, "#10102");
        TenantContext.set(a.id());
        try {
            assertThat((List<Map<String, Object>>) requests.pickupAreas(exchange.id()).get("districts"))
                .extracting(m -> m.get("id")).containsExactly(NASR);
            assertStatus(400, () -> requests.setPickupArea(exchange.id(), PICKUP_ONLY));
            requests.setPickupArea(exchange.id(), NASR);

            assertThat((List<Map<String, Object>>) requests.pickupAreas(refund.id()).get("districts"))
                .extracting(m -> m.get("id")).containsExactlyInAnyOrder(NASR, PICKUP_ONLY);
            requests.setPickupArea(refund.id(), PICKUP_ONLY);
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT pickup_district_id FROM return_requests WHERE id = ?", String.class, exchange.id())).isEqualTo(NASR);
        assertThat(jdbc.queryForObject("SELECT pickup_district_id FROM return_requests WHERE id = ?", String.class, refund.id())).isEqualTo(PICKUP_ONLY);
    }

    // ── S: settings switch ───────────────────────────────────────────────────

    @Test
    void s1_allowExchanges_needsBookingAccountAndLocation_stampsSinceOnTheFlip_bookingOffSwitchesItOff() {
        jdbc.update("UPDATE tenants SET portal_exchanges_enabled = false, portal_exchanges_since = NULL, portal_pickup_booking = false WHERE id = ?", a.id());
        TenantContext.set(a.id());
        try {
            assertCode("EXCHANGES_NEEDS_BOOKING", () -> settings.update(settingsWith(false, true)));
            jdbc.update("UPDATE courier_accounts SET return_business_location_id = NULL WHERE tenant_id = ?", a.id());
            assertCode("EXCHANGES_NEEDS_RETURN_LOCATION", () -> settings.update(settingsWith(false, true)));
            jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", a.id());
            assertCode("EXCHANGES_NEEDS_BOSTA", () -> settings.update(settingsWith(false, true)));
            jdbc.update("UPDATE courier_accounts SET status = 'active', return_business_location_id = 'loc-nour-5c' WHERE tenant_id = ?", a.id());

            Map<String, Object> on = settings.update(settingsWith(true, true));
            assertThat(on.get("exchangesEnabled")).isEqualTo(true);
            Object since = on.get("exchangesSince");
            assertThat(since).isNotNull();
            assertThat(settings.update(settingsWith(true, true)).get("exchangesSince")).as("only the off→on flip stamps").isEqualTo(since);

            assertThat(settings.update(settingsWith(false, null)).get("exchangesEnabled")).isEqualTo(false);
        } finally {
            TenantContext.clear();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    record Order(UUID id, String number, String piece) {}
    record Req(UUID id, String reference, UUID orderId, String originalPiece) {}

    private Tenant tenant(String name, String slug, String key) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since, " +
                    "    portal_exchanges_enabled, portal_exchanges_since) " +
                    "VALUES (?, ?, ?, true, true, now() - interval '2 days', true, now() - interval '1 day')", id, name, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, access_token_scopes) " +
                    "VALUES (?, ?, ?, 'connected', 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            owner, id, "owner-" + owner + "@test.local");
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
                    "    return_business_location_id, return_business_location_name) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, ?, 'active', ?, 'Maadi Warehouse')",
            id, encryption.encrypt(key), "hash-" + slug, "loc-" + slug);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
                    "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, id, TRACED_GID + slug.length());
        if ("nour-5c".equals(slug)) {
            jdbc.update("UPDATE locations SET shopify_location_id = ? WHERE id = ?", TRACED_GID, location);
        }
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Linen Shirt', 'active')",
            product, id, store, "gid://shopify/Product/" + slug);
        UUID original = variant(id, product, "White / M");
        UUID replacement = variant(id, product, "White / L");
        UUID otherProduct = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Wool Scarf', 'active')",
            otherProduct, id, store, "gid://shopify/Product/scarf-" + slug);
        UUID other = variant(id, otherProduct, "Grey");
        return new Tenant(id, store, original, replacement, other, owner, location, slug);
    }

    private UUID variant(UUID tenant, UUID product, String title) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, product, "gid://shopify/ProductVariant/" + v, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    /** Delivered order, a delivered forward leg with a full raw address, one delivered piece of the original variant. */
    private Order deliveredOrder(Tenant t, String number, int replacementStock) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona Placeholder', '01000000001', 'bosta') RETURNING id",
            UUID.class, t.id(), t.store(), "gid://shopify/Order/" + UUID.randomUUID(), number);
        String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"}," +
            "\"dropOffAddress\":{\"firstLine\":\"12 Placeholder Street, Block 4\",\"secondLine\":\"Near the pharmacy\"," +
            "\"buildingNumber\":\"12\",\"floor\":\"3\",\"apartment\":\"7\",\"city\":{\"_id\":\"" + CAIRO + "\",\"name\":\"Cairo\"}," +
            "\"district\":{\"_id\":\"" + NASR + "\",\"name\":\"Nasr City\"}}," +
            "\"receiver\":{\"firstName\":\"Mona\",\"lastName\":\"Placeholder\",\"fullName\":\"Mona Placeholder\",\"phone\":\"+201000000001\"}}";
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at, raw) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '2 days', ?::jsonb)",
            t.id(), order, String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 1_999_999_999L)), raw);
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                    piece, t.id(), t.original(), "PC-" + piece, piece, order);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                    item, t.id(), order, t.original());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                    t.id(), item, piece);
        for (int i = 0; i < replacementStock; i++) {
            String s = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                        "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
                        s, t.id(), t.replacement(), "PC-" + s, s, t.location());
        }
        return new Order(order, number, piece);
    }

    private Req approved(Tenant t, String number, int replacementStock) {
        return request(t, number, replacementStock, "approved", "exchange");
    }

    private Req requested(Tenant t, String number) {
        return request(t, number, 1, "requested", "exchange");
    }

    private Req approvedRefund(Tenant t, String number) {
        return request(t, number, 0, "approved", "refund");
    }

    private Req request(Tenant t, String number, int replacementStock, String status, String type) {
        Order o = deliveredOrder(t, number, replacementStock);
        String reference = "RR-" + ref();
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at, pickup_city_id, pickup_city_name, " +
            "    pickup_district_id, pickup_district_name, refund_fallback_ok) " +
            "VALUES (?, ?, ?, ?::return_request_status, ?, CASE WHEN ? = 'approved' THEN now() END, ?, 'Cairo', ?, 'Nasr City - 7th District', true) RETURNING id",
            UUID.class, t.id(), o.id(), type, status, reference, status, CAIRO, NASR);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, replacement_variant_id) " +
                    "VALUES (?, ?, ?, ?, 'wrong_size', ?)",
            t.id(), id, o.piece(), t.original(), "exchange".equals(type) ? t.replacement() : null);
        return new Req(id, reference, o.id(), o.piece());
    }

    private static String ref() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }

    /** Picks a replacement piece for the internal order, packs it and hands it to the courier (pickup close). */
    private String pickAndHandOver(Tenant t, String tn) {
        UUID orderId = jdbc.queryForObject("SELECT outbound_order_id FROM exchanges WHERE tracking_number = ?", UUID.class, tn);
        String piece = jdbc.queryForObject("SELECT id FROM pieces WHERE variant_id = ? AND status = 'available' LIMIT 1",
            String.class, t.replacement());
        TenantContext.set(t.id());
        try {
            assertThat(fulfill.scan(orderId, "PC-" + piece, t.owner()).success()).isTrue();
            fulfill.complete(orderId, t.owner());
        } finally {
            TenantContext.clear();
        }
        UUID pickup = pickups.openSession(t.id(), t.owner(), LocalDate.now(), null, null);
        assertThat(pickups.scan(t.id(), pickup, t.owner(), tn).outcome()).isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        pickups.closeSession(t.id(), pickup, t.owner());
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece)).isEqualTo("with_courier");
        return piece;
    }

    private void scanBackAndDisposition(Tenant t, String piece, String disposition) {
        TenantContext.set(t.id());
        try {
            UUID s = returnSessions.createSession(null, t.owner());
            returnSessions.scan(s, "PC-" + piece, t.location(), t.owner());
            returnSessions.disposition(s, piece, disposition, "damaged".equals(disposition) ? "Torn seam" : null, t.location(), t.owner());
        } finally {
            TenantContext.clear();
        }
    }

    private void ingest(Tenant t, String tn) {
        boolean enqueued = TenantContext.runAs(t.id(), () -> ingestionHelper.ingestDelivery(t.id(), KEY_A, tn, "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, t.id(), tn);
        webhookJob.process(eventId, t.id());
        TenantContext.clear();
    }

    private void awaitTrigger(String triggerType, String piece) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments " +
                "WHERE trigger_type = ? AND split_part(trigger_id, ':', 1) = ? AND status <> 'pending'", Integer.class, triggerType, piece);
            if (done != null && done >= 1) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + triggerType + " on " + piece);
    }

    private VariantStockService.VariantStock stockOf(Tenant t, UUID variant) {
        VariantStockService svc = new VariantStockService(jdbc);
        Map<UUID, VariantStockService.VariantStock> all = TenantContext.runAs(t.id(),
            () -> new TransactionTemplate(txm).execute(s -> svc.computeAll()));
        return svc.forVariant(all, variant);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status::text AS status, booking_status, booking_error, bosta_tracking_number, " +
            "booking_verified_at FROM return_requests WHERE id = ?", id);
    }

    private List<String> eventTypes(UUID requestId) {
        return jdbc.queryForList("SELECT event_type FROM return_request_events WHERE request_id = ? ORDER BY occurred_at, id",
            String.class, requestId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static List<String> names(JsonNode n) {
        List<String> names = new ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @SuppressWarnings("unchecked")
    private void assertEnqueuedFor(UUID requestId, UUID tenantId) throws Exception {
        ArgumentCaptor<IocJobLambda> captor = ArgumentCaptor.forClass(IocJobLambda.class);
        verify(jobScheduler, times(1)).enqueue(captor.capture());
        ReturnPickupBookingJob job = mock(ReturnPickupBookingJob.class);
        captor.getValue().accept(job);
        verify(job).book(requestId, tenantId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> problems(Tenant t, String type) {
        return TenantContext.runAs(t.id(), () -> new TransactionTemplate(txm).execute(s ->
            (List<Map<String, Object>>) exceptions.listExceptions(type, null, 0, 100).get("items")));
    }

    private static PortalSettingsService.Settings settingsWith(boolean booking, Boolean exchanges) {
        return new PortalSettingsService.Settings("nour-5c", true, false, 30, null, null, null, null, booking, exchanges);
    }

    private void assertCode(String code, Runnable body) {
        assertThatThrownBy(body::run).isInstanceOfSatisfying(PortalSettingsService.FieldException.class,
            e -> assertThat(e.code()).isEqualTo(code));
    }

    private void assertStatus(int status, Runnable body) {
        assertThatThrownBy(body::run).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(status));
    }
}
