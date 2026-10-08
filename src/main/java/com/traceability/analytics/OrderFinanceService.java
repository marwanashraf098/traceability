package com.traceability.analytics;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static com.traceability.analytics.AnalyticsSql.money;

/**
 * Analytics slice 7 — order finances: one row per sold order of a period (the slice 5 OrderFacts
 * cohort — post-floor, not cancelled, not an internal exchange order, a sold line placed in the
 * period) with what Bosta charged for it and what reached the merchant. Reads the V148 settlement
 * and V149 generated columns only (no raw parsing). Built on OrderFacts.SQL's CTEs, so booked /
 * outcome / returns mean exactly what they mean on every other analytics endpoint.
 *
 * Financial status — ONE per order, first match wins ({@link #financialStatus}):
 *   other_carrier   — shipped with Wijha (outcome wijha); no Bosta data, net null;
 *   lost            — refused / other_terminal: net = −fees;
 *   refunded        — a customer return recorded on a delivered order (the s2 returns, or a refund
 *                     in the return_refunds ledger): net = collected − fees − refunded amount; with
 *                     no refund recorded, net null and refundAmountUnknown;
 *   paid            — delivered and (the COD leg has a cashout transaction, or the order was
 *                     prepaid): net = deposited_amt (COD) or total − fees (prepaid);
 *   overdue         — delivered and the s3 delivered-not-paid rule holds
 *                     (SettlementSql.deliveredNotPaid), or in transit with no status change for
 *                     7 days (SettlementSql.stuckWithBosta);
 *   awaiting_payout — delivered COD, not paid yet;
 *   expected        — not shipped / in transit.
 * Net is null while pending (overdue, awaiting payout, expected).
 *
 * Prepaid = payment group Card (the s5 mapping: Paymob, Kashier, card gateways). Every other group
 * is paid through Bosta's cash cycle.
 *
 * Bosta fees: the sum over the order's Bosta legs (forward, return, exchange) Bosta charges for —
 * settled legs always, and every leg that isn't cancelled or terminated before pickup — of
 * SettlementSql.fee (settled bosta_fees, else the quote × 1.14); feesEstimated when any of them
 * is a quote. Null when no leg has a fee.
 */
@Service
public class OrderFinanceService {

    public static final List<String> STATUSES =
        List.of("other_carrier", "lost", "refunded", "paid", "overdue", "awaiting_payout", "expected");

    static final Set<String> PREPAID_GROUPS = Set.of("Card");

    public record Governorate(String key, String label, String labelAr) {}

    public record DeliveryStatus(String key, String label) {}

    public record OrderRow(UUID orderId, String name, Instant placedAt, String customer, Governorate governorate,
                           long items, BigDecimal total, String paymentGroup, DeliveryStatus deliveryStatus,
                           String financialStatus, BigDecimal bostaFees, boolean feesEstimated,
                           BigDecimal netToYou, boolean refundAmountUnknown, List<String> trackingNumbers) {}

    public record OrdersPage(AnalyticsPeriod.Range range, Instant asOf, int page, int size, long total,
                             Map<String, Long> counts, List<OrderRow> orders) {}

    public record VariantOrders(UUID variantId, Instant asOf, List<OrderRow> orders) {}

    /** The list's filters; null = no filter. */
    public record Filters(String status, String governorate, UUID variantId, String q) {

        boolean matchesExceptStatus(Row r) {
            if (governorate != null && !governorate.equals(r.out.governorate().key())) return false;
            if (variantId != null && !r.variantIds.contains(variantId)) return false;
            if (q != null && !q.isBlank()) {
                String needle = q.trim().toLowerCase(Locale.ROOT).replaceFirst("^#", "");
                String tracking = q.replaceAll("\\s+", "");
                boolean byName = r.out.name() != null
                    && r.out.name().toLowerCase(Locale.ROOT).replaceFirst("^#", "").contains(needle);
                boolean byTracking = !tracking.isEmpty()
                    && r.out.trackingNumbers().stream().anyMatch(t -> t != null && t.contains(tracking));
                if (!byName && !byTracking) return false;
            }
            return true;
        }
    }

    /** A row plus the fields filters read but the response doesn't carry. */
    record Row(OrderRow out, Set<UUID> variantIds) {}

