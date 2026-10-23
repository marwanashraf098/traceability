package com.traceability.inventory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;

/**
 * Draws piece labels into ONE PDF document — one page per piece, every resource (barcode image,
 * embedded font subsets) owned by that document, so nothing refers to a closed source document.
 * Geometry comes from PieceLabelLayout; fonts from LabelFonts.
 */
public final class LabelPdfRenderer {

    static final int DPI = 203;

    private LabelPdfRenderer() {}

    /** One page of a label PDF: a piece label, or (packing labels, flexible labeling slice 4) a separator. */
    public sealed interface Page permits Piece, Separator {}

    /** What one label shows. */
    public record Piece(String shortCode, String productTitle, String variantTitle, String sku) implements Page {}

    /**
     * The page between two orders' packing labels: plain text, NO barcode (so it can never be scanned as a
     * piece). {@code line2} null → one line only.
     */
    public record Separator(String line1, String line2) implements Page {}

    static final float SEPARATOR_MARGIN_MM = 2f, SEPARATOR_LINE1_PT = 14f, SEPARATOR_LINE2_PT = 11f, SEPARATOR_MIN_PT = 6f;

    public static byte[] render(List<Piece> pieces, PieceLabelLayout.Spec spec) throws IOException {
        return renderPages(List.copyOf(pieces), spec);
    }

    public static byte[] renderPages(List<? extends Page> pages, PieceLabelLayout.Spec spec) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            LabelFonts.Loaded fonts = new LabelFonts.Loaded(doc);
            for (Page item : pages) {
                if (item instanceof Separator sep) {
                    drawSeparator(doc, fonts, sep, spec);
                    continue;
                }
                Piece p = (Piece) item;
                PieceLabelLayout.Layout layout = PieceLabelLayout.layout(
                    spec, p.shortCode(), p.productTitle(), p.variantTitle(), p.sku());
                PDPage page = new PDPage(new PDRectangle(layout.pageWidth(), layout.pageHeight()));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    drawBarcode(cs, doc, p.shortCode(), layout.barcode());
                    for (PieceLabelLayout.TextLine line : layout.lines()) {
                        drawLine(cs, fonts, line);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Same page size as a piece label; each line centred, shrunk to fit the width. */
    private static void drawSeparator(PDDocument doc, LabelFonts.Loaded fonts, Separator sep, PieceLabelLayout.Spec spec)
            throws IOException {
        float w = spec.widthMm() * PieceLabelLayout.MM, h = spec.heightMm() * PieceLabelLayout.MM;
        PDPage page = new PDPage(new PDRectangle(w, h));
        doc.addPage(page);
        float maxW = w - 2 * SEPARATOR_MARGIN_MM * PieceLabelLayout.MM;
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            if (sep.line2() == null) {
                drawCentred(cs, fonts, sep.line1(), LabelFonts.Face.BOLD, SEPARATOR_LINE1_PT, maxW, w, h * 0.42f);
            } else {
                drawCentred(cs, fonts, sep.line1(), LabelFonts.Face.BOLD, SEPARATOR_LINE1_PT, maxW, w, h * 0.58f);
                drawCentred(cs, fonts, sep.line2(), LabelFonts.Face.REGULAR, SEPARATOR_LINE2_PT, maxW, w, h * 0.22f);
            }
        }
    }

    private static void drawCentred(PDPageContentStream cs, LabelFonts.Loaded fonts, String text, LabelFonts.Face face,
                                    float size, float maxW, float pageW, float baseline) throws IOException {
        List<LabelFonts.Run> runs = LabelFonts.runs(text == null ? "" : text, face);
        while (size > SEPARATOR_MIN_PT && LabelFonts.width(runs, size) > maxW) size -= 0.5f;
        float x = (pageW - LabelFonts.width(runs, size)) / 2;
        for (LabelFonts.Run run : runs) {
            cs.beginText();
            cs.setFont(fonts.font(run.face()), size);
            cs.newLineAtOffset(x, baseline);
            cs.showText(run.text());
            cs.endText();
            x += LabelFonts.width(List.of(run), size);
        }
    }

    private static void drawLine(PDPageContentStream cs, LabelFonts.Loaded fonts, PieceLabelLayout.TextLine line)
            throws IOException {
        float x = line.x();
        for (LabelFonts.Run run : line.runs()) {
            cs.beginText();
            cs.setFont(fonts.font(run.face()), line.size());
            cs.newLineAtOffset(x, line.baseline());
            cs.showText(run.text());
            cs.endText();
            x += LabelFonts.width(List.of(run), line.size());
        }
    }

    // ── Barcode (unchanged: Code 128 of the short code, 1:1 bitmap at 203 DPI, 10-module quiet zone) ──

    private static void drawBarcode(PDPageContentStream cs, PDDocument doc, String shortCode,
                                    PieceLabelLayout.Box box) throws IOException {
        // Bitmap at the exact draw-area size so PDFBox draws it 1:1 (no scaling). "P000001"
        // (7 chars) = 132 modules incl. quiet zones → 0.333 mm/module on 44 mm, well above the
        // 0.191 mm GS1 general-use minimum.
        BufferedImage img = renderBarcode(shortCode, box.w(), box.h());
        PDImageXObject xObj = PDImageXObject.createFromByteArray(doc, toPngBytes(img), "barcode");
        cs.drawImage(xObj, box.x(), box.y(), box.w(), box.h());
    }

    private static BufferedImage renderBarcode(String content, float drawWidthPt, float drawHeightPt) {
        int pixelW = Math.round(drawWidthPt  * DPI / 72f);
        int pixelH = Math.round(drawHeightPt * DPI / 72f);
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // 10 quiet-zone modules each side — ISO/IEC 15417 minimum; without this scanners
        // cannot locate the start/stop guard bars and the symbol is undecodable.
        hints.put(EncodeHintType.MARGIN, 10);
        BitMatrix matrix = new Code128Writer().encode(content, BarcodeFormat.CODE_128, pixelW, pixelH, hints);
        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    private static byte[] toPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }
}
