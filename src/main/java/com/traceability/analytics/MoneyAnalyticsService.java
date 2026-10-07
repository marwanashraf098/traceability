package com.traceability.analytics;

import com.traceability.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Analytics slice 3 — Bosta money: the cash pipeline, fees, the failed / exchange / return shipping
 * drill-down, stuck shipments and payouts. Bosta legs only — orders shipped with another carrier
 * (Wijha) or not shipped never appear in money. Live aggregation as app_user under RLS, every
 * statement also tenant-bound.
 *
 * Money is counted by money dates: fees by the leg's terminal date ({@link SettlementSql#terminalAt}),
 * payouts by cashout_date, money in the bank by cashout_date. The pipeline's first three stages are
 * point-in-time (now). Fees are Bosta's settled bosta_fees where the leg settled, else the quoted
 * shipmentFees × 1.14; every fee figure says how many legs were estimated.
 *
 * Never a batch "shortfall": Bosta's payout batch total covers every delivery of the business, tracked
 * by Traced or not, so /payouts shows it next to Traced's part without a difference, and "delivered,
 * not paid" is judged per shipment (two payout weekdays after the deposit, refreshed within 24 h,
 * still no payout id).
 */
@Service
public class MoneyAnalyticsService {

    /**
     * Shopify Egypt province codes → Bosta city names (dropOffAddress.city.name), for orders not
     * booked with Bosta yet. 6th of October (SU) and Helwan (HU) are under Giza / Cairo in Bosta.
     */
    static final Map<String, String> PROVINCE_TO_BOSTA_CITY = Map.ofEntries(
        Map.entry("C", "Cairo"), Map.entry("HU", "Cairo"), Map.entry("GZ", "Giza"), Map.entry("SU", "Giza"),
        Map.entry("ALX", "Alexandria"), Map.entry("DK", "Dakahlia"), Map.entry("KB", "El Kalioubia"),
        Map.entry("SHR", "Sharqia"), Map.entry("GH", "Gharbia"), Map.entry("MNF", "Monufia"),
        Map.entry("BH", "Behira"), Map.entry("DT", "Damietta"), Map.entry("PTS", "Port Said"),
        Map.entry("SUZ", "Suez"), Map.entry("IS", "Ismailia"), Map.entry("BA", "Red Sea"),
        Map.entry("JS", "South Sinai"), Map.entry("SIN", "North Sinai"), Map.entry("MT", "Matrouh"),
        Map.entry("KFS", "Kafr Alsheikh"), Map.entry("FYM", "Fayoum"), Map.entry("BNS", "Bani Suif"),
        Map.entry("MN", "Menya"), Map.entry("AST", "Assuit"), Map.entry("SHG", "Sohag"),
        Map.entry("KN", "Qena"), Map.entry("LX", "Luxor"), Map.entry("ASN", "Aswan"),
        Map.entry("WAD", "New Valley"));

    // ── Response records ────────────────────────────────────────────────────

    public record Stage(long count, BigDecimal value, BigDecimal expected) {}

    public record AwaitingPayout(long count, BigDecimal deposited, LocalDate nextCashoutDate,
                                 long deliveredNotYetSettled, BigDecimal deliveredNotYetSettledEstimate) {}

    public record InBank(long shipments, BigDecimal deposited, long payouts, LocalDate lastTransferDate) {}

    public record Pipeline(AnalyticsPeriod.Range range, Instant asOf, Stage notFulfilled, Stage inTransit,
                           AwaitingPayout awaitingPayout, InBank inYourBank, int openOrderDays) {}

    public record FeeFigure(long legs, BigDecimal amount, long estimatedCount) {}

    public record FeeComponents(long settledLegs, BigDecimal shippingFees, BigDecimal openingPackageFees,
                                BigDecimal collectionFees, BigDecimal insuranceFees, BigDecimal flexShipFees,
                                BigDecimal promotionDiscount, BigDecimal vat) {}

    public record Fees(AnalyticsPeriod.Range range, FeeFigure shipping, FeeFigure failed, FeeFigure exchange,
                       FeeFigure returned, FeeFigure total, FeeComponents settledComponents,
                       BigDecimal costPerSuccessfulDelivery, long deliveredCount,
                       BigDecimal costPerUnsuccessfulDelivery, long refusedCount,
                       BigDecimal payoutLagDays, long payoutLagShipments) {}

