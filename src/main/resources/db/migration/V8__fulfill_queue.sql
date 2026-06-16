-- V8__fulfill_queue.sql
-- Adds worker-locking columns to orders for pick-queue assignment (FR-8.1).
ALTER TABLE orders
    ADD COLUMN locked_by uuid REFERENCES users(id),
    ADD COLUMN locked_at timestamptz;

CREATE INDEX orders_locked_by ON orders (tenant_id, locked_by)
    WHERE locked_by IS NOT NULL;
