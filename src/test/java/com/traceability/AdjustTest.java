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
 * FR-13 integration tests: manual piece adjustments.
 *
 * adj1 — available→lost writes adjusted event + metadata + audit
 * adj2 — reason=other without note → 400
 * adj3 — reserved piece → 409 PIECE_COMMITTED naming the blocking order
 * adj4 — packed piece → 409 PIECE_COMMITTED naming the blocking order
 * adj5 — releaseForAdjust: frees allocation + piece→available
 * adj6 — found it (lost→available): appends event; original lost event preserved
 * adj7 — damaged/destroyed → available → 409 (terminal)
 * adj8 — tenant isolation: cross-tenant piece → 404
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdjustTest {

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

    @Autowired PieceAdjustService adjustSvc;
    @Autowired InventoryLedger    ledger;
    @Autowired JdbcTemplate       jdbc;
    @MockBean  JobScheduler       jobScheduler;

    UUID tenantId, tenantB, actorId, storeId, variantId, locationId;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        tenantB    = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AdjTenantA')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'AdjTenantB')", tenantB);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 'adj@test.com', 'x', 'owner'::user_role)",
            actorId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'AdjLoc')", locationId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'adj.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/ADJ', 'Adj Product')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/ADJ', 'Default', 'ADJ-SKU')",
            variantId, tenantId, productId);
    }

    @AfterEach
    void cleanPieces() {
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?",              tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?",               tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?",               tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?",                    tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?",                    tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?",                 tenantId);
    }

    // adj1: available → lost writes adjusted event, metadata contains reason, audit recorded
    @Test
    void adj1_availableToLost_writesEventAndAudit() {
        String piece = receivePiece();

        TenantContext.set(tenantId);
        try {
            adjustSvc.adjustPiece(piece, "lost", "theft_suspected", null, actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).isEqualTo("lost");

        String meta = jdbc.queryForObject(
            "SELECT metadata::text FROM piece_events WHERE piece_id = ? AND event_type = 'adjusted'",
            String.class, piece);
        assertThat(meta).contains("theft_suspected");

        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE tenant_id = ? AND action = 'piece_adjust' AND target_id = ?",
            Integer.class, tenantId, piece);
        assertThat(auditCount).isEqualTo(1);
    }

    // adj2: reason=other without note → 400
    @Test
    void adj2_otherWithoutNote_returns400() {
        String piece = receivePiece();

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() ->
                adjustSvc.adjustPiece(piece, "lost", "other", "  ", actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
        } finally {
            TenantContext.clear();
        }
    }

    // adj3: reserved piece → 409 PIECE_COMMITTED naming the blocking order
    @Test
    void adj3_reservedPiece_returns409WithOrderNumber() {
        String piece = receivePiece();
        UUID orderId = createOrder();
        reservePiece(piece, orderId);

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() ->
                adjustSvc.adjustPiece(piece, "lost", "cycle_count_missing", null, actorId))
                .isInstanceOf(PieceCommittedException.class)
                .satisfies(e -> {
                    PieceCommittedException pce = (PieceCommittedException) e;
                    assertThat(pce.getOrderId()).isEqualTo(orderId);
                    assertThat(pce.getOrderNumber()).isNotBlank();
                });
        } finally {
            TenantContext.clear();
        }
    }

    // adj4: packed piece → 409 PIECE_COMMITTED naming the blocking order
    @Test
    void adj4_packedPiece_returns409WithOrderNumber() {
        String piece = receivePiece();
        UUID orderId = createOrder();
        packPiece(piece, orderId);

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() ->
                adjustSvc.adjustPiece(piece, "damaged", "damaged_in_storage", null, actorId))
                .isInstanceOf(PieceCommittedException.class)
                .satisfies(e -> {
                    PieceCommittedException pce = (PieceCommittedException) e;
                    assertThat(pce.getOrderId()).isEqualTo(orderId);
                });
        } finally {
            TenantContext.clear();
        }
    }

    // adj5: releaseForAdjust frees allocation and makes piece available
    @Test
    void adj5_releaseForAdjust_freesAllocationAndRestoresAvailable() {
        String piece = receivePiece();
        UUID orderId = createOrder();
        reservePiece(piece, orderId);

        assertThat(pieceStatus(piece)).isEqualTo("reserved");

        TenantContext.set(tenantId);
        try {
            adjustSvc.releaseForAdjust(piece, actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).isEqualTo("available");

        Integer activeAllocs = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE piece_id = ? AND status = 'active'",
            Integer.class, piece);
        assertThat(activeAllocs).isZero();
    }

    // adj6: found it (lost → available) appends new event; original adjusted (lost) event preserved
    @Test
    void adj6_foundIt_appendsEventPreservesOriginal() {
        String piece = receivePiece();

        TenantContext.set(tenantId);
        try {
            // First: mark lost
            adjustSvc.adjustPiece(piece, "lost", "theft_suspected", null, actorId);
            assertThat(pieceStatus(piece)).isEqualTo("lost");

            // Then: found it
            adjustSvc.adjustPiece(piece, "available", "receiving_correction", null, actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).isEqualTo("available");

        // Both adjusted events must exist — history not rewritten
        Integer adjustedEventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'adjusted'",
            Integer.class, piece);
        assertThat(adjustedEventCount).isEqualTo(2);

        // Original (lost) event must still be present
        Integer lostEventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'adjusted' " +
            "AND to_status = 'lost'::piece_status",
            Integer.class, piece);
        assertThat(lostEventCount).isEqualTo(1);
    }

    // adj7: damaged/destroyed → available → 409 terminal
    @Test
    void adj7_terminalStatus_cannotReverseToAvailable() {
        String piece = receivePiece();

        TenantContext.set(tenantId);
        try {
            // Mark damaged first
            adjustSvc.adjustPiece(piece, "damaged", "damaged_in_storage", null, actorId);
            assertThat(pieceStatus(piece)).isEqualTo("damaged");

            // Attempt reverse
            assertThatThrownBy(() ->
                adjustSvc.adjustPiece(piece, "available", "cycle_count_missing", null, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            TenantContext.clear();
        }
    }

    // adj8: tenant isolation — piece owned by tenantA, TenantContext set to tenantB → 404
    @Test
    void adj8_tenantIsolation_crossTenantPieceNotVisible() {
        // Receive piece under tenantId
        String piece = receivePiece();

        // Attempt adjust as tenantB
        TenantContext.set(tenantB);
        try {
            assertThatThrownBy(() ->
                adjustSvc.adjustPiece(piece, "lost", "sample_giveaway", null, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String receivePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, last_event_at, last_user_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status, ?, now(), ?)",
            id, tenantId, variantId, "ADJ-" + id.substring(id.length() - 8), id, locationId, actorId);
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, ?, NULL, 'available'::piece_status)",
            tenantId, id, actorId, locationId);
        return id;
    }

    private UUID createOrder() {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, gen_random_uuid()::text, '#ADJ-' || floor(random()*99999), " +
            "    'new'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId);
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, variantId);
        return orderId;
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

    private void packPiece(String pieceId, UUID orderId) {
        reservePiece(pieceId, orderId);
        TenantContext.set(tenantId);
        ledger.transition(pieceId, PieceStatus.RESERVED, PieceStatus.PACKED,
            "pack", actorId, TransitionContext.forOrder(orderId, orderId));
        jdbc.update(
            "UPDATE allocations SET status = 'packed' WHERE piece_id = ? AND tenant_id = ?",
            pieceId, tenantId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }
}
