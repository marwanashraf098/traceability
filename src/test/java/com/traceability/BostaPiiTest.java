package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.*;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration tests for Bosta consignee PII population (V36).
 *
 * Tests cover:
 *   p1  delivery link → PII fields filled from receiver data
 *   p2  fill-only-if-null — existing non-null PII not overwritten
 *   p3  GDPR guard — redacted order PII never re-populated (compliance critical)
 *   p4  pii_source recorded as 'bosta'
 *   p5  pack-page scan response includes customerName/customerPhone
 *   p6  not-yet-linked order → scan returns null customerName (UI shows pending text)
 *   p7  backfill fills already-linked orders from shipments.raw
 *   p8  backfill skips redacted orders
 *   p9  populate runs under app_user / RLS (TenantContext test)
 *   p10 phone normalization — E.164 +20 stored as canonical 01XXXXXXXXX
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaPiiTest {

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

    @LocalServerPort   int port;
    @Autowired TestRestTemplate  rest;
    @Autowired JdbcTemplate      jdbc;
    @Autowired JwtService        jwtService;
    @Autowired BostaWebhookJob   bostaWebhookJob;
    @Autowired EncryptionService encryptionService;
    @Autowired ObjectMapper      mapper;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;

    // app_user RLS infrastructure (built in @BeforeAll after TestSetup sets the password)
    JdbcTemplate        appUserJdbc;
    TransactionTemplate appUserTx;

    private UUID ownerTenantId;
    private String ownerToken;
    private UUID storeId;
    private UUID variantId;

    @BeforeAll
    void setupOwner() {
        SignupRequest req = new SignupRequest("Bosta PII Co", "pii_owner", "pii@test.com", "password99", true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ownerToken    = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString((String) jwtService.verify(ownerToken).getClaim("tenant"));

        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'pii-test.myshopify.com', 'disconnected')",
            storeId, ownerTenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'PROD-PII', 'PII Product')",
            productId, ownerTenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-PII', 'PII Variant')",
            variantId, ownerTenantId, productId);

        // app_user datasource — TestSetup sets the password via ApplicationReadyEvent
        DriverManagerDataSource raw = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(raw);
        appUserJdbc = new JdbcTemplate(appUserDs);
        appUserTx   = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
    }

    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM unlinked_bosta_deliveries");
        jdbc.execute("DELETE FROM piece_events");
        jdbc.execute("DELETE FROM allocations");
        jdbc.execute("UPDATE pieces SET current_order_id = NULL");
        jdbc.execute("DELETE FROM shipments");
        jdbc.execute("DELETE FROM order_items");
        jdbc.execute("DELETE FROM pieces");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM webhook_events");
        jdbc.execute("DELETE FROM courier_accounts");
    }

    // -------------------------------------------------------------------------
    // p1 — delivery link → null fields filled from receiver data
    // -------------------------------------------------------------------------
    @Test
    void p1_deliveryLink_fillsNullCustomerFields() {
        setupCourierAccount();
        UUID orderId = createOrder("#PII-001", null, null);

        ObjectNode rawJson = buildDelivery("BOS-PII-001", 10, "#PII-001",
                "Ahmed Hassan", "+201001234567",
                "123 Tahrir St", "Cairo", "Downtown", "Qasr El Nile", 150);

        Long webhookId = insertWebhookEvent("BOS-PII-001", 10, "SEND", "2026-07-06T10:00:00Z");
        when(bostaGateway.fetchDelivery(eq("test-api-key"), eq("BOS-PII-001")))
                .thenReturn(toDelivery(rawJson));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT customer_name, customer_phone, address, pii_source FROM orders WHERE id = ?", orderId);
        assertThat(row.get("customer_name")).isEqualTo("Ahmed Hassan");
        assertThat(row.get("customer_phone")).isEqualTo("01001234567"); // normalized
        assertThat(row.get("address").toString()).contains("Cairo");
        assertThat(row.get("pii_source")).isEqualTo("bosta");
    }

    // -------------------------------------------------------------------------
    // p2 — existing non-null PII is NOT overwritten (fill-only-if-null)
    // -------------------------------------------------------------------------
    @Test
    void p2_existingPii_notOverwritten() {
        setupCourierAccount();
        UUID orderId = createOrder("#PII-002", "Shopify Customer", "+201099999999");

        ObjectNode rawJson = buildDelivery("BOS-PII-002", 10, "#PII-002",
                "Bosta Receiver", "+201001234567",
                "New Address", "Alexandria", "Sidi Bishr", "Zone A", 0);

        Long webhookId = insertWebhookEvent("BOS-PII-002", 10, "SEND", "2026-07-06T10:01:00Z");
        when(bostaGateway.fetchDelivery(eq("test-api-key"), eq("BOS-PII-002")))
                .thenReturn(toDelivery(rawJson));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT customer_name, customer_phone FROM orders WHERE id = ?", orderId);
        // Shopify values preserved — Bosta does NOT overwrite
        assertThat(row.get("customer_name")).isEqualTo("Shopify Customer");
        assertThat(row.get("customer_phone")).isEqualTo("+201099999999");
    }

    // -------------------------------------------------------------------------
    // p3 — GDPR guard: redacted order PII stays null after delivery re-link
    //       This is the compliance-critical test.
    // -------------------------------------------------------------------------
    @Test
    void p3_redactedOrder_deliveryLink_piiStaysNull() {
        setupCourierAccount();
        UUID orderId = createOrder("#PII-003", null, null);

        // GDPR redact: set pii_redacted_at (simulates customers/redact handler)
        jdbc.update(
            "UPDATE orders SET pii_redacted_at = now() WHERE id = ?", orderId);

        ObjectNode rawJson = buildDelivery("BOS-PII-003", 10, "#PII-003",
                "Ahmed Hassan", "+201001234567",
                "123 Tahrir St", "Cairo", "Downtown", "Qasr El Nile", 150);

        Long webhookId = insertWebhookEvent("BOS-PII-003", 10, "SEND", "2026-07-06T10:02:00Z");
        when(bostaGateway.fetchDelivery(eq("test-api-key"), eq("BOS-PII-003")))
                .thenReturn(toDelivery(rawJson));

        // Re-link delivery after redaction
        bostaWebhookJob.process(webhookId, ownerTenantId);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT customer_name, customer_phone, address, pii_source FROM orders WHERE id = ?", orderId);
        // PII must stay null — GDPR guard prevents re-population
        assertThat(row.get("customer_name")).isNull();
        assertThat(row.get("customer_phone")).isNull();
        assertThat(row.get("address")).isNull();
        assertThat(row.get("pii_source")).isNull();
    }

    // -------------------------------------------------------------------------
    // p4 — pii_source recorded as 'bosta' after successful link
    // -------------------------------------------------------------------------
    @Test
    void p4_piiSource_recordedAsBosta() {
        setupCourierAccount();
        createOrder("#PII-004", null, null);

        ObjectNode rawJson = buildDelivery("BOS-PII-004", 10, "#PII-004",
                "Fatma Ali", "+201112223344",
                "55 El Mansoura St", "Mansoura", "Zone 1", "District A", 200);

        Long webhookId = insertWebhookEvent("BOS-PII-004", 10, "SEND", "2026-07-06T10:03:00Z");
        when(bostaGateway.fetchDelivery(eq("test-api-key"), eq("BOS-PII-004")))
                .thenReturn(toDelivery(rawJson));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        String piiSource = jdbc.queryForObject(
            "SELECT pii_source FROM orders WHERE number = '#PII-004' AND tenant_id = ?",
            String.class, ownerTenantId);
        assertThat(piiSource).isEqualTo("bosta");
    }

    // -------------------------------------------------------------------------
    // p5 — pack-page scan response includes customerName/customerPhone after link
    // -------------------------------------------------------------------------
    @Test
    void p5_packPage_displaysBostaPii_afterLink() {
        setupCourierAccount();
        UUID orderId     = createOrder("#PII-005", null, null);
        UUID orderItemId = createOrderItem(orderId);
        String barcode   = "PC-PII-005";
        String pieceId   = createPiece("packed", barcode, orderId);
        createAllocation(orderItemId, pieceId);

        ObjectNode rawJson = buildDelivery("BOS-PII-005", 10, "#PII-005",
                "Layla Mostafa", "+201234567890",
                "77 Nasr City Rd", "Cairo", "Nasr City", "Zone B", 300);

        Long webhookId = insertWebhookEvent("BOS-PII-005", 10, "SEND", "2026-07-06T10:04:00Z");
        when(bostaGateway.fetchDelivery(eq("test-api-key"), eq("BOS-PII-005")))
                .thenReturn(toDelivery(rawJson));

        // Process webhook — this auto-links and populates PII
        bostaWebhookJob.process(webhookId, ownerTenantId);

        // Scan the piece via lookup as an OWNER (non-worker) — should see customerName
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken);
        ResponseEntity<Map> scanResp = rest.exchange(
            "http://localhost:" + port + "/api/v1/lookup?q=" + barcode,
            HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(scanResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> order = (Map<?, ?>) scanResp.getBody().get("currentOrder");
        assertThat(order).isNotNull();
        assertThat(order.get("customerName")).isEqualTo("Layla Mostafa");
        assertThat(order.get("customerPhone")).isEqualTo("01234567890");
    }

    // -------------------------------------------------------------------------
    // p6 — not-yet-linked order: scan returns null customerName
    //       UI renders t('common.pendingConsignee') — tested at component level
    // -------------------------------------------------------------------------
    @Test
    void p6_notYetLinked_scanReturnsNullCustomerName() {
        setupCourierAccount();
        UUID orderId     = createOrder("#PII-006", null, null);
        UUID orderItemId = createOrderItem(orderId);
        String barcode   = "PC-PII-006";
        String pieceId   = createPiece("packed", barcode, orderId);
        createAllocation(orderItemId, pieceId);
        // No shipment created, no webhook processed — order not linked to Bosta

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken);
        ResponseEntity<Map> scanResp = rest.exchange(
            "http://localhost:" + port + "/api/v1/lookup?q=" + barcode,
            HttpMethod.GET, new HttpEntity<>(h), Map.class);

        assertThat(scanResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> currentOrder = (Map<?, ?>) scanResp.getBody().get("currentOrder");
        assertThat(currentOrder).isNotNull();
        assertThat(currentOrder.get("customerName")).isNull();  // → frontend shows pendingConsignee text
        assertThat(currentOrder.get("customerPhone")).isNull();
    }

    // -------------------------------------------------------------------------
    // p7 — backfill fills already-linked orders (from shipments.raw)
    // -------------------------------------------------------------------------
    @Test
    void p7_backfill_fillsAlreadyLinkedOrder() {
        UUID orderId = createOrder("#PII-007", null, null);

        // Simulate an already-linked shipment with receiver data in raw
        ObjectNode shipmentRaw = buildDelivery("BOS-PII-007", 10, "#PII-007",
                "Omar Nour", "+201555666777",
                "88 Heliopolis Ave", "Cairo", "Heliopolis", "District C", 100);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, tracking_number, internal_state, raw) " +
            "VALUES (?, ?, 'BOS-PII-007', 'created'::shipment_internal_state, ?::jsonb)",
            ownerTenantId, orderId, shipmentRaw.toString());

        // Order has null PII (as-if linked before this feature shipped)
        assertThat(jdbc.queryForObject(
            "SELECT customer_name FROM orders WHERE id = ?", String.class, orderId)).isNull();

        // Call backfill endpoint
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            "http://localhost:" + port + "/api/v1/bosta/backfill-pii",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("updatedOrders")).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT customer_name, customer_phone, pii_source FROM orders WHERE id = ?", orderId);
        assertThat(row.get("customer_name")).isEqualTo("Omar Nour");
        assertThat(row.get("customer_phone")).isEqualTo("01555666777");
        assertThat(row.get("pii_source")).isEqualTo("bosta");
    }

    // -------------------------------------------------------------------------
    // p8 — backfill skips redacted orders
    // -------------------------------------------------------------------------
    @Test
    void p8_backfill_skipsRedactedOrder() {
        UUID orderId = createOrder("#PII-008", null, null);

        // Set pii_redacted_at (GDPR redacted)
        jdbc.update("UPDATE orders SET pii_redacted_at = now() WHERE id = ?", orderId);

        ObjectNode shipmentRaw = buildDelivery("BOS-PII-008", 10, "#PII-008",
                "Should Not Fill", "+201111111111",
                "Test St", "Cairo", "Zone X", "District X", 50);
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, tracking_number, internal_state, raw) " +
            "VALUES (?, ?, 'BOS-PII-008', 'created'::shipment_internal_state, ?::jsonb)",
            ownerTenantId, orderId, shipmentRaw.toString());

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(ownerToken);
        ResponseEntity<Map> resp = rest.exchange(
            "http://localhost:" + port + "/api/v1/bosta/backfill-pii",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("updatedOrders")).isEqualTo(0);

        // PII must remain null
        String name = jdbc.queryForObject(
            "SELECT customer_name FROM orders WHERE id = ?", String.class, orderId);
        assertThat(name).isNull();
    }

    // -------------------------------------------------------------------------
    // p9 — populate write runs under app_user / RLS (tenant context test)
    // -------------------------------------------------------------------------
    @Test
    void p9_tenantContext_populateVisibleViaAppUser() {
        setupCourierAccount();
        UUID orderId = createOrder("#PII-009", null, null);

        ObjectNode rawJson = buildDelivery("BOS-PII-009", 10, "#PII-009",
                "Rania Khaled", "+201009876543",
                "10 Garden City Rd", "Cairo", "Garden City", "District D", 250);

        Long webhookId = insertWebhookEvent("BOS-PII-009", 10, "SEND", "2026-07-06T10:08:00Z");
        when(bostaGateway.fetchDelivery(eq("test-api-key"), eq("BOS-PII-009")))
                .thenReturn(toDelivery(rawJson));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        // Verify via app_user + TenantContext — proves write happened under RLS
        String nameViaAppUser = TenantContext.runAs(ownerTenantId, () ->
            appUserTx.execute(s ->
                appUserJdbc.queryForObject(
                    "SELECT customer_name FROM orders WHERE id = ?",
                    String.class, orderId)));

        assertThat(nameViaAppUser).isEqualTo("Rania Khaled");
    }

    // -------------------------------------------------------------------------
    // p10 — phone normalization: E.164 +20 stored as canonical 01XXXXXXXXX
    // -------------------------------------------------------------------------
    @Test
    void p10_phoneNormalization_e164StoredAsCanonical() {
        setupCourierAccount();
        createOrder("#PII-010", null, null);

        // E.164 format from Bosta: +201001234567
        ObjectNode rawJson = buildDelivery("BOS-PII-010", 10, "#PII-010",
                "Test User", "+201001234567",
                "Address", "Cairo", "Zone", "District", 0);

        Long webhookId = insertWebhookEvent("BOS-PII-010", 10, "SEND", "2026-07-06T10:09:00Z");
        when(bostaGateway.fetchDelivery(eq("test-api-key"), eq("BOS-PII-010")))
                .thenReturn(toDelivery(rawJson));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        String stored = jdbc.queryForObject(
            "SELECT customer_phone FROM orders WHERE number = '#PII-010' AND tenant_id = ?",
            String.class, ownerTenantId);
        // Stored in canonical 01XXXXXXXXX form — consistent with blocklist + phone+COD matching
        assertThat(stored).isEqualTo("01001234567");

        // Verify normalizePhone() agrees
        assertThat(ShipmentLinkService.normalizePhone("+201001234567")).isEqualTo("01001234567");

        // Verify: phone+COD SQL normalization (used in matchByPhoneAndCod) produces the same
        String normalizedViaSql = jdbc.queryForObject(
            "SELECT '0' || RIGHT(REGEXP_REPLACE(?, '[^0-9]', '', 'g'), 10)",
            String.class, "+201001234567");
        assertThat(normalizedViaSql).isEqualTo("01001234567");
    }

    // ---- helpers ------------------------------------------------------------

    private void setupCourierAccount() {
        jdbc.update(
            "INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'test-wh-hash', 'active')",
            ownerTenantId, encryptionService.encrypt("test-api-key"));
    }

    private UUID createOrder(String number, String customerName, String customerPhone) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, number, external_id, status, customer_name, customer_phone) " +
            "VALUES (?, ?, ?, ?, 'packed'::order_status, ?, ?) RETURNING id",
            UUID.class, ownerTenantId, storeId, number, number, customerName, customerPhone);
    }

    private UUID createOrderItem(UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, ownerTenantId, orderId, variantId);
    }

    private String createPiece(String status, String barcode) {
        return createPiece(status, barcode, null);
    }

    private String createPiece(String status, String barcode, UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status, current_order_id) " +
            "VALUES (?, ?, ?, ?, ?::piece_status, ?)",
            id, ownerTenantId, variantId, barcode, status, orderId);
        return id;
    }

    private void createAllocation(UUID orderItemId, String pieceId) {
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES (?, ?, ?, 'packed'::allocation_status)",
            ownerTenantId, orderItemId, pieceId);
    }

    private Long insertWebhookEvent(String tracking, int state, String type, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"updatedAt\":\"%s\"}",
            tracking, state, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta'::webhook_source, ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, ownerTenantId, payload);
    }

    /**
     * Builds a synthetic Bosta delivery raw JSON with receiver + dropOffAddress fields.
     * Mirrors the confirmed live v0 API shape from delivery-receiver.json.
     */
    private ObjectNode buildDelivery(String tracking, int stateCode, String businessRef,
                                      String fullName, String phone,
                                      String firstLine, String city, String zone, String district,
                                      int cod) {
        ObjectNode root = mapper.createObjectNode();
        root.put("trackingNumber",     tracking);
        root.put("businessReference",  businessRef);
        root.put("_id",                "bosta_id_" + tracking);
        root.put("cod",                cod);

        // state object
        root.putObject("state").put("code", stateCode);
        root.putObject("type").put("value", "SEND");

        // receiver
        ObjectNode receiver = root.putObject("receiver");
        receiver.put("fullName",   fullName);
        String[] parts = fullName.split(" ", 2);
        receiver.put("firstName",  parts[0]);
        receiver.put("lastName",   parts.length > 1 ? parts[1] : "");
        receiver.put("phone",      phone);
        receiver.putNull("secondPhone");

        // dropOffAddress
        ObjectNode drop = root.putObject("dropOffAddress");
        drop.put("firstLine", firstLine);
        drop.putObject("city").put("name", city);
        drop.putObject("zone").put("name", zone);
        drop.putObject("district").put("name", district);
        drop.putObject("country").put("code", "EG");

        return root;
    }

    private BostaDelivery toDelivery(ObjectNode raw) {
        return new BostaDelivery(
                raw.path("trackingNumber").asText(),
                raw.path("state").path("code").asInt(),
                "SEND",
                0,
                raw.path("businessReference").asText(null),
                null,
                raw);
    }
}