    /*
     * Parameters: OrderFacts' 13 (soldLines 7, outcomes 2, returns 4), then the deciding leg's
     * now, weekday, now, now, weekday (deliveredNotPaid), now (stuckWithBosta), tenant id; the
     * order's Bosta legs: tenant id; refunds: tenant id.
     */
    static final String ORDERS_SQL = OrderFacts.SQL.substring(0, OrderFacts.SQL.indexOf("SELECT om.order_id, om.placed_at,"))
        + """
        SELECT om.order_id, om.placed_at, om.booked, om.outcome, om.city_id, om.city, om.returned_rev,
               om.item_count, om.variant_ids,
               o.number, o.customer_name, o.payment_group, o.ship_province AS province_code,
               lg.internal_state AS leg_state, lg.cod, lg.cashout_txn_id, lg.deposited_amt, lg.not_paid, lg.stuck,
               fe.fees, fe.fees_estimated, fe.tracking_numbers,
               rf.refunded
        FROM order_money om
        JOIN orders o ON o.id = om.order_id
        LEFT JOIN LATERAL (
            SELECT sh.internal_state::text AS internal_state,
                   """ + SettlementSql.cod("sh") + """
                    AS cod, sh.cashout_txn_id, sh.deposited_amt,
                   """ + SettlementSql.deliveredNotPaid("sh", "?::timestamptz", "?::int") + """
                    AS not_paid,
                   """ + SettlementSql.stuckWithBosta("sh", SettlementSql.lastChange("sh"), "?::timestamptz") + """
                    AS stuck
            FROM shipments sh
            WHERE sh.id = om.shipment_id AND sh.tenant_id = ?
        ) lg ON true
        LEFT JOIN LATERAL (
            SELECT SUM(c.fee) FILTER (WHERE c.charged)                                   AS fees,
                   COALESCE(bool_or(c.charged AND c.fee IS NOT NULL AND NOT c.settled), false) AS fees_estimated,
                   array_agg(c.tracking_number ORDER BY c.created_at, c.id)               AS tracking_numbers
            FROM (
                SELECT s.id, s.created_at, s.tracking_number,
                       """ + SettlementSql.fee("s") + """
                        AS fee,
                       """ + SettlementSql.settled("s") + """
                        AS settled,
                       (""" + SettlementSql.settled("s") + """
                        OR NOT (s.internal_state = 'cancelled'
                                OR (s.internal_state = 'terminated' AND s.collected_from_business_at IS NULL))) AS charged
                FROM shipments s
                WHERE s.order_id = om.order_id AND s.tenant_id = ? AND s.provider = 'bosta'
            ) c
        ) fe ON true
        LEFT JOIN LATERAL (
            SELECT SUM(r.amount) AS refunded
            FROM return_refunds r
            JOIN return_requests rr ON rr.id = r.request_id
            WHERE rr.order_id = om.order_id AND r.tenant_id = ? AND r.kind = 'refund'
              AND NOT EXISTS (SELECT 1 FROM return_refunds v
                              WHERE v.voids_refund_id = r.id AND v.tenant_id = r.tenant_id)
        ) rf ON true
        """;

    /**
     * The oldest of a variant's 10 newest orders (post-floor, not cancelled, not an internal exchange
     * order) — the drawer's window start. Parameters: override domains, override days, tenant id ×2,
     * variant id, now, limit.
     */
    private static final String VARIANT_WINDOW_SQL = """
        SELECT MIN(w.placed_at) FROM (
            SELECT o.id, o.placed_at
            FROM order_items oi
            JOIN orders o  ON o.id = oi.order_id
            JOIN stores st ON st.id = o.store_id
            LEFT JOIN unnest(?::text[], ?::text[]) AS ov(shop_domain, floor_day) ON ov.shop_domain = lower(st.shop_domain)
            WHERE oi.tenant_id = ? AND o.tenant_id = ? AND oi.variant_id = ?
              AND o.status <> 'cancelled'::order_status AND NOT o.raw_cancelled
              AND o.external_id NOT LIKE 'internal:exchange:%'
              AND o.placed_at <= ?::timestamptz
              AND (COALESCE(st.orders_ingest_from, (ov.floor_day::date::timestamp AT TIME ZONE 'Africa/Cairo')) IS NULL
                   OR o.placed_at >= COALESCE(st.orders_ingest_from, (ov.floor_day::date::timestamp AT TIME ZONE 'Africa/Cairo')))
            GROUP BY o.id, o.placed_at
            ORDER BY o.placed_at DESC, o.id DESC
            LIMIT ?
        ) w
        """;

    static final int VARIANT_ORDERS = 10;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AnalyticsFloorOverrides overrides;

