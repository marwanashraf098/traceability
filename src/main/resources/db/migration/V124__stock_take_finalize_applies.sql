-- V124 — Stock-take finalize applies the count (Marawan, 2026-10-01).
--
-- 1. stock_take_shopify_syncs.status
--      nothing_to_push     finalize wrote off nothing that reaches Shopify (was 'pushed' with an
--                          empty payload and pushed_at NULL — a no-op that looked like a push)
--      superseded_by_seed  the initial seed pushed current on-hand (which already reflects these
--                          write-offs) before this claim applied — never pushed afterwards
--    superseded_at records when. pushed_at stays set only by a real push (or the operator's
--    "it was applied" on a failed_ambiguous claim).
-- 2. shopify_inventory_adjustments.trigger_type gains 'stock_take_found' — the 4th increment
--    trigger (+1 at the Traced location for a piece found in a stock take whose earlier stock-take
--    write-off was pushed to Shopify). Approved 2026-10-01.

ALTER TABLE stock_take_shopify_syncs
    DROP CONSTRAINT stock_take_shopify_syncs_status_check;

ALTER TABLE stock_take_shopify_syncs
    ADD CONSTRAINT stock_take_shopify_syncs_status_check
    CHECK (status IN ('pending', 'pushed', 'failed', 'failed_ambiguous', 'nothing_to_push', 'superseded_by_seed'));

ALTER TABLE stock_take_shopify_syncs
    ADD COLUMN superseded_at timestamptz NULL;

ALTER TABLE shopify_inventory_adjustments
    DROP CONSTRAINT shopify_inventory_adjustments_trigger_type_check;

ALTER TABLE shopify_inventory_adjustments
    ADD CONSTRAINT shopify_inventory_adjustments_trigger_type_check
    CHECK (trigger_type IN (
        'receiving_session', 'return_inspection', 'damage_move', 'initial_seed',
        'void_correction', 'hold_enter', 'hold_exit', 'exchange_dispatch', 'stock_take_found'
    ));
