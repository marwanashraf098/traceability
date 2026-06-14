-- ============================================================
-- V5 — Day 5: import status tracking + Bosta webhook secret
-- ============================================================

-- ---- import_status on stores --------------------------------
-- Tracks background import job lifecycle per store.
CREATE TYPE store_import_status AS ENUM ('idle', 'pending', 'importing', 'completed', 'failed');

ALTER TABLE stores
    ADD COLUMN import_status  store_import_status NOT NULL DEFAULT 'idle',
    ADD COLUMN import_summary jsonb;

-- ---- webhook_secret on courier_accounts ---------------------
-- Per-tenant CSPRNG secret for Bosta webhook authorization.
-- Stored as SHA-256 hex hash (same pattern as refresh_tokens.token_hash in V3).
-- The raw 32-byte hex token is returned once at connect time; we never store it.
ALTER TABLE courier_accounts
    ADD COLUMN webhook_secret text;

-- ---- Fourth SECURITY DEFINER escape hatch -------------------
-- Bosta webhooks arrive before any tenant context is established.
-- The Authorization header carries a per-tenant CSPRNG secret (64 hex chars).
-- This function resolves the secret to the tenant_id without exposing a
-- BYPASSRLS connection in application code.
--
-- Justification: there is no bearer token in webhook requests; the secret IS
-- the authentication mechanism. A SECURITY DEFINER function is the only RLS-safe
-- cross-tenant read in this path.
--
-- Approved: 2026-06-14, explicitly.
--
-- Hashing: SHA-256 of the raw hex secret (UTF-8 bytes) is stored.
-- PostgreSQL: encode(sha256(p_secret::bytea), 'hex') hashes the literal bytes
-- of the hex string — identical to Java MessageDigest.SHA-256 on the same string.
--
-- Previous three hatches: auth_lookup_user (V1), resolve_tenant_by_shop_domain (V1),
-- lookup_refresh_token (V3). This is the fourth and final approved hatch.
CREATE OR REPLACE FUNCTION resolve_tenant_by_webhook_secret(p_secret text)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_tenant_id uuid;
BEGIN
    SELECT tenant_id INTO v_tenant_id
    FROM courier_accounts
    WHERE webhook_secret = encode(sha256(p_secret::bytea), 'hex')
      AND status = 'active'
    LIMIT 1;
    RETURN v_tenant_id;  -- NULL if not found or inactive
END;
$$;

REVOKE ALL ON FUNCTION resolve_tenant_by_webhook_secret(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_tenant_by_webhook_secret(text) TO app_user;