    public OrderFinanceService(JdbcTemplate jdbc, Clock clock, AnalyticsFloorOverrides overrides) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.overrides = overrides;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(AnalyticsPeriod.CAIRO));
    }

    // ── /orders ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public OrdersPage orders(AnalyticsPeriod period, Filters f, int page, int size) {
        Instant now = clock.instant();
        List<Row> rows = load(period, now);
        Map<String, Long> counts = new LinkedHashMap<>();
        STATUSES.forEach(s -> counts.put(s, 0L));
        List<OrderRow> matching = new ArrayList<>();
        for (Row r : rows) {
            if (!f.matchesExceptStatus(r)) continue;
            counts.merge(r.out.financialStatus(), 1L, Long::sum);
            if (f.status() == null || f.status().equals(r.out.financialStatus())) matching.add(r.out);
        }
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        return new OrdersPage(period.range(), now, page, size, matching.size(), counts,
            List.copyOf(matching.subList(from, to)));
    }

    /** Every matching order, newest first — the CSV export (the caller caps the count). */
    @Transactional(readOnly = true)
    public List<OrderRow> all(AnalyticsPeriod period, Filters f) {
        List<OrderRow> out = new ArrayList<>();
        for (Row r : load(period, clock.instant())) {
            if (f.matchesExceptStatus(r) && (f.status() == null || f.status().equals(r.out.financialStatus()))) {
                out.add(r.out);
            }
        }
        return out;
    }

    // ── /variants/{id}/orders ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public VariantOrders variantOrders(UUID variantId) {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        LocalDate today = today();
        // Window: from the Cairo day of the oldest of the variant's 10 newest orders to today, so the
        // facts query reads only the days the drawer needs.
        Timestamp start = jdbc.query(VARIANT_WINDOW_SQL, ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", overrides.shopDomains()));
            ps.setArray(2, ps.getConnection().createArrayOf("text", overrides.days()));
            ps.setObject(3, tid);
            ps.setObject(4, tid);
            ps.setObject(5, variantId);
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setInt(7, VARIANT_ORDERS);
        }, rs -> rs.next() ? rs.getTimestamp(1) : null);
        if (start == null) return new VariantOrders(variantId, now, List.of());
        LocalDate from = start.toInstant().atZone(AnalyticsPeriod.CAIRO).toLocalDate();
        AnalyticsPeriod window = new AnalyticsPeriod(from.isAfter(today) ? today : from, today);
        Filters f = new Filters(null, null, variantId, null);
        List<OrderRow> out = new ArrayList<>();
        for (Row r : load(window, now)) {
            if (!f.matchesExceptStatus(r)) continue;
            out.add(r.out);
            if (out.size() == VARIANT_ORDERS) break;
        }
        return new VariantOrders(variantId, now, out);
    }

    // ── shared ──────────────────────────────────────────────────────────────

    /** The period's orders with their finances, newest first (placed_at DESC, id DESC). */
    List<Row> load(AnalyticsPeriod period, Instant now) {
        UUID tid = TenantContext.require();
        Integer weekday = jdbc.query(SettlementSql.PAYOUT_WEEKDAY, rs -> rs.next() ? rs.getInt(1) : null,
            tid, Timestamp.from(now));
        OrderFacts.Cities cities = OrderFacts.cities(jdbc);
        Timestamp t = Timestamp.from(now);
        List<Row> rows = jdbc.query(ORDERS_SQL, ps -> {
            int i = AnalyticsSql.bindSoldLines(ps, tid, period, overrides);
            for (int k = 0; k < 6; k++) ps.setObject(i++, tid);        // outcomes 2, returns 4
            ps.setTimestamp(i++, t);                                   // deliveredNotPaid
            ps.setObject(i++, weekday, java.sql.Types.INTEGER);
            ps.setTimestamp(i++, t);
            ps.setTimestamp(i++, t);
            ps.setObject(i++, weekday, java.sql.Types.INTEGER);
            ps.setTimestamp(i++, t);                                   // stuckWithBosta
            ps.setObject(i++, tid);                                    // deciding leg
            ps.setObject(i++, tid);                                    // the order's legs
            ps.setObject(i, tid);                                      // refunds
        }, (rs, n) -> row(rs, cities));
        rows.sort(Comparator.comparing((Row r) -> r.out.placedAt()).reversed()
            .thenComparing((Row r) -> r.out.orderId().toString(), Comparator.reverseOrder()));
        return rows;
    }

    private static Row row(ResultSet rs, OrderFacts.Cities cities) throws SQLException {
        String outcome = rs.getString("outcome");
        String payment = rs.getString("payment_group");
        boolean prepaid = payment != null && PREPAID_GROUPS.contains(payment);
        BigDecimal total = money(rs.getBigDecimal("booked"));
        BigDecimal fees = moneyOrNull(rs.getBigDecimal("fees"));
        BigDecimal refunded = moneyOrNull(rs.getBigDecimal("refunded"));
        boolean customerReturn = rs.getBigDecimal("returned_rev") != null && rs.getBigDecimal("returned_rev").signum() > 0;
        Facts facts = new Facts(outcome, prepaid, rs.getString("cashout_txn_id") != null,
            rs.getBoolean("not_paid"), rs.getBoolean("stuck"), customerReturn || refunded != null);
        String status = financialStatus(facts);
        BigDecimal cod = moneyOrNull(rs.getBigDecimal("cod"));
        BigDecimal deposited = moneyOrNull(rs.getBigDecimal("deposited_amt"));
        BigDecimal net = null;
        boolean refundUnknown = false;
        switch (status) {
            case "lost" -> net = fees == null ? null : fees.negate();
            case "refunded" -> {
                BigDecimal collected = prepaid ? total : cod;
                if (refunded == null) refundUnknown = true;
                else if (collected != null) net = collected.subtract(fees == null ? BigDecimal.ZERO : fees).subtract(refunded);
            }
            case "paid" -> net = prepaid
                ? total.subtract(fees == null ? BigDecimal.ZERO : fees)
                : deposited != null ? deposited
                : cod == null ? null : cod.subtract(fees == null ? BigDecimal.ZERO : fees);
            default -> { }
        }
        String[] g = RevenueAnalyticsService.governorate(rs.getString("city_id"), rs.getString("city"),
            rs.getString("province_code"), cities);
        Set<UUID> variants = new HashSet<>();
        for (Object v : OrderFacts.array(rs.getArray("variant_ids"))) if (v != null) variants.add((UUID) v);
        List<String> tracking = new ArrayList<>();
        for (Object v : OrderFacts.array(rs.getArray("tracking_numbers"))) if (v != null) tracking.add((String) v);
        OrderRow out = new OrderRow(rs.getObject("order_id", UUID.class), rs.getString("number"),
            OrderFacts.instant(rs.getTimestamp("placed_at")), displayName(rs.getString("customer_name")),
            new Governorate(g[0], g[1], g[2]), rs.getLong("item_count"), total, payment,
            deliveryStatus(outcome, rs.getString("leg_state")), status, fees,
            rs.getBoolean("fees_estimated") && fees != null, net == null ? null : money(net), refundUnknown,
            List.copyOf(tracking));
        return new Row(out, variants);
    }

    /** What {@link #financialStatus} reads. */
    record Facts(String outcome, boolean prepaid, boolean cashedOut, boolean deliveredNotPaid,
                 boolean stuckInTransit, boolean customerReturn) {}

    /** THE financial-status rule — first match wins (see the class comment). */
    static String financialStatus(Facts f) {
        String o = f.outcome();
        if ("wijha".equals(o)) return "other_carrier";
        if ("refused".equals(o) || "other_terminal".equals(o)) return "lost";
        boolean delivered = "delivered".equals(o);
        if (delivered && f.customerReturn()) return "refunded";
        if (delivered && (f.cashedOut() || f.prepaid())) return "paid";
        if ((delivered && f.deliveredNotPaid()) || ("in_transit".equals(o) && f.stuckInTransit())) return "overdue";
        if (delivered) return "awaiting_payout";
        return "expected";
    }

    /** A plain delivery label from the outcome and the deciding leg's state. */
    static DeliveryStatus deliveryStatus(String outcome, String legState) {
        String label = switch (outcome == null ? "" : outcome) {
            case "delivered" -> "Delivered";
            case "refused" -> "returning".equals(legState) ? "Refused, returning" : "Refused";
            case "wijha" -> "Other carrier";
            case "not_shipped" -> "Not shipped";
            case "other_terminal" -> switch (legState == null ? "" : legState) {
                case "lost" -> "Lost";
                case "cancelled" -> "Cancelled";
                case "terminated" -> "Terminated";
                default -> "Failed";
            };
            case "in_transit" -> switch (legState == null ? "" : legState) {
                case "created" -> "Booked";
                case "with_courier" -> "With courier";
                case "exception" -> "Delivery exception";
                default -> "In transit";
            };
            default -> "Unknown";
        };
        return new DeliveryStatus(outcome, label);
    }

    /** First name + last initial ("Mona A."), never the full name; null when there is none. */
    static String displayName(String name) {
        if (name == null) return null;
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return null;
        if (parts.length == 1) return parts[0];
        String last = parts[parts.length - 1];
        return parts[0] + " " + new String(Character.toChars(last.codePointAt(0))) + ".";
    }

    static BigDecimal moneyOrNull(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }
}
