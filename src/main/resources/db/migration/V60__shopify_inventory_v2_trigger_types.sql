-- FR-17 v2 (increment-only sync): the audit table gets two new trigger types.
--   damage_move    — trigger 3, a sellable piece damaged in the warehouse
--                     (inventoryMoveQuantities available->damaged).
--   initial_seed   — Part C one-time positive seed of the empty Traced location.
-- receiving_session / return_inspection are unchanged (triggers 1 and 2).
ALTER TABLE shopify_inventory_adjustments
    DROP CONSTRAINT shopify_inventory_adjustments_trigger_type_check;

ALTER TABLE shopify_inventory_adjustments
    ADD CONSTRAINT shopify_inventory_adjustments_trigger_type_check
    CHECK (trigger_type IN ('receiving_session', 'return_inspection', 'damage_move', 'initial_seed'));
