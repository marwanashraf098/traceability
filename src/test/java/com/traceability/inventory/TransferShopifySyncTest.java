package com.traceability.inventory;

import com.traceability.integrations.shopify.ShopifyAmbiguousException;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Issue 2 (approved 2026-10-08) — transfers sync with Shopify, through the real TransferService
 * paths, Shopify gateway mocked.
 *
 *   ts1  send in 'remove' mode → one claim, ONE pushTransferOut call (−N per variant)
 *   ts2  retrying the send (push / sweep / a second claim) → no second call
 *   ts3  ambiguous response → failed_ambiguous, never re-sent by the sweep, CRITICAL alert raised
 *   ts4  came back good → +1 per piece; sold / lost / condemned → no write
 *   ts5  mode 'leave' → zero adjustment / claim rows and zero calls across send + reconcile + close
 *   ts6  the mode is snapshot at send: changing it mid-transfer changes nothing for that transfer
 *   ts7  main warehouse not linked → a skipped row with the reason, no call
 *   ts8  cancel is only possible before any scan → zero writes
 *   ts9  permanent move → decrement at close; bring-back → skipped (not from main), +1 on arrival
 *   ts10 pushTransferOut has exactly ONE call site (TransferShopifySync)
 *   ts11 a piece that left before the tenant's initial seed on an OLD send-and-back (no sent_at, no
 *        claim — the prod case) gets its +1 when it comes back: the seed never counted it
 *   ts12 the outbound −1 is failed_ambiguous / still pending → NO +1 on return (left for a person)
 *   ts13 bring-back of a permanently moved piece whose −1 applied → exactly ONE +1; a retry adds none
 *   ts14 main warehouse linked but never seeded → skipped 'not_seeded', no call
 *   ts15 no seed → the +1 can never fire, even with a pushed claim on record
 *   ts16 a definite rejection is re-sent by the sweep (and stops once pushed); an unexpected error
 *        after the call started is failed_ambiguous
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferShopifySyncTest {

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
    @Autowired TransferService transfers;
    @Autowired TransferShopifySync sync;
    @Autowired ExceptionService exceptions;
    @Autowired ShopifyInventoryService inventory;
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

    // ── ts1 ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void ts1_sendInRemoveMode_oneClaim_oneDecrementCall() throws Exception {
        T t = tenant("ts1", true);
        UUID tr = roundTrip(t, piece(t, t.shirt()), piece(t, t.shirt()), piece(t, t.scarf()));

        awaitSync(tr, "pushed");
        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(shopify, times(1)).pushTransferOut(any(), any(), deltas.capture(), eq(t.traced()),
            eq("traced://transfer/" + tr), anyString());
        assertThat(deltas.getValue()).extracting(ShopifyGateway.InventoryDelta::negativeDelta)
            .containsExactlyInAnyOrder(-2, -1);
        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", tr)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM transfers WHERE id = ?", String.class, tr)).isEqualTo("remove");
    }

    // ── ts2 ───────────────────────────────────────────────────────────────────

    @Test
    void ts2_retryingTheSend_neverCallsTwice() throws Exception {
        T t = tenant("ts2", true);
        UUID tr = roundTrip(t, piece(t, t.shirt()));
        awaitSync(tr, "pushed");
        UUID syncId = jdbc.queryForObject("SELECT id FROM transfer_shopify_syncs WHERE transfer_id = ?", UUID.class, tr);

        TenantContext.runAs(t.id(), () -> sync.pushNow(t.id(), syncId));
        TenantContext.runAs(t.id(), () -> sync.sweep(t.id()));
        // A second claim for the same transfer is refused by the one-per-transfer key.
        TenantContext.runAs(t.id(), () -> { inTx(() -> sync.claimSend(t.id(), tr)); return null; });
        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", tr)).isEqualTo(1);
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
    }

    // ── ts3 ───────────────────────────────────────────────────────────────────

    @Test
    void ts3_ambiguous_failedAmbiguous_neverResent_alertRaised() throws Exception {
        T t = tenant("ts3", true);
        doThrow(new ShopifyAmbiguousException("read timeout")).when(shopify)
            .pushTransferOut(any(), any(), anyList(), any(), any(), any());
        UUID tr = roundTrip(t, piece(t, t.shirt()));

        awaitSync(tr, "failed_ambiguous");
        jdbc.update("UPDATE transfer_shopify_syncs SET created_at = now() - interval '1 hour' WHERE transfer_id = ?", tr);
        TenantContext.runAs(t.id(), () -> sync.sweep(t.id()));
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        assertThat(status(tr)).isEqualTo("failed_ambiguous");

        List<Map<String, Object>> alerts = TenantContext.runAs(t.id(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "void_hold_sync_failed".equals(e.get("type")) && "transfer_out".equals(e.get("trigger_type"))).toList();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("severity")).isEqualTo("CRITICAL");
    }

    // ── ts4 ───────────────────────────────────────────────────────────────────

    @Test
    void ts4_cameBackGood_plusOnePerPiece_soldLostCondemned_noWrite() throws Exception {
        T t = tenant("ts4", true);
        String good1 = piece(t, t.shirt()), good2 = piece(t, t.shirt()), sold = piece(t, t.scarf()), lost = piece(t, t.scarf());
        UUID tr = roundTrip(t, good1, good2, sold, lost);
        awaitSync(tr, "pushed");

        run(t, () -> transfers.beginReconcile(tr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(tr, barcode(good1), "good", t.owner()));
        run(t, () -> transfers.reconcileScanBack(tr, barcode(good2), "good", t.owner()));
        UUID scarfLine = jdbc.queryForObject("SELECT id FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            UUID.class, tr, t.scarf());
        run(t, () -> transfers.classifyShortfall(tr, scarfLine, new TransferService.ShortfallCounts(1, 1, 0), t.owner()));
        run(t, () -> transfers.closeTransfer(tr, t.owner()));

        awaitReturns(t, 2);
        verify(shopify, times(2)).adjustInventoryQuantities(any(), any(), any(), eq(t.traced()), eq(1), eq("movement_received"), any());
        assertThat(jdbc.queryForList("SELECT split_part(trigger_id, ':', 1) FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'transfer_return' AND status = 'applied'", String.class, t.id()))
            .containsExactlyInAnyOrder(good1, good2);
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());   // only the send
    }

    // ── ts5 ───────────────────────────────────────────────────────────────────

    @Test
    void ts5_leaveMode_zeroWrites_acrossSendReconcileClose() throws Exception {
        T t = tenant("ts5", true);
        jdbc.update("UPDATE locations SET shopify_sync_mode = 'leave' WHERE id = ?", t.away());
        String a = piece(t, t.shirt()), b = piece(t, t.scarf());
        UUID tr = roundTrip(t, a, b);
        run(t, () -> transfers.beginReconcile(tr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(tr, barcode(a), "good", t.owner()));
        UUID scarfLine = jdbc.queryForObject("SELECT id FROM transfer_lines WHERE transfer_id = ? AND variant_id = ?",
            UUID.class, tr, t.scarf());
        run(t, () -> transfers.classifyShortfall(tr, scarfLine, new TransferService.ShortfallCounts(1, 0, 0), t.owner()));
        run(t, () -> transfers.closeTransfer(tr, t.owner()));
        Thread.sleep(500);

        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type <> 'initial_seed'", t.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", t.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT shopify_sync_mode FROM transfers WHERE id = ?", String.class, tr)).isEqualTo("leave");
        verifyNoInteractions(shopify);
    }

    // ── ts6 ───────────────────────────────────────────────────────────────────

    @Test
    void ts6_modeChangedMidTransfer_usesTheSnapshot() throws Exception {
        T t = tenant("ts6", true);
        // Sent in 'remove', then the location flips to 'leave' → the return still gets its +1.
        String p1 = piece(t, t.shirt());
        UUID removeTr = roundTrip(t, p1);
        awaitSync(removeTr, "pushed");
        jdbc.update("UPDATE locations SET shopify_sync_mode = 'leave' WHERE id = ?", t.away());
        run(t, () -> transfers.beginReconcile(removeTr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(removeTr, barcode(p1), "good", t.owner()));
        awaitReturns(t, 1);

        // Sent in 'leave', then the location flips to 'remove' → nothing on the way back either.
        String p2 = piece(t, t.shirt());
        UUID leaveTr = roundTrip(t, p2);
        jdbc.update("UPDATE locations SET shopify_sync_mode = 'remove' WHERE id = ?", t.away());
        run(t, () -> transfers.beginReconcile(leaveTr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(leaveTr, barcode(p2), "good", t.owner()));
        Thread.sleep(500);
        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ?", leaveTr)).isZero();
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return'",
            t.id())).isEqualTo(1);
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
    }

    // ── ts7 ───────────────────────────────────────────────────────────────────

    @Test
    void ts7_unlinkedMainWarehouse_skippedRowWithReason_noCall() throws Exception {
        T t = tenant("ts7", false);
        UUID tr = roundTrip(t, piece(t, t.shirt()));
        Thread.sleep(300);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, reason FROM transfer_shopify_syncs WHERE transfer_id = ?", tr);
        assertThat(row.get("status")).isEqualTo("skipped");
        assertThat(row.get("reason")).isEqualTo("main_warehouse_not_linked");
        verify(shopify, never()).pushTransferOut(any(), any(), anyList(), any(), any(), any());
    }

    // ── ts8 ───────────────────────────────────────────────────────────────────

    @Test
    void ts8_cancelOnlyBeforeAnyScan_zeroWrites() {
        T t = tenant("ts8", true);
        UUID empty = TenantContext.runAs(t.id(), () ->
            transfers.createTransfer("showroom", t.away(), null, "x", t.owner(), "round_trip"));
        run(t, () -> transfers.cancel(empty, t.owner()));
        assertThat(jdbc.queryForObject("SELECT status FROM transfers WHERE id = ?", String.class, empty)).isEqualTo("cancelled");

        UUID scanned = TenantContext.runAs(t.id(), () ->
            transfers.createTransfer("showroom", t.away(), null, "x", t.owner(), "round_trip"));
        String p = piece(t, t.shirt());
        TenantContext.runAs(t.id(), () -> transfers.scanOut(scanned, barcode(p), t.owner()));
        assertThatThrownBy(() -> run(t, () -> transfers.cancel(scanned, t.owner()))).isInstanceOf(TransferException.class);

        assertThat(count("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", t.id())).isZero();
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type <> 'initial_seed'", t.id())).isZero();
        verifyNoInteractions(shopify);
    }

    // ── ts9 ───────────────────────────────────────────────────────────────────

    @Test
    void ts9_permanentMove_decrementsAtClose_bringBack_skipped_thenPlusOneOnArrival() throws Exception {
        T t = tenant("ts9", true);
        String p = piece(t, t.shirt());
        UUID move = TenantContext.runAs(t.id(), () ->
            transfers.createTransfer("other", t.away(), null, "move", t.owner(), "relocate_out"));
        TenantContext.runAs(t.id(), () -> transfers.scanOut(move, barcode(p), t.owner()));
        run(t, () -> transfers.closeOneWay(move, t.owner()));
        awaitSync(move, "pushed");

        UUID back = TenantContext.runAs(t.id(), () ->
            transfers.createTransfer("other", t.main(), null, "back", t.owner(), "relocate_return", t.away()));
        TenantContext.runAs(t.id(), () -> transfers.returnScanOut(back, barcode(p), t.owner()));
        run(t, () -> transfers.markSent(back, t.owner()));
        Map<String, Object> row = jdbc.queryForMap("SELECT status, reason FROM transfer_shopify_syncs WHERE transfer_id = ?", back);
        assertThat(row).containsEntry("status", "skipped").containsEntry("reason", "not_from_main_warehouse");

        run(t, () -> transfers.beginReconcile(back, t.owner()));
        run(t, () -> transfers.reconcileScanBack(back, barcode(p), "good", t.owner()));
        awaitReturns(t, 1);
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        verify(shopify, times(1)).adjustInventoryQuantities(any(), any(), any(), eq(t.traced()), eq(1), eq("movement_received"), any());
    }

    // ── ts10 ──────────────────────────────────────────────────────────────────

    @Test
    void ts10_pushTransferOut_hasExactlyOneCallSite() throws Exception {
        List<String> callers = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            for (Path f : paths.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).contains("shopify.pushTransferOut(")) callers.add(f.getFileName().toString());
            }
        }
        assertThat(callers).containsExactly("TransferShopifySync.java");
    }

    // ── ts11 ──────────────────────────────────────────────────────────────────

    @Test
    void ts11_leftBeforeTheSeed_oldTransferWithoutSentAt_getsPlusOneOnReturn() throws Exception {
        T t = tenant("ts11", true);
        jdbc.update("UPDATE locations SET shopify_sync_mode = 'leave' WHERE id = ?", t.away());   // no claim at send
        String p = piece(t, t.shirt());
        UUID tr = roundTrip(t, p);
        // As a pre-V118 transfer in prod: no sent_at, created days ago; the tenant was seeded after it left.
        jdbc.update("UPDATE transfers SET sent_at = NULL, created_at = now() - interval '5 days' WHERE id = ?", tr);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, payload, status, created_at) VALUES (?, ?, ?, ?, 3, 'initial_seed', 'seed-early', '{}'::jsonb, " +
            "'applied', now() - interval '2 days')", t.id(), UUID.randomUUID(), t.shirt(), t.main());

        run(t, () -> transfers.beginReconcile(tr, t.owner()));
        jdbc.update("UPDATE transfers SET reconcile_started_at = NULL WHERE id = ?", tr);   // old row: neither stamp
        run(t, () -> transfers.reconcileScanBack(tr, barcode(p), "good", t.owner()));
        awaitReturns(t, 1);
        verify(shopify, times(1)).adjustInventoryQuantities(any(), any(), any(), eq(t.traced()), eq(1), eq("movement_received"), any());
        verify(shopify, never()).pushTransferOut(any(), any(), anyList(), any(), any(), any());
    }

    // ── ts12 ──────────────────────────────────────────────────────────────────

    @Test
    void ts12_outboundAmbiguousOrPending_noPlusOneOnReturn() throws Exception {
        T t = tenant("ts12", true);
        doThrow(new ShopifyAmbiguousException("read timeout")).when(shopify)
            .pushTransferOut(any(), any(), anyList(), any(), any(), any());
        String a = piece(t, t.shirt());
        UUID ambiguousTr = roundTrip(t, a);
        awaitSync(ambiguousTr, "failed_ambiguous");
        run(t, () -> transfers.beginReconcile(ambiguousTr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(ambiguousTr, barcode(a), "good", t.owner()));

        String b = piece(t, t.shirt());
        UUID pendingTr = roundTrip(t, b);
        awaitSync(pendingTr, "failed_ambiguous");
        jdbc.update("UPDATE transfer_shopify_syncs SET status = 'pending' WHERE transfer_id = ?", pendingTr);   // in flight
        run(t, () -> transfers.beginReconcile(pendingTr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(pendingTr, barcode(b), "good", t.owner()));
        Thread.sleep(600);

        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return'",
            t.id())).isZero();
        verify(shopify, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(TenantContext.runAs(t.id(), () -> exceptions.detectAllOpen()).stream()
            .filter(e -> "void_hold_sync_failed".equals(e.get("type")) && "transfer_out".equals(e.get("trigger_type"))).count())
            .as("the ambiguous one stays on the alert for a person").isEqualTo(1);
    }

    // ── ts13 ──────────────────────────────────────────────────────────────────

    @Test
    void ts13_bringBackAfterAppliedMove_exactlyOnePlusOne_retryAddsNone() throws Exception {
        T t = tenant("ts13", true);
        String p = piece(t, t.shirt());
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

        // Retry the +1 (a duplicate trigger / re-delivery): same key piece:transfer → no second call.
        inventory.onTransferReturn(t.id(), p, back, t.main()).get();
        inventory.onTransferReturn(t.id(), p, back, t.main()).get();
        Thread.sleep(300);
        verify(shopify, times(1)).adjustInventoryQuantities(any(), any(), any(), eq(t.traced()), eq(1), eq("movement_received"), any());
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return'",
            t.id())).isEqualTo(1);
    }

    // ── ts14 ──────────────────────────────────────────────────────────────────

    @Test
    void ts14_linkedButNeverSeeded_skippedNotSeeded_noCall() throws Exception {
        T t = tenant("ts14", true, false);
        UUID tr = roundTrip(t, piece(t, t.shirt()));
        Thread.sleep(300);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, reason FROM transfer_shopify_syncs WHERE transfer_id = ?", tr);
        assertThat(row).containsEntry("status", "skipped").containsEntry("reason", "not_seeded");
        verify(shopify, never()).pushTransferOut(any(), any(), anyList(), any(), any(), any());
    }

    // ── ts15 ──────────────────────────────────────────────────────────────────

    @Test
    void ts15_noSeed_plusOneNeverFires_evenWithAPushedClaimOnRecord() throws Exception {
        T t = tenant("ts15", true, false);
        String p = piece(t, t.shirt());
        UUID tr = roundTrip(t, p);
        // Whatever is on record, a tenant with no seed never gets the +1 (the seed would count it again).
        jdbc.update("UPDATE transfer_shopify_syncs SET status = 'pushed', reason = NULL, piece_ids = ?::jsonb WHERE transfer_id = ?",
            "[\"" + p + "\"]", tr);
        run(t, () -> transfers.beginReconcile(tr, t.owner()));
        run(t, () -> transfers.reconcileScanBack(tr, barcode(p), "good", t.owner()));
        Thread.sleep(600);
        assertThat(count("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return'",
            t.id())).isZero();
        verify(shopify, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── ts16 ──────────────────────────────────────────────────────────────────

    @Test
    void ts16_definiteRejection_resentBySweep_unexpectedError_ambiguous() throws Exception {
        T t = tenant("ts16", true);
        doThrow(new com.traceability.integrations.shopify.ShopifyException("HTTP 422"))
            .doNothing()
            .when(shopify).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        UUID tr = roundTrip(t, piece(t, t.shirt()));
        awaitSync(tr, "failed");
        TenantContext.runAs(t.id(), () -> sync.sweep(t.id()));
        assertThat(status(tr)).isEqualTo("pushed");
        TenantContext.runAs(t.id(), () -> sync.sweep(t.id()));
        verify(shopify, times(2)).pushTransferOut(any(), any(), anyList(), any(), any(), any());

        Mockito.reset(shopify);
        doThrow(new IllegalStateException("boom")).when(shopify).pushTransferOut(any(), any(), anyList(), any(), any(), any());
        UUID tr2 = roundTrip(t, piece(t, t.scarf()));
        awaitSync(tr2, "failed_ambiguous");
        jdbc.update("UPDATE transfer_shopify_syncs SET created_at = now() - interval '1 hour' WHERE transfer_id = ?", tr2);
        TenantContext.runAs(t.id(), () -> sync.sweep(t.id()));
        verify(shopify, times(1)).pushTransferOut(any(), any(), anyList(), any(), any(), any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    @Autowired org.springframework.transaction.PlatformTransactionManager txm;

    private void inTx(Runnable r) {
        new org.springframework.transaction.support.TransactionTemplate(txm).executeWithoutResult(s -> r.run());
    }

    private void run(T t, Runnable r) { TenantContext.runAs(t.id(), r); }

    /** A send-and-back: create, scan the pieces out, mark sent. */
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

    private String status(UUID transfer) {
        return jdbc.queryForObject("SELECT status FROM transfer_shopify_syncs WHERE transfer_id = ?", String.class, transfer);
    }

    private void awaitSync(UUID transfer, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            List<String> s = jdbc.queryForList("SELECT status FROM transfer_shopify_syncs WHERE transfer_id = ?", String.class, transfer);
            if (!s.isEmpty() && expected.equals(s.get(0))) return;
            if (System.currentTimeMillis() > deadline) throw new AssertionError("sync never reached " + expected + " (now " + s + ")");
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

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private static String barcode(String piece) { return "TS-" + piece; }

    private String piece(T t, UUID variant) {
        String id = String.format("01TRANSFERSYNC%012d", seq.incrementAndGet());
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?)", id, t.id(), variant, barcode(id), "T" + (1000000 + seq.get()), t.main());
        return id;
    }

    private T tenant(String name, boolean linked) { return tenant(name, linked, linked); }

    /** {@code seeded}: an applied initial seed an hour ago (the normal state of a linked tenant). */
    private T tenant(String name, boolean linked, boolean seeded) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), owner = UUID.randomUUID();
        UUID main = UUID.randomUUID(), away = UUID.randomUUID(), product = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Transfer " + name);
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
                "VALUES (?, ?, ?, ?, 'V', ?, ?)", v, tenant, product, "gid://shopify/ProductVariant/" + v, "SKU-" + v.toString().substring(0, 6),
                "gid://shopify/InventoryItem/" + v);
        }
        if (seeded) {
            jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
                "trigger_type, trigger_id, payload, status, created_at) VALUES (?, ?, ?, ?, 4, 'initial_seed', 'seed', '{}'::jsonb, " +
                "'applied', now() - interval '1 hour')", tenant, UUID.randomUUID(), shirt, main);
        }
        return new T(tenant, owner, main, away, shirt, scarf, traced);
    }
}
