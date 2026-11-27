package com.traceability.analytics;

/**
 * THE analytics cancelled-order rule (decision (c), approved 2026-11-27) — every analytics page, Booked and
 * Realized, reads these two fragments; never re-state the rule inline.
 *
 * An order is CANCELLED when Traced's status is 'cancelled' or Shopify cancelled it (orders.raw_cancelled).
 * A cancelled order is left out of analytics ONLY when it was cancelled BEFORE Bosta picked it up: no forward
 * Bosta leg of it (a Send — never a customer return pickup 25 or an exchange trip 30) was ever picked up
 * (collected_from_business_at, or a with_courier / returning / returned / delivered / lost history row), or
 * Shopify's cancelled_at is earlier than the first pickup. A cancelled order Bosta DID pick up first — the
 * refused → Return to Origin → cancelled-in-Shopify order — counts: booked (at the units its fulfillments
 * carried, {@link #shippedQtySql}), its outcome the deciding leg's (refused), its Bosta fees in Profit;
 * never realized revenue (it was not delivered) and never units sold ({@code revived} lines, left out of
 * every "sold" figure by its caller).
 *
 * orders.status and the Orders page are not touched (a separate bug). Order ids are unique across tenants and
 * every read runs under the caller's RLS, so the fragments bind no parameter (like
 * {@link SalesAnalyticsService#liveGenericLeg}). Padded with spaces for text-block splices.
 */
final class CancelledOrders {

    private CancelledOrders() {}

    /** SQL true when the order {@code o} (an orders / merchant_orders alias) is cancelled. */
    static String cancelledSql(String o) {
        return " (" + o + ".status = 'cancelled'::order_status OR " + o + ".raw_cancelled) ";
    }

    /**
     * SQL true when the order {@code o} counts in analytics: not cancelled, or cancelled only after Bosta picked
     * it up. Shopify's cancelled_at (raw — read only for a cancelled order that Bosta picked up) unknown =
     * not before the pickup.
     */
    static String countsSql(String o) {
        return " (CASE WHEN NOT " + cancelledSql(o) + " THEN true ELSE COALESCE(("
            + " SELECT analytics_ts(" + o + ".raw ->> 'cancelled_at') IS NULL"
            + "        OR analytics_ts(" + o + ".raw ->> 'cancelled_at') >= MIN(pk.at)"
            + " FROM shipments cs"
            + " CROSS JOIN LATERAL (SELECT LEAST(cs.collected_from_business_at,"
            + "     (SELECT MIN(ch.occurred_at) FROM shipment_status_history ch WHERE ch.shipment_id = cs.id"
            + "      AND ch.internal_state IN ('with_courier', 'returning', 'returned', 'delivered', 'lost'))) AS at) pk"
            + " WHERE cs.order_id = " + o + ".id AND cs.provider = 'bosta' AND cs.shipment_leg = 'forward'"
            + "   AND COALESCE(cs.type_code, '10') NOT IN ('25', '30')"
            + " HAVING MIN(pk.at) IS NOT NULL), false) END) ";
    }

    /**
     * A counted cancelled order's line quantity: Shopify zeroes current_quantity on cancel, so the units are
     * the ones its fulfillments (not cancelled / error / failure) carried for the line ({@code oi} =
     * order_items alias, matched on line_key); an order with no such fulfillment at all falls back to
     * order_items.quantity. Raw is read only for these orders.
     */
    static String shippedQtySql(String o, String oi) {
        return " (SELECT CASE WHEN bool_or(true) FILTER (WHERE fl.fid IS NOT NULL) THEN"
            + "          COALESCE(SUM(analytics_int(fl.li ->> 'quantity')) FILTER (WHERE fl.li ->> 'id' = " + oi + ".line_key), 0)"
            + "        ELSE " + oi + ".quantity END"
            + "  FROM (SELECT f ->> 'id' AS fid, li"
            + "        FROM jsonb_array_elements(CASE WHEN jsonb_typeof(" + o + ".raw -> 'fulfillments') = 'array'"
            + "                                       THEN " + o + ".raw -> 'fulfillments' ELSE '[]'::jsonb END) f"
            + "        CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(f -> 'line_items') = 'array'"
            + "                                       THEN f -> 'line_items' ELSE '[]'::jsonb END) li"
            + "        WHERE COALESCE(f ->> 'status', '') NOT IN ('cancelled', 'error', 'failure')"
            + "        UNION ALL SELECT NULL, NULL) fl) ";
    }
}
