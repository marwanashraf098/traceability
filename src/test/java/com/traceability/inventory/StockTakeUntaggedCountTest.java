package com.traceability.inventory;

import com.traceability.ApiException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.assertj.core.api.ThrowableAssert;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Flexible labeling slice 7 — a stock take counts untagged pieces per variant (switch on).
 *
 *   u1  counted == expected → nothing written off, nothing pushed
 *   u2  shortfall → exactly N written off (available → lost, stock_take_missing), OLDEST first (created_at, id);
 *       needs the attestation; Shopify −N exactly once through the existing pushStockTakeWriteOff, no other
 *       inventory call; the review plan predicted it
 *   u3  surplus → nothing written (no piece created), "extra" reported in the plan and the finalize result
 *   u4  a blank count blocks finalize (UNTAGGED_COUNT_MISSING, EN/AR, nothing changes); clearing a typed count
 *       blocks again; an explicit 0 writes off every expected untagged piece
 *   u5  mixed variant (labeled + untagged) and a labeled-only variant in one session — both rules apply
 *   u6  the labeled not-scanned rule never writes off an untagged piece (plan + finalize)
 *   u7  per-row Found / lost / mark damaged refuse an untagged piece; reconciliation buckets and coverage
 *       hold labeled pieces only
 *   u8  count validation (no untagged pieces of the variant, negative, too large) and drift: an untagged piece
 *       bound while the session is open leaves the expected count and is never written off
 *   u9  a session whose only free stock is untagged finalizes on its counts with zero scans
 *   b1  the counting screen stays blind: its responses (session detail, untagged-counts, the count PUT) carry no
 *       expected / write-off / extra — only Review's reconciliation does
 *   f1  switch off: no untagged keys anywhere, counts refused, finalize exactly as before
 *   x1  app_user: counts and rows are tenant-scoped (same-tenant positive control)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakeUntaggedCountTest {

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

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate                   jdbc;
    @Autowired StockTakeService               stockTake;
    @Autowired StockTakeReconciliationService reconciliation;

    record T(UUID tenant, UUID store, String shop, UUID location, String traced, UUID user) {}
    record V(UUID id, String item) {}

    final List<JobLambda> enqueued = new CopyOnWriteArrayList<>();
    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void stubs() {
        Mockito.reset(jobScheduler, shopifyGateway, tokenProvider);
        enqueued.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(jobScheduler.enqueue(any(JobLambda.class))).thenAnswer(inv -> { enqueued.add(inv.getArgument(0)); return null; });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── u1 ────────────────────────────────────────────────────────────────────

    @Test
    void u1_counted_equals_expected_writes_nothing_off() {
        T t = tenant("u1", true);
        V a = variant(t, "u1");
        List<String> labeled = pieces(t, a, 2);
        List<String> untagged = untagged(t, a, 4);
        UUID s = open(t);
        labeled.forEach(p -> scan(t, s, p));
        count(t, s, a, 4);

        Map<String, Object> plan = finalizePlan(t, s);
        assertThat(plan).containsEntry("untaggedWriteOffs", 0).containsEntry("untaggedExtra", 0)
            .containsEntry("untaggedMissingCounts", 0).containsEntry("blockedReason", null);
        assertThat(untaggedRow(plan, a)).containsEntry("expected", 4).containsEntry("counted", 4)
            .containsEntry("writeOff", 0).containsEntry("extra", 0);

        Map<String, Object> out = finalize(t, s, null);
        assertThat(out).containsEntry("untaggedWriteOffs", 0).containsEntry("shopifySyncStatus", "nothing_to_push");
        untagged.forEach(p -> assertThat(status(p)).isEqualTo("available"));
        assertThat(enqueued).isEmpty();
        verifyNoInteractions(shopifyGateway);
    }

    // ── u2 ────────────────────────────────────────────────────────────────────

    @Test
    void u2_shortfall_writes_off_exactly_n_oldest_first_and_decrements_shopify_once() throws Exception {
        T t = tenant("u2", true);
        V a = variant(t, "u2");
        List<String> labeled = pieces(t, a, 2);
        List<String> untagged = untagged(t, a, 5);
        // created_at decides, not the id: the LAST two ids are the oldest.
        jdbc.update("UPDATE pieces SET created_at = now() - interval '1 day' WHERE id IN (?, ?)", untagged.get(3), untagged.get(4));
        jdbc.update("UPDATE pieces SET created_at = now() - interval '1 hour' WHERE id IN (?, ?, ?)",
            untagged.get(0), untagged.get(1), untagged.get(2));
        UUID s = open(t);
        labeled.forEach(p -> scan(t, s, p));
        count(t, s, a, 3);

        Map<String, Object> plan = finalizePlan(t, s);
        assertThat(plan).containsEntry("untaggedWriteOffs", 2).containsEntry("shopifyDecrement", 2)
            .containsEntry("blockedReason", "ATTESTATION_REQUIRED");
        assertThat(untaggedRow(plan, a)).containsEntry("expected", 5).containsEntry("counted", 3).containsEntry("writeOff", 2);
        assertThatThrownBy(() -> finalize(t, s, null)).hasMessageContaining("ATTESTATION_REQUIRED");
        untagged.forEach(p -> assertThat(status(p)).isEqualTo("available"));

        attest(t, s);
        Map<String, Object> out = finalize(t, s, null);
        assertThat(out).containsEntry("untaggedWriteOffs", 2).containsEntry("writeOffs", 0);
        assertThat(status(untagged.get(3))).isEqualTo("lost");
        assertThat(status(untagged.get(4))).isEqualTo("lost");
        for (int i = 0; i < 3; i++) assertThat(status(untagged.get(i))).isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM piece_events WHERE tenant_id = ? AND event_type = 'adjusted' " +
            "AND from_status = 'available' AND to_status = 'lost' AND metadata->>'reason' = 'stock_take_missing' " +
            "AND metadata->>'session_id' = ? AND metadata->>'untagged' = 'true'", Integer.class, t.tenant(), s.toString()))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload->'deltas'::text FROM stock_take_shopify_syncs WHERE session_id = ?",
            String.class, s)).isEqualTo("{\"" + a.id() + "\": 2}");

        assertThat(enqueued).hasSize(1);
        enqueued.get(0).run();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(eq(t.shop()), any(), deltas.capture(), eq(t.traced()), any(), any());
        assertThat(deltas.getValue()).singleElement().satisfies(d -> {
            assertThat(d.inventoryItemGid()).isEqualTo(a.item());
            assertThat(d.negativeDelta()).isEqualTo(-2);
        });
        verifyNoMoreInteractions(shopifyGateway);   // no other decrement / adjust / move

        // Finalized: the review shows what happened.
        Map<String, Object> rec = TenantContext.runAs(t.tenant(), () -> reconciliation.reconciliation(s));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rec.get("untagged");
        assertThat(rows).singleElement().satisfies(r -> assertThat(r).containsEntry("expected", 5)
            .containsEntry("counted", 3).containsEntry("writeOff", 2).containsEntry("extra", 0));
    }

    // ── u3 ────────────────────────────────────────────────────────────────────

    @Test
    void u3_surplus_writes_nothing_and_reports_the_extra() {
        T t = tenant("u3", true);
        V a = variant(t, "u3");
        List<String> labeled = pieces(t, a, 1);
        untagged(t, a, 2);
        UUID s = open(t);
        scan(t, s, labeled.get(0));
        count(t, s, a, 5);
        int piecesBefore = count("SELECT count(*) FROM pieces WHERE tenant_id = ?", t.tenant());

        Map<String, Object> plan = finalizePlan(t, s);
        assertThat(plan).containsEntry("untaggedWriteOffs", 0).containsEntry("untaggedExtra", 3);
        assertThat(untaggedRow(plan, a)).containsEntry("extra", 3).containsEntry("writeOff", 0);

        Map<String, Object> out = finalize(t, s, null);
        assertThat(out).containsEntry("untaggedExtra", 3).containsEntry("untaggedWriteOffs", 0);
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ?", t.tenant())).as("no piece created").isEqualTo(piecesBefore);
        assertThat(count("SELECT count(*) FROM piece_events WHERE tenant_id = ? AND event_type <> 'received'", t.tenant())).isZero();
        assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ? AND status <> 'available'", t.tenant())).isZero();
        verifyNoInteractions(shopifyGateway);
    }

    // ── u4 ────────────────────────────────────────────────────────────────────

    @Test
    void u4_a_blank_count_blocks_finalize_and_an_explicit_zero_writes_off_all() {
        T t = tenant("u4", true);
        V a = variant(t, "u4"), b = variant(t, "u4b");
        List<String> labeled = pieces(t, a, 1);
        List<String> untaggedA = untagged(t, a, 3);
        untagged(t, b, 1);
        UUID s = open(t);
        scan(t, s, labeled.get(0));
        attest(t, s);

        assertThat(finalizePlan(t, s)).containsEntry("blockedReason", "UNTAGGED_COUNT_MISSING")
            .containsEntry("untaggedMissingCounts", 2);
        ApiException e = catchThrowableOfType(() -> finalize(t, s, null), ApiException.class);
        assertThat(e.errorCode()).isEqualTo("UNTAGGED_COUNT_MISSING");
        assertThat(e.messageEn()).isEqualTo("Enter the untagged count for 2 products (type 0 if there are none).");
        assertThat(e.messageAr()).contains("2");
        assertThat(sessionStatus(s)).isEqualTo("open");

        count(t, s, b, 1);
        count(t, s, a, 2);
        count(t, s, a, null);                                   // cleared → blank again
        assertThat(finalizePlan(t, s)).containsEntry("untaggedMissingCounts", 1);
        assertCodeIs(() -> finalize(t, s, null), "UNTAGGED_COUNT_MISSING");
        untaggedA.forEach(p -> assertThat(status(p)).isEqualTo("available"));

        count(t, s, a, 0);                                      // explicit 0: none on the shelf
        Map<String, Object> out = finalize(t, s, null);
        assertThat(out).containsEntry("untaggedWriteOffs", 3);
        untaggedA.forEach(p -> assertThat(status(p)).isEqualTo("lost"));
    }

    // ── u5 ────────────────────────────────────────────────────────────────────

    @Test
    void u5_mixed_variant_and_a_labeled_only_variant_in_one_session() throws Exception {
        T t = tenant("u5", true);
        V a = variant(t, "u5a"), b = variant(t, "u5b");
        List<String> aLabeled = pieces(t, a, 4);
        List<String> aUntagged = untagged(t, a, 3);
        List<String> bLabeled = pieces(t, b, 3);
        UUID s = open(t);
        for (int i = 0; i < 3; i++) scan(t, s, aLabeled.get(i));      // A: 1 labeled unscanned
        bLabeled.forEach(p -> scan(t, s, p));                           // B: all counted
        count(t, s, a, 2);                                              // A: 1 untagged short
        attest(t, s);

        Map<String, Object> plan = finalizePlan(t, s);
        assertThat(plan).containsEntry("writeOffs", 1).containsEntry("untaggedWriteOffs", 1)
            .containsEntry("shopifyDecrement", 2).containsEntry("expectedFree", 7).containsEntry("scannedFree", 6);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) plan.get("untagged");
        assertThat(rows).as("only A has untagged pieces").singleElement()
            .satisfies(r -> assertThat(r).containsEntry("variantId", a.id()));

        finalize(t, s, (Integer) plan.get("writeOffs"));
        assertThat(status(aLabeled.get(3))).isEqualTo("lost");
        assertThat(aUntagged.stream().filter(p -> "lost".equals(status(p))).count()).isEqualTo(1);
        assertThat(status(aUntagged.get(0))).as("oldest (same created_at → lowest id)").isEqualTo("lost");
        bLabeled.forEach(p -> assertThat(status(p)).isEqualTo("available"));
        assertThat(jdbc.queryForObject("SELECT payload->'deltas'::text FROM stock_take_shopify_syncs WHERE session_id = ?",
            String.class, s)).isEqualTo("{\"" + a.id() + "\": 2}");
        enqueued.get(0).run();
        verify(shopifyGateway, times(1)).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
    }

    // ── u6 ────────────────────────────────────────────────────────────────────

    @Test
    void u6_the_labeled_not_scanned_rule_never_writes_off_an_untagged_piece() {
        T t = tenant("u6", true);
        V a = variant(t, "u6");
        List<String> labeled = pieces(t, a, 3);
        List<String> untagged = untagged(t, a, 3);
        UUID s = open(t);
        scan(t, s, labeled.get(0));                                 // 2 labeled not scanned → written off
        count(t, s, a, 3);                                          // untagged all there
        attest(t, s);

        StockTakeReconciliationService.FinalizePlan plan =
            TenantContext.runAs(t.tenant(), () -> reconciliation.plan(s, t.tenant(), true));
        assertThat(plan.writeOffs()).extracting(StockTakeReconciliationService.WriteOff::pieceId)
            .containsExactlyInAnyOrder(labeled.get(1), labeled.get(2)).doesNotContainAnyElementsOf(untagged);
        assertThat(plan.untaggedWriteOffs()).isEmpty();

        finalize(t, s, plan.writeOffs().size());
        untagged.forEach(p -> assertThat(status(p)).as("never written off by the not-scanned rule").isEqualTo("available"));
        assertThat(status(labeled.get(1))).isEqualTo("lost");
    }

    // ── u7 ────────────────────────────────────────────────────────────────────

    @Test
    void u7_per_row_resolutions_refuse_untagged_pieces_and_buckets_hold_labeled_only() {
        T t = tenant("u7", true);
        V a = variant(t, "u7");
        List<String> labeled = pieces(t, a, 2);
        List<String> untagged = untagged(t, a, 2);
        UUID s = open(t);
        scan(t, s, labeled.get(0));
        attest(t, s);

        for (String action : List.of("found", "lost", "mark_damaged")) {
            assertCodeIs(() -> TenantContext.runAs(t.tenant(), () -> reconciliation.resolve(s,
                List.of(new StockTakeReconciliationService.ResolveItem(untagged.get(0), action)), t.user())),
                "UNTAGGED_COUNTED_PER_PRODUCT");
        }
        untagged.forEach(p -> assertThat(status(p)).isEqualTo("available"));

        Map<String, Object> rec = TenantContext.runAs(t.tenant(), () -> reconciliation.reconciliation(s));
        assertThat(rec.toString()).doesNotContain(untagged.get(0)).doesNotContain(untagged.get(1));
        assertThat(rec.get("coveragePercent")).as("1 of 2 labeled").isEqualTo(50.0);
        List<Map<String, Object>> sessions = TenantContext.runAs(t.tenant(), () -> stockTake.listSessions());
        assertThat(sessions.get(0)).containsEntry("expected", 2L).containsEntry("counted", 1L);
    }

    // ── u8 ────────────────────────────────────────────────────────────────────

    @Test
    void u8_count_validation_and_drift() {
        T t = tenant("u8", true);
        V a = variant(t, "u8"), b = variant(t, "u8b");
        List<String> labeledB = pieces(t, b, 1);
        List<String> untagged = untagged(t, a, 3);
        UUID s = open(t);
        scan(t, s, labeledB.get(0));

        assertCodeIs(() -> count(t, s, b, 1), "NOT_UNTAGGED_IN_SESSION");
        assertCodeIs(() -> count(t, s, a, -1), "INVALID_UNTAGGED_COUNT");
        assertCodeIs(() -> count(t, s, a, StockTakeService.MAX_UNTAGGED_COUNT + 1), "INVALID_UNTAGGED_COUNT");

        // A label bound to the oldest while the session is open (packing): tagged + reserved now.
        jdbc.update("UPDATE pieces SET short_code = 'P999001', status = 'reserved' WHERE id = ?", untagged.get(0));
        count(t, s, a, 1);
        attest(t, s);
        Map<String, Object> plan = finalizePlan(t, s);
        assertThat(untaggedRow(plan, a)).containsEntry("expected", 2).containsEntry("writeOff", 1);
        assertThat(plan).containsEntry("writeOffs", 0).containsEntry("driftSkipped", 0);

        finalize(t, s, null);
        assertThat(status(untagged.get(0))).as("bound during the count — left alone").isEqualTo("reserved");
        assertThat(status(untagged.get(1))).isEqualTo("lost");
        assertThat(status(untagged.get(2))).isEqualTo("available");
    }

    // ── u9 ────────────────────────────────────────────────────────────────────

    @Test
    void u9_an_untagged_only_session_finalizes_on_its_counts_with_zero_scans() {
        T t = tenant("u9", true);
        V a = variant(t, "u9");
        List<String> untagged = untagged(t, a, 2);
        UUID s = open(t);
        assertThat(finalizePlan(t, s)).containsEntry("blockedReason", "UNTAGGED_COUNT_MISSING");
        count(t, s, a, 2);
        assertThat(finalizePlan(t, s)).containsEntry("blockedReason", null).containsEntry("scans", 0);
        finalize(t, s, null);
        assertThat(sessionStatus(s)).isEqualTo("finalized");
        untagged.forEach(p -> assertThat(status(p)).isEqualTo("available"));

        // Labeled stock with zero scans is still ZERO_SCANS, exactly as today.
        V b = variant(t, "u9b");
        pieces(t, b, 1);
        UUID s2 = open(t);
        count(t, s2, a, 2);
        assertThat(finalizePlan(t, s2)).containsEntry("blockedReason", "ZERO_SCANS");
    }

    // ── b1 ────────────────────────────────────────────────────────────────────

    @Test
    void b1_the_counting_screen_responses_carry_no_expected_number() {
        T t = tenant("b1", true);
        V a = variant(t, "b1");
        untagged(t, a, 4);
        UUID s = open(t);
        count(t, s, a, 1);
        Set<String> blind = Set.of("variantId", "variantTitle", "sku", "productTitle", "counted");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> onDetail = (List<Map<String, Object>>)
            TenantContext.runAs(t.tenant(), () -> stockTake.getSessionDetail(s)).get("untagged");
        assertThat(onDetail).singleElement().satisfies(r -> {
            assertThat(r.keySet()).isEqualTo(blind);
            assertThat(r).containsEntry("counted", 1);
        });
        List<Map<String, Object>> counts = TenantContext.runAs(t.tenant(), () -> stockTake.untaggedCounts(s));
        assertThat(counts).singleElement().satisfies(r -> assertThat(r.keySet()).isEqualTo(blind));
        Map<String, Object> put = TenantContext.runAs(t.tenant(), () -> stockTake.setUntaggedCount(s, a.id(), 2, t.user()));
        assertThat(put.keySet()).containsExactlyInAnyOrder("sessionId", "variantId", "counted");

        // Review (owner / manager) keeps expected vs counted and the outcome.
        assertThat(untaggedRow(finalizePlan(t, s), a)).containsEntry("expected", 4).containsEntry("counted", 2)
            .containsEntry("writeOff", 2);
    }

    // ── f1 ────────────────────────────────────────────────────────────────────

    @Test
    void f1_switch_off_stock_take_exactly_as_today() {
        T t = tenant("f1", false);
        V a = variant(t, "f1");
        List<String> labeled = pieces(t, a, 3);
        UUID s = open(t);
        scan(t, s, labeled.get(0));
        scan(t, s, labeled.get(1));
        attest(t, s);

        Map<String, Object> rec = TenantContext.runAs(t.tenant(), () -> reconciliation.reconciliation(s));
        assertThat(rec).doesNotContainKey("untagged");
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) rec.get("finalizePlan");
        assertThat(plan).doesNotContainKeys("untagged", "untaggedWriteOffs", "untaggedExtra", "untaggedMissingCounts")
            .containsEntry("writeOffs", 1).containsEntry("shopifyDecrement", 1);
        assertThat(TenantContext.runAs(t.tenant(), () -> stockTake.untaggedCounts(s))).isEmpty();
        assertCodeIs(() -> count(t, s, a, 1), "UNTAGGED_COUNT_NOT_AVAILABLE");

        Map<String, Object> out = finalize(t, s, 1);
        assertThat(out).doesNotContainKeys("untaggedWriteOffs", "untaggedExtra").containsEntry("writeOffs", 1);
        assertThat(status(labeled.get(2))).isEqualTo("lost");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_take_expected WHERE session_id = ? AND untagged_at_open",
            Integer.class, s)).isZero();
    }

    // ── x1 ────────────────────────────────────────────────────────────────────

    @Test
    void x1_untagged_counts_are_tenant_scoped_under_app_user() {
        T mine = tenant("x1a", true), theirs = tenant("x1b", true);
        V a = variant(mine, "x1a"), b = variant(theirs, "x1b");
        untagged(mine, a, 2);
        untagged(theirs, b, 3);
        UUID s = open(mine), other = open(theirs);
        count(theirs, other, b, 1);

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        TenantContext.set(mine.tenant());
        try {
            List<StockTakeUntagged.Row> own = tx.execute(x -> StockTakeUntagged.rows(app, mine.tenant(), s, mine.location(), true));
            assertThat(own).as("same-tenant positive control").singleElement()
                .satisfies(r -> assertThat(r.expected()).isEqualTo(2));
            List<StockTakeUntagged.Row> leak = tx.execute(x -> StockTakeUntagged.rows(app, theirs.tenant(), other, theirs.location(), true));
            assertThat(leak).as("another tenant's session, pieces and counts are invisible under RLS").isEmpty();
            Integer visibleBefore = tx.execute(x -> app.queryForObject("SELECT count(*) FROM stock_take_untagged_counts", Integer.class));
            assertThat(visibleBefore).isZero();
            assertThatThrownBy(() -> tx.execute(x -> app.update(
                "INSERT INTO stock_take_untagged_counts (tenant_id, session_id, variant_id, counted, counted_by) VALUES (?, ?, ?, 0, ?)",
                theirs.tenant(), other, b.id(), theirs.user()))).as("RLS WITH CHECK").isInstanceOf(Exception.class);
            tx.execute(x -> app.update(
                "INSERT INTO stock_take_untagged_counts (tenant_id, session_id, variant_id, counted, counted_by) VALUES (?, ?, ?, 2, ?)",
                mine.tenant(), s, a.id(), mine.user()));
            Integer visibleAfter = tx.execute(x -> app.queryForObject("SELECT count(*) FROM stock_take_untagged_counts", Integer.class));
            assertThat(visibleAfter).as("own count visible").isEqualTo(1);
        } finally {
            TenantContext.clear();
        }
        assertThat(jdbc.queryForObject("SELECT counted FROM stock_take_untagged_counts WHERE session_id = ?", Integer.class, other))
            .as("their count untouched").isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private T tenant(String name, boolean flag) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, ?, ?)", tenant, "Tenant " + name, flag);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            user, tenant, name + "-" + tenant.toString().substring(0, 6) + "@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", location, tenant, traced);
        return new T(tenant, store, shop, location, traced, user);
    }

    private V variant(T t, String key) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
        String item = "gid://shopify/InventoryItem/" + key + "-" + variant.toString().substring(0, 6);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, t.tenant(), t.store(), "gid://shopify/Product/" + t.shop() + "-" + key);
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)", variant, t.tenant(), product,
            "gid://shopify/ProductVariant/" + t.shop() + "-" + key, "Variant " + key, "SKU-" + key, item);
        return new V(variant, item);
    }

    /** Labeled available pieces (short code set), scanned by their short code. */
    private List<String> pieces(T t, V v, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = seq.incrementAndGet();
            String id = String.format("01STUNTAGLAB%014d", k);
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
                "VALUES (?, ?, ?, 'available', ?, ?, ?)", id, t.tenant(), v.id(), "SU-" + k, String.format("U%07d", k), t.location());
            out.add(id);
        }
        return out;
    }

    /** Untagged available pieces (short_code NULL), same created_at, ids in creation order. */
    private List<String> untagged(T t, V v, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = seq.incrementAndGet();
            String id = String.format("01STUNTAGUNT%014d", k);
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, created_at) " +
                "VALUES (?, ?, ?, 'available', ?, NULL, ?, '2026-10-01T00:00:00Z')", id, t.tenant(), v.id(), "PC-" + id, t.location());
            out.add(id);
        }
        return out;
    }

    private UUID open(T t) {
        return TenantContext.runAs(t.tenant(), () ->
            (UUID) stockTake.openSession("all", null, t.location(), null, t.user()).get("sessionId"));
    }

    private void scan(T t, UUID s, String pieceId) {
        String code = jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, pieceId);
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, code, "good", t.user()));
    }

    private void count(T t, UUID s, V v, Integer n) {
        TenantContext.runAs(t.tenant(), () -> stockTake.setUntaggedCount(s, v.id(), n, t.user()));
    }

    private void attest(T t, UUID s) {
        TenantContext.runAs(t.tenant(), () -> reconciliation.attestComplete(s, t.user()));
    }

    private Map<String, Object> finalize(T t, UUID s, Integer confirm) {
        return TenantContext.runAs(t.tenant(), () -> reconciliation.finalizeSession(s, t.user(), confirm));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> finalizePlan(T t, UUID s) {
        return (Map<String, Object>) TenantContext.runAs(t.tenant(), () -> reconciliation.reconciliation(s)).get("finalizePlan");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> untaggedRow(Map<String, Object> plan, V v) {
        return ((List<Map<String, Object>>) plan.get("untagged")).stream()
            .filter(r -> v.id().equals(r.get("variantId"))).findFirst().orElseThrow();
    }

    private String status(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String sessionStatus(UUID s) {
        return jdbc.queryForObject("SELECT status FROM stock_take_sessions WHERE id = ?", String.class, s);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void assertCodeIs(ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(code));
    }
}
