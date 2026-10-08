package com.traceability.analytics;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Analytics slice 5 — one row per SOLD order of a period (the slice 1/2 cohort: post-floor, not
 * cancelled, not an internal exchange order, at least one sold line placed in the period), with
 * everything the revenue / delivery breakdowns group on. Built on the shared s1/s2 SQL
 * (SalesAnalyticsService.soldLines + ORDER_OUTCOMES + LINE_RETURNS), so booked / outcome / returns
 * mean exactly what they mean on the sales endpoints. Money is rounded to 2 decimals PER ORDER, so
 * every sum the services build from these rows adds up to the cent (the waterfall reconciles).
 * Money is aggregated straight from line_facts (lines ⋈ outcomes, merge-joined), never by joining
 * two aggregated CTEs: the planner's ~1-row jsonb estimates turned that into a 1129 × 1129 nested
 * loop (prod EXPLAIN, Femine 30 days, 2026-10-07).
 *
 * The services load ONE window covering the period, the previous period (and the 8-week trend) and
 * split the rows by placed_at in Java, instead of running this per period.
 *
 * Built on the caller's JdbcTemplate (app_user under RLS in the app; every statement also binds the
 * tenant). Not a bean.
 */
final class OrderFacts {

    /** One sold order. Money: booked / gross / discounts / returned to 2 decimals. */
    record Order(UUID orderId, Instant placedAt, BigDecimal booked, BigDecimal gross, BigDecimal discCode,
                 BigDecimal discAuto, long approximateLines, String outcome, String cityId, String cityName,
                 BigDecimal returned, String channel, String paymentGroup,
                 String provinceCode, boolean shopifyFulfilled, Instant handedAt, Instant deliveredAt,
                 String settlementStatus, String failureCategory) {

        BigDecimal discounts() {
            return gross.subtract(booked);
        }

        boolean delivered() {
            return "delivered".equals(outcome);
        }

        boolean failed() {
            return "refused".equals(outcome) || "other_terminal".equals(outcome);
        }

        /** Realized = booked of a delivered order less its customer returns; 0 otherwise. */
        BigDecimal realized() {
            return delivered() ? booked.subtract(returned) : BigDecimal.ZERO;
        }

        /** Fulfilled: Shopify fulfilled it, Bosta collected it, or it reached a delivered / refused end. */
        boolean fulfilled() {
            return shopifyFulfilled || handedAt != null || delivered() || "refused".equals(outcome);
        }

        boolean paidToYou() {
            return delivered() && "paid".equals(settlementStatus);
        }
    }

    /*
     * Parameters: soldLines(false)'s 7, ORDER_OUTCOMES' 2, LINE_RETURNS' 4, then 1 more tenant id
     * (the deciding leg's shipment row).
     *   handed_at  — Bosta's collectedFromBusiness on the leg, else the leg's first with_courier
     *                history row;
     *   delivered_at — shipments.delivered_at, else the first delivered history row.
     */
    static final String SQL = SalesAnalyticsService.soldLines(false) + SalesAnalyticsService.ORDER_OUTCOMES
        + SalesAnalyticsService.LINE_RETURNS + """
        , order_money AS (
            SELECT lf.order_id, lf.outcome, lf.city_id, lf.city, lf.shipment_id,
                   MIN(lf.placed_at)                                         AS placed_at,
                   ROUND(SUM(lf.qty * lf.unit_price), 2)                     AS booked,
                   ROUND(SUM(lf.gross), 2)                                   AS gross,
                   ROUND(SUM(lf.disc_code), 2)                               AS disc_code,
                   ROUND(SUM(lf.disc_auto), 2)                               AS disc_auto,
                   COUNT(*) FILTER (WHERE lf.approximate)                    AS approx_lines,
                   ROUND(COALESCE(SUM(lf.returned * lf.unit_price) FILTER (WHERE lf.outcome = 'delivered'), 0), 2)
                                                                             AS returned_rev
            FROM line_facts lf
            GROUP BY lf.order_id, lf.outcome, lf.city_id, lf.city, lf.shipment_id
        )
        SELECT om.order_id, om.placed_at, om.booked, om.gross, om.disc_code, om.disc_auto, om.approx_lines,
               om.outcome, om.city_id, om.city, om.returned_rev,
               o.channel, o.payment_group, o.ship_province AS province_code, o.shopify_fulfilled,
               lg.handed_at, lg.delivered_at, lg.settlement_status, lg.failure_category
        FROM order_money om
        JOIN orders o          ON o.id = om.order_id
        LEFT JOIN LATERAL (
            SELECT COALESCE(sh.collected_from_business_at,
                       (SELECT MIN(h.occurred_at) FROM shipment_status_history h
                        WHERE h.shipment_id = sh.id AND h.internal_state = 'with_courier'))   AS handed_at,
                   COALESCE(sh.delivered_at,
                       (SELECT MIN(h.occurred_at) FROM shipment_status_history h
                        WHERE h.shipment_id = sh.id AND h.internal_state = 'delivered'))      AS delivered_at,
                   sh.settlement_status,
                   sh.last_failure_category                                                   AS failure_category
            FROM shipments sh
            WHERE sh.id = om.shipment_id AND sh.tenant_id = ?
        ) lg ON true
        """;

