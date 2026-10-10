package com.traceability.analytics;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Analytics — sales by variant / product (slice 1), delivery outcomes, customer returns and cities
 * (slice 2), counted by ORDER placed_at (cohort): an order's outcome and returns belong to the
 * period it was placed in, not the delivery date. Live aggregation as app_user under RLS for the
 * caller's tenant (every statement is also tenant_id-bound). No stored counters.
 *
 * A SOLD LINE ({@link #soldLines}) is an order line whose order is
 * <ul>
 *   <li>placed on/after its store's analytics floor — {@code stores.orders_ingest_from}, else a
 *       configured {@link AnalyticsFloorOverrides} day, else no floor;</li>
 *   <li>not cancelled — {@code orders.status <> 'cancelled'} AND {@code raw->>'cancelled_at'} IS NULL;</li>
 *   <li>not an internal exchange order ({@code external_id LIKE 'internal:exchange:%'} — a replacement
 *       is not a sale);</li>
 * </ul>
 * with qty = {@code raw->>'current_quantity'} (Shopify's quantity after edits / removals / refunds)
 * falling back to {@code order_items.quantity}, plus the units refunded in Shopify AFTER the line
 * was fulfilled (see the rf CTE); lines with qty ≤ 0 are not sold.
 *
 * Unit net price = {@code raw->>'price'} − Σ {@code raw->'discount_allocations'[].amount} / the
 * line's ORIGINAL quantity ({@code raw->>'quantity'}): Shopify allocates a line's discount over the
 * units originally ordered, so an edit that removes a unit doesn't concentrate the whole discount on
 * the units left. (Same rule as RefundSuggestionService's stored-raw source.) Order-level amounts
 * ({@code orders.total_discounts}, which includes shipping discounts) are never spread over lines.
 * A line with no stored REST price (GraphQL-imported, internal) is priced at {@code variants.price}
 * and counted in approximateLines.
 *
 * Outcomes ({@link #ORDER_OUTCOMES}) and customer returns ({@link #LINE_RETURNS}) are documented
 * where they're defined. Wijha orders are sales; their outcome is the wijha bucket.
 */
@Service
public class SalesAnalyticsService {

    public enum Sort { UNITS, REVENUE }

    public static final int DEFAULT_PRODUCT_LIMIT = 10;
    public static final int MAX_PRODUCT_LIMIT = 100;

    // ── Response records ────────────────────────────────────────────────────

    /**
     * Outcome units (deliveredUnits … otherTerminalUnits) sum to soldUnits. returnedUnits counts
     * customer returns on DELIVERED orders only; netSoldUnits = delivered − returned;
     * returnRate = returned / delivered and refusalRate = refused / (delivered + refused + other
     * terminal), both null when the denominator is 0; returnedRevenue = returned units × the line's net unit price.
     */
    public record VariantSales(UUID variantId, UUID productId, String productTitle, String variantTitle,
                               String sku, String imageUrl, long soldUnits, BigDecimal grossRevenue,
                               long approximateLines, Instant lastSoldAt,
                               long deliveredUnits, long refusedUnits, long inTransitUnits, long wijhaUnits,
                               long notShippedUnits, long otherTerminalUnits, long returnedUnits,
                               long netSoldUnits, BigDecimal returnRate, BigDecimal refusalRate,
                               BigDecimal deliveredRevenue, BigDecimal returnedRevenue, BigDecimal netRevenue) {}

    /**
     * Slice-1 totals plus the same outcome / return figures, the delivered / refused / Wijha order
     * counts, returnsOnUndeliveredOrders (returned units on orders whose outcome isn't delivered —
     * not counted in returnedUnits) and unverifiedNoRestockLines (Shopify 'no_restock' refund lines
     * on orders with no stored fulfillments, so the before/after-fulfillment rule couldn't run).
     */
    public record Totals(long soldUnits, BigDecimal grossRevenue, long orders, long approximateLines,
                         long deliveredUnits, long refusedUnits, long inTransitUnits, long wijhaUnits,
                         long notShippedUnits, long otherTerminalUnits, long returnedUnits,
                         long netSoldUnits, BigDecimal returnRate, BigDecimal refusalRate,
                         BigDecimal deliveredRevenue, BigDecimal returnedRevenue, BigDecimal netRevenue,
                         long deliveredOrders, long refusedOrders, long wijhaOrders,
                         long returnsOnUndeliveredOrders, long unverifiedNoRestockLines) {}

    public record VariantSalesResponse(AnalyticsPeriod.Range range, Totals totals, List<VariantSales> variants) {}

    public record TopVariant(UUID variantId, String variantTitle, long soldUnits) {}

    public record ProductSales(UUID productId, String title, String imageUrl, long soldUnits,
                               BigDecimal grossRevenue, int variantCount, List<TopVariant> topVariants) {}

    public record ProductSalesResponse(AnalyticsPeriod.Range range, String sort, List<ProductSales> products) {}

    /**
     * successRate = delivered / (delivered + refused + other terminal), null when all are 0. cityId = Bosta's
     * city._id (null for the "Unknown" row); nameAr falls back to nameEn.
     */
    public record CitySales(String cityId, String nameEn, String nameAr, long orders, long deliveredOrders,
                            long refusedOrders, long inTransitOrders, long otherTerminalOrders,
                            BigDecimal successRate) {}

    public record CitySalesResponse(AnalyticsPeriod.Range range, List<CitySales> cities) {}

    /** The outcome columns of one VARIANTS_SQL row. */
    private record Outcome(long delivered, long refused, long inTransit, long wijha, long notShipped,
                           long otherTerminal, long returned, BigDecimal deliveredRevenue,
                           BigDecimal returnedRevenue, long returnsOnUndelivered, long deliveredOrders,
                           long refusedOrders, long wijhaOrders, long unverifiedNoRestock) {
        static final Outcome NONE = new Outcome(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0);
    }

    // ── SQL ─────────────────────────────────────────────────────────────────

    /*
     * Shared CTEs. Parameters, in order: period start, period end, override shop domains (text[]),
     * override days (text[]), tenant id (stores), tenant id (order_items), tenant id (orders).
     * Slice 8: no raw jsonb is read here — every field comes from the V149 generated columns
     * (orders.raw_cancelled / refund_lines / discount_types, order_items.unit_price / original_qty /
     * current_qty / line_discount / alloc_amounts / alloc_indexes / line_key), which Postgres keeps
     * in step with raw on every write. Only variants.price (the fallback for a line with no raw
     * price) is read from another table.
     *   bounds  — the period, [p_start, p_end).
     *   floors  — per store: orders_ingest_from, else the override day at 00:00 Cairo, else NULL.
     *   all_lines — every cohort line, qty ≤ 0 and raw-cancelled included (only the unverified
     *             no_restock count reads them); lines = all_lines with qty > 0 and not cancelled in
     *             raw — the SOLD lines every figure is built on.
     *   lines   — sold lines, one pass. ALL TIME post-floor when allTime (the variants endpoint needs
     *             lastSoldAt), else only lines placed in the period. in_period flags the period's
     *             lines; unit_price / revenue are computed for those only (NULL otherwise).
     *             MATERIALIZED so the grouping works on these narrow rows.
     *   gross / disc_code / disc_auto (slice 5, period lines only) — qty × the pre-discount unit price
     *             (raw price, else variants.price), and the part of the line's discount allocations that
     *             came from a discount code / an automatic discount (discount_types[index]), scaled like
     *             unit_price (÷ the original quantity). gross − revenue is the line's whole discount;
     *             what is neither code nor automatic (manual / draft-order) is the remainder.
     *   rf      — the line's Shopify refunds (slice 2, approved 2026-10-06), precomputed per Shopify
     *             line id in orders.refund_lines (analytics_refund_lines, V149). Shopify lowers
     *             current_quantity when a unit is refunded, so a unit sold, delivered and then
     *             refunded would vanish from sales. added_back = refunded units with restock_type
     *             'return' or 'no_restock' whose refund was created AFTER a (non-cancelled)
     *             fulfillment containing the line; a refund before that is a pre-ship
     *             cancellation / edit and stays out, and 'cancel' is always out. Order raw with no
     *             'fulfillments' key: only 'return' is added back, and the line's 'no_restock'
     *             refund lines are counted in unverified_no_restock. shopify_returned = the added-back
     *             'return' units (returns source 3); a 'no_restock' refund is a sale but not a return.
     */
    static String soldLines(boolean allTime) {
        return """
            WITH bounds AS (
                SELECT ?::timestamptz AS p_start, ?::timestamptz AS p_end
            ),
            overrides AS (
                SELECT * FROM unnest(?::text[], ?::text[]) AS o(shop_domain, floor_day)
            ),
            floors AS (
                SELECT s.id AS store_id,
                       COALESCE(s.orders_ingest_from,
                                (ov.floor_day::date::timestamp AT TIME ZONE 'Africa/Cairo')) AS floor_at
                FROM stores s
                LEFT JOIN overrides ov ON ov.shop_domain = lower(s.shop_domain)
                WHERE s.tenant_id = ?
            ),
            all_lines AS MATERIALIZED (
                SELECT oi.id AS order_item_id, oi.variant_id, oi.order_id, o.placed_at, q.qty,
                       o.shipping_carrier_class AS carrier_class,
                       (o.placed_at >= b.p_start AND o.placed_at < b.p_end) AS in_period,
                       (q.raw_price IS NULL) AS approximate,
                       rf.shopify_returned, rf.unverified_no_restock,
                       NOT o.raw_cancelled AS not_cancelled,
                       up.unit_price,
                       q.qty * up.unit_price AS revenue,
                       dc.gross, dc.disc_code, dc.disc_auto
                FROM order_items oi
                JOIN merchant_orders o   ON o.id = oi.order_id
                JOIN floors f   ON f.store_id = o.store_id
                JOIN variants v ON v.id = oi.variant_id
                CROSS JOIN bounds b
                CROSS JOIN LATERAL (
                    SELECT COALESCE((o.refund_lines -> oi.line_key ->> 0)::int, 0) AS added_back,
                           COALESCE((o.refund_lines -> oi.line_key ->> 1)::int, 0) AS shopify_returned,
                           COALESCE((o.refund_lines -> oi.line_key ->> 2)::int, 0) AS unverified_no_restock
                ) rf
                CROSS JOIN LATERAL (
                    SELECT COALESCE(oi.current_qty, oi.quantity) + rf.added_back AS qty,
                           COALESCE(oi.original_qty, oi.quantity)                AS original_qty,
                           oi.unit_price                                         AS raw_price
                ) q
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN o.placed_at >= b.p_start AND o.placed_at < b.p_end THEN
                               CASE
                                   WHEN q.raw_price IS NULL THEN COALESCE(v.price, 0)
                                   ELSE q.raw_price - oi.line_discount / NULLIF(GREATEST(q.original_qty, q.qty), 0)
                               END
                           END AS unit_price
                ) up
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN o.placed_at >= b.p_start AND o.placed_at < b.p_end
                                THEN q.qty * COALESCE(q.raw_price, v.price, 0) END AS gross,
                           CASE WHEN o.placed_at >= b.p_start AND o.placed_at < b.p_end THEN
                               COALESCE(q.qty * a.code_amt / NULLIF(GREATEST(q.original_qty, q.qty), 0), 0) END AS disc_code,
                           CASE WHEN o.placed_at >= b.p_start AND o.placed_at < b.p_end THEN
                               COALESCE(q.qty * a.auto_amt / NULLIF(GREATEST(q.original_qty, q.qty), 0), 0) END AS disc_auto
                    FROM (
                        SELECT SUM(al.amt) FILTER (WHERE o.discount_types[al.idx + 1] = 'discount_code') AS code_amt,
                               SUM(al.amt) FILTER (WHERE o.discount_types[al.idx + 1] = 'automatic')     AS auto_amt
                        FROM unnest(CASE WHEN q.raw_price IS NOT NULL THEN oi.alloc_amounts END,
                                    CASE WHEN q.raw_price IS NOT NULL THEN oi.alloc_indexes END) AS al(amt, idx)
                    ) a
                ) dc
                WHERE oi.tenant_id = ?
                  AND o.tenant_id = ?
                  AND (f.floor_at IS NULL OR o.placed_at >= f.floor_at)
                  AND o.status <> 'cancelled'::order_status
                  AND o.external_id NOT LIKE 'internal:exchange:%%'
                  %s
            ),
            lines AS (
                SELECT * FROM all_lines WHERE qty > 0 AND not_cancelled
            )
            """.formatted(allTime ? "" : "AND o.placed_at >= b.p_start AND o.placed_at < b.p_end");
    }

    /*
     * Order outcomes (slice 2) — appended to soldLines(false). Two more parameters: tenant id
     * (shipments), tenant id (status history).
     *   leg (LATERAL, per order, via shipments_order_idx) — the order's DECIDING forward leg:
     *           shipment_leg 'forward', never a type 25 (customer return pickup) or 30 (exchange)
     *           leg. An order has at most one forward leg that isn't terminated/cancelled
     *           (ux_active_forward_shipment_per_order); that one decides. With none, the latest
     *           terminated/cancelled leg decides (created_at, then id).
     *   h (LATERAL, per leg) — first delivered / first returning-or-returned history row.
     *   Index lookups per order, not CTE-to-CTE joins: the planner estimates the jsonb-filtered
     *   cohort at ~1 row and picks nested loops over CTE scans (prod EXPLAIN: 3.2 s on Femine).
     *   order_outcomes — one outcome per order, in this order:
     *     wijha / not_shipped — no deciding leg; or the leg is terminated/cancelled and either the
     *                           order shipped with another known carrier (Wijha) or Bosta never
     *                           picked it up (no collected_from_business_at, no with_courier /
     *                           returning / returned / delivered / lost history — 2026-10-08):
     *                           wijha when orders.shipping_carrier_class = 'other_known', else
     *                           not_shipped
     *     delivered       — the leg's state is delivered
     *     refused         — the leg turned Return to Origin (type code 20), is returning/returned, or
     *                       its history went returning/returned before any delivered
     *     delivered       — history shows delivered (current state moved on, e.g. exception)
     *     other_terminal  — lost, or terminated / cancelled after pickup
     *     in_transit      — created / with_courier / exception
     *   bosta_decides = a Bosta leg decided the outcome (the cities endpoint counts only those).
     */
    /**
     * THE outcome of a deciding Bosta forward leg (alias leg: internal_state, type_code) given its
     * history (alias h: first_delivered, first_return, picked_up) — WHEN branches of a CASE, used by
     * ORDER_OUTCOMES and by the money pipeline's city rates, so both read one rule. Rates built on it
     * (approved 2026-10-08): successRate = delivered ÷ (delivered + failed), refusalRate = refused ÷
     * (delivered + failed), failed = refused + other_terminal.
     * A leg cancelled / terminated BEFORE pickup (no collected_from_business_at, and no history of
     * Bosta holding the parcel — with_courier / returning / returned / delivered / lost) never left:
     * not_shipped, not a failure (approved 2026-10-08). Cancelled / terminated after pickup stays
     * other_terminal (failed).
     */
    static final String LEG_OUTCOME_WHENS = """
                       WHEN leg.internal_state IN ('terminated', 'cancelled')
                            AND leg.collected_from_business_at IS NULL
                            AND NOT COALESCE(h.picked_up, false) THEN 'not_shipped'
                       WHEN leg.internal_state = 'delivered' THEN 'delivered'
                       WHEN leg.type_code = '20'
                            OR leg.internal_state IN ('returning', 'returned')
                            OR (h.first_return IS NOT NULL
                                AND (h.first_delivered IS NULL OR h.first_return < h.first_delivered)) THEN 'refused'
                       WHEN h.first_delivered IS NOT NULL THEN 'delivered'
                       WHEN leg.internal_state IN ('lost', 'terminated', 'cancelled') THEN 'other_terminal'
                       ELSE 'in_transit'
        """;

    static final String ORDER_OUTCOMES = """
        , period_orders AS MATERIALIZED (
            SELECT DISTINCT order_id, carrier_class FROM lines
        ),
        order_outcomes AS MATERIALIZED (
            SELECT po.order_id, d.bosta_decides, leg.shipment_id,
                   CASE WHEN d.bosta_decides THEN leg.city_id END AS city_id,
                   CASE WHEN d.bosta_decides THEN leg.city END AS city,
                   CASE
                       WHEN NOT d.bosta_decides THEN
                           CASE WHEN po.carrier_class = 'other_known' THEN 'wijha' ELSE 'not_shipped' END
            """ + LEG_OUTCOME_WHENS + """
                   END AS outcome
            FROM period_orders po
            LEFT JOIN LATERAL (
                SELECT s.id AS shipment_id, s.internal_state, s.collected_from_business_at,
                       s.type_code, s.city_id, s.city_name AS city
                FROM shipments s
                WHERE s.order_id = po.order_id
                  AND s.tenant_id = ?
                  AND s.shipment_leg = 'forward'
                  AND COALESCE(s.type_code, '10') NOT IN ('25', '30')
                ORDER BY (s.internal_state IN ('terminated', 'cancelled')), s.created_at DESC, s.id DESC
                LIMIT 1
            ) leg ON true
            LEFT JOIN LATERAL (
                SELECT MIN(hh.occurred_at) FILTER (WHERE hh.internal_state = 'delivered')                AS first_delivered,
                       MIN(hh.occurred_at) FILTER (WHERE hh.internal_state IN ('returning', 'returned')) AS first_return,
                       COALESCE(bool_or(hh.internal_state IN ('with_courier', 'returning', 'returned', 'delivered', 'lost')), false)
                                                                                                          AS picked_up
                FROM shipment_status_history hh
                WHERE hh.shipment_id = leg.shipment_id
                  AND hh.tenant_id = ?
            ) h ON true
            CROSS JOIN LATERAL (
                SELECT leg.shipment_id IS NOT NULL
                       AND NOT (leg.internal_state IN ('terminated', 'cancelled')
                                AND (po.carrier_class = 'other_known'
                                     OR (leg.collected_from_business_at IS NULL AND NOT COALESCE(h.picked_up, false))))
                       AS bosta_decides
            ) d
        )
        """;

    /*
     * Customer returns (after delivery; refused/RTO is separate) — appended after ORDER_OUTCOMES.
     * Four more parameters: tenant id (dashboard exchanges), tenant id (piece events), tenant id
     * (tracked portal items), tenant id (untracked portal items). A returned UNIT is identified by
     * (order_item, piece) or (order_item, unit_no):
     *   (1) piece_events return_received FROM delivered — the piece's allocation on the order names
     *       the order_item — minus the units that were EXCHANGED (below);
     *   (2) portal refund-request items arrived or done: a tracked item's piece (same allocation
     *       join; UNION with (1), so a piece in both counts once), an untracked item's unit_no;
     *   (3) Shopify 'return' refunds added back to the line (lines.shopify_returned) — units only,
     *       no identity.
     * Per line: returned = LEAST(qty, GREATEST(identified units, Shopify units)) — every identified
     * unit once, and Shopify only adds the units the scans / portal don't already account for, so a
     * unit present in all three sources counts once (precedence piece > portal > Shopify).
     *
     * Exchanged units are not returns (slice 6), and ONLY those units are left out — never every
     * piece of an order that has an exchange (the scan labels every delivered piece of such an
     * order 'exchange_match', so the label alone over-excludes):
     *   portal exchange — the exchange request's own unit: a scan attributed to the request
     *                     (metadata request_id) or the piece bound to its item; exchange items never
     *                     enter (2), which reads refund requests only;
     *   dashboard exchange (exchanges row with no request, matched to the order) — one unit per row:
     *                     the order's 'exchange_match' scans are ranked (the exchange's inbound
     *                     variant first, then piece id) and the first N are left out, N = the
     *                     order's dashboard exchanges (dismissed / cancelled rows don't count).
     */
    static final String LINE_RETURNS = """
        , dashboard_exchanges AS (
            SELECT x.matched_order_id AS order_id,
                   COUNT(*)                                                         AS units,
                   COALESCE(array_agg(x.inbound_variant_id)
                            FILTER (WHERE x.inbound_variant_id IS NOT NULL), '{}')  AS variants
            FROM exchanges x
            JOIN period_orders po ON po.order_id = x.matched_order_id
            WHERE x.tenant_id = ?
              AND x.return_request_id IS NULL
              AND x.status NOT IN ('dismissed', 'cancelled')
            GROUP BY x.matched_order_id
        ),
        scanned AS (
            SELECT DISTINCT a.order_item_id, e.piece_id, e.order_id, p.variant_id,
                   COALESCE(e.metadata->>'return_kind', '') = 'exchange_match' AS exchange_labelled
            FROM piece_events e
            JOIN period_orders po ON po.order_id = e.order_id
            JOIN pieces p         ON p.id = e.piece_id
            JOIN allocations a    ON a.piece_id = e.piece_id
            JOIN order_items oi2  ON oi2.id = a.order_item_id AND oi2.order_id = e.order_id
            WHERE e.tenant_id = ?
              AND e.event_type = 'return_received'
              AND e.from_status = 'delivered'
              AND NOT EXISTS (
                  SELECT 1 FROM return_requests xr
                  WHERE xr.tenant_id = e.tenant_id AND xr.type = 'exchange'
                    AND xr.id::text = e.metadata->>'request_id')
              AND NOT EXISTS (
                  SELECT 1 FROM return_request_items xi
                  JOIN return_requests xr ON xr.id = xi.request_id
                  WHERE xi.tenant_id = e.tenant_id AND xr.type = 'exchange'
                    AND xr.order_id = e.order_id AND xi.piece_id = e.piece_id)
        ),
        scanned_ranked AS (
            SELECT s.order_item_id, s.piece_id, s.exchange_labelled,
                   COALESCE(dx.units, 0) AS exchanged_units,
                   ROW_NUMBER() OVER (
                       PARTITION BY s.order_id, s.exchange_labelled
                       ORDER BY (s.variant_id = ANY (COALESCE(dx.variants, '{}'))) DESC, s.piece_id
                   ) AS exchange_rank
            FROM scanned s
            LEFT JOIN dashboard_exchanges dx ON dx.order_id = s.order_id
        ),
        piece_units AS (
            SELECT order_item_id, piece_id
            FROM scanned_ranked
            WHERE NOT (exchange_labelled AND exchange_rank <= exchanged_units)
            UNION
            SELECT a.order_item_id, rri.piece_id
            FROM return_request_items rri
            JOIN return_requests rr ON rr.id = rri.request_id
            JOIN period_orders po   ON po.order_id = rr.order_id
            JOIN allocations a      ON a.piece_id = rri.piece_id
            JOIN order_items oi2    ON oi2.id = a.order_item_id AND oi2.order_id = rr.order_id
            WHERE rri.tenant_id = ?
              AND rr.type = 'refund'
              AND rri.item_status IN ('arrived', 'done')
              AND rri.piece_id IS NOT NULL
        ),
        untracked_units AS (
            SELECT DISTINCT rri.order_item_id, rri.unit_no
            FROM return_request_items rri
            JOIN return_requests rr ON rr.id = rri.request_id
            JOIN period_orders po   ON po.order_id = rr.order_id
            WHERE rri.tenant_id = ?
              AND rr.type = 'refund'
              AND rri.item_status IN ('arrived', 'done')
              AND rri.order_item_id IS NOT NULL
        ),
        identified AS (
            SELECT order_item_id, COUNT(*) AS units
            FROM (SELECT order_item_id, piece_id AS unit FROM piece_units
                  UNION ALL
                  SELECT order_item_id, 'unit:' || unit_no FROM untracked_units) u
            GROUP BY order_item_id
        ),
        line_facts AS (
            SELECT l.variant_id, l.order_id, l.qty, l.unit_price, oo.outcome,
                   LEAST(l.qty, GREATEST(COALESCE(i.units, 0), l.shopify_returned)) AS returned,
                   l.placed_at, l.gross, l.disc_code, l.disc_auto, l.approximate,
                   oo.city_id, oo.city, oo.shipment_id
            FROM lines l
            JOIN order_outcomes oo ON oo.order_id = l.order_id
            LEFT JOIN identified i ON i.order_item_id = l.order_item_id
        )
        """;

    /**
     * last_sold (CTE, appended after soldLines — it reads its {@code floors}): each variant's last
     * sale of ALL time, MAX(placed_at) over the soldLines cohort (post-floor, not cancelled, not an
     * internal exchange order, quantity after the refund add-back > 0, not cancelled in raw), from
     * the V149 columns. Two tenant ids. Shared by /sales/variants (lastSoldAt) and the stock slice
     * (last sale, dead stock).
     */
    static final String LAST_SOLD_CTE = """
        last_sold AS (
            SELECT oi.variant_id, MAX(o.placed_at) AS last_sold_at
            FROM order_items oi
            JOIN merchant_orders o ON o.id = oi.order_id
            JOIN floors f ON f.store_id = o.store_id
            WHERE oi.tenant_id = ? AND o.tenant_id = ?
              AND (f.floor_at IS NULL OR o.placed_at >= f.floor_at)
              AND o.status <> 'cancelled'::order_status
              AND o.external_id NOT LIKE 'internal:exchange:%'
              AND NOT o.raw_cancelled
              AND COALESCE(oi.current_qty, oi.quantity) + COALESCE((o.refund_lines -> oi.line_key ->> 0)::int, 0) > 0
            GROUP BY oi.variant_id
        )
        """;

    /*
     * Slices 1 + 2 in ONE statement over the period's lines (slice 8 — it used to be two, the first
     * re-reading every line of all time): per variant plus the grand-total row (GROUPING SETS ()),
     * which carries the distinct order count across all variants.
     *   sales    — sold units, gross revenue, approximate lines, orders (slice 1);
     *   outcomes — outcome units, returns, delivered / returned revenue (slice 2). Only a DELIVERED
     *              order's returns count as customer returns; returns on any other outcome (e.g. a
     *              Wijha order refunded in Shopify) are reported apart in returns_on_undelivered;
     *   last_sold — lastSoldAt = MAX(placed_at) over the variant's sold lines of ALL time (post-floor,
     *              not cancelled, not an internal exchange order, quantity after the refund add-back
     *              > 0, not cancelled in raw — the same cohort as soldLines), from the V149 columns
     *              (no prices needed); one hash aggregate over the tenant's lines, joined to the
     *              period's variants (filtering by those variants first made the planner loop over
     *              order_items per variant — 7 s on 60k orders). Two more tenant ids.
     * Only variants with a line in the period are returned.
     */
    private static final String VARIANTS_SQL = soldLines(false) + ORDER_OUTCOMES + LINE_RETURNS + """
        , sales AS (
            SELECT GROUPING(variant_id) AS is_total, variant_id,
                   COALESCE(SUM(qty), 0)               AS sold_units,
                   COALESCE(SUM(revenue), 0)           AS gross_revenue,
                   COUNT(*) FILTER (WHERE approximate) AS approximate_lines,
                   COUNT(DISTINCT order_id)            AS orders
            FROM lines
            GROUP BY GROUPING SETS ((variant_id), ())
        ),
        outcomes AS (
            SELECT GROUPING(variant_id) AS is_total, variant_id,
                   COALESCE(SUM(qty) FILTER (WHERE outcome = 'delivered'), 0)       AS delivered_units,
                   COALESCE(SUM(qty) FILTER (WHERE outcome = 'refused'), 0)         AS refused_units,
                   COALESCE(SUM(qty) FILTER (WHERE outcome = 'in_transit'), 0)      AS in_transit_units,
                   COALESCE(SUM(qty) FILTER (WHERE outcome = 'wijha'), 0)           AS wijha_units,
                   COALESCE(SUM(qty) FILTER (WHERE outcome = 'not_shipped'), 0)     AS not_shipped_units,
                   COALESCE(SUM(qty) FILTER (WHERE outcome = 'other_terminal'), 0)  AS other_terminal_units,
                   COALESCE(SUM(returned) FILTER (WHERE outcome = 'delivered'), 0)  AS returned_units,
                   COALESCE(SUM(qty * unit_price) FILTER (WHERE outcome = 'delivered'), 0)      AS delivered_revenue,
                   COALESCE(SUM(returned * unit_price) FILTER (WHERE outcome = 'delivered'), 0) AS returned_revenue,
                   COALESCE(SUM(returned) FILTER (WHERE outcome <> 'delivered'), 0) AS returns_on_undelivered,
                   COUNT(DISTINCT order_id) FILTER (WHERE outcome = 'delivered')    AS delivered_orders,
                   COUNT(DISTINCT order_id) FILTER (WHERE outcome = 'refused')      AS refused_orders,
                   COUNT(DISTINCT order_id) FILTER (WHERE outcome = 'wijha')        AS wijha_orders,
                   (SELECT COALESCE(SUM(unverified_no_restock), 0) FROM all_lines WHERE not_cancelled)  AS unverified_no_restock
            FROM line_facts
            GROUP BY GROUPING SETS ((variant_id), ())
        ),
        """ + LAST_SOLD_CTE + """
        SELECT s.is_total, s.variant_id, v.product_id, p.title AS product_title,
               v.title AS variant_title, v.sku, p.image_url, ls.last_sold_at,
               s.sold_units, s.gross_revenue, s.approximate_lines, s.orders,
               oc.delivered_units, oc.refused_units, oc.in_transit_units, oc.wijha_units, oc.not_shipped_units,
               oc.other_terminal_units, oc.returned_units, oc.delivered_revenue, oc.returned_revenue,
               oc.returns_on_undelivered, oc.delivered_orders, oc.refused_orders, oc.wijha_orders,
               oc.unverified_no_restock
        FROM sales s
        -- hashable equality (IS NOT DISTINCT FROM is not): the total row's NULL variant → the nil uuid
        LEFT JOIN outcomes oc ON oc.is_total = s.is_total
             AND COALESCE(oc.variant_id, '00000000-0000-0000-0000-000000000000'::uuid)
               = COALESCE(s.variant_id, '00000000-0000-0000-0000-000000000000'::uuid)
        LEFT JOIN last_sold ls ON ls.variant_id = s.variant_id
        LEFT JOIN variants v ON v.id = s.variant_id
        LEFT JOIN products p ON p.id = v.product_id
        ORDER BY s.is_total DESC, s.sold_units DESC, s.gross_revenue DESC, p.title, v.title
        """;

    /*
     * Per city of the deciding Bosta forward leg, keyed by Bosta's city._id (dropOffAddress.city —
     * never any other address field). nameEn = the leg's city name; nameAr = bosta_districts'
     * city_name_ar (Bosta's own reference data), falling back to nameEn. Legs with no city are one
     * "Unknown" row (cityId null), never dropped.
     */
    private static final String CITIES_SQL = soldLines(false) + ORDER_OUTCOMES + """
        , per_city AS (
            SELECT city_id,
                   MAX(city)                                           AS leg_name,
                   COUNT(*)                                            AS orders,
                   COUNT(*) FILTER (WHERE outcome = 'delivered')       AS delivered_orders,
                   COUNT(*) FILTER (WHERE outcome = 'refused')         AS refused_orders,
                   COUNT(*) FILTER (WHERE outcome = 'in_transit')      AS in_transit_orders,
                   COUNT(*) FILTER (WHERE outcome = 'other_terminal')  AS other_terminal_orders
            FROM order_outcomes
            WHERE bosta_decides
            GROUP BY city_id
        ),
        city_names AS (
            SELECT city_id, MAX(city_name) AS name_en, MAX(city_name_ar) AS name_ar
            FROM bosta_districts
            WHERE city_id IN (SELECT city_id FROM per_city)
            GROUP BY city_id
        )
        SELECT pc.city_id,
               CASE WHEN pc.city_id IS NULL THEN 'Unknown'
                    ELSE COALESCE(pc.leg_name, cn.name_en, pc.city_id) END                  AS name_en,
               CASE WHEN pc.city_id IS NULL THEN 'Unknown'
                    ELSE COALESCE(cn.name_ar, pc.leg_name, cn.name_en, pc.city_id) END      AS name_ar,
               pc.orders, pc.delivered_orders, pc.refused_orders, pc.in_transit_orders,
               pc.other_terminal_orders
        FROM per_city pc
        LEFT JOIN city_names cn ON cn.city_id = pc.city_id
        ORDER BY pc.orders DESC, (pc.city_id IS NULL), name_en
        """;

    private static String productsSql(Sort sort) {
        String variantOrder = sort == Sort.UNITS
            ? "pv.units DESC, pv.revenue DESC, v.title"
            : "pv.revenue DESC, pv.units DESC, v.title";
        String productOrder = sort == Sort.UNITS
            ? "pp.units DESC, pp.revenue DESC, p.title"
            : "pp.revenue DESC, pp.units DESC, p.title";
        return soldLines(false) + """
            , per_variant AS (
                SELECT variant_id, SUM(qty) AS units, SUM(revenue) AS revenue
                FROM lines GROUP BY variant_id
            ),
            per_product AS (
                SELECT v.product_id,
                       SUM(pv.units)   AS units,
                       SUM(pv.revenue) AS revenue,
                       COUNT(*)        AS variant_count,
                       (array_agg(v.id    ORDER BY %1$s))[1:3] AS top_ids,
                       (array_agg(v.title ORDER BY %1$s))[1:3] AS top_titles,
                       (array_agg(pv.units ORDER BY %1$s))[1:3] AS top_units
                FROM per_variant pv
                JOIN variants v ON v.id = pv.variant_id
                GROUP BY v.product_id
            )
            SELECT p.id AS product_id, p.title, p.image_url, pp.units, pp.revenue, pp.variant_count,
                   pp.top_ids, pp.top_titles, pp.top_units
            FROM per_product pp
            JOIN products p ON p.id = pp.product_id
            ORDER BY %2$s
            LIMIT ?
            """.formatted(variantOrder, productOrder);
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AnalyticsFloorOverrides overrides;

    public SalesAnalyticsService(JdbcTemplate jdbc, Clock clock, AnalyticsFloorOverrides overrides) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.overrides = overrides;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(AnalyticsPeriod.CAIRO));
    }

    @Transactional(readOnly = true)
    public VariantSalesResponse variants(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();

        // One statement (slice 8): sales, outcomes + returns, lastSoldAt.
        List<VariantSales> rows = new ArrayList<>();
        Totals[] totals = { totals(0, BigDecimal.ZERO, 0, 0, Outcome.NONE) };
        jdbc.query(VARIANTS_SQL, params(tid, period, 8, null), rs -> {
            Outcome o = rs.getObject("delivered_units") == null ? Outcome.NONE : new Outcome(
                rs.getLong("delivered_units"), rs.getLong("refused_units"), rs.getLong("in_transit_units"),
                rs.getLong("wijha_units"), rs.getLong("not_shipped_units"), rs.getLong("other_terminal_units"),
                rs.getLong("returned_units"), rs.getBigDecimal("delivered_revenue"),
                rs.getBigDecimal("returned_revenue"), rs.getLong("returns_on_undelivered"),
                rs.getLong("delivered_orders"), rs.getLong("refused_orders"), rs.getLong("wijha_orders"),
                rs.getLong("unverified_no_restock"));
            if (rs.getInt("is_total") == 1) {
                totals[0] = totals(rs.getLong("sold_units"), rs.getBigDecimal("gross_revenue"),
                    rs.getLong("orders"), rs.getLong("approximate_lines"), o);
                return;
            }
            UUID variantId = rs.getObject("variant_id", UUID.class);
            Timestamp last = rs.getTimestamp("last_sold_at");
            rows.add(new VariantSales(
                variantId,
                rs.getObject("product_id", UUID.class),
                rs.getString("product_title"),
                rs.getString("variant_title"),
                rs.getString("sku"),
                rs.getString("image_url"),
                rs.getLong("sold_units"),
                money(rs.getBigDecimal("gross_revenue")),
                rs.getLong("approximate_lines"),
                last == null ? null : last.toInstant(),
                o.delivered(), o.refused(), o.inTransit(), o.wijha(), o.notShipped(), o.otherTerminal(),
                o.returned(), o.delivered() - o.returned(),
                rate(o.returned(), o.delivered()), rate(o.refused(), o.delivered() + o.refused() + o.otherTerminal()),
                money(o.deliveredRevenue()), money(o.returnedRevenue()),
                money(o.deliveredRevenue()).subtract(money(o.returnedRevenue()))));
        });
        return new VariantSalesResponse(period.range(), totals[0], rows);
    }

    private static Totals totals(long soldUnits, BigDecimal grossRevenue, long orders, long approximateLines,
                                 Outcome o) {
        return new Totals(soldUnits, money(grossRevenue), orders, approximateLines,
            o.delivered(), o.refused(), o.inTransit(), o.wijha(), o.notShipped(), o.otherTerminal(),
            o.returned(), o.delivered() - o.returned(),
            rate(o.returned(), o.delivered()), rate(o.refused(), o.delivered() + o.refused() + o.otherTerminal()),
            money(o.deliveredRevenue()), money(o.returnedRevenue()),
            money(o.deliveredRevenue()).subtract(money(o.returnedRevenue())),
            o.deliveredOrders(), o.refusedOrders(), o.wijhaOrders(),
            o.returnsOnUndelivered(), o.unverifiedNoRestock());
    }

    @Transactional(readOnly = true)
    public ProductSalesResponse products(AnalyticsPeriod period, Sort sort, int limit) {
        UUID tid = TenantContext.require();
        List<ProductSales> rows = jdbc.query(productsSql(sort), params(tid, period, 0, limit),
            (rs, i) -> new ProductSales(
                rs.getObject("product_id", UUID.class),
                rs.getString("title"),
                rs.getString("image_url"),
                rs.getLong("units"),
                money(rs.getBigDecimal("revenue")),
                rs.getInt("variant_count"),
                topVariants(rs)));
        return new ProductSalesResponse(period.range(), sort.name().toLowerCase(), rows);
    }

    // ── /variants/daily ─────────────────────────────────────────────────────

    /** At most this many variant ids per /variants/daily call. */
    public static final int MAX_DAILY_VARIANTS = 20;

    /** One variant's sold units per Cairo day of the period (dense: a 0 for every day without a sale). */
    public record VariantDaily(UUID variantId, long totalUnits, List<Long> units) {}

    public record VariantDailyResponse(AnalyticsPeriod.Range range, List<LocalDate> days, List<VariantDaily> variants) {}

    /*
     * Sold units (the slice-1 rule: soldLines — post-floor, not cancelled, not an internal exchange
     * order, quantity after the refund add-back > 0) per variant and Cairo placed day. soldLines' 7
     * parameters, then the variant ids (uuid[]).
     */
    private static final String VARIANT_DAILY_SQL = soldLines(false) + """
        SELECT variant_id, (placed_at AT TIME ZONE 'Africa/Cairo')::date AS day, SUM(qty) AS units
        FROM lines
        WHERE variant_id = ANY (?::uuid[])
        GROUP BY variant_id, day
        """;

    /** Sold units per day for the given variants, in the order asked; unknown ids get zeros. */
    @Transactional(readOnly = true)
    public VariantDailyResponse variantsDaily(AnalyticsPeriod period, List<UUID> ids) {
        UUID tid = TenantContext.require();
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = period.from(); !d.isAfter(period.to()); d = d.plusDays(1)) days.add(d);
        Map<UUID, long[]> byVariant = new LinkedHashMap<>();
        for (UUID id : ids) byVariant.putIfAbsent(id, new long[days.size()]);
        jdbc.query(VARIANT_DAILY_SQL, ps -> {
            int i = AnalyticsSql.bindSoldLines(ps, tid, period, overrides);
            ps.setArray(i, ps.getConnection().createArrayOf("uuid", byVariant.keySet().toArray()));
        }, rs -> {
            long[] series = byVariant.get(rs.getObject("variant_id", UUID.class));
            int idx = (int) java.time.temporal.ChronoUnit.DAYS.between(period.from(), rs.getDate("day").toLocalDate());
            if (series != null && idx >= 0 && idx < series.length) series[idx] += rs.getLong("units");
        });
        List<VariantDaily> out = new ArrayList<>();
        for (Map.Entry<UUID, long[]> e : byVariant.entrySet()) {
            List<Long> units = new ArrayList<>(e.getValue().length);
            long total = 0;
            for (long u : e.getValue()) {
                units.add(u);
                total += u;
            }
            out.add(new VariantDaily(e.getKey(), total, units));
        }
        return new VariantDailyResponse(period.range(), days, out);
    }

    @Transactional(readOnly = true)
    public CitySalesResponse cities(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        List<CitySales> rows = jdbc.query(CITIES_SQL, params(tid, period, 2, null), (rs, i) -> {
            long delivered = rs.getLong("delivered_orders");
            long refused = rs.getLong("refused_orders");
            long otherTerminal = rs.getLong("other_terminal_orders");
            return new CitySales(rs.getString("city_id"), rs.getString("name_en"), rs.getString("name_ar"),
                rs.getLong("orders"), delivered, refused,
                rs.getLong("in_transit_orders"), otherTerminal,
                rate(delivered, delivered + refused + otherTerminal));
        });
        return new CitySalesResponse(period.range(), rows);
    }

    /**
     * The sold-line parameters (period, overrides, three tenant binds), then {@code extraTenantParams}
     * more tenant ids for the appended CTEs (in the order they appear), then the optional limit.
     */
    private PreparedStatementSetter params(UUID tid, AnalyticsPeriod period, int extraTenantParams, Integer limit) {
        return ps -> {
            int i = 1;
            ps.setTimestamp(i++, Timestamp.from(period.startInclusive()));
            ps.setTimestamp(i++, Timestamp.from(period.endExclusive()));
            ps.setArray(i++, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
            ps.setArray(i++, ps.getConnection().createArrayOf("text", overrides.days()));
            for (int t = 0; t < 3 + extraTenantParams; t++) ps.setObject(i++, tid);
            if (limit != null) ps.setInt(i, limit);
        };
    }

    /** numerator / denominator to 4 decimals, null when the denominator is 0. */
    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) return null;
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static List<TopVariant> topVariants(ResultSet rs) throws SQLException {
        Object[] ids    = arrayOf(rs.getArray("top_ids"));
        Object[] titles = arrayOf(rs.getArray("top_titles"));
        Object[] units  = arrayOf(rs.getArray("top_units"));
        List<TopVariant> out = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            out.add(new TopVariant((UUID) ids[i], (String) titles[i], ((Number) units[i]).longValue()));
        }
        return out;
    }

    private static Object[] arrayOf(Array a) throws SQLException {
        return a == null ? new Object[0] : (Object[]) a.getArray();
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
