package com.traceability;

import com.traceability.catalog.CatalogController;
import com.traceability.identity.CustomUserDetails;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 0 committed-inventory fix — committed/available/on_hand (VariantStockService,
 * called from CatalogController).
 *
 * Formula:
 *   committed(V) = SUM over order lines on orders.status='new' of
 *                  MAX(0, order_item.quantity - COUNT(active allocations on that line))
 *   on_hand(V)   = COUNT(pieces status='available' at an is_fulfillment location)
 *   available(V) = on_hand - committed   (not floored — a negative value is a real short)
 *
 * 'new' is the ONLY pre-pack orders.status value ever written by app code (see
 * VariantStockService's own doc comment) — no other order_status value belongs in the
 * committed window; leaving it isolates the exact production write pattern.
 *
 * Matrix:
 *   c1  — committed sums only status='new' lines, net of active allocations
 *   c1b — a piece scanned to a 'new' order's line already drops that line's committed
 *         contribution (double-covering guard)
 *   c2  — identity: available = on_hand - committed, NOT floored (negative = real short)
 *   c3  — on_hand excludes reserved/packed pieces and non-fulfillment locations
 *   revertToConfirm(a) — one order/SKU walked through REAL FulfillService/InventoryLedger
 *         calls: placed -> scan -> pack -> tracking_linked (awaiting_pickup) ->
 *         courier_update (with_courier) -> courier_update (delivered). committed must
 *         read 1,0,0,0,0,0 at each step. This is the literal root-cause reproduction:
 *         orders.status is asserted to still read 'packed' after real delivery (nothing
 *         ever advances it further), proving the OLD orders.status-keyed formula would
 *         have stayed stuck at committed=1 forever from the 'packed' step on.
 *   revertToConfirm(b) — placed -> cancelOrder() with NO piece ever scanned. Load-bearing
 *         for the 'new'-only predicate: proves cancelOrder() itself (not a separate
 *         cancelled_at/flag column) is what takes the order out of the committed window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommittedInventoryTest {

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

    @Autowired CatalogController catalogCtl;
    @Autowired FulfillService    fulfillSvc;
    @Autowired InventoryLedger   ledger;
    @Autowired JdbcTemplate      jdbc;

    UUID tenantId, ownerId, storeId, productId;

    @BeforeAll
    void setup() {
        tenantId  = UUID.randomUUID();
        ownerId   = UUID.randomUUID();
        storeId   = UUID.randomUUID();
        productId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'CommittedT1')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Owner', 'o@committed.local', 'h', 'owner')",
            ownerId, tenantId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'committed-test.myshopify.com', 'disconnected')",
            storeId, tenantId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'P-CMT', 'CommittedProduct', 'active')",
            productId, tenantId, storeId);
    }

    @BeforeEach
    void ctx() {
        TenantContext.set(tenantId);
        var p = new CustomUserDetails(ownerId, tenantId, "owner", null);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities()));
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        // pieces.current_order_id FKs to orders and piece_events FKs to both pieces and
        // orders — pieces/piece_events must go before orders, allocations before order_items.
        jdbc.update("DELETE FROM allocations  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces       WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM order_items  WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders       WHERE tenant_id = ?", tenantId);
        // V61: at most one is_fulfillment=true location per tenant — each test method that
        // creates one must not leak it into the next.
        jdbc.update("DELETE FROM locations    WHERE tenant_id = ?", tenantId);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private UUID insertVariant(String externalId, String sku) {
        UUID variantId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            variantId, tenantId, productId, externalId, sku, sku);
        return variantId;
    }

    private UUID insertLocation(String name, boolean isFulfillment) {
        UUID locId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_fulfillment) " +
            "VALUES (?, ?, ?, 'warehouse', ?)",
            locId, tenantId, name, isFulfillment);
        return locId;
    }

    private UUID insertOrder(String externalId, String status) {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO orders (id, tenant_id, store_id, external_id, status, on_hold) " +
            "VALUES (?, ?, ?, ?, ?::order_status, false)",
            orderId, tenantId, storeId, externalId, status);
        return orderId;
    }

    private UUID insertOrderItem(UUID orderId, UUID variantId, int quantity) {
        UUID itemId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
            "VALUES (?, ?, ?, ?, ?)",
            itemId, tenantId, orderId, variantId, quantity);
        return itemId;
    }

    private String insertPiece(UUID variantId, UUID locationId, String status) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
            "        ?::piece_status, ?)",
            id, tenantId, variantId, "PC-" + id, id, status, locationId);
        return id;
    }

    private void insertActiveAllocation(UUID orderItemId, String pieceId, String status) {
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?::allocation_status)",
            tenantId, orderItemId, pieceId, status);
    }

    private CatalogController.VariantRow variantRow(UUID variantId) {
        return catalogCtl.list().products().stream()
            .flatMap(p -> p.variants().stream())
            .filter(v -> v.id().equals(variantId.toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("variant not found in catalog: " + variantId));
    }

    // ── c1: committed window (orders.status='new' only) ──────────────────────

    @Test
    void c1_committed_sumsOnlyNewStatusLines_netOfActiveAllocations() {
        UUID variantId = insertVariant("gid://shopify/ProductVariant/C1", "SKU-C1");

        // One order per status, distinct quantity, no allocations — so the sum
        // uniquely identifies exactly which orders contributed.
        record OrderFixture(String status, int quantity) {}
        var fixtures = java.util.List.of(
            new OrderFixture("new",             1),
            new OrderFixture("packed",          5),
            new OrderFixture("awaiting_pickup", 6),
            new OrderFixture("with_courier",    7),
            new OrderFixture("delivered",       8),
            new OrderFixture("returning",       9),
            new OrderFixture("returned",       10),
            new OrderFixture("lost",           11),
            new OrderFixture("cancelled",      12),
            new OrderFixture("self_pickup_pending", 13)
        );

        int i = 0;
        for (var f : fixtures) {
            UUID orderId = insertOrder("EXT-C1-" + (i++), f.status());
            insertOrderItem(orderId, variantId, f.quantity());
        }

        long committed = variantRow(variantId).committed();

        // Only the 'new' order (qty=1) is in-window. A trivial always-0 impl fails
        // this (nonzero required); an always-sum-everything impl fails this too
        // (would be 1+5+6+7+8+9+10+11+12+13=82, not 1).
        assertThat(committed)
            .as("committed must equal the exact sum of orders.status='new' lines only")
            .isEqualTo(1L);
    }

    @Test
    void c1b_committed_netsOutActiveAllocations_notFlooredNegativeAtSqlLevel() {
        UUID variantId = insertVariant("gid://shopify/ProductVariant/C1B", "SKU-C1B");
        UUID orderId   = insertOrder("EXT-C1B", "new");
        UUID itemId    = insertOrderItem(orderId, variantId, 3);

        // No allocation yet: full quantity is committed.
        assertThat(variantRow(variantId).committed()).isEqualTo(3L);

        // One active allocation nets out one unit of demand.
        UUID fulfillmentLoc = insertLocation("C1B WH", true);
        String piece = insertPiece(variantId, fulfillmentLoc, "reserved");
        insertActiveAllocation(itemId, piece, "active");
        assertThat(variantRow(variantId).committed())
            .as("one active allocation must net out one unit of demand: MAX(0, 3-1)=2")
            .isEqualTo(2L);

        // Defensive: allocations exceeding quantity (can't happen via FulfillService.scan()'s
        // own LINE_FILLED guard, but the SQL formula must not go negative if it ever did) —
        // GREATEST(...,0) must floor the per-line contribution at zero, not let it go negative
        // and silently cancel out a different line's demand in the SUM.
        String piece2 = insertPiece(variantId, fulfillmentLoc, "reserved");
        String piece3 = insertPiece(variantId, fulfillmentLoc, "reserved");
        String piece4 = insertPiece(variantId, fulfillmentLoc, "reserved");
        insertActiveAllocation(itemId, piece2, "active");
        insertActiveAllocation(itemId, piece3, "active");
        insertActiveAllocation(itemId, piece4, "active");
        assertThat(variantRow(variantId).committed())
            .as("4 active allocations against qty=3 must floor this line's contribution at 0, not go negative")
            .isEqualTo(0L);
    }

    // ── c2: identity — available = on_hand - committed, not floored ──────────

    @Test
    void c2_identity_availableEqualsOnHandMinusCommitted_notFlooredWhenNegative() {
        UUID variantId = insertVariant("gid://shopify/ProductVariant/C2", "SKU-C2");
        UUID fulfillmentLoc = insertLocation("C2 WH", true);

        // 1 available piece on hand, but 3 units committed (no allocation) -> short by 2.
        insertPiece(variantId, fulfillmentLoc, "available");
        UUID orderId = insertOrder("EXT-C2", "new");
        insertOrderItem(orderId, variantId, 3);

        var row = variantRow(variantId);
        assertThat(row.committed()).isEqualTo(3L);
        assertThat(row.available())
            .as("available = on_hand(1) - committed(3) = -2, must NOT be floored at 0")
            .isEqualTo(-2L);
    }

    // ── c3: on_hand excludes reserved/packed pieces and non-fulfillment locations ──

    @Test
    void c3_onHand_excludesReservedPackedPiecesAndNonFulfillmentLocations() {
        UUID variantId = insertVariant("gid://shopify/ProductVariant/C3", "SKU-C3");
        UUID fulfillmentLoc    = insertLocation("C3 Fulfillment WH", true);
        UUID nonFulfillmentLoc = insertLocation("C3 Showroom", false);

        insertPiece(variantId, fulfillmentLoc, "available");     // counts
        insertPiece(variantId, nonFulfillmentLoc, "available");  // wrong location
        insertPiece(variantId, fulfillmentLoc, "reserved");      // wrong status
        insertPiece(variantId, fulfillmentLoc, "packed");        // wrong status

        var row = variantRow(variantId);
        assertThat(row.committed()).isZero();
        assertThat(row.available())
            .as("on_hand must count exactly the one available piece at the fulfillment location")
            .isEqualTo(1L);
    }

    // ── revert-to-confirm (a): real lifecycle, single order, no cancel ────────

    @Test
    void revertToConfirm_a_realLifecycle_placed_scan_pack_courierDeliver() {
        UUID variantId = insertVariant("gid://shopify/ProductVariant/RTCa", "SKU-RTC-A");
        UUID fulfillmentLoc = insertLocation("RTCa WH", true);
        String pieceId = insertPiece(variantId, fulfillmentLoc, "available");

        UUID orderId = insertOrder("EXT-RTC-A", "new");
        insertOrderItem(orderId, variantId, 1);

        // Stage: placed, nothing scanned.
        assertThat(variantRow(variantId).committed())
            .as("placed, unscanned: committed=1").isEqualTo(1L);

        // Stage: scan — real FulfillService.scan(), same call path the pick screen uses.
        String barcode = "PC-" + pieceId;
        FulfillService.ScanResult scanResult = fulfillSvc.scan(orderId, barcode, ownerId);
        assertThat(scanResult.success())
            .as("scan must succeed for this fixture: " + scanResult.code() + " " + scanResult.message())
            .isTrue();
        assertThat(variantRow(variantId).committed())
            .as("piece scanned: allocation already covers the demand, committed=0").isEqualTo(0L);

        // Stage: pack — real FulfillService.complete(). Sets orders.status='packed'
        // (single-item order, not self-pickup) — this is the value orders.status is
        // stuck at forever from here on for a courier-fulfilled order.
        int packedCount = fulfillSvc.complete(orderId, ownerId);
        assertThat(packedCount).isEqualTo(1);
        assertThat(variantRow(variantId).committed())
            .as("packed: order left the 'new' window, committed=0").isEqualTo(0L);

        // Stage: tracking_linked (packed -> awaiting_pickup) — the exact event_type
        // ShipmentLinkService.transitionPackedPieces() writes on AWB-link.
        ledger.transition(pieceId, PieceStatus.PACKED, PieceStatus.AWAITING_PICKUP,
            "tracking_linked", ownerId, new TransitionContext(orderId, null, null, orderId, null));
        assertThat(variantRow(variantId).committed())
            .as("awaiting_pickup: committed=0").isEqualTo(0L);

        // Stage: courier_update (awaiting_pickup -> with_courier) — the exact event_type
        // BostaWebhookJob writes for every courier state change.
        ledger.transition(pieceId, PieceStatus.AWAITING_PICKUP, PieceStatus.WITH_COURIER,
            "courier_update", null, new TransitionContext(orderId, null, null, orderId, null));
        assertThat(variantRow(variantId).committed())
            .as("with_courier: committed=0").isEqualTo(0L);

        // Stage: courier_update (with_courier -> delivered) — real delivery.
        ledger.transition(pieceId, PieceStatus.WITH_COURIER, PieceStatus.DELIVERED,
            "courier_update", null, new TransitionContext(orderId, null, null, orderId, null));
        assertThat(variantRow(variantId).committed())
            .as("delivered: committed=0").isEqualTo(0L);

        // Root-cause proof: orders.status never advanced past 'packed' — nothing in
        // production code writes 'with_courier'/'delivered' to orders.status for a
        // courier-fulfilled order. This is exactly why the OLD orders.status-keyed
        // formula (window including 'packed') would have read committed=1 at every
        // one of the last four assertions above, not just failed once.
        String finalOrderStatus = jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
        assertThat(finalOrderStatus)
            .as("orders.status must still read 'packed' after real delivery — proves the root cause")
            .isEqualTo("packed");
    }

    // ── revert-to-confirm (b): cancel with no piece ever scanned ──────────────

    @Test
    void revertToConfirm_b_cancelOrder_withNoPieceScanned_dropsCommittedToZero() {
        UUID variantId = insertVariant("gid://shopify/ProductVariant/RTCb", "SKU-RTC-B");
        UUID orderId = insertOrder("EXT-RTC-B", "new");
        insertOrderItem(orderId, variantId, 1);

        assertThat(variantRow(variantId).committed())
            .as("placed: committed=1").isEqualTo(1L);

        // Real FulfillService.cancelOrder() — no scan ever happened on this order.
        FulfillService.CancelResult result = fulfillSvc.cancelOrder(orderId, ownerId);
        assertThat(result.status())
            .as("a 'new' order with zero packed pieces must cancel immediately, not defer")
            .isEqualTo("cancelled");

        assertThat(variantRow(variantId).committed())
            .as("cancelled: committed=0 — proves cancelOrder() itself (not a separate flag) " +
                "is what the 'new'-only predicate relies on")
            .isEqualTo(0L);

        String finalOrderStatus = jdbc.queryForObject(
            "SELECT status::text FROM orders WHERE id = ?", String.class, orderId);
        assertThat(finalOrderStatus).isEqualTo("cancelled");
    }
}
