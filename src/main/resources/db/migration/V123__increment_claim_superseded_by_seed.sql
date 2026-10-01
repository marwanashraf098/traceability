-- V123 — An increment claim the initial seed made redundant: status 'superseded_by_seed'.
--
-- The seed (ShopifyInventoryReconcileService.apply) pushes Traced's CURRENT on-hand for a variant
-- at the Traced location. An increment claim (receiving_session / return_inspection / hold_exit)
-- that never applied and was created before the seed's on-hand read is already accounted for by
-- that push (or its pieces have since left on-hand) — retrying it would double count. The seed
-- marks such claims superseded in the same transaction as its own writes; the retry job, the
-- manual repush and claim()'s failed-only reclaim never touch them again, and every
-- inventory_increment_sync_failed predicate (status = 'failed') drops them, so their alerts
-- resolve on their own.
--   superseded_at   when the seed superseded the claim (NULL otherwise)

ALTER TABLE shopify_inventory_adjustments
    DROP CONSTRAINT shopify_inventory_adjustments_status_check;

ALTER TABLE shopify_inventory_adjustments
    ADD CONSTRAINT shopify_inventory_adjustments_status_check
    CHECK (status IN ('shadow', 'pending', 'applied', 'failed', 'skipped', 'superseded_by_seed'));

ALTER TABLE shopify_inventory_adjustments
    ADD COLUMN superseded_at timestamptz NULL;
