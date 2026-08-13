package com.traceability.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * FR-15.1 — Piece-level inventory read model.
 *
 * Two endpoints:
 *   GET /api/v1/inventory/summary  — tenant-scoped aggregate counts
 *   GET /api/v1/pieces             — paginated filtered piece list (drill-down)
 *
 * Group A counts are point-in-time (current pieces.status). Includes out_on_transfer
 * (FR-22) — "Out on transfer / At vendor": consignment stock outside the warehouse, not
 * sellable, not pickable (excluded from PICKABLE_ORDERS_FILTER-adjacent piece filters and
 * the 4-status Shopify on-hand formula purely because out_on_transfer is not one of the
 * statuses those filters key on — no extra predicate needed anywhere, proven by test).
 *
 * Group B counts (delivered / damaged / lost / sold) are windowed: current status = X
 * AND the piece_events transition INTO that status occurred within the last 30 days.
 * A piece delivered and later returned has current status != 'delivered', so it
 * is naturally excluded by the p.status predicate — no double-counting possible. sold
 * (FR-22) is included here — it's a new terminal, and showroom sell-through is the primary
 * reporting value of the whole transfers feature; without it, sold pieces would be
 * invisible in every summary view.
 */
@RestController
@RequestMapping("/api/v1")
public class InventoryController {

    private final JdbcTemplate      jdbc;
    private final TransactionTemplate tx;

