package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyCasStaleException;
import com.traceability.integrations.shopify.ShopifyException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Shopify Main-location reconciler R2 — write mode (V203, approved 2026-11-04). Every run goes through the real service
 * on an app_user connection (RLS enforced); fixtures are written as postgres. Shopify is a mock whose Main levels the
 * test sets (an applied write moves them). After every test: the only Shopify calls are reads and the two named
 * writes, and every write targets the tenant's Traced Main location.
 *
 *   w1  write allowlist: a listed store writes its confirmed difference (mode 'write'); an unlisted store never writes
 *       (report-only, confirmed diff only); 'all' = every store; empty = none; no Part 1 cutoff = none
 *   w2  compare-and-set: changeFromQuantity is the "available" of a FRESH read right before the write; a CAS rejection
 *       is recorded 'cas_rejected' (definite), the variant re-queued, and the next run writes it; Shopify moving
 *       between the confirmed read and the fresh one → 'changed_before_write', nothing sent
 *   w3  failed_ambiguous: another path's old ambiguous claim at Main is settled 'superseded_by_reconcile' in the
 *       transaction that records the applied write; a recent one still skips; a report-only store skips; an
 *       ambiguous reconcile write is never re-sent and holds its variant, then is settled by the next write
 *   w4  kill switch: enabled=false → no run, no Shopify call
 *   p1  Part 1 write: a queued claim is sent once (−N at Main) and never again; an ambiguous send is never re-sent;
 *       a definite failure is re-sent on the next run
 *   p2  Part 1 cutoff: a report_only claim from a fulfillment before part1-since → 'ignored_pre_cutoff' (kept, not
 *       owed — the target drops it); a new pre-cutoff fulfillment → no claim ('pre_cutoff')
 *   f2  fix 2: pieces that left Traced for a line still unfulfilled in Shopify are in the target; capped at Shopify's
 *       committed at Main (note unfulfilled_capped); cancelled or fulfilled orders add nothing
 *   a1  delivered_not_fulfilled_in_shopify: fires for an order delivered > 7 days ago and unfulfilled in Shopify (one
 *       per order), not for a recent one; clears when Shopify fulfills it or it is cancelled
 *   x2  cross-tenant as app_user: no tenant → zero writes rows; tenant B can't see A's; A sees its own
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainReconcileWriteTest {

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
    static final String CUTOFF = "2026-11-01T00:00:00Z";
    static final Set<String> ALLOWED = Set.of("fetchMainLevels", "fetchMainLevelsPage", "resolveInventoryItemIds",
        "pushMainReconcile", "pushPart1MainDecrement");

    @Autowired JdbcTemplate jdbc;               // postgres — fixtures only
    @Autowired ObjectMapper mapper;
    @Autowired ExceptionService exceptionService;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;

    final Map<String, ShopifyGateway.MainLevel> shopifyMain = new HashMap<>();
    /** A one-shot answer for the SECOND next fetchMainLevels call (the fresh read before a write — the run's own read comes first). */
    final Deque<ShopifyGateway.MainLevel> nextRead = new ArrayDeque<>();
    final AtomicInteger readsBeforeOverride = new AtomicInteger();
    final AtomicInteger seq = new AtomicInteger();

    record T(UUID tenant, UUID store, UUID main, String mainGid, UUID user, String shop) {
        String mainNum() { return mainGid.replaceAll("^.*/", ""); }
    }
    record V(UUID id, long shopifyId, String item) {}

    @BeforeAll
    void appUser() {
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTxm = new DataSourceTransactionManager(appDs);
    }

    MainReconcileService service(String writeShops, String part1Since) {
        MainReconcileService s = new MainReconcileService(appJdbc, appTxm, shopifyGateway, tokenProvider,
            new StoreRepository(appJdbc, appTxm));
        s.setReportOnly(false);
        s.setWriteShops(writeShops);
        s.setPart1Since(part1Since);
        s.setEnabled(true);
        s.setShops("");
        s.setConfirmAfterMinutes(0);
        s.setPart1WindowDays(30);
        s.setDebounceSeconds(0);
        s.setQueueBatch(500);
        s.setAmbiguousHoldMinutes(60);
        s.setPendingStaleMinutes(15);
        return s;
    }

    @BeforeEach
    void stubs() {
        Mockito.reset(shopifyGateway, tokenProvider);
        shopifyMain.clear();
        nextRead.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopifyGateway.fetchMainLevels(any(), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<ShopifyGateway.MainLevel> out = new ArrayList<>();
            if (!nextRead.isEmpty() && readsBeforeOverride.getAndDecrement() <= 0) { out.add(nextRead.poll()); return out; }
            for (String id : ids) {
                ShopifyGateway.MainLevel l = shopifyMain.get(id);
                if (l != null) out.add(l);
            }
            return out;
        });
        when(shopifyGateway.fetchMainLevelsPage(any(), any(), any(), any())).thenAnswer(inv ->
            new ShopifyGateway.MainLevelsPage(shopifyMain.values().stream().filter(ShopifyGateway.MainLevel::stocked).toList(), null));
        // An applied write moves Shopify's numbers, as Shopify would.
        doAnswer(inv -> { move(inv.getArgument(2), inv.getArgument(4)); return null; })
            .when(shopifyGateway).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        doAnswer(inv -> { move(inv.getArgument(2), inv.getArgument(4)); return null; })
            .when(shopifyGateway).pushPart1MainDecrement(any(), any(), any(), any(), anyInt(), any(), any());
    }

    private void move(String item, int delta) {
        ShopifyGateway.MainLevel l = shopifyMain.get(item);
        if (l != null) shopifyMain.put(item, new ShopifyGateway.MainLevel(item, true, l.onHand() + delta,
            l.available() + delta, l.committed(), l.damaged()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        assertWritesOnlyAtMain();
    }

    // ── w1: allowlist ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void w1_writeAllowlist_onlyListedStoresWrite() {
        T listed = tenant("w1a"), other = tenant("w1b");
        V a = variant(listed), b = variant(other);
        piece(listed, a, "available", listed.main());
        piece(other, b, "available", other.main());
        level(a, 3, 3, 0, 0);
        level(b, 3, 3, 0, 0);
        MainReconcileService s = service(listed.shop(), CUTOFF);

        run(s, listed, a); run(s, other, b);                // first sight: unconfirmed
        UUID ra = run(s, listed, a), rb = run(s, other, b); // confirmed
        assertThat(runRow(listed, ra)).containsEntry("mode", "write").containsEntry("writes_attempted", 1)
            .containsEntry("writes_applied", 1);
        assertThat(runRow(other, rb)).containsEntry("mode", "report_only").containsEntry("writes_attempted", 0);
        assertThat(onlyDiff(other, rb)).containsEntry("status", "confirmed").containsEntry("delta", -2);
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(a.item()), eq(listed.mainGid()), eq(-2), eq(3), any(), any());
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), eq(b.item()), any(), anyInt(), any(), any(), any());
        Map<String, Object> w = onlyWrite(listed);
        assertThat(w).containsEntry("status", "applied").containsEntry("delta", -2).containsEntry("target", 1)
            .containsEntry("shopify_on_hand", 3).containsEntry("fresh_on_hand", 3).containsEntry("change_from_quantity", 3);
        assertThat(writeCount(other)).isZero();
        // In sync afterwards: the next run writes nothing.
        UUID ra3 = run(s, listed, a);
        assertThat(runRow(listed, ra3)).containsEntry("variants_in_sync", 1).containsEntry("writes_attempted", 0);

        // 'all' = every store.
        MainReconcileService all = service("all", CUTOFF);
        assertThat(runRow(other, run(all, other, b))).containsEntry("mode", "write").containsEntry("writes_applied", 1);
        // Empty = none; no cutoff = none; report-only = none.
        assertThat(service("", CUTOFF).writeEnabled(listed.shop())).isFalse();
        assertThat(service("all", "").writeEnabled(listed.shop())).isFalse();
        MainReconcileService ro = service("all", CUTOFF);
        ro.setReportOnly(true);
        assertThat(ro.writeEnabled(listed.shop())).isFalse();
    }

    // ── w2: compare-and-set ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void w2_casRejection_definite_nextRunWrites_andChangedBeforeWrite_sendsNothing() {
        T t = tenant("w2");
        V v = variant(t), u = variant(t);
        piece(t, v, "available", t.main());
        piece(t, u, "available", t.main());
        level(v, 3, 2, 1, 0);   // available 2 ≠ on_hand 3: the baseline must be the fresh AVAILABLE
        level(u, 3, 3, 0, 0);
        MainReconcileService s = service("all", CUTOFF);

        run(s, t, v);
        doThrow(new ShopifyCasStaleException("stale")).doAnswer(inv -> { move(inv.getArgument(2), inv.getArgument(4)); return null; })
            .when(shopifyGateway).pushMainReconcile(any(), any(), eq(v.item()), any(), anyInt(), any(), any(), any());
        UUID r2 = run(s, t, v);
        assertThat(runRow(t, r2)).containsEntry("writes_attempted", 1).containsEntry("writes_applied", 0);
        Map<String, Object> w = onlyWrite(t);
        assertThat(w).containsEntry("status", "cas_rejected").containsEntry("change_from_quantity", 2);
        assertThat(jdbc.queryForObject("SELECT pending_source FROM main_reconcile_variant_state WHERE tenant_id = ? AND variant_id = ?",
            String.class, t.tenant(), v.id())).isEqualTo("recheck");
        UUID r3 = run(s, t, v);
        assertThat(runRow(t, r3)).containsEntry("writes_applied", 1);
        verify(shopifyGateway, times(2)).pushMainReconcile(any(), any(), eq(v.item()), eq(t.mainGid()), eq(-2), eq(2), any(), any());

        // Shopify moved between the confirmed read and the fresh read → nothing sent.
        run(s, t, u);
        nextRead.add(new ShopifyGateway.MainLevel(u.item(), true, 4, 4, 0, 0));
        readsBeforeOverride.set(1);
        run(s, t, u);
        assertThat(writesFor(t, u)).singleElement().satisfies(r -> assertThat(r)
            .containsEntry("status", "changed_before_write").containsEntry("fresh_on_hand", 4));
        verify(shopifyGateway, never()).pushMainReconcile(any(), any(), eq(u.item()), any(), anyInt(), any(), any(), any());
    }

    // ── w3: failed_ambiguous ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void w3_ambiguousClaims_oldSettledByTheWrite_recentSkip_reportOnlySkips_ownAmbiguousNeverResent() {
        T t = tenant("w3");
        V old = variant(t), recent = variant(t), own = variant(t);
        for (V v : List.of(old, recent, own)) { piece(t, v, "available", t.main()); level(v, 2, 2, 0, 0); }
        long oldClaim = ambiguousClaim(t, old, 120);
        long recentClaim = ambiguousClaim(t, recent, 10);
        MainReconcileService s = service("all", CUTOFF);

        // Report-only store: the old ambiguous claim still skips.
        MainReconcileService ro = service("", CUTOFF);
        UUID r0 = run(ro, t, old);
        assertThat(onlyDiff(t, r0)).containsEntry("skip_reason", MainReconcileRules.CLAIM_AMBIGUOUS);

        run(s, t, old, recent);
        UUID r2 = run(s, t, old, recent);
        assertThat(onlyDiffFor(t, r2, recent)).containsEntry("skip_reason", MainReconcileRules.CLAIM_AMBIGUOUS);
        assertThat(claimStatus(oldClaim)).isEqualTo("superseded_by_reconcile");
        assertThat(claimStatus(recentClaim)).isEqualTo("failed_ambiguous");
        Map<String, Object> w = writesFor(t, old).get(0);
        assertThat(w).containsEntry("status", "applied");
        assertThat(String.valueOf(w.get("settled"))).contains("shopify_inventory_adjustments").contains(String.valueOf(oldClaim));
        assertThat(jdbc.queryForObject("SELECT superseded_at IS NOT NULL FROM shopify_inventory_adjustments WHERE id = ?",
            Boolean.class, oldClaim)).isTrue();

        // An ambiguous reconcile write: never re-sent; it holds its variant until the hold passes, then is settled.
        doThrow(new ShopifyAmbiguousException("timeout"))
            .when(shopifyGateway).pushMainReconcile(any(), any(), eq(own.item()), any(), anyInt(), any(), any(), any());
        run(s, t, own);
        run(s, t, own);
        assertThat(writesFor(t, own)).singleElement().satisfies(r -> assertThat(r).containsEntry("status", "failed_ambiguous"));
        UUID r5 = run(s, t, own);
        assertThat(onlyDiffFor(t, r5, own)).containsEntry("skip_reason", MainReconcileRules.RECONCILE_WRITE_IN_FLIGHT);
        verify(shopifyGateway, times(1)).pushMainReconcile(any(), any(), eq(own.item()), any(), anyInt(), any(), any(), any());

        jdbc.update("UPDATE main_reconcile_writes SET created_at = now() - interval '2 hours' WHERE tenant_id = ? AND variant_id = ?",
            t.tenant(), own.id());
        doAnswer(inv -> { move(inv.getArgument(2), inv.getArgument(4)); return null; })
            .when(shopifyGateway).pushMainReconcile(any(), any(), eq(own.item()), any(), anyInt(), any(), any(), any());
        run(s, t, own);
        run(s, t, own);
        List<Map<String, Object>> ws = writesFor(t, own);
        assertThat(ws).extracting(r -> r.get("status")).containsExactly("superseded_by_reconcile", "applied");
        assertThat(String.valueOf(ws.get(1).get("settled"))).contains("main_reconcile_writes");
    }

    // ── w4: kill switch ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void w4_killSwitch_noRun_noShopifyCall() {
        T t = tenant("w4");
        V v = variant(t);
        piece(t, v, "available", t.main());
        level(v, 3, 3, 0, 0);
        MainReconcileService s = service("all", CUTOFF);
        s.setEnabled(false);
        assertThat((UUID) TenantContext.runAs(t.tenant(), () -> s.runVariants(t.tenant(), List.of(v.id())))).isNull();
        assertThat((UUID) TenantContext.runAs(t.tenant(), () -> s.runSweep(t.tenant()))).isNull();
        assertThat(Mockito.mockingDetails(shopifyGateway).getInvocations()).isEmpty();
    }

    // ── Part 1 ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void p1_part1Write_onceOnly_ambiguousNeverResent_definiteRetried() {
        T t = tenant("p1");
        V v = variant(t), amb = variant(t), def = variant(t);
        shipped(t, v, "9001", "90011");
        shipped(t, amb, "9002", "90021");
        shipped(t, def, "9003", "90031");
        doThrow(new ShopifyAmbiguousException("timeout"))
            .when(shopifyGateway).pushPart1MainDecrement(any(), any(), eq(amb.item()), any(), anyInt(), any(), any());
        doThrow(new ShopifyException("rejected")).doAnswer(inv -> { move(inv.getArgument(2), inv.getArgument(4)); return null; })
            .when(shopifyGateway).pushPart1MainDecrement(any(), any(), eq(def.item()), any(), anyInt(), any(), any());
        MainReconcileService s = service("all", CUTOFF);

        UUID r1 = run(s, t, v, amb, def);
        assertThat(runRow(t, r1)).containsEntry("writes_attempted", 3).containsEntry("writes_applied", 1);
        assertThat(p1Status(t, "9001")).isEqualTo("applied");
        assertThat(p1Status(t, "9002")).isEqualTo("failed_ambiguous");
        assertThat(p1Status(t, "9003")).isEqualTo("failed");
        assertThat(shopifyMain.get(v.item()).onHand()).isZero();

        run(s, t, v, amb, def);
        run(s, t, v, amb, def);
        verify(shopifyGateway, times(1)).pushPart1MainDecrement(any(), any(), eq(v.item()), eq(t.mainGid()), eq(-1), any(), any());
        verify(shopifyGateway, times(1)).pushPart1MainDecrement(any(), any(), eq(amb.item()), any(), anyInt(), any(), any());
        verify(shopifyGateway, times(2)).pushPart1MainDecrement(any(), any(), eq(def.item()), eq(t.mainGid()), eq(-1), any(), any());
        assertThat(p1Status(t, "9003")).isEqualTo("applied");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM main_reconcile_part1_claims WHERE tenant_id = ? AND fulfillment_id = '9003'",
            Integer.class, t.tenant())).isEqualTo(2);
        // Once applied, Shopify Main and Traced agree: no reconcile write for v.
        assertThat(writesFor(t, v)).isEmpty();
    }

    @Test
    void p2_part1Cutoff_oldReportOnlyClaimIgnored_notOwed_preCutoffLineNotClaimed() {
        T t = tenant("p2");
        V v = variant(t), w = variant(t);
        // An R1 report_only claim from a fulfillment before the cutoff.
        UUID o1 = order(t, fulfillments(fAt("9101", "424242", "success", "2026-10-15T08:00:00Z", line("91011", v, 1))));
        String p = piece(t, v, "with_courier", t.main());
        allocate(t, orderItem(t, o1, v, 1), p, "packed");
        leftCustody(t, p, o1, "with_courier", 0);
        jdbc.update("INSERT INTO main_reconcile_part1_claims (tenant_id, order_id, variant_id, location_id, fulfillment_id, " +
            "line_item_id, fulfilled_location_id, fulfilled_quantity, quantity, status, fulfillment_created_at) " +
            "VALUES (?, ?, ?, ?, '9101', '91011', '424242', 1, 1, 'report_only', '2026-10-15T08:00:00Z')", t.tenant(), o1, v.id(), t.main());
        // A pre-cutoff fulfillment with no claim yet.
        UUID o2 = order(t, fulfillments(fAt("9102", "424242", "success", "2026-10-20T08:00:00Z", line("91021", w, 1))));
        String q = piece(t, w, "with_courier", t.main());
        allocate(t, orderItem(t, o2, w, 1), q, "packed");
        leftCustody(t, q, o2, "with_courier", 0);
        level(v, 0, 0, 0, 0);   // Shopify settled these long ago (the merchant fixed Main by hand)
        level(w, 0, 0, 0, 0);

        UUID r = run(service("", CUTOFF), t, v, w);
        assertThat(p1Status(t, "9101")).isEqualTo("ignored_pre_cutoff");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_part1_claims WHERE tenant_id = ? AND fulfillment_id = '9102'",
            Integer.class, t.tenant())).isZero();
        assertThat(part1(r)).containsEntry(MainReconcileRules.P1_PRE_CUTOFF, 1);
        assertThat(runRow(t, r)).containsEntry("variants_in_sync", 2).containsEntry("differences", 0);
    }

    // ── fix 2 ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void f2_shippedForUnfulfilledLines_inTarget_cappedAtCommitted_cancelledAndFulfilledExcluded() {
        T t = tenant("f2");
        V v = variant(t), capped = variant(t), cancelled = variant(t), fulfilled = variant(t);
        delivered(t, v, order(t, "{}"), 2, 3);                          // unfulfilled, 2 out
        delivered(t, capped, order(t, "{}"), 3, 3);                     // unfulfilled, 3 out — Shopify committed only 1
        UUID oc = order(t, "{\"cancelled_at\": \"2026-11-01T09:00:00Z\"}");
        jdbc.update("UPDATE orders SET status = 'cancelled'::order_status WHERE id = ?", oc);
        delivered(t, cancelled, oc, 1, 3);
        UUID of = order(t, fulfillments(f("9301", t.mainNum(), "success", line("93011", fulfilled, 1))));
        delivered(t, fulfilled, of, 1, 3);
        level(v, 2, 0, 2, 0);
        level(capped, 2, 1, 1, 0);
        level(cancelled, 1, 1, 0, 0);
        level(fulfilled, 0, 0, 0, 0);

        UUID r = run(service("", CUTOFF), t, v, capped, cancelled, fulfilled);
        assertThat(diffs(t, r).stream().map(d -> d.get("variant_id")).toList()).doesNotContain(v.id(), fulfilled.id());
        assertThat(onlyDiffFor(t, r, capped)).containsEntry("unfulfilled_shipped", 1).containsEntry("unfulfilled_shipped_raw", 3)
            .containsEntry("note", MainReconcileRules.NOTE_UNFULFILLED_CAPPED).containsEntry("target", 1).containsEntry("delta", -1);
        assertThat(onlyDiffFor(t, r, cancelled)).containsEntry("unfulfilled_shipped", 0).containsEntry("delta", -1);
        assertThat(runRow(t, r)).containsEntry("variants_in_sync", 2);
    }

    // ── alert ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void a1_deliveredNotFulfilledInShopify_firesOncePerOrder_clearsWhenFulfilledOrCancelled() {
        T t = tenant("a1");
        V v = variant(t);
        UUID late = order(t, "{}"), recent = order(t, "{}"), cancelLater = order(t, "{}");
        delivered(t, v, late, 2, 8);
        delivered(t, v, recent, 1, 3);
        delivered(t, v, cancelLater, 1, 9);

        assertThat(alerts(t)).containsExactlyInAnyOrder(late, cancelLater);

        jdbc.update("UPDATE orders SET raw = ?::jsonb WHERE id = ?",
            fulfillments(f("9401", t.mainNum(), "success", line("94011", v, 2))), late);
        jdbc.update("UPDATE orders SET status = 'cancelled'::order_status WHERE id = ?", cancelLater);
        assertThat(alerts(t)).isEmpty();
    }

    // ── isolation ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void x2_crossTenant_appUser_writesTable_positiveControl() {
        T a = tenant("xa"), b = tenant("xb");
        V va = variant(a);
        piece(a, va, "available", a.main());
        level(va, 3, 3, 0, 0);
        MainReconcileService s = service("all", CUTOFF);
        run(s, a, va); run(s, a, va);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_writes WHERE tenant_id = ?", Integer.class, a.tenant())).isEqualTo(1);

        TransactionTemplate tx = new TransactionTemplate(appTxm);
        assertThat((Integer) tx.execute(st -> appJdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_writes", Integer.class))).isZero();
        assertThat((Integer) TenantContext.runAs(b.tenant(), () -> tx.execute(st -> appJdbc.queryForObject(
            "SELECT COUNT(*) FROM main_reconcile_writes WHERE tenant_id = ?", Integer.class, a.tenant())))).isZero();
        assertThat((Integer) TenantContext.runAs(a.tenant(), () -> tx.execute(st -> appJdbc.queryForObject(
            "SELECT COUNT(*) FROM main_reconcile_writes", Integer.class)))).as("positive control").isEqualTo(1);
        // A run for B with A's variant writes nothing.
        UUID rb = run(s, b, va);
        assertThat(runRow(b, rb)).containsEntry("variants_checked", 0).containsEntry("writes_attempted", 0);
    }

    // ── ops script ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void o1_opsScript_everyStatementRuns_readOnly() throws Exception {
        T t = tenant("o1");
        V v = variant(t);
        piece(t, v, "available", t.main());
        level(v, 3, 3, 0, 0);
        MainReconcileService s = service("all", CUTOFF);
        run(s, t, v); run(s, t, v);
        delivered(t, v, order(t, "{}"), 1, 8);
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/ops/2026-11-04-main-reconcile-r2.sql"));
        String body = java.util.Arrays.stream(sql.split("\n")).filter(l -> !l.trim().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n"));
        int queries = 0;
        for (String stmt : body.split(";")) {
            if (stmt.isBlank()) continue;
            if (stmt.trim().toUpperCase().startsWith("SET ")) { jdbc.execute(stmt); continue; }
            String u = stmt.trim().toUpperCase();
            assertThat(u.startsWith("WITH") || u.startsWith("SELECT")).as(stmt).isTrue();
            assertThat(u).doesNotContain("UPDATE ").doesNotContain("DELETE ").doesNotContain("INSERT ");
            jdbc.queryForList(stmt);
            queries++;
        }
        assertThat(queries).isEqualTo(5);
    }

    // ── guard ─────────────────────────────────────────────────────────────────────────────────────────────────

    /** After every test: reads and the two named writes only; every write names the tenant's Traced Main location. */
    private void assertWritesOnlyAtMain() {
        Set<String> mainGids = new HashSet<>(jdbc.queryForList(
            "SELECT shopify_location_id FROM locations WHERE is_fulfillment AND shopify_location_id IS NOT NULL", String.class));
        for (Invocation inv : Mockito.mockingDetails(shopifyGateway).getInvocations()) {
            String m = inv.getMethod().getName();
            assertThat(ALLOWED).as("Shopify call %s", m).contains(m);
            if (m.startsWith("fetchMainLevels") || m.startsWith("push")) {
                assertThat(mainGids).as("%s location", m).contains((String) inv.getArgument(m.startsWith("push") ? 3 : 2));
            }
        }
    }

    // ── fixtures (postgres) ───────────────────────────────────────────────────────────────────────────────────

    private UUID run(MainReconcileService s, T t, V... vs) {
        List<UUID> ids = Arrays.stream(vs).map(V::id).toList();
        UUID id = TenantContext.runAs(t.tenant(), () -> s.runVariants(t.tenant(), ids));
        assertThat(id).isNotNull();
        assertThat(runRow(t, id)).containsEntry("status", "completed");
        return id;
    }

    private List<UUID> alerts(T t) {
        return TenantContext.runAs(t.tenant(), () -> exceptionService.detectAllOpen()).stream()
            .filter(a -> "delivered_not_fulfilled_in_shopify".equals(a.get("type")))
            .map(a -> (UUID) a.get("order_id")).toList();
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

    private Map<String, Object> onlyWrite(T t) {
        List<Map<String, Object>> w = jdbc.queryForList("SELECT * FROM main_reconcile_writes WHERE tenant_id = ?", t.tenant());
        assertThat(w).hasSize(1);
        return w.get(0);
    }

    private List<Map<String, Object>> writesFor(T t, V v) {
        return jdbc.queryForList("SELECT * FROM main_reconcile_writes WHERE tenant_id = ? AND variant_id = ? ORDER BY created_at",
            t.tenant(), v.id());
    }

    private int writeCount(T t) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM main_reconcile_writes WHERE tenant_id = ?", Integer.class, t.tenant());
    }

    private String p1Status(T t, String fulfillmentId) {
        return jdbc.queryForObject("SELECT status FROM main_reconcile_part1_claims WHERE tenant_id = ? AND fulfillment_id = ?",
            String.class, t.tenant(), fulfillmentId);
    }

    private String claimStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM shopify_inventory_adjustments WHERE id = ?", String.class, id);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> part1(UUID run) {
        try {
            return mapper.readValue(jdbc.queryForObject("SELECT part1_summary::text FROM main_reconcile_runs WHERE id = ?",
                String.class, run), Map.class);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long ambiguousClaim(T t, V v, int minutesAgo) {
        String piece = piece(t, v, "lost", t.main());
        return jdbc.queryForObject("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, " +
            "delta, trigger_type, trigger_id, status, send_started_at, created_at) VALUES (?, ?, ?, ?, -1, 'piece_write_off', ?, " +
            "'failed_ambiguous', now() - make_interval(mins => ?), now() - make_interval(mins => ?)) RETURNING id",
            Long.class, t.tenant(), UUID.randomUUID(), v.id(), t.main(), piece + ":" + seq.incrementAndGet(), minutesAgo, minutesAgo);
    }

    /** A piece Traced shipped for a line Shopify fulfilled from another location (after the cutoff); Main still holds 1. */
    private void shipped(T t, V v, String fulfillmentId, String lineId) {
        UUID o = order(t, fulfillments(fAt(fulfillmentId, "424242", "success", "2026-11-02T08:00:00Z", line(lineId, v, 1))));
        String p = piece(t, v, "with_courier", t.main());
        allocate(t, orderItem(t, o, v, 1), p, "packed");
        leftCustody(t, p, o, "with_courier", 0);
        level(v, 1, 1, 0, 0);
    }

    /** n pieces of v delivered for the order daysAgo days ago (one order line of quantity n). */
    private void delivered(T t, V v, UUID order, int n, int daysAgo) {
        UUID line = orderItem(t, order, v, n);
        for (int i = 0; i < n; i++) {
            String p = piece(t, v, "delivered", t.main());
            allocate(t, line, p, "packed");
            leftCustody(t, p, order, "delivered", daysAgo);
        }
    }

    private void level(V v, int onHand, int available, int committed, int damaged) {
        shopifyMain.put(v.item(), new ShopifyGateway.MainLevel(v.item(), true, onHand, available, committed, damaged));
    }

    private T tenant(String name) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        long mainNum = 81_000_000L + seq.incrementAndGet();
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
        return new T(tenant, store, location, mainGid, user, shop);
    }

    private V variant(T t) {
        long sid = 7_200_000_000L + seq.incrementAndGet();
        String item = "gid://shopify/InventoryItem/" + (9_200_000_000L + sid);
        UUID id = UUID.randomUUID();
        UUID product = jdbc.queryForObject("SELECT id FROM products WHERE tenant_id = ? LIMIT 1", UUID.class, t.tenant());
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", id, t.tenant(), product, "gid://shopify/ProductVariant/" + sid, "SKU-" + sid, item);
        return new V(id, sid, item);
    }

    private String piece(T t, V v, String status, UUID location) {
        int k = seq.incrementAndGet();
        String id = String.format("01MAINWRITE%015d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?, now() - interval '30 days')",
            id, t.tenant(), v.id(), status, "MW-" + k, String.format("W%07d", k), location);
        return id;
    }

    private UUID order(T t, String raw) {
        int k = seq.incrementAndGet();
        return jdbc.queryForObject(
            "INSERT INTO orders (tenant_id, store_id, external_id, number, status, payment_method, placed_at, raw) " +
            "VALUES (?, ?, ?, ?, 'new'::order_status, 'cod', now(), ?::jsonb) RETURNING id",
            UUID.class, t.tenant(), t.store(), "gid://shopify/Order/MW" + k, "#MW" + k, raw);
    }

    private UUID orderItem(T t, UUID order, V v, int qty) {
        return jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, t.tenant(), order, v.id(), qty);
    }

    private void allocate(T t, UUID orderItem, String piece, String status) {
        jdbc.update("INSERT INTO allocations (tenant_id, order_item_id, piece_id, status) VALUES (?, ?, ?, ?::allocation_status)",
            t.tenant(), orderItem, piece, status);
        UUID order = jdbc.queryForObject("SELECT order_id FROM order_items WHERE id = ?", UUID.class, orderItem);
        jdbc.update("UPDATE pieces SET current_order_id = ? WHERE id = ?", order, piece);
    }

    private void leftCustody(T t, String piece, UUID order, String to, int daysAgo) {
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, actor_user_id, occurred_at) " +
            "VALUES (?, ?, ?, ?, 'packed', ?::piece_status, ?, now() - make_interval(days => ?))",
            t.tenant(), piece, "delivered".equals(to) ? "delivered" : "handed_to_courier", order, to, t.user(), daysAgo);
    }

    // ── fulfillment JSON ──────────────────────────────────────────────────────────────────────────────────────

    private static String line(String id, V v, int qty) {
        return "{\"id\": " + id + ", \"variant_id\": " + v.shopifyId() + ", \"quantity\": " + qty + "}";
    }

    private static String f(String id, String locationNum, String status, String... lines) {
        return fAt(id, locationNum, status, "2026-11-02T08:00:00Z", lines);
    }

    private static String fAt(String id, String locationNum, String status, String createdAt, String... lines) {
        return "{\"id\": " + id + ", \"location_id\": " + locationNum + ", \"status\": \"" + status +
            "\", \"created_at\": \"" + createdAt + "\", \"line_items\": [" + String.join(", ", lines) + "]}";
    }

    private static String fulfillments(String... fs) {
        return "{\"fulfillments\": [" + String.join(", ", fs) + "]}";
    }
}
