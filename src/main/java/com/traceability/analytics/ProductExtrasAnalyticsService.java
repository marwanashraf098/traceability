package com.traceability.analytics;

import com.traceability.analytics.AnalyticsSql.Compared;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.traceability.analytics.AnalyticsSql.money;
import static com.traceability.analytics.AnalyticsSql.rate;

/**
 * Analytics slice 5 — product extras over the period's sold lines (slice 1/2 cohort): ABC class per
 * variant by realized revenue, the most-failed products, the size curve with returns and exchanges
 * by size, and variants frequently bought together. Current + previous period.
 */
@Service
public class ProductExtrasAnalyticsService {

    public record AbcRow(UUID variantId, String sku, String productTitle, String variantTitle, BigDecimal realized,
                         BigDecimal share, BigDecimal cumulativeShare, String abcClass) {}

    public record FailedProduct(UUID productId, String title, long orders, long failedOrders, BigDecimal failureRate) {}

    public record SizeRow(String size, long soldUnits, BigDecimal share, long deliveredUnits, long returnedUnits,
                          BigDecimal returnRate, long exchangedUnits) {}

    public record SizeCurve(List<SizeRow> sizes, long unparseableUnits, List<String> unparseableValues,
                            long noSizeUnits) {}

    public record Pair(UUID variantA, String titleA, UUID variantB, String titleB, long orders) {}

    public record Extras(List<AbcRow> abc, List<FailedProduct> mostFailed, SizeCurve sizeCurve,
                         List<Pair> boughtTogether) {}

    static final int MOST_FAILED_MIN_ORDERS = 20;
    static final int PAIR_MIN_ORDERS = 3;

    /*
     * ONE statement over previous + current period (slice 8 — it used to be three, each re-reading
     * the lines and outcomes); is_current = the order was placed on or after the period start. Rows
     * of three kinds share the CTEs:
     *   'v' per variant — sold / delivered / returned units, realized revenue, exchanged units
     *       (portal exchange requests' items, not rejected, and dashboard exchanges with no request,
     *       not dismissed / cancelled — the old (inbound) variant), and the size: the variant's value
     *       of the product option named like "size" (products.size_position / variants.option1-3);
     *   'p' per product — orders, delivered and failed (refused + other terminal) orders, filtered
     *       to MOST_FAILED_MIN_ORDERS in Java;
     *   'x' variant pairs on the same order in at least PAIR_MIN_ORDERS orders.
     * Parameters after the shared 13: period start, tenant id, tenant id, period start, period start,
     * period start, pair minimum.
     */
    private static final String EXTRAS_SQL = SalesAnalyticsService.soldLines(false)
        + SalesAnalyticsService.ORDER_OUTCOMES + SalesAnalyticsService.LINE_RETURNS + """
        , order_period AS (
            SELECT DISTINCT order_id, placed_at >= ?::timestamptz AS is_current FROM lines
        ),
        exch AS (
            SELECT op.is_current, rri.variant_id, COUNT(*) AS units
            FROM return_request_items rri
            JOIN return_requests rr ON rr.id = rri.request_id
            JOIN order_period op    ON op.order_id = rr.order_id
            WHERE rri.tenant_id = ? AND rr.type = 'exchange' AND rr.status <> 'rejected'
            GROUP BY op.is_current, rri.variant_id
            UNION ALL
            SELECT op.is_current, x.inbound_variant_id, COUNT(*)
            FROM exchanges x
            JOIN order_period op ON op.order_id = x.matched_order_id
            WHERE x.tenant_id = ? AND x.return_request_id IS NULL
              AND x.status NOT IN ('dismissed', 'cancelled') AND x.inbound_variant_id IS NOT NULL
            GROUP BY op.is_current, x.inbound_variant_id
        ),
        exch_v AS (
            SELECT is_current, variant_id, SUM(units) AS units FROM exch GROUP BY is_current, variant_id
        ),
        per_variant AS (
            SELECT lf.placed_at >= ?::timestamptz                                     AS is_current,
                   lf.variant_id,
                   SUM(lf.qty)                                                          AS sold,
                   COALESCE(SUM(lf.qty) FILTER (WHERE lf.outcome = 'delivered'), 0)      AS delivered_units,
                   COALESCE(SUM(lf.returned) FILTER (WHERE lf.outcome = 'delivered'), 0) AS returned,
                   COALESCE(SUM((lf.qty - lf.returned) * lf.unit_price) FILTER (WHERE lf.outcome = 'delivered'), 0)
                                                                                        AS realized
            FROM line_facts lf
            GROUP BY 1, 2
        ),
        per_product AS (
            SELECT lf.placed_at >= ?::timestamptz AS is_current, v.product_id, p.title,
                   COUNT(DISTINCT lf.order_id)                                                          AS orders,
                   COUNT(DISTINCT lf.order_id) FILTER (WHERE lf.outcome = 'delivered')                    AS delivered,
                   COUNT(DISTINCT lf.order_id) FILTER (WHERE lf.outcome IN ('refused', 'other_terminal')) AS failed
            FROM line_facts lf
            JOIN variants v ON v.id = lf.variant_id
            JOIN products p ON p.id = v.product_id
            GROUP BY 1, v.product_id, p.title
        ),
        order_variants AS (
            SELECT DISTINCT order_id, variant_id, placed_at >= ?::timestamptz AS is_current FROM lines
        ),
        pairs AS (
            SELECT a.is_current, a.variant_id AS va, b.variant_id AS vb, COUNT(*) AS orders
            FROM order_variants a
            JOIN order_variants b ON b.order_id = a.order_id AND a.variant_id < b.variant_id
            GROUP BY a.is_current, a.variant_id, b.variant_id
            HAVING COUNT(*) >= ?
        )
        SELECT 'v' AS kind, pv.is_current, pv.variant_id, pv.sold, pv.delivered_units, pv.returned, pv.realized,
               COALESCE(ev.units, 0) AS exchanged, v.sku, v.title AS variant_title, p.title AS product_title,
               CASE p.size_position WHEN 1 THEN v.option1 WHEN 2 THEN v.option2 WHEN 3 THEN v.option3 END AS size_raw,
               p.size_position IS NOT NULL AS has_size_option,
               NULL::uuid AS product_id, NULL::text AS title, NULL::bigint AS orders, NULL::bigint AS delivered,
               NULL::bigint AS failed, NULL::uuid AS vb, NULL::text AS title_a, NULL::text AS title_b
        FROM per_variant pv
        JOIN variants v ON v.id = pv.variant_id
        JOIN products p ON p.id = v.product_id
        LEFT JOIN exch_v ev ON ev.variant_id = pv.variant_id AND ev.is_current = pv.is_current
        UNION ALL
        SELECT 'p', pp.is_current, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
               pp.product_id, pp.title, pp.orders, pp.delivered, pp.failed, NULL, NULL, NULL
        FROM per_product pp
        UNION ALL
        SELECT 'x', pr.is_current, pr.va, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
               NULL, NULL, pr.orders, NULL, NULL, pr.vb,
               pa.title || ' — ' || COALESCE(va.title, ''), pb.title || ' — ' || COALESCE(vb.title, '')
        FROM pairs pr
        JOIN variants va ON va.id = pr.va JOIN products pa ON pa.id = va.product_id
        JOIN variants vb ON vb.id = pr.vb JOIN products pb ON pb.id = vb.product_id
        """;

