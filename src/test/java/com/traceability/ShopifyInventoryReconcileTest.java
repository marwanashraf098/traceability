package com.traceability;

import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.ShopifyInventoryReconcileService;
import com.traceability.inventory.UlidGenerator;
import com.traceability.tenancy.TenantContext;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Part C — reconcile-then-write initial seed of the (empty) Traced Main Warehouse.
 *
 * Matrix:
 *   pc1 — reconcile() (read-only) computes the correct diff and never calls any write mutation
 *   pc2 — apply() seeds only the "seed" rows, with a strictly-positive delta from 0
 *   pc3 — a variant already non-zero in Shopify is skipped and flagged, never corrected
 *         downward (or upward) — regardless of whether target > or < current
 *   pc4 — idempotency: after a successful seed, a re-run sees the now-non-zero Shopify
 *         value and performs a zero/positive-only no-op, never a double-add
 *   pc6 — partial-seed-then-reconnect-completes-it: a variant whose seed fails is recorded
 *         'failed'; Shopify still shows it at zero, so the next apply() retries it, and on
 *         success the SAME audit row moves 'failed' -> 'applied' (not left stuck, not
 *         duplicated) — the fix for recordAudit()'s prior ON CONFLICT DO NOTHING
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyInventoryReconcileTest {

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

    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate jdbc;
    @Autowired ShopifyInventoryReconcileService reconcileService;

    UUID tenantId, storeId, productId, locationId, actorUserId;
    static final String SHOP_DOMAIN = "pc-test.myshopify.com";
    static final String TRACED_GID  = "gid://shopify/Location/pc-traced";

    @BeforeEach
    void setup() {
        tenantId   = UUID.randomUUID();
        storeId    = UUID.randomUUID();
        productId  = UUID.randomUUID();
        locationId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'PC Tenant')", tenantId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, role) " +
            "VALUES (?, ?, 'PC Owner', ?, 'owner')",
            actorUserId, tenantId, "pc-owner-" + tenantId + "@example.com");
        jdbc.update("INSERT INTO stores (id, tenant_id, shop_domain, status) VALUES (?, ?, ?, 'connected')",
            storeId, tenantId, SHOP_DOMAIN);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'gid://shopify/Product/pc', 'PC Product', 'active')",
            productId, tenantId, storeId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)",
            locationId, tenantId, TRACED_GID);

        when(tokenProvider.getValidToken(storeId)).thenReturn("test-token");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM shopify_inventory_adjustments WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM variants WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM products WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM locations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM stores WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM users WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
    }

    private UUID seedVariantWithOnHand(String externalId, String itemGid, int piecesAvailable) {
        UUID variantId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, ?, 'Variant', ?)",
            variantId, tenantId, productId, externalId, "SKU-" + externalId.hashCode());
        for (int i = 0; i < piecesAvailable; i++) {
            String pieceId = UlidGenerator.generate();
            jdbc.update(
                "INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                "VALUES (?, ?, ?, 'available'::piece_status, ?, " +
                "    'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), ?)",
                pieceId, tenantId, variantId, "PC-" + pieceId, pieceId, locationId);
        }
        when(shopifyGateway.resolveInventoryItemId(eq(SHOP_DOMAIN), eq("test-token"), eq(externalId)))
            .thenReturn(itemGid);
        return variantId;
    }

    // ── pc1: reconcile is read-only ──────────────────────────────────────────

    @Test
    void pc1_reconcile_computesDiffAndWritesNothing() {
        UUID variantId = seedVariantWithOnHand("gid://shopify/ProductVariant/pc1", "gid://shopify/InventoryItem/pc1", 3);
        when(shopifyGateway.fetchAvailableQuantities(eq(SHOP_DOMAIN), eq("test-token"), eq(TRACED_GID), anyList()))
            .thenReturn(List.of()); // Shopify shows nothing yet — fresh location

        TenantContext.set(tenantId);
        try {
            var report = reconcileService.reconcile();
            assertThat(report.tracedLocationGid()).isEqualTo(TRACED_GID);
            var row = report.rows().stream().filter(r -> r.variantId().equals(variantId)).findFirst().orElseThrow();
            assertThat(row.tracedOnHand()).isEqualTo(3L);
            assertThat(row.shopifyAvailable()).isEqualTo(0);
            assertThat(row.action()).isEqualTo(ShopifyInventoryReconcileService.ACTION_SEED);
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any());
        Long auditRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ?", Long.class, tenantId);
        assertThat(auditRows).as("pc1: first pass writes nothing at all").isZero();
    }

    // ── pc2: apply seeds only the seed rows, strictly positive delta from 0 ──

    @Test
    void pc2_apply_seedsWithPositiveDeltaFromZero() {
        UUID variantId = seedVariantWithOnHand("gid://shopify/ProductVariant/pc2", "gid://shopify/InventoryItem/pc2", 5);
        when(shopifyGateway.fetchAvailableQuantities(eq(SHOP_DOMAIN), eq("test-token"), eq(TRACED_GID), anyList()))
            .thenReturn(List.of());

        TenantContext.set(tenantId);
        try {
            var result = reconcileService.apply(actorUserId);
            assertThat(result.seeded()).isEqualTo(1);
            assertThat(result.failed()).isZero();
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway).adjustInventoryQuantities(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/pc2"), eq(TRACED_GID), eq(5), anyString(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(),
            argThat(loc -> !TRACED_GID.equals(loc)), anyInt(), any(), any());

        String status = jdbc.queryForObject(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed' AND variant_id = ?",
            String.class, tenantId, variantId);
        assertThat(status).isEqualTo("applied");
    }

    // ── pc3: non-zero-in-Shopify variant is skipped, never corrected ─────────

    @Test
    void pc3_nonZeroInShopify_skippedNeverCorrected() {
        // Shopify already shows 5 available; Traced thinks on_hand is only 2 (would be a
        // downward correction if auto-applied) — must be skipped regardless of direction.
        UUID variantId = seedVariantWithOnHand("gid://shopify/ProductVariant/pc3", "gid://shopify/InventoryItem/pc3", 2);
        when(shopifyGateway.fetchAvailableQuantities(eq(SHOP_DOMAIN), eq("test-token"), eq(TRACED_GID), anyList()))
            .thenReturn(List.of(new ShopifyGateway.InventoryLevel("gid://shopify/InventoryItem/pc3", 5)));

        TenantContext.set(tenantId);
        try {
            var report = reconcileService.reconcile();
            var row = report.rows().stream().filter(r -> r.variantId().equals(variantId)).findFirst().orElseThrow();
            assertThat(row.action()).isEqualTo(ShopifyInventoryReconcileService.ACTION_SKIP_NONZERO);

            var result = reconcileService.apply(actorUserId);
            assertThat(result.seeded()).isZero();
            assertThat(result.skippedNonZero()).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── pc4: idempotency — re-run after successful seed is a no-op ──────────

    @Test
    void pc4_reRunAfterSeed_isNoopNeverDoubleAdd() {
        seedVariantWithOnHand("gid://shopify/ProductVariant/pc4", "gid://shopify/InventoryItem/pc4", 4);

        TenantContext.set(tenantId);
        try {
            // First pass: Shopify empty -> seeds.
            when(shopifyGateway.fetchAvailableQuantities(eq(SHOP_DOMAIN), eq("test-token"), eq(TRACED_GID), anyList()))
                .thenReturn(List.of());
            var first = reconcileService.apply(actorUserId);
            assertThat(first.seeded()).isEqualTo(1);

            // Second pass: Shopify now reflects the seeded value -> must be a no-op, not a
            // second +4 (which would double the real Shopify count to 8).
            when(shopifyGateway.fetchAvailableQuantities(eq(SHOP_DOMAIN), eq("test-token"), eq(TRACED_GID), anyList()))
                .thenReturn(List.of(new ShopifyGateway.InventoryLevel("gid://shopify/InventoryItem/pc4", 4)));
            var second = reconcileService.apply(actorUserId);
            assertThat(second.seeded()).as("pc4: re-run must not double-add").isZero();
            assertThat(second.skippedNonZero()).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── pc5: GENUINE concurrency — two overlapping apply() calls for one tenant ──
    //
    // Deterministic, not timing-dependent: the winner is deliberately blocked INSIDE its
    // adjustInventoryQuantities call (still holding the per-tenant pg_advisory_xact_lock,
    // transaction not yet committed) while a second, concurrent apply() call is fired. That
    // second call must BLOCK on the advisory lock (proven by it still being alive after a
    // short wait) until the winner's transaction commits, then recompute live — seeing
    // Shopify's now-non-zero value via the second stubbed fetchAvailableQuantities answer —
    // and no-op rather than double-add.

    @Test
    void pc5_concurrentApply_secondCallBlocksThenNoOps() throws Exception {
        seedVariantWithOnHand("gid://shopify/ProductVariant/pc5", "gid://shopify/InventoryItem/pc5", 4);

        CountDownLatch winnerInsideAdjust = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        // First invocation (winner, inside the lock): Shopify shows nothing yet.
        // Second invocation (loser, only reachable after the winner commits and releases
        // the lock): Shopify now reflects the winner's write.
        when(shopifyGateway.fetchAvailableQuantities(eq(SHOP_DOMAIN), eq("test-token"), eq(TRACED_GID), anyList()))
            .thenReturn(List.of())
            .thenReturn(List.of(new ShopifyGateway.InventoryLevel("gid://shopify/InventoryItem/pc5", 4)));

        doAnswer(invocation -> {
            winnerInsideAdjust.countDown();
            releaseWinner.await(5, TimeUnit.SECONDS);
            return null;
        }).when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        AtomicReference<ShopifyInventoryReconcileService.ApplyResult> winnerResult = new AtomicReference<>();
        Thread winner = new Thread(() -> {
            TenantContext.set(tenantId);
            try {
                winnerResult.set(reconcileService.apply(actorUserId));
            } finally {
                TenantContext.clear();
            }
        });
        winner.start();

        assertThat(winnerInsideAdjust.await(5, TimeUnit.SECONDS))
            .as("pc5: winner must reach the Shopify call (still holding the tenant lock) " +
                "before the concurrent apply() fires")
            .isTrue();

        AtomicReference<ShopifyInventoryReconcileService.ApplyResult> loserResult = new AtomicReference<>();
        Thread loser = new Thread(() -> {
            TenantContext.set(tenantId);
            try {
                loserResult.set(reconcileService.apply(actorUserId));
            } finally {
                TenantContext.clear();
            }
        });
        loser.start();

        // The loser must be blocked on pg_advisory_xact_lock — the winner hasn't committed
        // (hasn't even returned from the mocked Shopify call yet).
        Thread.sleep(300);
        assertThat(loser.isAlive())
            .as("pc5: concurrent apply() must block on the tenant advisory lock, not race ahead")
            .isTrue();

        releaseWinner.countDown();
        winner.join(10_000);
        loser.join(10_000);

        assertThat(winnerResult.get().seeded()).as("pc5: winner seeds").isEqualTo(1);
        assertThat(loserResult.get())
            .as("pc5: loser must have run (not still blocked) after the winner released the lock")
            .isNotNull();
        assertThat(loserResult.get().seeded())
            .as("pc5: loser sees Shopify's now-non-zero value live — never a double-add")
            .isZero();
        assertThat(loserResult.get().skippedNonZero()).isEqualTo(1);

        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── pc6: a failed seed is retried on the next reconnect and the SAME audit ──
    //         row moves failed -> applied — the "partial seed then reconnect completes
    //         it" case pc1-pc5 never covered.

    @Test
    void pc6_failedSeed_reconnectRetrySucceeds_auditRowMovesFailedToApplied() throws Exception {
        UUID variantId = seedVariantWithOnHand("gid://shopify/ProductVariant/pc6", "gid://shopify/InventoryItem/pc6", 5);

        // Shopify shows nothing throughout — the failed attempt below never actually writes.
        when(shopifyGateway.fetchAvailableQuantities(eq(SHOP_DOMAIN), eq("test-token"), eq(TRACED_GID), anyList()))
            .thenReturn(List.of());

        // First apply(): Shopify rejects the write.
        doThrow(new ShopifyException("simulated rejection"))
            .when(shopifyGateway).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        TenantContext.set(tenantId);
        try {
            var first = reconcileService.apply(actorUserId);
            assertThat(first.seeded()).isZero();
            assertThat(first.failed()).isEqualTo(1);
        } finally {
            TenantContext.clear();
        }

        String statusAfterFirst = jdbc.queryForObject(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed' AND variant_id = ?",
            String.class, tenantId, variantId);
        assertThat(statusAfterFirst).as("pc6: first attempt recorded as failed").isEqualTo("failed");

        // Second apply() ("reconnect"): Shopify still shows 0 for this variant (nothing was
        // actually written) -> buildReport() recomputes ACTION_SEED again. This time the
        // write succeeds.
        reset(shopifyGateway);
        when(shopifyGateway.resolveInventoryItemId(eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/ProductVariant/pc6")))
            .thenReturn("gid://shopify/InventoryItem/pc6");
        when(shopifyGateway.fetchAvailableQuantities(eq(SHOP_DOMAIN), eq("test-token"), eq(TRACED_GID), anyList()))
            .thenReturn(List.of());
        // adjustInventoryQuantities left unstubbed on the reset mock -> succeeds (no-op) by default.

        TenantContext.set(tenantId);
        try {
            var second = reconcileService.apply(actorUserId);
            assertThat(second.seeded()).as("pc6: retry succeeds").isEqualTo(1);
            assertThat(second.failed()).isZero();
        } finally {
            TenantContext.clear();
        }

        verify(shopifyGateway, times(1)).adjustInventoryQuantities(
            eq(SHOP_DOMAIN), eq("test-token"), eq("gid://shopify/InventoryItem/pc6"), eq(TRACED_GID), eq(5), anyString(), any());

        String statusAfterRetry = jdbc.queryForObject(
            "SELECT status FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed' AND variant_id = ?",
            String.class, tenantId, variantId);
        assertThat(statusAfterRetry).as("pc6: retry moves the SAME row from failed to applied").isEqualTo("applied");

        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'initial_seed' AND variant_id = ?",
            Long.class, tenantId, variantId);
        assertThat(count).as("pc6: the retry updates the same row, never inserts a second one").isEqualTo(1L);
    }
}
