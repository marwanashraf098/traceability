-- V136 — Shopify webhook events: automatic retry with ordering safety (2026-10-04).
--
-- Before: a processing failure was stored in process_error and the JobRunr job ended normally —
-- nothing retried it, and Shopify never redelivers (we always answer 200). An event whose enqueue
-- never happened sat unprocessed forever.
--
--   retry_count     reprocessing attempts (sweeper / re-process claims)
--   next_retry_at   when the sweeper may try a failed event again (backoff); NULL = not scheduled
--   superseded_at   set instead of applying an event when newer data for the same order / product was
--                   already applied (a later event processed, or Traced's stored copy is newer) — an
--                   old payload must never overwrite a newer one; processed_at is set too
--
-- Legacy failures (process_error set before this migration, next_retry_at NULL) are never swept
-- automatically: the explicit re-process handles them (dry run first).

ALTER TABLE shopify_webhook_events
    ADD COLUMN retry_count   int NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at timestamptz,
    ADD COLUMN superseded_at timestamptz;

CREATE INDEX shopify_webhook_events_retry_due_idx
    ON shopify_webhook_events (next_retry_at)
    WHERE processed_at IS NULL AND next_retry_at IS NOT NULL;

CREATE INDEX shopify_webhook_events_unprocessed_idx
    ON shopify_webhook_events (received_at)
    WHERE processed_at IS NULL AND process_error IS NULL;
