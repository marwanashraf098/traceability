-- V135 — a Bosta rate limit reschedules a webhook event, never fails it (2026-10-04).
--
-- Prod 2026-10-04 00:28:59: one Bosta 429 (retry after 300 s) blocked the server's Bosta budget, and
-- the webhook job marked every event whose verify-by-fetch hit it 'failed' (78 new deliveries). A
-- failed event keeps its idempotency key, so the same (tracking, state, updatedAt) can never be
-- ingested again — the delivery stranded until Bosta changed it. BostaWebhookJob now keeps such an
-- event 'pending' and schedules it again after the retry-after (backoff, bounded):
--
--   rate_limit_retries    how many times this event was rescheduled for a rate limit
--   rate_limited_until    when its next attempt is due (informational; JobRunr holds the schedule)
--
-- Only after bosta.webhook.rate-limit-max-retries reschedules is the event marked failed.

ALTER TABLE webhook_events
    ADD COLUMN rate_limit_retries int NOT NULL DEFAULT 0,
    ADD COLUMN rate_limited_until timestamptz;
