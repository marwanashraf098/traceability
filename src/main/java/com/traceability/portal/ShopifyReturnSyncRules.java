package com.traceability.portal;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shopify-native returns S5a / S5b — the shared rules of "Traced creates the matching Return in Shopify".
 *
 * <p>{@link #tracedCreatedSql}: THE rule for "this mirrored Shopify return was created by Traced" — its GID is a
 * request's {@code shopify_return_gid}, or one of its lines carries a request's marker ("Traced RR-XXXXXX" in the
 * return reason note; covers a create whose answer never reached Traced). Such a return is never treated as
 * Shopify-native: no S3b shadow, no S4 expected-back line / intake alerts, not an S2 duplicate, not a Returns list
 * case S. It still counts in {@code shopify_restocked_units} — a merchant restocking it by hand in Shopify must still
 * be caught by the S1b guard.
 */
public final class ShopifyReturnSyncRules {

    private ShopifyReturnSyncRules() {}

    /** The marker Traced puts in every return line note it creates: "Traced " + the request reference. */
    public static String marker(String reference) {
        return "Traced " + reference;
    }

    /** A note carrying any request marker (SQL regex, POSIX). */
    static final String MARKER_REGEX = "Traced RR-[A-Z0-9]{6}";
    static final Pattern MARKER = Pattern.compile("Traced (RR-[A-Z0-9]{6})");

    /**
     * True when the shopify_returns row aliased {@code r} was created by Traced. Binds nothing; needs {@code r} in
     * scope (tenant_id, return_gid, id).
     */
    public static String tracedCreatedSql(String r) {
        return "(EXISTS (SELECT 1 FROM return_requests tcq WHERE tcq.tenant_id = " + r + ".tenant_id " +
               "          AND tcq.shopify_return_gid = " + r + ".return_gid) " +
               " OR EXISTS (SELECT 1 FROM shopify_return_lines tcl WHERE tcl.return_id = " + r + ".id " +
               "          AND tcl.tenant_id = " + r + ".tenant_id AND tcl.reason_note ~ '" + MARKER_REGEX + "'))";
    }

    /**
     * The request types that get a Shopify return: refunds (S5a) and exchanges (S5b, option A, approved 2026-11-08) —
     * an exchange goes to Shopify as a RETURN ONLY (the item coming back), never with an exchange line: the
     * replacement is Traced's own internal order and its stock leaves through Step 5a's −1.
     */
    public static final java.util.Set<String> SYNCED_TYPES = java.util.Set.of("refund", "exchange");

    /** SQL list of {@link #SYNCED_TYPES}. */
    static final String SYNCED_TYPES_SQL = "('refund', 'exchange')";

    /** Shopify's limit on a return line's returnReasonNote. */
    public static final int NOTE_LIMIT = 255;

    /** The last part of an exchange line note (2026-04's ReturnInput has no return-level note to carry it). */
    static final String EXCHANGE_WARNING = "Exchanged via Traced — don't refund in Shopify";

    /**
     * S5b — the line note of an exchange's Shopify return: "Traced RR-XXXXXX · exchanged for <product / variant>",
     * then "collect EGP X via Bosta" (or "via courier" for a store's own courier) when the frozen collect is &gt; 0,
     * then {@link #EXCHANGE_WARNING}. At most {@link #NOTE_LIMIT} characters (code points — never a split
     * character): the marker always stays whole, the warning is dropped first, then the product name is shortened
     * with "…"; the collect amount is never cut.
     */
    public static String exchangeNote(String reference, String replacementLabel, java.math.BigDecimal collect,
                                      boolean ownCourier) {
        return exchangeNote(reference, replacementLabel, collect, null, ownCourier);
    }

    /**
     * As above, with what the store owes the customer (the frozen {@code customer_owed} of a cheaper replacement):
     * "refund EGP X to customer" in place of the collect part. Only a positive amount is ever written — 0, none or
     * a negative value writes no money part at all (never "collect EGP 0", never a negative amount).
     */
    public static String exchangeNote(String reference, String replacementLabel, java.math.BigDecimal collect,
                                      java.math.BigDecimal owed, boolean ownCourier) {
        String marker = marker(reference);
        String sep = " · ";
        String cod = collect != null && collect.signum() > 0
            ? "collect EGP " + collect.stripTrailingZeros().toPlainString() + (ownCourier ? " via courier" : " via Bosta")
            : owed != null && owed.signum() > 0
                ? "refund EGP " + owed.stripTrailingZeros().toPlainString() + " to customer"
                : null;
        String product = "exchanged for " + (replacementLabel == null || replacementLabel.isBlank()
            ? "another item" : replacementLabel.strip());
        String tail = cod == null ? "" : sep + cod;
        String full = marker + sep + product + tail + sep + EXCHANGE_WARNING;
        if (cps(full) <= NOTE_LIMIT) return full;
        String noWarning = marker + sep + product + tail;
        if (cps(noWarning) <= NOTE_LIMIT) return noWarning;
        int room = NOTE_LIMIT - cps(marker + sep) - cps(tail) - 1;   // 1 for "…"
        return marker + sep + (room > 0 ? firstCodePoints(product, room) : "") + "…" + tail;
    }

    /** {@code s} cut to at most {@code max} code points (never inside a surrogate pair); null stays null. */
    public static String truncate(String s, int max) {
        return s == null || cps(s) <= max ? s : firstCodePoints(s, max);
    }

    private static int cps(String s) {
        return s.codePointCount(0, s.length());
    }

    private static String firstCodePoints(String s, int n) {
        return s.substring(0, s.offsetByCodePoints(0, Math.min(n, cps(s))));
    }

    /**
     * Item states that keep a request's Shopify return from being closed / cancelled: still expected, or back but not
     * graded yet. Process always comes first; the end waits for it.
     */
    static final java.util.Set<String> END_PENDING_ITEM = java.util.Set.of("awaiting", "arrived");

    /** Request statuses after which nothing more happens on the Traced side (refunded / closed / exchanged). */
    static final java.util.Set<String> TERMINAL = java.util.Set.of("refunded", "closed", "exchanged");

    /** At most this many attempts for a write that failed DEFINITELY (nothing applied). */
    public static final int MAX_ATTEMPTS = 5;

    /** A claim still 'pending' after this long had no recorded answer → failed_ambiguous. */
    static final int STUCK_PENDING_MINUTES = 15;

    /** The Traced reason label sent in the line note (English; never the customer's own text). */
    static final Map<String, String> REASON_LABELS = Map.of(
        "wrong_size", "Wrong size",
        "damaged", "Damaged",
        "not_as_pictured", "Not as pictured",
        "wrong_item", "Wrong item",
        "changed_mind", "Changed mind",
        "other", "Other");
}