    private OrderFacts() {}

    static List<Order> load(JdbcTemplate jdbc, UUID tid, AnalyticsPeriod period, AnalyticsFloorOverrides overrides) {
        return jdbc.query(SQL, ps -> {
            int i = AnalyticsSql.bindSoldLines(ps, tid, period, overrides);
            for (int k = 0; k < 7; k++) ps.setObject(i++, tid);   // outcomes 2, returns 4, leg 1
        }, (rs, n) -> row(rs));
    }

    /** The orders placed in {@code p} (Cairo days), out of a wider window's rows. */
    static List<Order> within(List<Order> orders, AnalyticsPeriod p) {
        Instant from = p.startInclusive(), to = p.endExclusive();
        List<Order> out = new ArrayList<>();
        for (Order o : orders) {
            if (!o.placedAt().isBefore(from) && o.placedAt().isBefore(to)) out.add(o);
        }
        return out;
    }

    /** The smallest period covering both. */
    static AnalyticsPeriod span(AnalyticsPeriod a, AnalyticsPeriod b) {
        return new AnalyticsPeriod(a.from().isBefore(b.from()) ? a.from() : b.from(),
                                   a.to().isAfter(b.to()) ? a.to() : b.to());
    }

    private static Order row(ResultSet rs) throws SQLException {
        return new Order(
            rs.getObject("order_id", UUID.class), instant(rs.getTimestamp("placed_at")),
            nz(rs.getBigDecimal("booked")), nz(rs.getBigDecimal("gross")), nz(rs.getBigDecimal("disc_code")),
            nz(rs.getBigDecimal("disc_auto")), rs.getLong("approx_lines"), rs.getString("outcome"),
            rs.getString("city_id"), rs.getString("city"), nz(rs.getBigDecimal("returned_rev")),
            rs.getString("channel"), rs.getString("payment_group"), rs.getString("province_code"),
            rs.getBoolean("shopify_fulfilled"), instant(rs.getTimestamp("handed_at")),
            instant(rs.getTimestamp("delivered_at")), rs.getString("settlement_status"),
            rs.getString("failure_category"));
    }

    /**
     * Bosta's cities (global reference data): id → [English, Arabic], and lower-case English name →
     * id (the lowest id when two cities share a name, so the answer never depends on row order).
     */
    record Cities(Map<String, String[]> byId, Map<String, String> idByName) {}

    static Cities cities(JdbcTemplate jdbc) {
        Map<String, String[]> byId = new HashMap<>();
        Map<String, String> byName = new HashMap<>();
        jdbc.query("SELECT city_id, MAX(city_name) AS en, MAX(city_name_ar) AS ar FROM bosta_districts " +
                   "WHERE city_id IS NOT NULL GROUP BY city_id ORDER BY city_id", rs -> {
            String id = rs.getString("city_id"), en = rs.getString("en");
            byId.put(id, new String[] {en, rs.getString("ar")});
            if (en != null) byName.putIfAbsent(en.trim().toLowerCase(java.util.Locale.ROOT), id);
        });
        return new Cities(byId, byName);
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static Object[] array(Array a) throws SQLException {
        return a == null ? new Object[0] : (Object[]) a.getArray();
    }
}
