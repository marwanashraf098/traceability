package com.traceability.analytics;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analytics slice 5 — the mapping tables, as plain Java so each rule is unit-tested on its own:
 * sales channel, payment method, delivery-failure reason, size. No database access.
 */
final class AnalyticsMappings {

    private AnalyticsMappings() {}

    // ── channel ─────────────────────────────────────────────────────────────

    static final String INSTAGRAM = "Instagram", FACEBOOK = "Facebook", TIKTOK = "TikTok", GOOGLE = "Google",
        OTHER_REFERRAL = "Other referral", DIRECT = "Direct", MANUAL_DM = "Manual / DM", UNKNOWN = "Unknown";

    /** The fixed channel order of the breakdown. */
    static final List<String> CHANNELS = List.of(INSTAGRAM, FACEBOOK, TIKTOK, GOOGLE, OTHER_REFERRAL, DIRECT,
        MANUAL_DM, UNKNOWN);

    private static final Pattern UTM_SOURCE = Pattern.compile("[?&]utm_source=([^&#]*)", Pattern.CASE_INSENSITIVE);

    /**
     * The order's sales channel, first rule that applies:
     *   1. source_name shopify_draft_order → Manual / DM (a draft order made by the merchant);
     *   2. the order carries no source fields at all (GraphQL-imported orders) → Unknown;
     *   3. the referrer host: instagram → Instagram, facebook / fb / messenger → Facebook, tiktok →
     *      TikTok, google → Google (Android app referrers like android-app://com.instagram.android too);
     *   4. utm_source: ig / instagram → Instagram, fb or any start of "facebook" of 2+ letters
     *      (Meta truncates it: fa, fac, faceb…) → Facebook, tiktok / tt → TikTok, google → Google;
     *   5. another site referred the visit → Other referral (the store's own domains — the order
     *      status page's host and any *.myshopify.com — count as direct; slice 8: the SQL twin,
     *      analytics_channel (V149), feeds the generated orders.channel, which has no store row);
     *   6. Direct.
     * The referrer comes before utm_source: some stores put campaign names in utm_source (prod,
     * 2026-10-07: "jeans - summer collection | 6/23"), while the referrer is where the click happened.
     */
    static String channel(String sourceName, boolean hasSourceFields, String referringSite, String landingSite,
                          Set<String> ownHosts) {
        if ("shopify_draft_order".equalsIgnoreCase(trim(sourceName))) return MANUAL_DM;
        if (!hasSourceFields) return UNKNOWN;
        String host = referrerHost(referringSite);
        String byHost = platformOfHost(host);
        if (byHost != null) return byHost;
        String byUtm = platformOfUtm(utmSource(landingSite));
        if (byUtm != null) return byUtm;
        if (host != null && !host.isEmpty() && !ownHosts.contains(stripWww(host))
            && !host.endsWith(".myshopify.com")) {
            return OTHER_REFERRAL;
        }
        return DIRECT;
    }

    /** Lower-case host of a referrer URL; for android-app://pkg/… the package name. Null when none. */
    static String referrerHost(String url) {
        String u = trim(url);
        if (u == null || u.isEmpty()) return null;
        u = u.toLowerCase(Locale.ROOT);
        int scheme = u.indexOf("://");
        String rest = scheme >= 0 ? u.substring(scheme + 3) : u;
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        String host = rest.substring(0, end);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.isEmpty() ? null : host;
    }

    static String platformOfHost(String host) {
        if (host == null) return null;
        if (host.contains("instagram")) return INSTAGRAM;
        if (host.contains("facebook") || host.equals("fb.com") || host.endsWith(".fb.com") || host.equals("fb.me")
            || host.contains("messenger") || host.equals("com.facebook.katana") || host.equals("com.facebook.orca")) {
            return FACEBOOK;
        }
        if (host.contains("tiktok") || host.contains("musically")) return TIKTOK;
        if (host.equals("google") || host.startsWith("google.") || host.contains(".google.")
            || host.startsWith("com.google.android.googlequicksearchbox")) {
            return GOOGLE;
        }
        return null;
    }

    static String utmSource(String landingSite) {
        if (landingSite == null) return null;
        Matcher m = UTM_SOURCE.matcher(landingSite);
        if (!m.find()) return null;
        try {
            return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return m.group(1).trim().toLowerCase(Locale.ROOT);
        }
    }

    static String platformOfUtm(String utm) {
        if (utm == null || utm.isEmpty()) return null;
        if (utm.equals("ig") || utm.equals("instagram") || utm.startsWith("instagram")) return INSTAGRAM;
        if (utm.equals("fb") || (utm.length() >= 2 && "facebook".startsWith(utm)) || utm.startsWith("facebook")) {
            return FACEBOOK;
        }
        if (utm.equals("tt") || utm.startsWith("tiktok")) return TIKTOK;
        if (utm.startsWith("google")) return GOOGLE;
        return null;
    }

