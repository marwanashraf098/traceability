-- V245 — Bazar top-up (approved 2026-11-27, Step 0 design B): add pieces to a LIVE bazar after Mark sent, on the SAME
--   bazar transfer, each top-up its own batch.
--
-- 1. transfer_send_batches — one top-up of a sent bazar transfer. batch_no 2, 3, … (the first send is batch 1 and has
--    no row). 'open' = a LIST only: nothing moved yet (the pieces are still available at their place, sellable online);
--    'sent' = every listed piece was claimed through TransferService.claimAndSend in ONE transaction, with the batch's
--    own Shopify claim; 'discarded' = the list was dropped, nothing ever moved. At most one open batch per transfer.
-- 2. transfer_send_batch_items — the listed pieces. Never deleted: taking a piece off the list stamps removed_at (with
--    the request id, so a retry is a no-op). One live item per (batch, piece).
-- 3. transfer_pieces.batch_id — the batch a piece was sent in (NULL = the first send).
-- 4. transfer_shopify_syncs.batch_id — ONE claim per batch (NULL = the transfer's first send). UNIQUE(transfer_id) is
--    replaced by a partial unique for the first send (batch_id IS NULL — every existing row, unchanged) and
--    UNIQUE(batch_id) for top-ups. Inserts name the partial index's predicate in ON CONFLICT.
-- RLS: NULLIF pattern, FORCE; app_user never deletes.

CREATE TABLE transfer_send_batches (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenants(id),
    transfer_id     uuid        NOT NULL REFERENCES transfers(id),
    batch_no        int         NOT NULL CHECK (batch_no >= 2),
    status          text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'sent', 'discarded')),
    created_by      uuid        REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    sent_by         uuid        REFERENCES users(id),
    sent_at         timestamptz,
    send_request_id text,
    discarded_by    uuid        REFERENCES users(id),
    discarded_at    timestamptz,
    CONSTRAINT transfer_send_batches_sent_pair CHECK ((status = 'sent') = (sent_at IS NOT NULL)),
    CONSTRAINT transfer_send_batches_discarded_pair CHECK ((status = 'discarded') = (discarded_at IS NOT NULL)),
    CONSTRAINT transfer_send_batches_no UNIQUE (transfer_id, batch_no)
);

CREATE UNIQUE INDEX ux_transfer_send_batches_one_open ON transfer_send_batches (transfer_id) WHERE status = 'open';

ALTER TABLE transfer_send_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfer_send_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transfer_send_batches
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON transfer_send_batches FROM app_user;

CREATE TABLE transfer_send_batch_items (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id),
    batch_id           uuid        NOT NULL REFERENCES transfer_send_batches(id),
    piece_id           text        NOT NULL REFERENCES pieces(id),
    variant_id         uuid        NOT NULL REFERENCES variants(id),
    added_by           uuid        REFERENCES users(id),
    added_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    added_via          text        NOT NULL CHECK (added_via IN ('scan', 'count')),
    add_request_id     text,
    removed_by         uuid        REFERENCES users(id),
    removed_at         timestamptz,
    remove_request_id  text
);

CREATE UNIQUE INDEX ux_transfer_send_batch_items_live ON transfer_send_batch_items (batch_id, piece_id)
    WHERE removed_at IS NULL;
CREATE INDEX ix_transfer_send_batch_items_batch ON transfer_send_batch_items (batch_id, added_at);

ALTER TABLE transfer_send_batch_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE transfer_send_batch_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transfer_send_batch_items
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON transfer_send_batch_items FROM app_user;

ALTER TABLE transfer_pieces ADD COLUMN batch_id uuid REFERENCES transfer_send_batches(id);

ALTER TABLE transfer_shopify_syncs ADD COLUMN batch_id uuid REFERENCES transfer_send_batches(id);
ALTER TABLE transfer_shopify_syncs DROP CONSTRAINT transfer_shopify_syncs_one_per_transfer;
CREATE UNIQUE INDEX ux_transfer_shopify_syncs_first_send ON transfer_shopify_syncs (transfer_id) WHERE batch_id IS NULL;
CREATE UNIQUE INDEX ux_transfer_shopify_syncs_batch ON transfer_shopify_syncs (batch_id) WHERE batch_id IS NOT NULL;
