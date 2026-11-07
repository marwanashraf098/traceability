package com.traceability.inventory;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shopify-native returns S4 (R4, approved 2026-11-07) — a returned item of a Shopify return comes back through normal
 * Scan returns intake. The ONE rule for "a Shopify return expects this piece back" and the ONE writer of
 * {@code shopify_return_intakes} (V222).
 *
 * An OPEN Shopify return line = a line of a return on an order Traced holds, whose store granted returns access
 * ({@code stores.returns_access = 'granted'}), with status OPEN or CLOSED (approved by the merchant — Shopify may
 * have processed the unit before it reached Traced), created no longer ago than the tenant's return window
 * ({@code tenants.customer_return_window_days}, 30 when none — approved 2026-11-07, so a return closed without the item
 * coming back never expects it forever), and fewer Traced intakes than its quantity. REQUESTED (not yet approved),
 * DECLINED and CANCELED returns expect nothing.
 *
 * A piece is expected back by a Shopify return when (a) it already satisfies a line since it last left Traced, or
 * (b) an open line of its ORIGINAL order has the piece's variant. A replacement shipped on a shadow order (#…-X1)
 * resolves to its original order through {@link ShopifyShadowOrders#restockOrderId} — Shopify puts a return of an
 * exchange item on the original order.
 *
 * Linking: the oldest open line first (return created, then line created); one line per scan; partial returns and
 * several returns per order are handled per line by the remaining count. When a Traced return request took the scan
 * first (duplicate), the row carries that request item — the Shopify line is COVERED by the request's scan.
 *
 * Not a bean: built on the caller's JdbcTemplate (joins its transaction, works under app_user + RLS); every query
 * also filters the tenant explicitly. Moves no piece, writes nothing to Shopify.
 */
public final class ShopifyReturnIntake {

    private ShopifyReturnIntake() {}

    /**
     * Open lines of an order for a variant, oldest first. Params: tenant, original order, variant.
     */
    static final String OPEN_LINES_SQL =
        "SELECT l.id AS line_id, l.return_id FROM shopify_return_lines l " +
        "JOIN shopify_returns r ON r.id = l.return_id AND r.tenant_id = l.tenant_id " +
        "JOIN stores st ON st.id = r.store_id " +
        "JOIN tenants t ON t.id = l.tenant_id " +
        "WHERE l.tenant_id = ? AND r.order_id = ? AND l.variant_id = ? " +
        "  AND r.status IN ('OPEN', 'CLOSED') AND st.returns_access = 'granted' " +
        "  AND now() <= COALESCE(r.shopify_created_at, r.created_at) " +
        "              + interval '1 day' * COALESCE(t.customer_return_window_days, 30) " +
        "  AND l.quantity > (SELECT COUNT(*) FROM shopify_return_intakes i " +
        "                    WHERE i.return_line_id = l.id AND i.tenant_id = l.tenant_id) " +
        "ORDER BY COALESCE(r.shopify_created_at, r.created_at), r.id, l.created_at, l.id";

    /**
     * The piece already satisfies a Shopify return line since it last left Traced (its newest move to the courier or
     * to the customer). Params: piece, tenant.
     */
    static final String LINKED_SINCE_DEPARTURE_SQL =
        "SELECT EXISTS (SELECT 1 FROM shopify_return_intakes i WHERE i.piece_id = ? AND i.tenant_id = ? " +
        "  AND i.received_at >= COALESCE((SELECT max(e.occurred_at) FROM piece_events e " +
        "      WHERE e.piece_id = i.piece_id AND e.to_status IN ('with_courier'::piece_status, 'delivered'::piece_status)), " +
        "      '-infinity'::timestamptz))";

    /** What a scan linked: the line and its return. */
    public record Linked(UUID intakeId, UUID returnLineId, UUID returnId, UUID orderId) {}

    /** The rule: a Shopify return expects this piece back. */
    public static boolean expects(JdbcTemplate jdbc, UUID tenantId, String pieceId, UUID pieceOrderId, UUID variantId) {
        if (tenantId == null || pieceId == null) return false;
        if (Boolean.TRUE.equals(jdbc.queryForObject(LINKED_SINCE_DEPARTURE_SQL, Boolean.class, pieceId, tenantId))) {
            return true;
        }
        if (pieceOrderId == null || variantId == null) return false;
        UUID original = ShopifyShadowOrders.restockOrderId(jdbc, tenantId, pieceOrderId);
        return !jdbc.queryForList(OPEN_LINES_SQL + " LIMIT 1", tenantId, original, variantId).isEmpty();
    }

    /**
     * Records which Shopify return line an accepted return scan satisfies (oldest open line first). Null when no line
     * expects it, or the piece already satisfies one since it last left. Serialised per original order (advisory
     * lock) so two scans can never take the same unit of a line.
     */
    public static Linked link(JdbcTemplate jdbc, UUID tenantId, String pieceId, UUID pieceOrderId, UUID variantId,
                              UUID sessionId, UUID sessionItemId, UUID requestItemId, UUID actorUserId) {
        if (tenantId == null || pieceId == null || pieceOrderId == null || variantId == null) return null;
        UUID original = ShopifyShadowOrders.restockOrderId(jdbc, tenantId, pieceOrderId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
            "shopify-return-intake:" + tenantId + ":" + original);
        if (Boolean.TRUE.equals(jdbc.queryForObject(LINKED_SINCE_DEPARTURE_SQL, Boolean.class, pieceId, tenantId))) {
            return null;
        }
        List<Map<String, Object>> open = jdbc.queryForList(OPEN_LINES_SQL + " LIMIT 1", tenantId, original, variantId);
        if (open.isEmpty()) return null;
        UUID lineId = (UUID) open.get(0).get("line_id");
        UUID returnId = (UUID) open.get(0).get("return_id");
        UUID id = UUID.randomUUID();
        int n = jdbc.update(
            "INSERT INTO shopify_return_intakes (id, tenant_id, return_line_id, return_id, order_id, piece_order_id, " +
            "    piece_id, variant_id, session_id, return_session_item_id, request_item_id, received_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, piece_id, return_line_id) DO NOTHING",
            id, tenantId, lineId, returnId, original, pieceOrderId, pieceId, variantId, sessionId, sessionItemId,
            requestItemId, actorUserId);
        return n == 0 ? null : new Linked(id, lineId, returnId, original);
    }
}
