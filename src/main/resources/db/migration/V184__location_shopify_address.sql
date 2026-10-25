-- Feature A (2026-10-25) — an Egyptian address on Traced's Shopify location.
--
-- Every Shopify location Traced created so far carries only address { countryCode: EG }.
-- MainLocationAddressService fills it (fill-only: an empty address, or one still exactly equal to
-- the address Traced last wrote; anything else is the merchant's and is never overwritten).
--
-- These columns record what Traced WROTE to Shopify and the outcome of the last check. They are
-- NOT the merchant-entered address (feature B owns those columns). No new table, so no new RLS
-- policy: locations' existing tenant_isolation policy covers every column added here.
--
--   shopify_address_written     the address Traced last wrote (jsonb: address1, address2, city,
--                               provinceCode, zip, countryCode) — the "still ours" comparison
--   shopify_address_written_at  when it was written
--   shopify_address_outcome     the last check's outcome (below)
--   shopify_address_dry_run     true when that outcome came from a dry run (nothing was written)
--   shopify_address_checked_at  when the outcome was recorded
--   shopify_address_error       for 'failed' only — the error message (no token, no address)

ALTER TABLE locations
    ADD COLUMN shopify_address_written    jsonb,
    ADD COLUMN shopify_address_written_at timestamptz,
    ADD COLUMN shopify_address_outcome    text,
    ADD COLUMN shopify_address_dry_run    boolean,
    ADD COLUMN shopify_address_checked_at timestamptz,
    ADD COLUMN shopify_address_error      text;

ALTER TABLE locations
    ADD CONSTRAINT locations_shopify_address_outcome_check CHECK (
        shopify_address_outcome IS NULL OR shopify_address_outcome IN
            ('filled', 'has_address', 'missing_scope', 'not_linked', 'failed', 'local_pickup_on'));
