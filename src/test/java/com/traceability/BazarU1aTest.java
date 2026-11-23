package com.traceability;

import com.traceability.bazar.BazarDayService;
import com.traceability.bazar.BazarEndService;
import com.traceability.bazar.BazarSaleService;
import com.traceability.bazar.BazarService;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.integrations.shopify.StoreRepository;
import com.traceability.inventory.MainReconcileAlerter;
import com.traceability.inventory.MainReconcileService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * U1a (2026-11-22 E2E, prod test20) — Bazar must-fix bugs 1 and 2, through the real bazar services, Shopify mocked.
 *
 *   q1 KEEP mode: every sale / cart / undo / undo receipt / return (good, damaged) / exchange / lost / close queues its
 *      variant(s) for Main reconcile (pending_since set, pending_source bazar:*, last_event_at untouched); scan-back
 *      good and bring-back by count don't; the return-damaged piece gets condition 'damaged'
 *   q2 REMOVE mode: none of those events queues anything (the send's own claim trigger aside — cleared first)
 *   b1 KEEP, 30 sales across 3 variants in one confirm window → the queued run sees them, the confirming run writes
 *      all three (−10 each), NOT held by the brake, no alert
 *   b2 a non-bazar 30-unit drift is still held (+ alert) — brake unchanged
 *   b3 REMOVE-mode bazar sales never exempt: a 30-unit difference on a Remove bazar's variants is still held
 *   d1 close brings the damaged pieces nobody scanned home: at main, still damaged, condition damaged, event
 *      bazar_damaged_brought_back {"via":"transfer_close"}, damaged_at_bazar 0, no Shopify mutation
 *
 * Main reconcile runs on its own instance on an app_user connection (RLS), write mode, confirm window 0.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazarU1aTest {

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
    static final Set<String> READS = Set.of("fetchMainLevels", "fetchMainLevelsPage", "resolveInventoryItemIds",
        "inactiveMainLevels");

    @Autowired JdbcTemplate     jdbc;
    @Autowired BazarService     bazar;
    @Autowired BazarSaleService sales;
    @Autowired BazarDayService  days;
    @Autowired BazarEndService  end;
    @Autowired com.traceability.inventory.MainReconcileQueue queue;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;

    final AtomicInteger seq = new AtomicInteger();
    final Map<String, ShopifyGateway.MainLevel> shopifyMain = new HashMap<>();
    UUID tenant, owner, main, product;
    String traced, shop;
    BazarSaleService.Actor actor;
    JdbcTemplate appJdbc;
    DataSourceTransactionManager appTxm;
    MainReconcileAlerter alerter;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID();
        owner = UUID.randomUUID();
        main = UUID.randomUUID();
        product = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        shop = "bazar-u1a-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        traced = "gid://shopify/Location/" + (91_000_000L + Math.abs(tenant.hashCode() % 1000));
        jdbc.update("INSERT INTO tenants (id, name, bazar_enabled) VALUES (?, 'Bazar U1a', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'h', 'owner')",
            owner, tenant, "owner-" + tenant + "@bazar-u1a.test");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, " +
            "shopify_sync_status) VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true, ?, 'linked')", main, tenant, traced);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Dress', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        actor = new BazarSaleService.Actor(owner, "owner");
        DataSource appDs = new TenantAwareDataSource(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw"));
        appJdbc = new JdbcTemplate(appDs);
        appTxm = new DataSourceTransactionManager(appDs);
    }

    @BeforeEach
    void reset() {
        Mockito.reset(shopify, tokenProvider);
        shopifyMain.clear();
        alerter = mock(MainReconcileAlerter.class);
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopify.resolveInventoryItemIds(any(), any(), anyList())).thenReturn(Map.of());
        when(shopify.inactiveMainLevels(any(), any(), any(), anyList())).thenReturn(Set.of());
        when(shopify.fetchMainLevels(any(), any(), any(), anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(3);
            List<ShopifyGateway.MainLevel> out = new ArrayList<>();
            for (String id : ids) if (shopifyMain.containsKey(id)) out.add(shopifyMain.get(id));
            return out;
        });
        doAnswer(inv -> { move(inv.getArgument(2), inv.getArgument(4)); return null; })
            .when(shopify).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        TenantContext.set(tenant);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    // ── q1 / q2 ──────────────────────────────────────────────────────────────

    @Test
    void q1_keep_everyBazarEventQueuesItsVariant_conditionDamaged() {
        UUID a = variant(), b = variant(), c = variant();
        List<String> pa = pieces(a, 8), pb = pieces(b, 2), pc = pieces(c, 3);
        pieces(b, 1, false);   // an untagged one — brought back by count
        Map<String, Object> ev = live("leave", List.of(a, b, c), List.of(8, 3, 3));
        UUID eventId = (UUID) ev.get("id");
        clearQueue();

        Map<String, Object> s1 = sales.sell(eventId, code(pa.get(0)), "q1-s1", "cash", actor);
        assertQueued(a, "bazar:sale");
        sales.undo(eventId, (UUID) s1.get("id"), actor);
        assertQueued(a, "bazar:undo");
        sales.completeCart(eventId, List.of(code(pa.get(1)), code(pb.get(0))), null, "cash", null, null, "q1-cart", actor);
        assertQueued(a, "bazar:sale"); assertQueued(b, "bazar:sale");
        UUID receipt = jdbc.queryForObject("SELECT id FROM bazar_receipts WHERE client_request_id = 'q1-cart'", UUID.class);
        sales.undoReceipt(eventId, receipt, actor);
        assertQueued(a, "bazar:undo"); assertQueued(b, "bazar:undo");
        sales.sell(eventId, code(pa.get(2)), "q1-s2", "cash", actor);
        clearQueue();
        sales.returnSale(eventId, code(pa.get(2)), null, "good", "q1-r2", actor);
        assertQueued(a, "bazar:return");
        sales.sell(eventId, code(pa.get(3)), "q1-s3", "cash", actor);
        clearQueue();
        sales.returnSale(eventId, code(pa.get(3)), null, "damaged", "q1-r3", actor);
        assertQueued(a, "bazar:return");
        assertThat(jdbc.queryForObject("SELECT condition FROM pieces WHERE id = ?", String.class, pa.get(3))).isEqualTo("damaged");
        sales.sell(eventId, code(pa.get(4)), "q1-s4", "cash", actor);
        clearQueue();
        sales.exchange(eventId, code(pa.get(4)), null, code(pc.get(0)), null, "cash", "good", "q1-x4", actor);
        assertQueued(a, "bazar:");   // the old piece back (return) and the new one sold — both variants queued
        assertQueued(c, "bazar:");
        closeToday(eventId, "q1-close");
        end.end(eventId, actor);

        clearQueue();
        end.scanBack(eventId, code(pa.get(5)), "q1-b5", actor);             // good leftover: not queued
        end.bringBackByCount(eventId, b, 1, "q1-bc", actor);               // by count: not queued
        assertNotQueued(a); assertNotQueued(b);
        end.markLost(eventId, List.of(pa.get(6)), actor);
        assertQueued(a, "bazar:lost");
        // the rest resolved so the transfer can close
        List<String> outstanding = jdbc.queryForList("SELECT piece_id FROM transfer_pieces WHERE transfer_id = ? AND outcome IS NULL",
            String.class, ev.get("transfer_id"));
        if (!outstanding.isEmpty()) end.markLost(eventId, outstanding, actor);
        clearQueue();
        end.closeTransfer(eventId, actor);
        assertQueued(a, "bazar:close"); assertQueued(b, "bazar:close"); assertQueued(c, "bazar:close");
        assertThat(jdbc.queryForList("SELECT last_event_at FROM main_reconcile_variant_state WHERE tenant_id = ? " +
            "AND variant_id IN (?, ?, ?)", tenant, a, b, c)).allMatch(r -> r.get("last_event_at") == null);
        assertNoMutation(a, b, c);
    }

    @Test
    void q2_remove_noBazarEventQueuesAnything() throws Exception {
        UUID a = variant(), b = variant();
        List<String> pa = pieces(a, 5), pb = pieces(b, 2);
        Map<String, Object> ev = live("remove", List.of(a, b), List.of(5, 2));
        UUID eventId = (UUID) ev.get("id");
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, ev.get("transfer_id"));
        clearQueue();

        Map<String, Object> s1 = sales.sell(eventId, code(pa.get(0)), "q2-s1", "cash", actor);
        sales.undo(eventId, (UUID) s1.get("id"), actor);
        sales.completeCart(eventId, List.of(code(pa.get(1)), code(pb.get(0))), null, "cash", null, null, "q2-cart", actor);
        sales.sell(eventId, code(pa.get(2)), "q2-s2", "cash", actor);
        sales.returnSale(eventId, code(pa.get(2)), null, "damaged", "q2-r2", actor);
        sales.sell(eventId, code(pa.get(3)), "q2-s3", "cash", actor);
        sales.exchange(eventId, code(pa.get(3)), null, code(pb.get(1)), null, "cash", "good", "q2-x3", actor);
        closeToday(eventId, "q2-close");
        end.end(eventId, actor);
        List<String> outstanding = jdbc.queryForList("SELECT piece_id FROM transfer_pieces WHERE transfer_id = ? AND outcome IS NULL",
            String.class, ev.get("transfer_id"));
        end.markLost(eventId, outstanding, actor);
        end.closeTransfer(eventId, actor);
        assertNotQueued(a); assertNotQueued(b);
        assertThat(count("SELECT COUNT(*) FROM main_reconcile_variant_state WHERE tenant_id = ? AND pending_source LIKE 'bazar:%'",
            tenant)).isZero();
    }

    // ── b1 / b2 / b3 — the brake ─────────────────────────────────────────────

    @Test
    void b1_keep_30SalesInOneWindow_writtenNotHeld_noAlert() {
        UUID a = variant(), b = variant(), c = variant();
        List<UUID> vs = List.of(a, b, c);
        for (UUID v : vs) pieces(v, 12);
        for (UUID v : vs) level(v, 12);   // Keep: Shopify still has all 12
        Map<String, Object> ev = live("leave", vs, List.of(10, 10, 10));
        UUID eventId = (UUID) ev.get("id");
        clearQueue();
        for (UUID v : vs) for (int i = 0; i < 10; i++) sales.sell(eventId, null, v, "b1-" + v + "-" + i, "cash", actor);
        for (UUID v : vs) assertQueued(v, "bazar:sale");

        MainReconcileService s = writer();
        // Fully explained by Keep-bazar sales → written on the first queued run (fast path), and never braked.
        UUID first = TenantContext.runAs(tenant, () -> s.runQueued(tenant));
        assertThat(first).as("the queue drives a run").isNotNull();
        for (UUID v : vs) assertThat(diff(first, v)).containsEntry("delta", -10).containsEntry("status", "confirmed")
            .containsEntry("note", "keep_bazar_fast");
        assertThat(runRow(first)).containsEntry("status", "completed").containsEntry("writes_applied", 3);
        assertThat(runRow(first).get("skip_reason")).isNull();
        assertThat(runRow(first)).containsEntry("brake_units_down", 0);
        for (UUID v : vs) {
            verify(shopify, times(1)).pushMainReconcile(any(), any(), eq(item(v)), eq(traced), eq(-10), anyInt(), any(), any());
        }
        verifyNoInteractions(alerter);
    }

    @Test
    void b2_nonBazar_30UnitDrift_stillHeld() {
        UUID a = variant(), b = variant(), c = variant();
        List<UUID> vs = List.of(a, b, c);
        for (UUID v : vs) { pieces(v, 2); level(v, 12); }   // Shopify 10 too high each, no bazar
        MainReconcileService s = writer();
        TenantContext.runAs(tenant, () -> s.runVariants(tenant, vs));
        UUID second = TenantContext.runAs(tenant, () -> s.runVariants(tenant, vs));
        assertThat(runRow(second)).containsEntry("status", "skipped").containsEntry("skip_reason", "large_change_held")
            .containsEntry("brake_units_down", 30);
        verify(shopify, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
        verify(alerter, times(1)).alert(anyString(), anyString());
    }

    @Test
    void b3_remove_bazarSalesNeverExempt_heldAsBefore() throws Exception {
        UUID a = variant(), b = variant(), c = variant();
        List<UUID> vs = List.of(a, b, c);
        for (UUID v : vs) { pieces(v, 12); level(v, 12); }
        // The send's −N "applied" in Traced but this mock never lowers Shopify → a 30-unit difference on a Remove bazar.
        Map<String, Object> ev = live("remove", vs, List.of(10, 10, 10));
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, ev.get("transfer_id"));
        UUID eventId = (UUID) ev.get("id");
        for (UUID v : vs) for (int i = 0; i < 10; i++) sales.sell(eventId, null, v, "b3-" + v + "-" + i, "cash", actor);
        MainReconcileService s = writer();
        TenantContext.runAs(tenant, () -> s.runVariants(tenant, vs));
        UUID second = TenantContext.runAs(tenant, () -> s.runVariants(tenant, vs));
        assertThat(runRow(second)).containsEntry("status", "skipped").containsEntry("skip_reason", "large_change_held")
            .containsEntry("brake_units_down", 30);
        verify(shopify, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    // ── f1 – f4 — the Keep-bazar fast path (no two-run wait for what Keep-bazar moves explain) ─────────────

    /**
     * A size selling every 3 min for 30 min: each sale is written on the first queued run after it (prod: 60 s
     * debounce + the every-minute queue job → 1–2 min), with the real 10-minute confirm window. Before the fast path
     * every sale restarted the two-run chain and nothing was written until the selling stopped.
     */
    @Test
    void f1_keep_sizeSellingEvery3Min_dropsOnTheNextQueuedRunAfterEachSale() {
        UUID a = variant();
        pieces(a, 12);
        level(a, 12);
        Map<String, Object> ev = live("leave", List.of(a), List.of(10));
        UUID eventId = (UUID) ev.get("id");
        MainReconcileService s = writer(10);
        for (int i = 1; i <= 10; i++) {
            clearQueue();
            sales.sell(eventId, null, a, "f1-" + i, "cash", actor);
            assertQueued(a, "bazar:sale");
            UUID run = TenantContext.runAs(tenant, () -> s.runQueued(tenant));
            assertThat(run).as("sale %d drives a queued run", i).isNotNull();
            assertThat(diff(run, a)).as("sale %d", i).containsEntry("delta", -1).containsEntry("status", "confirmed")
                .containsEntry("note", "keep_bazar_fast");
            assertThat(shopifyMain.get(item(a)).onHand()).as("Shopify after sale %d", i).isEqualTo(12 - i);
        }
        verify(shopify, times(10)).pushMainReconcile(any(), any(), eq(item(a)), eq(traced), eq(-1), anyInt(), any(), any());
        // In line now: a further run writes nothing.
        UUID after = TenantContext.runAs(tenant, () -> s.runVariants(tenant, List.of(a)));
        assertThat(runRow(after)).containsEntry("writes_applied", 0);
        verifyNoInteractions(alerter);
    }

    /**
     * Keep-bazar sales plus an unexplained drift: the explained part is written at once, the remainder waits for the
     * two-run rule — its chain survives the partial write — and is written only once 10 minutes have passed.
     */
    @Test
    void f2_keep_unexplainedRemainder_stillWaitsForTwoRuns() {
        UUID a = variant();
        pieces(a, 12);
        level(a, 14);   // Shopify 2 too high for reasons no bazar explains
        Map<String, Object> ev = live("leave", List.of(a), List.of(6));
        UUID eventId = (UUID) ev.get("id");
        for (int i = 0; i < 3; i++) sales.sell(eventId, null, a, "f2-" + i, "cash", actor);
        MainReconcileService s = writer(10);

        UUID first = TenantContext.runAs(tenant, () -> s.runVariants(tenant, List.of(a)));
        assertThat(diff(first, a)).containsEntry("delta", -5).containsEntry("status", "confirmed")
            .containsEntry("note", "keep_bazar_fast");
        verify(shopify, times(1)).pushMainReconcile(any(), any(), eq(item(a)), eq(traced), eq(-3), anyInt(), any(), any());
        assertThat(shopifyMain.get(item(a)).onHand()).isEqualTo(11);
        Map<String, Object> st = state(a);
        assertThat(st).as("the remainder's chain is kept across the write").containsEntry("last_delta", -2);
        assertThat(st.get("delta_since")).isNotNull();

        UUID second = TenantContext.runAs(tenant, () -> s.runVariants(tenant, List.of(a)));
        assertThat(diff(second, a)).containsEntry("delta", -2).containsEntry("status", "unconfirmed");
        assertThat(diff(second, a).get("note")).isNull();
        assertThat(state(a).get("delta_since")).as("same chain").isEqualTo(st.get("delta_since"));
        verify(shopify, times(1)).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());

        jdbc.update("UPDATE main_reconcile_variant_state SET delta_since = delta_since - interval '11 minutes' " +
            "WHERE tenant_id = ? AND variant_id = ?", tenant, a);
        UUID third = TenantContext.runAs(tenant, () -> s.runVariants(tenant, List.of(a)));
        assertThat(diff(third, a)).containsEntry("delta", -2).containsEntry("status", "confirmed");
        assertThat(diff(third, a).get("note")).isNull();
        verify(shopify, times(1)).pushMainReconcile(any(), any(), eq(item(a)), eq(traced), eq(-2), anyInt(), any(), any());
        assertThat(shopifyMain.get(item(a)).onHand()).isEqualTo(9);
    }

    /** No bazar at all: a drift is never fast — two runs inside 10 minutes write nothing. */
    @Test
    void f3_nonBazarDrift_neverFast_waitsForTwoRuns() {
        UUID a = variant();
        pieces(a, 4);
        level(a, 6);
        MainReconcileService s = writer(10);
        UUID first = TenantContext.runAs(tenant, () -> s.runVariants(tenant, List.of(a)));
        UUID second = TenantContext.runAs(tenant, () -> s.runVariants(tenant, List.of(a)));
        for (UUID run : List.of(first, second)) {
            assertThat(diff(run, a)).containsEntry("delta", -2).containsEntry("status", "unconfirmed");
            assertThat(diff(run, a).get("note")).isNull();
        }
        verify(shopify, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    /** Remove mode: its bazar sales are never fast (the send already left Shopify) — two-run rule as before. */
    @Test
    void f4_remove_bazarSalesNeverFast() throws Exception {
        UUID a = variant();
        pieces(a, 12);
        level(a, 12);   // the send's −N "applied" in Traced but this mock never lowered Shopify
        Map<String, Object> ev = live("remove", List.of(a), List.of(5));
        await("SELECT COUNT(*) FROM transfer_shopify_syncs WHERE transfer_id = ? AND status = 'pushed'", 1, ev.get("transfer_id"));
        UUID eventId = (UUID) ev.get("id");
        for (int i = 0; i < 3; i++) sales.sell(eventId, null, a, "f4-" + i, "cash", actor);
        MainReconcileService s = writer(10);
        UUID first = TenantContext.runAs(tenant, () -> s.runVariants(tenant, List.of(a)));
        UUID second = TenantContext.runAs(tenant, () -> s.runVariants(tenant, List.of(a)));
        for (UUID run : List.of(first, second)) {
            assertThat(diff(run, a)).containsEntry("delta", -5).containsEntry("status", "unconfirmed");
            assertThat(diff(run, a).get("note")).isNull();
        }
        verify(shopify, never()).pushMainReconcile(any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    // ── c1 / c2 — the Keep-bazar max wait (3 min from the first pending mark) ────────────────────────────────

    /**
     * A size selling every 20 s for 10 min, with the real 60 s debounce and the every-minute queue job. Every sale
     * touches the mark, so the debounce alone never lets it run; the cap makes it due 3 min after its first pending
     * mark — run on the next job tick (3–4 min) — and Shopify follows each time. Virtual clock: each 20 s tick ages
     * the pending mark by 20 s before the next sale.
     */
    @Test
    void c1_keep_sizeSellingEvery20s_getsARunAtLeastEvery3Min_shopifyFollows() {
        UUID a = variant();
        pieces(a, 31);
        level(a, 31);
        Map<String, Object> ev = live("leave", List.of(a), List.of(30));
        UUID eventId = (UUID) ev.get("id");
        clearQueue();
        MainReconcileService s = writer(10);
        s.setDebounceSeconds(60);
        s.setKeepBazarMaxWaitSeconds(180);
        List<Integer> runAt = new ArrayList<>();
        int sold = 0;
        for (int tick = 1; tick <= 30; tick++) {           // t = tick * 20 s
            age(a, 20);
            sales.sell(eventId, null, a, "c1-" + tick, "cash", actor);
            sold++;
            assertQueued(a, "bazar:sale");
            if (tick % 3 == 0) {                            // the every-minute job
                UUID run = TenantContext.runAs(tenant, () -> s.runQueued(tenant));
                if (run != null) {
                    runAt.add(tick * 20);
                    assertThat(diff(run, a)).containsEntry("status", "confirmed").containsEntry("note", "keep_bazar_fast");
                    assertThat(shopifyMain.get(item(a)).onHand()).as("Shopify follows at t=%ds", tick * 20)
                        .isEqualTo(31 - sold);
                }
            }
        }
        assertThat(runAt).as("runs at %s", runAt).hasSizeGreaterThanOrEqualTo(2);
        int last = 0;
        for (int t : runAt) { assertThat(t - last).as("gap before t=%ds", t).isLessThanOrEqualTo(240); last = t; }
        assertThat(600 - last).as("the open mark at the end").isLessThan(240);
        verifyNoInteractions(alerter);
    }

    /** A webhook-queued variant (no bazar) keeps the plain debounce: 10 min pending but touched 30 s ago is not due. */
    @Test
    void c2_webhookQueuedVariant_debounceUnchanged() throws Exception {
        clearQueue();
        long n = 7_700_000_000L + seq.incrementAndGet();
        UUID w = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
            "VALUES (?, ?, ?, ?, 'W', ?, ?, 500.00)", w, tenant, product, "gid://shopify/ProductVariant/" + n,
            "SKU-W-" + n, item(w));
        queue.onWebhook(tenant, "orders/updated",
            new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"line_items\":[{\"variant_id\":" + n + "}]}"));
        assertQueued(w, "webhook:orders/updated");
        MainReconcileService s = writer(10);
        s.setDebounceSeconds(60);
        s.setKeepBazarMaxWaitSeconds(180);
        jdbc.update("UPDATE main_reconcile_variant_state SET pending_since = now() - interval '10 minutes', " +
            "pending_touched_at = now() - interval '30 seconds' WHERE tenant_id = ? AND variant_id = ?", tenant, w);
        assertThat(TenantContext.runAs(tenant, () -> s.runQueued(tenant))).as("not due: touched 30 s ago").isNull();
        assertQueued(w, "webhook:orders/updated");
        jdbc.update("UPDATE main_reconcile_variant_state SET pending_touched_at = now() - interval '61 seconds' " +
            "WHERE tenant_id = ? AND variant_id = ?", tenant, w);
        UUID run = TenantContext.runAs(tenant, () -> s.runQueued(tenant));
        assertThat(run).as("due after the 60 s debounce").isNotNull();
        assertNotQueued(w);
    }

    /** The virtual clock: the variant's pending mark becomes {@code seconds} older. */
    private void age(UUID v, int seconds) {
        jdbc.update("UPDATE main_reconcile_variant_state SET pending_since = pending_since - ? * interval '1 second', " +
            "pending_touched_at = pending_touched_at - ? * interval '1 second' " +
            "WHERE tenant_id = ? AND variant_id = ? AND pending_since IS NOT NULL", seconds, seconds, tenant, v);
    }

    private Map<String, Object> state(UUID v) {
        return jdbc.queryForMap("SELECT last_delta, delta_since FROM main_reconcile_variant_state " +
            "WHERE tenant_id = ? AND variant_id = ?", tenant, v);
    }

    // ── d1 ───────────────────────────────────────────────────────────────────

    @Test
    void d1_close_bringsUnscannedDamagedHome_conditionDamaged_noShopifyWrite() {
        UUID a = variant();
        List<String> pa = pieces(a, 3);
        Map<String, Object> ev = live("leave", List.of(a), List.of(3));
        UUID eventId = (UUID) ev.get("id");
        UUID hall = (UUID) ev.get("location_id");
        sales.sell(eventId, code(pa.get(0)), "d1-s0", "cash", actor);
        sales.returnSale(eventId, code(pa.get(0)), null, "damaged", "d1-r0", actor);
        sales.sell(eventId, code(pa.get(1)), "d1-s1", "cash", actor);
        closeToday(eventId, "d1-close");
        end.end(eventId, actor);
        end.scanBack(eventId, code(pa.get(2)), "d1-b2", actor);
        assertThat(location(pa.get(0))).isEqualTo(hall);
        @SuppressWarnings("unchecked") Map<String, Object> before = (Map<String, Object>) end.leftovers(eventId, actor).get("counts");
        assertThat(before.get("damaged_at_bazar")).isEqualTo(1);

        Map<String, Object> closed = end.closeTransfer(eventId, actor);
        assertThat(closed.get("transfer_status")).isEqualTo("closed");
        @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) closed.get("counts");
        assertThat(c.get("damaged_at_bazar")).isEqualTo(0);
        assertThat(location(pa.get(0))).isEqualTo(main);
        assertThat(jdbc.queryForMap("SELECT status::text AS status, condition FROM pieces WHERE id = ?", pa.get(0)))
            .containsEntry("status", "damaged").containsEntry("condition", "damaged");
        Map<String, Object> last = jdbc.queryForMap("SELECT event_type, metadata->>'via' AS via, from_status::text AS f, " +
            "to_status::text AS t FROM piece_events WHERE piece_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1", pa.get(0));
        assertThat(last).containsEntry("event_type", "bazar_damaged_brought_back").containsEntry("via", "transfer_close")
            .containsEntry("f", "damaged").containsEntry("t", "damaged");
        assertThat((Object) end.leftovers(eventId, actor).get("damaged_to_bring_home")).asList().isEmpty();
        assertNoMutation(a);
    }

    // ── l1 — bug 3 / 4 reads ─────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_list_piecesOut_days_needsClose() {
        UUID a = variant();
        List<String> pa = pieces(a, 3);
        Map<String, Object> ev = live("leave", List.of(a), List.of(3));
        UUID eventId = (UUID) ev.get("id");
        Map<String, Object> today = ((List<Map<String, Object>>) days.days(eventId, actor).get("days")).get(0);
        assertThat(today).containsEntry("needs_close", false);                       // no money yet
        sales.sell(eventId, code(pa.get(0)), "l1-s0", "cash", actor);
        today = ((List<Map<String, Object>>) days.days(eventId, actor).get("days")).get(0);
        assertThat(today).containsEntry("status", "open").containsEntry("needs_close", true);
        closeToday(eventId, "l1-close");
        today = ((List<Map<String, Object>>) days.days(eventId, actor).get("days")).get(0);
        assertThat(today).containsEntry("needs_close", false);
        end.end(eventId, actor);
        assertThat(listRow(eventId)).containsEntry("transfer_status", "reconciling").containsEntry("pieces_out", 2);
        end.markLost(eventId, List.of(pa.get(1)), actor);
        assertThat(listRow(eventId)).containsEntry("pieces_out", 1);
    }

    private Map<String, Object> listRow(UUID eventId) {
        return bazar.list().stream().filter(r -> eventId.equals(r.get("id"))).findFirst().orElseThrow();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MainReconcileService writer() { return writer(0); }

    private MainReconcileService writer(int confirmAfterMinutes) {
        MainReconcileService s = new MainReconcileService(appJdbc, appTxm, shopify, tokenProvider,
            new StoreRepository(appJdbc, appTxm));
        s.setReportOnly(false);
        s.setWriteShops("all");
        s.setWriteExclude("");
        s.setPart1Since("2026-11-01T00:00:00Z");
        s.setEnabled(true);
        s.setShops("");
        s.setConfirmAfterMinutes(confirmAfterMinutes);
        s.setPart1WindowDays(30);
        s.setDebounceSeconds(0);
        s.setQueueBatch(500);
        s.setAmbiguousHoldMinutes(60);
        s.setPendingStaleMinutes(15);
        s.setBrakeUnits(20);
        s.setBrakePercent(10);
        s.setBrakeRealertHours(24);
        s.setAlerter(alerter);
        return s;
    }

    /** A live bazar ('leave' / 'remove') with {@code counts[i]} pieces of {@code variants[i]} sent, priced 500. */
    private Map<String, Object> live(String mode, List<UUID> variants, List<Integer> counts) {
        Map<String, Object> ev = bazar.create(new BazarService.CreateEvent("U1a " + seq.incrementAndGet(),
            LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2), null, "U1a Hall " + seq.incrementAndGet(),
            List.of("cash"), List.of("owner"), mode, null, null), owner);
        UUID eventId = (UUID) ev.get("id");
        for (int i = 0; i < variants.size(); i++) {
            bazar.sendByCount(eventId, variants.get(i), counts.get(i), "send-" + eventId + "-" + i, owner);
        }
        bazar.markSent(eventId, owner);
        bazar.start(eventId, owner);
        return ev;
    }

    private UUID variant() {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id, price) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 500.00)", v, tenant, product, "gid://shopify/ProductVariant/" + v,
            "V" + seq.incrementAndGet(), "SKU-" + v.toString().substring(0, 8), item(v));
        return v;
    }

    private static String item(UUID v) { return "gid://shopify/InventoryItem/" + v; }

    /** n tagged pieces received at main the real way (their receipt's +1 applied). Oldest first. */
    private List<String> pieces(UUID v, int n) { return pieces(v, n, true); }

    private List<String> pieces(UUID v, int n, boolean tagged) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = String.format("01BAZARU1A%016d", seq.incrementAndGet());
            UUID receipt = UUID.randomUUID();
            jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)",
                receipt, tenant, owner, main);
            jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
                "trigger_type, trigger_id, payload, status) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, 'applied')",
                tenant, UUID.randomUUID(), v, main, receipt.toString());
            jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, receipt_id, " +
                "created_at) VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, ?, now() - interval '1 day' + ? * interval '1 second')",
                id, tenant, v, "BU-" + id, tagged ? "U" + (6000000 + seq.get()) : null, main, receipt, i);
            out.add(id);
        }
        return out;
    }

    private String code(String id) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, id);
    }

    private UUID location(String piece) {
        return jdbc.queryForObject("SELECT current_location_id FROM pieces WHERE id = ?", UUID.class, piece);
    }

    private void closeToday(UUID eventId, String crid) {
        LocalDate today = jdbc.queryForObject("SELECT (now() AT TIME ZONE 'Africa/Cairo')::date", LocalDate.class);
        days.close(eventId, today, new BazarDayService.Entered(new BigDecimal("100000"), null, null, null, BigDecimal.ZERO),
            null, crid, actor);
    }

    private void clearQueue() {
        jdbc.update("UPDATE main_reconcile_variant_state SET pending_since = NULL, pending_touched_at = NULL, " +
            "pending_source = NULL WHERE tenant_id = ?", tenant);
    }

    private void assertQueued(UUID v, String sourcePrefix) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT pending_since, pending_source FROM main_reconcile_variant_state " +
            "WHERE tenant_id = ? AND variant_id = ?", tenant, v);
        assertThat(r).as("queued %s", v).hasSize(1);
        assertThat(r.get(0).get("pending_since")).as("pending %s", v).isNotNull();
        assertThat((String) r.get(0).get("pending_source")).startsWith(sourcePrefix);
    }

    private void assertNotQueued(UUID v) {
        assertThat(count("SELECT COUNT(*) FROM main_reconcile_variant_state WHERE tenant_id = ? AND variant_id = ? " +
            "AND pending_since IS NOT NULL", tenant, v)).as("not queued %s", v).isZero();
    }

    private void level(UUID v, int onHand) {
        shopifyMain.put(item(v), new ShopifyGateway.MainLevel(item(v), true, onHand, onHand, 0, 0));
    }

    private void move(String item, int delta) {
        ShopifyGateway.MainLevel l = shopifyMain.get(item);
        if (l != null) shopifyMain.put(item, new ShopifyGateway.MainLevel(item, true, l.onHand() + delta,
            l.available() + delta, l.committed(), l.damaged()));
    }

    private Map<String, Object> runRow(UUID run) {
        return jdbc.queryForMap("SELECT * FROM main_reconcile_runs WHERE id = ? AND tenant_id = ?", run, tenant);
    }

    private Map<String, Object> diff(UUID run, UUID variant) {
        List<Map<String, Object>> d = jdbc.queryForList(
            "SELECT * FROM main_reconcile_diffs WHERE run_id = ? AND variant_id = ?", run, variant);
        assertThat(d).as("one diff for %s in run %s", variant, run).hasSize(1);
        return d.get(0);
    }

    private void assertNoMutation(UUID... variants) {
        List<String> items = Arrays.stream(variants).map(BazarU1aTest::item).toList();
        for (Invocation inv : Mockito.mockingDetails(shopify).getInvocations()) {
            String args = Arrays.deepToString(inv.getArguments());
            if (items.stream().noneMatch(args::contains)) continue;
            assertThat(READS).as("Shopify call %s %s", inv.getMethod().getName(), args).contains(inv.getMethod().getName());
        }
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private void await(String sql, int n, Object... args) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (count(sql, args) < n) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("never reached " + n + ": " + sql);
            Thread.sleep(25);
        }
    }
}
