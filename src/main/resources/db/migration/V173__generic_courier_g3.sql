-- Non-Bosta couriers G3 — out at Pickups, delivered, returned parcels.
--
-- Nothing here changes what a Bosta shipment or a tenant with no generic shipments sees: three
-- nullable columns that only generic paths write (Bosta rows keep NULL), one CHECK on the new column.

-- 1. Where a shipment_status_history row came from, for GENERIC legs only (the approved invariant
--    amendment, 2026-10-20: a generic leg's 'delivered' comes from Shopify or an owner / manager, and
--    its history says which). Bosta rows — every existing row and every row Bosta paths write — stay
--    NULL: their source is the courier (provider_state / webhook_event_id) as before.
--      pickup_scan       — a pickup session closed with this parcel scanned (→ with_courier)
--      inferred_handover — Shopify said delivered while Traced never scanned it out (→ with_courier)
--      shopify           — the Shopify fulfillment's shipment_status became 'delivered' (→ delivered)
--      manual            — an owner / manager marked it delivered (→ delivered)
--      return_scan       — its waybill or one of its pieces was scanned in Scan returns (→ returned)
ALTER TABLE shipment_status_history ADD COLUMN source text
    CHECK (source IS NULL OR source IN ('pickup_scan', 'inferred_handover', 'shopify', 'manual', 'return_scan'));

-- 2. When the parcel left Traced's custody with its carrier (generic legs: the pickup close, or the
--    inferred handover). Bosta legs keep their own collectedFromBusiness / history; NULL here.
ALTER TABLE shipments ADD COLUMN handed_over_at timestamptz;

-- 3. A generic waybill scanned in Scan returns names its shipment exactly. The row's awb is the
--    shipment's own tracking number (so every existing awb = tracking_number reader works unchanged);
--    shipment_id is set ONLY for generic rows and is what the two provider-filtered parcel joins use
--    to include them. Bosta rows keep NULL and join exactly as before.
ALTER TABLE return_session_shipments ADD COLUMN shipment_id uuid REFERENCES shipments(id) ON DELETE CASCADE;
