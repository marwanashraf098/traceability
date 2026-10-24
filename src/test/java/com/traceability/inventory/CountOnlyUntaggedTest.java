package com.traceability.inventory;

import com.traceability.ApiException;
import com.traceability.identity.JwtService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Flexible labeling slice 2 — count-only receiving, untagged pieces, and the v1 guards.
 *
 *   r1  count-only finalize: N pieces, short_code NULL, barcode PC-ULID, receipt, main warehouse,
 *       received events; the piece counter is NOT claimed; Shopify +N exactly once, same call as
 *       print-and-stick receiving
 *   r2  8,193 units in one count-only finalize
 *   r3  count_only refused when the flag is off (at create AND at finalize) and outside the main warehouse;
 *       GET session reports mode + the Shopify summary
 *   l1  every receiving label print / reprint refuses a count-only receipt (409, nothing logged)
 *   l2  single-piece labels: Lookup reprint, returns intake reprint, generatePieceLabels refuse an
 *       untagged piece — no label_reprinted event
 *   a1  hold / void / adjust (damaged, lost, destroyed) refuse an untagged piece — no event, no change
 *   s1  a typed internal code is refused by the pick, transfer, stock-take and returns scans
 *   t1  stock take, switch OFF (slice 7): start refused while untagged pieces are in scope (location + variant
 *       subset); a subset without them starts; finalize refused when untagged stock arrived after open
 *   t2  backstop: plan() never writes off (or damage-corrects) an untagged piece even if one is expected
 *   k1  Lookup: canReprint false for an untagged piece; variantStock counts only with the flag
 *   m1  mixed variant: on_hand / committed / available unchanged in meaning; breakdown pieceSplit;
 *       /inventory/pieces lists only labeled pieces + untaggedCount
 *   x1  app_user: the untagged-count queries see only the caller's tenant (same-tenant positive control)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CountOnlyUntaggedTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivingService receiving;
    @Autowired LabelService labels;
    @Autowired PieceLabelReprintService lookupReprint;
    @Autowired ReturnSessionService returnSessions;
    @Autowired PieceAdjustService adjust;
    @Autowired FulfillService fulfill;
    @Autowired TransferService transfers;
    @Autowired StockTakeService stockTakes;
    @Autowired StockTakeReconciliationService reconciliation;
    @Autowired LookupService lookup;
    @Autowired VariantStockService variantStock;
    @MockBean  ShopifyInventoryService shopifyInventory;

    UUID tenant, owner, variantA, variantB, main, side, otherTenant, otherOwner, otherMain, otherVariant;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID();
        variantA = UUID.randomUUID(); variantB = UUID.randomUUID();
        main = UUID.randomUUID(); side = UUID.randomUUID();
        otherTenant = UUID.randomUUID(); otherOwner = UUID.randomUUID(); otherMain = UUID.randomUUID(); otherVariant = UUID.randomUUID();
        seedTenant(tenant, owner, main, side, "CO", variantA, variantB);
        seedTenant(otherTenant, otherOwner, otherMain, UUID.randomUUID(), "CX", otherVariant, UUID.randomUUID());
    }

    private void seedTenant(UUID t, UUID user, UUID mainLoc, UUID sideLoc, String p, UUID v1, UUID v2) {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, ?, true)", t, p + " Tenant");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'Owner', ?, 'hash', 'owner')",
            user, t, p.toLowerCase() + "-owner@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, t, p.toLowerCase() + ".myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Widget', 'active')",
            product, t, store, p + "-P");
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, ?, 'Blue', ?), (?, ?, ?, ?, 'Red', ?)",
            v1, t, product, p + "-V1", p + "BLUE", v2, t, product, p + "-V2", p + "RED");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES " +
            "(?, ?, ?, 'warehouse', true, true), (?, ?, ?, 'showroom', false, false)",
            mainLoc, t, p + " Main", sideLoc, t, p + " Showroom");
    }

    @BeforeEach
    void each() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id IN (?, ?)", tenant, otherTenant);
        reset(shopifyInventory);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (UUID t : List.of(tenant, otherTenant)) {
            jdbc.update("DELETE FROM stock_take_scans WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM stock_take_expected WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM stock_take_scope_variants WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM stock_take_sessions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM transfer_pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM transfers WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_session_items WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM return_sessions WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM label_reprints WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_events WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM pieces WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM receipt_lines WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM receipts WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM piece_counters WHERE tenant_id = ?", t);
        }
    }

    // ── receiving ─────────────────────────────────────────────────────────────

    @Test
    void r1_count_only_finalize_creates_untagged_pieces_without_claiming_codes() {
        printStick(variantB, 2);   // the counter exists and moved
        long counterBefore = counter();
        reset(shopifyInventory);

        UUID s = receiving.createSession(owner, main, "CO-1", null, null, ReceivingService.MODE_COUNT_ONLY);
        receiving.addLine(s, variantA, 3);
        receiving.addLine(s, variantB, 2);
        assertThat(receiving.finalize(s, owner)).isEqualTo(5);

        assertThat(count("SELECT count(*) FROM pieces WHERE receipt_id = ? AND short_code IS NULL AND status = 'available' " +
            "AND current_location_id = ? AND barcode = 'PC-' || id", s, main)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM pieces WHERE receipt_id = ? AND variant_id = ?", s, variantA)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM piece_events e JOIN pieces p ON p.id = e.piece_id WHERE p.receipt_id = ? " +
            "AND e.event_type = 'received' AND e.from_status IS NULL AND e.to_status = 'available' AND e.actor_user_id = ?",
            s, owner)).isEqualTo(5);
        assertThat(counter()).as("no short code claimed").isEqualTo(counterBefore);
        verify(shopifyInventory, times(1)).onReceivingSessionClose(tenant, s, main, Map.of(variantA, 3, variantB, 2));
        verifyNoMoreInteractions(shopifyInventory);

        // Print & stick: same call shape (the trigger is unchanged).
        UUID ps = printStick(variantA, 1);
        verify(shopifyInventory).onReceivingSessionClose(tenant, ps, main, Map.of(variantA, 1));
        assertThat(jdbc.queryForObject("SELECT short_code FROM pieces WHERE receipt_id = ?", String.class, ps)).startsWith("P");
    }

    @Test
    void r2_count_only_finalize_of_8193_units() {
        UUID s = receiving.createSession(owner, main, "CO-2", null, null, ReceivingService.MODE_COUNT_ONLY);
        receiving.addLine(s, variantA, 5000);
        receiving.addLine(s, variantB, 3193);
        assertThat(receiving.finalize(s, owner)).isEqualTo(8193);
        assertThat(count("SELECT count(*) FROM pieces WHERE receipt_id = ? AND short_code IS NULL", s)).isEqualTo(8193);
        assertThat(count("SELECT count(*) FROM piece_events e JOIN pieces p ON p.id = e.piece_id WHERE p.receipt_id = ?", s))
            .isEqualTo(8193);
    }

    @Test
    void r3_count_only_refused_without_the_flag_or_outside_the_main_warehouse() {
        assertCode(() -> receiving.createSession(owner, side, "CO-3a", null, null, ReceivingService.MODE_COUNT_ONLY),
            "COUNT_ONLY_MAIN_WAREHOUSE_ONLY");
        assertThatThrownBy(() -> receiving.createSession(owner, main, "CO-3b", null, null, "factory"))
            .hasMessageContaining("400");

        UUID s = receiving.createSession(owner, main, "CO-3c", null, null, ReceivingService.MODE_COUNT_ONLY);
        receiving.addLine(s, variantA, 1);
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        assertCode(() -> receiving.finalize(s, owner), "COUNT_ONLY_NOT_AVAILABLE");
        assertThat(count("SELECT count(*) FROM pieces WHERE receipt_id = ?", s)).isZero();
        assertCode(() -> receiving.createSession(owner, main, "CO-3d", null, null, ReceivingService.MODE_COUNT_ONLY),
            "COUNT_ONLY_NOT_AVAILABLE");

        // Over HTTP: flag off → 400; default (no mode) → print_stick, exactly today's request.
        ResponseEntity<Map> refused = post("/api/v1/receiving/sessions",
            Map.of("locationId", main.toString(), "reference", "CO-3e", "mode", "count_only"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).containsEntry("code", "COUNT_ONLY_NOT_AVAILABLE");
        ResponseEntity<Map> plain = post("/api/v1/receiving/sessions", Map.of("locationId", main.toString(), "reference", "CO-3f"));
        assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT mode FROM receipts WHERE id = ?::uuid", String.class, plain.getBody().get("sessionId")))
            .isEqualTo("print_stick");

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);
        UUID done = countOnly(variantA, 2);
        Map<String, Object> session = receiving.getSession(done);
        assertThat(session).containsEntry("mode", "count_only").containsKey("shopify");
        assertThat(receiving.getSession(printStick(variantA, 1))).containsEntry("mode", "print_stick").doesNotContainKey("shopify");
    }

    // ── labels ────────────────────────────────────────────────────────────────

    @Test
    void l1_every_receiving_label_endpoint_refuses_a_count_only_receipt() {
        UUID s = countOnly(variantA, 2);
        assertCode(() -> labels.generateSessionLabels(s, null, null), "COUNT_ONLY_NO_LABELS");
        assertCode(() -> labels.reprint(s, owner, "x", null, null), "COUNT_ONLY_NO_LABELS");
        assertCode(() -> labels.generateVariantLabels(s, variantA, null, null), "COUNT_ONLY_NO_LABELS");
        assertCode(() -> labels.reprintVariant(s, variantA, owner, "x", null, null), "COUNT_ONLY_NO_LABELS");
        assertThat(count("SELECT count(*) FROM label_reprints WHERE receipt_id = ?", s)).isZero();

        ResponseEntity<Map> http = rest.exchange(base() + "/api/v1/receiving/sessions/" + s + "/labels",
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(http.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getBody()).containsEntry("code", "COUNT_ONLY_NO_LABELS");

        // Print & stick still prints.
        UUID ps = printStick(variantA, 2);
        assertThatCode(() -> labels.generateSessionLabels(ps, null, null)).doesNotThrowAnyException();
    }

    @Test
    void l2_single_piece_labels_refuse_an_untagged_piece_before_any_event() {
        String piece = untaggedPiece();
        assertCode(() -> lookupReprint.reprint(piece, owner), "PIECE_UNTAGGED");
        assertCode(() -> labels.generatePieceLabel(piece, null, null), "PIECE_UNTAGGED");
        assertCode(() -> labels.generatePieceLabels(List.of(piece), null, null), "PIECE_UNTAGGED");
        UUID rs = returnSessions.createSession(null, owner);
        assertCode(() -> returnSessions.recordReprint(rs, piece, owner), "PIECE_UNTAGGED");
        assertThat(count("SELECT count(*) FROM piece_events WHERE piece_id = ? AND event_type = 'label_reprinted'", piece))
            .as("no label_reprinted event for a label that can't be printed").isZero();
    }

    // ── piece actions ─────────────────────────────────────────────────────────

    @Test
    void a1_hold_void_and_adjust_refuse_an_untagged_piece() {
        String piece = untaggedPiece();
        reset(shopifyInventory);   // receiving's own +N call is not what this test is about
        assertCode(() -> adjust.hold(piece, "quality_check", null, owner), "PIECE_UNTAGGED");
        assertCode(() -> adjust.voidPiece(piece, "duplicate_entry", null, owner), "PIECE_UNTAGGED");
        for (String to : List.of("damaged", "lost", "destroyed")) {
            assertCode(() -> adjust.adjustPiece(piece, to, "damaged_in_storage", null, owner), "PIECE_UNTAGGED");
        }
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece)).isEqualTo("available");
        assertThat(count("SELECT count(*) FROM piece_events WHERE piece_id = ?", piece)).as("only 'received'").isEqualTo(1);
        verifyNoInteractions(shopifyInventory);
    }

    // ── scans ─────────────────────────────────────────────────────────────────

    @Test
    void s1_a_typed_internal_code_is_refused_by_every_scan() {
        String piece = untaggedPiece();
        String internal = jdbc.queryForObject("SELECT barcode FROM pieces WHERE id = ?", String.class, piece);

        FulfillService.ScanResult pick = fulfill.scan(UUID.randomUUID(), internal, owner);
        assertThat(pick.success()).isFalse();
        assertThat(pick.code()).isEqualTo("PIECE_UNTAGGED");

        UUID transfer = transfers.createTransfer("showroom", side, null, "t", owner);
        TransferService.ScanOutResult out = transfers.scanOut(transfer, internal, owner);
        assertThat(out.code()).isEqualTo("PIECE_UNTAGGED");
        assertThat(count("SELECT count(*) FROM transfer_pieces WHERE piece_id = ?", piece)).isZero();

        UUID rs = returnSessions.createSession(null, owner);
        assertCode(() -> returnSessions.scan(rs, internal, main, owner, false), "PIECE_UNTAGGED");

        // A stock take opened before untagged stock arrived: its scan refuses the typed code.
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE id = ?", side, piece);   // out of scope for the open
        printStick(variantA, 1);
        UUID st = (UUID) stockTakes.openSession("all", null, main, null, owner).get("sessionId");
        assertCode(() -> stockTakes.scan(st, internal, "good", owner, false), "PIECE_UNTAGGED");
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, piece)).isEqualTo("available");
    }

    // ── stock take ────────────────────────────────────────────────────────────

    @Test
    void t1_stock_take_start_and_finalize_refuse_untagged_stock_in_scope() {
        printStick(variantB, 2);
        countOnly(variantA, 3);
        // Slice 7: with the switch ON untagged pieces are counted (StockTakeUntaggedCountTest); the refusal
        // is the switch-OFF behaviour. Restored in finally so a failure can't leak it into later tests.
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        try {
            ApiException e = catchThrowableOfType(() -> stockTakes.openSession("all", null, main, null, owner), ApiException.class);
            assertThat(e.errorCode()).isEqualTo("STOCK_TAKE_UNTAGGED_IN_SCOPE");
            assertThat(e.messageEn()).isEqualTo("Can't start a stock take here yet — CO Main has 3 untagged pieces " +
                "from flexible labeling. Turn flexible labeling back on to count them.");
            assertThat(e.messageAr()).contains("CO Main").contains("3");
            assertCode(() -> stockTakes.openSession("variant_subset", List.of(variantA), main, null, owner),
                "STOCK_TAKE_UNTAGGED_IN_SCOPE");
            assertThat(count("SELECT count(*) FROM stock_take_sessions WHERE tenant_id = ?", tenant)).isZero();

            // A subset without untagged pieces starts — and finalize refuses once untagged stock of it arrives.
            UUID st = (UUID) stockTakes.openSession("variant_subset", List.of(variantB), main, null, owner).get("sessionId");
            String b = jdbc.queryForObject("SELECT short_code FROM pieces WHERE variant_id = ? AND short_code IS NOT NULL LIMIT 1",
                String.class, variantB);
            stockTakes.scan(st, b, "good", owner, false);
            jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);    // count-only needs it
            countOnly(variantB, 1);
            jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
            assertCode(() -> reconciliation.finalizeSession(st, owner, 1), "STOCK_TAKE_UNTAGGED_IN_SCOPE");
            assertThat(count("SELECT count(*) FROM pieces WHERE tenant_id = ? AND status = 'lost'", tenant)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM stock_take_sessions WHERE id = ?", String.class, st)).isEqualTo("open");
        } finally {
            jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);
        }
    }

    @Test
    void t2_backstop_plan_never_writes_off_an_untagged_piece() {
        UUID ps = printStick(variantA, 2);
        UUID st = (UUID) stockTakes.openSession("all", null, main, null, owner).get("sessionId");
        // An untagged piece in the expected population (a state the open guard never allows) — not scanned.
        String untagged = untaggedPiece();
        jdbc.update("INSERT INTO stock_take_expected (session_id, tenant_id, piece_id, variant_id, status_at_open) " +
            "VALUES (?, ?, ?, ?, 'available')", st, tenant, untagged, variantA);
        jdbc.update("INSERT INTO stock_take_scans (id, tenant_id, session_id, piece_id, scanned_condition, source, actor_user_id) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'damaged', 'scan', ?)", tenant, st, untagged, owner);
        String tagged = jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ? LIMIT 1", String.class, ps);

        StockTakeReconciliationService.FinalizePlan plan = reconciliation.plan(st, tenant, true);
        assertThat(plan.writeOffs()).extracting(StockTakeReconciliationService.WriteOff::pieceId)
            .contains(tagged).doesNotContain(untagged);
        assertThat(plan.damageCorrections()).doesNotContain(untagged);
    }

    // ── Lookup + stock numbers ────────────────────────────────────────────────

    @Test
    void k1_lookup_cannot_reprint_an_untagged_piece_and_shows_the_split_with_the_flag() {
        String piece = untaggedPiece();
        printStick(variantA, 2);
        String internal = jdbc.queryForObject("SELECT barcode FROM pieces WHERE id = ?", String.class, piece);

        Map<String, Object> r = lookup.lookupPiece(internal, false);
        assertThat(r).containsEntry("shortCode", null).containsEntry("canReprint", false);
        assertThat(r.get("variantStock")).isEqualTo(Map.of("available", 3, "labeled", 2, "untagged", 1));

        String code = jdbc.queryForObject("SELECT short_code FROM pieces WHERE short_code IS NOT NULL LIMIT 1", String.class);
        assertThat(lookup.lookupPiece(code, false)).containsEntry("canReprint", true);

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        assertThat(lookup.lookupPiece(code, false)).doesNotContainKey("variantStock");
    }

    @Test
    void m1_mixed_variant_stock_numbers_keep_their_meaning() {
        printStick(variantA, 3);
        countOnly(variantA, 2);
        var stock = variantStock.forVariant(variantStock.computeAll(), variantA);
        assertThat(stock.onHand()).as("untagged pieces are on hand").isEqualTo(5);
        assertThat(stock.committed()).isZero();
        assertThat(stock.available()).isEqualTo(5);

        ResponseEntity<Map> breakdown = rest.exchange(base() + "/api/v1/inventory/variants/" + variantA + "/breakdown",
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(breakdown.getBody().get("onHand")).isEqualTo(5);
        assertThat(breakdown.getBody().get("pieceSplit")).isEqualTo(Map.of("available", 5, "labeled", 3, "untagged", 2));

        ResponseEntity<Map> list = rest.exchange(base() + "/api/v1/inventory/pieces?status=available&variantId=" + variantA,
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat((List<?>) list.getBody().get("items")).hasSize(3);
        assertThat(list.getBody()).containsEntry("untaggedCount", 2);

        ResponseEntity<Map> labeledOnly = rest.exchange(base() + "/api/v1/inventory/pieces?status=available&variantId=" + variantB,
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(labeledOnly.getBody()).doesNotContainKey("untaggedCount");

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        ResponseEntity<Map> off = rest.exchange(base() + "/api/v1/inventory/variants/" + variantA + "/breakdown",
            HttpMethod.GET, new HttpEntity<>(auth()), Map.class);
        assertThat(off.getBody()).doesNotContainKey("pieceSplit");
    }

    // ── RLS ───────────────────────────────────────────────────────────────────

    @Test
    void x1_untagged_counts_are_tenant_scoped_under_app_user() {
        countOnly(variantA, 2);
        TenantContext.set(otherTenant);
        UUID s = receiving.createSession(otherOwner, otherMain, "CX-1", null, null, ReceivingService.MODE_COUNT_ONLY);
        receiving.addLine(s, otherVariant, 4);
        receiving.finalize(s, otherOwner);

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        TenantContext.set(tenant);
        Map<String, Object> own = tx.execute(x -> UntaggedPieces.availableCounts(app, tenant, variantA));
        assertThat(own).containsEntry("untagged", 2);                                        // positive control
        Map<String, Object> theirs = tx.execute(x -> UntaggedPieces.availableCounts(app, otherTenant, otherVariant));
        assertThat(theirs).as("another tenant's pieces are invisible under RLS").containsEntry("untagged", 0);
        assertThatCode(() -> tx.execute(x -> { StockTakeService.refuseUntaggedInScope(app, otherTenant, otherMain, null); return null; }))
            .as("another tenant's untagged pieces never block this tenant's view").doesNotThrowAnyException();
        assertCode(() -> tx.execute(x -> { StockTakeService.refuseUntaggedInScope(app, tenant, main, null); return null; }),
            "STOCK_TAKE_UNTAGGED_IN_SCOPE");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID countOnly(UUID variant, int n) {
        UUID s = receiving.createSession(owner, main, "CO-" + UUID.randomUUID(), null, null, ReceivingService.MODE_COUNT_ONLY);
        receiving.addLine(s, variant, n);
        receiving.finalize(s, owner);
        return s;
    }

    private UUID printStick(UUID variant, int n) {
        UUID s = receiving.createSession(owner, main, "PS-" + UUID.randomUUID(), null, null);
        receiving.addLine(s, variant, n);
        receiving.finalize(s, owner);
        return s;
    }

    private String untaggedPiece() {
        UUID s = countOnly(variantA, 1);
        return jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ?", String.class, s);
    }

    private long counter() {
        List<Long> v = jdbc.queryForList("SELECT last_value FROM piece_counters WHERE tenant_id = ?", Long.class, tenant);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void assertCode(ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(code));
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, auth()), Map.class);
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(owner, tenant, "owner"));   // fresh: a token lives 30 min, a class can run longer in the full suite
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String base() { return "http://localhost:" + port; }
}
