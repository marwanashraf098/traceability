package com.traceability.inventory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.EnumMap;

import com.traceability.tenancy.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates thermal label PDFs (Code 128 barcode, piece ID, SKU, variant name).
 *
 * Default label size: 50×25mm — configurable per tenant via receipts.location_id→tenants.
 * Noto Sans Arabic is embedded for correct Arabic glyph rendering (FR-6.4).
 *
 * Arabic text handling:
 *   1. ICU4J ArabicShaping converts isolated code points to contextual letter forms.
 *   2. ICU4J Bidi reverses the visual order for RTL presentation.
 *   3. PDType0Font renders the shaped string using embedded NotoSansArabic glyphs.
 */
@Service
public class LabelService {

    // 1 point = 1/72 inch; 1 mm = 72/25.4 pt
    private static final float MM_TO_PT = 72f / 25.4f;

    // Default label size
    static final float DEFAULT_WIDTH_MM  = 50f;
    static final float DEFAULT_HEIGHT_MM = 25f;

    private static final float MARGIN        = 3f  * MM_TO_PT;   // 3mm margin
    private static final float BARCODE_H_MM  = 12f;
    private static final int   DPI           = 203;

    private final JdbcTemplate jdbc;

    public LabelService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a PDF containing one label per piece in the given session.
     * If the session has no pieces yet (not finalized), throws 422.
     *
     * @param sessionId  receipt UUID
     * @param labelW_mm  label width in mm  (null → DEFAULT_WIDTH_MM)
     * @param labelH_mm  label height in mm (null → DEFAULT_HEIGHT_MM)
     */
    @Transactional(readOnly = true)
    public byte[] generateSessionLabels(UUID sessionId, Float labelW_mm, Float labelH_mm)
            throws IOException {
        UUID tenantId = TenantContext.require();

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.barcode, p.short_code, v.sku, v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v ON v.id = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.receipt_id = ? AND p.tenant_id = ? " +
            "ORDER BY p.created_at",
            sessionId, tenantId);

        if (pieces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No pieces found for session — finalize first");
        }

