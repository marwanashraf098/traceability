-- Lookup "Sold" (C1, 2026-10-25): a piece sold outside Shopify is marked available → sold in Lookup
-- (event sold_offline) and "Not sold" reverses it (sold → available, event not_sold). Two new piece-sync
-- claims on the existing claim table — RLS and its tenant_isolation policy are unchanged (V48):
--   piece_sold         −1 at the Traced Main Warehouse (pushPieceSold — a named decrement), keyed
--                      piece:sold_event_id, decided by PieceShopifyRules.countedAtMain
--   piece_sold_return  +1 (Not sold), keyed piece:restore_event_id, only when the piece_sold −1 applied
--                      (PieceShopifyRules.departureReached — the same rule as Found it)
-- Both are claimed 'queued' (or 'skipped' + skip_reason) inside the adjust transaction and sent once
-- after commit, exactly like piece_write_off / piece_write_off_return (V161).
--
-- Each constraint is re-created with V161's list plus the two new trigger types; nothing else changes.

ALTER TABLE shopify_inventory_adjustments DROP CONSTRAINT shopify_inventory_adjustments_trigger_type_check;
ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_trigger_type_check
    CHECK (trigger_type IN ('receiving_session', 'return_inspection', 'damage_move', 'initial_seed',
                            'void_correction', 'hold_enter', 'hold_exit', 'exchange_dispatch',
                            'stock_take_found', 'transfer_return',
                            'piece_write_off', 'piece_write_off_return', 'damage_restore',
                            'damaged_restore_increment',
                            'piece_sold', 'piece_sold_return'));

-- Same NOT VALID shape as V161 (rows written before V161 are not checked).
ALTER TABLE shopify_inventory_adjustments DROP CONSTRAINT shopify_inventory_adjustments_piece_skip_reason_check;
ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_piece_skip_reason_check
    CHECK (status <> 'skipped' OR skip_reason IS NOT NULL
           OR trigger_type NOT IN ('void_correction', 'hold_enter', 'hold_exit', 'damage_move',
                                   'piece_write_off', 'piece_write_off_return', 'damage_restore',
                                   'damaged_restore_increment',
                                   'piece_sold', 'piece_sold_return'))
    NOT VALID;

-- The open-claims partial index (sweep / repush / alert). Small: only queued / pending / failed piece
-- claims qualify. Plain CREATE INDEX — CONCURRENTLY hangs under Flyway's transactional lock.
DROP INDEX idx_sia_piece_sync_open;
CREATE INDEX idx_sia_piece_sync_open
    ON shopify_inventory_adjustments (tenant_id, created_at)
 WHERE status IN ('queued', 'pending', 'failed')
   AND trigger_type IN ('void_correction', 'hold_enter', 'hold_exit', 'damage_move',
                        'piece_write_off', 'piece_write_off_return', 'damage_restore',
                        'damaged_restore_increment',
                        'piece_sold', 'piece_sold_return');
