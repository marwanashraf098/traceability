package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.traceability.identity.JwtService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PortalService;
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
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Non-Bosta couriers G4a — Bosta isolation, golden. One realistic Bosta tenant runs every returns-portal surface
 * G4a touches: portal config, lookup (in window, out of window, wrong phone) and submit (refund with a pickup
 * area); manual approve and auto-approve (the booking jobs they enqueue); the booking job's type-25 and type-30
 * payloads (a real local Bosta stub records every POST) and their read-back; the sweeper (an orphaned approval);
 * the drawer detail / list JSON, the Returns & exchanges cases and counts; Scan returns on the booked CRP leg
 * (return waybill, attributed piece, grading, close and the intake stamps it writes); and the returns analytics.
 *
 * The golden files were recorded on origin/main 73eeb8d — BEFORE any G4a code — with {@code -Dgolden.write=true}:
 *   g1  generic courier OFF                                  → byte-identical to g4a-bosta-isolation-off.json;
 *   g2  generic courier ON, portal switch OFF                → byte-identical to g4a-bosta-isolation-on.json;
 *   g3  generic courier ON, portal switch ON (G4a's switch)  → byte-identical to g4a-bosta-isolation-on.json.
 * (Before G4a the portal switch doesn't exist; the scenario sets it only when the column is there.)
 * Volatile values (ids, references, piece codes, tracking numbers, timestamps, dates) become stable tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenericG4aIsolationGoldenTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    // ── Bosta stub: v2 create (POST /api/v2/deliveries) + v0 read (GET /api/v0/deliveries/{tn}) ──

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

    /** What Bosta stores for a created delivery: type 25 keeps the customer on pickupAddress, type 30 on dropOffAddress. */
    static ObjectNode stored(JsonNode body, String tn) {
        int type = body.path("type").asInt();
        ObjectNode d = M.createObjectNode();
        d.put("_id", "del-" + tn);
        d.put("trackingNumber", tn);
        d.putObject("type").put("code", type).put("value", type == 30 ? "Exchange" : "Customer Return Pickup");
        d.putObject("state").put("code", 10).put("value", "Pickup requested");
        d.put("businessReference", body.path("businessReference").asText());
        d.put("cod", body.path("cod").asInt());
        if (body.has("specs")) d.set("specs", body.path("specs").deepCopy());
        if (body.has("returnSpecs")) d.set("returnSpecs", body.path("returnSpecs").deepCopy());
        JsonNode customer = type == 30 ? body.path("dropOffAddress") : body.path("pickupAddress");
        ObjectNode c = d.putObject(type == 30 ? "dropOffAddress" : "pickupAddress");
        c.put("firstLine", customer.path("firstLine").asText());
        c.putObject("city").put("_id", CAIRO).put("name", "Cairo");
        c.putObject("district").put("_id", customer.path("districtId").asText()).put("name", "Nasr City");
        ObjectNode merchant = d.putObject(type == 30 ? "pickupAddress" : "dropOffAddress");
        merchant.put("firstLine", "Merchant warehouse street");
        merchant.putObject("district").put("_id", "MERCHANT-DISTRICT").put("name", "Maadi");
        if (body.has("receiver")) d.set("receiver", body.path("receiver").deepCopy());
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
        r.add("analytics.settlement.refresh-enabled", () -> "false");
        r.add("bosta.base-url", () -> "http://127.0.0.1:" + BOSTA.getAddress().getPort());
    }

    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0";
    static final Path GOLDEN_OFF = Path.of("src/test/resources/golden/g4a-bosta-isolation-off.json");
    static final Path GOLDEN_ON  = Path.of("src/test/resources/golden/g4a-bosta-isolation-on.json");

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired EncryptionService encryption;
    @Autowired PortalService portal;
    @Autowired ReturnPickupBookingService booking;
    @Autowired ReturnSessionService returns;
    @MockBean  ShopifyGateway shopifyGateway;
    @MockBean  JobScheduler jobScheduler;

    @BeforeAll
    void districts() {
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true)",
                    NASR, CAIRO);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void g1_genericOff_bostaTenant_identicalToPreG4aGolden() throws Exception {
        String out = scenario("G4agold-off", 0L, false, false);
        check(out, GOLDEN_OFF, "off");
        assertThat(out).isEqualTo(Files.readString(GOLDEN_OFF));
    }

    @Test
    void g2_genericOn_portalSwitchOff_identicalToPreG4aGolden() throws Exception {
        String out = scenario("G4agold-on", 1_000_000L, true, false);
        check(out, GOLDEN_ON, "on");
        assertThat(out).isEqualTo(Files.readString(GOLDEN_ON));
    }

    @Test
    void g3_genericOn_portalSwitchOn_identicalToPreG4aGolden() throws Exception {
        String out = scenario("G4agold-on", 2_000_000L, true, true);
        check(out, null, "on-portal");
        assertThat(out).isEqualTo(Files.readString(GOLDEN_ON));
    }

    private void check(String out, Path golden, String tag) throws Exception {
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/g4a-bosta-isolation-" + tag + ".actual.json"), out);
        if (golden != null && Boolean.getBoolean("golden.write")) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, out);
        }
    }

    // ── The scenario ─────────────────────────────────────────────────────────────────────────────

    record Ctx(UUID tenant, UUID store, UUID location, UUID variant, UUID variant2, UUID worker, String slug) {}
    record Ord(UUID id, String number, List<String> pieces) {}

    private String scenario(String name, long offset, boolean genericOn, boolean portalGenericOn) throws Exception {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        String slug = name.toLowerCase() + (offset == 0 ? "" : "-" + offset / 1_000_000L);
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since, " +
            "    portal_exchanges_enabled, portal_exchanges_since) " +
            "VALUES (?, ?, ?, true, true, now() - interval '1 day', true, now() - interval '1 day')", tenant, name, slug);
        if (genericOn) jdbc.update("UPDATE tenants SET generic_courier_enabled = true WHERE id = ?", tenant);
        if (portalGenericOn && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'tenants' " +
                "  AND column_name = 'portal_generic_returns_enabled')", Boolean.class))) {
            jdbc.update("UPDATE tenants SET portal_generic_returns_enabled = true, portal_generic_returns_since = now() " +
                "WHERE id = ?", tenant);
        }
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, tenant, slug + ".myshopify.com");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main', true)", location, tenant);
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
        Ctx c = new Ctx(tenant, store, location, variant, variant2, worker, slug);
        long b = 8_484_805_000L + offset;
        NEXT_TRACKING.set(5_200_000_001L + offset);

        Ord refund   = order(c, "#4001", 2, b + 1, "now() - interval '2 days'");
        Ord auto     = order(c, "#4002", 1, b + 2, "now() - interval '3 days'");
        Ord exchange = order(c, "#4003", 1, b + 3, "now() - interval '4 days'");
        Ord old      = order(c, "#4004", 1, b + 4, "now() - interval '50 days'");
        Ord orphan   = order(c, "#4005", 1, b + 5, "now() - interval '5 days'");
        stockPiece(c, variant2);    // the exchange's replacement is in stock

        Map<String, Object> out = new LinkedHashMap<>();
        String token = jwt.issueAccessToken(owner, tenant, "owner");

        // 1. Portal: config, lookups, submit (manual approval)
        out.put("01_config", portal.config(slug).map(m -> (Object) new TreeMap<>(m)).orElse("empty"));
        Map<String, Object> lookup = portalLookup(slug, "4001", "01011114001");
        out.put("02_lookupInWindow", lookup);
        out.put("03_lookupOld", portalLookup(slug, "4004", "01011114004"));
        out.put("04_lookupWrongPhone", portalLookup(slug, "4001", "01099999999"));
        String portalToken = portalToken(slug, "4001", "01011114001");
        out.put("05_submit", attempt(() -> portal.submit(slug, portalToken, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(variant, 2, "wrong_size")), null, null, NASR))
            .map(r -> (Object) Map.of("outcome", String.valueOf(r.outcome()), "body", new TreeMap<>(r.body()))).orElse("empty")));
        out.put("06_enqueuedOnSubmit", enqueued());
        UUID refundReq = requestFor(refund);
        // The portal writes both items in one transaction (same created_at) — tie-break them by their piece's
        // creation order, so readers ordered by (created_at, id) list them the same way every run.
        jdbc.update("UPDATE return_request_items i SET created_at = i.created_at + (x.n * interval '1 millisecond') " +
            "FROM (SELECT i2.id, row_number() OVER (ORDER BY p.created_at, p.id) AS n FROM return_request_items i2 " +
            "      JOIN pieces p ON p.id = i2.piece_id WHERE i2.request_id = ?) x WHERE i.id = x.id", refundReq);

        // 2. Approve (HTTP) → enqueue → booking job → type-25 payload
        out.put("07_approve", post(token, "/api/v1/return-requests/" + refundReq + "/approve", null));
        out.put("08_enqueuedOnApprove", enqueued());
        booking.book(refundReq, tenant);
        out.put("09_bookingPosts", new ArrayList<>(POSTS));
        POSTS.clear();
        out.put("10_requestAfterBooking", requestRow(refundReq));

        // 3. Auto-approve
        jdbc.update("UPDATE tenants SET portal_auto_approve = true WHERE id = ?", tenant);
        String autoToken = portalToken(slug, "4002", "01011114002");
        out.put("11_submitAuto", attempt(() -> portal.submit(slug, autoToken, new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(variant, 1, "changed_mind")), null, null, NASR))
            .map(r -> (Object) Map.of("outcome", String.valueOf(r.outcome()), "body", new TreeMap<>(r.body()))).orElse("empty")));
        out.put("12_enqueuedOnAutoApprove", enqueued());
        jdbc.update("UPDATE tenants SET portal_auto_approve = false WHERE id = ?", tenant);
        UUID autoReq = requestFor(auto);
        booking.book(autoReq, tenant);
        out.put("13_autoBookingPosts", new ArrayList<>(POSTS));
        POSTS.clear();

        // 4. Exchange: approve (HTTP) → enqueue → type-30 payload → attached exchange
        UUID exReq = exchangeRequest(c, exchange);
        out.put("14_approveExchange", post(token, "/api/v1/return-requests/" + exReq + "/approve", null));
        out.put("15_enqueuedOnExchangeApprove", enqueued());
        booking.book(exReq, tenant);
        out.put("16_exchangePosts", new ArrayList<>(POSTS));
        POSTS.clear();
        out.put("17_exchangeRow", jdbc.queryForList(
            "SELECT e.status, e.tracking_number, o.external_id, o.number, o.status::text AS order_status, " +
            "       (SELECT string_agg(s.provider::text || ':' || s.shipment_leg || ':' || s.internal_state::text, ',') " +
            "          FROM shipments s WHERE s.order_id = o.id) AS legs " +
            "FROM exchanges e LEFT JOIN orders o ON o.id = e.outbound_order_id WHERE e.return_request_id = ?", exReq));

        // 5. Sweeper: an approved refund nobody enqueued
        UUID orphanReq = directRequest(c, orphan, "approved", "now() - interval '1 hour'");
        out.put("18_sweep", TenantContext.runAs(tenant, () -> String.valueOf(booking.sweepTenant(tenant))));
        out.put("19_enqueuedBySweep", enqueued());
        out.put("20_bookNowRefund", post(token, "/api/v1/return-requests/" + orphanReq + "/booking/book-now", null));
        out.put("21_retryNotFailed", post(token, "/api/v1/return-requests/" + orphanReq + "/booking/retry", null));

        // 6. The CRP leg Bosta created arrives; the sweeper links it; Scan returns takes the parcel back.
        String crpTn = (String) requestRow(refundReq).get("bosta_tracking_number");
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, raw, created_at) " +
            "VALUES (?, ?, 'bosta', ?, 'returned', 'return', '{\"type\":{\"code\":25,\"value\":\"Customer Return Pickup\"}}'::jsonb, now())",
            tenant, refund.id(), crpTn);
        out.put("22_sweepLinks", TenantContext.runAs(tenant, () -> String.valueOf(booking.sweepTenant(tenant))));
        out.put("23_enqueuedBySweep2", enqueued());
        TenantContext.set(tenant);
        UUID rs = returns.createSession(null, worker);
        Map<String, Object> rt = new LinkedHashMap<>();
        rt.put("01_crpAwb",   attempt(() -> returns.scan(rs, crpTn, location, worker)));
        rt.put("02_piece1",   attempt(() -> returns.scan(rs, refund.pieces().get(0), location, worker)));
        rt.put("03_piece2",   attempt(() -> returns.scan(rs, refund.pieces().get(1), location, worker)));
        rt.put("04_autoPiece", attempt(() -> returns.scan(rs, auto.pieces().get(0), location, worker)));
        rt.put("05_detail",   returns.getSession(rs));
        rt.put("06_grade1",   attempt(() -> returns.disposition(rs, id(refund.pieces().get(0)), "restock", null, location, worker, "worker")));
        rt.put("07_grade2",   attempt(() -> returns.disposition(rs, id(refund.pieces().get(1)), "damaged", "torn", location, worker, "worker")));
        rt.put("08_close",    attempt(() -> returns.close(rs, worker)));
        rt.put("09_stamps",   jdbc.queryForList(
            "SELECT o.number, s.shipment_leg, s.provider::text AS provider, s.internal_state::text AS state, " +
            "       s.return_intake_completed_at IS NOT NULL AS intake_done, s.return_intake_outcome, " +
            "       (SELECT string_agg(h.internal_state || ':' || coalesce(h.source, '-'), ',' ORDER BY h.occurred_at, h.id) " +
            "          FROM shipment_status_history h WHERE h.shipment_id = s.id) AS history " +
            "FROM shipments s JOIN orders o ON o.id = s.order_id WHERE s.tenant_id = ? " +
            "ORDER BY o.number, s.shipment_leg, s.tracking_number", tenant));
        out.put("24_returns", rt);
        TenantContext.clear();

        // 7. Merchant screens (HTTP, owner)
        Map<String, UUID> drawers = new LinkedHashMap<>();
        drawers.put("a_refund", refundReq); drawers.put("b_auto", autoReq); drawers.put("c_exchange", exReq); drawers.put("d_orphan", orphanReq);
        drawers.forEach((k, r) -> out.put("25_drawer_" + k, get(token, "/api/v1/return-requests/" + r)));
        out.put("26_requestsList", get(token, "/api/v1/return-requests?size=50"));
        out.put("27_cases", get(token, "/api/v1/returns-exchanges"));
        out.put("28_caseCounts", get(token, "/api/v1/returns-exchanges/counts"));
        out.put("29_portalSettings", get(token, "/api/v1/tenant/portal-settings"));
        out.put("30_requestRows", jdbc.queryForList(
            "SELECT o.number, rr.type, rr.status::text AS status, rr.booking_status, rr.link_source, " +
            "       rr.return_shipment_id IS NOT NULL AS linked, rr.bosta_tracking_number IS NOT NULL AS has_bosta_tn " +
            "FROM return_requests rr JOIN orders o ON o.id = rr.order_id WHERE rr.tenant_id = ? ORDER BY o.number", tenant));
        out.put("31_events", jdbc.queryForList(
            "SELECT o.number, e.event_type FROM return_request_events e JOIN return_requests rr ON rr.id = e.request_id " +
            "JOIN orders o ON o.id = rr.order_id WHERE e.tenant_id = ? ORDER BY o.number, e.occurred_at, e.event_type", tenant));

        // 8. Returns analytics (every analytics endpoint that reads return_requests)
        out.put("32_analyticsOrders",        get(token, "/api/v1/analytics/orders?size=200&period=30d"));
        out.put("33_analyticsProductExtras", get(token, "/api/v1/analytics/products/extras?period=30d"));
        out.put("34_analyticsSalesProducts", get(token, "/api/v1/analytics/sales/products?period=30d"));
        out.put("35_analyticsSalesVariants", get(token, "/api/v1/analytics/sales/variants?period=30d"));
        out.put("36_analyticsStockVariants", get(token, "/api/v1/analytics/stock/variants"));

        String json = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(out);
        return stabilise(json, name, slug, offset);
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

    /** A delivered order: Bosta forward leg with a full address raw, {@code pieces} delivered pieces of the variant. */
    private Ord order(Ctx c, String number, int pieces, long tn, String deliveredAtSql) {
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
            deliveredAtSql + ", now() - interval '9 days')", c.tenant(), order, String.valueOf(tn), raw);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < pieces; i++) {
            UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, raw) " +
                "VALUES (?, ?, ?, 1, '{\"price\": \"625.00\"}'::jsonb) RETURNING id", UUID.class, c.tenant(), order, c.variant());
            codes.add(piece(c, c.variant(), "delivered", order, item));
        }
        return new Ord(order, number, codes);
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

    private void stockPiece(Ctx c, UUID variant) {
        piece(c, variant, "available", null, null);
    }

    private UUID requestFor(Ord o) {
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ? ORDER BY created_at DESC LIMIT 1", UUID.class, o.id());
    }

    private UUID directRequest(Ctx c, Ord o, String status, String decidedAtSql) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, status, reference, decided_at, created_at, pickup_city_id, " +
            "    pickup_city_name, pickup_district_id, pickup_district_name) " +
            "VALUES (?, ?, ?::return_request_status, ?, " + decidedAtSql + ", now() - interval '2 hours', ?, 'Cairo', ?, " +
            "    'Nasr City - 7th District') RETURNING id",
            UUID.class, c.tenant(), o.id(), status, "RR-SWEEP", CAIRO, NASR);
        for (String code : o.pieces()) {
            jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code) " +
                "VALUES (?, ?, ?, ?, 'wrong_size')", c.tenant(), id, id(code), c.variant());
        }
        return id;
    }

    private UUID exchangeRequest(Ctx c, Ord o) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, created_at, pickup_city_id, " +
            "    pickup_city_name, pickup_district_id, pickup_district_name) " +
            "VALUES (?, ?, 'exchange', 'requested', ?, now() - interval '3 hours', ?, 'Cairo', ?, 'Nasr City - 7th District') RETURNING id",
            UUID.class, c.tenant(), o.id(), "RR-XCHNG", CAIRO, NASR);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, replacement_variant_id) " +
            "VALUES (?, ?, ?, ?, 'wrong_size', ?)", c.tenant(), id, id(o.pieces().get(0)), c.variant(), c.variant2());
        return id;
    }

    private String id(String shortCode) {
        return jdbc.queryForObject("SELECT id FROM pieces WHERE short_code = ?", String.class, shortCode);
    }

    private Map<String, Object> requestRow(UUID id) {
        return jdbc.queryForMap("SELECT status::text AS status, booking_status, booking_error, bosta_delivery_id, " +
            "bosta_tracking_number, booking_verified_at IS NOT NULL AS verified, return_shipment_id IS NOT NULL AS linked, " +
            "link_source FROM return_requests WHERE id = ?", id);
    }

    /** Every booking job enqueued since the last call: the request each would book. Clears the mock. */
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
            verify(job).book(req.capture(), any());
            ids.add(req.getValue().toString());
        }
        clearInvocations(jobScheduler);
        return ids;
    }

    private Map<String, Object> portalLookup(String slug, String number, String phone) {
        return portal.lookup(slug, number, phone).map(r -> {
            Map<String, Object> body = r.body() == null ? null : new TreeMap<>(r.body());
            if (body != null) body.remove("token");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", String.valueOf(r.outcome()));
            m.put("body", body);
            return m;
        }).orElse(Map.of("outcome", "empty"));
    }

    private String portalToken(String slug, String number, String phone) {
        return portal.lookup(slug, number, phone).map(r -> (String) r.body().get("token")).orElseThrow();
    }

    private Object get(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<Object> r = rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(h), Object.class);
        return Map.of("status", r.getStatusCode().value(), "body", String.valueOf(r.getBody() == null ? "" : json(r.getBody())));
    }

    private Object post(String token, String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Object> r = rest.exchange("http://localhost:" + port + path, HttpMethod.POST, new HttpEntity<>(body, h), Object.class);
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
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",      // uuid
            "PC-[0-9A-HJKMNP-TV-Z]{26}", "\\b[0-9A-HJKMNP-TV-Z]{26}\\b",          // piece barcode / id
            "\\bP[0-9]{6}\\b",                                                     // piece short code
            "\\bRR-[0-9A-Z]{6}\\b",                                                // request reference
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}(:\\d{2})?)?", // iso / pg time
            "\\b1[6-9]\\d{8}\\.\\d+\\b", "\\b1[6-9]\\d{11}\\b",                   // epoch s.frac / millis
            "\\b\\d{4}-\\d{2}-\\d{2}\\b"                                           // dates
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
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(84|52)[0-9]{8}\\b").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            long v = Long.parseLong(m.group());
            long base = m.group().startsWith("84") ? 8_484_805_000L : 5_200_000_001L;
            m.appendReplacement(b, (m.group().startsWith("84") ? "TN+" : "BTN+") + (v - base - offset));
        }
        m.appendTail(b);
        return b.toString();
    }
}
