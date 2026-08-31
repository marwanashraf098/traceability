-- ============================================================
-- V85 — Code-based forgot/reset password
-- ============================================================
-- password_reset_codes is intentionally NOT under tenant RLS, same posture as
-- magic_link_tokens (V16): a reset-code lookup is pre-session by definition,
-- so no GUC can exist before it.
--
-- Grant posture note (re-confirmed against magic_link_tokens before writing this):
-- V16's own comment claims "No app_user SELECT/UPDATE grants are issued; all
-- access goes via DEFINER" — but V16 never issues a REVOKE on the table, and
-- V1's `ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE,
-- DELETE ON TABLES TO app_user` (Flyway runs every migration as the same owner,
-- per V18's comment) applies automatically to every table created afterward
-- unless a migration explicitly REVOKEs — which V16 does not. So in practice
-- app_user already has plain SELECT/INSERT/UPDATE/DELETE on magic_link_tokens,
-- matching MagicLinkService.issueMagicLink()'s plain jdbc.update() INSERT (no
-- DEFINER wrapper there). This migration matches that ACTUAL posture exactly:
-- no REVOKE here either, same as V16. Issuance (PasswordResetService.requestReset)
-- and the request-throttle count both read/write this table via plain app_user
-- JdbcTemplate calls, the same access path magic_link_tokens issuance uses.
-- Only *consumption* goes through a SECURITY DEFINER function below — for
-- atomicity (FOR UPDATE + single-outcome business logic across sub-conditions),
-- not because app_user structurally lacks the grant.

CREATE TABLE password_reset_codes (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenants(id),
    user_id       uuid        NOT NULL REFERENCES users(id),
    code_hash     text        NOT NULL,   -- SHA-256, AuthRepository.sha256() reused
    expires_at    timestamptz NOT NULL,
    consumed_at   timestamptz,
    attempt_count integer     NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX password_reset_codes_user_created_idx ON password_reset_codes (user_id, created_at);
CREATE INDEX password_reset_codes_user_idx         ON password_reset_codes (user_id);

-- ---- Seventh SECURITY DEFINER escape hatch — APPROVED (Marawan, this build) --
-- Resolving the user by email is the same cross-tenant, pre-session basis as
-- auth_lookup_user (V1): the caller has only an email + a 6-digit code, no
-- tenant GUC yet. Scoping the code lookup to that user, then FOR UPDATE on the
-- newest unconsumed/unexpired code row, makes lockout-check + hash-compare +
-- consume atomic (same double-consume guard shape as consume_magic_link, V16).
-- All failure sub-conditions (no such active user / no eligible code / locked
-- after 5 attempts / hash mismatch) return the SAME empty result — no oracle
-- for the caller, mirrored 1:1 from consume_magic_link's design.
CREATE OR REPLACE FUNCTION verify_and_consume_reset_code(p_email text, p_code_hash text)
RETURNS TABLE(user_id uuid, tenant_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_user_id    uuid;
    v_tenant_id  uuid;
    v_code_id    uuid;
    v_code_hash  text;
    v_attempts   integer;
BEGIN
    -- Resolve the active user by email — same basis as auth_lookup_user.
    -- Table alias + qualified columns: the RETURNS TABLE(user_id, tenant_id) output
    -- parameters are in scope here too, and bare "tenant_id"/"id" would be ambiguous
    -- against the users columns of the same name (PL/pgSQL raised exactly this error
    -- during testing — fixed by qualifying, not by renaming the OUT parameters, so the
    -- public RETURNS TABLE shape stays identical to consume_magic_link's).
    SELECT u.id, u.tenant_id INTO v_user_id, v_tenant_id
    FROM users u
    WHERE u.email = p_email AND u.active = true;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    -- Newest unconsumed, unexpired code for this user. created_at DESC with id
    -- as tiebreak — gen_random_uuid() PKs are not time-ordered (see CLAUDE.md).
    -- Qualified for the same reason as the users query above — "user_id" is
    -- also a RETURNS TABLE OUT parameter name, so a bare reference here would
    -- be ambiguous too.
    SELECT prc.id, prc.code_hash, prc.attempt_count
    INTO   v_code_id, v_code_hash, v_attempts
    FROM   password_reset_codes prc
    WHERE  prc.user_id = v_user_id
      AND  prc.consumed_at IS NULL
      AND  prc.expires_at > now()
    ORDER BY prc.created_at DESC, prc.id DESC
    LIMIT 1
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF v_attempts >= 5 THEN
        RETURN; -- locked — even a correct code is rejected while locked
    END IF;

    IF v_code_hash <> p_code_hash THEN
        UPDATE password_reset_codes SET attempt_count = attempt_count + 1 WHERE id = v_code_id;
        RETURN;
    END IF;

    UPDATE password_reset_codes SET consumed_at = now() WHERE id = v_code_id;

    RETURN QUERY SELECT v_user_id, v_tenant_id;
END;
$$;

REVOKE ALL ON FUNCTION verify_and_consume_reset_code(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION verify_and_consume_reset_code(text, text) TO app_user;
