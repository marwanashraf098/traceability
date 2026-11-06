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
 * Returns portal fees — per-store rollout, the F4 COD. {@code traced.portal-fees.settings-enabled} unset (false, as
 * in prod) and {@code traced.portal-fees.tenants} = tenant A only (plus spaces and a tenant id that doesn't exist).
 * A's exchange carries its frozen collect as COD; B, unlisted, books exactly as before (cod 0, nothing stored).
 * Harness copied from PortalExchangeCodTest (same real local Bosta stub).
 *
 * c listed tenant · u unlisted tenant · x cross-tenant on app_user with same-tenant controls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalFeesRolloutCodTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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
        d.set("cod", body.path("cod").deepCopy());
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
        r.add("traced.portal-fees.tenants", () -> " " + A_ID + " , " + UUID.randomUUID() + " ,");
    }

    static final UUID A_ID = UUID.randomUUID();

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
    @Autowired com.traceability.analytics.OrderFinanceService finances;
    @Autowired com.traceability.analytics.AnalyticsFloorOverrides floorOverrides;
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
        a = tenant(A_ID, "Nour Studio 5c", "nour-5c", KEY_A);
        b = tenant(UUID.randomUUID(), "Other F4", "other-f4", KEY_B);
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
            jdbc.update("DELETE FROM return_request_amounts WHERE tenant_id = ?", t.id());
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

    @Autowired PortalFeesRollout rollout;

    // ── c: listed tenant — COD sent and verified ──────────────────────────────

    @Test
    void c1_listedTenant_flagOff_codSent_bookingCodStored_verified_drawerSaysBostaCollects() {
        assertThat(rollout.on(a.id())).isTrue();
        Req r = approved(a, "#6101", 1);
        freeze(a, r, "600.00");

        booking.book(r.id(), a.id());

        assertThat(POSTS).as("exactly one POST").hasSize(1);
        assertThat(POSTS.get(0).path("type").asInt()).isEqualTo(30);
        assertThat(POSTS.get(0).path("cod").asLong()).as("cod = the frozen collect").isEqualTo(600);
        assertThat(bookingCod(r.id())).isEqualByComparingTo("600");
        assertThat(row(r.id()).get("booking_verified_at")).isNotNull();
        assertThat(drawer(a, r)).containsEntry("collector", "bosta").containsEntry("bookedCod", "600.00");
    }

    // ── u: unlisted tenant — exactly as before ────────────────────────────────

    @Test
    void u1_unlistedTenant_sameFrozenCollect_codZero_nothingStored_verifiedAsBefore() {
        assertThat(rollout.on(b.id())).isFalse();
        Req r = approved(b, "#6101", 1);
        freeze(b, r, "600.00");

        booking.book(r.id(), b.id());

        assertThat(POSTS).hasSize(1);
        assertThat(POSTS.get(0).path("cod").isIntegralNumber() && POSTS.get(0).path("cod").asInt() == 0)
            .as("cod 0, as before F4").isTrue();
        assertThat(bookingCod(r.id())).isNull();
        assertThat(row(r.id())).containsEntry("booking_status", "booked");
        assertThat(row(r.id()).get("booking_verified_at")).as("read-back expects 0, as before").isNotNull();
    }

    // ── x: cross-tenant ───────────────────────────────────────────────────────

    @Test
    void x1_crossTenant_onAppUser_listedJobNeverBooksTheUnlistedTenant_eachTenantByItsOwnRollout() {
        DataSource ds = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(ds);
        ReturnPickupBookingService appUser = new ReturnPickupBookingService(appJdbc,
            new DataSourceTransactionManager(ds), bostaV2, gateway, encryption, scheduler, M, exchangeService);
        appUser.setPortalFees(rollout);

        Req mine = approved(a, "#6201", 1);
        Req theirs = approved(b, "#6201", 1);
        freeze(a, mine, "700.00");
        freeze(b, theirs, "900.00");

        appUser.book(theirs.id(), a.id());
        assertThat(POSTS).as("B's request is invisible to a tenant-A job").isEmpty();
        assertThat(bookingCod(theirs.id())).isNull();

        appUser.book(mine.id(), a.id());
        assertThat(POSTS).as("same-tenant control").hasSize(1);
        assertThat(POSTS.get(0).path("cod").asLong()).as("A's own frozen amount").isEqualTo(700);
        assertThat(bookingCod(mine.id())).isEqualByComparingTo("700");

        appUser.book(theirs.id(), b.id());
        assertThat(POSTS).hasSize(2);
        assertThat(POSTS.get(1).path("cod").asInt()).as("B unlisted: cod 0 although A is on").isZero();
        assertThat(bookingCod(theirs.id())).isNull();
    }

    // ── F4 helpers ────────────────────────────────────────────────────────────

    /** Freezes an exchange-kind amounts row with {@code collect} (what F2 / F3 write at submit). */
    private void freeze(Tenant t, Req r, String collect) {
        jdbc.update("INSERT INTO return_request_amounts (request_id, tenant_id, original_unit_price, original_price_source, " +
                    "    replacement_unit_price, price_difference, item_value, fee_kind, fee_amount, collect_cod) " +
                    "VALUES (?, ?, 900, 'stored', 900, 0, 900, 'exchange', 0, ?::numeric)", r.id(), t.id(), collect);
    }

    private java.math.BigDecimal bookingCod(UUID id) {
        return jdbc.queryForObject("SELECT booking_cod FROM return_requests WHERE id = ?", java.math.BigDecimal.class, id);
    }

    private Map<String, Object> drawer(Tenant t, Req r) {
        String status = jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, r.id());
        return TenantContext.runAs(t.id(), () -> new TransactionTemplate(txm).execute(s ->
            new RequestAmounts(jdbc).forDetail(t.id(), r.id(), status).orElseThrow()));
    }

    private static BostaV2Client.Exchange exchangeWithCod(java.math.BigDecimal cod) {
        return new BostaV2Client.Exchange(UUID.randomUUID().toString(), "#L1", "loc-nour-5c", "12 Placeholder Street, Block 4",
            null, null, null, null, "Cairo", NASR, "Mona", "Placeholder", "01000000001", "RR-X: Linen Shirt / White / L",
            "Linen Shirt / White / M", "Traced exchange request RR-X", cod);
    }

    private List<com.traceability.analytics.OrderFinanceService.OrderRow> financeRows(Tenant t) {
        com.traceability.analytics.AnalyticsPeriod p = new com.traceability.analytics.AnalyticsPeriod(
            LocalDate.now(java.time.ZoneId.of("Africa/Cairo")).minusDays(10), LocalDate.now(java.time.ZoneId.of("Africa/Cairo")));
        com.traceability.analytics.OrderFinanceService.Filters none = new com.traceability.analytics.OrderFinanceService.Filters(null, null, null, null);
        return TenantContext.runAs(t.id(), () -> new TransactionTemplate(txm).execute(s -> finances.orders(p, none, 0, 200).orders()));
    }

    private List<String> financeNames(Tenant t) {
        return financeRows(t).stream().map(com.traceability.analytics.OrderFinanceService.OrderRow::name).toList();
    }

    private Map<String, Object> financeRow(Tenant t, String number) {
        com.traceability.analytics.OrderFinanceService.OrderRow row = financeRows(t).stream()
            .filter(o -> number.equals(o.name())).findFirst().orElseThrow(() -> new AssertionError(number + " not in finances"));
        Map<String, Object> m = new HashMap<>();
        m.put("total", row.total());
        m.put("netToYou", row.netToYou());
        m.put("exchangeCod", row.exchangeCod());
        return m;
    }


    // ── Helpers ───────────────────────────────────────────────────────────────

    record Order(UUID id, String number, String piece) {}
    record Req(UUID id, String reference, UUID orderId, String originalPiece) {}

    private Tenant tenant(UUID id, String name, String slug, String key) {
        UUID store = UUID.randomUUID(), owner = UUID.randomUUID(), location = UUID.randomUUID();
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
            returnSessions.disposition(s, piece, disposition, "damaged".equals(disposition) ? "Torn seam" : null, t.location(), t.owner(), "owner");
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
