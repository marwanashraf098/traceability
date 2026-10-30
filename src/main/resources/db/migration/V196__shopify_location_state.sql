-- Shopify sync fixes (2026-10-30) — the state of the Traced location in Shopify.
--
-- BROEK deactivated "Traced Main Warehouse" in Shopify: every stock update failed at activation
-- ("The variant couldn't be stocked at Traced Main Warehouse because the location was deactivated"),
-- each one spending its attempts, silently. Traced now records what Shopify says about the linked
-- location on the location row itself (the only row whose GID Traced ever writes to):
--
--   shopify_location_state     'active' (default — every location linked so far), 'inactive'
--                              (deactivated in Shopify) or 'deleted' (Shopify has no location with
--                              that id any more)
--   shopify_location_state_at  when the current state was recorded (NULL: never checked)
--
-- Written by ShopifyLocationState (a failed write that names the location, and the periodic check)
-- under app_user + RLS — locations' existing tenant_isolation policy covers both columns. While the
-- main warehouse is not 'active', every Shopify stock update is HELD exactly like needs_reauth
-- (IncrementRecoveryRules.holdReason): nothing is sent and no attempt is spent.

ALTER TABLE locations
    ADD COLUMN shopify_location_state    text NOT NULL DEFAULT 'active',
    ADD COLUMN shopify_location_state_at timestamptz;

ALTER TABLE locations
    ADD CONSTRAINT locations_shopify_location_state_check
        CHECK (shopify_location_state IN ('active', 'inactive', 'deleted'));
