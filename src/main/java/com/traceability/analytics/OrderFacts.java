package com.traceability.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                 BigDecimal returned, String sourceName, boolean hasSourceFields, String referringSite,
                 String landingSite, String orderStatusUrl, String shopDomain, List<String> gateways,
                 String provinceCode, boolean shopifyFulfilled, Instant handedAt, Instant deliveredAt,
                 String settlementStatus, String failureReason) {

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
               r.source_name, r.referring_site, r.landing_site, r.order_status_url, st.shop_domain,
               (o.raw -> 'source_name') IS NOT NULL OR (o.raw -> 'referring_site') IS NOT NULL
                   OR (o.raw -> 'landing_site') IS NOT NULL                                   AS has_source_fields,
               COALESCE(r.payment_gateway_names, r."paymentGatewayNames")::text             AS gateways,
               COALESCE(r.shipping_address ->> 'province_code', r."shippingAddress" ->> 'provinceCode') AS province_code,
               (EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(r.fulfillments) = 'array'
                                                                THEN r.fulfillments ELSE '[]'::jsonb END) f
                        WHERE COALESCE(f ->> 'status', '') NOT IN ('cancelled', 'error', 'failure'))
                OR r."displayFulfillmentStatus" IN ('FULFILLED', 'PARTIALLY_FULFILLED'))     AS shopify_fulfilled,
               lg.handed_at, lg.delivered_at, lg.settlement_status, lg.failure_reason
        FROM order_money om
        JOIN orders o          ON o.id = om.order_id
        JOIN stores st         ON st.id = o.store_id
        CROSS JOIN LATERAL jsonb_to_record(COALESCE(o.raw, '{}'::jsonb)) AS r(
            source_name text, referring_site text, landing_site text, order_status_url text,
            payment_gateway_names jsonb, "paymentGatewayNames" jsonb, shipping_address jsonb,
            "shippingAddress" jsonb, fulfillments jsonb, "displayFulfillmentStatus" text)
        LEFT JOIN LATERAL (
            SELECT COALESCE(
                       CASE WHEN (sh.raw ->> 'collectedFromBusiness') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
                            THEN (sh.raw ->> 'collectedFromBusiness')::timestamptz END,
                       (SELECT MIN(h.occurred_at) FROM shipment_status_history h
                        WHERE h.shipment_id = sh.id AND h.internal_state = 'with_courier'))   AS handed_at,
                   COALESCE(sh.delivered_at,
                       (SELECT MIN(h.occurred_at) FROM shipment_status_history h
                        WHERE h.shipment_id = sh.id AND h.internal_state = 'delivered'))      AS delivered_at,
                   sh.settlement_status,
                   COALESCE(sh.last_failure_reason, sh.exception_reason)                      AS failure_reason
            FROM shipments sh
            WHERE sh.id = om.shipment_id AND sh.tenant_id = ?
        ) lg ON true
        """;

    private static final ObjectMapper JSON = new ObjectMapper();

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
            rs.getString("source_name"), rs.getBoolean("has_source_fields"), rs.getString("referring_site"),
            rs.getString("landing_site"), rs.getString("order_status_url"), rs.getString("shop_domain"),
            gateways(rs.getString("gateways")), rs.getString("province_code"), rs.getBoolean("shopify_fulfilled"),
            instant(rs.getTimestamp("handed_at")), instant(rs.getTimestamp("delivered_at")),
            rs.getString("settlement_status"), rs.getString("failure_reason"));
    }

    static List<String> gateways(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JsonNode n = JSON.readTree(json);
            if (n.isArray()) n.forEach(x -> { if (x.isTextual()) out.add(x.asText()); });
            else if (n.isTextual()) out.add(n.asText());
        } catch (Exception ignored) {
            // unreadable → no gateway → Other
        }
        return out;
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
