package com.traceability.overview;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Overview dashboard trends (FR-Overview §2) — 5 stat-card sparklines + Top-selling
 * SKUs. LIVE AGGREGATION over source-event timestamps only: no daily-snapshot table,
 * no rollup job, no stored counter that could drift from the ledger.
 *
 * Day bucketing is always Africa/Cairo local calendar days, via the same Cairo-pinned
 * {@link Clock} bean AppConfig already injects app-wide (never the JVM/DB default —
 * confirmed neither is Cairo). Bucketing happens in Java (Instant -> Cairo LocalDate),
 * not in SQL, so all 5 metrics share one bucketing/zero-fill implementation instead of
 * repeating an `AT TIME ZONE 'Africa/Cairo'` CTE five times.
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

    public record MetricTrend(
        String metric, int today, int yesterday, Double deltaPct, List<TrendPoint> series) {}

    public record TopSku(String sku, String title, String imageUrl, int units) {}

    // ── Trends ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MetricTrend> trends() {
        UUID tid = TenantContext.require();
        LocalDate today = LocalDate.now(clock);
        Instant lowerBound = today.minusDays(WINDOW_DAYS - 1L).atStartOfDay(CAIRO).toInstant();
        Timestamp lowerTs = Timestamp.from(lowerBound);

        List<MetricTrend> out = new ArrayList<>();
        out.add(toTrend("orders",     bucketize(ordersRaw(tid, lowerTs), today)));
        out.add(toTrend("shipments",  bucketize(shipmentsRaw(tid, lowerTs), today)));
        out.add(toTrend("delivered",  bucketize(deliveredRaw(tid, lowerTs), today)));
        out.add(toTrend("exceptions", bucketize(exceptionsRaw(tid, lowerTs), today)));
        out.add(toTrend("returns",    bucketize(returnsRaw(tid, lowerTs), today)));
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

    // Shipments = shipments.created_at, forward leg only — "first linked/dispatched"
    // is the outbound-to-customer shipment record's own creation moment. Scoped to
    // shipment_leg='forward' matching funnel()/throughput()'s existing precedent
    // (a return-leg shipment record isn't a "dispatch").
    private List<Instant> shipmentsRaw(UUID tid, Timestamp lower) {
        return jdbc.query(
            "SELECT created_at FROM shipments " +
            "WHERE tenant_id = ? AND shipment_leg = 'forward' AND created_at >= ?",
            TS_MAPPER, tid, lower);
    }

    // Delivered = shipment_status_history.occurred_at WHERE internal_state='delivered',
    // NOT shipments.internal_state. Resolves the Step-0 ambiguity: shipments has no
    // delivered_at column at all (only last_synced_at, rewritten on every sync, and
    // created_at) — shipment_status_history is the one real per-transition timestamp,
    // the same source funnel()/summary() already prefer over shipments' own stored
    // snapshot fields (see their failed_delivery_attempts comment). Forward leg only,
    // matching the Shipments metric above.
    private List<Instant> deliveredRaw(UUID tid, Timestamp lower) {
        return jdbc.query(
            """
            SELECT h.occurred_at
            FROM shipment_status_history h
            JOIN shipments s ON s.id = h.shipment_id AND s.tenant_id = h.tenant_id
                             AND s.shipment_leg = 'forward'
            WHERE h.tenant_id = ? AND h.internal_state = 'delivered' AND h.occurred_at >= ?
            """,
            TS_MAPPER, tid, lower);
    }

    // Returns = return_sessions.opened_at — ALL sessions opened that day regardless of
    // eventual status (open/closed/abandoned). This is a flow count of work started,
    // not a stock count of work outstanding (that's /returns/pending, untouched).
    private List<Instant> returnsRaw(UUID tid, Timestamp lower) {
        return jdbc.query(
            "SELECT opened_at FROM return_sessions WHERE tenant_id = ? AND opened_at >= ?",
            TS_MAPPER, tid, lower);
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

    // ── Bucketing / delta ────────────────────────────────────────────────────

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

    private MetricTrend toTrend(String metric, List<TrendPoint> series) {
        int today     = series.get(series.size() - 1).count();
        int yesterday = series.get(series.size() - 2).count();
        // Guard yesterday=0: a percentage change against a zero baseline is either
        // undefined (0 -> 0) or infinite (0 -> N) — neither is a real percentage.
        // null lets the frontend render "—"/"New" instead of a fabricated number.
        Double deltaPct = (yesterday == 0) ? null :
            BigDecimal.valueOf((today - yesterday) * 100.0 / yesterday)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new MetricTrend(metric, today, yesterday, deltaPct, series);
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
