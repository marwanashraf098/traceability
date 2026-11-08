package com.traceability.portal;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shopify-native returns S5a — the shared rules of "Traced creates the matching Return in Shopify".
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

    /** Request statuses after which nothing more happens on the Traced side (refund requests). */
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
