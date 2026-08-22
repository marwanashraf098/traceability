-- ============================================================
-- V82 — FR-13.x: Void / Hold trigger types on shopify_inventory_adjustments
--
-- Adds three new trigger_type values (see V60 for the same drop/re-add
-- pattern used for damage_move/initial_seed):
--   void_correction — decrement via ShopifyGateway.pushVoidCorrection()
--   hold_enter      — decrement via ShopifyGateway.pushHoldEnter()
--   hold_exit       — increment via the EXISTING positive path
--                     (applyIncrementAdjustment) — reuses this table purely
--                     for its claim-before-call idempotency, same as
--                     receiving_session/return_inspection.
--
-- Also adds 'skipped' to status: void's decrement is conditional (only
-- fires if the piece's originating receiving increment actually applied)
-- and the skip case is still recorded here for audit, per FR-13.x.
-- ============================================================

ALTER TABLE shopify_inventory_adjustments
    DROP CONSTRAINT shopify_inventory_adjustments_trigger_type_check;

ALTER TABLE shopify_inventory_adjustments
    ADD CONSTRAINT shopify_inventory_adjustments_trigger_type_check
    CHECK (trigger_type IN (
        'receiving_session', 'return_inspection', 'damage_move', 'initial_seed',
        'void_correction', 'hold_enter', 'hold_exit'
    ));

ALTER TABLE shopify_inventory_adjustments
    DROP CONSTRAINT shopify_inventory_adjustments_status_check;

ALTER TABLE shopify_inventory_adjustments
    ADD CONSTRAINT shopify_inventory_adjustments_status_check
    CHECK (status IN ('shadow', 'pending', 'applied', 'failed', 'skipped'));
