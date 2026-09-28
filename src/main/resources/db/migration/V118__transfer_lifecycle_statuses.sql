-- Transfer lifecycle: preparing → sent → reconciling → closed, plus cancelled.
--
-- Before: open | reconciling | closed. 'open' meant both "still scanning out" and "stock is
-- physically away", and an empty open transfer could never close (Begin Reconcile needs
-- outstanding pieces; there was no cancel).
--
-- After:
--   preparing   — created, pieces being scanned out (the only status scan-out accepts)
--   sent        — explicitly marked as sent (round_trip / relocate_return only, >= 1 piece);
--                 the only status Begin Reconcile accepts
--   reconciling — unchanged
--   closed      — unchanged (relocate_out still closes straight from preparing)
--   cancelled   — from preparing only, with zero transfer_pieces rows ever
--
-- Pieces still leave at scan time (available → out_on_transfer); mark-sent and cancel write
-- no piece events and never touch pieces.
--
-- Backfill of existing 'open' rows (transfer_pieces rows are never deleted — only given an
-- outcome — so "has a transfer_pieces row" is exactly "was ever scanned"):
--   no transfer_pieces                         → preparing
--   transfer_pieces, round_trip/relocate_return → sent
--   transfer_pieces, relocate_out               → preparing (relocate_out has no sent state)
-- reconciling / closed rows are unchanged. The new *_at / *_by columns stay NULL on
-- backfilled rows — the times were never recorded.

ALTER TABLE transfers DROP CONSTRAINT transfers_status_check;

ALTER TABLE transfers
    ADD COLUMN sent_at               timestamptz,
    ADD COLUMN sent_by               uuid REFERENCES users(id),
    ADD COLUMN cancelled_at          timestamptz,
    ADD COLUMN cancelled_by          uuid REFERENCES users(id),
    ADD COLUMN reconcile_started_at  timestamptz,
    ADD COLUMN reconcile_started_by  uuid REFERENCES users(id);

UPDATE transfers t
SET status = CASE
        WHEN t.transfer_mode IN ('round_trip', 'relocate_return')
         AND EXISTS (SELECT 1 FROM transfer_pieces tp WHERE tp.transfer_id = t.id)
        THEN 'sent'
        ELSE 'preparing'
    END
WHERE t.status = 'open';

ALTER TABLE transfers
    ADD CONSTRAINT transfers_status_check
        CHECK (status IN ('preparing', 'sent', 'reconciling', 'closed', 'cancelled')),
    ADD CONSTRAINT transfers_cancelled_has_timestamp
        CHECK (status <> 'cancelled' OR cancelled_at IS NOT NULL);

ALTER TABLE transfers ALTER COLUMN status SET DEFAULT 'preparing';
