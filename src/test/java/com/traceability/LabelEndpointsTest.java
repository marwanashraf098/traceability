package com.traceability;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.oned.Code128Reader;
import com.traceability.identity.model.AccessTokenResponse;
import com.traceability.inventory.LabelService;
import com.traceability.inventory.ReceivingService;
import com.traceability.inventory.ReturnSessionService;
import com.traceability.inventory.TransferService;
import com.traceability.tenancy.TenantContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every piece-label endpoint, through HTTP, at 50×25 (default) and 40×25 (widthMm/heightMm):
 * 200, application/pdf, one page per piece, the barcode on EVERY page decodes to that page's
 * piece short code, and every font on every page is embedded (no Standard-14 Helvetica).
 *
 * Fixture set (the same one the printer samples use): short English, very long English, long
 * Arabic, mixed Arabic/English with digits, Arabic next to "/" "&" "…", blank SKU with
 * "Default Title", Cyrillic.
 *
 * r1 is the regression for the Arabic + "/" label that returned 500 ("No glyph for U+002F" in
 * NotoSansArabic) — red on the pre-fix renderer.
 *
 * Set -Dlabel.samples.dir=/some/dir to also write each feature's PDF there for a printer check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelEndpointsTest {

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

    /** product title, variant title, sku (null = blank) — one piece each. */
    static final String[][] FIXTURES = {
        {"Linen Shirt", "White / M", "LIN-W-M"},
        {"Organic Cotton Oversized Heavyweight Hoodie with Embroidered Logo — Limited Autumn Edition",
            "Charcoal Grey / XXL / Relaxed Fit", "HOOD-ORG-CHAR-XXL-RELAXED-2026"},
        {"بلوزة قطنية واسعة بأكمام طويلة وتطريز يدوي — إصدار الخريف المحدود", "أحمر / مقاس كبير", "BLZ-AR-RED-L"},
        {"Vanilla Whey 1KG - بروتين واي", "بروتين واي 1000g", "WHEY-VW-1000"},
        {"عطر ورد & عود", "S / أحمر…", "PERF-ROSE-S"},
        {"Canvas Tote", "Default Title", null},
        {"Платье летнее", "Синий / M", "DRESS-RU-M"},
    };

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivingService receiving;
    @Autowired ReturnSessionService returnSessions;
    @Autowired TransferService transfers;
    @Autowired LabelService labels;
    @Autowired PasswordEncoder passwordEncoder;
    @MockBean JobScheduler jobScheduler;

    UUID tenantId, ownerId, locationId, sessionId, slashSessionId, slashVariantId;
    List<UUID> variantIds = new ArrayList<>();
    String token;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID(); ownerId = UUID.randomUUID(); locationId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String email = "owner-labels-" + ownerId + "@test.local";
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, 'Label Endpoints Co')", tenantId);
        jdbc.update("INSERT INTO users (id, tenant_id, name, email, password_hash, role, active) " +
            "VALUES (?, ?, 'Owner', ?, ?, 'owner', true)", ownerId, tenantId, email, passwordEncoder.encode("pass123"));
        jdbc.update("INSERT INTO stores (id, tenant_id, platform, shop_domain, status) " +
            "VALUES (?, ?, 'shopify', 'labels.myshopify.com', 'disconnected')", storeId, tenantId);
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Main Warehouse', 'warehouse', true, true)", locationId, tenantId);
        for (int i = 0; i < FIXTURES.length; i++) {
            UUID product = UUID.randomUUID(), variant = UUID.randomUUID();
            jdbc.update("INSERT INTO products (id, tenant_id, store_id, external_id, title, status) VALUES (?, ?, ?, ?, ?, 'active')",
                product, tenantId, storeId, "P-LBL-" + i, FIXTURES[i][0]);
            jdbc.update("INSERT INTO variants (id, tenant_id, product_id, external_id, title, sku) VALUES (?, ?, ?, ?, ?, ?)",
                variant, tenantId, product, "V-LBL-" + i, FIXTURES[i][1], FIXTURES[i][2]);
            variantIds.add(variant);
        }
        slashVariantId = variantIds.get(4);

        TenantContext.set(tenantId);
        try {
            sessionId = receiving.createSession(ownerId, locationId, "LABELS", null, null);
            for (UUID v : variantIds) receiving.addLine(sessionId, v, 1);
            receiving.addLine(sessionId, variantIds.get(0), 2);   // a variant with 3 pieces
            receiving.finalize(sessionId, ownerId);

            slashSessionId = receiving.createSession(ownerId, locationId, "SLASH", null, null);
            receiving.addLine(slashSessionId, slashVariantId, 1);
            receiving.finalize(slashSessionId, ownerId);
        } finally {
            TenantContext.clear();
        }

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        token = rest.postForEntity(base() + "/api/v1/auth/login",
            new HttpEntity<>(Map.of("email", email, "password", "pass123"), h), AccessTokenResponse.class)
            .getBody().accessToken();
    }

    // ── r1: the Arabic + "/" label that returned 500 ──────────────────────────

    @Test
    void r1_arabicNextToSlashAmpersandEllipsis_200_decodes_fontsEmbedded() throws Exception {
        ResponseEntity<byte[]> resp = get("/api/v1/receiving/sessions/" + slashSessionId + "/variants/" + slashVariantId + "/labels");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertLabels(resp.getBody(), codesOf(slashSessionId, slashVariantId), 50f, 25f);
    }

    // ── every endpoint, both sizes ────────────────────────────────────────────

    @Test
    void e1_receivingSessionLabels_bothSizes() throws Exception {
        for (float[] size : sizes()) {
            ResponseEntity<byte[]> resp = get("/api/v1/receiving/sessions/" + sessionId + "/labels" + query(size));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertLabels(resp.getBody(), codesOf(sessionId, null), size[0], size[1]);
            sample("receiving-session", size, resp.getBody());
        }
    }

    @Test
    void e2_receivingSessionReprint_bothSizes() throws Exception {
        for (float[] size : sizes()) {
            ResponseEntity<byte[]> resp = post("/api/v1/receiving/sessions/" + sessionId + "/reprint", reprintBody(size));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertLabels(resp.getBody(), codesOf(sessionId, null), size[0], size[1]);
        }
    }

    @Test
    void e3_receivingVariantLabels_bothSizes_everyFixture() throws Exception {
        for (float[] size : sizes()) {
            for (UUID v : variantIds) {
                ResponseEntity<byte[]> resp = get("/api/v1/receiving/sessions/" + sessionId + "/variants/" + v + "/labels" + query(size));
                assertThat(resp.getStatusCode()).as("variant %s", v).isEqualTo(HttpStatus.OK);
                assertLabels(resp.getBody(), codesOf(sessionId, v), size[0], size[1]);
            }
            ResponseEntity<byte[]> three = get("/api/v1/receiving/sessions/" + sessionId + "/variants/" + variantIds.get(0) + "/labels" + query(size));
            sample("receiving-variant", size, three.getBody());
        }
    }

    @Test
    void e4_receivingVariantReprint_bothSizes() throws Exception {
        for (float[] size : sizes()) {
            ResponseEntity<byte[]> resp = post("/api/v1/receiving/sessions/" + sessionId + "/variants/" + variantIds.get(1) + "/reprint", reprintBody(size));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertLabels(resp.getBody(), codesOf(sessionId, variantIds.get(1)), size[0], size[1]);
        }
    }

    @Test
    void e5_returnsReprintLabel_everyFixture_bothSizes() throws Exception {
        UUID returnSession = TenantContext.runAs(tenantId, () -> returnSessions.createSession("labels", ownerId));
        List<byte[]> pages = new ArrayList<>();
        for (float[] size : sizes()) {
            for (UUID v : variantIds) {
                String piece = pieceOf(sessionId, v);
                ResponseEntity<byte[]> resp = post("/api/v1/returns/sessions/" + returnSession + "/pieces/" + piece + "/reprint-label" + query(size), null);
                assertThat(resp.getStatusCode()).as("piece %s", piece).isEqualTo(HttpStatus.OK);
                assertLabels(resp.getBody(), List.of(shortCode(piece)), size[0], size[1]);
                if (v.equals(variantIds.get(2))) sample("returns-reprint", size, resp.getBody());
            }
        }
    }

    @Test
    void e6_returnsGatedPieceLabel_bothSizes() throws Exception {
        String piece = pieceOf(sessionId, variantIds.get(3));
        jdbc.update("UPDATE pieces SET status = 'return_pending_inspection'::piece_status WHERE id = ?", piece);
        try {
            for (float[] size : sizes()) {
                ResponseEntity<byte[]> resp = get("/api/v1/returns/pieces/" + piece + "/label" + query(size));
                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertLabels(resp.getBody(), List.of(shortCode(piece)), size[0], size[1]);
            }
        } finally {
            jdbc.update("UPDATE pieces SET status = 'available'::piece_status WHERE id = ?", piece);
        }
    }

    @Test
    void e7_transferReprintOutstanding_everyFixture_everyPageDecodes() throws Exception {
        UUID showroom = UUID.randomUUID();
        jdbc.update("INSERT INTO locations (id, tenant_id, name, type, is_default, is_fulfillment) " +
            "VALUES (?, ?, 'Showroom', 'showroom', false, false)", showroom, tenantId);
        List<String> pieces = new ArrayList<>();
        UUID transfer = TenantContext.runAs(tenantId, () -> {
            UUID t = transfers.createTransfer("showroom", showroom, null, "labels", ownerId);
            for (UUID v : variantIds) {
                String piece = pieceOf(sessionId, v);
                transfers.scanOut(t, jdbc.queryForObject("SELECT barcode FROM pieces WHERE id = ?", String.class, piece), ownerId);
                pieces.add(piece);
            }
            return t;
        });
        try {
            ResponseEntity<byte[]> resp = post("/api/v1/transfers/" + transfer + "/reprint-outstanding", null);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertLabels(resp.getBody(), pieces.stream().map(this::shortCode).toList(), 50f, 25f);
            sample("transfer-reprint-outstanding", new float[]{50f, 25f}, resp.getBody());

            // The endpoint prints the default 50×25 (the tenant size isn't wired yet); the same
            // one-document call it uses, at 40×25:
            byte[] at40 = TenantContext.runAs(tenantId, () -> labels.generatePieceLabels(pieces, 40f, 25f));
            assertLabels(at40, pieces.stream().map(this::shortCode).toList(), 40f, 25f);
            sample("transfer-reprint-outstanding", new float[]{40f, 25f}, at40);
        } finally {
            jdbc.update("DELETE FROM piece_events WHERE piece_id = ANY(?) AND event_type <> 'received'",
                (Object) pieces.toArray(new String[0]));
            jdbc.update("DELETE FROM transfer_pieces WHERE transfer_id = ?", transfer);
            jdbc.update("UPDATE pieces SET status = 'available'::piece_status WHERE id = ANY(?)", (Object) pieces.toArray(new String[0]));
        }
    }

    // ── assertions ────────────────────────────────────────────────────────────

    /**
     * One page per expected code; the barcode on EVERY page decodes, and the decoded codes are
     * exactly the expected ones (as a multiset — pieces from one batch share created_at, so their
     * page order isn't defined); page size; every font embedded.
     */
    static void assertLabels(byte[] pdf, List<String> expectedCodes, float widthMm, float heightMm) throws Exception {
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(expectedCodes.size());
            PDFRenderer renderer = new PDFRenderer(doc);
            List<String> decoded = new ArrayList<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                assertThat(page.getMediaBox().getWidth()).isCloseTo(widthMm * 72f / 25.4f, org.assertj.core.data.Offset.offset(0.1f));
                assertThat(page.getMediaBox().getHeight()).isCloseTo(heightMm * 72f / 25.4f, org.assertj.core.data.Offset.offset(0.1f));
                decoded.add(decode(renderer.renderImageWithDPI(i, 300)));
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    assertThat(font.isEmbedded()).as("page %d font %s embedded", i + 1, font.getName()).isTrue();
                    assertThat(font).as("no Standard-14 font").isNotInstanceOf(PDType1Font.class);
                }
            }
            assertThat(decoded).containsExactlyInAnyOrderElementsOf(expectedCodes);
        }
    }

    static String decode(BufferedImage img) throws Exception {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img)));
        return new Code128Reader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<float[]> sizes() {
        return List.of(new float[]{50f, 25f}, new float[]{40f, 25f});
    }

    private static String query(float[] size) {
        return size[0] == 50f ? "" : "?widthMm=" + size[0] + "&heightMm=" + size[1];
    }

    private static Map<String, Object> reprintBody(float[] size) {
        Map<String, Object> body = new HashMap<>();
        body.put("note", "label test");
        if (size[0] != 50f) { body.put("widthMm", size[0]); body.put("heightMm", size[1]); }
        return body;
    }

    private List<String> codesOf(UUID receipt, UUID variant) {
        return variant == null
            ? jdbc.queryForList("SELECT short_code FROM pieces WHERE receipt_id = ? ORDER BY created_at", String.class, receipt)
            : jdbc.queryForList("SELECT short_code FROM pieces WHERE receipt_id = ? AND variant_id = ? ORDER BY barcode", String.class, receipt, variant);
    }

    private String pieceOf(UUID receipt, UUID variant) {
        return jdbc.queryForObject("SELECT id FROM pieces WHERE receipt_id = ? AND variant_id = ? ORDER BY barcode LIMIT 1",
            String.class, receipt, variant);
    }

    private String shortCode(String piece) {
        return jdbc.queryForObject("SELECT short_code FROM pieces WHERE id = ?", String.class, piece);
    }

    private static void sample(String feature, float[] size, byte[] pdf) throws Exception {
        String dir = System.getProperty("label.samples.dir");
        if (dir == null || dir.isBlank()) return;
        Files.createDirectories(Path.of(dir));
        Files.write(Path.of(dir, feature + "-" + (int) size[0] + "x" + (int) size[1] + ".pdf"), pdf);
    }

    private ResponseEntity<byte[]> get(String path) {
        return rest.exchange(base() + path, HttpMethod.GET, new HttpEntity<>(auth()), byte[].class);
    }

    private ResponseEntity<byte[]> post(String path, Object body) {
        HttpHeaders h = auth();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base() + path, HttpMethod.POST, new HttpEntity<>(body, h), byte[].class);
    }

    private HttpHeaders auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private String base() { return "http://localhost:" + port; }
}
