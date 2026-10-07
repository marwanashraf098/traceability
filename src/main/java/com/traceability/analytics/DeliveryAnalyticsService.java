package com.traceability.analytics;

import com.traceability.analytics.AnalyticsSql.Compared;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static com.traceability.analytics.AnalyticsSql.money;
import static com.traceability.analytics.AnalyticsSql.rate;

/**
 * Analytics slice 5 — delivery: success rate, lost sales, handover and delivery times, the 8-week
 * success trend, fulfillment-speed buckets, and why deliveries failed. Same cohort as revenue (sold
 * orders placed in the period) and the slice-2 outcomes: delivered vs failed = refused (incl. Return
 * to Origin) + other terminal (lost / terminated / cancelled). Orders not shipped, still moving or
 * shipped with another carrier are neither. Current + previous period.
 */
@Service
public class DeliveryAnalyticsService {

    public record Week(LocalDate weekStart, long delivered, long failed, BigDecimal successRate) {}

    public record SpeedBucket(String bucket, long orders, long delivered, long failed, BigDecimal successRate) {}

    public record Summary(BigDecimal successRate, long delivered, long failed, BigDecimal lostSalesValue,
                          BigDecimal avgHoursOrderToHanded, long handedOrders,
                          BigDecimal avgHoursHandedToDelivered, BigDecimal avgHoursHandedToDeliveredCairoGiza,
                          BigDecimal avgHoursHandedToDeliveredOther, List<Week> weeklyTrend,
                          List<SpeedBucket> fulfillmentSpeed) {}

    public record Reason(String reason, long count, BigDecimal share) {}

    public record FailureReasons(long failedLegs, long withReason, BigDecimal coverage, List<Reason> reasons) {}

    static final List<String> SPEED_BUCKETS = List.of("same_day", "1_day", "2_days", "3_plus_days");

    private final JdbcTemplate jdbc;
    private final AnalyticsFloorOverrides overrides;

    public DeliveryAnalyticsService(JdbcTemplate jdbc, AnalyticsFloorOverrides overrides) {
        this.jdbc = jdbc;
        this.overrides = overrides;
    }

    // ── /delivery/summary ───────────────────────────────────────────────────

    /** One facts query covering both periods and both 8-week trends, split by placed_at. */
    @Transactional(readOnly = true)
    public Compared<Summary> summary(AnalyticsPeriod period) {
        AnalyticsPeriod prev = AnalyticsSql.previous(period);
        AnalyticsPeriod curWeeks = trendWindow(period), prevWeeks = trendWindow(prev);
        AnalyticsPeriod window = OrderFacts.span(OrderFacts.span(prev, prevWeeks), OrderFacts.span(period, curWeeks));
        List<OrderFacts.Order> all = OrderFacts.load(jdbc, TenantContext.require(), window, overrides);
        return new Compared<>(period.range(), prev.range(),
            summarise(OrderFacts.within(all, period), OrderFacts.within(all, curWeeks), curWeeks.from()),
            summarise(OrderFacts.within(all, prev), OrderFacts.within(all, prevWeeks), prevWeeks.from()));
    }

    /** The 8 Cairo weeks (Monday first) ending with the week that holds the period's last day. */
    static AnalyticsPeriod trendWindow(AnalyticsPeriod p) {
        LocalDate lastMonday = p.to().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new AnalyticsPeriod(lastMonday.minusWeeks(7), p.to());
    }

