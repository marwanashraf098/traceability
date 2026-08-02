package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-21: Stock Taking — session open + full-population snapshot.
 *
 * Mirrors the receiving-session shape (ReceivingService / receipts). Truth stays in
 * piece_events via InventoryLedger.transition() — these tables track the count session
 * itself, never piece state directly.
 *
 * Design-gate Fork 1 (spec): single fulfillment location, MVP. Fork 2: the snapshot
 * freezes the FULL piece population in scope at open — every status, not just
 * available/damaged — because reconciliation (Step 4) needs to show committed/gone
 * pieces too, not just write-off candidates.
 */
@Service
public class StockTakeService {

    private static final Set<String> SCOPE_TYPES = Set.of("all", "variant_subset");

    private final JdbcTemplate jdbc;

    public StockTakeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Open + snapshot ──────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> openSession(String scopeType, List<UUID> variantIds,
                                            UUID requestedLocationId, String note,
                                            UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        if (!SCOPE_TYPES.contains(scopeType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "scope_type must be 'all' or 'variant_subset'");
        }
        boolean variantSubset = "variant_subset".equals(scopeType);
        if (variantSubset && (variantIds == null || variantIds.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "variantIds is required when scopeType is 'variant_subset'");
        }

        UUID locationId = resolveFulfillmentLocation(tenantId, requestedLocationId);

        UUID sessionId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO stock_take_sessions " +
            "(id, tenant_id, status, scope_type, location_id, opened_by, note) " +
            "VALUES (?, ?, 'open', ?, ?, ?, ?)",
            sessionId, tenantId, scopeType, locationId, actorUserId, note);

        if (variantSubset) {
            for (UUID variantId : variantIds) {
                jdbc.update(
                    "INSERT INTO stock_take_scope_variants (session_id, variant_id, tenant_id) " +
                    "VALUES (?, ?, ?)",
                    sessionId, variantId, tenantId);
            }
        }

        int snapshotted = snapshotExpectedPopulation(sessionId, tenantId, locationId,
                                                       variantSubset, variantIds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("locationId", locationId);
        out.put("scopeType", scopeType);
        out.put("piecesSnapshotted", snapshotted);
        return out;
    }

    /**
     * One batched INSERT ... SELECT — the full piece population at the resolved location,
     * every status included (Fork 2: committed/gone pieces are counted-if-scanned, not
     * excluded from the snapshot; only the write-off decision at Step 4 restricts by status).
     */
    private int snapshotExpectedPopulation(UUID sessionId, UUID tenantId, UUID locationId,
                                            boolean variantSubset, List<UUID> variantIds) {
        if (variantSubset) {
            UUID[] variantArray = variantIds.toArray(new UUID[0]);
            return jdbc.update(con -> {
                var ps = con.prepareStatement(
                    "INSERT INTO stock_take_expected " +
                    "(tenant_id, session_id, piece_id, variant_id, status_at_open) " +
                    "SELECT ?, ?, p.id, p.variant_id, p.status::text " +
                    "FROM pieces p " +
                    "WHERE p.tenant_id = ? AND p.current_location_id = ? AND p.variant_id = ANY(?)");
                ps.setObject(1, tenantId);
                ps.setObject(2, sessionId);
                ps.setObject(3, tenantId);
                ps.setObject(4, locationId);
                ps.setArray(5, con.createArrayOf("uuid", variantArray));
                return ps;
            });
        }
        return jdbc.update(
            "INSERT INTO stock_take_expected " +
            "(tenant_id, session_id, piece_id, variant_id, status_at_open) " +
            "SELECT ?, ?, p.id, p.variant_id, p.status::text " +
            "FROM pieces p " +
            "WHERE p.tenant_id = ? AND p.current_location_id = ?",
            tenantId, sessionId, tenantId, locationId);
    }

    /**
     * Stock takes are scoped to the tenant's single fulfillment location (Fork 1, MVP).
     * V61 enforces exactly one is_fulfillment=true location per tenant, so this always
     * resolves to a specific row (or 422 if none is configured yet). A caller-supplied
     * locationId must match it — this isn't a free-form location picker.
     */
    private UUID resolveFulfillmentLocation(UUID tenantId, UUID requestedLocationId) {
        UUID fulfillmentLocationId = jdbc.query(
            "SELECT id FROM locations WHERE tenant_id = ? AND is_fulfillment = true",
            rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            tenantId);
        if (fulfillmentLocationId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Tenant has no fulfillment location configured");
        }
        if (requestedLocationId != null && !requestedLocationId.equals(fulfillmentLocationId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Stock takes are scoped to the tenant's single fulfillment location");
        }
        return fulfillmentLocationId;
    }

    // ── Scan (blind, idempotent) ─────────────────────────────────────────────

    private static final Set<String> CONDITIONS = Set.of("good", "damaged");

    /**
     * Resolve barcode -> piece (tenant-scoped only — same query shape as LookupService:
     * barcode OR id OR short_code, AND tenant_id). Insert stock_take_scans; a re-scan of
     * the same (session, piece) is a no-op via the partial unique index. Classification
     * is always recomputed fresh from current live state, even on a re-scan.
     *
     * Barcode resolution is tenant-scoped by construction — under RLS (app_user, no
     * BYPASSRLS) a piece belonging to another tenant is invisible to this query exactly
     * like a barcode that was never issued at all, so "cross-tenant" and "genuinely
     * unknown" are indistinguishable to this code and MUST be handled identically:
     * logged as an unknown scan (raw_barcode, piece_id NULL), never an error, and never
     * any hint of what tenant B's piece actually is. That IS the non-leak guarantee —
     * there is no separate bypass check to leak through.
     */
    @Transactional
    public Map<String, Object> scan(UUID sessionId, String barcode, String condition, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        SessionRow session = requireOpenSession(sessionId, tenantId);

        if (barcode == null || barcode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "barcode is required");
        }
        if (!CONDITIONS.contains(condition)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "condition must be 'good' or 'damaged'");
        }

