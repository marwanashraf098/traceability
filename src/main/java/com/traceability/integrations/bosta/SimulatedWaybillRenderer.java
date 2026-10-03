package com.traceability.integrations.bosta;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.traceability.inventory.LabelFonts;
import com.traceability.inventory.LabelTextFitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;

/**
 * Review mode (S2) — a simulated waybill: what a simulated-courier tenant (V130) prints instead of
 * calling Bosta's mass-awb. One page per tracking number, A4 or A6.
 *
 * Built so every scan path Traced has works on it exactly as on a real Bosta waybill:
 *   - TOP barcode, Code 128, in the exact shape of Bosta's real top barcode — the production
 *     sample "G - 0 2 - 8 4 8 4 8 0 5 6 9 9" (Jumi, 2026-10-01; fixture in AwbSpacedBarcodeTest
 *     .spaced() and TrackingNumberSpacesTest, hotfix ee35abe): {@code "G - 0 2 - "} + the digits
 *     separated by single spaces. TrackingNumberNormalizer strips the spaces and the hub prefix.
 *   - BOTTOM barcode, Code 128 of the plain digits (Bosta's "Tracking Number" barcode).
 *   - QR {@code "BOSTA_<digits>"} (Bosta's waybill QR — TrackingNumberNormalizer accepts it).
 *   - The tracking number as extractable text, so WaybillPdfAssembler can match each page to its
 *     shipment (batch print's order guarantee).
 * Plus the order number, customer, phone, address and COD, and a "SIMULATED — not a Bosta
 * shipment" banner in English and Arabic. Arabic text is shaped through LabelTextFitter /
 * LabelFonts (Noto Sans + Noto Sans Arabic, embedded), the same as piece labels.
 */
public final class SimulatedWaybillRenderer {

    private SimulatedWaybillRenderer() {}

    /** One waybill. Any field but trackingNumber may be null. */
    public record Waybill(String trackingNumber, String orderNumber, String customerName,
                          String customerPhone, String addressLine, BigDecimal cod) {}

    /** The hub prefix of Bosta's real top barcode sample (see the class comment). */
    static final String TOP_PREFIX = "G - 0 2 - ";

    /** The top barcode's content for a tracking number — the real Bosta shape. */
    public static String topBarcode(String trackingNumber) {
        return TOP_PREFIX + String.join(" ", trackingNumber.split(""));
    }

    public static String qrContent(String trackingNumber) {
        return "BOSTA_" + trackingNumber;
    }

    private static final int DPI = 203;   // same raster resolution as piece labels

    public static byte[] render(List<Waybill> waybills, String paper) {
        PDRectangle size = "A6".equalsIgnoreCase(paper) ? PDRectangle.A6 : PDRectangle.A4;
        float s = size.getWidth() / PDRectangle.A4.getWidth();   // scale A4 coordinates to A6
        try (PDDocument doc = new PDDocument()) {
            LabelFonts.Loaded fonts = new LabelFonts.Loaded(doc);
            for (Waybill w : waybills) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    drawPage(cs, doc, fonts, w, size, s);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Simulated waybill render failed", e);
        }
    }

