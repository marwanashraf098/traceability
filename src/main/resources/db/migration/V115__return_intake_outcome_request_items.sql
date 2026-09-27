-- ============================================================
-- V115 — Step 6b: a return leg's intake completed by its request's items
--
-- 'request_items_arrived': the return request linked to this courier-return leg has no
-- item still awaited after a per-item "Arrived" action (drawer or return session). Stamped
-- by ReturnRequestLifecycle with the acting user; an undo that puts an item back to awaiting
-- clears it. Unlike 'received_untracked' it raises no leg-level return_to_receive exception
-- (the item-level request_item_to_receive already covers stock).
-- ============================================================

ALTER TABLE shipments DROP CONSTRAINT shipments_return_intake_outcome_check;
ALTER TABLE shipments ADD CONSTRAINT shipments_return_intake_outcome_check
    CHECK (return_intake_outcome IN ('scanned', 'received_untracked', 'request_items_arrived'));
