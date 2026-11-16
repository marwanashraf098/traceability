package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bug 2 (approved 2026-11-16, BRK-47431-EG) — the order drawer's untracked unit arrivals and the owner / manager "Undo
 * arrival" for one whose Scan returns session has closed (a worker marked the wrong unit). The write is
 * UntrackedParcelUnits.undoArrival (the one writer of untracked_unit_intakes); this adds the transaction, the
 * merchant-order check and one audit_log row 'untracked_arrival_undone'. No piece, no stock, no Shopify — an untracked
 * unit never wrote to either.
 */
@Service
public class UntrackedArrivalUndoService {

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public UntrackedArrivalUndoService(JdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> arrivals(UUID orderId) {
        UUID tenantId = TenantContext.require();
        requireOrder(orderId, tenantId);
        return new UntrackedParcelUnits(jdbc).arrivals(tenantId, orderId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void undo(UUID orderId, UUID intakeId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requireOrder(orderId, tenantId);
        Map<String, Object> mark = new UntrackedParcelUnits(jdbc).undoArrival(tenantId, orderId, intakeId, actorUserId);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("intakeId", intakeId.toString());
        audit.put("shipmentId", String.valueOf(mark.get("shipment_id")));
        audit.put("orderItemId", String.valueOf(mark.get("order_item_id")));
        audit.put("unitNo", mark.get("unit_no"));
        audit.put("condition", mark.get("condition"));
        audit.put("sessionId", String.valueOf(mark.get("return_session_id")));
        auditService.record(actorUserId, "untracked_arrival_undone", "order", orderId.toString(), audit);
    }

    /** A merchant order of the caller's tenant (warehouse_orders — the drawer's own set), else 404. */
    private void requireOrder(UUID orderId, UUID tenantId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM warehouse_orders WHERE id = ? AND tenant_id = ?)", Boolean.class, orderId, tenantId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }
}
