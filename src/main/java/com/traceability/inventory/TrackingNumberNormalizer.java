package com.traceability.inventory;

/**
 * Normalizes a raw AWB scan to the bare numeric form stored in {@code shipments.tracking_number}.
 *
 * Bosta physical labels encode a hub routing prefix before the tracking number, e.g.
 * {@code D-07-2944282510} where {@code D-07} is the Mansoura-Talkha hub code. The prefix
 * varies per hub and must never be hardcoded. Stripping after the last {@code -} is
 * unambiguous because no stored tracking number contains a dash.
 */
public final class TrackingNumberNormalizer {

    private TrackingNumberNormalizer() {}

    /**
     * @return the bare digits, or {@code null} if the input cannot be reduced to a valid
     *         all-digit tracking number (caller must reject with a clear error).
     */
    public static String normalize(String raw) {
        if (raw == null) return null;

        // Strip zero-width and non-printing characters a scanner may inject, then trim.
        String s = raw.replaceAll("[\\p{Cf}\\p{Cc}&&[^\t\n\r]]", "").trim();
        if (s.isEmpty()) return null;

        // If the string contains a dash, the tracking number follows the last one.
        int lastDash = s.lastIndexOf('-');
        if (lastDash >= 0) {
            s = s.substring(lastDash + 1);
        }

        // Reject anything that is not purely numeric.
        if (!s.matches("^[0-9]+$")) return null;

        return s;
    }
}
