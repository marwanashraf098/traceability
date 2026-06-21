-- FR-4.6 prerequisite: track whether linkByAwbScan() failed to fetch
-- the Bosta internal _id after AWB link.  When true + provider_delivery_id IS NULL,
-- surfaces as MISSING_PROVIDER_ID exception so it can be retried or resolved.
ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS provider_id_fetch_failed boolean NOT NULL DEFAULT false;

-- Backfill existing Mode-B rows: extract _id from the raw Bosta data-node.
-- Scan-linked rows have raw IS NULL so they are naturally skipped here.
UPDATE shipments
SET provider_delivery_id = raw->>'_id'
WHERE provider_delivery_id IS NULL
  AND raw IS NOT NULL
  AND raw->>'_id' IS NOT NULL
  AND raw->>'_id' != '';
