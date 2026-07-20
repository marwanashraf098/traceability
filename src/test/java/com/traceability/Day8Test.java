package com.traceability;

import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Day 8 integration tests: receiving sessions, batchReceive, label PDFs.
 *
 * Test inventory (all via postgres/BYPASSRLS unless noted):
 *   (a) N pieces → exactly N pieces + N received events, all in one ACID transaction
 *   (b) 1,000 pieces created in ≤ 10 seconds (NFR N1)
 *   (c) All piece barcodes are unique and prefixed 'PC-'
 *   (d) All pieces land status='available' at the session location
 *   (e) Finalize rolls back entirely on barcode collision (all-or-nothing)
 *   (f) Every received event carries non-null actor_user_id
 *   (g) Label PDF is generated; contains Code 128 barcode bytes
 *   (h) Reprint is logged in label_reprints (one row per reprint call)
 *   (i) RLS scoping via app_user: tenant B cannot see tenant A's session or pieces
 *   (j) Arabic shaping: ICU4J produces connected letter forms (smoke test)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Day8Test {

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

    @Autowired InventoryLedger  ledger;
    @Autowired ReceivingService receivingSvc;
    @Autowired LabelService     labelSvc;
    @Autowired JdbcTemplate     jdbc;

    // app_user role infrastructure (for RLS isolation test)
    ReceivingService appUserReceiving;
    LabelService     appUserLabelSvc;
    TransactionTemplate appUserTx;

    // Shared fixture IDs
    UUID tenantAId, tenantBId;
    UUID actorId;
    UUID variantAId;
    UUID locationAId;

    @BeforeAll
    void setup() {
        tenantAId = UUID.randomUUID();
        tenantBId = UUID.randomUUID();
        actorId   = UUID.randomUUID();
        UUID storeId   = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        variantAId = UUID.randomUUID();
        locationAId = UUID.randomUUID();

        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TenantA')", tenantAId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'TenantB')", tenantBId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role) VALUES " +
            "(?, ?, 'Actor', 'actor@day8test.local', 'hash', 'owner')",
            actorId, tenantAId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) VALUES " +
            "(?, ?, 'shopify', 'day8test.myshopify.com', 'disconnected')",
            storeId, tenantAId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES " +
            "(?, ?, ?, 'P001', 'Widget', 'active')",
            productId, tenantAId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, 'V001', 'Widget Red', 'WIDRED')",
            variantAId, tenantAId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default) VALUES " +
            "(?, ?, 'Main Warehouse', 'warehouse', true)",
            locationAId, tenantAId);

        // app_user datasource (TestSetup set password via ApplicationReadyEvent)
        DriverManagerDataSource rawAppUser =
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        JdbcTemplate appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx = new TransactionTemplate(appUserTxm);
        InventoryLedger appUserLedger = new InventoryLedger(appUserJdbc);
        appUserReceiving = new ReceivingService(appUserJdbc, appUserLedger, null);
        appUserLabelSvc  = new LabelService(appUserJdbc);
    }

    @BeforeEach
    void setContext() { TenantContext.set(tenantAId); }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        // Clean up pieces and sessions created during each test
        jdbc.update("DELETE FROM label_reprints WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM piece_events WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM pieces WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM receipt_lines WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
        jdbc.update("DELETE FROM receipts WHERE tenant_id = ? OR tenant_id = ?", tenantAId, tenantBId);
    }

    // ── (a) N pieces → N pieces + N events atomically ─────────────────────────

    @Test
    void a_finalize_creates_exactly_N_pieces_and_N_events_atomically() {
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "REF-001", null, null);
        receivingSvc.addLine(sessionId, variantAId, 5);

        int created = receivingSvc.finalize(sessionId, actorId);

        assertThat(created).isEqualTo(5);
        assertThat(countPieces(sessionId)).isEqualTo(5);
        assertThat(countEvents(sessionId)).isEqualTo(5);

        // All events must be 'received' with from_status=NULL and to_status='available'
        List<Map<String, Object>> events = jdbc.queryForList(
            "SELECT pe.from_status, pe.to_status, pe.event_type, pe.actor_user_id " +
            "FROM piece_events pe JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.receipt_id = ?", sessionId);
        for (Map<String, Object> ev : events) {
            assertThat(ev.get("from_status")).as("from_status must be NULL for received events").isNull();
            assertThat(ev.get("to_status")).isEqualTo("available");
            assertThat(ev.get("event_type")).isEqualTo("received");
        }

        // Session must be finalized
        String status = jdbc.queryForObject(
            "SELECT status FROM receipts WHERE id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("finalized");
    }

    // ── (b) 1,000 pieces in ≤ 10 seconds ─────────────────────────────────────

    @Test
    void b_1000_pieces_within_10_seconds() {
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "PERF-1000", null, null);
        receivingSvc.addLine(sessionId, variantAId, 1000);

        long start = System.currentTimeMillis();
        int created = receivingSvc.finalize(sessionId, actorId);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(created).isEqualTo(1000);
        assertThat(elapsed)
            .as("1,000 pieces must be created in ≤ 10,000ms (actual: %dms)", elapsed)
            .isLessThanOrEqualTo(10_000L);
    }

    // ── (c) All barcodes unique with PC- prefix ───────────────────────────────

    @Test
    void c_all_piece_barcodes_are_unique_with_PC_prefix() {
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "BARCODES", null, null);
        receivingSvc.addLine(sessionId, variantAId, 50);
        receivingSvc.finalize(sessionId, actorId);

        List<String> barcodes = jdbc.queryForList(
            "SELECT barcode FROM pieces WHERE receipt_id = ?", String.class, sessionId);

        assertThat(barcodes).hasSize(50);
        assertThat(new HashSet<>(barcodes)).hasSize(50);  // all distinct
        barcodes.forEach(b -> assertThat(b).startsWith("PC-"));
    }

    // ── (d) All pieces status='available' at session location ─────────────────

    @Test
    void d_pieces_are_available_at_session_location() {
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "LOC-CHECK", null, null);
        receivingSvc.addLine(sessionId, variantAId, 3);
        receivingSvc.finalize(sessionId, actorId);

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT status, current_location_id FROM pieces WHERE receipt_id = ?", sessionId);

        assertThat(rows).hasSize(3);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("status")).isEqualTo("available");
            assertThat(row.get("current_location_id")).isEqualTo(locationAId);
        }
    }

    // ── (e) All-or-nothing: partial failure rolls back everything ─────────────

    @Test
    void e_batch_rolls_back_entirely_on_duplicate_barcode() {
        // Pre-insert a piece with a known barcode that will collide
        String collisionId = UlidGenerator.generate();
        jdbc.update(
            "INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status, current_location_id) " +
            "VALUES (?, ?, ?, 'PC-' || ?, 'P099999', 'available'::piece_status, ?)",
            collisionId, tenantAId, variantAId, collisionId, locationAId);

        // Build specs: one valid + one that will collide
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "ATOMIC", null, null);

        // Manually insert a line and then forcibly try to insert a collision.
        // We test batchReceive directly to force a barcode duplicate.
        List<InventoryLedger.ReceiveSpec> specs = List.of(
            new InventoryLedger.ReceiveSpec(UlidGenerator.generate(), tenantAId, variantAId, sessionId, locationAId),
            new InventoryLedger.ReceiveSpec(collisionId, tenantAId, variantAId, sessionId, locationAId) // duplicate
        );

        assertThatThrownBy(() -> {
            TenantContext.set(tenantAId);
            ledger.batchReceive(specs, actorId);
        }).isInstanceOf(Exception.class);

        // No pieces from this batch should exist (full rollback)
        int count = countPieces(sessionId);
        assertThat(count).isEqualTo(0);
        assertThat(countEvents(sessionId)).isEqualTo(0);
    }

    // ── (f) Every received event has non-null actor_user_id ───────────────────

    @Test
    void f_received_events_carry_actor_user_id() {
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "ACTOR", null, null);
        receivingSvc.addLine(sessionId, variantAId, 4);
        receivingSvc.finalize(sessionId, actorId);

        List<UUID> actors = jdbc.queryForList(
            "SELECT pe.actor_user_id FROM piece_events pe " +
            "JOIN pieces p ON p.id = pe.piece_id WHERE p.receipt_id = ?",
            UUID.class, sessionId);

        assertThat(actors).hasSize(4);
        actors.forEach(a -> assertThat(a).as("actor_user_id must not be null").isNotNull()
            .isEqualTo(actorId));
    }

    // ── (g) Label PDF generation ──────────────────────────────────────────────

    @Test
    void g_label_pdf_is_generated() throws IOException {
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "LABELS", null, null);
        receivingSvc.addLine(sessionId, variantAId, 2);
        receivingSvc.finalize(sessionId, actorId);

        byte[] pdf = labelSvc.generateSessionLabels(sessionId, null, null);

        assertThat(pdf).isNotEmpty();
        // PDF magic bytes
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        // 2 pieces → PDF should contain page-content bytes for both
        assertThat(pdf.length).isGreaterThan(500);

        // Save to /tmp for visual inspection of barcode + text layout
        java.nio.file.Files.write(java.nio.file.Path.of("/tmp/day8-label-test.pdf"), pdf);
        System.out.println("Label PDF written to /tmp/day8-label-test.pdf");
    }

    // ── (h) Reprint is logged ─────────────────────────────────────────────────

    @Test
    void h_reprint_is_logged_in_label_reprints() throws IOException {
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "REPRINT", null, null);
        receivingSvc.addLine(sessionId, variantAId, 2);
        receivingSvc.finalize(sessionId, actorId);

        int beforeCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM label_reprints WHERE receipt_id = ?",
            Integer.class, sessionId);
        assertThat(beforeCount).isZero();

        labelSvc.reprint(sessionId, actorId, "lost label", null, null);
        labelSvc.reprint(sessionId, actorId, "customer request", null, null);

        int afterCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM label_reprints WHERE receipt_id = ?",
            Integer.class, sessionId);
        assertThat(afterCount).isEqualTo(2);

        // Each log row must record piece_count and actor
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT piece_count, reprinted_by FROM label_reprints WHERE receipt_id = ? ORDER BY reprinted_at",
            sessionId);
        assertThat(rows).allMatch(r -> ((Number) r.get("piece_count")).intValue() == 2);
        assertThat(rows).allMatch(r -> actorId.equals(r.get("reprinted_by")));
    }

    // ── (i) RLS isolation via app_user: tenant B cannot see tenant A ──────────

    @Test
    void i_tenant_B_cannot_see_tenant_A_session_via_app_user() {
        // Create session and pieces as tenant A
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "RLS-TEST", null, null);
        receivingSvc.addLine(sessionId, variantAId, 3);
        receivingSvc.finalize(sessionId, actorId);

        // Set tenant B context and use app_user datasource (no BYPASSRLS)
        // Tenant B has no data yet so setup just sets a different tenant UUID.
        int sessionsSeenByB = appUserTx.execute(status -> {
            TenantContext.set(tenantBId);
            try {
                List<?> sessions = appUserReceiving.listSessions();
                return sessions.size();
            } finally {
                TenantContext.clear();
            }
        });
        assertThat(sessionsSeenByB)
            .as("Tenant B must see 0 sessions (all belong to tenant A)")
            .isEqualTo(0);

        // Also verify pieces: app_user with tenant B context sees 0 pieces from tenant A
        int piecesSeenByB = appUserTx.execute(status -> {
            TenantContext.set(tenantBId);
            try {
                Integer count = new JdbcTemplate(
                    new TenantAwareDataSource(
                        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw")))
                    .queryForObject(
                        "SELECT COUNT(*) FROM pieces WHERE receipt_id = ?",
                        Integer.class, sessionId);
                return count != null ? count : 0;
            } finally {
                TenantContext.clear();
            }
        });
        assertThat(piecesSeenByB)
            .as("Tenant B must see 0 pieces from tenant A session (RLS enforced)")
            .isEqualTo(0);
    }

    // ── (i2) Arabic label: font embeds + renders without box characters ──────

    @Test
    void i2_arabic_variant_label_renders_without_errors() throws Exception {
        // Create a variant with Arabic title
        UUID arabicProductId = UUID.randomUUID();
        UUID arabicVariantId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES " +
            "(?, ?, (SELECT id FROM stores WHERE tenant_id = ? LIMIT 1), 'PROD-AR', 'بروتين', 'active')",
            arabicProductId, tenantAId, tenantAId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES " +
            "(?, ?, ?, 'VAR-AR', 'مسحوق بروتين فانيلا', 'PROT-VAN')",
            arabicVariantId, tenantAId, arabicProductId);

        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "ARABIC-LABELS", null, null);
        receivingSvc.addLine(sessionId, arabicVariantId, 1);
        receivingSvc.finalize(sessionId, actorId);

        byte[] pdf = labelSvc.generateSessionLabels(sessionId, null, null);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        // Save PDF for visual inspection
        java.nio.file.Files.write(java.nio.file.Path.of("/tmp/day8-label-arabic.pdf"), pdf);

        // NotoSansArabic subset is embedded only when Arabic text is actually rendered.
        // The Arabic PDF must be larger than a Latin-only 1-page label (>1,700 bytes),
        // confirming font data was embedded. PDFBox uses font subsetting so it won't be
        // the full 177KB font — just the glyphs actually used.
        assertThat(pdf.length)
            .as("Arabic label must be larger than Latin-only label (NotoSansArabic subset embedded)")
            .isGreaterThan(1_800);

        // Render page 0 to PNG so the user can see Arabic glyphs are connected, not boxes.
        var readBuf = org.apache.pdfbox.io.RandomAccessReadBuffer.createBufferFromStream(
            new java.io.ByteArrayInputStream(pdf));
        try (org.apache.pdfbox.pdmodel.PDDocument rendered =
                 org.apache.pdfbox.Loader.loadPDF(readBuf)) {
            org.apache.pdfbox.rendering.PDFRenderer renderer =
                new org.apache.pdfbox.rendering.PDFRenderer(rendered);
            java.awt.image.BufferedImage img = renderer.renderImageWithDPI(0, 203);
            javax.imageio.ImageIO.write(img, "PNG",
                new java.io.File("/tmp/day8-label-arabic-preview.png"));
            System.out.println("Arabic label preview → /tmp/day8-label-arabic-preview.png");
        }
    }

    // ── (j) Arabic shaping smoke test ─────────────────────────────────────────

    @Test
    void j_arabic_shaping_produces_connected_forms() {
        // "مرحبا" (Marhaba / Hello in Arabic) in isolated Unicode code points.
        // Without shaping: letters appear disconnected in isolated form.
        // With ICU4J shaping: letters get contextual (initial/medial/final) forms.
        String arabic = "مرحبا";
        String shaped = LabelService.shapeForDisplay(arabic);

        // Shaped output must differ from input (contextual forms replace isolated forms)
        assertThat(shaped)
            .as("ICU4J shaping must produce contextual Arabic letter forms")
            .isNotEqualTo(arabic);

        // Must be non-empty
        assertThat(shaped).isNotBlank();

        // Quick sanity: shaped string length same as input (shaping is a 1:1 code-point remap)
        assertThat(shaped.length()).isEqualTo(arabic.length());
    }

    // ── (k) Full PDF round-trip: encode → render → rasterize → decode ────────

    @Test
    void k_label_barcode_survives_pdf_render_rasterize_decode() throws Exception {
        // Exercises the complete label pipeline: LabelService generates PDF, PDFBox
        // rasterizes a page, ZXing decodes the barcode image.  Fails if MARGIN, pixelW,
        // or the encoded content regresses in a way that makes the symbol undecodable.
        UUID sessionId = receivingSvc.createSession(actorId, locationAId, "LB2", null, null);
        receivingSvc.addLine(sessionId, variantAId, 1);
        receivingSvc.finalize(sessionId, actorId);

        // Retrieve the piece's short code — this is what FR-19 encodes in the barcode.
        String shortCode = jdbc.queryForObject(
            "SELECT short_code FROM pieces WHERE receipt_id = ? LIMIT 1",
            String.class, sessionId);
        assertThat(shortCode).isNotNull();
        assertThat(shortCode).matches("P\\d{6}");

        byte[] pdf = labelSvc.generateSessionLabels(sessionId, null, null);
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        // Rasterize page 0 at 300 DPI via PDFBox.
        var readBuf = org.apache.pdfbox.io.RandomAccessReadBuffer.createBufferFromStream(
            new java.io.ByteArrayInputStream(pdf));
        java.awt.image.BufferedImage page;
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                 org.apache.pdfbox.Loader.loadPDF(readBuf)) {
            page = new org.apache.pdfbox.rendering.PDFRenderer(doc)
                       .renderImageWithDPI(0, 300);
        }

        // Decode via ZXing — MultiFormatReader scans the entire rasterized page.
        var luminance = new com.google.zxing.client.j2se.BufferedImageLuminanceSource(page);
        var bitmap    = new com.google.zxing.BinaryBitmap(
                            new com.google.zxing.common.HybridBinarizer(luminance));
        com.google.zxing.Result decoded = new com.google.zxing.MultiFormatReader().decode(bitmap);

        assertThat(decoded.getText())
            .as("barcode decoded from rasterized PDF page must equal the short code")
            .isEqualTo(shortCode);
        assertThat(decoded.getBarcodeFormat())
            .as("format must be CODE_128")
            .isEqualTo(com.google.zxing.BarcodeFormat.CODE_128);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int countPieces(UUID sessionId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pieces WHERE receipt_id = ?", Integer.class, sessionId);
        return n != null ? n : 0;
    }

    private int countEvents(UUID sessionId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM piece_events pe " +
            "JOIN pieces p ON p.id = pe.piece_id " +
            "WHERE p.receipt_id = ?", Integer.class, sessionId);
        return n != null ? n : 0;
    }
}
