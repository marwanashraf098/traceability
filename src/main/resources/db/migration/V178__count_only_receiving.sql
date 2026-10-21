-- V178 — Flexible labeling slice 2: count-only receiving + untagged pieces (approved 2026-10-21).
-- Renumbered from V177 before merge: G4a took V177 on main (2026-10-21). Never applied anywhere as V177.
--
-- An UNTAGGED piece is a real piece with no printed code yet: short_code NULL. It is received by a
-- count-only session (receipts.mode = 'count_only' — no labels printed; Shopify +N exactly as normal
-- receiving) and gets its code when a blank label is bound to it (slices 3 / 4). barcode (PC-<ULID>)
-- stays NOT NULL — it is never printed. Untagged pieces can't be reprinted, transferred, held, damaged
-- or written off until tagged, and a stock take refuses to start / finalize while any is in scope.
--   - pieces.short_code: DROP NOT NULL (metadata only). UNIQUE (tenant_id, short_code) is unchanged —
--     NULLs never collide, a bound code still can't repeat in a tenant.
--   - receipts.mode: every existing receipt is 'print_stick' (today's flow). Slice 6 adds 'factory'.
-- The partial index for "an untagged available piece of this variant here" is V179 (CONCURRENTLY).

ALTER TABLE pieces ALTER COLUMN short_code DROP NOT NULL;

ALTER TABLE receipts ADD COLUMN mode text NOT NULL DEFAULT 'print_stick'
    CONSTRAINT receipts_mode_check CHECK (mode IN ('print_stick', 'count_only'));
