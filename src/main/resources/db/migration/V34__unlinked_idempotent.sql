-- V34: make unlinked_bosta_deliveries inserts idempotent.
--
-- Root cause: BostaWebhookJob.recordUnlinked() inserts a new row every time
-- a delivery can't be matched. When the dedup check (step 4) also runs outside
-- a transaction (no GUC → RLS filters webhook_events → COUNT always 0 → always
-- re-processes), backfill runs produce one unlinked row per run for the same
-- tracking number. This migration:
--
--   1. Deduplicates existing rows — keeps the most-recent active row per
--      (tenant_id, tracking_number), discards extras.
--   2. Adds a UNIQUE partial index so INSERT ... ON CONFLICT upserts work,
--      preventing future duplicates even if the dedup-check GUC bug recurs.

-- Step 1: remove duplicate active rows, keeping the most recent by last_seen_at.
DELETE FROM unlinked_bosta_deliveries
WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id, tracking_number
                   ORDER BY last_seen_at DESC
               ) AS rn
        FROM unlinked_bosta_deliveries
        WHERE resolved = false
    ) ranked
    WHERE rn > 1
);

-- Step 2: unique partial index — enforces at most one ACTIVE row per delivery.
-- Resolved rows are excluded so the same tracking can be re-opened after resolution.
CREATE UNIQUE INDEX uix_unlinked_active_per_tracking
    ON unlinked_bosta_deliveries (tenant_id, tracking_number)
    WHERE resolved = false;
