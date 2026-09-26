-- ============================================================
-- V110 — Step 5a: 'exchange_dispatch' trigger type (approved 2026-09-26)
--
-- The fourth member of the named Shopify decrement set: -1 per replacement piece of an
-- internal exchange order ('internal:exchange:%'), claimed once per piece (trigger_id =
-- piece id) when it first leaves Traced custody. Widens the trigger_type CHECK only —
-- same shape as V60 / V82. Nothing else changes.
-- ============================================================

ALTER TABLE shopify_inventory_adjustments
    DROP CONSTRAINT shopify_inventory_adjustments_trigger_type_check;

ALTER TABLE shopify_inventory_adjustments
    ADD CONSTRAINT shopify_inventory_adjustments_trigger_type_check
    CHECK (trigger_type IN (
        'receiving_session', 'return_inspection', 'damage_move', 'initial_seed',
        'void_correction', 'hold_enter', 'hold_exit', 'exchange_dispatch'
    ));
