package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.bosta.*;
import com.traceability.inventory.ShipmentLinkService;
import com.traceability.inventory.UlidGenerator;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * FR-22.8 — Mode B guard: transfers never touch Bosta, and Bosta never touches
 * out_on_transfer pieces.
 *
 * Structural confirmation (see FR-22.8 report, not re-derived here): grepping
 * TransferService/TransferController/TransferException finds zero references to
 * integrations.bosta, BostaWebhookJob, ShipmentLinkService, or any shipment/delivery/pickup/
 * courier vocabulary — the transfer paths cannot create or link a Bosta delivery because
 * they never call into that package at all.
 *
 * This class proves the REVERSE direction: Bosta-side code must not move an out_on_transfer
 * piece, on both write paths (webhook ingestion and manual link). No new guard code was
 * added for this — mirrors the self-pickup precedent, which also has no explicit
 * "is this piece self-pickup / out-on-transfer" check anywhere in BostaWebhookJob or
 * ShipmentLinkService (confirmed by grep — see FR-22.8 report). Both paths already rely on
 * state-aware querying / InventoryLedger.ALLOWED alone:
 *   - BostaWebhookJob reads the piece's CURRENT status and calls ledger.transition() with it;
 *     InventoryLedger.ALLOWED has no out_on_transfer:* entry for any Bosta-driven target
 *     (with_courier, delivered, return_in_transit, etc.), so transition() throws
 *     IllegalTransitionException — caught internally (log.warn + continue), never propagates.
 *   - ShipmentLinkService.transitionPackedPieces() pre-filters `p.status = 'packed'` in SQL,
 *     so an out_on_transfer piece is never even selected — an earlier, stronger guard than
 *     the ALLOWED check, but still no piece-type-aware code, purely state-driven.
 *
 * Honesty check on "routed to the exceptions list": traced BostaWebhookJob's control flow
 * end to end — after the per-piece catch-log-continue loop, the webhook unconditionally
 * proceeds to mark webhook_events.status='processed' (no error, no distinct marker), and
 * ExceptionService's ~15 detectors are all separate queries against shipment/delivery state,
 * none of which would surface a single skipped piece transition. This is NOT a "silent drop
 * or a stacktrace" (the piece cleanly stays put, the webhook completes normally, nothing
 * crashes) — but it is also not literally written to a distinguishable exceptions-list row.
 * This matches self-pickup's own existing, identical treatment exactly (same catch blocks,
 * same log.warn, no self-pickup-specific exceptions-list entry either) — captured here as
 * the honestly-reported current behavior, not silently upgraded beyond what self-pickup gets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferModeBGuardTest {

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

    @Autowired JdbcTemplate         jdbc;
    @Autowired ObjectMapper         mapper;
    @Autowired EncryptionService    encryptionService;
    @Autowired BostaWebhookJob      bostaWebhookJob;
    @Autowired ShipmentLinkService  shipmentLinkService;
    @MockBean  BostaGateway         bostaGateway;
    @MockBean  JobScheduler         jobScheduler;

    UUID tenantId;
    UUID storeId;
    UUID variantId;

    @BeforeAll
    void setup() {
        tenantId  = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Mode B Guard Co')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'modeb-owner@test.local', 'h', 'owner')",
            UUID.randomUUID(), tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'modeb-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'PROD-MB', 'Mode B Product')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-MB', 'Mode B Variant')",
            variantId, tenantId, productId);
    }

    @BeforeEach
    void cleanup() {
        jdbc.update("DELETE FROM unlinked_bosta_deliveries WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events              WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM allocations                WHERE tenant_id = ?", tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM shipments                  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items                WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces                     WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders                     WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM webhook_events              WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM courier_accounts            WHERE tenant_id = ?", tenantId);
    }

    // -----------------------------------------------------------------------
    // Bosta webhook path (BostaWebhookJob)
    // -----------------------------------------------------------------------

    @Test
    void bostaWebhook_outOnTransferPiece_notTransitioned_otherPieceOnSameOrderStillMoves() {
        String apiKey = "modeb-api-key-1";
        setupCourierAccount(encryptionService.encrypt(apiKey));

        UUID orderId     = createOrder("EXT-MB-001");
        UUID orderItemId = createOrderItem(orderId);
        // The out_on_transfer piece — structurally this piece would never carry a live
        // allocation under normal transfer/pick invariants (scanOut() requires 'available',
        // which precludes an active allocation) — the stale allocation here is a deliberate
        // fixture to exercise BostaWebhookJob's guard directly, not a naturally-reachable state.
        String xferPiece = createPiece("out_on_transfer");
        createAllocation(orderItemId, xferPiece);
        // Positive control on the SAME webhook/order: an ordinary with_courier piece must
        // still move normally — proves the guard is specific to the out_on_transfer piece,
        // not an accidental blanket no-op for the whole webhook.
        String normalPiece = createPiece("with_courier");
        createAllocation(orderItemId, normalPiece);

        String trackingNumber = "BOS-MB-001";
        createShipment(orderId, trackingNumber);
        Long webhookId = insertWebhookEvent(trackingNumber, 45, "2026-08-04T12:00:00Z");

        when(bostaGateway.fetchDelivery(eq(apiKey), eq(trackingNumber)))
            .thenReturn(new BostaDelivery(
                trackingNumber, 45, "ALL", 1, "EXT-MB-001", null, mapper.createObjectNode()));

        bostaWebhookJob.process(webhookId, tenantId);

        // The out_on_transfer piece: untouched, zero events, no crash.
        assertThat(pieceStatus(xferPiece)).isEqualTo("out_on_transfer");
        assertThat(courierUpdateEventCount(xferPiece)).isEqualTo(0);

        // Positive control: the ordinary piece on the same webhook still moved.
        assertThat(pieceStatus(normalPiece)).isEqualTo("delivered");
        assertThat(courierUpdateEventCount(normalPiece)).isEqualTo(1);

        // The webhook itself completes normally — no stacktrace, no failed status, no
        // silent drop of the whole event just because one piece couldn't move.
        assertThat(webhookStatus(webhookId)).isEqualTo("processed");
    }

    // -----------------------------------------------------------------------
    // Manual link path (ShipmentLinkService.manualLink)
    // -----------------------------------------------------------------------

    @Test
    void manualLink_outOnTransferPiece_notSelected_otherPieceOnSameOrderStillMoves() {
        UUID orderId     = createOrder("EXT-MB-002");
        UUID orderItemId = createOrderItem(orderId);
        String xferPiece = createPiece("out_on_transfer");
        createAllocation(orderItemId, xferPiece);
        // Positive control: an ordinary packed piece on the same order must still move to
        // awaiting_pickup — proves manualLink() isn't just failing outright.
        String packedPiece = createPiece("packed");
        createAllocation(orderItemId, packedPiece);

        String trackingNumber = "BOS-MB-002";
        Long unlinkedId = jdbc.queryForObject(
            "INSERT INTO unlinked_bosta_deliveries " +
            "  (tenant_id, tracking_number, business_reference, bosta_state_code, " +
            "   bosta_order_type, match_reason, resolved) " +
            "VALUES (?, ?, '#EXT-MB-002', 41, 'SEND', 'NO_MATCH', false) RETURNING id",
            Long.class, tenantId, trackingNumber);

        TenantContext.runAs(tenantId, () ->
            shipmentLinkService.manualLink(unlinkedId, orderId, null));

        // out_on_transfer piece: transitionPackedPieces()'s own SQL requires
        // p.status = 'packed', so this piece is never even selected — untouched, zero events.
        assertThat(pieceStatus(xferPiece)).isEqualTo("out_on_transfer");
        assertThat(trackingLinkedEventCount(xferPiece)).isEqualTo(0);

        // Positive control: the ordinary packed piece on the same order still moved.
        assertThat(pieceStatus(packedPiece)).isEqualTo("awaiting_pickup");
        assertThat(trackingLinkedEventCount(packedPiece)).isEqualTo(1);

        // manualLink() itself completes normally (no exception propagated) and still
        // resolves the unlinked row — its other effects don't depend on every piece moving.
        Boolean resolved = jdbc.queryForObject(
            "SELECT resolved FROM unlinked_bosta_deliveries WHERE id = ?", Boolean.class, unlinkedId);
        assertThat(resolved).isTrue();
    }

    // ---- helpers --------------------------------------------------------------

    private void setupCourierAccount(String encryptedKey) {
        jdbc.update(
            "INSERT INTO courier_accounts(tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES(?, 'bosta', ?, 'test-hash', 'active')",
            tenantId, encryptedKey);
    }

    private UUID createOrder(String extId) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES(?, ?, ?, ?, 'new'::order_status, now()) RETURNING id",
            UUID.class, tenantId, storeId, extId, "#" + extId);
    }

    private UUID createOrderItem(UUID orderId) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) " +
            "VALUES(?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, variantId);
    }

    private String createPiece(String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES(?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status)",
            id, tenantId, variantId, "PC-" + id, id, status);
        return id;
    }

    private void createAllocation(UUID orderItemId, String pieceId) {
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES(?, ?, ?, 'packed'::allocation_status)",
            tenantId, orderItemId, pieceId);
    }

    private void createShipment(UUID orderId, String trackingNumber) {
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, tracking_number) VALUES(?, ?, ?)",
            tenantId, orderId, trackingNumber);
    }

    private Long insertWebhookEvent(String trackingNumber, int state, String updatedAt) {
        String payload = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"updatedAt\":\"%s\"}",
            trackingNumber, state, updatedAt);
        return jdbc.queryForObject(
            "INSERT INTO webhook_events(source,tenant_id,topic,payload,status,received_at) " +
            "VALUES('bosta',?,'delivery_update',?::jsonb,'pending',now()) RETURNING id",
            Long.class, tenantId, payload);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private int courierUpdateEventCount(String pieceId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'courier_update'",
            Integer.class, pieceId);
        return n == null ? 0 : n;
    }

    private int trackingLinkedEventCount(String pieceId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'tracking_linked'",
            Integer.class, pieceId);
        return n == null ? 0 : n;
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }
}
