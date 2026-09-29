package com.traceability.inventory;

import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The label fonts — all embedded (PDType0Font, subset), no Standard-14 font anywhere on a label:
 * NotoSans-Regular / NotoSans-Bold for Latin, Greek, Cyrillic, digits and punctuation, and
 * NotoSansArabic-Regular for Arabic script (resources/fonts, SIL OFL — see OFL.txt there).
 *
 * Per-glyph fallback ({@link #runs}): Arabic-script code points go to the Arabic font; every other
 * code point goes to the Latin face when it has the glyph. NotoSansArabic has no glyph for "/",
 * "&", "…" or Latin letters, so those must never land in an Arabic run (a "/" next to Arabic used
 * to throw "No glyph for U+002F" and 500 the whole PDF). A code point no font has is replaced
 * with "?" so a label never fails to render.
 *
 * Widths are measured from the fonts' own hmtx tables ({@link #advance}) — the same numbers
 * PDType0Font.getStringWidth returns — so text can be fitted without a PDDocument.
 */
public final class LabelFonts {

    public enum Face { REGULAR, BOLD, ARABIC }

    private static final Map<Face, String> FILES = Map.of(
        Face.REGULAR, "fonts/NotoSans-Regular.ttf",
        Face.BOLD,    "fonts/NotoSans-Bold.ttf",
        Face.ARABIC,  "fonts/NotoSansArabic-Regular.ttf");

    /** Per glyph: advance and ink top/bottom (glyf bounding box), all in font units. */
    private record Metrics(byte[] bytes, CmapLookup cmap, int[] advances, int[] inkTop, int[] inkBottom, int unitsPerEm) {}

    private static final Map<Face, Metrics> METRICS = new EnumMap<>(Face.class);

    static {
        for (Face f : Face.values()) {
            try (InputStream in = new ClassPathResource(FILES.get(f)).getInputStream()) {
                byte[] bytes = in.readAllBytes();
                TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(bytes));
                int glyphs = ttf.getNumberOfGlyphs();
                int[] adv = new int[glyphs], top = new int[glyphs], bottom = new int[glyphs];
                org.apache.fontbox.ttf.GlyphTable glyf = ttf.getGlyph();
                for (int g = 0; g < glyphs; g++) {
                    adv[g] = ttf.getAdvanceWidth(g);
                    org.apache.fontbox.ttf.GlyphData d = glyf.getGlyph(g);
                    if (d != null) {
                        top[g] = (int) Math.ceil(d.getBoundingBox().getUpperRightY());
                        bottom[g] = (int) Math.floor(d.getBoundingBox().getLowerLeftY());
                    }
                }
                METRICS.put(f, new Metrics(bytes, ttf.getUnicodeCmapLookup(), adv, top, bottom, ttf.getUnitsPerEm()));
            } catch (IOException e) {
                throw new UncheckedIOException("Label font missing: " + FILES.get(f), e);
            }
        }
    }

    private LabelFonts() {}

    /** A run of text drawn in one face. */
    public record Run(String text, Face face) {}

    /** True when {@code face} has a glyph for the code point. */
    public static boolean has(Face face, int codePoint) {
        return METRICS.get(face).cmap().getGlyphId(codePoint) != 0;
    }

    /** Advance width of one code point in 1/1000 em (0 when the face has no glyph). */
    public static float advance(Face face, int codePoint) {
        Metrics m = METRICS.get(face);
        int gid = m.cmap().getGlyphId(codePoint);
        if (gid <= 0 || gid >= m.advances().length) return 0f;
        return m.advances()[gid] * 1000f / m.unitsPerEm();
    }

    /**
     * Ink top / bottom (em, bottom negative) of the glyphs in these runs; {0, 0} when nothing
     * is inked (spaces only).
     */
    public static float[] ink(List<Run> runs) {
        float top = 0f, bottom = 0f;
        for (Run r : runs) {
            Metrics m = METRICS.get(r.face());
            for (int i = 0; i < r.text().length(); ) {
                int cp = r.text().codePointAt(i);
                i += Character.charCount(cp);
                int gid = m.cmap().getGlyphId(cp);
                if (gid <= 0 || gid >= m.advances().length) continue;
                top = Math.max(top, m.inkTop()[gid] / (float) m.unitsPerEm());
                bottom = Math.min(bottom, m.inkBottom()[gid] / (float) m.unitsPerEm());
            }
        }
        return new float[]{top, bottom};
    }

    /**
     * The Arabic letters' ink envelope (em): every letter U+0621–U+064A and every presentation
     * form that isn't a combining mark — hamza and dots included, optional harakat excluded.
     * NotoSansArabic: +1.010 / −0.421 (1.431 em). The font's declared ascent/descent (1.374 /
     * −0.738, 2.112 em) reserves room for stacked Quranic marks.
     */
    public static final float[] ARABIC_LETTER_INK = arabicLetterInk();

    private static float[] arabicLetterInk() {
        StringBuilder letters = new StringBuilder();
        for (int cp = 0x0621; cp <= 0x064A; cp++) letters.appendCodePoint(cp);
        for (int cp = 0xFE70; cp <= 0xFEFC; cp++) {
            if (Character.getType(cp) != Character.NON_SPACING_MARK) letters.appendCodePoint(cp);
        }
        return ink(List.of(new Run(letters.toString(), Face.ARABIC)));
    }

    public static boolean isArabicScript(int cp) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
        return b == Character.UnicodeBlock.ARABIC
            || b == Character.UnicodeBlock.ARABIC_SUPPLEMENT
            || b == Character.UnicodeBlock.ARABIC_EXTENDED_A
            || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
            || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B;
    }

    public static boolean containsArabic(String text) {
        return text != null && text.codePoints().anyMatch(LabelFonts::isArabicScript);
    }

    /**
     * Splits already-display-ordered text into single-face runs, glyph by glyph: Arabic script →
     * ARABIC (when it has the glyph), anything else → {@code latin} when it has it, else ARABIC
     * when only that font has it, else "?" in {@code latin}.
     */
    public static List<Run> runs(String visual, Face latin) {
        List<Run> out = new ArrayList<>();
        if (visual == null || visual.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        Face curFace = null;
        for (int i = 0; i < visual.length(); ) {
            int cp = visual.codePointAt(i);
            i += Character.charCount(cp);
            Face face;
            int draw = cp;
            if (isArabicScript(cp) && has(Face.ARABIC, cp)) face = Face.ARABIC;
            else if (has(latin, cp)) face = latin;
            else if (has(Face.ARABIC, cp)) face = Face.ARABIC;
            else { face = latin; draw = '?'; }
            if (curFace != null && face != curFace) {
                out.add(new Run(cur.toString(), curFace));
                cur.setLength(0);
            }
            curFace = face;
            cur.appendCodePoint(draw);
        }
        out.add(new Run(cur.toString(), curFace));
        return out;
    }

    /** Width in points of the runs at {@code size}. */
    public static float width(List<Run> runs, float size) {
        float w = 0f;
        for (Run r : runs) {
            for (int i = 0; i < r.text().length(); ) {
                int cp = r.text().codePointAt(i);
                i += Character.charCount(cp);
                w += advance(r.face(), cp);
            }
        }
        return w / 1000f * size;
    }

    /** The three faces embedded into one document, loaded on first use (subset on save). */
    public static final class Loaded {
        private final PDDocument doc;
        private final Map<Face, PDType0Font> fonts = new EnumMap<>(Face.class);

        public Loaded(PDDocument doc) { this.doc = doc; }

        public PDType0Font font(Face face) throws IOException {
            PDType0Font f = fonts.get(face);
            if (f == null) {
                f = PDType0Font.load(doc, new ByteArrayInputStream(METRICS.get(face).bytes()), true);
                fonts.put(face, f);
            }
            return f;
        }
    }
}
