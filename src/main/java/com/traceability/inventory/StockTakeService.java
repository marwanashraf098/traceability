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

    // ── Shared session lookup (used by scan/reconciliation in later steps) ──

    record SessionRow(UUID id, String status, String scopeType, UUID locationId,
                       boolean completeCount) {}

    SessionRow requireOpenSession(UUID sessionId, UUID tenantId) {
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
        if (!"open".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Stock take session is not open (status=" + row.status() + ")");
        }
        return row;
    }
}
