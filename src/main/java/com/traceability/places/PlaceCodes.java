package com.traceability.places;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Place codes, in one place.
 * <ul>
 *   <li>Sticker barcodes: 'Q' + at least 6 digits from the tenant's bin_counters row — never shaped like a piece
 *       code (P…), a waybill (all digits, or digits after the last '-') or a Bosta QR, and never changed.</li>
 *   <li>Readable codes: zones Z1, Z2…; racks {zone}-R01…; shelves {rack}-S1…; boxes BOX-01… (per location, kept
 *       when the box moves). Each "next" is the lowest free number, counting archived places too (codes stay
 *       unique per location for ever).</li>
 * </ul>
 */
public final class PlaceCodes {

    private static final Pattern CODE_SHAPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,39}");
    private static final Pattern RESERVED_SHAPE = Pattern.compile("[PQ][0-9]{6,}", Pattern.CASE_INSENSITIVE);

    private PlaceCodes() {}

    /** Claims {@code n} (≥ 1) sticker barcodes for the tenant; same INSERT … ON CONFLICT claim as PieceCodeCounter. */
    public static List<String> claimScanCodes(JdbcTemplate jdbc, UUID tenantId, int n) {
        if (n < 1) throw new IllegalArgumentException("claim needs n >= 1");
        Long last = jdbc.queryForObject(
            "INSERT INTO bin_counters (tenant_id, last_value) VALUES (?, ?) " +
            "ON CONFLICT (tenant_id) DO UPDATE SET last_value = bin_counters.last_value + EXCLUDED.last_value " +
            "RETURNING last_value",
            Long.class, tenantId, (long) n);
        List<String> codes = new ArrayList<>(n);
        for (long v = last - n + 1; v <= last; v++) codes.add(formatScanCode(v));
        return codes;
    }

    /** "Q" + the value, at least 6 digits (Q000123, Q1000000). */
    public static String formatScanCode(long value) {
        return "Q" + String.format("%06d", value);
    }

    /** A typed readable code, trimmed; null when it isn't an allowed code. */
    static String cleanCode(String raw) {
        if (raw == null) return null;
        String c = raw.strip();
        if (!CODE_SHAPE.matcher(c).matches() || RESERVED_SHAPE.matcher(c).matches()) return null;
        return c;
    }

    /** The lowest {@code count} free numbers n ≥ 1 for codes {@code prefix + n} (case-insensitive) among {@code taken}. */
    static List<Integer> lowestFree(Collection<String> taken, String prefix, int count) {
        Pattern p = Pattern.compile(Pattern.quote(prefix.toUpperCase(Locale.ROOT)) + "([0-9]{1,6})");
        Set<Integer> used = new TreeSet<>();
        for (String code : taken) {
            Matcher m = p.matcher(code.toUpperCase(Locale.ROOT));
            if (m.matches()) used.add(Integer.parseInt(m.group(1)));
        }
        List<Integer> free = new ArrayList<>(count);
        for (int n = 1; free.size() < count; n++) {
            if (!used.contains(n)) free.add(n);
        }
        return free;
    }

    static String zone(int n)                  { return "Z" + n; }
    static String rack(String zone, int n)     { return zone + "-R" + String.format("%02d", n); }
    static String shelf(String rack, int n)    { return rack + "-S" + n; }
    static String box(int n)                   { return "BOX-" + String.format("%02d", n); }

    /**
     * What a place is called inside its parent: its code without the parent's code prefix (Z1-R01 in Z1 → R01,
     * Z1-R01-S2 on Z1-R01 → S2); a code that doesn't start with its parent's is shown whole (BOX-07).
     */
    static String shortLabel(String code, String parentCode) {
        if (parentCode != null) {
            String prefix = parentCode + "-";
            if (code.length() > prefix.length() && code.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return code.substring(prefix.length());
            }
        }
        return code;
    }
}
