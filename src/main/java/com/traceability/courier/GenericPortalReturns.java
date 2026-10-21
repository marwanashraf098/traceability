package com.traceability.courier;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Non-Bosta couriers G4a (V177) — returns-portal REFUNDS for orders shipped with another courier. The ONE
 * definition of "this order's return goes through the merchant's own courier":
 *
 * <pre>
 *   routeGeneric(order) = newestForwardIsGeneric(order)              (G3, GenericShipments)
 *                         AND tenants.portal_generic_returns_enabled (V177, default false)
 * </pre>
 *
 * Switch off → a generic order stays out of the portal exactly as in G3, and nothing here is reached for a
 * Bosta order (its newest forward leg is never generic). Such an order is eligible only while that leg is
 * DELIVERED (Shopify's shipment_status or the merchant's "Mark delivered" — the 2026-10-20 amendment) and
 * its delivered_at is inside the return window; handed_over_at is never used (Marawan, 2026-10-21).
 * Approval sets booking_status 'manual' and enqueues nothing; the merchant books their own courier.
 *
 * Not a bean: every caller passes its own JdbcTemplate (caller's connection, tenant context, transaction).
 */
public final class GenericPortalReturns {

    private GenericPortalReturns() {}

    /** Request statuses during which a customer return of the order is in progress (scan guard below). */
    static final String RETURN_IN_PROGRESS_STATUSES = "('requested', 'approved', 'pickup_booked', 'received')";

    /** SQL: routeGeneric for {@code tenantExpr} / {@code orderIdExpr} (columns or binds). */
    public static String routeGenericSql(String tenantExpr, String orderIdExpr) {
        return "(COALESCE((SELECT t_g.portal_generic_returns_enabled FROM tenants t_g WHERE t_g.id = " + tenantExpr + "), false) " +
               "AND " + GenericShipments.newestForwardIsGenericSql(orderIdExpr) + ")";
    }

    public static boolean routeGeneric(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        if (orderId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT " + routeGenericSql("?", "?"), Boolean.class, tenantId, orderId));
    }

    /**
     * The window for a generic order (the G3 exclusion's replacement in PortalService.deliveredWithinWindow):
     * the newest forward leg is generic and 'delivered', the switch is on, and delivered_at is within the
     * tenant's return window → its delivered_at; null otherwise (not delivered yet, out of window, switch off).
     */
    public static Timestamp deliveredWithinWindow(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.delivered_at, " +
            "       (now() - s.delivered_at <= interval '1 day' * t.customer_return_window_days) AS in_window " +
            "FROM tenants t " +
            "JOIN LATERAL (SELECT s1.provider, s1.internal_state, s1.delivered_at FROM shipments s1 " +
            "              WHERE s1.tenant_id = t.id AND s1.order_id = ? AND s1.shipment_leg = 'forward' " +
            "              ORDER BY s1.created_at DESC, s1.id DESC LIMIT 1) s ON true " +
            "WHERE t.id = ? AND t.portal_generic_returns_enabled " +
            "  AND s.provider = 'generic' AND s.internal_state = 'delivered' AND s.delivered_at IS NOT NULL",
            orderId, tenantId);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("in_window"))) return null;
        return (Timestamp) rows.get(0).get("delivered_at");
    }

    /** The carrier the order went out with (its newest generic forward leg's carrier_name), or null. */
    public static String forwardCarrier(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        return jdbc.queryForList(
            "SELECT NULLIF(btrim(s.carrier_name), '') FROM shipments s WHERE s.tenant_id = ? AND s.order_id = ? " +
            "  AND s.provider = 'generic' AND s.shipment_leg = 'forward' ORDER BY s.created_at DESC, s.id DESC LIMIT 1",
            String.class, tenantId, orderId).stream().findFirst().orElse(null);
    }

    /**
     * The portal's tenant-level hint (config key {@code otherCouriers}, present only when true): the switch is
     * on AND the store has shipped with another courier. A Bosta-only store — switch on or off — gets no key.
     */
    public static boolean offersOtherCouriers(JdbcTemplate jdbc, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT t.portal_generic_returns_enabled AND EXISTS (SELECT 1 FROM shipments s WHERE s.tenant_id = t.id " +
            "  AND s.provider = 'generic' AND s.shipment_leg = 'forward') FROM tenants t WHERE t.id = ?",
            Boolean.class, tenantId));
    }

    /**
     * Scan returns guard (G4a): a customer return of this generic order is in progress — a return request on
     * it is requested / approved / pickup_booked / received, or a generic return leg of it awaits intake.
     * Then a delivered piece (or the original waybill) scanned back is the CUSTOMER's return, not a parcel
     * returned to sender: the G3 forward-leg "returned" marking must not run. Only ever asked for an order
     * whose forward leg is generic (GenericLegTransitions.returnableGenericLeg found one).
     */
    public static boolean customerReturnInProgress(JdbcTemplate jdbc, UUID tenantId, UUID orderId) {
        if (orderId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM return_requests rr WHERE rr.tenant_id = ? AND rr.order_id = ? " +
            "                 AND rr.status IN " + RETURN_IN_PROGRESS_STATUSES + ") " +
            "    OR EXISTS (SELECT 1 FROM shipments s WHERE s.tenant_id = ? AND s.order_id = ? " +
            "                 AND s.provider = 'generic' AND s.shipment_leg = 'return' " +
            "                 AND s.return_intake_completed_at IS NULL " +
            "                 AND s.internal_state NOT IN ('cancelled', 'terminated'))",
            Boolean.class, tenantId, orderId, tenantId, orderId));
    }
}
