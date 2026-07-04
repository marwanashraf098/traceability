-- Backfill tracking columns on courier_accounts.
-- last_backfill_at: when the most recent backfill run completed.
-- last_backfill_total: slim list items seen during the last run.
-- last_backfill_enqueued: deliveries for which a webhook_events row was created (fetched + enqueued).
ALTER TABLE courier_accounts
    ADD COLUMN last_backfill_at        timestamptz,
    ADD COLUMN last_backfill_total     int DEFAULT 0,
    ADD COLUMN last_backfill_enqueued  int DEFAULT 0;

-- Add bosta_backfill to webhook_source so BostaBackfillJob can tag synthesized events.
-- This distinguishes backfill-generated events from live Bosta webhook events in audit queries.
ALTER TYPE webhook_source ADD VALUE IF NOT EXISTS 'bosta_backfill';
