-- V21: Exceptions center extensions
--
-- 1. Signal column for Shopify-cancel-vs-inflight detector (FR-15.3):
--    Set by the orders/cancelled webhook when the order is in awaiting_pickup
--    and cancelOrder() returns 409 (courier has already collected / is about to).
--    Drives detectShopifyCancelVsInflight() in ExceptionService.
--
-- 2. Change stuck_shipment_days default from 3 to 5 per FR-11.5 spec and pilot
--    tracker. Existing tenant rows keep their configured value unless changed here.

ALTER TABLE orders
    ADD COLUMN shopify_cancel_requested_at timestamptz;

ALTER TABLE tenants
    ALTER COLUMN stuck_shipment_days SET DEFAULT 5;

-- Update existing tenants that still have the old default value (3 → 5).
-- Tenants that deliberately set a different value are left untouched.
UPDATE tenants SET stuck_shipment_days = 5 WHERE stuck_shipment_days = 3;
