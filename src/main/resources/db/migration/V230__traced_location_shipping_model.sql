-- L1b-1 (approved by Marawan 2026-11-15) — which shipping model the store uses, so the Traced Main Warehouse is
-- never shown as shipping ("Ready") on a market-driven store just because a stale delivery-profile write "succeeded".
--
-- shopify_shipping_model:
--   'unknown'        never read, or the read failed (every row until its next first link / reconnect / hourly check)
--   'legacy'         ShopFeatures.marketDrivenShipping = false — delivery profiles are live; L1's join stays valid
--   'market_driven'  ShopFeatures.marketDrivenShipping = true — merchant delivery profiles are stale; Traced makes no
--                    delivery-profile call and can't confirm shipping (that needs read_markets, slice L1b-2)
-- Written only by TracedLocationOnlineService (first link, reconnect, the hourly location check, Turn back on), read
-- only through TracedShippingReadiness.SQL. Plain columns on locations: no view selects them; RLS and app_user's grants
-- on locations already cover them.
ALTER TABLE locations
    ADD COLUMN shopify_shipping_model text NOT NULL DEFAULT 'unknown'
        CHECK (shopify_shipping_model IN ('unknown', 'legacy', 'market_driven')),
    ADD COLUMN shopify_shipping_model_checked_at timestamptz;

-- L1b-1 review (Marawan 2026-11-15) — market-driven stores until L1b-2: the owner's "I've added it". Records that the
-- OWNER says Traced Main Warehouse is in the store's shipping rates; it hides the instruction (readiness
-- 'owner_confirmed', shown "Confirmed by you"). It is NOT Ready and never satisfies the move-feature gate, which needs a
-- status Traced verified itself. Written only by TracedLocationOnlineService.confirmShippingRates (owner-only endpoint).
ALTER TABLE locations
    ADD COLUMN shopify_shipping_owner_confirmed_at timestamptz,
    ADD COLUMN shopify_shipping_owner_confirmed_by uuid;
