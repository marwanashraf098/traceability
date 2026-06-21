package com.traceability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Day 9 integration tests: scan-driven pick/pack fulfill flow (FR-8 + FR-9 core).
 *
 * Test inventory:
 *   (a) Queue lists orders in correct statuses (new/ready_to_pick), oldest-first
 *   (b) Lock assigns locked_by + locked_at; owner-lock conflict rejected
 *   (c) Release clears lock; manager can release any order; worker can only release own
 *   (d) Scan PIECE_NOT_FOUND
 *   (e) Scan success: piece reserved, allocation inserted, counts correct
 *   (f) Scan DUPLICATE_SCAN: same piece scanned twice to same order
 *   (g) Scan ALREADY_RESERVED: piece already allocated to another order
 *   (h) Scan WRONG_VARIANT: piece variant not on order
 *   (i) Scan WRONG_STATUS: piece not available (e.g. damaged)
 *   (j) Scan race: two threads scan same piece → exactly one wins, one ALREADY_RESERVED
 *   (k) Over-allocation race: two threads scan two different pieces of same variant,
 *       line qty=1 → exactly one allocation, one rejection
 *   (l) Unscan: allocation released, piece status back to available
 *   (m) Complete: all reserved → packed, allocations → packed, order → packed
 *   (n) Cross-tenant: cannot scan pieces from another tenant
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day9Test {

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
    @Autowired ObjectMapper    objectMapper;

    // Tenant B datasource (RLS cross-tenant isolation test)
    FulfillService tenantBFulfillSvc;

    // Shared fixture IDs
    UUID tenantAId, tenantBId;
    UUID actorId, managerActorId, workerActorId;
    UUID storeAId, storeBId;
    UUID variantAId, variantBId;
    UUID locationAId;

    @BeforeAll
    void setup() {
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        actorId        = UUID.randomUUID();
        managerActorId = UUID.randomUUID();
        workerActorId  = UUID.randomUUID();
        storeAId   = UUID.randomUUID();
        storeBId   = UUID.randomUUID();
        variantAId = UUID.randomUUID();
        variantBId = UUID.randomUUID();
        locationAId = UUID.randomUUID();
        UUID productAId = UUID.randomUUID();
        UUID productBId = UUID.randomUUID();

        // Tenant A setup
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TenantA')", tenantAId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', 'owner@day9.local', 'h', 'owner')", actorId, tenantAId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Mgr', 'mgr@day9.local', 'h', 'manager')", managerActorId, tenantAId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Worker', 'worker@day9.local', 'h', 'worker')", workerActorId, tenantAId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'day9a.myshopify.com', 'disconnected')", storeAId, tenantAId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-A1', 'Widget', 'active')", productAId, tenantAId, storeAId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-A1', 'Widget Red', 'WIDRED')", variantAId, tenantAId, productAId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-A2', 'Widget Blue', 'WIDBLU')", variantBId, tenantAId, productAId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES (?, ?, 'WH-A', 'warehouse', true)", locationAId, tenantAId);

        // Tenant B setup
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TenantB')", tenantBId);
        UUID actorBId = UUID.randomUUID();
        UUID variantB1Id = UUID.randomUUID();
        UUID variantB2Id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'BOwner', 'owner@day9b.local', 'h', 'owner')", actorBId, tenantBId);
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'day9b.myshopify.com', 'disconnected')", storeBId, tenantBId);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-B1', 'Gadget', 'active')", productBId, tenantBId, storeBId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-B1', 'Gadget', 'GADGET')", variantB1Id, tenantBId, productBId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-B2', 'Gadget Blue', 'GADBLU')", variantB2Id, tenantBId, productBId);

        // Tenant B app_user datasource
        DriverManagerDataSource rawB = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDsB = new TenantAwareDataSource(rawB);
        JdbcTemplate jdbcB = new JdbcTemplate(appUserDsB);
        DataSourceTransactionManager txmB = new DataSourceTransactionManager(appUserDsB);
        InventoryLedger ledgerB = new InventoryLedger(jdbcB);
        AuditService auditSvcB = new AuditService(jdbcB, objectMapper);
        tenantBFulfillSvc = new FulfillService(jdbcB, ledgerB, auditSvcB);
    }

    @BeforeEach
    void setContext() { TenantContext.set(tenantAId); }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        jdbc.update("DELETE FROM allocations WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
        jdbc.update("DELETE FROM orders WHERE tenant_id IN (?, ?)", tenantAId, tenantBId);
    }

    // ── (a) Queue lists correct orders ────────────────────────────────────────

    @Test
    void a_queue_shows_new_and_ready_orders_oldest_first() {
        UUID ordNew   = insertOrder("new");
        UUID ordReady = insertOrder("ready_to_pick");
        UUID ordPacked = insertOrder("packed");
        UUID ordHeld  = insertOrderOnHold();

        List<Map<String, Object>> queue = fulfillSvc.getQueue();

        List<Object> ids = queue.stream().map(r -> r.get("id")).toList();
        assertThat(ids).containsExactly(ordNew, ordReady);
        assertThat(ids).doesNotContain(ordPacked, ordHeld);
    }

    // ── (b) Lock assigns locked_by ────────────────────────────────────────────

    @Test
    void b_lock_assigns_locked_by_and_rejects_concurrent_worker() {
        UUID orderId = insertOrder("new");

        fulfillSvc.lockOrder(orderId, actorId);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT locked_by, locked_at FROM orders WHERE id = ?", orderId);
        assertThat(row.get("locked_by")).isEqualTo(actorId);
        assertThat(row.get("locked_at")).isNotNull();

        // Same user locking again is idempotent
        assertThatCode(() -> fulfillSvc.lockOrder(orderId, actorId)).doesNotThrowAnyException();

        // Different user → conflict
        assertThatThrownBy(() -> fulfillSvc.lockOrder(orderId, workerActorId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("locked by another worker");
    }

    // ── (c) Release clears lock ───────────────────────────────────────────────

    @Test
    void c_release_clears_lock_manager_can_release_any() {
        UUID orderId = insertOrder("new");
        fulfillSvc.lockOrder(orderId, workerActorId);

        // Manager releases it
        fulfillSvc.releaseOrder(orderId, managerActorId, true);

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT locked_by, locked_at FROM orders WHERE id = ?", orderId);
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_at")).isNull();
    }

    // ── (d) Scan PIECE_NOT_FOUND ──────────────────────────────────────────────

    @Test
    void d_scan_pieceNotFound_for_unknown_barcode() {
        UUID orderId = insertOrderWithItem(variantAId, 1);
        insertAvailablePiece(variantAId); // available but not this barcode

        FulfillService.ScanResult result = fulfillSvc.scan(orderId, "PC-DOES-NOT-EXIST", actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("PIECE_NOT_FOUND");
    }

    // ── (e) Scan success ──────────────────────────────────────────────────────

    @Test
    void e_scan_success_reserves_piece_and_creates_allocation() {
        UUID orderId = insertOrderWithItem(variantAId, 3);
        String pieceId = insertAvailablePiece(variantAId);
        String barcode = "PC-" + pieceId;

        FulfillService.ScanResult result = fulfillSvc.scan(orderId, barcode, actorId);

        assertThat(result.success()).isTrue();
        assertThat(result.code()).isEqualTo("SCANNED");
        assertThat(result.pieceId()).isEqualTo(pieceId);
        assertThat(result.allocatedCount()).isEqualTo(1);
        assertThat(result.requiredQuantity()).isEqualTo(3);
        assertThat(result.allComplete()).isFalse();

        String pieceStatus = jdbc.queryForObject(
            "SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
        assertThat(pieceStatus).isEqualTo("reserved");

        long allocCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE piece_id = ? AND status = 'active'",
            Long.class, pieceId);
        assertThat(allocCount).isEqualTo(1);
    }

    // ── (f) Scan DUPLICATE_SCAN ───────────────────────────────────────────────

    @Test
    void f_scan_duplicate_scan_rejected() {
        UUID orderId = insertOrderWithItem(variantAId, 2);
        String pieceId = insertAvailablePiece(variantAId);
        String barcode = "PC-" + pieceId;

        FulfillService.ScanResult first = fulfillSvc.scan(orderId, barcode, actorId);
        assertThat(first.success()).isTrue();

        FulfillService.ScanResult second = fulfillSvc.scan(orderId, barcode, actorId);
        assertThat(second.success()).isFalse();
        assertThat(second.code()).isEqualTo("DUPLICATE_SCAN");
    }

    // ── (g) Scan ALREADY_RESERVED ─────────────────────────────────────────────

    @Test
    void g_scan_already_reserved_for_another_order() {
        UUID order1Id = insertOrderWithItem(variantAId, 1);
        UUID order2Id = insertOrderWithItem(variantAId, 1);
        String pieceId = insertAvailablePiece(variantAId);
        String barcode = "PC-" + pieceId;

        // Reserve for order 1
        FulfillService.ScanResult r1 = fulfillSvc.scan(order1Id, barcode, actorId);
        assertThat(r1.success()).isTrue();

        // Scan for order 2 should be rejected
        FulfillService.ScanResult r2 = fulfillSvc.scan(order2Id, barcode, actorId);
        assertThat(r2.success()).isFalse();
        assertThat(r2.code()).isEqualTo("ALREADY_RESERVED");
    }

    // ── (h) Scan WRONG_VARIANT ────────────────────────────────────────────────

    @Test
    void h_scan_wrong_variant_rejected() {
        UUID orderId = insertOrderWithItem(variantAId, 1);   // order wants variantA
        String pieceId = insertAvailablePiece(variantBId);  // but piece is variantB
        String barcode = "PC-" + pieceId;

        FulfillService.ScanResult result = fulfillSvc.scan(orderId, barcode, actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("WRONG_VARIANT");
    }

    // ── (h2) Scan LINE_FILLED — variant is on the order but the line is full ──

    @Test
    void h2_scan_line_filled_when_correct_variant_but_line_complete() {
        // Order has qty=1 for variantA. Scan the one valid piece → success.
        // Then scan a SECOND available variantA piece → LINE_FILLED, not WRONG_VARIANT.
        UUID orderId = insertOrderWithItem(variantAId, 1);
        String piece1 = insertAvailablePiece(variantAId);
        String piece2 = insertAvailablePiece(variantAId);

        FulfillService.ScanResult first = fulfillSvc.scan(orderId, "PC-" + piece1, actorId);
        assertThat(first.success()).isTrue();

        FulfillService.ScanResult second = fulfillSvc.scan(orderId, "PC-" + piece2, actorId);
        assertThat(second.success()).isFalse();
        assertThat(second.code())
            .as("correct-variant scan against a full line must be LINE_FILLED, not WRONG_VARIANT")
            .isEqualTo("LINE_FILLED");
    }

    // ── (i) Scan WRONG_STATUS ─────────────────────────────────────────────────

    @Test
    void i_scan_wrong_status_damaged_piece_rejected() {
        UUID orderId = insertOrderWithItem(variantAId, 1);
        String pieceId = insertPieceWithStatus(variantAId, "damaged");
        String barcode = "PC-" + pieceId;

        FulfillService.ScanResult result = fulfillSvc.scan(orderId, barcode, actorId);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("WRONG_STATUS");
    }

    // ── (j) Scan race: two threads, same piece ────────────────────────────────

    @Test
    void j_scan_race_same_piece_exactly_one_wins() throws InterruptedException {
        UUID orderId = insertOrderWithItem(variantAId, 2);
        String pieceId = insertAvailablePiece(variantAId);
        String barcode = "PC-" + pieceId;

        CountDownLatch ready     = new CountDownLatch(2);
        CountDownLatch go        = new CountDownLatch(1);
        AtomicInteger  successes = new AtomicInteger();
        AtomicInteger  rejections = new AtomicInteger();

        Runnable attempt = () -> {
            TenantContext.set(tenantAId);
            try {
                ready.countDown();
                go.await();
                FulfillService.ScanResult result = fulfillSvc.scan(orderId, barcode, actorId);
                if (result.success()) successes.incrementAndGet();
                else rejections.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                TenantContext.clear();
            }
        };

        Thread t1 = new Thread(attempt);
        Thread t2 = new Thread(attempt);
        t1.start(); t2.start();
        ready.await();
        go.countDown();
        t1.join(); t2.join();

        assertThat(successes.get()).as("exactly one scan wins").isEqualTo(1);
        assertThat(rejections.get()).as("exactly one scan rejected").isEqualTo(1);

        long allocCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE piece_id = ? AND status = 'active'",
            Long.class, pieceId);
        assertThat(allocCount).as("exactly one allocation").isEqualTo(1);
    }

    // ── (k) Over-allocation race: two different pieces, line qty=1 ────────────

    @Test
    void k_overallocation_race_line_qty1_exactly_one_wins() throws InterruptedException {
        UUID orderId = insertOrderWithItem(variantAId, 1);  // qty=1
        String piece1 = insertAvailablePiece(variantAId);
        String piece2 = insertAvailablePiece(variantAId);
        String barcode1 = "PC-" + piece1;
        String barcode2 = "PC-" + piece2;

        CountDownLatch ready    = new CountDownLatch(2);
        CountDownLatch go       = new CountDownLatch(1);
        AtomicInteger successes  = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        // Capture the loser's rejection code — must be WRONG_VARIANT (line capacity exhausted),
        // not ALREADY_RESERVED (piece-level) or an uncaught exception.
        AtomicReference<String> loserCode = new AtomicReference<>();

        // Thread 1 scans piece1, thread 2 scans piece2 — two different pieces, same variant
        Thread t1 = new Thread(() -> {
            TenantContext.set(tenantAId);
            try {
                ready.countDown();
                go.await();
                FulfillService.ScanResult r = fulfillSvc.scan(orderId, barcode1, actorId);
                if (r.success()) successes.incrementAndGet();
                else { rejections.incrementAndGet(); loserCode.compareAndSet(null, r.code()); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                TenantContext.clear();
            }
        });

        Thread t2 = new Thread(() -> {
            TenantContext.set(tenantAId);
            try {
                ready.countDown();
                go.await();
                FulfillService.ScanResult r = fulfillSvc.scan(orderId, barcode2, actorId);
                if (r.success()) successes.incrementAndGet();
                else { rejections.incrementAndGet(); loserCode.compareAndSet(null, r.code()); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                TenantContext.clear();
            }
        });

        t1.start(); t2.start();
        ready.await();
        go.countDown();
        t1.join(); t2.join();

        // 1. Thread counts: exactly one winner, exactly one loser (no exception escaped)
        assertThat(successes.get()).as("exactly one scan wins against qty=1 line").isEqualTo(1);
        assertThat(rejections.get()).as("exactly one scan rejected as over-allocation").isEqualTo(1);

        // 2. Rejection code: must be LINE_FILLED (the line-capacity guard fired).
        //    Both pieces are correct-variant and available, so WRONG_VARIANT (no matching line)
        //    and ALREADY_RESERVED (piece-level) are both wrong here. Only the capacity check
        //    (SELECT FOR UPDATE + post-lock COUNT) produces LINE_FILLED.
        assertThat(loserCode.get())
            .as("loser must be rejected with LINE_FILLED, not WRONG_VARIANT or ALREADY_RESERVED")
            .isEqualTo("LINE_FILLED");

        // 3. Database end-state: exactly one active allocation for this order (no over-allocation)
        long totalAlloc = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE status = 'active' " +
            "AND order_item_id IN (SELECT id FROM order_items WHERE order_id = ?)",
            Long.class, orderId);
        assertThat(totalAlloc).as("exactly one allocation in DB (no over-allocation)").isEqualTo(1);

        // 4. Both pieces still exist; the loser's piece must remain 'available' (not orphaned)
        long availableCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pieces WHERE id IN (?, ?) AND status = 'available'",
            Long.class, piece1, piece2);
        assertThat(availableCount).as("loser's piece must remain available, not stuck in reserved").isEqualTo(1);
    }

    // ── (l) Unscan ────────────────────────────────────────────────────────────

    @Test
    void l_unscan_releases_allocation_and_restores_available() {
        UUID orderId = insertOrderWithItem(variantAId, 2);
        String pieceId = insertAvailablePiece(variantAId);
        String barcode = "PC-" + pieceId;

        fulfillSvc.scan(orderId, barcode, actorId);

        String statusAfterScan = jdbc.queryForObject(
            "SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
        assertThat(statusAfterScan).isEqualTo("reserved");

        fulfillSvc.unscan(orderId, pieceId, actorId);

        String statusAfterUnscan = jdbc.queryForObject(
            "SELECT status FROM pieces WHERE id = ?", String.class, pieceId);
        assertThat(statusAfterUnscan).isEqualTo("available");

        long activeAlloc = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE piece_id = ? AND status = 'active'",
            Long.class, pieceId);
        assertThat(activeAlloc).isEqualTo(0);

        long releasedAlloc = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE piece_id = ? AND status = 'released'",
            Long.class, pieceId);
        assertThat(releasedAlloc).isEqualTo(1);
    }

    // ── (m) Complete: reserved → packed ──────────────────────────────────────

    @Test
    void m_complete_moves_all_pieces_to_packed_and_order_to_packed() {
        UUID orderId = insertOrderWithItem(variantAId, 2);
        String p1 = insertAvailablePiece(variantAId);
        String p2 = insertAvailablePiece(variantAId);

        fulfillSvc.scan(orderId, "PC-" + p1, actorId);
        fulfillSvc.scan(orderId, "PC-" + p2, actorId);

        int packed = fulfillSvc.complete(orderId, actorId);

        assertThat(packed).isEqualTo(2);

        for (String pid : List.of(p1, p2)) {
            String pieceStatus = jdbc.queryForObject(
                "SELECT status FROM pieces WHERE id = ?", String.class, pid);
            assertThat(pieceStatus).isEqualTo("packed");
        }

        long packedAllocs = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE status = 'packed' " +
            "AND order_item_id IN (SELECT id FROM order_items WHERE order_id = ?)",
            Long.class, orderId);
        assertThat(packedAllocs).isEqualTo(2);

        String orderStatus = jdbc.queryForObject(
            "SELECT status FROM orders WHERE id = ?", String.class, orderId);
        assertThat(orderStatus).isEqualTo("packed");
    }

    @Test
    void m2_complete_rejects_when_not_fully_scanned() {
        UUID orderId = insertOrderWithItem(variantAId, 2);
        String p1 = insertAvailablePiece(variantAId);
        fulfillSvc.scan(orderId, "PC-" + p1, actorId);  // only 1 of 2 scanned

        assertThatThrownBy(() -> fulfillSvc.complete(orderId, actorId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Not all items are scanned");
    }

    // ── (n) Cross-tenant isolation ────────────────────────────────────────────

    @Test
    void n_cross_tenant_piece_not_scannable_from_other_tenant() {
        // Tenant A piece
        String tenantAPieceId = insertAvailablePiece(variantAId);
        String tenantABarcode = "PC-" + tenantAPieceId;

        // Tenant B has its own order for variantA (same UUID re-used for variant in B setup)
        UUID tenantBVariantId = UUID.randomUUID();
        UUID tenantBProductId = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'P-BX', 'Widget', 'active')", tenantBProductId, tenantBId, storeBId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, 'V-BX', 'Widget X', 'WIDX')", tenantBVariantId, tenantBId, tenantBProductId);
        UUID tenantBOrderId = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, status) VALUES (?, ?, ?, 'ORDERBX', 'new'::order_status)", tenantBOrderId, tenantBId, storeBId);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (gen_random_uuid(), ?, ?, ?, 1)", tenantBId, tenantBOrderId, tenantBVariantId);

        TenantContext.clear();
        TenantContext.set(tenantBId);
        try {
            // Tenant B scanning Tenant A's barcode → PIECE_NOT_FOUND (RLS blocks it)
            FulfillService.ScanResult result =
                tenantBFulfillSvc.scan(tenantBOrderId, tenantABarcode, actorId);
            assertThat(result.success()).isFalse();
            assertThat(result.code()).isEqualTo("PIECE_NOT_FOUND");
        } finally {
            TenantContext.clear();
            TenantContext.set(tenantAId);
        }
    }

    // ── allComplete flag ──────────────────────────────────────────────────────

    @Test
    void e2_scan_sets_allComplete_when_last_piece_scanned() {
        UUID orderId = insertOrderWithItem(variantAId, 1);
        String pieceId = insertAvailablePiece(variantAId);

        FulfillService.ScanResult result = fulfillSvc.scan(orderId, "PC-" + pieceId, actorId);

        assertThat(result.success()).isTrue();
        assertThat(result.allComplete()).isTrue();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private UUID insertOrder(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold) " +
            "VALUES (?, ?, ?, ?, ?::order_status, false)",
            id, tenantAId, storeAId, id.toString(), status);
        return id;
    }

    private UUID insertOrderOnHold() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, true)",
            id, tenantAId, storeAId, id.toString());
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

    private String insertAvailablePiece(UUID variantId) {
        return insertPieceWithStatus(variantId, "available");
    }

    private String insertPieceWithStatus(UUID variantId, String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?::piece_status, ?)",
            id, tenantAId, variantId, "PC-" + id, status, locationAId);
        return id;
    }
}
