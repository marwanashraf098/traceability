package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyOrderPriceGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.FulfillService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.PickupAreaService;
import com.traceability.portal.PortalService;
import com.traceability.portal.RefundSuggestionService;
import com.traceability.portal.ReturnPickupBookingService;
import com.traceability.portal.ReturnRequestService;
import com.traceability.privacy.CustomerDataRequestService;
import com.traceability.privacy.CustomerRedaction;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P4b — a stored portal pre-connect order (orders.origin = 'portal_pre_connect', V163) works through
 * every returns path, with no forward shipment in Traced. Portal rows are seeded directly (no code
 * path creates them yet). Bosta is a real local HTTP stub, as in PortalExchangeBookingTest.
 *
 * E eligibility (portal_delivered_at) · A pickup area + booking from portal_delivery · L the
 * Traced-booked return leg links by tracking number, never by reference · X full exchange round
 * trip · R refund suggestion by shopify_order_gid · G GDPR by gid (redact, sticky, stored webhook,
 * export).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalPreConnectWorkflowTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    // ── Bosta stub: v2 create (kept + counted), v0 read ────────────────────────

    static final List<JsonNode> POSTS = new CopyOnWriteArrayList<>();
    static final Map<String, ObjectNode> DELIVERIES = new ConcurrentHashMap<>();
    static volatile String nextTracking = "7300000001";
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
                        DELIVERIES.put(tn, stored(body, tn));
                        send(ex, 200, "{\"success\":true,\"data\":{\"_id\":\"del-" + tn + "\",\"trackingNumber\":\"" + tn +
                            "\",\"state\":{\"code\":10,\"value\":\"Pickup requested\"},\"creationSrc\":\"API\"}}");
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

    /** What Bosta stores: type 25 → customer on pickupAddress; type 30 → customer on dropOffAddress. */
    static ObjectNode stored(JsonNode body, String tn) {
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
        String merchantSide = type == 30 ? "pickupAddress" : "dropOffAddress";
        ObjectNode customer = d.putObject(customerSide);
        customer.put("firstLine", body.path(customerSide).path("firstLine").asText());
        customer.putObject("district").put("_id", body.path(customerSide).path("districtId").asText()).put("name", "Nasr City");
        ObjectNode merchant = d.putObject(merchantSide);
        merchant.put("firstLine", "Merchant warehouse street");
        merchant.putObject("district").put("_id", "MERCHANT-DISTRICT");
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

    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0";
    static final String KEY = "bosta-raw-key-p4b";
    static final String SLUG = "nour-p4b";
    static final String ORDER_PHONE = "01000000001";
    static final String BOSTA_RECEIVER_PHONE = "+201099999999";

    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionService encryption;
    @Autowired PortalService portal;
    @Autowired PickupAreaService pickupAreas;
    @Autowired ReturnPickupBookingService booking;
    @Autowired ReturnRequestService requests;
    @Autowired RefundSuggestionService refundSuggestions;
    @Autowired CustomerDataRequestService dataRequests;
    @Autowired BostaIngestionHelper ingestionHelper;
    @Autowired BostaWebhookJob webhookJob;
    @Autowired FulfillService fulfill;
    @Autowired PlatformTransactionManager txm;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyOrderPriceGateway shopifyPrices;

    UUID tenant, store, owner, location, product, original, replacement;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); store = UUID.randomUUID(); owner = UUID.randomUUID(); location = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since, " +
                    "    portal_exchanges_enabled, portal_exchanges_since, customer_return_window_days) " +
                    "VALUES (?, 'Nour P4b', ?, true, true, now() - interval '2 days', true, now() - interval '1 day', 14)", tenant, SLUG);
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status, import_status, orders_ingest_from) " +
                    "VALUES (?, ?, ?, 'connected', 'idle', now() - interval '1 day')", store, tenant, SLUG + ".myshopify.com");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            owner, tenant, "owner-" + owner + "@test.local");
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
                    "    return_business_location_id, return_business_location_name) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'hash-p4b', 'active', 'loc-p4b', 'Maadi Warehouse')",
            tenant, encryption.encrypt(KEY));
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main Warehouse', true)", location, tenant);
        product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/p4b', 'Linen Shirt', 'active')",
            product, tenant, store);
        original = variant("White / M");
        replacement = variant("White / L");
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true) " +
                    "ON CONFLICT (district_id) DO NOTHING", NASR, CAIRO);
    }

    @BeforeEach
    void reset() {
        POSTS.clear();
        DELIVERIES.clear();
        nextTracking = String.valueOf(ThreadLocalRandom.current().nextLong(7_300_000_000L, 7_399_999_999L));
        Mockito.reset(shopifyPrices, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-test");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    // ── E: eligibility ────────────────────────────────────────────────────────

    @Test
    void e1_lookup_findsAPortalOrder_byNumberAndPhone_windowFromPortalDeliveredAt_linesUntracked() {
        Portal p = portalOrder("#P1001", "now() - interval '3 days'");

        PortalService.LookupResult r = portal.lookup(SLUG, "P1001", ORDER_PHONE).orElseThrow();

        assertThat(r.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        assertThat(r.body().get("deliveredAt")).isEqualTo(
            jdbc.queryForObject("SELECT portal_delivered_at FROM orders WHERE id = ?", java.sql.Timestamp.class, p.id())
                .toInstant().toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.body().get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).containsEntry("orderItemId", p.item().toString()).containsEntry("tracked", false);
    }

    @Test
    void e2_lookup_outsideTheWindow_notFound_andAForwardLegIsNeverUsedForAPortalOrder() {
        Portal old = portalOrder("#P1002", "now() - interval '20 days'");
        assertThat(portal.lookup(SLUG, "P1002", ORDER_PHONE).orElseThrow().outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);

        // Even a (never-expected) delivered forward leg inside the window doesn't make it eligible.
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered', 'forward', now() - interval '1 day')", tenant, old.id(), "5401" + ref6());
        assertThat(portal.lookup(SLUG, "P1002", ORDER_PHONE).orElseThrow().outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);

        Portal noTime = portalOrder("#P1003", "NULL");
        assertThat(portal.lookup(SLUG, "P1003", ORDER_PHONE).orElseThrow().outcome()).isEqualTo(PortalService.Outcome.NOT_FOUND);
        assertThat(noTime.id()).isNotNull();
    }

    // ── A: pickup area + booking from portal_delivery ─────────────────────────

    @Test
    void a1_pickupArea_cityAndDistrictFromPortalDelivery() {
        Portal p = portalOrder("#P2001", "now() - interval '3 days'");
        Optional<PickupAreaService.CityAreas> areas = TenantContext.runAs(tenant, () ->
            new TransactionTemplate(txm).execute(s -> pickupAreas.forOrder(tenant, p.id())));
        assertThat(areas).isPresent();
        assertThat(areas.get().cityId()).isEqualTo(CAIRO);
        assertThat(areas.get().preselectedDistrictId()).isEqualTo(NASR);
    }

    @Test
    void a2_refundBooking_type25_customerAddressFromPortalDelivery_receiverFromTheOrder() {
        Portal p = portalOrder("#P2002", "now() - interval '3 days'");
        UUID rr = request(p, "refund", "approved");

        booking.book(rr, tenant);

        assertThat(POSTS).hasSize(1);
        JsonNode body = POSTS.get(0);
        assertThat(body.path("type").asInt()).isEqualTo(25);
        assertThat(body.path("pickupAddress").path("firstLine").asText()).isEqualTo("12 Portal Street, Block 4");
        assertThat(body.path("pickupAddress").path("districtId").asText()).isEqualTo(NASR);
        assertThat(body.path("receiver").path("phone").asText()).as("the order's phone, not the old Bosta receiver")
            .isEqualTo(ORDER_PHONE);
        assertThat(body.path("businessReference").asText()).isEqualTo("#P2002");
        assertThat(jdbc.queryForObject("SELECT booking_status FROM return_requests WHERE id = ?", String.class, rr)).isEqualTo("booked");
    }

    // ── L: the Traced-booked return leg links by tracking number ──────────────

    @Test
    void l1_returnLegOfAPortalOrder_linksByTrackingNumber_evenWhenTheReferenceMatchesAnotherOrder() {
        Portal p = portalOrder("#P3001", "now() - interval '3 days'");
        UUID rr = request(p, "refund", "approved");
        String tn = nextTracking;
        booking.book(rr, tenant);
        assertThat(jdbc.queryForObject("SELECT bosta_tracking_number FROM return_requests WHERE id = ?", String.class, rr)).isEqualTo(tn);

        // A normal order the reference matcher WOULD pick: Bosta's businessReference names it.
        UUID decoy = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_phone) " +
            "VALUES (?, ?, ?, '#D3001', 'delivered', 'cod', now(), '01011112222') RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + ref6() + "3001");
        DELIVERIES.get(tn).put("businessReference", "#D3001");

        ingest(tn);

        Map<String, Object> leg = jdbc.queryForMap(
            "SELECT order_id, shipment_leg FROM shipments WHERE tenant_id = ? AND tracking_number = ?", tenant, tn);
        assertThat(leg.get("order_id")).as("the portal order, by the request's tracking number").isEqualTo(p.id());
        assertThat(leg.get("shipment_leg")).isEqualTo("return");
        assertThat(jdbc.queryForObject("SELECT return_shipment_id FROM return_requests WHERE id = ?", UUID.class, rr))
            .isEqualTo(jdbc.queryForObject("SELECT id FROM shipments WHERE tracking_number = ?", UUID.class, tn));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id = ?", Integer.class, decoy)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tenant_id = ? AND tracking_number = ?",
            Integer.class, tenant, tn)).isZero();
    }

    // ── X: full exchange round trip on a portal order ─────────────────────────

    @Test
    void x1_exchange_bookedFromPortalDelivery_replacementCreatedAndPickable_oldItemArrives_exchanged() {
        Portal p = portalOrder("#P4001", "now() - interval '3 days'");
        String stock = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available', ?)",
            stock, tenant, replacement, "PC-" + stock, stock, location);
        UUID rr = request(p, "exchange", "approved");
        String tn = nextTracking;

        booking.book(rr, tenant);

        JsonNode body = POSTS.get(0);
        assertThat(body.path("type").asInt()).isEqualTo(30);
        assertThat(body.path("dropOffAddress").path("firstLine").asText()).isEqualTo("12 Portal Street, Block 4");
        assertThat(body.path("receiver").path("phone").asText()).isEqualTo(ORDER_PHONE);

        Map<String, Object> ex = jdbc.queryForMap("SELECT * FROM exchanges WHERE tenant_id = ? AND tracking_number = ?", tenant, tn);
        assertThat(ex.get("return_request_id")).isEqualTo(rr);
        assertThat(ex.get("matched_order_id")).isEqualTo(p.id());
        UUID replacementOrder = (UUID) ex.get("outbound_order_id");
        Map<String, Object> o = jdbc.queryForMap(
            "SELECT external_id, origin, customer_phone, address->>'city' AS city FROM orders WHERE id = ?", replacementOrder);
        assertThat(o.get("external_id")).isEqualTo("internal:exchange:" + tn);
        assertThat(o.get("origin")).as("the replacement is a normal merchant order").isEqualTo("shopify");
        assertThat(o.get("customer_phone")).as("customer details from portal_delivery").isNotNull();

        // The replacement is in the Pick queue; the portal original never is.
        List<Map<String, Object>> queue = TenantContext.runAs(tenant, () -> fulfill.getQueue());
        assertThat(queue).extracting(m -> m.get("id")).contains(replacementOrder).doesNotContain(p.id());

        // Picked and packed like any exchange.
        TenantContext.runAs(tenant, () -> {
            assertThat(fulfill.scan(replacementOrder, "PC-" + stock, owner).success()).isTrue();
            fulfill.complete(replacementOrder, owner);
            return null;
        });

        // The old item comes back (untracked unit — "Arrived") → the request finishes as exchanged.
        UUID item = jdbc.queryForObject("SELECT id FROM return_request_items WHERE request_id = ?", UUID.class, rr);
        TenantContext.runAs(tenant, () -> { requests.itemArrived(rr, item, "sellable", owner); return null; });
        assertThat(jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, rr)).isEqualTo("exchanged");
    }

    // ── R: refund suggestion by gid ───────────────────────────────────────────

    @Test
    void r1_refundSuggestion_readsShopifyByTheGid() {
        Portal p = portalOrder("#P5001", "now() - interval '3 days'");
        UUID rr = request(p, "refund", "approved");
        String variantGid = jdbc.queryForObject("SELECT external_id FROM variants WHERE id = ?", String.class, original);
        when(shopifyPrices.fetchOrderPrices(anyString(), anyString(), eq(p.gid()))).thenReturn(
            new ShopifyOrderPriceGateway.OrderPrices("EGP", List.of(
                new ShopifyOrderPriceGateway.LinePrice(variantGid, 1, new BigDecimal("640.00")))));

        Map<String, Object> s = TenantContext.runAs(tenant, () ->
            new TransactionTemplate(txm).execute(t -> refundSuggestions.suggest(rr)));

        assertThat(s.get("source")).isEqualTo("shopify");
        assertThat(s.get("amount")).isEqualTo("640.00");
        verify(shopifyPrices).fetchOrderPrices(eq(SLUG + ".myshopify.com"), eq("shpat-test"), eq(p.gid()));
    }

    // ── G: GDPR by gid ────────────────────────────────────────────────────────

    @Test
    void g1_customersRedact_findsThePortalOrderByGid_clearsPortalDelivery_andItStaysCleared() {
        Portal p = portalOrder("#P6001", "now() - interval '3 days'");

        CustomerRedaction.Result r = TenantContext.runAs(tenant, () -> new TransactionTemplate(txm).execute(s ->
            new CustomerRedaction(jdbc).redactCustomer(tenant, List.of(p.gid()), null, null)));

        assertThat(r.orders()).isEqualTo(1);
        Map<String, Object> o = jdbc.queryForMap(
            "SELECT customer_name, customer_phone, portal_delivery, pii_redacted_at FROM orders WHERE id = ?", p.id());
        assertThat(o.get("customer_name")).isNull();
        assertThat(o.get("customer_phone")).isNull();
        assertThat(o.get("portal_delivery")).isNull();
        assertThat(o.get("pii_redacted_at")).isNotNull();

        // Sticky (V164): a later write can't put it back.
        jdbc.update("UPDATE orders SET portal_delivery = '{\"receiver\":{\"fullName\":\"Back again\"}}'::jsonb WHERE id = ?", p.id());
        assertThat(jdbc.queryForObject("SELECT portal_delivery FROM orders WHERE id = ?", Object.class, p.id())).isNull();

        // A stored orders/* webhook for the redacted portal order arrives stripped (V164 matches by gid too).
        UUID ev = jdbc.queryForObject(
            "INSERT INTO shopify_webhook_events (tenant_id, topic, shop_domain, webhook_id, payload_raw) " +
            "VALUES (?, 'orders/updated', ?, ?, ?::jsonb) RETURNING id", UUID.class, tenant, SLUG + ".myshopify.com",
            "wh-" + UUID.randomUUID(), "{\"admin_graphql_api_id\":\"" + p.gid() + "\",\"name\":\"#P6001\"," +
                "\"customer\":{\"first_name\":\"Mona\"},\"shipping_address\":{\"address1\":\"12 Portal Street\"}}");
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload_raw, 'customer') OR jsonb_exists(payload_raw, 'shipping_address') " +
            "FROM shopify_webhook_events WHERE id = ?", Boolean.class, ev)).isFalse();
    }

    @Test
    void g2_dataRequestExport_includesThePortalOrder_byGid_withItsPortalDelivery() throws Exception {
        Portal p = portalOrder("#P6002", "now() - interval '3 days'");
        UUID req = jdbc.queryForObject(
            "INSERT INTO customer_data_requests (tenant_id, webhook_event_id, shop_domain, orders_requested) " +
            "VALUES (?, gen_random_uuid(), ?, ARRAY[?]::text[]) RETURNING id", UUID.class, tenant, SLUG + ".myshopify.com", p.gid());

        CustomerDataRequestService.Export export = TenantContext.runAs(tenant, () -> dataRequests.export(tenant, req, owner));

        JsonNode out = M.readTree(export.json());
        JsonNode order = null;
        for (JsonNode o : out.path("orders")) if (p.id().toString().equals(o.path("id").asText())) order = o;
        assertThat(order).as("the portal order is in the export").isNotNull();
        assertThat(order.path("origin").asText()).isEqualTo("portal_pre_connect");
        assertThat(order.path("shopify_order_gid").asText()).isEqualTo(p.gid());
        assertThat(order.path("portal_delivery").path("dropOffAddress").path("firstLine").asText()).isEqualTo("12 Portal Street, Block 4");
        assertThat(order.path("portal_delivery").path("receiver").path("phone").asText()).isEqualTo(BOSTA_RECEIVER_PHONE);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    record Portal(UUID id, String gid, UUID item) {}

    /** A portal pre-connect order as P4c will store it: no forward shipment, the original Bosta delivery in portal_delivery. */
    private Portal portalOrder(String number, String deliveredAtSql) {
        String gid = "gid://shopify/Order/9" + ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
        String delivery = "{\"trackingNumber\":\"5555" + ref6() + "\"," +
            "\"dropOffAddress\":{\"firstLine\":\"12 Portal Street, Block 4\",\"secondLine\":\"Near the pharmacy\"," +
            "\"buildingNumber\":\"12\",\"floor\":\"3\",\"apartment\":\"7\",\"city\":{\"_id\":\"" + CAIRO + "\",\"name\":\"Cairo\"}," +
            "\"district\":{\"_id\":\"" + NASR + "\",\"name\":\"Nasr City\"}}," +
            "\"receiver\":{\"firstName\":\"Old\",\"lastName\":\"Receiver\",\"fullName\":\"Old Receiver\",\"phone\":\"" + BOSTA_RECEIVER_PHONE + "\"}}";
        UUID id = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, customer_name, " +
            "    customer_phone, origin, shopify_order_gid, portal_fetched_at, portal_delivered_at, portal_delivery) " +
            "VALUES (?, ?, ?, ?, 'delivered', 'cod', now() - interval '30 days', 'Mona Placeholder', ?, 'portal_pre_connect', ?, now(), " +
            deliveredAtSql + ", ?::jsonb) RETURNING id",
            UUID.class, tenant, store, "internal:portal:" + UUID.randomUUID(), number, ORDER_PHONE, gid, delivery);
        UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenant, id, original);
        return new Portal(id, gid, item);
    }

    /** A request with one UNTRACKED item (the order line's unit 1), pickup area chosen. */
    private UUID request(Portal p, String type, String status) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO return_requests (tenant_id, order_id, type, status, reference, decided_at, pickup_city_id, pickup_city_name, " +
            "    pickup_district_id, pickup_district_name, refund_fallback_ok) " +
            "VALUES (?, ?, ?, ?::return_request_status, ?, now(), ?, 'Cairo', ?, 'Nasr City - 7th District', true) RETURNING id",
            UUID.class, tenant, p.id(), type, status, "RR-" + ref6(), CAIRO, NASR);
        jdbc.update("INSERT INTO return_request_items (tenant_id, request_id, order_item_id, unit_no, variant_id, reason_code, replacement_variant_id) " +
                    "VALUES (?, ?, ?, 1, ?, 'wrong_size', ?)", tenant, id, p.item(), original, "exchange".equals(type) ? replacement : null);
        return id;
    }

    private UUID variant(String title) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, product, "gid://shopify/ProductVariant/" + v, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    private void ingest(String tn) {
        boolean enqueued = TenantContext.runAs(tenant, () -> ingestionHelper.ingestDelivery(tenant, KEY, tn, "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject("SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, tenant, tn);
        webhookJob.process(eventId, tenant);
        TenantContext.clear();
    }

    private static String ref6() {
        String alphabet = "23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        return sb.toString();
    }
}
