-- ============================================================
-- V90 — FR-22.11 (Relocate Return, B2): transfers.source_location_id
--
-- The B->A return leg needs an origin to scope returnScanOut()'s claim against
-- (a transferred_out piece at Warehouse-B-2 must not be claimable by a return
-- declared against Warehouse-B-1) — round_trip and relocate_out transfers have
-- no notion of "origin" at all (their only location is destination_location_id),
-- so this column is nullable and set ONLY for transfer_mode='relocate_return'.
-- Same optional-mode-field convention V89 used for adding relocate_return to the
-- CHECK ahead of any code reaching it.
-- ============================================================

ALTER TABLE transfers
    ADD COLUMN source_location_id uuid REFERENCES locations(id);
