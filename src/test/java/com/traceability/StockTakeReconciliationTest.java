package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
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

import static org.mockito.Mockito.verifyNoInteractions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * FR-21 Step 4: reconciliation (disposition report) + resolutions.
 *
 * srt1  — reconciliation buckets a mixed live population correctly (on-shelf, committed,
 *         with-courier/delivered, returns bench, previously-written-off) + variant rollup.
 * srt2  — action=found writes NO piece_event (only a manager_found scan row).
 * srt3  — action=lost on free stock blocked when complete_count=false.
 * srt4  — action=lost on free stock succeeds once complete_count=true.
 * srt5  — action=lost on a piece damaged at snapshot succeeds (proves damaged:lost).
 * srt6  — action=lost on a piece committed AT SNAPSHOT TIME routes through the release guard
 *         (PieceCommittedException), no one-tap write-off.
 * srt7  — drift guard: piece available at snapshot, REAL pick (FulfillService.scan) before
 *         resolve -> skipped, not lost.
 * srt8  — action=mark_damaged applies the available->damaged condition correction.
 * srt9  — attest-complete sets complete_count=true.
 * srt10 — cancel sets status=cancelled, no piece writes, ZERO piece_events rows, and ZERO
 *         interactions with ShopifyGateway — the "Abandon count" UI affordance (new in the
 *         Returns-pattern restyle) must never trigger the write-off path.
 * srt11 — reconciliation surfaces unexpected finds (resurfaced from lost).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakeReconciliationTest {

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

    @Autowired StockTakeService               stockTake;
    @Autowired StockTakeReconciliationService  reconciliation;
    @Autowired InventoryLedger                 ledger;
    @Autowired FulfillService                  fulfillService;
    @Autowired JdbcTemplate                    jdbc;
    @MockBean  JobScheduler                    jobScheduler;
    @MockBean  ShopifyGateway                  shopifyGateway;

    UUID tenantId, actorId, storeId, productId, variantA;
    UUID fulfillmentLocationId;

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        productId  = UUID.randomUUID();
        variantA   = UUID.randomUUID();
        fulfillmentLocationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'StockTakeReconTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 'strc@test.com', 'x', 'owner'::user_role)",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Main WH', true)",
            fulfillmentLocationId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'strc.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/STRC', 'Strc Product')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/Variant/STRCA', 'Variant A', 'STRC-A')",
            variantA, tenantId, productId);
    }

    @AfterEach
    void cleanState() {
        jdbc.update("DELETE FROM stock_take_scans WHERE tenant_id = ?",           tenantId);
        jdbc.update("DELETE FROM stock_take_expected WHERE tenant_id = ?",        tenantId);
        jdbc.update("DELETE FROM stock_take_scope_variants WHERE tenant_id = ?",  tenantId);
        jdbc.update("DELETE FROM stock_take_sessions WHERE tenant_id = ?",        tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?",                  tenantId);
        // piece_events references both pieces and orders — must go before either.
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?",               tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?",                tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?",                tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?",                     tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?",                     tenantId);
    }

    // srt1: mixed live population buckets correctly + variant rollup
    @Test
    void srt1_reconciliation_bucketsMixedPopulationAndRollup() {
        String onShelfCounted   = seedPiece("available");
        String onShelfUncounted = seedPiece("available");
        String damagedCounted   = seedPiece("damaged");
        UUID orderId = createOrder();
        String committed        = seedPieceForOrder("available", orderId); // will become reserved
        String withCourier      = seedPiece("with_courier");
        String returnsBench     = seedPiece("return_pending_inspection");
        String writtenOff       = seedPiece("lost");

        UUID sessionId = openAllScopeAndSnapshot();

        // reserve the committed piece against its order, real allocation row.
        reservePieceForOrder(committed, orderId);

        TenantContext.set(tenantId);
        try {
            stockTake.scan(sessionId, "PC-" + onShelfCounted, "good", actorId);
            stockTake.scan(sessionId, "PC-" + damagedCounted, "damaged", actorId);
        } finally {
            TenantContext.clear();
        }

        Map<String, Object> report;
        TenantContext.set(tenantId);
        try {
            report = reconciliation.reconciliation(sessionId);
        } finally {
            TenantContext.clear();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> buckets = (Map<String, Object>) report.get("buckets");

        assertThat(pieceIds(buckets, "on_shelf_counted")).contains(onShelfCounted);
        assertThat(pieceIds(buckets, "on_shelf_uncounted")).contains(onShelfUncounted);
        assertThat(pieceIds(buckets, "damaged")).contains(damagedCounted);
        assertThat(pieceIds(buckets, "committed_to_orders")).contains(committed);
        assertThat(pieceIds(buckets, "with_courier_or_delivered")).contains(withCourier);
        assertThat(pieceIds(buckets, "returns_bench")).contains(returnsBench);
        assertThat(pieceIds(buckets, "previously_written_off")).contains(writtenOff);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rollup = (List<Map<String, Object>>) report.get("variantRollup");
        Map<String, Object> variantRow = rollup.stream()
            .filter(r -> variantA.toString().equals(r.get("variantId").toString()))
            .findFirst().orElseThrow();
        assertThat(variantRow.get("totalKnown")).isEqualTo(7);
        assertThat(((Number) report.get("coveragePercent")).doubleValue()).isGreaterThan(0);
    }

    // srt2: found writes NO piece_event
    @Test
    void srt2_foundAction_writesNoPieceEvent() {
        String piece = seedPiece("available");
        UUID sessionId = openAllScopeAndSnapshot();

        Integer eventsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, piece);

        List<Map<String, Object>> results;
        TenantContext.set(tenantId);
        try {
            results = reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(piece, "found")), actorId);
        } finally {
            TenantContext.clear();
        }

        assertThat(results.get(0).get("result")).isEqualTo("match");

        Integer eventsAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ?", Integer.class, piece);
        assertThat(eventsAfter).as("no piece_event written by found-it").isEqualTo(eventsBefore);

        Integer scanCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stock_take_scans WHERE session_id = ? AND piece_id = ? AND source = 'manager_found'",
            Integer.class, sessionId, piece);
        assertThat(scanCount).isEqualTo(1);
        assertThat(pieceStatus(piece)).isEqualTo("available");
    }

    // srt3: lost blocked when complete_count=false
    @Test
    void srt3_lostBlockedWhenCompleteCountFalse() {
        String piece = seedPiece("available");
        UUID sessionId = openAllScopeAndSnapshot();

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(piece, "lost")), actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).isEqualTo("available");
    }

    // srt4: lost allowed once complete_count=true
    @Test
    void srt4_lostAllowedWhenCompleteCountTrue() {
        String piece = seedPiece("available");
        UUID sessionId = openAllScopeAndSnapshot();

        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            List<Map<String, Object>> results = reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(piece, "lost")), actorId);
            assertThat(results.get(0).get("result")).isEqualTo("written_off");
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).isEqualTo("lost");
    }

    // srt5: damaged -> lost succeeds (proves the new damaged:lost edge)
    @Test
    void srt5_damagedAtSnapshot_lostSucceeds() {
        String piece = seedPiece("damaged");
        UUID sessionId = openAllScopeAndSnapshot();

        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            List<Map<String, Object>> results = reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(piece, "lost")), actorId);
            assertThat(results.get(0).get("result")).isEqualTo("written_off");
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).isEqualTo("lost");
    }

    // srt6: committed AT SNAPSHOT TIME routes through the release guard
    @Test
    void srt6_committedAtSnapshot_routesThroughReleaseGuard() {
        UUID orderId = createOrder();
        String piece = seedPieceForOrder("available", orderId);
        reservePieceForOrder(piece, orderId); // now 'reserved', real allocation
        UUID sessionId = openAllScopeAndSnapshot(); // status_at_open='reserved'

        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            assertThatThrownBy(() -> reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(piece, "lost")), actorId))
                .isInstanceOf(PieceCommittedException.class)
                .satisfies(e -> assertThat(((PieceCommittedException) e).getOrderId()).isEqualTo(orderId));
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).as("never written off").isEqualTo("reserved");
    }

    // srt7: drift guard — real pick between snapshot and resolve -> skipped, not lost
    @Test
    void srt7_driftGuard_realPickBetweenSnapshotAndResolve_skipped() {
        String piece = seedPiece("available"); // free stock at snapshot
        UUID sessionId = openAllScopeAndSnapshot(); // status_at_open='available'

        UUID orderId = createOrder();
        TenantContext.set(tenantId);
        try {
            // Real pick — the production FulfillService.scan() path, not a raw ledger call.
            FulfillService.ScanResult scanResult = fulfillService.scan(orderId, "PC-" + piece, actorId);
            assertThat(scanResult.success()).as("the real pick must actually succeed").isTrue();
            assertThat(pieceStatus(piece)).isEqualTo("reserved");

            reconciliation.attestComplete(sessionId, actorId);
            List<Map<String, Object>> results = reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(piece, "lost")), actorId);
            assertThat(results.get(0).get("result")).isEqualTo("skipped_changed_during_count");
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).as("drift guard must prevent write-off").isEqualTo("reserved");
    }

    // srt8: mark_damaged applies the condition correction
    @Test
    void srt8_markDamaged_appliesConditionCorrection() {
        String piece = seedPiece("available");
        UUID sessionId = openAllScopeAndSnapshot();

        TenantContext.set(tenantId);
        try {
            List<Map<String, Object>> results = reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(piece, "mark_damaged")), actorId);
            assertThat(results.get(0).get("result")).isEqualTo("damaged");
        } finally {
            TenantContext.clear();
        }

        assertThat(pieceStatus(piece)).isEqualTo("damaged");
    }

    // srt9: attest-complete sets the flag
    @Test
    void srt9_attestComplete_setsFlag() {
        UUID sessionId = openAllScopeAndSnapshot();

        Boolean before = jdbc.queryForObject(
            "SELECT complete_count FROM stock_take_sessions WHERE id = ?", Boolean.class, sessionId);
        assertThat(before).isFalse();

        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }

        Boolean after = jdbc.queryForObject(
            "SELECT complete_count FROM stock_take_sessions WHERE id = ?", Boolean.class, sessionId);
        assertThat(after).isTrue();
    }

    // srt10: cancel sets status=cancelled, no piece writes, no piece_events, no Shopify call
    @Test
    void srt10_cancel_setsStatusCancelledNoWritesNoPieceEventsNoShopifyCall() {
        String piece = seedPiece("available");
        UUID sessionId = openAllScopeAndSnapshot();

        Integer eventsBefore = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, tenantId);

        TenantContext.set(tenantId);
        try {
            reconciliation.cancel(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }

        String status = jdbc.queryForObject(
            "SELECT status FROM stock_take_sessions WHERE id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("cancelled");
        assertThat(pieceStatus(piece)).isEqualTo("available");

        Integer eventsAfter = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE tenant_id = ?", Integer.class, tenantId);
        assertThat(eventsAfter)
            .as("srt10: abandon must write ZERO piece_events — no ledger transition of any kind")
            .isEqualTo(eventsBefore);

        verifyNoInteractions(shopifyGateway);
    }

    // srt11: reconciliation surfaces unexpected finds (resurfaced from lost)
    @Test
    void srt11_reconciliation_unexpectedFinds_resurfacedFromLost() {
        // Never at the fulfillment location at snapshot time -> excluded from the snapshot.
        UUID elsewhere = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Elsewhere', false)",
            elsewhere, tenantId);
        String piece = seedPieceAt("available", elsewhere);
        TenantContext.set(tenantId);
        try {
            ledger.transition(piece, PieceStatus.AVAILABLE, PieceStatus.LOST,
                "adjusted", actorId, TransitionContext.empty());
        } finally {
            TenantContext.clear();
        }

        UUID sessionId = openAllScopeAndSnapshot();

        TenantContext.set(tenantId);
        try {
            stockTake.scan(sessionId, "PC-" + piece, "good", actorId);
        } finally {
            TenantContext.clear();
        }

        Map<String, Object> report;
        TenantContext.set(tenantId);
        try {
            report = reconciliation.reconciliation(sessionId);
        } finally {
            TenantContext.clear();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> buckets = (Map<String, Object>) report.get("buckets");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unexpected = (List<Map<String, Object>>) buckets.get("unexpected_finds");
        assertThat(unexpected).anyMatch(p ->
            piece.equals(p.get("pieceId")) && "resurfaced_from_lost".equals(p.get("reason")));

        // "elsewhere" location is left in place — cleanState() clears pieces referencing it
        // after this test returns; deleting it here would race that same FK.
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID openAllScopeAndSnapshot() {
        UUID sessionId;
        TenantContext.set(tenantId);
        try {
            Map<String, Object> result = stockTake.openSession(
                "all", null, fulfillmentLocationId, null, actorId);
            sessionId = (UUID) result.get("sessionId");
        } finally {
            TenantContext.clear();
        }
        return sessionId;
    }

    private String seedPiece(String status) {
        return seedPieceAt(status, fulfillmentLocationId);
    }

    private String seedPieceAt(String status, UUID locationId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
            id, tenantId, variantA, "PC-" + id, id, status, locationId);
        return id;
    }

    private String seedPieceForOrder(String status, UUID orderId) {
        String id = seedPiece(status);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", orderId, id);
        return id;
    }

    private UUID createOrder() {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, gen_random_uuid()::text, '#STRC-' || floor(random()*99999), " +
            "    'ready_to_pick'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId);
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, variantA);
        return orderId;
    }

    private void reservePieceForOrder(String pieceId, UUID orderId) {
        TenantContext.set(tenantId);
        try {
            ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.RESERVED,
                "scan", actorId, TransitionContext.forOrder(orderId, orderId));
        } finally {
            TenantContext.clear();
        }
        UUID itemId = jdbc.queryForObject(
            "SELECT id FROM order_items WHERE order_id = ? AND tenant_id = ? LIMIT 1",
            UUID.class, orderId, tenantId);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status, allocated_by, allocated_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'active', ?, now())",
            tenantId, itemId, pieceId, actorId);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    @SuppressWarnings("unchecked")
    private List<String> pieceIds(Map<String, Object> buckets, String bucketKey) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) buckets.get(bucketKey);
        return rows.stream().map(r -> (String) r.get("pieceId")).toList();
    }
}