    /** The store's own hosts: the order status page's host and the .myshopify.com domain, without www. */
    static Set<String> ownHosts(String orderStatusUrl, String shopDomain) {
        Set<String> out = new LinkedHashSet<>();
        String h = referrerHost(orderStatusUrl);
        if (h != null) out.add(stripWww(h));
        if (shopDomain != null && !shopDomain.isBlank()) out.add(stripWww(shopDomain.trim().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    // ── payment ─────────────────────────────────────────────────────────────

    static final String COD = "COD", CARD = "Card", MANUAL = "Manual", MIXED = "Mixed", OTHER = "Other";
    static final List<String> PAYMENTS = List.of(COD, CARD, MANUAL, MIXED, OTHER);

    /**
     * The order's payment method from Shopify's gateway names: Cash on Delivery → COD; Paymob /
     * Kashier / "Pay with Card" / any card gateway → Card; "manual" → Manual. Two different kinds
     * on one order → Mixed; nothing recognisable (or no gateway) → Other.
     */
    static String payment(List<String> gateways) {
        Set<String> kinds = new LinkedHashSet<>();
        if (gateways != null) {
            for (String g : gateways) {
                String k = gatewayKind(g);
                if (k != null) kinds.add(k);
            }
        }
        if (kinds.isEmpty()) return OTHER;
        if (kinds.size() > 1) return MIXED;
        return kinds.iterator().next();
    }

    static String gatewayKind(String gateway) {
        String g = trim(gateway);
        if (g == null || g.isEmpty()) return null;
        g = g.toLowerCase(Locale.ROOT);
        if (g.contains("cash on delivery") || g.equals("cod") || g.contains("(cod)")) return COD;
        if (g.equals("manual")) return MANUAL;
        if (g.contains("gift")) return OTHER;          // a gift card is not a card payment
        if (g.contains("paymob") || g.contains("kashier") || g.contains("card") || g.contains("valu")
            || g.contains("fawry") || g.contains("instapay") || g.contains("wallet")) {
            return CARD;
        }
        return OTHER;
    }

    // ── delivery failure reason ─────────────────────────────────────────────

    static final String REFUSED = "Customer refused", UNREACHABLE = "Phone unreachable / not home",
        ADDRESS = "Wrong or incomplete address", POSTPONED = "Postponed / rescheduled",
        PRODUCT = "Product issue", OTHER_REASON = "Other";
    static final List<String> FAILURE_REASONS = List.of(REFUSED, UNREACHABLE, ADDRESS, POSTPONED, PRODUCT, OTHER_REASON);

    /**
     * Bosta's failure reason text (last_failure_reason, else the exception reason) as one of six
     * groups; null when there is no reason. Order matters: "not in the address" is the customer not
     * being home, checked before the address group.
     */
    static String failureReason(String reason) {
        String r = trim(reason);
        if (r == null || r.isEmpty()) return null;
        r = r.toLowerCase(Locale.ROOT);
        if (r.contains("refus") || r.contains("reject") || r.contains("doesn't want") || r.contains("does not want")
            || r.contains("customer cancel")) {
            return REFUSED;
        }
        if (r.contains("not in the address") || r.contains("not home") || r.contains("not available")
            || r.contains("phone") || r.contains("unreachable") || r.contains("not answer") || r.contains("no answer")
            || r.contains("closed") || r.contains("switched off")) {
            return UNREACHABLE;
        }
        if (r.contains("address") || r.contains("location") || r.contains("wrong area") || r.contains("out of zone")) {
            return ADDRESS;
        }
        if (r.contains("postpon") || r.contains("reschedul") || r.contains("another day") || r.contains("later date")) {
            return POSTPONED;
        }
        if (r.contains("product") || r.contains("damaged") || r.contains("wrong item") || r.contains("size")
            || r.contains("quality") || r.contains("open package")) {
            return PRODUCT;
        }
        return OTHER_REASON;
    }

    // ── size ────────────────────────────────────────────────────────────────

    /** Letter sizes in curve order. */
    static final List<String> LETTER_SIZES = List.of("XXS", "XS", "S", "M", "L", "XL", "XXL", "XXXL", "XXXXL");

    private static final Map<String, String> SIZE_ALIASES = Map.ofEntries(
        Map.entry("XXSMALL", "XXS"), Map.entry("XSMALL", "XS"), Map.entry("X-SMALL", "XS"), Map.entry("EXTRASMALL", "XS"),
        Map.entry("SMALL", "S"), Map.entry("SM", "S"), Map.entry("MEDIUM", "M"), Map.entry("MED", "M"),
        Map.entry("LARGE", "L"), Map.entry("LG", "L"), Map.entry("XLARGE", "XL"), Map.entry("X-LARGE", "XL"),
        Map.entry("EXTRALARGE", "XL"), Map.entry("2XL", "XXL"), Map.entry("2X", "XXL"), Map.entry("XXLARGE", "XXL"),
        Map.entry("3XL", "XXXL"), Map.entry("3X", "XXXL"), Map.entry("4XL", "XXXXL"), Map.entry("4X", "XXXXL"),
        Map.entry("2XS", "XXS"));

    private static final Pattern NUMERIC_SIZE = Pattern.compile("^(\\d{1,2})(?:[.,](5))?$");

    /**
     * A size value normalised: letter sizes upper-cased and aliased (2XL → XXL, Small → S); numeric
     * sizes 1–60 kept (shoe 37, waist 32, 37.5). Anything else (ranges like XL-XXL, "One size",
     * free text) → null = unparseable.
     */
    static String normaliseSize(String raw) {
        String s = trim(raw);
        if (s == null || s.isEmpty()) return null;
        s = s.toUpperCase(Locale.ROOT).replace(" ", "");
        if (LETTER_SIZES.contains(s)) return s;
        String alias = SIZE_ALIASES.get(s);
        if (alias != null) return alias;
        Matcher m = NUMERIC_SIZE.matcher(s);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > 60) return null;
            return m.group(2) == null ? String.valueOf(n) : n + ".5";
        }
        return null;
    }

    /** Curve order: letter sizes in their order, then numeric ascending. */
    static int sizeOrder(String normalised) {
        int i = LETTER_SIZES.indexOf(normalised);
        if (i >= 0) return i;
        try {
            return 100 + (int) Math.round(Double.parseDouble(normalised) * 2);
        } catch (NumberFormatException e) {
            return 1000;
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u200B-\\u200D\\uFEFF]", "").trim();
    }
}
