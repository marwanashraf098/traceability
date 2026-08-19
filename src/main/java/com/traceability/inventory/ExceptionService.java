package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Exceptions center (FR-15.3, +1 detector FR-24, +2 detectors FR-EXCHANGE): aggregates 20
 * exception detectors into one prioritised list, sorted CRITICAL→HIGH→MEDIUM→LOW then
 * oldest-first within each severity tier.
 *
 * Aggregation: per-type queries run separately and merged in Java.
 * A single UNION would require all of them to emit identical columns,
 * and the stuck-shipment resolved_at>last_synced_at staleness check is
 * awkward to express generically. Per-type Java merge is readable,
 * independently testable, and trivially extensible.
 *
 * Resolution suppression: each detector has a NOT EXISTS sub-query against
 * exception_resolutions keyed on (exception_type, subject_key).
 * Stuck shipments use an additional resolved_at > last_synced_at guard so
 * a post-ack Bosta sync reactivates the exception.
 */
@Service
public class ExceptionService {

    private static final Map<String, Integer> SEVERITY_ORDER = Map.of(
        "CRITICAL", 0,
        "HIGH",     1,
        "MEDIUM",   2,
        "LOW",      3
    );

    private final JdbcTemplate jdbc;
    private final Clock        clock;

    public ExceptionService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc  = jdbc;
        this.clock = clock;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> listExceptions(String typeFilter, String severityFilter,
                                               int page, int size) {
        UUID tenantId = TenantContext.require();

        // Per-tenant config
        Map<String, Object> cfg = jdbc.queryForMap(
            "SELECT never_received_window_days, stuck_shipment_days, " +
            "       return_in_transit_stuck_days " +
            "FROM tenants WHERE id = ?", tenantId);
        int neverReceivedDays       = ((Number) cfg.get("never_received_window_days")).intValue();
        int stuckDays               = ((Number) cfg.get("stuck_shipment_days")).intValue();
        int returnInTransitStuckDays = ((Number) cfg.get("return_in_transit_stuck_days")).intValue();

        // Collect all open exceptions
        List<Map<String, Object>> all = new ArrayList<>();
        all.addAll(detectLost(tenantId));
        all.addAll(detectNeverReceived(tenantId, neverReceivedDays));
        all.addAll(detectUnmatched(tenantId));
        all.addAll(detectBlocked(tenantId));
        all.addAll(detectStuck(tenantId, stuckDays));
        all.addAll(detectUnexpectedReturn(tenantId));
        all.addAll(detectDeliveryLimbo(tenantId));
        all.addAll(detectNdr(tenantId));
        all.addAll(detectGuidedUnpack(tenantId));
        all.addAll(detectMissingAwb(tenantId));
        // Grouped: all three "cancel vs. still-in-flight" signals live together —
        // shopify_cancel_vs_inflight (status=awaiting_pickup) is mutually exclusive with
        // the two below (status=cancelled), so none of the three can double-fire.
        all.addAll(detectShopifyCancelVsInflight(tenantId));
        all.addAll(detectCancelledWithLiveShipment(tenantId));
        all.addAll(detectCancelledButDelivered(tenantId));
        all.addAll(detectMissingProviderId(tenantId));
        all.addAll(detectHighAttempts(tenantId));
        all.addAll(detectShopifyEditConflict(tenantId));
        all.addAll(detectReturnInTransitStuck(tenantId, returnInTransitStuckDays));
        all.addAll(detectReturnSessionMismatch(tenantId));
        all.addAll(detectExchangeNeedsMapping(tenantId));
        all.addAll(detectExchangeUnmappedState(tenantId));

        // Enrich with descriptions and action hints
        all.forEach(this::enrich);

        // Optional filters
        if (typeFilter != null && !typeFilter.isBlank()) {
            all = all.stream()
                .filter(e -> typeFilter.equals(e.get("type")))
                .collect(java.util.stream.Collectors.toList());
        }
        if (severityFilter != null && !severityFilter.isBlank()) {
            all = all.stream()
                .filter(e -> severityFilter.equals(e.get("severity")))
                .collect(java.util.stream.Collectors.toList());
        }

        // Sort: severity asc (CRITICAL first), then occurredAt asc (oldest first)
        Instant epoch = Instant.EPOCH;
        all.sort(Comparator
            .comparingInt((Map<String, Object> e) ->
                SEVERITY_ORDER.getOrDefault((String) e.get("severity"), 99))
            .thenComparing(e -> toInstant(e.get("occurred_at"), epoch)));

        // Enrich with ageSeconds
        Instant now = clock.instant();
        all.forEach(e -> e.put("ageSeconds",
            Duration.between(toInstant(e.get("occurred_at"), now), now).getSeconds()));

        // Paginate
        int total  = all.size();
        int from   = Math.min(page * size, total);
        int to     = Math.min(from + size, total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page",  page);
        result.put("size",  size);
        result.put("items", all.subList(from, to));
        return result;
    }

    /** total = critical + warning; critical = severity CRITICAL, warning = HIGH+MEDIUM+LOW collapsed. */
    public record OpenExceptionCounts(int total, int critical, int warning) {}

    /**
     * Severity-bucketed count of currently-open exceptions — backs both the shell's
     * notification-bell number (via countOpenExceptions() below, unchanged signature)
     * and the Overview dashboard's exceptions-by-severity split. Deliberately calls the
     * SAME 17 detector methods listExceptions() calls (same config, same suppression
     * sub-queries), just tallying severities instead of enriching/sorting/paginating —
     * two independent implementations of the same 17 business rules would drift.
     * Equal in total to listExceptions(null,null,0,MAX).total.
     *
     * 4-tier severity (CRITICAL/HIGH/MEDIUM/LOW, as used by listExceptions()) collapses
     * to 2 buckets for the dashboard: CRITICAL → critical, everything else → warning.
     * This is a display simplification, not a new severity taxonomy — listExceptions()
     * and the exceptions list page keep the full 4-tier severity untouched.
     */
    @Transactional(readOnly = true)
    public OpenExceptionCounts countOpenExceptionsBySeverity() {
        UUID tenantId = TenantContext.require();

        Map<String, Object> cfg = jdbc.queryForMap(
            "SELECT never_received_window_days, stuck_shipment_days, " +
            "       return_in_transit_stuck_days " +
            "FROM tenants WHERE id = ?", tenantId);
        int neverReceivedDays        = ((Number) cfg.get("never_received_window_days")).intValue();
        int stuckDays                = ((Number) cfg.get("stuck_shipment_days")).intValue();
        int returnInTransitStuckDays = ((Number) cfg.get("return_in_transit_stuck_days")).intValue();

        List<Map<String, Object>> all = new ArrayList<>();
        all.addAll(detectLost(tenantId));
        all.addAll(detectNeverReceived(tenantId, neverReceivedDays));
        all.addAll(detectUnmatched(tenantId));
        all.addAll(detectBlocked(tenantId));
        all.addAll(detectStuck(tenantId, stuckDays));
        all.addAll(detectUnexpectedReturn(tenantId));
        all.addAll(detectDeliveryLimbo(tenantId));
        all.addAll(detectNdr(tenantId));
        all.addAll(detectGuidedUnpack(tenantId));
        all.addAll(detectMissingAwb(tenantId));
        all.addAll(detectShopifyCancelVsInflight(tenantId));
        all.addAll(detectCancelledWithLiveShipment(tenantId));
        all.addAll(detectCancelledButDelivered(tenantId));
        all.addAll(detectMissingProviderId(tenantId));
        all.addAll(detectHighAttempts(tenantId));
        all.addAll(detectShopifyEditConflict(tenantId));
        all.addAll(detectReturnInTransitStuck(tenantId, returnInTransitStuckDays));
        all.addAll(detectReturnSessionMismatch(tenantId));
        all.addAll(detectExchangeNeedsMapping(tenantId));
        all.addAll(detectExchangeUnmappedState(tenantId));

        int critical = 0;
        for (Map<String, Object> e : all) {
            if ("CRITICAL".equals(e.get("severity"))) critical++;
        }
        int total = all.size();
        return new OpenExceptionCounts(total, critical, total - critical);
    }

    /**
     * Count of currently-open exceptions — the shell's notification-bell number.
     * Thin delegator over countOpenExceptionsBySeverity() so the two never drift and
     * this method's `int` contract (relied on by ExceptionRlsTest) stays unchanged.
     */
    @Transactional(readOnly = true)
    public int countOpenExceptions() {
        return countOpenExceptionsBySeverity().total();
    }

    @Transactional
    public void resolve(String exceptionType, String subjectKey, UUID resolvedBy, String note) {
        UUID tenantId = TenantContext.require();
        jdbc.update(
            "INSERT INTO exception_resolutions " +
            "(tenant_id, exception_type, subject_key, resolved_by, note) " +
            "VALUES (?, ?, ?, ?, ?)",
            tenantId, exceptionType, subjectKey, resolvedBy, note);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listResolutions(int page, int size) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT er.id, er.exception_type, er.subject_key, er.resolved_at, er.note, " +
            "       u.name AS resolved_by_name " +
            "FROM exception_resolutions er " +
            "JOIN users u ON u.id = er.resolved_by " +
            "WHERE er.tenant_id = ? " +
            "ORDER BY er.resolved_at DESC LIMIT ? OFFSET ?",
            tenantId, size, (long) page * size);
    }

