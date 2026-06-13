-- Add Shopify line-item GID to order_items for idempotent upserts.
-- external_id = "gid://shopify/LineItem/123" — stable across re-imports.
--
-- Nullable so manually created items (not from Shopify) can coexist.
-- Partial unique index enforces uniqueness only where external_id is set;
-- PostgreSQL allows multiple NULLs in a regular UNIQUE constraint, but the
-- partial index makes the ON CONFLICT target explicit and unambiguous.
--
-- Hard-deletes of removed Shopify line items are a D6 webhook concern,
-- not the importer's: the importer only adds/updates lines via ON CONFLICT,
-- never removes them. A picker working against a stale line is caught by
-- the scan-race guard (piece is not allocated to that order).

ALTER TABLE order_items ADD COLUMN external_id text;

CREATE UNIQUE INDEX order_items_order_external_unique
    ON order_items (order_id, external_id)
    WHERE external_id IS NOT NULL;
