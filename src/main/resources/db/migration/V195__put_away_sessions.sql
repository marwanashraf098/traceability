-- V195 — Warehouse places S2 (approved 2026-10-28): put-away sessions.
--
-- A put-away session is one person putting pieces onto shelves / into boxes at ONE location: they scan a place
-- sticker (the session's current place, kept server-side), then pieces, each placed through
-- InventoryLedger.recordPlaceChange (pieces.bin_id + one piece_events row; status unchanged, never Shopify).
--   - put_away_sessions: source 'manual' (any piece with no place at the location — the only source S2 opens);
--     'receiving' / 'return_session' / 'transfer' + source_id are for S4's scoped sessions. current_bin_id is the
--     place being filled (a shelf / box of the session's location — composite FK).
--   - put_away_session_pieces: what the session placed (placed_bin_id / placed_at, the last place it put the
--     piece); S4 will pre-fill scoped pieces with a suggested_bin_id and no placed_bin_id yet.
--   - A session ends one of three ways (close_reason): 'finished' by its opener, 'closed_by_manager' (an owner /
--     manager closing someone's open session), 'idle' (PutAwayIdleCloseJob: no scan for 12 h; closed_by NULL).
--     Closing never moves a piece; summary = what it placed, where (recorded at close).
-- app_user: no DELETE on either; a session's location / source / opener never change.

CREATE TABLE put_away_sessions (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL REFERENCES tenants(id),
    location_id     uuid        NOT NULL,
    source          text        NOT NULL CHECK (source IN ('manual', 'receiving', 'return_session', 'transfer')),
    source_id       uuid,
    opened_by       uuid        NOT NULL REFERENCES users(id),
    status          text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    current_bin_id  uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_activity_at timestamptz NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    closed_by       uuid        REFERENCES users(id),
    close_reason    text        CHECK (close_reason IN ('finished', 'closed_by_manager', 'idle')),
    summary         jsonb,
    FOREIGN KEY (location_id, tenant_id) REFERENCES locations (id, tenant_id),
    FOREIGN KEY (current_bin_id, location_id) REFERENCES bins (id, location_id),
    UNIQUE (id, tenant_id),
    CONSTRAINT put_away_sessions_source_id CHECK ((source = 'manual') = (source_id IS NULL)),
    CONSTRAINT put_away_sessions_closed CHECK ((status = 'closed') = (closed_at IS NOT NULL AND close_reason IS NOT NULL))
);

-- One open manual session per person per location (Start resumes it).
CREATE UNIQUE INDEX put_away_sessions_one_open_manual
    ON put_away_sessions (tenant_id, opened_by, location_id) WHERE status = 'open' AND source = 'manual';
CREATE INDEX put_away_sessions_location_idx ON put_away_sessions (tenant_id, location_id, created_at DESC);
CREATE INDEX put_away_sessions_open_idx ON put_away_sessions (tenant_id, last_activity_at) WHERE status = 'open';

ALTER TABLE put_away_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE put_away_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON put_away_sessions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON put_away_sessions FROM app_user;
GRANT UPDATE (status, current_bin_id, last_activity_at, closed_at, closed_by, close_reason, summary)
    ON put_away_sessions TO app_user;

CREATE TABLE put_away_session_pieces (
    session_id        uuid        NOT NULL,
    tenant_id         uuid        NOT NULL REFERENCES tenants(id),
    piece_id          text        NOT NULL REFERENCES pieces(id),
    suggested_bin_id  uuid        REFERENCES bins(id),
    placed_bin_id     uuid        REFERENCES bins(id),
    placed_at         timestamptz,
    PRIMARY KEY (session_id, piece_id),
    FOREIGN KEY (session_id, tenant_id) REFERENCES put_away_sessions (id, tenant_id),
    CONSTRAINT put_away_session_pieces_placed CHECK ((placed_bin_id IS NULL) = (placed_at IS NULL))
);
CREATE INDEX put_away_session_pieces_piece_idx ON put_away_session_pieces (tenant_id, piece_id);

ALTER TABLE put_away_session_pieces ENABLE ROW LEVEL SECURITY;
ALTER TABLE put_away_session_pieces FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON put_away_session_pieces
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON put_away_session_pieces FROM app_user;
GRANT UPDATE (suggested_bin_id, placed_bin_id, placed_at) ON put_away_session_pieces TO app_user;
