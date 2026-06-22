package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class FulfillService {

    private final JdbcTemplate    jdbc;
    private final InventoryLedger ledger;
    private final AuditService    auditService;

    public FulfillService(JdbcTemplate jdbc, InventoryLedger ledger, AuditService auditService) {
        this.jdbc         = jdbc;
        this.ledger       = ledger;
        this.auditService = auditService;
    }

    // ── Queue ─────────────────────────────────────────────────────────────────

    /**
     * Returns orders eligible for action: status IN ('new','ready_to_pick',
     * 'self_pickup_pending'), not on hold, oldest first.
     * self_pickup_pending orders are fully packed and awaiting customer handover.
     */
    public List<Map<String, Object>> getQueue() {
        UUID tenantId = TenantContext.require();
        return jdbc.queryForList(
            "SELECT o.id, o.number, o.customer_name, o.customer_phone, " +
            "       o.status, o.payment_method, o.cod_amount, o.placed_at, " +
            "       o.locked_by, o.locked_at, o.is_self_pickup, " +
            "       COALESCE(SUM(oi.quantity), 0) AS total_units, " +
            "       COALESCE(( " +
            "           SELECT COUNT(*) FROM allocations a " +
            "           JOIN order_items oi2 ON oi2.id = a.order_item_id " +
            "           WHERE oi2.order_id = o.id AND a.status IN ('active','packed') " +
            "       ), 0) AS scanned_units " +
            "FROM orders o " +
            "LEFT JOIN order_items oi ON oi.order_id = o.id " +
            "WHERE o.tenant_id = ? " +
            "  AND o.status IN ('new','ready_to_pick','self_pickup_pending') " +
            "  AND o.on_hold = false " +
            "GROUP BY o.id " +
            "ORDER BY o.created_at ASC",
            tenantId);
    }

    // ── Order detail ──────────────────────────────────────────────────────────

    /** Order + items + allocated pieces for the pick screen. */
    public Map<String, Object> getOrder(UUID orderId) {
        UUID tenantId = TenantContext.require();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT o.id, o.number, o.customer_name, o.customer_phone, o.address, " +
            "       o.status, o.payment_method, o.cod_amount, o.placed_at, " +
            "       o.locked_by, o.locked_at, o.is_self_pickup, o.cancel_requested_at " +
            "FROM orders o " +
            "WHERE o.id = ? AND o.tenant_id = ?",
            orderId, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");

        Map<String, Object> order = new LinkedHashMap<>(rows.get(0));
        order.put("items", getItemsWithAllocations(orderId, tenantId));
        return order;
    }

    // ── Locking ───────────────────────────────────────────────────────────────

    @Transactional
    public void lockOrder(UUID orderId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        requirePickableStatus(orderId, tenantId);

        int rows = jdbc.update(
            "UPDATE orders SET locked_by = ?, locked_at = now() " +
            "WHERE id = ? AND tenant_id = ? " +
            "  AND (locked_by IS NULL OR locked_by = ?)",
            actorUserId, orderId, tenantId, actorUserId);

        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order is already locked by another worker");
        }
    }

    @Transactional
    public void releaseOrder(UUID orderId, UUID actorUserId, boolean isManager) {
        UUID tenantId = TenantContext.require();
        String sql = isManager
            ? "UPDATE orders SET locked_by = NULL, locked_at = NULL WHERE id = ? AND tenant_id = ?"
            : "UPDATE orders SET locked_by = NULL, locked_at = NULL WHERE id = ? AND tenant_id = ? AND locked_by = ?";

        int rows = isManager
            ? jdbc.update(sql, orderId, tenantId)
            : jdbc.update(sql, orderId, tenantId, actorUserId);

        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order not locked by you — only a manager can release it");
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    /**
     * Validates and processes a barcode scan against an order.
     *
     * Isolation: READ_COMMITTED is pinned explicitly so that when transition()
     * joins this transaction via REQUIRED propagation, both run at READ_COMMITTED.
     * The outer transaction governs the combined isolation.
     *
     * Over-allocation guard: SELECT FOR UPDATE on the order_item row serializes
     * concurrent scans for the same (order, variant) pair. The second thread
     * blocks on the lock until the first commits, then re-reads the allocation
     * count and sees capacity exhausted — rejected without a partial write.
     *
     * Validation order (approved):
     *   1. PIECE_NOT_FOUND   — no piece with this barcode in this tenant
     *   2. DUPLICATE_SCAN    — piece already allocated to this order
     *   3. ALREADY_RESERVED  — piece has a live allocation to another order
     *   4a. WRONG_VARIANT    — piece variant matches NO line on this order
     *   4b. LINE_FILLED      — variant matches a line, but it is already fully allocated
     *   5. WRONG_STATUS      — piece is not 'available'
     *   6. transition()      — available→reserved; StateConflictException → ALREADY_RESERVED
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScanResult scan(UUID orderId, String barcode, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // 1. Look up piece by barcode
        List<Map<String, Object>> pieceRows = jdbc.queryForList(
            "SELECT p.id, p.variant_id, p.status FROM pieces p " +
            "WHERE p.barcode = ? AND p.tenant_id = ?",
            barcode, tenantId);
        if (pieceRows.isEmpty()) {
            return ScanResult.rejected("PIECE_NOT_FOUND", "Barcode not found in inventory");
        }
        Map<String, Object> piece     = pieceRows.get(0);
        String              pieceId   = (String) piece.get("id");
        UUID                variantId = (UUID)   piece.get("variant_id");
        String              status    = (String) piece.get("status");

        // 2. DUPLICATE_SCAN: piece already allocated to this order
        Integer dupCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE a.piece_id = ? AND oi.order_id = ? AND a.status IN ('active','packed')",
            Integer.class, pieceId, orderId);
        if (dupCount != null && dupCount > 0) {
            return ScanResult.rejected("DUPLICATE_SCAN", "Piece already scanned for this order");
        }

        // 3. ALREADY_RESERVED: piece has an active allocation on any other order
        Integer reservedCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE piece_id = ? AND status IN ('active','packed')",
            Integer.class, pieceId);
        if (reservedCount != null && reservedCount > 0) {
            return ScanResult.rejected("ALREADY_RESERVED", "Piece is already reserved for another order");
        }

        // 4. Find the order_item for this variant that still has capacity
        //    (without lock yet — just to know the item exists)
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT oi.id AS order_item_id, oi.quantity, " +
            "       COALESCE((" +
            "           SELECT COUNT(*) FROM allocations a " +
            "           WHERE a.order_item_id = oi.id AND a.status IN ('active','packed')" +
            "       ), 0) AS allocated " +
            "FROM order_items oi " +
            "WHERE oi.order_id = ? AND oi.variant_id = ? AND oi.tenant_id = ?",
            orderId, variantId, tenantId);

        if (items.isEmpty()) {
            return ScanResult.rejected("WRONG_VARIANT", "This variant is not on the order");
        }

        // Find a line with remaining capacity
        Map<String, Object> targetItem = null;
        for (Map<String, Object> item : items) {
            long allocated = ((Number) item.get("allocated")).longValue();
            long quantity  = ((Number) item.get("quantity")).longValue();
            if (allocated < quantity) {
                targetItem = item;
                break;
            }
        }
        if (targetItem == null) {
            // Variant is on the order but every line for it is already fully allocated.
            // Distinct from WRONG_VARIANT (which means no matching line exists at all).
            return ScanResult.rejected("LINE_FILLED", "This line is already complete");
        }

        UUID orderItemId = (UUID) targetItem.get("order_item_id");

        // 5. Acquire row lock on order_item — serializes concurrent scans against the same line.
        //    The second thread blocks here until the first commits and releases the lock.
        //
        //    CRITICAL: the allocation count must be a SEPARATE SQL statement (not a subquery
        //    in the same FOR UPDATE statement). Under READ_COMMITTED, a single SQL statement
        //    sees data committed before THAT STATEMENT began. Embedding the count in the
        //    FOR UPDATE gives T2 a stale snapshot taken before the lock was granted, missing
        //    T1's committed INSERT. A second, independent SELECT COUNT sees the snapshot at
        //    its own start time — after T1 committed — and correctly reads 1.
        List<Map<String, Object>> lockedItem = jdbc.queryForList(
            "SELECT quantity FROM order_items WHERE id = ? FOR UPDATE",
            orderItemId);

        if (lockedItem.isEmpty()) {
            return ScanResult.rejected("WRONG_VARIANT", "Order item not found");
        }
        long lockedQuantity = ((Number) lockedItem.get(0).get("quantity")).longValue();

        // Fresh statement after the lock is held — sees committed state from the winner thread.
        Long countResult = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations WHERE order_item_id = ? AND status IN ('active','packed')",
            Long.class, orderItemId);
        long lockedAllocated = countResult != null ? countResult : 0L;

        if (lockedAllocated >= lockedQuantity) {
            // Post-lock re-read: the winning concurrent thread just filled this line.
            // The piece is correct-variant, just nothing left to scan for this line.
            return ScanResult.rejected("LINE_FILLED", "This line is already complete");
        }

        // 6. WRONG_STATUS: piece must be available
        if (!"available".equals(status)) {
            return ScanResult.rejected("WRONG_STATUS", "Piece is not available (status: " + status + ")");
        }

        // 7. Atomic transition available → reserved; catches the 5→6 race on the same piece
        try {
            ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.RESERVED,
                "scan", actorUserId, TransitionContext.forOrder(orderId, orderId));
        } catch (StateConflictException e) {
            return ScanResult.rejected("ALREADY_RESERVED", "Piece was claimed by a concurrent scan");
        }

        // 8. Insert allocation
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status, allocated_by, allocated_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'active', ?, now())",
            tenantId, orderItemId, pieceId, actorUserId);

        long newAllocated = lockedAllocated + 1;
        boolean allComplete = isOrderFullyScanned(orderId, tenantId);

        return ScanResult.success(pieceId, barcode, variantId, orderItemId,
            (int) newAllocated, (int) lockedQuantity, allComplete);
    }

    // ── Un-scan ───────────────────────────────────────────────────────────────

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unscan(UUID orderId, String pieceId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // Find the active allocation for this piece on this order
        List<Map<String, Object>> allocs = jdbc.queryForList(
            "SELECT a.id FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE a.piece_id = ? AND oi.order_id = ? AND a.status = 'active' AND a.tenant_id = ?",
            pieceId, orderId, tenantId);

        if (allocs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "No active allocation for this piece on this order");
        }

        UUID allocId = (UUID) allocs.get(0).get("id");

        // Transition piece: reserved → available
        ledger.transition(pieceId, PieceStatus.RESERVED, PieceStatus.AVAILABLE,
            "unscan", actorUserId, new TransitionContext(orderId, null, null, null, null));

        // Release the allocation
        jdbc.update("UPDATE allocations SET status = 'released' WHERE id = ?", allocId);
    }

    // ── Complete ──────────────────────────────────────────────────────────────

    /**
     * Completes picking for an order: validates all lines are fully scanned,
     * transitions all reserved pieces to packed, marks allocations packed,
     * and sets order status to 'packed'.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int complete(UUID orderId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        // Ensure all lines are fully scanned
        List<Map<String, Object>> lines = jdbc.queryForList(
            "SELECT oi.id, oi.quantity, " +
            "       COALESCE((" +
            "           SELECT COUNT(*) FROM allocations a " +
            "           WHERE a.order_item_id = oi.id AND a.status IN ('active','packed')" +
            "       ), 0) AS allocated " +
            "FROM order_items oi " +
            "WHERE oi.order_id = ? AND oi.tenant_id = ?",
            orderId, tenantId);

        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Order has no items");
        }
        for (Map<String, Object> line : lines) {
            long qty    = ((Number) line.get("quantity")).longValue();
            long alloc  = ((Number) line.get("allocated")).longValue();
            if (alloc < qty) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Not all items are scanned — complete scanning before packing");
            }
        }

        // Gather all reserved pieces for this order
        List<String> pieceIds = jdbc.queryForList(
            "SELECT a.piece_id FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.status = 'active' AND a.tenant_id = ?",
            String.class, orderId, tenantId);

        // Transition each piece: reserved → packed
        for (String pieceId : pieceIds) {
            ledger.transition(pieceId, PieceStatus.RESERVED, PieceStatus.PACKED,
                "pack", actorUserId, TransitionContext.forOrder(orderId, orderId));
        }

        // Mark all active allocations as packed
        jdbc.update(
            "UPDATE allocations SET status = 'packed' " +
            "WHERE order_item_id IN (" +
            "    SELECT id FROM order_items WHERE order_id = ? AND tenant_id = ?" +
            ") AND status = 'active'",
            orderId, tenantId);

        // Advance order: self-pickup orders go to self_pickup_pending (no AWB step);
        // standard orders go to packed (then await AWB-link).
        Boolean isSelfPickup = jdbc.queryForObject(
            "SELECT is_self_pickup FROM orders WHERE id = ? AND tenant_id = ?",
            Boolean.class, orderId, tenantId);
        String newOrderStatus = Boolean.TRUE.equals(isSelfPickup) ? "self_pickup_pending" : "packed";
        jdbc.update(
            "UPDATE orders SET status = ?::order_status WHERE id = ? AND tenant_id = ?",
            newOrderStatus, orderId, tenantId);

        return pieceIds.size();
    }

    // ── Self-pickup ───────────────────────────────────────────────────────────

    /**
     * Toggles the self-pickup flag before picking begins (new / ready_to_pick only).
     * Post-complete statuses (packed / awaiting_pickup / self_pickup_pending) must use
     * convertToSelfPickup() — toggling the flag there is a silent dead-end.
     */
    @Transactional
    public void setSelfPickup(UUID orderId, boolean selfPickup) {
        UUID tenantId = TenantContext.require();

        String status = jdbc.query(
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString("status") : null,
            orderId, tenantId);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        // Post-complete: flag toggle has no effect — pieces are already packed/in transit.
        // Redirect the caller to the dedicated action.
        if ("packed".equals(status) || "awaiting_pickup".equals(status)
                || "self_pickup_pending".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order is past picking — use the convert-to-self-pickup action (current: " + status + ")");
        }

        if ("cancelled".equals(status) || "delivered".equals(status)
                || "returned".equals(status) || "lost".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot change self-pickup flag on order in terminal status: " + status);
        }

        jdbc.update(
            "UPDATE orders SET is_self_pickup = ? WHERE id = ? AND tenant_id = ?",
            selfPickup, orderId, tenantId);
    }

    /**
     * FR-9.9b — Convert a packed or awaiting_pickup order to the self-pickup path.
     * Allowed only to Owner/Manager (enforced at controller layer).
     *
     * Sequence (Bosta call FIRST if awaiting_pickup; DB writes only on success):
     *   1. If awaiting_pickup + shipment linked: cancelDelivery() at Bosta.
     *      On 409 (terminal/already-delivered) or 502 (5xx): abort — order unchanged.
     *      [FR-4.6 endpoint pending confirmation — see Item 3 STOP note]
     *   2. removeFromPickupManifest() — idempotent DELETE of pickup_shipments row.
     *   3. UPDATE orders: status→self_pickup_pending, is_self_pickup=true, metadata=audit record.
     *   Pieces STAY Packed — no piece_events for this transition.
     *   Idempotent: already self_pickup_pending → no-op.
     */
    @Transactional
    public void convertToSelfPickup(UUID orderId, String reason, UUID actorUserId) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "reason is required for convert-to-self-pickup");
        }

        UUID tenantId = TenantContext.require();

        String status = jdbc.query(
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString("status") : null,
            orderId, tenantId);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        if ("self_pickup_pending".equals(status)) {
            return; // idempotent — already in target state, no double record
        }

        if (!"packed".equals(status) && !"awaiting_pickup".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "convertToSelfPickup requires packed or awaiting_pickup (current: " + status + ")");
        }

        // Step 1: Cancel at Bosta if the order already has an AWB / is on a pickup manifest.
        // FR-4.6 not yet implemented — block the awaiting_pickup path until cancelDelivery()
        // is wired. Proceeding without it would leave a live Bosta AWB on a self-pickup order.
        if ("awaiting_pickup".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Self-pickup conversion after AWB link requires Bosta delivery cancellation " +
                "(FR-4.6) — not yet available. Cancel the Bosta AWB manually first, " +
                "then contact support to force the transition.");
        }

        // Step 2: Remove from pickup manifest (no-op for packed orders — they can't be on one).
        removeFromPickupManifest(orderId, tenantId);

        // Step 3: Advance order + write order-level audit record.
        jdbc.update(
            "UPDATE orders " +
            "SET status       = 'self_pickup_pending'::order_status, " +
            "    is_self_pickup = true, " +
            "    metadata       = json_build_object(" +
            "        'type',            'converted_to_self_pickup'," +
            "        'reason',          ?::text," +
            "        'actor',           ?::text," +
            "        'previous_status', ?::text," +
            "        'converted_at',    now()::text" +
            "    )::jsonb " +
            "WHERE id = ? AND tenant_id = ?",
            reason, actorUserId != null ? actorUserId.toString() : null,
            status, orderId, tenantId);
        // Pieces STAY Packed — handover() takes over from here unchanged.
        auditService.record(actorUserId, "convert_to_self_pickup", "order", orderId.toString(),
            Map.of("reason", reason, "previous_status", status));
    }

    /**
     * Confirms customer collection for a self-pickup order.
     * Transitions all packed pieces → delivered (event_type='handover').
     * Order advances to 'delivered'.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int handover(UUID orderId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        String status = jdbc.query(
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString("status") : null,
            orderId, tenantId);
        if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        if (!"self_pickup_pending".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order must be in 'self_pickup_pending' to confirm handover (current: " + status + ")");
        }

        List<String> pieceIds = jdbc.queryForList(
            "SELECT a.piece_id FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status = 'packed'",
            String.class, orderId, tenantId);

        for (String pieceId : pieceIds) {
            ledger.transition(pieceId, PieceStatus.PACKED, PieceStatus.DELIVERED,
                "handover", actorUserId,
                new TransitionContext(orderId, null, null, orderId, "{\"self_pickup\":true}"));
        }

        jdbc.update(
            "UPDATE orders SET status = 'delivered'::order_status WHERE id = ? AND tenant_id = ?",
            orderId, tenantId);

        return pieceIds.size();
    }

    // ── Cancellation ─────────────────────────────────────────────────────────

    /**
     * Cancels an order:
     * - Pre-pack (reserved pieces only): auto-releases → order → cancelled (200).
     * - Post-pack (packed pieces): sets cancel_requested_at; returns 202 with
     *   remaining packed count. Worker must unpack each piece via unpackPiece().
     * - With courier (awaiting_pickup / with_courier): 409 — pieces are at Bosta.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CancelResult cancelOrder(UUID orderId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        String status = jdbc.query(
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            rs -> rs.next() ? rs.getString("status") : null,
            orderId, tenantId);
        if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");

        if ("cancelled".equals(status) || "delivered".equals(status) ||
            "returned".equals(status)  || "lost".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order is already in terminal status: " + status);
        }
        if ("with_courier".equals(status) || "awaiting_pickup".equals(status) ||
            "returning".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order has been handed to courier — contact Bosta to arrange cancellation. " +
                "Pieces will be released once the courier returns them.");
        }

        // Post-pack: all pieces are physically in a box
        if ("packed".equals(status) || "self_pickup_pending".equals(status)) {
            Integer packedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocations a " +
                "JOIN order_items oi ON oi.id = a.order_item_id " +
                "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status = 'packed'",
                Integer.class, orderId, tenantId);
            int packed = packedCount != null ? packedCount : 0;

            if (packed > 0) {
                jdbc.update(
                    "UPDATE orders SET cancel_requested_at = COALESCE(cancel_requested_at, now()) " +
                    "WHERE id = ? AND tenant_id = ?",
                    orderId, tenantId);
                // FR-9.11: packed orders can't be on a pickup manifest, but call is idempotent.
                removeFromPickupManifest(orderId, tenantId);
                return new CancelResult("cancel_requested",
                    "Pieces are packed — each piece must be physically unpacked before cancellation completes",
                    packed);
            }
            // packed == 0 fallthrough: shouldn't happen for these statuses, but handle gracefully
        }

        // Pre-pack auto-release: free all reserved pieces
        List<String> reservedPieces = jdbc.queryForList(
            "SELECT a.piece_id FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status = 'active'",
            String.class, orderId, tenantId);

        for (String pieceId : reservedPieces) {
            try {
                ledger.transition(pieceId, PieceStatus.RESERVED, PieceStatus.AVAILABLE,
                    "unreserved", actorUserId,
                    new TransitionContext(orderId, null, null, null, null));
            } catch (StateConflictException | IllegalTransitionException e) {
                // Piece already moved (concurrent unscan or earlier release) — skip
            }
        }

        jdbc.update(
            "UPDATE allocations SET status = 'released' " +
            "WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id = ? AND tenant_id = ?) " +
            "  AND status = 'active'",
            orderId, tenantId);

        jdbc.update(
            "UPDATE orders SET status = 'cancelled'::order_status, cancel_requested_at = NULL " +
            "WHERE id = ? AND tenant_id = ?",
            orderId, tenantId);

        // FR-9.11: pre-pack orders can't be on a manifest, but call is idempotent.
        removeFromPickupManifest(orderId, tenantId);
        return new CancelResult("cancelled", "Order cancelled", 0);
    }

    /**
     * Physically unpacks one packed piece for a post-pack cancellation.
     * Transitions packed → available (event_type='unpacked').
     * When the last packed piece is unpacked, automatically cancels the order.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UnpackResult unpackPiece(UUID orderId, String pieceId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();

        Integer check = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders " +
            "WHERE id = ? AND tenant_id = ? " +
            "  AND cancel_requested_at IS NOT NULL " +
            "  AND status IN ('packed'::order_status, 'self_pickup_pending'::order_status)",
            Integer.class, orderId, tenantId);
        if (check == null || check == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "No pending cancellation on this order");
        }

        List<Map<String, Object>> allocs = jdbc.queryForList(
            "SELECT a.id FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE a.piece_id = ? AND oi.order_id = ? AND a.status = 'packed' AND a.tenant_id = ?",
            pieceId, orderId, tenantId);
        if (allocs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Piece is not in packed state for this order");
        }

        UUID allocId = (UUID) allocs.get(0).get("id");

        ledger.transition(pieceId, PieceStatus.PACKED, PieceStatus.AVAILABLE,
            "unpacked", actorUserId,
            new TransitionContext(orderId, null, null, null, null));

        jdbc.update("UPDATE allocations SET status = 'released' WHERE id = ?", allocId);

        Integer remaining = jdbc.queryForObject(
            "SELECT COUNT(*) FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status = 'packed'",
            Integer.class, orderId, tenantId);
        int remainingCount = remaining != null ? remaining : 0;

        if (remainingCount == 0) {
            jdbc.update(
                "UPDATE orders SET status = 'cancelled'::order_status, cancel_requested_at = NULL " +
                "WHERE id = ? AND tenant_id = ?",
                orderId, tenantId);
            // FR-9.11: packed orders can't be on a manifest, but idempotent.
            removeFromPickupManifest(orderId, tenantId);
            return new UnpackResult(true, 0);
        }

        return new UnpackResult(false, remainingCount);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * FR-9.11 — Removes all pickup_shipments rows whose shipment belongs to this order.
     * Idempotent: if the order has no shipment or was never on a manifest, this is a no-op.
     * Called from cancelOrder() success branches and unpackPiece() auto-cancel path.
     * Also called from ShopifyWebhookProcessorJob.handleOrderCancelled() unconditionally,
     * which is the only path that cleans up awaiting_pickup manifest rows (since those orders
     * 409 out of cancelOrder() before reaching this code).
     */
    public void removeFromPickupManifest(UUID orderId) {
        UUID tenantId = TenantContext.require();
        removeFromPickupManifest(orderId, tenantId);
    }

    private void removeFromPickupManifest(UUID orderId, UUID tenantId) {
        jdbc.update(
            "DELETE FROM pickup_shipments " +
            "WHERE tenant_id = ? " +
            "  AND shipment_id IN (" +
            "      SELECT id FROM shipments WHERE order_id = ? AND tenant_id = ?" +
            "  )",
            tenantId, orderId, tenantId);
    }

    private void requirePickableStatus(UUID orderId, UUID tenantId) {
        List<String> rows = jdbc.queryForList(
            "SELECT status FROM orders WHERE id = ? AND tenant_id = ?",
            String.class, orderId, tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        String s = rows.get(0);
        if (!"new".equals(s) && !"ready_to_pick".equals(s)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Order status '" + s + "' cannot be picked");
        }
    }

    private boolean isOrderFullyScanned(UUID orderId, UUID tenantId) {
        Integer remaining = jdbc.queryForObject(
            "SELECT COUNT(*) FROM order_items oi " +
            "WHERE oi.order_id = ? AND oi.tenant_id = ? " +
            "  AND oi.quantity > COALESCE((" +
            "      SELECT COUNT(*) FROM allocations a " +
            "      WHERE a.order_item_id = oi.id AND a.status IN ('active','packed')" +
            "  ), 0)",
            Integer.class, orderId, tenantId);
        return remaining != null && remaining == 0;
    }

    private List<Map<String, Object>> getItemsWithAllocations(UUID orderId, UUID tenantId) {
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT oi.id, oi.variant_id, v.sku, v.title AS variant_title, " +
            "       p.title AS product_title, oi.quantity, " +
            "       COALESCE((" +
            "           SELECT COUNT(*) FROM allocations a " +
            "           WHERE a.order_item_id = oi.id AND a.status IN ('active','packed')" +
            "       ), 0) AS allocated " +
            "FROM order_items oi " +
            "JOIN variants v ON v.id = oi.variant_id " +
            "JOIN products p ON p.id = v.product_id " +
            "WHERE oi.order_id = ? AND oi.tenant_id = ? " +
            "ORDER BY oi.id",
            orderId, tenantId);

        for (Map<String, Object> item : items) {
            UUID itemId = (UUID) item.get("id");
            List<Map<String, Object>> pieces = jdbc.queryForList(
                "SELECT a.piece_id, a.status AS allocation_status, " +
                "       p.barcode, p.status AS piece_status " +
                "FROM allocations a " +
                "JOIN pieces p ON p.id = a.piece_id " +
                "WHERE a.order_item_id = ? AND a.status IN ('active','packed') " +
                "ORDER BY a.allocated_at",
                itemId);
            item.put("allocatedPieces", pieces);
        }
        return items;
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record CancelResult(
            String status,
            String message,
            int    remainingPacked) {}

    public record UnpackResult(
            boolean cancelled,
            int     remainingPacked) {}

    public record ScanResult(
            boolean  success,
            String   code,
            String   message,
            String   pieceId,
            String   barcode,
            UUID     variantId,
            UUID     orderItemId,
            int      allocatedCount,
            int      requiredQuantity,
            boolean  allComplete) {

        static ScanResult success(String pieceId, String barcode, UUID variantId,
                                  UUID orderItemId, int allocated, int required,
                                  boolean allComplete) {
            return new ScanResult(true, "SCANNED", null, pieceId, barcode,
                variantId, orderItemId, allocated, required, allComplete);
        }

        static ScanResult rejected(String code, String message) {
            return new ScanResult(false, code, message, null, null, null, null, 0, 0, false);
        }
    }
}
