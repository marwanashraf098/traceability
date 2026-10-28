package com.traceability.labels;

import com.traceability.identity.JwtService;
import com.traceability.inventory.ReceivingService;
import com.traceability.inventory.ShopifyInventoryService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Flexible labeling slice 6c — closing a factory run, and its shortfall report.
 *
 *   c1  every line with a gap needs a reason; Short-shipped / Lost void THAT line's leftover issued codes
 *       (factory_run_short_shipped / factory_run_lost), Still coming voids nothing and keeps the run open; a complete
 *       line is left alone
 *   c2  a Still coming line's codes stay scannable — and are received normally
 *   c3  a second close finishes the run (Still coming → Lost): closed_at / closed_by set; a short-shipped line can't
 *       change; a closed run can't be closed, printed or received again (no reopen)
 *   c4  close is refused while an open receiving session of the run holds live scans (named in the message); after
 *       the undo it closes
 *   c5  a voided line's code is then refused at scan: "This label was voided"
 *   c6  validation: a missing reason (lines named), an unknown line, a bad reason — nothing changes; a Still coming
 *       line that has since fully arrived is cleared and the run closes
 *   r1  the report: CSV (header + one row per line: product, variant, SKU, expected, received, gap, reason, overage;
 *       Arabic headings with lang=ar) and the A4 PDF's text
 *   h1  HTTP: switch off → 404 (close + both reports); worker → 403; owner closes and downloads both
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelRunCloseTest {

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
    @Autowired FactoryReceivingService factory;
    @Autowired ReceivingService receiving;
    @MockBean  ShopifyInventoryService shopifyInventory;

    UUID tenant, owner, worker, main, shirtS, shirtM, mug;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); worker = UUID.randomUUID(); main = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, 'Close Tenant', true)", tenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'Mona', 'close-o@test.local', 'h', 'owner'), (?, ?, 'W', 'close-w@test.local', 'h', 'worker')",
            owner, tenant, worker, tenant);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Close Main', 'warehouse', true, true)", main, tenant);
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', 'close.myshopify.com', 'disconnected')",
            store, tenant);
        UUID shirt = product(store, "Linen Shirt"), cup = product(store, "قدح فخار");
        shirtS = variant(shirt, "S", "SHIRT-S");
        shirtM = variant(shirt, "M", "SHIRT-M");
        mug    = variant(cup, "Default Title", "MUG-1");
    }

    private UUID product(UUID store, String title) {
        UUID p = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
            p, tenant, store, "P-" + p, title);
        return p;
    }

    private UUID variant(UUID product, String title, String sku) {
        UUID v = UUID.randomUUID();
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
            v, tenant, product, "V-" + sku, title, sku);
        return v;
    }

    @BeforeEach
    void each() {
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (String t : List.of("receipt_factory_scans", "label_codes", "label_code_batches", "piece_events", "pieces",
                "receipt_lines", "receipts", "label_run_lines", "label_runs", "piece_counters")) {
            jdbc.update("DELETE FROM " + t + " WHERE tenant_id = ?", tenant);
        }
    }

    // ── c1 ────────────────────────────────────────────────────────────────────

    @Test
    void c1_short_shipped_and_lost_void_that_line_only_still_coming_keeps_the_run_open() {
        UUID run = printedRun(shirtS, 3, shirtM, 2, mug, 2);
        receive(run, codes(run, shirtS));                        // complete
        receive(run, codes(run, shirtM).subList(0, 1));          // 1 of 2

        LabelRunService.CloseResult r = runs.close(run, owner, List.of(
            new LabelRunService.CloseLine(line(run, shirtM), "short_shipped"),
            new LabelRunService.CloseLine(line(run, mug), "still_coming")));

        assertThat(r.voided()).isEqualTo(1);
        assertThat(r.closed()).isFalse();
        assertThat(r.run().status()).isEqualTo("open");
        assertThat(r.run().stillComing()).isTrue();
        assertThat(statuses(run, shirtM)).containsExactlyInAnyOrder("bound", "voided:factory_run_short_shipped");
        assertThat(statuses(run, mug)).containsExactly("issued", "issued");
        assertThat(statuses(run, shirtS)).containsOnly("bound");
        assertThat(r.run().lines()).extracting(LabelRunService.RunLine::closeReason)
            .containsExactly(null, "short_shipped", "still_coming");
        assertThat(r.run().lines()).extracting(LabelRunService.RunLine::gap).containsExactly(0, 1, 2);
        assertThat(runs.list()).filteredOn(s -> s.id().equals(run)).singleElement()
            .satisfies(s -> { assertThat(s.status()).isEqualTo("open"); assertThat(s.stillComing()).isTrue(); });
        assertThat(jdbc.queryForObject("SELECT close_recorded_by FROM label_run_lines WHERE id = ?", UUID.class, line(run, shirtM)))
            .isEqualTo(owner);
    }

    // ── c2 / c3 ───────────────────────────────────────────────────────────────

    @Test
    void c2_c3_still_coming_codes_stay_scannable_and_a_second_close_finishes_the_run() {
        UUID run = printedRun(shirtM, 2, mug, 2);
        runs.close(run, owner, List.of(new LabelRunService.CloseLine(line(run, shirtM), "short_shipped"),
            new LabelRunService.CloseLine(line(run, mug), "still_coming")));

        // c2: a still-coming piece arrives later and is received normally
        UUID s = session(run);
        assertThat(factory.scan(s, codes(run, mug).get(0), owner, false).outcome()).isEqualTo("STAGED");
        assertThat(receiving.finalize(s, owner)).isEqualTo(1);

        // a short-shipped line can't change
        assertRefused(() -> runs.close(run, owner, List.of(new LabelRunService.CloseLine(line(run, shirtM), "lost"),
            new LabelRunService.CloseLine(line(run, mug), "lost"))), LabelCodeException.Code.INVALID_CLOSE);

        // c3: Still coming → Lost finishes the run
        LabelRunService.CloseResult r = runs.close(run, owner, List.of(new LabelRunService.CloseLine(line(run, mug), "lost")));
        assertThat(r.closed()).isTrue();
        assertThat(r.voided()).isEqualTo(1);
        assertThat(statuses(run, mug)).containsExactlyInAnyOrder("bound", "voided:factory_run_lost");
        assertThat(jdbc.queryForMap("SELECT status, closed_by, closed_at FROM label_runs WHERE id = ?", run))
            .containsEntry("status", "closed").containsEntry("closed_by", owner).extractingByKey("closed_at").isNotNull();
        assertThat(r.run().closedBy()).isEqualTo("Mona");

        // no reopen: closed stays closed
        assertRefused(() -> runs.close(run, owner, List.of()), LabelCodeException.Code.RUN_CLOSED);
        assertRefused(() -> runs.print(run, owner), LabelCodeException.Code.RUN_CLOSED);
        assertRefused(() -> session(run), LabelCodeException.Code.RUN_CLOSED);
    }

    // ── c4 ────────────────────────────────────────────────────────────────────

    @Test
    void c4_close_waits_for_live_scans_to_be_finalized_or_undone() {
        UUID run = printedRun(shirtS, 2);
        UUID s = receiving.createSession(owner, main, "DOCK-4", null, null, ReceivingService.MODE_FACTORY, run);
        factory.scan(s, codes(run, shirtS).get(0), owner, false);
        List<LabelRunService.CloseLine> lost = List.of(new LabelRunService.CloseLine(line(run, shirtS), "lost"));

        assertThatThrownBy(() -> runs.close(run, owner, lost)).isInstanceOf(LabelCodeException.class)
            .satisfies(e -> {
                LabelCodeException l = (LabelCodeException) e;
                assertThat(l.code()).isEqualTo(LabelCodeException.Code.RUN_HAS_LIVE_SCANS);
                assertThat(l.messageEn()).isEqualTo("Finalize or undo receiving session «DOCK-4» first");
                assertThat(l.details()).singleElement().satisfies(d -> assertThat(d.get("scans")).isEqualTo(1));
            });
        assertThat(statuses(run, shirtS)).containsOnly("issued");

        factory.undoLast(s, owner);
        assertThat(runs.close(run, owner, lost).closed()).isTrue();
        assertThat(statuses(run, shirtS)).containsOnly("voided:factory_run_lost");
    }

    // ── c5 ────────────────────────────────────────────────────────────────────

    @Test
    void c5_a_voided_lines_code_is_refused_at_scan() {
        UUID run = printedRun(shirtS, 1, mug, 1);
        String voided = codes(run, shirtS).get(0);
        runs.close(run, owner, List.of(new LabelRunService.CloseLine(line(run, shirtS), "short_shipped"),
            new LabelRunService.CloseLine(line(run, mug), "still_coming")));
        UUID s = session(run);
        FactoryReceivingService.ScanOutcome o = factory.scan(s, voided, owner, false);
        assertThat(o.outcome()).isEqualTo("REJECTED:CODE_VOIDED");
        assertThat(o.messageEn()).startsWith("This label was voided");
    }

    // ── c7 ────────────────────────────────────────────────────────────────────

    @Test
    void c7_the_final_close_voids_every_code_still_issued_including_complete_lines() {
        UUID run = printedRun(shirtS, 2, mug, 1);
        String leftover = codes(run, shirtS).get(1);
        UUID s = session(run);
        assertThat(factory.scan(s, codes(run, shirtS).get(0), owner, false).outcome()).isEqualTo("STAGED");
        FactoryReceivingService.ExtraLabel replacement = factory.extraLabel(s, shirtS, "c7-1", owner);
        assertThat(factory.scan(s, replacement.code(), owner, false).outcome()).isEqualTo("STAGED");
        assertThat(factory.scan(s, codes(run, mug).get(0), owner, false).outcome()).isEqualTo("STAGED");
        FactoryReceivingService.ExtraLabel unusedOverage = factory.extraLabel(s, mug, "c7-2", owner);
        assertThat(unusedOverage.overage()).isTrue();
        receiving.finalize(s, owner);

        LabelRunService.CloseResult r = runs.close(run, owner, List.of());   // no gaps → no reasons needed
        assertThat(r.closed()).isTrue();
        assertThat(r.voided()).isEqualTo(2);
        assertThat(statuses(run, shirtS)).containsExactlyInAnyOrder("bound", "bound", "voided:factory_run_closed");
        assertThat(statuses(run, mug)).containsExactlyInAnyOrder("bound", "voided:factory_run_closed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id " +
            "WHERE l.run_id = ? AND c.status = 'issued'", Integer.class, run)).isZero();

        // refused at scan, in a session of another (open) run
        UUID other = printedRun(shirtM, 1);
        FactoryReceivingService.ScanOutcome o = factory.scan(session(other), leftover, owner, false);
        assertThat(o.outcome()).isEqualTo("REJECTED:CODE_VOIDED");
    }

    // ── c6 ────────────────────────────────────────────────────────────────────

    @Test
    void c6_validation_and_a_still_coming_line_that_arrived_since() {
        UUID run = printedRun(shirtS, 2, mug, 1);
        assertThatThrownBy(() -> runs.close(run, owner, List.of(new LabelRunService.CloseLine(line(run, mug), "lost"))))
            .isInstanceOf(LabelCodeException.class)
            .satisfies(e -> {
                LabelCodeException l = (LabelCodeException) e;
                assertThat(l.code()).isEqualTo(LabelCodeException.Code.INVALID_CLOSE);
                assertThat(l.details()).extracting(d -> d.get("lineId")).containsExactly(line(run, shirtS));
            });
        assertRefused(() -> runs.close(run, owner, List.of(new LabelRunService.CloseLine(UUID.randomUUID(), "lost"))),
            LabelCodeException.Code.INVALID_CLOSE);
        assertRefused(() -> runs.close(run, owner, List.of(new LabelRunService.CloseLine(line(run, mug), "damaged"))),
            LabelCodeException.Code.INVALID_CLOSE);
        assertThat(statuses(run, mug)).containsOnly("issued");
        assertThat(jdbc.queryForObject("SELECT count(*)::int FROM label_run_lines WHERE run_id = ? AND close_reason IS NOT NULL",
            Integer.class, run)).isZero();

        runs.close(run, owner, List.of(new LabelRunService.CloseLine(line(run, shirtS), "still_coming"),
            new LabelRunService.CloseLine(line(run, mug), "still_coming")));
        receive(run, codes(run, shirtS));
        receive(run, codes(run, mug));
        LabelRunService.CloseResult r = runs.close(run, owner, List.of());
        assertThat(r.closed()).as("everything arrived: the Still coming lines clear and the run closes").isTrue();
        assertThat(r.run().lines()).extracting(LabelRunService.RunLine::closeReason).containsOnlyNulls();
    }

    // ── r1: report ────────────────────────────────────────────────────────────

    @Test
    void r1_report_csv_and_pdf() throws Exception {
        UUID run = runs.create(owner, "Nile Textiles", "PO-7", List.of(
            new LabelRunService.CreateLine(shirtS, 3), new LabelRunService.CreateLine(mug, 2))).id();
        runs.print(run, owner);
        receive(run, codes(run, shirtS).subList(0, 2));
        UUID s = session(run);
        FactoryReceivingService.ExtraLabel extra = factory.extraLabel(s, mug, "r1", owner);
        receiveIn(s, List.of(codes(run, mug).get(0), codes(run, mug).get(1), extra.code()));
        runs.close(run, owner, List.of(new LabelRunService.CloseLine(line(run, shirtS), "short_shipped")));

        FactoryRunReport.Report report = runs.report(run);
        String csv = new String(FactoryRunReport.csv(report, "en"), StandardCharsets.UTF_8);
        assertThat(csv.lines().toList()).containsExactly(
            "﻿Run,Factory,Reference,Product,Variant,SKU,Expected,Received,Gap,Reason,Overage",
            "Nile Textiles · PO-7,Nile Textiles,PO-7,Linen Shirt,S,SHIRT-S,3,2,1,Short-shipped,0",
            "Nile Textiles · PO-7,Nile Textiles,PO-7,قدح فخار,Default Title,MUG-1,2,3,0,,1");
        String ar = new String(FactoryRunReport.csv(report, "ar"), StandardCharsets.UTF_8);
        assertThat(ar.lines().findFirst().orElseThrow()).contains("المتوقع").contains("المستلم").contains("النقص");
        assertThat(ar).contains("شحن ناقص");

        try (PDDocument doc = Loader.loadPDF(FactoryRunReport.pdf(report, "en"))) {
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isCloseTo(595.3f, within(1f));     // A4
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Factory run shortfall report").contains("Nile Textiles").contains("PO-7")
                .contains("Linen Shirt · S").contains("SHIRT-S").contains("Short-shipped").contains("Closed")
                .contains("Expected").contains("Overage").contains("Total");
        }
        try (PDDocument doc = Loader.loadPDF(FactoryRunReport.pdf(report, "ar"))) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void r2_csv_cells_are_quoted_and_never_formulas() {
        assertThat(FactoryRunReport.cell("A, B")).isEqualTo("\"A, B\"");
        assertThat(FactoryRunReport.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(FactoryRunReport.cell("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(FactoryRunReport.cell("-3")).isEqualTo("-3");
    }

    // ── h1: HTTP ──────────────────────────────────────────────────────────────

    @Test
    void h1_http_switch_and_roles() {
        UUID run = printedRun(shirtS, 1);
        TenantContext.clear();
        String base = "/api/v1/label-runs/" + run;
        Map<String, Object> body = Map.of("lines", List.of(Map.of("lineId", line(run, shirtS).toString(), "reason", "lost")));

        assertThat(call(HttpMethod.POST, base + "/close", body, worker, "worker").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, base + "/report/csv", null, worker, "worker").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        for (String[] c : List.of(new String[] {"POST", base + "/close"}, new String[] {"GET", base + "/report/csv"},
                                  new String[] {"GET", base + "/report/pdf"})) {
            assertThat(call(HttpMethod.valueOf(c[0]), c[1], body, owner, "owner").getStatusCode()).as(c[1]).isEqualTo(HttpStatus.NOT_FOUND);
        }
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);

        ResponseEntity<String> closed = call(HttpMethod.POST, base + "/close", body, owner, "owner");
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closed.getBody()).contains("\"closed\":true").contains("\"voided\":1");
        ResponseEntity<String> csv = call(HttpMethod.GET, base + "/report/csv", null, owner, "owner");
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(csv.getBody()).contains("Linen Shirt,S,SHIRT-S,1,0,1,Lost,0");
        ResponseEntity<byte[]> pdf = rest.exchange("http://localhost:" + port + base + "/report/pdf?lang=ar", HttpMethod.GET,
            new HttpEntity<>(auth(owner, "owner")), byte[].class);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(pdf.getBody()).startsWith("%PDF".getBytes());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID printedRun(Object... lines) {
        List<LabelRunService.CreateLine> l = new ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) l.add(new LabelRunService.CreateLine((UUID) lines[i], (Integer) lines[i + 1]));
        UUID run = runs.create(owner, "Nile Textiles", "PO-7", l).id();
        runs.print(run, owner);
        return run;
    }

    private List<String> codes(UUID run, UUID variant) {
        return jdbc.queryForList("SELECT c.code FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id " +
            "WHERE l.run_id = ? AND l.variant_id = ? AND NOT c.overage ORDER BY length(c.code), c.code", String.class, run, variant);
    }

    private List<String> statuses(UUID run, UUID variant) {
        return jdbc.queryForList("SELECT c.status || COALESCE(':' || c.void_reason, '') FROM label_codes c " +
            "JOIN label_run_lines l ON l.id = c.run_line_id WHERE l.run_id = ? AND l.variant_id = ?", String.class, run, variant);
    }

    private UUID line(UUID run, UUID variant) {
        return jdbc.queryForObject("SELECT id FROM label_run_lines WHERE run_id = ? AND variant_id = ?", UUID.class, run, variant);
    }

    private UUID session(UUID run) {
        return receiving.createSession(owner, main, null, null, null, ReceivingService.MODE_FACTORY, run);
    }

    private void receive(UUID run, List<String> codes) {
        receiveIn(session(run), codes);
    }

    private void receiveIn(UUID session, List<String> codes) {
        codes.forEach(c -> assertThat(factory.scan(session, c, owner, false).outcome()).isEqualTo("STAGED"));
        receiving.finalize(session, owner);
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, LabelCodeException.Code code) {
        assertThatThrownBy(call).isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(code));
    }

    private ResponseEntity<String> call(HttpMethod m, String path, Object body, UUID user, String role) {
        HttpHeaders h = auth(user, role);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("http://localhost:" + port + path, m, new HttpEntity<>(m == HttpMethod.GET ? null : body, h), String.class);
    }

    private HttpHeaders auth(UUID user, String role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(user, tenant, role));
        return h;
    }
}
