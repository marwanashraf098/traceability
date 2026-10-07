package com.traceability.analytics;

/**
 * Analytics slice 3 — the shared SQL definitions for Bosta money, used by both the refresh job
 * and the money endpoints so they can never disagree. All take a shipments alias.
 */
final class SettlementSql {

    private SettlementSql() {}

    // Every fragment is padded with spaces: callers splice them after text blocks, whose
    // trailing spaces Java strips ("SELECT """ + x would otherwise glue to "SELECTx").

    /** A leg Bosta has finished with — the only legs that carry fees / settlement. */
    static final String TERMINAL_STATES = " ('delivered', 'returned', 'lost', 'terminated') ";

    /** When the leg finished: delivered / returned stamps, else its last status change, else creation. */
    static String terminalAt(String s) {
        return " COALESCE(" + s + ".delivered_at, " + s + ".returned_at, "
            + "(SELECT MAX(h.occurred_at) FROM shipment_status_history h WHERE h.shipment_id = " + s + ".id), "
            + s + ".last_synced_at, " + s + ".created_at) ";
    }

    /**
     * The leg's money category:
     *   exchange — a type 30 leg;
     *   return   — a type 25 leg (customer return pickup) or any return leg;
     *   failed   — a forward leg that turned Return to Origin (type 20) or went returning / returned;
     *   shipping — every other forward leg.
     */
    static String legKind(String s) {
        return " (CASE WHEN " + s + ".raw->'type'->>'code' = '30' THEN 'exchange' "
            + "WHEN " + s + ".raw->'type'->>'code' = '25' OR " + s + ".shipment_leg = 'return' THEN 'return' "
            + "WHEN " + s + ".raw->'type'->>'code' = '20' OR " + s + ".internal_state IN ('returning', 'returned') THEN 'failed' "
            + "ELSE 'shipping' END) ";
    }

    /** The leg settled — Bosta's own figures exist. */
    static String settled(String s) {
        return " (" + s + ".settlement_status IN ('deposited', 'paid') AND " + s + ".bosta_fees IS NOT NULL) ";
    }

    /**
     * The leg's fee incl. VAT: Bosta's settled bosta_fees, else the quoted shipmentFees × 1.14
     * (Egyptian VAT on the quote — the settled bosta_fees = shipmentFees + vat on every settled
     * prod row). NULL when neither is known.
     */
    static String fee(String s) {
        return " (CASE WHEN " + settled(s) + " THEN " + s + ".bosta_fees "
            + "ELSE ROUND(" + s + ".shipment_fees_quoted * 1.14, 2) END) ";
    }

    /** The order's COD: the column, else the Bosta payload's cod. */
    static String cod(String s) {
        return " COALESCE(" + s + ".cod_amount, CASE WHEN (" + s + ".raw->>'cod') ~ '^-?[0-9]+(\\.[0-9]+)?$' "
            + "THEN (" + s + ".raw->>'cod')::numeric END, 0) ";
    }

    /**
     * Joins the leg's order and store with the analytics floor (stores.orders_ingest_from, else
     * the analytics.floor-overrides day). Two parameters: override shop domains, override days
     * (text[] each). Use with {@link #POST_FLOOR}.
     */
    static String floorJoin(String s) {
        return " JOIN orders fo ON fo.id = " + s + ".order_id "
            + " JOIN stores fst ON fst.id = fo.store_id "
            + " LEFT JOIN unnest(?::text[], ?::text[]) AS fov(shop_domain, floor_day) "
            + "        ON fov.shop_domain = lower(fst.shop_domain) ";
    }

    static final String POST_FLOOR =
        " (COALESCE(fst.orders_ingest_from, (fov.floor_day::date::timestamp AT TIME ZONE 'Africa/Cairo')) IS NULL "
        + " OR fo.placed_at >= COALESCE(fst.orders_ingest_from, (fov.floor_day::date::timestamp AT TIME ZONE 'Africa/Cairo'))) ";

    /**
     * The tenant's payout weekday (ISO, 1 = Monday) — the weekday shared by the most distinct
     * cashout dates in the last 90 days, when at least two dates agree; else NULL. One parameter:
     * tenant id.
     */
    static final String PAYOUT_WEEKDAY = """
        SELECT d FROM (
            SELECT EXTRACT(ISODOW FROM cashout_date)::int AS d, COUNT(DISTINCT cashout_date) AS n
            FROM shipments
            WHERE tenant_id = ? AND cashout_date IS NOT NULL
              AND cashout_date > (now() AT TIME ZONE 'Africa/Cairo')::date - 90
            GROUP BY 1
        ) w
        WHERE n >= 2
        ORDER BY n DESC, d
        LIMIT 1
        """;
}
