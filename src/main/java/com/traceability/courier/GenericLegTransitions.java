package com.traceability.courier;

import com.traceability.inventory.IllegalTransitionException;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PieceStatus;
import com.traceability.inventory.StateConflictException;
import com.traceability.inventory.TransitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-Bosta couriers G3 — the ONE writer of a generic (provider = 'generic') forward leg's state past
 * 'created'. Bosta legs are never touched here: every write is guarded by {@code provider = 'generic'}.
 *
 * <pre>
 *   created ──(pickup close, PickupSessionService)───────────────► with_courier   source pickup_scan
 *   created ──(Shopify 'delivered', parcel packed in Traced)──────► with_courier   source inferred_handover
 *                                                                  └► delivered    source shopify
 *   with_courier ──(Shopify shipment_status 'delivered')───────────► delivered      source shopify
 *   with_courier ──(owner / manager "Mark delivered")──────────────► delivered      source manual
 *   with_courier | delivered ──(its waybill / a piece in Scan returns)► returned   source return_scan
 * </pre>
 *
 * Approved invariant amendment (2026-10-20): a generic leg becomes 'delivered' ONLY from Shopify's
 * fulfillment shipment_status or an owner / manager action; pieces move only through
 * {@link InventoryLedger#transition}; every write is idempotent (a conditional UPDATE on the expected
 * state — a repeat finds nothing to move) and records its source on the shipment_status_history row.
 * Monotonic: delivered and returned are never left by a later Shopify status.
 *
 * Runs in the caller's transaction and tenant context (RLS applies).
 */
@Component
public class GenericLegTransitions {

    private static final Logger log = LoggerFactory.getLogger(GenericLegTransitions.class);

    public static final String SOURCE_PICKUP_SCAN       = "pickup_scan";
    public static final String SOURCE_INFERRED_HANDOVER = "inferred_handover";
    public static final String SOURCE_SHOPIFY           = "shopify";
    public static final String SOURCE_MANUAL            = "manual";
    public static final String SOURCE_RETURN_SCAN       = "return_scan";

    private final JdbcTemplate jdbc;
    private final InventoryLedger ledger;

