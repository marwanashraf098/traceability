package com.traceability.portal;

import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Returns portal Step 4b — the merchant's side of customer return requests: list, detail,
 * approve, reject. Tenant-scoped by TenantContext (request filter) + RLS, with explicit
 * tenant_id filters as defence in depth. No pieces move here; a rejected request only
 * deactivates its items so those pieces are returnable again.
 */
@Service
public class ReturnRequestService {

    static final int REASON_MAX = 300;
    private static final Set<String> STATUSES = Set.of(
        "requested", "approved", "rejected", "pickup_booked", "received", "refund_pending", "refunded", "cancelled");

    private final JdbcTemplate jdbc;

    public ReturnRequestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(String status, int page, int size) {
        UUID tenantId = TenantContext.require();
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
        }
        String statusFilter = status == null || status.isBlank() ? null : status;
        List<Map<String, Object>> rows = jdbc.query(
            "SELECT rr.id, rr.reference, o.number AS order_number, o.customer_name, rr.status::text AS status, " +
            "       rr.created_at, " +
            "       (SELECT COUNT(*) FROM return_request_items i WHERE i.request_id = rr.id) AS item_count, " +
            "       (SELECT array_agg(DISTINCT i.reason_code ORDER BY i.reason_code) " +
            "          FROM return_request_items i WHERE i.request_id = rr.id) AS reason_codes " +
            "FROM return_requests rr JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "WHERE rr.tenant_id = ? AND (?::text IS NULL OR rr.status::text = ?) " +
            "ORDER BY rr.created_at DESC, rr.id DESC LIMIT ? OFFSET ?",
            (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getObject("id", UUID.class).toString());
                row.put("reference", rs.getString("reference"));
                row.put("orderNumber", rs.getString("order_number"));
                row.put("customerName", rs.getString("customer_name"));
                row.put("itemCount", rs.getInt("item_count"));
                java.sql.Array codes = rs.getArray("reason_codes");
                row.put("reasonCodes", codes == null ? List.of() : Arrays.asList((String[]) codes.getArray()));
                row.put("status", rs.getString("status"));
                row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                return row;
            },
            tenantId, statusFilter, statusFilter, size, page * size);
        Integer total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM return_requests rr WHERE rr.tenant_id = ? AND (?::text IS NULL OR rr.status::text = ?)",
            Integer.class, tenantId, statusFilter, statusFilter);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("total", total);
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID id) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT rr.id, rr.reference, rr.order_id, o.number AS order_number, o.customer_name, " +
            "       rr.type, rr.status::text AS status, rr.customer_email, rr.customer_note, rr.created_at, " +
            "       rr.decided_at, rr.decided_by, u.name AS decided_by_name, rr.rejection_reason, rr.return_shipment_id " +
            "FROM return_requests rr " +
            "JOIN orders o ON o.id = rr.order_id AND o.tenant_id = rr.tenant_id " +
            "LEFT JOIN users u ON u.id = rr.decided_by " +
            "WHERE rr.id = ? AND rr.tenant_id = ?",
            id, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found");
        Map<String, Object> r = rows.get(0);

        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT i.id, i.piece_id AS \"pieceId\", p.short_code AS \"shortCode\", i.variant_id AS \"variantId\", " +
            "       pr.title AS \"productTitle\", v.title AS \"variantTitle\", pr.image_url AS \"imageUrl\", " +
            "       i.reason_code AS \"reasonCode\", i.active " +
            "FROM return_request_items i " +
            "JOIN pieces p    ON p.id = i.piece_id " +
            "JOIN variants v  ON v.id = i.variant_id " +
            "JOIN products pr ON pr.id = v.product_id " +
            "WHERE i.request_id = ? AND i.tenant_id = ? " +
            "ORDER BY pr.title, v.title, p.created_at, i.id",
            id, tenantId);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", r.get("id").toString());
        d.put("reference", r.get("reference"));
        d.put("orderId", r.get("order_id").toString());
        d.put("orderNumber", r.get("order_number"));
        d.put("customerName", r.get("customer_name"));
        d.put("type", r.get("type"));
        d.put("status", r.get("status"));
        d.put("email", r.get("customer_email"));
        d.put("note", r.get("customer_note"));
        d.put("createdAt", r.get("created_at"));
        d.put("decidedAt", r.get("decided_at"));
        d.put("decidedBy", r.get("decided_by"));
        d.put("decidedByName", r.get("decided_by_name"));
        d.put("rejectionReason", r.get("rejection_reason"));
        d.put("returnShipmentId", r.get("return_shipment_id"));
        d.put("items", items);
        return d;
    }

    /** requested → approved. Anything else → 409 (unknown id → 404). */
    @Transactional
    public void approve(UUID id, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        int updated = jdbc.update(
            "UPDATE return_requests SET status = 'approved', decided_at = now(), decided_by = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'requested'",
            actorUserId, id, tenantId);
        if (updated != 1) throw notRequested(id, tenantId);
    }

    /**
     * requested → rejected with a reason (required, ≤ 300). Deactivates the request's items in
     * the same transaction, so those pieces are returnable again (lookup counts them).
     */
    @Transactional
    public void reject(UUID id, String reason, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        String r = reason == null ? null : reason.trim();
        if (r == null || r.isEmpty() || r.length() > REASON_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A rejection reason is required (at most " + REASON_MAX + " characters).");
        }
        int updated = jdbc.update(
            "UPDATE return_requests SET status = 'rejected', decided_at = now(), decided_by = ?, rejection_reason = ? " +
            "WHERE id = ? AND tenant_id = ? AND status = 'requested'",
            actorUserId, r, id, tenantId);
        if (updated != 1) throw notRequested(id, tenantId);
        jdbc.update("UPDATE return_request_items SET active = false WHERE request_id = ? AND tenant_id = ?",
            id, tenantId);
    }

    private ResponseStatusException notRequested(UUID id, UUID tenantId) {
        Boolean exists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests WHERE id = ? AND tenant_id = ?)",
            Boolean.class, id, tenantId);
        return Boolean.TRUE.equals(exists)
            ? new ResponseStatusException(HttpStatus.CONFLICT, "Only a request awaiting a decision can be approved or rejected.")
            : new ResponseStatusException(HttpStatus.NOT_FOUND, "Return request not found");
    }
}
