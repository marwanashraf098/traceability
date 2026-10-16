package com.traceability.inventory;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalizes a scanned value before a PIECE lookup (S1, approved 2026-10-16).
 *
 * A USB / Bluetooth scanner is a keyboard: with Caps Lock on it types {@code p000123}, with an
 * Arabic OS layout it types Arabic glyphs and (macOS) Arabic-Indic digits. Every piece column
 * ({@code pieces.barcode}, {@code id}, {@code short_code}) is ASCII uppercase, so the lookup input
 * is brought to that form. Lookup input ONLY — callers keep storing and echoing the raw scan
 * (raw_scan / raw_barcode / current_waybill_scan stay evidence of what was actually sent).
 *
 * Rules, in order:
 * <ol>
 *   <li>strip invisible characters (Unicode Cf / Cc) and every whitespace character;</li>
 *   <li>fold Arabic-Indic (U+0660–0669) and Persian (U+06F0–06F9) digits to ASCII;</li>
 *   <li>uppercase ONLY a piece-shaped result ({@code P}+6 or more digits, {@code PC-}+ULID, bare
 *       ULID — shape matched case-insensitively); anything else (order numbers, tracking numbers,
 *       other carriers' codes) is returned after rules 1–2 and never case-changed;</li>
 *   <li>one narrow layout recovery: exactly one leading glyph from {@link #P_KEY_GLYPHS} followed
 *       by 6 or more digits → {@code "P"} + digits. No other reverse mapping: PC-/ULID and
 *       {@code BOSTA_} typed through an Arabic layout are ambiguous as text and are left alone.</li>
 * </ol>
 * The shared vectors are in {@code shared/scan-normalization-vectors.json}.
 */
public final class ScanInputNormalizer {

    private ScanInputNormalizer() {}

    /**
     * Every glyph the physical P key produces on the Arabic layouts merchants use, read from the
     * OS layout tables (macOS UCKeyTranslate; Windows "Arabic (101)" = KBDA1, whose letter and
     * Shift layers equal macOS "Arabic - PC"), with and without Caps Lock:
     * <ul>
     *   <li>{@code ؛} U+061B ARABIC SEMICOLON — Shift+P on Windows Arabic 101 and macOS Arabic - PC;</li>
     *   <li>{@code [} U+005B LEFT SQUARE BRACKET — Shift+P on macOS Arabic;</li>
     *   <li>{@code ح} U+062D ARABIC LETTER HAH — P without Shift on all three (a scanner that
     *       compensates for Caps Lock, or a lowercase scan).</li>
     * </ul>
     * {@code [} is also Shift+F on Windows 101 / Arabic - PC; no piece code starts with F, so the
     * recovery can't mistake one.
     */
    static final Set<Integer> P_KEY_GLYPHS = Set.of(0x061B, 0x005B, 0x062D);

    private static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";
    private static final Pattern PIECE_SHAPE = Pattern.compile(
        "P[0-9]{6,}|PC-" + ULID + "|" + ULID, Pattern.CASE_INSENSITIVE);
    private static final Pattern DIGITS_6 = Pattern.compile("[0-9]{6,}");
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Cc}&&[^\t\n\r]]");

    /** The value to bind in a piece lookup ({@code barcode = ? OR id = ? OR short_code = ?}). */
    public static String normalizePiece(String raw) {
        if (raw == null) return null;
        String s = foldDigits(stripInvisible(raw));
        if (PIECE_SHAPE.matcher(s).matches()) return s.toUpperCase(Locale.ROOT);
        if (!s.isEmpty()) {
            int first = s.codePointAt(0);
            String rest = s.substring(Character.charCount(first));
            if (P_KEY_GLYPHS.contains(first) && DIGITS_6.matcher(rest).matches()) return "P" + rest;
        }
        return s;
    }

    /**
     * Drops zero-width / non-printing characters a scanner may inject, then every whitespace
     * character anywhere — Character.isWhitespace plus no-break and other Unicode space separators.
     */
    static String stripInvisible(String in) {
        String s = INVISIBLE.matcher(in).replaceAll("");
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) b.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    /** Arabic-Indic (U+0660..) and Extended Arabic-Indic / Persian (U+06F0..) digits → ASCII. */
    public static String foldDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') b.append((char) ('0' + (c - '۰')));
            else b.append(c);
        }
        return b.toString();
    }
}
