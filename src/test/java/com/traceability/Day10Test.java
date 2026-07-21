package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Day 10 integration tests: piece-lookup timeline (FR-14).
 *
 * (a) Piece lookup returns full timeline newest-first
 * (b) Actor names populated; null actor_user_id → "System"
 * (c) Phrase keys match event type + transition
 * (d) Unknown barcode → 404
 * (e) Cross-tenant barcode → 404 (RLS fail-closed)
 * (f) Worker role omits customer PII from currentOrder; operational fields kept
 * (g) Tracking number lookup routes to shipment + piece list
 * (h) Piece links back to order; shipment links back to order (bidirectional navigation)
 * (i) Piece with receiving-session origin has receivingSession populated
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day10Test {

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

    @Autowired LookupService  lookupSvc;
    @Autowired FulfillService fulfillSvc;
    @Autowired InventoryLedger ledger;
    @Autowired JdbcTemplate   jdbc;

    // app_user-connected service + tx wrapper for RLS isolation tests
    LookupService      appUserLookupSvc;
    TransactionTemplate appUserTx;

    UUID tenantAId, tenantBId;
    UUID ownerActorId, workerActorId;
    UUID storeAId, storeBId;
    UUID variantAId;
    UUID locationAId;
    UUID productAId;

    @BeforeAll
    void setup() {
        tenantAId     = UUID.randomUUID();
        tenantBId     = UUID.randomUUID();
        ownerActorId  = UUID.randomUUID();
        workerActorId = UUID.randomUUID();
        storeAId      = UUID.randomUUID();
        storeBId      = UUID.randomUUID();
        productAId    = UUID.randomUUID();
        variantAId    = UUID.randomUUID();
        locationAId   = UUID.randomUUID();

        // Tenant A
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TenantA')", tenantAId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Amira', 'amira@d10.local', 'h', 'owner')", ownerActorId, tenantAId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Karim', 'karim@d10.local', 'h', 'worker')", workerActorId, tenantAId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'day10a.myshopify.com', 'disconnected')", storeAId, tenantAId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-D10', 'Helmet', 'active')", productAId, tenantAId, storeAId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-D10', 'Red', 'HELM-RED')", variantAId, tenantAId, productAId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES (?, ?, 'Main Warehouse', 'warehouse', true)", locationAId, tenantAId);

        // Tenant B
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TenantB')", tenantBId);
        UUID actorBId  = UUID.randomUUID();
        UUID productBId = UUID.randomUUID();
        UUID variantBId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'BOwner', 'bowner@d10.local', 'h', 'owner')", actorBId, tenantBId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'day10b.myshopify.com', 'disconnected')", storeBId, tenantBId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-B10', 'Gadget', 'active')", productBId, tenantBId, storeBId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-B10', 'Blue', 'GAD-BLU')", variantBId, tenantBId, productBId);

        // app_user datasource for RLS isolation tests
        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(raw);
        appUserLookupSvc = new LookupService(new JdbcTemplate(appUserDs));
        // TransactionTemplate drives setAutoCommit(false) → TenantAwareDataSource sets the GUC.
        // @Transactional on appUserLookupSvc has no effect (no Spring proxy on new-constructed beans).
        appUserTx = new TransactionTemplate(new DataSourceTransactionManager(appUserDs));
        appUserTx.setReadOnly(true);
    }

    @BeforeEach
    void setContext() { TenantContext.set(tenantAId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM allocations  WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM pieces       WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM shipments    WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM order_items  WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM orders       WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM receipts     WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
    }

    // ── (a) Full timeline newest-first ────────────────────────────────────────

    @Test
    void a_piece_lookup_returns_timeline_newest_first() {
        String pieceId = insertPiece(variantAId);
        String barcode = "PC-" + pieceId;
        UUID orderId   = insertOrderWithItem(variantAId, 1);

        // Receive event written manually (received event has null from_status)
        insertEvent(pieceId, "received", null, "available", ownerActorId, locationAId, null, null);
        insertEvent(pieceId, "scan",     "available", "reserved", ownerActorId, null, orderId, null);

        Map<String, Object> result = lookupSvc.lookupPiece(barcode, false);

        assertThat(result.get("type")).isEqualTo("piece");
        assertThat(result.get("barcode")).isEqualTo(barcode);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) result.get("timeline");
        assertThat(timeline).hasSize(2);

        // Newest first: scan event (id=2) before received event (id=1)
        assertThat(timeline.get(0).get("eventType")).isEqualTo("scan");
        assertThat(timeline.get(1).get("eventType")).isEqualTo("received");
    }

    // ── (b) Actor name + "System" fallback ───────────────────────────────────

    @Test
    void b_actor_name_populated_and_system_for_null_actor() {
        String pieceId = insertPiece(variantAId);
        String barcode = "PC-" + pieceId;

        insertEvent(pieceId, "received",      null,        "available", ownerActorId, locationAId, null, null);
        insertEvent(pieceId, "courier_update","available", "delivered", null,         null,         null, null);

        Map<String, Object> result = lookupSvc.lookupPiece(barcode, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) result.get("timeline");

        // Newest first: courier_update (null actor → System), then received (Amira)
        Map<String, Object> courierEvent = timeline.get(0);
        assertThat(courierEvent.get("actor")).isEqualTo("System");
        assertThat(courierEvent.get("isSystem")).isEqualTo(true);

        Map<String, Object> receivedEvent = timeline.get(1);
        assertThat(receivedEvent.get("actor")).isEqualTo("Amira");
        assertThat(receivedEvent.get("isSystem")).isEqualTo(false);
    }

    // ── (c) Phrase keys ───────────────────────────────────────────────────────

    @Test
    void c_phrase_keys_map_correctly_for_all_event_types() {
        String pieceId = insertPiece(variantAId);
        UUID orderId = insertOrderWithItem(variantAId, 2);

        insertEvent(pieceId, "received",       null,       "available", ownerActorId, locationAId, null, null);
        insertEvent(pieceId, "scan",           "available","reserved",  ownerActorId, null, orderId, null);
        insertEvent(pieceId, "unscan",         "reserved", "available", ownerActorId, null, orderId, null);
        insertEvent(pieceId, "scan",           "available","reserved",  ownerActorId, null, orderId, null);
        insertEvent(pieceId, "pack",           "reserved", "packed",    ownerActorId, null, orderId, null);
        insertEvent(pieceId, "courier_update", "packed",   "awaiting_pickup", null, null, null, null);
        insertEvent(pieceId, "courier_update", "awaiting_pickup","with_courier", null, null, null, null);
        insertEvent(pieceId, "courier_update", "with_courier","delivered", null, null, null, null);

        Map<String, Object> result = lookupSvc.lookupPiece("PC-" + pieceId, false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tl = (List<Map<String, Object>>) result.get("timeline");

        // newest-first, so delivered is first
        assertThat(tl.get(0).get("phraseKey")).isEqualTo("courier_delivered");
        assertThat(tl.get(1).get("phraseKey")).isEqualTo("courier_picked_up");
        assertThat(tl.get(2).get("phraseKey")).isEqualTo("courier_awaiting_pickup");
        assertThat(tl.get(3).get("phraseKey")).isEqualTo("packed_for_order");
        assertThat(tl.get(4).get("phraseKey")).isEqualTo("reserved_for_order");
        assertThat(tl.get(5).get("phraseKey")).isEqualTo("returned_to_stock");
        assertThat(tl.get(6).get("phraseKey")).isEqualTo("reserved_for_order");
        assertThat(tl.get(7).get("phraseKey")).isEqualTo("received_at");
    }

    // ── (d) Unknown barcode → 404 ────────────────────────────────────────────

    @Test
    void d_unknown_barcode_returns_404() {
        assertThatThrownBy(() -> lookupSvc.lookupPiece("PC-DOESNOTEXIST", false))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    // ── (e) Cross-tenant → 404 (RLS, app_user path) ──────────────────────────

    @Test
    void e_cross_tenant_barcode_not_found_via_rls() {
        String pieceId = insertPiece(variantAId);
        String barcode = "PC-" + pieceId;

        // Tenant B tries to look up Tenant A's piece; appUserTx sets GUC to tenantBId
        // so RLS filters out tenantA's row → LookupNotFoundException.
        TenantContext.set(tenantBId);
        try {
            assertThatThrownBy(() -> appUserTx.execute(s -> appUserLookupSvc.lookupPiece(barcode, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        } finally {
            TenantContext.set(tenantAId);
        }
    }

    // ── (f) Worker role hides customer PII ───────────────────────────────────

    @Test
    void f_worker_role_hides_customer_pii_from_current_order() {
        UUID orderId = insertOrderWithCustomer("Fatima Salem", "+201234567890");
        // reserve the piece so it links to the order
        String pieceId = insertPieceLinkedToOrder(variantAId, orderId);
        insertEvent(pieceId, "scan", "available", "reserved", workerActorId, null, orderId, null);

        // Owner sees PII
        Map<String, Object> ownerResult = lookupSvc.lookupPiece("PC-" + pieceId, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> ownerOrder = (Map<String, Object>) ownerResult.get("currentOrder");
        assertThat(ownerOrder).isNotNull();
        assertThat(ownerOrder.get("customerName")).isEqualTo("Fatima Salem");
        assertThat(ownerOrder.get("customerPhone")).isEqualTo("+201234567890");
        assertThat(ownerOrder.get("number")).isNotNull();

        // Worker does NOT see PII but sees order number (operational)
        Map<String, Object> workerResult = lookupSvc.lookupPiece("PC-" + pieceId, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> workerOrder = (Map<String, Object>) workerResult.get("currentOrder");
        assertThat(workerOrder).isNotNull();
        assertThat(workerOrder).doesNotContainKey("customerName");
        assertThat(workerOrder).doesNotContainKey("customerPhone");
        assertThat(workerOrder.get("number")).isNotNull();
    }

    // ── (g) Tracking number lookup ────────────────────────────────────────────

    @Test
    void g_tracking_number_lookup_returns_shipment_and_pieces() {
        UUID orderId   = insertOrderWithItem(variantAId, 1);
        UUID shipmentId = insertShipment(orderId, "5678901234");
        String pieceId  = insertPiece(variantAId);
        // Insert allocation so the piece appears in the shipment piece list
        UUID orderItemId = jdbc.queryForObject(
            "SELECT id FROM order_items WHERE order_id = ?", UUID.class, orderId);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'packed'::allocation_status)",
            tenantAId, orderItemId, pieceId);

        Map<String, Object> result = lookupSvc.lookupTracking("5678901234");

        assertThat(result.get("type")).isEqualTo("tracking");
        assertThat(result.get("trackingNumber")).isEqualTo("5678901234");
        assertThat(result.get("shipmentId")).isEqualTo(shipmentId.toString());
        assertThat(result.get("orderId")).isEqualTo(orderId.toString());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pieces = (List<Map<String, Object>>) result.get("pieces");
        assertThat(pieces).hasSize(1);
        assertThat(pieces.get(0).get("barcode")).isEqualTo("PC-" + pieceId);
    }

    // ── (h) Bidirectional navigation ──────────────────────────────────────────

    @Test
    void h_piece_links_to_order_and_order_contains_piece_via_fulfill_endpoint() {
        UUID orderId  = insertOrderWithItem(variantAId, 1);
        String pieceId = insertPiece(variantAId);

        fulfillSvc.scan(orderId, "PC-" + pieceId, ownerActorId);

        // Piece → currentOrder has orderId
        Map<String, Object> pieceResult = lookupSvc.lookupPiece("PC-" + pieceId, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> linkedOrder = (Map<String, Object>) pieceResult.get("currentOrder");
        assertThat(linkedOrder).isNotNull();
        assertThat(linkedOrder.get("id")).isEqualTo(orderId.toString());

        // Order → allocatedPieces contains this piece (bidirectional, via FulfillService)
        Map<String, Object> orderDetail = fulfillSvc.getOrder(orderId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) orderDetail.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocated = (List<Map<String, Object>>) items.get(0).get("allocatedPieces");
        assertThat(allocated).hasSize(1);
        assertThat(allocated.get(0).get("barcode")).isEqualTo("PC-" + pieceId);
    }

    // ── (i) Receiving session origin ──────────────────────────────────────────

    @Test
    void i_piece_with_receipt_origin_returns_receiving_session() {
        UUID receiptId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, location_id, received_by, status) VALUES (?, ?, ?, ?, 'finalized')",
            receiptId, tenantAId, locationAId, ownerActorId);

        String pieceId = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, receipt_id, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?, ?)",
            pieceId, tenantAId, variantAId, "PC-" + pieceId, pieceId, receiptId, locationAId);

        Map<String, Object> result = lookupSvc.lookupPiece("PC-" + pieceId, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> session = (Map<String, Object>) result.get("receivingSession");
        assertThat(session).isNotNull();
        assertThat(session.get("id")).isEqualTo(receiptId.toString());
        assertThat(session.get("locationName")).isEqualTo("Main Warehouse");
    }

    // ── (e-pos) Same-tenant PC-barcode visible via app_user RLS ──────────────

    @Test
    void e_pos_same_tenant_barcode_found_via_rls() {
        String pieceId = insertPiece(variantAId);
        // appUserTx sets GUC to tenantAId (TenantContext set by @BeforeEach);
        // RLS passes → piece from tenantA is visible.
        Map<String, Object> result = appUserTx.execute(
                s -> appUserLookupSvc.lookupPiece("PC-" + pieceId, false));
        assertThat(result).isNotNull();
        assertThat(result.get("type")).isEqualTo("piece");
    }

    // ── (e-pos2) Same-tenant short code visible via app_user RLS ─────────────

    @Test
    void e_pos2_same_tenant_short_code_found_via_rls() {
        String pieceId = insertPiece(variantAId);
        String shortCode = jdbc.queryForObject(
            "SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
        Map<String, Object> result = appUserTx.execute(
                s -> appUserLookupSvc.lookupPiece(shortCode, false));
        assertThat(result).isNotNull();
        assertThat(result.get("type")).isEqualTo("piece");
    }

    // ── (e-pos3) Same-tenant tracking number visible via app_user RLS ─────────

    @Test
    void e_pos3_same_tenant_tracking_found_via_rls() {
        UUID orderId = insertOrderWithItem(variantAId, 1);
        insertShipment(orderId, "TN-RLS-POS3");
        Map<String, Object> result = appUserTx.execute(
                s -> appUserLookupSvc.lookupTracking("TN-RLS-POS3"));
        assertThat(result).isNotNull();
        assertThat(result.get("type")).isEqualTo("tracking");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private String insertPiece(UUID variantId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?)",
            id, tenantAId, variantId, "PC-" + id, id, locationAId);
        return id;
    }

    private String insertPieceLinkedToOrder(UUID variantId, UUID orderId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'reserved'::piece_status, ?, ?)",
            id, tenantAId, variantId, "PC-" + id, id, orderId, locationAId);
        return id;
    }

    private UUID insertOrderWithItem(UUID variantId, int qty) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, false)",
            orderId, tenantAId, storeAId, orderId.toString());
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (gen_random_uuid(), ?, ?, ?, ?)",
            tenantAId, orderId, variantId, qty);
        return orderId;
    }

    private UUID insertOrderWithCustomer(String name, String phone) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, on_hold, customer_name, customer_phone) " +
            "VALUES (?, ?, ?, ?, '#TEST', 'new'::order_status, false, ?, ?)",
            orderId, tenantAId, storeAId, orderId.toString(), name, phone);
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (gen_random_uuid(), ?, ?, ?, 1)",
            tenantAId, orderId, variantAId);
        return orderId;
    }

    private UUID insertShipment(UUID orderId, String trackingNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state) " +
            "VALUES (?, ?, ?, ?, 'with_courier'::shipment_internal_state)",
            id, tenantAId, orderId, trackingNumber);
        return id;
    }

    private void insertEvent(String pieceId, String eventType,
                             String fromStatus, String toStatus,
                             UUID actorId, UUID locationId,
                             UUID orderId, UUID shipmentId) {
        jdbc.update(
            "INSERT INTO piece_events " +
            "(tenant_id, piece_id, event_type, actor_user_id, location_id, order_id, shipment_id, from_status, to_status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::piece_status, ?::piece_status)",
            tenantAId, pieceId, eventType, actorId, locationId, orderId, shipmentId,
            fromStatus, toStatus);
    }
}
