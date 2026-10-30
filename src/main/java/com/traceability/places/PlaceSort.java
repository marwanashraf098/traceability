package com.traceability.places;

import java.util.List;
import java.util.Locale;

/**
 * Warehouse places S3 — the walking order of places, as one sortable string per place ({@code sortKey}): screens
 * sort by plain string comparison, never by the codes themselves.
 * <ul>
 *   <li>Natural order of each code: digit runs compare as numbers (Z2 before Z10, R02 before R10, S9 before S10),
 *       letters case-insensitively.</li>
 *   <li>A place sorts right under its parent (Z1 › Z1-R01 › Z1-R01-S2 › a box on Z1-R01-S2 › Z1-R01-S3).</li>
 *   <li>Boxes standing on a zone's floor sort after all of that zone's racks.</li>
 * </ul>
 * Codes are ASCII (bins_code_shape: letters, digits, '.', '_', '-'), so a space (below every code character — '-'
 * and '.' sort below '/', so '/' would not do) ends a parent's key before its children, and '~' (above every code
 * character, upper-cased) puts floor boxes last in their zone.
 */
public final class PlaceSort {

    private static final int DIGITS = 10;
    private static final char SEPARATOR = ' ';

    private PlaceSort() {}

    /** One place's chain, top (its zone) first, the place itself last: {kind, code} pairs. */
    public record Level(String kind, String code) {}

    public static String key(List<Level> chainTopFirst) {
        StringBuilder key = new StringBuilder();
        Level parent = null;
        for (Level level : chainTopFirst) {
            if (parent != null) {
                key.append(SEPARATOR);
                if ("box".equals(level.kind()) && "zone".equals(parent.kind())) key.append('~');
            }
            key.append(natural(level.code()));
            parent = level;
        }
        return key.toString();
    }

    /** The code with every digit run left-padded to ten digits, upper case: Z2 → Z0000000002. */
    static String natural(String code) {
        String c = code == null ? "" : code.toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(c.length() + DIGITS);
        int i = 0;
        while (i < c.length()) {
            char ch = c.charAt(i);
            if (ch >= '0' && ch <= '9') {
                int j = i;
                while (j < c.length() && c.charAt(j) >= '0' && c.charAt(j) <= '9') j++;
                String run = c.substring(i, j).replaceFirst("^0+(?=.)", "");
                out.append("0".repeat(Math.max(0, DIGITS - run.length()))).append(run);
                i = j;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }
}
