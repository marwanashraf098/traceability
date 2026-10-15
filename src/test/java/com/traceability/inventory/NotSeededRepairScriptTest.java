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
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * scripts/ops/2026-10-14-not-seeded-repair.sql through real psql in the database container.
 *
 * Fixture (built through the real TransferService / PieceAdjustService while Shopify wasn't counting,
 * then shaped exactly like the rows the pre-V167 gate wrote — skip reason 'not_seeded' — and the
 * receiving claims flipped to applied, i.e. the prod BROEK shape):
 *   variant A: P1 voided (−1 owed); X moved out permanently (−1 owed); Y moved out on the SAME move and
 *              brought back good since (net zero — the AG6 case); 2 more available. Shopify 5, Traced 3.
 *   variant B: W moved out on the same move, its bring-back SENT not received (the W13T case — stays in
 *              the −N, the pushed claim gives the +1 on arrival); 1 more available. Shopify 2, Traced 1.
 *   variant C: PC voided, but Shopify was already fixed by hand: Shopify 2 = Traced 2 → stays skipped.
 *
 *   n0 V167 marker missing → refuses, nothing written
 *   n1 dry run → per-SKU −N / now → after / Traced, every exclusion with why; nothing written
 *   n2 a Shopify level older than the variant's latest piece event → the whole run aborts, nothing written
 *   n3 commit → P1 reopened (reason + error kept in payload.repair); the move reopened with deltas
 *      {A:1, B:1}, piece_ids [W, X], Y excluded came_back_to_main; PC stays skipped with a note
 *   n4 commit again → nothing changes
 *   n5 the app sends them: ONE pushVoidCorrection (−1), ONE pushTransferOut (A −1, B −1); W's arrival → +1
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotSeededRepairScriptTest {

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

    static final String SCRIPT = "2026-10-14-not-seeded-repair.sql";
    static final String SCOPES = "read_orders,write_inventory,read_products,write_locations,read_locations";

    @Autowired JdbcTemplate jdbc;
    @Autowired TransferService transfers;
    @Autowired TransferShopifySync sync;
    @Autowired PieceAdjustService adjust;
    @Autowired ShopifyInventoryService inventory;
    @MockBean ShopifyGateway shopify;
    @MockBean ShopifyTokenProvider tokenProvider;

    final AtomicInteger seq = new AtomicInteger();
    UUID tenant, owner, main, away, a, b, c, move, wBack;
    String traced, levelLocation, p1, pc, x, y, w;

    @BeforeAll
    void fixture() throws Exception {
        when(tokenProvider.getValidToken(any())).thenReturn("tok");
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); main = UUID.randomUUID(); away = UUID.randomUUID();
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        String shop = "nsr-" + tenant.toString().substring(0, 6) + ".myshopify.com";
        levelLocation = "777" + Math.abs(tenant.hashCode());
        traced = "gid://shopify/Location/" + levelLocation;
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Repair Gate Co')", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'h', 'owner')",
            owner, tenant, "o-" + owner + "@t.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status, import_status, access_token_scopes) " +
            "VALUES (?, ?, 'shopify', ?, 'connected', 'completed', ?)", store, tenant, shop, SCOPES);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment, shopify_location_id, shopify_sync_status) " +
            "VALUES (?, ?, 'Main', 'warehouse', true, true, ?, 'linked')", main, tenant, traced);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Showroom', 'warehouse', false, false)", away, tenant);
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'P', 'active')",
            product, tenant, store, "gid://shopify/Product/" + shop);
        a = variant(product, "A"); b = variant(product, "B"); c = variant(product, "C");

        p1 = piece(a); x = piece(a); y = piece(a); piece(a); piece(a);
        w = piece(b); piece(b);
        pc = piece(c); piece(c); piece(c);

        // While Shopify wasn't counting (every receiving claim 'failed'): the void, the move, the bring-backs.
        as(() -> adjust.voidPiece(p1, "receiving_overcount", null, owner));
        as(() -> adjust.voidPiece(pc, "receiving_overcount", null, owner));
        move = TenantContext.runAs(tenant, () -> transfers.createTransfer("other", away, null, "move", owner, "relocate_out"));
        for (String p : List.of(x, y, w)) TenantContext.runAs(tenant, () -> transfers.scanOut(move, barcode(p), owner));
        as(() -> transfers.closeOneWay(move, owner));
        UUID yBack = TenantContext.runAs(tenant, () ->
            transfers.createTransfer("other", main, null, "back", owner, "relocate_return", away));
        TenantContext.runAs(tenant, () -> transfers.returnScanOut(yBack, barcode(y), owner));
        as(() -> transfers.markSent(yBack, owner));
        as(() -> transfers.beginReconcile(yBack, owner));
        as(() -> transfers.reconcileScanBack(yBack, barcode(y), "good", owner));
        wBack = TenantContext.runAs(tenant, () -> transfers.createTransfer("other", main, null, "back", owner, "relocate_return", away));
        TenantContext.runAs(tenant, () -> transfers.returnScanOut(wBack, barcode(w), owner));
        as(() -> transfers.markSent(wBack, owner));
        Thread.sleep(300);

        // Shape them like the pre-V167 gate's rows, and let the receiving +1s be the ones that applied.
        assertThat(jdbc.update("UPDATE shopify_inventory_adjustments SET skip_reason = 'not_seeded' WHERE tenant_id = ? " +
            "AND trigger_type = 'void_correction' AND status = 'skipped' AND skip_reason = 'not_counting'", tenant)).isEqualTo(2);
        assertThat(jdbc.update("UPDATE transfer_shopify_syncs SET reason = 'not_seeded' WHERE transfer_id = ? " +
            "AND status = 'skipped' AND reason = 'not_counting'", move)).isEqualTo(1);
        jdbc.update("UPDATE shopify_inventory_adjustments SET status = 'applied' WHERE tenant_id = ? AND trigger_type = 'receiving_session'", tenant);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND trigger_type = 'transfer_return'", Integer.class, tenant)).as("Y's +1 was skipped too (prod AG6)").isZero();

        // Shopify levels at the Traced location, synced after every piece event.
        level(a, 5); level(b, 2); level(c, 2);
    }

    @BeforeEach
    void resetMocks() {
        Mockito.reset(shopify);
    }

    @Test @Order(0)
    void n0_markerMissing_refuses_nothingWritten() throws Exception {
        jdbc.execute("ALTER FUNCTION shopify_counting_source(uuid) RENAME TO shopify_counting_source_hidden");
        try {
            ExecResult r = psql(false);
            assertThat(r.getExitCode()).isNotZero();
            assertThat(r.getStderr()).contains("V167 (shopify_counting_source) is not applied");
        } finally {
            jdbc.execute("ALTER FUNCTION shopify_counting_source_hidden(uuid) RENAME TO shopify_counting_source");
        }
        assertUntouched();
    }

    @Test @Order(1)
    void n1_dryRun_reportsPerSkuAndExclusions_writesNothing() throws Exception {
        ExecResult r = psql(false);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        String out = r.getStdout();
        assertThat(out).contains("SKU-A").contains("SKU-B")
            .contains("came_back_to_main").contains("shopify_at_or_below_traced")
            .contains("Shopify 2, Traced 2, would send 1")
            .contains("dry run — ROLLED BACK");
        assertThat(line(out, "SKU-A")).as("A: −2, 5 → 3, Traced 3").containsPattern("\\|\\s+2\\s+\\|\\s+5\\s+\\|\\s+3\\s+\\|\\s+3\\s+\\|");
        assertThat(line(out, "SKU-B")).as("B: −1, 2 → 1, Traced 1").containsPattern("\\|\\s+1\\s+\\|\\s+2\\s+\\|\\s+1\\s+\\|\\s+1\\s+\\|");
        assertUntouched();
    }

    @Test @Order(2)
    void n2_staleLevel_abortsTheWholeRun_nothingWritten() throws Exception {
        jdbc.update("UPDATE variant_shopify_levels SET synced_at = now() - interval '1 day' WHERE variant_id = ?", b);
        try {
            ExecResult r = psql(true);
            assertThat(r.getExitCode()).isNotZero();
            assertThat(r.getStderr()).contains("1 variant(s) have no Shopify level synced after their latest piece event");
        } finally {
            jdbc.update("UPDATE variant_shopify_levels SET synced_at = now() WHERE variant_id = ?", b);
        }
        assertUntouched();
    }

    @Test @Order(3)
    void n3_commit_reopensTheOwedRows_excludesTheRest() throws Exception {
        ExecResult r = psql(true);
        assertThat(r.getExitCode()).as(r.getStderr()).isZero();
        assertThat(r.getStdout()).contains("COMMITTED");

        Map<String, Object> v1 = voidRow(p1);
        assertThat(v1).containsEntry("status", "queued").containsEntry("skip_reason", "not_seeded");
        assertThat((String) v1.get("repair")).contains("\"from\": \"not_seeded\"").contains("\"by\": \"ops\"");
        Map<String, Object> vc = voidRow(pc);
        assertThat(vc).containsEntry("status", "skipped");
        assertThat((String) vc.get("repair")).contains("shopify_at_or_below_traced");

        Map<String, Object> m = jdbc.queryForMap("SELECT status, source, reason, deltas::text AS deltas, piece_ids::text AS piece_ids, " +
            "payload->'repair'->'excluded' AS excluded FROM transfer_shopify_syncs WHERE transfer_id = ?", move);
        assertThat(m).containsEntry("status", "queued").containsEntry("source", "repair").containsEntry("reason", "not_seeded");
        assertThat(jdbc.queryForObject("SELECT deltas = ?::jsonb FROM transfer_shopify_syncs WHERE transfer_id = ?", Boolean.class,
            "{\"" + a + "\": 1, \"" + b + "\": 1}", move)).isTrue();
        assertThat(jdbc.queryForObject("SELECT piece_ids @> ?::jsonb AND jsonb_array_length(piece_ids) = 2 FROM transfer_shopify_syncs " +
            "WHERE transfer_id = ?", Boolean.class, "[\"" + x + "\", \"" + w + "\"]", move)).isTrue();
        assertThat(m.get("excluded").toString()).contains(y).contains("came_back_to_main");
    }

    @Test @Order(4)
    void n4_commitAgain_changesNothing() throws Exception {
        String before = snapshot();
        assertThat(psql(true).getExitCode()).isZero();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test @Order(5)
    @SuppressWarnings("unchecked")
    void n5_theAppSendsThem_onceEach_andTheInFlightBringBackGetsItsPlusOne() throws Exception {
        long voidId = jdbc.queryForObject("SELECT id FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'void_correction' " +
            "AND trigger_id = ?", Long.class, tenant, p1);
        UUID syncId = jdbc.queryForObject("SELECT id FROM transfer_shopify_syncs WHERE transfer_id = ?", UUID.class, move);
        TenantContext.runAs(tenant, () -> inventory.pushPieceClaimNow(tenant, voidId, false));
        TenantContext.runAs(tenant, () -> sync.pushNow(tenant, syncId));

        verify(shopify, times(1)).pushVoidCorrection(any(), any(), any(), eq(traced), eq(-1), eq("traced://piece/" + p1), anyString());
        ArgumentCaptor<List<ShopifyGateway.InventoryDelta>> deltas = ArgumentCaptor.forClass(List.class);
        verify(shopify, times(1)).pushTransferOut(any(), any(), deltas.capture(), eq(traced), eq("traced://transfer/" + move), anyString());
        assertThat(deltas.getValue()).extracting(ShopifyGateway.InventoryDelta::negativeDelta).containsExactly(-1, -1);
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_shopify_syncs WHERE id = ?", String.class, syncId)).isEqualTo("pushed");

        as(() -> transfers.beginReconcile(wBack, owner));
        as(() -> transfers.reconcileScanBack(wBack, barcode(w), "good", owner));
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? AND trigger_type = 'transfer_return' " +
                "AND status = 'applied'", Integer.class, tenant) < 1) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("W never got its +1");
            Thread.sleep(25);
        }
        verify(shopify, times(1)).adjustInventoryQuantities(any(), any(), any(), eq(traced), eq(1), eq("movement_received"), any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void assertUntouched() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
            "AND skip_reason = 'not_seeded' AND status = 'skipped' AND NOT jsonb_exists(payload, 'repair')", Integer.class, tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM transfer_shopify_syncs WHERE transfer_id = ?", String.class, move))
            .isEqualTo("skipped");
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(payload, 'repair') FROM transfer_shopify_syncs WHERE transfer_id = ?", Boolean.class, move))
            .isFalse();
    }

    private Map<String, Object> voidRow(String piece) {
        return jdbc.queryForMap("SELECT status, skip_reason, (payload->'repair')::text AS repair FROM shopify_inventory_adjustments " +
            "WHERE tenant_id = ? AND trigger_type = 'void_correction' AND trigger_id = ?", tenant, piece);
    }

    private String snapshot() {
        return jdbc.queryForList("SELECT id::text, status, skip_reason, payload::text FROM shopify_inventory_adjustments WHERE tenant_id = ? " +
                "AND trigger_type = 'void_correction' ORDER BY id", tenant).toString()
            + jdbc.queryForList("SELECT status, deltas::text, piece_ids::text, payload::text FROM transfer_shopify_syncs WHERE tenant_id = ? " +
                "ORDER BY created_at, id", tenant);
    }

    private static String line(String out, String sku) {
        return Arrays.stream(out.split("\n")).filter(l -> l.contains(sku) && l.contains("Repair Gate Co")).findFirst().orElse("");
    }

    private ExecResult psql(boolean commit) throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath("scripts/ops/" + SCRIPT), "/tmp/" + SCRIPT);
        List<String> cmd = new ArrayList<>(List.of("psql", "-U", "postgres", "-d", "traceability_test", "-v", "ON_ERROR_STOP=1"));
        if (commit) cmd.addAll(List.of("-v", "commit=yes"));
        cmd.addAll(List.of("-f", "/tmp/" + SCRIPT));
        return POSTGRES.execInContainer(cmd.toArray(String[]::new));
    }

    private void as(Runnable r) { TenantContext.runAs(tenant, r); }

    private static String barcode(String piece) { return "NS-" + piece; }

    private UUID variant(UUID product, String key) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku, shopify_inventory_item_id) " +
            "VALUES (?, ?, ?, ?, 'V', ?, ?)", v, tenant, product, "gid://shopify/ProductVariant/" + v, "SKU-" + key,
            "gid://shopify/InventoryItem/" + v);
        return v;
    }

    /** A piece at main, received through a receipt whose receiving claim is 'failed' (flipped to applied later). */
    private String piece(UUID variant) {
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, tenant_id, received_by, location_id) VALUES (?, ?, ?, ?)", receipt, tenant, owner, main);
        jdbc.update("INSERT INTO shopify_inventory_adjustments (tenant_id, batch_id, variant_id, location_id, delta, trigger_type, " +
            "trigger_id, payload, status, attempt_count) VALUES (?, ?, ?, ?, 1, 'receiving_session', ?, '{}'::jsonb, 'failed', ?)",
            tenant, UUID.randomUUID(), variant, main, receipt.toString(), IncrementRecoveryRules.MAX_ATTEMPTS);
        String id = String.format("01NOTSEEDREPAIR%011d", seq.incrementAndGet());
        jdbc.update("INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id, receipt_id) " +
            "VALUES (?, ?, ?, ?, ?, 'available'::piece_status, ?, ?)", id, tenant, variant, barcode(id), "N" + (1000000 + seq.get()),
            main, receipt);
        return id;
    }

    private void level(UUID variant, int available) {
        jdbc.update("INSERT INTO variant_shopify_levels (tenant_id, variant_id, location_id, available, synced_at) VALUES (?, ?, ?, ?, now())",
            tenant, variant, levelLocation, available);
    }
}
