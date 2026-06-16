package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.*;
import com.traceability.security.EncryptionService;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Day 11 integration tests: Mode B fulfillment linking (FR-9.6 + FR-4.4).
 *
 * (a) AWB-scan creates shipment + tracking_linked events + pieces packed→awaiting_pickup + order advanced
 * (b) Swapped AWB (tracking already linked to different order) → 409
 * (c) Webhook auto-match by businessReference → shipment + linking (no manual scan needed)
 * (d) Unmatched delivery → unlinked_bosta_deliveries; manual link resolves it
 * (e) Courier status update on a properly-linked shipment transitions its pieces
 * (f) Cross-tenant tracking lookup → 404 (RLS fail-closed)
 *
 * BostaGateway and JobScheduler are @MockBean — no real Bosta API calls.
 * BostaWebhookJob is invoked directly as a Java method.
 * All piece transitions run through the real InventoryLedger against a real DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day11Test {

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

    @Autowired ShipmentLinkService  shipmentLinkSvc;
    @Autowired LookupService        lookupSvc;
    @Autowired BostaWebhookJob      bostaWebhookJob;
    @Autowired JdbcTemplate         jdbc;
    @Autowired EncryptionService    encryptionService;
    @Autowired ObjectMapper         mapper;
    @MockBean  BostaGateway         bostaGateway;
    @MockBean  JobScheduler         jobScheduler;

    UUID tenantId, actorId, storeId, variantId;

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        actorId   = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'D11Tenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Amira', 'amira@d11.local', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'd11.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-D11', 'Jacket', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-D11', 'Blue M', 'JACK-BLU-M')", variantId, tenantId, productId);
        // Courier account used by webhook job to load and decrypt the API key
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
                    "VALUES (gen_random_uuid(), ?, 'bosta', ?, 'test-hash', 'active')",
                    tenantId, encryptionService.encrypt("d11-api-key"));
    }

    @BeforeEach void setContext() { TenantContext.set(tenantId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?", tenantId);
    }

    // ── (a) AWB scan at pack ──────────────────────────────────────────────────

    @Test
    void a_awbScan_createsShipmentLinksPackedPiecesAndAdvancesOrder() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String p1    = createPiece("packed");
        String p2    = createPiece("packed");
        createAllocation(itemId, p1, "packed");
        createAllocation(itemId, p2, "packed");

        Map<String, Object> result = shipmentLinkSvc.linkByAwbScan(orderId, "AWB-A001", actorId);

        assertThat(result.get("linkedPieces")).isEqualTo(2);
        assertThat(result.get("orderStatus")).isEqualTo("awaiting_pickup");

        // Shipment created at 'created' state
        assertThat(shipmentState("AWB-A001")).isEqualTo("created");

        // Both pieces advanced to awaiting_pickup
        assertThat(pieceStatus(p1)).isEqualTo("awaiting_pickup");
        assertThat(pieceStatus(p2)).isEqualTo("awaiting_pickup");

        // tracking_linked events written with shipment_id populated
        int events = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE event_type = 'tracking_linked' AND tenant_id = ?",
            Integer.class, tenantId);
        assertThat(events).isEqualTo(2);

        String shipId = result.get("shipmentId").toString();
        int eventsWithShipment = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events " +
            "WHERE event_type = 'tracking_linked' AND shipment_id = ?::uuid AND tenant_id = ?",
            Integer.class, shipId, tenantId);
        assertThat(eventsWithShipment).isEqualTo(2);

        // Order advanced
        assertThat(orderStatus(orderId)).isEqualTo("awaiting_pickup");
    }

    // ── (b) Swapped AWB rejection ─────────────────────────────────────────────

    @Test
    void b_swappedAwb_rejectedWith409ContainingConflictingOrderInfo() {
        UUID order1 = createOrder("packed");
        UUID order2 = createOrder("packed");
        jdbc.update("UPDATE orders SET number = 'ORD-SWAP-1' WHERE id = ?", order1);
        jdbc.update("UPDATE orders SET number = 'ORD-SWAP-2' WHERE id = ?", order2);

        // Link AWB to order 1 first
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, 'AWB-SWAPPED', 'created')",
            tenantId, order1);

        // Attempting to link the same AWB to order 2 must be rejected
        ResponseStatusException ex = catchThrowableOfType(
            () -> shipmentLinkSvc.linkByAwbScan(order2, "AWB-SWAPPED", actorId),
            ResponseStatusException.class);

        assertThat(ex.getStatusCode().value()).isEqualTo(409);
        assertThat(ex.getReason()).contains("AWB-SWAPPED");
        assertThat(ex.getReason()).contains("ORD-SWAP-1");

        // Shipment must still belong to order1 (unchanged)
        UUID owningOrder = jdbc.queryForObject(
            "SELECT order_id FROM shipments WHERE tracking_number = ?",
            UUID.class, "AWB-SWAPPED");
        assertThat(owningOrder).isEqualTo(order1);
    }

    // ── (c) Auto-match by businessReference ───────────────────────────────────

    @Test
    void c_webhookAutoMatch_byBusinessReference_createsShipmentAndLinksPackedPieces() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String piece = createPiece("packed");
        createAllocation(itemId, piece, "packed");
        jdbc.update("UPDATE orders SET number = 'ORD-AUTOMATCH' WHERE id = ?", orderId);

        String tracking = "AWB-AUTOMATCH";
        Long webhookId  = insertWebhookEvent(tracking, 41, "SEND", "2026-06-17T10:00:00Z");

        // Mock: delivery carries businessReference that matches the order number
        ObjectNode raw = mapper.createObjectNode();
        when(bostaGateway.fetchDelivery(eq("d11-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 0, "ORD-AUTOMATCH", raw));

        bostaWebhookJob.process(webhookId, tenantId);

        assertThat(webhookStatus(webhookId)).isEqualTo("processed");

        // Shipment created and linked to the right order
        UUID shipOrderId = jdbc.queryForObject(
            "SELECT order_id FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            UUID.class, tracking, tenantId);
        assertThat(shipOrderId).isEqualTo(orderId);

        // Piece advanced by auto-match (packed → awaiting_pickup)
        assertThat(pieceStatus(piece)).isEqualTo("awaiting_pickup");

        // Order advanced
        assertThat(orderStatus(orderId)).isEqualTo("awaiting_pickup");

        // tracking_linked event written with the correct shipment context
        int linkEvents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE event_type = 'tracking_linked' AND piece_id = ?",
            Integer.class, piece);
        assertThat(linkEvents).isEqualTo(1);
    }

    // ── (d) Unmatched → unlinked; manual link resolves ────────────────────────

    @Test
    void d_unmatchedDelivery_goesToUnlinkedTable_manualLinkResolves() {
        UUID orderId = createOrder("packed");
        UUID itemId  = createOrderItem(orderId);
        String piece = createPiece("packed");
        createAllocation(itemId, piece, "packed");
        jdbc.update("UPDATE orders SET number = 'ORD-MANUAL' WHERE id = ?", orderId);

        String tracking = "AWB-UNMATCHED";
        Long webhookId  = insertWebhookEvent(tracking, 41, "SEND", "2026-06-17T11:00:00Z");

        // Mock: delivery has a businessReference that matches NO order
        ObjectNode raw = mapper.createObjectNode();
        when(bostaGateway.fetchDelivery(eq("d11-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 41, "SEND", 0, "UNKNOWN-999", raw));

        bostaWebhookJob.process(webhookId, tenantId);

        // Webhook processed; delivery recorded as unlinked
        assertThat(webhookStatus(webhookId)).isEqualTo("processed");
        Integer unlinkedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM unlinked_bosta_deliveries WHERE tracking_number = ? AND tenant_id = ?",
            Integer.class, tracking, tenantId);
        assertThat(unlinkedCount).isEqualTo(1);

        // Piece and order unchanged (no auto-link happened)
        assertThat(pieceStatus(piece)).isEqualTo("packed");
        assertThat(orderStatus(orderId)).isEqualTo("packed");

        // Operator manually links the unlinked delivery to the order.
        // bostaWebhookJob.process() uses TenantContext.runAs() which clears the context
        // on exit — restore it so manualLink() can call TenantContext.require().
        TenantContext.set(tenantId);
        Long unlinkedId = jdbc.queryForObject(
            "SELECT id FROM unlinked_bosta_deliveries WHERE tracking_number = ?",
            Long.class, tracking);
        shipmentLinkSvc.manualLink(unlinkedId, orderId, actorId);

        // After manual link: piece and order advanced; unlinked row resolved
        assertThat(pieceStatus(piece)).isEqualTo("awaiting_pickup");
        assertThat(orderStatus(orderId)).isEqualTo("awaiting_pickup");

        Boolean resolved = jdbc.queryForObject(
            "SELECT resolved FROM unlinked_bosta_deliveries WHERE id = ?", Boolean.class, unlinkedId);
        assertThat(resolved).isTrue();

        // Shipment row created with the correct tracking number
        Integer shipCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tracking_number = ? AND order_id = ?",
            Integer.class, tracking, orderId);
        assertThat(shipCount).isEqualTo(1);
    }

    // ── (e) Courier update on linked shipment transitions pieces ─────────────

    @Test
    void e_courierUpdate_onLinkedShipment_transitionsPieces() {
        // Piece already at with_courier (simulate state after AWB scan + pickup pickup)
        UUID orderId = createOrder("with_courier");
        UUID itemId  = createOrderItem(orderId);
        String piece = createPiece("with_courier");
        createAllocation(itemId, piece, "packed");

        // Shipment already linked (created by AWB scan earlier)
        UUID shipmentId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (?, ?, ?, 'AWB-COURIER45', 'with_courier')",
            shipmentId, tenantId, orderId);

        String tracking = "AWB-COURIER45";
        Long webhookId  = insertWebhookEvent(tracking, 45, "ALL", "2026-06-17T12:00:00Z");

        ObjectNode raw = mapper.createObjectNode();
        when(bostaGateway.fetchDelivery(eq("d11-api-key"), eq(tracking)))
            .thenReturn(new BostaDelivery(tracking, 45, "ALL", 1, "ORD-COURIER45", raw));

        bostaWebhookJob.process(webhookId, tenantId);

        // State 45 (delivered): piece → delivered, shipment → delivered
        assertThat(pieceStatus(piece)).isEqualTo("delivered");
        assertThat(shipmentState(tracking)).isEqualTo("delivered");
        assertThat(webhookStatus(webhookId)).isEqualTo("processed");

        // One courier_update event written on the piece
        int courierEvents = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events " +
            "WHERE event_type = 'courier_update' AND piece_id = ?",
            Integer.class, piece);
        assertThat(courierEvents).isEqualTo(1);

        // Event has the correct shipment_id
        UUID eventShipId = jdbc.queryForObject(
            "SELECT shipment_id FROM piece_events " +
            "WHERE event_type = 'courier_update' AND piece_id = ?",
            UUID.class, piece);
        assertThat(eventShipId).isEqualTo(shipmentId);
    }

    // ── (f) Cross-tenant tracking lookup → 404 ────────────────────────────────

    @Test
    void f_crossTenantTrackingLookup_notFound() {
        UUID orderId = createOrder("awaiting_pickup");
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (gen_random_uuid(), ?, ?, 'AWB-XTENANT', 'created')",
            tenantId, orderId);

        // Switch context to a different tenant
        UUID otherTenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'OtherTenant')", otherTenantId);
        TenantContext.set(otherTenantId);

        try {
            ResponseStatusException ex = catchThrowableOfType(
                () -> lookupSvc.lookupTracking("AWB-XTENANT"),
                ResponseStatusException.class);
            assertThat(ex.getStatusCode().value()).isEqualTo(404);
        } finally {
            TenantContext.set(tenantId); // restore for cleanup
            jdbc.update("DELETE FROM tenants WHERE id = ?", otherTenantId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID createOrder(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, status) " +
            "VALUES (?, ?, ?, ?::order_status) RETURNING id",
            UUID.class, tenantId, storeId, "EXT-" + UUID.randomUUID(), status);
    }

    private UUID createOrderItem(UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, 2) RETURNING id",
            UUID.class, tenantId, orderId, variantId);
    }

    private String createPiece(String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status) " +
            "VALUES (?, ?, ?, ?, ?::piece_status)",
            id, tenantId, variantId, "PC-" + id, status);
        return id;
    }

    private void createAllocation(UUID itemId, String pieceId, String status) {
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES (?, ?, ?, ?::allocation_status)",
            tenantId, itemId, pieceId, status);
    }

    private Long insertWebhookEvent(String tracking, int state, String type, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"updatedAt\":\"%s\"}",
            tracking, state, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events (source, tenant_id, topic, payload, status, received_at) " +
            "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) RETURNING id",
            Long.class, tenantId, payload);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String shipmentState(String tracking) {
        return jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE tracking_number = ? AND tenant_id = ?",
            String.class, tracking, tenantId);
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }
}
