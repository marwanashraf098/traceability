-- V198 — Warehouse places S5 (2026-10-31): counting one place, and place stickers in the full stock take.
--
-- A place count is one person counting ONE shelf or box: they scan its sticker, then every piece they find there.
-- Each scan is recorded with its result (place_count_scans) — nothing changes on the pieces while counting. Finish
-- applies the count in ONE transaction through InventoryLedger.recordPlaceChange (source 'place_count'): pieces
-- found here that were elsewhere / nowhere are put here, pieces expected here but not scanned get "No place yet"
-- (place_cleared). A count never writes a piece off, never changes a status and never touches Shopify. It is NOT
-- a stock-take session (no reconciliation, no Shopify push).
--   - place_counts: one per (place, start); one OPEN count per place (partial UNIQUE, like V197). status
--     open → finished (Finish) | cancelled (Cancel, or PlaceCountIdleCancelJob after 12 h with no scan; summary
--     {"reason":"idle"}). summary = what Finish applied (or the cancel reason).
--   - place_count_scans: every scan's result; a piece once per count (partial UNIQUE — a repeat scan is ignored).
--   - stock_take_sessions.current_bin_id: the place a full stock take is counting right now (set by scanning a
--     place sticker during the stock take; pieces scanned after it are placed there, source 'stock_take'). NULL —
--     no sticker scanned — means the stock take never changes a place. Finalize / reconciliation don't read it.
-- app_user: no DELETE on either new table; a count's place, location and opener never change; scans are append-only.

CREATE TABLE place_counts (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES tenants(id),
    location_id       uuid        NOT NULL,
    bin_id            uuid        NOT NULL,
    opened_by         uuid        NOT NULL REFERENCES users(id),
    status            text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'finished', 'cancelled')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    last_activity_at  timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    finished_by       uuid        REFERENCES users(id),
    summary           jsonb,
    FOREIGN KEY (location_id, tenant_id) REFERENCES locations (id, tenant_id),
    FOREIGN KEY (bin_id, location_id) REFERENCES bins (id, location_id),
    UNIQUE (id, tenant_id),
    CONSTRAINT place_counts_ended CHECK ((status = 'open') = (finished_at IS NULL))
);

-- One open count per place (Start on a place with an open count opens that count).
CREATE UNIQUE INDEX place_counts_one_open_per_place ON place_counts (tenant_id, bin_id) WHERE status = 'open';
CREATE INDEX place_counts_place_idx ON place_counts (tenant_id, bin_id, created_at DESC);
CREATE INDEX place_counts_open_idx ON place_counts (tenant_id, last_activity_at) WHERE status = 'open';

ALTER TABLE place_counts ENABLE ROW LEVEL SECURITY;
ALTER TABLE place_counts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON place_counts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON place_counts FROM app_user;
GRANT UPDATE (status, last_activity_at, finished_at, finished_by, summary) ON place_counts TO app_user;

CREATE TABLE place_count_scans (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenants(id),
    count_id     uuid        NOT NULL,
    piece_id     text        REFERENCES pieces(id),
    raw_scan     text        NOT NULL,
    result       text        NOT NULL CHECK (result IN ('found_here', 'move_here', 'place_here', 'other_location',
                                                        'not_placeable', 'untagged', 'unknown')),
    from_bin_id  uuid        REFERENCES bins(id),
    scanned_at   timestamptz NOT NULL DEFAULT now(),
    scanned_by   uuid        REFERENCES users(id),
    via_phone    boolean     NOT NULL DEFAULT false,
    FOREIGN KEY (count_id, tenant_id) REFERENCES place_counts (id, tenant_id)
);
-- A piece is recorded once per count; a repeat scan is ignored.
CREATE UNIQUE INDEX place_count_scans_piece_once ON place_count_scans (count_id, piece_id) WHERE piece_id IS NOT NULL;
CREATE INDEX place_count_scans_count_idx ON place_count_scans (tenant_id, count_id, scanned_at DESC);

ALTER TABLE place_count_scans ENABLE ROW LEVEL SECURITY;
ALTER TABLE place_count_scans FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON place_count_scans
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON place_count_scans FROM app_user;

-- The full stock take's "counting place X" (see header).
ALTER TABLE stock_take_sessions ADD COLUMN current_bin_id uuid REFERENCES bins(id);
