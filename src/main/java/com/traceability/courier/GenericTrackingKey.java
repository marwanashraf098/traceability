package com.traceability.courier;

import com.traceability.inventory.ScanInputNormalizer;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Non-Bosta couriers, G1 — the canonical key of a GENERIC shipment's tracking number
 * ({@code shipments.tracking_key}, unique per tenant for provider = 'generic', V169).
 *
 * Used ONLY for generic rows. Bosta numbers keep going through TrackingNumberNormalizer and
 * ScanInputNormalizer, which this class never changes. Not wired into any scan path in G1 (G2).
 *
 * Steps, in order:
 * <ol>
 *   <li>drop control / zero-width characters and every whitespace character;</li>
 *   <li>fold Arabic-Indic and Persian digits to ASCII ({@link ScanInputNormalizer#foldDigits});</li>
 *   <li>a URL ({@code scheme://…} or {@code www.…}): the first tracking-looking query parameter
 *       (awb, billcode, waybill, tracking…, track…, …) when it has a value, else the last
 *       non-empty path segment;</li>
 *   <li>uppercase, then keep A–Z and 0–9 only — no prefix or dash stripping ("WJ-12345" → "WJ12345").</li>
 * </ol>
 * Returns null when nothing is left. Shared vectors: {@code shared/generic-tracking-key-vectors.json}.
 */
public final class GenericTrackingKey {

    private GenericTrackingKey() {}

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Cc}]");

    /** Exact query-parameter names (lowercase) that carry a tracking number. */
    static final Set<String> TRACKING_PARAMS = Set.of(
        "awb", "awbno", "awb_no", "awbnumber", "awb_number",
        "billcode", "bill_code", "billno", "bill_no",
        "waybill", "waybillno", "waybill_no", "waybillnumber", "waybill_number",
        "tn", "trackno", "track_no", "consignment", "consignmentno", "shipment", "shipmentno", "shipmentnumber");

    /** Query-parameter name prefixes (lowercase): tracking, tracking_number, trackingNo, track_id, … */
    private static final String[] TRACKING_PARAM_PREFIXES = {"tracking", "track"};

    public static String of(String raw) {
        if (raw == null) return null;
        String s = ScanInputNormalizer.foldDigits(stripInvisible(raw));
        if (s.isEmpty()) return null;
        if (looksLikeUrl(s)) {
            String fromUrl = fromUrl(s);
            if (fromUrl != null) s = fromUrl;
        }
        String up = s.toUpperCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(up.length());
        for (int i = 0; i < up.length(); i++) {
            char c = up.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.isEmpty() ? null : b.toString();
    }

    private static String stripInvisible(String in) {
        String s = INVISIBLE.matcher(in).replaceAll("");
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) b.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    private static boolean looksLikeUrl(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("www.");
    }

    /** The tracking-looking query value, else the last non-empty path segment, else null. */
    private static String fromUrl(String s) {
        String withScheme = s.toLowerCase(Locale.ROOT).startsWith("www.") ? "https://" + s : s;
        URI uri;
        try {
            uri = URI.create(withScheme);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String name = decode(pair.substring(0, eq)).toLowerCase(Locale.ROOT);
                String value = decode(pair.substring(eq + 1));
                if (value.isEmpty() || !isTrackingParam(name)) continue;
                return value;
            }
        }
        String path = uri.getRawPath();
        if (path != null) {
            String[] segments = path.split("/");
            for (int i = segments.length - 1; i >= 0; i--) {
                String seg = decode(segments[i]);
                if (!seg.isEmpty()) return seg;
            }
        }
        return null;
    }

    private static boolean isTrackingParam(String name) {
        if (TRACKING_PARAMS.contains(name)) return true;
        for (String p : TRACKING_PARAM_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
