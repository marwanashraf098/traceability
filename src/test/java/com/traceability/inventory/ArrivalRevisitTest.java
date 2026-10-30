package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Drift fix 1 (2026-10-30) — skipped decrements are revisited once their arrival reaches Shopify (ArrivalRevisit).
 * testreciving, claims 2551 / 2552: a Lookup Sold was skipped as arrival_unconfirmed while the receiving +2 was held
 * (disconnect); after reconnect the +2 applied and the −1 was never sent — Shopify one too high.
 *
 * Fixtures mirror that tenant: Shopify counts through receiving (an earlier receipt's +N applied — no seed), and the
 * receipt under test has its +N held ('failed' never_sent, 1 attempt). "The arrival applies" is the real retry pass
 * (retryDueIncrements → markIncrementResult 'applied').
 *
 *   a1 — sold / write-off / hold / void / damage, each skipped during the held arrival → each sent exactly once after
 *        it applies (its own method); later retries and sweeps send nothing more
 *   a2 — the piece came back before the arrival applied (unhold, Not sold) → the skip is closed, nothing sent, the
 *        return writes nothing either
 *   a3 — crash between the +N commit and the revisit (claim marked applied, no revisit) → the sweep's backstop sends
 *        once; a second sweep sends nothing
 *   a4 — stock-take write-off of a never-arrived piece → left out of the session push (payload.excluded) with its own
 *        skipped piece_write_off claim → sent once through pushPieceWriteOff after the arrival applies
 *   a5 — exchange dispatch of a never-arrived replacement → skipped arrival_unconfirmed, no call → sent once after
 *   a6 — transfer that left such a piece out → arrival applies → no transfer re-send; HIGH transfer_stock_not_removed
 *        alert naming the transfer's pieces
 *   a7 — overlap: concurrent sweeps (backstop) and a live revisit racing a sweep → every owed −1 exactly once
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArrivalRevisitTest {

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
    @Autowired StockTakeService stockTake;
    @Autowired StockTakeReconciliationService reconciliation;
    @Autowired TransferShopifySync transfers;
    @Autowired ExceptionService exceptions;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @MockBean JobScheduler jobScheduler;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID user, UUID variant, String item) {}

    final List<JobLambda> enqueued = new CopyOnWriteArrayList<>();
    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider, jobScheduler);
        enqueued.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(jobScheduler.enqueue(any(JobLambda.class))).thenAnswer(inv -> { enqueued.add(inv.getArgument(0)); return null; });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── a1 ────────────────────────────────────────────────────────────────────────

    @Test
    void a1_departuresSkippedDuringHeldArrival_eachSentExactlyOnceAfterItApplies() {
        T t = tenant("a1");
        UUID r = heldReceipt(t, 5);
        String sold = piece(t, r), lost = piece(t, r), held = piece(t, r), voided = piece(t, r), damaged = piece(t, r);

        as(t, () -> adjust.markSold(sold, null, t.user()));
        as(t, () -> adjust.adjustPiece(lost, "lost", "cycle_count_missing", null, t.user()));
        as(t, () -> adjust.hold(held, "quality_check", null, t.user()));
        as(t, () -> adjust.voidPiece(voided, "duplicate_entry", null, t.user()));
        as(t, () -> adjust.adjustPiece(damaged, "damaged", "damaged_in_storage", null, t.user()));

        for (String[] c : new String[][] { {"piece_sold", sold}, {"piece_write_off", lost}, {"hold_enter", held},
                                           {"void_correction", voided}, {"damage_move", damaged} }) {
            assertThat(claim(t, c[0], c[1])).as(c[0]).containsEntry("status", "skipped")
                .containsEntry("skip_reason", "arrival_unconfirmed");
        }
        verifyNoDecrements();

        arrive(t, r);

        assertThat(receiving(t, r)).isEqualTo("applied");
        for (String[] c : new String[][] { {"piece_sold", sold}, {"piece_write_off", lost}, {"hold_enter", held},
                                           {"void_correction", voided}, {"damage_move", damaged} }) {
            assertThat(claim(t, c[0], c[1])).as(c[0]).containsEntry("status", "applied").containsEntry("skip_reason", null);
        }
        verify(shopifyGateway, times(1)).pushPieceSold(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()), eq(-1),
            eq("traced://piece/" + sold), anyString());
        verify(shopifyGateway, times(1)).pushPieceWriteOff(any(), any(), any(), any(), eq(-1), eq("traced://piece/" + lost), anyString());
        verify(shopifyGateway, times(1)).pushHoldEnter(any(), any(), any(), any(), eq(-1), eq("traced://piece/" + held), anyString());
        verify(shopifyGateway, times(1)).pushVoidCorrection(any(), any(), any(), any(), eq(-1), eq("traced://piece/" + voided), anyString());
        verify(shopifyGateway, times(1)).moveAvailableToDamaged(any(), any(), any(), any(), eq(1), eq("damaged"),
            eq("traced://piece/" + damaged), anyString());

        sweep(t); sweep(t);
        as(t, inventory::retryDueIncrements);
        verify(shopifyGateway, times(1)).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, times(1)).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, times(1)).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, times(1)).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, times(1)).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), any(), any(), eq(5), any(), any());
    }

    // ── a2 ────────────────────────────────────────────────────────────────────────

    @Test
    void a2_pieceBackBeforeTheArrivalApplies_nothingSent() {
        T t = tenant("a2");
        UUID r = heldReceipt(t, 2);
        String held = piece(t, r), sold = piece(t, r);
        as(t, () -> adjust.hold(held, "quality_check", null, t.user()));
        as(t, () -> adjust.markSold(sold, null, t.user()));

        as(t, () -> adjust.unhold(held, t.user()));
        as(t, () -> adjust.markNotSold(sold, t.user()));

        assertThat(claim(t, "hold_enter", held)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", PieceShopifyRules.NOT_SENT_PIECE_RETURNED);
        assertThat(claim(t, "hold_exit", held)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", PieceShopifyRules.DEPARTURE_NOT_REACHED);
        assertThat(claim(t, "piece_sold", sold)).containsEntry("skip_reason", PieceShopifyRules.NOT_SENT_PIECE_RETURNED);
        assertThat(claim(t, "piece_sold_return", sold)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", PieceShopifyRules.DEPARTURE_NOT_REACHED);

        arrive(t, r);
        sweep(t);

        assertThat(receiving(t, r)).isEqualTo("applied");
        verifyNoDecrements();
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(claim(t, "hold_enter", held)).containsEntry("status", "skipped");
        assertThat(claim(t, "piece_sold", sold)).containsEntry("status", "skipped");
    }

    // ── a3 ────────────────────────────────────────────────────────────────────────

    @Test
    void a3_crashBetweenTheCommitAndTheRevisit_backstopSendsOnce() {
        T t = tenant("a3");
        UUID r = heldReceipt(t, 3);
        String sold = piece(t, r);
        as(t, () -> adjust.markSold(sold, null, t.user()));

        // The +N committed 'applied' and the process died before anything else ran.
        jdbc.update("UPDATE shopify_inventory_adjustments SET status = 'applied', applied_at = now(), attempt_count = 2 " +
            "WHERE tenant_id = ? AND trigger_type = 'receiving_session' AND trigger_id = ?", t.tenant(), r.toString());
        assertThat(claim(t, "piece_sold", sold)).containsEntry("status", "skipped");

        sweep(t);
        sweep(t);

        assertThat(claim(t, "piece_sold", sold)).containsEntry("status", "applied");
        verify(shopifyGateway, times(1)).pushPieceSold(any(), any(), eq(t.item()), any(), eq(-1), eq("traced://piece/" + sold), anyString());
    }

    // ── a4 ────────────────────────────────────────────────────────────────────────

    @Test
    void a4_stockTakeWriteOffOfNeverArrivedPiece_skippedThenRevisited() throws Exception {
        T t = tenant("a4");
        UUID counted = appliedReceipt(t, 2), r = heldReceipt(t, 1);
        String a1 = piece(t, counted), a2 = piece(t, counted), b1 = piece(t, r);
        UUID s = TenantContext.runAs(t.tenant(), () ->
            (UUID) stockTake.openSession("all", null, t.location(), null, t.user()).get("sessionId"));
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, a1, "good", t.user()));
        TenantContext.runAs(t.tenant(), () -> reconciliation.attestComplete(s, t.user()));

        TenantContext.runAs(t.tenant(), () -> reconciliation.finalizeSession(s, t.user(), 2));

        assertThat(status(a2)).isEqualTo("lost");
        assertThat(status(b1)).isEqualTo("lost");
        assertThat(jdbc.queryForObject("SELECT payload->'deltas'::text FROM stock_take_shopify_syncs WHERE session_id = ?",
            String.class, s)).as("only the counted piece's write-off is pushed").isEqualTo("{\"" + t.variant() + "\": 1}");
        assertThat(jdbc.queryForObject("SELECT payload->'excluded'->>? FROM stock_take_shopify_syncs WHERE session_id = ?",
            String.class, b1, s)).isEqualTo("arrival_unconfirmed");
        assertThat(claim(t, "piece_write_off", b1)).containsEntry("trigger_id", b1 + ":stock_take:" + s)
            .containsEntry("status", "skipped").containsEntry("skip_reason", "arrival_unconfirmed");

        assertThat(enqueued).hasSize(1);
        enqueued.get(0).run();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway).pushStockTakeWriteOff(any(), any(), deltas.capture(), eq(t.traced()), any(), any());
        assertThat(deltas.getValue()).singleElement().extracting(ShopifyGateway.InventoryDelta::negativeDelta).isEqualTo(-1);
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());

        arrive(t, r);
        sweep(t);

        assertThat(claim(t, "piece_write_off", b1)).containsEntry("status", "applied");
        verify(shopifyGateway, times(1)).pushPieceWriteOff(any(), any(), eq(t.item()), eq(t.traced()), eq(-1),
            eq("traced://piece/" + b1), anyString());
        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
    }

    // ── a5 ────────────────────────────────────────────────────────────────────────

    @Test
    void a5_exchangeDispatchOfNeverArrivedReplacement_skippedThenSentOnce() throws Exception {
        T t = tenant("a5");
        UUID r = heldReceipt(t, 1);
        String replacement = exchangeReplacement(t, r);

        inventory.onExchangeReplacementDispatched(t.tenant(), replacement).get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForMap("SELECT status, skip_reason FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'exchange_dispatch' AND trigger_id = ?", t.tenant(), replacement))
            .containsEntry("status", "skipped").containsEntry("skip_reason", "arrival_unconfirmed");
        verify(shopifyGateway, never()).pushExchangeDispatch(any(), any(), any(), any(), anyInt(), any(), any());

        arrive(t, r);
        sweep(t);
        as(t, () -> inventory.resumeHeldExchangeDispatches(t.tenant()));

        assertThat(jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'exchange_dispatch' AND trigger_id = ?", String.class, t.tenant(), replacement)).isEqualTo("applied");
        verify(shopifyGateway, times(1)).pushExchangeDispatch(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()), eq(-1),
            eq("traced://piece/" + replacement), anyString());
    }

    // ── a6 ────────────────────────────────────────────────────────────────────────

    @Test
    void a6_transferThatLeftThePieceOut_alertOnly_noResend() {
        T t = tenant("a6");
        UUID r = heldReceipt(t, 2);
        String away = piece(t, r), stays = piece(t, r);
        UUID showroom = UUID.randomUUID(), transfer = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, is_fulfillment) VALUES (?, ?, 'Showroom', false)",
            showroom, t.tenant());
        jdbc.update("INSERT INTO transfers (id, tenant_id, transfer_type, destination_location_id, status, created_by) " +
            "VALUES (?, ?, 'showroom', ?, 'sent', ?)", transfer, t.tenant(), showroom, t.user());
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?", showroom, away);
        jdbc.update("INSERT INTO transfer_shopify_syncs (tenant_id, transfer_id, location_id, status, reason, payload) " +
            "VALUES (?, ?, ?, 'skipped', 'arrival_not_counted', ?::jsonb)", t.tenant(), transfer, t.location(),
            "{\"excluded\": [{\"piece_id\": \"" + away + "\", \"variant_id\": \"" + t.variant() + "\", \"reason\": \"arrival_unconfirmed\"}]}");
        assertThat(alert(t, "transfer_stock_not_removed")).as("nothing owed while the arrival hasn't applied").isNull();

        arrive(t, r);
        sweep(t);
        as(t, () -> transfers.sweep(t.tenant()));

        verify(shopifyGateway, never()).pushTransferOut(any(), any(), any(), any(), any(), any());
        verifyNoDecrements();
        Map<String, Object> a = alert(t, "transfer_stock_not_removed");
        assertThat(a).isNotNull();
        assertThat(a.get("severity")).isEqualTo("HIGH");
        assertThat(a.get("transfer_id")).isEqualTo(transfer);
        assertThat((String) a.get("descriptionEn")).startsWith("Transfer to Showroom: Shopify still counts 1 piece(s)")
            .contains("PS-" + away).doesNotContain("PS-" + stays);
        assertThat((String) a.get("descriptionAr")).contains("PS-" + away);
        assertThat(a.get("actionUrl")).isEqualTo("/transfers/" + transfer);
    }

    // ── a7 ────────────────────────────────────────────────────────────────────────

    @Test
    void a7_sweepAndRevisitOverlap_everyOwedDecrementExactlyOnce() throws Exception {
        T t = tenant("a7");
        UUID crashed = heldReceipt(t, 3), live = heldReceipt(t, 3);
        List<String> crashedPieces = List.of(piece(t, crashed), piece(t, crashed), piece(t, crashed));
        List<String> livePieces = List.of(piece(t, live), piece(t, live), piece(t, live));
        for (String p : crashedPieces) as(t, () -> adjust.markSold(p, null, t.user()));
        for (String p : livePieces) as(t, () -> adjust.markSold(p, null, t.user()));
        jdbc.update("UPDATE shopify_inventory_adjustments SET status = 'applied', applied_at = now() " +
            "WHERE tenant_id = ? AND trigger_type = 'receiving_session' AND trigger_id = ?", t.tenant(), crashed.toString());
        makeDue(t, live);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        runs.add(pool.submit(() -> { await(go); return TenantContext.runAs(t.tenant(), inventory::retryDueIncrements); }));
        for (int i = 0; i < 3; i++) {
            runs.add(pool.submit(() -> { await(go); return TenantContext.runAs(t.tenant(), () -> inventory.sweepPieceClaims(t.tenant())); }));
        }
        go.countDown();
        for (Future<?> f : runs) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        sweep(t);   // whatever the race left queued goes now — still once

        for (String p : crashedPieces) assertThat(claim(t, "piece_sold", p)).as(p).containsEntry("status", "applied");
        for (String p : livePieces) assertThat(claim(t, "piece_sold", p)).as(p).containsEntry("status", "applied");
        for (String p : concat(crashedPieces, livePieces)) {
            verify(shopifyGateway, times(1)).pushPieceSold(any(), any(), any(), any(), eq(-1), eq("traced://piece/" + p), anyString());
        }
        verify(shopifyGateway, times(6)).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static void await(CountDownLatch l) {
        try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a); out.addAll(b); return out;
    }

    private void verifyNoDecrements() {
        verify(shopifyGateway, never()).pushPieceSold(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).pushExchangeDispatch(any(), any(), any(), any(), anyInt(), any(), any());
    }

    /** A connected, linked tenant that Shopify counts through receiving (an earlier receipt's +N applied — no seed). */
    private T tenant(String name) {
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
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, "gid://shopify/ProductVariant/" + shop, "SKU-" + name, item);
        T t = new T(tenant, store, shop, location, traced, user, variant, item);
        appliedReceipt(t, 1);   // Shopify counts at the main warehouse (shopify_counting_source = 'receiving')
        return t;
    }

    private UUID appliedReceipt(T t, int units) {
        return receipt(t, units, "applied", 1, null, "now() - interval '2 days'");
    }

    /** A receipt whose +N is held: failed never_sent after 1 attempt (a disconnect), next retry in an hour. */
    private UUID heldReceipt(T t, int units) {
        return receipt(t, units, "failed", 1, "never_sent", "now() - interval '1 hour'");
    }

    private UUID receipt(T t, int units, String status, int attempts, String failureClass, String atSql) {
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, t.tenant(), t.user(), t.location());
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, attempt_count, failure_class, error, first_attempt_at, next_attempt_at, " +
            "created_at) VALUES (?, ?, ?, ?, ?, 'receiving_session', ?, ?::jsonb, ?, ?, ?, ?, " + atSql + ", " +
            ("failed".equals(status) ? "now() + interval '1 hour'" : "NULL") + ", " + atSql + ")",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), units, receipt.toString(),
            "{\"reason\": \"received\", \"delta\": " + units + "}", status, attempts, failureClass,
            "failed".equals(status) ? "Not sent — Shopify needs to be reconnected" : null);
        return receipt;
    }

    private String piece(T t, UUID receipt) {
        int k = seq.incrementAndGet();
        String id = "01AR" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, receipt_id) " +
            "VALUES (?, ?, ?, 'available'::piece_status, ?, ?, ?, ?)",
            id, t.tenant(), t.variant(), "PS-" + id, String.format("R%07d", k), t.location(), receipt);
        return id;
    }

    /** An internal exchange order's replacement, packed and handed to the courier, received on {@code receipt}. */
    private String exchangeReplacement(T t, UUID receipt) {
        String tracking = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
        UUID orderId = jdbc.queryForObject("INSERT INTO orders (tenant_id, store_id, external_id, number, status, placed_at) " +
            "VALUES (?, ?, ?, ?, 'awaiting_pickup'::order_status, now()) RETURNING id",
            UUID.class, t.tenant(), t.store(), "internal:exchange:" + tracking, "EXC-" + tracking);
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.tenant(), orderId, t.variant());
        String piece = piece(t, receipt);
        jdbc.update("UPDATE pieces SET status = 'with_courier', current_order_id = ? WHERE id = ?", orderId, piece);
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed')",
            t.tenant(), item, piece);
        jdbc.update("INSERT INTO exchanges (tenant_id, tracking_number, status, outbound_order_id, outbound_variant_id, raw) " +
            "VALUES (?, ?, 'mapped', ?, ?, '{}'::jsonb)", t.tenant(), tracking, orderId, t.variant());
        return piece;
    }

    private void makeDue(T t, UUID receipt) {
        jdbc.update("UPDATE shopify_inventory_adjustments SET next_attempt_at = now() - interval '1 second' " +
            "WHERE tenant_id = ? AND trigger_type = 'receiving_session' AND trigger_id = ?", t.tenant(), receipt.toString());
    }

    /** The held +N goes out: the real retry pass (its claim → applied → the revisit). */
    private void arrive(T t, UUID receipt) {
        makeDue(t, receipt);
        ShopifyInventoryService.RetryResult r = TenantContext.runAs(t.tenant(), inventory::retryDueIncrements);
        assertThat(r.blockedBy()).isNull();
    }

    private void sweep(T t) {
        TenantContext.runAs(t.tenant(), () -> inventory.sweepPieceClaims(t.tenant()));
    }

    private String receiving(T t, UUID receipt) {
        return jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'receiving_session' AND trigger_id = ?", String.class, t.tenant(), receipt.toString());
    }

    private String status(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    private void as(T t, Runnable r) {
        TenantContext.runAs(t.tenant(), () -> { r.run(); return null; });
    }

    private Map<String, Object> claim(T t, String triggerType, String piece) {
        return jdbc.queryForMap("SELECT trigger_id, status, skip_reason, attempt_count FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = ? AND split_part(trigger_id, ':', 1) = ?", t.tenant(), triggerType, piece);
    }

    private Map<String, Object> alert(T t, String type) {
        return TenantContext.runAs(t.tenant(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> type.equals(e.get("type"))).findFirst().orElse(null);
    }
}
