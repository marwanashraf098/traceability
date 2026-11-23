package com.traceability.inventory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * The jewelry rat-tail label (60 × 12 mm die-cut) — pure geometry, no PDF. A tenant whose label size is 60 × 12
 * ({@link #is}) gets this page for every piece label and label-group separator; any other size keeps
 * PieceLabelLayout and the bitmap barcode, untouched.
 *
 * <pre>
 *   x 0–20 mm   Flag A — Code 128 of the P-code, bars y 4.3–10.7 mm, the code under it (5.5 pt, baseline 1.75)
 *   x 20–40 mm  the tail — never printed
 *   x 40–60 mm  Flag B — name 6.5 pt bold (→ 5 → "…"), variant 6 pt (→ 4.75 → "…"), SKU 6 pt; no price
 * </pre>
 * All ink stays ≥ 1 mm inside each flag. Both flags print the same way up.
 *
 * <p><b>Bars</b> are vector rectangles from ZXing's module row (MARGIN 0; ZXing 3.5.3 already encodes P + digits
 * at the minimum length: Start B, P, one digit in set B when the digit count is odd, CODE C, digit pairs — 90 modules
 * for P + 6 digits, 101 for P + 7 / P + 8). One module = 2 dots at 300 dpi = 0.48 pt, and the start x is snapped to
 * the 0.24 pt dot grid, so a 300 dpi printer draws every bar edge on a dot boundary with nothing resampled. The
 * symbol is centred in Flag A by its actual module count.
 *
 * <p><b>Text</b> direction: when the name or variant contains Arabic, the RTL baselines are used (they leave room for
 * the Arabic letters' ink envelope — a Latin-first name with Arabic in it needs the same room), and each Flag B line
 * is aligned by its own direction: right-to-left lines end at 58.6 mm, left-to-right lines (the SKU always) start at
 * 41.4 mm. Shaping and bidi are LabelTextFitter's (ICU4J), fonts LabelFonts' (no new font).
 *
 * <p><b>Separator</b> (label runs and tag stock print one before each group): no barcode and no code — Flag A shows
 * only "× N" (the group's label count), Flag B the name and variant (no SKU).
 */
public final class RatTailLabel {

    public static final float MM = PieceLabelLayout.MM;
    public static final float WIDTH_MM = 60f, HEIGHT_MM = 12f;

    /** Flags and tail along x (mm). */
    public static final float FLAG_A_END_MM = 20f, TAIL_END_MM = 40f;
    /** Ink stays at least this far inside each flag's edges (mm). */
    public static final float SAFE_MM = 1f;

    /** 300 dpi: one dot = 0.24 pt; one module = 2 dots. */
    public static final float DOT_PT = 72f / 300f;
    public static final float MODULE_PT = 2 * DOT_PT;
    static final float BAR_BOTTOM_MM = 4.3f, BAR_TOP_MM = 10.7f;

    static final float CODE_SIZE = 5.5f, CODE_BASELINE_MM = 1.75f;
    static final float COUNT_SIZE = 9f, COUNT_BASELINE_MM = 4.8f;

    static final float TEXT_LEFT_MM = 41.4f, TEXT_RIGHT_MM = 58.6f;
    static final float TEXT_MAX_WIDTH_MM = TEXT_RIGHT_MM - TEXT_LEFT_MM;   // 17.2
    static final float NAME_START = 6.5f, NAME_FLOOR = 5f;
    static final float VARIANT_START = 6f, VARIANT_FLOOR = 4.75f;
    static final float SKU_SIZE = 6f;

    /** Piece label baselines (mm): name / variant / SKU, and name / SKU when the variant is hidden. */
    static final float[] PIECE_LTR = {8.45f, 5.4f, 2.3f}, PIECE_RTL = {8.6f, 5.2f, 2.2f}, PIECE_NO_VARIANT = {7.2f, 3.9f};
    /** Separator baselines (mm): name / variant. */
    static final float[] SEPARATOR_LTR = {7.2f, 3.9f}, SEPARATOR_RTL = {7.4f, 3.8f};

    private RatTailLabel() {}

    /** The tenant setting that selects this label: exactly 60 × 12 mm. */
    public static boolean is(PieceLabelLayout.Spec spec) {
        return spec != null && Math.abs(spec.widthMm() - WIDTH_MM) < 0.01f && Math.abs(spec.heightMm() - HEIGHT_MM) < 0.01f;
    }

    public enum Row { CODE, NAME, VARIANT, SKU, COUNT }

    /** One bar: x and width in points. */
    public record Bar(float x, float w) {}

    /** The symbol: {@code modules} wide from {@code x}, y {@code y}..{@code y + h} (points). */
    public record Bars(float x, float y, float h, int modules, List<Bar> bars) {
        public float width() { return modules * MODULE_PT; }
    }

    /** One positioned line: {@code runs} (visual order) at (x, baseline), {@code size} pt, {@code width} pt wide. */
    public record Line(Row row, List<LabelFonts.Run> runs, String logical, float size, float x, float baseline,
                       float width, boolean rtl) {
        /** Ink extent in points: {bottom, top} from the glyphs' bounding boxes. */
        public float[] inkY() {
            float[] ink = LabelFonts.ink(runs);
            return new float[]{baseline + ink[1] * size, baseline + ink[0] * size};
        }
    }

    /** {@code bars} null for a separator. */
    public record Layout(float pageWidth, float pageHeight, Bars bars, List<Line> lines, boolean ellipsized) {}

    // ── barcode ───────────────────────────────────────────────────────────────

    /** Code 128 module row of {@code code}, no quiet zone (true = bar). */
    public static boolean[] modules(String code) {
        EnumMap<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 0);
        BitMatrix m = new Code128Writer().encode(code, BarcodeFormat.CODE_128, 0, 1, hints);
        boolean[] row = new boolean[m.getWidth()];
        for (int x = 0; x < row.length; x++) row[x] = m.get(x, 0);
        return row;
    }

    /** The bars of {@code code}, centred in Flag A by module count, start snapped to the 300 dpi dot grid. */
    public static Bars bars(String code) {
        boolean[] mods = modules(code);
        float width = mods.length * MODULE_PT;
        float x0 = Math.round((FLAG_A_END_MM * MM - width) / 2f / DOT_PT) * DOT_PT;
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < mods.length; ) {
            if (!mods[i]) { i++; continue; }
            int j = i;
            while (j < mods.length && mods[j]) j++;
            bars.add(new Bar(x0 + i * MODULE_PT, (j - i) * MODULE_PT));
            i = j;
        }
        float y = BAR_BOTTOM_MM * MM;
        return new Bars(x0, y, BAR_TOP_MM * MM - y, mods.length, List.copyOf(bars));
    }

    // ── layouts ───────────────────────────────────────────────────────────────

    public static Layout piece(String shortCode, String productTitle, String variantTitle, String sku) {
        String code = clean(shortCode);
        Texts t = texts(productTitle, variantTitle);
        String skuText = clean(sku);
        List<Line> lines = new ArrayList<>();
        boolean ellipsized = false;

        LabelTextFitter.Fit c = LabelTextFitter.fit(code, LabelFonts.Face.REGULAR, CODE_SIZE, CODE_SIZE, 1, FLAG_A_END_MM * MM);
        lines.add(centredInFlagA(Row.CODE, c, CODE_BASELINE_MM));

        float[] base = t.variant.isEmpty() ? PIECE_NO_VARIANT : (t.arabic ? PIECE_RTL : PIECE_LTR);
        int i = 0;
        if (!t.name.isEmpty()) ellipsized |= flagB(lines, Row.NAME, name(t.name), base[i]);
        i++;
        if (!t.variant.isEmpty()) {
            ellipsized |= flagB(lines, Row.VARIANT, variant(t.variant), base[i]);
            i++;
        }
        if (!skuText.isEmpty()) {
            ellipsized |= flagB(lines, Row.SKU, LabelTextFitter.fit(skuText, LabelFonts.Face.REGULAR, SKU_SIZE, SKU_SIZE, 1,
                TEXT_MAX_WIDTH_MM * MM, LabelTextFitter.Cut.GRAPHEME), base[i]);
        }
        return new Layout(WIDTH_MM * MM, HEIGHT_MM * MM, bars(code), List.copyOf(lines), ellipsized);
    }

    /** The page before a group of {@code count} labels: "× N" on Flag A, name + variant on Flag B, no barcode. */
    public static Layout separator(int count, String productTitle, String variantTitle) {
        if (count < 1) throw new IllegalArgumentException("a separator heads at least one label");
        Texts t = texts(productTitle, variantTitle);
        List<Line> lines = new ArrayList<>();
        boolean ellipsized = false;
        LabelTextFitter.Fit n = LabelTextFitter.fit(countText(count), LabelFonts.Face.BOLD, COUNT_SIZE, COUNT_SIZE, 1,
            (FLAG_A_END_MM - 2 * SAFE_MM) * MM);
        lines.add(centredInFlagA(Row.COUNT, n, COUNT_BASELINE_MM));
        float[] base = t.arabic ? SEPARATOR_RTL : SEPARATOR_LTR;
        if (!t.name.isEmpty()) ellipsized |= flagB(lines, Row.NAME, name(t.name), base[0]);
        if (!t.variant.isEmpty()) ellipsized |= flagB(lines, Row.VARIANT, variant(t.variant), base[1]);
        return new Layout(WIDTH_MM * MM, HEIGHT_MM * MM, null, List.copyOf(lines), ellipsized);
    }

    /** "× 12" — U+00D7 MULTIPLICATION SIGN, a space, the count. */
    public static String countText(int count) {
        return "× " + count;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private record Texts(String name, String variant, boolean arabic) {}

    /** Same rules as PieceLabelLayout: "Default Title" hidden; a blank name takes the variant's place. */
    private static Texts texts(String productTitle, String variantTitle) {
        String name = clean(productTitle);
        String variant = clean(variantTitle);
        if ("Default Title".equalsIgnoreCase(variant)) variant = "";
        if (name.isEmpty()) { name = variant; variant = ""; }
        return new Texts(name, variant, LabelFonts.containsArabic(name) || LabelFonts.containsArabic(variant));
    }

    private static LabelTextFitter.Fit name(String name) {
        return LabelTextFitter.fit(name, LabelFonts.Face.BOLD, NAME_START, NAME_FLOOR, 1, TEXT_MAX_WIDTH_MM * MM);
    }

    private static LabelTextFitter.Fit variant(String variant) {
        return LabelTextFitter.fit(variant, LabelFonts.Face.REGULAR, VARIANT_START, VARIANT_FLOOR, 1, TEXT_MAX_WIDTH_MM * MM);
    }

    private static Line centredInFlagA(Row row, LabelTextFitter.Fit fit, float baselineMm) {
        LabelTextFitter.Line l = fit.lines().get(0);
        float x = (FLAG_A_END_MM * MM - l.width()) / 2f;
        return new Line(row, l.runs(), l.logical(), fit.size(), x, baselineMm * MM, l.width(), l.rtl());
    }

    /** One Flag B line: right-to-left ends at 58.6 mm, left-to-right starts at 41.4 mm. True when "…" was added. */
    private static boolean flagB(List<Line> out, Row row, LabelTextFitter.Fit fit, float baselineMm) {
        if (fit.lines().isEmpty()) return false;
        LabelTextFitter.Line l = fit.lines().get(0);
        float x = l.rtl() ? TEXT_RIGHT_MM * MM - l.width() : TEXT_LEFT_MM * MM;
        out.add(new Line(row, l.runs(), l.logical(), fit.size(), x, baselineMm * MM, l.width(), l.rtl()));
        return fit.ellipsized();
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }
}
