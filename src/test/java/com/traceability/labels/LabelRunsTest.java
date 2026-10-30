package com.traceability.labels;

import com.traceability.TestPostgres;
import com.traceability.identity.JwtService;
import com.traceability.receiving.ReceivingImportService;
import com.traceability.receiving.ShopifyInventoryFile;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Flexible labeling slice 6a — factory runs (create, print, reprint, Excel expected quantities).
 *
 *   c1  create: factory, reference, lines in the given order with totals; nothing printed yet
 *   c2  validation (400, nothing written): blank factory, reference > 100, no lines, expected 0 / 20,001, a variant
 *       twice, another tenant's variant; more than 20,000 pieces in all → RUN_TOO_LARGE; exactly 20,000 is fine
 *   p1  print issues exactly the expected codes per line (kind factory, the line's variant, run_line_id, a batch
 *       naming the run, codes from the shared counter); a second print issues none; two prints at once issue once
 *   p2  2,500 + 300 expected: batches of ≤ 2,000; label parts never split a line's group unless it alone is larger
 *       (part 1 = 2,000 of line 1, part 2 = 500 of line 1 + 300 of line 2); pack() keeps whole groups together
 *   p3  the PDF: grouped by product, each group's separator FIRST (product + variant, no code), then its labels;
 *       nothing after the last label; reprint = the same codes; received / voided codes are never printed again
 *   p4  a closed run can't print (RUN_CLOSED)
 *   i1  Excel: "On hand (new)" is each variant's expected quantity; above 20,000 → unmatched too_large
 *   h1  HTTP: switch off → 404 on every endpoint; a worker → 403; an owner creates (201), prints and gets the PDF
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelRunsTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired LabelRunService runs;

    UUID tenant, owner, worker, shirtS, shirtM, mug, otherTenant, otherVariant;

    @BeforeAll
    void setup() {
        tenant = UUID.randomUUID(); owner = UUID.randomUUID(); worker = UUID.randomUUID();
        otherTenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled) VALUES (?, 'Run Tenant', true), (?, 'Run Other', true)",
            tenant, otherTenant);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'Mona', 'run-o@test.local', 'h', 'owner'), (?, ?, 'W', 'run-w@test.local', 'h', 'worker')",
            owner, tenant, worker, tenant);
        UUID store = store(tenant, "run");
        UUID shirt = product(tenant, store, "Linen Shirt", "linen-shirt");
        UUID cup = product(tenant, store, "Mug", "mug");
        shirtS = variant(tenant, shirt, "S", "SHIRT-S");
        mug    = variant(tenant, cup, "Default Title", "MUG-1");
        shirtM = variant(tenant, shirt, "M", "SHIRT-M");
        otherVariant = variant(otherTenant, product(otherTenant, store(otherTenant, "run-x"), "Other", "other"), "X", "OTHER-X");
    }

    private UUID store(UUID t, String p) {
        UUID s = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            s, t, p + ".myshopify.com");
        return s;
    }

    private UUID product(UUID t, UUID store, String title, String handle) {
        UUID p = UUID.randomUUID();
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status, raw) " +
            "VALUES (?, ?, ?, ?, ?, 'active', jsonb_build_object('handle', ?::text))", p, t, store, "P-" + handle, title, handle);
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
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = true WHERE id = ?", tenant);
        TenantContext.set(tenant);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        for (String t : List.of("label_codes", "label_code_batches", "label_run_lines", "label_runs", "piece_counters")) {
            jdbc.update("DELETE FROM " + t + " WHERE tenant_id = ?", tenant);
        }
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void c1_create_keeps_the_lines_in_order_and_prints_nothing() {
        LabelRunService.RunDetail r = runs.create(owner, "  Nile Textiles ", " PO-7 ", List.of(line(shirtM, 4), line(mug, 2), line(shirtS, 3)));
        assertThat(r.factoryName()).isEqualTo("Nile Textiles");
        assertThat(r.reference()).isEqualTo("PO-7");
        assertThat(r.status()).isEqualTo("open");
        assertThat(r.createdBy()).isEqualTo("Mona");
        assertThat(r.lines()).extracting(LabelRunService.RunLine::variantId).containsExactly(shirtM, mug, shirtS);
        assertThat(r.lines()).extracting(LabelRunService.RunLine::lineNo).containsExactly(1, 2, 3);
        assertThat(r.expected()).isEqualTo(9);
        assertThat(r.printed()).isZero();
        assertThat(r.toPrint()).isEqualTo(9);
        assertThat(r.parts()).isEmpty();
        assertThat(count("SELECT count(*) FROM label_codes WHERE tenant_id = ?", tenant)).isZero();
        assertThat(runs.list()).singleElement().satisfies(s -> {
            assertThat(s.expected()).isEqualTo(9);
            assertThat(s.lines()).isEqualTo(3);
            assertThat(s.received()).isZero();
        });
        assertThat(runs.create(owner, "Delta", null, List.of(line(mug, 1))).reference()).isNull();
    }

    @Test
    void c2_validation_refuses_and_writes_nothing() {
        assertRefused(() -> runs.create(owner, " ", null, List.of(line(mug, 1))), LabelCodeException.Code.INVALID_RUN);
        assertRefused(() -> runs.create(owner, "F", "x".repeat(101), List.of(line(mug, 1))), LabelCodeException.Code.INVALID_RUN);
        assertRefused(() -> runs.create(owner, "F", null, List.of()), LabelCodeException.Code.INVALID_RUN);
        assertRefused(() -> runs.create(owner, "F", null, List.of(line(mug, 0))), LabelCodeException.Code.INVALID_RUN);
        assertRefused(() -> runs.create(owner, "F", null, List.of(line(mug, 20_001))), LabelCodeException.Code.INVALID_RUN);
        assertRefused(() -> runs.create(owner, "F", null, List.of(line(mug, 1), line(mug, 2))), LabelCodeException.Code.INVALID_RUN);
        assertRefused(() -> runs.create(owner, "F", null, List.of(line(otherVariant, 1))), LabelCodeException.Code.UNKNOWN_VARIANT);
        assertRefused(() -> runs.create(owner, "F", null, List.of(line(mug, 15_000), line(shirtS, 5_001))),
            LabelCodeException.Code.RUN_TOO_LARGE);
        assertThat(count("SELECT count(*) FROM label_runs WHERE tenant_id = ?", tenant)).isZero();

        assertThat(runs.create(owner, "F", null, List.of(line(mug, 15_000), line(shirtS, 5_000))).expected()).isEqualTo(20_000);
    }

    // ── print ─────────────────────────────────────────────────────────────────

    @Test
    void p1_print_issues_exactly_the_expected_codes_once() throws Exception {
        UUID run = runs.create(owner, "Nile", null, List.of(line(shirtS, 3), line(mug, 2))).id();
        LabelRunService.PrintResult first = runs.print(run, owner);
        assertThat(first.issued()).isEqualTo(5);
        assertThat(first.run().printed()).isEqualTo(5);
        assertThat(first.run().toPrint()).isZero();
        for (LabelRunService.RunLine l : first.run().lines()) {
            assertThat(count("SELECT count(*) FROM label_codes WHERE run_line_id = ? AND kind = 'factory' AND status = 'issued' " +
                "AND variant_id = ? AND NOT overage", l.id(), l.variantId())).as(l.sku()).isEqualTo(l.expected());
        }
        assertThat(count("SELECT count(*) FROM label_code_batches WHERE run_id = ? AND kind = 'factory'", run)).isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT code) FROM label_codes WHERE tenant_id = ?", tenant)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT last_value FROM piece_counters WHERE tenant_id = ?", Long.class, tenant)).isEqualTo(5L);

        assertThat(runs.print(run, owner).issued()).as("a second print issues none").isZero();
        assertThat(count("SELECT count(*) FROM label_codes WHERE tenant_id = ?", tenant)).isEqualTo(5);

        // Two prints at the same moment: the run lock lets one issue, the other finds nothing left.
        UUID run2 = runs.create(owner, "Delta", null, List.of(line(shirtM, 7))).id();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            calls.add(pool.submit(() -> {
                TenantContext.set(tenant);
                try { return runs.print(run2, owner).issued(); } finally { TenantContext.clear(); }
            }));
        }
        List<Integer> issued = new ArrayList<>();
        for (Future<Integer> f : calls) issued.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(issued).containsExactlyInAnyOrder(7, 0);
        assertThat(count("SELECT count(*) FROM label_codes c JOIN label_run_lines l ON l.id = c.run_line_id WHERE l.run_id = ?", run2))
            .isEqualTo(7);
    }

    @Test
    void p2_batches_and_parts_of_at_most_2000() {
        UUID run = runs.create(owner, "Big", null, List.of(line(shirtS, 2_500), line(mug, 300))).id();
        LabelRunService.PrintResult r = runs.print(run, owner);
        assertThat(r.issued()).isEqualTo(2_800);
        assertThat(jdbc.queryForList("SELECT count FROM label_code_batches WHERE run_id = ? ORDER BY idempotency_key", Integer.class, run))
            .containsExactly(2_000, 800);
        assertThat(r.run().parts()).hasSize(2);
        assertThat(r.run().parts().get(0).labels()).isEqualTo(2_000);
        assertThat(r.run().parts().get(0).groups()).extracting(LabelRunService.PartGroup::sku).containsExactly("SHIRT-S");
        assertThat(r.run().parts().get(1).groups()).extracting(LabelRunService.PartGroup::sku, LabelRunService.PartGroup::labels)
            .containsExactly(tuple("SHIRT-S", 500), tuple("MUG-1", 300));

        // Whole groups move to the next part rather than being split.
        List<List<LabelRunService.Chunk>> parts = LabelRunService.pack(List.of(chunk("A", 1_500), chunk("B", 800), chunk("C", 200)));
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).extracting(LabelRunService.Chunk::sku).containsExactly("A");
        assertThat(parts.get(1)).extracting(LabelRunService.Chunk::sku).containsExactly("B", "C");
        assertThat(LabelRunService.pack(List.of(chunk("A", 2_000), chunk("B", 1)))).hasSize(2);
        assertThat(LabelRunService.pack(List.of(chunk("A", 1_999), chunk("B", 1)))).hasSize(1);
        assertThat(LabelRunService.pack(List.of(chunk("A", 10), chunk("B", 4_001))))
            .extracting(List::size).containsExactly(1, 1, 1, 1);      // A | B 2000 | B 2000 | B 1
    }

    @Test
    void p3_pdf_is_grouped_by_product_with_the_separator_first_and_reprints_the_same_codes() throws Exception {
        // Lines: shirt S, mug, shirt M — the shirt's two variants print together, before the mug.
        UUID run = runs.create(owner, "Nile", null, List.of(line(shirtS, 2), line(mug, 1), line(shirtM, 1))).id();
        runs.print(run, owner);
        List<String> pages = pages(runs.pdf(run, 1));
        assertThat(pages).hasSize(4 + 3);                                       // 4 labels + 3 headers
        List<String> codesS = codes(shirtS), codesM = codes(shirtM), codesMug = codes(mug);
        assertSeparator(pages.get(0), "Linen Shirt", "S");
        assertThat(pages.get(1)).contains(codesS.get(0));
        assertThat(pages.get(2)).contains(codesS.get(1));
        assertSeparator(pages.get(3), "Linen Shirt", "M");
        assertThat(pages.get(4)).contains(codesM.get(0));
        assertSeparator(pages.get(5), "Mug", null);
        assertThat(pages.get(6)).contains(codesMug.get(0));

        // Reprint: the same codes, nothing issued.
        assertThat(pages(runs.pdf(run, 1))).isEqualTo(pages);
        assertThat(count("SELECT count(*) FROM label_codes WHERE tenant_id = ?", tenant)).isEqualTo(4);

        // A received (bound) or voided code is never printed again.
        jdbc.update("UPDATE label_codes SET status = 'voided', voided_at = now(), void_reason = 'test' WHERE code = ?", codesS.get(0));
        List<String> after = pages(runs.pdf(run, 1));
        assertThat(String.join("\n", after)).doesNotContain(codesS.get(0)).contains(codesS.get(1));
        assertThatThrownBy(() -> runs.pdf(run, 2)).isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(LabelCodeException.Code.INVALID_PART));
    }

    @Test
    void p4_a_closed_run_cannot_print() {
        UUID run = runs.create(owner, "Nile", null, List.of(line(mug, 1))).id();
        jdbc.update("UPDATE label_runs SET status = 'closed', closed_at = now(), closed_by = ? WHERE id = ?", owner, run);
        assertRefused(() -> runs.print(run, owner), LabelCodeException.Code.RUN_CLOSED);
        assertThat(count("SELECT count(*) FROM label_codes WHERE tenant_id = ?", tenant)).isZero();
    }

    // ── Excel ─────────────────────────────────────────────────────────────────

    @Test
    void i1_excel_reads_on_hand_new_as_the_expected_quantity() {
        List<List<String>> t = new ArrayList<>();
        t.add(ShopifyInventoryFile.SHOPIFY_HEADERS);
        t.add(row("linen-shirt", "Linen Shirt", "S", "SHIRT-S", "5", "40"));
        t.add(row("mug", "Mug", "Default Title", "MUG-1", "0", "20001"));
        t.add(row("linen-shirt", "Linen Shirt", "M", "SHIRT-M", "3", ""));
        ReceivingImportService.Preview p = runs.importPreview("factory.xlsx", ShopifyInventoryFile.writeXlsx(t, Set.of()));
        assertThat(p.quantityColumn()).isEqualTo("new");
        assertThat(p.matched()).singleElement().satisfies(m -> {
            assertThat(m.variantId()).isEqualTo(shirtS);
            assertThat(m.quantity()).as("On hand (new), not On hand (current)").isEqualTo(40);
        });
        assertThat(p.unmatched()).singleElement().satisfies(u -> {
            assertThat(u.reason()).isEqualTo("too_large");
            assertThat(u.sku()).isEqualTo("MUG-1");
        });
        assertThat(p.units()).isEqualTo(40);
        assertThat(p.skipped()).isEqualTo(1);                                    // the empty On hand (new)
        assertThat(count("SELECT count(*) FROM label_runs WHERE tenant_id = ?", tenant)).isZero();
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    @Test
    void h1_switch_roles_and_the_owner_flow_over_http() {
        Map<String, Object> body = Map.of("factoryName", "Nile", "lines", List.of(Map.of("variantId", mug, "expectedQty", 2)));
        ResponseEntity<Map> created = exchange(HttpMethod.POST, "/api/v1/label-runs", body, owner, "owner", Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        assertThat(exchange(HttpMethod.POST, "/api/v1/label-runs/" + id + "/print", null, owner, "owner", Map.class).getBody())
            .containsEntry("issued", 2);
        ResponseEntity<byte[]> pdf = exchange(HttpMethod.GET, "/api/v1/label-runs/" + id + "/pdf?part=1", null, owner, "owner", byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(exchange(HttpMethod.GET, "/api/v1/label-runs", null, owner, "owner", List.class).getBody()).hasSize(1);

        for (String[] call : endpoints(id)) {
            assertThat(exchange(HttpMethod.valueOf(call[0]), call[1], call[0].equals("POST") ? body : null, worker, "worker", String.class)
                .getStatusCode()).as("worker " + call[1]).isEqualTo(HttpStatus.FORBIDDEN);
        }
        jdbc.update("UPDATE tenants SET flexible_labeling_enabled = false WHERE id = ?", tenant);
        for (String[] call : endpoints(id)) {
            assertThat(exchange(HttpMethod.valueOf(call[0]), call[1], call[0].equals("POST") ? body : null, owner, "owner", String.class)
                .getStatusCode()).as("switch off " + call[1]).isEqualTo(HttpStatus.NOT_FOUND);
        }
        HttpHeaders h = auth(owner, "owner");
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(ShopifyInventoryFile.writeCsv(List.of(ShopifyInventoryFile.SHOPIFY_HEADERS,
            row("mug", "Mug", "Default Title", "MUG-1", "0", "2")))) {
            @Override public String getFilename() { return "f.csv"; }
        });
        assertThat(rest.exchange(base() + "/api/v1/label-runs/import/preview", HttpMethod.POST, new HttpEntity<>(form, h), String.class)
            .getStatusCode()).as("switch off import").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("SELECT count(*) FROM label_runs WHERE tenant_id = ?", tenant)).isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String[][] endpoints(String id) {
        return new String[][] {
            {"GET", "/api/v1/label-runs"}, {"POST", "/api/v1/label-runs"}, {"GET", "/api/v1/label-runs/" + id},
            {"POST", "/api/v1/label-runs/" + id + "/print"}, {"GET", "/api/v1/label-runs/" + id + "/pdf?part=1"} };
    }

    private static LabelRunService.CreateLine line(UUID variant, int qty) {
        return new LabelRunService.CreateLine(variant, qty);
    }

    private static LabelRunService.Chunk chunk(String sku, int n) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) codes.add("P" + i);
        return new LabelRunService.Chunk(UUID.randomUUID(), "P", "V", sku, codes);
    }

    private static List<String> row(String handle, String title, String option, String sku, String current, String newValue) {
        List<String> r = new ArrayList<>(Collections.nCopies(ShopifyInventoryFile.SHOPIFY_HEADERS.size(), ""));
        r.set(0, handle); r.set(1, title); r.set(2, "Size"); r.set(3, option); r.set(8, sku); r.set(11, "Main");
        r.set(17, current); r.set(18, newValue);
        return r;
    }

    private List<String> codes(UUID variant) {
        return jdbc.queryForList("SELECT code FROM label_codes WHERE variant_id = ? AND tenant_id = ? ORDER BY length(code), code",
            String.class, variant, tenant);
    }

    private static List<String> pages(byte[] pdf) throws Exception {
        List<String> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                s.setStartPage(p);
                s.setEndPage(p);
                out.add(s.getText(doc));
            }
        }
        return out;
    }

    private static void assertSeparator(String page, String product, String variant) {
        assertThat(page).contains(product);
        if (variant != null) assertThat(page).contains(variant);
        assertThat(page).as("a separator carries no code").doesNotContainPattern("P[0-9]{6}");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, LabelCodeException.Code code) {
        assertThatThrownBy(call).isInstanceOf(LabelCodeException.class)
            .satisfies(e -> assertThat(((LabelCodeException) e).code()).isEqualTo(code));
    }

    private <T> ResponseEntity<T> exchange(HttpMethod m, String path, Object body, UUID user, String role, Class<T> type) {
        HttpHeaders h = auth(user, role);
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, m, new HttpEntity<>(body, h), type);
    }

    private HttpHeaders auth(UUID user, String role) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt.issueAccessToken(user, tenant, role));
        return h;
    }

    private String base() { return "http://localhost:" + port; }
}
