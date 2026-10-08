package com.traceability.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Analytics slice 5 — small shared helpers for the breakdown services. */
public final class AnalyticsSql {

    private AnalyticsSql() {}

    /**
     * A figure for the period and for the previous period of the same length, right before it.
     * previousRange / previous are null when the previous period was not computed (see
     * {@link #previousOrNull}).
     */
    public record Compared<T>(AnalyticsPeriod.Range range, AnalyticsPeriod.Range previousRange,
                              T current, T previous) {}

    /** Periods longer than this skip the previous-period comparison unless compare=true. */
    static final int COMPARE_MAX_DAYS = 92;

    /**
     * The previous period, or null for a period longer than {@link #COMPARE_MAX_DAYS} days when the
     * caller didn't ask to compare — a year against the year before doubles the work for a delta the
     * screen rarely needs.
     */
    static AnalyticsPeriod previousOrNull(AnalyticsPeriod p, boolean compare) {
        long days = ChronoUnit.DAYS.between(p.from(), p.to()) + 1;
        return days > COMPARE_MAX_DAYS && !compare ? null : previous(p);
    }

    static AnalyticsPeriod.Range rangeOf(AnalyticsPeriod p) {
        return p == null ? null : p.range();
    }

    /** The previous period: same number of days, ending the day before {@code p.from()}. */
    static AnalyticsPeriod previous(AnalyticsPeriod p) {
        long days = ChronoUnit.DAYS.between(p.from(), p.to()) + 1;
        return new AnalyticsPeriod(p.from().minusDays(days), p.from().minusDays(1));
    }

    /** Binds SalesAnalyticsService.soldLines' 7 parameters; returns the next index. */
    static int bindSoldLines(PreparedStatement ps, UUID tid, AnalyticsPeriod p, AnalyticsFloorOverrides overrides)
            throws SQLException {
        ps.setTimestamp(1, Timestamp.from(p.startInclusive()));
        ps.setTimestamp(2, Timestamp.from(p.endExclusive()));
        ps.setArray(3, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
        ps.setArray(4, ps.getConnection().createArrayOf("text", overrides.days()));
        ps.setObject(5, tid);
        ps.setObject(6, tid);
        ps.setObject(7, tid);
        return 8;
    }

    /** numerator ÷ denominator to 4 decimals; null when the denominator is 0. */
    static BigDecimal rate(long numerator, long denominator) {
        return denominator == 0 ? null
            : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    static BigDecimal rate(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null
            : numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
