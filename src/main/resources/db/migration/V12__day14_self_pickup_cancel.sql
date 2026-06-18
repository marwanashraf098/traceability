-- V12__day14_self_pickup_cancel.sql
-- Self-pickup + cancellation flows (FR-9.9–9.13)

-- New order status: packed self-pickup orders waiting for customer collection
-- Note: ALTER TYPE ADD VALUE can run in a transaction on PostgreSQL 12+
-- but the new value is not usable within the same transaction.
-- This migration only adds the value; no DML in V12 uses it.
ALTER TYPE order_status ADD VALUE IF NOT EXISTS 'self_pickup_pending';

-- Flag: set on the order to skip AWB-link and enable direct handover
ALTER TABLE orders
    ADD COLUMN is_self_pickup boolean NOT NULL DEFAULT false;

-- Tracks post-pack cancellation requests (guided-unpack flow).
-- Non-null → exceptions center surfaces as 'guided_unpack' HIGH.
-- Cleared when all pieces are physically unpacked and order → cancelled.
ALTER TABLE orders
    ADD COLUMN cancel_requested_at timestamptz;

-- Partial index for the guided_unpack exception detector
CREATE INDEX orders_cancel_requested
    ON orders (tenant_id)
    WHERE cancel_requested_at IS NOT NULL;
