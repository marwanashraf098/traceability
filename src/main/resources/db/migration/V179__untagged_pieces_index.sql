-- V179 — Flexible labeling slice 2: find an untagged available piece of a variant at a location
-- (binding a blank label at pack / Tag stock, slices 3 / 4; untagged counts per variant).
--
-- Partial: only untagged available pieces — empty until a tenant receives count-only. Prod size
-- checked 2026-10-21 before writing this: pieces ≈ 6,154 rows, 4.4 MB total (heap 1.5 MB).
--
-- Non-transactional (V179__untagged_pieces_index.sql.conf): CREATE INDEX CONCURRENTLY never blocks
-- piece writes. If a build fails it leaves an INVALID index behind; the DROP first makes a re-run
-- (after `flyway repair`) start clean. Nothing else in this file.

DROP INDEX CONCURRENTLY IF EXISTS pieces_untagged_available_idx;

CREATE INDEX CONCURRENTLY pieces_untagged_available_idx
    ON pieces (tenant_id, variant_id, current_location_id)
    WHERE short_code IS NULL AND status = 'available';
