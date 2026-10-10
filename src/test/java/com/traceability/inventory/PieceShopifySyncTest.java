package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Lookup adjustments ↔ Shopify — piece sync (approved 2026-10-10). C1: the existing hooks (void, hold,
 * hold exit, damage move) brought up to the transfer-push standard. Fixtures mirror production: a
 * tenant seeded an hour ago, pieces received a day ago (before the seed) and adjusted now.
 *
 *   v1 void of a piece received before the seed (receiving claim superseded by the seed — the
 *      2026-10-10 Snouts case) → one −1                  v2 received after the seed, never counted → skipped
 *   d1 damage → one available→damaged move carrying traced://piece/{id}
 *   n1 linked-but-unseeded hold → skipped not_seeded     n2 unlinked void → skipped main_warehouse_not_linked
 *   n3 piece at another location → skipped not_at_main (a row, never a silent return)
 *   h1 hold_enter failed (definite) → unhold cancels it, no +1, the sweep never sends it
 *   h2 hold_enter skipped → unhold: no +1                h3 hold_enter ambiguous → unhold skipped + alert
 *   h4 held before the seed → unhold +1 (the seed left it out)
 *   r1 timeout → failed_ambiguous: never re-sent by the sweep or by hand; alert
 *   r2 4xx → the sweep re-sends up to 5 attempts in all, then the alert
 *   r3 pending 15+ min → failed_ambiguous                r4 concurrent double hold → one claim, one call
 *   s1 the seed supersedes an unsent piece claim — never sent afterwards
 *   l1 a legacy failed damage_move (before the cutoff) → never swept, repush refused
 *   b1 damaged → destroyed: no Shopify call             c1 same tenant: a receiving +1 still applies
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PieceShopifySyncTest {

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

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;
    @Autowired PieceAdjustService adjust;
    @Autowired ShopifyInventoryService inventory;
    @Autowired ShopifyInventoryReconcileService seed;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID user, UUID variant, String item) {}

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.fetchAvailableQuantities(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ShopifyGateway.InventoryLevel> out = new ArrayList<>();
            for (Object item : (List<?>) inv.getArgument(3)) out.add(new ShopifyGateway.InventoryLevel((String) item, 0));
            return out;
        });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── void / damage ─────────────────────────────────────────────────────────────

    @Test
    void v1_voidOfPieceReceivedBeforeSeed_receivingSupersededBySeed_decrementsOnce() throws Exception {
        T t = tenant("v1", true, true);
        UUID receipt = receipt(t, "superseded_by_seed");
        String piece = piece(t, "available", receipt, true);

        as(t, () -> adjust.voidPiece(piece, "receiving_overcount", null, t.user()));

        awaitStatus(t, "void_correction", piece, "applied");
        verify(shopifyGateway, times(1)).pushVoidCorrection(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(-1), eq("traced://piece/" + piece), anyString());
    }

    @Test
    void v2_voidOfPieceReceivedAfterSeed_neverCounted_skippedWithReason() throws Exception {
        T t = tenant("v2", true, true);
        String piece = piece(t, "available", null, false);

        as(t, () -> adjust.voidPiece(piece, "duplicate_entry", null, t.user()));

        assertThat(claim(t, "void_correction", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "arrival_not_counted");
        Thread.sleep(200);
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void d1_damage_oneMoveCarryingThePieceReference() throws Exception {
        T t = tenant("d1", true, true);
        String piece = piece(t, "available", null, true);

        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));

        awaitStatus(t, "damage_move", piece, "applied");
        verify(shopifyGateway, times(1)).moveAvailableToDamaged(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(1), eq("damaged"), eq("traced://piece/" + piece), anyString());
        String damageEventId = jdbc.queryForObject("SELECT metadata->>'damage_event_id' FROM piece_events " +
            "WHERE piece_id = ? AND to_status = 'damaged'", String.class, piece);
        assertThat(claim(t, "damage_move", piece).get("trigger_id")).isEqualTo(piece + ":" + damageEventId);
    }

    // ── R5 / R4: skips are rows, never silent ─────────────────────────────────────

    @Test
    void n1_linkedButUnseeded_hold_skippedNotSeeded_noCall() throws Exception {
        T t = tenant("n1", true, false);
        String piece = piece(t, "available", null, true);

        as(t, () -> adjust.hold(piece, "quality_check", null, t.user()));

        assertThat(claim(t, "hold_enter", piece)).containsEntry("status", "skipped").containsEntry("skip_reason", "not_seeded");
        Thread.sleep(200);
        verifyNoWrites();
    }

    @Test
    void n2_unlinked_void_skippedMainNotLinked() throws Exception {
        T t = tenant("n2", false, false);
        String piece = piece(t, "available", receipt(t, "applied"), true);

        as(t, () -> adjust.voidPiece(piece, "receiving_overcount", null, t.user()));

        assertThat(claim(t, "void_correction", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "main_warehouse_not_linked");
        Thread.sleep(200);
        verifyNoWrites();
    }

    @Test
    void n3_pieceAtAnotherLocation_damage_skippedNotAtMain() throws Exception {
        T t = tenant("n3", true, true);
        UUID showroom = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Showroom', false)",
            showroom, t.tenant());
        String piece = piece(t, "available", null, true);
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?", showroom, piece);

        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));

        Map<String, Object> c = claim(t, "damage_move", piece);
        assertThat(c).containsEntry("status", "skipped").containsEntry("skip_reason", "not_at_main");
        assertThat(c.get("location_id")).as("the row names the main warehouse — the only place Traced writes")
            .isEqualTo(t.location());
        Thread.sleep(200);
        verifyNoWrites();
    }

    // ── hold exit gated by the departure (D5) ─────────────────────────────────────

    @Test
    void h1_holdEnterFailedDefinite_unhold_cancelsIt_noPlusOne_neverSentLater() throws Exception {
        T t = tenant("h1", true, true);
        String piece = piece(t, "available", null, true);
        doThrow(new ShopifyException("Hold enter HTTP 422")).when(shopifyGateway)
            .pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        as(t, () -> adjust.hold(piece, "quality_check", null, t.user()));
        awaitStatus(t, "hold_enter", piece, "failed");

        as(t, () -> adjust.unhold(piece, t.user()));

        assertThat(claim(t, "hold_enter", piece)).containsEntry("status", "cancelled");
        assertThat(claim(t, "hold_exit", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "departure_cancelled");
        as(t, () -> inventory.sweepPieceClaims(t.tenant()));
        verify(shopifyGateway, times(1)).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void h2_holdEnterSkipped_unhold_noPlusOne() throws Exception {
        T t = tenant("h2", true, true);
        String piece = piece(t, "available", null, false);   // received after the seed, never counted
        as(t, () -> adjust.hold(piece, "quality_check", null, t.user()));
        assertThat(claim(t, "hold_enter", piece)).containsEntry("status", "skipped");

        as(t, () -> adjust.unhold(piece, t.user()));

        assertThat(claim(t, "hold_exit", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "departure_not_reached");
        Thread.sleep(200);
        verifyNoWrites();
    }

    @Test
    void h3_holdEnterAmbiguous_unhold_skippedAmbiguous_alert() throws Exception {
        T t = tenant("h3", true, true);
        String piece = piece(t, "available", null, true);
        doThrow(new ShopifyAmbiguousException("Hold enter: no confirmed response")).when(shopifyGateway)
            .pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        as(t, () -> adjust.hold(piece, "quality_check", null, t.user()));
        awaitStatus(t, "hold_enter", piece, "failed_ambiguous");

        as(t, () -> adjust.unhold(piece, t.user()));

        assertThat(claim(t, "hold_exit", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "departure_ambiguous");
        Thread.sleep(200);
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(alertKeys(t)).contains("void_hold_sync_failed:hold_enter:" + claim(t, "hold_enter", piece).get("trigger_id"),
            "void_hold_sync_failed:hold_exit:" + claim(t, "hold_exit", piece).get("trigger_id"));
    }

    @Test
    void h4_heldBeforeTheSeed_unhold_plusOne() throws Exception {
        T t = tenant("h4", true, false);
        String piece = piece(t, "available", null, true);
        as(t, () -> adjust.hold(piece, "quality_check", null, t.user()));   // not seeded yet: skipped
        insertSeed(t, "now()");                                             // the seed left the held piece out

        as(t, () -> adjust.unhold(piece, t.user()));

        awaitStatus(t, "hold_exit", piece, "applied");
        verify(shopifyGateway, times(1)).pushPieceIncrement(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(1), eq("correction"), eq("traced://piece/" + piece), anyString());
        verify(shopifyGateway, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── R6 safety ─────────────────────────────────────────────────────────────────

    @Test
    void r1_timeout_failedAmbiguous_neverResentBySweepOrRepush_alert() throws Exception {
        T t = tenant("r1", true, true);
        String piece = piece(t, "available", null, true);
        doThrow(new ShopifyAmbiguousException("Hold enter: no confirmed response")).when(shopifyGateway)
            .pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        as(t, () -> adjust.hold(piece, "quality_check", null, t.user()));
        awaitStatus(t, "hold_enter", piece, "failed_ambiguous");
        String triggerId = (String) claim(t, "hold_enter", piece).get("trigger_id");

        as(t, () -> inventory.sweepPieceClaims(t.tenant()));
        assertThatThrownBy(() -> as(t, () -> inventory.repushFailedVoidOrHold("hold_enter", triggerId)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(shopifyGateway, times(1)).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(claim(t, "hold_enter", piece)).containsEntry("status", "failed_ambiguous");
        assertThat(alertKeys(t)).contains("void_hold_sync_failed:hold_enter:" + triggerId);
    }

    @Test
    void r2_definite4xx_sweepResendsUpToFiveAttemptsInAll_thenAlert() throws Exception {
        T t = tenant("r2", true, true);
        String piece = piece(t, "available", null, true);
        doThrow(new ShopifyException("Hold enter HTTP 422")).when(shopifyGateway)
            .pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        as(t, () -> adjust.hold(piece, "quality_check", null, t.user()));
        awaitStatus(t, "hold_enter", piece, "failed");
        String triggerId = (String) claim(t, "hold_enter", piece).get("trigger_id");
        assertThat(alertKeys(t)).as("a definite failure with attempts left is retried, not alerted")
            .doesNotContain("void_hold_sync_failed:hold_enter:" + triggerId);

        for (int i = 0; i < 7; i++) as(t, () -> inventory.sweepPieceClaims(t.tenant()));

        verify(shopifyGateway, times(5)).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(claim(t, "hold_enter", piece)).containsEntry("status", "failed").containsEntry("attempt_count", 5);
        assertThat(alertKeys(t)).contains("void_hold_sync_failed:hold_enter:" + triggerId);
    }

    @Test
    void r3_pendingForFifteenMinutes_becomesAmbiguous_neverSent() throws Exception {
        T t = tenant("r3", true, true);
        String piece = piece(t, "on_hold", null, true);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, attempt_count, send_started_at) " +
            "VALUES (?, ?, ?, ?, -1, 'hold_enter', ?, 'pending', 1, now() - interval '16 minutes')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), piece + ":" + UUID.randomUUID());

        as(t, () -> inventory.sweepPieceClaims(t.tenant()));

        assertThat(claim(t, "hold_enter", piece)).containsEntry("status", "failed_ambiguous");
        verifyNoWrites();
    }

    @Test
    void r4_concurrentDoubleHold_oneClaim_oneCall() throws Exception {
        T t = tenant("r4", true, true);
        String piece = piece(t, "available", null, true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                TenantContext.runAs(t.tenant(), () -> adjust.hold(piece, "quality_check", null, t.user()));
                return null;
            }));
        }
        go.countDown();
        int failures = 0;
        for (Future<?> f : fs) {
            try { f.get(10, TimeUnit.SECONDS); } catch (ExecutionException e) { failures++; }
        }
        pool.shutdown();

        assertThat(failures).as("the ledger lets exactly one hold through").isEqualTo(1);
        awaitStatus(t, "hold_enter", piece, "applied");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'hold_enter'", Integer.class, t.tenant())).isEqualTo(1);
        verify(shopifyGateway, times(1)).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── seed / legacy ─────────────────────────────────────────────────────────────

    @Test
    void s1_seedSupersedesAnUnsentPieceClaim_neverSentAfterwards() throws Exception {
        T t = tenant("s1", true, false);
        String piece = piece(t, "on_hold", null, true);
        piece(t, "available", null, true);
        String triggerId = piece + ":" + UUID.randomUUID();
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, attempt_count, failure_class) " +
            "VALUES (?, ?, ?, ?, -1, 'hold_enter', ?, 'failed', 1, 'rejected')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), triggerId);

        TenantContext.runAs(t.tenant(), () -> seed.apply(null));

        assertThat(claim(t, "hold_enter", piece)).containsEntry("status", "superseded_by_seed");
        as(t, () -> inventory.sweepPieceClaims(t.tenant()));
        verify(shopifyGateway, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void l1_legacyFailedDamageMove_beforeCutoff_neverSwept_repushRefused() throws Exception {
        T t = tenant("l1", true, true);
        String piece = piece(t, "damaged", null, true);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, error, created_at) " +
            "VALUES (?, ?, ?, ?, 0, 'damage_move', ?, 'failed', 'referenceDocumentUri (Expected value to not be null)', " +
            "        piece_sync_cutoff() - interval '1 day')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), piece);

        as(t, () -> inventory.sweepPieceClaims(t.tenant()));
        assertThatThrownBy(() -> as(t, () -> inventory.repushFailedVoidOrHold("damage_move", piece)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(claim(t, "damage_move", piece)).containsEntry("status", "failed").containsEntry("attempt_count", 0);
        verifyNoWrites();
    }

    // ── R2 / control ──────────────────────────────────────────────────────────────

    @Test
    void b1_damagedToDestroyed_noShopifyCall() throws Exception {
        T t = tenant("b1", true, true);
        String piece = piece(t, "available", null, true);
        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));
        awaitStatus(t, "damage_move", piece, "applied");
        clearInvocations(shopifyGateway);

        as(t, () -> adjust.adjustPiece(piece, "destroyed", "other", "dropped in the yard", t.user()));

        Thread.sleep(300);
        verifyNoWrites();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type <> 'initial_seed'", Integer.class, t.tenant())).isEqualTo(1);
    }

    @Test
    void c1_sameTenantControl_receivingIncrementStillApplies() throws Exception {
        T t = tenant("c1", true, true);
        String piece = piece(t, "available", null, true);
        as(t, () -> adjust.hold(piece, "quality_check", null, t.user()));
        awaitStatus(t, "hold_enter", piece, "applied");

        UUID session = UUID.randomUUID();
        inventory.onReceivingSessionClose(t.tenant(), session, t.location(), Map.of(t.variant(), 3)).get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'receiving_session' AND trigger_id = ?", String.class, t.tenant(), session.toString()))
            .isEqualTo("applied");
        verify(shopifyGateway).adjustInventoryQuantities(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(3), eq("received"), anyString());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private void verifyNoWrites() {
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private T tenant(String name, boolean linked, boolean seeded) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        String item = "gid://shopify/InventoryItem/" + name + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        if (linked) {
            jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        } else {
            jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_sync_status, is_fulfillment) " +
                "VALUES (?, ?, 'Main Warehouse', 'error', true)", location, tenant);
        }
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shop, "SKU-" + name, item);
        T t = new T(tenant, store, shop, location, traced, user, variant, item);
        if (seeded) insertSeed(t, "now() - interval '1 hour'");
        return t;
    }

    /** An applied initial seed row at {@code atSql}. */
    private void insertSeed(T t, String atSql) {
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, 1, 'initial_seed', ?, 'applied', " + atSql + ")",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), "fixture-seed:" + UUID.randomUUID());
    }

    /** receivedBeforeSeed: created a day ago (before the hour-old seed); otherwise now. */
    private String piece(T t, String status, UUID receipt, boolean receivedBeforeSeed) {
        int k = seq.incrementAndGet();
        String id = "01PS" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, " +
            "receipt_id, created_at) VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, ?, " +
            (receivedBeforeSeed ? "now() - interval '1 day'" : "now()") + ")",
            id, t.tenant(), t.variant(), status, "PS-" + id, String.format("S%07d", k), t.location(), receipt);
        return id;
    }

    /** A receipt whose receiving claim has the given status. */
    private UUID receipt(T t, String receivingStatus) {
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, t.tenant(), t.user(), t.location());
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, ?, now() - interval '1 day')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), receipt.toString(), receivingStatus);
        return receipt;
    }

    private void as(T t, Runnable r) {
        TenantContext.runAs(t.tenant(), () -> { r.run(); return null; });
    }

    private Map<String, Object> claim(T t, String triggerType, String piece) {
        return jdbc.queryForMap("SELECT trigger_id, status, skip_reason, attempt_count, location_id " +
            "FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = ? " +
            "AND split_part(trigger_id, ':', 1) = ?", t.tenant(), triggerType, piece);
    }

    private void awaitStatus(T t, String triggerType, String piece, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = ? AND split_part(trigger_id, ':', 1) = ? AND status = ?",
                Integer.class, t.tenant(), triggerType, piece, status) == 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(triggerType + " for " + piece + " never reached " + status + ": "
                    + jdbc.queryForList("SELECT trigger_type, status, skip_reason, error FROM shopify_inventory_adjustments " +
                        "WHERE tenant_id = ?", t.tenant()));
            }
            Thread.sleep(25);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> alertKeys(T t) {
        Map<String, Object> r = TenantContext.runAs(t.tenant(), () ->
            exceptions.listExceptions("void_hold_sync_failed", null, 0, 100));
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> i : (List<Map<String, Object>>) r.get("items")) keys.add((String) i.get("subject_key"));
        return keys;
    }
}