    static Summary summarise(List<OrderFacts.Order> orders, List<OrderFacts.Order> trendOrders, LocalDate trendStart) {
        long delivered = 0, failed = 0;
        BigDecimal lost = BigDecimal.ZERO;
        double handSum = 0, delSum = 0, cgSum = 0, otherSum = 0;
        long handN = 0, delN = 0, cgN = 0, otherN = 0;
        long[][] speed = new long[4][3];   // orders, delivered, failed
        for (OrderFacts.Order o : orders) {
            if (o.delivered()) delivered++;
            if (o.failed()) {
                failed++;
                lost = lost.add(o.booked());
            }
            if (o.handedAt() != null && !o.handedAt().isBefore(o.placedAt())) {
                handSum += hours(o.placedAt(), o.handedAt());
                handN++;
                long days = ChronoUnit.DAYS.between(o.placedAt().atZone(AnalyticsPeriod.CAIRO).toLocalDate(),
                    o.handedAt().atZone(AnalyticsPeriod.CAIRO).toLocalDate());
                int b = (int) Math.min(3, days);
                speed[b][0]++;
                if (o.delivered()) speed[b][1]++;
                if (o.failed()) speed[b][2]++;
            }
            if (o.delivered() && o.handedAt() != null && o.deliveredAt() != null && !o.deliveredAt().isBefore(o.handedAt())) {
                double h = hours(o.handedAt(), o.deliveredAt());
                delSum += h;
                delN++;
                if (isCairoGiza(o.cityName())) {
                    cgSum += h;
                    cgN++;
                } else {
                    otherSum += h;
                    otherN++;
                }
            }
        }
        List<SpeedBucket> buckets = new ArrayList<>();
        for (int b = 0; b < 4; b++) {
            buckets.add(new SpeedBucket(SPEED_BUCKETS.get(b), speed[b][0], speed[b][1], speed[b][2],
                rate(speed[b][1], speed[b][1] + speed[b][2])));
        }
        return new Summary(rate(delivered, delivered + failed), delivered, failed, money(lost),
            avg(handSum, handN), handN, avg(delSum, delN), avg(cgSum, cgN), avg(otherSum, otherN),
            weekly(trendOrders, trendStart), buckets);
    }

    static List<Week> weekly(List<OrderFacts.Order> orders, LocalDate start) {
        long[][] w = new long[8][2];
        for (OrderFacts.Order o : orders) {
            long i = ChronoUnit.WEEKS.between(start, o.placedAt().atZone(AnalyticsPeriod.CAIRO).toLocalDate());
            if (i < 0 || i > 7) continue;
            if (o.delivered()) w[(int) i][0]++;
            if (o.failed()) w[(int) i][1]++;
        }
        List<Week> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) out.add(new Week(start.plusWeeks(i), w[i][0], w[i][1], rate(w[i][0], w[i][0] + w[i][1])));
        return out;
    }

    static boolean isCairoGiza(String city) {
        if (city == null) return false;
        String c = city.trim().toLowerCase(Locale.ROOT);
        return c.equals("cairo") || c.equals("giza");
    }

    private static double hours(java.time.Instant a, java.time.Instant b) {
        return Duration.between(a, b).toMinutes() / 60.0;
    }

    private static BigDecimal avg(double sum, long n) {
        return n == 0 ? null : BigDecimal.valueOf(sum / n).setScale(1, RoundingMode.HALF_UP);
    }

    // ── /delivery/failure-reasons ───────────────────────────────────────────

    @Transactional(readOnly = true)
    public Compared<FailureReasons> failureReasons(AnalyticsPeriod period) {
        AnalyticsPeriod prev = AnalyticsSql.previous(period);
        List<OrderFacts.Order> all = OrderFacts.load(jdbc, TenantContext.require(), OrderFacts.span(prev, period), overrides);
        return new Compared<>(period.range(), prev.range(), reasons(OrderFacts.within(all, period)),
            reasons(OrderFacts.within(all, prev)));
    }

    /** Over failed orders (refused + other terminal); share = of the failed legs that have a reason. */
    static FailureReasons reasons(List<OrderFacts.Order> orders) {
        Map<String, Long> counts = new LinkedHashMap<>();
        AnalyticsMappings.FAILURE_REASONS.forEach(r -> counts.put(r, 0L));
        long failed = 0, withReason = 0;
        for (OrderFacts.Order o : orders) {
            if (!o.failed()) continue;
            failed++;
            String r = AnalyticsMappings.failureReason(o.failureReason());
            if (r == null) continue;
            withReason++;
            counts.merge(r, 1L, Long::sum);
        }
        List<Reason> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) out.add(new Reason(e.getKey(), e.getValue(), rate(e.getValue(), withReason)));
        out.sort(Comparator.comparing(Reason::count).reversed()
            .thenComparing(r -> AnalyticsMappings.FAILURE_REASONS.indexOf(r.reason())));
        return new FailureReasons(failed, withReason, rate(withReason, failed), out);
    }
}
