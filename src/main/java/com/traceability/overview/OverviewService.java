package com.traceability.overview;

import com.traceability.fulfillment.OrderStatusDeriver;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Overview dashboard trends (FR-Overview §2, extended for the date-range picker +
 * COD-delivered + Late-to-pack behavior change) — 5 stat-card sparklines, a live
 * Late-to-pack tile, and Top-selling SKUs. LIVE AGGREGATION over source-event
 * timestamps only: no daily-snapshot table, no rollup job, no stored counter that
 * could drift from the ledger.
 *
 * Day bucketing is always Africa/Cairo local calendar days, via the same Cairo-pinned
 * {@link Clock} bean AppConfig already injects app-wide (never the JVM/DB default —
 * confirmed neither is Cairo). Bucketing happens in Java (Instant -> Cairo LocalDate),
 * not in SQL, so all metrics share one bucketing/zero-fill implementation instead of
 * repeating an `AT TIME ZONE 'Africa/Cairo'` CTE for each one.
 *
 * The 14-day trailing sparkline (`series`) and the caller-selected [from,to] headline
 * (`total`) are DELIBERATELY DECOUPLED: the sparkline always shows the same 14-day
 * shape regardless of which date-range preset is active, while `total` is scoped to
 * whatever range the caller asked for. This means Today/Yesterday don't collapse the
 * sparkline to a single point.
 */
@Service
public class OverviewService {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final int WINDOW_DAYS = 14;
    private static final int TOP_SKUS_WINDOW_DAYS = 30;
    private static final int TOP_SKUS_LIMIT = 5;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OverviewService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ── Response records ────────────────────────────────────────────────────

    public record TrendPoint(String date, int count) {}

    // total is a double so it can carry either an exact integer count (orders,
    // delivered, exceptions, returns) or a fractional EGP amount (cod_delivered)
    // through one shared shape — matches the frontend's `number` type either way.
    public record MetricTrend(String metric, double total, List<TrendPoint> series) {}

    public record TopSku(String sku, String title, String imageUrl, int units) {}

    public record LateToPack(int overdue, int over48) {}

    // ── Trends ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MetricTrend> trends(String fromStr, String toStr) {
        UUID tid = TenantContext.require();
        LocalDate today = LocalDate.now(clock);

