-- ============================================================
-- V174 — Shopify order edits reach line quantities (2026-10-21).
--
-- order_items.quantity now follows Shopify's CURRENT quantity until the order leaves Traced custody
-- (OrderLineFreeze). Shopify keeps a line removed by an order edit in the order, with current_quantity 0,
-- so a removed line is KEPT with quantity 0 — never deleted: allocations, return_request_items and
-- untracked_unit_intakes reference order_items(id), and analytics join refunds to the line.
--
-- Only the CHECK changes: quantity > 0 → quantity >= 0. Lock safety (prod, ~14k rows): the DROP and the
-- NOT VALID ADD each take a brief ACCESS EXCLUSIVE lock with no scan; VALIDATE scans under SHARE UPDATE
-- EXCLUSIVE (reads and writes continue). Every existing row is > 0, so it validates. merchant_orders is a
-- view on orders, not order_items — untouched.
-- ============================================================

ALTER TABLE order_items DROP CONSTRAINT order_items_quantity_check;

ALTER TABLE order_items ADD CONSTRAINT order_items_quantity_check CHECK (quantity >= 0) NOT VALID;

ALTER TABLE order_items VALIDATE CONSTRAINT order_items_quantity_check;
