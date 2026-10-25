package com.traceability.inventory;

import com.traceability.ApiException;
import com.traceability.identity.JwtService;
import com.traceability.labels.LabelCodeException;
import com.traceability.labels.LabelCodeService;
import com.traceability.labels.TagStockService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
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
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Flexible labeling slice 3 — binding named codes (InventoryLedger.bindLabel) and Tag stock.
 *
 *   b1  a code binds to an untagged piece of ITS variant only, oldest first; never a labeled piece; the piece
 *       gets the code as its short code; one label_bound event (status unchanged)
 *   b2  NO_UNTAGGED_STOCK when the variant has none left at the main warehouse (the code stays issued);
 *       untagged stock elsewhere doesn't count
 *   b3  voided → CODE_VOIDED; unknown → UNKNOWN_CODE; a bound code again → already bound, no second event
 *   b4  two concurrent binds of different codes never take the same piece; one piece + two codes → one wins
 *   b5  counts: labeled +1, untagged −1, on_hand / committed / available unchanged; no Shopify call
 *   b6  a bound piece works in the normal paths: Lookup reprint, transfer scan-out, hold
 *   s1  an ISSUED code in the pick, transfer, returns and stock-take scans and Lookup → "Label not used yet —
 *       tag it in Tag stock first"; a voided code stays "not found"
 *   t1  Tag stock: overview (most untagged first, search, totals), start resumes, print ≤ untagged, replacement,
 *       scan (TAGGED / ALREADY_TAGGED / REJECTED), session summary, void unused (this session only), end
 *   f1  flag off → refused; workers refused
 *   x1  app_user: another tenant's code is unknown, its stock invisible (same-tenant positive control); no DELETE
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TagStockTest {

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
    @Autowired InventoryLedger ledger;
    @Autowired LabelCodeService labels;
    @Autowired TagStockService tagStock;
    @Autowired ReceivingService receiving;
    @Autowired FulfillService fulfill;
    @Autowired TransferService transfers;
    @Autowired ReturnSessionService returnSessions;
    @Autowired StockTakeService stockTakes;
    @Autowired LookupService lookup;
    @Autowired PieceLabelReprintService lookupReprint;
    @Autowired PieceAdjustService adjust;
    @Autowired VariantStockService variantStock;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;
    @MockBean  ShopifyInventoryService shopifyInventory;

    UUID tenant, owner, worker, variantA, variantB, main, side, other, otherOwner, otherMain, otherVariant;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); worker = UUID.randomUUID();
        variantA = UUID.randomUUID(); variantB = UUID.randomUUID(); main = UUID.randomUUID(); side = UUID.randomUUID();
        other = UUID.randomUUID(); otherOwner = UUID.randomUUID(); otherMain = UUID.randomUUID(); otherVariant = UUID.randomUUID();
        seed(tenant, owner, main, side, "TS", variantA, variantB);
        seed(other, otherOwner, otherMain, UUID.randomUUID(), "TX", otherVariant, UUID.randomUUID());
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'W', 'ts-w@test.local', 'h', 'worker')",
            worker, tenant);
    }

    private void seed(UUID t, UUID user, UUID mainLoc, UUID sideLoc, String p, UUID v1, UUID v2) {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?)", t, p + " Tenant");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'h', 'owner')",
            user, t, p.toLowerCase() + "-o@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, t, p.toLowerCase() + "-ts.myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'Tag Shirt', 'active')",
            product, t, store, p + "-P");
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, ?, 'Blue', ?), (?, ?, ?, ?, 'Red', ?)", v1, t, product, p + "-V1", p + "BLUE", v2, t, product, p + "-V2", p + "RED");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES " +
            "(?, ?, ?, 'warehouse', true, true), (?, ?, ?, 'showroom', false, false)", mainLoc, t, p + " Main", sideLoc, t, p + " Showroom");
    }

    @BeforeEach
    void each() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id IN (?, ?)", tenant, other);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (UUID t : List.of(tenant, other)) {
            for (String table : List.of("stock_take_scans", "stock_take_expected", "stock_take_scope_variants", "stock_take_sessions",
                    "transfer_pieces", "transfer_lines", "transfer_shopify_syncs", "transfers", "return_session_items", "return_sessions", "label_codes",
                    "label_code_batches", "tag_sessions", "piece_events", "pieces", "receipt_lines", "receipts", "piece_counters")) {
                jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", t);
            }
        }
        reset(shopifyInventory);
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

    private LabelCodeService.IssuedCode code(UUID variant) {
        return labels.issue(owner, "tag", List.of(new LabelCodeService.Line(variant, 1, null, null)), "k-" + UUID.randomUUID()).codes().get(0);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    private int untagged(UUID variant) {
        return count("SELECT count(*) FROM pieces WHERE variant_id = ? AND short_code IS NULL AND status = 'available'", variant);
    }

    private int labeled(UUID variant) {
        return count("SELECT count(*) FROM pieces WHERE variant_id = ? AND short_code IS NOT NULL AND status = 'available'", variant);
    }

    /** ApiException (scan refusals) or LabelCodeException (label codes / binding) — by its code. */
    private static void assertCode(ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).satisfies(e -> assertThat(
            e instanceof LabelCodeException l ? l.code().name()
                : e instanceof ApiException a ? a.errorCode() : e.toString()).isEqualTo(code));
    }

    // ── binding ───────────────────────────────────────────────────────────────

    @Test
    void b1_binds_to_its_variant_oldest_untagged_piece_only() {
        printStick(variantA, 1);                         // an OLDER, labeled piece of A
        countOnly(variantB, 2);                          // untagged B — never a match for an A code
        UUID first = countOnly(variantA, 1);
        countOnly(variantA, 1);
        String oldest = jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ?", String.class, first);
        var c = code(variantA);

        InventoryLedger.BindResult r = ledger.bindLabel(c.id(), owner, InventoryLedger.BindRef.tagSession(null, false), c.code());

        assertThat(r.alreadyBound()).isFalse();
        assertThat(r.pieceId()).as("oldest untagged piece of the code's variant").isEqualTo(oldest);
        assertThat(jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, oldest)).isEqualTo(c.code());
        assertThat(jdbc.queryForMap("SELECT status, piece_id FROM label_codes WHERE id = ?", c.id()))
            .containsEntry("status", "bound").containsEntry("piece_id", oldest);
        assertThat(jdbc.queryForMap("SELECT event_type, from_status::text AS f, to_status::text AS t, metadata ->> 'code' AS code " +
            "FROM piece_events WHERE piece_id = ? AND event_type = 'label_bound'", oldest))
            .containsEntry("f", "available").containsEntry("t", "available").containsEntry("code", c.code());
        assertThat(untagged(variantB)).isEqualTo(2);
    }

    @Test
    void b2_no_untagged_stock_left_at_the_main_warehouse() {
        printStick(variantA, 2);
        UUID co = countOnly(variantA, 1);
        // An untagged piece elsewhere (only possible by hand) doesn't count — binding is main-warehouse only.
        jdbc.update("UPDATE pieces SET current_location_id = ? WHERE receipt_id = ?", side, co);
        var c = code(variantA);
        assertCode(() -> ledger.bindLabel(c.id(), owner, null, c.code()), "NO_UNTAGGED_STOCK");
        assertThat(jdbc.queryForObject("SELECT status FROM label_codes WHERE id = ?", String.class, c.id())).isEqualTo("issued");
        assertThat(count("SELECT count(*) FROM piece_events WHERE event_type = 'label_bound' AND tenant_id = ?", tenant)).isZero();
    }

    @Test
    void b3_voided_unknown_and_already_bound() {
        countOnly(variantA, 2);
        var voided = code(variantA);
        labels.voidCodes(List.of(voided.id()), "torn");
        assertCode(() -> ledger.bindLabel(voided.id(), owner, null, voided.code()), "CODE_VOIDED");
        assertCode(() -> ledger.bindLabel(UUID.randomUUID(), owner, null, "P9"), "UNKNOWN_CODE");

        var c = code(variantA);
        String piece = ledger.bindLabel(c.id(), owner, null, c.code()).pieceId();
        InventoryLedger.BindResult again = ledger.bindLabel(c.id(), owner, null, c.code());
        assertThat(again.alreadyBound()).isTrue();
        assertThat(again.pieceId()).isEqualTo(piece);
        assertThat(count("SELECT count(*) FROM piece_events WHERE event_type = 'label_bound' AND tenant_id = ?", tenant))
            .as("one event for two scans").isEqualTo(1);
        assertThat(untagged(variantA)).isEqualTo(1);
    }

    @Test
    void b4_concurrent_binds_never_take_the_same_piece() throws Exception {
        countOnly(variantA, 2);
        List<LabelCodeService.IssuedCode> codes = labels.issue(owner, "tag",
            List.of(new LabelCodeService.Line(variantA, 3, null, null)), "b4").codes();
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier go = new CyclicBarrier(threads);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (var c : codes) {
                results.add(pool.submit(() -> TenantContext.runAs(tenant, () -> {
                    go.await();
                    try { return ledger.bindLabel(c.id(), owner, null, c.code()).pieceId(); }
                    catch (LabelCodeException e) { return e.code().name(); }
                })));
            }
            List<String> out = new ArrayList<>();
            for (Future<String> f : results) out.add(f.get(60, TimeUnit.SECONDS));
            assertThat(out).filteredOn(s -> s.equals("NO_UNTAGGED_STOCK")).hasSize(1);
            List<String> pieces = out.stream().filter(s -> !s.equals("NO_UNTAGGED_STOCK")).toList();
            assertThat(new HashSet<>(pieces)).as("two codes, two different pieces").hasSize(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(untagged(variantA)).isZero();
        assertThat(count("SELECT count(DISTINCT short_code) FROM pieces WHERE variant_id = ?", variantA)).isEqualTo(2);
    }

    @Test
    void b4b_a_bind_held_open_never_blocks_or_shares_its_piece_with_another_bind() throws Exception {
        countOnly(variantA, 2);
        List<LabelCodeService.IssuedCode> codes = labels.issue(owner, "tag",
            List.of(new LabelCodeService.Line(variantA, 2, null, null)), "b4b").codes();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch aBound = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A binds and keeps its transaction (and its piece's row lock) open.
            Future<String> a = pool.submit(() -> TenantContext.runAs(tenant, () -> tx.execute(st -> {
                String piece = ledger.bindLabel(codes.get(0).id(), owner, null, codes.get(0).code()).pieceId();
                aBound.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return piece;
            })));
            assertThat(aBound.await(30, TimeUnit.SECONDS)).isTrue();
            // B, meanwhile: skips A's locked piece and takes the other one at once — never waits for A.
            Future<String> b = pool.submit(() -> TenantContext.runAs(tenant,
                () -> ledger.bindLabel(codes.get(1).id(), owner, null, codes.get(1).code()).pieceId()));
            String bPiece = b.get(5, TimeUnit.SECONDS);
            release.countDown();
            assertThat(bPiece).isNotEqualTo(a.get(30, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void b5_tagging_moves_counts_but_no_stock_and_never_calls_shopify() {
        printStick(variantA, 2);
        countOnly(variantA, 3);
        reset(shopifyInventory);
        var before = variantStock.forVariant(variantStock.computeAll(), variantA);
        var c = code(variantA);
        ledger.bindLabel(c.id(), owner, null, c.code());
        var after = variantStock.forVariant(variantStock.computeAll(), variantA);
        assertThat(labeled(variantA)).isEqualTo(3);
        assertThat(untagged(variantA)).isEqualTo(2);
        assertThat(after).isEqualTo(before);
        assertThat(after.onHand()).isEqualTo(5);
        verifyNoInteractions(shopifyInventory);
    }

    @Test
    void b6_a_bound_piece_works_in_the_normal_paths() throws Exception {
        countOnly(variantA, 3);
        List<String> pieces = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            var c = code(variantA);
            pieces.add(ledger.bindLabel(c.id(), owner, null, c.code()).pieceId());
            codes.add(c.code());
        }
        assertThat(lookup.lookupPiece(codes.get(0), false)).containsEntry("canReprint", true).containsEntry("shortCode", codes.get(0));
        assertThat(lookupReprint.reprint(pieces.get(0), owner)).isNotEmpty();

        UUID transfer = transfers.createTransfer("showroom", side, null, "t", owner);
        assertThat(transfers.scanOut(transfer, codes.get(1), owner).success()).as("a bound piece transfers").isTrue();

        adjust.hold(pieces.get(2), "quality_check", null, owner);
        assertThat(jdbc.queryForObject("SELECT status::text FROM pieces WHERE id = ?", String.class, pieces.get(2))).isEqualTo("on_hold");
    }

    // ── an issued code in the other scan paths ───────────────────────────────

    @Test
    void s1_an_unused_label_says_tag_it_first_everywhere() {
        printStick(variantA, 1);
        countOnly(variantA, 1);
        var c = code(variantA);

        // Slice 4: with the switch on the pick scan BINDS the label (PackLabelsTest); with it off it still says this.
        FulfillService.ScanResult pick;
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        try {
            pick = fulfill.scan(UUID.randomUUID(), c.code(), owner);
        } finally {
            jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);
        }
        assertThat(pick.code()).isEqualTo("LABEL_NOT_USED_YET");
        assertThat(pick.message()).isEqualTo("Label not used yet — tag it in Tag stock first");

        UUID transfer = transfers.createTransfer("showroom", side, null, "t", owner);
        assertThat(transfers.scanOut(transfer, c.code(), owner).code()).isEqualTo("LABEL_NOT_USED_YET");

        UUID rs = returnSessions.createSession(null, owner);
        assertCode(() -> returnSessions.scan(rs, c.code(), main, owner, false), "LABEL_NOT_USED_YET");

        UUID st = (UUID) stockTakes.openSession("variant_subset", List.of(variantB), main, null, owner).get("sessionId");
        assertCode(() -> stockTakes.scan(st, c.code(), "good", owner, false), "LABEL_NOT_USED_YET");

        Map<String, Object> l = lookup.lookupPiece(c.code().toLowerCase(), false);
        assertThat(l).containsEntry("type", "label_code").containsEntry("status", "issued").containsEntry("code", c.code());
        assertThat((Map<String, Object>) l.get("variant")).containsEntry("title", "Blue").containsEntry("productTitle", "Tag Shirt");

        labels.voidCodes(List.of(c.id()), "torn");
        assertThat(fulfill.scan(UUID.randomUUID(), c.code(), owner).code()).as("voided → refused by name (slice 4)").isEqualTo("CODE_VOIDED");
    }

    // ── Tag stock ─────────────────────────────────────────────────────────────

    @Test
    void t1_tag_stock_session_end_to_end() {
        printStick(variantA, 1);
        countOnly(variantA, 2);
        countOnly(variantB, 4);

        TagStockService.Overview o = tagStock.overview(null);
        assertThat(o.untagged()).isEqualTo(6);
        assertThat(o.labeled()).isEqualTo(1);
        assertThat(o.variants()).extracting(TagStockService.VariantStock::variantId).containsExactly(variantB, variantA);
        assertThat(tagStock.overview("red").variants()).extracting(TagStockService.VariantStock::variantId).containsExactly(variantB);

        TagStockService.Session s = tagStock.start(owner);
        assertThat(tagStock.start(owner).id()).as("resumes the open session").isEqualTo(s.id());
        assertCode(() -> tagStock.print(s.id(), variantA, 3, "t1-too-many", owner), "TOO_MANY_LABELS");
        LabelCodeService.Batch b = tagStock.print(s.id(), variantA, 2, "t1-a", owner);
        LabelCodeService.Batch rep = tagStock.replacement(s.id(), variantA, "t1-rep", owner);
        assertThat(rep.codes()).singleElement().extracting(LabelCodeService.IssuedCode::variantId).isEqualTo(variantA);

        TagStockService.ScanOutcome first = tagStock.scan(s.id(), b.codes().get(0).code(), owner, true);
        assertThat(first.outcome()).isEqualTo("TAGGED");
        assertThat(first.viaPhone()).isTrue();
        assertThat(first.variant()).extracting("untagged", "labeled").containsExactly(1, 2);
        assertThat(first.taggedThisSession()).isEqualTo(1);
        assertThat(tagStock.scan(s.id(), b.codes().get(0).code(), owner, false).outcome()).isEqualTo("ALREADY_TAGGED");
        String pieceLabel = jdbc.queryForObject("SELECT short_code FROM pieces WHERE receipt_id IS NOT NULL AND short_code LIKE 'P%' " +
            "AND variant_id = ? ORDER BY created_at LIMIT 1", String.class, variantA);
        assertThat(tagStock.scan(s.id(), pieceLabel, owner, false).outcome()).as("any piece label").isEqualTo("ALREADY_TAGGED");
        assertThat(tagStock.scan(s.id(), "P0999999", owner, false).outcome()).isEqualTo("REJECTED:UNKNOWN_CODE");
        assertThat(tagStock.scan(s.id(), rep.codes().get(0).code(), owner, false).outcome()).isEqualTo("TAGGED");
        TagStockService.ScanOutcome none = tagStock.scan(s.id(), b.codes().get(1).code(), owner, false);
        assertThat(none.outcome()).isEqualTo("REJECTED:NO_UNTAGGED_STOCK");
        assertThat(none.messageAr()).isNotBlank();

        TagStockService.Session summary = tagStock.get(s.id());
        assertThat(summary.tagged()).isEqualTo(2);
        assertThat(summary.variants()).singleElement().extracting("variantId", "printed", "tagged", "unused", "untaggedLeft", "labeled")
            .containsExactly(variantA, 3, 2, 1, 0, 3);

        // Void unused: only this session's still-issued labels.
        var outside = code(variantB);
        assertThat(tagStock.voidUnused(s.id(), null)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM label_codes WHERE id = ?", String.class, outside.id())).isEqualTo("issued");

        TagStockService.Session ended = tagStock.end(s.id(), owner);
        assertThat(ended.endedAt()).isNotNull();
        assertCode(() -> tagStock.scan(s.id(), outside.code(), owner, false), "TAG_SESSION_ENDED");
        assertThat(tagStock.start(owner).id()).as("a new session after the end").isNotEqualTo(s.id());
    }

    // ── reprint instead of new codes (live-test fix, 2026-10-25) ──────────────────

    private static String pdfText(byte[] pdf) throws java.io.IOException {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    private int codesOf(UUID variant) { return count("SELECT count(*) FROM label_codes WHERE variant_id = ?", variant); }

    @Test
    void r1_a_second_print_in_the_same_session_reprints_the_same_codes_and_issues_none() throws Exception {
        countOnly(variantA, 10);
        TagStockService.Session s = tagStock.start(owner);
        LabelCodeService.Batch b = tagStock.print(s.id(), variantA, 3, "r1-a", owner);
        assertThat(tagStock.overview(null, owner).variants()).filteredOn(v -> v.variantId().equals(variantA))
            .singleElement().extracting(TagStockService.VariantStock::unusedThisSession).isEqualTo(3);

        var outside = code(variantA);                                                   // not printed in this session
        int before = codesOf(variantA);
        String text = pdfText(tagStock.reprintUnused(s.id(), variantA));
        for (LabelCodeService.IssuedCode c : b.codes()) assertThat(text).contains(c.code());
        assertThat(text).as("only this session's labels").doesNotContain(outside.code());
        assertThat(codesOf(variantA)).as("a reprint issues nothing").isEqualTo(before);

        tagStock.scan(s.id(), b.codes().get(0).code(), owner, false);                   // one is used now
        String after = pdfText(tagStock.reprintUnused(s.id(), variantA));
        assertThat(after).doesNotContain(b.codes().get(0).code()).contains(b.codes().get(1).code());

        // "Print new labels" still issues new ones.
        LabelCodeService.Batch more = tagStock.print(s.id(), variantA, 2, "r1-b", owner);
        assertThat(more.codes()).hasSize(2).extracting(LabelCodeService.IssuedCode::code).doesNotContainAnyElementsOf(
            b.codes().stream().map(LabelCodeService.IssuedCode::code).toList());
        assertThat(codesOf(variantA)).isEqualTo(before + 2);

        // Without a session open the list shows 0 unused this session; another session's codes don't count.
        assertThat(tagStock.overview(null, worker).variants()).filteredOn(v -> v.variantId().equals(variantA))
            .singleElement().extracting(TagStockService.VariantStock::unusedThisSession).isEqualTo(0);
        assertCode(() -> tagStock.reprintUnused(s.id(), variantB), "NO_ISSUED_CODES");
    }

    @Test
    void r2_a_replacement_reuses_an_unused_replacement_code_of_the_variant() {
        countOnly(variantA, 3);
        TagStockService.Session s = tagStock.start(owner);
        LabelCodeService.Batch first = tagStock.replacement(s.id(), variantA, "r2-1", owner);
        int before = codesOf(variantA);
        LabelCodeService.Batch again = tagStock.replacement(s.id(), variantA, "r2-2", owner);
        assertThat(again.created()).isFalse();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.codes()).singleElement().extracting(LabelCodeService.IssuedCode::code).isEqualTo(first.codes().get(0).code());
        assertThat(codesOf(variantA)).as("reused, none issued").isEqualTo(before);

        tagStock.scan(s.id(), first.codes().get(0).code(), owner, false);              // used → a new one next time
        LabelCodeService.Batch fresh = tagStock.replacement(s.id(), variantA, "r2-3", owner);
        assertThat(fresh.created()).isTrue();
        assertThat(fresh.codes().get(0).code()).isNotEqualTo(first.codes().get(0).code());
        assertThat(tagStock.replacement(s.id(), variantB, "r2-4", owner).codes().get(0).variantId())
            .as("never another variant's code").isEqualTo(variantB);
    }

    @Test
    void r3_leftovers_from_ended_sessions_are_listed_reprinted_and_voided() throws Exception {
        countOnly(variantA, 5);
        TagStockService.Session s = tagStock.start(owner);
        LabelCodeService.Batch b = tagStock.print(s.id(), variantA, 3, "r3-a", owner);
        tagStock.scan(s.id(), b.codes().get(0).code(), owner, false);
        assertThat(tagStock.overview(null, owner).leftovers()).as("an open session's labels aren't leftovers").isEmpty();

        tagStock.end(s.id(), owner);
        List<TagStockService.Leftover> left = tagStock.overview(null, owner).leftovers();
        assertThat(left).singleElement().satisfies(l -> {
            assertThat(l.variantId()).isEqualTo(variantA);
            assertThat(l.count()).isEqualTo(2);
            assertThat(l.codeIds()).containsExactlyInAnyOrder(b.codes().get(1).id(), b.codes().get(2).id());
        });
        int before = codesOf(variantA);
        String text = pdfText(tagStock.leftoverPdf(variantA));
        assertThat(text).contains(b.codes().get(1).code()).contains(b.codes().get(2).code()).doesNotContain(b.codes().get(0).code());
        assertThat(codesOf(variantA)).isEqualTo(before);

        // Void = the existing all-or-nothing void.
        assertThat(labels.voidCodes(left.get(0).codeIds(), "tag_session_leftover")).isEqualTo(2);
        assertThat(tagStock.overview(null, owner).leftovers()).isEmpty();
        assertCode(() -> tagStock.leftoverPdf(variantA), "NO_ISSUED_CODES");
    }

    @Test
    void r4_switch_off_hides_reprint_and_leftovers_and_http_answers_a_pdf_when_on() {
        countOnly(variantA, 2);
        TagStockService.Session s = tagStock.start(owner);
        tagStock.print(s.id(), variantA, 1, "r4-a", owner);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(owner, tenant, "owner"));
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> pdf = rest.exchange(base() + "/api/v1/tag-stock/sessions/" + s.id() + "/reprint", HttpMethod.POST,
            new HttpEntity<>(Map.of("variantId", variantA.toString()), h), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType().toString()).isEqualTo("application/pdf");

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        try {
            assertThatThrownBy(() -> tagStock.reprintUnused(s.id(), variantA)).hasMessageContaining("404");
            assertThatThrownBy(() -> tagStock.leftoverPdf(variantA)).hasMessageContaining("404");
            assertThat(rest.exchange(base() + "/api/v1/tag-stock/leftovers/" + variantA + "/pdf", HttpMethod.POST,
                new HttpEntity<>(h), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);
        }
    }

    // ── flag, roles, RLS ──────────────────────────────────────────────────────

    @Test
    void f1_flag_off_and_workers_are_refused() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        assertThatThrownBy(() -> tagStock.overview(null)).hasMessageContaining("404");
        assertThatThrownBy(() -> tagStock.start(owner)).hasMessageContaining("404");
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(owner, tenant, "owner"));
        assertThat(rest.exchange(base() + "/api/v1/tag-stock", HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);
        assertThat(rest.exchange(base() + "/api/v1/tag-stock", HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        HttpHeaders w = new HttpHeaders();
        w.setBearerAuth(jwt.issueAccessToken(worker, tenant, "worker"));
        assertThat(rest.exchange(base() + "/api/v1/tag-stock", HttpMethod.GET, new HttpEntity<>(w), String.class).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void x1_app_user_sees_only_its_own_codes_and_stock() {
        countOnly(variantA, 2);
        var c = code(variantA);

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        JdbcTemplate app = new JdbcTemplate(ds);
        InventoryLedger appLedger = new InventoryLedger(app);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

        TenantContext.set(other);
        assertCode(() -> tx.execute(x -> appLedger.bindLabel(c.id(), otherOwner, null, c.code())), "UNKNOWN_CODE");
        Integer theirSessions = tx.execute(x -> app.queryForObject("SELECT count(*)::int FROM tag_sessions", Integer.class));
        assertThat(theirSessions).isZero();
        TenantContext.set(tenant);
        assertThat(jdbc.queryForObject("SELECT status FROM label_codes WHERE id = ?", String.class, c.id())).isEqualTo("issued");

        InventoryLedger.BindResult r = tx.execute(x -> appLedger.bindLabel(c.id(), owner, null, c.code()));   // positive control
        assertThat(r.alreadyBound()).isFalse();
        UUID session = tagStock.start(owner).id();
        assertThatThrownBy(() -> tx.execute(x -> app.update("DELETE FROM tag_sessions WHERE id = ?", session)))
            .isInstanceOf(DataAccessException.class).cause().hasMessageContaining("permission denied");
    }

    private String base() { return "http://localhost:" + port; }
}
