package com.traceability.fulfillment;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * The order's shipping badge (2026-10-05), derived when the order is read — replaces the reconcile job's
 * 'not_created' flag (red "Shipment not created" on every order not linked within ~48 min, including orders
 * shipped with Wijha and orders simply not booked yet).
 *
 * In precedence order:
 *   cancelled                   the order is cancelled (neutral)
 *   linked                      a live Bosta forward shipment — the UI keeps its existing delivery badge
 *   (none)                      self-pickup, a tenant without a Bosta account, or an order already
 *                               delivered / returned / lost without a shipment
 *   bosta_tracking_not_linked   a Bosta tracking number on a Shopify fulfillment that Traced couldn't link
 *                               (link_status conflict / gave_up, or still unlinked after the grace period) — red
 *   shipped_elsewhere           shipped with another carrier (shipping_carrier_class 'other_known') — neutral
 *   not_booked_overdue          Bosta-eligible, no shipment for {@code overdueDays} or more — warning
 *   awaiting_booking            Bosta-eligible, no shipment yet — neutral
 * "Bosta-eligible" = carrier NULL, 'unknown' (Jumi's "Other") or 'bosta' still being linked.
 */
public record OrderShippingBadge(String state, String carrier, Integer days) {

    public static final String CANCELLED = "cancelled";
    public static final String LINKED = "linked";
    public static final String BOSTA_TRACKING_NOT_LINKED = "bosta_tracking_not_linked";
    public static final String SHIPPED_ELSEWHERE = "shipped_elsewhere";
    public static final String NOT_BOOKED_OVERDUE = "not_booked_overdue";
    public static final String AWAITING_BOOKING = "awaiting_booking";

    private static final Set<String> DONE_WITHOUT_SHIPMENT = Set.of("delivered", "returned", "lost");
    private static final Set<String> DEAD_LEG = Set.of("cancelled", "terminated");

    /**
     * @param forwardState  the latest forward shipment's internal_state, or null when there is none
     * @param bostaLinkProblem  a Bosta-class fulfillment Traced couldn't link (see the SQL in OrderController)
     */
    public static OrderShippingBadge derive(String orderStatus, boolean selfPickup, String forwardState,
                                            String carrierClass, String carrierName, boolean bostaLinkProblem,
                                            Instant placedAt, Instant now, int overdueDays, boolean tenantShipsWithBosta) {
        if ("cancelled".equals(orderStatus)) return new OrderShippingBadge(CANCELLED, null, null);
        if (forwardState != null && !DEAD_LEG.contains(forwardState)) return new OrderShippingBadge(LINKED, "Bosta", null);
        if (selfPickup || !tenantShipsWithBosta || DONE_WITHOUT_SHIPMENT.contains(orderStatus)) return null;
        if (bostaLinkProblem) return new OrderShippingBadge(BOSTA_TRACKING_NOT_LINKED, "Bosta", null);
        if (isShippedElsewhere(carrierClass, forwardState)) return new OrderShippingBadge(SHIPPED_ELSEWHERE, carrierName, null);
        if (placedAt != null) {
            long days = Duration.between(placedAt, now).toDays();
            if (days >= overdueDays) return new OrderShippingBadge(NOT_BOOKED_OVERDUE, null, (int) days);
        }
        return new OrderShippingBadge(AWAITING_BOOKING, null, null);
    }

    /**
     * Shipped with another known carrier and no live Bosta forward shipment — such an order is not waiting to be
     * packed. The funnel's "New", the Overview's late-to-pack and the funnel's "Shipped elsewhere" count all ask
     * this (OrderController.funnel, EmbeddedController.ordersFunnel, OverviewService.lateToPack).
     */
    public static boolean isShippedElsewhere(String carrierClass, String forwardState) {
        return OrderCarrier.OTHER_KNOWN.equals(carrierClass) && (forwardState == null || DEAD_LEG.contains(forwardState));
    }

    /**
     * SQL (alias {@code o} = orders): true when the order has a non-cancelled Bosta-class fulfillment whose
     * tracking number isn't a shipment of the order, and Traced gave up on it (conflict / gave_up) or it has
     * been unlinked for more than {@code graceMinutes}.
     */
    public static String bostaLinkProblemSql(int graceMinutes) {
        return "EXISTS (SELECT 1 FROM order_fulfillment_tracking ft " +
               "  WHERE ft.order_id = o.id AND ft.tenant_id = o.tenant_id AND ft.carrier_class = 'bosta' " +
               "    AND lower(coalesce(ft.fulfillment_status, '')) <> 'cancelled' " +
               "    AND NOT EXISTS (SELECT 1 FROM shipments s2 WHERE s2.order_id = o.id AND s2.tenant_id = o.tenant_id " +
               "                      AND s2.tracking_number = ft.tracking_number) " +
               "    AND (ft.link_status IN ('conflict', 'gave_up') " +
               "         OR ft.first_seen_at < now() - INTERVAL '" + Math.max(0, graceMinutes) + " minutes'))";
    }
}
