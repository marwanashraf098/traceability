package com.traceability.inventory;

import com.google.zxing.oned.Code128Writer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

/**
 * Place sticker sizes — the large place stickers / signs (A6 105×148 mm, A4 210×297 mm, and A6 four to an A4 sheet),
 * drawn into the label PDF by {@link LabelPdfRenderer}. The 50×25 mm sticker is not drawn here (PieceLabelLayout).
 *
 * One layout for every size, scaled by the page width (A6 = 1, A4 = 2), top to bottom: the red DAMAGED band (Damaged
 * places only), the kind (Shelf / Box / Rack / Zone), the code as large as the space allows, the path line
 * (Z1 › R01 › S2 / on Z1-R01-S2 / Z1 floor), the location, then the Code 128 of the Q scan code and the Q code
 * under it. Every text line goes through LabelTextFitter (Arabic shaping + bidi, ICU4J) and is centred.
 *
 * The barcode is drawn as vector bars, so it is sharp at any printer resolution. Bar width X is a whole number of
 * dots of a 203-dpi printer — A6 4 dots (0.50 mm), A4 8 dots (1.00 mm) — narrowed only if a long scan code would
 * not fit, never below {@link #MIN_X_MM}; the quiet zone is 10 X each side (ISO/IEC 15417).
 */
final class PlaceSigns {

    static final float MM = PieceLabelLayout.MM;
    static final float A6_W_MM = 105f, A6_H_MM = 148f, A4_W_MM = 210f, A4_H_MM = 297f;
    /** One cell of the 2×2 sheet: a quarter of A4. */
    static final float CELL_W_MM = A4_W_MM / 2, CELL_H_MM = A4_H_MM / 2;
    static final float A6_X_MM = 4 * 25.4f / 203f, A4_X_MM = 8 * 25.4f / 203f, MIN_X_MM = 0.25f;
    static final int QUIET_MODULES = 10;
    static final float MARGIN_MM = 8f, BAND_MM = 14f, BARCODE_H_MM = 26f;
    static final float KIND_PT = 16f, PATH_PT = 20f, PATH_FLOOR_PT = 12f, LOCATION_PT = 12f, LOCATION_FLOOR_PT = 8f,
        QTEXT_PT = 14f, BAND_PT = 26f, CODE_MAX_PT = 140f;
    /** Cut marks on the sheet: 0.5 pt lines along the cut lines, 5–12 mm in from the paper edge, a ±5 mm centre cross. */
    static final float MARK_FROM_MM = 5f, MARK_TO_MM = 12f, CROSS_MM = 5f;

    static final Color RED = new Color(0xC6, 0x28, 0x28), GREY = new Color(0x55, 0x55, 0x55);

    private PlaceSigns() {}

