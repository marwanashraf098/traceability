-- V157 — Transfers sync with Shopify (2026-10-08 diagnosis, Issue 2; approved: model (b), sync by
-- custody; a FIFTH named decrement, pushTransferOut; per-location mode 'remove' | 'leave'; no
-- mirroring to other Shopify locations — Traced still writes only to the Traced Main Warehouse).
--
-- 1. locations.shopify_sync_mode — what happens to Shopify while stock sits at this (non-main)
--    location: 'remove' (default — the units leave the Traced Main Warehouse count when they are
--    sent here) or 'leave' (Shopify unchanged). Not used for the main warehouse itself.
-- 2. transfers.shopify_sync_mode — that mode, SNAPSHOT at send time, so changing the setting
--    mid-transfer never makes the −N and the later +1s asymmetric. NULL = not yet sent / n/a.
-- 3. transfer_pieces.from_location_id — where the piece was when it was scanned onto the transfer
--    (scanOut / returnScanOut). Only pieces that left the MAIN warehouse are decremented.
-- 4. transfer_shopify_syncs — ONE claim row per transfer for its send-time decrement (claim-before-
--    call): 'queued' (claimed, waiting to be sent) → 'pending' (send started) → 'pushed' | 'failed'
--    (definitive rejection — nothing applied) | 'failed_ambiguous' (no confirmed response — NEVER
--    re-sent automatically; a person verifies in Shopify). 'skipped' rows record WHY nothing was
--    sent (reason). deltas = {variantId: units}, piece_ids = the pieces counted.
-- 5. shopify_inventory_adjustments: the per-piece +1 when a piece comes back to the main
--    warehouse is a normal increment claim, trigger 'transfer_return', key piece_id:transfer_id.

ALTER TABLE locations ADD COLUMN shopify_sync_mode text NOT NULL DEFAULT 'remove'
    CHECK (shopify_sync_mode IN ('remove', 'leave'));

ALTER TABLE transfers ADD COLUMN shopify_sync_mode text
    CHECK (shopify_sync_mode IN ('remove', 'leave'));

ALTER TABLE transfer_pieces ADD COLUMN from_location_id uuid REFERENCES locations(id);

CREATE TABLE transfer_shopify_syncs (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenants(id),
    transfer_id      uuid        NOT NULL REFERENCES transfers(id),
    location_id      uuid        REFERENCES locations(id),
    status           text        NOT NULL
        CHECK (status IN ('queued', 'pending', 'pushed', 'failed', 'failed_ambiguous', 'skipped')),
    reason           text,
    source           text        NOT NULL DEFAULT 'send' CHECK (source IN ('send', 'repair')),
    deltas           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    piece_ids        jsonb       NOT NULL DEFAULT '[]'::jsonb,
    attempt_count    int         NOT NULL DEFAULT 0,
    error            text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    send_started_at  timestamptz,
    pushed_at        timestamptz,
    CHECK (status <> 'skipped' OR reason IS NOT NULL),
    CONSTRAINT transfer_shopify_syncs_one_per_transfer UNIQUE (transfer_id)
);

CREATE INDEX ix_transfer_shopify_syncs_open
    ON transfer_shopify_syncs (tenant_id, status, created_at)
    WHERE status IN ('queued', 'pending', 'failed', 'failed_ambiguous');

ALTER TABLE transfer_shopify_syncs ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfer_shopify_syncs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transfer_shopify_syncs
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- A claim is a record of what was (or wasn't) sent: app_user never deletes one.
REVOKE DELETE, TRUNCATE ON transfer_shopify_syncs FROM app_user;

ALTER TABLE shopify_inventory_adjustments DROP CONSTRAINT shopify_inventory_adjustments_trigger_type_check;
ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_trigger_type_check
    CHECK (trigger_type IN ('receiving_session', 'return_inspection', 'damage_move', 'initial_seed',
                            'void_correction', 'hold_enter', 'hold_exit', 'exchange_dispatch',
                            'stock_take_found', 'transfer_return'));
