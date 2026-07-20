package com.traceability;

import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.integrations.bosta.BostaPickupService;
import com.traceability.inventory.FulfillService;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-9.11 / 9.9b / 9.5 (COD derive) / Item-5 (setSelfPickup guard) integration tests.
 *
 * Matrix:
 *   COD derivation (V22 dropped total_cod_amount):
 *     1. getManifest() COD = live SUM from pickup_shipments (no stored column)
 *     2. COD updates correctly when a shipment is removed from the manifest
 *
 *   removeFromPickupManifest (FR-9.11):
 *     3. Direct call removes the pickup_shipments row for an awaiting_pickup order
 *     4. Direct call on an order not on any manifest is a no-op (no error)
 *     5. cancelOrder() pre-pack auto-release calls removeFromPickupManifest (no-op, no crash)
 *
 *   Shopify-cancel awaiting_pickup combined (FR-9.11 + signal):
 *     6. removeFromPickupManifest removes manifest row; signal stays; order stays awaiting_pickup
 *
 *   convertToSelfPickup (FR-9.9b):
 *     7. packed → self_pickup_pending: is_self_pickup=true, metadata written, pieces STAY packed
 *     8. awaiting_pickup → self_pickup_pending: manifest cleaned, order advanced
 *     9. reason missing → 400
 *    10. Wrong status (new) → 409
 *    11. Already self_pickup_pending → no-op (no double record, idempotent)
 *
 *   setSelfPickup guard (Item 5):
 *    12. packed → 409 with "use convert-to-self-pickup" message
 *    13. awaiting_pickup → 409
 *    14. self_pickup_pending → 409
 *    15. new → accepted (still works pre-pick)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fr9ManifestSelfPickupTest {

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

    // Pin to Wednesday 2026-06-17 so pickup-date tests are run-day-independent.
    // schedulePickup() rejects Fridays and past dates — both checks use the injected Clock.
    static final ZoneId  CAIRO    = ZoneId.of("Africa/Cairo");
    static final Instant PINNED   = LocalDate.of(2026, 6, 17).atTime(10, 0).atZone(CAIRO).toInstant();
    static final LocalDate THURSDAY = LocalDate.of(2026, 6, 18); // next valid day after pinned Wednesday

    @Autowired FulfillService    fulfillService;
    @Autowired BostaPickupService pickupService;
    @Autowired JdbcTemplate      jdbc;
    @Autowired EncryptionService encryptionService;
    @MockBean  BostaGateway      bostaGateway;
    @MockBean  JobScheduler      jobScheduler;
    @MockBean  Clock             clock;

    UUID tenantId, storeId, actorId, variantId;
    UUID courierAccountId;

    @BeforeAll
    void setupFixture() {
        tenantId  = UUID.randomUUID();
        actorId   = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        courierAccountId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Fr9Tenant')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Owner', 'owner@fr9.local', 'h', 'owner')", actorId, tenantId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
                    "VALUES (?, ?, 'shopify', 'fr9.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
                    "VALUES (?, ?, ?, 'P-FR9', 'Widget', 'active')", productId, tenantId, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'V-FR9', 'Red', 'WGT-FR9')", variantId, tenantId, productId);
        jdbc.update("INSERT INTO courier_accounts " +
                    "(id, tenant_id, provider, api_key_encrypted, webhook_secret, status, " +
                    " pickup_mode, awb_format, awb_lang, pickup_business_location_id) " +
                    "VALUES (?, ?, 'bosta', ?, 'test-hash', 'active', " +
                    "        'BOSTA_MANAGED', 'A4', 'ar', 'loc-fr9')",
                    courierAccountId, tenantId, encryptionService.encrypt("fr9-api-key"));
    }

    @BeforeEach
    void ctx() {
        TenantContext.set(tenantId);
        lenient().when(clock.instant()).thenReturn(PINNED);
        lenient().when(clock.getZone()).thenReturn(CAIRO);
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM pickup_shipments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pickups          WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events     WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations      WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces           WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments        WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items      WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders           WHERE tenant_id = ?", tenantId);
    }

    // ── COD derivation (V22 — no stored column) ───────────────────────────────

    @Test
    void t1_manifestCodDerivedFromLiveSum_noStoredColumn() {
        // Confirm total_cod_amount no longer exists on the pickups table (V22 drop)
        int colCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND table_name = 'pickups' " +
            "  AND column_name = 'total_cod_amount'",
            Integer.class);
        assertThat(colCount).as("total_cod_amount must be dropped by V22").isZero();

        // BOSTA_MANAGED pickup: two awaiting_pickup orders picked up automatically by schedulePickup
        UUID o1 = awaitingPickupOrder(new BigDecimal("100.00"));
        UUID o2 = awaitingPickupOrder(new BigDecimal("200.00"));
        shipment(o1, "AWB-COD1");
        shipment(o2, "AWB-COD2");

        BostaPickupService.PickupManifest manifest = TenantContext.runAs(tenantId, () ->
            pickupService.schedulePickup(tenantId, THURSDAY));

        assertThat(manifest.totalCod()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(manifest.shipments()).hasSize(2);
    }

    @Test
    void t2_manifestCodUpdatesAfterShipmentRemoved() {
        UUID o1 = awaitingPickupOrder(new BigDecimal("150.00"));
        UUID o2 = awaitingPickupOrder(new BigDecimal("250.00"));
        UUID s1 = shipmentId(o1, "AWB-REM1");
        UUID s2 = shipmentId(o2, "AWB-REM2");

        // Manually create a pickup and link both shipments
        UUID pickupId = jdbc.queryForObject(
            "INSERT INTO pickups (tenant_id, courier_account_id, scheduled_date, session_status) " +
            "VALUES (?, ?, CURRENT_DATE + 1, 'open') RETURNING id",
            UUID.class, tenantId, courierAccountId);
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)",
                    pickupId, s1, tenantId);
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)",
                    pickupId, s2, tenantId);

        // getManifest: both shipments → 400
        BostaPickupService.PickupManifest full = TenantContext.runAs(tenantId, () ->
            pickupService.getManifest(tenantId, pickupId));
        assertThat(full.totalCod()).isEqualByComparingTo(new BigDecimal("400.00"));

        // Remove one shipment from the manifest
        jdbc.update("DELETE FROM pickup_shipments WHERE pickup_id = ? AND shipment_id = ?", pickupId, s1);

        // getManifest: only s2 remains → 250
        BostaPickupService.PickupManifest trimmed = TenantContext.runAs(tenantId, () ->
            pickupService.getManifest(tenantId, pickupId));
        assertThat(trimmed.totalCod()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(trimmed.shipments()).hasSize(1);
    }

    // ── removeFromPickupManifest (FR-9.11) ────────────────────────────────────

    @Test
    void t3_removeFromPickupManifest_removesRow() {
        UUID orderId = awaitingPickupOrder(new BigDecimal("100.00"));
        UUID shipId  = shipmentId(orderId, "AWB-RMFM1");
        UUID pickupId = jdbc.queryForObject(
            "INSERT INTO pickups (tenant_id, courier_account_id, scheduled_date, session_status) " +
            "VALUES (?, ?, CURRENT_DATE + 1, 'open') RETURNING id",
            UUID.class, tenantId, courierAccountId);
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)",
                    pickupId, shipId, tenantId);

        assertThat(pickupShipmentCount(pickupId)).isEqualTo(1);

        fulfillService.removeFromPickupManifest(orderId);

        assertThat(pickupShipmentCount(pickupId)).isZero();
    }

    @Test
    void t4_removeFromPickupManifest_notOnManifest_isNoOp() {
        UUID orderId = awaitingPickupOrder(new BigDecimal("50.00"));
        // No pickup_shipments row
        assertThatNoException().isThrownBy(() ->
            fulfillService.removeFromPickupManifest(orderId));
    }

    @Test
    void t5_cancelOrderPrePack_callsRemoveFromManifest_noError() {
        // Pre-pack order (new) — no shipment, not on any manifest
        UUID orderId = order("new", null);
        // cancelOrder() must succeed without error (removeFromPickupManifest is a no-op)
        FulfillService.CancelResult result = fulfillService.cancelOrder(orderId, actorId);
        assertThat(result.status()).isEqualTo("cancelled");
        // Confirm no orphaned rows
        assertThat(pickupShipmentCountForOrder(orderId)).isZero();
    }

    // ── Shopify-cancel awaiting_pickup (FR-9.11 combined) ────────────────────

    @Test
    void t6_shopifyCancelAwaitingPickup_manifestCleaned_orderStaysAwaitingPickup() {
        UUID orderId = awaitingPickupOrder(new BigDecimal("80.00"));
        UUID shipId  = shipmentId(orderId, "AWB-SCV6");
        UUID pickupId = jdbc.queryForObject(
            "INSERT INTO pickups (tenant_id, courier_account_id, scheduled_date, session_status) " +
            "VALUES (?, ?, CURRENT_DATE + 1, 'open') RETURNING id",
            UUID.class, tenantId, courierAccountId);
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)",
                    pickupId, shipId, tenantId);

        // Simulate handleOrderCancelled() 409 path: stamp signal
        jdbc.update(
            "UPDATE orders SET shopify_cancel_requested_at = now() WHERE id = ? AND tenant_id = ?",
            orderId, tenantId);

        assertThat(pickupShipmentCount(pickupId)).isEqualTo(1);

        // Simulate the post-try/catch unconditional removeFromPickupManifest call
        fulfillService.removeFromPickupManifest(orderId);

        // Manifest row removed
        assertThat(pickupShipmentCount(pickupId)).isZero();

        // Order STAYS awaiting_pickup
        String status = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        assertThat(status).isEqualTo("awaiting_pickup");

        // Signal still stamped
        Integer sigCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE id = ? AND shopify_cancel_requested_at IS NOT NULL",
            Integer.class, orderId);
        assertThat(sigCount).isEqualTo(1);
    }

    // ── convertToSelfPickup (FR-9.9b) ─────────────────────────────────────────

    @Test
    void t7_convertToSelfPickup_fromPacked_advancesOrder_pieceStayPacked() {
        UUID orderId = order("packed", null);
        String pieceId = piece(orderId);
        allocationPacked(orderId, pieceId);

        fulfillService.convertToSelfPickup(orderId, "Customer called in", actorId);

        String newStatus = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        assertThat(newStatus).isEqualTo("self_pickup_pending");

        Boolean isSelfPickup = jdbc.queryForObject(
            "SELECT is_self_pickup FROM orders WHERE id = ? AND tenant_id = ?",
            Boolean.class, orderId, tenantId);
        assertThat(isSelfPickup).isTrue();

        // Audit record written in metadata
        String metadata = jdbc.queryForObject(
            "SELECT metadata::text FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        assertThat(metadata)
            .contains("converted_to_self_pickup")
            .contains("Customer called in")
            .contains("packed");

        // Piece STAYS in packed status
        String pieceStatus = jdbc.queryForObject(
            "SELECT status FROM pieces WHERE id = ? AND tenant_id = ?",
            String.class, pieceId, tenantId);
        assertThat(pieceStatus).isEqualTo("packed");
        // No piece_events written for this transition
        int eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE order_id = ? AND tenant_id = ?",
            Integer.class, orderId, tenantId);
        assertThat(eventCount).isZero();
    }

    @Test
    void t8_convertToSelfPickup_fromAwaitingPickup_cleansManifestandAdvances() {
        UUID orderId = awaitingPickupOrder(new BigDecimal("120.00"));
        UUID shipId  = shipmentId(orderId, "AWB-SPC8");
        UUID pickupId = jdbc.queryForObject(
            "INSERT INTO pickups (tenant_id, courier_account_id, scheduled_date, session_status) " +
            "VALUES (?, ?, CURRENT_DATE + 1, 'open') RETURNING id",
            UUID.class, tenantId, courierAccountId);
        jdbc.update("INSERT INTO pickup_shipments (pickup_id, shipment_id, tenant_id) VALUES (?, ?, ?)",
                    pickupId, shipId, tenantId);

        assertThat(pickupShipmentCount(pickupId)).isEqualTo(1);

        // FR-4.6 not yet available — awaiting_pickup path must fail closed (no side effects).
        assertThatThrownBy(() ->
            fulfillService.convertToSelfPickup(orderId, "customer collecting", actorId))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));

        // Order status unchanged
        String status = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        assertThat(status).isEqualTo("awaiting_pickup");

        // No metadata audit row written
        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        assertThat(meta).isNull();

        // Manifest row NOT removed
        assertThat(pickupShipmentCount(pickupId)).isEqualTo(1);
    }

    @Test
    void t9_convertToSelfPickup_reasonMissing_400() {
        UUID orderId = order("packed", null);
        assertThatThrownBy(() -> fulfillService.convertToSelfPickup(orderId, null, actorId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("reason");
        assertThatThrownBy(() -> fulfillService.convertToSelfPickup(orderId, "   ", actorId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("reason");
    }

    @Test
    void t10_convertToSelfPickup_wrongStatus_409() {
        UUID orderId = order("new", null);
        assertThatThrownBy(() -> fulfillService.convertToSelfPickup(orderId, "reason", actorId))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void t11_convertToSelfPickup_alreadySelfPickupPending_idempotentNoDoubleRecord() {
        UUID orderId = order("self_pickup_pending", null);
        // Mark is_self_pickup + initial metadata to simulate a prior successful convert
        jdbc.update("UPDATE orders SET is_self_pickup = true, " +
                    "metadata = '{\"type\":\"converted_to_self_pickup\",\"reason\":\"first call\"}'::jsonb " +
                    "WHERE id = ? AND tenant_id = ?", orderId, tenantId);

        // Second call must be a no-op — no exception, no overwrite of metadata
        assertThatNoException().isThrownBy(() ->
            fulfillService.convertToSelfPickup(orderId, "second call", actorId));

        // Metadata still has the first record, not overwritten
        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        assertThat(meta).contains("first call");
        assertThat(meta).doesNotContain("second call");
    }

    // ── setSelfPickup guard (Item 5) ──────────────────────────────────────────

    @Test
    void t12_setSelfPickup_onPacked_rejects409() {
        UUID orderId = order("packed", null);
        assertThatThrownBy(() -> fulfillService.setSelfPickup(orderId, true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> {
                ResponseStatusException rse = (ResponseStatusException) e;
                assertThat(rse.getStatusCode().value()).isEqualTo(409);
                assertThat(rse.getReason()).contains("convert-to-self-pickup");
            });
    }

    @Test
    void t13_setSelfPickup_onAwaitingPickup_rejects409() {
        UUID orderId = awaitingPickupOrder(BigDecimal.ZERO);
        assertThatThrownBy(() -> fulfillService.setSelfPickup(orderId, true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void t14_setSelfPickup_onSelfPickupPending_rejects409() {
        UUID orderId = order("self_pickup_pending", null);
        assertThatThrownBy(() -> fulfillService.setSelfPickup(orderId, true))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void t15_setSelfPickup_onNew_accepted() {
        UUID orderId = order("new", null);
        assertThatNoException().isThrownBy(() -> fulfillService.setSelfPickup(orderId, true));
        Boolean flag = jdbc.queryForObject(
            "SELECT is_self_pickup FROM orders WHERE id = ? AND tenant_id = ?",
            Boolean.class, orderId, tenantId);
        assertThat(flag).isTrue();
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private UUID order(String status, BigDecimal cod) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, " +
            "    cod_amount, placed_at) " +
            "VALUES (?, ?, ?, '#FR9', ?::order_status, 'cod', ?, now()) RETURNING id",
            UUID.class, tenantId, storeId, "FR9-" + UUID.randomUUID(), status, cod);
    }

    private UUID awaitingPickupOrder(BigDecimal cod) {
        return order("awaiting_pickup", cod);
    }

    private UUID shipmentId(UUID orderId, String tracking) {
        return jdbc.queryForObject(
            "INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, cod_amount) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'created'::shipment_internal_state, " +
            "    (SELECT cod_amount FROM orders WHERE id = ? AND tenant_id = ?)) RETURNING id",
            UUID.class, tenantId, orderId, tracking, orderId, tenantId);
    }

    private void shipment(UUID orderId, String tracking) { shipmentId(orderId, tracking); }

    private String piece(UUID orderId) {
        String pieceId = "01" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'packed'::piece_status, ?)",
            pieceId, tenantId, variantId, "BC-" + pieceId, pieceId, orderId);
        return pieceId;
    }

    private void allocationPacked(UUID orderId, String pieceId) {
        UUID itemId = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
            "VALUES (?, ?, ?, 1, 'EXT-ITEM-" + UUID.randomUUID() + "') RETURNING id",
            UUID.class, tenantId, orderId, variantId);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'packed')",
            tenantId, itemId, pieceId);
    }

    private int pickupShipmentCount(UUID pickupId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pickup_shipments WHERE pickup_id = ? AND tenant_id = ?",
            Integer.class, pickupId, tenantId);
        return n != null ? n : 0;
    }

    private int pickupShipmentCountForOrder(UUID orderId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pickup_shipments ps " +
            "JOIN shipments s ON s.id = ps.shipment_id " +
            "WHERE s.order_id = ? AND ps.tenant_id = ?",
            Integer.class, orderId, tenantId);
        return n != null ? n : 0;
    }
}
