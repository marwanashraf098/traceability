-- L1b-2 (approved by Marawan 2026-11-16) — on a market-driven store Traced READS whether the Traced Main Warehouse is
-- in an active shipping rate of the store's PRIMARY market (Shopify 2026-07 Markets API, optional scope read_markets),
-- so readiness can be Ready without the owner's word. Traced never edits shipping rates or delivery profiles.
--
-- shopify_market_shipping_status:
--   NULL           never read, or the last read gave no verdict (primary market not identified, a list not read in
--                  full, a market inheriting its settings) — readiness keeps the owner-confirmed path
--   'covered'      an active rate of the primary market lists Traced Main's location id
--   'not_covered'  every rate of the primary market was read in full and none lists it — beats the owner's word
--   'no_access'    read_markets isn't granted (or Shopify answered ACCESS_DENIED) — owner-confirmed path
-- A failed read (transport / HTTP / GraphQL error) changes nothing. Written only by TracedLocationOnlineService; read
-- through TracedShippingReadiness.SQL. _covered_by = "<market> — <rate>" (merchant config, no PII); _missing = names
-- of the store's OTHER active markets whose rates don't list Traced Main (info only, never a blocker).
-- _check_claimed_at: the owner / manager "Check again" claim (one per 2 minutes per tenant, conditional UPDATE).
-- shopify_delivery_profile_id: the default ("General") profile's id from the legacy membership READ — the deep link to
-- that profile in Shopify. Plain columns on locations: no view selects them; RLS and app_user's grants already cover them.
ALTER TABLE locations
    ADD COLUMN shopify_market_shipping_status text
        CHECK (shopify_market_shipping_status IN ('covered', 'not_covered', 'no_access')),
    ADD COLUMN shopify_market_shipping_checked_at timestamptz,
    ADD COLUMN shopify_market_shipping_covered_by text,
    ADD COLUMN shopify_market_shipping_missing text[],
    ADD COLUMN shopify_shipping_check_claimed_at timestamptz,
    ADD COLUMN shopify_delivery_profile_id text;
