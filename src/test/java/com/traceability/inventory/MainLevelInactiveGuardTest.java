package com.traceability.inventory;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyLocationGateway;
import com.traceability.integrations.shopify.ShopifyMainLevelInactiveException;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Part D P2 (2026-11-08) — the inactive-level guard, service half (tests 8 and 9 of the Part D plan). The REAL services
 * run; Shopify is mocked. "Inactive" = {@code ShopifyGateway.inactiveMainLevels} names the item (the gateway's own
 * baseline read refuses on its own — MainLevelInactiveWireTest). Dev store, 2026-11-08 (scripts/dev/part-d-p2, CLI's
 * app): a write onto an inactive level lands silently on the hidden level; a fresh activation key re-activates it, a
 * re-used one never does.
 *
 * Test 8 — increments:
 *   i1 — a +N whose first send finds no active level (still inactive after activation): the activation carried a FRESH
 *        key (never the catalog key), the claim is failed / never_sent with the main_level_inactive error (re-tried on
 *        the normal backoff) and inventory_increment_sync_failed is raised now
 *   i2 — a recovery check (identical re-send of an ambiguous claim) on an inactive level: NOT sent; the claim stays
 *        ambiguous with its key and baseline, error main_level_inactive, alert raised
 *   i3 — a piece +1 (hold_exit) still inactive after its fresh-key activation: no +1 sent, failed / never_sent with
 *        the main_level_inactive error, CRITICAL void_hold_sync_failed
 * Test 9 — decrements / moves / batch pushes (each with a positive control: an active level sends once):
 *   d1 — every per-piece departure and move (void, hold-enter, damage move, write-off, Sold, damage restore):
 *        inactive → nothing sent, no activation, skipped main_level_inactive, CRITICAL void_hold_sync_failed
 *   d2 — exchange dispatch: skipped main_level_inactive, no −1, CRITICAL alert
 *   d3 — stock-take write-off push: ANY inactive item refuses the whole push — nothing sent, 'failed' with the
 *        main_level_inactive error, not re-thrown (not auto-retried), CRITICAL alert
 *   d4 — transfer out: the whole push skipped main_level_inactive, nothing sent, CRITICAL alert
 *   d5 — Main reconcile Part 1: its Main read finds the level not stocked → nothing sent, 'failed' with the
 *        main_level_inactive error, CRITICAL alert
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainLevelInactiveGuardTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            TestPostgres.newContainer()
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
    static final String REASON = ShopifyMainLevelInactiveException.REASON;

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired ShopifyInventoryService inventory;
    @Autowired StockTakeShopifyPushJob stockTakePush;
    @Autowired TransferShopifySync transfers;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean ShopifyLocationGateway locationGateway;
    @MockBean JobScheduler jobScheduler;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID owner, UUID variant, String item) {}

    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider, locationGateway, jobScheduler);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    /** Shopify reports the item's level at the Traced location as not active. */
    private void inactive(T t) {
        when(shopifyGateway.inactiveMainLevels(eq(t.shop()), eq("tok"), eq(t.traced()), any()))
            .thenReturn(Set.of(t.item()));
    }

    private void active(T t) {
        when(shopifyGateway.inactiveMainLevels(eq(t.shop()), eq("tok"), eq(t.traced()), any())).thenReturn(Set.of());
    }

    // ── Test 8: increments ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void i1_firstSend_stillInactiveAfterFreshActivation_neverSent_alert() throws Exception {
        T t = tenant("i1");
        doThrow(new ShopifyMainLevelInactiveException("Adjust")).when(shopifyGateway)
            .adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());

        UUID session = receive(t, 2);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway).activateInventoryItem(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()), key.capture());
        assertThat(key.getValue()).startsWith("traced-activate:")
            .isNotEqualTo(ShopifyCatalogActivationService.activationKey(t.tenant(), t.variant(), t.traced()));
        Map<String, Object> c = increment(t, session);
        assertThat(c).containsEntry("status", "failed").containsEntry("failure_class", "never_sent");
        assertThat((String) c.get("error")).startsWith(REASON + ": ");
        assertThat(c.get("next_attempt_at")).as("re-tried on the normal backoff").isNotNull();
        assertThat(alerts(t, "inventory_increment_sync_failed")).anySatisfy(a -> assertThat(a).containsEntry("kind", "gave_up"));

        // The next attempt finds the level active → applied, the alert is gone (the error is rewritten).
        Mockito.reset(shopifyGateway);
        jdbc.update("UPDATE shopify_inventory_adjustments SET next_attempt_at = now() - interval '1 second' " +
            "WHERE tenant_id = ? AND trigger_id = ?", t.tenant(), session.toString());
        TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
        assertThat(increment(t, session)).containsEntry("status", "applied");
        assertThat(alerts(t, "inventory_increment_sync_failed")).isEmpty();
    }

    @Test
    void i2_recoveryCheck_onInactiveLevel_notSent_staysAmbiguous_alert() {
        T t = tenant("i2");
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, t.tenant(), t.owner(), t.location());
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, attempt_count, failure_class, error, sent_idempotency_key, " +
            "change_from_quantity, sent_key_first_at, first_attempt_at, last_attempt_at, next_attempt_at) " +
            "VALUES (?, ?, ?, ?, 3, 'receiving_session', ?, '{\"reason\": \"received\", \"delta\": 3}'::jsonb, 'failed', 1, " +
            "'ambiguous', 'Read timed out', 'k-orig', 5, now() - interval '1 hour', now() - interval '1 hour', " +
            "now() - interval '1 hour', now() - interval '1 second')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), receipt.toString());
        inactive(t);

        TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);

        verify(shopifyGateway, never()).resendInventoryAdjustment(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        Map<String, Object> c = increment(t, receipt);
        assertThat(c).containsEntry("status", "failed").containsEntry("failure_class", "ambiguous")
            .containsEntry("sent_idempotency_key", "k-orig").containsEntry("change_from_quantity", 5);
        assertThat((String) c.get("error")).startsWith(REASON + ": ");
        assertThat(alerts(t, "inventory_increment_sync_failed")).isNotEmpty();
    }

    @Test
    void i3_pieceIncrement_stillInactive_neverSent_criticalAlert() {
        T t = tenant("i3");
        String piece = piece(t);
        long claim = pieceClaim(t, "hold_exit", piece + ":" + UUID.randomUUID(), 1);
        inactive(t);

        send(t, claim);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(shopifyGateway).activateInventoryItem(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()), key.capture());
        assertThat(key.getValue()).startsWith("traced-activate:");
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        Map<String, Object> c = claimRow(t, claim);
        assertThat(c).containsEntry("status", "failed").containsEntry("failure_class", "never_sent");
        assertThat((String) c.get("error")).startsWith(REASON + ": ");
        assertThat(critical(t, "hold_exit")).isNotNull();
    }

    // ── Test 9: decrements, moves, batch pushes ──────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"void_correction", "hold_enter", "damage_move", "piece_write_off", "piece_sold", "damage_restore"})
    void d1_pieceDepartureOrMove_inactive_skipped_nothingSent_criticalAlert(String trigger) {
        T t = tenant("d1" + trigger.substring(0, 4));
        String piece = piece(t);
        long claim = pieceClaim(t, trigger, piece + ":" + UUID.randomUUID(), "damage_restore".equals(trigger) ? 1 : -1);
        inactive(t);

        send(t, claim);

        verifyNoWrite();
        verify(shopifyGateway, never()).activateInventoryItem(any(), any(), any(), any(), any());
        assertThat(claimRow(t, claim)).containsEntry("status", "skipped").containsEntry("skip_reason", REASON);
        Map<String, Object> a = critical(t, trigger);
        assertThat(a).isNotNull();
        assertThat(a).containsEntry("severity", "CRITICAL").containsEntry("skip_reason", REASON);

        // Positive control: the same trigger on an active level is sent once.
        String other = piece(t);
        long ok = pieceClaim(t, trigger, other + ":" + UUID.randomUUID(), "damage_restore".equals(trigger) ? 1 : -1);
        active(t);
        send(t, ok);
        assertThat(claimRow(t, ok)).containsEntry("status", "applied");
    }

    @Test
    void d2_exchangeDispatch_inactive_skipped_noDecrement_criticalAlert() throws Exception {
        T t = tenant("d2");
        String piece = replacementPiece(t);
        inactive(t);

        inventory.onExchangeReplacementDispatched(t.tenant(), piece).get(5, TimeUnit.SECONDS);

        verify(shopifyGateway, never()).pushExchangeDispatch(any(), any(), any(), any(), anyInt(), any(), any());
        Map<String, Object> c = jdbc.queryForMap("SELECT status, skip_reason FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'exchange_dispatch' AND trigger_id = ?", t.tenant(), piece);
        assertThat(c).containsEntry("status", "skipped").containsEntry("skip_reason", REASON);
        assertThat(critical(t, "exchange_dispatch")).isNotNull();

        String ok = replacementPiece(t);
        active(t);
        inventory.onExchangeReplacementDispatched(t.tenant(), ok).get(5, TimeUnit.SECONDS);
        verify(shopifyGateway, times(1)).pushExchangeDispatch(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void d3_stockTakePush_anyInactiveItem_wholePushRefused_notRetried_criticalAlert() {
        T t = tenant("d3");
        UUID session = stockTakeSync(t);
        inactive(t);

        stockTakePush.push(session, t.tenant());   // not re-thrown: JobRunr would re-send it

        verify(shopifyGateway, never()).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
        Map<String, Object> y = jdbc.queryForMap("SELECT status, error FROM stock_take_shopify_syncs WHERE session_id = ?", session);
        assertThat(y).containsEntry("status", "failed");
        assertThat((String) y.get("error")).startsWith(REASON + ": ");
        Map<String, Object> a = critical(t, "stock_take_write_off");
        assertThat(a).isNotNull();
        assertThat(a).containsEntry("trigger_id", session.toString());

        UUID ok = stockTakeSync(t);
        active(t);
        stockTakePush.push(ok, t.tenant());
        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
    }

    @Test
    void d4_transferOut_inactive_skipped_nothingSent_criticalAlert() {
        T t = tenant("d4");
        UUID sync = transferSync(t);
        inactive(t);

        TenantContext.runAs(t.tenant(), () -> { transfers.pushNow(t.tenant(), sync); return null; });

        verify(shopifyGateway, never()).pushTransferOut(any(), any(), any(), any(), any(), any());
        Map<String, Object> y = jdbc.queryForMap("SELECT status, reason FROM transfer_shopify_syncs WHERE id = ?", sync);
        assertThat(y).containsEntry("status", "skipped").containsEntry("reason", REASON);
        assertThat(critical(t, "transfer_out")).isNotNull();

        UUID ok = transferSync(t);
        active(t);
        TenantContext.runAs(t.tenant(), () -> { transfers.pushNow(t.tenant(), ok); return null; });
        verify(shopifyGateway, times(1)).pushTransferOut(any(), any(), any(), any(), any(), any());
    }

    @Test
    void d5_part1_levelNotStocked_nothingSent_criticalAlert() {
        T t = tenant("d5");
        UUID claim = part1Claim(t);
        when(shopifyGateway.fetchMainLevels(eq(t.shop()), eq("tok"), eq(t.traced()), eq(List.of(t.item()))))
            .thenReturn(List.of(new ShopifyGateway.MainLevel(t.item(), false, 0, 0, 0, 0)));
        MainReconcileWriter writer = new MainReconcileWriter(jdbc, new TransactionTemplate(txm), shopifyGateway, () -> true);

        TenantContext.runAs(t.tenant(), () -> writer.sendPart1(t.tenant(), t.shop(), "tok",
            new MainReconcileWriter.Main(t.location(), t.traced())));

        verify(shopifyGateway, never()).pushPart1MainDecrement(any(), any(), any(), any(), anyInt(), any(), any());
        Map<String, Object> c = jdbc.queryForMap("SELECT status, error FROM main_reconcile_part1_claims WHERE id = ?", claim);
        assertThat(c).containsEntry("status", "failed");
        assertThat((String) c.get("error")).startsWith(REASON + ": ");
        assertThat(critical(t, "main_reconcile_part1")).isNotNull();

        UUID ok = part1Claim(t);
        when(shopifyGateway.fetchMainLevels(eq(t.shop()), eq("tok"), eq(t.traced()), eq(List.of(t.item()))))
            .thenReturn(List.of(new ShopifyGateway.MainLevel(t.item(), true, 4, 4, 0, 0)));
        TenantContext.runAs(t.tenant(), () -> writer.sendPart1(t.tenant(), t.shop(), "tok",
            new MainReconcileWriter.Main(t.location(), t.traced())));
        // Both go out now: the refused claim had attempts left, so this run re-checks it and sends it too.
        verify(shopifyGateway, times(2)).pushPart1MainDecrement(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(jdbc.queryForList("SELECT status FROM main_reconcile_part1_claims WHERE id IN (?, ?)", String.class,
            claim, ok)).containsExactly("applied", "applied");
        assertThat(critical(t, "main_reconcile_part1")).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    private void verifyNoWrite() {
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).moveDamagedToAvailable(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID();
        UUID owner = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        String item = "gid://shopify/InventoryItem/" + name + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            owner, tenant, name + "-o-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shop, "SKU-" + name, item);
        return new T(tenant, store, shop, location, traced, owner, variant, item);
    }

    private String piece(T t) {
        int k = seq.incrementAndGet();
        String id = "01ML" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, 'available'::piece_status, ?, ?, ?)",
            id, t.tenant(), t.variant(), "ML-" + id, String.format("M%07d", k), t.location());
        return id;
    }

    /** A queued per-piece claim, as the Lookup-adjust engine writes it in the adjust transaction. */
    private long pieceClaim(T t, String trigger, String triggerId, int delta) {
        return jdbc.queryForObject("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, " +
            "delta, trigger_type, trigger_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'queued') RETURNING id", Long.class,
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), delta, trigger, triggerId);
    }

    private void send(T t, long claim) {
        TenantContext.runAs(t.tenant(), () -> { inventory.pushPieceClaimNow(t.tenant(), claim, false); return null; });
    }

    private Map<String, Object> claimRow(T t, long claim) {
        return jdbc.queryForMap("SELECT status, skip_reason, failure_class, error FROM shopify_inventory_adjustments " +
            "WHERE id = ? AND tenant_id = ?", claim, t.tenant());
    }

    private UUID receive(T t, int qty) throws Exception {
        UUID session = UUID.randomUUID();
        inventory.onReceivingSessionClose(t.tenant(), session, t.location(), Map.of(t.variant(), qty)).get(10, TimeUnit.SECONDS);
        return session;
    }

    private Map<String, Object> increment(T t, UUID trigger) {
        return jdbc.queryForMap("SELECT status, failure_class, error, next_attempt_at, sent_idempotency_key, " +
            "change_from_quantity FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'receiving_session' AND trigger_id = ?", t.tenant(), trigger.toString());
    }

    private UUID stockTakeSync(T t) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO stock_take_sessions (id, tenant_id, status, scope_type, location_id, opened_by) " +
            "VALUES (?, ?, 'finalized', 'all', ?, ?)", session, t.tenant(), t.location(), t.owner());
        jdbc.update("INSERT INTO stock_take_shopify_syncs (tenant_id, session_id, status, payload) VALUES (?, ?, 'pending', ?::jsonb)",
            t.tenant(), session, "{\"locationId\": \"" + t.location() + "\", \"deltas\": {\"" + t.variant() + "\": 1}}");
        return session;
    }

    private UUID transferSync(T t) {
        UUID dest = UUID.randomUUID(), transfer = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, ?, false)",
            dest, t.tenant(), "Showroom " + seq.incrementAndGet());
        jdbc.update("INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, status, created_by) " +
            "VALUES (?, ?, 'showroom', ?, 'sent', ?)", transfer, t.tenant(), dest, t.owner());
        return jdbc.queryForObject("INSERT INTO transfer_shopify_syncs (tenant_id, transfer_id, location_id, status, deltas) " +
            "VALUES (?, ?, ?, 'queued', ?::jsonb) RETURNING id", UUID.class,
            t.tenant(), transfer, t.location(), "{\"" + t.variant() + "\": 1}");
    }

    private UUID part1Claim(T t) {
        UUID order = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'delivered'::order_status, now()) RETURNING id", UUID.class,
            t.tenant(), t.store(), "gid://shopify/Order/" + seq.incrementAndGet() + t.shop(), "#P1-" + seq.get());
        return jdbc.queryForObject("INSERT INTO main_reconcile_part1_claims (tenant_id, order_id, variant_id, location_id, " +
            "fulfillment_id, line_item_id, fulfilled_location_id, fulfilled_quantity, quantity, status) " +
            "VALUES (?, ?, ?, ?, ?, 'l1', '555', 1, 1, 'queued') RETURNING id", UUID.class,
            t.tenant(), order, t.variant(), t.location(), "f-" + seq.incrementAndGet());
    }

    /** A packed replacement piece of an internal exchange order named by an exchanges row, at the main warehouse
     *  (ExchangeDispatchAmbiguousTest's fixture). */
    private String replacementPiece(T t) {
        String tracking = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        UUID orderId = jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'awaiting_pickup'::order_status, now()) RETURNING id",
            UUID.class, t.tenant(), t.store(), "internal:exchange:" + tracking, "EXC-" + tracking);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.tenant(), orderId, t.variant());
        jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
            "VALUES (?, ?, 'bosta', ?, 'created'::shipment_internal_state, 'forward')", t.tenant(), orderId, tracking);
        String piece = UlidGenerator.generate();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, current_order_id) " +
            "VALUES (?, ?, ?, ?, 'P' || LPAD((abs(hashtext(?)) % 999999 + 1)::text, 6, '0'), 'packed'::piece_status, ?, ?)",
            piece, t.tenant(), t.variant(), "PC-" + piece, piece, t.location(), orderId);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            t.tenant(), item, piece);
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, outbound_variant_id, raw) " +
            "VALUES (?, ?, 'mapped', ?, ?, '{}'::jsonb)", t.tenant(), tracking, orderId, t.variant());
        return piece;
    }

    private List<Map<String, Object>> alerts(T t, String type) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> type.equals(e.get("type"))).toList();
    }

    /** The CRITICAL void_hold_sync_failed row for this trigger type, or null. */
    private Map<String, Object> critical(T t, String triggerType) {
        return alerts(t, "void_hold_sync_failed").stream()
            .filter(e -> triggerType.equals(e.get("trigger_type"))).findFirst().orElse(null);
    }
}