    private final JdbcTemplate jdbc;
    private final AnalyticsFloorOverrides overrides;

    public ProductExtrasAnalyticsService(JdbcTemplate jdbc, AnalyticsFloorOverrides overrides) {
        this.jdbc = jdbc;
        this.overrides = overrides;
    }

    @Transactional(readOnly = true)
    public Compared<Extras> extras(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        AnalyticsPeriod prev = AnalyticsSql.previous(period);
        AnalyticsPeriod window = OrderFacts.span(prev, period);
        java.sql.Timestamp curStart = java.sql.Timestamp.from(period.startInclusive());
        Map<Boolean, List<VariantRow>> variants = split();
        Map<Boolean, List<FailedProduct>> failed = split();
        Map<Boolean, List<Pair>> pairs = split();
        jdbc.query(EXTRAS_SQL, ps -> {
            int i = AnalyticsSql.bindSoldLines(ps, tid, window, overrides);
            for (int k = 0; k < 6; k++) ps.setObject(i++, tid);
            ps.setTimestamp(i++, curStart);          // order_period
            ps.setObject(i++, tid);                  // exch: requests
            ps.setObject(i++, tid);                  // exch: dashboard exchanges
            ps.setTimestamp(i++, curStart);          // per_variant
            ps.setTimestamp(i++, curStart);          // per_product
            ps.setTimestamp(i++, curStart);          // order_variants
            ps.setInt(i, PAIR_MIN_ORDERS);
        }, rs -> {
            boolean current = rs.getBoolean("is_current");
            switch (rs.getString("kind")) {
                case "v" -> variants.get(current).add(new VariantRow(rs.getObject("variant_id", UUID.class),
                    rs.getLong("sold"), rs.getLong("delivered_units"), rs.getLong("returned"), rs.getBigDecimal("realized"),
                    rs.getLong("exchanged"), rs.getString("sku"), rs.getString("variant_title"),
                    rs.getString("product_title"), rs.getString("size_raw"), rs.getBoolean("has_size_option")));
                case "p" -> {
                    long orders = rs.getLong("orders"), d = rs.getLong("delivered"), f = rs.getLong("failed");
                    if (orders >= MOST_FAILED_MIN_ORDERS) {
                        // failureRate = failed ÷ (delivered + failed) — the one success / failure definition.
                        failed.get(current).add(new FailedProduct(rs.getObject("product_id", UUID.class),
                            rs.getString("title"), orders, f, rate(f, d + f)));
                    }
                }
                default -> pairs.get(current).add(new Pair(rs.getObject("variant_id", UUID.class), rs.getString("title_a"),
                    rs.getObject("vb", UUID.class), rs.getString("title_b"), rs.getLong("orders")));
            }
        });
        return new Compared<>(period.range(), prev.range(), extrasOf(variants.get(true), failed.get(true), pairs.get(true)),
            extrasOf(variants.get(false), failed.get(false), pairs.get(false)));
    }

