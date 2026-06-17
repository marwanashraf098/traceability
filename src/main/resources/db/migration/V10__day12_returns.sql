-- ============================================================
-- V10 — Day 12: Returns intake + resolution (FR-12)
-- ============================================================

-- Per-tenant window for the never-received detector (FR-12.4).
-- Default 3 days: if Bosta marks a shipment returned but pieces
-- are not scanned back in within this window, they surface in the
-- never-received report.
ALTER TABLE tenants
    ADD COLUMN never_received_window_days integer NOT NULL DEFAULT 3;

-- Clock column for the never-received detector.
-- Set by BostaWebhookJob when it processes state 46 (returned_to_business).
-- NULL = shipment has not yet been confirmed returned by Bosta.
ALTER TABLE shipments
    ADD COLUMN returned_at timestamptz;

-- Index for the never-received detector: find returned shipments whose
-- window has expired without intake.
CREATE INDEX shipments_returned_at_idx
    ON shipments (tenant_id, returned_at)
    WHERE internal_state = 'returned' AND returned_at IS NOT NULL;

-- Index to quickly find return_received events per piece when evaluating
-- the NOT EXISTS clause in the never-received detector query.
CREATE INDEX piece_events_return_received_idx
    ON piece_events (tenant_id, piece_id, event_type)
    WHERE event_type = 'return_received';
