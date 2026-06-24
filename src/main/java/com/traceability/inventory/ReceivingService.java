package com.traceability.inventory;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class ReceivingService {

    private final JdbcTemplate    jdbc;
    private final InventoryLedger ledger;

    public ReceivingService(JdbcTemplate jdbc, InventoryLedger ledger) {
        this.jdbc   = jdbc;
        this.ledger = ledger;
    }

    // ── Create ──────────────────────────────────────────────────────────────

    @Transactional
    public UUID createSession(UUID actorUserId, UUID locationId,
                              String reference, String supplierName, String note) {
        UUID tenantId = TenantContext.require();
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, tenant_id, reference, supplier_name, received_by, location_id, note, status) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 'open')",
            id, tenantId, reference, supplierName, actorUserId, locationId, note);
        return id;
    }

    // ── Lines ────────────────────────────────────────────────────────────────

    @Transactional
    public UUID addLine(UUID sessionId, UUID variantId, int quantity) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipt_lines (id, tenant_id, receipt_id, variant_id, quantity) VALUES (?, ?, ?, ?, ?)",
            id, tenantId, sessionId, variantId, quantity);
        return id;
    }

    @Transactional
    public void updateLine(UUID sessionId, UUID lineId, int quantity) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);
        int rows = jdbc.update(
            "UPDATE receipt_lines SET quantity = ? " +
            "WHERE id = ? AND receipt_id = ? AND tenant_id = ?",
            quantity, lineId, sessionId, tenantId);
        if (rows == 0) throw notFound("line");
    }

    @Transactional
    public void deleteLine(UUID sessionId, UUID lineId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);
        jdbc.update(
            "DELETE FROM receipt_lines WHERE id = ? AND receipt_id = ? AND tenant_id = ?",
            lineId, sessionId, tenantId);
    }

    // ── Finalize ─────────────────────────────────────────────────────────────

    /**
     * Finalizes a receiving session: generates one ULID piece per unit across all
     * lines, then delegates to InventoryLedger.batchReceive() which inserts all
     * pieces and received events in two SQL round-trips inside one ACID transaction.
     *
     * All-or-nothing: if any INSERT fails (barcode uniqueness, FK) the entire
     * transaction rolls back and no partial session is committed.
     *
     * @return number of pieces created
     */
    @Transactional
    public int finalize(UUID sessionId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOpen(sessionId, tenantId);

        // Fetch location_id for this session (needed per-piece).
        UUID locationId = jdbc.queryForObject(
            "SELECT location_id FROM receipts WHERE id = ? AND tenant_id = ?",
            UUID.class, sessionId, tenantId);
        if (locationId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Session has no location set");
        }

        // Fetch lines: variantId × quantity.
        List<Map<String, Object>> lines = jdbc.queryForList(
            "SELECT variant_id, quantity FROM receipt_lines WHERE receipt_id = ? AND tenant_id = ?",
            sessionId, tenantId);

        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Session has no lines — add items before finalizing");
        }

        // Build one ReceiveSpec per unit.
        List<InventoryLedger.ReceiveSpec> specs = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            UUID variantId = (UUID) line.get("variant_id");
            int  qty       = (Integer) line.get("quantity");
            for (int i = 0; i < qty; i++) {
                specs.add(new InventoryLedger.ReceiveSpec(
                    UlidGenerator.generate(),
                    tenantId,
                    variantId,
                    sessionId,
                    locationId));
            }
        }

        // Two-INSERT atomic commit — all pieces + events or nothing.
        ledger.batchReceive(specs, actorUserId);

        // Mark session finalized.
        jdbc.update(
            "UPDATE receipts SET status = 'finalized', finalized_at = now() " +
            "WHERE id = ? AND tenant_id = ?",
            sessionId, tenantId);

        return specs.size();
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Map<String, Object> getSession(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT r.id, r.status, r.reference, r.supplier_name, r.note, " +
            "       r.location_id, l.name AS location_name, " +
            "       r.received_by, r.created_at, r.finalized_at, " +
            "       (SELECT COUNT(*) FROM pieces p WHERE p.receipt_id = r.id) AS piece_count " +
            "FROM receipts r " +
            "LEFT JOIN locations l ON l.id = r.location_id " +
            "WHERE r.id = ? AND r.tenant_id = ?",
            sessionId, tenantId);
        if (rows.isEmpty()) throw notFound("session");
        Map<String, Object> session = new LinkedHashMap<>(rows.get(0));
        session.put("lines", getLines(sessionId, tenantId));
        return session;
    }

    public List<Map<String, Object>> listSessions() {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT r.id, r.status, r.reference, r.supplier_name, r.created_at, r.finalized_at, " +
            "       l.name AS location_name, " +
            "       COALESCE((SELECT SUM(rl.quantity) FROM receipt_lines rl WHERE rl.receipt_id = r.id), 0) AS line_units, " +
            "       (SELECT COUNT(*) FROM pieces p WHERE p.receipt_id = r.id) AS piece_count " +
            "FROM receipts r " +
            "LEFT JOIN locations l ON l.id = r.location_id " +
            "WHERE r.tenant_id = ? AND r.kind = 'inbound' " +
            "ORDER BY r.created_at DESC",
            tenantId);
    }

    /** Search variants by SKU or title prefix for the add-line autocomplete. */
    public List<Map<String, Object>> getSessionPieces(UUID sessionId) {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT p.id, p.barcode, p.status, v.title AS variant_title, pr.title AS product_title " +
            "FROM pieces p " +
            "JOIN variants v  ON v.id  = p.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE p.receipt_id = ? AND p.tenant_id = ? " +
            "ORDER BY p.created_at",
            sessionId, tenantId);
    }

    public List<Map<String, Object>> searchVariants(String query) {
        UUID tenantId = TenantContext.require();
        String pattern = "%" + query.toLowerCase() + "%";
        return jdbc.queryForList(
            "SELECT v.id, v.title, v.sku, p.title AS product_title " +
            "FROM variants v " +
            "JOIN products p ON p.id = v.product_id " +
            "WHERE v.tenant_id = ? AND p.status = 'active' " +
            "  AND (LOWER(v.sku) LIKE ? OR LOWER(v.title) LIKE ? OR LOWER(p.title) LIKE ?) " +
            "ORDER BY v.sku NULLS LAST, v.title " +
            "LIMIT 20",
            tenantId, pattern, pattern, pattern);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> getLines(UUID sessionId, UUID tenantId) {
        return jdbc.queryForList(
            "SELECT rl.id, rl.variant_id, v.title AS variant_title, v.sku, " +
            "       p.title AS product_title, rl.quantity " +
            "FROM receipt_lines rl " +
            "JOIN variants v ON v.id = rl.variant_id " +
            "JOIN products p ON p.id = v.product_id " +
            "WHERE rl.receipt_id = ? AND rl.tenant_id = ? " +
            "ORDER BY rl.created_at",
            sessionId, tenantId);
    }

    private void requireOpen(UUID sessionId, UUID tenantId) {
        List<String> rows = jdbc.queryForList(
            "SELECT status FROM receipts WHERE id = ? AND tenant_id = ?",
            String.class, sessionId, tenantId);
        if (rows.isEmpty()) throw notFound("session");
        if (!"open".equals(rows.get(0))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Session is already finalized and cannot be modified");
        }
    }

    private ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiving " + what + " not found");
    }
}