    public record ExtraByAwb(String trackingNumber, String orderNumber, String type, BigDecimal fee,
                             boolean estimated, String reason, LocalDate date, String city, List<String> skus) {}

    public record ExtraBySku(UUID variantId, String sku, String productTitle, String variantTitle,
                             long failed, long exchanges, long returns, BigDecimal extraFees) {}

    public record ExtraFees(AnalyticsPeriod.Range range, String groupBy, List<ExtraByAwb> shipments,
                            List<ExtraBySku> skus, BigDecimal total, long estimatedCount) {}

    public record StuckShipment(String trackingNumber, String orderNumber, String lastStatus, long days,
                                BigDecimal cod) {}

    public record NotPaidShipment(String trackingNumber, String orderNumber, LocalDate depositedOn,
                                  BigDecimal deposited, long days) {}

    public record Stuck(Instant asOf, List<StuckShipment> neverPickedUp, List<StuckShipment> stuckWithBosta,
                        List<NotPaidShipment> deliveredNotPaid, Integer payoutWeekday, long unresolved) {}

    public record Payout(String transactionId, LocalDate date, long trackedShipments,
                         BigDecimal trackedDeposited, BigDecimal bostaBatchTotal) {}

    public record Payouts(AnalyticsPeriod.Range range, List<Payout> payouts) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AnalyticsFloorOverrides overrides;
    private final int openOrderDays;

