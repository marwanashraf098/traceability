-- Lookup adjustments ↔ Shopify (approved 2026-10-10, D1–D11). Columns and values on the existing
-- claim table — RLS and its tenant_isolation policy are unchanged (V48).
--
-- Piece claims (the "piece sync" engine in ShopifyInventoryService): void_correction, hold_enter,
-- hold_exit, damage_move, and the new piece_write_off (−1), piece_write_off_return (+1),
-- damage_restore (move damaged → available) and damaged_restore_increment (+1). Each is claimed
-- 'queued' (or 'skipped' with a skip_reason) INSIDE the transaction that writes its piece event,
-- then sent once after commit:
--   queued / failed → pending (send_started_at, attempt_count + 1) → applied
--                                                                  | failed            (definite — the sweep re-sends, ≤ 5 attempts)
--                                                                  | failed_ambiguous  (never re-sent, by the sweep or by hand)
--   cancelled             — a departure claim not yet sent when the piece came back to good
--   superseded_by_repair  — a legacy row replaced by the 2026-10-10 repair script's new claim
--
-- piece_sync_cutoff(): the moment this migration ran. Piece claims created before it are legacy —
-- the piece-sync sweep and the manual repush never touch them (D9).

ALTER TABLE shopify_inventory_adjustments DROP CONSTRAINT shopify_inventory_adjustments_status_check;
ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_status_check
    CHECK (status IN ('shadow', 'pending', 'applied', 'failed', 'skipped', 'superseded_by_seed',
                      'skipped_shopify_restocked', 'skipped_not_fulfillment_location',
                      'queued', 'failed_ambiguous', 'cancelled', 'superseded_by_repair'));

ALTER TABLE shopify_inventory_adjustments DROP CONSTRAINT shopify_inventory_adjustments_trigger_type_check;
ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_trigger_type_check
    CHECK (trigger_type IN ('receiving_session', 'return_inspection', 'damage_move', 'initial_seed',
                            'void_correction', 'hold_enter', 'hold_exit', 'exchange_dispatch',
                            'stock_take_found', 'transfer_return',
                            'piece_write_off', 'piece_write_off_return', 'damage_restore',
                            'damaged_restore_increment'));

ALTER TABLE shopify_inventory_adjustments
    ADD COLUMN skip_reason     text        NULL,
    ADD COLUMN send_started_at timestamptz NULL;

-- A piece claim that was skipped always says why (never a silent skip). NOT VALID: rows written
-- before this migration (void's old 'skipped' rows carry their reason in error) are not checked.
ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_piece_skip_reason_check
    CHECK (status <> 'skipped' OR skip_reason IS NOT NULL
           OR trigger_type NOT IN ('void_correction', 'hold_enter', 'hold_exit', 'damage_move',
                                   'piece_write_off', 'piece_write_off_return', 'damage_restore',
                                   'damaged_restore_increment'))
    NOT VALID;

DO $$
BEGIN
    EXECUTE format(
        'CREATE FUNCTION piece_sync_cutoff() RETURNS timestamptz LANGUAGE sql IMMUTABLE PARALLEL SAFE AS %L',
        format('SELECT %L::timestamptz', now()));
END $$;

CREATE INDEX idx_sia_piece_sync_open
    ON shopify_inventory_adjustments (tenant_id, created_at)
 WHERE status IN ('queued', 'pending', 'failed')
   AND trigger_type IN ('void_correction', 'hold_enter', 'hold_exit', 'damage_move',
                        'piece_write_off', 'piece_write_off_return', 'damage_restore',
                        'damaged_restore_increment');
