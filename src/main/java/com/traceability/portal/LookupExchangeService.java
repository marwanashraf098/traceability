package com.traceability.portal;

import com.traceability.courier.GenericExchanges;
import com.traceability.courier.GenericLegTransitions;
import com.traceability.inventory.IllegalTransitionException;
import com.traceability.inventory.InventoryLedger;
import com.traceability.inventory.PieceStatus;
import com.traceability.inventory.StateConflictException;
import com.traceability.inventory.TransitionContext;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * C2 Lookup Exchange (approved 2026-10-28) — an owner / manager sends an available piece as the replacement of a
 * customer's exchange, straight from Lookup, on a store that ships with its own courier.
 *
 * The exchange is a merchant return request ({@code origin = 'lookup'}, type 'exchange', auto-approved, booking
 * 'manual') on the G4b generic lifecycle unchanged: the internal replacement order
 * ({@link GenericExchanges#attachGenericForRequest}), Arrived / Scan returns attribution of the item coming back, and
 * 'exchanged'. What Lookup adds:
 * <ul>
 *   <li>the Lookup piece is reserved to the replacement order at once (available → reserved, allocation 'active' —
 *       FulfillService.scan's shape), and the order is 'confirmed' (outside the pick queue's statuses; never
 *       picked — FulfillService refuses NOT_PICKABLE);</li>
 *   <li>entering the outbound tracking number hands it to the courier at once
 *       ({@link GenericLegTransitions#dispatchLookupReplacement}) and the Step-5a decrement runs once;</li>
 *   <li>Cancel exchange, only before the replacement left.</li>
 * </ul>
 * Gate: {@code tenants.generic_courier_enabled} AND no active Bosta account — never the portal's other-couriers
 * switch. Main-warehouse, tagged, available pieces only.
 */
@Service
public class LookupExchangeService {

    private static final Logger log = LoggerFactory.getLogger(LookupExchangeService.class);

    public static final String ORIGIN_LOOKUP = "lookup";
    static final String DEFAULT_REASON = "other";
    private static final int SEARCH_LIMIT = 20;

    /** The order statuses the pick queue's gate takes (FulfillService.PICKABLE_SHIPMENT_GATE) — never edited there. */
    public static final String PICKABLE_STATUSES_SQL = "('new', 'ready_to_pick', 'self_pickup_pending')";

    private final JdbcTemplate jdbc;
    private final InventoryLedger ledger;
    private final ReturnRequestService requestService;
    private final GenericLegTransitions legs;

    public LookupExchangeService(JdbcTemplate jdbc, InventoryLedger ledger, ReturnRequestService requestService,
                                 GenericLegTransitions legs) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.requestService = requestService;
        this.legs = legs;
    }

    // ── Gate ─────────────────────────────────────────────────────────────────────────────────────

    /** generic_courier_enabled AND no active Bosta account. The ONE gate for Lookup's Exchange (button + server). */
    public static boolean gateOpen(JdbcTemplate jdbc, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT t.generic_courier_enabled AND NOT EXISTS (SELECT 1 FROM courier_accounts ca " +
            "    WHERE ca.tenant_id = t.id AND ca.provider = 'bosta' AND ca.status = 'active') " +
            "FROM tenants t WHERE t.id = ?", Boolean.class, tenantId));
    }

    /** The Lookup exchange (id, reference) whose replacement order the piece is reserved / packed to, or null. */
    public static Map<String, Object> reservedTo(JdbcTemplate jdbc, UUID tenantId, String pieceId) {
        return jdbc.queryForList(
            "SELECT rr.id::text AS \"requestId\", rr.reference FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id " +
            "JOIN return_requests rr ON rr.replacement_order_id = oi.order_id AND rr.tenant_id = a.tenant_id " +
            "WHERE a.piece_id = ? AND a.tenant_id = ? AND a.status IN ('active', 'packed') " +
            "  AND rr.origin = 'lookup' LIMIT 1", pieceId, tenantId).stream().findFirst().orElse(null);
    }

    /**
     * The reference of the Lookup exchange whose replacement this order is, while the replacement hasn't left and the
     * exchange isn't cancelled — else null (FulfillService.cancelOrder's refusal; every other order: null).
     */
    public static String openReferenceFor(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        String reference = jdbc.queryForList(
            "SELECT rr.reference FROM return_requests rr JOIN merchant_orders o ON o.id = rr.replacement_order_id " +
            "  AND o.tenant_id = rr.tenant_id WHERE rr.replacement_order_id = ? AND rr.tenant_id = ? " +
            "  AND rr.origin = 'lookup' AND o.status <> 'cancelled'", String.class, orderId, tenantId)
            .stream().findFirst().orElse(null);
        if (reference == null || replacementLeft(jdbc, tenantId, orderId)) return null;
        return reference;
    }

    /** Is this order a Lookup exchange's replacement? (FulfillService's NOT_PICKABLE refusal.) */
    public static boolean isLookupReplacementOrder(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests WHERE replacement_order_id = ? AND tenant_id = ? " +
            "  AND origin = 'lookup')", Boolean.class, orderId, tenantId));
    }

    private UUID requireGate() {
        UUID tenantId = TenantContext.require();
        if (!gateOpen(jdbc, tenantId)) throw LookupExchangeException.unavailable();
        return tenantId;
    }

    // ── Step 1: the original order ───────────────────────────────────────────────────────────────

    /**
     * The customer's original orders matching {@code q} (number, name, phone) that have something to bring back:
     * a delivered piece not in a live return, or an untracked line with a free unit. Never an internal order
     * (exchange replacements, portal pre-connect rows — merchant_orders already leaves the latter out).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchOrders(String q) {
        UUID tenantId = requireGate();
        String term = q == null ? "" : q.strip();
        if (term.length() < 2) return List.of();
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.queryForList(
            "SELECT o.id::text AS id, o.number, o.customer_name AS \"customerName\", " +
            "       (SELECT s.delivered_at FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id " +
            "          AND s.shipment_leg = 'forward' AND s.delivered_at IS NOT NULL " +
            "        ORDER BY s.delivered_at DESC, s.id DESC LIMIT 1) AS \"deliveredAt\", " +
            "       (SELECT s.carrier_name FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id " +
            "          AND s.shipment_leg = 'forward' ORDER BY s.created_at DESC, s.id DESC LIMIT 1) AS \"carrierName\", " +
            "       (SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi WHERE oi.order_id = o.id) AS \"itemCount\" " +
            "FROM merchant_orders o " +
            "WHERE o.tenant_id = ? AND o.external_id NOT LIKE 'internal:%' AND o.pii_redacted_at IS NULL " +
            "  AND (o.number ILIKE ? OR o.customer_name ILIKE ? OR o.customer_phone ILIKE ?) " +
            "  AND (" + RETURNABLE_PIECE_EXISTS + " OR " + RETURNABLE_UNIT_LINE_EXISTS + ") " +
            "ORDER BY o.placed_at DESC NULLS LAST, o.id DESC LIMIT " + SEARCH_LIMIT,
            tenantId, like, like, like);
    }

    private static final String RETURNABLE_PIECE_WHERE =
        "p.tenant_id = o.tenant_id AND p.current_order_id = o.id AND p.status = 'delivered'::piece_status " +
        "AND NOT EXISTS (SELECT 1 FROM return_request_items rri WHERE rri.piece_id = p.id " +
        "                AND rri.tenant_id = p.tenant_id AND rri.active) ";

    private static final String RETURNABLE_PIECE_EXISTS =
        "EXISTS (SELECT 1 FROM pieces p WHERE " + RETURNABLE_PIECE_WHERE + ")";

    // An untracked line (PortalService.LINE_UNTRACKED_SQL) with fewer units in requests than its cap.
    private static final String RETURNABLE_UNIT_LINE_EXISTS =
        "EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id AND oi.tenant_id = o.tenant_id AND " +
        PortalService.LINE_UNTRACKED_SQL + " AND " + PortalService.UNTRACKED_CAP_SQL + " > " +
        "  (SELECT COUNT(*) FROM return_request_items rri WHERE rri.order_item_id = oi.id " +
        "     AND rri.tenant_id = oi.tenant_id AND rri.item_status <> 'not_coming'))";

    // ── Step 2: what comes back ──────────────────────────────────────────────────────────────────

    /**
     * What can come back on this order: delivered tracked pieces not in a live return ({@code kind: piece}), and each
     * free unit of an untracked line, one row per unit ({@code kind: unit}). Same rules as the portal's binding
     * (PortalService), except a variant marked non-returnable for customers is still offered — the merchant decides.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> returnables(UUID orderId) {
        // The order first: an unknown order, an internal one and a portal pre-connect one are the same 404.
        requireOriginalOrder(TenantContext.require(), orderId);
        UUID tenantId = requireGate();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : jdbc.queryForList(
                "SELECT p.id, p.short_code, v.id AS variant_id, v.title AS variant_title, pr.title AS product_title " +
                "FROM merchant_orders o JOIN pieces p ON " + RETURNABLE_PIECE_WHERE +
                "JOIN variants v ON v.id = p.variant_id JOIN products pr ON pr.id = v.product_id " +
                "WHERE o.id = ? AND o.tenant_id = ? ORDER BY pr.title, v.title, p.created_at, p.id",
                orderId, tenantId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "piece");
            m.put("pieceId", p.get("id"));
            m.put("shortCode", p.get("short_code"));
            m.put("variantId", p.get("variant_id").toString());
            m.put("productTitle", p.get("product_title"));
            m.put("variantTitle", p.get("variant_title"));
            out.add(m);
        }
        for (Map<String, Object> line : untrackedLines(tenantId, orderId)) {
            UUID orderItemId = (UUID) line.get("id");
            for (int unit : freeUnits(tenantId, orderItemId, ((Number) line.get("cap")).intValue())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", "unit");
                m.put("orderItemId", orderItemId.toString());
                m.put("unitNo", unit);
                m.put("variantId", line.get("variant_id").toString());
                m.put("productTitle", line.get("product_title"));
                m.put("variantTitle", line.get("variant_title"));
                out.add(m);
            }
        }
        return out;
    }

    private List<Map<String, Object>> untrackedLines(UUID tenantId, UUID orderId) {
        return jdbc.queryForList(
            "SELECT oi.id, oi.variant_id, v.title AS variant_title, pr.title AS product_title, " +
            PortalService.UNTRACKED_CAP_SQL + " AS cap " +
            "FROM order_items oi JOIN variants v ON v.id = oi.variant_id JOIN products pr ON pr.id = v.product_id " +
            "WHERE oi.order_id = ? AND oi.tenant_id = ? AND " + PortalService.LINE_UNTRACKED_SQL +
            "ORDER BY pr.title, v.title, oi.id", orderId, tenantId);
    }

    /** Units 1..cap of an untracked line not in a request that isn't released (PortalService.bindUntrackedUnits' rule). */
    private List<Integer> freeUnits(UUID tenantId, UUID orderItemId, int cap) {
        Set<Integer> taken = new HashSet<>(jdbc.queryForList(
            "SELECT unit_no::int FROM return_request_items WHERE order_item_id = ? AND tenant_id = ? " +
            "  AND item_status <> 'not_coming'", Integer.class, orderItemId, tenantId));
        List<Integer> free = new ArrayList<>();
        for (int unit = 1; unit <= cap; unit++) if (!taken.contains(unit)) free.add(unit);
        return free;
    }

    private void requireOriginalOrder(UUID tenantId, UUID orderId) {
        if (orderId == null || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM merchant_orders WHERE id = ? AND tenant_id = ? " +
                "  AND external_id NOT LIKE 'internal:%' AND pii_redacted_at IS NULL)", Boolean.class, orderId, tenantId))) {
            throw LookupExchangeException.orderNotFound();
        }
    }

    // ── Step 3: create ───────────────────────────────────────────────────────────────────────────

    public record CreateRequest(UUID orderId, String returningPieceId, UUID returningOrderItemId, Integer returningUnitNo,
                                String reasonCode, String carrier, String outboundTrackingNumber,
                                String returnTrackingNumber) {}

    public record Created(String requestId, String reference, String replacementOrderNumber, boolean dispatched) {}

    /**
     * Creates the Lookup exchange in ONE transaction (all or nothing): the request (approved, booking 'manual'), its
     * item (the piece or unit coming back), the internal replacement order, the Lookup piece reserved to it — and,
     * when the merchant already has them, the waybills through the existing manual-courier path (outbound → handed
     * to the courier now; return → the old item's generic return leg, SAME_AS_OUTBOUND as G4b).
     */
    @Transactional
    public Created create(String pieceId, CreateRequest req, UUID actorUserId) {
        UUID tenantId = requireGate();
        if (req == null || req.orderId() == null) throw LookupExchangeException.orderNotFound();

        // The piece going out: tagged, available, at the main warehouse. Locked for the whole transaction.
        Map<String, Object> piece = jdbc.queryForList(
            "SELECT p.status::text AS status, p.short_code, p.variant_id, " +
            "       COALESCE(l.is_fulfillment, false) AS at_main " +
            "FROM pieces p LEFT JOIN locations l ON l.id = p.current_location_id AND l.tenant_id = p.tenant_id " +
            "WHERE p.id = ? AND p.tenant_id = ? FOR UPDATE OF p", pieceId, tenantId)
            .stream().findFirst().orElseThrow(LookupExchangeException::pieceNotAvailable);
        if (piece.get("short_code") == null) throw LookupExchangeException.pieceUntagged();
        if (!"available".equals(piece.get("status"))) throw LookupExchangeException.pieceNotAvailable();
        if (!Boolean.TRUE.equals(piece.get("at_main"))) throw LookupExchangeException.notAtMainWarehouse();
        UUID replacementVariant = (UUID) piece.get("variant_id");

        requireOriginalOrder(tenantId, req.orderId());
        UUID orderId = req.orderId();
        String reason = req.reasonCode() != null && PortalService.REASON_CODES.contains(req.reasonCode())
            ? req.reasonCode() : DEFAULT_REASON;

        // What comes back: exactly one delivered piece of this order, or one free unit of an untracked line.
        String returningPiece = null;
        UUID returningLine = null;
        Integer returningUnit = null;
        UUID returningVariant;
        if (req.returningPieceId() != null && !req.returningPieceId().isBlank()) {
            returningPiece = req.returningPieceId().strip();
            returningVariant = jdbc.queryForList(
                "SELECT p.variant_id FROM merchant_orders o JOIN pieces p ON " + RETURNABLE_PIECE_WHERE +
                "WHERE o.id = ? AND o.tenant_id = ? AND p.id = ? FOR UPDATE OF p",
                UUID.class, orderId, tenantId, returningPiece).stream().findFirst()
                .orElseThrow(LookupExchangeException::returningNotEligible);
        } else if (req.returningOrderItemId() != null) {
            Map<String, Object> line = untrackedLines(tenantId, orderId).stream()
                .filter(l -> req.returningOrderItemId().equals(l.get("id"))).findFirst()
                .orElseThrow(LookupExchangeException::returningNotEligible);
            List<Integer> free = freeUnits(tenantId, req.returningOrderItemId(), ((Number) line.get("cap")).intValue());
            if (req.returningUnitNo() != null) {
                if (!free.contains(req.returningUnitNo())) throw LookupExchangeException.returningNotEligible();
                returningUnit = req.returningUnitNo();
            } else {
                if (free.isEmpty()) throw LookupExchangeException.returningNotEligible();
                returningUnit = free.get(0);
            }
            returningLine = req.returningOrderItemId();
            returningVariant = (UUID) line.get("variant_id");
        } else {
            throw LookupExchangeException.returningNotEligible();
        }

        // The request: approved now (the merchant made it), booking 'manual' (their own courier).
        UUID requestId = UUID.randomUUID();
        String reference = ReturnReferences.newReference(jdbc, tenantId);
        jdbc.update(
            "INSERT INTO return_requests (id, tenant_id, order_id, type, status, reference, decided_at, decided_by, origin) " +
            "VALUES (?, ?, ?, 'exchange', 'approved'::return_request_status, ?, now(), ?, ?)",
            requestId, tenantId, orderId, reference, actorUserId, ORIGIN_LOOKUP);
        try {
            jdbc.update(
                "INSERT INTO return_request_items (tenant_id, request_id, piece_id, variant_id, reason_code, " +
                "    replacement_variant_id, order_item_id, unit_no) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, requestId, returningPiece, returningVariant, reason, replacementVariant, returningLine, returningUnit);
        } catch (DuplicateKeyException e) {
            throw LookupExchangeException.returningNotEligible();   // a concurrent request took it first
        }
        ReturnRequestLifecycle lifecycle = new ReturnRequestLifecycle(jdbc);
        lifecycle.event(tenantId, requestId, "requested", actorUserId, ReturnRequestLifecycle.meta(
            "items", 1, "type", "exchange", "replacement_variant_id", replacementVariant.toString(), "source", ORIGIN_LOOKUP,
            "replacement_piece_id", pieceId));
        lifecycle.event(tenantId, requestId, "approved", actorUserId, ReturnRequestLifecycle.meta(
            "type", "exchange", "source", ORIGIN_LOOKUP));

        // G4b's replacement order, unchanged — then 'confirmed': outside the pick queue's statuses, nothing to pick.
        UUID replacementOrder = GenericExchanges.attachGenericForRequest(jdbc, tenantId, requestId);
        if (replacementOrder == null) throw new IllegalStateException("Lookup exchange " + requestId + " has no replacement order");
        jdbc.update("UPDATE merchant_orders SET status = 'confirmed'::order_status WHERE id = ? AND tenant_id = ?",
            replacementOrder, tenantId);
        ReturnRequestService.markManual(jdbc, tenantId, requestId, orderId);
        jdbc.update(
            "UPDATE return_requests SET manual_carrier_name = COALESCE(manual_carrier_name, " +
            "    (SELECT generic_default_carrier FROM tenants WHERE id = ?)) WHERE id = ? AND tenant_id = ?",
            tenantId, requestId, tenantId);

        // The Lookup piece, reserved to it — FulfillService.scan's shape (ledger edge available:reserved + active allocation).
        UUID orderItemId = jdbc.queryForObject(
            "SELECT id FROM order_items WHERE order_id = ? AND tenant_id = ? ORDER BY id LIMIT 1",
            UUID.class, replacementOrder, tenantId);
        try {
            ledger.transition(pieceId, PieceStatus.AVAILABLE, PieceStatus.RESERVED, "scan", actorUserId,
                TransitionContext.forOrder(replacementOrder, replacementOrder));
        } catch (StateConflictException | IllegalTransitionException e) {
            throw LookupExchangeException.pieceNotAvailable();
        }
        jdbc.update(
            "INSERT INTO allocations (id, tenant_id, order_item_id, piece_id, status, allocated_by, allocated_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, 'active', ?, now())", tenantId, orderItemId, pieceId, actorUserId);

        boolean dispatched = false;
        String outbound = blankToNull(req.outboundTrackingNumber());
        String back = blankToNull(req.returnTrackingNumber());
        if (outbound != null || back != null || blankToNull(req.carrier()) != null) {
            ReturnRequestService.ManualCourierResult r = requestService.manualCourier(
                requestId, req.carrier(), back, outbound, actorUserId, legs);
            dispatched = r.outboundTrackingNumber() != null;
        }
        String number = jdbc.queryForObject("SELECT number FROM merchant_orders WHERE id = ? AND tenant_id = ?",
            String.class, replacementOrder, tenantId);
        log.info("Lookup exchange {} created — replacement order {}, piece reserved{}", requestId, replacementOrder,
            dispatched ? ", handed to the courier" : "");
        return new Created(requestId.toString(), reference, number, dispatched);
    }

    // ── Cancel ───────────────────────────────────────────────────────────────────────────────────

    /**
     * POST /return-requests/{id}/cancel-exchange — a Lookup exchange whose replacement hasn't left: the piece goes back
     * to available (releaseForAdjust's steps: reserved → available, event unreserved, allocation released), a
     * 'created' replacement leg (if any) is cancelled, the replacement order is cancelled, and the request is closed
     * 'exchange_failed' (or, when the old item already came back — 'exchanged' — it stays exchanged). Never after the
     * replacement left: any forward history past 'created' or any piece past reserved → 409 REPLACEMENT_LEFT. No
     * Shopify write — nothing was decremented.
     */
    @Transactional
    public void cancel(UUID requestId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        Map<String, Object> rr = jdbc.queryForList(
            "SELECT status::text AS status, type, origin, replacement_order_id FROM return_requests " +
            "WHERE id = ? AND tenant_id = ? FOR UPDATE", requestId, tenantId).stream().findFirst()
            .orElseThrow(LookupExchangeException::notLookupExchange);
        if (!ORIGIN_LOOKUP.equals(rr.get("origin")) || !"exchange".equals(rr.get("type"))
                || rr.get("replacement_order_id") == null) {
            throw LookupExchangeException.notLookupExchange();
        }
        UUID order = (UUID) rr.get("replacement_order_id");
        if (replacementLeft(jdbc, tenantId, order)) throw LookupExchangeException.replacementLeft();
        String orderStatus = jdbc.queryForObject("SELECT status::text FROM merchant_orders WHERE id = ? AND tenant_id = ?",
            String.class, order, tenantId);
        if ("cancelled".equals(orderStatus)) throw LookupExchangeException.notLookupExchange();

        int released = 0;
        for (Map<String, Object> a : jdbc.queryForList(
                "SELECT a.id, a.piece_id FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
                "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status = 'active' FOR UPDATE OF a", order, tenantId)) {
            ledger.transition((String) a.get("piece_id"), PieceStatus.RESERVED, PieceStatus.AVAILABLE, "unreserved",
                actorUserId, new TransitionContext(order, null, null, null, null));
            jdbc.update("UPDATE allocations SET status = 'released' WHERE id = ?", a.get("id"));
            released++;
        }
        jdbc.update(
            "UPDATE shipments SET internal_state = 'cancelled', last_synced_at = now() WHERE order_id = ? AND tenant_id = ? " +
            "  AND provider = 'generic' AND shipment_leg = 'forward' AND internal_state = 'created'", order, tenantId);
        jdbc.update("UPDATE merchant_orders SET status = 'cancelled'::order_status WHERE id = ? AND tenant_id = ?",
            order, tenantId);

        ReturnRequestLifecycle lifecycle = new ReturnRequestLifecycle(jdbc);
        lifecycle.event(tenantId, requestId, "exchange_cancelled", actorUserId, ReturnRequestLifecycle.meta(
            "source", ORIGIN_LOOKUP, "pieces_released", released));
        if (ReturnRequestLifecycle.CLOSABLE.contains((String) rr.get("status"))) {
            lifecycle.close(tenantId, requestId, ReturnRequestLifecycle.EXCHANGE_FAILED, null, actorUserId);
        }
        log.info("Lookup exchange {} cancelled — {} piece(s) back to available", requestId, released);
    }

    /** The replacement left Traced: forward history past 'created', or a piece past reserved on the order. */
    static boolean replacementLeft(JdbcTemplate jdbc, UUID tenantId, UUID order) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM shipment_status_history h JOIN shipments s ON s.id = h.shipment_id " +
            "               WHERE s.order_id = ? AND s.tenant_id = ? AND s.shipment_leg = 'forward' " +
            "                 AND h.internal_state <> 'created') " +
            "    OR EXISTS (SELECT 1 FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id " +
            "               JOIN pieces p ON p.id = a.piece_id " +
            "               WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status IN ('active', 'packed') " +
            "                 AND p.status::text <> 'reserved')",
            Boolean.class, order, tenantId, order, tenantId));
    }

    /** The drawer's lookupExchange block (inside otherCourier.replacement), or null for any other request. */
    static Map<String, Object> drawerBlock(JdbcTemplate jdbc, UUID tenantId, UUID requestId) {
        Map<String, Object> rr = jdbc.queryForList(
            "SELECT rr.origin, rr.replacement_order_id, o.status::text AS order_status FROM return_requests rr " +
            "LEFT JOIN merchant_orders o ON o.id = rr.replacement_order_id AND o.tenant_id = rr.tenant_id " +
            "WHERE rr.id = ? AND rr.tenant_id = ?", requestId, tenantId).stream().findFirst().orElse(null);
        if (rr == null || !ORIGIN_LOOKUP.equals(rr.get("origin")) || rr.get("replacement_order_id") == null) return null;
        UUID order = (UUID) rr.get("replacement_order_id");
        Map<String, Object> pc = jdbc.queryForList(
            "SELECT p.id, p.short_code, p.status::text AS status FROM allocations a " +
            "JOIN order_items oi ON oi.id = a.order_item_id JOIN pieces p ON p.id = a.piece_id " +
            "WHERE oi.order_id = ? AND a.tenant_id = ? AND a.status IN ('active', 'packed') " +
            "ORDER BY a.allocated_at DESC LIMIT 1", order, tenantId).stream().findFirst().orElse(null);
        boolean cancelled = "cancelled".equals(rr.get("order_status"));
        boolean left = replacementLeft(jdbc, tenantId, order);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pieceId", pc == null ? null : pc.get("id"));
        m.put("pieceShortCode", pc == null ? null : pc.get("short_code"));
        m.put("pieceStatus", pc == null ? null : pc.get("status"));
        m.put("left", left);
        m.put("cancelled", cancelled);
        m.put("canCancel", !left && !cancelled);
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
