package com.traceability.integrations.shopify;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Find your store" — the ONE place a merchant-typed store address becomes a shop domain. Used by
 * {@code POST /api/v1/shopify/resolve-store} and by {@code /oauth/initiate} (which normalises before its
 * same-shop rule, so a capitalised address can no longer break the callback's shop match).
 *
 * Accepts:
 *   (a) any {@code <handle>.myshopify.com} address — scheme, capitals, whitespace (anywhere), invisible
 *       characters (zero-width, bidi marks, BOM), a trailing dot / slash, a path or query;
 *   (b) a Shopify admin link — {@code admin.shopify.com/store/<handle>/…}, or the legacy
 *       {@code <handle>.myshopify.com/admin…}.
 * Anything else — another website, credentials or a port in the address, junk — is NOT a Shopify address.
 *
 * Output is always {@code <handle>.myshopify.com}, lowercase, {@code <handle>} a single DNS label
 * ({@code [a-z0-9]}, inner hyphens, ≤ 63 chars). Pure string work: never a network call.
 */
public final class ShopDomainNormalizer {

    private ShopDomainNormalizer() {}

    public enum Source { MYSHOPIFY, ADMIN_LINK;
        public String wire() { return this == MYSHOPIFY ? "myshopify" : "admin_link"; }
    }

    /** A recognised store: {@code shopDomain} is {@code <handle>.myshopify.com}. */
    public record Result(String shopDomain, Source source) {}

    /** The input is not a Shopify store address. */
    public static final class NotShopifyAddress extends RuntimeException {
        public NotShopifyAddress() { super("Not a Shopify store address"); }
    }

    static final String HANDLE = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    /** The validated shop-domain shape — the only host Traced ever builds a store URL from. */
    public static final Pattern SHOP_DOMAIN = Pattern.compile("^" + HANDLE + "\\.myshopify\\.com$");
    private static final Pattern MYSHOPIFY_HOST = Pattern.compile("^(" + HANDLE + ")\\.myshopify\\.com$");
    private static final Pattern ADMIN_STORE_PATH = Pattern.compile("^/store/(" + HANDLE + ")(?:[/?#].*)?$");
    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://");
    // Format characters (zero-width space/joiners, bidi marks, BOM, soft hyphen …) and every kind of space.
    private static final Pattern INVISIBLE_OR_SPACE = Pattern.compile("[\\p{Cf}\\p{Z}\\s\\u00AD]");

    public static Result normalize(String input) {
        if (input == null) throw new NotShopifyAddress();
        String s = INVISIBLE_OR_SPACE.matcher(input).replaceAll("").toLowerCase(Locale.ROOT);
        s = SCHEME.matcher(s).replaceFirst("");
        if (s.isEmpty()) throw new NotShopifyAddress();

        int cut = firstOf(s, '/', '?', '#');
        String host = cut < 0 ? s : s.substring(0, cut);
        String rest = cut < 0 ? "" : s.substring(cut);
        if (host.contains("@") || host.contains(":")) throw new NotShopifyAddress();   // credentials / port
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);

        Matcher m = MYSHOPIFY_HOST.matcher(host);
        if (m.matches()) {
            boolean adminPath = rest.equals("/admin") || rest.startsWith("/admin/") || rest.startsWith("/admin?");
            return new Result(m.group(1) + ".myshopify.com", adminPath ? Source.ADMIN_LINK : Source.MYSHOPIFY);
        }
        if (host.equals("admin.shopify.com")) {
            Matcher p = ADMIN_STORE_PATH.matcher(rest);
            if (p.matches()) return new Result(p.group(1) + ".myshopify.com", Source.ADMIN_LINK);
        }
        throw new NotShopifyAddress();
    }

    /** True only for an already-normalised {@code <handle>.myshopify.com}. */
    public static boolean isShopDomain(String s) {
        return s != null && SHOP_DOMAIN.matcher(s).matches();
    }

    private static int firstOf(String s, char... cs) {
        int best = -1;
        for (char c : cs) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }
}