    /** One sign on its own A6 / A4 page. */
    static void page(PDDocument doc, LabelFonts.Loaded fonts, LabelPdfRenderer.Sign sign) throws IOException {
        boolean a4 = sign.size() == LabelPdfRenderer.SignSize.A4;
        float w = (a4 ? A4_W_MM : A6_W_MM) * MM, h = (a4 ? A4_H_MM : A6_H_MM) * MM;
        PDPage page = new PDPage(new PDRectangle(w, h));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            draw(cs, fonts, sign, 0, 0, w, h, a4 ? A4_X_MM : A6_X_MM);
        }
    }

    /** Up to four A6 signs on one A4 page, 2×2 in reading order (top left, top right, bottom left, bottom right). */
    static void sheet(PDDocument doc, LabelFonts.Loaded fonts, List<LabelPdfRenderer.Sign> signs) throws IOException {
        float w = A4_W_MM * MM, h = A4_H_MM * MM, cw = CELL_W_MM * MM, ch = CELL_H_MM * MM;
        PDPage page = new PDPage(new PDRectangle(w, h));
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            for (int i = 0; i < signs.size() && i < 4; i++) {
                float ox = (i % 2) * cw, oy = h - (i / 2 + 1) * ch;
                draw(cs, fonts, signs.get(i), ox, oy, cw, ch, A6_X_MM);
            }
            cutMarks(cs, w, h);
        }
    }

    /** Where the cell at {@code index} (0–3) sits on the sheet: {x, y, w, h} in points. */
    static float[] cell(int index) {
        float cw = CELL_W_MM * MM, ch = CELL_H_MM * MM;
        return new float[]{(index % 2) * cw, A4_H_MM * MM - (index / 2 + 1) * ch, cw, ch};
    }

    private static void cutMarks(PDPageContentStream cs, float w, float h) throws IOException {
        float cx = w / 2, cy = h / 2, from = MARK_FROM_MM * MM, to = MARK_TO_MM * MM, cross = CROSS_MM * MM;
        cs.setStrokingColor(Color.BLACK);
        cs.setLineWidth(0.5f);
        line(cs, cx, from, cx, to);
        line(cs, cx, h - from, cx, h - to);
        line(cs, from, cy, to, cy);
        line(cs, w - from, cy, w - to, cy);
        line(cs, cx - cross, cy, cx + cross, cy);
        line(cs, cx, cy - cross, cx, cy + cross);
    }

    private static void line(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws IOException {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    /** The sign inside the box (ox, oy, w, h); {@code xMm} = the preferred bar width. */
    static void draw(PDPageContentStream cs, LabelFonts.Loaded fonts, LabelPdfRenderer.Sign s, float ox, float oy,
                     float w, float h, float xMm) throws IOException {
        float k = w / (A6_W_MM * MM);
        float m = MARGIN_MM * MM * k, innerW = w - 2 * m, cx = ox + w / 2;
        float top = oy + h - m, bottom = oy + m;

        if (s.damagedLabel() != null) {
            float bandH = BAND_MM * MM * k;
            cs.setNonStrokingColor(RED);
            cs.addRect(ox + m, top - bandH, innerW, bandH);
            cs.fill();
            LabelTextFitter.Fit band = LabelTextFitter.fit(s.damagedLabel(), LabelFonts.Face.BOLD, BAND_PT * k,
                BAND_PT * k * 0.5f, 1, innerW - 4 * MM * k);
            float bandTop = top - (bandH - fitHeight(band)) / 2;
            drawFit(cs, fonts, band, bandTop, cx, Color.WHITE);
            top -= bandH + 4 * MM * k;
        }
        if (notBlank(s.kind())) {
            LabelTextFitter.Fit kind = LabelTextFitter.fit(s.kind(), LabelFonts.Face.BOLD, KIND_PT * k, KIND_PT * k * 0.6f,
                1, innerW);
            top = drawFit(cs, fonts, kind, top, cx, GREY) - 2 * MM * k;
        }

        // Bottom up: the Q code text, the barcode, then the location and the path above it.
        LabelTextFitter.Fit qText = LabelTextFitter.fit(s.scanCode(), LabelFonts.Face.REGULAR, QTEXT_PT * k, QTEXT_PT * k,
            1, innerW, LabelTextFitter.Cut.GRAPHEME);
        float qTop = bottom + fitHeight(qText);
        drawFit(cs, fonts, qText, qTop, cx, Color.BLACK);
        float barH = BARCODE_H_MM * MM * k;
        float barY = qTop + 1.5f * MM * k;
        drawBars(cs, s.scanCode(), cx, barY, barH, innerW, xMm);
        float above = barY + barH + 5 * MM * k;
        if (notBlank(s.location())) {
            LabelTextFitter.Fit loc = LabelTextFitter.fit(s.location(), LabelFonts.Face.REGULAR, LOCATION_PT * k,
                LOCATION_FLOOR_PT * k, 1, innerW);
            above += fitHeight(loc);
            drawFit(cs, fonts, loc, above, cx, GREY);
            above += 1.5f * MM * k;
        }
        if (notBlank(s.path())) {
            LabelTextFitter.Fit path = LabelTextFitter.fit(s.path(), LabelFonts.Face.REGULAR, PATH_PT * k,
                PATH_FLOOR_PT * k, 2, innerW);
            above += fitHeight(path);
            drawFit(cs, fonts, path, above, cx, Color.BLACK);
        }

        // The code fills what is left between the kind and the path, as large as fits, vertically centred.
        float gap = 3 * MM * k, midTop = top, midBottom = above + gap;
        String code = s.code() == null ? "" : s.code().strip();
        if (!code.isEmpty() && midTop > midBottom) {
            float perPt = LabelTextFitter.measure(code, LabelFonts.Face.BOLD, 1f);
            float[] em = PieceLabelLayout.lineMetrics(LabelFonts.runs(LabelTextFitter.display(code), LabelFonts.Face.BOLD));
            float size = Math.min(CODE_MAX_PT * k, Math.min(innerW / Math.max(perPt, 0.001f), (midTop - midBottom) / em[1]));
            LabelTextFitter.Fit fit = LabelTextFitter.fit(code, LabelFonts.Face.BOLD, size, size, 1, innerW + 0.5f,
                LabelTextFitter.Cut.GRAPHEME);
            float lineTop = midTop - ((midTop - midBottom) - fitHeight(fit)) / 2;
            drawFit(cs, fonts, fit, lineTop, cx, Color.BLACK);
        }
    }

    /** The bar width actually used for {@code content} in {@code availW} points: the preferred X, narrowed to fit. */
    static float barWidthMm(String content, float availW, float preferredMm) {
        int modules = new Code128Writer().encode(content).length + 2 * QUIET_MODULES;
        float fitMm = availW / MM / modules;
        return Math.max(MIN_X_MM, Math.min(preferredMm, fitMm));
    }

    /** Code 128 of {@code content} as black bars centred on {@code cx}, quiet zone included (left white). */
    private static void drawBars(PDPageContentStream cs, String content, float cx, float y, float barH, float availW,
                                 float preferredMm) throws IOException {
        boolean[] modules = new Code128Writer().encode(content);
        float x = barWidthMm(content, availW, preferredMm) * MM;
        float total = (modules.length + 2 * QUIET_MODULES) * x;
        float start = cx - total / 2 + QUIET_MODULES * x;
        cs.setNonStrokingColor(Color.BLACK);
        int i = 0;
        while (i < modules.length) {
            if (!modules[i]) { i++; continue; }
            int j = i;
            while (j < modules.length && modules[j]) j++;
            cs.addRect(start + i * x, y, (j - i) * x, barH);
            i = j;
        }
        cs.fill();
    }

    /** Draws the fitted lines centred on {@code cx} from {@code top} down; returns the y under the last line. */
    private static float drawFit(PDPageContentStream cs, LabelFonts.Loaded fonts, LabelTextFitter.Fit fit, float top,
                                 float cx, Color color) throws IOException {
        cs.setNonStrokingColor(color);
        for (LabelTextFitter.Line l : fit.lines()) {
            float[] em = PieceLabelLayout.lineMetrics(l.runs());
            float baseline = top - em[0] * fit.size();
            float x = cx - l.width() / 2;
            for (LabelFonts.Run run : l.runs()) {
                cs.beginText();
                cs.setFont(fonts.font(run.face()), fit.size());
                cs.newLineAtOffset(x, baseline);
                cs.showText(run.text());
                cs.endText();
                x += LabelFonts.width(List.of(run), fit.size());
            }
            top -= em[1] * fit.size();
        }
        return top;
    }

    private static float fitHeight(LabelTextFitter.Fit fit) {
        float h = 0;
        for (LabelTextFitter.Line l : fit.lines()) h += PieceLabelLayout.lineMetrics(l.runs())[1] * fit.size();
        return h;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
