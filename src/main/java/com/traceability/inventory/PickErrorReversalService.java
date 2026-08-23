package com.traceability.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot reversal for the 2026-08-23 incident: order #2212102474
 * (fulfilled-outside-Traced, not_traced_at already set, real Bosta AWB 4239980602 live
 * since 2026-08-17) was wrongly picked (16:21) and packed (16:22) in Pick & Pack — see
 * the Step 0 diagnosis (piece_events 2960 scan, 2961 pack, 2962 tracking_linked).
 *
 * Hardcoded to this exact piece/order/allocation/tenant on purpose — this is a one-shot
 * for one incident, not a general reversal tool. Do NOT generalize or parameterize it.
 *
 * Confirmed pre-checks (do not re-derive — see Step 0 report):
 *   - not-traced orders with no legitimate Traced allocation rest at status='new' in this
 *     tenant (§4 query, new=37).
 *   - This piece's receiving_session Shopify increment is status='failed' (never pushed) —
 *     nothing to reconcile in Shopify either direction; this reversal is internal-only.
 *
 * Safety: ledger.transition()'s expectedStatus race-guard is the correctness backstop — if
 * the piece is not exactly AWAITING_PICKUP when this runs (someone already touched it),
 * transition() throws StateConflictException, the @Transactional method rolls back all
 * three writes, and NOTHING here catches that exception. It must propagate.
 */
@Service
public class PickErrorReversalService {

    private static final UUID TENANT_ID =
        UUID.fromString("e785e5e4-2c5c-428e-afdd-d26d90754229");
    private static final String PIECE_ID = "01KZ1JSQRY1JSKZPTNB126AP7M";
    private static final UUID ORDER_ID =
        UUID.fromString("ebe1c608-d184-4cd5-a55c-e2ffd5c55291");
    private static final UUID ALLOCATION_ID =
        UUID.fromString("5cf96ab2-29f6-46a4-8677-719aac1a4330");

    private final JdbcTemplate    jdbc;
    private final InventoryLedger ledger;
    private final AuditService    auditService;
    private final ObjectMapper    mapper;

    public PickErrorReversalService(JdbcTemplate jdbc, InventoryLedger ledger,
                                     AuditService auditService, ObjectMapper mapper) {
        this.jdbc         = jdbc;
        this.ledger       = ledger;
        this.auditService = auditService;
        this.mapper       = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reverse(UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        if (!TENANT_ID.equals(tenantId)) {
            throw new IllegalStateException(
                "PickErrorReversalService is hardcoded to tenant " + TENANT_ID +
                " — refusing to run under tenant " + tenantId);
        }

        String eventMetadata = buildEventMetadata();

        // 1. Piece: awaiting_pickup -> available. StateConflictException here aborts the
        //    whole transaction (default RuntimeException rollback) — deliberately uncaught.
        ledger.transition(PIECE_ID, PieceStatus.AWAITING_PICKUP, PieceStatus.AVAILABLE,
            "pick_error_reversed", actorUserId,
            new TransitionContext(ORDER_ID, null, null, null, eventMetadata));

        // 2. Allocation: packed -> released.
        int allocRows = jdbc.update(
            "UPDATE allocations SET status = 'released' WHERE id = ? AND tenant_id = ?",
            ALLOCATION_ID, tenantId);
        if (allocRows != 1) {
            throw new IllegalStateException(
                "Expected to release exactly 1 allocation row (" + ALLOCATION_ID +
                "), matched " + allocRows + " — aborting");
        }

        // 3. Order: restore to the not-traced resting status (confirmed 'new' — §4 query,
        //    not re-derived here). not_traced_at is untouched — stays set.
        int orderRows = jdbc.update(
            "UPDATE orders SET status = 'new'::order_status WHERE id = ? AND tenant_id = ?",
            ORDER_ID, tenantId);
        if (orderRows != 1) {
            throw new IllegalStateException(
                "Expected to update exactly 1 order row (" + ORDER_ID +
                "), matched " + orderRows + " — aborting");
        }

        Map<String, Object> auditMeta = new LinkedHashMap<>();
        auditMeta.put("pieceId", PIECE_ID);
        auditMeta.put("allocationId", ALLOCATION_ID.toString());
        auditMeta.put("reversesEvents", List.of(2960, 2961, 2962));
        auditMeta.put("awb", "4239980602");
        auditService.record(actorUserId, "pick_error_reversal", "order", ORDER_ID.toString(),
            auditMeta);
    }

    private String buildEventMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", "erroneous_pick_reversal");
        m.put("reverses_events", List.of(2960, 2961, 2962));
        m.put("awb", "4239980602");
        m.put("physical_action_required", "unbox_and_reshelve");
        m.put("note", "fresh unit wrongly picked/packed onto live returned shipment; " +
            "digital reversed, physical unit must be un-boxed and returned to shelf");
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize reversal metadata", e);
        }
    }
}
