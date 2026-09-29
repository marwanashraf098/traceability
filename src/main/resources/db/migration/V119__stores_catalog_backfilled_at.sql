-- One-time catalog backfill marker (every Shopify product status is imported since
-- 2026-09-29). Set by a successful ShopifyImportJob run (connect / reconnect / sync) and by
-- CatalogBackfillJob; NULL on a connected store means its catalog still needs the
-- all-statuses re-import. Column only — the existing stores RLS policy is unchanged.
ALTER TABLE stores ADD COLUMN catalog_backfilled_at timestamptz NULL;
