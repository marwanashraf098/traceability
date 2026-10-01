-- ============================================================
-- V126 — Pick & Pack S3: waybill scan mode.
--
-- tenants.pick_pack_mode   — how the store packs: 'order_queue' (default — every existing store
--                            keeps today's behaviour) or 'waybill_scan'. Owner-only write.
-- pack_sessions            — one packer's session; the mode is copied in at start so an owner
--                            switching mid-shift doesn't change a session already open.
-- pack_session_orders      — what happened in a session: packed / set_aside / rejected, with the
--                            raw scan and the rejection code or set-aside reason.
--
-- Tenant RLS (NULLIF pattern) + FORCE on both new tables. app_user: sessions SELECT/INSERT/UPDATE
-- (UPDATE only to end one); session orders SELECT/INSERT (history — never edited).
-- ============================================================

ALTER TABLE tenants
    ADD COLUMN pick_pack_mode text NOT NULL DEFAULT 'order_queue'
        CHECK (pick_pack_mode IN ('order_queue', 'waybill_scan'));

CREATE TABLE pack_sessions (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    user_id     uuid        NOT NULL REFERENCES users(id),
    mode        text        NOT NULL CHECK (mode IN ('order_queue', 'waybill_scan')),
    status      text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'ended')),
    started_at  timestamptz NOT NULL DEFAULT now(),
    ended_at    timestamptz,
    CHECK ((status = 'ended') = (ended_at IS NOT NULL))
);

-- At most one open session per packer: a concurrent second start hits this and resumes instead.
CREATE UNIQUE INDEX pack_sessions_one_open_per_user
    ON pack_sessions (tenant_id, user_id)
    WHERE status = 'open';

CREATE INDEX pack_sessions_tenant_started_idx
    ON pack_sessions (tenant_id, started_at DESC);

ALTER TABLE pack_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE pack_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pack_sessions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE TABLE pack_session_orders (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    session_id  uuid        NOT NULL REFERENCES pack_sessions(id),
    -- NULL for a rejected scan that matched no order (unknown waybill, a piece code, ...).
    -- ON DELETE CASCADE: production never deletes orders; DemoSeeder.reseed and test cleanups do.
    order_id    uuid        REFERENCES orders(id) ON DELETE CASCADE,
    raw_scan    text,
    outcome     text        NOT NULL CHECK (outcome IN ('packed', 'set_aside', 'rejected')),
    reason      text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX pack_session_orders_session_idx
    ON pack_session_orders (session_id, created_at DESC);
CREATE INDEX pack_session_orders_tenant_order_idx
    ON pack_session_orders (tenant_id, order_id);

ALTER TABLE pack_session_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE pack_session_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pack_session_orders
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE ALL ON pack_sessions, pack_session_orders FROM app_user;
GRANT SELECT, INSERT, UPDATE ON pack_sessions TO app_user;
GRANT SELECT, INSERT ON pack_session_orders TO app_user;
