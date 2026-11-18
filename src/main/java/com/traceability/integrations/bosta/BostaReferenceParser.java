package com.traceability.integrations.bosta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * THE reading of a Bosta businessReference (2026-11-17, exchange reference S1) — every place that turns a
 * reference into order-number candidates goes through here: {@link OrderReference} (and through it every
 * reference linker), {@link PreConnectDeliveryFilter} (keep / ignore, the pre-connect parent check) and
 * {@code PortalOnDemandLookup.referenceMatches}. Not here, by rule: {@code ShopifyShadowOrders.shadowForReturnReference}
 * and {@code ShopifyNativeExchange}'s regex — they read the raw Shopify Return name (-R&lt;n&gt;) on purpose.
 *
 * Shapes seen in prod (2026-11-17):
 * <ul>
 *   <li>(a) "BRK-47472-EG:BRK-47472-EG-R2" — order before the ':', the Shopify Return name after it;</li>
 *   <li>(b) "jamilatheboutique:#64968-R1", "overdue-9573:#OD2394-R1", "wjwy1a-ia:#5126" — the Bosta Shopify
 *       plugin: the shop handle before the ':', the order name after it, an optional -R&lt;n&gt; Return suffix;</li>
 *   <li>(c) "#LT-22267" — a prefixed order name; (d) "#4092" / "13313" — bare; (e) null.</li>
 * </ul>
 *
 * {@link #candidates}: the reference as sent, the part after the first ':', the part before it (all trimmed,
 * empty parts dropped); with {@code stripReturnSuffix} each is also tried without a trailing "-R&lt;digits&gt;".
 * The suffix is stripped ONLY where a trip that belongs to a Shopify Return should land on its original order
 * (an exchange's matched_order_id) or where a match can only KEEP a delivery (the pre-connect keep check) —
 * never for forward-leg linking (a SEND for a Return's replacement must not become the original's forward leg).
 * {@link #numberVariants}: each candidate as sent, '#'-stripped, '#'-prefixed, each also upper- and lower-cased
 * (orders.number is compared with IN, so the index on (tenant_id, number) keeps working).
 * {@link #orderNumber}: the ONE clean order number of a reference, or null whenever it is not unambiguous.
 */
public final class BostaReferenceParser {

    private BostaReferenceParser() {}

    /** Shopify's Return-name suffix: "#64968-R1" → "#64968". */
    private static final Pattern RETURN_SUFFIX = Pattern.compile("-R[0-9]+$");
    /** A clean order number: letters, digits, '-' and '_' only (the portal's safe-number rule). */
    private static final Pattern CLEAN_NUMBER = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");

    /** The reference's order-number candidates, in order: as sent, after the ':', before the ':'. */
    public static List<String> candidates(String reference, boolean stripReturnSuffix) {
        Set<String> out = new LinkedHashSet<>();
        if (reference == null || reference.isBlank()) return new ArrayList<>(out);
        String r = reference.trim();
        List<String> parts = new ArrayList<>();
        parts.add(r);
        int colon = r.indexOf(':');
        if (colon >= 0) {
            parts.add(r.substring(colon + 1).trim());
            parts.add(r.substring(0, colon).trim());
        }
        for (String p : parts) {
            if (p.isEmpty()) continue;
            out.add(p);
            if (stripReturnSuffix) {
                String s = stripSuffix(p);
                if (!s.isEmpty() && !stripHash(s).isEmpty()) out.add(s);
            }
        }
        return new ArrayList<>(out);
    }

    /** Each candidate as sent, '#'-stripped and '#'-prefixed, each also upper- and lower-cased. */
    public static Set<String> numberVariants(String reference, boolean stripReturnSuffix) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : candidates(reference, stripReturnSuffix)) {
            String bare = stripHash(c);
            if (bare.isEmpty()) continue;
            for (String v : new String[]{c, bare, "#" + bare}) {
                out.add(v);
                out.add(v.toUpperCase(Locale.ROOT));
                out.add(v.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * The ONE clean order number of a reference ('#' and any -R&lt;digits&gt; suffix removed), or null when it is
     * not unambiguous. Used where a single number is compared or searched for (the pre-connect ignore rule, the
     * pre-connect parent import) — so null must mean "don't decide on it":
     * <ul>
     *   <li>no ':' → the reference itself;</li>
     *   <li>one ':' → the two sides when they name the same number ((a): "BRK-1-EG:BRK-1-EG-R1"); else the part
     *       after the ':' when it is written as an order name — starts with '#' ((b): "shop-handle:#5126"); a
     *       side that is empty leaves the other; anything else (two different plain values) → null;</li>
     *   <li>more than one ':' → null;</li>
     *   <li>a result that isn't letters / digits / '-' / '_' (≤ 40) → null.</li>
     * </ul>
     */
    public static String orderNumber(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String r = reference.trim();
        int colon = r.indexOf(':');
        String number;
        if (colon < 0) {
            number = clean(r);
        } else {
            if (r.indexOf(':', colon + 1) >= 0) return null;
            String headRaw = r.substring(0, colon).trim();
            String tailRaw = r.substring(colon + 1).trim();
            String head = clean(headRaw);
            String tail = clean(tailRaw);
            if (head.isEmpty()) number = tail;
            else if (tail.isEmpty()) number = head;
            else if (head.equalsIgnoreCase(tail)) number = tail;
            else if (tailRaw.startsWith("#")) number = tail;
            else return null;
        }
        if (number.isEmpty() || !CLEAN_NUMBER.matcher(number).matches()) return null;
        return number;
    }

    /** The reference names this order: one of its candidates ('#'-stripped) equals the name ('#'-stripped), any case. */
    public static boolean names(String reference, String orderName, boolean stripReturnSuffix) {
        if (reference == null || orderName == null) return false;
        String name = stripHash(orderName);
        if (name.isEmpty()) return false;
        for (String c : candidates(reference, stripReturnSuffix)) {
            if (stripHash(c).equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String clean(String part) {
        return stripHash(stripSuffix(part.trim()));
    }

    private static String stripSuffix(String s) {
        return RETURN_SUFFIX.matcher(s).replaceFirst("").trim();
    }

    private static String stripHash(String s) {
        String t = s.trim();
        return t.startsWith("#") ? t.substring(1).trim() : t;
    }
}
