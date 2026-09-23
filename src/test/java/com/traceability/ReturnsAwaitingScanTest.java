package com.traceability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.integrations.bosta.BostaDelivery;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaIngestionHelper;
import com.traceability.integrations.bosta.BostaWebhookJob;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantAwareDataSource;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Returns page — courier returns awaiting scan (Step 2).
 *
 * A: the shared "return leg awaiting scan" definition (ShipmentLinkService.awaitingScan())
 *    — any age; excludes scan evidence, completed intake, legs not yet returned, forward legs.
 * B: GET /api/v1/returns/awaiting-scan — worker gets 200; cross-tenant isolation with a
 *    same-tenant positive control and an app_user RLS check.
 * D: scanning a CRP AWB carries Bosta's returnSpecs.packageDetails; forward AWB unchanged.
 * G: CRP customer address comes from pickupAddress (customer), not dropOffAddress
 *    (merchant) — revert-to-confirm pair.
 *
 * CRP fixtures use the stored production shape: type.code=25, pickupAddress = customer,
 * dropOffAddress/returnAddress = merchant (returnAddress carries businessLocationId),
 * receiver = customer, sender = merchant; bare numeric tracking numbers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReturnsAwaitingScanTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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
    }

    private static final String RAW_API_KEY = "awaiting-scan-api-key";

    @LocalServerPort int port;
    @Autowired TestRestTemplate     rest;
    @Autowired JdbcTemplate         jdbc;
    @Autowired PasswordEncoder      passwordEncoder;
    @Autowired ObjectMapper         mapper;
    @Autowired EncryptionService    encryptionService;
    @Autowired ShipmentLinkService  linkSvc;
    @Autowired ExceptionService     exceptionSvc;
    @Autowired BostaWebhookJob      webhookJob;
    @Autowired BostaIngestionHelper ingestionHelper;

    @MockBean BostaGateway bostaGateway;
    @MockBean JobScheduler jobScheduler;

    private JdbcTemplate        appUserJdbc;
    private TransactionTemplate appUserTx;

    UUID tenantA, tenantB, storeA, storeB, variantA;
    String workerTokenA, ownerTokenB;

    @BeforeAll
    void setup() {
        DriverManagerDataSource rawDs =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawDs);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));

        tenantA = UUID.randomUUID(); tenantB = UUID.randomUUID();
        storeA  = UUID.randomUUID(); storeB  = UUID.randomUUID();
        variantA = UUID.randomUUID();
        UUID productA = UUID.randomUUID(), workerA = UUID.randomUUID(), ownerB = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AwaitA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AwaitB')", tenantB);
        String workerEmail = "worker-await-" + workerA + "@test.local";
        String ownerEmail  = "owner-await-" + ownerB + "@test.local";
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                    "VALUES (?, ?, 'Worker A', ?, ?, 'worker', true)",
                    workerA, tenantA, workerEmail, passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
                    "VALUES (?, ?, 'Owner B', ?, ?, 'owner', true)",
                    ownerB, tenantB, ownerEmail, passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'await-a.myshopify.com', 'disconnected')", storeA, tenantA);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'await-b.myshopify.com', 'disconnected')", storeB, tenantB);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-AW', 'Cotton Hoodie', 'active')", productA, tenantA, storeA);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-AW', 'Grey L', 'HOOD-GRY-L')", variantA, tenantA, productA);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
                    "VALUES (gen_random_uuid(), ?, 'AW Warehouse', 'warehouse', true, true)", tenantA);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'await-hash', 'active')",
                    tenantA, encryptionService.encrypt(RAW_API_KEY));

        workerTokenA = login(workerEmail);
        ownerTokenB  = login(ownerEmail);
    }

    @BeforeEach void ctx() { TenantContext.set(tenantA); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (UUID t : List.of(tenantA, tenantB)) {
            jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders WHERE tenant_id = ?", t);
        }
    }

    // ── A ───────────────────────────────────────────────────────────────────

    @Test
    void a_awaitingScan_anyAge_excludesEvidenceIntakeInTransitAndForward() {
        String today    = seedLeg(tenantA, storeA, "return",  "returned", 0,  false, crpRaw(2, "2 hoodies", "هوديز 2"));
        String old      = seedLeg(tenantA, storeA, "return",  "returned", 20, false, crpRaw(1, "1 hoodie", null));
        String scanned  = seedLeg(tenantA, storeA, "return",  "returned", 5,  false, crpRaw(1, "x", null));
        addScanEvidence(tenantA, scanned);
        String intake   = seedLeg(tenantA, storeA, "return",  "returned", 5,  true,  crpRaw(1, "x", null));
        String transit  = seedLeg(tenantA, storeA, "return",  "returning", 0, false, crpRaw(1, "x", null));
        String forward  = seedLeg(tenantA, storeA, "forward", "returned", 5,  false, null);

        Map<String, Object> result = linkSvc.awaitingScan();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");

        assertThat(result.get("count")).isEqualTo(2);
        assertThat(items).extracting(i -> i.get("trackingNumber")).containsExactly(today, old);
        assertThat(items).extracting(i -> i.get("trackingNumber"))
            .doesNotContain(scanned, intake, transit, forward);

        Map<String, Object> first = items.get(0);
        assertThat(first.get("itemsCount")).isEqualTo(2);
        assertThat(first.get("description")).isEqualTo("2 hoodies");
        assertThat(first.get("descriptionAr")).isEqualTo("هوديز 2");
        assertThat(first.get("orderNumber")).isNotNull();
        assertThat(first.get("returnedAt")).isNotNull();
        assertThat(first.keySet()).as("no customer PII in a worker-visible feed")
            .containsExactlyInAnyOrder("shipmentId", "trackingNumber", "orderNumber", "returnedAt",
                                       "itemsCount", "description", "descriptionAr");

        // Detector parity: same definition + the age window (default 3 days) → only the old one.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exc = (List<Map<String, Object>>)
            exceptionSvc.listExceptions("return_leg_unscanned", null, 0, 100).get("items");
        assertThat(exc).extracting(e -> e.get("tracking_number")).containsExactly(old);
    }

    // ── B ───────────────────────────────────────────────────────────────────

    @Test
    void b_workerGets200_crossTenantIsolated_withPositiveControlAndAppUserRls() {
        String legA = seedLeg(tenantA, storeA, "return", "returned", 1, false, crpRaw(1, "Hoodie", null));
        String legB = seedLeg(tenantB, storeB, "return", "returned", 1, false, crpRaw(1, "Other tenant", null));

        ResponseEntity<Map> asWorkerA = get("/api/v1/returns/awaiting-scan", workerTokenA);
        assertThat(asWorkerA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asWorkerA.getBody().get("count")).isEqualTo(1);
        assertThat(trackings(asWorkerA)).containsExactly(legA).doesNotContain(legB);

        ResponseEntity<Map> asOwnerB = get("/api/v1/returns/awaiting-scan", ownerTokenB);
        assertThat(asOwnerB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trackings(asOwnerB)).as("same-tenant positive control").containsExactly(legB);

        // RLS proof under app_user: the shared predicate with NO tenant filter still only
        // sees the GUC tenant's rows.
        Integer countA = TenantContext.runAs(tenantA, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject("SELECT COUNT(*) FROM shipments s WHERE " +
                ShipmentLinkService.RETURN_LEG_AWAITING_SCAN_SQL, Integer.class)));
        Integer countB = TenantContext.runAs(tenantB, () -> appUserTx.execute(s ->
            appUserJdbc.queryForObject("SELECT COUNT(*) FROM shipments s WHERE " +
                ShipmentLinkService.RETURN_LEG_AWAITING_SCAN_SQL, Integer.class)));
        assertThat(countA).isEqualTo(1);
        assertThat(countB).isEqualTo(1);

        ResponseEntity<Map> anon = rest.exchange(base() + "/api/v1/returns/awaiting-scan",
            HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── D ───────────────────────────────────────────────────────────────────

    @Test
    void d_crpAwbScan_carriesBostaDescription_forwardAwbUnchanged_missingFieldsNull() {
        UUID orderId = seedOrder(tenantA, storeA);
        seedDeliveredPiece(orderId);
        String crp = seedLegForOrder(tenantA, orderId, "return", "returned", 1, false,
            crpRaw(1, "1 hoodie grey L", "هودي رمادي"));
        UUID fwdOrder = seedOrder(tenantA, storeA);
        seedDeliveredPiece(fwdOrder);
        String fwd = seedLegForOrder(tenantA, fwdOrder, "forward", "delivered", 0, false, null);
        UUID bareOrder = seedOrder(tenantA, storeA);
        String bare = seedLegForOrder(tenantA, bareOrder, "return", "returned", 1, false,
            "{\"type\":{\"code\":25,\"value\":\"Customer Return Pickup\"}}");

        UUID session = UUID.fromString((String) post("/api/v1/returns/sessions", Map.of(), workerTokenA)
            .getBody().get("sessionId"));

        Map<?, ?> crpScan = post("/api/v1/returns/sessions/" + session + "/scan",
            Map.of("scan", "C-12-" + crp), workerTokenA).getBody();
        assertThat(crpScan.get("scanType")).isEqualTo("awb");
        assertThat(crpScan.get("itemsCount")).isEqualTo(1);
        assertThat(crpScan.get("description")).isEqualTo("1 hoodie grey L");
        assertThat(crpScan.get("descriptionAr")).isEqualTo("هودي رمادي");

        Map<?, ?> fwdScan = post("/api/v1/returns/sessions/" + session + "/scan",
            Map.of("scan", fwd), workerTokenA).getBody();
        assertThat(fwdScan.get("scanType")).isEqualTo("awb");
        assertThat(fwdScan.containsKey("itemsCount")).as("forward AWB response unchanged").isFalse();
        assertThat(fwdScan.containsKey("description")).isFalse();

        Map<?, ?> bareScan = post("/api/v1/returns/sessions/" + session + "/scan",
            Map.of("scan", bare), workerTokenA).getBody();
        assertThat(bareScan.get("itemsCount")).isNull();
        assertThat(bareScan.get("description")).isNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> courierReturns = (List<Map<String, Object>>)
            get("/api/v1/returns/sessions/" + session, workerTokenA).getBody().get("courierReturns");
        assertThat(courierReturns).extracting(c -> c.get("awb")).containsExactlyInAnyOrder(crp, bare);
        assertThat(courierReturns).filteredOn(c -> crp.equals(c.get("awb")))
            .singleElement().satisfies(c -> assertThat(c.get("description")).isEqualTo("1 hoodie grey L"));
    }

    // ── G ───────────────────────────────────────────────────────────────────

    @Test
    void g_crpCustomerAddress_fromPickupAddress_notMerchantDropOff() throws Exception {
        UUID orderId = seedOrder(tenantA, storeA);
        String number = jdbc.queryForObject("SELECT number FROM orders WHERE id = ?", String.class, orderId);
        seedLegForOrder(tenantA, orderId, "forward", "delivered", 0, false, null);

        String tracking = bareTracking();
        ObjectNode raw = (ObjectNode) mapper.readTree(crpRaw(1, "1 hoodie", null));
        raw.put("_id", "crp-" + tracking);
        raw.put("trackingNumber", tracking);
        raw.putObject("state").put("code", 41);
        raw.put("businessReference", number);
        raw.put("updatedAt", "2026-09-20T10:00:00.000Z");
        BostaDelivery d = new BostaDelivery(tracking, 41, "CUSTOMER RETURN PICKUP", 0, number, null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking))).thenReturn(d);

        boolean enqueued = TenantContext.runAs(tenantA,
            () -> ingestionHelper.ingestDelivery(tenantA, RAW_API_KEY, tracking, "bosta_poll"));
        assertThat(enqueued).isTrue();
        Long eventId = jdbc.queryForObject(
            "SELECT id FROM webhook_events WHERE tenant_id = ? AND payload->>'trackingNumber' = ? " +
            "ORDER BY received_at DESC, id DESC LIMIT 1", Long.class, tenantA, tracking);
        webhookJob.process(eventId, tenantA);

        assertThat(jdbc.queryForObject("SELECT shipment_leg FROM shipments WHERE tracking_number = ?",
            String.class, tracking)).isEqualTo("return");

        JsonNode address = mapper.readTree(jdbc.queryForObject(
            "SELECT address::text FROM orders WHERE id = ?", String.class, orderId));
        assertThat(address.path("firstLine").asText())
            .as("CRP customer address must come from pickupAddress, never the merchant's dropOffAddress")
            .isEqualTo("12 Customer St, Apt 4");
        assertThat(address.path("city").asText()).isEqualTo("Giza");
        assertThat(jdbc.queryForObject("SELECT customer_phone FROM orders WHERE id = ?", String.class, orderId))
            .as("receiver is the customer on a CRP").isEqualTo("01011112222");
        assertThat(jdbc.queryForObject("SELECT customer_name FROM orders WHERE id = ?", String.class, orderId))
            .isEqualTo("Mona Customer");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String bareTracking() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    /** Stored production CRP shape: pickupAddress = customer; dropOffAddress/returnAddress = merchant. */
    private static String crpRaw(Integer itemsCount, String description, String descriptionAr) {
        String pkg = "\"packageDetails\":{\"itemsCount\":" + itemsCount +
            ",\"description\":\"" + description + "\"" +
            (descriptionAr != null ? ",\"descriptionAr\":\"" + descriptionAr + "\"" : "") + "}";
        String merchant = "{\"firstLine\":\"Merchant Warehouse, Plot 7\",\"city\":{\"_id\":\"FceDyHXwpSYYF9zGW\"," +
            "\"name\":\"New Cairo\"},\"zone\":{\"_id\":\"g3jl3V8FMN\",\"name\":\"Fifth Settlement\"}," +
            "\"district\":{\"_id\":\"YFbGMSvcx2j\",\"name\":\"Industrial Zone\"}}";
        return "{\"type\":{\"code\":25,\"value\":\"Customer Return Pickup\"}," +
            "\"receiver\":{\"_id\":\"rcv1\",\"fullName\":\"Mona Customer\",\"phone\":\"+201011112222\"}," +
            "\"sender\":{\"_id\":\"snd1\",\"name\":\"Merchant Co\",\"phone\":\"+201099998888\"}," +
            "\"pickupAddress\":{\"firstLine\":\"12 Customer St, Apt 4\",\"city\":{\"_id\":\"0064Qb0OgcA\"," +
            "\"name\":\"Giza\"},\"zone\":{\"_id\":\"CHZJGpbyab\",\"name\":\"Dokki\"}," +
            "\"district\":{\"_id\":\"rdvFQsb5Qdl\",\"name\":\"Mesaha\"}}," +
            "\"dropOffAddress\":" + merchant + "," +
            "\"returnAddress\":" + merchant.substring(0, merchant.length() - 1) +
                ",\"businessLocationId\":\"P7J9dkmaB\"}," +
            "\"returnSpecs\":{\"packageType\":\"Parcel\"," + pkg + "}," +
            "\"specs\":{\"packageDetails\":{\"itemsCount\":1}}}";
    }

    private UUID seedOrder(UUID tenant, UUID store) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, 'cod'::order_payment_method, now()) RETURNING id",
            UUID.class, tenant, store, "gid://shopify/Order/" + UUID.randomUUID(),
            "#" + ThreadLocalRandom.current().nextInt(10_000, 99_999));
    }

    private String seedLeg(UUID tenant, UUID store, String leg, String state, int returnedDaysAgo,
                           boolean intakeDone, String rawJson) {
        return seedLegForOrder(tenant, seedOrder(tenant, store), leg, state, returnedDaysAgo, intakeDone, rawJson);
    }

    private String seedLegForOrder(UUID tenant, UUID orderId, String leg, String state, int returnedDaysAgo,
                                   boolean intakeDone, String rawJson) {
        String tracking = bareTracking();
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg, " +
            "    created_at, returned_at, return_intake_completed_at, raw) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, ?, now() - interval '30 days', " +
            "    CASE WHEN ? = 'returned' THEN now() - (interval '1 day' * ?) END, " +
            "    CASE WHEN ? THEN now() END, ?::jsonb)",
            tenant, orderId, tracking, state, leg, state, returnedDaysAgo, intakeDone, rawJson);
        return tracking;
    }

    private void addScanEvidence(UUID tenant, String tracking) {
        UUID orderId = jdbc.queryForObject("SELECT order_id FROM shipments WHERE tracking_number = ?",
            UUID.class, tracking);
        String piece = seedDeliveredPiece(orderId);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status) " +
                    "VALUES (?, ?, 'return_received', ?, 'delivered', 'return_pending_inspection')",
                    tenant, piece, orderId);
    }

    private String seedDeliveredPiece(UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, last_event_at) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        'delivered'::piece_status, ?, now())",
            id, tenantA, variantA, "PC-" + id, id, orderId);
        UUID itemId = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            itemId, tenantA, orderId, variantA);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            tenantA, itemId, id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private List<Object> trackings(ResponseEntity<Map> resp) {
        return ((List<Map<String, Object>>) resp.getBody().get("items")).stream()
            .map(i -> i.get("trackingNumber")).toList();
    }

    private String base() { return "http://localhost:" + port; }

    private String login(String email) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> resp = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().accessToken();
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(String path, Object body, String token) {
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, authJson(token)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(authJson(token)), Map.class);
    }
}