    public GenericLegTransitions(JdbcTemplate jdbc, InventoryLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public enum Delivered {
        /** with_courier → delivered now. */
        DELIVERED,
        /** created → with_courier (inferred handover) → delivered now; raises the LOW alert. */
        DELIVERED_INFERRED_HANDOVER,
        /** Already delivered — nothing written (idempotent). */
        ALREADY_DELIVERED,
        /** 'created' and the order isn't packed in Traced — nothing written; Shopify's status stays on the order and an alert says so. */
        HELD_NOT_PACKED,
        /** Any other state (returned, cancelled, terminated, created for a manual mark…) or not a generic forward leg — nothing written. */
        NOT_ELIGIBLE
    }

    // ── with_courier (pickup close) ──────────────────────────────────────────────────────────────

    /** The pickup close's history row for a generic leg: same row as Bosta's, plus its source. Bosta rows never come here. */
    public void recordHandover(UUID tenantId, UUID shipmentId) {
        int n = jdbc.update(
            "UPDATE shipments SET handed_over_at = COALESCE(handed_over_at, now()) " +
            "WHERE id = ? AND tenant_id = ? AND provider = 'generic'", shipmentId, tenantId);
        if (n == 1) history(tenantId, shipmentId, "with_courier", SOURCE_PICKUP_SCAN);
    }

    // ── delivered ────────────────────────────────────────────────────────────────────────────────

    /**
     * Shopify's fulfillment shipment_status became 'delivered' for this generic leg (or the merchant
     * marked it — {@code source} manual). Rule for a leg Traced never scanned out ('created'):
     * <ul>
     *   <li>Shopify, and every allocated piece of the order is packed / awaiting pickup (or the order has no
     *       allocation at all — packed outside Traced) → ALLOW: an inferred handover is recorded as such
     *       (history source inferred_handover, pieces → with_courier through the ledger), then delivered.
     *       The {@code generic_delivered_without_pickup} LOW alert names it.</li>
     *   <li>Shopify, but a piece is still being picked (reserved / available) → HOLD: nothing moves; the same
     *       alert says Shopify reports it delivered before Traced packed it.</li>
     *   <li>manual → never from 'created' (only legs with the courier can be marked delivered).</li>
     * </ul>
     */
    public Delivered deliver(UUID tenantId, UUID shipmentId, String source, UUID actorUserId) {
        Map<String, Object> leg = lockLeg(tenantId, shipmentId);
        if (leg == null) return Delivered.NOT_ELIGIBLE;
        String state = (String) leg.get("state");
        UUID orderId = (UUID) leg.get("order_id");
        if ("delivered".equals(state)) return Delivered.ALREADY_DELIVERED;

        boolean inferred = false;
        if ("created".equals(state)) {
            if (!SOURCE_SHOPIFY.equals(source)) return Delivered.NOT_ELIGIBLE;
            if (hasUnpackedPiece(orderId)) {
                log.info("Generic leg {} — Shopify says delivered but order {} isn't packed in Traced: held", shipmentId, orderId);
                return Delivered.HELD_NOT_PACKED;
            }
            int n = jdbc.update(
                "UPDATE shipments SET internal_state = 'with_courier', handed_over_at = COALESCE(handed_over_at, now()), " +
                "    last_synced_at = now() " +
                "WHERE id = ? AND tenant_id = ? AND provider = 'generic' AND shipment_leg = 'forward' " +
                "  AND internal_state = 'created'", shipmentId, tenantId);
            if (n != 1) return Delivered.NOT_ELIGIBLE;
            history(tenantId, shipmentId, "with_courier", SOURCE_INFERRED_HANDOVER);
            movePieces(orderId, shipmentId, List.of(PieceStatus.PACKED, PieceStatus.AWAITING_PICKUP), PieceStatus.WITH_COURIER,
                "handed_to_courier", null, "{\"event\":\"generic_inferred_handover\",\"source\":\"shopify\"}");
            inferred = true;
        } else if (!"with_courier".equals(state)) {
            return Delivered.NOT_ELIGIBLE;
        }

        int n = jdbc.update(
            "UPDATE shipments SET internal_state = 'delivered', delivered_at = COALESCE(delivered_at, now()), " +
            "    last_synced_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND provider = 'generic' AND shipment_leg = 'forward' " +
            "  AND internal_state = 'with_courier'", shipmentId, tenantId);
        if (n != 1) return Delivered.NOT_ELIGIBLE;
        history(tenantId, shipmentId, "delivered", source);
        movePieces(orderId, shipmentId, List.of(PieceStatus.WITH_COURIER), PieceStatus.DELIVERED, "courier_update",
            SOURCE_MANUAL.equals(source) ? actorUserId : null,
            "{\"event\":\"generic_delivered\",\"source\":\"" + source + "\"}");
        log.info("Generic leg {} (order {}) delivered — source {}{}", shipmentId, orderId, source,
            inferred ? " (inferred handover)" : "");
        return inferred ? Delivered.DELIVERED_INFERRED_HANDOVER : Delivered.DELIVERED;
    }

    // ── returned ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The warehouse scanned this generic leg's waybill (or one of its pieces) in Scan returns while the leg is
     * with_courier or delivered: no courier status exists for it, so the scan IS the evidence — the leg
     * becomes 'returned' (returned_at, history source return_scan). Pieces are not moved here; each piece
     * scan moves its own piece, exactly as for a Bosta return-to-origin parcel.
     *
     * @return true when the leg moved now (false: not generic, already returned, or not with the courier)
     */
    public boolean markReturnedByScan(UUID tenantId, UUID shipmentId) {
        int n = jdbc.update(
            "UPDATE shipments SET internal_state = 'returned', returned_at = COALESCE(returned_at, now()), " +
            "    last_synced_at = now() " +
            "WHERE id = ? AND tenant_id = ? AND provider = 'generic' AND shipment_leg = 'forward' " +
            "  AND internal_state IN ('with_courier', 'delivered')", shipmentId, tenantId);
        if (n == 1) {
            history(tenantId, shipmentId, "returned", SOURCE_RETURN_SCAN);
            log.info("Generic leg {} returned — scanned in Scan returns", shipmentId);
        }
        return n == 1;
    }

    /**
     * The order's generic forward leg a return scan of one of its pieces should mark returned
     * (with_courier / delivered), or null. One live forward leg per order (V104).
     */
    public UUID returnableGenericLeg(UUID tenantId, UUID orderId) {
        if (orderId == null) return null;
        return jdbc.queryForList(
            "SELECT s.id FROM shipments s WHERE s.tenant_id = ? AND s.order_id = ? AND s.provider = 'generic' " +
            "  AND s.shipment_leg = 'forward' AND s.internal_state IN ('with_courier', 'delivered') " +
            "ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
            UUID.class, tenantId, orderId).stream().findFirst().orElse(null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> lockLeg(UUID tenantId, UUID shipmentId) {
        return jdbc.queryForList(
            "SELECT s.order_id, s.internal_state::text AS state FROM shipments s " +
            "WHERE s.id = ? AND s.tenant_id = ? AND s.provider = 'generic' AND s.shipment_leg = 'forward' " +
            "FOR UPDATE OF s", shipmentId, tenantId).stream().findFirst().orElse(null);
    }

    /** A live allocation whose piece isn't packed yet (still being picked) — Shopify's 'delivered' can't be trusted over it. */
    private boolean hasUnpackedPiece(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "  JOIN pieces p ON p.id = a.piece_id " +
            "  WHERE oi.order_id = ? AND a.status IN ('active', 'packed') " +
            "    AND p.status::text NOT IN ('packed', 'awaiting_pickup', 'with_courier', 'delivered'))",
            Boolean.class, orderId));
    }

    private void history(UUID tenantId, UUID shipmentId, String state, String source) {
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at, source) " +
            "VALUES (?, ?, ?, now(), ?)", tenantId, shipmentId, state, source);
    }

    /** The order's allocated pieces in one of {@code from} → {@code to}, each through the ledger; others untouched. */
    private void movePieces(UUID orderId, UUID shipmentId, List<PieceStatus> from, PieceStatus to, String eventType,
                            UUID actorUserId, String metadata) {
        List<Map<String, Object>> pieces = jdbc.queryForList(
            "SELECT p.id, p.status::text AS status FROM pieces p " +
            "JOIN allocations a ON a.piece_id = p.id JOIN order_items oi ON oi.id = a.order_item_id " +
            "WHERE oi.order_id = ? AND a.status IN ('active', 'packed')", orderId);
        for (Map<String, Object> pr : pieces) {
            PieceStatus current = PieceStatus.fromDb((String) pr.get("status"));
            if (!from.contains(current)) continue;
            try {
                ledger.transition((String) pr.get("id"), current, to, eventType, actorUserId,
                    new TransitionContext(orderId, shipmentId, null, orderId, metadata));
            } catch (StateConflictException e) {
                if (e.getActual() != to) log.warn("Piece {} in unexpected state {} for {} — skipped", pr.get("id"), e.getActual(), to);
            } catch (IllegalTransitionException e) {
                log.warn("No legal transition {} → {} for piece {} — skipped", current, to, pr.get("id"));
            }
        }
    }
}
