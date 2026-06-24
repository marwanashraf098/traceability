-- V29: Returns-receiving session + customer-return guard + stuck-in-transit detector.

-- 1. Session-kind discriminator on receipts.
--    'inbound' covers all existing rows (receiving sessions).
--    'returns' marks the new returns-receiving sessions.
ALTER TABLE receipts
    ADD COLUMN kind text NOT NULL DEFAULT 'inbound'
    CONSTRAINT receipts_kind_check CHECK (kind IN ('inbound', 'returns'));

-- 2. Per-tenant customer-return window.
--    delivered → return_pending_inspection is rejected outside this window.
--    Default 30 days — generous for pilot.
ALTER TABLE tenants
    ADD COLUMN customer_return_window_days integer NOT NULL DEFAULT 30;

-- 3. Per-tenant threshold for the new return_in_transit_stuck detector (15th).
ALTER TABLE tenants
    ADD COLUMN return_in_transit_stuck_days integer NOT NULL DEFAULT 3;

-- Index: returns sessions list sorted by created_at.
CREATE INDEX receipts_kind_tenant_idx ON receipts (tenant_id, kind, created_at DESC);
