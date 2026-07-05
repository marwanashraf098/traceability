-- V35: Bosta two-tier polling support
--
-- last_polled_at: when Tier 1 (status poll) last fetched this shipment from Bosta.
--   NULL = never polled. Used for round-robin rotation: ORDER BY last_polled_at ASC NULLS FIRST
--   ensures no shipment is starved if the in-flight set > max-per-cycle.
--
-- webhook_source enum: add bosta_poll (Tier 1) and bosta_poll_discovery (Tier 2) so
--   webhook_events.source correctly tags poll-originated synthetic events, keeping
--   them distinct from live webhooks ('bosta') and manual backfill ('bosta_backfill').

ALTER TABLE shipments
    ADD COLUMN last_polled_at timestamptz;

-- Partial index: only non-terminal shipments participate in Tier 1. Speeds the
--   ORDER BY last_polled_at ASC NULLS FIRST LIMIT N query in BostaStatusPollJob.
CREATE INDEX shipments_poll_order_idx
    ON shipments (tenant_id, last_polled_at ASC NULLS FIRST)
    WHERE internal_state NOT IN ('delivered','returned','lost','terminated','cancelled');

ALTER TYPE webhook_source ADD VALUE IF NOT EXISTS 'bosta_poll';
ALTER TYPE webhook_source ADD VALUE IF NOT EXISTS 'bosta_poll_discovery';
