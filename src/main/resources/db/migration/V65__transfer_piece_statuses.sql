-- ============================================================
-- V65 — FR-22.2: piece_status enum additions for Transfers
--
-- Gate G1 (approved by Marawan). Adds the two new values needed by the
-- transfer state machine: out_on_transfer (active) and sold (terminal).
--
-- Statements-only: nothing in THIS migration file may reference the new
-- values (ALTER TYPE ... ADD VALUE cannot be used in the same transaction
-- it's added in — Postgres requires the new enum label to be committed
-- before it can appear in any expression). InventoryLedger.ALLOWED and
-- PieceStatus.java pick these up in application code, not SQL.
-- ============================================================

ALTER TYPE piece_status ADD VALUE 'out_on_transfer';
ALTER TYPE piece_status ADD VALUE 'sold';
