package com.traceability.analytics;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.traceability.analytics.AnalyticsSql.money;
import static com.traceability.analytics.AnalyticsSql.rate;

/**
 * Analytics slice 10 — margin and profit, from variants.unit_cost (Shopify's cost when the read pass
 * stored it, EGP only, or a manual cost). Owner-only (controller). Costed / total is ALWAYS returned:
 * every cost figure covers costed lines only; with nothing costed they are null and the screen says
 * "needs cost per item in Shopify".
 *
 * Per sold line of the period (the s1 cohort, the s2 outcome and returns):
 *   kept units   = delivered qty − returned (0 unless delivered);
 *   realized     = kept units × unit price (the s2 realized revenue);
 *   COGS         = kept units × unit_cost (costed lines only);
 *   fees         = the order's Bosta fees (SettlementSql.fee — settled, else quote × 1.14 — over the
 *                  legs Bosta charges for, the s7 rule), shipping legs and the rest (failed, return,
 *                  exchange) apart, split across the order's lines by line value.
 * Gross margin = (realized − COGS) ÷ realized, costed lines; contribution profit = realized − COGS −
 * fees, costed lines; true net per SKU = realized − COGS − allocated shipping − allocated other fees.
 */
@Service
public class ProfitAnalyticsService {

    public record Coverage(long variantsSold, long variantsCosted, long keptUnits, long costedUnits,
                           BigDecimal realizedTotal, BigDecimal realizedCosted, BigDecimal costedRevenueShare) {}

    public record Summary(AnalyticsPeriod.Range range, Coverage coverage, BigDecimal cogs, BigDecimal grossProfit,
                          BigDecimal grossMargin, BigDecimal feesTotal, BigDecimal feesOnCosted,
                          BigDecimal contributionProfit, BigDecimal contributionMargin) {}

    public record TypeMargin(String productType, long keptUnits, long costedUnits, BigDecimal realized,
                             BigDecimal realizedCosted, BigDecimal cogs, BigDecimal grossMargin) {}

    public record ByType(AnalyticsPeriod.Range range, Coverage coverage, List<TypeMargin> types) {}

    public record SkuProfit(UUID variantId, String productTitle, String variantTitle, String sku, String productType,
                            boolean costed, BigDecimal unitCost, long keptUnits, BigDecimal realized, BigDecimal cogs,
                            BigDecimal shippingFees, BigDecimal otherFees, BigDecimal netBeforeCost, BigDecimal trueNet,
                            BigDecimal trueNetMargin) {}

    public record Skus(AnalyticsPeriod.Range range, Coverage coverage, String sort, long total, List<SkuProfit> skus) {}

    /* Parameters: the shared 13, then tenant id (the orders' legs). */
    private static final String PROFIT_SQL = SalesAnalyticsService.soldLines(false) + SalesAnalyticsService.ORDER_OUTCOMES
        + SalesAnalyticsService.LINE_RETURNS + """
        , order_val AS (
            SELECT order_id, SUM(qty * unit_price) AS val FROM line_facts GROUP BY order_id
        ),
        order_fees AS (
            SELECT ov.order_id, f.shipping, f.other
            FROM order_val ov
            CROSS JOIN LATERAL (
                SELECT SUM(c.fee) FILTER (WHERE c.charged AND c.kind = 'shipping')  AS shipping,
                       SUM(c.fee) FILTER (WHERE c.charged AND c.kind <> 'shipping') AS other
                FROM (
                    SELECT """ + SettlementSql.legKind("s") + """
                            AS kind,
                           """ + SettlementSql.fee("s") + """
                            AS fee,
                           (""" + SettlementSql.settled("s") + """
                            OR NOT (s.internal_state = 'cancelled'
                                    OR (s.internal_state = 'terminated' AND s.collected_from_business_at IS NULL))) AS charged
                    FROM shipments s
                    WHERE s.order_id = ov.order_id AND s.tenant_id = ? AND s.provider = 'bosta'
                ) c
            ) f
        ),
        per_line AS (
            SELECT lf.variant_id, v.unit_cost,
                   CASE WHEN lf.outcome = 'delivered' THEN lf.qty - lf.returned ELSE 0 END                  AS kept,
                   CASE WHEN lf.outcome = 'delivered' THEN (lf.qty - lf.returned) * lf.unit_price ELSE 0 END AS realized,
                   COALESCE(lf.qty * lf.unit_price / NULLIF(ov.val, 0), 0)                                    AS share,
                   COALESCE(ofe.shipping, 0) AS o_ship, COALESCE(ofe.other, 0) AS o_other
            FROM line_facts lf
            JOIN variants v    ON v.id = lf.variant_id
            JOIN order_val ov  ON ov.order_id = lf.order_id
            LEFT JOIN order_fees ofe ON ofe.order_id = lf.order_id
        )
        SELECT pl.variant_id, v.title AS variant_title, v.sku, p.title AS product_title, p.product_type_norm AS ptype,
               MAX(pl.unit_cost) AS unit_cost,
               SUM(pl.kept) AS kept, SUM(pl.realized) AS realized,
               SUM(pl.kept * pl.unit_cost) AS cogs,
               SUM(pl.o_ship * pl.share) AS ship, SUM(pl.o_other * pl.share) AS other
        FROM per_line pl
        JOIN variants v ON v.id = pl.variant_id
        JOIN products p ON p.id = v.product_id
        GROUP BY pl.variant_id, v.title, v.sku, p.title, p.product_type_norm
        """;

    private final JdbcTemplate jdbc;
    private final AnalyticsFloorOverrides overrides;

    public ProfitAnalyticsService(JdbcTemplate jdbc, AnalyticsFloorOverrides overrides) {
        this.jdbc = jdbc;
        this.overrides = overrides;
    }

