package com.traceability.embedded;

import com.traceability.fulfillment.OrderStatusDeriver;
import com.traceability.inventory.ExceptionService;
import com.traceability.overview.OverviewService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Read-only embedded Shopify dashboard endpoints.
 *
 * All endpoints require ROLE_SHOPIFY_EMBEDDED — issued only by ShopifySessionTokenFilter
 * after verifying a valid App Bridge session token. No Traced-platform role can pass
 * this gate; SHOPIFY_EMBEDDED cannot reach any other controller (they require OWNER/MANAGER).
 *
 * Structural read-only guarantee: no @PostMapping/@PutMapping/@DeleteMapping/@PatchMapping
 * anywhere in this package — enforced by EmbeddedReadOnlyGuardTest in CI.
 *
 * All queries use NULLIF(current_setting('app.current_tenant', true), '')::uuid and are
 * wrapped in TransactionTemplate so TenantAwareConnection fires the GUC before the query.
 */
@RestController
@RequestMapping("/api/v1/embedded")
public class EmbeddedController {

    private final JdbcTemplate       jdbc;
    private final TransactionTemplate tx;
    private final ExceptionService   exceptionService;
    private final OverviewService    overviewService;

    private static final List<String> GROUP_A = List.of(
            "available", "reserved", "packed",
            "awaiting_pickup", "with_courier", "return_pending_inspection");
    private static final List<String> GROUP_B = List.of("delivered", "damaged", "lost");

