-- V22: FR-9 cleanup — derive COD on read + order metadata for convert-to-self-pickup audit

-- Item 1: Drop the stored total_cod_amount column from pickups.
-- COD totals are now computed live from SUM(shipments.cod_amount) via pickup_shipments.
-- This removes the stale-total risk when shipments are removed from a manifest.
ALTER TABLE pickups DROP COLUMN IF EXISTS total_cod_amount;

-- Item 4/5: Order-level metadata for privileged-action audit records.
-- convertToSelfPickup() writes a JSON record here (type, reason, actor, previous_status, converted_at).
-- Also foundation for FR-2.6 once a dedicated audit table is built.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS metadata jsonb;
