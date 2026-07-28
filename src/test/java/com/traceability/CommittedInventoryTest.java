package com.traceability;

import com.traceability.catalog.CatalogController;
import com.traceability.identity.CustomUserDetails;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Committed / Available inventory columns (derive-on-read, no stored counter).
 *
 * Matrix:
 *   c1 — committed sums only the pre-courier, non-cancelled order_status window
 *        and excludes every terminal/cancelled/with_courier+ status; same-tenant
 *        positive control (a nonzero, exact sum, not a trivial always-zero pass)
 *   c2 — identity: available = on_hand(fulfillment) - committed holds across a
 *        full order lifecycle (new -> ... -> with_courier), including the courier
 *        handoff where on_hand and committed both drop by the same amount
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
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders      WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces       WHERE tenant_id = ?", tenantId);
    }

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

    private void insertOrderItem(UUID orderId, UUID variantId, int quantity) {
        jdbc.update(
            "INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?)",
            tenantId, orderId, variantId, quantity);
    }

    private CatalogController.VariantRow variantRow(UUID variantId) {
        return catalogCtl.list().products().stream()
            .flatMap(p -> p.variants().stream())
            .filter(v -> v.id().equals(variantId.toString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("variant not found in catalog: " + variantId));
    }

    // ── c1: committed window + same-tenant positive control ──────────────────

    @Test
    void c1_committed_sumsOnlyPreCourierNonCancelledWindow() {
        UUID variantId = insertVariant("gid://shopify/ProductVariant/C1", "SKU-C1");

        // One order per status, each with a distinct quantity, so the sum uniquely
        // identifies exactly which orders contributed.
        record OrderFixture(String status, int quantity) {}
        List<OrderFixture> fixtures = List.of(
            new OrderFixture("new",            1),
            new OrderFixture("confirmed",      2),
            new OrderFixture("ready_to_pick",  3),
            new OrderFixture("picking",        4),
            new OrderFixture("packed",         5),
            new OrderFixture("awaiting_pickup",6),
            new OrderFixture("with_courier",   7),
            new OrderFixture("delivered",      8),
            new OrderFixture("returning",      9),
            new OrderFixture("returned",      10),
            new OrderFixture("lost",          11),
            new OrderFixture("cancelled",     12)
        );

        int i = 0;
        for (OrderFixture f : fixtures) {
            UUID orderId = insertOrder("EXT-C1-" + (i++), f.status());
            insertOrderItem(orderId, variantId, f.quantity());
        }

        long committed = variantRow(variantId).committed();

        // In-window sum: 1+2+3+4+5+6 = 21. Nonzero and exact — proves both that
        // excluded statuses (7..12, summing to 57) are dropped AND that included
        // ones are actually counted (a trivial always-0 implementation would fail
        // this, not just an always-count-everything implementation summing to 78).
        assertThat(committed)
            .as("committed must equal the exact sum of the pre-courier, non-cancelled window")
            .isEqualTo(21L);
    }

    // ── c2: identity — available = on_hand(fulfillment) - committed ──────────

    @Test
    void c2_identity_availableConstantAcrossLifecycle_dropsTogetherAtCourierHandoff() {
        UUID variantId = insertVariant("gid://shopify/ProductVariant/C2", "SKU-C2");
        UUID fulfillmentLoc = insertLocation("C2 Fulfillment WH", true);

        // 5 pieces on hand at the fulfillment location, all available, no orders yet.
        for (int i = 0; i < 5; i++) {
            String pieceId = "01HC2TEST00000000000000" + i;
            jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
                "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), " +
                "        'available'::piece_status, ?)",
                pieceId, tenantId, variantId, "BC-C2-" + i, pieceId, fulfillmentLoc);
        }

        assertThat(variantRow(variantId).committed()).isZero();
        assertThat(variantRow(variantId).available()).isEqualTo(5L);

        UUID orderId = insertOrder("EXT-C2-LIFECYCLE", "new");
        insertOrderItem(orderId, variantId, 2);

        // Two of the five pieces get allocated to this order as it progresses.
        List<String> allocatedPieceIds = List.of("01HC2TEST000000000000000", "01HC2TEST000000000000001");

        // Stage: new — order placed, no piece transitions yet.
        assertLifecycleStage(variantId, "new", 5, 2, 3);

        // Stage: confirmed — pieces reserved for the order.
        transitionPieces(allocatedPieceIds, "reserved");
        assertLifecycleStage(variantId, "confirmed", 5, 2, 3);

        // Stage: ready_to_pick / picking — pieces still reserved (picking in progress).
        assertLifecycleStage(variantId, "ready_to_pick", 5, 2, 3);
        assertLifecycleStage(variantId, "picking", 5, 2, 3);

        // Stage: packed — pieces packed.
        transitionPieces(allocatedPieceIds, "packed");
        assertLifecycleStage(variantId, "packed", 5, 2, 3);

        // Stage: awaiting_pickup — pieces awaiting_pickup.
        transitionPieces(allocatedPieceIds, "awaiting_pickup");
        assertLifecycleStage(variantId, "awaiting_pickup", 5, 2, 3);

        // Stage: with_courier — courier handoff. Both on_hand and committed drop
        // by 2 (the allocated pieces leave on_hand statuses; the order leaves the
        // committed window). Available must stay constant at 3.
        transitionPieces(allocatedPieceIds, "with_courier");
        assertLifecycleStage(variantId, "with_courier", 3, 0, 3);
    }

    private void transitionPieces(List<String> pieceIds, String toStatus) {
        for (String pieceId : pieceIds) {
            jdbc.update("UPDATE pieces SET status = ?::piece_status WHERE id = ? AND tenant_id = ?",
                toStatus, pieceId, tenantId);
        }
    }

    private void assertLifecycleStage(UUID variantId, String orderStatus,
                                       long expectedOnHand, long expectedCommitted,
                                       long expectedAvailable) {
        jdbc.update("UPDATE orders SET status = ?::order_status WHERE external_id = 'EXT-C2-LIFECYCLE' AND tenant_id = ?",
            orderStatus, tenantId);

        var row = variantRow(variantId);
        long onHand = row.pieceCounts().get("available")
                    + row.pieceCounts().get("reserved")
                    + row.pieceCounts().get("packed")
                    + row.pieceCounts().get("awaiting_pickup");

        assertThat(onHand)
            .as("stage=%s: on_hand (tenant-wide, single fulfillment location) must equal %d",
                orderStatus, expectedOnHand)
            .isEqualTo(expectedOnHand);
        assertThat(row.committed())
            .as("stage=%s: committed must equal %d", orderStatus, expectedCommitted)
            .isEqualTo(expectedCommitted);
        assertThat(row.available())
            .as("stage=%s: available must equal %d", orderStatus, expectedAvailable)
            .isEqualTo(expectedAvailable);
    }
}
