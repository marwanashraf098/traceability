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
    /** C2 (V193): a Lookup exchange's replacement — tracking entered, handed to the courier at once. */
    public static final String SOURCE_LOOKUP_DISPATCH   = "lookup_dispatch";

    private final JdbcTemplate jdbc;
    private final InventoryLedger ledger;
    /** G4b: the Step-5a decrement for a replacement piece leaving custody on the inferred handover (bean only). */
    private com.traceability.inventory.ShopifyInventoryService shopifyInventory;

    public GenericLegTransitions(JdbcTemplate jdbc, InventoryLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setShopifyInventory(@org.springframework.context.annotation.Lazy
                                    com.traceability.inventory.ShopifyInventoryService shopifyInventory) {
        this.shopifyInventory = shopifyInventory;
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
            List<Moved> left = movePieces(orderId, shipmentId, List.of(PieceStatus.PACKED, PieceStatus.AWAITING_PICKUP),
                PieceStatus.WITH_COURIER, "handed_to_courier", null,
                "{\"event\":\"generic_inferred_handover\",\"source\":\"shopify\"}");
            // G4b (approved 2026-10-21): the piece really left Traced custody — the Step-5a decrement for an internal
            // exchange replacement runs once this commits (a no-op for any other order; once per piece by its claim),
            // exactly as PickupSessionService.closeSession does for the pickup handover.
            for (Moved m : left) {
                if (shopifyInventory != null && com.traceability.inventory.ShopifyInventoryService.leavesCustody(m.from(), PieceStatus.WITH_COURIER)) {
                    String pieceId = m.pieceId();
                    com.traceability.inventory.ShopifyInventoryService.afterCommit(() ->
                        shopifyInventory.onExchangeReplacementDispatched(tenantId, pieceId));
                }
            }
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

    // ── G4a: the generic RETURN leg of a portal request ──────────────────────────────────────────

    public enum ReturnLegOutcome {
        /** The return leg was created. */
        CREATED,
        /** Nothing readable in the tracking number (no letters / digits). */
        NO_KEY,
        /** The same number as the order's own forward waybill — refused; the request continues with no leg. */
        SAME_AS_FORWARD,
        /** Another of this tenant's generic shipments already carries the number. */
        KEY_TAKEN
    }

    public record ReturnLeg(ReturnLegOutcome outcome, UUID shipmentId, String trackingNumber) {}

    /**
     * G4a — the merchant booked the customer's return with their own courier and typed its tracking number:
     * a generic return leg (provider 'generic', shipment_leg 'return', source 'portal_request', state
     * 'created') keyed by {@link GenericTrackingKey} (unique per tenant across generic rows — V169). Never a
     * Bosta row. The caller links it to the request, in the same transaction.
     */
    public ReturnLeg createPortalReturnLeg(UUID tenantId, UUID orderId, String carrierName, String rawTracking) {
        String tracking = rawTracking == null ? null : rawTracking.strip();
        String key = GenericTrackingKey.of(tracking);
        if (key == null) return new ReturnLeg(ReturnLegOutcome.NO_KEY, null, null);
        Boolean sameAsForward = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE tenant_id = ? AND order_id = ? AND provider = 'generic' " +
            "  AND shipment_leg = 'forward' AND tracking_key = ?)", Boolean.class, tenantId, orderId, key);
        if (Boolean.TRUE.equals(sameAsForward)) return new ReturnLeg(ReturnLegOutcome.SAME_AS_FORWARD, null, null);
        UUID id = UUID.randomUUID();
        int n = jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, tracking_key, internal_state, " +
            "    shipment_leg, source, carrier_name) " +
            "VALUES (?, ?, ?, 'generic', ?, ?, 'created', 'return', 'portal_request', ?) ON CONFLICT DO NOTHING",
            id, tenantId, orderId, tracking, key, carrierName);
        if (n != 1) return new ReturnLeg(ReturnLegOutcome.KEY_TAKEN, null, null);
        log.info("Generic return leg {} created for order {} — carrier '{}'", id, orderId, carrierName);
        return new ReturnLeg(ReturnLegOutcome.CREATED, id, tracking);
    }

    // ── G4b: the replacement's OUTBOUND leg (generic exchange) ───────────────────────────────────

    public enum ForwardLegOutcome { CREATED, NO_KEY, KEY_TAKEN, ALREADY_HAS_LEG }

    public record ForwardLeg(ForwardLegOutcome outcome, UUID shipmentId, String trackingNumber) {}

    /**
     * G4b — the merchant booked the replacement's delivery with their courier and typed its waybill: a generic
     * FORWARD leg on the internal replacement order (source 'merchant_entered', state 'created'), keyed by
     * {@link GenericTrackingKey}. From here the replacement is pickable (FulfillService.PICKABLE_SHIPMENT_GATE: newest
     * forward leg 'created'), packs by waybill scan (G2) and leaves at a Pickups close (G3). One live forward leg per
     * order (V104) — a second call finds ALREADY_HAS_LEG. Never a Bosta row.
     */
    public ForwardLeg createReplacementForwardLeg(UUID tenantId, UUID orderId, String carrierName, String rawTracking) {
        String tracking = rawTracking == null ? null : rawTracking.strip();
        String key = GenericTrackingKey.of(tracking);
        if (key == null) return new ForwardLeg(ForwardLegOutcome.NO_KEY, null, null);
        Boolean live = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipments WHERE tenant_id = ? AND order_id = ? AND shipment_leg = 'forward' " +
            "  AND internal_state NOT IN ('terminated', 'cancelled'))", Boolean.class, tenantId, orderId);
        if (Boolean.TRUE.equals(live)) return new ForwardLeg(ForwardLegOutcome.ALREADY_HAS_LEG, null, null);
        UUID id = UUID.randomUUID();
        int n = jdbc.update(
            "INSERT INTO shipments (id, tenant_id, order_id, provider, tracking_number, tracking_key, internal_state, " +
            "    shipment_leg, source, carrier_name) " +
            "VALUES (?, ?, ?, 'generic', ?, ?, 'created', 'forward', 'merchant_entered', ?) ON CONFLICT DO NOTHING",
            id, tenantId, orderId, tracking, key, carrierName);
        if (n != 1) return new ForwardLeg(ForwardLegOutcome.KEY_TAKEN, null, null);
        log.info("Generic replacement leg {} created for order {} — carrier '{}'", id, orderId, carrierName);
        return new ForwardLeg(ForwardLegOutcome.CREATED, id, tracking);
    }

    /**
     * C2 Lookup Exchange (approved 2026-10-28) — the outbound tracking number of a LOOKUP exchange's replacement was
     * entered: the merchant already has the piece in hand, so the parcel is with the courier now (no pick, pack or
     * Pickups). The leg goes 'created' → 'with_courier' (handed_over_at, history source lookup_dispatch) and the
     * reserved piece goes reserved → packed (event pack) → with_courier (event handed_to_courier) through the two
     * existing ledger edges, its allocation active → packed, the order → with_courier. Once this commits, the
     * Step-5a decrement runs for each piece that left (ShopifyInventoryService.onExchangeReplacementDispatched —
     * unchanged; once per piece by its own claim).
     *
     * Only a generic forward leg in 'created' on an order a {@code origin = 'lookup'} request names as its
     * replacement — a portal (G4b) exchange or any other order never matches, and a repeat finds nothing to move.
     *
     * @return the pieces handed to the courier now (empty: not eligible, or nothing was reserved — the caller refuses)
     */
    public List<String> dispatchLookupReplacement(UUID tenantId, UUID shipmentId, UUID actorUserId) {
        List<UUID> orders = jdbc.queryForList(
            "UPDATE shipments s SET internal_state = 'with_courier', handed_over_at = COALESCE(handed_over_at, now()), " +
            "    last_synced_at = now() " +
            "WHERE s.id = ? AND s.tenant_id = ? AND s.provider = 'generic' AND s.shipment_leg = 'forward' " +
            "  AND s.internal_state = 'created' " +
            "  AND EXISTS (SELECT 1 FROM return_requests rr WHERE rr.replacement_order_id = s.order_id " +
            "              AND rr.tenant_id = s.tenant_id AND rr.origin = 'lookup' AND rr.type = 'exchange') " +
            "RETURNING s.order_id", UUID.class, shipmentId, tenantId);
        if (orders.isEmpty()) return List.of();
        UUID orderId = orders.get(0);
        history(tenantId, shipmentId, "with_courier", SOURCE_LOOKUP_DISPATCH);

        movePieces(orderId, shipmentId, List.of(PieceStatus.RESERVED), PieceStatus.PACKED, "pack", actorUserId,
            "{\"event\":\"lookup_exchange_dispatch\"}");
        jdbc.update(
            "UPDATE allocations SET status = 'packed' " +
            "WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id = ? AND tenant_id = ?) AND status = 'active'",
            orderId, tenantId);
        List<Moved> left = movePieces(orderId, shipmentId, List.of(PieceStatus.PACKED), PieceStatus.WITH_COURIER,
            "handed_to_courier", actorUserId, "{\"event\":\"lookup_exchange_dispatch\"}");
        jdbc.update("UPDATE merchant_orders SET status = 'with_courier'::order_status WHERE id = ? AND tenant_id = ?",
            orderId, tenantId);
        List<String> pieces = new java.util.ArrayList<>();
        for (Moved m : left) {
            pieces.add(m.pieceId());
            if (shopifyInventory != null && com.traceability.inventory.ShopifyInventoryService.leavesCustody(m.from(), PieceStatus.WITH_COURIER)) {
                String pieceId = m.pieceId();
                com.traceability.inventory.ShopifyInventoryService.afterCommit(() ->
                    shopifyInventory.onExchangeReplacementDispatched(tenantId, pieceId));
            }
        }
        log.info("Lookup exchange leg {} (order {}) handed to the courier — {} piece(s)", shipmentId, orderId, pieces.size());
        return pieces;
    }

    /**
     * G4a — a generic return leg never gets a courier status, so once Traced has its intake (stamped by a
     * Scan returns close, or earlier by an Arrived action) it becomes 'returned' (returned_at, history source
     * return_scan). Without this it would stay 'created' — "non-terminal" — and keep covering its order's
     * delivered pieces forever (ShipmentLinkService.hasReturnLegAwaitingIntake). Called by the session close;
     * generic return legs only; idempotent (returned_at set = done; a leg already moved to 'returned' by the shared
     * resolveReturnLegIfComplete gets its returned_at and history row here).
     * @return how many legs moved now
     */
    public int markIntakeCompleteReturnLegsReturned(UUID tenantId) {
        return markIntakeCompleteReturnLegsReturned(jdbc, tenantId);
    }

    /**
     * The same, on the caller's JdbcTemplate (G4b: the drawer's Arrived outside a Scan returns session —
     * ReturnRequestLifecycle — stamps intake without a session close, so it runs this right after).
     */
    public static int markIntakeCompleteReturnLegsReturned(JdbcTemplate jdbc, UUID tenantId) {
        List<UUID> moved = jdbc.queryForList(
            "UPDATE shipments SET internal_state = 'returned', returned_at = COALESCE(returned_at, now()), " +
            "    last_synced_at = now() " +
            "WHERE tenant_id = ? AND provider = 'generic' AND shipment_leg = 'return' " +
            "  AND return_intake_completed_at IS NOT NULL " +
            // not yet returned — or flipped to 'returned' by the shared ShipmentLinkService.resolveReturnLegIfComplete
            // (any provider: state only, no history, no returned_at) and not yet recorded here
            "  AND (internal_state NOT IN ('returned', 'cancelled', 'terminated') " +
            "       OR (internal_state = 'returned' AND returned_at IS NULL)) RETURNING id",
            UUID.class, tenantId);
        for (UUID id : moved) history(jdbc, tenantId, id, "returned", SOURCE_RETURN_SCAN);
        return moved.size();
    }

    /**
     * G4b — the Arrived undo cleared a generic return leg's intake stamp: the leg goes back to 'created' (history
     * source return_scan), so it never sits in 'returned' without its intake (which would read as "back but never
     * scanned in"). Generic return legs only; only one in 'returned' with no intake moves.
     */
    public static int unreturnIntakeClearedReturnLegs(JdbcTemplate jdbc, UUID tenantId, UUID requestId) {
        List<UUID> moved = jdbc.queryForList(
            "UPDATE shipments s SET internal_state = 'created', returned_at = NULL, last_synced_at = now() " +
            "FROM return_requests rr WHERE rr.id = ? AND rr.tenant_id = ? AND s.id = rr.return_shipment_id " +
            "  AND s.tenant_id = rr.tenant_id AND s.provider = 'generic' AND s.shipment_leg = 'return' " +
            "  AND s.internal_state = 'returned' AND s.return_intake_completed_at IS NULL RETURNING s.id",
            UUID.class, requestId, tenantId);
        for (UUID id : moved) history(jdbc, tenantId, id, "created", SOURCE_RETURN_SCAN);
        return moved.size();
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
        history(jdbc, tenantId, shipmentId, state, source);
    }

    private static void history(JdbcTemplate jdbc, UUID tenantId, UUID shipmentId, String state, String source) {
        jdbc.update("INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at, source) " +
            "VALUES (?, ?, ?, now(), ?)", tenantId, shipmentId, state, source);
    }

    record Moved(String pieceId, PieceStatus from) {}

    /** The order's allocated pieces in one of {@code from} → {@code to}, each through the ledger; others untouched. */
    private List<Moved> movePieces(UUID orderId, UUID shipmentId, List<PieceStatus> from, PieceStatus to, String eventType,
                                   UUID actorUserId, String metadata) {
        List<Moved> moved = new java.util.ArrayList<>();
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
                moved.add(new Moved((String) pr.get("id"), current));
            } catch (StateConflictException e) {
                if (e.getActual() != to) log.warn("Piece {} in unexpected state {} for {} — skipped", pr.get("id"), e.getActual(), to);
            } catch (IllegalTransitionException e) {
                log.warn("No legal transition {} → {} for piece {} — skipped", current, to, pr.get("id"));
            }
        }
        return moved;
    }
}
