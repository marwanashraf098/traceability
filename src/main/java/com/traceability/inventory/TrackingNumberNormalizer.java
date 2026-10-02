package com.traceability.inventory;

/**
 * Normalizes a raw AWB scan to the bare numeric form stored in {@code shipments.tracking_number}.
 *
 * Bosta physical labels encode a hub routing prefix before the tracking number, e.g.
 * {@code D-07-2944282510} where {@code D-07} is the Mansoura-Talkha hub code. The prefix
 * varies per hub and must never be hardcoded. Stripping after the last {@code -} is
 * unambiguous because no stored tracking number contains a dash.
 *
 * Bosta's TOP waybill barcode encodes a space between every character, e.g.
 * {@code "G - 0 2 - 8 4 8 4 8 0 5 6 9 9"} (seen in production, Jumi 2026-10-01), so every
 * whitespace character — ASCII, tab / newline, no-break space, any Unicode space separator —
 * is removed before the prefix strip and the digits-only check. Nothing else changes.
 *
 * Bosta's waybill QR code holds {@code "BOSTA_<tracking number>"} (camera spike, 2026-10-02):
 * after whitespace removal a case-insensitive {@code BOSTA_} prefix is dropped and the rest must
 * be all digits — {@code "BOSTA_8484805699"} → {@code "8484805699"}, anything else → null.
 */
public final class TrackingNumberNormalizer {

    private TrackingNumberNormalizer() {}

    private static final String BOSTA_QR_PREFIX = "BOSTA_";

    /**
     * @return the bare digits, or {@code null} if the input cannot be reduced to a valid
     *         all-digit tracking number (caller must reject with a clear error).
     */
    public static String normalize(String raw) {
        if (raw == null) return null;

        // Strip zero-width and non-printing characters a scanner may inject, then every
        // whitespace character anywhere (not just the ends) — see the class comment.
        String s = removeWhitespace(raw.replaceAll("[\\p{Cf}\\p{Cc}&&[^\t\n\r]]", ""));
        if (s.isEmpty()) return null;

        // Bosta waybill QR: "BOSTA_<digits>" (prefix case-insensitive) — see the class comment.
        if (s.regionMatches(true, 0, BOSTA_QR_PREFIX, 0, BOSTA_QR_PREFIX.length())) {
            String rest = s.substring(BOSTA_QR_PREFIX.length());
            return rest.matches("^[0-9]+$") ? rest : null;
        }

        // If the string contains a dash, the tracking number follows the last one.
        int lastDash = s.lastIndexOf('-');
        if (lastDash >= 0) {
            s = s.substring(lastDash + 1);
        }

        // Reject anything that is not purely numeric.
        if (!s.matches("^[0-9]+$")) return null;

        return s;
    }

    /** Character.isWhitespace (ASCII space, tab, newlines, …) plus no-break / other Unicode space separators. */
    private static String removeWhitespace(String in) {
        StringBuilder b = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); ) {
            int cp = in.codePointAt(i);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) b.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return b.toString();
    }
}