        record PieceRow(String id, String status) {}
        PieceRow piece = jdbc.query(
            "SELECT id, status::text AS status FROM pieces " +
            "WHERE (barcode = ? OR id = ? OR short_code = ?) AND tenant_id = ?",
            rs -> rs.next() ? new PieceRow(rs.getString("id"), rs.getString("status")) : null,
            barcode, barcode, barcode, tenantId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("barcode", barcode);

        if (piece == null) {
            jdbc.update(
                "INSERT INTO stock_take_scans " +
                "(id, tenant_id, session_id, piece_id, raw_barcode, scanned_condition, source, actor_user_id) " +
                "VALUES (gen_random_uuid(), ?, ?, NULL, ?, ?, 'scan', ?)",
                tenantId, sessionId, barcode, condition, actorUserId);
            out.put("pieceId", null);
            out.put("classification", "unknown");
            return out;
        }

        // Idempotent: ON CONFLICT target matches the partial unique index exactly
        // (session_id, piece_id) WHERE piece_id IS NOT NULL. First scan wins; a re-scan
        // does not overwrite the recorded condition, but classification below is always
        // computed fresh against current live state.
        int inserted = jdbc.update(
            "INSERT INTO stock_take_scans " +
            "(id, tenant_id, session_id, piece_id, scanned_condition, source, actor_user_id) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, 'scan', ?) " +
            "ON CONFLICT (session_id, piece_id) WHERE piece_id IS NOT NULL DO NOTHING",
            tenantId, sessionId, piece.id(), condition, actorUserId);

        out.put("pieceId", piece.id());
        out.put("alreadyScanned", inserted == 0);
        out.put("classification", classify(sessionId, piece.id(), condition, piece.status()));
        return out;
    }

    /**
     * - in snapshot, scanned condition agrees with status_at_open -> "match"
     * - in snapshot, disagrees -> "condition_mismatch"
     * - not in snapshot, piece's CURRENT live status is 'lost' -> "unexpected_resurfaced"
     *   (system thought it was gone; it just got scanned, so it's physically present)
     * - not in snapshot, not lost (e.g. a different variant under a variant-subset scope,
     *   or created after the snapshot) -> "out_of_scope" — a real outcome the spec's three
     *   named buckets don't enumerate but a blind scanner will still hit.
     */
    private String classify(UUID sessionId, String pieceId, String scannedCondition, String liveStatus) {
        UUID tenantId = TenantContext.require();
        String statusAtOpen = jdbc.query(
            "SELECT status_at_open FROM stock_take_expected WHERE session_id = ? AND piece_id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString("status_at_open") : null,
            sessionId, pieceId, tenantId);

        if (statusAtOpen != null) {
            String expectedCondition = "damaged".equals(statusAtOpen) ? "damaged" : "good";
            return expectedCondition.equals(scannedCondition) ? "match" : "condition_mismatch";
        }
        if ("lost".equals(liveStatus)) {
            return "unexpected_resurfaced";
        }
        return "out_of_scope";
    }

    // ── Shared session lookup (used by scan/reconciliation in later steps) ──

    record SessionRow(UUID id, String status, String scopeType, UUID locationId,
                       boolean completeCount) {}

    SessionRow requireOpenSession(UUID sessionId, UUID tenantId) {
        SessionRow row = requireSession(sessionId, tenantId);
        if (!"open".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stock take session is not open (status=" + row.status() + ")");
        }
        return row;
    }

    /** No status check — reconciliation must be viewable for finalized/cancelled sessions too. */
    SessionRow requireSession(UUID sessionId, UUID tenantId) {
        SessionRow row = jdbc.query(
            "SELECT id, status, scope_type, location_id, complete_count " +
            "FROM stock_take_sessions WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getString("scope_type"),
                rs.getObject("location_id", UUID.class),
                rs.getBoolean("complete_count")) : null,
            sessionId, tenantId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock take session not found");
        }
        return row;
    }
}
