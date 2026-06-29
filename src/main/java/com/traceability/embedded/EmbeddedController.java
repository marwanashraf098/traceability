package com.traceability.embedded;

import com.traceability.inventory.ExceptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

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

    private static final List<String> GROUP_A = List.of(
            "available", "reserved", "packed",
            "awaiting_pickup", "with_courier", "return_pending_inspection");
    private static final List<String> GROUP_B = List.of("delivered", "damaged", "lost");

    public EmbeddedController(JdbcTemplate jdbc,
                               PlatformTransactionManager txm,
                               ExceptionService exceptionService) {
        this.jdbc             = jdbc;
        this.tx               = new TransactionTemplate(txm);
        this.exceptionService = exceptionService;
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
}
