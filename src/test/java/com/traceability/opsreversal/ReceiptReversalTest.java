package com.traceability.opsreversal;

import com.traceability.TestPostgres;
import com.traceability.integrations.shopify.ShopifyAmbiguousException;
import com.traceability.integrations.shopify.ShopifyException;
import com.traceability.integrations.shopify.ShopifyGateway;
import com.traceability.integrations.shopify.ShopifyTokenProvider;
import com.traceability.inventory.InventoryController;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.traceability.inventory.PieceAdjustService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.traceability.opsreversal.ReceiptReversalService.NOTE;
import static com.traceability.opsreversal.ReceiptReversalService.RECEIPT_ID;
import static com.traceability.opsreversal.ReceiptReversalService.TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * One-off receipt reversal (Floe Wear PO-01, 2026-11-05). Fixture: the real tenant / receipt ids, two variants
 * (A: 3 pieces, B: 2 pieces) received into Main with their receiving +N applied, plus a second finalized receipt
 * (the 2 Nov one) that must never be touched.
 *
 *   d1 dry run: per-variant counts + Shopify deltas + current available; nothing written
 *   d2 dry run flags a variant whose Shopify available is below its decrement
 *   a1 apply: every piece voided through the ledger (actor = tenant user, note), one −1 per piece through
 *      void_correction, all applied; receipt 'reversed'; ONE audit row naming the requester; other receipt untouched
 *   a2 guard: a piece with an event besides 'received' → nothing voided, receipt still finalized
 *   a3 would go negative → refused; allowNegative → goes ahead
 *   a4 ambiguous is never re-sent; a definite failure is re-sent by the retry pass and applies
 *   a5 another receipt id → 400
 *   a6 a piece of a later variant changes mid-run → that variant's transaction re-checks and stops; earlier variant
 *      voided, the changed variant untouched, receipt still finalized
 *   o1 overview: recent activity and 30-day throughput leave the reversed session out
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceiptReversalTest {

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
        r.add("traced.ops.receipt-reversal.pace-ms", () -> "0");
        r.add("traced.ops.receipt-reversal.retry-wait-ms", () -> "10");
    }

    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";
    static final String SHOP = "floe-test.myshopify.com";
    static final String MAIN_GID = "gid://shopify/Location/main";
    static final UUID OTHER_RECEIPT = UUID.fromString("c5002445-2713-439f-89e2-e15c8718184f");

    @Autowired JdbcTemplate jdbc;
    @Autowired ReceiptReversalService service;
    @Autowired InventoryController inventoryController;
    @MockBean ShopifyGateway shopifyGateway;
    @MockBean ShopifyTokenProvider tokenProvider;
    @SpyBean PieceAdjustService adjustSpy;

    UUID owner, requester, store, main, variantA, variantB;
    final Map<UUID, String> item = new HashMap<>();
    final Map<String, Integer> available = new HashMap<>();
    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void fixture() {
        Mockito.reset(shopifyGateway, tokenProvider, adjustSpy);
        jdbc.execute("TRUNCATE tenants CASCADE");
        available.clear();
        item.clear();
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        when(shopifyGateway.fetchMainLevels(any(), any(), any(), any())).thenAnswer(inv -> {
            List<ShopifyGateway.MainLevel> out = new ArrayList<>();
            for (Object i : (List<?>) inv.getArgument(3)) {
                int a = available.getOrDefault((String) i, 100);
                out.add(new ShopifyGateway.MainLevel((String) i, true, a, a, 0, 0));
            }
            return out;
        });

        UUID otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Floe Wear'), (?, 'Traced support')", TENANT_ID, otherTenant);
        owner = UUID.randomUUID();
        requester = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Marco', ?, 'h', 'owner')",
            owner, TENANT_ID, "marco-" + owner + "@test.local");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Marawan', ?, 'h', 'owner')",
            requester, otherTenant, "marawan-" + requester + "@test.local");
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes, last_sync_at) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?, now())", store, TENANT_ID, SHOP, SCOPES);
        main = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, shopify_location_id, shopify_sync_status, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', ?, 'linked', true)", main, TENANT_ID, MAIN_GID);
        UUID product = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, 'gid://shopify/Product/1', 'Tee', 'active')",
            product, TENANT_ID, store);
        variantA = variant(product, "A");
        variantB = variant(product, "B");

        jdbc.update("INSERT INTO receipts (id, tenant_id, reference, received_by, location_id, status, finalized_at, kind) " +
            "VALUES (?, ?, 'PO-01', ?, ?, 'finalized', now() - interval '1 day', 'inbound'), " +
            "       (?, ?, NULL, ?, ?, 'finalized', now() - interval '3 days', 'inbound')",
            RECEIPT_ID, TENANT_ID, owner, main, OTHER_RECEIPT, TENANT_ID, owner, main);
        receivingClaim(OTHER_RECEIPT, variantA, 2);
        receivingClaim(RECEIPT_ID, variantA, 3);
        receivingClaim(RECEIPT_ID, variantB, 2);
        for (int i = 0; i < 2; i++) piece(OTHER_RECEIPT, variantA);
        for (int i = 0; i < 3; i++) piece(RECEIPT_ID, variantA);
        for (int i = 0; i < 2; i++) piece(RECEIPT_ID, variantB);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ── dry run ───────────────────────────────────────────────────────────────────

    @Test
    void d1_dryRun_reportsPerVariantCountsAndDeltas_writesNothing() throws Exception {
        Map<String, Object> r = run(false, false);

        assertThat(r.get("phase")).isEqualTo("dry_run_done");
        Map<String, Map<String, Object>> v = variants(r);
        assertThat(v.get("SKU-A")).containsEntry("pieces", 3).containsEntry("to_void", 3)
            .containsEntry("expected_shopify_delta", -3).containsEntry("shopify_available", 100)
            .containsEntry("would_go_negative", false).containsEntry("blocked", 0);
        assertThat(v.get("SKU-B")).containsEntry("pieces", 2).containsEntry("expected_shopify_delta", -2);
        assertThat(totals(r)).containsEntry("pieces", 5).containsEntry("expected_shopify_delta", -5);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE status <> 'available'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_inventory_adjustments WHERE trigger_type = 'void_correction'",
            Integer.class)).isZero();
        assertThat(receiptStatus(RECEIPT_ID)).isEqualTo("finalized");
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void d2_dryRun_flagsVariantWhoseShopifyAvailableIsBelowItsDecrement() throws Exception {
        available.put(item.get(variantB), 1);
        Map<String, Object> r = run(false, false);
        assertThat(variants(r).get("SKU-B")).containsEntry("shopify_available", 1).containsEntry("would_go_negative", true);
        assertThat(variants(r).get("SKU-A")).containsEntry("would_go_negative", false);
        assertThat(r.get("would_go_negative")).isEqualTo(List.of("SKU-B"));
    }

    // ── apply ─────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void a1_apply_voidsEveryPieceThroughTheLedger_oneMinusOneEach_receiptReversed_otherReceiptUntouched() throws Exception {
        Map<String, Object> r = run(true, false);

        assertThat(r.get("phase")).isEqualTo("done");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE receipt_id = ? AND status = 'voided'",
            Integer.class, RECEIPT_ID)).isEqualTo(5);
        // one voided event per piece, person-attributed, with the note — and nothing deleted
        List<Map<String, Object>> events = jdbc.queryForList(
            "SELECT e.actor_user_id, e.from_status::text AS f, e.metadata->>'reason' AS reason, e.metadata->>'note' AS note " +
            "FROM piece_events e JOIN pieces p ON p.id = e.piece_id WHERE p.receipt_id = ? AND e.event_type = 'voided'", RECEIPT_ID);
        assertThat(events).hasSize(5).allSatisfy(e -> assertThat(e)
            .containsEntry("actor_user_id", owner).containsEntry("f", "available")
            .containsEntry("reason", "other").containsEntry("note", NOTE));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.receipt_id = ? AND e.event_type = 'received'", Integer.class, RECEIPT_ID)).isEqualTo(5);

        // Shopify: one void_correction claim per piece, all applied, one −1 call each at Main
        verify(shopifyGateway, times(5)).pushVoidCorrection(eq(SHOP), eq("tok"), anyString(), eq(MAIN_GID), eq(-1),
            startsWith("traced://piece/"), anyString());
        verify(shopifyGateway, times(3)).pushVoidCorrection(any(), any(), eq(item.get(variantA)), any(), anyInt(), any(), any());
        verify(shopifyGateway, times(2)).pushVoidCorrection(any(), any(), eq(item.get(variantB)), any(), anyInt(), any(), any());
        Map<String, Object> shopify = (Map<String, Object>) service.report().get("shopify");
        assertThat(shopify).containsEntry("settled", true).containsEntry("applied_delta", -5);
        assertThat((Map<String, Integer>) shopify.get("totals")).containsExactly(Map.entry("applied", 5));

        assertThat(receiptStatus(RECEIPT_ID)).isEqualTo("reversed");
        List<Map<String, Object>> audit = jdbc.queryForList(
            "SELECT actor_user_id, metadata FROM audit_log WHERE action = 'receipt_reversed' AND target_id = ?", RECEIPT_ID.toString());
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("actor_user_id")).isEqualTo(requester);
        assertThat(audit.get(0).get("metadata").toString()).contains(requester.toString()).contains(owner.toString());

        // the 2 Nov receipt: untouched
        assertThat(receiptStatus(OTHER_RECEIPT)).isEqualTo("finalized");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE receipt_id = ? AND status = 'available'",
            Integer.class, OTHER_RECEIPT)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.receipt_id = ? AND e.event_type <> 'received'", Integer.class, OTHER_RECEIPT)).isZero();

        // a second apply changes nothing
        run(true, false);
        verify(shopifyGateway, times(5)).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'receipt_reversed'", Integer.class)).isEqualTo(1);
    }

    @Test
    void a2_guard_aPieceWithAnotherEvent_abortsBeforeAnyWrite() throws Exception {
        String touched = jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ? AND variant_id = ? LIMIT 1",
            String.class, RECEIPT_ID, variantB);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
            "VALUES (?, ?, 'relabeled', ?, ?, 'available', 'available')", TENANT_ID, touched, owner, main);

        Map<String, Object> r = run(true, false);

        assertThat(r).containsEntry("phase", "stopped").containsEntry("stop_code", "GUARD_FAILED");
        assertThat(variants(r).get("SKU-B").get("blocked_pieces").toString()).contains(touched + "=has_events_besides_received");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE status = 'voided'", Integer.class)).isZero();
        assertThat(receiptStatus(RECEIPT_ID)).isEqualTo("finalized");
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void a3_wouldGoNegative_refusedUnlessAllowNegative() throws Exception {
        available.put(item.get(variantA), 2);

        Map<String, Object> refused = run(true, false);
        assertThat(refused).containsEntry("phase", "stopped").containsEntry("stop_code", "WOULD_GO_NEGATIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE status = 'voided'", Integer.class)).isZero();

        Map<String, Object> forced = run(true, true);
        assertThat(forced.get("phase")).isEqualTo("done");
        assertThat(receiptStatus(RECEIPT_ID)).isEqualTo("reversed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a4_ambiguousNeverResent_definiteFailureResentByRetryPass() throws Exception {
        List<String> a = jdbc.queryForList("SELECT id FROM pieces WHERE receipt_id = ? AND variant_id = ? ORDER BY id",
            String.class, RECEIPT_ID, variantA);
        AtomicInteger definite = new AtomicInteger();
        doAnswer(inv -> {
            String ref = inv.getArgument(5);
            if (ref.equals("traced://piece/" + a.get(0))) throw new ShopifyAmbiguousException("timeout");
            if (ref.equals("traced://piece/" + a.get(1)) && definite.getAndIncrement() == 0) {
                throw new ShopifyException("THROTTLED");
            }
            return null;
        }).when(shopifyGateway).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());

        Map<String, Object> r = run(true, false);

        assertThat(r.get("phase")).isEqualTo("done");
        verify(shopifyGateway, times(1)).pushVoidCorrection(any(), any(), any(), any(), anyInt(), eq("traced://piece/" + a.get(0)), any());
        verify(shopifyGateway, times(2)).pushVoidCorrection(any(), any(), any(), any(), anyInt(), eq("traced://piece/" + a.get(1)), any());
        Map<String, Object> shopify = (Map<String, Object>) service.report().get("shopify");
        assertThat((Map<String, Integer>) shopify.get("totals"))
            .containsEntry("applied", 4).containsEntry("failed_ambiguous", 1);
        assertThat(shopify).containsEntry("settled", true).containsEntry("applied_delta", -4);
    }

    @Test
    void a5_anyOtherReceipt_refused() {
        assertThatThrownBy(() -> service.start(new ReceiptReversalService.Request(OTHER_RECEIPT, owner, requester, true, false)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }

    @Test
    void a6_guardReCheckedInsideEachVariantTransaction() throws Exception {
        // variants are processed in SKU order: A first, then B
        String bPiece = jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ? AND variant_id = ? LIMIT 1",
            String.class, RECEIPT_ID, variantB);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                // someone touches a variant-B piece while variant A is being voided (separate, committed connection)
                jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status) " +
                    "VALUES (?, ?, 'relabeled', ?, ?, 'available', 'available')", TENANT_ID, bPiece, owner, main);
            }
            return inv.callRealMethod();
        }).when(adjustSpy).voidPieceSendLater(anyString(), anyString(), anyString(), any());

        Map<String, Object> r = run(true, false);

        assertThat(r).containsEntry("phase", "stopped").containsEntry("stop_code", "GUARD_FAILED");
        assertThat(r.get("stop_message").toString()).contains("SKU-B").contains(bPiece + "=has_events_besides_received");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE variant_id = ? AND receipt_id = ? AND status = 'voided'",
            Integer.class, variantA, RECEIPT_ID)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pieces WHERE variant_id = ? AND status = 'voided'",
            Integer.class, variantB)).isZero();
        assertThat(receiptStatus(RECEIPT_ID)).isEqualTo("finalized");
        verify(shopifyGateway, never()).pushVoidCorrection(any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── readers of receipts.status ────────────────────────────────────────────────

    @Test
    void o1_overview_recentActivityAndThroughput_leaveTheReversedSessionOut() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "x", null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        int before = TenantContext.runAs(TENANT_ID, () ->
            inventoryController.throughput().stream().mapToInt(InventoryController.ThroughputDay::received).sum());
        assertThat(before).isEqualTo(7);

        run(true, false);

        List<InventoryController.ActivityItem> activity = TenantContext.runAs(TENANT_ID, () -> inventoryController.recentActivity(10));
        assertThat(activity).extracting(InventoryController.ActivityItem::refId).containsExactly(OTHER_RECEIPT.toString());
        int after = TenantContext.runAs(TENANT_ID, () ->
            inventoryController.throughput().stream().mapToInt(InventoryController.ThroughputDay::received).sum());
        assertThat(after).isEqualTo(2);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private Map<String, Object> run(boolean apply, boolean allowNegative) throws Exception {
        service.start(new ReceiptReversalService.Request(RECEIPT_ID, owner, requester, apply, allowNegative));
        long deadline = System.currentTimeMillis() + 30_000;
        Map<String, Object> r = service.report();
        while (Boolean.TRUE.equals(r.get("running"))) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("run never finished: " + r);
            Thread.sleep(25);
            r = service.report();
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> variants(Map<String, Object> r) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> v : (List<Map<String, Object>>) r.get("variants")) out.put((String) v.get("sku"), v);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> r) { return (Map<String, Object>) r.get("totals"); }

    private String receiptStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, id);
    }

    private UUID variant(UUID product, String name) {
        UUID v = UUID.randomUUID();
        String gid = "gid://shopify/InventoryItem/" + name;
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)", v, TENANT_ID, product, "gid://shopify/ProductVariant/" + name, name, "SKU-" + name, gid);
        item.put(v, gid);
        return v;
    }

    private void receivingClaim(UUID receipt, UUID variant, int n) {
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, " +
            "trigger_type, trigger_id, status, created_at) VALUES (?, ?, ?, ?, ?, 'receiving_session', ?, 'applied', now() - interval '1 day')",
            TENANT_ID, UUID.randomUUID(), variant, main, n, receipt.toString());
    }

    private void piece(UUID receipt, UUID variant) {
        int k = seq.incrementAndGet();
        String id = "01RR" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).toUpperCase();
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, status, barcode, short_code, current_location_id, receipt_id, created_at) " +
            "VALUES (?, ?, ?, 'available', ?, ?, ?, ?, now() - interval '1 day')",
            id, TENANT_ID, variant, "RR-" + id, String.format("R%07d", k), main, receipt);
        jdbc.update("INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, to_status, occurred_at) " +
            "VALUES (?, ?, 'received', ?, ?, 'available', now() - interval '1 day')", TENANT_ID, id, owner, main);
    }
}
