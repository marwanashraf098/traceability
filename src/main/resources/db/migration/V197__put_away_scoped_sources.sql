-- V197 — Warehouse places S4: put-away lists after receiving, returns and transfers.
--
-- A put-away list is the gather list in reverse: the pieces a flow brought into a location (a receipt, a Tag stock
-- session, a return session's restocked pieces, a transfer's pieces returned good), each with where it should go.
-- "Start put away" opens a put_away_sessions row scoped to that flow (source + source_id — V195 already has both).
--   - source gains 'tag_session' (Tag stock: the pieces tagged in that session, found by their label_bound events).
--     V195's 'receiving' / 'return_session' / 'transfer' are used as named there.
--   - One OPEN scoped session per person per flow: Start resumes it (the manual sessions keep V195's own index —
--     one open per person per location). Two quick taps on Start can't open two.
-- Nothing else changes: grants, RLS, the session pieces table and every S2 path are as V195 left them.

ALTER TABLE put_away_sessions DROP CONSTRAINT put_away_sessions_source_check;
ALTER TABLE put_away_sessions ADD CONSTRAINT put_away_sessions_source_check
    CHECK (source IN ('manual', 'receiving', 'tag_session', 'return_session', 'transfer'));

CREATE UNIQUE INDEX put_away_sessions_one_open_scoped
    ON put_away_sessions (tenant_id, opened_by, source, source_id) WHERE status = 'open' AND source <> 'manual';
