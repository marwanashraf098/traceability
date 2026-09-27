-- ============================================================
-- V114 — Step 6a: return request items for UNTRACKED order lines
--
-- An untracked line is an order_items row with NO allocations row of any status (the
-- per-line form of ShipmentLinkService.orderUntrackedSql). Customers can now return or
-- exchange such lines through the portal. There is no piece to bind, so an untracked item
-- binds one UNIT of the order line instead: (order_item_id, unit_no), one row per unit —
-- every count over request items stays COUNT(*).
--
-- piece_id becomes NULLable; exactly one binding is set (a piece, or an order line + unit
-- number ≥ 1). A unit is in at most one live request (partial UNIQUE, like the existing
-- per-piece index, which stays). arrived_condition / arrived_by record the per-item
-- "Arrived" action (NULL arrived_by = the system). No pieces are created or moved and no
-- stock changes here: an untracked returned item only enters stock through Receiving.
-- ============================================================

ALTER TABLE return_request_items
    ALTER COLUMN piece_id DROP NOT NULL,
    ADD COLUMN order_item_id     uuid     REFERENCES order_items(id),
    ADD COLUMN unit_no           smallint,
    ADD COLUMN arrived_condition text
        CONSTRAINT return_request_items_arrived_condition_check
        CHECK (arrived_condition IN ('sellable', 'damaged')),
    ADD COLUMN arrived_by        uuid     REFERENCES users(id),
    ADD CONSTRAINT return_request_items_binding_check CHECK (
        (piece_id IS NOT NULL AND order_item_id IS NULL AND unit_no IS NULL)
        OR (piece_id IS NULL AND order_item_id IS NOT NULL AND unit_no IS NOT NULL AND unit_no >= 1));

CREATE UNIQUE INDEX return_request_items_one_active_per_unit
    ON return_request_items (order_item_id, unit_no) WHERE active;

CREATE INDEX return_request_items_order_item_idx
    ON return_request_items (order_item_id) WHERE order_item_id IS NOT NULL;
