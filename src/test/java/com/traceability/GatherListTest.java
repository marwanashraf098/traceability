package com.traceability;

import com.traceability.inventory.FulfillService;
import com.traceability.inventory.UlidGenerator;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-8.7 — GatherService (FulfillService.getGatherList) read-only pick-wave aggregation.
 *
 * Test inventory:
 *   a — positive control: 3 ready_to_pick orders, overlapping variants — correct needed
 *       SUM per variant and correct orderNumbers sets
 *   b — availability: available < needed → shortage = true
 *   c — availability: available >= needed → shortage = false
 *   d — status filter: 'new' and 'ready_to_pick' both included (matching getQueue()'s
 *       PICKABLE_ORDERS_FILTER), 'packed' and on-hold excluded
 *   e — the production bug fixed 2026-07-28: a FRESH 'new' order with zero allocations
 *       (the new→ready_to_pick confirmation flow isn't built, so real orders sit in
 *       'new' the whole time they're pickable) must appear in gather with needed equal
 *       to the full order_item quantity — this is the fixture gap that let the original
 *       ready_to_pick-only filter ship undetected
 *   f — empty: no eligible orders at all → empty rows, orderCount = 0
 *   g — limit: limit=N returns only the oldest N orders' demand
 *   h — mid-pick: order stays ready_to_pick with a SUBSET of pieces already actively
 *       allocated (this codebase never transitions orders to 'picking' — see
 *       getGatherList() javadoc) — needed must subtract those active allocations, and
 *       shortage must recalculate against the reduced remaining, not the raw quantity
 *   i — per-line flooring: one order_item with active_count > quantity (stray/corrupt
 *       over-allocation) must floor at 0 for that line, not let the negative leak into
 *       the SUM and cannibalize a different, healthy order_item's demand on the same
 *       variant
 *   j — displayName: product title distinct from variant title composes as
 *       "product - variant" via ProductDisplayName, the same helper LabelService uses
 *       under the physical piece barcode
 *   k — cross-tenant isolation (paired with the positive control in `a`): tenant B's
 *       gather never sees tenant A's orders or demand
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatherListTest {

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

    @Autowired FulfillService fulfillSvc;
    @Autowired JdbcTemplate   jdbc;
    @MockBean  JobScheduler   jobScheduler;

    UUID tenantA, storeA;
    UUID tenantB, storeB;

    @BeforeAll
    void setupFixture() {
        tenantA = UUID.randomUUID();
        storeA  = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        storeB  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'GatherTenantA')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'GatherTenantB')", tenantB);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'gathera.myshopify.com', 'connected')", storeA, tenantA);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'gatherb.myshopify.com', 'connected')", storeB, tenantB);
    }

    @BeforeEach void ctx() { TenantContext.set(tenantA); }

    @AfterEach void clean() {
        TenantContext.clear();
        for (UUID t : new UUID[]{tenantA, tenantB}) {
            jdbc.update("DELETE FROM allocations WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces      WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM orders      WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM variants     WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM products     WHERE tenant_id = ?", t);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID insertProduct(UUID tenantId, UUID storeId, String externalId, String title) {
        return jdbc.queryForObject(
            "INSERT INTO products (tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, ?, 'active') RETURNING id",
            UUID.class, tenantId, storeId, externalId, title);
    }

    private UUID insertVariant(UUID tenantId, UUID productId, String externalId, String sku, String title) {
        return jdbc.queryForObject(
            "INSERT INTO variants (tenant_id, product_id, external_id, sku, title) " +
            "VALUES (?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, productId, externalId, sku, title);
    }

    private UUID insertOrder(UUID tenantId, UUID storeId, String externalId, String number,
                              String status, boolean onHold, Instant createdAt) {
        // placed_at set equal to createdAt: PICKABLE_ORDERS_FILTER (shared with getQueue())
        // requires placed_at within the lookback window — NULL placed_at (the column
        // default) would silently exclude every fixture from both screens.
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, on_hold, " +
            "                    created_at, placed_at) " +
            "VALUES (?, ?, ?, ?, ?::order_status, ?, ?, ?) RETURNING id",
            UUID.class, tenantId, storeId, externalId, number, status, onHold,
            Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private UUID insertOrderItem(UUID tenantId, UUID orderId, UUID variantId, int quantity) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?) " +
            "RETURNING id",
            UUID.class, tenantId, orderId, variantId, quantity);
    }

    private String insertPiece(UUID tenantId, UUID variantId, String status) {
        String pieceId = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status)",
            pieceId, tenantId, variantId, "PC-" + pieceId, pieceId, status);
        return pieceId;
    }

    private void insertAllocation(UUID tenantId, UUID orderItemId, String pieceId, String status) {
        jdbc.update(
            "INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) " +
            "VALUES (?, ?, ?, ?::allocation_status)",
            tenantId, orderItemId, pieceId, status);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void a_positiveControl_overlappingVariants_correctNeededAndOrderNumbers() {
        UUID productId = insertProduct(tenantA, storeA, "GL-P1", "Gather Product");
        UUID variantX  = insertVariant(tenantA, productId, "GL-VX", "SKU-X", "Variant X");
        UUID variantY  = insertVariant(tenantA, productId, "GL-VY", "SKU-Y", "Variant Y");

        UUID orderA = insertOrder(tenantA, storeA, "GL-OA", "#GL-A", "ready_to_pick", false, Instant.now().minusSeconds(300));
        UUID orderB = insertOrder(tenantA, storeA, "GL-OB", "#GL-B", "ready_to_pick", false, Instant.now().minusSeconds(200));
        UUID orderC = insertOrder(tenantA, storeA, "GL-OC", "#GL-C", "ready_to_pick", false, Instant.now().minusSeconds(100));

        insertOrderItem(tenantA, orderA, variantX, 2);
        insertOrderItem(tenantA, orderB, variantX, 1);
        insertOrderItem(tenantA, orderC, variantY, 5);

        // sufficient stock for both variants — this test isolates the needed/orderNumbers math
        for (int i = 0; i < 5; i++) insertPiece(tenantA, variantX, "available");
        for (int i = 0; i < 5; i++) insertPiece(tenantA, variantY, "available");

        FulfillService.GatherListResponse resp = fulfillSvc.getGatherList(null);

        assertThat(resp.orderCount()).isEqualTo(3);
        assertThat(resp.rows()).hasSize(2);

        FulfillService.GatherRow rowX = resp.rows().stream()
            .filter(r -> r.variantId().equals(variantX)).findFirst().orElseThrow();
        assertThat(rowX.needed()).isEqualTo(3);
        assertThat(rowX.orderNumbers()).containsExactlyInAnyOrder("#GL-A", "#GL-B");
        assertThat(rowX.shortage()).isFalse();

        FulfillService.GatherRow rowY = resp.rows().stream()
            .filter(r -> r.variantId().equals(variantY)).findFirst().orElseThrow();
        assertThat(rowY.needed()).isEqualTo(5);
        assertThat(rowY.orderNumbers()).containsExactly("#GL-C");
        assertThat(rowY.shortage()).isFalse();
    }

    @Test
    void b_shortage_whenAvailableLessThanNeeded_flaggedTrue() {
        UUID productId = insertProduct(tenantA, storeA, "GL-P2", "Shortage Product");
        UUID variant   = insertVariant(tenantA, productId, "GL-V2", "SKU-SHORT", "Short Variant");
        UUID order     = insertOrder(tenantA, storeA, "GL-O2", "#GL-2", "ready_to_pick", false, Instant.now());
        insertOrderItem(tenantA, order, variant, 5);
        insertPiece(tenantA, variant, "available");
        insertPiece(tenantA, variant, "available");
        // only 2 available < 5 needed

        FulfillService.GatherRow row = fulfillSvc.getGatherList(null).rows().get(0);
        assertThat(row.needed()).isEqualTo(5);
        assertThat(row.availableCount()).isEqualTo(2);
        assertThat(row.shortage()).isTrue();
    }

    @Test
    void c_shortage_whenSufficientStock_flaggedFalse() {
        UUID productId = insertProduct(tenantA, storeA, "GL-P3", "Sufficient Product");
        UUID variant   = insertVariant(tenantA, productId, "GL-V3", "SKU-OK", "OK Variant");
        UUID order     = insertOrder(tenantA, storeA, "GL-O3", "#GL-3", "ready_to_pick", false, Instant.now());
        insertOrderItem(tenantA, order, variant, 2);
        insertPiece(tenantA, variant, "available");
        insertPiece(tenantA, variant, "available");
        insertPiece(tenantA, variant, "available");
        // 3 available >= 2 needed

        FulfillService.GatherRow row = fulfillSvc.getGatherList(null).rows().get(0);
        assertThat(row.needed()).isEqualTo(2);
        assertThat(row.availableCount()).isEqualTo(3);
        assertThat(row.shortage()).isFalse();
    }

    @Test
    void d_statusFilter_excludesPackedAndOnHold_includesNewAndReadyToPick() {
        // Mirrors getQueue()'s PICKABLE_ORDERS_FILTER exactly: 'new' IS included (the
        // new→ready_to_pick confirmation flow isn't built, so real orders sit in 'new'
        // the whole time they're pickable — this is the bug fixed 2026-07-28: gather
        // used to filter status = 'ready_to_pick' only and silently miss these).
        // 'packed' and on-hold orders are excluded, same as the queue.
        UUID productId = insertProduct(tenantA, storeA, "GL-P4", "Filter Product");
        UUID variant   = insertVariant(tenantA, productId, "GL-V4", "SKU-FILT", "Filter Variant");

        UUID newOrder     = insertOrder(tenantA, storeA, "GL-NEW",    "#GL-NEW",    "new",           false, Instant.now());
        UUID packedOrder  = insertOrder(tenantA, storeA, "GL-PACKED", "#GL-PACKED", "packed",        false, Instant.now());
        UUID onHoldOrder  = insertOrder(tenantA, storeA, "GL-HOLD",   "#GL-HOLD",   "ready_to_pick", true,  Instant.now());
        UUID readyOrder   = insertOrder(tenantA, storeA, "GL-READY",  "#GL-READY",  "ready_to_pick", false, Instant.now());

        insertOrderItem(tenantA, newOrder,    variant, 1);
        insertOrderItem(tenantA, packedOrder, variant, 1);
        insertOrderItem(tenantA, onHoldOrder, variant, 1);
        insertOrderItem(tenantA, readyOrder,  variant, 1);
        insertPiece(tenantA, variant, "available");
        insertPiece(tenantA, variant, "available");

        FulfillService.GatherListResponse resp = fulfillSvc.getGatherList(null);

        assertThat(resp.orderCount()).isEqualTo(2);
        assertThat(resp.rows()).hasSize(1);
        assertThat(resp.rows().get(0).needed()).isEqualTo(2);
        assertThat(resp.rows().get(0).orderNumbers()).containsExactlyInAnyOrder("#GL-NEW", "#GL-READY");
    }

    @Test
    void e_freshNewOrder_zeroAllocations_appearsWithNeededEqualToFullQuantity() {
        // The production case this whole fix is for: the new→ready_to_pick confirmation
        // flow isn't built, so a freshly-imported order just sits in 'new' — it never
        // becomes 'ready_to_pick'. getQueue() has always shown it; gather must too.
        UUID productId = insertProduct(tenantA, storeA, "GL-P8", "Fresh New Product");
        UUID variant   = insertVariant(tenantA, productId, "GL-V8", "SKU-NEWORD", "Fresh New Variant");
        UUID order     = insertOrder(tenantA, storeA, "GL-O8", "#GL-8", "new", false, Instant.now());
        insertOrderItem(tenantA, order, variant, 3);
        // zero allocations — nothing scanned yet, this order hasn't been touched at all

        FulfillService.GatherListResponse resp = fulfillSvc.getGatherList(null);

        assertThat(resp.orderCount()).isEqualTo(1);
        assertThat(resp.rows()).hasSize(1);
        assertThat(resp.rows().get(0).needed()).isEqualTo(3);
        assertThat(resp.rows().get(0).orderNumbers()).containsExactly("#GL-8");
    }

    @Test
    void f_empty_noEligibleOrders_emptyRowsZeroCount() {
        FulfillService.GatherListResponse resp = fulfillSvc.getGatherList(null);
        assertThat(resp.orderCount()).isZero();
        assertThat(resp.rows()).isEmpty();
    }

    @Test
    void g_limit_returnsOldestNOrdersDemandOnly() {
        UUID productId = insertProduct(tenantA, storeA, "GL-P5", "Limit Product");
        UUID variantOld = insertVariant(tenantA, productId, "GL-VOLD", "SKU-OLD", "Old Variant");
        UUID variantNew = insertVariant(tenantA, productId, "GL-VNEW", "SKU-NEW", "New Variant");

        UUID oldOrder = insertOrder(tenantA, storeA, "GL-OLD", "#GL-OLD", "ready_to_pick", false, Instant.now().minusSeconds(500));
        UUID newOrder = insertOrder(tenantA, storeA, "GL-NEWO", "#GL-NEWO", "ready_to_pick", false, Instant.now().minusSeconds(10));

        insertOrderItem(tenantA, oldOrder, variantOld, 4);
        insertOrderItem(tenantA, newOrder, variantNew, 7);

        FulfillService.GatherListResponse resp = fulfillSvc.getGatherList(1);

        assertThat(resp.orderCount()).isEqualTo(1);
        assertThat(resp.rows()).hasSize(1);
        assertThat(resp.rows().get(0).variantId()).isEqualTo(variantOld);
        assertThat(resp.rows().get(0).needed()).isEqualTo(4);
    }

    @Test
    void h_midPick_activeAllocationsReduceRemaining_andRecalculateShortage() {
        UUID productId = insertProduct(tenantA, storeA, "GL-P6", "Mid-Pick Product");
        UUID variant   = insertVariant(tenantA, productId, "GL-V6", "SKU-MIDPICK", "Mid-Pick Variant");
        UUID order     = insertOrder(tenantA, storeA, "GL-O6", "#GL-6", "ready_to_pick", false, Instant.now());
        UUID orderItemId = insertOrderItem(tenantA, order, variant, 5);

        // 3 of the 5 units already scanned/reserved for THIS order — order stays
        // 'ready_to_pick' the whole time (no 'picking' transition in this codebase).
        for (int i = 0; i < 3; i++) {
            String pieceId = insertPiece(tenantA, variant, "reserved");
            insertAllocation(tenantA, orderItemId, pieceId, "active");
        }
        // 2 more pieces still sitting on the shelf, unreserved.
        insertPiece(tenantA, variant, "available");
        insertPiece(tenantA, variant, "available");

        FulfillService.GatherRow row = fulfillSvc.getGatherList(null).rows().get(0);

        // remaining = 5 needed - 3 already-active-allocated = 2
        assertThat(row.needed()).isEqualTo(2);
        // only the 2 unreserved shelf pieces count as available
        assertThat(row.availableCount()).isEqualTo(2);
        // 2 available >= 2 remaining → no shortage. The pre-fix bug (raw needed=5,
        // available=2) would have wrongly flagged this as a shortage.
        assertThat(row.shortage()).isFalse();
    }

    @Test
    void i_perLineFlooring_overAllocatedLineDoesNotCannibalizeOtherLinesDemand() {
        UUID productId = insertProduct(tenantA, storeA, "GL-P7", "Flooring Product");
        UUID variant   = insertVariant(tenantA, productId, "GL-V7", "SKU-FLOOR", "Flooring Variant");

        // Line A: quantity 2, but 3 active allocations — a stray/corrupt over-allocation
        // that scan()'s FOR-UPDATE guard should prevent in normal operation, but the
        // aggregate must not trust that blindly. Un-floored, (2 - 3) = -1 leaks into the
        // variant's SUM and silently eats into line B's demand below.
        UUID orderCorrupt = insertOrder(tenantA, storeA, "GL-OCORRUPT", "#GL-CORRUPT", "ready_to_pick", false, Instant.now());
        UUID itemCorrupt  = insertOrderItem(tenantA, orderCorrupt, variant, 2);
        for (int i = 0; i < 3; i++) {
            String pieceId = insertPiece(tenantA, variant, "reserved");
            insertAllocation(tenantA, itemCorrupt, pieceId, "active");
        }

        // Line B: healthy, quantity 4, zero allocations — remaining should stay 4.
        UUID orderHealthy = insertOrder(tenantA, storeA, "GL-OHEALTHY", "#GL-HEALTHY", "ready_to_pick", false, Instant.now());
        insertOrderItem(tenantA, orderHealthy, variant, 4);

        FulfillService.GatherRow row = fulfillSvc.getGatherList(null).rows().get(0);

        // Floored: line A contributes 0 (not -1), line B contributes 4 → total 4.
        // Un-floored, the SUM would wrongly read 3 (-1 + 4), silently understating
        // line B's real demand because of line A's corrupt state.
        assertThat(row.needed()).isEqualTo(4);
    }

    @Test
    void j_displayName_composesProductAndVariant_matchingLabelServiceFormat() {
        // Product title is DISTINCT from variant title — must compose as
        // "product - variant" via ProductDisplayName, the exact same helper
        // LabelService uses under the physical piece barcode.
        UUID productId = insertProduct(tenantA, storeA, "GL-P9", "Wireless Mouse");
        UUID variant   = insertVariant(tenantA, productId, "GL-V9", "SKU-MOUSE", "Black");
        UUID order     = insertOrder(tenantA, storeA, "GL-O9", "#GL-9", "ready_to_pick", false, Instant.now());
        insertOrderItem(tenantA, order, variant, 2);
        insertPiece(tenantA, variant, "available");
        insertPiece(tenantA, variant, "available");

        FulfillService.GatherRow row = fulfillSvc.getGatherList(null).rows().get(0);

        assertThat(row.displayName()).isEqualTo("Wireless Mouse - Black");
    }

    @Test
    void k_crossTenantIsolation_tenantBNeverSeesTenantADemand() {
        // Tenant A: seed the same positive-control shape as test `a`.
        UUID productA = insertProduct(tenantA, storeA, "GL-XP1", "Tenant A Product");
        UUID variantA = insertVariant(tenantA, productA, "GL-XVA", "SKU-XA", "Tenant A Variant");
        UUID orderA   = insertOrder(tenantA, storeA, "GL-XOA", "#GL-XA", "ready_to_pick", false, Instant.now());
        insertOrderItem(tenantA, orderA, variantA, 3);
        insertPiece(tenantA, variantA, "available");

        // Same-tenant positive control: tenant A must see its own demand.
        FulfillService.GatherListResponse respA = fulfillSvc.getGatherList(null);
        assertThat(respA.orderCount()).isEqualTo(1);
        assertThat(respA.rows()).hasSize(1);
        assertThat(respA.rows().get(0).variantId()).isEqualTo(variantA);

        // Tenant B: no orders seeded at all — must see nothing, and specifically
        // must never see tenant A's order/variant despite querying in the same call.
        TenantContext.set(tenantB);
        try {
            FulfillService.GatherListResponse respB = fulfillSvc.getGatherList(null);
            assertThat(respB.orderCount()).isZero();
            assertThat(respB.rows()).isEmpty();
        } finally {
            TenantContext.set(tenantA);
        }
    }
}
