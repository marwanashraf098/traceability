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
        return " (CASE WHEN " + s + ".type_code = '30' THEN 'exchange' "
            + "WHEN " + s + ".type_code = '25' OR " + s + ".shipment_leg = 'return' THEN 'return' "
            + "WHEN " + s + ".type_code = '20' OR " + s + ".internal_state IN ('returning', 'returned') THEN 'failed' "
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
        return " COALESCE(" + s + ".cod_amount, " + s + ".raw_cod, 0) ";       // raw_cod: V149 generated column
    }

    /**
     * Joins the leg's order and store with the analytics floor (stores.orders_ingest_from, else
     * the analytics.floor-overrides day). Two parameters: override shop domains, override days
     * (text[] each). Use with {@link #POST_FLOOR}.
     */
    static String floorJoin(String s) {
        return " JOIN merchant_orders fo ON fo.id = " + s + ".order_id "
            + " JOIN stores fst ON fst.id = fo.store_id "
            + " LEFT JOIN unnest(?::text[], ?::text[]) AS fov(shop_domain, floor_day) "
            + "        ON fov.shop_domain = lower(fst.shop_domain) ";
    }

    static final String POST_FLOOR =
        " (COALESCE(fst.orders_ingest_from, (fov.floor_day::date::timestamp AT TIME ZONE 'Africa/Cairo')) IS NULL "
        + " OR fo.placed_at >= COALESCE(fst.orders_ingest_from, (fov.floor_day::date::timestamp AT TIME ZONE 'Africa/Cairo'))) ";

    /**
     * A ZERO cash cycle (B1, 2026-10-08): Bosta settled the leg for exactly 0 — e.g. a fee-free
     * Return to Origin, or a COD that equalled the fees — and no cashout names it. Nothing is owed,
     * and Bosta never sends a cashout for it (prod 2026-10-08: 9 of 10 zero cycles had none, while
     * every negative deposit got one, netted in a batch). Such a leg is never 'unresolved', never
     * delivered-not-paid, never awaiting payout, and the refresh job stops re-reading it.
     */
    static String zeroCycle(String s) {
        return " (" + s + ".cash_cycle_id IS NOT NULL AND " + s + ".deposited_amt = 0 AND "
            + s + ".cashout_txn_id IS NULL) ";
    }

    /**
     * Delivered, not paid (slice 3 — the ONE rule, used by /money/stuck and the order finance list):
     * the leg was deposited, its settlement was refreshed within 24 hours (so "no payout" is current
     * news, not a stale read), and two of the tenant's payout weekdays have passed since the deposit
     * — 14 days when the weekday isn't known. {@code now} and {@code weekday} are SQL expressions
     * (usually "?::timestamptz" / "?::int"); they are spliced in this order: now, weekday, now, now,
     * weekday.
     */
    static String deliveredNotPaid(String s, String now, String weekday) {
        return " (" + s + ".settlement_status = 'deposited' AND " + s + ".deposited_at IS NOT NULL AND NOT " + zeroCycle(s)
            + " AND " + s + ".settlement_refreshed_at > " + now + " - interval '24 hours' "
            + " AND CASE WHEN " + weekday + " IS NULL THEN " + s + ".deposited_at < " + now + " - interval '14 days' "
            + "          ELSE (SELECT COUNT(*) FROM generate_series((" + s + ".deposited_at AT TIME ZONE 'Africa/Cairo')::date + 1, "
            + "                                                    (" + now + " AT TIME ZONE 'Africa/Cairo')::date, "
            + "                                                    interval '1 day') g(d) "
            + "                WHERE EXTRACT(ISODOW FROM g.d) = " + weekday + ") >= 2 END) ";
    }

    /** Stuck with Bosta (slice 3): picked up, still moving, no status change for 7 days. */
    static String stuckWithBosta(String s, String lastChange, String now) {
        return " (" + s + ".internal_state IN ('with_courier', 'returning', 'exception') "
            + " AND " + lastChange + " < " + now + " - interval '7 days') ";
    }

    /** Booked, never picked up (slice 3): a forward leg still 'created' 7 days after it was booked. */
    static String neverPickedUp(String s, String now) {
        return " (" + s + ".shipment_leg = 'forward' AND " + s + ".internal_state = 'created' "
            + " AND " + s + ".created_at < " + now + " - interval '7 days') ";
    }

    /** The leg's last status change: its newest history row, else its creation. */
    static String lastChange(String s) {
        return " COALESCE((SELECT MAX(h.occurred_at) FROM shipment_status_history h WHERE h.shipment_id = "
            + s + ".id), " + s + ".created_at) ";
    }

    /**
     * Payout lag (B1, 2026-10-08 — ONE definition, /money/fees and the cash forecast): the MEDIAN of
     * payout day − Cairo delivery day over paid forward legs that were delivered and paid on or
     * after the delivery day. Each caller adds its own window on the payout day. Use with
     * {@link #floorJoin} / {@link #POST_FLOOR}.
     */
    static String medianPayoutLag(String s) {
        return " percentile_cont(0.5) WITHIN GROUP (ORDER BY " + s + ".cashout_date - ("
            + s + ".delivered_at AT TIME ZONE 'Africa/Cairo')::date) ";
    }

    static String payoutLagLeg(String s) {
        return " (" + s + ".shipment_leg = 'forward' AND " + s + ".settlement_status = 'paid' "
            + " AND " + s + ".cashout_date IS NOT NULL AND " + s + ".delivered_at IS NOT NULL "
            + " AND " + s + ".cashout_date >= (" + s + ".delivered_at AT TIME ZONE 'Africa/Cairo')::date) ";
    }

    /**
     * The tenant's payout weekday (ISO, 1 = Monday) — the weekday shared by the most distinct
     * cashout dates in the 90 days before now, when at least two dates agree; else NULL. Parameters:
     * tenant id, now (the caller's Clock — never the database clock, so tests can fix time).
     */
    static final String PAYOUT_WEEKDAY = """
        SELECT d FROM (
            SELECT EXTRACT(ISODOW FROM cashout_date)::int AS d, COUNT(DISTINCT cashout_date) AS n
            FROM shipments
            WHERE tenant_id = ? AND cashout_date IS NOT NULL
              AND cashout_date > (?::timestamptz AT TIME ZONE 'Africa/Cairo')::date - 90
            GROUP BY 1
        ) w
        WHERE n >= 2
        ORDER BY n DESC, d
        LIMIT 1
        """;
}
