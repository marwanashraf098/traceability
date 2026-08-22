-- ============================================================
-- V80 — FR-13.x: piece_status enum additions for Void / On Hold
--
-- Adds the two new values needed by the piece-adjustment state machine:
-- voided (terminal — receiving-overcount / duplicate-entry correction, NOT a
-- loss) and on_hold (reversible QC/quarantine).
--
-- Statements-only: nothing in THIS migration file may reference the new
-- values (ALTER TYPE ... ADD VALUE cannot be used in the same transaction
-- it's added in — Postgres requires the new enum label to be committed
-- before it can appear in any expression). InventoryLedger.ALLOWED and
-- PieceStatus.java pick these up in application code, not SQL. Same pattern
-- as V65 (out_on_transfer, sold).
-- ============================================================

ALTER TYPE piece_status ADD VALUE 'voided';
ALTER TYPE piece_status ADD VALUE 'on_hold';
