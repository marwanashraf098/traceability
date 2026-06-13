package com.traceability.inventory;

import java.util.UUID;

/**
 * Optional context carried by every transition() call.
 *
 * orderId            → written to piece_events.order_id
 * shipmentId         → written to piece_events.shipment_id
 * locationId         → written to piece_events.location_id
 * currentOrderIdToSet → value set on pieces.current_order_id (null clears it)
 * metadata           → raw JSON written to piece_events.metadata (nullable)
 */
public record TransitionContext(
        UUID orderId,
        UUID shipmentId,
        UUID locationId,
        UUID currentOrderIdToSet,
        String metadata) {

    public static TransitionContext empty() {
        return new TransitionContext(null, null, null, null, null);
    }

    public static TransitionContext forOrder(UUID orderId, UUID currentOrderIdToSet) {
        return new TransitionContext(orderId, null, null, currentOrderIdToSet, null);
    }
}
