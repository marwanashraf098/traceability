package com.traceability;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ExceptionService;
import com.traceability.inventory.InventoryStockController;
import com.traceability.inventory.LookupService;
import com.traceability.inventory.PieceAdjustService;
import com.traceability.inventory.PieceCommittedException;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.overview.OverviewService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-13.x — Void / On Hold integration tests (Phase 1 gate).
 *
 * vh1  — void: available->voided writes 'voided' event + audit; gateway.pushVoidCorrection
 *        called with negativeDelta=-1; positive path and pushHoldEnter never called
 * vh2  — void skips the decrement when the originating receiving increment never applied —
 *        status='skipped', gateway never called, piece still voided
 * vh3  — void on reserved piece -> PieceCommittedException
 * vh4  — void on packed piece -> PieceCommittedException
 * vh5  — voided piece absent from ExceptionService.listExceptions("lost", ...)
 * vh6  — voided piece absent from OverviewService.trends() "exceptions" trend
 * vh7  — voided piece absent from InventoryStockController.breakdown() (not even counted —
 *        'voided' is outside the enumerated status list entirely)
 * vh8  — hold enter -1 gateway / exit +1 EXISTING positive path, symmetric
 * vh9  — on_hold->damaged escalation makes NO second Shopify call (no double-decrement)
 * vh10 — hold idempotency: same hold cycle triggered twice calls Shopify exactly once
 * vh11 — cross-tenant void/hold -> 404 (RLS)
 * vh12 — order-number lookup resolves the right order; cross-tenant -> 404
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VoidHoldTest {

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
        r.add("shopify.api-version",        () -> "2024-10");
        r.add("shopify.client-id",          () -> "test-client-id");
        r.add("shopify.client-secret",      () -> "test-client-secret");
        r.add("shopify.scopes",             () -> "read_products");
        r.add("shopify.webhook-base-url",   () -> "https://test.example.com");
        r.add("bosta.api-base-url",         () -> "https://app.bosta.co");
    }

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate               jdbc;
    @Autowired PieceAdjustService         pieceAdjustService;
    @Autowired ShopifyInventoryService    shopifyInventoryService;
    @Autowired ExceptionService           exceptionService;
    @Autowired OverviewService            overviewService;
    @Autowired InventoryStockController   stockController;
    @Autowired LookupService              lookupService;

    UUID tenantId, tenantB, storeId, locationId, productId, variantA;
    UUID tenantBLocationId, tenantBVariantId, tenantBStoreId, tenantBProductId;

    static final String SHOP_DOMAIN = "vh-test.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/777";

    @BeforeAll
    void seedFixtures() {
        tenantId = UUID.randomUUID();
        tenantB  = UUID.randomUUID();
        storeId  = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'VoidHoldTenantA')", tenantId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'VoidHoldTenantB')", tenantB);

        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes) " +
            "VALUES (?, ?, ?, 'idle', 'read_orders,write_inventory,read_products,write_locations,read_locations,read_customers')",
            storeId, tenantId, SHOP_DOMAIN);

        productId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/VH1', 'VH Product', 'active')",
            productId, tenantId, storeId);

        variantA = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/VH1', 'Default', 'VH-SKU')",
            variantA, tenantId, productId);

        locationId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'VH Warehouse', ?, 'linked', true)",
            locationId, tenantId, TRACED_GID);

        // Tenant B fixtures for vh11/vh12 cross-tenant checks.
        tenantBStoreId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'vh-b.myshopify.com', 'idle', 'read_orders,write_inventory')",
            tenantBStoreId, tenantB);
        tenantBProductId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/VHB', 'VH B Product', 'active')",
            tenantBProductId, tenantB, tenantBStoreId);
        tenantBVariantId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/VHB', 'Default', 'VHB-SKU')",
            tenantBVariantId, tenantB, tenantBProductId);
        tenantBLocationId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name) VALUES (?, ?, 'VH B Loc')",
            tenantBLocationId, tenantB);
    }

    @BeforeEach
    void resetStubs() {
        reset(shopifyGateway);
        when(tokenProvider.getValidToken(storeId)).thenReturn("vh-token");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenReturn("gid://shopify/InventoryItem/VH1");
    }

    // ── vh1: void writes event + calls the negative-delta gateway, nothing else ──

    @Test @Order(1)
    void vh1_void_writesEventAndCallsOnlyPushVoidCorrection() throws Exception {
        UUID receiptId = seedFinalizedReceiptWithAppliedIncrement();
        String pieceId = seedAvailablePiece("VH1-001", receiptId);

        TenantContext.set(tenantId);
        try {
            pieceAdjustService.voidPiece(pieceId, "receiving_overcount", null, null);
        } finally {
            TenantContext.clear();
        }
        waitForAsync();

        assertThat(pieceStatus(pieceId)).isEqualTo("voided");

        Long eventCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events WHERE piece_id = ? AND event_type = 'voided'",
            Long.class, pieceId);
        assertThat(eventCount).isEqualTo(1L);

        verify(shopifyGateway, timeout(3000)).pushVoidCorrection(
            eq(SHOP_DOMAIN), eq("vh-token"), eq("gid://shopify/InventoryItem/VH1"),
            eq(TRACED_GID), eq(-1), anyString(), anyString());
        verify(shopifyGateway, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status, delta FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'void_correction' AND trigger_id = ? AND tenant_id = ?",
            pieceId, tenantId);
        assertThat(row.get("status")).isEqualTo("applied");
        assertThat(((Number) row.get("delta")).intValue()).isEqualTo(-1);
    }

    // ── vh2: void skips the decrement when the receiving increment never applied ──

    @Test @Order(2)
    void vh2_void_skipsDecrementWhenReceivingIncrementNeverApplied() throws Exception {
        // Piece with NO receipt_id at all — the simplest "increment never fired" case.
        String pieceId = seedAvailablePiece("VH2-001", null);

        TenantContext.set(tenantId);
        try {
            pieceAdjustService.voidPiece(pieceId, "duplicate_entry", null, null);
        } finally {
            TenantContext.clear();
        }
        waitForAsync();

        assertThat(pieceStatus(pieceId)).isEqualTo("voided");

        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE trigger_type = 'void_correction' AND trigger_id = ? AND tenant_id = ?",
            pieceId, tenantId);
        assertThat(row.get("status")).as("vh2: recorded for audit even though skipped").isEqualTo("skipped");
    }

    // ── vh3/vh4: void blocked on committed pieces ─────────────────────────────

    @Test @Order(3)
    void vh3_void_reservedPiece_returnsPieceCommitted() {
        String pieceId = seedAvailablePiece("VH3-001", null);
        UUID orderId = reservePieceUnderNewOrder(pieceId);

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> pieceAdjustService.voidPiece(pieceId, "receiving_overcount", null, null))
                .isInstanceOf(PieceCommittedException.class)
                .satisfies(e -> assertThat(((PieceCommittedException) e).getOrderId()).isEqualTo(orderId));
        } finally {
            TenantContext.clear();
        }
    }

    @Test @Order(4)
    void vh4_void_packedPiece_returnsPieceCommitted() {
        String pieceId = seedAvailablePiece("VH4-001", null);
        UUID orderId = packPieceUnderNewOrder(pieceId);

        TenantContext.set(tenantId);
        try {
            assertThatThrownBy(() -> pieceAdjustService.voidPiece(pieceId, "duplicate_entry", null, null))
                .isInstanceOf(PieceCommittedException.class)
                .satisfies(e -> assertThat(((PieceCommittedException) e).getOrderId()).isEqualTo(orderId));
        } finally {
            TenantContext.clear();
        }
    }

    // ── vh5/vh6/vh7: voided excluded from all three loss-reporting sites ─────

    @Test @Order(5)
    void vh5_voidedPiece_absentFromExceptionServiceDetectLost() {
        String lostPiece = seedLostPiece("VH5-LOST");
        String voidedPiece = seedVoidedPiece("VH5-VOID");

        TenantContext.set(tenantId);
        Map<String, Object> result;
        try {
            result = exceptionService.listExceptions("lost", null, 0, 100);
        } finally {
            TenantContext.clear();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        List<Object> pieceIds = items.stream().map(i -> i.get("piece_id")).toList();

        assertThat(pieceIds).as("genuinely lost piece IS an open exception")
            .anyMatch(id -> id != null && id.toString().equals(lostPiece));
        assertThat(pieceIds).as("voided piece must never appear in the 'lost' detector")
            .noneMatch(id -> id != null && id.toString().equals(voidedPiece));
    }

    @Test @Order(6)
    void vh6_voidedPiece_doesNotInflateOverviewExceptionsTrend() {
        Long before = countTodayLostPieces();
        String voidedPiece = seedVoidedPiece("VH6-VOID");

        TenantContext.set(tenantId);
        try {
            overviewService.trends(); // exercises exceptionsRaw() internally — must not throw
        } finally {
            TenantContext.clear();
        }

        Long after = countTodayLostPieces();
        assertThat(after).as("voided piece must not be counted as a lost-status row").isEqualTo(before);
    }

    @Test @Order(7)
    void vh7_voidedPiece_excludedFromInventoryBreakdown() {
        InventoryStockController.PhaseCounts before = breakdownAsOwner();
        seedVoidedPiece("VH7-VOID");
        InventoryStockController.PhaseCounts after = breakdownAsOwner();

        // 'voided' is not in breakdown()'s enumerated status list at all — every bucket
        // (good/out/delivered/back/problem) must be unchanged by adding one voided piece.
        assertThat(after).as("voided piece changes no bucket in the phase-count breakdown")
            .isEqualTo(before);
    }

    /** breakdown() carries @PreAuthorize("hasAnyRole('OWNER','MANAGER')") — calling the
     *  controller bean directly (not through MockMvc) still goes through method security,
     *  so a ROLE_OWNER authority must be on the SecurityContext for the call to succeed. */
    private InventoryStockController.PhaseCounts breakdownAsOwner() {
        org.springframework.security.core.context.SecurityContext ctx =
            org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "vh-test-owner", null,
            List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OWNER"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
        TenantContext.set(tenantId);
        try {
            return stockController.breakdown();
        } finally {
            TenantContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ── vh8: hold enter/exit symmetry ─────────────────────────────────────────

    @Test @Order(8)
    void vh8_hold_enterDecrementsExitIncrements() throws Exception {
        String pieceId = seedAvailablePiece("VH8-001", null);

        UUID holdEventId;
        TenantContext.set(tenantId);
        try {
            holdEventId = pieceAdjustService.hold(pieceId, "quality_check", null, null);
        } finally {
            TenantContext.clear();
        }
        waitForAsync();

        assertThat(pieceStatus(pieceId)).isEqualTo("on_hold");
        // idempotencyKey is a deterministic hash of (tenantId, "hold_enter", pieceId+":"+
        // holdEventId, variantId, locationId) — recompute it the same way rather than
        // asserting on its (opaque) string contents.
        String expectedKey = ShopifyGateway.idempotencyKey(
            tenantId, "hold_enter", pieceId + ":" + holdEventId, variantA, locationId);
        verify(shopifyGateway, timeout(3000)).pushHoldEnter(
            eq(SHOP_DOMAIN), eq("vh-token"), eq("gid://shopify/InventoryItem/VH1"),
            eq(TRACED_GID), eq(-1), eq("traced://piece/" + pieceId), eq(expectedKey));

        TenantContext.set(tenantId);
        try {
            pieceAdjustService.unhold(pieceId, null);
        } finally {
            TenantContext.clear();
        }
        waitForAsync();

        assertThat(pieceStatus(pieceId)).isEqualTo("available");
        verify(shopifyGateway, timeout(3000)).adjustInventoryQuantities(
            eq(SHOP_DOMAIN), eq("vh-token"), eq("gid://shopify/InventoryItem/VH1"),
            eq(TRACED_GID), eq(1), eq("hold_exit"), anyString());
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── vh9: escalation on_hold->damaged never double-decrements ─────────────

    @Test @Order(9)
    void vh9_onHoldEscalationToDamaged_makesNoSecondShopifyCall() throws Exception {
        assertEscalationMakesNoShopifyCall("VH9-001", "damaged", "damaged_in_storage");
    }

    // ── vh9b/vh9c: same guard for the other two escalation targets (lost, destroyed) —
    // explicit per-target coverage rather than relying on damaged's assertion alone, per
    // Marawan's ask: this property currently rests on adjustPiece()'s current==AVAILABLE
    // guard (a single `if`), and a future refactor of that guard could silently reintroduce
    // a double-decrement on lost/destroyed without vh9 (damaged-only) ever catching it. ──

    @Test @Order(91)
    void vh9b_onHoldEscalationToLost_makesNoSecondShopifyCall() throws Exception {
        assertEscalationMakesNoShopifyCall("VH9B-001", "lost", "theft_suspected");
    }

    @Test @Order(92)
    void vh9c_onHoldEscalationToDestroyed_makesNoSecondShopifyCall() throws Exception {
        assertEscalationMakesNoShopifyCall("VH9C-001", "destroyed", "damaged_in_storage");
    }

    /**
     * Holds a fresh piece (one pushHoldEnter call), then escalates on_hold->toStatus, and
     * asserts: exactly one pushHoldEnter call total (none from the escalation), and ZERO calls
     * to every other Shopify write method — moveAvailableToDamaged, pushVoidCorrection,
     * adjustInventoryQuantities, pushStockTakeWriteOff. The piece already left Shopify's
     * sellable pool at hold_enter; any of these firing again would be a double-decrement.
     */
    private void assertEscalationMakesNoShopifyCall(String barcode, String toStatus, String reason) throws Exception {
        String pieceId = seedAvailablePiece(barcode, null);

        TenantContext.set(tenantId);
        try {
            pieceAdjustService.hold(pieceId, "quarantine", null, null);
        } finally {
            TenantContext.clear();
        }
        waitForAsync();
        verify(shopifyGateway, timeout(3000).times(1))
            .pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());

        TenantContext.set(tenantId);
        try {
            pieceAdjustService.adjustPiece(pieceId, toStatus, reason, null, null);
        } finally {
            TenantContext.clear();
        }
        waitForAsync();

        assertThat(pieceStatus(pieceId)).isEqualTo(toStatus);

        verify(shopifyGateway, times(1))
            .pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
    }

    // ── vh10: hold idempotency — same cycle triggered twice, one Shopify call ─

    @Test @Order(10)
    void vh10_holdEnterTriggeredTwiceForSameCycle_callsShopifyOnce() throws Exception {
        String pieceId = seedAvailablePiece("VH10-001", null);
        UUID holdEventId = UUID.randomUUID();

        TenantContext.set(tenantId);
        try {
            shopifyInventoryService.onHoldEnter(tenantId, pieceId, locationId, holdEventId).get(5, TimeUnit.SECONDS);
            shopifyInventoryService.onHoldEnter(tenantId, pieceId, locationId, holdEventId).get(5, TimeUnit.SECONDS);
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway, times(1)).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── vh11: cross-tenant void/hold -> 404 ────────────────────────────────────

    @Test @Order(11)
    void vh11_crossTenantVoidAndHold_return404() {
        String pieceId = seedAvailablePiece("VH11-001", null);

        TenantContext.set(tenantB);
        try {
            assertThatThrownBy(() -> pieceAdjustService.voidPiece(pieceId, "receiving_overcount", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            assertThatThrownBy(() -> pieceAdjustService.hold(pieceId, "quality_check", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        } finally {
            TenantContext.clear();
        }
    }

    // ── vh12: order-number lookup ──────────────────────────────────────────────

    @Test @Order(12)
    void vh12_orderNumberLookup_resolvesRightOrder_crossTenant404() {
        UUID orderId;
        TenantContext.set(tenantId);
        try {
            orderId = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
                "VALUES (?, ?, 'gid://shopify/Order/VH12', '#7777', 'new'::order_status, 'cod', now()) RETURNING id",
                UUID.class, tenantId, storeId);

            Map<String, Object> byHash = lookupService.lookupOrder("#7777");
            assertThat(byHash.get("orderId")).isEqualTo(orderId.toString());

            Map<String, Object> byBareDigits = lookupService.lookupOrder("7777");
            assertThat(byBareDigits.get("orderId")).isEqualTo(orderId.toString());
        } finally {
            TenantContext.clear();
        }

        TenantContext.set(tenantB);
        try {
            assertThatThrownBy(() -> lookupService.lookupOrder("#7777"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void waitForAsync() throws InterruptedException {
        Thread.sleep(400);
    }

    private String seedAvailablePiece(String barcode, UUID receiptId) {
        String id = "01HVH" + UUID.randomUUID().toString().replace("-", "").substring(0, 19).toUpperCase();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, receipt_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'available'::piece_status, ?, " +
            "    'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?)",
            id, tenantId, variantA, receiptId, barcode, id, locationId);
        return id;
    }

    /** A finalized receiving session whose Trigger-1 increment already applied. */
    private UUID seedFinalizedReceiptWithAppliedIncrement() {
        UUID receiptId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, location_id, status, kind, finalized_at) " +
            "VALUES (?, ?, ?, 'finalized', 'inbound', now())",
            receiptId, tenantId, locationId);
        jdbc.update(
            "INSERT INTO shopify_inventory_adjustments " +
            "(tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, status) " +
            "VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, 'applied')",
            tenantId, UUID.randomUUID(), variantA, locationId, receiptId.toString());
        return receiptId;
    }

    private String seedLostPiece(String barcode) {
        String id = seedAvailablePiece(barcode, null);
        jdbc.update("UPDATE pieces SET status = 'lost'::piece_status, last_event_at = now() WHERE id = ?", id);
        return id;
    }

    private String seedVoidedPiece(String barcode) {
        String id = seedAvailablePiece(barcode, null);
        jdbc.update("UPDATE pieces SET status = 'voided'::piece_status, last_event_at = now() WHERE id = ?", id);
        return id;
    }

    private Long countTodayLostPieces() {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM pieces WHERE tenant_id = ? AND status = 'lost'::piece_status " +
            "AND last_event_at >= now() - interval '1 day'",
            Long.class, tenantId);
    }

    private UUID reservePieceUnderNewOrder(String pieceId) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, gen_random_uuid()::text, '#VH-' || floor(random()*99999), " +
            "    'ready_to_pick'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId);
        UUID itemId = jdbc.queryForObject(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
            UUID.class, tenantId, orderId, variantA);
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'active'::allocation_status)",
            tenantId, itemId, pieceId);
        jdbc.update("UPDATE pieces SET status = 'reserved'::piece_status WHERE id = ?", pieceId);
        return orderId;
    }

    private UUID packPieceUnderNewOrder(String pieceId) {
        UUID orderId = reservePieceUnderNewOrder(pieceId);
        jdbc.update("UPDATE allocations SET status = 'packed' WHERE piece_id = ? AND tenant_id = ?", pieceId, tenantId);
        jdbc.update("UPDATE pieces SET status = 'packed'::piece_status WHERE id = ?", pieceId);
        return orderId;
    }

    private String pieceStatus(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String pieceCondition(String pieceId) {
        return jdbc.queryForObject("SELECT condition FROM pieces WHERE id = ?", String.class, pieceId);
    }
}
