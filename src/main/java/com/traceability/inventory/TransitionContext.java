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
 * rawScan            → verbatim scanner output written to piece_events.raw_scan (nullable);
 *                      evidence only — set only on AWB tracking_linked events, null everywhere else
 */
public record TransitionContext(
        UUID orderId,
        UUID shipmentId,
        UUID locationId,
        UUID currentOrderIdToSet,
        String metadata,
        String rawScan) {

    /** Convenience: all existing call sites that don't carry a raw scan. */
    public TransitionContext(UUID orderId, UUID shipmentId, UUID locationId,
                             UUID currentOrderIdToSet, String metadata) {
        this(orderId, shipmentId, locationId, currentOrderIdToSet, metadata, null);
    }

    public static TransitionContext empty() {
        return new TransitionContext(null, null, null, null, null);
    }

    public static TransitionContext forOrder(UUID orderId, UUID currentOrderIdToSet) {
        return new TransitionContext(orderId, null, null, currentOrderIdToSet, null);
    }

    /** Returns a copy of this context with rawScan set (for AWB tracking_linked events). */
    public TransitionContext withRawScan(String raw) {
        return new TransitionContext(orderId, shipmentId, locationId, currentOrderIdToSet, metadata, raw);
    }
}