    // ── Detectors ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> detectLost(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'lost' AS type, 'CRITICAL' AS severity, 'piece' AS subject_type, " +
            "       p.id AS piece_id, p.barcode, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       COALESCE(p.last_event_at, p.created_at) AS occurred_at, " +
            "       'lost:piece:' || p.id AS subject_key " +
            "FROM pieces p " +
            "LEFT JOIN orders o ON o.id = p.current_order_id " +
            "LEFT JOIN shipments s ON s.order_id = o.id AND s.shipment_leg = 'forward' " +
            "WHERE p.status = 'lost'::piece_status " +
            "  AND p.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = p.tenant_id " +
            "        AND er.exception_type = 'lost' " +
            "        AND er.subject_key = 'lost:piece:' || p.id) ",
            tid);
    }

    private List<Map<String, Object>> detectNeverReceived(UUID tid, int windowDays) {
        return jdbc.queryForList(
            "SELECT 'never_received' AS type, 'HIGH' AS severity, 'piece' AS subject_type, " +
            "       p.id AS piece_id, p.barcode, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.tracking_number, s.returned_at AS occurred_at, " +
            "       'never_received:piece:' || p.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "JOIN order_items oi ON oi.order_id = o.id " +
            "JOIN allocations a  ON a.order_item_id = oi.id " +
            "                    AND a.status IN ('packed','active') " +
            "JOIN pieces p ON p.id = a.piece_id AND p.tenant_id = ? " +
            "WHERE s.shipment_leg = 'forward' " +
            "  AND s.internal_state = 'returned' " +
            "  AND s.returned_at IS NOT NULL " +
            "  AND s.returned_at < now() - (interval '1 day' * ?) " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM piece_events pe " +
            "      WHERE pe.piece_id = p.id AND pe.event_type = 'return_received' " +
            "        AND pe.tenant_id = ?) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = p.tenant_id " +
            "        AND er.exception_type = 'never_received' " +
            "        AND er.subject_key = 'never_received:piece:' || p.id) ",
            tid, tid, windowDays, tid, tid);
    }

    /**
     * Deliberately NOT filtered by bosta_order_type. Single-item EXCHANGE deliveries are
     * intercepted at ingest (BostaWebhookJob step 6.5) and never reach
     * unlinked_bosta_deliveries at all going forward; the 4 fleet-confirmed rows from
     * before that reroute existed are marked resolved=true by the V74 backfill, so this
     * query already excludes them via WHERE resolved = false. A bosta_order_type =
     * 'EXCHANGE' row CAN still land here going forward — that's the intentional
     * multi-item-anomaly fallback (see ExchangeIngestService javadoc) — and it must keep
     * surfacing as a real exception ("do not auto-handle"), so it is not filtered out.
     */
    private List<Map<String, Object>> detectUnmatched(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'unmatched_delivery' AS type, 'MEDIUM' AS severity, 'delivery' AS subject_type, " +
            "       u.id AS unlinked_id, u.tracking_number, u.business_reference, " +
            "       u.bosta_state_code, u.first_seen_at AS occurred_at, " +
            "       'unmatched:' || u.id AS subject_key " +
            "FROM unlinked_bosta_deliveries u " +
            "WHERE u.tenant_id = ? AND u.resolved = false " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = u.tenant_id " +
            "        AND er.exception_type = 'unmatched_delivery' " +
            "        AND er.subject_key = 'unmatched:' || u.id) ",
            tid);
    }

    private List<Map<String, Object>> detectBlocked(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'blocked_customer' AS type, 'LOW' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       o.customer_name, o.hold_reason, o.created_at AS occurred_at, " +
            "       'blocked:' || o.id AS subject_key " +
            "FROM orders o " +
            "WHERE o.tenant_id = ? AND o.on_hold = true " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = o.tenant_id " +
            "        AND er.exception_type = 'blocked_customer' " +
            "        AND er.subject_key = 'blocked:' || o.id) ",
            tid);
    }

    private List<Map<String, Object>> detectStuck(UUID tid, int stuckDays) {
        return jdbc.queryForList(
            "SELECT 'stuck_shipment' AS type, 'HIGH' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.internal_state::text AS shipment_state, " +
            "       s.number_of_attempts, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       COALESCE(s.last_synced_at, s.created_at) AS occurred_at, " +
            "       'stuck:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.shipment_leg = 'forward' " +
            "  AND s.internal_state NOT IN (" +
            "      'delivered'::shipment_internal_state," +
            "      'returned'::shipment_internal_state," +
            "      'lost'::shipment_internal_state," +
            "      'terminated'::shipment_internal_state," +
            "      'cancelled'::shipment_internal_state) " +
            "  AND COALESCE(s.last_synced_at, s.created_at) < now() - (interval '1 day' * ?) " +
            "  AND s.tenant_id = ? " +
            // A resolved_at newer than last_synced_at means the operator ack'd the
            // latest known state — suppress.  If Bosta syncs AFTER the ack,
            // resolved_at < new last_synced_at and the NOT EXISTS finds no valid row.
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'stuck_shipment' " +
            "        AND er.subject_key = 'stuck:shipment:' || s.id " +
            "        AND er.resolved_at > COALESCE(s.last_synced_at, s.created_at)) ",
            tid, stuckDays, tid);
    }

    private List<Map<String, Object>> detectUnexpectedReturn(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'unexpected_return' AS type, 'HIGH' AS severity, 'piece' AS subject_type, " +
            "       p.id AS piece_id, p.barcode, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       pe.occurred_at, " +
            "       'unexpected_return:' || p.id AS subject_key " +
            "FROM pieces p " +
            "JOIN piece_events pe ON pe.piece_id = p.id " +
            "    AND pe.event_type = 'return_received' " +
            "    AND pe.from_status IN ('with_courier'::piece_status, 'awaiting_pickup'::piece_status) " +
            "LEFT JOIN orders o ON o.id = p.current_order_id " +
            "WHERE p.tenant_id = ? " +
            "  AND p.status = 'return_pending_inspection'::piece_status " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = p.tenant_id " +
            "        AND er.exception_type = 'unexpected_return' " +
            "        AND er.subject_key = 'unexpected_return:' || p.id) ",
            tid);
    }

    private List<Map<String, Object>> detectDeliveryLimbo(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'delivery_limbo' AS type, 'HIGH' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.number_of_attempts, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       COALESCE(s.last_synced_at, s.created_at) AS occurred_at, " +
            "       'delivery_limbo:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.provider_state = 103 " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'delivery_limbo' " +
            "        AND er.subject_key = 'delivery_limbo:shipment:' || s.id) ",
            tid, tid);
    }

    private List<Map<String, Object>> detectNdr(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'ndr_failed' AS type, " +
            "       CASE WHEN nc.severity = 'critical' THEN 'CRITICAL' ELSE 'MEDIUM' END AS severity, " +
            "       'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.number_of_attempts, " +
            "       CASE WHEN s.raw->>'exceptionCode' IS NOT NULL " +
            "            THEN (s.raw->>'exceptionCode')::integer END AS ndr_code, " +
            "       nc.description AS ndr_description, " +
            "       nc.category AS ndr_category, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       COALESCE(s.last_synced_at, s.created_at) AS occurred_at, " +
            "       'ndr:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "LEFT JOIN ndr_codes nc " +
            "    ON nc.code = CASE WHEN s.raw->>'exceptionCode' IS NOT NULL " +
            "                      THEN (s.raw->>'exceptionCode')::integer END " +
            "WHERE s.provider_state = 47 " +
            "  AND s.internal_state = 'exception'::shipment_internal_state " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'ndr_failed' " +
            "        AND er.subject_key = 'ndr:shipment:' || s.id) ",
            tid, tid);
    }

    private List<Map<String, Object>> detectGuidedUnpack(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'guided_unpack' AS type, 'HIGH' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       o.cancel_requested_at AS occurred_at, " +
            "       'guided_unpack:order:' || o.id AS subject_key " +
            "FROM orders o " +
            "WHERE o.tenant_id = ? " +
            "  AND o.cancel_requested_at IS NOT NULL " +
            "  AND o.status IN ('packed'::order_status, 'self_pickup_pending'::order_status)",
            tid);
    }

    private List<Map<String, Object>> detectShopifyCancelVsInflight(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'shopify_cancel_vs_inflight' AS type, 'HIGH' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.tracking_number, " +
            "       o.shopify_cancel_requested_at AS occurred_at, " +
            "       'shopify_cancel_vs_inflight:order:' || o.id AS subject_key " +
            "FROM orders o " +
            "LEFT JOIN shipments s ON s.order_id = o.id AND s.tenant_id = o.tenant_id AND s.shipment_leg = 'forward' " +
            "WHERE o.tenant_id = ? " +
            "  AND o.shopify_cancel_requested_at IS NOT NULL " +
            "  AND o.status = 'awaiting_pickup'::order_status " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = o.tenant_id " +
            "        AND er.exception_type = 'shopify_cancel_vs_inflight' " +
            "        AND er.subject_key = 'shopify_cancel_vs_inflight:order:' || o.id) ",
            tid);
    }

    /**
     * B1 — order.status='cancelled' but the latest FORWARD shipment (by created_at DESC,
     * id DESC as tiebreak — UUIDv4 is not time-ordered, never order by id alone) is still
     * non-terminal: created/with_courier/returning/exception. The AWB is still live at
     * Bosta and the courier can still collect it even though the order is dead in Traced
     * (and/or Shopify) — Shopify cancel ≠ Bosta cancel.
     *
     * Self-resolving like detectGuidedUnpack — no exception_resolutions row: the exception
     * disappears the moment the shipment syncs into a terminal state.
     *
     * status='cancelled' alone doesn't say which cancel path fired — cancel_requested_at
     * (Traced-side cancelOrder()) vs shopify_cancel_requested_at (Shopify-side webhook).
     * The risk (AWB still live) is identical either way, so occurred_at is a path-agnostic
     * COALESCE and the message never assumes one path over the other.
     *
     * Mutually exclusive with detectShopifyCancelVsInflight on order.status ('cancelled'
     * here vs 'awaiting_pickup' there) — cannot double-fire for the same order.
     */
    private List<Map<String, Object>> detectCancelledWithLiveShipment(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'cancelled_live_shipment' AS type, 'HIGH' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.internal_state::text AS shipment_state, " +
            "       COALESCE(o.cancel_requested_at, o.shopify_cancel_requested_at, " +
            "                s.last_synced_at, o.created_at) AS occurred_at, " +
            "       'cancelled_live_shipment:order:' || o.id AS subject_key " +
            "FROM orders o " +
            "JOIN LATERAL ( " +
            "    SELECT id, tracking_number, internal_state, last_synced_at " +
            "    FROM shipments " +
            "    WHERE order_id = o.id AND tenant_id = o.tenant_id AND shipment_leg = 'forward' " +
            // UUIDv4 is not time-ordered — order by created_at, never id.
            "    ORDER BY created_at DESC, id DESC " +
            "    LIMIT 1 " +
            ") s ON true " +
            "WHERE o.tenant_id = ? " +
            "  AND o.status = 'cancelled'::order_status " +
            "  AND s.internal_state NOT IN ( " +
            "      'delivered'::shipment_internal_state, " +
            "      'returned'::shipment_internal_state, " +
            "      'lost'::shipment_internal_state, " +
            "      'terminated'::shipment_internal_state, " +
            "      'cancelled'::shipment_internal_state) ",
            tid);
    }

    /**
     * B1 — order.status='cancelled' but the latest FORWARD shipment already reached
     * 'delivered' — the courier delivered it despite the cancellation. Distinct code from
     * {@link #detectCancelledWithLiveShipment} because the remediation is different
     * (reconcile COD / arrange a return, not "cancel the AWB before it's collected").
     * Self-resolving the same way — no exception_resolutions row.
     */
    private List<Map<String, Object>> detectCancelledButDelivered(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'cancelled_but_delivered' AS type, 'HIGH' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       COALESCE(o.cancel_requested_at, o.shopify_cancel_requested_at, " +
            "                s.last_synced_at, o.created_at) AS occurred_at, " +
            "       'cancelled_but_delivered:order:' || o.id AS subject_key " +
            "FROM orders o " +
            "JOIN LATERAL ( " +
            "    SELECT id, tracking_number, internal_state, last_synced_at " +
            "    FROM shipments " +
            "    WHERE order_id = o.id AND tenant_id = o.tenant_id AND shipment_leg = 'forward' " +
            // UUIDv4 is not time-ordered — order by created_at, never id.
            "    ORDER BY created_at DESC, id DESC " +
            "    LIMIT 1 " +
            ") s ON true " +
            "WHERE o.tenant_id = ? " +
            "  AND o.status = 'cancelled'::order_status " +
            "  AND s.internal_state = 'delivered'::shipment_internal_state ",
            tid);
    }

    private List<Map<String, Object>> detectMissingAwb(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'missing_awb' AS type, 'MEDIUM' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.awb_print_failed_reason AS failed_reason, " +
            "       s.awb_print_failed_at AS occurred_at, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       'missing_awb:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.awb_print_failed_reason IS NOT NULL " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'missing_awb' " +
            "        AND er.subject_key = 'missing_awb:shipment:' || s.id " +
            "        AND er.resolved_at > COALESCE(s.awb_print_failed_at, s.created_at)) ",
            tid, tid);
    }

    private List<Map<String, Object>> detectHighAttempts(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'high_attempts' AS type, 'MEDIUM' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       s.number_of_attempts, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       COALESCE(s.last_synced_at, s.created_at) AS occurred_at, " +
            "       'high_attempts:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id AND o.tenant_id = ? " +
            "WHERE s.number_of_attempts >= 2 " +
            "  AND s.internal_state NOT IN ( " +
            "      'delivered'::shipment_internal_state," +
            "      'returned'::shipment_internal_state," +
            "      'lost'::shipment_internal_state," +
            "      'terminated'::shipment_internal_state," +
            "      'cancelled'::shipment_internal_state) " +
            "  AND s.tenant_id = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'high_attempts' " +
            "        AND er.subject_key = 'high_attempts:shipment:' || s.id) ",
            tid, tid);
    }

    private List<Map<String, Object>> detectShopifyEditConflict(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'shopify_edit_conflict' AS type, 'HIGH' AS severity, 'order' AS subject_type, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       o.status::text AS order_status, " +
            "       o.shopify_edit_conflict_diff::text AS diff_json, " +
            "       o.shopify_edit_conflict_at AS occurred_at, " +
            "       'shopify_edit_conflict:order:' || o.id AS subject_key " +
            "FROM orders o " +
            "WHERE o.tenant_id = ? " +
            "  AND o.shopify_edit_conflict_at IS NOT NULL " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = o.tenant_id " +
            "        AND er.exception_type = 'shopify_edit_conflict' " +
            "        AND er.subject_key = 'shopify_edit_conflict:order:' || o.id) ",
            tid);
    }

    /**
     * 15th detector: piece stuck in return_in_transit with no warehouse intake scan.
     *
     * Fires when a piece has been at return_in_transit for more than
     * return_in_transit_stuck_days without receiving a return_received event.
     *
     * Does NOT deduplicate with detectStuck (subject=shipment, type=stuck_shipment)
     * or detectNeverReceived (fires only after shipment reaches 'returned' state).
     * These are different subjects and exception types — co-firing is correct.
     */
    private List<Map<String, Object>> detectReturnInTransitStuck(UUID tid, int stuckDays) {
        return jdbc.queryForList(
            "SELECT 'return_in_transit_stuck' AS type, 'HIGH' AS severity, 'piece' AS subject_type, " +
            "       p.id AS piece_id, p.barcode, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.tracking_number, " +
            "       p.last_event_at AS occurred_at, " +
            "       'return_in_transit_stuck:piece:' || p.id AS subject_key " +
            "FROM pieces p " +
            "JOIN allocations a  ON a.piece_id      = p.id " +
            "                    AND a.status        IN ('active','packed') " +
            "JOIN order_items oi ON oi.id            = a.order_item_id " +
            "JOIN orders o       ON o.id             = oi.order_id " +
            "JOIN shipments s    ON s.order_id       = o.id AND s.shipment_leg = 'forward' " +
            "WHERE p.status     = 'return_in_transit'::piece_status " +
            "  AND p.tenant_id  = ? " +
            "  AND p.last_event_at < now() - (interval '1 day' * ?) " +
            "  AND s.tenant_id  = ? " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM piece_events pe " +
            "      WHERE pe.piece_id   = p.id " +
            "        AND pe.event_type = 'return_received' " +
            "        AND pe.tenant_id  = ?) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id      = p.tenant_id " +
            "        AND er.exception_type = 'return_in_transit_stuck' " +
            "        AND er.subject_key    = 'return_in_transit_stuck:piece:' || p.id " +
            "        AND er.resolved_at    > now() - interval '7 days') ",
            tid, stuckDays, tid, tid);
    }

    /**
     * FR-24: a piece scanned into a return session that resolved to 'mismatch' —
     * either a legal-scan piece the operator flagged as "not the real piece", or an
     * ours-but-illegal-state piece (never eligible for restock/damage) explicitly
     * resolved via the mismatch release valve. Derived from return_session_items,
     * same NOT EXISTS/exception_resolutions suppression pattern as every other
     * detector — no separate persistence for this exception type.
     */
    private List<Map<String, Object>> detectReturnSessionMismatch(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'return_mismatch' AS type, 'MEDIUM' AS severity, 'piece' AS subject_type, " +
            "       p.id AS piece_id, p.barcode, " +
            "       rsi.session_id, " +
            "       rsi.disposition_at AS occurred_at, " +
            "       'return_mismatch:item:' || rsi.id AS subject_key " +
            "FROM return_session_items rsi " +
            "JOIN pieces p ON p.id = rsi.piece_id AND p.tenant_id = ? " +
            "WHERE rsi.tenant_id = ? " +
            "  AND rsi.disposition = 'mismatch' " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = rsi.tenant_id " +
            "        AND er.exception_type = 'return_mismatch' " +
            "        AND er.subject_key = 'return_mismatch:item:' || rsi.id) ",
            tid, tid);
    }

    /**
     * FR-EXCHANGE Phase 1: a Bosta-direct exchange upserted at ingest (§ExchangeIngestService)
     * that still needs its outbound/inbound descriptions mapped to catalog variants before
     * it can enter Fulfill/Returns. No exception_resolutions row is written by the mapping
     * step — mapping flips exchanges.status away from 'needs_mapping', which removes the row
     * from this query directly, so no separate resolution/suppression bookkeeping is needed
     * (same self-resolving pattern as detectGuidedUnpack).
     */
    private List<Map<String, Object>> detectExchangeNeedsMapping(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'exchange_needs_mapping' AS type, 'MEDIUM' AS severity, 'exchange' AS subject_type, " +
            "       e.id AS exchange_id, e.tracking_number, " +
            "       e.outbound_description, e.inbound_description, " +
            "       e.created_at AS occurred_at, " +
            "       'exchange_needs_mapping:' || e.id AS subject_key " +
            "FROM exchanges e " +
            "WHERE e.tenant_id = ? AND e.status = 'needs_mapping'",
            tid);
    }

    /**
     * FR-EXCHANGE Phase 3/4: the exchange's forward shipment (created at pack) has
     * reported a {@code state.value} the interpreter doesn't recognize yet
     * (ExchangeStateInterpreter FAIL-SAFE DEFAULT — see its javadoc). No transition was
     * applied; {@code raw} was still refreshed on every webhook, so this reads the
     * latest-known value straight off the shipment row rather than a separately stored
     * flag. Self-resolving (same pattern as detectExchangeNeedsMapping): once a future
     * pass adds the value to the interpreter's vocabulary and a later webhook re-reports
     * it (or the same value is re-fetched), the next webhook applies a real transition and
     * this query stops matching — no exception_resolutions bookkeeping needed.
     */
    private List<Map<String, Object>> detectExchangeUnmappedState(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'exchange_unmapped_state' AS type, 'MEDIUM' AS severity, 'exchange' AS subject_type, " +
            "       e.id AS exchange_id, e.tracking_number, e.outbound_order_id AS order_id, " +
            "       s.raw #>> '{state,value}' AS unmapped_state_value, " +
            "       s.last_synced_at AS occurred_at, " +
            "       'exchange_unmapped_state:' || e.id AS subject_key " +
            "FROM exchanges e " +
            "JOIN shipments s ON s.order_id = e.outbound_order_id AND s.shipment_leg = 'forward' " +
            "WHERE e.tenant_id = ? AND s.tenant_id = ? " +
            "  AND s.raw #>> '{state,value}' IS NOT NULL " +
            "  AND lower(trim(s.raw #>> '{state,value}')) NOT IN ('new', 'picked_up', 'in_transit')",
            tid, tid);
    }

    private List<Map<String, Object>> detectMissingProviderId(UUID tid) {
        return jdbc.queryForList(
            "SELECT 'missing_provider_id' AS type, 'MEDIUM' AS severity, 'shipment' AS subject_type, " +
            "       s.id AS shipment_id, s.tracking_number, " +
            "       o.id AS order_id, o.number AS order_number, " +
            "       s.created_at AS occurred_at, " +
            "       'missing_provider_id:shipment:' || s.id AS subject_key " +
            "FROM shipments s " +
            "JOIN orders o ON o.id = s.order_id " +
            "WHERE s.tenant_id = ? " +
            "  AND s.provider_id_fetch_failed = true " +
            "  AND s.provider_delivery_id IS NULL " +
            "  AND s.internal_state NOT IN ( " +
            "      'delivered'::shipment_internal_state, " +
            "      'returned'::shipment_internal_state, " +
            "      'lost'::shipment_internal_state, " +
            "      'terminated'::shipment_internal_state, " +
            "      'cancelled'::shipment_internal_state) " +
            "  AND NOT EXISTS ( " +
            "      SELECT 1 FROM exception_resolutions er " +
            "      WHERE er.tenant_id = s.tenant_id " +
            "        AND er.exception_type = 'missing_provider_id' " +
            "        AND er.subject_key = 'missing_provider_id:shipment:' || s.id) ",
            tid);
    }

    // ── Enrichment ────────────────────────────────────────────────────────────

    private void enrich(Map<String, Object> item) {
        String type = (String) item.get("type");
        switch (type) {
            case "lost" -> {
                String b = str(item, "barcode");
                item.put("descriptionEn", "Piece " + b + " is marked as lost by the courier");
                item.put("descriptionAr", "القطعة " + b + " مُسجَّلة كمفقودة لدى شركة الشحن");
                item.put("suggestedAction", "confirm_write_off");
                item.put("actionUrl", ordersUrl(item));
            }
            case "never_received" -> {
                String b = str(item, "barcode");
                item.put("descriptionEn", "Piece " + b + " from a returned shipment was never scanned back in");
                item.put("descriptionAr", "القطعة " + b + " من شحنة مرتجعة لم يتم استلامها في المستودع");
                item.put("suggestedAction", "intake_or_write_off");
                item.put("actionUrl", "/returns");
            }
            case "unmatched_delivery" -> {
                String t = str(item, "tracking_number");
                item.put("descriptionEn", "Bosta delivery " + t + " could not be matched to an order");
                item.put("descriptionAr", "شحنة بوسطة " + t + " لم يتم ربطها بطلب");
                item.put("suggestedAction", "manual_link");
                item.put("actionUrl", "/shipments/unlinked");
            }
            case "blocked_customer" -> {
                String n = str(item, "order_number");
                String r = str(item, "hold_reason");
                String suffix = (r != null && !r.isBlank()) ? ": " + r : "";
                item.put("descriptionEn", "Order " + n + " is on hold" + suffix);
                item.put("descriptionAr", "الطلب " + n + " معلَّق" + suffix);
                item.put("suggestedAction", "review_and_release");
                item.put("actionUrl", ordersUrl(item));
                // FR-7.8a: resolution actions — release hold or cancel
                item.put("releaseUrl", "/api/v1/orders/" + str(item, "order_id") + "/release-hold");
                item.put("cancelUrl",  "/api/v1/orders/" + str(item, "order_id") + "/cancel");
            }
            case "stuck_shipment" -> {
                String t = str(item, "tracking_number");
                String st = str(item, "shipment_state");
                item.put("descriptionEn", "Shipment " + t + " stuck in '" + st + "' — no courier update");
                item.put("descriptionAr", "الشحنة " + t + " متوقفة في حالة '" + st + "' بدون تحديث");
                item.put("suggestedAction", "contact_courier");
                item.put("actionUrl", ordersUrl(item));
            }
            case "unexpected_return" -> {
                String b = str(item, "barcode");
                item.put("descriptionEn", "Piece " + b + " was physically returned with no prior RTO state from courier");
                item.put("descriptionAr", "القطعة " + b + " أُرجعت فعلياً دون حالة إرجاع من شركة الشحن");
                item.put("suggestedAction", "inspect_and_resolve");
                item.put("actionUrl", "/returns");
            }
            case "delivery_limbo" -> {
                String t = str(item, "tracking_number");
                Object att = item.get("number_of_attempts");
                item.put("descriptionEn", "Return delivery " + t + " failed " + att + "× — awaiting action at Bosta hub");
                item.put("descriptionAr", "فشل إرجاع الشحنة " + t + " " + att + " مرات — في انتظار الإجراء");
                item.put("suggestedAction", "retry_or_cancel_return");
                item.put("actionUrl", ordersUrl(item));
            }
            case "ndr_failed" -> {
                String t = str(item, "tracking_number");
                String d = str(item, "ndr_description");
                String desc = (d != null && !d.isBlank()) ? ": " + d : "";
                item.put("descriptionEn", "Failed delivery attempt for " + t + desc);
                item.put("descriptionAr", "فشل محاولة توصيل " + t + desc);
                item.put("suggestedAction", "contact_customer");
                item.put("actionUrl", ordersUrl(item));
            }
            case "guided_unpack" -> {
                String n = str(item, "order_number");
                item.put("descriptionEn", "Order " + n + " cancelled — pieces are packed and must be physically unpacked");
                item.put("descriptionAr", "الطلب " + n + " ملغى — القطع معبَّأة وتحتاج إلى فك التعبئة يدوياً");
                item.put("suggestedAction", "unpack_pieces");
                Object oid = item.get("order_id");
                item.put("actionUrl", oid != null ? "/fulfill/" + oid : "/fulfill");
            }
            case "missing_awb" -> {
                String t = str(item, "tracking_number");
                String r = str(item, "failed_reason");
                item.put("descriptionEn", "AWB could not be printed for shipment " + t + ": " + r);
                item.put("descriptionAr", "تعذّر طباعة بوليصة الشحن للشحنة " + t + ": " + r);
                item.put("suggestedAction", "retry_awb_print");
                item.put("actionUrl", "/shipments/" + item.get("shipment_id"));
            }
            case "missing_provider_id" -> {
                String t = str(item, "tracking_number");
                String n = str(item, "order_number");
                item.put("descriptionEn",
                    "Bosta internal ID could not be fetched for shipment " + t + " (order " + n + ") — delivery cancellation unavailable");
                item.put("descriptionAr",
                    "تعذّر جلب المعرّف الداخلي من بوسطة للشحنة " + t + " (الطلب " + n + ") — إلغاء التوصيل غير متاح");
                item.put("suggestedAction", "retry_provider_id_fetch");
                item.put("actionUrl", "/shipments/" + item.get("shipment_id"));
            }
            case "high_attempts" -> {
                String t = str(item, "tracking_number");
                Object att = item.get("number_of_attempts");
                item.put("descriptionEn",
                    "Shipment " + t + " has had " + att + " delivery attempt(s) — customer may be unreachable");
                item.put("descriptionAr",
                    "تمّت " + att + " محاولات توصيل للشحنة " + t + " — قد يتعذّر الوصول للعميل");
                item.put("suggestedAction", "contact_customer");
                item.put("actionUrl", ordersUrl(item));
            }
            case "shopify_cancel_vs_inflight" -> {
                String n = str(item, "order_number");
                String t = str(item, "tracking_number");
                String shipSuffix = (t != null) ? " (AWB: " + t + ")" : "";
                item.put("descriptionEn",
                    "Shopify cancelled order " + n + shipSuffix +
                    " but the parcel is still in-flight with the courier");
                item.put("descriptionAr",
                    "أُلغي الطلب " + n + shipSuffix +
                    " في شوبيفاي ولكن الشحنة لا تزال مع مندوب التوصيل");
                item.put("suggestedAction",
                    "Shopify cancelled but parcel is in-flight — convert to self-pickup, " +
                    "cancel via guided flow, or let it RTO.");
                item.put("actionUrl", ordersUrl(item));
            }
            case "cancelled_live_shipment" -> {
                String t = str(item, "tracking_number");
                item.put("descriptionEn",
                    "This order is cancelled but its Bosta shipment (" + t + ") is still " +
                    "active. Cancel the AWB in Bosta so the courier doesn't collect it.");
                item.put("descriptionAr",
                    "هذا الطلب ملغي ولكن شحنة بوسطة الخاصة به (" + t + ") ما زالت نشطة. " +
                    "ألغِ بوليصة الشحن في بوسطة حتى لا يقوم المندوب باستلامها.");
                item.put("suggestedAction", "cancel_awb_at_bosta");
                item.put("actionUrl", ordersUrl(item));
                // item-28 / §6.1: wire terminateDelivery() one-click resolve here when the
                // Bosta cancel endpoint ships.
            }
            case "cancelled_but_delivered" -> {
                String t = str(item, "tracking_number");
                String shipSuffix = (t != null && !t.isBlank()) ? " (" + t + ")" : "";
                item.put("descriptionEn",
                    "This order was cancelled but the courier already delivered it" +
                    shipSuffix + ". Reconcile COD / arrange a return.");
                item.put("descriptionAr",
                    "تم إلغاء هذا الطلب ولكن المندوب قام بالفعل بتوصيله" + shipSuffix +
                    ". يجب تسوية الدفع عند الاستلام أو ترتيب عملية استرجاع.");
                item.put("suggestedAction", "reconcile_cod_or_arrange_return");
                item.put("actionUrl", ordersUrl(item));
            }
            case "return_in_transit_stuck" -> {
                String b = str(item, "barcode");
                String t = str(item, "tracking_number");
                String shipSuffix = (t != null && !t.isBlank()) ? " (AWB: " + t + ")" : "";
                item.put("descriptionEn",
                    "Piece " + b + shipSuffix +
                    " arrived back per Bosta but was never scanned into the warehouse");
                item.put("descriptionAr",
                    "القطعة " + b + shipSuffix +
                    " أُرجعت وفق بوسطة ولكنها لم تُمسح عند الاستلام في المستودع");
                item.put("suggestedAction", "intake_piece");
                item.put("actionUrl", "/returns");
            }
            case "shopify_edit_conflict" -> {
                String n      = str(item, "order_number");
                String status = str(item, "order_status");
                String diff   = str(item, "diff_json");
                item.put("descriptionEn",
                    "Shopify edited order " + n + " (" + status + ") after it entered the fulfillment flow — " +
                    "line items changed");
                item.put("descriptionAr",
                    "تعديل Shopify على الطلب " + n + " (" + status + ") بعد دخوله مسار التنفيذ — " +
                    "تغيّرت بنود الطلب");
                item.put("suggestedAction", "review_order");
                item.put("actionUrl", ordersUrl(item));
                if (diff != null) item.put("diffJson", diff);
            }
            case "return_mismatch" -> {
                String b = str(item, "barcode");
                item.put("descriptionEn", "Piece " + b + " scanned into a return session didn't match its expected disposition");
                item.put("descriptionAr", "القطعة " + b + " الممسوحة في جلسة إرجاع لم تطابق حالتها المتوقعة");
                item.put("suggestedAction", "inspect_and_resolve");
                item.put("actionUrl", "/returns");
            }
            case "exchange_needs_mapping" -> {
                String t = str(item, "tracking_number");
                item.put("descriptionEn", "Exchange " + t + " needs its outbound/inbound items mapped to catalog variants");
                item.put("descriptionAr", "الاستبدال " + t + " يحتاج إلى ربط الأصناف الصادرة والواردة بمتغيرات الكتالوج");
                item.put("suggestedAction", "map_exchange");
                item.put("actionUrl", "/exchanges/" + item.get("exchange_id"));
            }
            case "exchange_unmapped_state" -> {
                String t = str(item, "tracking_number");
                String v = str(item, "unmapped_state_value");
                item.put("descriptionEn", "Exchange " + t + " reported an unrecognized courier state ('" + v + "') — no update was applied");
                item.put("descriptionAr", "الاستبدال " + t + " أبلغ عن حالة غير معروفة من شركة الشحن ('" + v + "') — لم يتم تطبيق أي تحديث");
                item.put("suggestedAction", "review_exchange_state");
                Object oid = item.get("order_id");
                item.put("actionUrl", oid != null ? "/fulfill/" + oid : "/exceptions");
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Instant toInstant(Object val, Instant fallback) {
        if (val instanceof Timestamp ts) return ts.toInstant();
        if (val instanceof Instant i)    return i;
        return fallback;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static String ordersUrl(Map<String, Object> item) {
        Object oid = item.get("order_id");
        return oid != null ? "/orders/" + oid : "/orders";
    }
}
