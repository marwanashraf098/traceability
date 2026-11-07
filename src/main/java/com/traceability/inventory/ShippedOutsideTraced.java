package com.traceability.inventory;

import com.traceability.account.AuditService;
import com.traceability.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * "Shipped outside Traced" (approved by Marawan 2026-11-07) — pieces a pick scan reserved (or packed) on an order that
 * then left WITHOUT Traced's pack / pickup flow: the Bosta plugin made the waybill, the courier took it, Shopify
 * fulfilled it. Nothing else can move such a piece afterwards — the courier webhook's reserved → with_courier is not a
 * ledger edge (skipped by design), scan / complete refuse ALREADY_SHIPPED — so it stays reserved for ever: the portal
 * hides the item and Traced's stock is one off (prod 2026-11-07: The Snouts #2212095474 / P001578).
 *
 * <p><b>THE rule</b> ({@link #STUCK_PIECES_SQL}, used by the alert and the action — never re-derive it): a piece in
 * reserved / packed / awaiting_pickup with an active / packed allocation on a Shopify order (merchant_orders, not
 * internal, not cancelled) that Shopify shows fulfilled, whose NEWEST forward leg is with the courier or delivered and
 * has moved past 'created' in its history. Returning / returned legs are left out (the parcel is coming back —
 * Scan returns handles it).
 *
 * <p><b>Alert</b> {@code shipped_outside_traced} (MEDIUM, condition-only, one per order): the rule, with the first
 * successful Shopify fulfillment (else placed_at) more than {@code traced.shipped-outside-traced.days} (7) ago.
 *
 * <p><b>Action</b> ({@link #apply}, owner / manager): moves each piece out of custody through EXISTING ledger edges
 * only — reserved → packed (event pack), packed / awaiting_pickup → with_courier (event handed_to_courier; the
 * packed:with_courier reuse is approved for this), then → delivered (event delivered) when the leg is delivered.
 * Allocations active → packed (never released — returns and courier webhooks read active / packed), the order's
 * status to the leg's state only while it is still not packed, one audit row. <b>No Shopify write</b>: Shopify
 * already fulfilled the order — no increment, no decrement (the named decrement set stays at seven), no
 * exchange-dispatch hook. Later courier webhooks move the pieces on as for any order.
 */
@Service
public class ShippedOutsideTraced {

    private static final Logger log = LoggerFactory.getLogger(ShippedOutsideTraced.class);

    /** Named params :t (tenant), :days (fulfilled at least this many days ago; 0 = any), :order (null = every order). */
    public static final String STUCK_PIECES_SQL =
        "SELECT o.id AS order_id, o.number AS order_number, p.id AS piece_id, p.status::text AS piece_status, " +
        "       a.id AS allocation_id, leg.id AS shipment_id, leg.internal_state::text AS leg_state, " +
        "       COALESCE(f.fulfilled_at, o.placed_at) AS since " +
        "FROM pieces p " +
        "JOIN allocations a  ON a.piece_id = p.id AND a.tenant_id = p.tenant_id AND a.status IN ('active', 'packed') " +
        "JOIN order_items oi ON oi.id = a.order_item_id AND oi.tenant_id = a.tenant_id " +
        "JOIN merchant_orders o ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id " +
        "JOIN LATERAL (SELECT s.id, s.internal_state FROM shipments s " +
        "              WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id AND s.shipment_leg = 'forward' " +
        "              ORDER BY s.created_at DESC, s.id DESC LIMIT 1) leg ON true " +
        "LEFT JOIN LATERAL (SELECT min(CASE WHEN fl->>'created_at' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T' " +
        "                                   THEN (fl->>'created_at')::timestamptz END) AS fulfilled_at " +
        "                   FROM jsonb_array_elements(CASE WHEN jsonb_typeof(o.raw->'fulfillments') = 'array' " +
        "                                                  THEN o.raw->'fulfillments' ELSE '[]'::jsonb END) fl " +
        "                   WHERE COALESCE(fl->>'status', '') NOT IN ('cancelled', 'error', 'failure')) f ON true " +
        "WHERE p.tenant_id = :t " +
        "  AND p.status IN ('reserved', 'packed', 'awaiting_pickup') " +
        "  AND (CAST(:order AS uuid) IS NULL OR o.id = CAST(:order AS uuid)) " +
        "  AND o.external_id NOT LIKE 'internal:%' " +
        "  AND o.shopify_fulfilled AND NOT o.is_cancelled AND o.cancel_requested_at IS NULL " +
        "  AND leg.internal_state IN ('with_courier', 'delivered') " +
        "  AND EXISTS (SELECT 1 FROM shipment_status_history h WHERE h.shipment_id = leg.id " +
        "              AND h.tenant_id = o.tenant_id AND h.internal_state <> 'created') " +
        "  AND COALESCE(f.fulfilled_at, o.placed_at) < now() - make_interval(days => :days)";

    private static final String META = "{\"event\":\"shipped_outside_traced\"}";

    private final JdbcTemplate jdbc;
    private final InventoryLedger ledger;
    private final AuditService audit;

    public ShippedOutsideTraced(JdbcTemplate jdbc, InventoryLedger ledger, AuditService audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.audit = audit;
    }

    /** The rule's rows (caller's connection and tenant context). */
    public static List<Map<String, Object>> stuckPieces(JdbcTemplate jdbc, UUID tenantId, UUID orderId, int days) {
        return new NamedParameterJdbcTemplate(jdbc).queryForList(STUCK_PIECES_SQL + " ORDER BY o.id, p.id",
            new MapSqlParameterSource("t", tenantId).addValue("order", orderId == null ? null : orderId.toString())
                .addValue("days", days));
    }

    /** The order drawer's offer: {pieces, target} when the action applies now, else null (key absent). */
    public static Map<String, Object> offer(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        List<Map<String, Object>> rows = stuckPieces(jdbc, tenantId, orderId, 0);
        if (rows.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pieces", rows.size());
        m.put("target", "delivered".equals(rows.get(0).get("leg_state")) ? "delivered" : "with_courier");
        return m;
    }

    public record Result(int pieces, String target) {}

    @Transactional
    public Result apply(UUID orderId, UUID actorUserId) {
        UUID tenantId = TenantContext.require();
        List<UUID> order = jdbc.queryForList(
            "SELECT id FROM merchant_orders WHERE id = ? AND tenant_id = ? FOR UPDATE", UUID.class, orderId, tenantId);
        if (order.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        List<Map<String, Object>> rows = stuckPieces(jdbc, tenantId, orderId, 0);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Nothing to move: no piece of this order is still held in Traced after it shipped.");
        }
        UUID shipmentId = (UUID) rows.get(0).get("shipment_id");
        boolean delivered = "delivered".equals(rows.get(0).get("leg_state"));
        PieceStatus target = delivered ? PieceStatus.DELIVERED : PieceStatus.WITH_COURIER;
        TransitionContext ctx = new TransitionContext(orderId, shipmentId, null, orderId, META);
        List<String> pieces = new ArrayList<>();
        try {
            for (Map<String, Object> r : rows) {
                String pieceId = (String) r.get("piece_id");
                PieceStatus from = PieceStatus.fromDb((String) r.get("piece_status"));
                if (from == PieceStatus.RESERVED) {
                    ledger.transition(pieceId, PieceStatus.RESERVED, PieceStatus.PACKED, "pack", actorUserId, ctx);
                    from = PieceStatus.PACKED;
                }
                ledger.transition(pieceId, from, PieceStatus.WITH_COURIER, "handed_to_courier", actorUserId, ctx);
                if (delivered) {
                    ledger.transition(pieceId, PieceStatus.WITH_COURIER, PieceStatus.DELIVERED, "delivered", actorUserId, ctx);
                }
                jdbc.update("UPDATE allocations SET status = 'packed' WHERE id = ? AND tenant_id = ? AND status = 'active'",
                    r.get("allocation_id"), tenantId);
                pieces.add(pieceId);
            }
        } catch (StateConflictException | IllegalTransitionException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A piece of this order just changed. Reload and try again.");
        }
        // Forward only, a literal per target (OrderStatusBackwardWriteGuardTest: no parameterised status write here).
        jdbc.update(delivered
            ? "UPDATE merchant_orders o SET status = 'delivered' WHERE o.id = ? AND o.tenant_id = ? AND " + PackListRules.NOT_YET_PACKED_SQL
            : "UPDATE merchant_orders o SET status = 'with_courier' WHERE o.id = ? AND o.tenant_id = ? AND " + PackListRules.NOT_YET_PACKED_SQL,
            orderId, tenantId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pieces", pieces);
        meta.put("to", target.db);
        meta.put("shipment_id", shipmentId.toString());
        audit.record(actorUserId, "shipped_outside_traced", "order", orderId.toString(), meta);
        log.info("Shipped outside Traced: order {} — {} piece(s) → {}", orderId, pieces.size(), target.db);
        return new Result(pieces.size(), target.db);
    }
}