    public EmbeddedController(JdbcTemplate jdbc,
                               PlatformTransactionManager txm,
                               ExceptionService exceptionService,
                               OverviewService overviewService) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.exceptionService = exceptionService;
        this.overviewService  = overviewService;
    }

    public record StatusCount(String status, long count) {}
    public record InventorySummary(List<StatusCount> groupA, List<StatusCount> groupB) {}
    public record DayCount(String date, int count) {}

    // ── GET /api/v1/embedded/inventory/summary ────────────────────────────────

    @GetMapping("/inventory/summary")
    @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")
    public InventorySummary inventorySummary() {
        return tx.execute(txs -> {
            Map<String, Long> a = new LinkedHashMap<>();
            for (String s : GROUP_A) a.put(s, 0L);
            jdbc.query("""
                    SELECT status::text AS s, COUNT(*) AS c
                    FROM pieces
                    WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                      AND status IN ('available','reserved','packed',
                                     'awaiting_pickup','with_courier','return_pending_inspection')
                    GROUP BY status
                    """, (RowCallbackHandler) rs -> a.put(rs.getString("s"), rs.getLong("c")));

            Map<String, Long> b = new LinkedHashMap<>();
            for (String s : GROUP_B) b.put(s, 0L);
            jdbc.query("""
                    SELECT p.status::text AS s, COUNT(*) AS c
                    FROM pieces p
                    WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                      AND p.status IN ('delivered','damaged','lost')
                      AND EXISTS (
                          SELECT 1 FROM piece_events pe
                          WHERE pe.tenant_id = p.tenant_id
                            AND pe.piece_id  = p.id
                            AND pe.to_status = p.status
                            AND pe.occurred_at >= now() - INTERVAL '30 days'
                      )
                    GROUP BY p.status
                    """, (RowCallbackHandler) rs -> b.put(rs.getString("s"), rs.getLong("c")));

            return new InventorySummary(
                    GROUP_A.stream().map(s -> new StatusCount(s, a.getOrDefault(s, 0L))).toList(),
                    GROUP_B.stream().map(s -> new StatusCount(s, b.getOrDefault(s, 0L))).toList());
        });
    }

    // ── GET /api/v1/embedded/orders/daily-counts ──────────────────────────────

    @GetMapping("/orders/daily-counts")
    @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")
    public List<DayCount> dailyCounts(@RequestParam(defaultValue = "30") int days) {
        days = Math.max(7, Math.min(days, 90));
        final String from = LocalDate.now().minusDays(days - 1).toString();
        return tx.execute(txs -> jdbc.query("""
                WITH gs AS (
                    SELECT generate_series(?::date, CURRENT_DATE, '1 day'::interval)::date AS day
                ),
                oc AS (
                    SELECT DATE(placed_at) AS day, COUNT(*)::int AS cnt
                    FROM orders
                    WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                      AND placed_at >= ?::date
                    GROUP BY DATE(placed_at)
                )
                SELECT gs.day::text AS date, COALESCE(oc.cnt, 0) AS count
                FROM gs LEFT JOIN oc ON oc.day = gs.day
                ORDER BY gs.day
                """,
                (rs, row) -> new DayCount(rs.getString("date"), rs.getInt("count")),
                from, from));
    }

    // ── GET /api/v1/embedded/stores/status ───────────────────────────────────

    @GetMapping("/stores/status")
    @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")
    public List<Map<String, Object>> storesStatus() {
        return tx.execute(txs -> jdbc.queryForList(
                "SELECT shop_domain, status, import_status, last_sync_at FROM stores " +
                "WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid"));
    }

    // ── GET /api/v1/embedded/exceptions ──────────────────────────────────────

    /**
     * Trimmed read-only exception list for the embedded dashboard.
     * Returns the top N open exceptions (default 10, max 50) sorted CRITICAL→LOW,
     * with only type + severity + subjectKey — no resolve/ack capability.
     */
    @GetMapping("/exceptions")
    @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")
    public Map<String, Object> exceptions(@RequestParam(defaultValue = "10") int limit) {
        limit = Math.min(limit, 50);
        // tx.execute() is mandatory — TenantAwareConnection sets the GUC (app.current_tenant)
        // at transaction start; ExceptionService.listExceptions() line 54 queries tenants via
        // app_user (RLS-enforced), so without the GUC the RLS policy returns 0 rows and
        // queryForMap throws EmptyResultDataAccessException.
        final int effectiveLimit = limit;
        Map<String, Object> full = tx.execute(txs ->
                exceptionService.listExceptions(null, null, 0, effectiveLimit));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) full.getOrDefault("items", List.of());

        List<Map<String, Object>> trimmed = items.stream()
                .map(ex -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("type",       ex.get("exceptionType"));
                    t.put("severity",   ex.get("severity"));
                    t.put("subjectKey", ex.get("subjectKey"));
                    return t;
                })
                .toList();

        Object total = full.getOrDefault("total", 0);
        return Map.of("count", total, "exceptions", trimmed);
    }

    // ── GET /api/v1/embedded/orders/funnel ────────────────────────────────────

    public record FunnelCounts(int newCount, int picking, int packed, int courier, int delivered) {}

    /**
     * Read-only mirror of {@code OrderController.funnel()} (today's orders bucketed via
     * {@link OrderStatusDeriver}). SQL and bucket logic are DUPLICATED here rather than
     * calling into {@code OrderController} — that controller is
     * {@code @PreAuthorize("hasAnyRole('OWNER','MANAGER')")}-gated and SHOPIFY_EMBEDDED
     * cannot reach it. KNOWN DRIFT RISK: if {@code OrderController.funnel()}'s LATERAL
     * join or bucket mapping changes, this copy must be updated by hand — no shared
     * helper exists between the two controllers (deliberately out of scope for this
     * build pass; see the build-spec comment on {@link #ordersList()} below).
     */
    @GetMapping("/orders/funnel")
    @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")
    public FunnelCounts ordersFunnel() {
        record StatusRow(String orderStatus, OrderStatusDeriver.DerivedOrderStatus derived) {}

        List<StatusRow> rows = tx.execute(txs -> jdbc.query("""
                SELECT o.status, o.not_traced_at,
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
                WHERE o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND o.placed_at::date = CURRENT_DATE
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
                    return new StatusRow(orderStatus, derived);
                }));

        int newCount = 0, picking = 0, packed = 0, courier = 0, delivered = 0;
        for (StatusRow row : rows) {
            String primaryKey = row.derived().primaryKey();
            boolean isCourierAwbState =
                    "status.awaiting_courier".equals(primaryKey) || "status.label_created".equals(primaryKey);
            if (isCourierAwbState && !row.derived().packedConfirmed()) {
                if ("picking".equals(row.orderStatus())) picking++; else newCount++;
                continue;
            }
            switch (primaryKey) {
                case "status.new", "status.confirmed", "status.ready_to_pick" -> newCount++;
                case "status.picking" -> picking++;
                case "status.packed" -> packed++;
                case "status.awaiting_courier", "status.in_transit", "status.label_created" -> courier++;
                case "status.delivered" -> delivered++;
                default -> { /* outside the forward-pipeline funnel — not counted */ }
            }
        }
        return new FunnelCounts(newCount, picking, packed, courier, delivered);
    }

    // ── GET /api/v1/embedded/overview/late-to-pack ─────────────────────────────

    /**
     * Delegates to {@link OverviewService#lateToPack()} — the ONE implementation, no
     * second copy. This used to be a hand-duplicated query here (same predicate, same
     * bug: raw {@code orders.status} never reflects a shipment that progressed without
     * Traced's own pack scan ever firing — see OverviewService's fix). It's safe to call
     * the service directly: {@code OverviewService} carries no {@code @PreAuthorize} of
     * its own — that gate lives on {@code OverviewController}, a separate class — so this
     * endpoint's own {@code @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")} is still the
     * only authorization check that applies. {@code tx.execute()} is mandatory, same
     * reason as {@code exceptions()} above: {@code TenantAwareConnection} sets the RLS GUC
     * at transaction start, and {@code OverviewService.lateToPack()}'s own explicit
     * {@code tenant_id = ?} predicate (via {@code TenantContext}, populated for embedded
     * requests too by {@code TenantContextFilter} running after {@code
     * ShopifySessionTokenFilter}) is defense-in-depth on top of that, not a replacement
     * for it.
     */
    @GetMapping("/overview/late-to-pack")
    @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")
    public OverviewService.LateToPack lateToPack() {
        return tx.execute(txs -> overviewService.lateToPack());
    }

    // ── GET /api/v1/embedded/orders/list ────────────────────────────────────────

    public record EmbeddedOrderRow(
            String id, String number, boolean isExchange, boolean notTraced,
            String customerName, String customerPhone, BigDecimal codAmount,
            Instant placedAt,
            String primaryKey, OrderStatusDeriver.Tone tone,
            String fulfillmentKey, OrderStatusDeriver.Tone fulfillmentTone) {}

    /**
     * Capped recent-orders list for the embedded Orders tab — the 50 most recently
     * created orders, NO pagination/search/tracking/deliveryState filters (scope-locked
     * by the build spec). Status facets come from calling {@link OrderStatusDeriver#derive}
     * server-side — the SAME deriver {@code OrderController} uses — never re-derived
     * client-side. SQL is DUPLICATED from {@code OrderController.list()}'s LATERAL join
     * (trimmed: no q/tracking/deliveryState filters, no pagination) because that
     * controller is OWNER/MANAGER-gated and unreachable from SHOPIFY_EMBEDDED. KNOWN
     * DRIFT RISK: if {@code OrderController.list()}'s LATERAL join or
     * {@code max_progress_rank} CASE changes, this copy must be updated by hand — this is
     * the accepted tradeoff of not extracting a shared service in this pass.
     *
     * {@code ORDER BY o.created_at DESC, o.id DESC} — never {@code id} alone (UUIDv4 is
     * not time-ordered); {@code created_at} is the tiebreak-safe recency column.
     */
    @GetMapping("/orders/list")
    @PreAuthorize("hasRole('SHOPIFY_EMBEDDED')")
    public List<EmbeddedOrderRow> ordersList() {
        return tx.execute(txs -> jdbc.query("""
                SELECT o.id, o.number, o.customer_name, o.customer_phone, o.status,
                       o.cod_amount, o.placed_at, o.not_traced_at,
                       (e.id IS NOT NULL) AS is_exchange,
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
                LEFT JOIN exchanges e ON e.outbound_order_id = o.id
                WHERE o.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT 50
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
                    Timestamp placedAtTs = rs.getTimestamp("placed_at");
                    return new EmbeddedOrderRow(
                            rs.getObject("id", UUID.class).toString(),
                            rs.getString("number"),
                            rs.getBoolean("is_exchange"),
                            derived.notTraced(),
                            rs.getString("customer_name"),
                            rs.getString("customer_phone"),
                            rs.getBigDecimal("cod_amount"),
                            placedAtTs != null ? placedAtTs.toInstant() : null,
                            derived.primaryKey(), derived.tone(),
                            derived.fulfillmentKey(), derived.fulfillmentTone());
                }));
    }
}
