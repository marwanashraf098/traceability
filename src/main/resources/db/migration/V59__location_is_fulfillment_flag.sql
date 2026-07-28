-- Location Shopify-sync scoping flag (shadow mode, Part 1 of the
-- Committed-inventory build). Distinct from shopify_sync_status/
-- shopify_location_id (V48) — those track whether a location has been
-- technically linked to a real Shopify Location object. is_fulfillment
-- is a business flag: does this location's stock count toward the
-- shadow inventory number, independent of link state. Must be true for
-- today's Main Warehouse even though it may not be Shopify-linked yet.
ALTER TABLE locations
    ADD COLUMN is_fulfillment boolean NOT NULL DEFAULT false;

-- Backfill: every existing tenant's default location becomes the
-- fulfillment location. Runs as the Flyway owner (postgres, BYPASSRLS)
-- so it touches all tenants regardless of FORCE ROW LEVEL SECURITY.
UPDATE locations SET is_fulfillment = true WHERE is_default = true;
