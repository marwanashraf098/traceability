package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V167 — "Shopify is counting at the main warehouse" (approved 2026-10-14): main linked AND (an applied
 * initial_seed OR an applied receiving_session claim at main), ONE definition (shopify_counting_source),
 * used by the transfer send / return and every Lookup piece decision. The normal onboarding path —
 * connect with 0, receive into Traced — is never seeded; its receiving +1s count, so its departures
 * must come out again. Real TransferService / PieceAdjustService paths, Shopify gateway mocked.
 *
 *   g1 unseeded, receiving counted: send-and-back → claim queued → ONE push (−1) → back good → +1
 *   g2 unseeded, receiving counted: permanent move −1 at close → bring-back → +1 on arrival
 *   g3 unseeded, NO applied receiving (only a failed one): send skipped 'not_counting', no call; even a
 *      pushed claim on record never gives the +1 (the gate wraps the return too)
 *   g4 the send leaves out a piece whose own arrival never reached Shopify (failed legacy receiving
 *      claim / no claim at all) — −N counts only the rest, payload.excluded names each with its reason,
 *      and only the counted piece gets +1 back
 *   g5 every piece's arrival uncounted → skipped 'arrival_not_counted', payload lists them, no call
 *   g6 Lookup, unseeded, receiving counted: hold → −1 (pushHoldEnter) → unhold → +1; void → −1
 *   g7 Lookup, unseeded, NO applied receiving: hold → skipped 'not_counting', no call
 *   g8 shopify_counting_source: NULL / 'receiving' / 'seed' (seeded first, then receiving — The Snouts) /
 *      unlinked NULL; as app_user it sees only the caller's tenant (positive control on the same call)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShopifyCountingGateTest {

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
    @Autowired PlatformTransactionManager txm;
    @Autowired TransferService transfers;
    @Autowired TransferShopifySync sync;
    @Autowired PieceAdjustService adjust;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID id, UUID owner, UUID main, UUID away, UUID shirt, UUID scarf, String traced) {}

    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopify, tokenProvider);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── g1 ──────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void g1_unseededReceivingCounted_sendAndBack_minusOnePushed_plusOneOnReturn() throws Exception {
        T t = tenant("g1", true, false);
        String p = receivedPiece(t, t.shirt(), "applied", false);
        UUID tr = roundTrip(t, p);

        awaitSync(tr, "pushed");
        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(shopify, times(1)).pushTransferOut(any(), any(), deltas.capture(), eq(t.traced()),
            eq("traced://transfer/" + tr), anyString());
        assertThat(deltas.getValue()).extracting(ShopifyGateway.InventoryDelta::negativeDelta).containsExactly(-1);

        run(t, () -> transfers.beginReconcile(tr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(tr, barcode(p), "good", t.owner()));
        awaitReturns(t, 1);
        verify(shopify, times(1)).adjustInventoryQuantities(any(), any(), any(), eq(t.traced()), eq(1),
            eq("movement_received"), any());
    }

    // ── g2 ──────────────────────────────────────────────────────────────────

    @Test
    void g2_unseededReceivingCounted_permanentMove_thenBringBack_plusOneOnArrival() throws Exception {
        T t = tenant("g2", true, false);
        String p = receivedPiece(t, t.shirt(), "applied", false);
        UUID move = TenantContext.runAs(t.id(), () ->
            transfers.createTransfer("other", t.away(), null, "move", t.owner(), "relocate_out"));
        TenantContext.runAs(t.id(), () -> transfers.scanOut(move, barcode(p), t.owner()));
        run(t, () -> transfers.closeOneWay(move, t.owner()));
        awaitSync(move, "pushed");

        UUID back = TenantContext.runAs(t.id(), () ->
            transfers.createTransfer("other", t.main(), null, "back", t.owner(), "relocate_return", t.away()));
        TenantContext.runAs(t.id(), () -> transfers.returnScanOut(back, barcode(p), t.owner()));
        run(t, () -> transfers.markSent(back, t.owner()));
        run(t, () -> transfers.beginReconcile(back, t.owner()));
        run(t, () -> transfers.reconcileScanBack(back, barcode(p), "good", t.owner()));

        awaitReturns(t, 1);
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        verify(shopify, times(1)).adjustInventoryQuantities(any(), any(), any(), eq(t.traced()), eq(1),
            eq("movement_received"), any());
    }

    // ── g3 ──────────────────────────────────────────────────────────────────

    @Test
    void g3_unseededNoAppliedReceiving_skippedNotCounting_andNoPlusOneEvenWithPushedClaim() throws Exception {
        T t = tenant("g3", true, false);
        String p = receivedPiece(t, t.shirt(), "failed", false);
        UUID tr = roundTrip(t, p);
        Thread.sleep(300);
        assertThat(jdbc.queryForMap("SELECT status, reason FROM transfer_shopify_syncs WHERE transfer_id = ?", tr))
            .containsEntry("status", "skipped").containsEntry("reason", "not_counting");
        verify(shopify, never()).pushTransferOut(any(), any(), anyList(), any(), any(), any());

        // Whatever is on record, a tenant Shopify isn't counting for never gets the +1.
        jdbc.update("UPDATE transfer_shopify_syncs SET status = 'pushed', reason = NULL, piece_ids = ?::jsonb WHERE transfer_id = ?",
            "[\"" + p + "\"]", tr);
        run(t, () -> transfers.beginReconcile(tr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(tr, barcode(p), "good", t.owner()));
        Thread.sleep(600);
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return'",
            t.id())).isZero();
        verify(shopify, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── g4 ──────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void g4_sendLeavesOutPiecesWhoseArrivalNeverReachedShopify() throws Exception {
        T t = tenant("g4", true, false);
        String counted = receivedPiece(t, t.shirt(), "applied", false);
        String legacy  = receivedPiece(t, t.shirt(), "failed", true);   // failed legacy receiving claim
        String noClaim = piece(t, t.scarf(), null);                      // received with no claim at all
        UUID tr = roundTrip(t, counted, legacy, noClaim);

        awaitSync(tr, "pushed");
        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(shopify, times(1)).pushTransferOut(any(), any(), deltas.capture(), eq(t.traced()), any(), anyString());
        assertThat(deltas.getValue()).extracting(ShopifyGateway.InventoryDelta::negativeDelta).containsExactly(-1);
        assertThat(jdbc.queryForObject("SELECT piece_ids::text FROM transfer_shopify_syncs WHERE transfer_id = ?", String.class, tr))
            .isEqualTo("[\"" + counted + "\"]");
        List<Map<String, Object>> excluded = jdbc.queryForList(
            "SELECT e->>'piece_id' AS piece_id, e->>'reason' AS reason FROM transfer_shopify_syncs y, " +
            "jsonb_array_elements(y.payload->'excluded') e WHERE y.transfer_id = ? ORDER BY 1", tr);
        assertThat(excluded).containsExactlyInAnyOrder(
            Map.of("piece_id", legacy, "reason", "arrival_not_counted"),
            Map.of("piece_id", noClaim, "reason", "arrival_not_counted"));

        run(t, () -> transfers.beginReconcile(tr, t.owner()));
        for (String p : List.of(counted, legacy, noClaim)) {
            run(t, () -> transfers.reconcileScanBack(tr, barcode(p), "good", t.owner()));
        }
        awaitReturns(t, 1);
        Thread.sleep(300);
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return'",
            t.id())).as("only the counted piece comes back +1").isEqualTo(1);
    }

    // ── g5 ──────────────────────────────────────────────────────────────────

    @Test
    void g5_everyArrivalUncounted_skippedArrivalNotCounted_payloadListsThem_noCall() throws Exception {
        T t = tenant("g5", true, false);
        receivedPiece(t, t.scarf(), "applied", false);                  // Shopify counts this tenant
        String a = receivedPiece(t, t.shirt(), "failed", true);
        UUID tr = roundTrip(t, a);
        Thread.sleep(300);
        assertThat(jdbc.queryForMap("SELECT status, reason FROM transfer_shopify_syncs WHERE transfer_id = ?", tr))
            .containsEntry("status", "skipped").containsEntry("reason", "arrival_not_counted");
        assertThat(jdbc.queryForObject("SELECT payload->'excluded'->0->>'piece_id' FROM transfer_shopify_syncs WHERE transfer_id = ?",
            String.class, tr)).isEqualTo(a);
        verify(shopify, never()).pushTransferOut(any(), any(), anyList(), any(), any(), any());
    }

    // ── g6 ──────────────────────────────────────────────────────────────────

    @Test
    void g6_lookupUnseededReceivingCounted_holdMinusOne_unholdPlusOne_voidMinusOne() throws Exception {
        T t = tenant("g6", true, false);
        String held = receivedPiece(t, t.shirt(), "applied", false);
        String voided = receivedPiece(t, t.shirt(), "applied", false);

        run(t, () -> adjust.hold(held, "quality_check", null, t.owner()));
        awaitPiece(t, "hold_enter", held, "applied");
        verify(shopify, times(1)).pushHoldEnter(any(), any(), any(), eq(t.traced()), eq(-1), eq("traced://piece/" + held), anyString());

        run(t, () -> adjust.unhold(held, t.owner()));
        awaitPiece(t, "hold_exit", held, "applied");
        verify(shopify, times(1)).pushPieceIncrement(any(), any(), any(), eq(t.traced()), eq(1), any(), eq("traced://piece/" + held), anyString());

        run(t, () -> adjust.voidPiece(voided, "receiving_overcount", null, t.owner()));
        awaitPiece(t, "void_correction", voided, "applied");
        verify(shopify, times(1)).pushVoidCorrection(any(), any(), any(), eq(t.traced()), eq(-1), eq("traced://piece/" + voided), anyString());
    }

    // ── g7 ──────────────────────────────────────────────────────────────────

    @Test
    void g7_lookupUnseededNoAppliedReceiving_holdSkippedNotCounting_noCall() throws Exception {
        T t = tenant("g7", true, false);
        String p = receivedPiece(t, t.shirt(), "failed", false);

        run(t, () -> adjust.hold(p, "quality_check", null, t.owner()));

        assertThat(jdbc.queryForMap("SELECT status, skip_reason FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'hold_enter' AND split_part(trigger_id, ':', 1) = ?", t.id(), p))
            .containsEntry("status", "skipped").containsEntry("skip_reason", "not_counting");
        Thread.sleep(200);
        verify(shopify, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── g8 ──────────────────────────────────────────────────────────────────

    @Test
    void g8_countingSource_values_andAppUserSeesOnlyItsTenant() {
        T none = tenant("g8n", true, false);
        receivedPiece(none, none.shirt(), "failed", false);
        T byReceiving = tenant("g8r", true, false);
        receivedPiece(byReceiving, byReceiving.shirt(), "applied", false);
        T seedFirst = tenant("g8s", true, true);                       // seed an hour ago, receiving now
        receivedPiece(seedFirst, seedFirst.shirt(), "applied", false);
        T unlinked = tenant("g8u", false, false);
        receivedPiece(unlinked, unlinked.shirt(), "applied", false);

        assertThat(source(none.id())).isNull();
        assertThat(source(byReceiving.id())).isEqualTo("receiving");
        assertThat(source(seedFirst.id())).isEqualTo("seed");
        assertThat(source(unlinked.id())).isNull();

        // app_user + RLS: tenant A's count is invisible from tenant B; positive control on the same call.
        assertThat(sourceAsAppUser(none.id(), byReceiving.id())).isNull();
        assertThat(sourceAsAppUser(byReceiving.id(), byReceiving.id())).isEqualTo("receiving");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String source(UUID tenant) {
        return jdbc.queryForObject("SELECT shopify_counting_source(?)", String.class, tenant);
    }

    private String sourceAsAppUser(UUID guc, UUID asked) {
        return new TransactionTemplate(txm).execute(s -> {
            jdbc.execute("SET LOCAL ROLE app_user");
            jdbc.queryForObject("SELECT set_config('app.current_tenant', ?, true)", String.class, guc.toString());
            return jdbc.queryForObject("SELECT shopify_counting_source(?)", String.class, asked);
        });
    }

    private void run(T t, Runnable r) { TenantContext.runAs(t.id(), r); }

    private UUID roundTrip(T t, String... pieces) {
        UUID tr = TenantContext.runAs(t.id(), () ->
            transfers.createTransfer("showroom", t.away(), null, "trip", t.owner(), "round_trip"));
        for (String p : pieces) {
            TransferService.ScanOutResult r = TenantContext.runAs(t.id(), () -> transfers.scanOut(tr, barcode(p), t.owner()));
            assertThat(r.success()).as("scan-out " + p).isTrue();
        }
        run(t, () -> transfers.markSent(tr, t.owner()));
        return tr;
    }

    private void awaitSync(UUID transfer, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            List<String> s = jdbc.queryForList("SELECT status FROM transfer_shopify_syncs WHERE transfer_id = ?", String.class, transfer);
            if (!s.isEmpty() && expected.equals(s.get(0))) return;
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("sync never reached " + expected + " (now " + jdbc.queryForList(
                    "SELECT status, reason, payload::text FROM transfer_shopify_syncs WHERE transfer_id = ?", transfer) + ")");
            }
            Thread.sleep(25);
        }
    }

    private void awaitReturns(T t, int n) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return' " +
                "AND status = 'applied'", t.id()) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + " transfer_return");
            Thread.sleep(25);
        }
        Thread.sleep(200);
    }

    private void awaitPiece(T t, String triggerType, String piece, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = ? " +
                "AND split_part(trigger_id, ':', 1) = ? AND status = ?", t.id(), triggerType, piece, status) == 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(triggerType + " never reached " + status + ": " + jdbc.queryForList(
                    "SELECT trigger_type, status, skip_reason, error FROM shopify_inventory_adjustments WHERE tenant_id = ?", t.id()));
            }
            Thread.sleep(25);
        }
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private static String barcode(String piece) { return "CG-" + piece; }

    /** A piece received at main through a receipt whose receiving claim has {@code status}. */
    private String receivedPiece(T t, UUID variant, String status, boolean legacy) {
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
            receipt, t.id(), t.owner(), t.main());
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, legacy, attempt_count) " +
            "VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, ?, ?, ?)",
            t.id(), UUID.randomUUID(), variant, t.main(), receipt.toString(), status, legacy,
            "failed".equals(status) ? IncrementRecoveryRules.MAX_ATTEMPTS : 1);
        return piece(t, variant, receipt);
    }

    private String piece(T t, UUID variant, UUID receipt) {
        String id = String.format("01COUNTINGGATE%012d", seq.incrementAndGet());
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, receipt_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, ?)", id, t.id(), variant, barcode(id),
            "G" + (1000000 + seq.get()), t.main(), receipt);
        return id;
    }

    /** {@code seeded}: an applied initial seed an hour ago. */
    private T tenant(String name, boolean linked, boolean seeded) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        UUID main = UUID.randomUUID(), away = UUID.randomUUID(), product = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Gate " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            owner, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, ?)", main, tenant, linked ? traced : null, linked ? "linked" : "unsynced");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Showroom', 'warehouse', false, false)", away, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        UUID shirt = UUID.randomUUID(), scarf = UUID.randomUUID();
        for (UUID v : List.of(shirt, scarf)) {
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
                "VALUES (?, ?, ?, ?, 'V', ?, ?)", v, tenant, product, "gid://shopify/ProductVariant/" + v,
                "SKU-" + v.toString().substring(0, 6), "gid://shopify/InventoryItem/" + v);
        }
        if (seeded) {
            jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
                "trigger_type, trigger_id, payload, status, created_at) VALUES (?, ?, ?, ?, 4, 'initial_seed', 'seed', '{}'::jsonb, " +
                "'applied', now() - interval '1 hour')", tenant, UUID.randomUUID(), shirt, main);
        }
        return new T(tenant, owner, main, away, shirt, scarf, traced);
    }
}
