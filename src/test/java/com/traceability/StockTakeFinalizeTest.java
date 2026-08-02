package com.traceability;

import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FR-21 Step 5: finalize + live Shopify decrement.
 *
 * sft1 — delta computed from stock_take_missing piece_events, proven independent of a
 *        committed piece of the SAME variant in scope; adjustInventoryQuantities never
 *        invoked on this path; inventorySetOnHandQuantities structurally unreachable.
 * sft2 — claim row is committed ('pending') BEFORE the HTTP call is ever made.
 * sft3 — double-finalize idempotency: status guard (409) + UNIQUE(session_id) -> one
 *        claim, one job enqueue, one push.
 * sft4 — ambiguous ack -> failed_ambiguous; a second push() invocation does NOT re-call
 *        Shopify (no auto-retry of an ambiguous outcome).
 * sft5 — single mutation covers all variants in one call.
 * sft6 — location/store resolution is deterministic on a multi-store tenant (Step 0.5).
 * sft7 — definitive failure -> failed, and IS safely re-attempted on a second push() call
 *        (simulating JobRunr's own retry), eventually succeeding.
 * sft8 — zero-delta finalize: claim lands 'pushed' directly, no job enqueued.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakeFinalizeTest {

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

    @Autowired JdbcTemplate                   jdbc;
    @Autowired StockTakeService               stockTake;
    @Autowired StockTakeReconciliationService reconciliation;
    @Autowired StockTakeShopifyPushJob        pushJob;
    @Autowired InventoryLedger                ledger;

    UUID tenantId, actorId, storeId, productId, variantA, variantB, fulfillmentLocationId;

    static final String SHOP_DOMAIN = "sft.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/SFT-TRACED";

    @BeforeAll
    void setupFixture() {
        tenantId   = UUID.randomUUID();
        actorId    = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        productId  = UUID.randomUUID();
        variantA   = UUID.randomUUID();
        variantB   = UUID.randomUUID();
        fulfillmentLocationId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'StockTakeFinalizeTenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) " +
            "VALUES (?, ?, 'Actor', 'sft@test.com', 'x', 'owner'::user_role)",
            actorId, tenantId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main WH', true, ?, 'linked')",
            fulfillmentLocationId, tenantId, TRACED_GID);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, ?, 'idle', 'read_products,write_inventory', now())",
            storeId, tenantId, SHOP_DOMAIN);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/SFT', 'Sft Product', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/SFTA', 'Variant A', 'SFT-A')",
            variantA, tenantId, productId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'gid://shopify/ProductVariant/SFTB', 'Variant B', 'SFT-B')",
            variantB, tenantId, productId);
    }

    @BeforeEach
    void resetStubs() {
        reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(storeId)).thenReturn("sft-token");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenAnswer(inv -> "gid://shopify/InventoryItem/" + inv.getArgument(2));
    }

    @AfterEach
    void cleanState() {
        jdbc.update("DELETE FROM stock_take_shopify_syncs WHERE tenant_id = ?",  tenantId);
        jdbc.update("DELETE FROM stock_take_scans WHERE tenant_id = ?",          tenantId);
        jdbc.update("DELETE FROM stock_take_expected WHERE tenant_id = ?",       tenantId);
        jdbc.update("DELETE FROM stock_take_scope_variants WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stock_take_sessions WHERE tenant_id = ?",       tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?",                 tenantId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?",              tenantId);
        jdbc.update("DELETE FROM allocations WHERE tenant_id = ?",               tenantId);
        jdbc.update("DELETE FROM order_items WHERE tenant_id = ?",               tenantId);
        jdbc.update("UPDATE pieces SET current_order_id = NULL WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM orders WHERE tenant_id = ?",                    tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?",                    tenantId);
        // Leave the shared stores row alone (identity fixture); reset any extra rows added
        // by the multi-store determinism test.
        jdbc.update("DELETE FROM stores WHERE tenant_id = ? AND id != ?", tenantId, storeId);
    }

    // sft1: delta independent of committed pieces + dedicated-method-only proof
    @Test
    void sft1_deltaIndependentOfCommittedPieces_dedicatedMethodOnly() throws Exception {
        String lost1 = seedPiece("available", variantA);
        String lost2 = seedPiece("damaged", variantA);
        UUID orderId = createOrder(variantA);
        String committed = seedPieceForOrder("available", variantA, orderId);

        UUID sessionId = openAllScope();
        reservePieceForOrder(committed, orderId);

        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            reconciliation.resolve(sessionId, List.of(
                new StockTakeReconciliationService.ResolveItem(lost1, "lost"),
                new StockTakeReconciliationService.ResolveItem(lost2, "lost")), actorId);
        } finally {
            TenantContext.clear();
        }
        assertThat(pieceStatus(lost1)).isEqualTo("lost");
        assertThat(pieceStatus(lost2)).isEqualTo("lost");
        assertThat(pieceStatus(committed)).as("committed piece untouched by write-off").isEqualTo("reserved");

        Map<String, Object> result;
        TenantContext.set(tenantId);
        try {
            result = reconciliation.finalizeSession(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }
        assertThat(result.get("status")).isEqualTo("finalized");

        Integer deltaBefore = jdbc.queryForObject(
            "SELECT (payload->'deltas'->>?)::int FROM stock_take_shopify_syncs WHERE session_id = ?",
            Integer.class, variantA.toString(), sessionId);
        assertThat(deltaBefore).as("delta must be exactly 2 (the two lost pieces), never 3").isEqualTo(2);

        pushJob.push(sessionId, tenantId);

        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltasCaptor = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway).pushStockTakeWriteOff(
            eq(SHOP_DOMAIN), eq("sft-token"), deltasCaptor.capture(), eq(TRACED_GID),
            eq("traced://stock-take/" + sessionId), any());
        List<ShopifyGateway.InventoryDelta> deltas = deltasCaptor.getValue();
        assertThat(deltas).hasSize(1);
        assertThat(deltas.get(0).negativeDelta()).as("negative delta = -2, independent of the committed piece")
            .isEqualTo(-2);

        // Dedicated-method-only: the increment-only path must never be touched.
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any());

        // Structural proof: inventorySetOnHandQuantities does not exist anywhere in scope.
        boolean onInterface = java.util.Arrays.stream(ShopifyGateway.class.getMethods())
            .anyMatch(m -> m.getName().equals("inventorySetOnHandQuantities"));
        assertThat(onInterface).isFalse();

        String status = jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("pushed");
    }

    // sft2: claim row committed ('pending') BEFORE the HTTP call
    @Test
    void sft2_claimCommittedBeforeHttpCall() throws Exception {
        String lost = seedPiece("available", variantA);
        UUID sessionId = openAllScope();
        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(lost, "lost")), actorId);
            reconciliation.finalizeSession(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }

        CountDownLatch insideHttpCall = new CountDownLatch(1);
        CountDownLatch releaseHttpCall = new CountDownLatch(1);
        doAnswer(inv -> {
            insideHttpCall.countDown();
            releaseHttpCall.await(5, TimeUnit.SECONDS);
            return null;
        }).when(shopifyGateway).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());

        Thread pusher = new Thread(() -> pushJob.push(sessionId, tenantId));
        pusher.start();

        assertThat(insideHttpCall.await(5, TimeUnit.SECONDS))
            .as("push must reach the HTTP call").isTrue();

        // The claim row is ALREADY 'pending' (it was committed by finalizeSession(), a
        // separate, already-committed transaction) — readable right now, mid-HTTP-call,
        // from this test's own connection.
        String statusDuringCall = jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId);
        assertThat(statusDuringCall).isEqualTo("pending");

        releaseHttpCall.countDown();
        pusher.join(10_000);

        String statusAfter = jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId);
        assertThat(statusAfter).isEqualTo("pushed");
    }

    // sft3: double-finalize idempotency
    @Test
    void sft3_doubleFinalize_oneClaimOneJob() {
        String lost = seedPiece("available", variantA);
        UUID sessionId = openAllScope();
        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(lost, "lost")), actorId);

            reconciliation.finalizeSession(sessionId, actorId);

            assertThatThrownBy(() -> reconciliation.finalizeSession(sessionId, actorId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
        } finally {
            TenantContext.clear();
        }

        Integer claimCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM stock_take_shopify_syncs WHERE session_id = ?", Integer.class, sessionId);
        assertThat(claimCount).as("UNIQUE(session_id) — exactly one claim").isEqualTo(1);

        verify(jobScheduler, times(1)).enqueue(any(org.jobrunr.jobs.lambdas.JobLambda.class));
    }

    // sft4: ambiguous ack -> failed_ambiguous, second push() does NOT re-call Shopify
    @Test
    void sft4_ambiguousAck_noAutoRetry() {
        String lost = seedPiece("available", variantA);
        UUID sessionId = openAllScope();
        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(lost, "lost")), actorId);
            reconciliation.finalizeSession(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }

        doThrow(new ShopifyAmbiguousException("simulated timeout, no confirmed response"))
            .when(shopifyGateway).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());

        pushJob.push(sessionId, tenantId);

        String status = jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("failed_ambiguous");

        // Simulate a stray/duplicate retry invocation of the SAME job.
        pushJob.push(sessionId, tenantId);

        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
        String statusAfterRetryAttempt = jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId);
        assertThat(statusAfterRetryAttempt).as("still failed_ambiguous — never silently re-pushed")
            .isEqualTo("failed_ambiguous");
    }

    // sft5: single mutation covers all variants in one call
    @Test
    void sft5_singleMutation_allVariantsOneCall() {
        String lostA = seedPiece("available", variantA);
        String lostB = seedPiece("available", variantB);
        UUID sessionId = openAllScope();
        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            reconciliation.resolve(sessionId, List.of(
                new StockTakeReconciliationService.ResolveItem(lostA, "lost"),
                new StockTakeReconciliationService.ResolveItem(lostB, "lost")), actorId);
            reconciliation.finalizeSession(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }

        pushJob.push(sessionId, tenantId);

        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltasCaptor = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(
            any(), any(), deltasCaptor.capture(), any(), any(), any());
        assertThat(deltasCaptor.getValue()).hasSize(2);

        String status = jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("pushed");
    }

    // sft6: deterministic store/location resolution on a multi-store tenant
    @Test
    void sft6_multiStoreTenant_deterministicResolution() {
        UUID staleStoreId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, shop_domain, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'stale-sft.myshopify.com', 'idle', 'read_products,write_inventory', now() - interval '10 days')",
            staleStoreId, tenantId);
        when(tokenProvider.getValidToken(staleStoreId)).thenReturn("stale-token");

        String lost = seedPiece("available", variantA);
        UUID sessionId = openAllScope();
        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(lost, "lost")), actorId);
            reconciliation.finalizeSession(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }

        pushJob.push(sessionId, tenantId);

        verify(shopifyGateway).pushStockTakeWriteOff(
            eq(SHOP_DOMAIN), eq("sft-token"), any(), eq(TRACED_GID), any(), any());
        verify(shopifyGateway, never()).pushStockTakeWriteOff(
            eq("stale-sft.myshopify.com"), any(), any(), any(), any(), any());
    }

    // sft7: definitive failure -> failed, safely re-attempted on a second push() call
    @Test
    void sft7_definitiveFailure_reattemptSucceeds() {
        String lost = seedPiece("available", variantA);
        UUID sessionId = openAllScope();
        TenantContext.set(tenantId);
        try {
            reconciliation.attestComplete(sessionId, actorId);
            reconciliation.resolve(sessionId,
                List.of(new StockTakeReconciliationService.ResolveItem(lost, "lost")), actorId);
            reconciliation.finalizeSession(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }

        doThrow(new ShopifyException("simulated definitive rejection"))
            .when(shopifyGateway).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());

        // push() rethrows a definitive ShopifyException on purpose — that's the signal
        // JobRunr's own executor catches to schedule a retry. Calling push() directly here
        // (bypassing the real JobRunr worker) means this test sees that same throw.
        assertThatThrownBy(() -> pushJob.push(sessionId, tenantId))
            .isInstanceOf(ShopifyException.class);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId))
            .isEqualTo("failed");

        reset(shopifyGateway);
        when(tokenProvider.getValidToken(storeId)).thenReturn("sft-token");
        when(shopifyGateway.resolveInventoryItemId(anyString(), anyString(), anyString()))
            .thenAnswer(inv -> "gid://shopify/InventoryItem/" + inv.getArgument(2));
        // pushStockTakeWriteOff left unstubbed -> succeeds (no-op) by default on the reset mock.

        pushJob.push(sessionId, tenantId);

        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
        assertThat(jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId))
            .as("a definitive failure IS safely re-attempted and can succeed").isEqualTo("pushed");
    }

    // sft8: zero-delta finalize — claim lands 'pushed' directly, no job enqueued
    @Test
    void sft8_zeroDelta_noJobEnqueued() {
        UUID sessionId = openAllScope();

        reset(jobScheduler);
        Map<String, Object> result;
        TenantContext.set(tenantId);
        try {
            result = reconciliation.finalizeSession(sessionId, actorId);
        } finally {
            TenantContext.clear();
        }
        assertThat(result.get("status")).isEqualTo("finalized");

        String status = jdbc.queryForObject(
            "SELECT status FROM stock_take_shopify_syncs WHERE session_id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("pushed");

        verify(jobScheduler, never()).enqueue(any(org.jobrunr.jobs.lambdas.JobLambda.class));
        verify(shopifyGateway, never()).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID openAllScope() {
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

    private String seedPiece(String status, UUID variantId) {
        String id = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?::piece_status, ?)",
            id, tenantId, variantId, "PC-" + id, id, status, fulfillmentLocationId);
        return id;
    }

    private String seedPieceForOrder(String status, UUID variantId, UUID orderId) {
        String id = seedPiece(status, variantId);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", orderId, id);
        return id;
    }

    private UUID createOrder(UUID variantId) {
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at) " +
            "VALUES (?, ?, gen_random_uuid()::text, '#SFT-' || floor(random()*99999), " +
            "    'ready_to_pick'::order_status, 'cod', now()) RETURNING id",
            UUID.class, tenantId, storeId);
        jdbc.update(
            "INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1)",
            tenantId, orderId, variantId);
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
}
