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
import java.util.List;
import java.util.UUID;

/**
 * Analytics slice 1 — sales by variant and by product, counted by ORDER placed_at (cohort).
 * Live aggregation, one SQL statement per endpoint, as app_user under RLS for the caller's tenant
 * (every statement is also tenant_id-bound). No stored counters.
 *
 * A SOLD LINE ({@link #SOLD_LINES}) is an order line whose order is
 * <ul>
 *   <li>placed on/after its store's analytics floor — {@code stores.orders_ingest_from}, else a
 *       configured {@link AnalyticsFloorOverrides} day, else no floor;</li>
 *   <li>not cancelled — {@code orders.status <> 'cancelled'} AND {@code raw->>'cancelled_at'} IS NULL;</li>
 *   <li>not an internal exchange order ({@code external_id LIKE 'internal:exchange:%'} — a replacement
 *       is not a sale);</li>
 * </ul>
 * with qty = {@code raw->>'current_quantity'} (Shopify's quantity after edits / removals) falling
 * back to {@code order_items.quantity}; lines with qty ≤ 0 are not sold.
 *
 * Unit net price = {@code raw->>'price'} − Σ {@code raw->'discount_allocations'[].amount} / the
 * line's ORIGINAL quantity ({@code raw->>'quantity'}): Shopify allocates a line's discount over the
 * units originally ordered, so an edit that removes a unit doesn't concentrate the whole discount on
 * the units left. (Same rule as RefundSuggestionService's stored-raw source.) Order-level amounts
 * ({@code orders.total_discounts}, which includes shipping discounts) are never spread over lines.
 * A line with no stored REST price (GraphQL-imported, internal) is priced at {@code variants.price}
 * and counted in approximateLines.
 *
 * Wijha orders are included — they're sales; delivery outcomes are a later slice.
 */
@Service
public class SalesAnalyticsService {

    public enum Sort { UNITS, REVENUE }

    public static final int DEFAULT_PRODUCT_LIMIT = 10;
    public static final int MAX_PRODUCT_LIMIT = 100;

    // ── Response records ────────────────────────────────────────────────────

    public record VariantSales(UUID variantId, UUID productId, String productTitle, String variantTitle,
                               String sku, String imageUrl, long soldUnits, BigDecimal grossRevenue,
                               long approximateLines, Instant lastSoldAt) {}

    public record Totals(long soldUnits, BigDecimal grossRevenue, long orders, long approximateLines) {}

    public record VariantSalesResponse(AnalyticsPeriod.Range range, Totals totals, List<VariantSales> variants) {}

    public record TopVariant(UUID variantId, String variantTitle, long soldUnits) {}

    public record ProductSales(UUID productId, String title, String imageUrl, long soldUnits,
                               BigDecimal grossRevenue, int variantCount, List<TopVariant> topVariants) {}

    public record ProductSalesResponse(AnalyticsPeriod.Range range, String sort, List<ProductSales> products) {}

    // ── SQL ─────────────────────────────────────────────────────────────────

    /*
     * Shared CTEs. Parameters, in order: period start, period end, override shop domains (text[]),
     * override days (text[]), tenant id (stores), tenant id (order_items), tenant id (orders).
     *   bounds  — the period, [p_start, p_end).
     *   floors  — per store: orders_ingest_from, else the override day at 00:00 Cairo, else NULL.
     *   lines   — sold lines, one pass. ALL TIME post-floor when allTime (the variants endpoint needs
     *             lastSoldAt), else only lines placed in the period. in_period flags the period's
     *             lines; revenue is computed for those only (NULL otherwise). MATERIALIZED so the
     *             grouping works on these narrow rows — inlined, the planner carried each line's
     *             raw jsonb into the sort and spilled it to disk (prod EXPLAIN, Femine 366 days).
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
            lines AS MATERIALIZED (
                SELECT oi.variant_id, oi.order_id, o.placed_at, q.qty,
                       (o.placed_at >= b.p_start AND o.placed_at < b.p_end) AS in_period,
                       (q.raw_price IS NULL) AS approximate,
                       CASE WHEN o.placed_at >= b.p_start AND o.placed_at < b.p_end THEN
                           q.qty * CASE
                               WHEN q.raw_price IS NULL THEN COALESCE(v.price, 0)
                               ELSE q.raw_price - COALESCE((
                                        SELECT SUM((d->>'amount')::numeric)
                                        FROM jsonb_array_elements(
                                            CASE WHEN jsonb_typeof(oi.raw->'discount_allocations') = 'array'
                                                 THEN oi.raw->'discount_allocations' ELSE '[]'::jsonb END) d
                                    ), 0) / NULLIF(GREATEST(q.original_qty, q.qty), 0)
                           END
                       END AS revenue
                FROM order_items oi
                JOIN orders o   ON o.id = oi.order_id
                JOIN floors f   ON f.store_id = o.store_id
                JOIN variants v ON v.id = oi.variant_id
                CROSS JOIN bounds b
                CROSS JOIN LATERAL (
                    SELECT COALESCE((oi.raw->>'current_quantity')::int, oi.quantity) AS qty,
                           COALESCE((oi.raw->>'quantity')::int, oi.quantity)         AS original_qty,
                           (oi.raw->>'price')::numeric                               AS raw_price
                ) q
                WHERE oi.tenant_id = ?
                  AND o.tenant_id = ?
                  AND (f.floor_at IS NULL OR o.placed_at >= f.floor_at)
                  AND o.status <> 'cancelled'::order_status
                  AND o.raw->>'cancelled_at' IS NULL
                  AND o.external_id NOT LIKE 'internal:exchange:%%'
                  AND q.qty > 0
                  %s
            )
            """.formatted(allTime ? "" : "AND o.placed_at >= b.p_start AND o.placed_at < b.p_end");
    }

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
        List<VariantSales> rows = new ArrayList<>();
        Totals[] totals = { new Totals(0, money(BigDecimal.ZERO), 0, 0) };

        jdbc.query(VARIANTS_SQL, soldLineParams(tid, period, null), rs -> {
            if (rs.getInt("is_total") == 1) {
                totals[0] = new Totals(rs.getLong("sold_units"), money(rs.getBigDecimal("gross_revenue")),
                    rs.getLong("orders"), rs.getLong("approximate_lines"));
                return;
            }
            Timestamp last = rs.getTimestamp("last_sold_at");
            rows.add(new VariantSales(
                rs.getObject("variant_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("product_title"),
                rs.getString("variant_title"),
                rs.getString("sku"),
                rs.getString("image_url"),
                rs.getLong("sold_units"),
                money(rs.getBigDecimal("gross_revenue")),
                rs.getLong("approximate_lines"),
                last == null ? null : last.toInstant()));
        });
        return new VariantSalesResponse(period.range(), totals[0], rows);
    }

    @Transactional(readOnly = true)
    public ProductSalesResponse products(AnalyticsPeriod period, Sort sort, int limit) {
        UUID tid = TenantContext.require();
        List<ProductSales> rows = jdbc.query(productsSql(sort), soldLineParams(tid, period, limit),
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

    private PreparedStatementSetter soldLineParams(UUID tid, AnalyticsPeriod period, Integer limit) {
        return ps -> {
            ps.setTimestamp(1, Timestamp.from(period.startInclusive()));
            ps.setTimestamp(2, Timestamp.from(period.endExclusive()));
            ps.setArray(3, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
            ps.setArray(4, ps.getConnection().createArrayOf("text", overrides.days()));
            ps.setObject(5, tid);
            ps.setObject(6, tid);
            ps.setObject(7, tid);
            if (limit != null) ps.setInt(8, limit);
        };
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
