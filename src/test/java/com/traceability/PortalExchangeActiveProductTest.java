package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.UlidGenerator;
import com.traceability.portal.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.IocJobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Portal exchanges offer and accept only ACTIVE Shopify products. Draft / archived / unlisted
 * products are imported and still refundable, but never offered or sent as a replacement.
 *
 * One tenant, two products, both with a sibling in stock: A (archived) and B (active, the
 * same-tenant positive control throughout).
 *   L  lookup: no exchange options for A's line, options for B's.
 *   S  submit: an exchange for A → invalid (400); for B → created.
 *   A  approval race: submitted while active, archived, approved → 409 REPLACEMENT_NOT_ACTIVE,
 *      no job, no Bosta POST; still active → approved, and its booking does reach Bosta.
 *   B  booking job: approved, then archived → booking fails its precondition, no Bosta POST.
 *   R  a refund request for the archived product's line still submits.
 * L, S and A are revert-checked with the status predicate removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PortalExchangeActiveProductTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("traceability_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static { POSTGRES.start(); }

    // ── Bosta stub: counts create POSTs, serves the read-back ─────────────────

    static final ObjectMapper M = new ObjectMapper();
    static final List<JsonNode> POSTS = new CopyOnWriteArrayList<>();
    static final Map<String, ObjectNode> DELIVERIES = new ConcurrentHashMap<>();
    static volatile String nextTracking = "7300000001";
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

    static ObjectNode stored(JsonNode body, String tn) {
        ObjectNode d = M.createObjectNode();
        d.put("_id", "del-" + tn);
        d.put("trackingNumber", tn);
        d.putObject("type").put("code", body.path("type").asInt()).put("value", "Exchange");
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

    static final String CAIRO = "FceDyHXwpSYYF9zGW", NASR = "Iy7-lFD0BE0";
    static final String SLUG = "active-only-ex", KEY = "bosta-raw-key-active-only";
    static final String PHONE = "01000000001";

    @Autowired JdbcTemplate jdbc;
    @Autowired EncryptionService encryption;
    @Autowired PortalService portal;
    @Autowired PortalTokenService tokens;
    @Autowired ReturnRequestService requests;
    @Autowired ReturnPickupBookingService booking;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    UUID tenantId, storeId, ownerId, locationId, productA, productB, a1, a2, b1, b2;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); storeId = UUID.randomUUID(); ownerId = UUID.randomUUID(); locationId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, portal_slug, portal_enabled, portal_pickup_booking, portal_pickup_booking_since, " +
                    "    portal_exchanges_enabled, portal_exchanges_since) " +
                    "VALUES (?, 'Active Only Co', ?, true, true, now() - interval '2 days', true, now() - interval '1 day')", tenantId, SLUG);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'active-only.myshopify.com', 'connected')",
            storeId, tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) VALUES (?, ?, 'Owner', ?, 'h', 'owner', true)",
            ownerId, tenantId, "owner-" + ownerId + "@test.local");
        jdbc.update("INSERT INTO courier_accounts (id, tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
                    "    return_business_location_id, return_business_location_name) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'hash-active-only', 'active', 'loc-active-only', 'Maadi Warehouse')",
            tenantId, encryption.encrypt(KEY));
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
                    "VALUES (?, ?, 'Main Warehouse', 'gid://shopify/Location/7300', 'linked', true)", locationId, tenantId);
        jdbc.update("INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, zone_id, zone_name, zone_name_ar, " +
                    "district_name, district_name_ar, pickup_available, dropoff_available) VALUES " +
                    "(?, ?, 'Cairo', 'القاهرة', 'z1', 'Nasr City', 'مدينة نصر', 'Nasr City - 7th District', 'مدينة نصر - الحي السابع', true, true) " +
                    "ON CONFLICT (district_id) DO NOTHING", NASR, CAIRO);

        productA = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/A', 'Wool Coat', 'archived')",
            productA, tenantId, storeId);
        a1 = variant(productA, "Grey / M");
        a2 = variant(productA, "Grey / L");
        productB = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/B', 'Linen Shirt', 'active')",
            productB, tenantId, storeId);
        b1 = variant(productB, "White / M");
        b2 = variant(productB, "White / L");
        stock(a2, 2);
        stock(b2, 2);
    }

    @BeforeEach
    void reset() {
        POSTS.clear();
        DELIVERIES.clear();
        nextTracking = String.valueOf(ThreadLocalRandom.current().nextLong(7_300_000_000L, 7_399_999_999L));
        clearInvocations(jobScheduler);
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("shpat-test");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString())).thenReturn("gid://shopify/InventoryItem/7301");
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("UPDATE products SET status = 'archived' WHERE id = ?", productA);
        jdbc.update("UPDATE products SET status = 'active' WHERE id = ?", productB);
        jdbc.update("DELETE FROM portal_lookup_attempts WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE exchanges SET outbound_order_id = NULL, matched_order_id = NULL, return_request_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM exchanges WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_request_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM return_requests WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ? AND status <> 'available'", tenantId);
        jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
    }

    // ── L: lookup ───────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_lookup_archivedProductOffersNoExchange_activeProductDoes() {
        deliveredOrder("#81001", a1);
        deliveredOrder("#81002", b1);

        Map<String, Object> lineA = firstLine("81001");
        Map<String, Object> lineB = firstLine("81002");

        assertThat((List<Object>) lineA.get("exchangeOptions")).as("archived product: nothing to exchange for").isEmpty();
        assertThat(lineA).as("the line itself is still offered (refund)").containsEntry("variantId", a1.toString());
        assertThat((List<Map<String, Object>>) lineB.get("exchangeOptions")).as("active product: its sibling is offered")
            .extracting(m -> m.get("variantId")).containsExactly(b2.toString());
    }

    @Test
    void l2_draftAndUnlisted_alsoOfferNothing() {
        deliveredOrder("#81003", b1);
        for (String status : List.of("draft", "unlisted", "archived")) {
            jdbc.update("UPDATE products SET status = ? WHERE id = ?", status, productB);
            assertThat((List<?>) firstLine("81003").get("exchangeOptions")).as(status).isEmpty();
        }
    }

    // ── S: submit ───────────────────────────────────────────────────────────

    @Test
    void s1_submitExchange_archivedProduct_invalid_activeProduct_created() {
        Order oa = deliveredOrder("#82001", a1);
        Order ob = deliveredOrder("#82002", b1);

        assertThat(portal.submit(SLUG, tokens.issue(tenantId, oa.id()), exchange(a1, a2)).orElseThrow().outcome())
            .isEqualTo(PortalService.SubmitOutcome.INVALID);
        assertThat(portal.submit(SLUG, tokens.issue(tenantId, ob.id()), exchange(b1, b2)).orElseThrow().outcome())
            .isEqualTo(PortalService.SubmitOutcome.CREATED);
        assertThat(jdbc.queryForList("SELECT o.number FROM return_requests rr JOIN orders o ON o.id = rr.order_id WHERE rr.tenant_id = ?",
            String.class, tenantId)).containsExactly("#82002");
    }

    // ── R: refunds unaffected ───────────────────────────────────────────────

    @Test
    void r1_refundRequest_forTheArchivedProductsLine_created() {
        Order oa = deliveredOrder("#83001", a1);
        PortalService.SubmitResult res = portal.submit(SLUG, tokens.issue(tenantId, oa.id()), new PortalService.SubmitRequest(
            List.of(new PortalService.SubmitLine(a1, 1, "changed_mind")), null, null, NASR, "refund", null, null)).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.SubmitOutcome.CREATED);
        assertThat(jdbc.queryForObject("SELECT type FROM return_requests WHERE tenant_id = ?", String.class, tenantId)).isEqualTo("refund");
    }

    // ── A: approval race ────────────────────────────────────────────────────

    @Test
    void a1_submittedWhileActive_archived_approve409_noJob_noBostaPost() {
        UUID r = submitExchangeForB("#84001");
        jdbc.update("UPDATE products SET status = 'archived' WHERE id = ?", productB);

        TenantContext.set(tenantId);
        assertThatThrownBy(() -> requests.approve(r, ownerId))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode().value()).isEqualTo(409);
                assertThat(e.getReason()).startsWith(ReturnRequestService.REPLACEMENT_NOT_ACTIVE)
                    .contains("no longer active in Shopify");
            });
        TenantContext.clear();

        assertThat(status(r)).isEqualTo("requested");
        verify(jobScheduler, never()).enqueue(any(IocJobLambda.class));
        booking.book(r, tenantId);   // even a stray job run can't book it
        assertThat(POSTS).as("no Bosta call").isEmpty();
    }

    @Test
    void a2_stillActive_approves_andItsBookingReachesBosta() {
        UUID r = submitExchangeForB("#84002");

        TenantContext.set(tenantId);
        requests.approve(r, ownerId);
        TenantContext.clear();

        assertThat(status(r)).isEqualTo("approved");
        verify(jobScheduler, times(1)).enqueue(any(IocJobLambda.class));
        booking.book(r, tenantId);
        assertThat(POSTS).as("the positive control reaches Bosta — the fixture is bookable").hasSize(1);
        assertThat(POSTS.get(0).path("type").asInt()).isEqualTo(30);
    }

    // ── B: booking job precondition ─────────────────────────────────────────

    @Test
    void b1_approvedThenArchived_bookingFailsPrecondition_noBostaPost() {
        UUID r = submitExchangeForB("#85001");
        TenantContext.set(tenantId);
        requests.approve(r, ownerId);
        TenantContext.clear();
        jdbc.update("UPDATE products SET status = 'archived' WHERE id = ?", productB);

        booking.book(r, tenantId);

        Map<String, Object> row = jdbc.queryForMap("SELECT booking_status, booking_error FROM return_requests WHERE id = ?", r);
        assertThat(row.get("booking_status")).isEqualTo("failed");
        assertThat((String) row.get("booking_error")).contains("no longer active in Shopify");
        assertThat(POSTS).as("no Bosta call").isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    record Order(UUID id, String piece) {}

    private UUID submitExchangeForB(String number) {
        Order o = deliveredOrder(number, b1);
        assertThat(portal.submit(SLUG, tokens.issue(tenantId, o.id()), exchange(b1, b2)).orElseThrow().outcome())
            .isEqualTo(PortalService.SubmitOutcome.CREATED);
        return jdbc.queryForObject("SELECT id FROM return_requests WHERE order_id = ?", UUID.class, o.id());
    }

    private static PortalService.SubmitRequest exchange(UUID from, UUID to) {
        return new PortalService.SubmitRequest(List.of(new PortalService.SubmitLine(from, 1, "wrong_size")),
            null, null, NASR, "exchange", to, true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstLine(String number) {
        PortalService.LookupResult res = portal.lookup(SLUG, number, PHONE).orElseThrow();
        assertThat(res.outcome()).isEqualTo(PortalService.Outcome.SUCCESS);
        return ((List<Map<String, Object>>) res.body().get("lines")).get(0);
    }

    private String status(UUID r) {
        return jdbc.queryForObject("SELECT status::text FROM return_requests WHERE id = ?", String.class, r);
    }

    private UUID variant(UUID product, String title) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenantId, product, "gid://shopify/ProductVariant/" + v, title, "SKU-" + v.toString().substring(0, 6));
        return v;
    }

    private void stock(UUID variant, int count) {
        for (int i = 0; i < count; i++) {
            String id = UlidGenerator.generate();
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
                id, tenantId, variant, "PC-" + id, id, locationId);
        }
    }

    /** Delivered order with a delivered forward leg carrying a full address, one delivered piece of {@code variant}. */
    private Order deliveredOrder(String number, UUID variant) {
        UUID order = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, " +
            "    customer_name, customer_phone, pii_source) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now(), 'Mona Placeholder', ?, 'bosta') RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/" + UUID.randomUUID(), number, PHONE);
        String raw = "{\"type\":{\"code\":10,\"value\":\"Send\"}," +
            "\"dropOffAddress\":{\"firstLine\":\"12 Placeholder Street, Block 4\",\"secondLine\":\"Near the pharmacy\"," +
            "\"buildingNumber\":\"12\",\"floor\":\"3\",\"apartment\":\"7\",\"city\":{\"_id\":\"" + CAIRO + "\",\"name\":\"Cairo\"}," +
            "\"district\":{\"_id\":\"" + NASR + "\",\"name\":\"Nasr City\"}}," +
            "\"receiver\":{\"firstName\":\"Mona\",\"lastName\":\"Placeholder\",\"fullName\":\"Mona Placeholder\",\"phone\":\"+201000000001\"}}";
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, delivered_at, raw) " +
                    "VALUES (?, ?, 'bosta', ?, 'delivered'::shipment_internal_state, 'forward', now() - interval '2 days', ?::jsonb)",
            tenantId, order, String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 1_999_999_999L)), raw);
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
                    "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'delivered'::piece_status, ?, now())",
                    piece, tenantId, variant, "PC-" + piece, piece, order);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
                    item, tenantId, order, variant);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
                    tenantId, item, piece);
        return new Order(order, piece);
    }
}
