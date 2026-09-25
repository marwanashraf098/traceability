-- ============================================================
-- V104 — Step 4c-1: more than one courier-return (CRP) leg per order
--
-- V43's ux_active_shipment_per_order_leg was UNIQUE (order_id, shipment_leg) over every
-- non-terminated/cancelled row, so a FINISHED return leg ('returned', 'delivered', ...)
-- held the order's only return slot forever: a second CRP on the same order (a second
-- portal request, or one after a dashboard-made CRP) was booked in Bosta but its INSERT in
-- ShipmentLinkService.createOrFindReturnShipment() hit this index and the delivery landed
-- in unlinked_bosta_deliveries.
--
-- New shape:
--   forward legs — still at most one active per order (same predicate as V19/V43).
--   return legs  — no per-order limit; each is guarded only by the existing global
--                  UNIQUE shipments.tracking_number (V1), which createOrFindReturnShipment
--                  already finds by before it inserts.
--
-- The forward-only index is a strict subset of the old one, so existing data already
-- satisfies it and its creation cannot fail. It is created before the old index is
-- dropped so the forward rule is never unguarded.
-- ============================================================

CREATE UNIQUE INDEX ux_active_forward_shipment_per_order
    ON shipments (order_id)
    WHERE shipment_leg = 'forward'
      AND internal_state NOT IN ('terminated', 'cancelled');

DROP INDEX ux_active_shipment_per_order_leg;
