package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaV2Client;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Returns portal Step 4c-3 — booking the Bosta CUSTOMER_RETURN_PICKUP (type 25) on approval.
 *
 * Bosta is a REAL local HTTP stub (com.sun HttpServer), bound as bosta.base-url: it answers
 * the v2 create (POST /api/v2/deliveries?apiVersion=1) with a per-test behaviour and the v0
 * read (GET /api/v0/deliveries/{tn}) used by the read-back, the manual tracking entry and the
 * CRP ingest. Every POST it receives is counted and kept, so "exactly one POST" is observed,
 * not assumed. The create read timeout is 1 s here (bosta.create-read-timeout).
 *
 * JobScheduler is mocked: enqueues are captured and asserted (and the captured lambda is run
 * against a mock job to prove which request it books). Booking itself is called directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnPickupBookingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    // ── Bosta stub ────────────────────────────────────────────────────────────

    enum Create { CREATED, BAD_REQUEST, RATE_LIMITED, SERVER_ERROR, TIMEOUT, DROPPED, CREATED_NO_TRACKING }

    static final AtomicReference<Create> CREATE = new AtomicReference<>(Create.CREATED);
    static final AtomicReference<String> NEXT_TRACKING = new AtomicReference<>("5100000001");
    static final List<JsonNode> POSTS = new CopyOnWriteArrayList<>();
    static final List<String> POST_AUTH = new CopyOnWriteArrayList<>();
    /** GET /api/v0/deliveries/{tn} → delivery JSON (the "data" object), or null for 404. */
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
                                DELIVERIES.put(tn, rearranged(body, tn));
                                send(ex, 200, "{\"success\":true,\"message\":\"Done successfully.\",\"data\":{\"_id\":\"del-" + tn +
                                    "\",\"trackingNumber\":\"" + tn + "\",\"state\":{\"code\":10,\"value\":\"Pickup requested\"},\"creationSrc\":\"API\"}}");
                            }
                            case BAD_REQUEST -> send(ex, 400, "{\"success\":false,\"message\":\"Invalid district for the given city\",\"errorCode\":\"3004\"}");
                            case RATE_LIMITED -> send(ex, 429, "{\"success\":false,\"errorCode\":429,\"retryAfter\":60}");
                            case SERVER_ERROR -> send(ex, 500, "{\"success\":false,\"message\":\"Internal Server Error\",\"errorCode\":1000}");
                            case CREATED_NO_TRACKING -> send(ex, 200, "{\"success\":true,\"data\":{}}");
                            case TIMEOUT -> { Thread.sleep(2500); send(ex, 200, "{}"); }
                            case DROPPED -> { /* close without answering — the client sees the connection end */ }
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

    /** What Bosta stores for a CRP created from {@code body}: customer moved to pickupAddress. */
    static ObjectNode rearranged(JsonNode body, String tn) {
        ObjectNode d = M.createObjectNode();
        d.put("_id", "del-" + tn);
        d.put("trackingNumber", tn);
        d.putObject("type").put("code", 25).put("value", "Customer Return Pickup");
        d.putObject("state").put("code", 10).put("value", "Pickup requested");
        d.put("businessReference", body.path("businessReference").asText());
        d.put("cod", body.path("cod").asInt());
        d.set("returnSpecs", body.path("returnSpecs").deepCopy());
        ObjectNode pickup = d.putObject("pickupAddress");
        pickup.put("firstLine", body.path("dropOffAddress").path("firstLine").asText());
        pickup.putObject("district").put("_id", body.path("dropOffAddress").path("districtId").asText()).put("name", "Nasr City");
        ObjectNode drop = d.putObject("dropOffAddress");
        drop.put("firstLine", "Merchant warehouse street");
        drop.putObject("district").put("_id", "MERCHANT-DISTRICT").put("name", "Maadi");
        d.put("createdAt", Instant.now().toString());
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

    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0", GONE = "GoneDistrict1";
    static final String KEY_A = "bosta-raw-key-tenant-a", KEY_B = "bosta-raw-key-tenant-b";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EncryptionService encryption;
    @Autowired ReturnPickupBookingService booking;
    @Autowired ReturnRequestService requests;
    @Autowired PortalTokenService tokens;
    @Autowired ExceptionService exceptions;
    @Autowired BostaIngestionHelper ingestionHelper;
    @Autowired BostaWebhookJob webhookJob;
    @Autowired BostaV2Client bostaV2;
    @Autowired BostaGateway gateway;
    @Autowired PickupBookingScheduler scheduler;
    @Autowired PlatformTransactionManager txm;
    @MockBean JobScheduler jobScheduler;

    final TestRestTemplate jdkRest = new TestRestTemplate(
        new org.springframework.boot.web.client.RestTemplateBuilder()
            .requestFactory(() -> new org.springframework.http.client.JdkClientHttpRequestFactory()));

    record Tenant(UUID id, UUID store, UUID variant, UUID variant2, UUID owner, String slug) {}

    Tenant a, b;
    String ownerA, workerA;

    @BeforeAll
    void setup() {
        a = tenant("Snouts Store", "snouts-4c3", KEY_A);
        b = tenant("Jumi Store", "jumi-4c3", KEY_B);
        ownerA  = login(a.owner());
        workerA = login(user(a.id(), "worker"));
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true), " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Gone District', 'حي قديم', false, true)",
                    NASR, CAIRO, GONE, CAIRO);
    }

    @BeforeEach
    void reset() {
        CREATE.set(Create.CREATED);
        NEXT_TRACKING.set(String.valueOf(ThreadLocalRandom.current().nextLong(5_000_000_000L, 5_999_999_999L)));
        POSTS.clear();
        POST_AUTH.clear();
        DELIVERIES.clear();
        clearInvocations(jobScheduler);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (Tenant t : List.of(a, b)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t.id());
            jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", t.id());
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
            jdbc.update("UPDATE tenants SET portal_pickup_booking = true, portal_pickup_booking_since = now() - interval '1 day', " +
                        "portal_auto_approve = false WHERE id = ?", t.id());
            jdbc.update("UPDATE courier_accounts SET status = 'active', return_business_location_id = ?, " +
                        "return_business_location_name = 'Maadi Warehouse' WHERE tenant_id = ?", "loc-" + t.slug(), t.id());
        }
    }

    // ── Payload ───────────────────────────────────────────────────────────────

    @Test
    void payload_exactFieldSetAndValues_rawKey_forbiddenFieldsAbsent_thenBookedAndVerified() {
        Req r = approvedRequest(a, "#1047", 2);

        booking.book(r.id(), a.id());

        assertThat(POSTS).hasSize(1);
        assertThat(POST_AUTH).containsExactly(KEY_A);
        JsonNode p = POSTS.get(0);
        assertThat(fieldNames(p)).containsExactlyInAnyOrder("type", "cod", "dropOffAddress", "businessLocationId",
            "receiver", "businessReference", "uniqueBusinessReference", "returnSpecs", "returnNotes");
        assertThat(p.path("type").asInt()).isEqualTo(25);
        assertThat(p.path("cod").isInt() && p.path("cod").asInt() == 0).isTrue();
        assertThat(fieldNames(p.path("dropOffAddress"))).containsExactlyInAnyOrder(
            "firstLine", "secondLine", "buildingNumber", "floor", "apartment", "city", "districtId");
        assertThat(p.path("dropOffAddress").path("firstLine").asText()).isEqualTo("12 Placeholder Street, Block 4");
        assertThat(p.path("dropOffAddress").path("secondLine").asText()).isEqualTo("Near the pharmacy");
        assertThat(p.path("dropOffAddress").path("buildingNumber").asText()).isEqualTo("12");
        assertThat(p.path("dropOffAddress").path("floor").asText()).isEqualTo("3");
        assertThat(p.path("dropOffAddress").path("apartment").asText()).isEqualTo("7");
        assertThat(p.path("dropOffAddress").path("city").asText()).isEqualTo("Cairo");
        assertThat(p.path("dropOffAddress").path("districtId").asText()).isEqualTo(NASR);
        assertThat(p.path("businessLocationId").asText()).isEqualTo("loc-snouts-4c3");
        assertThat(fieldNames(p.path("receiver"))).containsExactlyInAnyOrder("firstName", "lastName", "phone");
        assertThat(p.path("receiver").path("firstName").asText()).isEqualTo("Mona");
        assertThat(p.path("receiver").path("lastName").asText()).isEqualTo("Placeholder");
        assertThat(p.path("receiver").path("phone").asText()).isEqualTo("01000000001");
        assertThat(p.path("businessReference").asText()).isEqualTo("#1047");
        assertThat(p.path("uniqueBusinessReference").asText()).isEqualTo(r.id().toString());
        assertThat(fieldNames(p.path("returnSpecs"))).containsExactly("packageDetails");
        assertThat(p.path("returnSpecs").path("packageDetails").path("itemsCount").asInt()).isEqualTo(2);
        assertThat(p.path("returnSpecs").path("packageDetails").path("description").asText())
            .isEqualTo(r.reference() + ": Linen Shirt / Sand · M × 2");
        assertThat(p.path("returnNotes").asText()).isEqualTo("Traced return request " + r.reference());
        for (String forbidden : List.of("pickupAddress", "returnAddress", "allowToOpenPackage", "webhookUrl", "specs")) {
            assertThat(p.has(forbidden)).as(forbidden).isFalse();
        }

        Map<String, Object> row = row(r.id());
        assertThat(row).containsEntry("booking_status", "booked").containsEntry("status", "pickup_booked")
            .containsEntry("bosta_tracking_number", NEXT_TRACKING.get()).containsEntry("bosta_delivery_id", "del-" + NEXT_TRACKING.get());
        assertThat(row.get("booking_verified_at")).as("read-back matched (customer district under pickupAddress)").isNotNull();
        assertThat(row.get("booking_error")).isNull();
    }

    @Test
    void description_truncatedTo250() {
        Req r = approvedRequest(a, "#1048", 1);
        jdbc.update("UPDATE products SET title = ? WHERE id = (SELECT product_id FROM variants WHERE id = ?)",
            "X".repeat(400), a.variant());
        try {
            booking.book(r.id(), a.id());
            assertThat(POSTS.get(0).path("returnSpecs").path("packageDetails").path("description").asText()).hasSize(250);
        } finally {
            jdbc.update("UPDATE products SET title = 'Linen Shirt' WHERE id = (SELECT product_id FROM variants WHERE id = ?)", a.variant());
        }
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    void twoConcurrentJobs_exactlyOnePost_andARerunAfterBookedPostsNothing() throws Exception {
        Req r = approvedRequest(a, "#2001", 1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) fs.add(pool.submit(() -> { go.await(); booking.book(r.id(), a.id()); return null; }));
        go.countDown();
        for (Future<?> f : fs) f.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(POSTS).as("exactly one POST for two concurrent jobs").hasSize(1);
        assertThat(row(r.id()).get("booking_status")).isEqualTo("booked");

        booking.book(r.id(), a.id());
        assertThat(POSTS).as("a retry after 'booked' never POSTs").hasSize(1);
    }

    // ── No automatic retry ────────────────────────────────────────────────────

    @Test
    void timeout_5xx_dropped_unreadable2xx_exactlyOnePostEach_failedAmbiguous_neverRePosted() {
        for (Create c : List.of(Create.TIMEOUT, Create.SERVER_ERROR, Create.DROPPED, Create.CREATED_NO_TRACKING)) {
            POSTS.clear();
            CREATE.set(c);
            Req r = approvedRequest(a, "#3" + c.ordinal(), 1);

            booking.book(r.id(), a.id());

            assertThat(POSTS).as(c + ": exactly one POST").hasSize(1);
            assertThat(row(r.id()).get("booking_status")).as(c.name()).isEqualTo("failed_ambiguous");
            assertThat((String) row(r.id()).get("booking_error")).contains("Check in Bosta");
            booking.book(r.id(), a.id());
            assertThat(POSTS).as(c + ": never re-POSTed automatically").hasSize(1);
        }
    }

    @Test
    void badRequest_failedWithBostaMessage_rateLimited_failed_bothRetryable() {
        CREATE.set(Create.BAD_REQUEST);
        Req r1 = approvedRequest(a, "#4001", 1);
        booking.book(r1.id(), a.id());
        assertThat(row(r1.id())).containsEntry("booking_status", "failed")
            .containsEntry("booking_error", "Invalid district for the given city");

        CREATE.set(Create.RATE_LIMITED);
        Req r2 = approvedRequest(a, "#4002", 1);
        booking.book(r2.id(), a.id());
        assertThat(row(r2.id()).get("booking_status")).isEqualTo("failed");
        assertThat((String) row(r2.id()).get("booking_error")).contains("rate-limiting");

        CREATE.set(Create.CREATED);
        booking.book(r1.id(), a.id());
        assertThat(POSTS).as("'failed' is re-claimable: the retry POSTs once more").hasSize(3);
        assertThat(row(r1.id()).get("booking_status")).isEqualTo("booked");
    }

    // ── Preconditions ─────────────────────────────────────────────────────────

    @Test
    void eachFailingPrecondition_failedWithReason_noPost() {
        record Case(String expect, java.util.function.Consumer<Req> breaker, Runnable restore) {}
        List<Case> cases = List.of(
            new Case("isn't approved", r -> jdbc.update("UPDATE return_requests SET status = 'requested' WHERE id = ?", r.id()), () -> {}),
            new Case("switched off", r -> jdbc.update("UPDATE tenants SET portal_pickup_booking = false WHERE id = ?", a.id()),
                () -> jdbc.update("UPDATE tenants SET portal_pickup_booking = true WHERE id = ?", a.id())),
            new Case("no active Bosta account", r -> jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", a.id()),
                () -> jdbc.update("UPDATE courier_accounts SET status = 'active' WHERE tenant_id = ?", a.id())),
            new Case("where returns go back to", r -> jdbc.update("UPDATE courier_accounts SET return_business_location_id = NULL WHERE tenant_id = ?", a.id()),
                () -> jdbc.update("UPDATE courier_accounts SET return_business_location_id = 'loc-snouts-4c3' WHERE tenant_id = ?", a.id())),
            new Case("pickup area", r -> jdbc.update("UPDATE return_requests SET pickup_district_id = NULL WHERE id = ?", r.id()), () -> {}),
            new Case("isn't available", r -> jdbc.update("UPDATE return_requests SET pickup_district_id = ? WHERE id = ?", GONE, r.id()), () -> {}),
            new Case("privacy request", r -> jdbc.update("UPDATE orders SET pii_redacted_at = now() WHERE id = ?", r.orderId()), () -> {}),
            new Case("missing or too short", r -> jdbc.update(
                "UPDATE shipments SET raw = jsonb_set(raw, '{dropOffAddress,firstLine}', '\"12 St\"') WHERE order_id = ?", r.orderId()), () -> {}));
        int i = 0;
        for (Case c : cases) {
            Req r = approvedRequest(a, "#5" + (i++), 1);
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

    // ── Enqueue after commit ──────────────────────────────────────────────────

    @Test
    void approve_rolledBack_noJob_committed_oneJobForThatRequest() throws Exception {
        Req r = requestedRequest(a, "#6001", 1);
        TransactionTemplate outer = new TransactionTemplate(txm);
        TenantContext.set(a.id());
        outer.execute(s -> { requests.approve(r.id(), a.owner()); s.setRollbackOnly(); return null; });
        TenantContext.clear();
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));
        assertThat(row(r.id()).get("status")).isEqualTo("requested");

        assertThat(post("/api/v1/return-requests/" + r.id() + "/approve", null, ownerA).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertEnqueuedFor(r.id(), a.id());
    }

    @Test
    void approve_bookingOff_noJob() {
        jdbc.update("UPDATE tenants SET portal_pickup_booking = false WHERE id = ?", a.id());
        Req r = requestedRequest(a, "#6002", 1);
        assertThat(post("/api/v1/return-requests/" + r.id() + "/approve", null, ownerA).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));
    }

    @Test
    void autoApproveSubmit_jobEnqueuedAfterCommit() throws Exception {
        jdbc.update("UPDATE tenants SET portal_auto_approve = true WHERE id = ?", a.id());
        Order o = deliveredOrder(a, "#6003", 1);
        String token = tokens.issue(a.id(), o.id());
        Map<String, Object> body = Map.of(
            "lines", List.of(Map.of("variantId", a.variant().toString(), "quantity", 1, "reasonCode", "wrong_size")),
            "districtId", NASR);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        ResponseEntity<Map> resp = jdkRest.exchange(base() + "/api/v1/portal/snouts-4c3/requests", HttpMethod.POST,
            new HttpEntity<>(M.writeValueAsString(body), h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().keySet()).as("nothing internal leaks").containsExactlyInAnyOrder("reference", "status");
        UUID id = jdbc.queryForObject("SELECT id FROM return_requests WHERE reference = ?", UUID.class, resp.getBody().get("reference"));
        assertEnqueuedFor(id, a.id());
    }

    // ── Sweeper ───────────────────────────────────────────────────────────────

    @Test
    void sweeper_orphanEnqueued_preSwitchApprovalIgnored_stuckPendingAmbiguous_unverifiedRechecked() throws Exception {
        Req orphan = approvedRequest(a, "#7001", 1);
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '5 minutes' WHERE id = ?", orphan.id());
        Req tooNew = approvedRequest(a, "#7002", 1);                                   // decided just now
        Req preSwitch = approvedRequest(a, "#7003", 1);
        jdbc.update("UPDATE return_requests SET decided_at = now() - interval '3 days' WHERE id = ?", preSwitch.id());
        Req stuck = approvedRequest(a, "#7004", 1);
        jdbc.update("UPDATE return_requests SET booking_status = 'pending', booking_attempted_at = now() - interval '20 minutes' WHERE id = ?", stuck.id());
        Req unverified = approvedRequest(a, "#7005", 1);
        booking.book(unverified.id(), a.id());
        jdbc.update("UPDATE return_requests SET booking_verified_at = NULL WHERE id = ?", unverified.id());
        clearInvocations(jobScheduler);

        ReturnPickupBookingService.SweepResult res = booking.sweepTenant(a.id());

        assertThat(res.enqueued()).isEqualTo(1);
        assertEnqueuedFor(orphan.id(), a.id());
        assertThat(row(stuck.id())).containsEntry("booking_status", "failed_ambiguous")
            .containsEntry("booking_error", "The booking may or may not have reached Bosta — check in Bosta.");
        assertThat(row(unverified.id()).get("booking_verified_at")).as("read-back retried").isNotNull();
        assertThat(row(tooNew.id()).get("booking_status")).isNull();
        assertThat(row(preSwitch.id()).get("booking_status")).isNull();
    }

    // ── Read-back ─────────────────────────────────────────────────────────────

    @Test
    void readBack_customerOnDropOff_alsoPasses_mismatch_needsReview_andRaisesException() {
        // Not rearranged: Bosta kept the customer on dropOffAddress.
        Req r1 = approvedRequest(a, "#8001", 1);
        CREATE.set(Create.CREATED);
        booking.book(r1.id(), a.id());
        ObjectNode d = DELIVERIES.get((String) row(r1.id()).get("bosta_tracking_number"));
        d.remove("pickupAddress");
        ((ObjectNode) d.path("dropOffAddress")).putObject("district").put("_id", NASR);
        jdbc.update("UPDATE return_requests SET booking_verified_at = NULL WHERE id = ?", r1.id());
        booking.sweepTenant(a.id());
        assertThat(row(r1.id()).get("booking_verified_at")).isNotNull();

        // Mismatch: Bosta holds a different item count and district.
        Req r2 = approvedRequest(a, "#8002", 2);
        NEXT_TRACKING.set("5200000002");
        CREATE.set(Create.CREATED);
        DELIVERIES.clear();
        booking.book(r2.id(), a.id());   // read-back of the stub's copy matches → verified
        ObjectNode d2 = DELIVERIES.get("5200000002");
        ((ObjectNode) d2.path("returnSpecs").path("packageDetails")).put("itemsCount", 5);
        ((ObjectNode) d2.path("pickupAddress").path("district")).put("_id", "SomewhereElse");
        jdbc.update("UPDATE return_requests SET booking_verified_at = NULL WHERE id = ?", r2.id());
        booking.sweepTenant(a.id());

        Map<String, Object> row = row(r2.id());
        assertThat(row.get("booking_status")).isEqualTo("needs_review");
        assertThat((String) row.get("booking_error")).isEqualTo("Bosta's delivery differs from the request: itemsCount, district.");
        Map<String, Object> ex = problems(a).stream().filter(e -> r2.reference().equals(e.get("reference"))).findFirst().orElseThrow();
        assertThat(ex.get("severity")).isEqualTo("HIGH");
        assertThat((String) ex.get("descriptionEn")).contains(r2.reference()).contains("#8002").contains("5200000002")
            .contains("details differ");
        assertThat((String) ex.get("descriptionAr")).contains(r2.reference());
        assertThat(ex.get("actionUrl")).isEqualTo("/exchanges?tab=requests&request=" + r2.id());
    }

    @Test
    void exception_firesForFailedAndAmbiguous_withTheRightText() {
        CREATE.set(Create.BAD_REQUEST);
        Req failed = approvedRequest(a, "#8101", 1);
        booking.book(failed.id(), a.id());
        CREATE.set(Create.SERVER_ERROR);
        Req ambiguous = approvedRequest(a, "#8102", 1);
        booking.book(ambiguous.id(), a.id());

        List<Map<String, Object>> list = problems(a);
        assertThat(text(list, failed)).contains("couldn't be booked");
        assertThat(text(list, ambiguous)).contains("may or may not have been booked");
    }

    // ── Linking ───────────────────────────────────────────────────────────────

    @Test
    void linking_webhookFirst_thenBooking_bookingFirst_thenIngest_bothEndLinked() {
        // Webhook first: the CRP's return leg already exists when the booking saves the number.
        Req r1 = approvedRequest(a, "#9001", 1);
        String tn1 = "5300000001";
        UUID leg1 = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'return') RETURNING id",
            UUID.class, a.id(), r1.orderId(), tn1);
        NEXT_TRACKING.set(tn1);
        booking.book(r1.id(), a.id());
        assertThat(row(r1.id()).get("return_shipment_id")).isEqualTo(leg1);

        // Booking first: the CRP is then ingested through the real webhook path (type 25).
        Req r2 = approvedRequest(a, "#9002", 1);
        String tn2 = "5300000002";
        NEXT_TRACKING.set(tn2);
        booking.book(r2.id(), a.id());
        assertThat(row(r2.id()).get("return_shipment_id")).isNull();
        DELIVERIES.get(tn2).put("updatedAt", "2026-09-25T10:00:00.000Z");
        boolean enqueued = TenantContext.runAs(a.id(), () -> ingestionHelper.ingestDelivery(a.id(), KEY_A, tn2, "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, a.id(), tn2);
        webhookJob.process(eventId, a.id());
        UUID leg2 = jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn2);
        assertThat(row(r2.id()).get("return_shipment_id")).isEqualTo(leg2);
    }

    // ── Merchant actions ──────────────────────────────────────────────────────

    @Test
    void retry_onlyFromFailed_enqueues_rolesEnforced() throws Exception {
        CREATE.set(Create.BAD_REQUEST);
        Req r = approvedRequest(a, "#10001", 1);
        booking.book(r.id(), a.id());
        clearInvocations(jobScheduler);

        assertThat(post("/api/v1/return-requests/" + r.id() + "/booking/retry", null, workerA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/v1/return-requests/" + r.id() + "/booking/retry", null, ownerA).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertEnqueuedFor(r.id(), a.id());

        Req notFailed = approvedRequest(a, "#10002", 1);
        assertThat(post("/api/v1/return-requests/" + notFailed.id() + "/booking/retry", null, ownerA).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void notBooked_movesAmbiguousToFailed_andRetries() throws Exception {
        CREATE.set(Create.SERVER_ERROR);
        Req r = approvedRequest(a, "#10101", 1);
        booking.book(r.id(), a.id());
        clearInvocations(jobScheduler);

        assertThat(post("/api/v1/return-requests/" + r.id() + "/booking/retry", null, ownerA).getStatusCode())
            .as("ambiguous can't be retried directly").isEqualTo(HttpStatus.CONFLICT);
        assertThat(post("/api/v1/return-requests/" + r.id() + "/booking/not-booked", null, ownerA).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(row(r.id()).get("booking_status")).isEqualTo("failed");
        assertEnqueuedFor(r.id(), a.id());
    }

    @Test
    void confirmTracking_validCrpOfThisOrder_booked_wrongOrderTypeOrTooOld_rejected() {
        CREATE.set(Create.TIMEOUT);
        Req r = approvedRequest(a, "#11001", 1);
        booking.book(r.id(), a.id());
        assertThat(row(r.id()).get("booking_status")).isEqualTo("failed_ambiguous");

        DELIVERIES.put("5400000001", crp("5400000001", "#99999", 25, Instant.now()));
        DELIVERIES.put("5400000002", crp("5400000002", "#11001", 10, Instant.now()));
        DELIVERIES.put("5400000003", crp("5400000003", "#11001", 25, Instant.now().minusSeconds(86_400)));
        DELIVERIES.put("5400000004", crp("5400000004", "#11001", 25, Instant.now()));

        assertThat(confirm(r, "5400000001")).as("other order").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirm(r, "5400000002")).as("not type 25").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirm(r, "5400000003")).as("created before the claim").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirm(r, "not-a-number")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirm(r, "5499999999")).as("unknown in Bosta").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(row(r.id()).get("booking_status")).isEqualTo("failed_ambiguous");

        assertThat(confirm(r, " 5400 000004 ")).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row = row(r.id());
        assertThat(row).containsEntry("booking_status", "booked").containsEntry("status", "pickup_booked")
            .containsEntry("bosta_tracking_number", "5400000004");
        assertThat(row.get("booking_verified_at")).isNotNull();
        assertThat(POSTS).as("confirming never POSTs").hasSize(1);
    }

    @Test
    void changeArea_blockedOnceBooked() {
        Req r = approvedRequest(a, "#11101", 1);
        booking.book(r.id(), a.id());
        ResponseEntity<Map> resp = rest.exchange(base() + "/api/v1/return-requests/" + r.id() + "/pickup-area", HttpMethod.PUT,
            new HttpEntity<>(Map.of("districtId", NASR), auth(ownerA)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── Settings switch ───────────────────────────────────────────────────────

    @Test
    void settingsSwitch_needsActiveAccountAndSavedLocation_stampsSince() {
        jdbc.update("UPDATE tenants SET portal_pickup_booking = false, portal_pickup_booking_since = NULL WHERE id = ?", a.id());
        jdbc.update("UPDATE courier_accounts SET return_business_location_id = NULL WHERE tenant_id = ?", a.id());
        ResponseEntity<Map> noLocation = rest.exchange(base() + "/api/v1/tenant/portal-settings", HttpMethod.PUT,
            new HttpEntity<>(settings(true), auth(ownerA)), Map.class);
        assertThat(noLocation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(noLocation.getBody()).containsEntry("field", "pickupBooking").containsEntry("error", "BOOKING_NEEDS_RETURN_LOCATION");

        jdbc.update("UPDATE courier_accounts SET status = 'disconnected' WHERE tenant_id = ?", a.id());
        assertThat(rest.exchange(base() + "/api/v1/tenant/portal-settings", HttpMethod.PUT,
            new HttpEntity<>(settings(true), auth(ownerA)), Map.class).getBody()).containsEntry("error", "BOOKING_NEEDS_BOSTA");
        assertThat(get("/api/v1/tenant/portal-settings", ownerA).getBody()).containsEntry("bostaConnected", false)
            .containsEntry("portalPickupBooking", false);

        jdbc.update("UPDATE courier_accounts SET status = 'active', return_business_location_id = 'loc-snouts-4c3' WHERE tenant_id = ?", a.id());
        ResponseEntity<Map> ok = rest.exchange(base() + "/api/v1/tenant/portal-settings", HttpMethod.PUT,
            new HttpEntity<>(settings(true), auth(ownerA)), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("portalPickupBooking", true).containsEntry("bostaConnected", true);
        assertThat(jdbc.queryForObject("SELECT portal_pickup_booking_since FROM tenants WHERE id = ?", Object.class, a.id())).isNotNull();

        assertThat(rest.exchange(base() + "/api/v1/tenant/portal-settings", HttpMethod.PUT,
            new HttpEntity<>(settings(false), auth(ownerA)), Map.class).getBody()).containsEntry("portalPickupBooking", false);
    }

    // ── Cross-tenant on a real app_user connection ────────────────────────────

    @Test
    void crossTenant_onAppUser_bookRetryConfirm_withSameTenantPositiveControls() {
        DataSource appUserDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        ReturnPickupBookingService appUser = new ReturnPickupBookingService(new JdbcTemplate(appUserDs),
            new DataSourceTransactionManager(appUserDs), bostaV2, gateway, encryption, scheduler, M);

        Req mine = approvedRequest(a, "#12001", 1);
        Req theirs = approvedRequest(b, "#12001", 1);

        appUser.book(theirs.id(), a.id());                       // job carrying the wrong tenant
        assertThat(POSTS).as("B's request is invisible to a tenant-A job").isEmpty();
        assertThat(row(theirs.id()).get("booking_status")).isNull();
        appUser.book(mine.id(), a.id());                         // positive control
        assertThat(POSTS).hasSize(1);
        assertThat(POST_AUTH).containsExactly(KEY_A);
        assertThat(row(mine.id()).get("booking_status")).isEqualTo("booked");

        jdbc.update("UPDATE return_requests SET booking_status = 'failed' WHERE id IN (?, ?)", mine.id(), theirs.id());
        jdbc.update("UPDATE return_requests SET status = 'approved' WHERE id = ?", mine.id());
        clearInvocations(jobScheduler);
        assertNotFound(() -> TenantContext.runAs(a.id(), () -> appUser.retry(theirs.id())));
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));
        TenantContext.runAs(a.id(), () -> appUser.retry(mine.id()));
        verify(jobScheduler).enqueue(any(IocJobLambda.class));

        jdbc.update("UPDATE return_requests SET booking_status = 'failed_ambiguous', booking_attempted_at = now(), " +
                    "status = 'approved', bosta_tracking_number = NULL WHERE id IN (?, ?)", mine.id(), theirs.id());
        DELIVERIES.put("5500000001", crp("5500000001", "#12001", 25, Instant.now()));
        assertNotFound(() -> TenantContext.runAs(a.id(), () -> appUser.confirmBooked(theirs.id(), "5500000001")));
        assertThat(row(theirs.id()).get("booking_status")).isEqualTo("failed_ambiguous");
        TenantContext.runAs(a.id(), () -> appUser.confirmBooked(mine.id(), "5500000001"));
        assertThat(row(mine.id()).get("booking_status")).isEqualTo("booked");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    record Order(UUID id, String number) {}
    record Req(UUID id, String reference, UUID orderId) {}

    private Tenant tenant(String name, String slug, String key) {
        UUID id = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since) " +
                    "VALUES (?, ?, ?, true, true, now() - interval '1 day')", id, name, slug);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, id, slug + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, ?, 'owner', true)",
            owner, id, "owner-" + owner + "@test.local", passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
                    "    return_business_location_id, return_business_location_name) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, ?, 'active', ?, 'Maadi Warehouse')",
            id, encryption.encrypt(key), "hash-" + slug, "loc-" + slug);
        return new Tenant(id, store, variant(id, store, "Linen Shirt", "Sand · M"), variant(id, store, "Wool Scarf", "Grey"), owner, slug);
    }

    private UUID user(UUID tenant, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, ?, ?, ?, ?::user_role, true)",
            id, tenant, role, role + "-" + id + "@test.local", passwordEncoder.encode("pass123"), role);
        return id;
    }

    private UUID variant(UUID tenant, UUID store, String product, String title) {
        UUID p = UUID.randomUUID(), v = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            p, tenant, store, "P-" + p, product);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, p, "V-" + v, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    /** Delivered order (placeholder customer), a delivered forward leg with a full raw address, N delivered pieces. */
    private Order deliveredOrder(Tenant t, String number, int pieces) {
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
        for (int i = 0; i < pieces; i++) {
            String piece = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                        "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                        piece, t.id(), t.variant(), "PC-" + piece, piece, order);
            UUID item = UUID.randomUUID();
            jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                        item, t.id(), order, t.variant());
            jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                        t.id(), item, piece);
        }
        return new Order(order, number);
    }

    private Req approvedRequest(Tenant t, String number, int items) {
        return request(t, number, items, "approved");
    }

    private Req requestedRequest(Tenant t, String number, int items) {
        return request(t, number, items, "requested");
    }

    private Req request(Tenant t, String number, int items, String status) {
        Order o = deliveredOrder(t, number, items);
        String reference = "RR-" + ref();
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, decided_at, pickup_city_id, pickup_city_name, " +
            "    pickup_district_id, pickup_district_name) " +
            "VALUES (?, ?, ?::return_request_status, ?, CASE WHEN ? = 'approved' THEN now() END, ?, 'Cairo', ?, 'Nasr City - 7th District') RETURNING id",
            UUID.class, t.id(), o.id(), status, reference, status, CAIRO, NASR);
        for (String piece : jdbc.queryForList("SELECT id FROM pieces WHERE current_order_id = ?", String.class, o.id())) {
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) VALUES (?, ?, ?, ?, 'wrong_size')",
                t.id(), id, piece, t.variant());
        }
        return new Req(id, reference, o.id());
    }

    private static String ref() {
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }

    private static ObjectNode crp(String tn, String businessReference, int type, Instant created) {
        ObjectNode d = M.createObjectNode();
        d.put("_id", "del-" + tn);
        d.put("trackingNumber", tn);
        d.putObject("type").put("code", type).put("value", type == 25 ? "Customer Return Pickup" : "Send");
        d.putObject("state").put("code", 10);
        d.put("businessReference", businessReference);
        d.put("cod", 0);
        d.put("createdAt", created.toString());
        return d;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT status::text AS status, booking_status, booking_error, bosta_delivery_id, " +
            "bosta_tracking_number, booking_verified_at, return_shipment_id FROM return_requests WHERE id = ?", id);
    }

    private static List<String> fieldNames(JsonNode n) {
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
    private List<Map<String, Object>> problems(Tenant t) {
        return TenantContext.runAs(t.id(), () -> new TransactionTemplate(txm).execute(s ->
            (List<Map<String, Object>>) exceptions.listExceptions("pickup_booking_problem", null, 0, 100).get("items")));
    }

    private static String text(List<Map<String, Object>> list, Req r) {
        return list.stream().filter(e -> r.reference().equals(e.get("reference"))).map(e -> (String) e.get("descriptionEn"))
            .findFirst().orElseThrow();
    }

    private HttpStatusCode confirm(Req r, String tracking) {
        return post("/api/v1/return-requests/" + r.id() + "/booking/confirm", Map.of("trackingNumber", tracking), ownerA).getStatusCode();
    }

    private void assertNotFound(Runnable body) {
        assertThatThrownBy(body::run).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    private static Map<String, Object> settings(boolean booking) {
        Map<String, Object> m = new HashMap<>();
        m.put("slug", "snouts-4c3");
        m.put("enabled", true);
        m.put("autoApprove", false);
        m.put("returnWindowDays", 30);
        m.put("pickupBooking", booking);
        return m;
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

    private ResponseEntity<Map> post(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, auth(token)), Map.class);
    }
}