    record Row(UUID variantId, String productTitle, String variantTitle, String sku, String ptype, BigDecimal unitCost,
               long kept, BigDecimal realized, BigDecimal cogs, BigDecimal ship, BigDecimal other) {
        boolean costed() {
            return unitCost != null;
        }
    }

    List<Row> load(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        return jdbc.query(PROFIT_SQL, ps -> {
            int i = AnalyticsSql.bindSoldLines(ps, tid, period, overrides);
            for (int k = 0; k < 6; k++) ps.setObject(i++, tid);
            ps.setObject(i, tid);
        }, (rs, n) -> new Row(rs.getObject("variant_id", UUID.class), rs.getString("product_title"), rs.getString("variant_title"),
            rs.getString("sku"), rs.getString("ptype"), rs.getBigDecimal("unit_cost"), rs.getLong("kept"),
            OrderFacts.nz(rs.getBigDecimal("realized")), rs.getBigDecimal("cogs"), OrderFacts.nz(rs.getBigDecimal("ship")),
            OrderFacts.nz(rs.getBigDecimal("other"))));
    }

    static Coverage coverage(List<Row> rows) {
        long sold = rows.size(), costed = 0, kept = 0, costedUnits = 0;
        BigDecimal all = BigDecimal.ZERO, costedRev = BigDecimal.ZERO;
        for (Row r : rows) {
            kept += r.kept();
            all = all.add(r.realized());
            if (r.costed()) {
                costed++;
                costedUnits += r.kept();
                costedRev = costedRev.add(r.realized());
            }
        }
        return new Coverage(sold, costed, kept, costedUnits, money(all), money(costedRev), rate(costedRev, all));
    }

    @Transactional(readOnly = true)
    public Summary summary(AnalyticsPeriod period) {
        List<Row> rows = load(period);
        Coverage cov = coverage(rows);
        BigDecimal cogs = BigDecimal.ZERO, fees = BigDecimal.ZERO, feesCosted = BigDecimal.ZERO;
        for (Row r : rows) {
            BigDecimal f = r.ship().add(r.other());
            fees = fees.add(f);
            if (r.costed()) {
                cogs = cogs.add(OrderFacts.nz(r.cogs()));
                feesCosted = feesCosted.add(f);
            }
        }
        if (cov.variantsCosted() == 0) {
            return new Summary(period.range(), cov, null, null, null, money(fees), null, null, null);
        }
        BigDecimal gross = cov.realizedCosted().subtract(cogs);
        BigDecimal contribution = gross.subtract(feesCosted);
        return new Summary(period.range(), cov, money(cogs), money(gross), rate(gross, cov.realizedCosted()), money(fees),
            money(feesCosted), money(contribution), rate(contribution, cov.realizedCosted()));
    }

    @Transactional(readOnly = true)
    public ByType byProductType(AnalyticsPeriod period) {
        List<Row> rows = load(period);
        Map<String, Object[]> acc = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
        for (Row r : rows) {
            Object[] a = acc.computeIfAbsent(r.ptype() == null ? "uncategorised" : r.ptype(),
                k -> new Object[] {0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = (Long) a[0] + r.kept();
            a[2] = ((BigDecimal) a[2]).add(r.realized());
            if (r.costed()) {
                a[1] = (Long) a[1] + r.kept();
                a[3] = ((BigDecimal) a[3]).add(r.realized());
                a[4] = ((BigDecimal) a[4]).add(OrderFacts.nz(r.cogs()));
            }
        }
        List<TypeMargin> out = new ArrayList<>();
        acc.forEach((k, a) -> {
            BigDecimal rc = (BigDecimal) a[3], cogs = (BigDecimal) a[4];
            boolean any = (Long) a[1] > 0 || rc.signum() > 0;
            out.add(new TypeMargin(k, (Long) a[0], (Long) a[1], money((BigDecimal) a[2]), money(rc),
                any ? money(cogs) : null, any ? rate(rc.subtract(cogs), rc) : null));
        });
        out.sort(Comparator.comparing(TypeMargin::realized).reversed().thenComparing(TypeMargin::productType));
        return new ByType(period.range(), coverage(rows), out);
    }

    public static final List<String> SKU_SORTS = List.of("trueNet", "realized", "margin");

    @Transactional(readOnly = true)
    public Skus skus(AnalyticsPeriod period, String sort, int limit) {
        List<Row> rows = load(period);
        List<SkuProfit> out = new ArrayList<>();
        for (Row r : rows) {
            BigDecimal before = r.realized().subtract(r.ship()).subtract(r.other());
            BigDecimal net = r.costed() ? before.subtract(OrderFacts.nz(r.cogs())) : null;
            out.add(new SkuProfit(r.variantId(), r.productTitle(), r.variantTitle(), r.sku(), r.ptype(), r.costed(),
                r.unitCost() == null ? null : money(r.unitCost()), r.kept(), money(r.realized()),
                r.costed() ? money(OrderFacts.nz(r.cogs())) : null, money(r.ship()), money(r.other()), money(before),
                net == null ? null : money(net), net == null ? null : rate(net, r.realized())));
        }
        Comparator<SkuProfit> c = switch (sort) {
            case "realized" -> Comparator.comparing(SkuProfit::realized).reversed();
            case "margin" -> Comparator.comparing(SkuProfit::trueNetMargin, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(SkuProfit::trueNet, Comparator.nullsLast(Comparator.naturalOrder()));
        };
        out.sort(c.thenComparing(s -> s.variantId().toString()));
        return new Skus(period.range(), coverage(rows), sort, out.size(), List.copyOf(out.subList(0, Math.min(limit, out.size()))));
    }
}
