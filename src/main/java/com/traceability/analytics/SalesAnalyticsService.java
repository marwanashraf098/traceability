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
     * returnRate = returned / delivered and refusalRate = refused / (delivered + refused), both null
     * when the denominator is 0; returnedRevenue = returned units × the line's net unit price.
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

    /** successRate = delivered / (delivered + refused), null when both are 0. city is the raw Bosta name. */
    public record CitySales(String city, long orders, long deliveredOrders, long refusedOrders,
                            long inTransitOrders, long otherTerminalOrders, BigDecimal successRate) {}

    public record CitySalesResponse(AnalyticsPeriod.Range range, List<CitySales> cities) {}

    /** One row of OUTCOMES_SQL. */
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
     *   bounds  — the period, [p_start, p_end).
     *   floors  — per store: orders_ingest_from, else the override day at 00:00 Cairo, else NULL.
     *   all_lines — every cohort line, qty ≤ 0 and raw-cancelled included (only the unverified
     *             no_restock count reads them); lines = all_lines with qty > 0 and not cancelled in
     *             raw — the SOLD lines every figure is built on. raw cancelled_at is checked here,
     *             not in all_lines' WHERE: a jsonb IS NULL filter gets a 0.5% default selectivity
     *             and the ~1-row estimate sent the planner into nested loops. Each raw is read once
     *             through jsonb_to_record (ov / li) — every raw->… reference decompresses it again.
     *   lines   — sold lines, one pass. ALL TIME post-floor when allTime (the variants endpoint needs
     *             lastSoldAt), else only lines placed in the period. in_period flags the period's
     *             lines; unit_price / revenue are computed for those only (NULL otherwise).
     *             MATERIALIZED so the grouping works on these narrow rows — inlined, the planner
     *             carried each line's raw jsonb into the sort and spilled it to disk (prod EXPLAIN,
     *             Femine 366 days).
     *   rf      — the line's Shopify refunds (slice 2, approved 2026-10-06). Shopify lowers
     *             current_quantity when a unit is refunded, so a unit sold, delivered and then
     *             refunded would vanish from sales. added_back = refunded units with restock_type
     *             'return' or 'no_restock' whose refund was created AFTER a (non-cancelled)
     *             fulfillment containing the line; a refund before that is a pre-ship
     *             cancellation / edit and stays out, and 'cancel' is always out. Order raw with no
     *             'fulfillments' key: only 'return' is added back, and the line's 'no_restock'
     *             refund lines are counted in unverified_no_restock. shopify_returned = the added-back
     *             'return' units (returns source 3); a 'no_restock' refund is a sale but not a return.
     */
    private static String soldLines(boolean allTime) {
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
                       ov.cancelled_at IS NULL AS not_cancelled,
                       up.unit_price,
                       q.qty * up.unit_price AS revenue
                FROM order_items oi
                JOIN orders o   ON o.id = oi.order_id
                JOIN floors f   ON f.store_id = o.store_id
                JOIN variants v ON v.id = oi.variant_id
                CROSS JOIN bounds b
                CROSS JOIN LATERAL jsonb_to_record(COALESCE(o.raw, '{}'::jsonb))
                    AS ov(cancelled_at text, refunds jsonb, fulfillments jsonb)
                CROSS JOIN LATERAL jsonb_to_record(COALESCE(oi.raw, '{}'::jsonb))
                    AS li(price numeric, quantity int, current_quantity int, discount_allocations jsonb)
                CROSS JOIN LATERAL (
                    SELECT COALESCE(SUM(x.units) FILTER (WHERE x.counts), 0)                         AS added_back,
                           COALESCE(SUM(x.units) FILTER (WHERE x.counts AND x.restock = 'return'), 0) AS shopify_returned,
                           COUNT(*) FILTER (WHERE x.restock = 'no_restock' AND NOT x.has_fulfillments) AS unverified_no_restock
                    FROM (
                        SELECT (rli->>'quantity')::int AS units,
                               rli->>'restock_type'    AS restock,
                               (ov.fulfillments IS NOT NULL) AS has_fulfillments,
                               rli->>'restock_type' IN ('return', 'no_restock') AND CASE
                                   WHEN (ov.fulfillments IS NOT NULL) THEN EXISTS (
                                       SELECT 1
                                       FROM jsonb_array_elements(CASE WHEN jsonb_typeof(ov.fulfillments) = 'array'
                                                                      THEN ov.fulfillments ELSE '[]'::jsonb END) f
                                       WHERE COALESCE(f->>'status', '') NOT IN ('cancelled', 'error', 'failure')
                                         AND (f->>'created_at')::timestamptz < (r->>'created_at')::timestamptz
                                         AND EXISTS (
                                             SELECT 1
                                             FROM jsonb_array_elements(CASE WHEN jsonb_typeof(f->'line_items') = 'array'
                                                                            THEN f->'line_items' ELSE '[]'::jsonb END) fl
                                             WHERE 'gid://shopify/LineItem/' || (fl->>'id') = oi.external_id))
                                   ELSE rli->>'restock_type' = 'return'
                               END AS counts
                        FROM jsonb_array_elements(CASE WHEN jsonb_typeof(ov.refunds) = 'array'
                                                       THEN ov.refunds ELSE '[]'::jsonb END) r
                        CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(r->'refund_line_items') = 'array'
                                                                     THEN r->'refund_line_items' ELSE '[]'::jsonb END) rli
                        WHERE 'gid://shopify/LineItem/' || (rli->>'line_item_id') = oi.external_id
                    ) x
                ) rf
                CROSS JOIN LATERAL (
                    SELECT COALESCE(li.current_quantity, oi.quantity) + rf.added_back AS qty,
                           COALESCE(li.quantity, oi.quantity)                         AS original_qty,
                           li.price                                               AS raw_price
                ) q
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN o.placed_at >= b.p_start AND o.placed_at < b.p_end THEN
                               CASE
                                   WHEN q.raw_price IS NULL THEN COALESCE(v.price, 0)
                                   ELSE q.raw_price - COALESCE((
                                            SELECT SUM((d->>'amount')::numeric)
                                            FROM jsonb_array_elements(
                                                CASE WHEN jsonb_typeof(li.discount_allocations) = 'array'
                                                     THEN li.discount_allocations ELSE '[]'::jsonb END) d
                                        ), 0) / NULLIF(GREATEST(q.original_qty, q.qty), 0)
                               END
                           END AS unit_price
                ) up
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
     *     wijha / not_shipped — no deciding leg; or the leg is terminated/cancelled and the order
     *                           shipped with another known carrier (Wijha): wijha when
     *                           orders.shipping_carrier_class = 'other_known', else not_shipped
     *     delivered       — the leg's state is delivered
     *     refused         — the leg turned Return to Origin (type code 20), is returning/returned, or
     *                       its history went returning/returned before any delivered
     *     delivered       — history shows delivered (current state moved on, e.g. exception)
     *     other_terminal  — lost / terminated / cancelled
     *     in_transit      — created / with_courier / exception
     *   bosta_decides = a Bosta leg decided the outcome (the cities endpoint counts only those).
     */
    private static final String ORDER_OUTCOMES = """
        , period_orders AS MATERIALIZED (
            SELECT DISTINCT order_id, carrier_class FROM lines
        ),
        order_outcomes AS MATERIALIZED (
            SELECT po.order_id, d.bosta_decides,
                   CASE WHEN d.bosta_decides THEN leg.city END AS city,
                   CASE
                       WHEN NOT d.bosta_decides THEN
                           CASE WHEN po.carrier_class = 'other_known' THEN 'wijha' ELSE 'not_shipped' END
                       WHEN leg.internal_state = 'delivered' THEN 'delivered'
                       WHEN leg.type_code = '20'
                            OR leg.internal_state IN ('returning', 'returned')
                            OR (h.first_return IS NOT NULL
                                AND (h.first_delivered IS NULL OR h.first_return < h.first_delivered)) THEN 'refused'
                       WHEN h.first_delivered IS NOT NULL THEN 'delivered'
                       WHEN leg.internal_state IN ('lost', 'terminated', 'cancelled') THEN 'other_terminal'
                       ELSE 'in_transit'
                   END AS outcome
            FROM period_orders po
            LEFT JOIN LATERAL (
                SELECT s.id AS shipment_id, s.internal_state,
                       s.raw->'type'->>'code'                   AS type_code,
                       s.raw->'dropOffAddress'->'city'->>'name' AS city
                FROM shipments s
                WHERE s.order_id = po.order_id
                  AND s.tenant_id = ?
                  AND s.shipment_leg = 'forward'
                  AND COALESCE(s.raw->'type'->>'code', '10') NOT IN ('25', '30')
                ORDER BY (s.internal_state IN ('terminated', 'cancelled')), s.created_at DESC, s.id DESC
                LIMIT 1
            ) leg ON true
            LEFT JOIN LATERAL (
                SELECT MIN(hh.occurred_at) FILTER (WHERE hh.internal_state = 'delivered')                AS first_delivered,
                       MIN(hh.occurred_at) FILTER (WHERE hh.internal_state IN ('returning', 'returned')) AS first_return
                FROM shipment_status_history hh
                WHERE hh.shipment_id = leg.shipment_id
                  AND hh.tenant_id = ?
            ) h ON true
            CROSS JOIN LATERAL (
                SELECT leg.shipment_id IS NOT NULL
                       AND NOT (leg.internal_state IN ('terminated', 'cancelled')
                                AND po.carrier_class = 'other_known') AS bosta_decides
            ) d
        )
        """;

    /*
     * Customer returns (after delivery; refused/RTO is separate) — appended after ORDER_OUTCOMES.
     * Three more parameters: tenant id (piece events), tenant id (tracked portal items), tenant id
     * (untracked portal items). A returned UNIT is identified by (order_item, piece) or
     * (order_item, unit_no):
     *   (1) piece_events return_received FROM delivered, not exchange_match and not attributed to an
     *       exchange request — the piece's allocation on the order names the order_item;
     *   (2) portal refund-request items arrived or done: a tracked item's piece (same allocation
     *       join; UNION with (1), so a piece in both counts once), an untracked item's unit_no;
     *   (3) Shopify 'return' refunds added back to the line (lines.shopify_returned) — units only,
     *       no identity.
     * Per line: returned = LEAST(qty, GREATEST(identified units, Shopify units)) — every identified
     * unit once, and Shopify only adds the units the scans / portal don't already account for, so a
     * unit present in all three sources counts once (precedence piece > portal > Shopify).
     * Exchange-kind portal items and exchange inbound pieces are never returns here.
     */
    private static final String LINE_RETURNS = """
        , piece_units AS (
            SELECT a.order_item_id, e.piece_id
            FROM piece_events e
            JOIN period_orders po ON po.order_id = e.order_id
            JOIN allocations a    ON a.piece_id = e.piece_id
            JOIN order_items oi2  ON oi2.id = a.order_item_id AND oi2.order_id = e.order_id
            WHERE e.tenant_id = ?
              AND e.event_type = 'return_received'
              AND e.from_status = 'delivered'
              AND COALESCE(e.metadata->>'return_kind', '') <> 'exchange_match'
              AND NOT EXISTS (
                  SELECT 1 FROM return_requests xr
                  WHERE xr.tenant_id = e.tenant_id AND xr.type = 'exchange'
                    AND xr.id::text = e.metadata->>'request_id')
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
                   LEAST(l.qty, GREATEST(COALESCE(i.units, 0), l.shopify_returned)) AS returned
            FROM lines l
            JOIN order_outcomes oo ON oo.order_id = l.order_id
            LEFT JOIN identified i ON i.order_item_id = l.order_item_id
        )
        """;

    /*
     * One pass over the lines, grouped per variant plus the grand-total row (GROUPING SETS ()),
     * which carries the distinct order count across all variants. lastSoldAt = MAX(placed_at) over
     * the variant's sold lines of all time (post-floor), not just the period; only variants with a
     * line in the period are returned.
     */
    private static final String VARIANTS_SQL = soldLines(true) + """
        , per_variant AS (
            SELECT GROUPING(variant_id) AS is_total, variant_id,
                   MAX(placed_at)                                       AS last_sold_at,
                   COALESCE(SUM(qty) FILTER (WHERE in_period), 0)       AS sold_units,
                   COALESCE(SUM(revenue) FILTER (WHERE in_period), 0)   AS gross_revenue,
                   COUNT(*) FILTER (WHERE in_period AND approximate)    AS approximate_lines,
                   COUNT(DISTINCT order_id) FILTER (WHERE in_period)    AS orders,
                   COUNT(*) FILTER (WHERE in_period)                    AS period_lines
            FROM lines
            GROUP BY GROUPING SETS ((variant_id), ())
        )
        SELECT pv.is_total, pv.variant_id, v.product_id, p.title AS product_title,
               v.title AS variant_title, v.sku, p.image_url, pv.last_sold_at,
               pv.sold_units, pv.gross_revenue, pv.approximate_lines, pv.orders
        FROM per_variant pv
        LEFT JOIN variants v ON v.id = pv.variant_id
        LEFT JOIN products p ON p.id = v.product_id
        WHERE pv.is_total = 1 OR pv.period_lines > 0
        ORDER BY pv.is_total DESC, pv.sold_units DESC, pv.gross_revenue DESC, p.title, v.title
        """;

    /*
     * Slice 2 — outcomes and returns per variant (+ grand total), merged into the slice-1 rows in
     * Java: the slice-1 statement stays as it was, this one reads only the period's lines. Only a
     * DELIVERED order's returns count as customer returns; returns on any other outcome (e.g. a Wijha
     * order refunded in Shopify) are reported apart in returns_on_undelivered.
     */
    private static final String OUTCOMES_SQL = soldLines(false) + ORDER_OUTCOMES + LINE_RETURNS + """
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
        """;

    /** Per city of the deciding Bosta forward leg (dropOffAddress.city.name only — never other address fields). */
    private static final String CITIES_SQL = soldLines(false) + ORDER_OUTCOMES + """
        SELECT city,
               COUNT(*)                                            AS orders,
               COUNT(*) FILTER (WHERE outcome = 'delivered')       AS delivered_orders,
               COUNT(*) FILTER (WHERE outcome = 'refused')         AS refused_orders,
               COUNT(*) FILTER (WHERE outcome = 'in_transit')      AS in_transit_orders,
               COUNT(*) FILTER (WHERE outcome = 'other_terminal')  AS other_terminal_orders
        FROM order_outcomes
        WHERE bosta_decides
        GROUP BY city
        ORDER BY orders DESC, city NULLS LAST
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

        // Statement 2 (slice 2): outcomes + returns over the period's lines, keyed by variant.
        Map<UUID, Outcome> outcomes = new HashMap<>();
        Outcome[] totalOutcome = { Outcome.NONE };
        jdbc.query(OUTCOMES_SQL, params(tid, period, 5, null), rs -> {
            Outcome o = new Outcome(
                rs.getLong("delivered_units"), rs.getLong("refused_units"), rs.getLong("in_transit_units"),
                rs.getLong("wijha_units"), rs.getLong("not_shipped_units"), rs.getLong("other_terminal_units"),
                rs.getLong("returned_units"), rs.getBigDecimal("delivered_revenue"),
                rs.getBigDecimal("returned_revenue"), rs.getLong("returns_on_undelivered"),
                rs.getLong("delivered_orders"), rs.getLong("refused_orders"), rs.getLong("wijha_orders"),
                rs.getLong("unverified_no_restock"));
            if (rs.getInt("is_total") == 1) totalOutcome[0] = o;
            else outcomes.put(rs.getObject("variant_id", UUID.class), o);
        });

        // Statement 1 (slice 1): sales, approximate lines, lastSoldAt (all time).
        List<VariantSales> rows = new ArrayList<>();
        Totals[] totals = { totals(0, BigDecimal.ZERO, 0, 0, totalOutcome[0]) };
        jdbc.query(VARIANTS_SQL, params(tid, period, 0, null), rs -> {
            if (rs.getInt("is_total") == 1) {
                totals[0] = totals(rs.getLong("sold_units"), rs.getBigDecimal("gross_revenue"),
                    rs.getLong("orders"), rs.getLong("approximate_lines"), totalOutcome[0]);
                return;
            }
            UUID variantId = rs.getObject("variant_id", UUID.class);
            Outcome o = outcomes.getOrDefault(variantId, Outcome.NONE);
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
                rate(o.returned(), o.delivered()), rate(o.refused(), o.delivered() + o.refused()),
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
            rate(o.returned(), o.delivered()), rate(o.refused(), o.delivered() + o.refused()),
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

    @Transactional(readOnly = true)
    public CitySalesResponse cities(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        List<CitySales> rows = jdbc.query(CITIES_SQL, params(tid, period, 2, null), (rs, i) -> {
            long delivered = rs.getLong("delivered_orders");
            long refused = rs.getLong("refused_orders");
            return new CitySales(rs.getString("city"), rs.getLong("orders"), delivered, refused,
                rs.getLong("in_transit_orders"), rs.getLong("other_terminal_orders"),
                rate(delivered, delivered + refused));
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
