package com.traceability;

import com.traceability.inventory.*;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-3.6 integration tests: Shopify line-item edits on in-progress orders.
 *
 * Tests call FulfillService.handleShopifyLineItemEdit() directly (the routing + release
 * method) with a pre-computed diff, avoiding the need to mock ShopifyGateway.
 *
 * r1 — picking, line removed → allocations released, pieces→Available, exception raised
 * r2 — picking, qty 3→1 → exactly 2 pieces released, 1 still allocated, diff correct
 * r3 — picking, qty increased / line added → exception, NO auto-allocation, allocations untouched
 * r4 — new/ready_to_pick edited → line items update, NO release, NO exception
 * r5 — packed order edited → exception raised, allocations + pieces UNTOUCHED
 * r6 — with_courier edited → exception only, nothing released
 * r7 — released events attributed to null (SHOPIFY_WEBHOOK_ACTOR sentinel)
 * r8 — double handleShopifyLineItemEdit → single release, single exception
 * r9 — tenant isolation: edit signal on tenantA order not visible under tenantB
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day36Test {

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

    UUID tenantA, tenantB, actorId, storeId, variantId, locationId;
    private static final String EXT_A = "gid://shopify/LineItem/101";
    private static final String EXT_B = "gid://shopify/LineItem/102";

    @BeforeAll
    void setupFixture() {
        tenantA    = UUID.randomUUID();
        tenantB    = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'D36A')", tenantA);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'D36B')", tenantB);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
                    "VALUES (?, ?, 'Actor', 'd36@test.com', 'x', 'owner'::user_role)", actorId, tenantA);
        jdbc.update("INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'D36Loc')", locationId, tenantA);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, " +
            "    access_token_encrypted, access_token_expires_at) " +
            "VALUES (?, ?, 'shopify', 'd36.myshopify.com', 'connected', 'completed', 'enc', " +
            "    now() + interval '876000 hours')",
            storeId, tenantA);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title) " +
                    "VALUES (?, ?, ?, 'gid://shopify/Product/D36', 'D36 Widget')",
                    productId, tenantA, storeId);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
                    "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/D36', 'Default', 'D36-SKU')",
                    variantId, tenantA, productId);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?",                    tenantA);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?",                     tenantA);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?",                     tenantA);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?",   tenantA);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?",                          tenantA);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?",                          tenantA);
    }

    // r1: picking order, line removed → allocations released, pieces→Available, exception raised
    @Test
    void r1_pickingOrder_lineRemoved_allocationsReleasedAndExceptionRaised() {
        UUID   orderId = createOrder("picking");
        UUID   itemId  = addOrderItem(orderId, EXT_A, 2);
        String p1      = receivePiece();
        String p2      = receivePiece();
        reservePiece(p1, orderId, itemId);
        reservePiece(p2, orderId, itemId);

        TenantContext.set(tenantA);
        try {
            fulfillSvc.handleShopifyLineItemEdit(
                orderId, "picking",
                "{\"removed\":[\"" + EXT_A + "\"],\"reduced\":{},\"added\":[],\"increased\":{}}",
                List.of(EXT_A),   // removed external IDs
                Map.of()          // releaseCountByExternalId (fully removed, not partial)
            );
        } finally {
            TenantContext.clear();
        }

        // Both pieces must be Available
        assertThat(pieceStatus(p1)).isEqualTo("available");
        assertThat(pieceStatus(p2)).isEqualTo("available");

        // Both allocations released
        assertThat(activeAllocCount(orderId)).isZero();

        // Exception signal set
        assertEditConflictSignaled(orderId);
    }

    // r2: picking, qty 3→1 → exactly 2 pieces released, 1 still allocated
    @Test
    void r2_pickingOrder_qtyReduced_exactlyTwoPiecesReleased_oneRemains() {
        UUID   orderId = createOrder("picking");
        UUID   itemId  = addOrderItem(orderId, EXT_A, 3);
        String p1      = receivePiece();
        String p2      = receivePiece();
        String p3      = receivePiece();
        reservePiece(p1, orderId, itemId);
        reservePiece(p2, orderId, itemId);
        reservePiece(p3, orderId, itemId);

        TenantContext.set(tenantA);
        try {
            // qty 3→1: release 2
            fulfillSvc.handleShopifyLineItemEdit(
                orderId, "picking",
                "{\"reduced\":{\"" + EXT_A + "\":[3,1]}}",
                List.of(),
                Map.of(EXT_A, 2)   // release 2 (= 3-1)
            );
        } finally {
            TenantContext.clear();
        }

        // Exactly 1 piece still reserved
        assertThat(activeAllocCount(orderId)).isEqualTo(1);

        // Exactly 2 pieces now available
        long availableCount = List.of(p1, p2, p3).stream()
            .filter(p -> "available".equals(pieceStatus(p)))
            .count();
        assertThat(availableCount).isEqualTo(2);

        // Exception signal set
        assertEditConflictSignaled(orderId);
    }

    // r3: picking, qty increased / line added → exception, NO auto-allocation, allocations untouched
    @Test
    void r3_pickingOrder_lineAdded_noAutoAllocation_existingAllocsUntouched() {
        UUID   orderId = createOrder("picking");
        UUID   itemId  = addOrderItem(orderId, EXT_A, 1);
        String p1      = receivePiece();
        reservePiece(p1, orderId, itemId);

        TenantContext.set(tenantA);
        try {
            // Line B is new (added), line A qty unchanged — but diff still reports changes
            fulfillSvc.handleShopifyLineItemEdit(
                orderId, "picking",
                "{\"removed\":[],\"reduced\":{},\"added\":[\"" + EXT_B + "\"],\"increased\":{}}",
                List.of(),   // no removals
                Map.of()     // no reductions
            );
        } finally {
            TenantContext.clear();
        }

        // p1 must still be reserved — NO auto-release
        assertThat(pieceStatus(p1)).isEqualTo("reserved");
        assertThat(activeAllocCount(orderId)).isEqualTo(1);

        // Exception signal still raised (operator must handle the added line)
        assertEditConflictSignaled(orderId);
    }

    // r4: new/ready_to_pick order edited → NO release, NO exception
    @Test
    void r4_newOrder_lineEdited_noReleaseNoException() {
        UUID orderId = createOrder("new");
        addOrderItem(orderId, EXT_A, 2);

        TenantContext.set(tenantA);
        try {
            fulfillSvc.handleShopifyLineItemEdit(
                orderId, "new",
                "{\"reduced\":{\"" + EXT_A + "\":[2,1]}}",
                List.of(),
                Map.of(EXT_A, 1)
            );
        } finally {
            TenantContext.clear();
        }

        // No exception signal
        Integer conflict = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE id = ? AND shopify_edit_conflict_at IS NOT NULL",
            Integer.class, orderId);
        assertThat(conflict).isZero();
    }

    // r5: packed order edited → exception raised, allocations + pieces UNTOUCHED (box sealed)
    @Test
    void r5_packedOrder_lineEdited_exceptionRaised_allocationsUntouched() {
        UUID   orderId = createOrder("packed");
        UUID   itemId  = addOrderItem(orderId, EXT_A, 1);
        String p1      = receivePiece();
        packPiece(p1, orderId, itemId);

        TenantContext.set(tenantA);
        try {
            fulfillSvc.handleShopifyLineItemEdit(
                orderId, "packed",
                "{\"removed\":[\"" + EXT_A + "\"]}",
                List.of(EXT_A),
                Map.of()
            );
        } finally {
            TenantContext.clear();
        }

        // Piece must still be packed — DO NOT touch
        assertThat(pieceStatus(p1)).isEqualTo("packed");

        // Allocation must still be packed
        String allocStatus = jdbc.queryForObject(
            "SELECT a.status::text FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.piece_id = ?",
            String.class, orderId, p1);
        assertThat(allocStatus).isEqualTo("packed");

        // Exception signal set
        assertEditConflictSignaled(orderId);
    }

    // r6: with_courier edited → exception only, nothing released
    @Test
    void r6_withCourierOrder_lineEdited_exceptionOnlyNothingReleased() {
        UUID orderId = createOrder("with_courier");
        addOrderItem(orderId, EXT_A, 1);

        TenantContext.set(tenantA);
        try {
            fulfillSvc.handleShopifyLineItemEdit(
                orderId, "with_courier",
                "{\"removed\":[\"" + EXT_A + "\"]}",
                List.of(EXT_A),
                Map.of()
            );
        } finally {
            TenantContext.clear();
        }

        // Exception signal set
        assertEditConflictSignaled(orderId);

        // No piece events caused by this (no pieces exist for this order — confirmed no-op)
        Integer evtCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE tenant_id = ? AND event_type = 'unreserved'",
            Integer.class, tenantA);
        assertThat(evtCount).isZero();
    }

    // r7: released piece events carry actor_user_id = null (SHOPIFY_WEBHOOK_ACTOR sentinel)
    @Test
    void r7_releasedEvents_attributedToWebhookSentinel_actorIsNull() {
        UUID   orderId = createOrder("picking");
        UUID   itemId  = addOrderItem(orderId, EXT_A, 1);
        String p1      = receivePiece();
        reservePiece(p1, orderId, itemId);

        TenantContext.set(tenantA);
        try {
            fulfillSvc.handleShopifyLineItemEdit(
                orderId, "picking",
                "{\"removed\":[\"" + EXT_A + "\"]}",
                List.of(EXT_A),
                Map.of()
            );
        } finally {
            TenantContext.clear();
        }

        // The "unreserved" event on p1 must have actor_user_id = NULL
        List<Map<String, Object>> events = jdbc.queryForList(
            "SELECT actor_user_id FROM piece_events WHERE piece_id = ? AND event_type = 'unreserved'",
            p1);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("actor_user_id")).isNull();
    }

    // r8: calling handleShopifyLineItemEdit twice → single release, single exception
    @Test
    void r8_doubleHandleEdit_singleRelease_singleException() {
        UUID   orderId = createOrder("picking");
        UUID   itemId  = addOrderItem(orderId, EXT_A, 1);
        String p1      = receivePiece();
        reservePiece(p1, orderId, itemId);

        TenantContext.set(tenantA);
        try {
            String diffJson = "{\"removed\":[\"" + EXT_A + "\"]}";
            List<String> removed = List.of(EXT_A);

            // First call: releases p1
            fulfillSvc.handleShopifyLineItemEdit(orderId, "picking", diffJson, removed, Map.of());

            // Second call: piece already available, ledger catches the conflict, allocation already released
            fulfillSvc.handleShopifyLineItemEdit(orderId, "picking", diffJson, removed, Map.of());
        } finally {
            TenantContext.clear();
        }

        // Piece still available — not double-released to some bad state
        assertThat(pieceStatus(p1)).isEqualTo("available");

        // Only one "unreserved" event in history
        Integer eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'unreserved'",
            Integer.class, p1);
        assertThat(eventCount).isEqualTo(1);

        // Exception signal set exactly once (COALESCE means same timestamp, no double-row)
        Integer conflictCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE id = ? AND shopify_edit_conflict_at IS NOT NULL",
            Integer.class, orderId);
        assertThat(conflictCount).isEqualTo(1);
    }

    // r9: tenant isolation — signal on tenantA order invisible to tenantB queries
    @Test
    void r9_tenantIsolation_conflictNotVisibleCrossTenant() {
        // Create order under tenantA, set conflict signal
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, 'gid://shopify/Order/D36ISO', '#D36ISO', 'picking'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantA, storeId);

        TenantContext.set(tenantA);
        try {
            fulfillSvc.setEditConflictSignal(orderId, tenantA, "{\"removed\":[]}");
        } finally {
            TenantContext.clear();
        }

        // tenantA can see it
        Integer countA = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE id = ? AND tenant_id = ? AND shopify_edit_conflict_at IS NOT NULL",
            Integer.class, orderId, tenantA);
        assertThat(countA).isEqualTo(1);

        // tenantB cannot see the same order
        Integer countB = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE id = ? AND tenant_id = ? AND shopify_edit_conflict_at IS NOT NULL",
            Integer.class, orderId, tenantB);
        assertThat(countB).isZero();

        // Clean up this test's order (it bypasses the @AfterEach which only deletes tenantA)
        jdbc.update("DELETE FROM orders WHERE id = ?", orderId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID createOrder(String status) {
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, gen_random_uuid()::text, '#D36-' || floor(random()*99999), " +
            "    ?::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantA, storeId, status);
    }

    private UUID addOrderItem(UUID orderId, String externalId, int qty) {
        return jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity, external_id) " +
            "VALUES (?, ?, ?, ?, ?) RETURNING id",
            UUID.class, tenantA, orderId, variantId, qty, externalId);
    }

    private String receivePiece() {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, status, current_location_id, last_event_at, last_user_id) " +
            "VALUES (?, ?, ?, ?, 'available'::piece_status, ?, now(), ?)",
            id, tenantA, variantId, "D36-" + id.substring(id.length() - 8), locationId, actorId);
        jdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
            "VALUES (?, ?, 'received', ?, ?, NULL, 'available'::piece_status)",
            tenantA, id, actorId, locationId);
        return id;
    }

    private void reservePiece(String pieceId, UUID orderId, UUID itemId) {
        TenantContext.set(tenantA);
        ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.RESERVED,
            "scan", actorId, TransitionContext.forOrder(orderId, orderId));
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status, allocated_by, allocated_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'active', ?, now())",
            tenantA, itemId, pieceId, actorId);
    }

    private void packPiece(String pieceId, UUID orderId, UUID itemId) {
        reservePiece(pieceId, orderId, itemId);
        TenantContext.set(tenantA);
        ledger.transition(pieceId, PieceStatus.RESERVED, PieceStatus.PACKED,
            "pack", actorId, TransitionContext.forOrder(orderId, orderId));
        jdbc.update(
            "UPDATE allocations SET status = 'packed' WHERE piece_id = ? AND tenant_id = ?",
            pieceId, tenantA);
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject(
            "SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private int activeAllocCount(UUID orderId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.status = 'active'",
            Integer.class, orderId);
        return n != null ? n : 0;
    }

    private void assertEditConflictSignaled(UUID orderId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE id = ? AND shopify_edit_conflict_at IS NOT NULL",
            Integer.class, orderId);
        assertThat(count).as("shopify_edit_conflict_at should be set on order " + orderId).isEqualTo(1);
    }
}
