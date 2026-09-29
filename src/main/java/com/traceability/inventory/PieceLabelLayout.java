package com.traceability.inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything goes on one piece label — pure geometry, no PDF. Supports any size; today
 * every label is 50×25 mm (the tenant's 40×25 setting isn't wired yet), and 40×25 is covered by
 * the same rules and tests.
 *
 * Common to all sizes: top/bottom margin 1.5 mm, text side margins 2 mm, 1 pt gap under the
 * barcode. Line height: Latin-only lines 1.1 × size (baseline 0.85 × size below the line top);
 * lines containing Arabic use the Arabic letters' ink envelope from the font (LabelFonts
 * .ARABIC_LETTER_INK: +1.010 / −0.421 em → 1.431 × size), or the line's own glyph ink when that
 * reaches further (harakat), so consecutive Arabic lines' dots and hamza never collide. The barcode keeps x = 3 mm and width = W − 6 mm (module width
 * and quiet zones unchanged); its height is 10 mm.
 *
 * Rows, top to bottom (all NotoSans, Arabic glyphs NotoSansArabic):
 *   piece code   Bold 7.5 pt fixed, centred
 *   product      Regular 6 pt → floor 5 pt, max 2 lines
 *   variant      Regular 5.5 → 5 pt, 1 line  (omitted when blank or "Default Title")
 *   SKU          Regular 5 pt, 1 line        (omitted when blank)
 * Every line is fitted to the text width (LabelTextFitter: wrap, shrink, "…") and centred;
 * each line keeps its own bidi order.
 *
 * When the rows don't fit vertically, in this order until they do:
 *   1. product → 5 pt   2. variant → 5 pt   3. product → 1 line with "…"
 *   4. drop the SKU row   5. drop the variant row (last resort — the variant outranks the
 *      second product line and the SKU).
 * The piece code and one product line are always kept, and no two boxes ever overlap. The steps
 * that fired are listed in {@link Layout#steps()}.
 */
public final class PieceLabelLayout {

    public static final float MM = 72f / 25.4f;
    static final float MARGIN_TB     = 1.5f * MM;
    static final float TEXT_SIDE     = 2f * MM;
    static final float BARCODE_X     = 3f * MM;
    static final float BARCODE_GAP   = 1f;
    /** Latin-only lines: box 1.1 × size, baseline 0.85 × size below its top. */
    static final float LINE_HEIGHT   = 1.1f;
    static final float ASCENT        = 0.85f;
    public static final float DEFAULT_BARCODE_HEIGHT_MM = 10f;

    static final float CODE_SIZE = 7.5f;
    static final float TITLE_START = 6f, TITLE_FLOOR = 5f;
    static final float VARIANT_START = 5.5f, VARIANT_FLOOR = 5f;
    static final float SKU_SIZE = 5f;

    private PieceLabelLayout() {}

    public record Spec(float widthMm, float heightMm, float barcodeHeightMm) {
        public static Spec of(float widthMm, float heightMm) {
            return new Spec(widthMm, heightMm, DEFAULT_BARCODE_HEIGHT_MM);
        }
    }

    /** Axis-aligned box in PDF points (origin bottom-left). */
    public record Box(float x, float y, float w, float h) {
        /** Overlap of more than 0.001 pt (boxes that only touch don't intersect). */
        public boolean intersects(Box o) {
            float e = 0.001f;
            return x + e < o.x + o.w && o.x + e < x + w && y + e < o.y + o.h && o.y + e < y + h;
        }
        public boolean inside(float pageW, float pageH) {
            float e = 0.01f;
            return x >= -e && y >= -e && x + w <= pageW + e && y + h <= pageH + e;
        }
    }

    public enum Row { CODE, TITLE, VARIANT, SKU }

    /** The vertical-fit steps, in the order they are tried. */
    public enum Step { TITLE_TO_FLOOR, VARIANT_TO_FLOOR, TITLE_ONE_LINE, DROP_SKU, DROP_VARIANT }

    /** One positioned line: draw {@code runs} at (x, baseline) in {@code size}; ascent/height in em. */
    public record TextLine(Row row, List<LabelFonts.Run> runs, String logical, float size, float x, float baseline,
                           float width, float ascent, float height) {
        public Box box() {
            float top = baseline + ascent * size;
            return new Box(x, top - height * size, width, height * size);
        }
    }

    public record Layout(float pageWidth, float pageHeight, Box barcode, List<TextLine> lines, List<Row> dropped,
                         List<Step> steps, boolean ellipsized) {}

    /** {ascent, height} in em for one line: 0.85 / 1.1 for Latin-only, the Arabic ink envelope otherwise. */
    static float[] lineMetrics(List<LabelFonts.Run> runs) {
        boolean arabic = runs.stream().anyMatch(r -> r.face() == LabelFonts.Face.ARABIC);
        if (!arabic) return new float[]{ASCENT, LINE_HEIGHT};
        float[] ink = LabelFonts.ink(runs);
        float ascent = Math.max(LabelFonts.ARABIC_LETTER_INK[0], ink[0]);
        float descent = Math.max(-LabelFonts.ARABIC_LETTER_INK[1], -ink[1]);
        return new float[]{ascent, ascent + descent};
    }

    public static Layout layout(Spec spec, String shortCode, String productTitle, String variantTitle, String sku) {
        float pageW = spec.widthMm() * MM, pageH = spec.heightMm() * MM;
        float barcodeH = spec.barcodeHeightMm() * MM;
        Box barcode = new Box(BARCODE_X, pageH - MARGIN_TB - barcodeH, pageW - 2 * BARCODE_X, barcodeH);
        float textW = pageW - 2 * TEXT_SIDE;
        float budget = barcode.y() - BARCODE_GAP - MARGIN_TB;

        String title = clean(productTitle);
        String variant = clean(variantTitle);
        if ("Default Title".equalsIgnoreCase(variant)) variant = "";
        if (title.isEmpty()) { title = variant; variant = ""; }
        String skuText = clean(sku);

        LabelTextFitter.Fit code = LabelTextFitter.fit(clean(shortCode), LabelFonts.Face.BOLD, CODE_SIZE, CODE_SIZE, 1, textW);
        LabelTextFitter.Fit t = title.isEmpty() ? null
            : LabelTextFitter.fit(title, LabelFonts.Face.REGULAR, TITLE_START, TITLE_FLOOR, 2, textW);
        LabelTextFitter.Fit v = variant.isEmpty() ? null
            : LabelTextFitter.fit(variant, LabelFonts.Face.REGULAR, VARIANT_START, VARIANT_FLOOR, 1, textW);
        LabelTextFitter.Fit s = skuText.isEmpty() ? null
            : LabelTextFitter.fit(skuText, LabelFonts.Face.REGULAR, SKU_SIZE, SKU_SIZE, 1, textW);
        List<Row> dropped = new ArrayList<>();
        List<Step> steps = new ArrayList<>();

        if (height(code, t, v, s) > budget && t != null) {
            t = LabelTextFitter.fit(title, LabelFonts.Face.REGULAR, TITLE_FLOOR, TITLE_FLOOR, 2, textW);
            steps.add(Step.TITLE_TO_FLOOR);
        }
        if (height(code, t, v, s) > budget && v != null) {
            v = LabelTextFitter.fit(variant, LabelFonts.Face.REGULAR, VARIANT_FLOOR, VARIANT_FLOOR, 1, textW);
            steps.add(Step.VARIANT_TO_FLOOR);
        }
        if (height(code, t, v, s) > budget && t != null) {
            t = LabelTextFitter.fit(title, LabelFonts.Face.REGULAR, TITLE_FLOOR, TITLE_FLOOR, 1, textW);
            steps.add(Step.TITLE_ONE_LINE);
        }
        if (height(code, t, v, s) > budget && s != null) { s = null; dropped.add(Row.SKU); steps.add(Step.DROP_SKU); }
        if (height(code, t, v, s) > budget && v != null) { v = null; dropped.add(Row.VARIANT); steps.add(Step.DROP_VARIANT); }

        List<TextLine> lines = new ArrayList<>();
        float top = barcode.y() - BARCODE_GAP;
        top = place(lines, Row.CODE, code, top, pageW);
        if (t != null) top = place(lines, Row.TITLE, t, top, pageW);
        if (v != null) top = place(lines, Row.VARIANT, v, top, pageW);
        if (s != null) place(lines, Row.SKU, s, top, pageW);

        boolean ellipsized = (t != null && t.ellipsized()) || (v != null && v.ellipsized()) || (s != null && s.ellipsized());
        return new Layout(pageW, pageH, barcode, lines, dropped, steps, ellipsized);
    }

    /** Every line centred on the page (= within the text width); stacked top-down. */
    private static float place(List<TextLine> out, Row row, LabelTextFitter.Fit fit, float top, float pageW) {
        for (LabelTextFitter.Line l : fit.lines()) {
            float[] m = lineMetrics(l.runs());
            float x = (pageW - l.width()) / 2f;
            out.add(new TextLine(row, l.runs(), l.logical(), fit.size(), x, top - m[0] * fit.size(), l.width(), m[0], m[1]));
            top -= m[1] * fit.size();
        }
        return top;
    }

    private static float height(LabelTextFitter.Fit... fits) {
        float h = 0f;
        for (LabelTextFitter.Fit f : fits) {
            if (f == null) continue;
            for (LabelTextFitter.Line l : f.lines()) h += lineMetrics(l.runs())[1] * f.size();
        }
        return h;
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }
}