    public MoneyAnalyticsService(JdbcTemplate jdbc, Clock clock, AnalyticsFloorOverrides overrides,
                                 @Value("${analytics.money.open-order-days:60}") int openOrderDays) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.overrides = overrides;
        this.openOrderDays = openOrderDays;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(AnalyticsPeriod.CAIRO));
    }

    // ── /pipeline ───────────────────────────────────────────────────────────

    /*
     * City success rates (s2 rules: delivered vs turned RTO / returning / returned) over the
     * tenant's forward legs of the last 90 days, and the overall rate. One parameter: tenant id.
     */
    private static final String RATES = """
        rates AS (
            SELECT s.raw->'dropOffAddress'->'city'->>'name' AS city,
                   COUNT(*) FILTER (WHERE s.internal_state = 'delivered')                       AS delivered,
                   COUNT(*) FILTER (WHERE s.raw->'type'->>'code' = '20'
                                       OR s.internal_state IN ('returning', 'returned'))       AS refused
            FROM shipments s
            WHERE s.tenant_id = ? AND s.shipment_leg = 'forward'
              AND COALESCE(s.raw->'type'->>'code', '10') NOT IN ('25', '30')
              AND s.created_at > now() - interval '90 days'
            GROUP BY 1
        ),
        overall AS (
            SELECT SUM(delivered)::numeric / NULLIF(SUM(delivered + refused), 0) AS rate FROM rates
        ),
        city_rate AS (
            SELECT city, delivered::numeric / NULLIF(delivered + refused, 0) AS rate FROM rates
        )
        """;

    @Transactional(readOnly = true)
    public Pipeline pipeline(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        LocalDate today = today();
        AnalyticsPeriod open = new AnalyticsPeriod(today.minusDays(openOrderDays - 1L), today);

        // Not fulfilled + in transit: order values (slice-1 sold lines over the open window), each
        // order's expected value at its city's success rate.
        String stagesSql = SalesAnalyticsService.soldLines(false) + ", " + RATES + """
            , open_orders AS (
                SELECT l.order_id, SUM(l.revenue) AS value FROM lines l
                JOIN orders o ON o.id = l.order_id
                WHERE COALESCE(o.shipping_carrier_class, '') <> 'other_known'
                  AND EXISTS (SELECT 1 FROM courier_accounts ca
                              WHERE ca.tenant_id = o.tenant_id AND ca.provider = 'bosta' AND ca.status = 'active')
                GROUP BY l.order_id
            ),
            leg AS (
                SELECT DISTINCT ON (s.order_id) s.order_id, s.internal_state,
                       s.raw->'type'->>'code' AS type_code,
                       s.raw->'dropOffAddress'->'city'->>'name' AS city
                FROM shipments s
                JOIN open_orders oo ON oo.order_id = s.order_id
                WHERE s.tenant_id = ? AND s.shipment_leg = 'forward'
                  AND COALESCE(s.raw->'type'->>'code', '10') NOT IN ('25', '30')
                ORDER BY s.order_id, (s.internal_state IN ('terminated', 'cancelled')), s.created_at DESC, s.id DESC
            ),
            province AS (
                SELECT * FROM unnest(?::text[], ?::text[]) AS p(code, city)
            ),
            not_fulfilled AS (
                SELECT oo.order_id, oo.value,
                       oo.value * COALESCE(cr.rate, (SELECT rate FROM overall)) AS expected
                FROM open_orders oo
                JOIN orders o ON o.id = oo.order_id
                LEFT JOIN leg ON leg.order_id = oo.order_id
                LEFT JOIN province pv ON pv.code = o.raw->'shipping_address'->>'province_code'
                LEFT JOIN city_rate cr ON cr.city = COALESCE(leg.city, pv.city)
                WHERE leg.order_id IS NULL OR leg.internal_state IN ('created', 'terminated', 'cancelled')
            )
            SELECT COUNT(*) AS n, COALESCE(SUM(value), 0) AS value, SUM(expected) AS expected
            FROM not_fulfilled
            """;
        Stage notFulfilled = jdbc.query(stagesSql, ps -> {
            int i = soldLineParams(ps, tid, open);
            ps.setObject(i++, tid);                       // rates
            ps.setObject(i++, tid);                       // leg
            ps.setArray(i++, ps.getConnection().createArrayOf("text", PROVINCE_TO_BOSTA_CITY.keySet().toArray(new String[0])));
            ps.setArray(i, ps.getConnection().createArrayOf("text", PROVINCE_TO_BOSTA_CITY.values().toArray(new String[0])));
        }, rs -> {
            rs.next();
            return new Stage(rs.getLong("n"), money(rs.getBigDecimal("value")), moneyOrNull(rs.getBigDecimal("expected")));
        });

        // In transit with Bosta: forward legs picked up and still moving (not turned RTO).
        String transitSql = "WITH " + RATES + """
            SELECT COUNT(*) AS n,
                   COALESCE(SUM(t.cod), 0) AS value,
                   SUM(t.cod * COALESCE(cr.rate, (SELECT rate FROM overall))) AS expected
            FROM (
                SELECT """ + SettlementSql.cod("s") + """
                        AS cod, s.raw->'dropOffAddress'->'city'->>'name' AS city
                FROM shipments s""" + SettlementSql.floorJoin("s") + """
                WHERE s.tenant_id = ? AND s.provider = 'bosta' AND s.shipment_leg = 'forward'
                  AND COALESCE(s.raw->'type'->>'code', '10') NOT IN ('20', '25', '30')
                  AND s.internal_state IN ('with_courier', 'exception')
                  AND fo.status <> 'cancelled'::order_status
                  AND """ + SettlementSql.POST_FLOOR + """
            ) t
            LEFT JOIN city_rate cr ON cr.city = t.city
            """;
        Stage inTransit = jdbc.query(transitSql, ps -> {
            ps.setObject(1, tid);
            ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
            ps.setArray(3, ps.getConnection().createArrayOf("text", overrides.days()));
            ps.setObject(4, tid);
        }, rs -> {
            rs.next();
            return new Stage(rs.getLong("n"), money(rs.getBigDecimal("value")), moneyOrNull(rs.getBigDecimal("expected")));
        });

        // Delivered, awaiting payout (deposited, no payout yet) + delivered legs Bosta hasn't settled.
        AwaitingPayout awaiting = jdbc.query(
            "SELECT COUNT(*) FILTER (WHERE s.settlement_status = 'deposited') AS n, " +
            "       COALESCE(SUM(s.deposited_amt) FILTER (WHERE s.settlement_status = 'deposited'), 0) AS deposited, " +
            "       MIN(s.next_cashout_date) FILTER (WHERE s.settlement_status = 'deposited' " +
            "                                       AND s.next_cashout_date >= ?) AS next_cashout, " +
            "       COUNT(*) FILTER (WHERE s.settlement_status = 'none' AND s.internal_state = 'delivered' " +
            "                          AND s.shipment_leg = 'forward') AS unsettled, " +
            "       COALESCE(SUM(" + SettlementSql.cod("s") + " - COALESCE(" + SettlementSql.fee("s") + ", 0)) " +
            "                FILTER (WHERE s.settlement_status = 'none' AND s.internal_state = 'delivered' " +
            "                          AND s.shipment_leg = 'forward'), 0) AS unsettled_estimate " +
            "FROM shipments s" + SettlementSql.floorJoin("s") +
            "WHERE s.tenant_id = ? AND s.provider = 'bosta' AND " + SettlementSql.POST_FLOOR,
            ps -> {
                ps.setDate(1, Date.valueOf(today));
                ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
                ps.setArray(3, ps.getConnection().createArrayOf("text", overrides.days()));
                ps.setObject(4, tid);
            },
            rs -> {
                rs.next();
                Date next = rs.getDate("next_cashout");
                return new AwaitingPayout(rs.getLong("n"), money(rs.getBigDecimal("deposited")),
                    next == null ? null : next.toLocalDate(), rs.getLong("unsettled"),
                    money(rs.getBigDecimal("unsettled_estimate")));
            });

        // In your bank: paid legs whose payout date falls in the period.
        InBank bank = jdbc.query(
            "SELECT COUNT(*) AS n, COALESCE(SUM(s.deposited_amt), 0) AS deposited, " +
            "       COUNT(DISTINCT s.cashout_txn_id) AS payouts, MAX(s.cashout_date) AS last_date " +
            "FROM shipments s" + SettlementSql.floorJoin("s") +
            "WHERE s.tenant_id = ? AND s.provider = 'bosta' AND s.settlement_status = 'paid' " +
            "  AND s.cashout_date >= ? AND s.cashout_date <= ? AND " + SettlementSql.POST_FLOOR,
            ps -> {
                ps.setArray(1, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
                ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.days()));
                ps.setObject(3, tid);
                ps.setDate(4, Date.valueOf(period.from()));
                ps.setDate(5, Date.valueOf(period.to()));
            },
            rs -> {
                rs.next();
                Date last = rs.getDate("last_date");
                return new InBank(rs.getLong("n"), money(rs.getBigDecimal("deposited")), rs.getLong("payouts"),
                    last == null ? null : last.toLocalDate());
            });

        return new Pipeline(period.range(), now, notFulfilled, inTransit, awaiting, bank, openOrderDays);
    }

    // ── /fees ───────────────────────────────────────────────────────────────

    /*
     * The tenant's finished Bosta legs whose terminal date is in the period, with kind / fee /
     * estimated. Parameters: override domains, override days, tenant id, period start, period end.
     */
    private static String finishedLegs() {
        return """
            WITH legs AS MATERIALIZED (
                SELECT s.id, s.order_id, s.tracking_number, s.internal_state, s.settlement_status,
                       s.shipping_fees, s.opening_package_fees, s.collection_fees, s.insurance_fees,
                       s.flex_ship_fees, s.promotion_discount, s.vat, s.last_failure_reason,
                       """ + SettlementSql.legKind("s") + """
                        AS kind,
                       """ + SettlementSql.fee("s") + """
                        AS fee,
                       NOT """ + SettlementSql.settled("s") + """
                        AS estimated,
                       t.terminal_at
                FROM shipments s""" + SettlementSql.floorJoin("s") + """
                CROSS JOIN LATERAL (SELECT """ + SettlementSql.terminalAt("s") + """
                     AS terminal_at) t
                WHERE s.tenant_id = ? AND s.provider = 'bosta'
                  AND s.internal_state IN """ + SettlementSql.TERMINAL_STATES + """
                  AND """ + SettlementSql.POST_FLOOR + """
                  AND t.terminal_at >= ? AND t.terminal_at < ?
            )
            """;
    }

    private PreparedStatementSetter legParams(UUID tid, AnalyticsPeriod period, Object... extra) {
        return ps -> {
            int i = 1;
            ps.setArray(i++, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
            ps.setArray(i++, ps.getConnection().createArrayOf("text", overrides.days()));
            ps.setObject(i++, tid);
            ps.setTimestamp(i++, Timestamp.from(period.startInclusive()));
            ps.setTimestamp(i++, Timestamp.from(period.endExclusive()));
            for (Object o : extra) ps.setObject(i++, o);
        };
    }

    @Transactional(readOnly = true)
    public Fees fees(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        String sql = finishedLegs() + """
            SELECT
              COUNT(*) FILTER (WHERE kind = 'shipping')                                  AS ship_n,
              COALESCE(SUM(fee) FILTER (WHERE kind = 'shipping'), 0)                     AS ship_amt,
              COUNT(*) FILTER (WHERE kind = 'shipping' AND estimated)                    AS ship_est,
              COUNT(*) FILTER (WHERE kind = 'failed')                                    AS fail_n,
              COALESCE(SUM(fee) FILTER (WHERE kind = 'failed'), 0)                       AS fail_amt,
              COUNT(*) FILTER (WHERE kind = 'failed' AND estimated)                      AS fail_est,
              COUNT(*) FILTER (WHERE kind = 'exchange')                                  AS exch_n,
              COALESCE(SUM(fee) FILTER (WHERE kind = 'exchange'), 0)                     AS exch_amt,
              COUNT(*) FILTER (WHERE kind = 'exchange' AND estimated)                    AS exch_est,
              COUNT(*) FILTER (WHERE kind = 'return')                                    AS ret_n,
              COALESCE(SUM(fee) FILTER (WHERE kind = 'return'), 0)                       AS ret_amt,
              COUNT(*) FILTER (WHERE kind = 'return' AND estimated)                      AS ret_est,
              COUNT(*) FILTER (WHERE NOT estimated)                                      AS settled_n,
              COALESCE(SUM(shipping_fees) FILTER (WHERE NOT estimated), 0)               AS c_ship,
              COALESCE(SUM(opening_package_fees) FILTER (WHERE NOT estimated), 0)        AS c_open,
              COALESCE(SUM(collection_fees) FILTER (WHERE NOT estimated), 0)             AS c_coll,
              COALESCE(SUM(insurance_fees) FILTER (WHERE NOT estimated), 0)              AS c_ins,
              COALESCE(SUM(flex_ship_fees) FILTER (WHERE NOT estimated), 0)              AS c_flex,
              COALESCE(SUM(promotion_discount) FILTER (WHERE NOT estimated), 0)          AS c_promo,
              COALESCE(SUM(vat) FILTER (WHERE NOT estimated), 0)                         AS c_vat,
              COUNT(*) FILTER (WHERE kind = 'shipping' AND internal_state = 'delivered') AS delivered_n,
              COALESCE(SUM(fee) FILTER (WHERE kind = 'shipping' AND internal_state = 'delivered'), 0) AS delivered_fee
            FROM legs
            """;
        Map<String, Object> r = jdbc.query(sql, legParams(tid, period), rs -> {
            rs.next();
            Map<String, Object> m = new LinkedHashMap<>();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) m.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
            return m;
        });
        FeeFigure shipping = fig(r, "ship"), failed = fig(r, "fail"), exchange = fig(r, "exch"), returned = fig(r, "ret");
        FeeFigure total = new FeeFigure(shipping.legs() + failed.legs() + exchange.legs() + returned.legs(),
            shipping.amount().add(failed.amount()).add(exchange.amount()).add(returned.amount()),
            shipping.estimatedCount() + failed.estimatedCount() + exchange.estimatedCount() + returned.estimatedCount());
        FeeComponents comps = new FeeComponents(lng(r, "settled_n"), dec(r, "c_ship"), dec(r, "c_open"),
            dec(r, "c_coll"), dec(r, "c_ins"), dec(r, "c_flex"), dec(r, "c_promo").negate(), dec(r, "c_vat"));
        long delivered = lng(r, "delivered_n");

        // Payout lag: legs whose payout date is in the period.
        Map<String, Object> lag = jdbc.queryForMap(
            "SELECT AVG(s.cashout_date - (s.deposited_at AT TIME ZONE 'Africa/Cairo')::date) AS lag, COUNT(*) AS n " +
            "FROM shipments s WHERE s.tenant_id = ? AND s.provider = 'bosta' AND s.settlement_status = 'paid' " +
            "  AND s.deposited_at IS NOT NULL AND s.cashout_date >= ? AND s.cashout_date <= ?",
            tid, Date.valueOf(period.from()), Date.valueOf(period.to()));
        BigDecimal lagDays = lag.get("lag") == null ? null
            : new BigDecimal(lag.get("lag").toString()).setScale(1, RoundingMode.HALF_UP);

        return new Fees(period.range(), shipping, failed, exchange, returned, total, comps,
            delivered == 0 ? null : money(dec(r, "delivered_fee").divide(BigDecimal.valueOf(delivered), 4, RoundingMode.HALF_UP)),
            delivered,
            failed.legs() == 0 ? null : money(failed.amount().divide(BigDecimal.valueOf(failed.legs()), 4, RoundingMode.HALF_UP)),
            failed.legs(), lagDays, ((Number) lag.get("n")).longValue());
    }

    // ── /fees/extra ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ExtraFees extraFees(AnalyticsPeriod period, String groupBy) {
        UUID tid = TenantContext.require();
        if ("awb".equals(groupBy)) {
            String sql = finishedLegs() + """
                SELECT l.tracking_number, o.number AS order_number, l.kind, l.fee, l.estimated,
                       l.last_failure_reason AS reason,
                       (l.terminal_at AT TIME ZONE 'Africa/Cairo')::date AS day,
                       s.raw->'dropOffAddress'->'city'->>'name' AS city,
                       (SELECT array_agg(DISTINCT COALESCE(v.sku, v.title) ORDER BY COALESCE(v.sku, v.title))
                          FROM order_items oi JOIN variants v ON v.id = oi.variant_id
                          WHERE oi.order_id = l.order_id) AS skus
                FROM legs l
                JOIN shipments s ON s.id = l.id
                JOIN orders o ON o.id = l.order_id
                WHERE l.kind IN ('failed', 'exchange', 'return')
                ORDER BY l.terminal_at DESC, l.tracking_number
                """;
            List<ExtraByAwb> rows = jdbc.query(sql, legParams(tid, period), (rs, n) -> {
                java.sql.Array skus = rs.getArray("skus");
                List<String> list = skus == null ? List.of() : List.of((String[]) skus.getArray());
                Date day = rs.getDate("day");
                return new ExtraByAwb(rs.getString("tracking_number"), rs.getString("order_number"),
                    rs.getString("kind"), moneyOrNull(rs.getBigDecimal("fee")), rs.getBoolean("estimated"),
                    rs.getString("reason"), day == null ? null : day.toLocalDate(), rs.getString("city"), list);
            });
            BigDecimal total = rows.stream().map(x -> x.fee() == null ? BigDecimal.ZERO : x.fee())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            long est = rows.stream().filter(ExtraByAwb::estimated).count();
            return new ExtraFees(period.range(), "awb", rows, null, money(total), est);
        }

        // By SKU: each leg's fee split over its order's lines by line value share (qty × net unit
        // price, raw price else variants.price).
        String sql = finishedLegs() + """
            , extra AS (
                SELECT * FROM legs WHERE kind IN ('failed', 'exchange', 'return')
            ),
            line_values AS (
                SELECT e.id AS leg_id, e.kind, e.fee, e.estimated, oi.variant_id,
                       GREATEST(COALESCE(li.current_quantity, oi.quantity), 0)
                         * COALESCE(li.price, v.price, 0) AS value
                FROM extra e
                JOIN order_items oi ON oi.order_id = e.order_id
                JOIN variants v ON v.id = oi.variant_id
                CROSS JOIN LATERAL jsonb_to_record(COALESCE(oi.raw, '{}'::jsonb))
                    AS li(price numeric, current_quantity int)
            ),
            shares AS (
                SELECT lv.*, lv.value / NULLIF(SUM(lv.value) OVER (PARTITION BY lv.leg_id), 0) AS share,
                       COUNT(*) OVER (PARTITION BY lv.leg_id) AS lines_in_leg
                FROM line_values lv
            )
            SELECT sh.variant_id, v.sku, p.title AS product_title, v.title AS variant_title,
                   COUNT(DISTINCT sh.leg_id) FILTER (WHERE sh.kind = 'failed')   AS failed,
                   COUNT(DISTINCT sh.leg_id) FILTER (WHERE sh.kind = 'exchange') AS exchanges,
                   COUNT(DISTINCT sh.leg_id) FILTER (WHERE sh.kind = 'return')   AS returns,
                   COALESCE(SUM(sh.fee * COALESCE(sh.share, 1.0 / sh.lines_in_leg)), 0) AS extra_fees,
                   COUNT(DISTINCT sh.leg_id) FILTER (WHERE sh.estimated)         AS estimated
            FROM shares sh
            JOIN variants v ON v.id = sh.variant_id
            JOIN products p ON p.id = v.product_id
            GROUP BY sh.variant_id, v.sku, p.title, v.title
            ORDER BY extra_fees DESC, p.title, v.title
            """;
        long[] est = {0};
        List<ExtraBySku> rows = jdbc.query(sql, legParams(tid, period), (rs, n) -> {
            est[0] += rs.getLong("estimated");
            return new ExtraBySku(rs.getObject("variant_id", UUID.class), rs.getString("sku"),
                rs.getString("product_title"), rs.getString("variant_title"), rs.getLong("failed"),
                rs.getLong("exchanges"), rs.getLong("returns"), money(rs.getBigDecimal("extra_fees")));
        });
        BigDecimal total = jdbc.query(finishedLegs() +
            "SELECT COALESCE(SUM(fee), 0) FROM legs WHERE kind IN ('failed', 'exchange', 'return')",
            legParams(tid, period), rs -> { rs.next(); return rs.getBigDecimal(1); });
        Long legsEstimated = jdbc.query(finishedLegs() +
            "SELECT COUNT(*) FROM legs WHERE kind IN ('failed', 'exchange', 'return') AND estimated",
            legParams(tid, period), rs -> { rs.next(); return rs.getLong(1); });
        return new ExtraFees(period.range(), "sku", null, rows, money(total), legsEstimated == null ? 0 : legsEstimated);
    }

    // ── /stuck ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Stuck stuck() {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        Integer weekday = jdbc.query(SettlementSql.PAYOUT_WEEKDAY, rs -> rs.next() ? rs.getInt(1) : null, tid);

        String base = " FROM shipments s" + SettlementSql.floorJoin("s") +
            " WHERE s.tenant_id = ? AND s.provider = 'bosta' AND fo.status <> 'cancelled'::order_status AND "
            + SettlementSql.POST_FLOOR;

        List<StuckShipment> never = jdbc.query(
            "SELECT s.tracking_number, fo.number, s.internal_state::text AS st, " +
            "       EXTRACT(DAY FROM (?::timestamptz - s.created_at))::bigint AS days, " + SettlementSql.cod("s") + " AS cod" +
            base + " AND s.shipment_leg = 'forward' AND s.internal_state = 'created' " +
            "  AND s.created_at < ?::timestamptz - interval '7 days' " +
            "ORDER BY s.created_at, s.tracking_number",
            ps -> stuckParams(ps, now, tid),
            (rs, n) -> new StuckShipment(rs.getString(1), rs.getString(2), "Booked, never picked up",
                rs.getLong("days"), money(rs.getBigDecimal("cod"))));

        List<StuckShipment> withBosta = jdbc.query(
            "SELECT s.tracking_number, fo.number, " +
            "       COALESCE(s.raw->'state'->>'value', s.internal_state::text) AS st, " +
            "       EXTRACT(DAY FROM (?::timestamptz - lc.last_change))::bigint AS days, " + SettlementSql.cod("s") + " AS cod" +
            " FROM shipments s" + SettlementSql.floorJoin("s") +
            " CROSS JOIN LATERAL (SELECT COALESCE((SELECT MAX(h.occurred_at) FROM shipment_status_history h " +
            "                                       WHERE h.shipment_id = s.id), s.created_at) AS last_change) lc" +
            " WHERE s.tenant_id = ? AND s.provider = 'bosta' AND fo.status <> 'cancelled'::order_status AND "
            + SettlementSql.POST_FLOOR +
            "  AND s.internal_state IN ('with_courier', 'returning', 'exception') " +
            "  AND lc.last_change < ?::timestamptz - interval '7 days' " +
            "ORDER BY lc.last_change, s.tracking_number",
            ps -> stuckParams(ps, now, tid),
            (rs, n) -> new StuckShipment(rs.getString(1), rs.getString(2), rs.getString("st"),
                rs.getLong("days"), money(rs.getBigDecimal("cod"))));

        // Delivered, not paid — per shipment only: deposited, refreshed within 24 h, and two of the
        // tenant's payout weekdays have passed since the deposit (no known weekday: 14 days).
        List<NotPaidShipment> notPaid = jdbc.query(
            "SELECT s.tracking_number, fo.number, (s.deposited_at AT TIME ZONE 'Africa/Cairo')::date AS dep_day, " +
            "       s.deposited_amt, EXTRACT(DAY FROM (?::timestamptz - s.deposited_at))::bigint AS days" +
            base + " AND s.settlement_status = 'deposited' AND s.deposited_at IS NOT NULL " +
            "  AND s.settlement_refreshed_at > ?::timestamptz - interval '24 hours' " +
            "  AND CASE WHEN ?::int IS NULL THEN s.deposited_at < ?::timestamptz - interval '14 days' " +
            "           ELSE (SELECT COUNT(*) FROM generate_series((s.deposited_at AT TIME ZONE 'Africa/Cairo')::date + 1, " +
            "                                                     (?::timestamptz AT TIME ZONE 'Africa/Cairo')::date, " +
            "                                                     interval '1 day') g(d) " +
            "                 WHERE EXTRACT(ISODOW FROM g.d) = ?::int) >= 2 END " +
            "ORDER BY s.deposited_at, s.tracking_number",
            ps -> {
                Timestamp t = Timestamp.from(now);
                ps.setTimestamp(1, t);
                ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
                ps.setArray(3, ps.getConnection().createArrayOf("text", overrides.days()));
                ps.setObject(4, tid);
                ps.setTimestamp(5, t);
                ps.setObject(6, weekday, java.sql.Types.INTEGER);
                ps.setTimestamp(7, t);
                ps.setTimestamp(8, t);
                ps.setObject(9, weekday, java.sql.Types.INTEGER);
            },
            (rs, n) -> new NotPaidShipment(rs.getString(1), rs.getString(2), rs.getDate("dep_day").toLocalDate(),
                money(rs.getBigDecimal("deposited_amt")), rs.getLong("days")));

        Long unresolved = jdbc.queryForObject(
            "SELECT COUNT(*) FROM shipments WHERE tenant_id = ? AND settlement_status = 'unresolved'", Long.class, tid);
        return new Stuck(now, never, withBosta, notPaid, weekday, unresolved == null ? 0 : unresolved);
    }

    private void stuckParams(PreparedStatement ps, Instant now, UUID tid) throws SQLException {
        Timestamp t = Timestamp.from(now);
        ps.setTimestamp(1, t);
        ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
        ps.setArray(3, ps.getConnection().createArrayOf("text", overrides.days()));
        ps.setObject(4, tid);
        ps.setTimestamp(5, t);
    }

    // ── /payouts ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Payouts payouts(AnalyticsPeriod period) {
        UUID tid = TenantContext.require();
        List<Payout> rows = jdbc.query(
            "SELECT s.cashout_txn_id, MAX(s.cashout_date) AS day, COUNT(*) AS n, " +
            "       COALESCE(SUM(s.deposited_amt), 0) AS deposited, MAX(s.cashout_amount) AS batch " +
            "FROM shipments s WHERE s.tenant_id = ? AND s.provider = 'bosta' AND s.cashout_txn_id IS NOT NULL " +
            "  AND s.cashout_date >= ? AND s.cashout_date <= ? " +
            "GROUP BY s.cashout_txn_id ORDER BY day DESC, s.cashout_txn_id",
            (rs, n) -> new Payout(rs.getString(1), rs.getDate("day").toLocalDate(), rs.getLong("n"),
                money(rs.getBigDecimal("deposited")), moneyOrNull(rs.getBigDecimal("batch"))),
            tid, Date.valueOf(period.from()), Date.valueOf(period.to()));
        return new Payouts(period.range(), rows);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Binds SalesAnalyticsService.soldLines(false)'s 7 parameters; returns the next index. */
    private int soldLineParams(PreparedStatement ps, UUID tid, AnalyticsPeriod p) throws SQLException {
        ps.setTimestamp(1, Timestamp.from(p.startInclusive()));
        ps.setTimestamp(2, Timestamp.from(p.endExclusive()));
        ps.setArray(3, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
        ps.setArray(4, ps.getConnection().createArrayOf("text", overrides.days()));
        ps.setObject(5, tid);
        ps.setObject(6, tid);
        ps.setObject(7, tid);
        return 8;
    }

    private static FeeFigure fig(Map<String, Object> r, String p) {
        return new FeeFigure(lng(r, p + "_n"), money(dec(r, p + "_amt")), lng(r, p + "_est"));
    }

    private static long lng(Map<String, Object> r, String k) {
        Object o = r.get(k);
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static BigDecimal dec(Map<String, Object> r, String k) {
        Object o = r.get(k);
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
    }

    static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal moneyOrNull(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

}
