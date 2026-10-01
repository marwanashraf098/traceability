package com.traceability.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Stock-take finalize applies the count (Marawan, 2026-10-01).
 *
 *   f1  — demo-shaped session: unscanned available → lost (pushed), unscanned damaged → lost (Traced
 *         only, not in the push), scanned damaged on a live-available piece → damaged (+ its damage
 *         move), committed and returns-bench pieces untouched; the push sends the available
 *         write-offs only; the review plan predicted exactly that.
 *   f2  — 0 scans → 400 ZERO_SCANS, nothing changes.
 *   f3  — drift: a piece picked between open and finalize is skipped, never written off.
 *   f4  — rolled-back finalize: no events, no claim, no job.
 *   f5  — typed confirmation: coverage < 80% → 409 without / with a wrong count, applies with the
 *         right one; write-offs > 10% of free stock at full-ish coverage → required too; under both
 *         thresholds → not required.
 *   f6  — write-offs without the full-coverage attestation → 409, nothing changes.
 *   f7  — the expected set holds only physically present statuses (no delivered / with_courier / lost).
 *   f8  — everything counted → 'nothing_to_push', pushed_at NULL, no job.
 *   g1  — found piece whose write-off was pushed → lost → available back at the location, +1 increment.
 *   g2  — found piece whose write-off push failed → available, NO +1.
 *   h1  — unlinked store finalizes with a write-off → push held (failed) → link + seed → the push is
 *         superseded, Shopify gets only the seed delta, the push job then does nothing.
 *   x1  — cross-tenant on app_user: A's finalize never touches B's pieces; A's write-off applies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockTakeFinalizeAppliesTest {

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

    @MockBean JobScheduler         jobScheduler;
    @MockBean ShopifyGateway       shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;

    @Autowired JdbcTemplate                   jdbc;
    @Autowired PlatformTransactionManager     txm;
    @Autowired ObjectMapper                   mapper;
    @Autowired StockTakeService               stockTake;
    @Autowired StockTakeReconciliationService reconciliation;
    @Autowired StockTakeShopifyPushJob        pushJob;
    @Autowired ShopifyInventoryService        shopifyInventory;
    @Autowired ShopifyInventoryReconcileService seed;
    @Autowired AuditService                   auditService;

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
        when(shopifyGateway.fetchAvailableQuantities(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ShopifyGateway.InventoryLevel> out = new ArrayList<>();
            for (Object item : (List<?>) inv.getArgument(3)) out.add(new ShopifyGateway.InventoryLevel((String) item, 0));
            return out;
        });
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── f1: the demo-shaped session ───────────────────────────────────────────────

    @Test
    void f1_appliesTheCount_pushesAvailableWriteOffsOnly() throws Exception {
        T t = tenant("f1", true);
        V a = variant(t, "f1a"), b = variant(t, "f1b");
        List<String> aPieces = pieces(t, a, 10, "available");
        String aDamagedOnScan = piece(t, a, "available");
        List<String> bDamaged = pieces(t, b, 2, "damaged");
        String reserved = piece(t, a, "reserved");
        String bench = piece(t, a, "return_pending_inspection");
        UUID s = open(t);
        for (int i = 0; i < 8; i++) scan(t, s, aPieces.get(i), "good");      // A: 2 of 10 unscanned
        scan(t, s, aDamagedOnScan, "damaged");                                // condition correction
        scan(t, s, bDamaged.get(0), "damaged");                               // B: 1 of 2 unscanned
        attest(t, s);

        Map<String, Object> plan = finalizePlan(t, s);
        assertThat(plan).containsEntry("writeOffs", 3).containsEntry("damageCorrections", 1)
            .containsEntry("shopifyDecrement", 2).containsEntry("requiresTypedConfirmation", true);

        finalize(t, s, 3);

        assertThat(status(aPieces.get(8))).isEqualTo("lost");
        assertThat(status(aPieces.get(9))).isEqualTo("lost");
        assertThat(status(bDamaged.get(1))).isEqualTo("lost");
        assertThat(status(aDamagedOnScan)).isEqualTo("damaged");
        assertThat(status(reserved)).as("committed — never written off").isEqualTo("reserved");
        assertThat(status(bench)).as("returns bench — not free stock").isEqualTo("return_pending_inspection");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM piece_events WHERE tenant_id = ? AND to_status = 'lost' " +
            "AND metadata->>'reason' = 'stock_take_missing' AND metadata->>'session_id' = ?", Integer.class, t.tenant(), s.toString()))
            .isEqualTo(3);
        assertThat(claim(s)).containsEntry("status", "pending");
        assertThat(jdbc.queryForObject("SELECT payload->'deltas'::text FROM stock_take_shopify_syncs WHERE session_id = ?",
            String.class, s)).isEqualTo("{\"" + a.id() + "\": 2}");

        assertThat(enqueued).hasSize(1);
        enqueued.get(0).run();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(shopifyGateway).pushStockTakeWriteOff(any(), any(), deltas.capture(), eq(t.traced()), any(), any());
        assertThat(deltas.getValue()).hasSize(1);
        assertThat(deltas.getValue().get(0).negativeDelta()).isEqualTo(-2);
        assertThat(claim(s)).containsEntry("status", "pushed");
        assertThat(claim(s).get("pushed_at")).isNotNull();
        verify(shopifyGateway, timeout(5000)).moveAvailableToDamaged(any(), any(), eq(a.item()), any(), eq(1), any(), any());
    }

    // ── f2: zero scans ────────────────────────────────────────────────────────────

    @Test
    void f2_zeroScans_refused_nothingChanges() {
        T t = tenant("f2", true);
        V a = variant(t, "f2");
        List<String> ps = pieces(t, a, 5, "available");
        UUID s = open(t);
        attest(t, s);

        assertThatThrownBy(() -> finalize(t, s, 5))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("ZERO_SCANS");
        assertThat(ps).allSatisfy(p -> assertThat(status(p)).isEqualTo("available"));
        assertThat(sessionStatus(s)).isEqualTo("open");
        assertThat(claimCount(s)).isZero();
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
    }

    // ── f3: drift guard ───────────────────────────────────────────────────────────

    @Test
    void f3_pieceMovedSinceOpen_skipped() {
        T t = tenant("f3", true);
        V a = variant(t, "f3");
        List<String> ps = pieces(t, a, 10, "available");
        UUID s = open(t);
        for (int i = 0; i < 9; i++) scan(t, s, ps.get(i), "good");
        attest(t, s);
        jdbc.update("UPDATE pieces SET status = 'reserved' WHERE id = ?", ps.get(9));   // picked mid-count

        Map<String, Object> result = finalize(t, s, null);

        assertThat(result).containsEntry("writeOffs", 0).containsEntry("driftSkipped", 1);
        assertThat(status(ps.get(9))).isEqualTo("reserved");
        assertThat(claim(s)).containsEntry("status", "nothing_to_push");
    }

    // ── f4: rolled-back finalize ──────────────────────────────────────────────────

    @Test
    void f4_rolledBack_noEvents_noClaim_noJob() {
        T t = tenant("f4", true);
        V a = variant(t, "f4");
        List<String> ps = pieces(t, a, 10, "available");
        UUID s = open(t);
        for (int i = 0; i < 9; i++) scan(t, s, ps.get(i), "good");
        attest(t, s);

        TenantContext.runAs(t.tenant(), () -> new TransactionTemplate(txm).executeWithoutResult(st -> {
            reconciliation.finalizeSession(s, t.user(), null);
            st.setRollbackOnly();
        }));

        assertThat(status(ps.get(9))).isEqualTo("available");
        assertThat(sessionStatus(s)).isEqualTo("open");
        assertThat(claimCount(s)).isZero();
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
    }

    // ── f5: typed confirmation thresholds ─────────────────────────────────────────

    @Test
    void f5_typedConfirmation_lowCoverage() {
        T t = tenant("f5a", true);
        V a = variant(t, "f5a");
        List<String> ps = pieces(t, a, 10, "available");
        UUID s = open(t);
        for (int i = 0; i < 7; i++) scan(t, s, ps.get(i), "good");          // 70% < 80%
        attest(t, s);

        assertThat(finalizePlan(t, s)).containsEntry("requiresTypedConfirmation", true).containsEntry("writeOffs", 3);
        assertThatThrownBy(() -> finalize(t, s, null)).hasMessageContaining("CONFIRMATION_REQUIRED");
        assertThatThrownBy(() -> finalize(t, s, 2)).hasMessageContaining("CONFIRMATION_REQUIRED");
        assertThat(status(ps.get(9))).isEqualTo("available");

        finalize(t, s, 3);
        assertThat(status(ps.get(9))).isEqualTo("lost");
    }

    @Test
    void f5b_typedConfirmation_writeOffShare_andNotRequiredUnderBoth() {
        // 20 free, 17 scanned = 85% coverage (≥ 80%) but 3 write-offs = 15% (> 10%) → required.
        T t = tenant("f5b", true);
        V a = variant(t, "f5b");
        List<String> ps = pieces(t, a, 20, "available");
        UUID s = open(t);
        for (int i = 0; i < 17; i++) scan(t, s, ps.get(i), "good");
        attest(t, s);
        assertThat(finalizePlan(t, s)).containsEntry("requiresTypedConfirmation", true);
        assertThatThrownBy(() -> finalize(t, s, null)).hasMessageContaining("CONFIRMATION_REQUIRED");

        // 20 free, 19 scanned = 95%, 1 write-off = 5% → not required.
        T u = tenant("f5c", true);
        V b = variant(u, "f5c");
        List<String> qs = pieces(u, b, 20, "available");
        UUID s2 = open(u);
        for (int i = 0; i < 19; i++) scan(u, s2, qs.get(i), "good");
        attest(u, s2);
        assertThat(finalizePlan(u, s2)).containsEntry("requiresTypedConfirmation", false);
        finalize(u, s2, null);
        assertThat(status(qs.get(19))).isEqualTo("lost");
    }

    // ── f6: attestation still required for write-offs ────────────────────────────

    @Test
    void f6_writeOffsWithoutAttestation_refused() {
        T t = tenant("f6", true);
        V a = variant(t, "f6");
        List<String> ps = pieces(t, a, 10, "available");
        UUID s = open(t);
        for (int i = 0; i < 9; i++) scan(t, s, ps.get(i), "good");

        assertThatThrownBy(() -> finalize(t, s, null)).hasMessageContaining("ATTESTATION_REQUIRED");
        assertThat(status(ps.get(9))).isEqualTo("available");
        assertThat(sessionStatus(s)).isEqualTo("open");
    }

    // ── f7: expected set = physically present ─────────────────────────────────────

    @Test
    void f7_expectedSet_onlyPhysicallyPresentStatuses() {
        T t = tenant("f7", true);
        V a = variant(t, "f7");
        for (String st : List.of("available", "damaged", "on_hold", "reserved", "packed", "awaiting_pickup",
                "return_pending_inspection", "with_courier", "delivered", "lost", "destroyed")) {
            piece(t, a, st);
        }
        UUID s = open(t);
        assertThat(jdbc.queryForList("SELECT status_at_open FROM stock_take_expected WHERE session_id = ? ORDER BY 1",
            String.class, s)).containsExactly("available", "awaiting_pickup", "damaged", "on_hold", "packed",
                "reserved", "return_pending_inspection");
    }

    // ── f8: nothing to push ───────────────────────────────────────────────────────

    @Test
    void f8_everythingCounted_nothingToPush() {
        T t = tenant("f8", true);
        V a = variant(t, "f8");
        List<String> ps = pieces(t, a, 3, "available");
        UUID s = open(t);
        ps.forEach(p -> scan(t, s, p, "good"));
        attest(t, s);

        finalize(t, s, null);

        assertThat(claim(s)).containsEntry("status", "nothing_to_push").containsEntry("pushed_at", null);
        verify(jobScheduler, never()).enqueue(any(JobLambda.class));
    }

    // ── g1/g2: found pieces ───────────────────────────────────────────────────────

    @Test
    void g1_foundPiece_writeOffWasPushed_plusOne() throws Exception {
        T t = tenant("g1", true);
        V a = variant(t, "g1");
        List<String> ps = pieces(t, a, 3, "available");
        UUID s1 = open(t);
        scan(t, s1, ps.get(0), "good"); scan(t, s1, ps.get(1), "good");
        attest(t, s1);
        finalize(t, s1, 1);                                   // ps[2] written off
        enqueued.get(0).run();                                // the push applies
        assertThat(claim(s1)).containsEntry("status", "pushed");

        UUID s2 = open(t);
        ps.forEach(p -> scan(t, s2, p, "good"));              // ps[2] turns up
        attest(t, s2);
        Map<String, Object> plan = finalizePlan(t, s2);
        assertThat(plan).containsEntry("founds", 1).containsEntry("foundIncrements", 1L);
        finalize(t, s2, null);

        assertThat(status(ps.get(2))).isEqualTo("available");
        assertThat(jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, ps.get(2)))
            .isEqualTo(t.location());
        String key = ShopifyGateway.idempotencyKey(t.tenant(), "stock_take_found", ps.get(2) + ":" + s2, a.id(), t.location());
        verify(shopifyGateway, timeout(5000)).adjustInventoryQuantities(eq(t.shop()), any(), eq(a.item()), eq(t.traced()),
            eq(1), eq("correction"), eq(key));
        awaitClaim(t, "stock_take_found", "applied");
    }

    @Test
    void g2_foundPiece_writeOffNeverReachedShopify_noPlusOne() throws Exception {
        T t = tenant("g2", true);
        V a = variant(t, "g2");
        List<String> ps = pieces(t, a, 3, "available");
        UUID s1 = open(t);
        scan(t, s1, ps.get(0), "good"); scan(t, s1, ps.get(1), "good");
        attest(t, s1);
        finalize(t, s1, 1);
        doThrow(new ShopifyException("rejected")).when(shopifyGateway)
            .pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> enqueued.get(0).run()).isInstanceOf(ShopifyException.class);
        assertThat(claim(s1)).containsEntry("status", "failed");

        UUID s2 = open(t);
        ps.forEach(p -> scan(t, s2, p, "good"));
        attest(t, s2);
        assertThat(finalizePlan(t, s2)).containsEntry("founds", 1).containsEntry("foundIncrements", 0L);
        finalize(t, s2, null);

        assertThat(status(ps.get(2))).isEqualTo("available");
        Thread.sleep(1000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'stock_take_found'", Integer.class, t.tenant())).as("no +1 claim").isZero();
        verify(shopifyGateway, never()).adjustInventoryQuantities(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── h1: an unlinked store's write-off push is superseded by the seed ──────────

    @Test
    void h1_unlinkedStore_writeOffPushHeld_seedSupersedesIt() throws Exception {
        T t = tenant("h1", false);
        V a = variant(t, "h1");
        List<String> ps = pieces(t, a, 5, "available");
        UUID s = open(t);
        for (int i = 0; i < 4; i++) scan(t, s, ps.get(i), "good");
        attest(t, s);
        finalize(t, s, 1);
        assertThatThrownBy(() -> enqueued.get(0).run()).isInstanceOf(IllegalStateException.class);  // not linked
        assertThat(claim(s)).containsEntry("status", "failed");

        link(t);
        ShopifyInventoryReconcileService.ApplyResult result = TenantContext.runAs(t.tenant(), () -> seed.apply(null));

        assertThat(result.seeded()).isEqualTo(1);
        assertThat(claim(s)).containsEntry("status", "superseded_by_seed");
        enqueued.get(0).run();                                                 // a JobRunr retry: no-op now
        verify(shopifyGateway, never()).pushStockTakeWriteOff(any(), any(), any(), any(), any(), any());
        verify(shopifyGateway, times(1)).adjustInventoryQuantities(any(), any(), eq(a.item()), eq(t.traced()),
            eq(4), any(), any());
        assertThat(claim(s)).containsEntry("status", "superseded_by_seed");
    }

    // ── x1: cross-tenant on app_user ──────────────────────────────────────────────

    @Test
    void x1_appUser_finalizeForANeverTouchesB() {
        T a = tenant("x1a", true), b = tenant("x1b", true);
        V va = variant(a, "x1a"), vb = variant(b, "x1b");
        List<String> pa = pieces(a, va, 10, "available");
        List<String> pb = pieces(b, vb, 10, "available");
        UUID sa = open(a);
        for (int i = 0; i < 9; i++) scan(a, sa, pa.get(i), "good");
        attest(a, sa);
        UUID sb = open(b);
        scan(b, sb, pb.get(0), "good");
        attest(b, sb);

        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        DataSourceTransactionManager appTxm = new DataSourceTransactionManager(appDs);
        InventoryLedger appLedger = new InventoryLedger(appJdbc);
        AuditService appAudit = new AuditService(appJdbc, mapper);
        StockTakeReconciliationService appReconciliation = new StockTakeReconciliationService(appJdbc,
            new StockTakeService(appJdbc, mapper), appLedger,
            new PieceAdjustService(appJdbc, appLedger, appAudit, mapper, shopifyInventory),
            appAudit, mapper, jobScheduler, pushJob, shopifyInventory);

        TenantContext.runAs(a.tenant(), () -> new TransactionTemplate(appTxm).execute(st ->
            appReconciliation.finalizeSession(sa, a.user(), null)));

        assertThat(status(pa.get(9))).as("same-tenant positive control").isEqualTo("lost");
        assertThat(pb).as("B's pieces untouched").allSatisfy(p -> assertThat(status(p)).isEqualTo("available"));
        assertThat(sessionStatus(sb)).isEqualTo("open");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private T tenant(String name, boolean linked) {
        UUID tenant = UUID.randomUUID(), store = UUID.randomUUID(), location = UUID.randomUUID(), user = UUID.randomUUID();
        String shop = name + "-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        String traced = "gid://shopify/Location/" + shop;
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
        return new T(tenant, store, shop, location, traced, user);
    }

    private void link(T t) {
        jdbc.update("UPDATE locations SET shopify_sync_status = 'linked', shopify_location_id = ? WHERE id = ?",
            t.traced(), t.location());
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

    private String piece(T t, V v, String status) {
        int k = seq.incrementAndGet();
        String id = String.format("01STFINALIZE%014d", k);
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id) " +
            "VALUES (?, ?, ?, ?::piece_status, ?, ?, ?)", id, t.tenant(), v.id(), status, "SF-" + k,
            String.format("F%07d", k), t.location());
        return id;
    }

    private List<String> pieces(T t, V v, int n, String status) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(piece(t, v, status));
        return out;
    }

    private UUID open(T t) {
        return TenantContext.runAs(t.tenant(), () ->
            (UUID) stockTake.openSession("all", null, t.location(), null, t.user()).get("sessionId"));
    }

    private void scan(T t, UUID s, String pieceId, String condition) {
        TenantContext.runAs(t.tenant(), () -> stockTake.scan(s, pieceId, condition, t.user()));
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

    private String status(String pieceId) {
        return jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieceId);
    }

    private String sessionStatus(UUID s) {
        return jdbc.queryForObject("SELECT status FROM stock_take_sessions WHERE id = ?", String.class, s);
    }

    private Map<String, Object> claim(UUID s) {
        return jdbc.queryForMap("SELECT status, pushed_at FROM stock_take_shopify_syncs WHERE session_id = ?", s);
    }

    private int claimCount(UUID s) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_take_shopify_syncs WHERE session_id = ?", Integer.class, s);
    }

    private void awaitClaim(T t, String triggerType, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = ? AND status = ?", Integer.class, t.tenant(), triggerType, status) == 0) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError(triggerType + " never reached " + status);
            Thread.sleep(25);
        }
    }
}