        LocalDate from, to;
        try {
            from = fromStr != null ? LocalDate.parse(fromStr) : today.minusDays(6);
            to   = toStr   != null ? LocalDate.parse(toStr)   : today;
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from/to must be YYYY-MM-DD");
        }
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }

        // The raw-event query's lower bound must cover BOTH the fixed 14-day sparkline
        // window AND whatever range the caller asked for — a "Last 30 days" or "Custom"
        // selection can reach further back than the sparkline's own 14 days.
        LocalDate seriesLower = today.minusDays(WINDOW_DAYS - 1L);
        LocalDate effectiveLower = seriesLower.isBefore(from) ? seriesLower : from;
        Timestamp lowerTs = Timestamp.from(effectiveLower.atStartOfDay(CAIRO).toInstant());

        List<MetricTrend> out = new ArrayList<>();
        out.add(countMetric("orders",     ordersRaw(tid, lowerTs),     today, from, to));
        out.add(codMetric("cod_delivered", codDeliveredRaw(tid, lowerTs), today, from, to));
        out.add(countMetric("delivered",  deliveredRaw(tid, lowerTs),  today, from, to));
        out.add(countMetric("exceptions", exceptionsRaw(tid, lowerTs), today, from, to));
        out.add(countMetric("returns",    returnsRaw(tid, lowerTs),    today, from, to));
        return out;
    }

    private static final org.springframework.jdbc.core.RowMapper<Instant> TS_MAPPER =
        (rs, i) -> rs.getTimestamp(1).toInstant();

    // Orders = orders.placed_at.
    private List<Instant> ordersRaw(UUID tid, Timestamp lower) {
        return jdbc.query(
            "SELECT placed_at FROM orders WHERE tenant_id = ? AND placed_at >= ?",
            TS_MAPPER, tid, lower);
    }

    // Delivered = one row per ORDER (not per shipment/event), keyed by the EARLIEST
    // shipment_status_history.occurred_at where internal_state='delivered' on that
    // order's forward leg. Deduped via GROUP BY s.order_id — an order that (rarely)
    // has two forward-leg shipments both independently reach 'delivered' (reship,
    // data-quality edge case) is counted once, at its first delivery, not twice.
    // NOT shipments.internal_state: shipments has no delivered_at column at all
    // (only last_synced_at, rewritten on every sync, and created_at) —
    // shipment_status_history is the one real per-transition timestamp.
    private List<Instant> deliveredRaw(UUID tid, Timestamp lower) {
        return jdbc.query(
            """
            SELECT MIN(h.occurred_at) AS occurred_at
            FROM shipment_status_history h
            JOIN shipments s ON s.id = h.shipment_id AND s.tenant_id = h.tenant_id
                             AND s.shipment_leg = 'forward'
            WHERE h.tenant_id = ? AND h.internal_state = 'delivered' AND h.occurred_at >= ?
            GROUP BY s.order_id
            """,
            TS_MAPPER, tid, lower);
    }

    // COD delivered = orders.cod_amount summed once per order that was delivered in
    // range, keyed at the SAME per-order-deduped delivery moment as `deliveredRaw`
    // above (mirrors its exact GROUP BY s.order_id dedup — see that method's doc for
    // why). orders.cod_amount, NOT shipments.cod_amount: shipments.cod_amount is
    // declared in the schema but never written by any INSERT/UPDATE in the codebase
    // (verified against all 3 `INSERT INTO shipments` call sites in
    // ShipmentLinkService — none populate it) — it is permanently NULL. orders.cod_amount
    // is the one live figure: set from Shopify at sync (ShopifySyncService, "cod"
    // payment_method => totalPrice) and operator-correctable pre-pack via
    // FulfillService.updateCod() (FR-7.5). Prepaid orders store cod_amount=NULL —
    // COALESCE to 0 so they contribute nothing (this is a cash-collected metric, not GMV).
    private record CodEvent(Instant occurredAt, BigDecimal codAmount) {}

    private List<CodEvent> codDeliveredRaw(UUID tid, Timestamp lower) {
        return jdbc.query(
            """
            SELECT MIN(h.occurred_at) AS occurred_at, COALESCE(o.cod_amount, 0) AS cod_amount
            FROM shipment_status_history h
            JOIN shipments s ON s.id = h.shipment_id AND s.tenant_id = h.tenant_id
                             AND s.shipment_leg = 'forward'
            JOIN orders o ON o.id = s.order_id AND o.tenant_id = s.tenant_id
            WHERE h.tenant_id = ? AND h.internal_state = 'delivered' AND h.occurred_at >= ?
            GROUP BY s.order_id, o.cod_amount
            """,
            (rs, i) -> new CodEvent(rs.getTimestamp("occurred_at").toInstant(), rs.getBigDecimal("cod_amount")),
            tid, lower);
    }

    /**
     * Exceptions = 12 of ExceptionService's 20 detectors — EVENT-BASED ones only.
     *
     * Corrects a Step-0 arithmetic error: the earlier report said "14 event-based / 6
     * refresh-based" — the actual split, re-verified against every one of the 20
     * detector queries, is 12 / 8:
     *
     *   EXCLUDED as refresh/threshold-based (7) — {@code stuck_shipment},
     *   {@code never_received}, {@code return_in_transit_stuck} gate on elapsed time
     *   past a base timestamp (the exception truly "opens" at base+N days, not at the
     *   base timestamp itself — bucketing by the base would misfile it N days into the
     *   past); {@code delivery_limbo}, {@code ndr_failed}, {@code high_attempts},
     *   {@code exchange_unmapped_state} key off {@code COALESCE(last_synced_at,
     *   created_at)}, which is rewritten on every Bosta sync — not a discrete "became
     *   exceptional" moment at all, even in principle.
     *
     *   EXCLUDED as having no real event timestamp (1) — {@code blocked_customer}'s
     *   only timestamp is {@code orders.created_at} (order creation), but its
     *   condition is {@code on_hold=true}, which is set by a bare UPDATE with no
     *   audit trail or held-at column anywhere (confirmed by reading both writers —
     *   BlocklistService, ShopifySyncService). Bucketing by created_at would silently
     *   misattribute the day a hold was applied to the day the order was placed,
     *   which can be arbitrarily earlier — a worse distortion than the 7 above (those
     *   are off by a knowable N days; this one is off by an unknown, unbounded
     *   amount). Excluded rather than fabricated.
     *
     *   INCLUDED (12) — every other detector already has a genuine discrete-event
     *   timestamp column. Each query below reuses that detector's exact state-match
     *   WHERE clause verbatim, with ONLY the {@code exception_resolutions}/{@code
     *   resolved} suppression clause dropped (that clause answers "does this still
     *   need an operator," a different question from "did this happen that day").
     *   This intentionally means a self-resolving detector's count can shift on
     *   re-query if its underlying state later changes (e.g. an exchange leaves
     *   'needs_mapping') — accepted, not a bug: this is what "live aggregation, no
     *   stored snapshot" means, the same characteristic the currently-open badge
     *   already has (re-querying "today" tomorrow gives a different number for
     *   "today" too, because the DB state moved).
     */
    private List<Instant> exceptionsRaw(UUID tid, Timestamp lower) {
        List<Instant> all = new ArrayList<>();

        // lost
        all.addAll(jdbc.query(
            "SELECT COALESCE(p.last_event_at, p.created_at) FROM pieces p " +
            "WHERE p.tenant_id = ? AND p.status = 'lost'::piece_status " +
            "  AND COALESCE(p.last_event_at, p.created_at) >= ?",
            TS_MAPPER, tid, lower));

        // unmatched_delivery
        all.addAll(jdbc.query(
            "SELECT u.first_seen_at FROM unlinked_bosta_deliveries u " +
            "WHERE u.tenant_id = ? AND u.first_seen_at >= ?",
            TS_MAPPER, tid, lower));

        // unexpected_return
        all.addAll(jdbc.query(
            "SELECT pe.occurred_at FROM pieces p " +
            "JOIN piece_events pe ON pe.piece_id = p.id " +
            "    AND pe.event_type = 'return_received' " +
            "    AND pe.from_status IN ('with_courier'::piece_status, 'awaiting_pickup'::piece_status) " +
            "WHERE p.tenant_id = ? AND p.status = 'return_pending_inspection'::piece_status " +
            "  AND pe.occurred_at >= ?",
            TS_MAPPER, tid, lower));

        // guided_unpack
        all.addAll(jdbc.query(
            "SELECT o.cancel_requested_at FROM orders o " +
            "WHERE o.tenant_id = ? AND o.cancel_requested_at IS NOT NULL " +
            "  AND o.status IN ('packed'::order_status, 'self_pickup_pending'::order_status) " +
            "  AND o.cancel_requested_at >= ?",
            TS_MAPPER, tid, lower));

        // shopify_cancel_vs_inflight
        all.addAll(jdbc.query(
            "SELECT o.shopify_cancel_requested_at FROM orders o " +
            "WHERE o.tenant_id = ? AND o.shopify_cancel_requested_at IS NOT NULL " +
            "  AND o.status = 'awaiting_pickup'::order_status " +
            "  AND o.shopify_cancel_requested_at >= ?",
            TS_MAPPER, tid, lower));

        // cancelled_with_live_shipment
        all.addAll(jdbc.query(
            "SELECT COALESCE(o.cancel_requested_at, o.shopify_cancel_requested_at, " +
            "                s.last_synced_at, o.created_at) " +
            "FROM orders o " +
            "JOIN LATERAL ( " +
            "    SELECT id, internal_state, last_synced_at FROM shipments " +
            "    WHERE order_id = o.id AND tenant_id = o.tenant_id AND shipment_leg = 'forward' " +
            "    ORDER BY created_at DESC, id DESC LIMIT 1 " +
            ") s ON true " +
            "WHERE o.tenant_id = ? AND o.status = 'cancelled'::order_status " +
            "  AND s.internal_state NOT IN ( " +
            "      'delivered'::shipment_internal_state, 'returned'::shipment_internal_state, " +
            "      'lost'::shipment_internal_state, 'terminated'::shipment_internal_state, " +
            "      'cancelled'::shipment_internal_state) " +
            "  AND COALESCE(o.cancel_requested_at, o.shopify_cancel_requested_at, " +
            "               s.last_synced_at, o.created_at) >= ?",
            TS_MAPPER, tid, lower));

        // cancelled_but_delivered
        all.addAll(jdbc.query(
            "SELECT COALESCE(o.cancel_requested_at, o.shopify_cancel_requested_at, " +
            "                s.last_synced_at, o.created_at) " +
            "FROM orders o " +
            "JOIN LATERAL ( " +
            "    SELECT id, internal_state, last_synced_at FROM shipments " +
            "    WHERE order_id = o.id AND tenant_id = o.tenant_id AND shipment_leg = 'forward' " +
            "    ORDER BY created_at DESC, id DESC LIMIT 1 " +
            ") s ON true " +
            "WHERE o.tenant_id = ? AND o.status = 'cancelled'::order_status " +
            "  AND s.internal_state = 'delivered'::shipment_internal_state " +
            "  AND COALESCE(o.cancel_requested_at, o.shopify_cancel_requested_at, " +
            "               s.last_synced_at, o.created_at) >= ?",
            TS_MAPPER, tid, lower));

        // missing_awb
        all.addAll(jdbc.query(
            "SELECT s.awb_print_failed_at FROM shipments s " +
            "WHERE s.tenant_id = ? AND s.awb_print_failed_reason IS NOT NULL " +
            "  AND s.awb_print_failed_at >= ?",
            TS_MAPPER, tid, lower));

        // shopify_edit_conflict
        all.addAll(jdbc.query(
            "SELECT o.shopify_edit_conflict_at FROM orders o " +
            "WHERE o.tenant_id = ? AND o.shopify_edit_conflict_at IS NOT NULL " +
            "  AND o.shopify_edit_conflict_at >= ?",
            TS_MAPPER, tid, lower));

        // return_mismatch
        all.addAll(jdbc.query(
            "SELECT rsi.disposition_at FROM return_session_items rsi " +
            "WHERE rsi.tenant_id = ? AND rsi.disposition = 'mismatch' " +
            "  AND rsi.disposition_at >= ?",
            TS_MAPPER, tid, lower));

        // exchange_needs_mapping
        all.addAll(jdbc.query(
            "SELECT e.created_at FROM exchanges e " +
            "WHERE e.tenant_id = ? AND e.status = 'needs_mapping' AND e.created_at >= ?",
            TS_MAPPER, tid, lower));

        // missing_provider_id
        all.addAll(jdbc.query(
            "SELECT s.created_at FROM shipments s " +
            "WHERE s.tenant_id = ? AND s.provider_id_fetch_failed = true " +
            "  AND s.provider_delivery_id IS NULL " +
            "  AND s.internal_state NOT IN ( " +
            "      'delivered'::shipment_internal_state, 'returned'::shipment_internal_state, " +
            "      'lost'::shipment_internal_state, 'terminated'::shipment_internal_state, " +
            "      'cancelled'::shipment_internal_state) " +
            "  AND s.created_at >= ?",
            TS_MAPPER, tid, lower));

        return all;
    }

    // Returns = return_sessions.opened_at — ALL sessions opened that day regardless of
    // eventual status (open/closed/abandoned). This is a flow count of work started,
    // not a stock count of work outstanding (that's /returns/pending, untouched).
    private List<Instant> returnsRaw(UUID tid, Timestamp lower) {
        return jdbc.query(
            "SELECT opened_at FROM return_sessions WHERE tenant_id = ? AND opened_at >= ?",
            TS_MAPPER, tid, lower);
    }

    // ── Bucketing / range-total helpers ─────────────────────────────────────

    private List<TrendPoint> bucketize(List<Instant> raw, LocalDate today) {
        Map<LocalDate, Integer> counts = new HashMap<>();
        for (Instant t : raw) {
            counts.merge(t.atZone(CAIRO).toLocalDate(), 1, Integer::sum);
        }
        List<TrendPoint> series = new ArrayList<>(WINDOW_DAYS);
        for (int i = WINDOW_DAYS - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            series.add(new TrendPoint(d.toString(), counts.getOrDefault(d, 0)));
        }
        return series;
    }

    private int countInRange(List<Instant> raw, LocalDate from, LocalDate to) {
        int n = 0;
        for (Instant t : raw) {
            LocalDate d = t.atZone(CAIRO).toLocalDate();
            if (!d.isBefore(from) && !d.isAfter(to)) n++;
        }
        return n;
    }

    private MetricTrend countMetric(String metric, List<Instant> raw, LocalDate today, LocalDate from, LocalDate to) {
        return new MetricTrend(metric, countInRange(raw, from, to), bucketize(raw, today));
    }

    private List<TrendPoint> bucketizeAmount(List<CodEvent> raw, LocalDate today) {
        Map<LocalDate, BigDecimal> sums = new HashMap<>();
        for (CodEvent e : raw) {
            LocalDate d = e.occurredAt().atZone(CAIRO).toLocalDate();
            sums.merge(d, e.codAmount(), BigDecimal::add);
        }
        List<TrendPoint> series = new ArrayList<>(WINDOW_DAYS);
        for (int i = WINDOW_DAYS - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            BigDecimal sum = sums.getOrDefault(d, BigDecimal.ZERO);
            series.add(new TrendPoint(d.toString(), sum.setScale(0, RoundingMode.HALF_UP).intValue()));
        }
        return series;
    }

    private double sumInRange(List<CodEvent> raw, LocalDate from, LocalDate to) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CodEvent e : raw) {
            LocalDate d = e.occurredAt().atZone(CAIRO).toLocalDate();
            if (!d.isBefore(from) && !d.isAfter(to)) sum = sum.add(e.codAmount());
        }
        return sum.setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private MetricTrend codMetric(String metric, List<CodEvent> raw, LocalDate today, LocalDate from, LocalDate to) {
        return new MetricTrend(metric, sumInRange(raw, from, to), bucketizeAmount(raw, today));
    }

    // ── Late-to-pack (live state, NOT scoped by the date-range picker) ─────────
    //
    // BUG (fixed): the previous version filtered on the raw `orders.status` enum alone
    // (status IN new/confirmed/ready_to_pick/picking). That column only ever advances via
    // FulfillService's own scan-gated pack flow (completePack() -> 'packed'/'self_pickup_pending',
    // ShipmentLinkService -> 'awaiting_pickup') — it never reflects a shipment that progressed
    // (with_courier, delivered, returned, ...) without Traced's own pack scan ever firing for
    // that order, which is exactly what happens on a Mode-B early webhook match
    // (ShipmentLinkService.tryMatchDelivery(), unguarded) or any order fulfilled outside
    // Traced's scan flow entirely — orders.status stays stuck at a pre-pack value forever while
    // the real shipment is long since delivered. That's why the ratio of inflated/real orders
    // differed per tenant (how much of that tenant's fulfillment ever touches Traced's own pack
    // scan): not a join fanout (COUNT(*) with no join to a multi-row child table — no fanout
    // possible), a stale/never-advanced status column.
    //
    // Fix: use OrderStatusDeriver.derive() — the ONE place order status is decided elsewhere
    // in this codebase (OrderController.list()/detail()/funnel()) — instead of reading
    // orders.status directly, so a shipment's real progress is checked, not just Traced's own
    // pack-flow flag. "Still pre-pack" = derive()'s primaryKey resolves to New or Picking,
    // using funnel()'s own established Mode-B fallback (a shipment record existing is NOT proof
    // packing happened; packedConfirmed decides). One row per order via the same
    // shipment_leg='forward', ORDER BY created_at DESC (never id DESC — UUIDv4 isn't
    // time-ordered), LIMIT 1 LATERAL join funnel() already uses — COUNT is over DISTINCT
    // orders by construction, not a join-inflated row count.
    @Transactional(readOnly = true)
    public LateToPack lateToPack() {
        UUID tid = TenantContext.require();
        Instant now = clock.instant();
        Timestamp cutoff24 = Timestamp.from(now.minusSeconds(24 * 3600L));
        Timestamp cutoff48 = Timestamp.from(now.minusSeconds(48 * 3600L));

        record Candidate(String orderStatus, Timestamp placedAt, OrderStatusDeriver.DerivedOrderStatus derived) {}

        // placed_at < cutoff24 (the older of the two cutoffs) bounds the fetch to only
        // candidates that could possibly count toward either number — over48 is a strict
        // subset of overdue's placed_at threshold, never a wider one.
        List<Candidate> candidates = jdbc.query("""
            SELECT o.status, o.placed_at, o.not_traced_at,
                   s.internal_state            AS delivery_state,
                   COALESCE(s.failed_delivery_attempts, 0) AS failed_delivery_attempts,
                   COALESCE(s.number_of_attempts, 0)       AS number_of_attempts,
                   s.exception_code, s.is_delayed, s.sla_breached,
                   s.max_progress_rank
            FROM orders o
            LEFT JOIN LATERAL (
                SELECT internal_state, number_of_attempts,
                       exception_code, is_delayed, sla_breached,
                       (SELECT COUNT(*) FROM shipment_status_history h2
                        LEFT JOIN ndr_codes n ON n.code = h2.exception_code
                        WHERE h2.shipment_id = sh.id AND h2.internal_state = 'exception'
                          AND h2.exception_code IS NOT NULL
                          AND (n.category = 'forward' OR n.category IS NULL)
                       ) AS failed_delivery_attempts,
                       COALESCE(
                           (SELECT MAX(CASE h.internal_state
                                       WHEN 'created'      THEN 1
                                       WHEN 'with_courier'  THEN 2
                                       WHEN 'returning'     THEN 3
                                       WHEN 'exception'     THEN 0
                                   END)
                            FROM shipment_status_history h
                            WHERE h.shipment_id = sh.id),
                           CASE sh.internal_state
                               WHEN 'created'      THEN 1
                               WHEN 'with_courier'  THEN 2
                               WHEN 'returning'     THEN 3
                               WHEN 'exception'     THEN 0
                           END
                       ) AS max_progress_rank
                FROM shipments sh
                WHERE order_id = o.id AND tenant_id = o.tenant_id
                  AND shipment_leg = 'forward'
                ORDER BY created_at DESC, id DESC
                LIMIT 1
            ) s ON true
            WHERE o.tenant_id = ? AND o.placed_at IS NOT NULL AND o.placed_at < ?
            """,
            (rs, i) -> {
                String orderStatus = rs.getString("status");
                Timestamp notTracedAt = rs.getTimestamp("not_traced_at");
                var derived = OrderStatusDeriver.derive(
                    orderStatus,
                    rs.getString("delivery_state"),
                    rs.getObject("max_progress_rank", Integer.class),
                    rs.getInt("number_of_attempts"),
                    rs.getInt("failed_delivery_attempts"),
                    rs.getObject("exception_code", Integer.class),
                    rs.getObject("is_delayed", Boolean.class),
                    rs.getObject("sla_breached", Boolean.class),
                    notTracedAt != null);
                return new Candidate(orderStatus, rs.getTimestamp("placed_at"), derived);
            },
            tid, cutoff24);

        int overdue = 0, over48 = 0;
        for (Candidate c : candidates) {
            if (!isStillPrePack(c.orderStatus(), c.derived())) continue;
            if (c.placedAt().before(cutoff24)) overdue++;
            if (c.placedAt().before(cutoff48)) over48++;
        }
        return new LateToPack(overdue, over48);
    }

    // Mirrors funnel()'s New+Picking classification exactly (OrderController.funnel()) —
    // both must agree on what "not yet packed" means. A shipment record existing
    // (status.awaiting_courier / status.label_created) is not proof packing happened;
    // when packedConfirmed is false, the order is still pre-pack regardless of what
    // rank the shipment itself reached.
    private static boolean isStillPrePack(String orderStatus, OrderStatusDeriver.DerivedOrderStatus derived) {
        String primaryKey = derived.primaryKey();
        boolean isCourierAwbState =
            "status.awaiting_courier".equals(primaryKey) || "status.label_created".equals(primaryKey);
        if (isCourierAwbState && !derived.packedConfirmed()) {
            return true;
        }
        return switch (primaryKey) {
            case "status.new", "status.confirmed", "status.ready_to_pick", "status.picking" -> true;
            default -> false;
        };
    }

    // ── Top-selling SKUs ─────────────────────────────────────────────────────

    // Units ordered per SKU (grouped by variant id, not the nullable sku string —
    // several variants can share a null sku), rolling 30 Cairo-days, top 5,
    // cancelled orders excluded. title = "{product} — {variant}" unless the variant
    // title is Shopify's single-variant placeholder ("Default Title"), matching the
    // existing Lookup.tsx precedent of hiding that placeholder rather than showing it.
    @Transactional(readOnly = true)
    public List<TopSku> topSkus() {
        UUID tid = TenantContext.require();
        LocalDate today = LocalDate.now(clock);
        Instant lowerBound = today.minusDays(TOP_SKUS_WINDOW_DAYS - 1L).atStartOfDay(CAIRO).toInstant();

        return jdbc.query(
            """
            SELECT v.sku, v.title AS variant_title, pr.title AS product_title,
                   pr.image_url, SUM(oi.quantity)::int AS units
            FROM order_items oi
            JOIN orders o    ON o.id = oi.order_id
            JOIN variants v  ON v.id = oi.variant_id
            JOIN products pr ON pr.id = v.product_id
            WHERE oi.tenant_id = ?
              AND o.placed_at >= ?
              AND o.status <> 'cancelled'::order_status
            GROUP BY v.id, v.sku, v.title, pr.title, pr.image_url
            ORDER BY units DESC
            LIMIT ?
            """,
            (rs, i) -> new TopSku(
                rs.getString("sku"),
                titleFor(rs.getString("product_title"), rs.getString("variant_title")),
                rs.getString("image_url"),
                rs.getInt("units")),
            tid, Timestamp.from(lowerBound), TOP_SKUS_LIMIT);
    }

    private static String titleFor(String productTitle, String variantTitle) {
        if (variantTitle == null || variantTitle.isBlank() || "Default Title".equals(variantTitle)) {
            return productTitle;
        }
        return productTitle + " — " + variantTitle;
    }
}
