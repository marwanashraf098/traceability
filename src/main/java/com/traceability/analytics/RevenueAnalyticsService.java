package com.traceability.analytics;

import com.traceability.analytics.AnalyticsSql.Compared;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.BiFunction;

import static com.traceability.analytics.AnalyticsSql.money;
import static com.traceability.analytics.AnalyticsSql.rate;

/**
 * Analytics slice 5 — revenue: summary (gross → discounts → booked → realized, a waterfall that
 * reconciles to the cent, the order funnel, per day), breakdowns by channel / payment / governorate
 * / product type, discount codes, and the weekday × hour heatmap. Every figure comes for the period
 * and the previous period of the same length. Cohort = the slice 1/2 sold orders (placed in the
 * period, post-floor, not cancelled, not internal exchange orders); outcomes are the slice-2 order
 * outcomes, so "realized" = booked of delivered orders less their customer returns.
 */
@Service
public class RevenueAnalyticsService {

    public record DiscountSplit(BigDecimal code, BigDecimal automatic, BigDecimal other, BigDecimal total) {}

    /**
     * gross − discounts − inTransit − notShipped − otherCarrier − refused − otherTerminal − returns
     * = netRealized, exactly (money is rounded per order before summing).
     */
    public record Waterfall(BigDecimal gross, BigDecimal discounts, BigDecimal inTransit, BigDecimal notShipped,
                            BigDecimal otherCarrier, BigDecimal refused, BigDecimal otherTerminal,
                            BigDecimal returns, BigDecimal netRealized) {}

    public record Funnel(long ordered, long fulfilled, long delivered, long paidToYou) {}

    public record Day(LocalDate date, BigDecimal booked, BigDecimal realized) {}

    public record Summary(BigDecimal grossSales, DiscountSplit discounts, BigDecimal booked, BigDecimal realized,
                          BigDecimal realizedShare, long orders, long approximateLines, Waterfall waterfall,
                          Funnel funnel, List<Day> daily) {}

    public record Group(String key, String label, String labelAr, BigDecimal booked, BigDecimal realized,
                        long orders, long deliveredOrders, long failedOrders, BigDecimal successRate) {}

    public record Breakdown(String by, List<Group> groups) {}

    public record DiscountRow(String code, String label, long orders, BigDecimal booked, BigDecimal discountCost,
                              long deliveredOrders, long failedOrders, BigDecimal successRate,
                              BigDecimal revenuePerCost) {}

    public record Discounts(List<DiscountRow> codes, DiscountRow automatic) {}

    public record Cell(int weekday, int hour, long orders, BigDecimal avgOrders) {}

    public record Heatmap(List<Cell> cells, Map<Integer, Integer> weekdayOccurrences) {}

    public enum By { CHANNEL, PAYMENT, GOVERNORATE, PRODUCT_TYPE }

    private final JdbcTemplate jdbc;
    private final AnalyticsFloorOverrides overrides;

    public RevenueAnalyticsService(JdbcTemplate jdbc, AnalyticsFloorOverrides overrides) {
        this.jdbc = jdbc;
        this.overrides = overrides;
    }

    /** One facts query over previous + current period, split by placed_at. */
    private <T> Compared<T> compared(AnalyticsPeriod p, BiFunction<List<OrderFacts.Order>, AnalyticsPeriod, T> f) {
        AnalyticsPeriod prev = AnalyticsSql.previous(p);
        List<OrderFacts.Order> all = OrderFacts.load(jdbc, TenantContext.require(), OrderFacts.span(prev, p), overrides);
        return new Compared<>(p.range(), prev.range(),
            f.apply(OrderFacts.within(all, p), p), f.apply(OrderFacts.within(all, prev), prev));
    }

