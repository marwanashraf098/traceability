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
    public byte[] generateSessionLabels(UUID sessionId, Float labelW_mm, Float labelH_mm)
            throws IOException {
        UUID tenantId = TenantContext.require();

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.barcode, v.sku, v.title AS variant_title, pr.title AS product_title " +
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
    public byte[] generatePieceLabel(String pieceId, Float labelW_mm, Float labelH_mm)
            throws IOException {
        UUID tenantId = TenantContext.require();

        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.barcode, v.sku, v.title AS variant_title, pr.title AS product_title " +
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
                String pieceId      = (String) piece.get("id");
                String barcode      = (String) piece.get("barcode");
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
                    drawLabel(cs, doc, latinFont, arabicFont, barcode, pieceId, sku,
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
                           String barcode, String pieceId, String sku, String variantTitle,
                           float wPt, float hPt) throws IOException {

        // ── Barcode image ────────────────────────────────────────────────────
        BufferedImage barcodeImg = renderBarcode(barcode, wPt, hPt);
        PDImageXObject barcodeXObj = PDImageXObject.createFromByteArray(
            doc, toPngBytes(barcodeImg), "barcode");

        float barcodeW = wPt - 2 * MARGIN;
        float barcodeH = BARCODE_H_MM * MM_TO_PT;
        float barcodeY = hPt - MARGIN - barcodeH;

        cs.drawImage(barcodeXObj, MARGIN, barcodeY, barcodeW, barcodeH);

        // ── Text rows ────────────────────────────────────────────────────────
        float fontSize1 = 5.5f;
        float fontSize2 = 6.5f;

        // Row 1: abbreviated piece ID centred — always ASCII → Helvetica
        String shortId = "PC-" + pieceId.substring(pieceId.length() - 10);
        drawCenteredText(cs, latinFont, fontSize1, shortId, wPt, barcodeY - 1.5f * MM_TO_PT);

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

    private BufferedImage renderBarcode(String content, float wPt, float hPt) {
        int pixelW = Math.round(wPt * DPI / 72f);
        int pixelH = Math.round(BARCODE_H_MM * MM_TO_PT * DPI / 72f);
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 0);
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
