-- ============================================================
-- V81 — FR-13.x: pieces.condition
--
-- Backs the Lookup "Condition" field. Defaults every existing and future
-- row to 'good'; PieceAdjustService/ReturnService flip it to 'damaged'
-- wherever a piece transitions into DAMAGED (disposition-damaged and
-- return-inspection-damaged paths). No backfill needed beyond the
-- DEFAULT — no piece is retroactively known-damaged from this column
-- alone (status already carries that; condition is additive metadata).
-- ============================================================

ALTER TABLE pieces
    ADD COLUMN condition text NOT NULL DEFAULT 'good'
    CHECK (condition IN ('good', 'damaged'));
