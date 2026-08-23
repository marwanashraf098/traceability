package com.traceability;

import com.traceability.inventory.*;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * 2026-08-24 build (Step 0 Spec 2, Part C): scan()/complete() re-verify eligibility
 * server-side via shipment_status_history — never trust the queue list filter alone.
 *
 * wp1 — scan() on an order whose forward shipment's LIVE internal_state was rewound to
 *       'created' (the corrupted incident shape) but shipment_status_history still has a
 *       with_courier row → rejected ALREADY_SHIPPED. RED before Part C (today: succeeds).
 * wp2 — complete() on the same corrupted shape, order fully pre-scanned → 409 CONFLICT.
 *       RED before Part C (today: succeeds).
 * wp3 — positive control: a shipment whose history has ONLY ever been 'created' (AWB
 *       linked, never dispatched — order #2212110474's real shape) → scan() and complete()
 *       both succeed normally. Must stay GREEN — proves the guard checks for progress
 *       beyond created, not merely presence of a shipment/history.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlreadyShippedGuardTest {

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

    @Autowired FulfillService  fulfillSvc;
    @Autowired InventoryLedger ledger;
    @Autowired JdbcTemplate    jdbc;
    @MockBean  JobScheduler    jobScheduler;

    UUID tenantId, actorId, storeId, variantId, locationId;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AlreadyShippedTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 'wp@test.com', 'x', 'owner'::user_role)",
            actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'WpLoc')",
            locationId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'wp.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/WP', 'Wp Product')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/WP', 'Default', 'WP-SKU')",
            variantId, tenantId, productId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?",                 tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?",                  tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?",                  tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?",                       tenantId);
        jdbc.update("DELETE FROM shipment_status_history WHERE tenant_id = ?",      tenantId);
        jdbc.update("DELETE FROM shipments WHERE tenant_id = ?",                    tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?",                       tenantId);
    }

    // wp1: scan() rejected ALREADY_SHIPPED when history shows progress past 'created',
    // even though the live internal_state column reads 'created' (the corrupted shape).
    @Test
    void wp1_scan_rejectsAlreadyShipped_whenHistoryShowsProgressPastCreated() {
        UUID orderId = seedOrder("WP1");
        seedForwardShipment(orderId, "WP1-AWB", "created", "with_courier"); // corrupted: live=created, history has with_courier
        String piece = receivePiece();

        TenantContext.set(tenantId);
        FulfillService.ScanResult result;
        try {
            result = fulfillSvc.scan(orderId, piece, actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("ALREADY_SHIPPED");

        // Nothing was allocated/transitioned
        assertThat(pieceStatus(piece)).isEqualTo("available");
        Integer allocCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE piece_id = ?", Integer.class, piece);
        assertThat(allocCount).isZero();
    }

    // wp2: complete() rejected 409 CONFLICT on the same corrupted shape, order fully scanned.
    @Test
    void wp2_complete_rejects409_whenHistoryShowsProgressPastCreated() {
        UUID orderId = seedOrder("WP2");
        seedForwardShipment(orderId, "WP2-AWB", "created", "with_courier");
        String piece = receivePiece();
        reservePiece(piece, orderId);

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> fulfillSvc.complete(orderId, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            TenantContext.clear();
        }

        // Piece must still be reserved, not packed — nothing partially applied.
        assertThat(pieceStatus(piece)).isEqualTo("reserved");
        String orderStatus = jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("new");
    }

    // wp3: positive control — shipment history has ONLY 'created' rows → scan() and
    // complete() both succeed normally.
    @Test
    void wp3_neverShipped_scanAndCompleteSucceed() {
        UUID orderId = seedOrder("WP3");
        seedForwardShipment(orderId, "WP3-AWB", "created", "created"); // history: only 'created'
        String piece = receivePiece();

        TenantContext.set(tenantId);
        FulfillService.ScanResult scanResult;
        try {
            scanResult = fulfillSvc.scan(orderId, piece, actorId);
        } finally {
            TenantContext.clear();
        }
        assertThat(scanResult.success()).isTrue();
        assertThat(scanResult.code()).isEqualTo("SCANNED");

        TenantContext.set(tenantId);
        try {
            int packed = fulfillSvc.complete(orderId, actorId);
            assertThat(packed).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).isEqualTo("packed");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID seedOrder(String suffix) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId, "gid://shopify/Order/" + suffix, "#" + suffix);
    }

    /**
     * Seeds a forward shipment whose LIVE internal_state is {@code liveState} but whose
     * shipment_status_history carries exactly one row at {@code historyState} — lets tests
     * deliberately break the lockstep the way the real incident did (live column rewound to
     * 'created' by the mapper bug, history still holding the truth).
     */
    private void seedForwardShipment(UUID orderId, String tracking, String liveState, String historyState) {
        UUID shipmentId = jdbc.queryForObject(
            "INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, " +
            "    internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, ?::shipment_internal_state, 'forward') RETURNING id",
            UUID.class, tenantId, orderId, tracking, liveState);
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, variantId);
        jdbc.update(
            "INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state) " +
            "VALUES (?, ?, ?)",
            tenantId, shipmentId, historyState);
    }

    private String receivePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at, last_user_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?, now(), ?)",
            id, tenantId, variantId, "WP-" + id.substring(id.length() - 8), id, locationId, actorId);
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
}
