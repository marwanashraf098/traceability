package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Lookup adjustments ↔ Shopify — C2 (approved 2026-10-10: D1, D4 non-main, D5, D8, D10, D11).
 * Fixtures mirror production: a tenant seeded an hour ago, pieces received a day ago (before the
 * seed) and adjusted now; a returned piece that still holds its 'packed' allocation.
 *
 *   w1/w2 Lookup Lost / Destroyed of a counted piece → one piece_write_off claim, one pushPieceWriteOff(−1)
 *   f1 Found it after an applied write-off → +1      f2 after a write-off that never pushed → nothing
 *   f3 Found it while the write-off is still retryable → cancelled, never sent
 *   f4 stock-take found after a pushed Lookup Lost → +1    f5 … while the write-off is retryable → cancelled
 *   m1 'leave'-mode piece at another location → Lost → −1 at MAIN
 *   m2 'remove' move pushed → skipped departure_removed   m3 move not confirmed → departure_ambiguous + alert
 *   m4 moved before the seed → not_counted_at_main
 *   b1 Lookup damaged (move applied) → Back to good → ONE damaged → available move
 *   b2 return-inspection damaged (packed allocation kept) → Back to good → +1; b3 with a Shopify refund
 *      restock outstanding → skipped_shopify_restocked; a later refund restock raises restocked_twice
 *   b4 damaged before the seed → +1                  b5 failed damage move → no call, skipped row
 *   b6 ambiguous damage move → skipped + alert         b7 concurrent double Back to good → one call
 *   b8 destroyed / voided → available still refused    b9 event, condition, custody phrase
 *   rp1 repair dry run: reads Shopify, zero writes; apply sends each correction once, a re-run nothing
 *   c1 same tenant: a receiving +1 and a transfer-out push still work
 *   x1 app_user: tenant B can't claim or sweep tenant A's pieces; A's own claim goes through
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PieceShopifySyncC2Test {

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
    @Autowired ObjectMapper mapper;
    @Autowired PieceAdjustService adjust;
    @Autowired ShopifyInventoryService inventory;
    @Autowired ReturnService returns;
    @Autowired TransferService transfers;
    @Autowired StockTakeService stockTake;
    @Autowired StockTakeReconciliationService reconciliation;
    @Autowired ExceptionService exceptions;
    @Autowired LookupAdjustRepairService repair;
    @MockBean JobScheduler jobScheduler;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID user, UUID variant,
             String variantGid, String item, UUID away) {}

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void reset() {
        Mockito.reset(shopifyGateway, tokenProvider, jobScheduler);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.fetchAvailableQuantities(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ShopifyGateway.InventoryLevel> out = new ArrayList<>();
            for (Object item : (List<?>) inv.getArgument(3)) out.add(new ShopifyGateway.InventoryLevel((String) item, 0));
            return out;
        });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── D1: Lookup lost / destroyed ───────────────────────────────────────────────

    @Test
    void w1_lost_oneWriteOffClaim_oneDecrement() throws Exception {
        assertWriteOff("w1", "lost", "cycle_count_missing");
    }

    @Test
    void w2_destroyed_oneWriteOffClaim_oneDecrement() throws Exception {
        assertWriteOff("w2", "destroyed", "damaged_in_storage");
    }

    private void assertWriteOff(String name, String to, String reason) throws Exception {
        T t = tenant(name, true);
        String piece = piece(t, "available", true);

        as(t, () -> adjust.adjustPiece(piece, to, reason, null, t.user()));

        awaitStatus(t, "piece_write_off", piece, "applied");
        String writeOffEventId = jdbc.queryForObject("SELECT metadata->>'write_off_event_id' FROM piece_events " +
            "WHERE piece_id = ? AND to_status = ?::piece_status", String.class, piece, to);
        Map<String, Object> c = claim(t, "piece_write_off", piece);
        assertThat(c).containsEntry("trigger_id", piece + ":" + writeOffEventId).containsEntry("delta", -1)
            .containsEntry("location_id", t.location());
        verify(shopifyGateway, times(1)).pushPieceWriteOff(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(-1), eq("traced://piece/" + piece), anyString());
        assertThat(count(t, "trigger_type <> 'initial_seed'")).isEqualTo(1);
    }

    // ── D5: Found it ──────────────────────────────────────────────────────────────

    @Test
    void f1_foundItAfterAppliedWriteOff_plusOne() throws Exception {
        T t = tenant("f1", true);
        String piece = piece(t, "available", true);
        as(t, () -> adjust.adjustPiece(piece, "lost", "cycle_count_missing", null, t.user()));
        awaitStatus(t, "piece_write_off", piece, "applied");

        as(t, () -> adjust.adjustPiece(piece, "available", "cycle_count_missing", null, t.user()));

        awaitStatus(t, "piece_write_off_return", piece, "applied");
        verify(shopifyGateway, times(1)).pushPieceIncrement(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(1), eq("correction"), eq("traced://piece/" + piece), anyString());
    }

    @Test
    void f2_foundItAfterWriteOffThatNeverPushed_nothing() throws Exception {
        T t = tenant("f2", true);
        String piece = piece(t, "available", false);   // received after the seed, never counted
        as(t, () -> adjust.adjustPiece(piece, "lost", "cycle_count_missing", null, t.user()));
        assertThat(claim(t, "piece_write_off", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "arrival_not_counted");

        as(t, () -> adjust.adjustPiece(piece, "available", "cycle_count_missing", null, t.user()));

        assertThat(claim(t, "piece_write_off_return", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "departure_not_reached");
        Thread.sleep(300);
        verifyNoWrites();
    }

    @Test
    void f3_foundItWhileWriteOffRetryable_cancelled_neverSent() throws Exception {
        T t = tenant("f3", true);
        String piece = piece(t, "available", true);
        doThrow(new ShopifyException("Piece write-off HTTP 422")).when(shopifyGateway)
            .pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        as(t, () -> adjust.adjustPiece(piece, "lost", "cycle_count_missing", null, t.user()));
        awaitStatus(t, "piece_write_off", piece, "failed");

        as(t, () -> adjust.adjustPiece(piece, "available", "cycle_count_missing", null, t.user()));
        as(t, () -> inventory.sweepPieceClaims(t.tenant()));

        assertThat(claim(t, "piece_write_off", piece)).containsEntry("status", "cancelled");
        assertThat(claim(t, "piece_write_off_return", piece)).containsEntry("skip_reason", "departure_cancelled");
        verify(shopifyGateway, times(1)).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void f4_stockTakeFoundAfterPushedLookupLost_plusOne() throws Exception {
        T t = tenant("f4", true);
        String piece = piece(t, "available", true);
        String other = piece(t, "available", true);
        as(t, () -> adjust.adjustPiece(piece, "lost", "theft_suspected", null, t.user()));
        awaitStatus(t, "piece_write_off", piece, "applied");

        UUID s = TenantContext.runAs(t.tenant(), () ->
            (UUID) stockTake.openSession("all", null, t.location(), null, t.user()).get("sessionId"));
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, other, "good", t.user()));
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, piece, "good", t.user()));
        TenantContext.runAs(t.tenant(), () -> reconciliation.attestComplete(s, t.user()));
        Map<String, Object> plan = (Map<String, Object>) TenantContext.runAs(t.tenant(),
            () -> reconciliation.reconciliation(s)).get("finalizePlan");
        assertThat(plan).containsEntry("foundIncrements", 1L);
        TenantContext.runAs(t.tenant(), () -> reconciliation.finalizeSession(s, t.user(), null));

        awaitClaim(t, "stock_take_found", "applied");
        verify(shopifyGateway, timeout(5000)).adjustInventoryQuantities(eq(t.shop()), any(), eq(t.item()), eq(t.traced()),
            eq(1), eq("correction"), anyString());
    }

    @Test
    void f5_stockTakeFoundWhileWriteOffRetryable_cancelled_neverSent() throws Exception {
        T t = tenant("f5", true);
        String piece = piece(t, "available", true);
        String other = piece(t, "available", true);
        doThrow(new ShopifyException("Piece write-off HTTP 422")).when(shopifyGateway)
            .pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        as(t, () -> adjust.adjustPiece(piece, "lost", "theft_suspected", null, t.user()));
        awaitStatus(t, "piece_write_off", piece, "failed");

        UUID s = TenantContext.runAs(t.tenant(), () ->
            (UUID) stockTake.openSession("all", null, t.location(), null, t.user()).get("sessionId"));
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, other, "good", t.user()));
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, piece, "good", t.user()));
        TenantContext.runAs(t.tenant(), () -> reconciliation.attestComplete(s, t.user()));
        TenantContext.runAs(t.tenant(), () -> reconciliation.finalizeSession(s, t.user(), null));
        as(t, () -> inventory.sweepPieceClaims(t.tenant()));

        assertThat(status(piece)).isEqualTo("available");
        assertThat(claim(t, "piece_write_off", piece)).containsEntry("status", "cancelled");
        verify(shopifyGateway, times(1)).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(count(t, "trigger_type = 'stock_take_found'")).as("never reached Shopify — no +1").isZero();
    }

    // ── D4: pieces away from the main warehouse ───────────────────────────────────

    @Test
    void m1_leaveModePieceAway_lost_decrementsAtMain() throws Exception {
        T t = tenant("m1", true);
        jdbc.update("UPDATE locations SET shopify_sync_mode = 'leave' WHERE id = ?", t.away());
        String piece = piece(t, "available", true);
        relocate(t, piece);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE tenant_id = ?", Integer.class,
            t.tenant())).as("leave mode: the move wrote nothing").isZero();

        as(t, () -> adjust.adjustPiece(piece, "lost", "theft_suspected", null, t.user()));

        awaitStatus(t, "piece_write_off", piece, "applied");
        verify(shopifyGateway, times(1)).pushPieceWriteOff(any(), any(), eq(t.item()), eq(t.traced()), eq(-1), any(), any());
        assertThat(claim(t, "piece_write_off", piece).get("location_id")).isEqualTo(t.location());
    }

    @Test
    void m2_removeModeMovePushed_lost_skippedDepartureRemoved() throws Exception {
        T t = tenant("m2", true);
        String piece = piece(t, "available", true);
        UUID move = relocate(t, piece);
        awaitTransfer(move, "pushed");

        as(t, () -> adjust.adjustPiece(piece, "lost", "theft_suspected", null, t.user()));

        assertThat(claim(t, "piece_write_off", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "departure_removed");
        Thread.sleep(300);
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void m3_moveNotConfirmed_lost_skippedAmbiguous_alert() throws Exception {
        T t = tenant("m3", true);
        doThrow(new ShopifyAmbiguousException("Transfer out: no confirmed response")).when(shopifyGateway)
            .pushTransferOut(any(), any(), anyList(), any(), any(), any());
        String piece = piece(t, "available", true);
        UUID move = relocate(t, piece);
        awaitTransfer(move, "failed_ambiguous");

        as(t, () -> adjust.adjustPiece(piece, "lost", "theft_suspected", null, t.user()));

        Map<String, Object> c = claim(t, "piece_write_off", piece);
        assertThat(c).containsEntry("status", "skipped").containsEntry("skip_reason", "departure_ambiguous");
        assertThat(alertKeys(t)).contains("void_hold_sync_failed:piece_write_off:" + c.get("trigger_id"));
        Thread.sleep(300);
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void m4_movedBeforeTheSeed_lost_notCountedAtMain() throws Exception {
        T t = tenant("m4", true);
        jdbc.update("UPDATE locations SET shopify_sync_mode = 'leave' WHERE id = ?", t.away());
        String piece = piece(t, "available", true);
        UUID move = relocate(t, piece);
        jdbc.update("UPDATE transfers SET closed_at = now() - interval '2 hours' WHERE id = ?", move);

        as(t, () -> adjust.adjustPiece(piece, "lost", "theft_suspected", null, t.user()));

        assertThat(claim(t, "piece_write_off", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "not_counted_at_main");
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── D11: Back to good ─────────────────────────────────────────────────────────

    @Test
    void b1_lookupDamaged_moveApplied_backToGood_oneReverseMove() throws Exception {
        T t = tenant("b1", true);
        String piece = piece(t, "available", true);
        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));
        awaitStatus(t, "damage_move", piece, "applied");

        as(t, () -> adjust.restore(piece, "repaired", null, t.user()));

        awaitStatus(t, "damage_restore", piece, "applied");
        verify(shopifyGateway, times(1)).moveDamagedToAvailable(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(1), eq("correction"), eq("traced://piece/" + piece), anyString());
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        assertThat(status(piece)).isEqualTo("available");
    }

    @Test
    void b2_returnInspectionDamaged_packedAllocationKept_backToGood_plusOne() throws Exception {
        T t = tenant("b2", true);
        String piece = returnedDamagedPiece(t, null);

        as(t, () -> adjust.restore(piece, "mis_graded", null, t.user()));

        awaitStatus(t, "damaged_restore_increment", piece, "applied");
        verify(shopifyGateway, times(1)).pushPieceIncrement(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()),
            eq(1), eq("correction"), eq("traced://piece/" + piece), anyString());
        verify(shopifyGateway, never()).moveDamagedToAvailable(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void b3_returnInspectionDamaged_refundRestockOutstanding_skipped_thenLateRestockAlerts() throws Exception {
        T t = tenant("b3", true);
        String refundRaw = "{\"refunds\":[{\"refund_line_items\":[{\"quantity\":1,\"restock_type\":\"return\"," +
            "\"line_item\":{\"variant_id\":" + t.variantGid().replace("gid://shopify/ProductVariant/", "") + "}}]}]}";
        String piece = returnedDamagedPiece(t, refundRaw);
        UUID order = jdbc.queryForObject("SELECT oi.order_id FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE a.piece_id = ?", UUID.class, piece);

        as(t, () -> adjust.restore(piece, "repaired", null, t.user()));

        Map<String, Object> c = claim(t, "damaged_restore_increment", piece);
        assertThat(c).containsEntry("status", "skipped_shopify_restocked").containsEntry("skip_reason", "shopify_restocked");
        Thread.sleep(300);
        verifyNoWrites();

        // Positive control on the same order: a second unit restored after Traced's count is level → +1, and a
        // further refund restock arriving afterwards is the late double count → restocked_twice.
        String second = returnedDamagedPiece(t, null);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", order, second);
        as(t, () -> adjust.restore(second, "repaired", null, t.user()));
        awaitStatus(t, "damaged_restore_increment", second, "applied");
        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?", refundRaw.replace("\"quantity\":1", "\"quantity\":2"), order);
        assertThat(alertTypes(t)).contains("restocked_twice");
    }

    @Test
    void b4_damagedBeforeTheSeed_backToGood_plusOne() throws Exception {
        T t = tenant("b4", false);
        String piece = piece(t, "available", true);
        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));   // unseeded: skipped
        insertSeed(t, "now()");

        as(t, () -> adjust.restore(piece, "repaired", null, t.user()));

        awaitStatus(t, "damaged_restore_increment", piece, "applied");
        verify(shopifyGateway, times(1)).pushPieceIncrement(any(), any(), eq(t.item()), eq(t.traced()), eq(1), any(), any(), any());
        verify(shopifyGateway, never()).moveDamagedToAvailable(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void b5_failedDamageMove_backToGood_noCall_skippedRow() throws Exception {
        T t = tenant("b5", true);
        String piece = piece(t, "available", true);
        doThrow(new ShopifyException("inventoryMoveQuantities failed: Not enough available")).when(shopifyGateway)
            .moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any(), any());
        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));
        awaitStatus(t, "damage_move", piece, "failed");
        clearInvocations(shopifyGateway);

        as(t, () -> adjust.restore(piece, "repaired", null, t.user()));

        assertThat(claim(t, "damaged_restore_increment", piece)).containsEntry("status", "skipped")
            .containsEntry("skip_reason", "departure_cancelled");
        assertThat(claim(t, "damage_move", piece)).containsEntry("status", "cancelled");
        Thread.sleep(300);
        verifyNoWrites();
    }

    @Test
    void b6_ambiguousDamageMove_backToGood_skipped_alert() throws Exception {
        T t = tenant("b6", true);
        String piece = piece(t, "available", true);
        doThrow(new ShopifyAmbiguousException("Damage move: no confirmed response")).when(shopifyGateway)
            .moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any(), any());
        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));
        awaitStatus(t, "damage_move", piece, "failed_ambiguous");

        as(t, () -> adjust.restore(piece, "repaired", null, t.user()));

        Map<String, Object> c = claim(t, "damaged_restore_increment", piece);
        assertThat(c).containsEntry("status", "skipped").containsEntry("skip_reason", "departure_ambiguous");
        assertThat(alertKeys(t)).contains("void_hold_sync_failed:damaged_restore_increment:" + c.get("trigger_id"));
        verify(shopifyGateway, never()).moveDamagedToAvailable(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void b7_concurrentDoubleBackToGood_oneCall() throws Exception {
        T t = tenant("b7", true);
        String piece = piece(t, "available", true);
        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));
        awaitStatus(t, "damage_move", piece, "applied");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                TenantContext.runAs(t.tenant(), () -> adjust.restore(piece, "repaired", null, t.user()));
                return null;
            }));
        }
        go.countDown();
        int failures = 0;
        for (Future<?> f : fs) {
            try { f.get(10, TimeUnit.SECONDS); } catch (ExecutionException e) { failures++; }
        }
        pool.shutdown();

        assertThat(failures).isEqualTo(1);
        awaitStatus(t, "damage_restore", piece, "applied");
        assertThat(count(t, "trigger_type = 'damage_restore'")).isEqualTo(1);
        verify(shopifyGateway, times(1)).moveDamagedToAvailable(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void b8_destroyedAndVoided_neverBackToGood() {
        T t = tenant("b8", true);
        for (String st : List.of("destroyed", "voided")) {
            String piece = piece(t, st, true);
            assertThatThrownBy(() -> as(t, () -> adjust.restore(piece, "repaired", null, t.user())))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            assertThatThrownBy(() -> as(t, () -> adjust.adjustPiece(piece, "available", "other", "x", t.user())))
                .as("no " + st + " → available edge").isInstanceOf(RuntimeException.class);
            assertThat(status(piece)).isEqualTo(st);
        }
        assertThat(count(t, "trigger_type <> 'initial_seed'")).isZero();
    }

    @Test
    void b9_backToGood_eventConditionAndCustodyPhrase() throws Exception {
        T t = tenant("b9", true);
        String piece = piece(t, "available", true);
        as(t, () -> adjust.adjustPiece(piece, "damaged", "damaged_in_storage", null, t.user()));
        awaitStatus(t, "damage_move", piece, "applied");
        assertThat(jdbc.queryForObject("SELECT condition FROM pieces WHERE id = ?", String.class, piece)).isEqualTo("damaged");
        assertThatThrownBy(() -> as(t, () -> adjust.restore(piece, "other", " ", t.user())))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        as(t, () -> adjust.restore(piece, "other", "supplier fixed the zip", t.user()));

        Map<String, Object> ev = jdbc.queryForMap("SELECT event_type, from_status::text AS f, to_status::text AS t, " +
            "metadata->>'reason' AS reason, metadata->>'note' AS note, metadata->>'restore_event_id' AS rid " +
            "FROM piece_events WHERE piece_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1", piece);
        assertThat(ev).containsEntry("event_type", "adjusted").containsEntry("f", "damaged").containsEntry("t", "available")
            .containsEntry("reason", "other").containsEntry("note", "supplier fixed the zip");
        assertThat(claim(t, "damage_restore", piece).get("trigger_id")).isEqualTo(piece + ":" + ev.get("rid"));
        assertThat(jdbc.queryForObject("SELECT condition FROM pieces WHERE id = ?", String.class, piece)).isEqualTo("good");
        assertThat(LookupService.phraseKey("adjusted", "damaged", "available")).isEqualTo("back_to_good");
        assertThat(LookupService.phraseKey("adjusted", "lost", "available")).isEqualTo("found_it");
    }

    // ── D10: the repair ───────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void rp1_repairDryRun_zeroWrites_applyOnce_rerunNothing() throws Exception {
        T t = tenant("rp1", true);
        String voided = pieceWithId(t, LookupAdjustRepairService.VOID_PIECE, "voided");
        String damaged = pieceWithId(t, LookupAdjustRepairService.DAMAGE_PIECE, "damaged");
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, skip_reason, error, created_at) VALUES (?, ?, ?, ?, -1, 'void_correction', ?, 'skipped', " +
            "'arrival_not_counted', 'Receiving increment never applied for this piece''s session — on_hand already correct', " +
            "piece_sync_cutoff() - interval '1 hour')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), voided);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, error, created_at) VALUES (?, ?, ?, ?, 0, 'damage_move', ?, 'failed', " +
            "'referenceDocumentUri (Expected value to not be null)', piece_sync_cutoff() - interval '1 hour')",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), damaged);
        when(shopifyGateway.resolveInventoryItemId(any(), any(), any())).thenReturn(t.item());
        when(shopifyGateway.fetchStateQuantities(any(), any(), any(), any(), any()))
            .thenReturn(Map.of(t.item(), Map.of("available", 7, "damaged", 0, "on_hand", 7)));

        Map<String, Object> dry = repair.run(t.tenant(), false);

        assertThat(dry).containsEntry("mode", "dry_run");
        List<Map<String, Object>> ps = (List<Map<String, Object>>) dry.get("pieces");
        assertThat(ps).hasSize(2);
        assertThat(ps.get(0)).containsEntry("countedAtMain", true).containsEntry("shopifyBefore",
            Map.of("available", 7, "damaged", 0, "on_hand", 7));
        assertThat((String) ps.get(0).get("action")).startsWith("would claim void_correction");
        assertThat((String) ps.get(1).get("action")).startsWith("would claim damage_move");
        verifyNoWrites();
        assertThat(count(t, "trigger_id LIKE '%repair-2026-10-10'")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'damage_move' AND trigger_id = ?", String.class, t.tenant(), damaged)).isEqualTo("failed");

        repair.run(t.tenant(), true);
        repair.run(t.tenant(), true);

        verify(shopifyGateway, times(1)).pushVoidCorrection(any(), any(), eq(t.item()), eq(t.traced()), eq(-1),
            eq("traced://piece/" + voided), anyString());
        verify(shopifyGateway, times(1)).moveAvailableToDamaged(any(), any(), eq(t.item()), eq(t.traced()), eq(1),
            eq("damaged"), eq("traced://piece/" + damaged), anyString());
        assertThat(jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'damage_move' AND trigger_id = ?", String.class, t.tenant(), damaged)).isEqualTo("superseded_by_repair");
        assertThat(jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'void_correction' AND trigger_id = ?", String.class, t.tenant(), voided)).isEqualTo("skipped");
    }

    // ── controls ──────────────────────────────────────────────────────────────────

    @Test
    void c1_sameTenant_receivingPlusOne_andTransferOutPush_unchanged() throws Exception {
        T t = tenant("c1", true);
        String lost = piece(t, "available", true);
        as(t, () -> adjust.adjustPiece(lost, "lost", "theft_suspected", null, t.user()));
        awaitStatus(t, "piece_write_off", lost, "applied");

        UUID session = UUID.randomUUID();
        inventory.onReceivingSessionClose(t.tenant(), session, t.location(), Map.of(t.variant(), 2)).get(10, TimeUnit.SECONDS);
        verify(shopifyGateway).adjustInventoryQuantities(eq(t.shop()), eq("tok"), eq(t.item()), eq(t.traced()), eq(2),
            eq("received"), anyString());

        String moving = piece(t, "available", true);
        UUID move = relocate(t, moving);
        awaitTransfer(move, "pushed");
        verify(shopifyGateway, times(1)).pushTransferOut(any(), any(), anyList(), eq(t.traced()),
            eq("traced://transfer/" + move), anyString());
    }

    @Test
    void x1_appUser_tenantBNeverClaimsOrSweepsTenantA_ownClaimGoesThrough() throws Exception {
        T a = tenant("x1a", true), b = tenant("x1b", true);
        String pa = piece(a, "available", true);

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        ShopifyInventoryService appInventory = new ShopifyInventoryService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            mapper, new StoreRepository(appJdbc, appTxm));
        TransactionTemplate appTx = new TransactionTemplate(appTxm);

        assertThatThrownBy(() -> TenantContext.runAs(b.tenant(), () -> appTx.execute(st ->
            appInventory.claimPieceDeparture(b.tenant(), "piece_write_off", pa, pa + ":x"))))
            .as("tenant B cannot even see A's piece").isInstanceOf(IllegalStateException.class);

        Long id = TenantContext.runAs(a.tenant(), () -> appTx.execute(st ->
            appInventory.claimPieceDeparture(a.tenant(), "piece_write_off", pa, pa + ":own")));
        assertThat(id).as("same-tenant positive control").isNotNull();
        // Its first send failed definitively — the sweep owes it a re-send.
        jdbc.update("UPDATE shopify_inventory_adjustments SET status = 'failed', attempt_count = 1 WHERE id = ?", id);

        assertThat(TenantContext.runAs(b.tenant(), () -> appInventory.sweepPieceClaims(b.tenant()))).isZero();
        assertThat(claim(a, "piece_write_off", pa)).containsEntry("status", "failed");
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());

        assertThat(TenantContext.runAs(a.tenant(), () -> appInventory.sweepPieceClaims(a.tenant()))).isEqualTo(1);
        assertThat(claim(a, "piece_write_off", pa)).containsEntry("status", "applied");
        verify(shopifyGateway, times(1)).pushPieceWriteOff(any(), any(), eq(a.item()), eq(a.traced()), eq(-1), any(), any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private void verifyNoWrites() {
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushHoldEnter(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).pushPieceWriteOff(any(), any(), any(), any(), anyInt(), any(), any());
        verify(shopifyGateway, never()).moveAvailableToDamaged(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).moveDamagedToAvailable(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).pushPieceIncrement(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private T tenant(String name, boolean seeded) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID(), away = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        String variantGid = "gid://shopify/ProductVariant/" + (9_000_000 + seq.incrementAndGet());
        String item = "gid://shopify/InventoryItem/" + name + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", location, tenant, traced);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Showroom', 'warehouse', false, false)", away, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", variant, tenant, product, variantGid, "SKU-" + name, item);
        T t = new T(tenant, store, shop, location, traced, user, variant, variantGid, item, away);
        if (seeded) insertSeed(t, "now() - interval '1 hour'");
        return t;
    }

    private void insertSeed(T t, String atSql) {
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, 1, 'initial_seed', ?, 'applied', " + atSql + ")",
            t.tenant(), UUID.randomUUID(), t.variant(), t.location(), "fixture-seed:" + UUID.randomUUID());
    }

    private String piece(T t, String status, boolean receivedBeforeSeed) {
        return pieceWithId(t, "01C2" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase(),
            status, receivedBeforeSeed);
    }

    private String pieceWithId(T t, String id, String status) { return pieceWithId(t, id, status, true); }

    private String pieceWithId(T t, String id, String status, boolean receivedBeforeSeed) {
        int k = seq.incrementAndGet();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, " + (receivedBeforeSeed ? "now() - interval '1 day'" : "now()") + ")",
            id, t.tenant(), t.variant(), status, "C2-" + id + "-" + k, String.format("C%07d", k), t.location());
        return id;
    }

    /**
     * A piece received before the seed, sold (its allocation stays 'packed' — as in production), returned
     * after the seed and marked damaged at return inspection through ReturnService.markDamaged.
     */
    private String returnedDamagedPiece(T t, String orderRaw) {
        String piece = piece(t, "return_pending_inspection", true);
        UUID order = UUID.randomUUID(), item = UUID.randomUUID();
        jdbc.update("INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'ready_to_pick'::order_status, ?::jsonb)",
            order, t.tenant(), t.store(), "gid://shopify/Order/" + seq.incrementAndGet(), "#C2-" + seq.get(),
            orderRaw == null ? "{}" : orderRaw);
        jdbc.update("INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?, 1)",
            item, t.tenant(), order, t.variant());
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, 'packed'::allocation_status)",
            t.tenant(), item, piece);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", order, piece);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, order_id, occurred_at) " +
            "VALUES (?, ?, 'courier_update', 'with_courier', 'delivered', ?, now() - interval '30 minutes')", t.tenant(), piece, order);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, from_status, to_status, order_id, occurred_at) " +
            "VALUES (?, ?, 'return_received', 'delivered', 'return_pending_inspection', ?, now() - interval '10 minutes')",
            t.tenant(), piece, order);
        as(t, () -> returns.markDamaged(piece, "damaged_in_transit", t.user()));
        return piece;
    }

    /** A permanent move (relocate_out) of the piece from main to the away location, closed now. */
    private UUID relocate(T t, String piece) {
        UUID move = TenantContext.runAs(t.tenant(), () ->
            transfers.createTransfer("other", t.away(), null, "move", t.user(), "relocate_out"));
        String barcode = jdbc.queryForObject("SELECT barcode FROM pieces WHERE id = ?", String.class, piece);
        TransferService.ScanOutResult r = TenantContext.runAs(t.tenant(), () -> transfers.scanOut(move, barcode, t.user()));
        assertThat(r.success()).isTrue();
        as(t, () -> transfers.closeOneWay(move, t.user()));
        assertThat(jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, piece)).isEqualTo(t.away());
        // A permanent move leaves the piece 'transferred_out' — Lookup can't adjust it (no transferred_out →
        // lost edge). No Lookup path makes a relocated piece 'available' at the destination today; this
        // stands in for one (e.g. a hand correction), so D4's away-from-main rule is exercised end to end.
        jdbc.update("UPDATE pieces SET status = 'available'::piece_status WHERE id = ?", piece);
        return move;
    }

    private void awaitTransfer(UUID transfer, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = ?",
                Integer.class, transfer, status) == 0) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("transfer sync never reached " + status);
            Thread.sleep(25);
        }
    }

    private void as(T t, Runnable r) {
        TenantContext.runAs(t.tenant(), () -> { r.run(); return null; });
    }

    private String status(String piece) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece);
    }

    private int count(T t, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND " + where,
            Integer.class, t.tenant());
    }

    private Map<String, Object> claim(T t, String triggerType, String piece) {
        return jdbc.queryForMap("SELECT trigger_id, status, skip_reason, delta, location_id " +
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

    private void awaitClaim(T t, String triggerType, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(t, "trigger_type = '" + triggerType + "' AND status = '" + status + "'") == 0) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError(triggerType + " never reached " + status);
            Thread.sleep(25);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> alerts(T t, String type) {
        Map<String, Object> r = TenantContext.runAs(t.tenant(), () -> exceptions.listExceptions(type, null, 0, 100));
        return (List<Map<String, Object>>) r.get("items");
    }

    private List<String> alertKeys(T t) {
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> i : alerts(t, "void_hold_sync_failed")) keys.add((String) i.get("subject_key"));
        return keys;
    }

    private List<String> alertTypes(T t) {
        List<String> types = new ArrayList<>();
        for (Map<String, Object> i : alerts(t, null)) types.add((String) i.get("type"));
        return types;
    }
}