    private static void drawPage(PDPageContentStream cs, PDDocument doc, LabelFonts.Loaded fonts,
                                 Waybill w, PDRectangle size, float s) throws IOException {
        float W = size.getWidth(), H = size.getHeight();
        float m = 36 * s;                  // margin
        float y = H - m;

        // Banner — framed, top of the page.
        float bannerH = 46 * s;
        cs.setLineWidth(1.5f * s);
        cs.addRect(m, y - bannerH, W - 2 * m, bannerH);
        cs.stroke();
        text(cs, fonts, "SIMULATED — not a Bosta shipment", m + 10 * s, y - 20 * s, 14 * s, LabelFonts.Face.BOLD);
        text(cs, fonts, "شحنة تجريبية — ليست شحنة بوسطة", m + 10 * s, y - 38 * s, 11 * s, LabelFonts.Face.REGULAR);
        y -= bannerH + 18 * s;

        // TOP barcode (real Bosta shape) + QR to its right.
        float qr = 96 * s;
        float barW = W - 2 * m - qr - 16 * s;
        float barH = 64 * s;
        drawImage(cs, doc, code128(topBarcode(w.trackingNumber()), barW, barH), m, y - barH, barW, barH);
        drawImage(cs, doc, qr(qrContent(w.trackingNumber()), qr), W - m - qr, y - qr, qr, qr);
        y -= Math.max(barH, qr) + 22 * s;

        // Tracking number, big — the extractable text the page assembler matches on.
        text(cs, fonts, "Tracking number: " + w.trackingNumber(), m, y, 18 * s, LabelFonts.Face.BOLD);
        y -= 30 * s;

        y = field(cs, fonts, "Order", w.orderNumber(), m, y, s);
        y = field(cs, fonts, "Customer", w.customerName(), m, y, s);
        y = field(cs, fonts, "Phone", w.customerPhone(), m, y, s);
        y = field(cs, fonts, "Address", w.addressLine(), m, y, s);
        y = field(cs, fonts, "COD (EGP)", w.cod() == null ? "0.00" : w.cod().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(), m, y, s);

        // BOTTOM barcode — plain digits.
        float bottomH = 56 * s;
        float bottomY = m + 16 * s;
        drawImage(cs, doc, code128(w.trackingNumber(), W - 2 * m, bottomH), m, bottomY, W - 2 * m, bottomH);
        text(cs, fonts, w.trackingNumber(), m, bottomY - 12 * s, 10 * s, LabelFonts.Face.REGULAR);
    }

    private static float field(PDPageContentStream cs, LabelFonts.Loaded fonts, String label, String value,
                               float x, float y, float s) throws IOException {
        text(cs, fonts, label + ":", x, y, 11 * s, LabelFonts.Face.BOLD);
        text(cs, fonts, value == null || value.isBlank() ? "—" : value, x + 90 * s, y, 11 * s, LabelFonts.Face.REGULAR);
        return y - 20 * s;
    }

    /** Draws one line: Arabic shaped + bidi-ordered, split into single-font runs (as piece labels). */
    private static void text(PDPageContentStream cs, LabelFonts.Loaded fonts, String logical,
                             float x, float y, float size, LabelFonts.Face latin) throws IOException {
        String visual = LabelTextFitter.display(logical);
        for (LabelFonts.Run run : LabelFonts.runs(visual, latin)) {
            cs.beginText();
            cs.setFont(fonts.font(run.face()), size);
            cs.newLineAtOffset(x, y);
            cs.showText(run.text());
            cs.endText();
            x += LabelFonts.width(List.of(run), size);
        }
    }

    private static void drawImage(PDPageContentStream cs, PDDocument doc, BufferedImage img,
                                  float x, float y, float w, float h) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", png);
        PDImageXObject xo = PDImageXObject.createFromByteArray(doc, png.toByteArray(), "img");
        cs.drawImage(xo, x, y, w, h);
    }

    private static BufferedImage code128(String content, float wPt, float hPt) {
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 10);   // ISO/IEC 15417 quiet zone, as piece labels
        BitMatrix m = new Code128Writer().encode(content, BarcodeFormat.CODE_128,
            Math.round(wPt * DPI / 72f), Math.round(hPt * DPI / 72f), hints);
        return MatrixToImageWriter.toBufferedImage(m);
    }

    private static BufferedImage qr(String content, float sidePt) {
        int px = Math.round(sidePt * DPI / 72f);
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 2);
        try {
            return MatrixToImageWriter.toBufferedImage(
                new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints));
        } catch (WriterException e) {
            throw new IllegalStateException("QR encode failed for " + content, e);
        }
    }
}
