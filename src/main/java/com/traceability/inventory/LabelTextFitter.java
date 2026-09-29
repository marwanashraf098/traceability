package com.traceability.inventory;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import com.ibm.icu.text.BreakIterator;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits one piece of label text into a width, measured with the real fonts (LabelFonts), not by
 * counting characters: wrap to at most N lines, then shrink step by step to a floor size, then
 * end the last line with "…".
 *
 * Every line is fitted in LOGICAL order and only then shaped (Arabic contextual forms) and
 * bidi-ordered for display, so for Arabic the "…" sits at the logical end (the visual left) and
 * a cut never splits a grapheme. Paragraph direction is the text's first strong character.
 */
public final class LabelTextFitter {

    public static final String ELLIPSIS = "…";
    static final float STEP = 0.5f;

    private LabelTextFitter() {}

    /** One display line: the logical text it came from, its font runs in visual order, its width. */
    public record Line(String logical, List<LabelFonts.Run> runs, float width, boolean rtl) {}

    /** Where a "…" cut may fall: after a whole word (title, variant) or at any grapheme (SKU). */
    public enum Cut { WORD, GRAPHEME }

    /** The fitted lines at {@code size}; {@code ellipsized} only when text was actually removed. */
    public record Fit(List<Line> lines, float size, boolean ellipsized) {}

    /**
     * Wrap into at most {@code maxLines} lines of {@code maxWidth}, starting at {@code start} pt and
     * shrinking by 0.5 pt down to {@code floor}; at the floor, the last line ends with "…".
     */
    public static Fit fit(String logical, LabelFonts.Face latin, float start, float floor, int maxLines, float maxWidth) {
        return fit(logical, latin, start, floor, maxLines, maxWidth, Cut.WORD);
    }

    public static Fit fit(String logical, LabelFonts.Face latin, float start, float floor, int maxLines, float maxWidth,
                          Cut cut) {
        String text = normalize(logical);
        for (float size = start; size >= floor - 0.001f; size -= STEP) {
            List<String> lines = wrap(text, latin, size, maxWidth);
            if (lines.size() <= maxLines) return new Fit(toLines(lines, latin, size), size, false);
        }
        List<String> lines = wrap(text, latin, floor, maxWidth);
        // Still more lines than allowed at the floor: text has to go. The "…" is added at the
        // logical end of what's kept (bidi shows it at the visual end for the line's direction).
        List<String> kept = new ArrayList<>(lines.subList(0, maxLines - 1));
        String rest = String.join(" ", lines.subList(maxLines - 1, lines.size()));
        String last = ellipsize(rest, latin, floor, maxWidth, cut);
        kept.add(last);
        return new Fit(toLines(kept, latin, floor), floor, !last.equals(rest));
    }

    /** Arabic shaping (contextual forms) + bidi reordering to visual left-to-right order. */
    public static String display(String logical) {
        if (logical == null || logical.isBlank()) return "";
        if (!LabelFonts.containsArabic(logical)) return logical;
        try {
            String shaped = new ArabicShaping(ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL)
                .shape(logical);
            Bidi bidi = new Bidi();
            bidi.setPara(shaped, isRtl(logical) ? Bidi.RTL : Bidi.LTR, null);
            return bidi.writeReordered(Bidi.DO_MIRRORING);
        } catch (ArabicShapingException e) {
            return logical;
        }
    }

    /** Right-to-left when the first strong character is Arabic. */
    public static boolean isRtl(String logical) {
        return logical != null && Bidi.getBaseDirection(logical) == Bidi.RTL;
    }

    /** Width in points of {@code logical} as displayed at {@code size}. */
    public static float measure(String logical, LabelFonts.Face latin, float size) {
        return LabelFonts.width(LabelFonts.runs(display(logical), latin), size);
    }

    // ── wrapping ──────────────────────────────────────────────────────────────

    private static String normalize(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    /** Greedy word wrap; a word wider than the line is broken at grapheme boundaries. */
    static List<String> wrap(String text, LabelFonts.Face latin, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) return lines;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (measure(candidate, latin, size) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            String rest = word;
            while (measure(rest, latin, size) > maxWidth) {
                int cut = longestFittingPrefix(rest, "", latin, size, maxWidth);
                if (cut == 0) cut = firstGraphemeEnd(rest);
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /**
     * {@code text} if it fits. Otherwise, for WORD: the longest prefix ending at a line-break
     * opportunity (ICU — Arabic and Latin), trailing spaces and separators (— – - , ، / & ; :)
     * removed, + "…"; if even the first word doesn't fit, a grapheme cut. For GRAPHEME: the
     * longest grapheme prefix + "…".
     */
    static String ellipsize(String text, LabelFonts.Face latin, float size, float maxWidth, Cut cut) {
        if (measure(text, latin, size) <= maxWidth) return text;
        if (cut == Cut.WORD) {
            BreakIterator it = BreakIterator.getLineInstance();
            it.setText(text);
            String best = null;
            for (int b = it.first(); b != BreakIterator.DONE; b = it.next()) {
                String kept = stripTrailingSeparators(text.substring(0, b));
                if (kept.isEmpty() || b == text.length()) continue;
                if (measure(kept + ELLIPSIS, latin, size) <= maxWidth) best = kept;
                else break;
            }
            if (best != null) return best + ELLIPSIS;
        }
        int end = longestFittingPrefix(text, ELLIPSIS, latin, size, maxWidth);
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    private static final String SEPARATORS = ",،/&;؛:·|+";

    /** Drops trailing whitespace, dashes and list separators so a cut never reads "إصدار —…". */
    static String stripTrailingSeparators(String s) {
        int end = s.length();
        while (end > 0) {
            int cp = s.codePointBefore(end);
            if (Character.isWhitespace(cp) || Character.getType(cp) == Character.DASH_PUNCTUATION
                    || SEPARATORS.indexOf(cp) >= 0) {
                end -= Character.charCount(cp);
            } else {
                break;
            }
        }
        return s.substring(0, end);
    }

    /** Largest grapheme boundary b such that text[0,b) (trimmed) + suffix fits; binary search. */
    private static int longestFittingPrefix(String text, String suffix, LabelFonts.Face latin, float size, float maxWidth) {
        List<Integer> bounds = graphemeBoundaries(text);
        int lo = 0, hi = bounds.size() - 1, best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int b = bounds.get(mid);
            if (measure(text.substring(0, b).stripTrailing() + suffix, latin, size) <= maxWidth) {
                best = b;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private static List<Integer> graphemeBoundaries(String text) {
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        List<Integer> out = new ArrayList<>();
        for (int b = it.first(); b != BreakIterator.DONE; b = it.next()) out.add(b);
        return out;
    }

    private static int firstGraphemeEnd(String text) {
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        it.first();
        int b = it.next();
        return b == BreakIterator.DONE ? text.length() : b;
    }

    private static List<Line> toLines(List<String> logicalLines, LabelFonts.Face latin, float size) {
        List<Line> out = new ArrayList<>();
        for (String l : logicalLines) {
            List<LabelFonts.Run> runs = LabelFonts.runs(display(l), latin);
            out.add(new Line(l, runs, LabelFonts.width(runs, size), isRtl(l)));
        }
        return out;
    }
}
