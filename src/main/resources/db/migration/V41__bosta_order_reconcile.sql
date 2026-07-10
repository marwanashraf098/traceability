-- ============================================================
-- V41 — Bosta order reconcile: retry counter + 'not_created' flag
-- ============================================================
-- Tracks how many reconcile cycles checked an order for a Bosta delivery
-- that hasn't appeared yet. After N failures the order is flagged as
-- 'not_created' so the merchant sees a distinct badge and can act.
--
-- bosta_link_attempts   — count of cycles that found no matching delivery
-- bosta_link_last_check — timestamp of the last reconcile check (prevents
--                         re-processing the same order twice per job run)
-- bosta_link_status     — null = still trying; 'not_created' = gave up after N
--                         The flag is cleared automatically when any path
--                         (webhook, backfill, reconcile) successfully links a
--                         Bosta delivery to the order.

ALTER TABLE orders
    ADD COLUMN bosta_link_attempts   integer     NOT NULL DEFAULT 0,
    ADD COLUMN bosta_link_last_check timestamptz,
    ADD COLUMN bosta_link_status     text;

-- Sparse index covering the exceptions page query and order-list filter.
-- Only flagged orders land here (< 1% of rows in normal operation).
CREATE INDEX orders_bosta_not_created_idx ON orders (tenant_id, placed_at DESC)
    WHERE bosta_link_status = 'not_created';