    // ── /revenue/summary ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Compared<Summary> summary(AnalyticsPeriod period) {
        return compared(period, RevenueAnalyticsService::summarise);
    }

    static Summary summarise(List<OrderFacts.Order> orders, AnalyticsPeriod p) {
        BigDecimal gross = BigDecimal.ZERO, code = BigDecimal.ZERO, auto = BigDecimal.ZERO, booked = BigDecimal.ZERO;
        Map<String, BigDecimal> byOutcome = new HashMap<>();
        BigDecimal returns = BigDecimal.ZERO;
        long approx = 0, fulfilled = 0, delivered = 0, paid = 0;
        TreeMap<LocalDate, BigDecimal[]> days = new TreeMap<>();
        for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1)) {
            days.put(d, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        }
        for (OrderFacts.Order o : orders) {
            gross = gross.add(o.gross());
            code = code.add(o.discCode());
            auto = auto.add(o.discAuto());
            booked = booked.add(o.booked());
            byOutcome.merge(o.outcome(), o.booked(), BigDecimal::add);
            if (o.delivered()) returns = returns.add(o.returned());
            approx += o.approximateLines();
            if (o.fulfilled()) fulfilled++;
            if (o.delivered()) delivered++;
            if (o.paidToYou()) paid++;
            BigDecimal[] day = days.get(o.placedAt().atZone(AnalyticsPeriod.CAIRO).toLocalDate());
            if (day != null) {
                day[0] = day[0].add(o.booked());
                day[1] = day[1].add(o.realized());
            }
        }
        BigDecimal discounts = gross.subtract(booked);
        BigDecimal deliveredBooked = byOutcome.getOrDefault("delivered", BigDecimal.ZERO);
        BigDecimal realized = deliveredBooked.subtract(returns);
        Waterfall w = new Waterfall(money(gross), money(discounts),
            money(byOutcome.get("in_transit")), money(byOutcome.get("not_shipped")), money(byOutcome.get("wijha")),
            money(byOutcome.get("refused")), money(byOutcome.get("other_terminal")), money(returns), money(realized));
        List<Day> daily = new ArrayList<>();
        days.forEach((d, v) -> daily.add(new Day(d, money(v[0]), money(v[1]))));
        return new Summary(money(gross),
            new DiscountSplit(money(code), money(auto), money(discounts.subtract(code).subtract(auto)), money(discounts)),
            money(booked), money(realized), rate(realized, booked), orders.size(), approx, w,
            new Funnel(orders.size(), fulfilled, delivered, paid), daily);
    }

    // ── /revenue/breakdown ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Compared<Breakdown> breakdown(AnalyticsPeriod period, By by) {
        if (by == By.PRODUCT_TYPE) return productTypes(period);
        OrderFacts.Cities cities = by == By.GOVERNORATE ? OrderFacts.cities(jdbc) : null;
        return compared(period, (orders, p) -> group(orders, by, cities));
    }

    static Breakdown group(List<OrderFacts.Order> orders, By by, OrderFacts.Cities cities) {
        Map<String, Acc> groups = new LinkedHashMap<>();
        if (by == By.CHANNEL) AnalyticsMappings.CHANNELS.forEach(c -> groups.put(c, new Acc(c, c, null)));
        if (by == By.PAYMENT) AnalyticsMappings.PAYMENTS.forEach(c -> groups.put(c, new Acc(c, c, null)));
        for (OrderFacts.Order o : orders) {
            String[] k = key(o, by, cities);
            groups.computeIfAbsent(k[0], x -> new Acc(k[0], k[1], k[2])).add(o);
        }
        List<Group> out = new ArrayList<>();
        for (Acc a : groups.values()) out.add(a.group());
        if (by == By.GOVERNORATE) {
            out.sort(Comparator.comparing(Group::booked).reversed()
                .thenComparing(g -> "unknown".equals(g.key()))
                .thenComparing(Group::label));
        }
        return new Breakdown(by.name().toLowerCase(Locale.ROOT).replace("_type", "Type"), out);
    }

    /** [key, label, labelAr]. */
    static String[] key(OrderFacts.Order o, By by, OrderFacts.Cities cities) {
        return switch (by) {
            case CHANNEL -> new String[] {o.channel(), o.channel(), null};          // orders.channel (V149)
            case PAYMENT -> new String[] {o.paymentGroup(), o.paymentGroup(), null}; // orders.payment_group (V149)
            case GOVERNORATE -> governorate(o, cities);
            case PRODUCT_TYPE -> throw new IllegalArgumentException("product type is line-level");
        };
    }

    /**
     * Bosta's city of the deciding leg when the order was booked with Bosta; else the Shopify
     * shipping province mapped to the Bosta city of that name; else Unknown.
     */
    static String[] governorate(OrderFacts.Order o, OrderFacts.Cities cities) {
        String id = o.cityId();
        String fallbackName = o.cityName();
        if (id == null && o.provinceCode() != null) {
            String bostaName = MoneyAnalyticsService.PROVINCE_TO_BOSTA_CITY.get(o.provinceCode().trim().toUpperCase(Locale.ROOT));
            if (bostaName != null) {
                id = cities.idByName().get(bostaName.toLowerCase(Locale.ROOT));
                fallbackName = bostaName;
            }
        }
        if (id == null) return new String[] {"unknown", "Unknown", "غير معروف"};
        String[] names = cities.byId().get(id);
        String en = fallbackName != null ? fallbackName : names != null && names[0] != null ? names[0] : id;
        String ar = names != null && names[1] != null ? names[1] : en;
        return new String[] {id, en, ar};
    }

    private static final class Acc {
        final String key, label, labelAr;
        BigDecimal booked = BigDecimal.ZERO, realized = BigDecimal.ZERO;
        long orders, delivered, failed;

        Acc(String key, String label, String labelAr) {
            this.key = key;
            this.label = label;
            this.labelAr = labelAr;
        }

        void add(OrderFacts.Order o) {
            booked = booked.add(o.booked());
            realized = realized.add(o.realized());
            orders++;
            if (o.delivered()) delivered++;
            if (o.failed()) failed++;
        }

        Group group() {
            return new Group(key, label, labelAr, money(booked), money(realized), orders, delivered, failed,
                rate(delivered, delivered + failed));
        }
    }

    /*
     * Product type per sold line: products.raw product_type (REST) / productType (GraphQL), blank →
     * Uncategorised. Orders count once per type they contain. One query over previous + current
     * period, split by is_current (the line's placed_at; parameter after the shared 13 = period start).
     */
    private static final String PRODUCT_TYPE_SQL = SalesAnalyticsService.soldLines(false)
        + SalesAnalyticsService.ORDER_OUTCOMES + SalesAnalyticsService.LINE_RETURNS + """
        SELECT lf.placed_at >= ?::timestamptz                                   AS is_current,
               p.product_type_norm                                                AS ptype,
               COALESCE(SUM(lf.qty * lf.unit_price), 0)                           AS booked,
               COALESCE(SUM((lf.qty - lf.returned) * lf.unit_price) FILTER (WHERE lf.outcome = 'delivered'), 0)
                                                                                  AS realized,
               COUNT(DISTINCT lf.order_id)                                        AS orders,
               COUNT(DISTINCT lf.order_id) FILTER (WHERE lf.outcome = 'delivered') AS delivered,
               COUNT(DISTINCT lf.order_id) FILTER (WHERE lf.outcome IN ('refused', 'other_terminal')) AS failed
        FROM line_facts lf
        JOIN variants v ON v.id = lf.variant_id
        JOIN products p ON p.id = v.product_id
        GROUP BY 1, 2
        ORDER BY booked DESC, ptype NULLS LAST
        """;

    private Compared<Breakdown> productTypes(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        AnalyticsPeriod prev = AnalyticsSql.previous(period);
        Map<Boolean, List<Group>> rows = new HashMap<>(Map.of(true, new ArrayList<>(), false, new ArrayList<>()));
        jdbc.query(PRODUCT_TYPE_SQL, ps -> {
            int i = AnalyticsSql.bindSoldLines(ps, tid, OrderFacts.span(prev, period), overrides);
            for (int k = 0; k < 6; k++) ps.setObject(i++, tid);
            ps.setTimestamp(i, java.sql.Timestamp.from(period.startInclusive()));
        }, rs -> {
            String t = rs.getString("ptype");
            long d = rs.getLong("delivered"), f = rs.getLong("failed");
            rows.get(rs.getBoolean("is_current")).add(new Group(t == null ? "uncategorised" : t,
                t == null ? "Uncategorised" : t, null, money(rs.getBigDecimal("booked")),
                money(rs.getBigDecimal("realized")), rs.getLong("orders"), d, f, rate(d, d + f)));
        });
        return new Compared<>(period.range(), prev.range(), new Breakdown("productType", rows.get(true)),
            new Breakdown("productType", rows.get(false)));
    }

    // ── /revenue/discounts ──────────────────────────────────────────────────

    /*
     * kind 'alloc': one line discount allocation (its cost scaled like the line's unit price —
     * qty ÷ the original quantity); kind 'app': the order used a discount code / automatic discount
     * (any target, shipping included). Codes are grouped case-insensitively.
     */
    private static final String DISCOUNTS_SQL = SalesAnalyticsService.soldLines(false) + """
        , line_allocs AS (
            SELECT l.order_id, o.discount_types[al.idx + 1] AS type, o.discount_labels[al.idx + 1] AS code,
                   l.qty * al.amt / NULLIF(GREATEST(COALESCE(oi.original_qty, oi.quantity), l.qty), 0) AS cost
            FROM lines l
            JOIN order_items oi ON oi.id = l.order_item_id
            JOIN orders o       ON o.id = l.order_id
            CROSS JOIN LATERAL unnest(CASE WHEN NOT l.approximate THEN oi.alloc_amounts END,
                                      CASE WHEN NOT l.approximate THEN oi.alloc_indexes END) AS al(amt, idx)
        ),
        order_apps AS (
            SELECT DISTINCT po.order_id, app.type, app.code
            FROM (SELECT DISTINCT order_id FROM lines) po
            JOIN orders o ON o.id = po.order_id
            CROSS JOIN LATERAL unnest(o.discount_types, o.discount_labels) AS app(type, code)
        )
        SELECT 'alloc' AS kind, order_id, type, code, cost FROM line_allocs WHERE type IN ('discount_code', 'automatic')
        UNION ALL
        SELECT 'app', order_id, type, code, NULL FROM order_apps WHERE type IN ('discount_code', 'automatic')
        """;

    @Transactional(readOnly = true)
    public Compared<Discounts> discounts(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        AnalyticsPeriod prev = AnalyticsSql.previous(period);
        AnalyticsPeriod window = OrderFacts.span(prev, period);
        List<OrderFacts.Order> all = OrderFacts.load(jdbc, tid, window, overrides);
        Map<UUID, OrderFacts.Order> cur = new HashMap<>(), before = new HashMap<>();
        for (OrderFacts.Order o : OrderFacts.within(all, period)) cur.put(o.orderId(), o);
        for (OrderFacts.Order o : OrderFacts.within(all, prev)) before.put(o.orderId(), o);
        Map<String, DiscAcc> curCodes = new TreeMap<>(), prevCodes = new TreeMap<>();
        DiscAcc curAuto = new DiscAcc(null), prevAuto = new DiscAcc(null);
        jdbc.query(DISCOUNTS_SQL, ps -> AnalyticsSql.bindSoldLines(ps, tid, window, overrides), rs -> {
            UUID order = rs.getObject("order_id", UUID.class);
            boolean isCurrent = cur.containsKey(order);
            if (!isCurrent && !before.containsKey(order)) return;
            boolean auto = "automatic".equals(rs.getString("type"));
            String code = rs.getString("code");
            DiscAcc acc = auto ? (isCurrent ? curAuto : prevAuto)
                : (isCurrent ? curCodes : prevCodes).computeIfAbsent(code == null ? "" : code, DiscAcc::new);
            acc.orders.add(order);
            BigDecimal cost = rs.getBigDecimal("cost");
            if (cost != null) acc.cost = acc.cost.add(cost);
        });
        return new Compared<>(period.range(), prev.range(), discountsOf(curCodes, curAuto, cur),
            discountsOf(prevCodes, prevAuto, before));
    }

    private static Discounts discountsOf(Map<String, DiscAcc> codes, DiscAcc automatic, Map<UUID, OrderFacts.Order> facts) {
        List<DiscountRow> rows = new ArrayList<>();
        for (DiscAcc a : codes.values()) rows.add(a.row(facts, a.code, a.code));
        rows.sort(Comparator.comparing(DiscountRow::orders).reversed().thenComparing(DiscountRow::code));
        return new Discounts(rows, automatic.row(facts, null, "Automatic discounts"));
    }

    private static final class DiscAcc {
        final String code;
        final Set<UUID> orders = new HashSet<>();
        BigDecimal cost = BigDecimal.ZERO;

        DiscAcc(String code) {
            this.code = code;
        }

        DiscountRow row(Map<UUID, OrderFacts.Order> facts, String code, String label) {
            BigDecimal booked = BigDecimal.ZERO;
            long delivered = 0, failed = 0;
            for (UUID id : orders) {
                OrderFacts.Order o = facts.get(id);
                if (o == null) continue;
                booked = booked.add(o.booked());
                if (o.delivered()) delivered++;
                if (o.failed()) failed++;
            }
            BigDecimal c = money(cost);
            return new DiscountRow(code, label, orders.size(), money(booked), c, delivered, failed,
                rate(delivered, delivered + failed), c.signum() == 0 ? null : rate(money(booked), c));
        }
    }

    // ── /revenue/heatmap ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Compared<Heatmap> heatmap(AnalyticsPeriod period) {
        return compared(period, RevenueAnalyticsService::heat);
    }

    /** Average orders per Cairo weekday (ISO 1 = Monday) × hour: count ÷ how many of that weekday the period has. */
    static Heatmap heat(List<OrderFacts.Order> orders, AnalyticsPeriod p) {
        long[][] counts = new long[8][24];
        for (OrderFacts.Order o : orders) {
            ZonedDateTime z = o.placedAt().atZone(AnalyticsPeriod.CAIRO);
            counts[z.getDayOfWeek().getValue()][z.getHour()]++;
        }
        Map<Integer, Integer> occ = new LinkedHashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) occ.put(d.getValue(), 0);
        for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1)) occ.merge(d.getDayOfWeek().getValue(), 1, Integer::sum);
        List<Cell> cells = new ArrayList<>();
        for (int wd = 1; wd <= 7; wd++) {
            for (int h = 0; h < 24; h++) {
                int n = occ.get(wd);
                cells.add(new Cell(wd, h, counts[wd][h], n == 0 ? null
                    : BigDecimal.valueOf(counts[wd][h]).divide(BigDecimal.valueOf(n), 2, java.math.RoundingMode.HALF_UP)));
            }
        }
        return new Heatmap(cells, occ);
    }
}