    private static <T> Map<Boolean, List<T>> split() {
        Map<Boolean, List<T>> m = new HashMap<>();
        m.put(true, new ArrayList<>());
        m.put(false, new ArrayList<>());
        return m;
    }

    record VariantRow(UUID variantId, long sold, long deliveredUnits, long returned, BigDecimal realized,
                      long exchanged, String sku, String variantTitle, String productTitle, String sizeRaw,
                      boolean hasSizeOption) {}

    /** Most-failed: highest failure rate, then most orders, top 10. Pairs: most orders, top 10. */
    static Extras extrasOf(List<VariantRow> variants, List<FailedProduct> failed, List<Pair> pairs) {
        List<FailedProduct> f = new ArrayList<>(failed);
        f.sort(Comparator.comparing(FailedProduct::failureRate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Comparator.comparingLong(FailedProduct::orders).reversed())
            .thenComparing(x -> Objects.toString(x.title(), "")));
        List<Pair> p = new ArrayList<>(pairs);
        p.sort(Comparator.comparingLong(Pair::orders).reversed().thenComparing(Pair::titleA).thenComparing(Pair::titleB));
        return new Extras(abc(variants), f.subList(0, Math.min(10, f.size())), sizeCurve(variants),
            p.subList(0, Math.min(10, p.size())));
    }

    /**
     * ABC by realized revenue, highest first: A while the realized share of the variants before it
     * is under 80 % (the variant crossing 80 % is still A), B under 95 %, else C. Variants with no
     * positive realized revenue are C.
     */
    static List<AbcRow> abc(List<VariantRow> variants) {
        List<VariantRow> sorted = new ArrayList<>(variants);
        sorted.sort(Comparator.comparing((VariantRow v) -> v.realized()).reversed()
            .thenComparing(v -> Objects.toString(v.productTitle(), ""))
            .thenComparing(v -> Objects.toString(v.variantTitle(), "")));
        BigDecimal total = sorted.stream().map(VariantRow::realized).filter(r -> r.signum() > 0)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AbcRow> out = new ArrayList<>();
        BigDecimal before = BigDecimal.ZERO;
        for (VariantRow v : sorted) {
            boolean positive = v.realized().signum() > 0 && total.signum() > 0;
            BigDecimal share = positive ? v.realized().divide(total, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal shareBefore = total.signum() > 0 ? before.divide(total, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            String cls = !positive ? "C"
                : shareBefore.compareTo(new BigDecimal("0.80")) < 0 ? "A"
                : shareBefore.compareTo(new BigDecimal("0.95")) < 0 ? "B" : "C";
            if (positive) before = before.add(v.realized());
            BigDecimal cumulative = total.signum() > 0 ? before.divide(total, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            out.add(new AbcRow(v.variantId(), v.sku(), v.productTitle(), v.variantTitle(), money(v.realized()),
                share.setScale(4, RoundingMode.HALF_UP), cumulative, cls));
        }
        return out;
    }

    /**
     * Size per variant: the product's size option value, normalised (AnalyticsMappings.normaliseSize).
     * A product without a size option: the first " / " part of the variant title that is a size, else
     * the variant has no size (noSizeUnits). A size option value that won't normalise (e.g. "XL-XXL")
     * is excluded and counted (unparseableUnits / unparseableValues).
     */
    static SizeCurve sizeCurve(List<VariantRow> variants) {
        Map<String, long[]> bySize = new TreeMap<>(Comparator.comparingInt(AnalyticsMappings::sizeOrder)
            .thenComparing(Comparator.naturalOrder()));
        long unparseable = 0, noSize = 0;
        Set<String> badValues = new TreeSet<>();
        for (VariantRow v : variants) {
            String size;
            if (v.hasSizeOption()) {
                size = AnalyticsMappings.normaliseSize(v.sizeRaw());
                if (size == null) {
                    unparseable += v.sold();
                    if (v.sizeRaw() != null) badValues.add(v.sizeRaw().trim());
                    continue;
                }
            } else {
                size = null;
                if (v.variantTitle() != null) {
                    for (String part : v.variantTitle().split(" / ")) {
                        size = AnalyticsMappings.normaliseSize(part);
                        if (size != null) break;
                    }
                }
                if (size == null) {
                    noSize += v.sold();
                    continue;
                }
            }
            long[] a = bySize.computeIfAbsent(size, k -> new long[4]);
            a[0] += v.sold();
            a[1] += v.deliveredUnits();
            a[2] += v.returned();
            a[3] += v.exchanged();
        }
        long totalSold = bySize.values().stream().mapToLong(a -> a[0]).sum();
        List<SizeRow> rows = new ArrayList<>();
        bySize.forEach((s, a) -> rows.add(new SizeRow(s, a[0], rate(a[0], totalSold), a[1], a[2], rate(a[2], a[1]), a[3])));
        return new SizeCurve(rows, unparseable, new ArrayList<>(badValues), noSize);
    }
}
