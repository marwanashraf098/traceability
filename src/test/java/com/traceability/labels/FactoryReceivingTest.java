package com.traceability.labels;

import com.traceability.identity.JwtService;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.LabelService;
import com.traceability.inventory.LookupService;
import com.traceability.inventory.ReceivingService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Flexible labeling slice 6b — factory receiving (Receiving → "Factory-labeled").
 *
 *   r1  every scan rejection, in order, with its readable EN / AR message; a rejection stages nothing
 *   s1  one live staging per code across sessions: another session's scan is refused, the partial UNIQUE refuses a
 *       second live row outright, and after an undo the code can be scanned elsewhere
 *   u1  undo last scan (newest first; nothing left → NOTHING_TO_UNDO); delete refuses while scans are live
 *       (UNDO_SCANS_FIRST) and works once they're undone
 *   o1  "Piece without a label": one factory code for the line, overage only when the line is already full; the same
 *       key returns the same code; it scans and is received like any other
 *   f1  finalize all-or-nothing: a code voided between scan and finalize refuses the whole finalize — no piece, no
 *       receipt line, no Shopify call, the session still open, the other codes still issued
 *   f2  finalize: the pieces carry exactly the scanned codes, the codes are bound to them, the received events name the
 *       code and the run, receipt_lines per variant, Shopify +N once through the receiving trigger; a second finalize 409
 *   f3  two sessions of one run finalized at the same moment: both receive, every code bound once, Shopify once each
 *   f4  a run received across two sessions (progress counts both)
 *   l1  Lookup: "Received from factory run «factory · reference»"
 *   p1  Print session labels on a factory receipt = the same codes, logged (label_reprints + label_reprinted per
 *       piece), never a new code; the unlogged print routes refuse it
 *   x1  a factory session takes no lines and no Excel import
 *   h1  HTTP: switch off → 404 (create + scan); worker → 403; main warehouse only; open run only; a run only for factory
 *   a1  as app_user: scan, undo and finalize work under RLS; another tenant sees none of the scans (positive control);
 *       a scan row can't be edited or deleted
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactoryReceivingTest {

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
    @Autowired LabelRunService runs;
    @Autowired LabelCodeService labelCodes;
    @Autowired FactoryReceivingService factory;
    @Autowired ReceivingService receiving;
    @Autowired LabelService labels;
    @Autowired LookupService lookup;
    @MockBean  ShopifyInventoryService shopifyInventory;

    UUID tenant, owner, worker, main, other, shirtS, mug, otherTenant, otherOwner, otherMain, otherVariant;
    JdbcTemplate app;
    TransactionTemplate appTx;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); worker = UUID.randomUUID();
        main = UUID.randomUUID(); other = UUID.randomUUID();
        otherTenant = UUID.randomUUID(); otherOwner = UUID.randomUUID(); otherMain = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, 'FR Tenant', true), (?, 'FR Other', true)",
            tenant, otherTenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'Mona', 'fr-o@test.local', 'h', 'owner'), (?, ?, 'W', 'fr-w@test.local', 'h', 'worker'), " +
            "(?, ?, 'Other', 'fr-x@test.local', 'h', 'owner')", owner, tenant, worker, tenant, otherOwner, otherTenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES " +
            "(?, ?, 'FR Main', 'warehouse', true, true), (?, ?, 'FR Shop', 'warehouse', false, false), " +
            "(?, ?, 'FR Other Main', 'warehouse', true, true)", main, tenant, other, tenant, otherMain, otherTenant);
        UUID store = store(tenant, "fr");
        shirtS = variant(tenant, product(tenant, store, "Linen Shirt", "linen-shirt"), "S", "SHIRT-S");
        mug    = variant(tenant, product(tenant, store, "Mug", "mug"), "Default Title", "MUG-1");
        otherVariant = variant(otherTenant, product(otherTenant, store(otherTenant, "fr-x"), "Other", "other"), "X", "OTHER-X");

        DriverManagerDataSource raw = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource ds = new TenantAwareDataSource(raw);
        app = new JdbcTemplate(ds);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private UUID store(UUID t, String p) {
        UUID s = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            s, t, p + ".myshopify.com");
        return s;
    }

    private UUID product(UUID t, UUID store, String title, String handle) {
        UUID p = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            p, t, store, "P-" + handle, title);
        return p;
    }

    private UUID variant(UUID t, UUID product, String title, String sku) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, t, product, "V-" + sku, title, sku);
        return v;
    }

    @BeforeEach
    void each() {
        reset(shopifyInventory);
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id IN (?, ?)", tenant, otherTenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (UUID t : List.of(tenant, otherTenant)) {
            for (String table : List.of("receipt_factory_scans", "label_reprints", "label_codes", "label_code_batches",
                    "piece_events", "pieces", "receipt_lines", "receipts", "label_run_lines", "label_runs", "piece_counters")) {
                jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", t);
            }
        }
    }

    // ── r1: rejections ────────────────────────────────────────────────────────

    @Test
    void r1_every_rejection_in_order_with_a_readable_message() throws Exception {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 4);
        UUID run2 = printedRun("Delta Knits", null, mug, 1);
        List<String> codes = codes(run, shirtS);
        UUID s1 = session(run), s2 = session(run);

        // a piece's own short code: an ordinary print & stick piece
        UUID ps = receiving.createSession(owner, main, "PS", null, null, ReceivingService.MODE_PRINT_STICK);
        receiving.addLine(ps, mug, 1);
        receiving.finalize(ps, owner);
        String pieceCode = jdbc.queryForObject("SELECT short_code FROM pieces WHERE receipt_id = ?", String.class, ps);
        String tag = labelCodes.issue(owner, "tag", List.of(new LabelCodeService.Line(mug, 1, null, null)), "fr-tag").codes().get(0).code();
        String pack = labelCodes.issue(owner, "pack", List.of(new LabelCodeService.Line(mug, 1, null, null)), "fr-pack").codes().get(0).code();
        labelCodes.voidCodes(List.of(codeId(codes.get(1))), "test");
        assertThat(scan(s1, codes.get(0)).outcome()).isEqualTo("STAGED");

        assertRejected(scan(s1, "P999999"), "UNKNOWN_CODE", "isn't one of yours");
        assertRejected(scan(s1, pieceCode), "PIECE_ALREADY_IN_STOCK", "already in stock");
        assertRejected(scan(s1, tag), "NOT_FACTORY_LABEL", "Tag stock label, not a factory label");
        assertRejected(scan(s1, pack), "NOT_FACTORY_LABEL", "packing label, not a factory label");
        assertRejected(scan(s1, codes.get(1)), "CODE_VOIDED", "voided");
        assertRejected(scan(s1, codes(run2, mug).get(0)), "OTHER_RUN", "belongs to factory run «Delta Knits»");
        assertRejected(scan(s1, codes.get(0)), "ALREADY_SCANNED", "Already scanned in this session");
        assertRejected(scan(s2, codes.get(0)), "SCANNED_IN_OTHER_SESSION", "another receiving session");
        // received: finalize s1, then the same code again elsewhere
        receiving.finalize(s1, owner);
        assertRejected(scan(s2, codes.get(0)), "ALREADY_RECEIVED", "Already received");

        FactoryReceivingService.ScanOutcome ar = scan(s2, "P999999");
        assertThat(ar.messageAr()).isNotBlank();
        assertThat(liveScans(s2)).as("rejections stage nothing").isZero();
    }

    // ── s1: staging uniqueness ────────────────────────────────────────────────

    @Test
    void s1_one_live_staging_per_code_across_sessions() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 2);
        String code = codes(run, shirtS).get(0);
        UUID a = session(run), b = session(run);
        assertThat(scan(a, code).outcome()).isEqualTo("STAGED");
        assertRejected(scan(b, code), "SCANNED_IN_OTHER_SESSION", "another receiving session");
        UUID line = jdbc.queryForObject("SELECT run_line_id FROM label_codes WHERE code = ? AND tenant_id = ?", UUID.class, code, tenant);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO receipt_factory_scans (tenant_id, receipt_id, label_code_id, run_line_id, variant_id, scanned_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)", tenant, b, codeId(code), line, shirtS, owner))
            .as("the partial UNIQUE refuses a second live row").hasMessageContaining("ux_receipt_factory_scans_live");

        factory.undoLast(a, owner);
        assertThat(scan(b, code).outcome()).as("after the undo, the code can be scanned elsewhere").isEqualTo("STAGED");
        assertThat(liveScans(a)).isZero();
        assertThat(liveScans(b)).isEqualTo(1);
    }

    // ── u1: undo / delete ─────────────────────────────────────────────────────

    @Test
    void u1_undo_last_and_delete_only_once_nothing_is_live() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 3);
        List<String> codes = codes(run, shirtS);
        UUID s = session(run);
        scan(s, codes.get(0));
        scan(s, codes.get(1));
        FactoryReceivingService.UndoResult u = factory.undoLast(s, owner);
        assertThat(u.code()).as("newest first").isEqualTo(codes.get(1));
        assertThat(u.progress().staged()).isEqualTo(1);

        assertThatThrownBy(() -> receiving.deleteSession(s)).isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(LabelCodeException.Code.UNDO_SCANS_FIRST));
        assertThat(factory.undoLast(s, owner).code()).isEqualTo(codes.get(0));
        assertRefused(() -> factory.undoLast(s, owner), LabelCodeException.Code.NOTHING_TO_UNDO);

        receiving.deleteSession(s);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM receipts WHERE id = ?", Integer.class, s)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM receipt_factory_scans WHERE receipt_id = ?", Integer.class, s))
            .as("the undone scans went with the session").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM label_codes WHERE code = ANY(?) AND status = 'issued'",
            Integer.class, (Object) codes.toArray(new String[0]))).as("the labels stay valid").isEqualTo(3);
    }

    // ── o1: piece without a label / overage ──────────────────────────────────

    @Test
    void o1_piece_without_a_label_is_overage_only_when_the_line_is_full() throws Exception {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 2);
        List<String> codes = codes(run, shirtS);
        UUID s = session(run);

        FactoryReceivingService.ExtraLabel first = factory.extraLabel(s, shirtS, "k-1", owner);
        assertThat(first.overage()).as("0 of 2 here — a lost label, not overage").isFalse();
        assertThat(factory.extraLabel(s, shirtS, "k-1", owner).codeId()).as("same key, same code").isEqualTo(first.codeId());

        scan(s, codes.get(0));
        scan(s, codes.get(1));
        FactoryReceivingService.ExtraLabel extra = factory.extraLabel(s, shirtS, "k-2", owner);
        assertThat(extra.overage()).as("2 of 2 staged — the line is full").isTrue();
        assertThat(jdbc.queryForObject("SELECT kind || ':' || overage FROM label_codes WHERE id = ?", String.class, extra.codeId()))
            .isEqualTo("factory:true");
        assertRefused(() -> factory.extraLabel(s, mug, "k-3", owner), LabelCodeException.Code.UNKNOWN_VARIANT);

        FactoryReceivingService.ScanOutcome o = scan(s, extra.code());
        assertThat(o.outcome()).isEqualTo("STAGED");
        assertThat(o.overage()).isTrue();
        assertThat(receiving.finalize(s, owner)).isEqualTo(3);
        LabelRunService.RunLine line = runs.get(run).lines().get(0);
        assertThat(line.received()).isEqualTo(3);
        assertThat(line.overage()).isEqualTo(1);
        assertThat(factory.extraLabelPdf(s, first.codeId())).startsWith("%PDF".getBytes());
    }

    // ── f1 / f2 / f3 / f4: finalize ───────────────────────────────────────────

    @Test
    void f1_a_code_voided_after_its_scan_refuses_the_whole_finalize() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 3);
        List<String> codes = codes(run, shirtS);
        UUID s = session(run);
        codes.forEach(c -> scan(s, c));
        labelCodes.voidCodes(List.of(codeId(codes.get(1))), "lost at the factory");

        assertThatThrownBy(() -> receiving.finalize(s, owner)).isInstanceOf(LabelCodeException.class)
            .satisfies(e -> {
                LabelCodeException l = (LabelCodeException) e;
                assertThat(l.code()).isEqualTo(LabelCodeException.Code.FACTORY_CODES_CHANGED);
                assertThat(l.details()).extracting(d -> d.get("code")).containsExactly(codes.get(1));
            });
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM pieces WHERE receipt_id = ?", Integer.class, s)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM receipt_lines WHERE receipt_id = ?", Integer.class, s)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM receipts WHERE id = ?", String.class, s)).isEqualTo("open");
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM label_codes WHERE code IN (?, ?) AND status = 'issued'",
            Integer.class, codes.get(0), codes.get(2))).isEqualTo(2);
        verifyNoInteractions(shopifyInventory);
    }

    @Test
    void f2_pieces_carry_the_scanned_codes_and_shopify_gets_n_once() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 3, mug, 2);
        List<String> shirts = codes(run, shirtS), mugs = codes(run, mug);
        UUID s = session(run);
        List<String> scanned = List.of(shirts.get(0), mugs.get(1), shirts.get(2));
        scanned.forEach(c -> scan(s, c));

        assertThat(receiving.finalize(s, owner)).isEqualTo(3);

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.short_code, p.variant_id, p.current_location_id, c.status, c.piece_id, c.code " +
            "FROM pieces p JOIN label_codes c ON c.code = p.short_code AND c.tenant_id = p.tenant_id WHERE p.receipt_id = ?", s);
        assertThat(pieces).extracting(p -> p.get("short_code")).containsExactlyInAnyOrderElementsOf(scanned);
        assertThat(pieces).allSatisfy(p -> {
            assertThat(p.get("status")).isEqualTo("bound");
            assertThat(p.get("piece_id")).isEqualTo(p.get("id"));
            assertThat(p.get("current_location_id")).isEqualTo(main);
        });
        List<String> meta = jdbc.queryForList("SELECT e.metadata::text FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.receipt_id = ? AND e.event_type = 'received'", String.class, s);
        assertThat(meta).hasSize(3).allSatisfy(m -> assertThat(m).contains("\"label_run_id\": \"" + run + "\"").contains("label_code_id"));
        assertThat(jdbc.queryForList("SELECT variant_id, quantity FROM receipt_lines WHERE receipt_id = ?", s))
            .extracting(r -> r.get("variant_id") + "=" + r.get("quantity"))
            .containsExactlyInAnyOrder(shirtS + "=2", mug + "=1");
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id " +
            "WHERE l.run_id = ?", Integer.class, run)).as("no code issued by receiving").isEqualTo(5);

        verify(shopifyInventory, times(1)).onReceivingSessionClose(eq(tenant), eq(s), eq(main), eq(Map.of(shirtS, 2, mug, 1)));
        verifyNoMoreInteractions(shopifyInventory);
        assertThatThrownBy(() -> receiving.finalize(s, owner)).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void f3_two_sessions_of_one_run_finalized_at_once_both_receive_once() throws Exception {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 4);
        List<String> codes = codes(run, shirtS);
        UUID a = session(run), b = session(run);
        scan(a, codes.get(0)); scan(a, codes.get(1));
        scan(b, codes.get(2));
        TenantContext.clear();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> calls = new ArrayList<>();
        try (Connection holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "postgres")) {
            holder.setAutoCommit(false);
            try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM label_runs WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, run);
                ps.executeQuery();
            }
            for (UUID s : List.of(a, b)) {
                calls.add(pool.submit(() -> {
                    TenantContext.set(tenant);
                    try {
                        return (Object) receiving.finalize(s, owner);
                    } catch (Exception e) {
                        return e;
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            long deadline = System.currentTimeMillis() + 15_000;
            while (waitingOnLocks() < 2) {
                if (System.currentTimeMillis() > deadline) fail("the two finalizes never both waited on the run");
                Thread.sleep(25);
            }
            holder.commit();
        }
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : calls) results.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(results).containsExactly(2, 1);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM pieces WHERE receipt_id IN (?, ?)", Integer.class, a, b)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id " +
            "WHERE l.run_id = ? AND c.status = 'bound'", Integer.class, run)).isEqualTo(3);
        verify(shopifyInventory, times(1)).onReceivingSessionClose(eq(tenant), eq(a), eq(main), eq(Map.of(shirtS, 2)));
        verify(shopifyInventory, times(1)).onReceivingSessionClose(eq(tenant), eq(b), eq(main), eq(Map.of(shirtS, 1)));
    }

    @Test
    void f4_a_run_received_across_two_sessions() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 3);
        List<String> codes = codes(run, shirtS);
        UUID a = session(run);
        scan(a, codes.get(0)); scan(a, codes.get(1));
        receiving.finalize(a, owner);
        UUID b = session(run);
        FactoryReceivingService.ScanOutcome o = scan(b, codes.get(2));
        assertThat(o.line().received()).as("received in the first session").isEqualTo(2);
        assertThat(o.line().staged()).isEqualTo(1);
        assertRejected(scan(b, codes.get(0)), "ALREADY_RECEIVED", "Already received");
        receiving.finalize(b, owner);
        assertThat(runs.get(run).received()).isEqualTo(3);
        assertThat(runs.list()).filteredOn(r -> r.id().equals(run)).extracting(LabelRunService.RunSummary::received).containsExactly(3);
    }

    // ── l1: Lookup ────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void l1_lookup_names_the_factory_run() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 1);
        UUID run2 = printedRun("Delta Knits", null, mug, 1);
        UUID s = session(run), s2 = session(run2);
        String code = codes(run, shirtS).get(0), code2 = codes(run2, mug).get(0);
        scan(s, code); scan(s2, code2);
        receiving.finalize(s, owner); receiving.finalize(s2, owner);

        List<Map<String, Object>> timeline = (List<Map<String, Object>>) lookup.lookupPiece(code, false).get("timeline");
        assertThat(timeline).filteredOn(e -> "received".equals(e.get("eventType")))
            .singleElement().satisfies(e -> {
                assertThat(e.get("phraseKey")).isEqualTo("received_factory_run");
                assertThat(e.get("factoryRun")).isEqualTo("Nile Textiles · PO-7");
            });
        List<Map<String, Object>> t2 = (List<Map<String, Object>>) lookup.lookupPiece(code2, false).get("timeline");
        assertThat(t2).filteredOn(e -> "received".equals(e.get("eventType"))).extracting(e -> e.get("factoryRun"))
            .containsExactly("Delta Knits");
    }

    // ── p1: print session labels ──────────────────────────────────────────────

    @Test
    void p1_print_session_labels_reprints_the_same_codes_and_logs_it() throws Exception {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 2);
        List<String> codes = codes(run, shirtS);
        UUID s = session(run);
        codes.forEach(c -> scan(s, c));
        receiving.finalize(s, owner);
        int codesBefore = jdbc.queryForObject("SELECT count(*)::int FROM label_codes WHERE tenant_id = ?", Integer.class, tenant);

        assertRefused(() -> labels.generateSessionLabels(s, null, null), LabelCodeException.Code.FACTORY_REPRINT_ONLY);
        assertRefused(() -> labels.generateVariantLabels(s, shirtS, null, null), LabelCodeException.Code.FACTORY_REPRINT_ONLY);

        byte[] pdf = labels.reprint(s, owner, "factory reprint", null, null);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            codes.forEach(c -> assertThat(text).contains(c));
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.receipt_id = ? AND e.event_type = 'label_reprinted'", Integer.class, s)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM label_reprints WHERE receipt_id = ?", Integer.class, s)).isEqualTo(1);
        labels.reprintVariant(s, shirtS, owner, null, null, null);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM piece_events e JOIN pieces p ON p.id = e.piece_id " +
            "WHERE p.receipt_id = ? AND e.event_type = 'label_reprinted'", Integer.class, s)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM label_codes WHERE tenant_id = ?", Integer.class, tenant))
            .as("never a new code").isEqualTo(codesBefore);
    }

    // ── x1: no lines ──────────────────────────────────────────────────────────

    @Test
    void x1_a_factory_session_takes_no_lines() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 1);
        UUID s = session(run);
        assertRefused(() -> receiving.addLine(s, shirtS, 1), LabelCodeException.Code.FACTORY_SESSION_NO_LINES);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM receipt_lines WHERE receipt_id = ?", Integer.class, s)).isZero();
        assertRefused(() -> receiving.finalize(s, owner), LabelCodeException.Code.NO_FACTORY_SCANS);
    }

    // ── h1: HTTP ──────────────────────────────────────────────────────────────

    @Test
    void h1_http_switch_role_location_and_run_rules() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 1);
        String code = codes(run, shirtS).get(0);
        TenantContext.clear();
        Map<String, Object> body = new HashMap<>(Map.of("locationId", main.toString(), "mode", "factory", "labelRunId", run.toString()));

        assertThat(post("/api/v1/receiving/sessions", body, worker, "worker").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<String> nonMain = post("/api/v1/receiving/sessions",
            Map.of("locationId", other.toString(), "mode", "factory", "labelRunId", run.toString()), owner, "owner");
        assertThat(nonMain.getBody()).contains("FACTORY_MAIN_WAREHOUSE_ONLY");
        assertThat(post("/api/v1/receiving/sessions", Map.of("locationId", main.toString(), "mode", "print_stick",
            "labelRunId", run.toString()), owner, "owner").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> created = rest.exchange(base() + "/api/v1/receiving/sessions", HttpMethod.POST,
            new HttpEntity<>(body, json(owner, "owner")), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String session = (String) created.getBody().get("sessionId");
        ResponseEntity<String> detail = rest.exchange(base() + "/api/v1/receiving/sessions/" + session, HttpMethod.GET,
            new HttpEntity<>(json(owner, "owner")), String.class);
        assertThat(detail.getBody()).contains("\"mode\":\"factory\"").contains("Nile Textiles").contains("\"supplier_name\":\"Nile Textiles\"");
        assertThat(post("/api/v1/receiving/sessions/" + session + "/factory/scan", Map.of("code", code), owner, "owner").getBody())
            .contains("\"outcome\":\"STAGED\"");
        assertThat(post("/api/v1/receiving/sessions/" + session + "/factory/scan", Map.of("code", code), worker, "worker")
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        assertThat(post("/api/v1/receiving/sessions", body, owner, "owner").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/receiving/sessions/" + session + "/factory/scan", Map.of("code", code), owner, "owner")
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/receiving/sessions/" + session + "/factory/undo", null, owner, "owner")
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/v1/receiving/sessions/" + session + "/finalize", null, owner, "owner")
            .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM pieces WHERE receipt_id = ?::uuid", Integer.class, session)).isZero();

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);
        jdbc.update("UPDATE label_runs SET status = 'closed', closed_at = now(), closed_by = ? WHERE id = ?", owner, run);
        assertThat(post("/api/v1/receiving/sessions", body, owner, "owner").getBody()).contains("RUN_CLOSED");
    }

    // ── a1: app_user ──────────────────────────────────────────────────────────

    @Test
    void a1_app_user_scan_undo_finalize_under_rls_and_isolation() {
        UUID run = printedRun("Nile Textiles", "PO-7", shirtS, 3);
        List<String> codes = codes(run, shirtS);
        TenantContext.clear();
        InventoryLedger appLedger = new InventoryLedger(app);
        FactoryReceivingService appFactory = new FactoryReceivingService(app, appLedger, new LabelCodeService(app), null);
        ReceivingService appReceiving = new ReceivingService(app, appLedger, null, appFactory);

        UUID s = as(tenant, () -> appReceiving.createSession(owner, main, null, null, null, "factory", run));
        assertThat(as(tenant, () -> appFactory.scan(s, codes.get(0), owner, false)).outcome()).isEqualTo("STAGED");
        assertThat(as(tenant, () -> appFactory.scan(s, codes.get(1), owner, false)).outcome()).isEqualTo("STAGED");
        assertThat(as(tenant, () -> appFactory.undoLast(s, owner)).code()).isEqualTo(codes.get(1));

        // isolation: tenant A sees its scan (positive control), the other tenant none; the other tenant can't scan A's code
        assertThat(as(tenant, () -> app.queryForObject("SELECT count(*)::int FROM receipt_factory_scans", Integer.class))).isEqualTo(2);
        assertThat(as(otherTenant, () -> app.queryForObject("SELECT count(*)::int FROM receipt_factory_scans", Integer.class))).isZero();
        Integer noTenant = appTx.execute(st -> app.queryForObject("SELECT count(*)::int FROM receipt_factory_scans", Integer.class));
        assertThat(noTenant).as("no tenant set").isZero();
        UUID otherRun = as(otherTenant, () -> app.queryForObject("INSERT INTO label_runs (tenant_id, factory_name, created_by) " +
            "VALUES (?, 'X', ?) RETURNING id", UUID.class, otherTenant, otherOwner));
        as(otherTenant, () -> app.update("INSERT INTO label_run_lines (tenant_id, run_id, line_no, variant_id, expected_qty) " +
            "VALUES (?, ?, 1, ?, 1)", otherTenant, otherRun, otherVariant));
        UUID otherSession = as(otherTenant, () -> appReceiving.createSession(otherOwner, otherMain, null, null, null, "factory", otherRun));
        FactoryReceivingService.ScanOutcome cross = as(otherTenant, () -> appFactory.scan(otherSession, codes.get(2), otherOwner, false));
        assertThat(cross.outcome()).as("another tenant's label is unknown here").isEqualTo("REJECTED:UNKNOWN_CODE");

        // grants: a scan row is undone, never edited or deleted
        for (String sql : List.of("UPDATE receipt_factory_scans SET via_phone = true",
                                  "UPDATE receipt_factory_scans SET label_code_id = label_code_id",
                                  "DELETE FROM receipt_factory_scans")) {
            assertThatThrownBy(() -> as(tenant, () -> app.update(sql))).as(sql).rootCause().hasMessageContaining("permission denied");
        }

        assertThat(as(tenant, () -> appReceiving.finalize(s, owner))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT short_code FROM pieces WHERE receipt_id = ?", String.class, s)).isEqualTo(codes.get(0));
        assertThat(jdbc.queryForObject("SELECT status FROM label_codes WHERE code = ? AND tenant_id = ?", String.class, codes.get(0), tenant))
            .isEqualTo("bound");
    }

    @Test
    void a2_rls_forced_on_the_staging_table() {
        assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class " +
            "WHERE relname = 'receipt_factory_scans' AND relnamespace = 'public'::regnamespace", Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO receipts (tenant_id, mode) VALUES (?, 'factory')", tenant))
            .hasMessageContaining("receipts_factory_has_run");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A run of (variant, expected) pairs, printed. */
    private UUID printedRun(String factoryName, String reference, Object... lines) {
        List<LabelRunService.CreateLine> l = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) l.add(new LabelRunService.CreateLine((UUID) lines[i], (Integer) lines[i + 1]));
        UUID run = runs.create(owner, factoryName, reference, l).id();
        runs.print(run, owner);
        return run;
    }

    private List<String> codes(UUID run, UUID variant) {
        return jdbc.queryForList("SELECT c.code FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id " +
            "WHERE l.run_id = ? AND l.variant_id = ? AND NOT c.overage ORDER BY length(c.code), c.code", String.class, run, variant);
    }

    private UUID codeId(String code) {
        return jdbc.queryForObject("SELECT id FROM label_codes WHERE code = ? AND tenant_id = ?", UUID.class, code, tenant);
    }

    private UUID session(UUID run) {
        return receiving.createSession(owner, main, null, null, null, ReceivingService.MODE_FACTORY, run);
    }

    private FactoryReceivingService.ScanOutcome scan(UUID session, String code) {
        return factory.scan(session, code, owner, false);
    }

    private int liveScans(UUID session) {
        return jdbc.queryForObject("SELECT count(*)::int FROM receipt_factory_scans WHERE receipt_id = ? AND undone_at IS NULL",
            Integer.class, session);
    }

    private static void assertRejected(FactoryReceivingService.ScanOutcome o, String code, String messagePart) {
        assertThat(o.outcome()).isEqualTo("REJECTED:" + code);
        assertThat(o.messageEn()).contains(messagePart);
        assertThat(o.messageAr()).isNotBlank();
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, LabelCodeException.Code code) {
        assertThatThrownBy(call).isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(code));
    }

    private <T> T as(UUID t, Supplier<T> body) {
        TenantContext.set(t);
        try {
            return appTx.execute(s -> body.get());
        } finally {
            TenantContext.clear();
        }
    }

    private int waitingOnLocks() {
        Integer n = jdbc.queryForObject(
            "SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
            Integer.class);
        return n == null ? 0 : n;
    }

    private ResponseEntity<String> post(String path, Object body, UUID user, String role) {
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, json(user, role)), String.class);
    }

    private HttpHeaders json(UUID user, String role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(user, tenant, role));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String base() { return "http://localhost:" + port; }
}
