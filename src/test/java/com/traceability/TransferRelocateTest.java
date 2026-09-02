package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-22.10 — Relocate (one-way A->B) B1: TransferService.closeOneWay()'s two-write guarantee.
 *
 * Deliberately proven against the REAL cross-service consumer (FulfillService.scan()) and the
 * REAL unique partial index (transfer_pieces_one_active), not a unit-level status string check
 * alone — this is what the diagnosis flagged as the easiest thing to get subtly wrong: a close
 * that transitions the piece correctly but forgets the transfer_pieces bookkeeping write (or
 * vice versa) can pass a naive "is it un-pickable" test while still being broken in one of the
 * two ways that matter (wrongly pickable again, OR permanently un-returnable).
 *
 * relocate_unpickable and relocate_returnable are each revert-to-confirm proven independently
 * (see the B1 build report) by temporarily reverting closeOneWay() to a plausible wrong
 * implementation and confirming this exact test goes red, then restoring it and confirming
 * green — not committed here as separate red/green variants, since these ARE the green
 * assertions the revert cycle checks against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferRelocateTest {

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

    @Autowired TransferService transferSvc;
    @Autowired FulfillService  fulfillSvc;
    @Autowired JdbcTemplate    jdbc;

    UUID tenantId;
    UUID actorId;
    UUID storeId;
    UUID variantId;
    UUID destinationLocationId;
    UUID fulfillmentLocationId;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        actorId  = UUID.randomUUID();
        storeId  = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        destinationLocationId = UUID.randomUUID();
        fulfillmentLocationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Relocate Test Co')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Test Actor', 'actor@relocate-test.com', 'h', 'owner')",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'relocate-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'PROD-RL', 'Relocate Widget', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title) " +
            "VALUES (?, ?, ?, 'VAR-RL', 'Relocate Widget Variant')",
            variantId, tenantId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)",
            fulfillmentLocationId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Second Warehouse', 'warehouse', false, false)",
            destinationLocationId, tenantId);
    }

    @BeforeEach
    void setTenantContext() { TenantContext.set(tenantId); }

    @AfterEach
    void clearTenantContext() { TenantContext.clear(); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String insertAvailablePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'available'::piece_status)",
            id, tenantId, variantId, "PC-" + id, id);
        return id;
    }

    private UUID insertOrderWithItem(int qty) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold, placed_at) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, false, now())",
            orderId, tenantId, storeId, orderId.toString());
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (gen_random_uuid(), ?, ?, ?, ?)",
            tenantId, orderId, variantId, qty);
        return orderId;
    }

    private String fetchStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String transferPieceOutcome(UUID transferId, String pieceId) {
        return jdbc.queryForObject(
            "SELECT outcome FROM transfer_pieces WHERE transfer_id = ? AND piece_id = ?",
            String.class, transferId, pieceId);
    }

    // -----------------------------------------------------------------------
    // Test 1 — UN-PICKABLE, proven via the real pick path (FulfillService.scan())
    // -----------------------------------------------------------------------

    @Test
    void relocate_closedPiece_isUnpickable_viaRealScanPath() {
        UUID transferId = transferSvc.createTransfer(
            "other", destinationLocationId, null, "B1 test", actorId, "relocate_out");
        String pieceId = insertAvailablePiece();
        TransferService.ScanOutResult scanOut = transferSvc.scanOut(transferId, "PC-" + pieceId, actorId);
        assertThat(scanOut.success()).as("fixture scanOut must succeed").isTrue();
        assertThat(fetchStatus(pieceId)).isEqualTo("out_on_transfer");

        transferSvc.closeOneWay(transferId, actorId);

        assertThat(fetchStatus(pieceId))
            .as("relocate close must land the piece on the new terminal status")
            .isEqualTo("transferred_out");

        // The actual proof: no pick-path code was touched to make this piece un-pickable —
        // FulfillService.scan()'s pre-existing status='available' gate must reject it on its own.
        UUID orderId = insertOrderWithItem(1);
        FulfillService.ScanResult result = fulfillSvc.scan(orderId, "PC-" + pieceId, actorId);

        assertThat(result.success()).as("a transferred_out piece must not be pickable").isFalse();
        assertThat(result.code()).isEqualTo("WRONG_STATUS");
    }

    // -----------------------------------------------------------------------
    // Test 2 — RETURNABLE: the one_active unique-index slot is freed
    // -----------------------------------------------------------------------

    @Test
    void relocate_closedPiece_writesRelocatedOutcome_freeingItForAFutureReturn() {
        UUID transferId = transferSvc.createTransfer(
            "other", destinationLocationId, null, "B1 test", actorId, "relocate_out");
        String pieceId = insertAvailablePiece();
        transferSvc.scanOut(transferId, "PC-" + pieceId, actorId);

        transferSvc.closeOneWay(transferId, actorId);

        // If this is NULL, transfer_pieces_one_active (UNIQUE ON piece_id WHERE outcome IS
        // NULL) permanently blocks this piece_id from ever being claimed by a future return
        // transfer — the piece would be un-pickable AND un-returnable, a genuine stranding bug.
        assertThat(transferPieceOutcome(transferId, pieceId))
            .as("must be non-null to free the one-active-transfer slot for a future return")
            .isEqualTo("relocated");

        // Direct proof, not just an inference from the column value: exercise the ACTUAL
        // unique partial index a future return transfer's claim-INSERT would run against
        // (the same statement shape TransferService.scanOut() itself uses for its claim).
        // If outcome were still NULL, this INSERT would throw a duplicate-key violation.
        UUID lineId = jdbc.queryForObject(
            "SELECT id FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            UUID.class, transferId, variantId);
        UUID futureReturnTransferId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, created_by) " +
            "VALUES (?, ?, 'other', ?, ?)",
            futureReturnTransferId, tenantId, fulfillmentLocationId, actorId);
        jdbc.update(
            "INSERT INTO transfer_pieces (id, tenant_id, transfer_id, line_id, piece_id) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?)",
            tenantId, futureReturnTransferId, lineId, pieceId);
        // No exception reaching here IS the proof — the piece_id was successfully claimed a
        // second time because its original row's outcome is no longer NULL.
    }

    // -----------------------------------------------------------------------
    // Positive control — round_trip transfers are unaffected by any of this
    // -----------------------------------------------------------------------

    @Test
    void roundTrip_positiveControl_scanOutReconcileClose_stillWorksUnaffected() {
        UUID transferId = transferSvc.createTransfer(
            "showroom", destinationLocationId, null, "control", actorId);
        String pieceId = insertAvailablePiece();
        transferSvc.scanOut(transferId, "PC-" + pieceId, actorId);
        transferSvc.beginReconcile(transferId, actorId);
        transferSvc.reconcileScanBack(transferId, "PC-" + pieceId, "good", actorId);
        transferSvc.closeTransfer(transferId, actorId);

        assertThat(fetchStatus(pieceId)).isEqualTo("available");
        String status = jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, transferId);
        assertThat(status).isEqualTo("closed");
    }
}
