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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "Physically with Bosta" shared gate.
 *
 * FulfillService.isPhysicallyWithCourier() (wraps the pre-existing
 * hasEverShippedPastCreated() — same query, no second derivation) now gates
 * cancelOrder() and holdOrder(), replacing cancelOrder()'s old orders.status-keyed
 * inline check. PickupSessionService.closeSession() journals the physical handover
 * into shipment_status_history so the gate is true from the moment of handover, even
 * before any Bosta webhook confirms it.
 *
 * a — revert-to-confirm: real with_courier history → cancelOrder() 409. This only
 *     passes because the gate call is in place in cancelOrder(); removing that call
 *     turns this RED.
 * b — positive control (same tenant): pre-pack order, history only 'created' →
 *     cancelOrder() AND holdOrder() both succeed normally.
 * c — manual-journal-only: PickupSessionService.closeSession() journals with_courier
 *     into history with NO Bosta webhook ever arriving → the gate still blocks Cancel
 *     and Hold. Proves the journaling closes the blind window.
 * d — intended behavior delta: orders.status='awaiting_pickup' alone, with NO
 *     forward-leg history past 'created', no longer blocks cancelOrder() (the old
 *     inline check over-blocked on status alone). Pieces release RESERVED→AVAILABLE
 *     via the ordinary pre-pack path.
 * e — holdOrder() is newly gated (with_courier → 409); releaseHold() stays allowed
 *     even when with-courier.
 * f — CRITICAL safety check: after the manual journal row + custody lock from (c),
 *     feeding a late Bosta forward 'created' webhook (code 10) must leave the live
 *     shipment state at 'with_courier' — never rewound, never 'exception'.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhysicallyWithCourierGateTest {

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

    @Autowired FulfillService       fulfillSvc;
    @Autowired PickupSessionService pickupSvc;
    @Autowired InventoryLedger      ledger;
    @Autowired JdbcTemplate         jdbc;
    @Autowired ObjectMapper         mapper;
    @Autowired BostaWebhookJob      webhookJob;
    @Autowired EncryptionService    encryptionService;
    @MockBean  JobScheduler         jobScheduler;
    @MockBean  BostaGateway         bostaGateway;

    UUID tenantId, actorId, storeId, variantId, locationId;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PwcGateTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 'pwc@test.com', 'x', 'owner'::user_role)",
            actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'PwcLoc')",
            locationId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'pwc.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/PWC', 'Pwc Product')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/PWC', 'Default', 'PWC-SKU')",
            variantId, tenantId, productId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM pickup_shipments WHERE tenant_id = ?",              tenantId);
        jdbc.update("DELETE FROM pickups WHERE tenant_id = ?",                       tenantId);
        jdbc.update("DELETE FROM exception_resolutions WHERE tenant_id = ?",         tenantId);
        // shipment_status_history FKs to webhook_events with no CASCADE — must be
        // deleted before webhook_events, not after.
        jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?",       tenantId);
        jdbc.update("DELETE FROM webhook_events WHERE tenant_id = ?",                tenantId);
        jdbc.update("DELETE FROM courier_accounts WHERE tenant_id = ?",              tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?",                  tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?",                   tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?",                   tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?",                        tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?",                     tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?",                        tenantId);
        reset(bostaGateway);
    }

    // ── a: revert-to-confirm ─────────────────────────────────────────────────

    @Test
    void a_revertToConfirm_cancelBlocked_whenHistoryShowsCourierProgress() {
        UUID orderId = seedOrder("A");
        seedForwardShipmentWithHistory(orderId, "PWC-A-AWB", "with_courier", "with_courier");

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> fulfillSvc.cancelOrder(orderId, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            TenantContext.clear();
        }
    }

    // ── b: positive control ──────────────────────────────────────────────────

    @Test
    void b_positiveControl_prePack_historyOnlyCreated_cancelSucceeds() {
        UUID orderId = seedOrder("B-CANCEL");
        seedForwardShipmentWithHistory(orderId, "PWC-B1-AWB", "created", "created");

        TenantContext.set(tenantId);
        try {
            FulfillService.CancelResult result = fulfillSvc.cancelOrder(orderId, actorId);
            assertThat(result.status()).isEqualTo("cancelled");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void b_positiveControl_prePack_historyOnlyCreated_holdSucceeds() {
        UUID orderId = seedOrder("B-HOLD");
        seedForwardShipmentWithHistory(orderId, "PWC-B2-AWB", "created", "created");

        TenantContext.set(tenantId);
        try {
            fulfillSvc.holdOrder(orderId, actorId, "positive control hold");
        } finally {
            TenantContext.clear();
        }

        Boolean onHold = jdbc.queryForObject(
            "SELECT on_hold FROM orders WHERE id = ?", Boolean.class, orderId);
        assertThat(onHold).isTrue();
    }

    // ── c: manual-journal-only (no Bosta webhook ever arrives) ──────────────

    @Test
    void c_manualJournalOnly_noWebhookEver_gateStillBlocksCancelAndHold() {
        UUID orderId = seedOrder("C");
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, variantId);
        String pieceId = receivePiece();

        TenantContext.set(tenantId);
        try {
            FulfillService.ScanResult scanResult = fulfillSvc.scan(orderId, pieceId, actorId);
            assertThat(scanResult.success()).isTrue();
            int packed = fulfillSvc.complete(orderId, actorId);
            assertThat(packed).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }

        String tracking = "9990001112";
        jdbc.update(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'forward')",
            tenantId, orderId, tracking);

        UUID pickupId = pickupSvc.openSession(tenantId, actorId, LocalDate.now(), "morning", "manual journal test");
        PickupSessionService.ScanResult pickupScan = pickupSvc.scan(tenantId, pickupId, actorId, tracking);
        assertThat(pickupScan.outcome()).isEqualTo(PickupSessionService.ScanOutcome.ACCEPTED);
        pickupSvc.closeSession(tenantId, pickupId, actorId);

        // No Bosta webhook was ever fired — the ONLY writer of shipment_status_history
        // here is PickupSessionService.closeSession()'s own manual journal row.
        List<Map<String, Object>> historyRows = jdbc.queryForList(
            "SELECT internal_state, webhook_event_id FROM shipment_status_history WHERE tenant_id = ?",
            tenantId);
        assertThat(historyRows).hasSize(1);
        assertThat(historyRows.get(0).get("internal_state")).isEqualTo("with_courier");
        assertThat(historyRows.get(0).get("webhook_event_id")).isNull();

        assertThat(fulfillSvc.isPhysicallyWithCourier(orderId, tenantId)).isTrue();

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> fulfillSvc.cancelOrder(orderId, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
            assertThatThrownBy(() -> fulfillSvc.holdOrder(orderId, actorId, "should be blocked"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            TenantContext.clear();
        }
    }

    // ── d: intended behavior delta ───────────────────────────────────────────

    @Test
    void d_awaitingPickupStatusAlone_noCourierHistory_cancelNowSucceeds_piecesReleased() {
        UUID orderId = seedOrder("D");
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, variantId);
        String pieceId = receivePiece();
        reservePiece(pieceId, orderId);

        // orders.status reached 'awaiting_pickup' (e.g. AWB-linked) but the forward
        // shipment's history never progressed past 'created' — the exact shape the OLD
        // orders.status-keyed inline check over-blocked on.
        jdbc.update("UPDATE orders SET status = 'awaiting_pickup'::order_status WHERE id = ?", orderId);

        TenantContext.set(tenantId);
        FulfillService.CancelResult result;
        try {
            result = fulfillSvc.cancelOrder(orderId, actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(result.status())
            .as("no longer blocked by orders.status alone — the gate is history-based")
            .isEqualTo("cancelled");
        assertThat(pieceStatus(pieceId)).isEqualTo("available");
        Integer activeAllocs = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE piece_id = ? AND status = 'active'",
            Integer.class, pieceId);
        assertThat(activeAllocs).isZero();
        // FulfillService has no ShopifyGateway dependency at all (grep confirms) — there
        // is nothing for cancelOrder() to call; this DB-only outcome is complete.
    }

    // ── e: Hold newly gated; release stays allowed ───────────────────────────

    @Test
    void e_holdGated_withCourier409_releaseHoldStillAllowed() {
        UUID orderId = seedOrder("E");
        seedForwardShipmentWithHistory(orderId, "PWC-E-AWB", "with_courier", "with_courier");

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> fulfillSvc.holdOrder(orderId, actorId, "should be blocked"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            TenantContext.clear();
        }

        // on_hold was set before courier physically took the order (e.g. held while
        // still packed, then handed over anyway) — releaseHold() must stay allowed.
        jdbc.update("UPDATE orders SET on_hold = true, hold_reason = 'pre-courier hold' WHERE id = ?", orderId);

        TenantContext.set(tenantId);
        try {
            fulfillSvc.releaseHold(orderId, actorId);
        } finally {
            TenantContext.clear();
        }

        Boolean onHold = jdbc.queryForObject(
            "SELECT on_hold FROM orders WHERE id = ?", Boolean.class, orderId);
        assertThat(onHold).isFalse();
    }

    // ── f: CRITICAL — journaling must not corrupt subsequent webhooks ───────

    @Test
    void f_manualJournalPlusCustodyLock_lateCreatedWebhook_staysWithCourier_notException() {
        UUID orderId = seedOrder("F");
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, variantId);
        String pieceId = receivePiece();

        TenantContext.set(tenantId);
        try {
            fulfillSvc.scan(orderId, pieceId, actorId);
            fulfillSvc.complete(orderId, actorId);
        } finally {
            TenantContext.clear();
        }

        String tracking = "9990002223";
        UUID shipmentId = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'forward') RETURNING id",
            UUID.class, tenantId, orderId, tracking);

        UUID pickupId = pickupSvc.openSession(tenantId, actorId, LocalDate.now(), "morning", "webhook safety test");
        pickupSvc.scan(tenantId, pickupId, actorId, tracking);
        pickupSvc.closeSession(tenantId, pickupId, actorId);

        // Preconditions: custody lock + manual journal row both in place before any webhook.
        assertThat(jdbc.queryForObject(
            "SELECT custody_locked_by_scan FROM shipments WHERE id = ?", Boolean.class, shipmentId))
            .isTrue();
        assertThat(jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE id = ?", String.class, shipmentId))
            .isEqualTo("with_courier");

        setupCourierAccount("pwc-key");
        ObjectNode raw = mapper.createObjectNode();
        raw.put("trackingNumber", tracking);
        raw.put("updatedAt", "2026-09-14T09:00:00.000Z");
        raw.putObject("state").put("code", 10);
        raw.putObject("type").put("value", "SEND");

        BostaDelivery delivery = new BostaDelivery(tracking, 10, "SEND", 0, "#PWC-F", null, raw);
        when(bostaGateway.fetchDelivery(anyString(), eq(tracking))).thenReturn(delivery);

        Long eventId = insertWebhookEvent(tracking, 10, "2026-09-14T09:00:00.000Z");
        webhookJob.process(eventId, tenantId);

        String liveState = jdbc.queryForObject(
            "SELECT internal_state FROM shipments WHERE id = ?", String.class, shipmentId);
        assertThat(liveState)
            .as("custody-lock branch must win — a late 'created' webhook must not rewind " +
                "this shipment, and the manual journal row must not push it into 'exception' either")
            .isEqualTo("with_courier");

        assertThat(webhookStatus(eventId)).isEqualTo("processed");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedOrder(String suffix) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/PWC-" + suffix, "#PWC-" + suffix);
    }

    private void seedForwardShipmentWithHistory(UUID orderId, String tracking, String liveState, String historyState) {
        UUID shipmentId = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward') RETURNING id",
            UUID.class, tenantId, orderId, tracking, liveState);
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, variantId);
        jdbc.update(
            "INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) VALUES (?, ?, ?)",
            tenantId, shipmentId, historyState);
    }

    private String receivePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at, last_user_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?, now(), ?)",
            id, tenantId, variantId, "PWC-" + id.substring(id.length() - 8), id, locationId, actorId);
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, ?, NULL, 'available'::piece_status)",
            tenantId, id, actorId, locationId);
        return id;
    }

    private void reservePiece(String pieceId, UUID orderId) {
        UUID itemId = jdbc.queryForObject(
            "SELECT id FROM order_items WHERE order_id = ? AND tenant_id = ? LIMIT 1",
            UUID.class, orderId, tenantId);
        TenantContext.set(tenantId);
        ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.RESERVED,
            "scan", actorId, TransitionContext.forOrder(orderId, orderId));
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status, allocated_by, allocated_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'active', ?, now())",
            tenantId, itemId, pieceId, actorId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private void setupCourierAccount(String rawApiKey) {
        jdbc.update(
            "INSERT INTO courier_accounts " +
            "    (tenant_id, provider, api_key_encrypted, webhook_secret, status) " +
            "VALUES (?, 'bosta', ?, 'pwc-hash', 'active')",
            tenantId, encryptionService.encrypt(rawApiKey));
    }

    private Long insertWebhookEvent(String trackingNumber, int stateCode, String updatedAt) {
        String payloadJson = String.format(
            "{\"trackingNumber\":\"%s\",\"state\":%d,\"type\":\"SEND\",\"updatedAt\":\"%s\"}",
            trackingNumber, stateCode, updatedAt);
        return TenantContext.runAs(tenantId, () ->
            jdbc.queryForObject(
                "INSERT INTO webhook_events " +
                "    (source, tenant_id, topic, payload, status, received_at) " +
                "VALUES ('bosta', ?, 'delivery_update', ?::jsonb, 'pending', now()) " +
                "RETURNING id",
                Long.class, tenantId, payloadJson));
    }

    private String webhookStatus(Long id) {
        return jdbc.queryForObject(
            "SELECT status FROM webhook_events WHERE id = ?", String.class, id);
    }
}
