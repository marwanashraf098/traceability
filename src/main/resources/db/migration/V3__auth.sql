-- ============================================================
-- V3 — Auth support: refresh tokens + PIN lockout
-- ============================================================

-- ---- refresh_tokens -----------------------------------------
-- Opaque tokens: the raw token lives only in the client; we store
-- the SHA-256 hash so a stolen DB copy cannot replay tokens.
CREATE TABLE refresh_tokens (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    user_id     uuid        NOT NULL REFERENCES users(id),
    token_hash  text        NOT NULL UNIQUE,
    issued_at   timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz
);

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON refresh_tokens
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

CREATE INDEX refresh_tokens_user_idx    ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_expires_idx ON refresh_tokens (expires_at)
    WHERE revoked_at IS NULL;

-- ---- PIN lockout columns on users ---------------------------
ALTER TABLE users
    ADD COLUMN pin_fail_count   integer     NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until timestamptz;

-- ---- Third SECURITY DEFINER escape hatch --------------------
-- The refresh endpoint receives only an opaque token; there is no JWT yet
-- to carry tenant_id, so we cannot set the GUC before the lookup.
-- This function is the only safe cross-tenant read for refresh tokens.
-- (The other two escape hatches are auth_lookup_user and resolve_tenant_by_shop_domain in V1.)
CREATE OR REPLACE FUNCTION lookup_refresh_token(p_hash text)
RETURNS TABLE (
    id         uuid,
    tenant_id  uuid,
    user_id    uuid,
    expires_at timestamptz,
    revoked_at timestamptz
)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT id, tenant_id, user_id, expires_at, revoked_at
    FROM refresh_tokens
    WHERE token_hash = p_hash;
$$;

GRANT EXECUTE ON FUNCTION lookup_refresh_token(text) TO app_user;

-- Grants for the new table and the new users columns are covered by
-- the GRANT/ALTER DEFAULT PRIVILEGES already issued in V1.
