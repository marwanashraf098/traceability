-- L1 (approved by Marawan 2026-11-12) — the Traced Main Warehouse sells online from its first link.
--
-- At FIRST link (the Shopify location created, or an existing "Traced Main Warehouse" reused) Traced makes sure the
-- location fulfils online orders (read → locationEdit only when false → read back) and joins it to the default
-- delivery profile (ShopifyFulfillmentActivationService.joinWithReadBack). On a reconnect of an already-linked tenant
-- both are only READ: what Shopify says is recorded here and on V67's columns, and the connection card offers the
-- owner "Turn back on" — never a silent write.
--
-- shopify_online_fulfillment_status:
--   'unknown'  never checked (every row before L1 until its next connect / reconnect)
--   'on'       Shopify says the location fulfils online orders
--   'off'      Shopify says it doesn't (found on a reconnect read — the card warns, owner may turn it back on)
--   'error'    the read or the edit failed (the reason in _error) — never blocks signup or import
-- Plain columns on locations: no view selects them (merchant_orders / warehouse_orders are on orders), so no view is
-- recreated. locations already has RLS (tenant_isolation, NULLIF) and app_user's grants cover the new columns.
ALTER TABLE locations
    ADD COLUMN shopify_online_fulfillment_status text NOT NULL DEFAULT 'unknown'
        CHECK (shopify_online_fulfillment_status IN ('unknown', 'on', 'off', 'error')),
    ADD COLUMN shopify_online_fulfillment_error        text,
    ADD COLUMN shopify_online_fulfillment_checked_at   timestamptz,
    ADD COLUMN shopify_online_fulfillment_turned_on_at timestamptz,
    ADD COLUMN shopify_delivery_profile_checked_at     timestamptz;
