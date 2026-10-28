-- V190 — Warehouse places S1: find the pieces on a place (piece counts per place, archive refusal, the S2
-- put-away list and the S3 pick lines). Partial: only placed pieces — empty until S2 puts something away.
--
-- Non-transactional (V190__pieces_bin_index.sql.conf): CREATE INDEX CONCURRENTLY never blocks piece writes
-- (V179 pattern; Flyway's lock is session-level, spring.flyway.postgresql.transactional-lock: false). If a build
-- fails it leaves an INVALID index behind; the DROP first makes a re-run (after `flyway repair`) start clean.

DROP INDEX CONCURRENTLY IF EXISTS pieces_bin_idx;

CREATE INDEX CONCURRENTLY pieces_bin_idx
    ON pieces (tenant_id, bin_id)
    WHERE bin_id IS NOT NULL;
