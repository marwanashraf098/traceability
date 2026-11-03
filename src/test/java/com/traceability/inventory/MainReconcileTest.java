package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify Main-location reconciler, R1 report-only (V201). Every run goes through the real service on an app_user
 * connection (RLS enforced); fixtures are written as postgres. Shopify is a mock whose Main levels the test sets.
 *
 *   t1  target = custody at Main − units Shopify already fulfilled FROM MAIN for orders Traced still holds;
 *       in sync → no diff row; Shopify higher → an unconfirmed diff with the delta and all four quantities
 *   t2  a cancelled / errored fulfillment never counts as fulfilled from Main; it skips the variant only while it is
 *       younger than the cancelled-fulfillment window (Shopify may re-fulfill) — an older one is simply ignored
 *   t3  cancel + refund restock AT MAIN while Traced still holds the pieces → in sync; a restock at ANOTHER location
 *       doesn't count (Shopify Main stays down)
 *   t4  each variant skip reason (claim / ambiguous claim / stock take / transfer / arrival revisit / unreadable,
 *       pending fulfillment / Part 1 in flight / not linked / not activated / unread)
 *   t5  store-level skip: Main inactive in Shopify → run skipped, Shopify never read; at most one skipped run row per
 *       store per hour (later runs write nothing and take the queue off)
 *   t6  away pieces: out on a 'leave' transfer counts at Main; a pushed 'remove' transfer doesn't
 *   p1  Part 1: Traced shipped a line Shopify fulfilled from another location → ONE report-only claim, once only
 *       across runs; the runner adds the owed unit back (in sync, never double-counted)
 *   p2  Part 1: no Traced pieces → not_shipped_by_traced; pieces still in custody → waiting, claimed once they leave
 *   p3  Part 1: cancelled order with nothing shipped → cancelled_before_shipping; cancelled fulfillment → no claim
 *   r1  two-run rule: confirmed only on a second run ≥ N min after the first with the same delta and no Shopify event
 *       in between; a webhook in between keeps it unconfirmed and restarts the window
 *   q1  webhooks queue their variants (last_event_at stamped); Traced claims queue theirs (trigger); the queued run
 *       takes them off the queue
 *   q2  a piece leaving Traced custody (→ with_courier / delivered, written as app_user) queues its variant; the
 *       queued run claims Part 1 at once; a receive / other move queues nothing
 *   e1  retention: runs + diffs older than retention-days are deleted (own tenant only, as app_user); recent runs,
 *       Part 1 claims and another tenant's old runs stay
 *   x1  cross-tenant as app_user: a run for tenant B sees nothing of A (positive control: A sees its own); with no
 *       tenant set app_user reads zero rows of every new table
 *   w1  report-only: the only Shopify calls are reads, every read targets the Main location; writes need report-only
 *       off, a write shop and the Part 1 cutoff (R2 — the write tests are MainReconcileWriteTest)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainReconcileTest {

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
    static final Set<String> READS = Set.of("fetchMainLevels", "fetchMainLevelsPage", "resolveInventoryItemIds");

    @Autowired JdbcTemplate jdbc;               // postgres — fixtures only
    @Autowired ObjectMapper mapper;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    MainReconcileService service;
    MainReconcileQueue queue;

    /** item gid → Shopify's Main level (what the mock answers). */
    final Map<String, ShopifyGateway.MainLevel> shopifyMain = new HashMap<>();
    final AtomicInteger seq = new AtomicInteger();

    record T(UUID tenant, UUID store, UUID main, String mainGid, UUID user) {
        String mainNum() { return mainGid.replaceAll("^.*/", ""); }
    }
    record V(UUID id, long shopifyId, String item) {
        String gid() { return "gid://shopify/ProductVariant/" + shopifyId; }
    }

    @BeforeAll
    void appUser() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTxm = new DataSourceTransactionManager(appDs);
        service = newService();
        queue = new MainReconcileQueue(appJdbc, appTxm);
    }

    MainReconcileService newService() {
        MainReconcileService s = new MainReconcileService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            new StoreRepository(appJdbc, appTxm));
        s.setReportOnly(true);
        s.setEnabled(true);
        s.setShops("");
        s.setConfirmAfterMinutes(10);
        s.setPart1WindowDays(30);
        s.setDebounceSeconds(0);
        s.setQueueBatch(500);
        return s;
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(shopifyGateway, tokenProvider);
        shopifyMain.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopifyGateway.fetchMainLevels(any(), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<ShopifyGateway.MainLevel> out = new ArrayList<>();
            for (String id : ids) {
                ShopifyGateway.MainLevel l = shopifyMain.get(id);
                if (l != null) out.add(l);
            }
            return out;
        });
        when(shopifyGateway.fetchMainLevelsPage(any(), any(), any(), any())).thenAnswer(inv ->
            new ShopifyGateway.MainLevelsPage(shopifyMain.values().stream().filter(ShopifyGateway.MainLevel::stocked).toList(), null));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        assertOnlyReadsAtMain();
    }

    // ── t1: target ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void t1_target_isCustodyMinusFulfilledFromMain_comparedOnHand() {
        T t = tenant("t1");
        V v = variant(t);
        for (int i = 0; i < 3; i++) piece(t, v, "available", t.main());
        UUID order = order(t, fulfillments(f("1", t.mainNum(), "success", line("11", v, 2))));
        UUID line = orderItem(t, order, v, 2);
        allocate(t, line, piece(t, v, "reserved", t.main()), "active");
        allocate(t, line, piece(t, v, "reserved", t.main()), "active");
        // Shopify: fulfilled 2 from Main → on_hand 3; committed 0. Traced: 5 at Main, 2 of them already fulfilled.
        level(v, 3, 3, 0, 0);

        UUID run = run(t, v);
        assertThat(runRow(t, run)).containsEntry("status", "completed").containsEntry("variants_checked", 1)
            .containsEntry("variants_in_sync", 1).containsEntry("differences", 0);
        assertThat(diffs(t, run)).isEmpty();

        // Shopify on_hand 5 (the fulfillment never reached Main's count) → Traced's 3 is the truth: delta −2.
        level(v, 5, 4, 1, 0);
        UUID run2 = run(t, v);
        Map<String, Object> d = onlyDiff(t, run2);
        assertThat(d).containsEntry("status", "unconfirmed").containsEntry("custody_at_main", 5)
            .containsEntry("fulfilled_from_main", 2).containsEntry("target", 3).containsEntry("shopify_on_hand", 5)
            .containsEntry("shopify_available", 4).containsEntry("shopify_committed", 1).containsEntry("delta", -2);
    }

    @Test
    void t2_cancelledFulfillment_neverCountsAsFulfilledFromMain_skipsOnlyWhileRecent() {
        T t = tenant("t2");
        V old = variant(t), recent = variant(t), errored = variant(t);
        // Cancelled yesterday (older than the 1-hour window): ignored — not fulfilled from Main, no skip.
        UUID o1 = order(t, fulfillments(f("2", t.mainNum(), "cancelled", line("21", old, 1))));
        allocate(t, orderItem(t, o1, old, 1), piece(t, old, "reserved", t.main()), "active");
        // Cancelled 10 minutes ago: Shopify may re-fulfill → skipped.
        UUID o2 = order(t, fulfillments(fAt("22", t.mainNum(), "cancelled", minutesAgo(10), line("221", recent, 1))));
        allocate(t, orderItem(t, o2, recent, 1), piece(t, recent, "reserved", t.main()), "active");
        // Errored 2 hours ago: ignored like an old cancel.
        UUID o3 = order(t, fulfillments(fAt("23", t.mainNum(), "error", minutesAgo(120), line("231", errored, 1))));
        allocate(t, orderItem(t, o3, errored, 1), piece(t, errored, "reserved", t.main()), "active");
        level(old, 1, 1, 0, 0);
        level(recent, 1, 1, 0, 0);
        level(errored, 1, 1, 0, 0);

        UUID run = run(t, old, recent, errored);
        assertThat(runRow(t, run)).containsEntry("variants_checked", 3).containsEntry("variants_in_sync", 2)
            .containsEntry("variants_skipped", 1);
        assertThat(onlyDiff(t, run)).containsEntry("variant_id", recent.id()).containsEntry("status", "skipped")
            .containsEntry("skip_reason", MainReconcileRules.FULFILLMENT_CANCELLED)
            .containsEntry("fulfilled_from_main", 0).containsEntry("target", 1).containsEntry("delta", 0);

        // Had Shopify counted the old cancelled fulfillment (on_hand 0), Traced's held piece is still the truth: +1.
        level(old, 0, 0, 0, 0);
        assertThat(onlyDiff(t, run(t, old))).containsEntry("fulfilled_from_main", 0).containsEntry("target", 1)
            .containsEntry("delta", 1).containsEntry("status", "unconfirmed");
    }

    @Test
    void t3_cancelAndRestockAtMain_inSync_restockElsewhereDoesNotCount() {
        T t = tenant("t3");
        V atMain = variant(t), elsewhere = variant(t);
        // Fulfilled from Main, then the order cancelled with a refund restock — at Main for one, at another location
        // for the other. Traced still holds both pieces (reserved).
        UUID o1 = order(t, "{\"cancelled_at\": \"2026-11-01T10:00:00Z\", " + fulfillmentsField(f("31", t.mainNum(), "success", line("311", atMain, 1))) +
            ", \"refunds\": [{\"id\": 1, \"refund_line_items\": [{\"quantity\": 1, \"restock_type\": \"return\", \"location_id\": " +
            t.mainNum() + ", \"line_item\": {\"variant_id\": " + atMain.shopifyId() + "}}]}]}");
        allocate(t, orderItem(t, o1, atMain, 1), piece(t, atMain, "reserved", t.main()), "active");
        UUID o2 = order(t, "{\"cancelled_at\": \"2026-11-01T10:00:00Z\", " + fulfillmentsField(f("32", t.mainNum(), "success", line("321", elsewhere, 1))) +
            ", \"refunds\": [{\"id\": 2, \"refund_line_items\": [{\"quantity\": 1, \"restock_type\": \"return\", \"location_id\": 999777, " +
            "\"line_item\": {\"variant_id\": " + elsewhere.shopifyId() + "}}]}]}");
        allocate(t, orderItem(t, o2, elsewhere, 1), piece(t, elsewhere, "reserved", t.main()), "active");
        level(atMain, 1, 1, 0, 0);      // −1 fulfilled, +1 restocked at Main
        level(elsewhere, 0, 0, 0, 0);   // −1 fulfilled at Main; the restock went to another location

        UUID run = run(t, atMain, elsewhere);
        assertThat(runRow(t, run)).containsEntry("variants_checked", 2).containsEntry("variants_in_sync", 2);
        assertThat(diffs(t, run)).isEmpty();
    }

    // ── t4 / t5: skips ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void t4_everyVariantSkipReason() {
        T t = tenant("t4");
        Map<String, V> by = new LinkedHashMap<>();
        for (String r : List.of("claim", "ambiguous", "stocktake", "transfer", "arrival", "unreadable", "pending",
                                "part1", "notlinked", "notactivated", "unread", "control")) {
            V v = r.equals("notlinked") ? variantNoItem(t) : variant(t);
            by.put(r, v);
            piece(t, v, "available", t.main());
            if (!r.equals("notactivated") && !r.equals("unread")) level(v, 1, 1, 0, 0);
        }
        claimRow(t, by.get("claim"), "hold_enter", "queued", null);
        claimRow(t, by.get("ambiguous"), "piece_sold", "failed_ambiguous", null);
        UUID session = jdbc.queryForObject("INSERT INTO stock_take_sessions (tenant_id, scope_type, opened_by, location_id) " +
            "VALUES (?, 'all', ?, ?) RETURNING id", UUID.class, t.tenant(), t.user(), t.main());
        jdbc.update("INSERT INTO stock_take_shopify_syncs (tenant_id, session_id, status, payload) VALUES (?, ?, 'failed_ambiguous', ?::jsonb)",
            t.tenant(), session, "{\"deltas\": {\"" + by.get("stocktake").id() + "\": 1}}");
        UUID transfer = jdbc.queryForObject("INSERT INTO transfers (tenant_id, transfer_type, destination_location_id, created_by, " +
            "status, transfer_mode, source_location_id) VALUES (?, 'showroom', ?, ?, 'sent', 'round_trip', ?) RETURNING id",
            UUID.class, t.tenant(), location(t, "Showroom"), t.user(), t.main());
        jdbc.update("INSERT INTO transfer_shopify_syncs (tenant_id, transfer_id, location_id, status, deltas) VALUES (?, ?, ?, 'pending', ?::jsonb)",
            t.tenant(), transfer, t.main(), "{\"" + by.get("transfer").id() + "\": 1}");
        // Arrival revisit: a departure skipped arrival_unconfirmed whose receipt's +1 is still pending.
        UUID receipt = jdbc.queryForObject("INSERT INTO receipts (tenant_id) VALUES (?) RETURNING id", UUID.class, t.tenant());
        String away = piece(t, by.get("arrival"), "on_hold", t.main());
        jdbc.update("UPDATE pieces SET receipt_id = ? WHERE id = ?", receipt, away);
        claimRow(t, by.get("arrival"), "receiving_session", "pending", receipt.toString());
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, trigger_type, " +
            "trigger_id, status, skip_reason) VALUES (?, ?, ?, ?, -1, 'hold_enter', ?, 'skipped', 'arrival_unconfirmed')",
            t.tenant(), UUID.randomUUID(), by.get("arrival").id(), t.main(), away + ":" + UUID.randomUUID());
        // Fulfillments Traced can't read / that aren't done, on orders holding the variant's pieces.
        UUID o1 = order(t, "{\"fulfillments\": [{\"status\": \"SUCCESS\"}]}");
        allocate(t, orderItem(t, o1, by.get("unreadable"), 1), piece(t, by.get("unreadable"), "reserved", t.main()), "active");
        UUID o2 = order(t, fulfillments(f("41", t.mainNum(), "pending", line("411", by.get("pending"), 1))));
        allocate(t, orderItem(t, o2, by.get("pending"), 1), piece(t, by.get("pending"), "reserved", t.main()), "active");
        UUID o3 = order(t, "{}");
        jdbc.update("INSERT INTO main_reconcile_part1_claims (tenant_id, order_id, variant_id, location_id, fulfillment_id, " +
            "line_item_id, fulfilled_location_id, fulfilled_quantity, quantity, status) VALUES (?, ?, ?, ?, 'f1', 'l1', '555', 1, 1, 'queued')",
            t.tenant(), o3, by.get("part1").id(), t.main());
        shopifyMain.put(by.get("notactivated").item(), new ShopifyGateway.MainLevel(by.get("notactivated").item(), false, 0, 0, 0, 0));
        // "unread": Shopify answers nothing for its item.

        UUID run = run(t, by.values().toArray(V[]::new));
        Map<UUID, String> reasons = diffs(t, run).stream().filter(d -> "skipped".equals(d.get("status")))
            .collect(Collectors.toMap(d -> (UUID) d.get("variant_id"), d -> (String) d.get("skip_reason")));
        assertThat(reasons).containsEntry(by.get("claim").id(), MainReconcileRules.CLAIM_IN_FLIGHT)
            .containsEntry(by.get("ambiguous").id(), MainReconcileRules.CLAIM_AMBIGUOUS)
            .containsEntry(by.get("stocktake").id(), MainReconcileRules.STOCK_TAKE_SYNC_IN_FLIGHT)
            .containsEntry(by.get("transfer").id(), MainReconcileRules.TRANSFER_SYNC_IN_FLIGHT)
            .containsEntry(by.get("arrival").id(), MainReconcileRules.ARRIVAL_REVISIT_PENDING)
            .containsEntry(by.get("unreadable").id(), MainReconcileRules.FULFILLMENT_UNREADABLE)
            .containsEntry(by.get("pending").id(), MainReconcileRules.FULFILLMENT_PENDING)
            .containsEntry(by.get("part1").id(), MainReconcileRules.PART1_IN_FLIGHT)
            .containsEntry(by.get("notlinked").id(), MainReconcileRules.NOT_LINKED)
            .containsEntry(by.get("notactivated").id(), MainReconcileRules.NOT_ACTIVATED_AT_MAIN)
            .containsEntry(by.get("unread").id(), MainReconcileRules.SHOPIFY_UNREAD)
            .doesNotContainKey(by.get("control").id())
            .hasSize(11);
        assertThat(runRow(t, run)).containsEntry("variants_checked", 12).containsEntry("variants_skipped", 11)
            .containsEntry("variants_in_sync", 1);
    }

    @Test
    void t5_mainInactiveInShopify_storeSkipped_shopifyNeverRead() {
        T t = tenant("t5");
        V v = variant(t);
        piece(t, v, "available", t.main());
        jdbc.update("UPDATE locations SET shopify_location_state = 'inactive' WHERE id = ?", t.main());

        UUID run = run(t, v);
        assertThat(runRow(t, run)).containsEntry("status", "skipped").containsEntry("skip_reason", MainReconcileRules.STORE_LOCATION_INACTIVE);

        // Within the hour: no second skipped row — for a queued run and a sweep alike — and the queue is taken off.
        jdbc.update("INSERT INTO main_reconcile_variant_state (tenant_id, variant_id, pending_since, pending_touched_at, pending_source) " +
            "VALUES (?, ?, now() - interval '5 minutes', now() - interval '5 minutes', 'test')", t.tenant(), v.id());
        assertThat(TenantContext.runAs(t.tenant(), () -> service.runQueued(t.tenant()))).isNull();
        assertThat(TenantContext.runAs(t.tenant(), () -> service.runSweep(t.tenant()))).isNull();
        assertThat(runCount(t)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_variant_state WHERE tenant_id = ? AND pending_since IS NOT NULL",
            Integer.class, t.tenant())).isZero();
        // An hour later: one more.
        jdbc.update("UPDATE main_reconcile_runs SET started_at = started_at - interval '61 minutes' WHERE tenant_id = ?", t.tenant());
        assertThat(runRow(t, run(t, v))).containsEntry("status", "skipped");
        assertThat(runCount(t)).isEqualTo(2);

        verify(shopifyGateway, never()).fetchMainLevels(any(), any(), any(), anyList());
        verify(shopifyGateway, never()).fetchMainLevelsPage(any(), any(), any(), any());
    }

    @Test
    void t6_awayPieces_leaveModeCounts_pushedRemoveDoesNot() {
        T t = tenant("t6");
        V leave = variant(t), removed = variant(t);
        UUID showroom = location(t, "Showroom");
        String a = piece(t, leave, "out_on_transfer", showroom);
        String b = piece(t, removed, "out_on_transfer", showroom);
        UUID t1 = transfer(t, showroom, "leave");
        UUID t2 = transfer(t, showroom, "remove");
        transferPiece(t, t1, a);
        transferPiece(t, t2, b);
        jdbc.update("INSERT INTO transfer_shopify_syncs (tenant_id, transfer_id, location_id, status, deltas, piece_ids) " +
            "VALUES (?, ?, ?, 'pushed', ?::jsonb, ?::jsonb)", t.tenant(), t2, t.main(), "{\"" + removed.id() + "\": 1}", "[\"" + b + "\"]");
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, trigger_type, " +
            "trigger_id, status) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, 'applied')", t.tenant(), UUID.randomUUID(), leave.id(),
            t.main(), UUID.randomUUID().toString());   // Shopify counts at Main through receiving
        level(leave, 1, 1, 0, 0);
        level(removed, 0, 0, 0, 0);

        UUID run = run(t, leave, removed);
        assertThat(runRow(t, run)).as(diffs(t, run).toString()).containsEntry("variants_in_sync", 2);
        level(removed, 1, 1, 0, 0);   // had Shopify NOT been lowered, Traced's 0 would be the truth
        assertThat(onlyDiff(t, run(t, removed))).containsEntry("away_counted", 0).containsEntry("delta", -1);
    }

    // ── Part 1 ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void p1_shippedByTraced_fulfilledElsewhere_oneClaimOnceOnly_runnerInSync() {
        T t = tenant("p1");
        V v = variant(t);
        UUID order = order(t, fulfillments(f("9001", "424242", "success", line("90011", v, 1))));
        String piece = piece(t, v, "with_courier", t.main());
        allocate(t, orderItem(t, order, v, 1), piece, "packed");
        leftCustody(t, piece, order);
        level(v, 1, 1, 0, 0);   // Shopify took the unit off the OTHER location; Main still holds it

        UUID run1 = run(t, v);
        UUID run2 = run(t, v);
        List<Map<String, Object>> claims = jdbc.queryForList(
            "SELECT status, quantity, fulfilled_quantity, fulfillment_id, line_item_id FROM main_reconcile_part1_claims WHERE tenant_id = ?", t.tenant());
        assertThat(claims).hasSize(1);
        assertThat(claims.get(0)).containsEntry("status", "report_only").containsEntry("quantity", 1)
            .containsEntry("fulfillment_id", "9001").containsEntry("line_item_id", "90011");
        assertThat(part1(t, run1)).containsEntry(MainReconcileRules.P1_WOULD_SUBTRACT, 1).containsEntry("new_claim_units", 1);
        assertThat(part1(t, run2)).containsEntry(MainReconcileRules.P1_ALREADY_CLAIMED, 1).containsEntry("new_claim_units", 0);
        // The runner owes the unit back to Main until Part 1 applies — no second subtract.
        assertThat(runRow(t, run2)).containsEntry("variants_in_sync", 1).containsEntry("differences", 0);
    }

    @Test
    void p2_notShippedByTraced_and_waitingUntilPiecesLeave() {
        T t = tenant("p2");
        V untraced = variant(t), waiting = variant(t);
        order(t, fulfillments(f("9101", "424242", "success", line("91011", untraced, 1))));
        UUID o2 = order(t, fulfillments(f("9102", "424242", "success", line("91021", waiting, 1))));
        String piece = piece(t, waiting, "packed", t.main());
        allocate(t, orderItem(t, o2, waiting, 1), piece, "packed");
        level(untraced, 0, 0, 0, 0);
        level(waiting, 1, 1, 0, 0);

        UUID run1 = run(t, untraced, waiting);
        assertThat(part1(t, run1)).containsEntry(MainReconcileRules.P1_NOT_SHIPPED_BY_TRACED, 1)
            .containsEntry(MainReconcileRules.P1_WAITING, 1).containsEntry("new_claim_units", 0);
        assertThat(claimCount(t)).isZero();

        // The piece leaves Traced → claimed now.
        jdbc.update("UPDATE pieces SET status = 'with_courier'::piece_status WHERE id = ?", piece);
        leftCustody(t, piece, o2);
        UUID run2 = run(t, waiting);
        assertThat(part1(t, run2)).containsEntry(MainReconcileRules.P1_WOULD_SUBTRACT, 1);
        assertThat(claimCount(t)).isEqualTo(1);
        assertThat(runRow(t, run2)).containsEntry("variants_in_sync", 1);
    }

    @Test
    void p3_cancelledBeforeShipping_and_cancelledFulfillment_noClaim() {
        T t = tenant("p3");
        V v = variant(t), w = variant(t);
        UUID o1 = order(t, "{\"cancelled_at\": \"2026-11-01T09:00:00Z\", " + fulfillmentsField(f("9201", "424242", "success", line("92011", v, 1))) + "}");
        allocate(t, orderItem(t, o1, v, 1), piece(t, v, "available", t.main()), "released");
        order(t, fulfillments(f("9202", "424242", "cancelled", line("92021", w, 1))));
        level(v, 1, 1, 0, 0);
        level(w, 0, 0, 0, 0);

        UUID run = run(t, v, w);
        assertThat(part1(t, run)).containsEntry(MainReconcileRules.P1_CANCELLED_BEFORE_SHIPPING, 1)
            .containsEntry(MainReconcileRules.P1_FULFILLMENT_CANCELLED, 1);
        assertThat(claimCount(t)).isZero();
    }

    // ── two-run rule ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void r1_twoRunRule_confirmsOnlyAfterTheWindowWithNoShopifyEventBetween() {
        T t = tenant("r1");
        V v = variant(t), w = variant(t);
        piece(t, v, "available", t.main());
        piece(t, w, "available", t.main());
        level(v, 3, 3, 0, 0);   // delta −2
        level(w, 4, 4, 0, 0);   // delta −3

        Map<String, Object> first = onlyDiffFor(t, run(t, v, w), v);
        assertThat(first).containsEntry("status", "unconfirmed");
        assertThat(jdbc.queryForObject("SELECT pending_source FROM main_reconcile_variant_state WHERE tenant_id = ? AND variant_id = ?",
            String.class, t.tenant(), v.id())).as("re-queued for its second look").isEqualTo("recheck");
        // Second run too soon → still unconfirmed.
        assertThat(onlyDiffFor(t, run(t, v, w), v)).containsEntry("status", "unconfirmed");

        // 11 minutes later; w had an inventory_levels/update in between.
        jdbc.update("UPDATE main_reconcile_variant_state SET delta_since = delta_since - interval '11 minutes' WHERE tenant_id = ?", t.tenant());
        jdbc.update("UPDATE main_reconcile_variant_state SET last_event_at = now() - interval '1 minute' WHERE tenant_id = ? AND variant_id = ?",
            t.tenant(), w.id());
        UUID run3 = run(t, v, w);
        assertThat(onlyDiffFor(t, run3, v)).containsEntry("status", "confirmed").containsEntry("delta", -2);
        assertThat(onlyDiffFor(t, run3, w)).containsEntry("status", "unconfirmed");
        assertThat(runRow(t, run3)).containsEntry("confirmed", 1).containsEntry("confirmed_units_down", 2)
            .containsEntry("confirmed_units_up", 0);

        // A different delta restarts the window.
        level(v, 4, 4, 0, 0);
        assertThat(onlyDiffFor(t, run(t, v), v)).containsEntry("status", "unconfirmed").containsEntry("delta", -3);
    }

    // ── queue ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void q1_webhooksAndClaimsQueueVariants_queuedRunTakesThemOff() throws Exception {
        T t = tenant("q1");
        V v = variant(t), w = variant(t), x = variant(t);
        piece(t, v, "available", t.main());
        level(v, 1, 1, 0, 0);
        level(w, 0, 0, 0, 0);
        level(x, 0, 0, 0, 0);

        TenantContext.runAs(t.tenant(), () -> {
            queue.onWebhook(t.tenant(), "orders/updated", mapper.readTree("{\"line_items\": [{\"variant_id\": " + v.shopifyId() + "}]}"));
            queue.onWebhook(t.tenant(), "inventory_levels/update",
                mapper.readTree("{\"inventory_item_id\": " + w.item().replaceAll("^.*/", "") + ", \"location_id\": " + t.mainNum() + "}"));
            queue.onWebhook(t.tenant(), "inventory_levels/update",   // another location → not queued
                mapper.readTree("{\"inventory_item_id\": " + x.item().replaceAll("^.*/", "") + ", \"location_id\": 999}"));
            return null;
        });
        claimRow(t, x, "receiving_session", "applied", UUID.randomUUID().toString());   // the V201 trigger queues it

        Map<UUID, Map<String, Object>> st = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM main_reconcile_variant_state WHERE tenant_id = ?", t.tenant())) {
            st.put((UUID) r.get("variant_id"), r);
        }
        assertThat(st.get(v.id())).containsEntry("pending_source", "webhook:orders/updated").containsEntry("last_event_source", "orders/updated");
        assertThat(st.get(w.id())).containsEntry("last_event_source", "inventory_levels/update");
        assertThat(st.get(x.id())).containsEntry("pending_source", "claim:receiving_session").containsEntry("last_event_at", null);

        Thread.sleep(20);
        UUID run = TenantContext.runAs(t.tenant(), () -> service.runQueued(t.tenant()));
        assertThat(runRow(t, run)).containsEntry("kind", "variant").containsEntry("variants_checked", 3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_variant_state WHERE tenant_id = ? AND pending_since IS NOT NULL",
            Integer.class, t.tenant())).isZero();
        assertThat(TenantContext.runAs(t.tenant(), () -> service.runQueued(t.tenant()))).as("nothing left").isNull();
    }

    @Test
    void q2_pieceLeavingCustody_queuesItsVariant_part1ClaimedOnTheQueuedRun() {
        T t = tenant("q2");
        V v = variant(t), stays = variant(t);
        UUID order = order(t, fulfillments(f("9301", "424242", "success", line("93011", v, 1))));
        String piece = piece(t, v, "packed", t.main());
        allocate(t, orderItem(t, order, v, 1), piece, "packed");
        String other = piece(t, stays, "available", t.main());
        level(v, 1, 1, 0, 0);
        level(stays, 1, 1, 0, 0);

        // Events that don't leave custody queue nothing (written as app_user, the real writer's role).
        appEvent(t, other, null, null, "available", "received");
        appEvent(t, piece, order, "reserved", "packed", "pack");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_variant_state WHERE tenant_id = ?", Integer.class, t.tenant()))
            .isZero();

        // The piece leaves → its variant is queued (debounced like the others).
        jdbc.update("UPDATE pieces SET status = 'with_courier'::piece_status WHERE id = ?", piece);
        appEvent(t, piece, order, "packed", "with_courier", "handed_to_courier");
        Map<String, Object> st = jdbc.queryForMap("SELECT * FROM main_reconcile_variant_state WHERE tenant_id = ? AND variant_id = ?",
            t.tenant(), v.id());
        assertThat(st).containsEntry("pending_source", "piece_left");
        assertThat(st.get("pending_since")).isNotNull();
        assertThat(st.get("pending_touched_at")).isNotNull();

        // Debounce passed (the test's debounce is 0 s; step the touch back instead of waiting on the clock).
        jdbc.update("UPDATE main_reconcile_variant_state SET pending_touched_at = pending_touched_at - interval '1 second' " +
            "WHERE tenant_id = ?", t.tenant());
        UUID run = TenantContext.runAs(t.tenant(), () -> service.runQueued(t.tenant()));
        assertThat(runRow(t, run)).containsEntry("variants_checked", 1).containsEntry("variants_in_sync", 1);
        assertThat(part1(t, run)).containsEntry(MainReconcileRules.P1_WOULD_SUBTRACT, 1);
        assertThat(claimCount(t)).isEqualTo(1);

        // with_courier → delivered is not a second departure.
        jdbc.update("UPDATE pieces SET status = 'delivered'::piece_status WHERE id = ?", piece);
        appEvent(t, piece, order, "with_courier", "delivered", "delivered");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_variant_state WHERE tenant_id = ? AND pending_since IS NOT NULL",
            Integer.class, t.tenant())).isZero();
    }

    @Test
    void e1_retention_deletesOldRunsAndDiffs_keepsRecentClaimsAndOtherTenants() {
        T t = tenant("e1"), other = tenant("e1o");
        V v = variant(t), ov = variant(other);
        piece(t, v, "available", t.main());
        piece(other, ov, "available", other.main());
        level(v, 0, 0, 0, 0);    // delta +1 → a diff row
        level(ov, 0, 0, 0, 0);
        UUID oldRun = run(t, v), recentRun = run(t, v), otherOld = run(other, ov);
        UUID order = order(t, "{}");
        jdbc.update("INSERT INTO main_reconcile_part1_claims (tenant_id, order_id, variant_id, location_id, fulfillment_id, " +
            "line_item_id, fulfilled_location_id, fulfilled_quantity, quantity, run_id) VALUES (?, ?, ?, ?, 'fe1', 'le1', '555', 1, 1, ?)",
            t.tenant(), order, v.id(), t.main(), oldRun);
        jdbc.update("UPDATE main_reconcile_runs SET started_at = now() - interval '15 days' WHERE id IN (?, ?)", oldRun, otherOld);
        assertThat(diffs(t, oldRun)).isNotEmpty();

        int deleted = TenantContext.runAs(t.tenant(), () -> service.purgeOld(t.tenant()));
        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_runs WHERE id = ?", Integer.class, oldRun)).isZero();
        assertThat(diffs(t, oldRun)).isEmpty();
        assertThat(runRow(t, recentRun)).containsEntry("status", "completed");
        assertThat(diffs(t, recentRun)).isNotEmpty();
        assertThat(claimCount(t)).as("Part 1 claims are never deleted").isEqualTo(1);
        assertThat(runRow(other, otherOld)).as("another tenant's old run is untouched").isNotNull();
        assertThat(diffs(other, otherOld)).isNotEmpty();
    }

    @Test
    void s1_sweep_checksEveryMainLevelAndEveryTracedVariant() {
        T t = tenant("s1");
        V shopifyOnly = variant(t), tracedOnly = variant(t);
        level(shopifyOnly, 5, 5, 0, 0);                 // Shopify-only stock at Main: Traced's 0 is the truth
        piece(t, tracedOnly, "available", t.main());    // not stocked at Main in Shopify → not activated
        UUID run = TenantContext.runAs(t.tenant(), () -> service.runSweep(t.tenant()));
        Map<UUID, Map<String, Object>> byVariant = diffs(t, run).stream().collect(Collectors.toMap(d -> (UUID) d.get("variant_id"), d -> d));
        assertThat(byVariant.get(shopifyOnly.id())).containsEntry("delta", -5).containsEntry("status", "unconfirmed");
        assertThat(byVariant.get(tracedOnly.id())).containsEntry("skip_reason", MainReconcileRules.NOT_ACTIVATED_AT_MAIN);
        assertThat(runRow(t, run)).containsEntry("kind", "sweep");
    }

    // ── isolation ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void x1_crossTenant_appUser_seesNothingOfAnotherTenant_positiveControl() {
        T a = tenant("xa"), b = tenant("xb");
        V va = variant(a);
        piece(a, va, "available", a.main());
        level(va, 0, 0, 0, 0);

        UUID runB = TenantContext.runAs(b.tenant(), () -> service.runVariants(b.tenant(), List.of(va.id())));
        assertThat(runRow(b, runB)).containsEntry("variants_checked", 0);
        UUID runA = TenantContext.runAs(a.tenant(), () -> service.runVariants(a.tenant(), List.of(va.id())));
        assertThat(runRow(a, runA)).as("positive control").containsEntry("variants_checked", 1).containsEntry("differences", 1);

        // app_user, no tenant set: zero rows from every new table although rows exist.
        TransactionTemplate tx = new TransactionTemplate(appTxm);
        for (String table : List.of("main_reconcile_runs", "main_reconcile_diffs", "main_reconcile_variant_state", "main_reconcile_part1_claims")) {
            assertThat((Integer) tx.execute(s -> appJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)))
                .as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_diffs", Integer.class)).isPositive();
        // Tenant B in context: A's rows stay invisible.
        assertThat((Integer) TenantContext.runAs(b.tenant(), () -> tx.execute(s -> appJdbc.queryForObject(
            "SELECT COUNT(*) FROM main_reconcile_diffs WHERE tenant_id = ?", Integer.class, a.tenant())))).isZero();
    }

    // ── report-only ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void w1_reportOnlyByDefault_writesNeedReportOnlyOff_aWriteShop_andTheCutoff() {
        // R2 (V203): report-only=false is allowed now; a store writes only when also listed (or 'all') with a cutoff.
        MainReconcileService s = new MainReconcileService(appJdbc, appTxm, shopifyGateway, tokenProvider, new StoreRepository(appJdbc, appTxm));
        s.setReportOnly(true); s.setWriteShops("all"); s.setPart1Since("2026-11-04T00:00:00Z");
        assertThat(s.writeEnabled("x.myshopify.com")).isFalse();
        s.setReportOnly(false);
        assertThat(s.writeEnabled("x.myshopify.com")).isTrue();
        s.setWriteShops("");
        assertThat(s.writeEnabled("x.myshopify.com")).isFalse();
        s.setWriteShops("all"); s.setPart1Since("");
        assertThat(s.writeEnabled("x.myshopify.com")).isFalse();
        // This class's service stays report-only: every run here is mode 'report_only' (assertOnlyReadsAtMain after each).
        assertThat(service.writeEnabled("x.myshopify.com")).isFalse();
    }

    /** After every test: Shopify saw reads only, and every location-scoped read named the tenant's Main location. */
    private void assertOnlyReadsAtMain() {
        Set<String> mainGids = new HashSet<>(jdbc.queryForList(
            "SELECT shopify_location_id FROM locations WHERE is_fulfillment AND shopify_location_id IS NOT NULL", String.class));
        for (Invocation inv : Mockito.mockingDetails(shopifyGateway).getInvocations()) {
            String m = inv.getMethod().getName();
            assertThat(READS).as("Shopify call %s", m).contains(m);
            if (m.startsWith("fetchMainLevels")) assertThat(mainGids).contains((String) inv.getArgument(2));
        }
    }

    // ── fixtures (postgres) ───────────────────────────────────────────────────────────────────────────────────

    private UUID run(T t, V... vs) {
        List<UUID> ids = Arrays.stream(vs).map(V::id).toList();
        UUID id = TenantContext.runAs(t.tenant(), () -> service.runVariants(t.tenant(), ids));
        assertThat(id).isNotNull();
        return id;
    }

    private Map<String, Object> runRow(T t, UUID run) {
        return jdbc.queryForMap("SELECT * FROM main_reconcile_runs WHERE id = ? AND tenant_id = ?", run, t.tenant());
    }

    private List<Map<String, Object>> diffs(T t, UUID run) {
        return jdbc.queryForList("SELECT * FROM main_reconcile_diffs WHERE run_id = ? AND tenant_id = ?", run, t.tenant());
    }

    private Map<String, Object> onlyDiff(T t, UUID run) {
        List<Map<String, Object>> d = diffs(t, run);
        assertThat(d).hasSize(1);
        return d.get(0);
    }

    private Map<String, Object> onlyDiffFor(T t, UUID run, V v) {
        List<Map<String, Object>> d = diffs(t, run).stream().filter(r -> v.id().equals(r.get("variant_id"))).toList();
        assertThat(d).hasSize(1);
        return d.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> part1(T t, UUID run) {
        try {
            return mapper.readValue(jdbc.queryForObject("SELECT part1_summary::text FROM main_reconcile_runs WHERE id = ?",
                String.class, run), Map.class);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private int claimCount(T t) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_part1_claims WHERE tenant_id = ?", Integer.class, t.tenant());
    }

    private int runCount(T t) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_runs WHERE tenant_id = ?", Integer.class, t.tenant());
    }

    /** A piece event written as app_user under the tenant (RLS on) — the role every real writer uses. */
    private void appEvent(T t, String piece, UUID order, String from, String to, String type) {
        TransactionTemplate tx = new TransactionTemplate(appTxm);
        TenantContext.runAs(t.tenant(), () -> tx.execute(s -> appJdbc.update(
            "INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, actor_user_id) " +
            "VALUES (?, ?, ?, ?, ?::piece_status, ?::piece_status, ?)", t.tenant(), piece, type, order, from, to, t.user())));
    }

    private static String minutesAgo(int m) {
        return java.time.Instant.now().minusSeconds(m * 60L).toString();
    }

    private void level(V v, int onHand, int available, int committed, int damaged) {
        shopifyMain.put(v.item(), new ShopifyGateway.MainLevel(v.item(), true, onHand, available, committed, damaged));
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        long mainNum = 80_000_000L + seq.incrementAndGet();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String mainGid = "gid://shopify/Location/" + mainNum;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", tenant, "Tenant " + name);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", location, tenant, mainGid);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            UUID.randomUUID(), tenant, store, "gid://shopify/Product/" + shop);
        return new T(tenant, store, location, mainGid, user);
    }

    private UUID location(T t, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES (?, ?, ?, 'warehouse', false, false)",
            id, t.tenant(), name + seq.incrementAndGet());
        return id;
    }

    private V variant(T t) {
        long sid = 7_100_000_000L + seq.incrementAndGet();
        String item = "gid://shopify/InventoryItem/" + (9_100_000_000L + sid);
        return insertVariant(t, sid, item);
    }

    private V variantNoItem(T t) {
        return insertVariant(t, 7_100_000_000L + seq.incrementAndGet(), null);
    }

    private V insertVariant(T t, long sid, String item) {
        UUID id = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ? LIMIT 1", UUID.class, t.tenant());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", id, t.tenant(), product, "gid://shopify/ProductVariant/" + sid, "SKU-" + sid, item);
        return new V(id, sid, item);
    }

    private String piece(T t, V v, String status, UUID location) {
        int k = seq.incrementAndGet();
        String id = String.format("01MAINRECON%015d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, now() - interval '1 day')",
            id, t.tenant(), v.id(), status, "MR-" + k, String.format("M%07d", k), location);
        return id;
    }

    private UUID order(T t, String raw) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), ?::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Order/MR" + k, "#MR" + k, raw);
    }

    private UUID orderItem(T t, UUID order, V v, int qty) {
        return jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, t.tenant(), order, v.id(), qty);
    }

    private void allocate(T t, UUID orderItem, String piece, String status) {
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
            t.tenant(), orderItem, piece, status);
        if (!"released".equals(status)) {
            UUID order = jdbc.queryForObject("SELECT order_id FROM order_items WHERE id = ?", UUID.class, orderItem);
            jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", order, piece);
        }
    }

    private void leftCustody(T t, String piece, UUID order) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, actor_user_id) " +
            "VALUES (?, ?, 'handed_to_courier', ?, 'packed', 'with_courier', ?)", t.tenant(), piece, order, t.user());
    }

    private UUID transfer(T t, UUID destination, String mode) {
        return jdbc.queryForObject("INSERT INTO transfers (tenant_id, transfer_type, destination_location_id, created_by, status, " +
            "transfer_mode, source_location_id, shopify_sync_mode, sent_at) VALUES (?, 'showroom', ?, ?, 'sent', 'round_trip', ?, ?, now()) RETURNING id",
            UUID.class, t.tenant(), destination, t.user(), t.main(), mode);
    }

    private void transferPiece(T t, UUID transfer, String piece) {
        UUID variant = jdbc.queryForObject("SELECT variant_id FROM pieces WHERE id = ?", UUID.class, piece);
        UUID line = jdbc.queryForObject("INSERT INTO transfer_lines (tenant_id, transfer_id, variant_id) VALUES (?, ?, ?) RETURNING id",
            UUID.class, t.tenant(), transfer, variant);
        jdbc.update("INSERT INTO transfer_pieces (tenant_id, transfer_id, line_id, piece_id, from_location_id) VALUES (?, ?, ?, ?, ?)",
            t.tenant(), transfer, line, piece, t.main());
    }

    private void claimRow(T t, V v, String trigger, String status, String triggerId) {
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, trigger_type, " +
            "trigger_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", t.tenant(), UUID.randomUUID(), v.id(), t.main(),
            trigger.equals("receiving_session") ? 1 : -1, trigger,
            triggerId != null ? triggerId : "01X" + seq.incrementAndGet() + ":" + UUID.randomUUID(), status);
    }

    // ── fulfillment JSON ──────────────────────────────────────────────────────────────────────────────────────

    private static String line(String id, V v, int qty) {
        return "{\"id\": " + id + ", \"variant_id\": " + v.shopifyId() + ", \"quantity\": " + qty + "}";
    }

    private static String f(String id, String locationNum, String status, String... lines) {
        return "{\"id\": " + id + ", \"location_id\": " + locationNum + ", \"status\": \"" + status +
            "\", \"created_at\": \"2026-11-01T08:00:00Z\", \"line_items\": [" + String.join(", ", lines) + "]}";
    }

    /** A fulfillment whose updated_at (Shopify's change time — the cancel time for a cancelled one) is given. */
    private static String fAt(String id, String locationNum, String status, String updatedAt, String... lines) {
        return "{\"id\": " + id + ", \"location_id\": " + locationNum + ", \"status\": \"" + status +
            "\", \"created_at\": \"2026-11-01T08:00:00Z\", \"updated_at\": \"" + updatedAt + "\", \"line_items\": [" +
            String.join(", ", lines) + "]}";
    }

    private static String fulfillmentsField(String... fs) {
        return "\"fulfillments\": [" + String.join(", ", fs) + "]";
    }

    private static String fulfillments(String... fs) {
        return "{" + fulfillmentsField(fs) + "}";
    }
}
