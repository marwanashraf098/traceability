package com.traceability.labels;

import com.traceability.inventory.LabelFonts;
import com.traceability.inventory.LabelTextFitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Flexible labeling slice 6c — a factory run's SHORTFALL REPORT: run, factory, reference, then per line product ·
 * variant · SKU / expected / received / gap / reason / overage. Two shapes: CSV (UTF-8 with BOM so spreadsheet apps
 * read Arabic) and an A4 PDF (PDFBox, the piece labels' embedded Noto Sans + Noto Sans Arabic — Arabic shaped and
 * bidi-ordered by LabelTextFitter). Headings and reasons in English or Arabic ({@code lang}).
 */
public final class FactoryRunReport {

    public record Row(String productTitle, String variantTitle, String sku, int expected, int received, int gap,
                      String reason, int overage) {}
    public record Report(String factoryName, String reference, String status, boolean stillComing, String createdAt,
                         String closedAt, List<Row> rows) {}

    private FactoryRunReport() {}

    private record Words(String title, String run, String factory, String reference, String status, String product,
                         String variant, String sku, String expected, String received, String gap, String reason,
                         String overage, String total, String open, String closed, String stillComing,
                         String shortShipped, String lost, String stillComingReason) {}

    private static final Words EN = new Words("Factory run shortfall report", "Run", "Factory", "Reference", "Status",
        "Product", "Variant", "SKU", "Expected", "Received", "Gap", "Reason", "Overage", "Total", "Open", "Closed",
        "Still coming", "Short-shipped", "Lost", "Still coming");
    private static final Words AR = new Words("تقرير النقص لدفعة المصنع", "الدفعة", "المصنع", "المرجع", "الحالة",
        "المنتج", "المتغير", "SKU", "المتوقع", "المستلم", "النقص", "السبب", "الزيادة", "الإجمالي", "مفتوحة", "مغلقة",
        "ما زالت قادمة", "شحن ناقص", "مفقود", "ما زال قادمًا");

    private static Words words(String lang) {
        return lang != null && lang.toLowerCase().startsWith("ar") ? AR : EN;
    }

    static String reasonLabel(String reason, Words w) {
        if (reason == null) return "";
        return switch (reason) {
            case LabelRunService.REASON_SHORT_SHIPPED -> w.shortShipped();
            case LabelRunService.REASON_LOST -> w.lost();
            case LabelRunService.REASON_STILL_COMING -> w.stillComingReason();
            default -> reason;
        };
    }

    private static String statusLabel(Report r, Words w) {
        if ("closed".equals(r.status())) return w.closed();
        return r.stillComing() ? w.stillComing() : w.open();
    }

    static String runName(Report r) {
        return FactoryReceivingService.runName(r.factoryName(), r.reference());
    }

    // ── CSV ───────────────────────────────────────────────────────────────────

