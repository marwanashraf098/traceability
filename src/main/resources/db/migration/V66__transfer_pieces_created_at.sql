-- ============================================================
-- V66 — FR-22.4: transfer_pieces.created_at
--
-- Gap found while implementing classifyShortfall(): FIFO-pick "outstanding
-- pieces by transferred_out time" needs a deterministic ordering column.
-- transfer_pieces.id is a random uuid (unlike pieces.id, an app-generated
-- ULID) so it carries no time ordering. Add the missing timestamp; every
-- other table in this schema has one.
-- ============================================================

ALTER TABLE transfer_pieces
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