    public InventoryController(JdbcTemplate jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txm);
    }

    // ── Response records ──────────────────────────────────────────────────────

    public record StatusCount(String status, long count) {}
    public record InventorySummary(List<StatusCount> groupA, List<StatusCount> groupB) {}
    public record StatusTotals(Map<String, Long> statusCounts) {}
    public record Valuation(long lowStockCount, java.math.BigDecimal inventoryValue,
                            long variantsCosted, long variantsTotal) {}
    public record ThroughputDay(String date, int received, int shipped) {}
    public record ActivityItem(String type, String refId, String refCode, String actorName,
                               String locationName, String occurredAt, Integer pieceCount) {}
    public record PieceRow(String id, String barcode, String status,
                           String variantTitle, String sku, String productTitle,
                           String locationName, String lastEventAt) {}
    public record PiecePage(List<PieceRow> items, long total, int page, int size) {}

    // ── Ordered status lists ──────────────────────────────────────────────────

    private static final List<String> GROUP_A_STATUSES = List.of(
        "available", "reserved", "packed",
        "awaiting_pickup", "with_courier", "return_pending_inspection", "out_on_transfer"
    );
    private static final List<String> GROUP_B_STATUSES = List.of(
        "delivered", "damaged", "lost", "sold"
    );

    // Every piece_status value — mirrors CatalogController.ALL_STATUSES. Kept as its
    // own local constant rather than a shared import: each controller owns its own
    // exhaustive list deliberately, same reasoning as GROUP_A/GROUP_B_STATUSES above.
    private static final List<String> ALL_PIECE_STATUSES = List.of(
        "available", "reserved", "packed", "awaiting_pickup",
        "with_courier", "delivered", "return_in_transit",
        "return_pending_inspection", "damaged", "lost", "destroyed",
        "out_on_transfer", "sold"
    );

    // ── Summary ───────────────────────────────────────────────────────────────

    @GetMapping("/inventory/summary")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public InventorySummary summary() {
        return tx.execute(txs -> {
            // Group A: point-in-time — count where current status = X
            Map<String, Long> rawA = new LinkedHashMap<>();
            for (String s : GROUP_A_STATUSES) rawA.put(s, 0L);
            jdbc.query("""
                SELECT status::text AS s, COUNT(*) AS c
                FROM pieces
                WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND status IN ('available','reserved','packed',
                                 'awaiting_pickup','with_courier','return_pending_inspection',
                                 'out_on_transfer')
                GROUP BY status
                """,
                rs -> { rawA.put(rs.getString("s"), rs.getLong("c")); });

            // Group B: windowed — current status = X AND entered X within last 30 days.
            // Status is correlated (pe.to_status = p.status) so one query covers all three.
            Map<String, Long> rawB = new LinkedHashMap<>();
            for (String s : GROUP_B_STATUSES) rawB.put(s, 0L);
            jdbc.query("""
                SELECT p.status::text AS s, COUNT(*) AS c
                FROM pieces p
                WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND p.status IN ('delivered','damaged','lost','sold')
                  AND EXISTS (
                      SELECT 1 FROM piece_events pe
                      WHERE pe.tenant_id = p.tenant_id
                        AND pe.piece_id  = p.id
                        AND pe.to_status = p.status
                        AND pe.occurred_at >= now() - INTERVAL '30 days'
                  )
                GROUP BY p.status
                """,
                rs -> { rawB.put(rs.getString("s"), rs.getLong("c")); });

            List<StatusCount> groupA = GROUP_A_STATUSES.stream()
                .map(s -> new StatusCount(s, rawA.getOrDefault(s, 0L)))
                .toList();
            List<StatusCount> groupB = GROUP_B_STATUSES.stream()
                .map(s -> new StatusCount(s, rawB.getOrDefault(s, 0L)))
                .toList();

            return new InventorySummary(groupA, groupB);
        });
    }

    // ── Live per-status totals (Overview dashboard) ─────────────────────────────
    //
    // FR-Overview §1.1 — a dedicated LIVE (current, not time-windowed) per-status
    // count, distinct from Group A (live but only 7 statuses) and Group B (windowed —
    // "entered this status in the last 30 days and still in it", which understates
    // true live damaged/lost totals). This is the single source for the Overview
    // dashboard's Available/Reserved/Damaged/Lost tiles, client-summed Total, and the
    // donut — a pure per-status GROUP BY, nothing else folded in (low-stock and
    // inventory value live on their own /inventory/valuation endpoint — see
    // ValuationController — deliberately kept off this hot per-status count).

    @GetMapping("/inventory/status-totals")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public StatusTotals statusTotals() {
        return tx.execute(txs -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String s : ALL_PIECE_STATUSES) counts.put(s, 0L);
            jdbc.query("""
                SELECT status::text AS s, COUNT(*) AS c
                FROM pieces
                WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                GROUP BY status
                """,
                rs -> { counts.put(rs.getString("s"), rs.getLong("c")); });
            return new StatusTotals(counts);
        });
    }

    // ── Low-stock + inventory value (Overview dashboard) ────────────────────────
    //
    // FR-Overview §D1/§D2 — deliberately its own endpoint, NOT folded into
    // statusTotals() above: both numbers here need the variants JOIN (unit_cost,
    // low_stock_threshold) that the hot per-status pieces count has no reason to pay
    // for on every call. Inventory value = SUM(available * unit_cost) over COSTED
    // variants only — variantsCosted/variantsTotal let the frontend show a "based on
    // N of M variants costed" caveat instead of a misleading "EGP 0" when nothing is
    // costed yet (the day-one state for both pilots).

    @GetMapping("/inventory/valuation")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public Valuation valuation() {
        return tx.execute(txs -> {
            Long threshold = jdbc.queryForObject(
                "SELECT low_stock_threshold FROM tenants " +
                "WHERE id = NULLIF(current_setting('app.current_tenant', true), '')::uuid",
                Long.class);

            Map<String, Object> row = jdbc.queryForMap("""
                WITH avail AS (
                    SELECT variant_id, COUNT(*) AS available_count
                    FROM pieces
                    WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                      AND status = 'available'::piece_status
                    GROUP BY variant_id
                )
                SELECT
                    COUNT(*) FILTER (WHERE COALESCE(a.available_count, 0) < ?) AS low_stock_count,
                    COUNT(*) FILTER (WHERE v.unit_cost IS NOT NULL) AS variants_costed,
                    COUNT(*) AS variants_total,
                    COALESCE(SUM(COALESCE(a.available_count, 0) * v.unit_cost)
                             FILTER (WHERE v.unit_cost IS NOT NULL), 0) AS inventory_value
                FROM variants v
                LEFT JOIN avail a ON a.variant_id = v.id
                WHERE v.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                """, threshold);

            return new Valuation(
                ((Number) row.get("low_stock_count")).longValue(),
                (java.math.BigDecimal) row.get("inventory_value"),
                ((Number) row.get("variants_costed")).longValue(),
                ((Number) row.get("variants_total")).longValue());
        });
    }

    // ── Throughput — 30d received vs shipped (Overview dashboard) ───────────────
    //
    // FR-Overview §1.4. Source is piece_events, NOT /orders/daily-counts (that's
    // orders placed, a different concept). "Received" = event_type='received' — the
    // literal string InventoryLedger.batchReceive() writes (confirmed by reading its
    // INSERT, not guessed). "Shipped" (handed to courier) = to_status='with_courier',
    // deliberately NOT filtered by event_type: two different writers set that
    // transition — BostaWebhookJob's courier-driven path (event_type='courier_update')
    // and PickupSessionService's pickup-close bypass (event_type='handed_to_courier',
    // the FR-16 packed→with_courier bypass) — the piece_status transition itself, not
    // either writer's event_type label, is what "shipped" means here.
    // Fixed 30-day window per D4 — the "Today ▾" control does not thread a param here.

    @GetMapping("/inventory/throughput")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    @Transactional(readOnly = true)
    public List<ThroughputDay> throughput() {
        String from = java.time.LocalDate.now().minusDays(29).toString();
        return jdbc.query("""
            WITH gs AS (
                SELECT generate_series(?::date, CURRENT_DATE, '1 day'::interval)::date AS day
            ),
            recv AS (
                SELECT DATE(occurred_at) AS day, COUNT(*)::int AS cnt
                FROM piece_events
                WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND event_type = 'received'
                  AND occurred_at >= ?::date
                GROUP BY DATE(occurred_at)
            ),
            ship AS (
                SELECT DATE(occurred_at) AS day, COUNT(*)::int AS cnt
                FROM piece_events
                WHERE tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
                  AND to_status = 'with_courier'::piece_status
                  AND occurred_at >= ?::date
                GROUP BY DATE(occurred_at)
            )
            SELECT gs.day::text AS date, COALESCE(recv.cnt, 0) AS received, COALESCE(ship.cnt, 0) AS shipped
            FROM gs
            LEFT JOIN recv ON recv.day = gs.day
            LEFT JOIN ship ON ship.day = gs.day
            ORDER BY gs.day
            """,
            (rs, row) -> new ThroughputDay(rs.getString("date"), rs.getInt("received"), rs.getInt("shipped")),
            from, from, from);
    }

    // ── Recent activity feed (Overview dashboard) ────────────────────────────────
    //
    // FR-Overview §1.5. Receiving + stock-take ONLY — NOT audit_log (that's
    // AuditService's user/settings action log, a different concept) and NOT pickups
    // (pickups has no actor column at all — see PROGRESS.md follow-up — an actor-less
    // line would break the "by {name}" pattern every other line here follows, so
    // pickups are excluded entirely rather than shown inconsistently).
    //
    // One canonical event per session, at FINALIZATION — not two lines (opened +
    // completed) per session. A still-open receiving/stock-take session is not yet a
    // completed unit of work worth broadcasting as "activity"; this simplifies the
    // UNION to one clean row per finished session, actor = whoever finalized it.
    //
    // refCode/refId are returned raw (not formatted) — the frontend renders the
    // "Session {ref} opened/completed by {actor}" line through an i18n template, never
    // stored English.
    //
    // receipts.kind discriminates 'inbound' (goods receiving) vs 'returns' (the FR-12
    // Waybill Session, a different feature that also lives in the receipts table) —
    // this query MUST filter kind='inbound', otherwise a finalized returns session
    // would be misclassified as a "pieces received" line.

    @GetMapping("/activity/recent")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    @Transactional(readOnly = true)
    public List<ActivityItem> recentActivity(@RequestParam(defaultValue = "10") int limit) {
        limit = Math.min(limit, 50);
        return jdbc.query("""
            SELECT 'receiving' AS type, r.id::text AS ref_id, r.reference AS ref_code,
                   u.name AS actor_name, loc.name AS location_name,
                   r.finalized_at AS occurred_at,
                   COALESCE((SELECT SUM(rl.quantity)::int FROM receipt_lines rl
                             WHERE rl.receipt_id = r.id), 0) AS piece_count
            FROM receipts r
            LEFT JOIN users u     ON u.id   = r.received_by
            LEFT JOIN locations loc ON loc.id = r.location_id
            WHERE r.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
              AND r.kind = 'inbound'
              AND r.finalized_at IS NOT NULL

            UNION ALL

            SELECT 'stock_take' AS type, st.id::text AS ref_id, NULL AS ref_code,
                   u2.name AS actor_name, loc2.name AS location_name,
                   st.finalized_at AS occurred_at,
                   NULL AS piece_count
            FROM stock_take_sessions st
            LEFT JOIN users u2       ON u2.id  = st.finalized_by
            LEFT JOIN locations loc2 ON loc2.id = st.location_id
            WHERE st.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
              AND st.finalized_at IS NOT NULL

            ORDER BY occurred_at DESC
            LIMIT ?
            """,
            (rs, i) -> new ActivityItem(
                rs.getString("type"),
                rs.getString("ref_id"),
                rs.getString("ref_code"),
                rs.getString("actor_name"),
                rs.getString("location_name"),
                rs.getTimestamp("occurred_at").toInstant().toString(),
                rs.getObject("piece_count", Integer.class)),
            limit);
    }

    // ── Piece list (drill-down) ────────────────────────────────────────────────

    @GetMapping("/pieces")
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public PiecePage pieces(
            @RequestParam String status,
            @RequestParam(defaultValue = "false") boolean within30d,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        try { PieceStatus.fromDb(status); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status: " + status);
        }

        size = Math.min(size, 100);
        final int offset = page * size;
        final int finalSize = size;

        // Optional window clause appended to both the COUNT and the SELECT.
        String windowClause = within30d ? """
            AND EXISTS (
                SELECT 1 FROM piece_events pe
                WHERE pe.tenant_id  = p.tenant_id
                  AND pe.piece_id   = p.id
                  AND pe.to_status  = p.status
                  AND pe.occurred_at >= now() - INTERVAL '30 days'
            )
            """ : "";

        String baseFrom = """
            FROM pieces p
            JOIN variants  v  ON v.id  = p.variant_id
            JOIN products  pr ON pr.id = v.product_id
            LEFT JOIN locations l ON l.id = p.current_location_id
            WHERE p.tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
              AND p.status = ?::piece_status
            """ + windowClause;

        final String finalBaseFrom = baseFrom;
        final String finalStatus   = status;

        return tx.execute(txs -> {
            long total = Objects.requireNonNull(
                jdbc.queryForObject("SELECT COUNT(*) " + finalBaseFrom, Long.class, finalStatus));

            List<PieceRow> items = jdbc.query(
                """
                SELECT p.id, p.barcode, p.status::text,
                       v.title  AS variant_title,
                       v.sku,
                       pr.title AS product_title,
                       l.name   AS location_name,
                       p.last_event_at::text AS last_event_at
                """ + finalBaseFrom + """
                ORDER BY p.last_event_at DESC NULLS LAST
                LIMIT ? OFFSET ?
                """,
                (rs, i) -> new PieceRow(
                    rs.getString("id"),
                    rs.getString("barcode"),
                    rs.getString("status"),
                    rs.getString("variant_title"),
                    rs.getString("sku"),
                    rs.getString("product_title"),
                    rs.getString("location_name"),
                    rs.getString("last_event_at")
                ),
                finalStatus, finalSize, offset
            );

            return new PiecePage(items, total, page, finalSize);
        });
    }
}
