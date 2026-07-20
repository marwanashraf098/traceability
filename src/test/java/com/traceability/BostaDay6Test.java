package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.identity.JwtService;
import com.traceability.identity.model.SignupRequest;
import com.traceability.identity.model.TokenResponse;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.UlidGenerator;
import com.traceability.security.EncryptionService;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Day 6 integration tests for BostaWebhookJob → InventoryLedger wiring.
 *
 * BostaGateway and JobScheduler are @MockBean — no real Bosta API calls.
 * BostaWebhookJob is invoked directly as a Java method (not via JobRunr).
 * All piece transitions run through the real InventoryLedger against a real DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BostaDay6Test {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate     rest;
    @Autowired JdbcTemplate         jdbc;
    @Autowired JwtService           jwtService;
    @Autowired BostaWebhookJob      bostaWebhookJob;
    @Autowired EncryptionService    encryptionService;
    @Autowired ObjectMapper         mapper;
    @MockBean  BostaGateway         bostaGateway;
    @MockBean  JobScheduler         jobScheduler;

    private UUID ownerTenantId;
    private UUID storeId;
    private UUID variantId;

    @BeforeAll
    void setupOwner() {
        SignupRequest req = new SignupRequest("Bosta D6 Co", "d6_owner", "d6@test.com", "password99", true);
        ResponseEntity<TokenResponse> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/signup", req, TokenResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = resp.getBody().accessToken();
        ownerTenantId = UUID.fromString((String) jwtService.verify(token).getClaim("tenant"));

        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'd6-test.myshopify.com', 'disconnected')",
            storeId, ownerTenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'PROD-D6', 'D6 Product')",
            productId, ownerTenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-D6', 'D6 Variant')",
            variantId, ownerTenantId, productId);
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
    // (1) State 45 (Delivered) → all active/packed pieces move to delivered,
    //     one courier_update event per piece, webhook marked processed.
    // -------------------------------------------------------------------------
    @Test
    void webhookJob_state45_allPiecesMovedToDelivered() {
        String trackingNumber = "BOS-D6-DELIVERED";
        setupCourierAccount(encryptionService.encrypt("api-key-d6a"));

        UUID orderId      = createOrder("EXT-D6-001");
        UUID orderItemId  = createOrderItem(orderId);
        String piece1     = createPiece("with_courier");
        String piece2     = createPiece("with_courier");
        createAllocation(orderItemId, piece1);
        createAllocation(orderItemId, piece2);
        createShipment(orderId, trackingNumber);

        Long webhookId = insertWebhookEvent(trackingNumber, 45, "ALL", "2026-06-14T12:00:00Z");

        when(bostaGateway.fetchDelivery(eq("api-key-d6a"), eq(trackingNumber)))
            .thenReturn(new BostaDelivery(
                trackingNumber, 45, "ALL", 1, "EXT-D6-001", null, mapper.createObjectNode()));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        assertThat(pieceStatus(piece1)).isEqualTo("delivered");
        assertThat(pieceStatus(piece2)).isEqualTo("delivered");
        assertThat(courierUpdateEventCount(piece1)).isEqualTo(1);
        assertThat(courierUpdateEventCount(piece2)).isEqualTo(1);
        assertThat(webhookStatus(webhookId)).isEqualTo("processed");
    }

    // -------------------------------------------------------------------------
    // (2) Redelivery of same webhook event → dedup check fires, no duplicate
    //     piece transitions, both webhook rows end up 'processed'.
    // -------------------------------------------------------------------------
    @Test
    void webhookJob_redelivery_noDuplicatePieceTransitions() {
        String trackingNumber = "BOS-D6-REDELIVERY";
        setupCourierAccount(encryptionService.encrypt("api-key-d6b"));

        UUID orderId     = createOrder("EXT-D6-002");
        UUID orderItemId = createOrderItem(orderId);
        String piece1    = createPiece("with_courier");
        createAllocation(orderItemId, piece1);
        createShipment(orderId, trackingNumber);

        String commonPayload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":45,\"updatedAt\":\"2026-06-14T13:00:00Z\"}",
            trackingNumber);

        Long id1 = jdbc.queryForObject(
            "INSERT INTO webhook_events(source,tenant_id,topic,payload,status,received_at) " +
            "VALUES('bosta',?,'delivery_update',?::jsonb,'pending',now()) RETURNING id",
            Long.class, ownerTenantId, commonPayload);
        Long id2 = jdbc.queryForObject(
            "INSERT INTO webhook_events(source,tenant_id,topic,payload,status,received_at) " +
            "VALUES('bosta',?,'delivery_update',?::jsonb,'pending',now()) RETURNING id",
            Long.class, ownerTenantId, commonPayload);

        when(bostaGateway.fetchDelivery(eq("api-key-d6b"), eq(trackingNumber)))
            .thenReturn(new BostaDelivery(
                trackingNumber, 45, "ALL", 1, "EXT-D6-002", null, mapper.createObjectNode()));

        bostaWebhookJob.process(id1, ownerTenantId);
        bostaWebhookJob.process(id2, ownerTenantId);

        // Piece moved exactly once despite two processings
        assertThat(courierUpdateEventCount(piece1)).isEqualTo(1);
        assertThat(pieceStatus(piece1)).isEqualTo("delivered");

        // Both webhook rows are resolved
        assertThat(webhookStatus(id1)).isEqualTo("processed");
        assertThat(webhookStatus(id2)).isEqualTo("processed");

        // Second row is flagged as duplicate
        String secondError = jdbc.queryForObject(
            "SELECT error FROM webhook_events WHERE id = ?", String.class, id2);
        assertThat(secondError).contains("duplicate");
    }

    // -------------------------------------------------------------------------
    // (3) Unlinked Mode-B delivery: tracking_number has no matching shipment.
    //     Row recorded in unlinked_bosta_deliveries; webhook marked processed
    //     (not failed — this is an expected Mode-B scenario).
    // -------------------------------------------------------------------------
    @Test
    void webhookJob_unlinkedTracking_recordedInUnlinkedTable() {
        String trackingNumber = "BOS-D6-UNLINKED";
        setupCourierAccount(encryptionService.encrypt("api-key-d6c"));

        Long webhookId = insertWebhookEvent(trackingNumber, 45, "ALL", "2026-06-14T14:00:00Z");

        when(bostaGateway.fetchDelivery(eq("api-key-d6c"), eq(trackingNumber)))
            .thenReturn(new BostaDelivery(
                trackingNumber, 45, "ALL", 1, "ORD-UNLINKED", null, mapper.createObjectNode()));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        assertThat(webhookStatus(webhookId)).isEqualTo("processed");

        Integer unlinkedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tracking_number = ?",
            Integer.class, trackingNumber);
        assertThat(unlinkedCount).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // (4) Unknown Bosta state code → webhook marked failed, pieces untouched.
    //     Job returns at step 7 (before shipment lookup); no piece transition.
    // -------------------------------------------------------------------------
    @Test
    void webhookJob_unknownStateCode_webhookFailedAndPiecesUntouched() {
        String trackingNumber = "BOS-D6-UNKNOWN";
        setupCourierAccount(encryptionService.encrypt("api-key-d6d"));

        UUID orderId     = createOrder("EXT-D6-004");
        UUID orderItemId = createOrderItem(orderId);
        String piece1    = createPiece("with_courier");
        createAllocation(orderItemId, piece1);
        createShipment(orderId, trackingNumber);

        Long webhookId = insertWebhookEvent(trackingNumber, 999, "SEND", "2026-06-14T15:00:00Z");

        when(bostaGateway.fetchDelivery(eq("api-key-d6d"), eq(trackingNumber)))
            .thenReturn(new BostaDelivery(
                trackingNumber, 999, "SEND", 0, "EXT-D6-004", null, mapper.createObjectNode()));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        assertThat(webhookStatus(webhookId)).isEqualTo("failed");
        assertThat(pieceStatus(piece1)).isEqualTo("with_courier");
        assertThat(courierUpdateEventCount(piece1)).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // (5a) State 41 SEND → shipment moves to 'with_courier', but pieces are NOT
    //      moved (piece_status_after is NULL for this mapping).
    // -------------------------------------------------------------------------
    @Test
    void webhookJob_state41Send_shipmentUpdatedNoPieceTransition() {
        String trackingNumber = "BOS-D6-SEND";
        setupCourierAccount(encryptionService.encrypt("api-key-d6e"));

        UUID orderId     = createOrder("EXT-D6-005");
        UUID orderItemId = createOrderItem(orderId);
        String piece1    = createPiece("packed");
        createAllocation(orderItemId, piece1);
        createShipment(orderId, trackingNumber);

        Long webhookId = insertWebhookEvent(trackingNumber, 41, "SEND", "2026-06-14T16:00:00Z");

        when(bostaGateway.fetchDelivery(eq("api-key-d6e"), eq(trackingNumber)))
            .thenReturn(new BostaDelivery(
                trackingNumber, 41, "SEND", 1, "EXT-D6-005", null, mapper.createObjectNode()));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        assertThat(webhookStatus(webhookId)).isEqualTo("processed");
        assertThat(pieceStatus(piece1)).isEqualTo("packed");
        assertThat(courierUpdateEventCount(piece1)).isEqualTo(0);

        String shipmentState = jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE tracking_number = ?",
            String.class, trackingNumber);
        assertThat(shipmentState).isEqualTo("with_courier");
    }

    // -------------------------------------------------------------------------
    // (5b) State 41 RTO → pieces moved to 'return_in_transit', shipment to
    //      'returning'. Contrasts with 41 SEND above.
    // -------------------------------------------------------------------------
    @Test
    void webhookJob_state41Rto_piecesMovedToReturnInTransit() {
        String trackingNumber = "BOS-D6-RTO";
        setupCourierAccount(encryptionService.encrypt("api-key-d6f"));

        UUID orderId     = createOrder("EXT-D6-006");
        UUID orderItemId = createOrderItem(orderId);
        String piece1    = createPiece("with_courier");
        createAllocation(orderItemId, piece1);
        createShipment(orderId, trackingNumber);

        Long webhookId = insertWebhookEvent(trackingNumber, 41, "RTO", "2026-06-14T17:00:00Z");

        when(bostaGateway.fetchDelivery(eq("api-key-d6f"), eq(trackingNumber)))
            .thenReturn(new BostaDelivery(
                trackingNumber, 41, "RTO", 2, "EXT-D6-006", null, mapper.createObjectNode()));

        bostaWebhookJob.process(webhookId, ownerTenantId);

        assertThat(webhookStatus(webhookId)).isEqualTo("processed");
        assertThat(pieceStatus(piece1)).isEqualTo("return_in_transit");
        assertThat(courierUpdateEventCount(piece1)).isEqualTo(1);

        String shipmentState = jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE tracking_number = ?",
            String.class, trackingNumber);
        assertThat(shipmentState).isEqualTo("returning");
    }

    // ---- helpers ------------------------------------------------------------

    private void setupCourierAccount(String encryptedKey) {
        jdbc.update(
            "INSERT INTO courier_accounts(tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES(?, 'bosta', ?, 'test-hash', 'active')",
            ownerTenantId, encryptedKey);
    }

    private UUID createOrder(String extId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES(?, ?, ?, 'new'::order_status) RETURNING id",
            UUID.class, ownerTenantId, storeId, extId);
    }

    private UUID createOrderItem(UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES(?, ?, ?, 1) RETURNING id",
            UUID.class, ownerTenantId, orderId, variantId);
    }

    private String createPiece(String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES(?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status)",
            id, ownerTenantId, variantId, "PC-" + id, id, status);
        return id;
    }

    private void createAllocation(UUID orderItemId, String pieceId) {
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES(?, ?, ?, 'packed'::allocation_status)",
            ownerTenantId, orderItemId, pieceId);
    }

    private void createShipment(UUID orderId, String trackingNumber) {
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, tracking_number) VALUES(?, ?, ?)",
            ownerTenantId, orderId, trackingNumber);
    }

    private Long insertWebhookEvent(String trackingNumber, int state, String type, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"updatedAt\":\"%s\"}",
            trackingNumber, state, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events(source,tenant_id,topic,payload,status,received_at) " +
            "VALUES('bosta',?,'delivery_update',?::jsonb,'pending',now()) RETURNING id",
            Long.class, ownerTenantId, payload);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private int courierUpdateEventCount(String pieceId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'courier_update'",
            Integer.class, pieceId);
        return n == null ? 0 : n;
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }
}
