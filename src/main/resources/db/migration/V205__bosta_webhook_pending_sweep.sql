-- V205 — Bosta webhook events left 'pending' are re-enqueued by a sweeper (2026-11-05).
--
-- POST /webhooks/bosta commits the webhook_events row, then enqueues BostaWebhookJob. When that enqueue
-- fails or times out (JobRunr's owner pool exhausted, 2026-11-04 stall) the row stays 'pending' and nothing
-- ever processed it. BostaWebhookPendingSweeper now re-enqueues rows pending > 10 min (48 h window):
--
--   sweep_count        how many times the sweeper re-enqueued this row (capped)
--   sweep_enqueued_at  lease: when the sweeper last enqueued it — not taken again for 30 min
--
-- Both columns are metadata-only adds (constant default) — no table rewrite, no long lock.

ALTER TABLE webhook_events
    ADD COLUMN sweep_count       int NOT NULL DEFAULT 0,
    ADD COLUMN sweep_enqueued_at timestamptz;
