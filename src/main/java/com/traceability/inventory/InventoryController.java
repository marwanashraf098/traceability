package com.traceability.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
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
 * Group A counts are point-in-time (current pieces.status).
 * Group B counts (delivered / damaged / lost) are windowed: current status = X
 * AND the piece_events transition INTO that status occurred within the last 30 days.
 * A piece delivered and later returned has current status != 'delivered', so it
 * is naturally excluded by the p.status predicate — no double-counting possible.
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
    public record PieceRow(String id, String barcode, String status,
                           String variantTitle, String sku, String productTitle,
                           String locationName, String lastEventAt) {}
    public record PiecePage(List<PieceRow> items, long total, int page, int size) {}

    // ── Ordered status lists ──────────────────────────────────────────────────

    private static final List<String> GROUP_A_STATUSES = List.of(
        "available", "reserved", "packed",
        "awaiting_pickup", "with_courier", "return_pending_inspection"
    );
    private static final List<String> GROUP_B_STATUSES = List.of(
        "delivered", "damaged", "lost"
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
                                 'awaiting_pickup','with_courier','return_pending_inspection')
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
                  AND p.status IN ('delivered','damaged','lost')
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
