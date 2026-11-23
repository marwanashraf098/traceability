package com.traceability.labels;

import com.traceability.TestPostgres;
import com.traceability.account.TenantController;
import com.traceability.identity.CustomUserDetails;
import com.traceability.integrations.bosta.BostaGateway;
import com.traceability.inventory.LabelService;
import com.traceability.inventory.LabelSizes;
import com.traceability.inventory.PieceLabelLayout;
import com.traceability.inventory.ReceivingService;
import com.traceability.inventory.ShopifyInventoryService;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * The tenant's label size (Settings → Business) is THE size of every one-piece-per-page label PDF — LabelSizes —
 * including the 60 × 12 jewelry rat-tail; packing labels stay 50 × 25 whatever the setting.
 *
 *   w1  40×25 tenant → receiving session labels, variant labels, single + multi piece reprints are 40×25 pages (the
 *       stored setting was ignored before); no setting → 50×25; explicit widthMm / heightMm still win
 *   w2  60×12 tenant → the same paths print 60×12 rat-tail pages (vector bars, no image); an untagged piece is still
 *       refused PIECE_UNTAGGED
 *   w3  code-first labels (batchPdf, codesPdf — also the factory extra label and tag-stock leftovers) follow the setting
 *   w4  label runs on 60×12: a "× N" separator before each group (N = that group's labels), then the labels; on
 *       50×25 the header page is today's (product / variant text, no count, no barcode)
 *   w5  tag stock multi-print on 60×12: "× N" separators; on 50×25 the header page is today's
 *   w6  packing labels on a 60×12 (and 40×25) tenant: the pack PDF and the pack-screen replacement label are 50×25
 *   w7  Settings: PUT 60x12 → GET 60x12 → LabelSizes 60×12; an unknown size is 400
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelSizeWiringTest {

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

    static final float MM = PieceLabelLayout.MM;
    private static final AtomicLong TRACKING = new AtomicLong(4_300_000_000L);

    @Autowired JdbcTemplate jdbc;
    @Autowired LabelService labelService;
    @Autowired LabelCodeService labelCodes;
    @Autowired LabelRunService runs;
    @Autowired TagStockService tagStock;
    @Autowired PackLabelService packLabels;
    @Autowired ReceivingService receiving;
    @Autowired TenantController tenantCtl;
    @MockBean ShopifyInventoryService shopifyInventory;
    @MockBean BostaGateway bostaGateway;
    @MockBean JobScheduler jobScheduler;

    record T(UUID id, UUID owner, UUID store, UUID main, UUID ring, UUID chain) {}

    T rat, small, plain;

    @BeforeAll
    void setup() {
        rat = seed("RT");
        small = seed("SM");
        plain = seed("PN");
        jdbc.update("UPDATE tenants SET label_width_mm = 60, label_height_mm = 12 WHERE id = ?", rat.id());
        jdbc.update("UPDATE tenants SET label_width_mm = 40, label_height_mm = 25 WHERE id = ?", small.id());
    }

    private T seed(String p) {
        UUID t = UUID.randomUUID(), owner = UUID.randomUUID(), store = UUID.randomUUID(), main = UUID.randomUUID();
        UUID prod = UUID.randomUUID(), ring = UUID.randomUUID(), chain = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (id, name, flexible_labeling_enabled, pick_pack_mode) VALUES (?, ?, true, 'order_queue')",
            t, p + " Tenant");
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES (?, ?, 'O', ?, 'h', 'owner')",
            owner, t, p.toLowerCase() + "-o@test.local");
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES (?, ?, 'shopify', ?, 'disconnected')",
            store, t, p.toLowerCase() + "-ls.myshopify.com");
        jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, 'خاتم فضة', 'active')",
            prod, t, store, p + "-P1");
        jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, ?, 'مقاس 7', ?), (?, ?, ?, ?, 'Default Title', ?)",
            ring, t, prod, p + "-V1", p + "-RING-7", chain, t, prod, p + "-V2", p + "-CHAIN");
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) VALUES " +
            "(?, ?, ?, 'warehouse', true, true)", main, t, p + " Main");
        return new T(t, owner, store, main, ring, chain);
    }

    @AfterEach
    void clean() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void as(T t) { TenantContext.set(t.id()); }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private UUID labeledSession(T t, UUID variant, int n) {
        as(t);
        UUID s = receiving.createSession(t.owner(), t.main(), "PS-" + UUID.randomUUID(), null, null);
        receiving.addLine(s, variant, n);
        receiving.finalize(s, t.owner());
        return s;
    }

    private UUID untaggedSession(T t, UUID variant, int n) {
        as(t);
        UUID s = receiving.createSession(t.owner(), t.main(), "CO-" + UUID.randomUUID(), null, null, ReceivingService.MODE_COUNT_ONLY);
        receiving.addLine(s, variant, n);
        receiving.finalize(s, t.owner());
        return s;
    }

    private List<String> pieceIds(UUID session) {
        return jdbc.queryForList("SELECT id FROM pieces WHERE receipt_id = ? ORDER BY short_code", String.class, session);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void w1_forty_by_twentyfive_tenant_gets_forty_by_twentyfive() throws Exception {
        UUID s = labeledSession(small, small.ring(), 2);
        assertAllPages(labelService.generateSessionLabels(s, null, null), 40, 25, 2);
        assertAllPages(labelService.generateVariantLabels(s, small.ring(), null, null).pdf(), 40, 25, 2);
        List<String> ids = pieceIds(s);
        assertAllPages(labelService.generatePieceLabel(ids.get(0), null, null), 40, 25, 1);
        assertAllPages(labelService.generatePieceLabels(ids, null, null), 40, 25, 2);
        assertAllPages(labelService.reprint(s, small.owner(), "w1", null, null), 40, 25, 2);
        assertAllPages(labelService.generateSessionLabels(s, 50f, 25f), 50, 25, 2);        // explicit size wins

        UUID p = labeledSession(plain, plain.ring(), 1);
        assertAllPages(labelService.generateSessionLabels(p, null, null), 50, 25, 1);      // no setting → 50×25
    }

    @Test
    void w2_rat_tail_tenant_gets_rat_tail_pages() throws Exception {
        UUID s = labeledSession(rat, rat.ring(), 3);
        for (byte[] pdf : List.of(
                labelService.generateSessionLabels(s, null, null),
                labelService.generateVariantLabels(s, rat.ring(), null, null).pdf(),
                labelService.generatePieceLabels(pieceIds(s), null, null),
                labelService.reprint(s, rat.owner(), "w2", null, null))) {
            assertAllPages(pdf, 60, 12, 3);
            assertNoImages(pdf);
        }
        assertAllPages(labelService.generatePieceLabel(pieceIds(s).get(0), null, null), 60, 12, 1);
        List<String> texts = pageTexts(labelService.generateSessionLabels(s, null, null));
        // Within a variant the session's labels print in piece-id order (B9b, LabelService.LABEL_ORDER_SQL), so
        // the page order is not the short-code order: every code exactly once, one per page, each with the SKU.
        List<String> codes = jdbc.queryForList("SELECT short_code FROM pieces WHERE receipt_id = ?", String.class, s);
        List<String> printed = new ArrayList<>();
        for (String t : texts) {
            List<String> onPage = codes.stream().filter(t::contains).toList();
            assertThat(onPage).as("one code per page: " + t).hasSize(1);
            assertThat(t).contains("RT-RING-7");
            printed.add(onPage.get(0));
        }
        assertThat(printed).containsExactlyInAnyOrderElementsOf(codes);

        UUID u = untaggedSession(rat, rat.ring(), 1);
        String untagged = pieceIds(u).get(0);
        assertThatThrownBy(() -> labelService.generatePieceLabel(untagged, null, null))
            .isInstanceOf(UntaggedException.class);
    }

    @Test
    void w3_code_first_labels_follow_the_setting() throws Exception {
        as(rat);
        LabelCodeService.Batch b = labelCodes.issue(rat.owner(), "tag",
            List.of(new LabelCodeService.Line(rat.ring(), 2, null, null)), "w3-" + UUID.randomUUID());
        assertAllPages(labelCodes.batchPdf(b.id()), 60, 12, 2);
        assertAllPages(labelCodes.codesPdf(b.codes().stream().map(LabelCodeService.IssuedCode::id).toList()), 60, 12, 2);

        as(small);
        LabelCodeService.Batch sb = labelCodes.issue(small.owner(), "tag",
            List.of(new LabelCodeService.Line(small.ring(), 1, null, null)), "w3-" + UUID.randomUUID());
        assertAllPages(labelCodes.batchPdf(sb.id()), 40, 25, 1);
    }

    @Test
    void w4_label_runs_separator_counts_on_rat_tail_and_unchanged_headers_elsewhere() throws Exception {
        as(rat);
        UUID run = runs.create(rat.owner(), "Nile Gold", null, List.of(
            new LabelRunService.CreateLine(rat.ring(), 3), new LabelRunService.CreateLine(rat.chain(), 2))).id();
        runs.print(run, rat.owner());
        byte[] pdf = runs.pdf(run, 1);
        assertAllPages(pdf, 60, 12, 7);
        assertSeparatorCounts(pdf, List.of(3, 2));

        as(plain);
        UUID plainRun = runs.create(plain.owner(), "Nile Gold", null, List.of(new LabelRunService.CreateLine(plain.ring(), 2))).id();
        runs.print(plainRun, plain.owner());
        byte[] plainPdf = runs.pdf(plainRun, 1);
        assertAllPages(plainPdf, 50, 25, 3);
        assertTodaysHeader(plainPdf);
    }

    @Test
    void w5_tag_stock_separator_counts_on_rat_tail_and_unchanged_headers_elsewhere() throws Exception {
        untaggedSession(rat, rat.ring(), 4);
        untaggedSession(rat, rat.chain(), 2);
        as(rat);
        TagStockService.Session s = tagStock.start(rat.owner());
        TagStockService.PrintMany r = tagStock.printMany(s.id(), List.of(
            new TagStockService.PrintLine(rat.ring(), 4), new TagStockService.PrintLine(rat.chain(), 1)),
            "w5-" + UUID.randomUUID(), rat.owner());
        byte[] pdf = tagStock.groupsPdf(s.id(), r.groups());
        assertAllPages(pdf, 60, 12, 7);
        assertSeparatorCounts(pdf, List.of(4, 1));
        tagStock.end(s.id(), rat.owner());

        untaggedSession(plain, plain.ring(), 2);
        as(plain);
        TagStockService.Session ps = tagStock.start(plain.owner());
        TagStockService.PrintMany pr = tagStock.printMany(ps.id(), List.of(new TagStockService.PrintLine(plain.ring(), 2)),
            "w5-" + UUID.randomUUID(), plain.owner());
        byte[] plainPdf = tagStock.groupsPdf(ps.id(), pr.groups());
        assertAllPages(plainPdf, 50, 25, 3);
        assertTodaysHeader(plainPdf);
        tagStock.end(ps.id(), plain.owner());
    }

    @Test
    void w6_packing_labels_stay_fifty_by_twentyfive() throws Exception {
        for (T t : List.of(rat, small)) {
            untaggedSession(t, t.chain(), 3);
            as(t);
            UUID order = jdbc.queryForObject(
                "INSERT INTO orders (tenant_id, store_id, external_id, number, status, customer_name, payment_method, placed_at, created_at) " +
                "VALUES (?, ?, ?, ?, 'new', 'Mona', 'cod', now(), now()) RETURNING id",
                UUID.class, t.id(), t.store(), "EXT-" + UUID.randomUUID(), "#W6-" + t.id().toString().substring(0, 6));
            UUID item = jdbc.queryForObject("INSERT INTO order_items (tenant_id, order_id, variant_id, quantity) VALUES (?, ?, ?, 1) RETURNING id",
                UUID.class, t.id(), order, t.chain());
            jdbc.update("INSERT INTO shipments (tenant_id, order_id, provider, tracking_number, internal_state, shipment_leg) " +
                "VALUES (?, ?, 'bosta', ?, 'created', 'forward')", t.id(), order, String.valueOf(TRACKING.incrementAndGet()));

            PackLabelService.Summary printed = packLabels.print(new PackLabelService.Request("all", "oldest", null), t.owner());
            assertThat(printed.labels()).isEqualTo(1);
            assertAllPages(packLabels.pdf(printed.parts().get(0).orderIds()), 50, 25, 2);     // order header + label
            assertAllPages(packLabels.replacement(order, item, "w6-" + UUID.randomUUID(), t.owner()), 50, 25, 1);
        }
    }

    @Test
    void w7_settings_round_trip() {
        T t = seed("ST");
        CustomUserDetails owner = new CustomUserDetails(t.owner(), t.id(), "owner", null);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities()));
        assertThat(tenantCtl.get(owner).get("labelSize")).isEqualTo("50x25");
        tenantCtl.update(new TenantController.TenantSettingsRequest(null, null, "60x12", null, null), owner);
        assertThat(tenantCtl.get(owner).get("labelSize")).isEqualTo("60x12");
        PieceLabelLayout.Spec spec = LabelSizes.forTenant(jdbc, t.id());
        assertThat(spec.widthMm()).isEqualTo(60f);
        assertThat(spec.heightMm()).isEqualTo(12f);
        tenantCtl.update(new TenantController.TenantSettingsRequest(null, null, "40x25", null, null), owner);
        assertThat(tenantCtl.get(owner).get("labelSize")).isEqualTo("40x25");
        assertThatThrownBy(() -> tenantCtl.update(new TenantController.TenantSettingsRequest(null, null, "60x15", null, null), owner))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("60x12");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void assertAllPages(byte[] pdf, float wMm, float hMm, int pages) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(pages);
            for (PDPage page : doc.getPages()) {
                assertThat(page.getMediaBox().getWidth()).isCloseTo(wMm * MM, within(0.01f));
                assertThat(page.getMediaBox().getHeight()).isCloseTo(hMm * MM, within(0.01f));
            }
        }
    }

    private static void assertNoImages(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (PDPage page : doc.getPages()) {
                for (var name : page.getResources().getXObjectNames()) {
                    assertThat(page.getResources().isImageXObject(name)).isFalse();
                }
            }
        }
    }

    /**
     * Each "× N" page is followed by exactly N label pages; the counts appear in this order. A separator's Flag A
     * (x 0–20 mm) reads exactly "× N" and a label's Flag A exactly its P-code; no label page carries "×".
     */
    private static void assertSeparatorCounts(byte[] pdf, List<Integer> expected) throws IOException {
        List<String> pages = pageTexts(pdf);
        List<String> flagA = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            for (int p = 0; p < doc.getNumberOfPages(); p++) flagA.add(flagAText(doc, p));
        }
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < pages.size(); ) {
            String head = flagA.get(i);
            assertThat(head).as("page " + (i + 1) + " should start a group").matches("× [0-9]+");
            assertThat(pages.get(i)).as("a separator carries no code").doesNotContainPattern("P[0-9]{6,}");
            int n = Integer.parseInt(head.substring(2));
            assertThat(head).isEqualTo("× " + n);
            seen.add(n);
            for (int k = 1; k <= n; k++) {
                assertThat(flagA.get(i + k)).as("label page " + (i + k + 1)).matches("P[0-9]{6,}");
                assertThat(pages.get(i + k)).doesNotContain("×");
            }
            i += n + 1;
        }
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    /** The text of Flag A (x 0–20 mm, full height) of page {@code page}, lines joined by one space. */
    private static String flagAText(PDDocument doc, int page) throws IOException {
        PDFTextStripperByArea strip = new PDFTextStripperByArea();
        strip.addRegion("flagA", new java.awt.geom.Rectangle2D.Float(0, 0, 20 * MM, 12 * MM));
        strip.extractRegions(doc.getPage(page));
        return String.join(" ", strip.getTextForRegion("flagA").strip().lines().map(String::strip)
            .filter(l -> !l.isEmpty()).toList());
    }

    /** The 50×25 group header as before: product and variant text only — no count, no barcode image. */
    private static void assertTodaysHeader(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDPage header = doc.getPage(0);
            for (var name : header.getResources().getXObjectNames()) {
                assertThat(header.getResources().isImageXObject(name)).isFalse();
            }
            PDFTextStripper strip = new PDFTextStripper();
            strip.setStartPage(1); strip.setEndPage(1);
            String text = strip.getText(doc);
            assertThat(text).doesNotContain("×").doesNotContainPattern("P[0-9]{6,}");
            assertThat(text.strip().lines().count()).isEqualTo(2);
        }
    }

    private static List<String> pageTexts(byte[] pdf) throws IOException {
        List<String> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper strip = new PDFTextStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                strip.setStartPage(i); strip.setEndPage(i);
                out.add(strip.getText(doc).strip());
            }
        }
        return out;
    }
}
