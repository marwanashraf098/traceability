package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Finding 2 (2026-11-04): a Bosta exchange (type 30) made for a SHOPIFY-NATIVE exchange — a return app or Shopify
 * self-serve created the exchange in Shopify (returnCreate / returnProcess), and the merchant's Bosta plugin or
 * dashboard booked the courier. Shopify already counts the replacement unit on its own exchange line, so Step 5a's
 * exchange_dispatch −1 would take the same unit out of Shopify a second time (prod: BROEK, AWB 7756985263,
 * businessReference "BRK-46369-EG:BRK-46369-EG-R1").
 *
 * ONE definition, used by every exchange_dispatch path (the live trigger, the re-push, and the send itself — which
 * covers claims held while the store needed a reconnect and claims flipped by ArrivalRevisit). Such a claim is
 * recorded 'skipped' with {@link #SKIP_REASON} — the correct outcome, no alert.
 *
 * Native when the piece's internal exchange order is named by an exchanges row that Traced did not book
 * (return_request_id NULL) and that no exchange request names, AND either
 *   (a) the Bosta businessReference's part after ':' ends in -R&lt;digits&gt; (Shopify's Return name), or
 *   (b) the exchange's matched original is a Shopify order with a same-variant line Traced never counted —
 *       stored quantity 0 with Shopify current_quantity &gt; 0 ("Added after shipping": Shopify's exchange line).
 * Never native: Traced portal exchanges (return_request_id set) and Lookup / generic exchanges (named by
 * return_requests.replacement_order_id; they have no exchanges row).
 */
public final class ShopifyNativeExchange {

    private ShopifyNativeExchange() {}

    public static final String SKIP_REASON = "shopify_native_exchange";

    /** (a): the businessReference's last ':'-part ends in Shopify's Return name suffix -R&lt;digits&gt;. */
    static final String RETURN_NAME_REFERENCE_REGEX = ":[^:]*-R[0-9]+$";

    /**
     * The exchanges row (alias e) is a Shopify-native exchange for the replacement variant {@code variantExpr}.
     * Needs e and the variant expression in scope.
     */
    static String nativeExchangeSql(String variantExpr) {
        return "(e.return_request_id IS NULL " +
               " AND NOT EXISTS (SELECT 1 FROM return_requests rr WHERE rr.replacement_order_id = e.outbound_order_id " +
               "                   AND rr.tenant_id = e.tenant_id) " +
               " AND (COALESCE(e.raw->>'businessReference', '') ~ '" + RETURN_NAME_REFERENCE_REGEX + "' " +
               "   OR EXISTS (SELECT 1 FROM merchant_orders mo " +
               "              JOIN order_items moi ON moi.order_id = mo.id AND moi.tenant_id = mo.tenant_id " +
               "              WHERE mo.id = e.matched_order_id AND mo.tenant_id = e.tenant_id " +
               "                AND mo.external_id LIKE 'gid://shopify/Order/%' " +
               "                AND moi.variant_id = " + variantExpr +
               "                AND moi.quantity = 0 AND COALESCE(moi.current_qty, 0) > 0)))";
    }

    /**
     * Is this piece the replacement of a Shopify-native exchange? {@code includeReleased}: the re-push and the send
     * also look at a released allocation (the replacement may have come back since it left).
     */
    static boolean isReplacement(JdbcTemplate jdbc, UUID tenantId, String pieceId, boolean includeReleased) {
        String statuses = includeReleased ? "('active', 'packed', 'released')" : "('active', 'packed')";
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pieces p " +
            "  JOIN allocations a ON a.piece_id = p.id AND a.tenant_id = p.tenant_id " +
            "  JOIN order_items oi ON oi.id = a.order_item_id AND oi.tenant_id = p.tenant_id " +
            "  JOIN merchant_orders o ON o.id = oi.order_id AND o.tenant_id = p.tenant_id " +
            "  JOIN exchanges e ON e.outbound_order_id = o.id AND e.tenant_id = o.tenant_id " +
            "  WHERE p.id = ? AND p.tenant_id = ? AND a.status IN " + statuses +
            "    AND o.external_id LIKE 'internal:exchange:%' " +
            "    AND " + nativeExchangeSql("p.variant_id") + ")",
            Boolean.class, pieceId, tenantId));
    }
}