    public static byte[] csv(Report r, String lang) {
        Words w = words(lang);
        StringBuilder out = new StringBuilder("﻿");
        row(out, w.run(), w.factory(), w.reference(), w.product(), w.variant(), w.sku(), w.expected(), w.received(),
            w.gap(), w.reason(), w.overage());
        for (Row x : r.rows()) {
            row(out, runName(r), r.factoryName(), r.reference() == null ? "" : r.reference(), x.productTitle(),
                x.variantTitle(), x.sku() == null ? "" : x.sku(), String.valueOf(x.expected()), String.valueOf(x.received()),
                String.valueOf(x.gap()), reasonLabel(x.reason(), w), String.valueOf(x.overage()));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void row(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    /** RFC 4180 quoting; a leading = + - @ is neutralised so a spreadsheet never evaluates a product name. */
    static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+")) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ── PDF (A4) ──────────────────────────────────────────────────────────────

    private static final float MARGIN = 40f;
    private static final float ROW_H = 18f;
    private static final float SIZE = 9f;
    /** Column x offsets (from the left margin) and widths, A4 portrait (515 pt of content width). */
    private static final float[] COL_X = {0f, 190f, 262f, 310f, 360f, 398f, 470f};
    private static final float[] COL_W = {185f, 68f, 46f, 46f, 34f, 68f, 45f};

    public static byte[] pdf(Report r, String lang) {
        Words w = words(lang);
        try (PDDocument doc = new PDDocument()) {
            LabelFonts.Loaded fonts = new LabelFonts.Loaded(doc);
            PDRectangle a4 = PDRectangle.A4;
            PDPage page = new PDPage(a4);
            doc.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(doc, page);
            float y = a4.getHeight() - MARGIN;

            text(cs, fonts, w.title(), MARGIN, y, 15f, LabelFonts.Face.BOLD, 515f);
            y -= 26f;
            y = field(cs, fonts, w.factory(), r.factoryName(), y);
            y = field(cs, fonts, w.reference(), r.reference() == null ? "—" : r.reference(), y);
            y = field(cs, fonts, w.status(), statusLabel(r, w), y);
            y -= 8f;
            y = header(cs, fonts, w, y);

            int expected = 0, received = 0, gap = 0, overage = 0;
            for (Row x : r.rows()) {
                if (y < MARGIN + ROW_H) {
                    cs.close();
                    page = new PDPage(a4);
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    y = header(cs, fonts, w, a4.getHeight() - MARGIN);
                }
                String product = x.productTitle() + " · " + x.variantTitle();
                String[] cells = {product, x.sku() == null ? "—" : x.sku(), String.valueOf(x.expected()),
                    String.valueOf(x.received()), String.valueOf(x.gap()), reasonLabel(x.reason(), w),
                    String.valueOf(x.overage())};
                for (int c = 0; c < cells.length; c++) {
                    text(cs, fonts, cells[c], MARGIN + COL_X[c], y, SIZE, LabelFonts.Face.REGULAR, COL_W[c]);
                }
                y -= ROW_H;
                expected += x.expected(); received += x.received(); gap += x.gap(); overage += x.overage();
            }
            if (y < MARGIN + ROW_H) {
                cs.close();
                page = new PDPage(a4);
                doc.addPage(page);
                cs = new PDPageContentStream(doc, page);
                y = a4.getHeight() - MARGIN;
            }
            rule(cs, y + ROW_H - 4f);
            String[] totals = {w.total(), "", String.valueOf(expected), String.valueOf(received), String.valueOf(gap), "",
                String.valueOf(overage)};
            for (int c = 0; c < totals.length; c++) {
                text(cs, fonts, totals[c], MARGIN + COL_X[c], y, SIZE, LabelFonts.Face.BOLD, COL_W[c]);
            }
            cs.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Factory run report render failed", e);
        }
    }

    private static float field(PDPageContentStream cs, LabelFonts.Loaded fonts, String label, String value, float y)
            throws IOException {
        text(cs, fonts, label + ":", MARGIN, y, 10f, LabelFonts.Face.BOLD, 90f);
        text(cs, fonts, value, MARGIN + 95f, y, 10f, LabelFonts.Face.REGULAR, 420f);
        return y - 16f;
    }

    private static float header(PDPageContentStream cs, LabelFonts.Loaded fonts, Words w, float y) throws IOException {
        String[] head = {w.product() + " · " + w.variant(), w.sku(), w.expected(), w.received(), w.gap(), w.reason(), w.overage()};
        for (int c = 0; c < head.length; c++) {
            text(cs, fonts, head[c], MARGIN + COL_X[c], y, SIZE, LabelFonts.Face.BOLD, COL_W[c]);
        }
        rule(cs, y - 5f);
        return y - ROW_H;
    }

    private static void rule(PDPageContentStream cs, float y) throws IOException {
        cs.setLineWidth(0.5f);
        cs.moveTo(MARGIN, y);
        cs.lineTo(PDRectangle.A4.getWidth() - MARGIN, y);
        cs.stroke();
    }

    /** One line of text: Arabic shaped + bidi-ordered, in single-font runs; cut with "…" to fit {@code maxWidth}. */
    private static void text(PDPageContentStream cs, LabelFonts.Loaded fonts, String logical, float x, float y,
                             float size, LabelFonts.Face latin, float maxWidth) throws IOException {
        String s = logical == null ? "" : logical;
        List<LabelFonts.Run> runs = LabelFonts.runs(LabelTextFitter.display(s), latin);
        while (LabelFonts.width(runs, size) > maxWidth && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
            runs = LabelFonts.runs(LabelTextFitter.display(s.strip() + "…"), latin);
        }
        for (LabelFonts.Run run : runs) {
            cs.beginText();
            cs.setFont(fonts.font(run.face()), size);
            cs.newLineAtOffset(x, y);
            cs.showText(run.text());
            cs.endText();
            x += LabelFonts.width(List.of(run), size);
        }
    }
}
