-- ============================================================
-- V89 — FR-22.10 (Relocate B1): transfer_mode + relocated outcome
--
-- transfer_mode is independent of transfer_type — transfer_type stays a
-- CATEGORY (showroom/dryclean/repair/other, see V64's header comment),
-- transfer_mode is the WORKFLOW: round_trip is every existing transfer
-- (open -> scan-out -> reconciling -> scan-back/classify -> closed,
-- completely unaffected by this migration); relocate_out is the new B1
-- one-shot close (open -> closed directly, no reconcile stage — see
-- TransferService.closeOneWay()). relocate_return is added to the CHECK
-- now so FR-22.11 (B2, the B->A return path) needs no further schema
-- change, but it is unreachable from application code until B2 ships —
-- no service method sets or branches on it yet.
--
-- transfer_pieces.outcome gains 'relocated' — the terminal value
-- closeOneWay() writes per piece, in the SAME transaction as the piece's
-- out_on_transfer:transferred_out ledger transition. This is what frees
-- transfer_pieces_one_active's UNIQUE (piece_id) WHERE outcome IS NULL
-- slot so a future return transfer (B2) can claim the same piece_id —
-- skipping this write would leave outcome NULL and permanently strand the
-- piece (un-pickable AND un-returnable).
-- ============================================================

ALTER TABLE transfers
    ADD COLUMN transfer_mode text NOT NULL DEFAULT 'round_trip'
        CHECK (transfer_mode IN ('round_trip', 'relocate_out', 'relocate_return'));

ALTER TABLE transfer_pieces
    DROP CONSTRAINT transfer_pieces_outcome_check;

ALTER TABLE transfer_pieces
    ADD CONSTRAINT transfer_pieces_outcome_check
    CHECK (outcome IN ('returned_good', 'condemned', 'sold', 'lost', 'relocated'));
