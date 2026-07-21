package com.traceability;

import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.*;
import com.traceability.tenancy.TenantAwareDataSource;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.jobrunr.scheduling.JobScheduler;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Reader;

/**
 * Integration tests for per-variant label printing (FR-20).
 *
 * All RLS-sensitive assertions use the app_user datasource (no BYPASSRLS).
 *
 *   (a) Variant X → 26-page PDF; Variant Y → 20-page PDF.  Assert via PDFBox page count.
 *   (b) X's 26 barcodes are exactly the pieces with receipt_id+variant_id=X; no leakage.
 *   (c) Open (non-finalized) session → 422.
 *   (d) Variant not present in session → 422.
 *   (e) label_reprints row written with correct receipt_id, variant_id, piece_count, reprinted_by.
 *   (f) Same variant on two lines → single merged 15-page PDF; piece_count = sum in reprint log.
 *   (g) Cross-tenant: tenant B hitting tenant A's session → 404 via RLS.
 *   (h) WORKER role → 403 on GET variant labels endpoint.
 *   (i) Arabic variant title renders without font errors (extends i2 pattern from Day8Test).
 *   (j) Existing session-wide generateSessionLabels + reprint still pass after LabelService refactor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class VariantLabelTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate   rest;
    @Autowired JdbcTemplate       jdbc;
    @Autowired LabelService       labelSvc;
    @Autowired ReceivingService   receivingSvc;
    @Autowired PasswordEncoder    passwordEncoder;
    @MockBean  JobScheduler       jobScheduler;

    // app_user datasource for RLS-enforced assertions
    LabelService        appUserLabelSvc;
    JdbcTemplate        appUserJdbc;
    TransactionTemplate appUserTx;

    // Shared fixture IDs
    UUID tenantAId, tenantBId;
    UUID actorId;
    UUID storeId;
    UUID productId;
    UUID variantXId;   // 26 pieces in mainSession
    UUID variantYId;   // 20 pieces in mainSession
    UUID variantDoubleId; // on 2 lines in doubleLineSession (10 + 5 = 15 pieces)
    UUID arabicVariantId; // Arabic title
    UUID variantDummyId;  // exists in DB but NOT in any session
    UUID locationId;

    UUID mainSessionId;       // finalized: 26 of X + 20 of Y
    UUID doubleLineSessionId; // finalized: variantDouble on 2 lines (10+5)
    UUID arabicSessionId;     // finalized: 1 of arabicVariant
    UUID openSessionId;       // open (not finalized)

    @BeforeAll
    void setup() {
        tenantAId       = UUID.randomUUID();
        tenantBId       = UUID.randomUUID();
        actorId         = UUID.randomUUID();
        storeId         = UUID.randomUUID();
        productId       = UUID.randomUUID();
        variantXId      = UUID.randomUUID();
        variantYId      = UUID.randomUUID();
        variantDoubleId = UUID.randomUUID();
        arabicVariantId = UUID.randomUUID();
        variantDummyId  = UUID.randomUUID();
        locationId      = UUID.randomUUID();

        // ── Tenant A fixtures ────────────────────────────────────────────────
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'VLT-TenantA')", tenantAId);
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'VLT-TenantB')", tenantBId);
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Actor', 'actor@vlt.test', 'hash', 'owner', true)",
            actorId, tenantAId);
        jdbc.update(
            "INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'vlt.myshopify.com', 'disconnected')",
            storeId, tenantAId);
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'P-VLT', 'Widget VLT', 'active')",
            productId, tenantAId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VX', 'Widget Red', 'VLT-X')",
            variantXId, tenantAId, productId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VY', 'Widget Blue', 'VLT-Y')",
            variantYId, tenantAId, productId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VD', 'Widget Double', 'VLT-D')",
            variantDoubleId, tenantAId, productId);
        // Arabic product with a pure-Arabic title so labelName is all-Arabic
        // (mixed Latin+Arabic would cause NotoSansArabic to fail on Latin glyphs)
        UUID arabicProductId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO products (id, tenant_id, store_id, external_id, title, status) " +
            "VALUES (?, ?, ?, 'P-AR', 'بروتين', 'active')",
            arabicProductId, tenantAId, storeId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'VAR-AR', 'مسحوق بروتين', 'PROT-AR')",
            arabicVariantId, tenantAId, arabicProductId);
        jdbc.update(
            "INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) " +
            "VALUES (?, ?, ?, 'V-DUMMY', 'Dummy', 'VLT-DUMMY')",
            variantDummyId, tenantAId, productId);
        jdbc.update(
            "INSERT INTO locations (id, tenant_id, name, type, is_default) " +
            "VALUES (?, ?, 'VLT Warehouse', 'warehouse', true)",
            locationId, tenantAId);

        // ── Tenant B — no data, just a UUID for cross-tenant isolation ───────
        // (tenantB row already inserted above)

        // ── Sessions via service (need TenantContext) ─────────────────────────
        TenantContext.set(tenantAId);
        try {
            // Main session: 26 of X + 20 of Y
            mainSessionId = receivingSvc.createSession(actorId, locationId, "MAIN-VLT", null, null);
            receivingSvc.addLine(mainSessionId, variantXId, 26);
            receivingSvc.addLine(mainSessionId, variantYId, 20);
            receivingSvc.finalize(mainSessionId, actorId);

            // Double-line session: variantDouble on TWO lines (10 + 5 = 15 pieces)
            doubleLineSessionId = receivingSvc.createSession(actorId, locationId, "DOUBLE-VLT", null, null);
            receivingSvc.addLine(doubleLineSessionId, variantDoubleId, 10);
            receivingSvc.addLine(doubleLineSessionId, variantDoubleId, 5);
            receivingSvc.finalize(doubleLineSessionId, actorId);

            // Arabic session: 1 piece of arabicVariant
            arabicSessionId = receivingSvc.createSession(actorId, locationId, "ARABIC-VLT", null, null);
            receivingSvc.addLine(arabicSessionId, arabicVariantId, 1);
            receivingSvc.finalize(arabicSessionId, actorId);

            // Open session: NOT finalized (for test c)
            openSessionId = receivingSvc.createSession(actorId, locationId, "OPEN-VLT", null, null);
            receivingSvc.addLine(openSessionId, variantXId, 3);
            // intentionally NOT finalized
        } finally {
            TenantContext.clear();
        }

        // ── app_user datasource ──────────────────────────────────────────────
        DriverManagerDataSource rawAppUser =
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "app_user", "testpw");
        TenantAwareDataSource appUserDs = new TenantAwareDataSource(rawAppUser);
        appUserJdbc = new JdbcTemplate(appUserDs);
        DataSourceTransactionManager appUserTxm = new DataSourceTransactionManager(appUserDs);
        appUserTx     = new TransactionTemplate(appUserTxm);
        appUserLabelSvc = new LabelService(appUserJdbc);
    }

    @BeforeEach
    void setContext() { TenantContext.set(tenantAId); }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        // Clean variant-level reprint rows after each test so assertions stay independent
        jdbc.update("DELETE FROM label_reprints WHERE variant_id IS NOT NULL AND tenant_id = ?", tenantAId);
    }

    // ── (a) Page count: 26 for X, 20 for Y ───────────────────────────────────

    @Test
    void a_variant_pdf_page_counts_match_piece_counts() throws Exception {
        LabelService.VariantPdf xResult = labelSvc.generateVariantLabels(mainSessionId, variantXId, null, null);
        LabelService.VariantPdf yResult = labelSvc.generateVariantLabels(mainSessionId, variantYId, null, null);

        assertThat(xResult.pdf()).isNotEmpty();
        assertThat(yResult.pdf()).isNotEmpty();

        try (PDDocument xDoc = loadPdf(xResult.pdf());
             PDDocument yDoc = loadPdf(yResult.pdf())) {
            assertThat(xDoc.getNumberOfPages())
                .as("Variant X must produce 26 labels (one per piece)")
                .isEqualTo(26);
            assertThat(yDoc.getNumberOfPages())
                .as("Variant Y must produce 20 labels (one per piece)")
                .isEqualTo(20);
        }
    }

    // ── (b) Barcodes match DB pieces exactly, no cross-variant leakage ────────

    @Test
    void b_variant_labels_match_db_pieces_no_leakage() throws Exception {
        // Pull expected short_codes from DB for each variant
        List<String> xCodes = jdbc.queryForList(
            "SELECT short_code FROM pieces WHERE receipt_id = ? AND variant_id = ? ORDER BY barcode ASC",
            String.class, mainSessionId, variantXId);
        List<String> yCodes = jdbc.queryForList(
            "SELECT short_code FROM pieces WHERE receipt_id = ? AND variant_id = ? ORDER BY barcode ASC",
            String.class, mainSessionId, variantYId);

        assertThat(xCodes).hasSize(26);
        assertThat(yCodes).hasSize(20);

        // No overlap between the two variant sets
        assertThat(xCodes).doesNotContainAnyElementsOf(yCodes);

        // Decode first page from X's PDF and verify it belongs to X, not Y
        byte[] xPdf = labelSvc.generateVariantLabels(mainSessionId, variantXId, null, null).pdf();
        String decoded = decodeFirstPageBarcode(xPdf);
        assertThat(xCodes).as("decoded barcode from X PDF must be a piece of variant X").contains(decoded);
        assertThat(yCodes).as("decoded barcode from X PDF must NOT be a piece of variant Y").doesNotContain(decoded);

        // Page count confirms no extra pages from other variants
        try (PDDocument doc = loadPdf(xPdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(26);
        }
    }

    // ── (c) Open session → 422 ────────────────────────────────────────────────

    @Test
    void c_open_session_returns_422() {
        assertThatThrownBy(() ->
            labelSvc.generateVariantLabels(openSessionId, variantXId, null, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ── (d) Variant not in session → 422 ─────────────────────────────────────

    @Test
    void d_variant_not_in_session_returns_422() {
        // variantDummyId is a real variant in the DB but was never added to mainSession
        assertThatThrownBy(() ->
            labelSvc.generateVariantLabels(mainSessionId, variantDummyId, null, null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    // ── (e) Reprint row written with correct fields ────────────────────────────

    @Test
    void e_reprint_row_has_correct_fields() throws Exception {
        byte[] pdf = labelSvc.reprintVariant(mainSessionId, variantXId, actorId, "test reprint", null, null);
        assertThat(pdf).isNotEmpty();

        // Assert row contents via postgres BYPASSRLS — cross-tenant RLS isolation is
        // covered separately by test (g); this test focuses on field correctness only.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT receipt_id, variant_id, piece_count, reprinted_by " +
            "FROM label_reprints WHERE receipt_id = ? AND variant_id = ? " +
            "ORDER BY reprinted_at DESC LIMIT 1",
            mainSessionId, variantXId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("receipt_id")).isEqualTo(mainSessionId);
        assertThat(row.get("variant_id")).isEqualTo(variantXId);
        assertThat(((Number) row.get("piece_count")).intValue()).isEqualTo(26);
        assertThat(row.get("reprinted_by")).isEqualTo(actorId);
    }

    // ── (f) Same variant on two lines → merged PDF, piece_count = sum ─────────

    @Test
    void f_duplicate_variant_lines_produce_merged_pdf() throws Exception {
        // doubleLineSession has 10+5=15 pieces of variantDoubleId across 2 lines
        LabelService.VariantPdf result =
            labelSvc.generateVariantLabels(doubleLineSessionId, variantDoubleId, null, null);

        try (PDDocument doc = loadPdf(result.pdf())) {
            assertThat(doc.getNumberOfPages())
                .as("Merged PDF for 2 lines of same variant must have 15 pages (10+5)")
                .isEqualTo(15);
        }

        // Reprint logs total piece_count = 15, not per-line counts
        labelSvc.reprintVariant(doubleLineSessionId, variantDoubleId, actorId, "merge test", null, null);
        Integer reprintCount = jdbc.queryForObject(
            "SELECT piece_count FROM label_reprints WHERE receipt_id = ? AND variant_id = ? " +
            "ORDER BY reprinted_at DESC LIMIT 1",
            Integer.class, doubleLineSessionId, variantDoubleId);
        assertThat(reprintCount).isEqualTo(15);
    }

    // ── (g) Cross-tenant: tenant B → 404 via RLS ──────────────────────────────

    @Test
    void g_cross_tenant_returns_404_via_rls() {
        // tenant B tries to access tenant A's session via the app_user (RLS-enforced) datasource
        appUserTx.execute(txStatus -> {
            TenantContext.set(tenantBId);
            try {
                appUserLabelSvc.generateVariantLabels(mainSessionId, variantXId, null, null);
                fail("Expected 404 for cross-tenant access");
            } catch (ResponseStatusException e) {
                assertThat(e.getStatusCode())
                    .as("Tenant B must receive 404 (session not found under RLS)")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            } catch (Exception e) {
                fail("Unexpected exception: " + e.getMessage());
            } finally {
                TenantContext.clear();
            }
            return null;
        });
    }

    // ── (h) WORKER role → 403 ────────────────────────────────────────────────

    @Test
    void h_worker_role_returns_403() {
        UUID workerId = UUID.randomUUID();
        String workerEmail = "worker-vlt-" + workerId + "@test.local";
        jdbc.update(
            "INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Worker', ?, ?, 'worker', true)",
            workerId, tenantAId, workerEmail, passwordEncoder.encode("pass123"));

        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AccessTokenResponse> loginResp = rest.postForEntity(
            base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", workerEmail, "password", "pass123"), loginHeaders),
            AccessTokenResponse.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String workerToken = loginResp.getBody().accessToken();

        HttpHeaders reqHeaders = new HttpHeaders();
        reqHeaders.setBearerAuth(workerToken);
        ResponseEntity<byte[]> resp = rest.exchange(
            base() + "/api/v1/receiving/sessions/" + mainSessionId +
                "/variants/" + variantXId + "/labels",
            HttpMethod.GET, new HttpEntity<>(reqHeaders), byte[].class);

        assertThat(resp.getStatusCode())
            .as("WORKER role must receive 403 on variant labels endpoint")
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── (i) Arabic variant title renders without font errors ──────────────────

    @Test
    void i_arabic_variant_title_renders_without_font_errors() throws Exception {
        LabelService.VariantPdf result =
            labelSvc.generateVariantLabels(arabicSessionId, arabicVariantId, null, null);

        assertThat(result.pdf()).isNotEmpty();
        assertThat(new String(result.pdf(), 0, 4)).isEqualTo("%PDF");

        try (PDDocument doc = loadPdf(result.pdf())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }

        // Arabic variant title triggers NotoSansArabic embedding → PDF > 1,800 bytes
        assertThat(result.pdf().length)
            .as("Arabic label must embed NotoSansArabic font subset (PDF > 1,800 bytes)")
            .isGreaterThan(1_800);

        // Rasterize page 0 to confirm no rendering errors
        try (PDDocument doc = loadPdf(result.pdf())) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 203);
            assertThat(img).isNotNull();
            assertThat(img.getWidth()).isGreaterThan(0);
        }
    }

    // ── (j) Session-wide label + reprint still green after refactor ──────────

    @Test
    void j_session_wide_labels_unaffected_by_refactor() throws Exception {
        // generateSessionLabels and reprint() must still work exactly as before
        byte[] pdf = labelSvc.generateSessionLabels(mainSessionId, null, null);
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        try (PDDocument doc = loadPdf(pdf)) {
            assertThat(doc.getNumberOfPages())
                .as("Session-wide PDF must have 46 pages (26+20)")
                .isEqualTo(46);
        }

        // reprint() must log a whole-session row (variant_id = NULL)
        labelSvc.reprint(mainSessionId, actorId, "regression check", null, null);
        Integer reprintCount = jdbc.queryForObject(
            "SELECT piece_count FROM label_reprints WHERE receipt_id = ? AND variant_id IS NULL " +
            "ORDER BY reprinted_at DESC LIMIT 1",
            Integer.class, mainSessionId);
        assertThat(reprintCount).isEqualTo(46);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private PDDocument loadPdf(byte[] pdf) throws IOException {
        return Loader.loadPDF(
            RandomAccessReadBuffer.createBufferFromStream(new ByteArrayInputStream(pdf)));
    }

    /** Rasterizes page 0 of the PDF and decodes its Code 128 barcode with ZXing. */
    private String decodeFirstPageBarcode(byte[] pdf) throws Exception {
        try (PDDocument doc = loadPdf(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 300);
            BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(img)));
            return new Code128Reader().decode(bitmap).getText();
        }
    }
}
