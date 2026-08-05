-- FR-17 v2 — fulfillment activation (deliveryProfileUpdate).
--
-- Distinct milestone from shopify_sync_status/shopify_location_id (V48): those track whether
-- the Shopify Location OBJECT exists and is linked. This tracks whether that location has
-- ALSO been joined to the shop's default delivery profile's location group — the step that
-- makes it live to the storefront (Shopify sums on_hand across every location in a product's
-- delivery profile). A location can sit linked-but-not-activated indefinitely; activation is
-- a deliberate, separately-gated action (see ShopifyFulfillmentActivationService — not yet
-- wired to any automatic trigger as of this migration).
ALTER TABLE locations
    ADD COLUMN shopify_delivery_profile_status text NOT NULL DEFAULT 'not_activated'
        CHECK (shopify_delivery_profile_status IN ('not_activated', 'activated', 'error')),
    ADD COLUMN shopify_delivery_profile_error        text,
    ADD COLUMN shopify_delivery_profile_activated_at timestamptz;
