-- ============================================================
-- V16 — Magic-link tokens for Shopify Path-2 owner first-login
-- ============================================================
-- This table is intentionally NOT under tenant RLS.
-- Access is pre-session: the whole point of consuming a magic link is
-- to *establish* the session — no GUC can exist before this lookup.
-- The only reader+consumer is the consume_magic_link SECURITY DEFINER
-- function below (sixth approved hatch, see blueprint.md §16.1).
-- No app_user SELECT/UPDATE grants are issued; all access goes via DEFINER.

CREATE TABLE magic_link_tokens (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    user_id     uuid        NOT NULL REFERENCES users(id),
    token_hash  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz
);

-- Hash-based lookup — not UNIQUE because we allow issuing multiple links
-- (each replaces nothing; the first one consumed wins; the rest expire).
CREATE INDEX magic_link_tokens_hash_idx ON magic_link_tokens (token_hash);

-- ---- Sixth SECURITY DEFINER escape hatch --------------------------------
-- Justification: consuming a magic link is inherently pre-session; the JWT
-- we issue IS the session, so no tenant GUC can be set before this lookup.
-- Atomic SELECT FOR UPDATE + UPDATE prevents concurrent double-consume:
-- the second request waits for the first's UPDATE to commit, then sees
-- consumed_at IS NOT NULL and returns empty (→ MAGIC_LINK_INVALID).
-- NOT FOUND / expired / consumed all return empty — no oracle for callers.
-- search_path is pinned against DEFINER injection (same as all other hatches).
-- EXECUTE is granted only to app_user; PUBLIC is explicitly revoked.
CREATE OR REPLACE FUNCTION consume_magic_link(p_token_hash text)
RETURNS TABLE(user_id uuid, tenant_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_id         uuid;
    v_user_id    uuid;
    v_tenant_id  uuid;
    v_expires_at timestamptz;
    v_consumed   timestamptz;
BEGIN
    SELECT mlt.id, mlt.user_id, mlt.tenant_id, mlt.expires_at, mlt.consumed_at
    INTO   v_id, v_user_id, v_tenant_id, v_expires_at, v_consumed
    FROM   magic_link_tokens mlt
    WHERE  mlt.token_hash = p_token_hash
    FOR UPDATE;

    -- All invalid sub-conditions (not found / expired / consumed) return empty.
    -- Callers must not distinguish which sub-condition fired — no oracle.
    IF NOT FOUND OR v_consumed IS NOT NULL OR v_expires_at < now() THEN
        RETURN;
    END IF;

    UPDATE magic_link_tokens SET consumed_at = now() WHERE id = v_id;

    RETURN QUERY SELECT v_user_id, v_tenant_id;
END;
$$;

REVOKE ALL ON FUNCTION consume_magic_link(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION consume_magic_link(text) TO app_user;
