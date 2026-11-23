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
 *   x 40–60 mm  Flag B — the name (bold 6 pt, up to 2 lines), then "variant · SKU" (5.5 pt); no price
 * </pre>
 * All ink stays ≥ 1 mm inside each flag. Both flags print the same way up.
 *
 * <p><b>Bars</b> are vector rectangles from ZXing's module row (MARGIN 0; ZXing 3.5.3 already encodes P + digits
 * at the minimum length: Start B, P, one digit in set B when the digit count is odd, CODE C, digit pairs — 90 modules
 * for P + 6 digits, 101 for P + 7 / P + 8). One module = 2 dots at 300 dpi = 0.48 pt, and the start x is snapped to
 * the 0.24 pt dot grid, so a 300 dpi printer draws every bar edge on a dot boundary with nothing resampled. The
 * symbol is centred in Flag A by its actual module count.
 *
 * <p><b>Flag B</b> (piece label and separator alike): lines 1–2 the product name, bold 6 pt, wrapped at word
 * boundaries to at most 2 lines — when it needs more (or one word is wider than a line) the whole name shrinks in
 * 0.25 pt steps down to 5 pt until it fits 2 lines — and "…" only when it still doesn't fit at 5 pt. Line 3 the
 * variant and the SKU joined with " | " (regular 5.5 pt → 4.75 →
 * "…"; "Default Title" hidden, an empty part skipped, nothing at all → no line 3). The lines that exist are centred
 * as a block in the 1–11 mm safe area, line to line by the font's ink envelope (Arabic's when the name or variant
 * contains Arabic — its letters and tashkeel reach further) plus a fixed gap, so lines never touch.
 *
 * <p>Direction: when the name or variant contains Arabic, every Flag B line ends at 58.6 mm (right-aligned);
 * otherwise every line starts at 41.4 mm. Each line's own bidi keeps a Latin SKU in Latin order. Shaping and bidi
 * are LabelTextFitter's (ICU4J), fonts LabelFonts' (no new font).
 *
 * <p><b>Separator</b> (label runs and tag stock print one before each group): no barcode and no code — Flag A shows
 * only "× N" (the group's label count), Flag B the same lines as the group's labels.
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
    /** Flag B's lines are centred in this band (the safe area, mm). */
    static final float TEXT_BOTTOM_MM = SAFE_MM, TEXT_TOP_MM = HEIGHT_MM - SAFE_MM;
    static final int NAME_MAX_LINES = 2;
    static final float NAME_START = 6f, NAME_FLOOR = 5f, NAME_STEP = 0.25f;
    static final float DETAIL_START = 5.5f, DETAIL_FLOOR = 4.75f;
    static final String DETAIL_JOIN = " | ";
    /** Clear space between one line's ink envelope and the next (mm). */
    static final float LINE_GAP_MM = 0.35f;
    /**
     * Ink envelope per em, {ascent, descent}: Latin (NotoSans accents ≈ 0.944 above, descenders ≈ 0.24 below) and
     * Arabic (letters +1.01 / −0.421, tashkeel inside). Three Arabic lines at full size: 9.6 mm ≤ the 10 mm band.
     */
    static final float[] LATIN_ENVELOPE = {0.96f, 0.27f}, ARABIC_ENVELOPE = {1.02f, 0.43f};

    private RatTailLabel() {}

    /** The tenant setting that selects this label: exactly 60 × 12 mm. */
    public static boolean is(PieceLabelLayout.Spec spec) {
        return spec != null && Math.abs(spec.widthMm() - WIDTH_MM) < 0.01f && Math.abs(spec.heightMm() - HEIGHT_MM) < 0.01f;
    }

    /** NAME = a line of the product name (one or two); DETAIL = "variant | SKU". */
    public enum Row { CODE, NAME, DETAIL, COUNT }

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
        List<Line> lines = new ArrayList<>();
        LabelTextFitter.Fit c = LabelTextFitter.fit(code, LabelFonts.Face.REGULAR, CODE_SIZE, CODE_SIZE, 1, FLAG_A_END_MM * MM);
        lines.add(centredInFlagA(Row.CODE, c, CODE_BASELINE_MM));
        boolean ellipsized = flagB(lines, productTitle, variantTitle, sku);
        return new Layout(WIDTH_MM * MM, HEIGHT_MM * MM, bars(code), List.copyOf(lines), ellipsized);
    }

    /** The page before a group of {@code count} labels: "× N" on Flag A, the group's Flag B, no barcode. */
    public static Layout separator(int count, String productTitle, String variantTitle, String sku) {
        if (count < 1) throw new IllegalArgumentException("a separator heads at least one label");
        List<Line> lines = new ArrayList<>();
        LabelTextFitter.Fit n = LabelTextFitter.fit(countText(count), LabelFonts.Face.BOLD, COUNT_SIZE, COUNT_SIZE, 1,
            (FLAG_A_END_MM - 2 * SAFE_MM) * MM);
        lines.add(centredInFlagA(Row.COUNT, n, COUNT_BASELINE_MM));
        boolean ellipsized = flagB(lines, productTitle, variantTitle, sku);
        return new Layout(WIDTH_MM * MM, HEIGHT_MM * MM, null, List.copyOf(lines), ellipsized);
    }

    public static Layout separator(int count, String productTitle, String variantTitle) {
        return separator(count, productTitle, variantTitle, null);
    }

    /** "× 12" — U+00D7 MULTIPLICATION SIGN, a space, the count. */
    public static String countText(int count) {
        return "× " + count;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Name lines (≤ 2) and the "variant · SKU" line, before positioning. */
    record FlagB(List<LabelTextFitter.Line> name, float nameSize, LabelTextFitter.Line detail, float detailSize,
                 boolean arabic, boolean ellipsized) {}

    /** Same rules as PieceLabelLayout: "Default Title" hidden; a blank name takes the variant's place. */
    static FlagB flagBText(String productTitle, String variantTitle, String sku) {
        String name = clean(productTitle);
        String variant = clean(variantTitle);
        if ("Default Title".equalsIgnoreCase(variant)) variant = "";
        if (name.isEmpty()) { name = variant; variant = ""; }
        boolean arabic = LabelFonts.containsArabic(name) || LabelFonts.containsArabic(variant);
        float max = TEXT_MAX_WIDTH_MM * MM;

        List<LabelTextFitter.Line> nameLines = List.of();
        float nameSize = NAME_START;
        boolean ellipsized = false;
        if (!name.isEmpty()) {
            while (nameSize > NAME_FLOOR + 0.001f && !fitsTwoLines(name, nameSize, max)) nameSize -= NAME_STEP;
            LabelTextFitter.Fit f = LabelTextFitter.fit(name, LabelFonts.Face.BOLD, nameSize, nameSize, NAME_MAX_LINES, max);
            nameLines = f.lines();
            ellipsized = f.ellipsized();
        }

        String detail = String.join(DETAIL_JOIN, java.util.stream.Stream.of(variant, clean(sku))
            .filter(x -> !x.isEmpty()).toList());
        LabelTextFitter.Line detailLine = null;
        float detailSize = DETAIL_START;
        if (!detail.isEmpty()) {
            LabelTextFitter.Fit f = LabelTextFitter.fit(detail, LabelFonts.Face.REGULAR, DETAIL_START, DETAIL_FLOOR, 1, max);
            detailLine = f.lines().get(0);
            detailSize = f.size();
            ellipsized |= f.ellipsized();
        }
        return new FlagB(nameLines, nameSize, detailLine, detailSize, arabic, ellipsized);
    }

    /** Positions Flag B: the lines centred as a block in 1–11 mm, aligned by the label's direction. */
    private static boolean flagB(List<Line> out, String productTitle, String variantTitle, String sku) {
        FlagB t = flagBText(productTitle, variantTitle, sku);
        record Pending(Row row, LabelTextFitter.Line line, float size) {}
        List<Pending> pending = new ArrayList<>();
        for (LabelTextFitter.Line l : t.name()) pending.add(new Pending(Row.NAME, l, t.nameSize()));
        if (t.detail() != null) pending.add(new Pending(Row.DETAIL, t.detail(), t.detailSize()));
        if (pending.isEmpty()) return false;

        float[] env = t.arabic() ? ARABIC_ENVELOPE : LATIN_ENVELOPE;
        float gap = LINE_GAP_MM * MM;
        float height = (pending.size() - 1) * gap;
        for (Pending p : pending) height += (env[0] + env[1]) * p.size();
        float top = (TEXT_BOTTOM_MM + TEXT_TOP_MM) / 2f * MM + height / 2f;
        for (Pending p : pending) {
            float baseline = top - env[0] * p.size();
            LabelTextFitter.Line l = p.line();
            float x = t.arabic() ? TEXT_RIGHT_MM * MM - l.width() : TEXT_LEFT_MM * MM;
            out.add(new Line(p.row(), l.runs(), l.logical(), p.size(), x, baseline, l.width(), l.rtl()));
            top = baseline - env[1] * p.size() - gap;
        }
        return t.ellipsized();
    }

    /** At {@code size} every word fits a line and the name wraps into at most 2 lines. */
    private static boolean fitsTwoLines(String name, float size, float max) {
        return widestWord(name, size) <= max
            && LabelTextFitter.wrap(name.replaceAll("\\s+", " "), LabelFonts.Face.BOLD, size, max).size() <= NAME_MAX_LINES;
    }

    private static float widestWord(String text, float size) {
        float w = 0;
        for (String word : text.split("\\s+")) w = Math.max(w, LabelTextFitter.measure(word, LabelFonts.Face.BOLD, size));
        return w;
    }

    private static Line centredInFlagA(Row row, LabelTextFitter.Fit fit, float baselineMm) {
        LabelTextFitter.Line l = fit.lines().get(0);
        float x = (FLAG_A_END_MM * MM - l.width()) / 2f;
        return new Line(row, l.runs(), l.logical(), fit.size(), x, baselineMm * MM, l.width(), l.rtl());
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }
}