        return renderPdf(pieces,
            labelW_mm  != null ? labelW_mm  : DEFAULT_WIDTH_MM,
            labelH_mm  != null ? labelH_mm  : DEFAULT_HEIGHT_MM);
    }

    /** Generates a PDF for a single piece by its ID (for reprint). */
    @Transactional(readOnly = true)
    public byte[] generatePieceLabel(String pieceId, Float labelW_mm, Float labelH_mm)
            throws IOException {
        UUID tenantId = TenantContext.require();

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.barcode, p.short_code, v.sku, v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v ON v.id = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.id = ? AND p.tenant_id = ?",
            pieceId, tenantId);

        if (pieces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Piece not found");
        }

        return renderPdf(pieces,
            labelW_mm  != null ? labelW_mm  : DEFAULT_WIDTH_MM,
            labelH_mm  != null ? labelH_mm  : DEFAULT_HEIGHT_MM);
    }

    /** Logs a reprint event and returns the PDF bytes. */
    @Transactional
    public byte[] reprint(UUID sessionId, UUID actorUserId, String note,
                          Float labelW_mm, Float labelH_mm) throws IOException {
        UUID tenantId = TenantContext.require();
        byte[] pdf = generateSessionLabels(sessionId, labelW_mm, labelH_mm);

        int count = countPieces(sessionId, tenantId);
        jdbc.update(
            "INSERT INTO label_reprints (tenant_id, receipt_id, reprinted_by, piece_count, note) " +
            "VALUES (?, ?, ?, ?, ?)",
            tenantId, sessionId, actorUserId, count, note);

        return pdf;
    }

    // ── Per-variant labels ────────────────────────────────────────────────────

    /** Returned by generateVariantLabels so the controller can build a SKU-based filename. */
    public record VariantPdf(byte[] pdf, String sku) {}

    /**
     * Generates a PDF containing one label per piece for a specific variant
     * in the given session.  Session must be finalized (404 if not found,
     * 422 if open).  Zero matching pieces → 422.
     * Ordering: barcode ASC (ULID is time-sortable = generation order).
     */
    @Transactional(readOnly = true)
    public VariantPdf generateVariantLabels(UUID sessionId, UUID variantId,
                                            Float labelW_mm, Float labelH_mm) throws IOException {
        UUID tenantId = TenantContext.require();
        requireFinalized(sessionId, tenantId);

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.barcode, p.short_code, v.sku, v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.receipt_id = ? AND p.variant_id = ? AND p.tenant_id = ? " +
            "ORDER BY p.barcode ASC",
            sessionId, variantId, tenantId);

        if (pieces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "No pieces found for this variant in this session");
        }

        String sku = (String) pieces.get(0).get("sku");
        byte[] pdf = renderPdf(pieces,
            labelW_mm != null ? labelW_mm : DEFAULT_WIDTH_MM,
            labelH_mm != null ? labelH_mm : DEFAULT_HEIGHT_MM);
        return new VariantPdf(pdf, sku);
    }

    /**
     * Logs a variant-level reprint event and returns the PDF bytes.
     * Writes a label_reprints row with variant_id set.
     */
    @Transactional
    public byte[] reprintVariant(UUID sessionId, UUID variantId, UUID actorUserId, String note,
                                 Float labelW_mm, Float labelH_mm) throws IOException {
        UUID tenantId = TenantContext.require();
        VariantPdf result = generateVariantLabels(sessionId, variantId, labelW_mm, labelH_mm);
        int count = countVariantPieces(sessionId, variantId, tenantId);
        jdbc.update(
            "INSERT INTO label_reprints (tenant_id, receipt_id, variant_id, reprinted_by, piece_count, note) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            tenantId, sessionId, variantId, actorUserId, count, note);
        return result.pdf();
    }

    private void requireFinalized(UUID sessionId, UUID tenantId) {
        List<String> rows = jdbc.queryForList(
            "SELECT status FROM receipts WHERE id = ? AND tenant_id = ?",
            String.class, sessionId, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found");
        }
        if (!"finalized".equals(rows.get(0))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Session is not finalized — finalize the session first");
        }
    }

    private int countVariantPieces(UUID sessionId, UUID variantId, UUID tenantId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pieces WHERE receipt_id = ? AND variant_id = ? AND tenant_id = ?",
            Integer.class, sessionId, variantId, tenantId);
        return n != null ? n : 0;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private byte[] renderPdf(List<Map<String, Object>> pieces,
                             float widthMm, float heightMm) throws IOException {
        float wPt = widthMm  * MM_TO_PT;
        float hPt = heightMm * MM_TO_PT;
        PDRectangle pageSize = new PDRectangle(wPt, hPt);

        try (PDDocument doc = new PDDocument();
             InputStream fontStream =
                 new ClassPathResource("fonts/NotoSansArabic-Regular.ttf").getInputStream()) {

            // Latin fields (piece ID, SKU, barcode human-readable) — always ASCII;
            // use the built-in Helvetica so no embedding is needed.
            PDFont latinFont  = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            // Arabic/mixed variant titles — embed Noto Sans Arabic.
            PDType0Font arabicFont = PDType0Font.load(doc, fontStream, true);

            for (Map<String, Object> piece : pieces) {
                String shortCode    = (String) piece.get("short_code");
                String sku          = nullSafe(piece.get("sku"));
                String productTitle = truncate(nullSafe(piece.get("product_title")), 28);
                String variantTitle = nullSafe(piece.get("variant_title"));
                // Show variant only when it carries real info (not the Shopify placeholder)
                String labelName = productTitle.isEmpty() ? variantTitle
                    : ("Default Title".equalsIgnoreCase(variantTitle) ? productTitle
                       : truncate(productTitle + " - " + variantTitle, 32));

                PDPage page = new PDPage(pageSize);
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    drawLabel(cs, doc, latinFont, arabicFont, shortCode, sku,
                              labelName, wPt, hPt);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void drawLabel(PDPageContentStream cs, PDDocument doc,
                           PDFont latinFont, PDType0Font arabicFont,
                           String shortCode, String sku, String variantTitle,
                           float wPt, float hPt) throws IOException {

        // ── Barcode image ────────────────────────────────────────────────────
        // Compute draw dimensions first so renderBarcode gets the exact target width —
        // the bitmap pixel count must match the drawn pt width at DPI, not the full label.
        float barcodeW = wPt - 2 * MARGIN;
        float barcodeH = BARCODE_H_MM * MM_TO_PT;
        float barcodeY = hPt - MARGIN - barcodeH;

        // Encode the short code (e.g. "P000001", 7 chars) — 132 total modules at MARGIN=10
        // → 0.333mm/module on a 44mm label, well above the 0.191mm GS1 general-use minimum.
        BufferedImage barcodeImg = renderBarcode(shortCode, barcodeW, barcodeH);
        PDImageXObject barcodeXObj = PDImageXObject.createFromByteArray(
            doc, toPngBytes(barcodeImg), "barcode");

        cs.drawImage(barcodeXObj, MARGIN, barcodeY, barcodeW, barcodeH);

        // ── Text rows ────────────────────────────────────────────────────────
        float fontSize1 = 5.5f;
        float fontSize2 = 6.5f;

        // Row 1: short code as human-readable caption — matches what is encoded in the barcode.
        drawCenteredText(cs, latinFont, fontSize1, shortCode, wPt, barcodeY - 1.5f * MM_TO_PT);

        // Row 2: SKU left (ASCII → Helvetica), variant name right (may be Arabic)
        float row2Y = barcodeY - 1.5f * MM_TO_PT - fontSize1 - 2f;
        drawTextRow(cs, latinFont, arabicFont, fontSize2, sku, variantTitle, wPt, row2Y);
    }

    // ── Text helpers ──────────────────────────────────────────────────────────

    private void drawCenteredText(PDPageContentStream cs, PDFont font,
                                  float size, String text, float pageWidth, float y)
            throws IOException {
        float tw;
        try {
            tw = font.getStringWidth(text) / 1000f * size;
        } catch (Exception e) {
            tw = 0;
        }
        float x = (pageWidth - tw) / 2f;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    /**
     * Draws left text in latinFont and right text in the appropriate font:
     * arabicFont if the string contains Arabic; latinFont otherwise.
     */
    private void drawTextRow(PDPageContentStream cs, PDFont latinFont, PDType0Font arabicFont,
                             float size, String left, String right, float pageWidth, float y)
            throws IOException {
        // Left text (SKU — always ASCII)
        if (!left.isEmpty()) {
            cs.beginText();
            cs.setFont(latinFont, size);
            cs.newLineAtOffset(MARGIN, y);
            cs.showText(left);
            cs.endText();
        }

        // Right text (variant name — pick font based on content)
        boolean isArabic = containsArabic(right);
        PDFont  rightFont = isArabic ? arabicFont : latinFont;
        String  displayRight = isArabic ? shapeForDisplay(right) : right;

        float tw;
        try {
            tw = rightFont.getStringWidth(displayRight) / 1000f * size;
        } catch (Exception e) {
            tw = 0;
        }
        float rx = pageWidth - MARGIN - tw;
        cs.beginText();
        cs.setFont(rightFont, size);
        cs.newLineAtOffset(rx, y);
        cs.showText(displayRight);
        cs.endText();
    }

    /**
     * Applies ICU4J Arabic shaping so letter forms connect properly and text reads
     * left-to-right in visual (rendered) order. Latin text passes through unchanged.
     */
    public static String shapeForDisplay(String text) {
        if (text == null || text.isBlank()) return "";
        if (!containsArabic(text)) return text;
        try {
            // 1. Shape: convert isolated Unicode code points to contextual letter forms
            ArabicShaping shaping = new ArabicShaping(
                ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_VISUAL_LTR);
            String shaped = shaping.shape(text);

            // 2. Reorder: apply Unicode Bidi algorithm to get visual left-to-right order
            Bidi bidi = new Bidi();
            bidi.setPara(shaped, Bidi.RTL, null);
            return bidi.writeReordered(Bidi.DO_MIRRORING);
        } catch (ArabicShapingException e) {
            return text; // fallback: unshaped but won't show boxes (font has glyphs)
        }
    }

    private static boolean containsArabic(String text) {
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC) return true;
        }
        return false;
    }

    // ── Barcode rendering ─────────────────────────────────────────────────────

    private BufferedImage renderBarcode(String content, float drawWidthPt, float drawHeightPt) {
        // Generate bitmap at the exact draw-area size so PDFBox draws it 1:1 (no scale).
        int pixelW = Math.round(drawWidthPt  * DPI / 72f);
        int pixelH = Math.round(drawHeightPt * DPI / 72f);
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // 10 quiet-zone modules each side — ISO/IEC 15417 minimum; without this scanners
        // cannot locate the start/stop guard bars and the symbol is undecodable.
        hints.put(EncodeHintType.MARGIN, 10);
        BitMatrix matrix = new Code128Writer().encode(content, BarcodeFormat.CODE_128,
            pixelW, pixelH, hints);
        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    private byte[] toPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    private String nullSafe(Object o) { return o != null ? o.toString() : ""; }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : (s != null ? s : "");
    }

    private int countPieces(UUID sessionId, UUID tenantId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pieces WHERE receipt_id = ? AND tenant_id = ?",
            Integer.class, sessionId, tenantId);
        return n != null ? n : 0;
    }
}
