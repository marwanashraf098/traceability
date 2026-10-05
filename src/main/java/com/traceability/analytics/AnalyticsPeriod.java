package com.traceability.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * An analytics period: whole Africa/Cairo calendar days, [from 00:00 Cairo, to+1 00:00 Cairo).
 * The day boundaries are computed through the zone rules (never a fixed +02/+03 offset), so a
 * day that crosses Egypt's DST change is 23 or 25 hours long, as it really is.
 *
 * Presets end today (inclusive): today = 1 day, 7d = 7 days, 30d = 30 days. A custom range is
 * from/to (YYYY-MM-DD), both required, from ≤ to, at most {@link #MAX_DAYS} days. No parameters
 * at all = 30d. A preset and a custom range together are refused.
 */
public record AnalyticsPeriod(LocalDate from, LocalDate to) {

    public static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    public static final int MAX_DAYS = 366;

    public static AnalyticsPeriod resolve(String period, String fromStr, String toStr, LocalDate today) {
        boolean custom = fromStr != null || toStr != null;
        if (period != null && custom) {
            throw bad("Use either period or from/to, not both");
        }
        if (custom) {
            if (fromStr == null || toStr == null) throw bad("from and to are both required");
            LocalDate from, to;
            try {
                from = LocalDate.parse(fromStr);
                to   = LocalDate.parse(toStr);
            } catch (DateTimeParseException e) {
                throw bad("from/to must be YYYY-MM-DD");
            }
            if (from.isAfter(to)) throw bad("from must be on or before to");
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
                throw bad("The range can be at most " + MAX_DAYS + " days");
            }
            return new AnalyticsPeriod(from, to);
        }
        String p = period == null ? "30d" : period;
        return switch (p) {
            case "today" -> new AnalyticsPeriod(today, today);
            case "7d"    -> new AnalyticsPeriod(today.minusDays(6), today);
            case "30d"   -> new AnalyticsPeriod(today.minusDays(29), today);
            default      -> throw bad("period must be today, 7d or 30d");
        };
    }

    public Instant startInclusive() {
        return from.atStartOfDay(CAIRO).toInstant();
    }

    public Instant endExclusive() {
        return to.plusDays(1).atStartOfDay(CAIRO).toInstant();
    }

    public Range range() {
        return new Range(from.toString(), to.toString(), CAIRO.getId());
    }

    public record Range(String from, String to, String tz) {}

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
