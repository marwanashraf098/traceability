-- ============================================================
-- V86 — Lock down magic_link_tokens and password_reset_codes
-- ============================================================
-- Both tables' own migration comments (V16, V85) claimed protection that was
-- never actually enforced: V1's `ALTER DEFAULT PRIVILEGES ... GRANT SELECT,
-- INSERT, UPDATE, DELETE ON TABLES TO app_user` auto-grants every table
-- created afterward unless a migration explicitly REVOKEs — and neither V16
-- nor V85 did. Confirmed empirically: a live app_user connection with
-- app.current_tenant set to tenant A could SELECT tenant B's rows from both
-- tables directly (no RLS exists on either — by design, both are pre-session
-- surfaces with no tenant GUC available yet). This migration makes the
-- "DEFINER-only access" intent real instead of aspirational.
--
-- shopify_oauth_state has the same gap (see docs/blueprint.md §16.1 note on
-- hatch #8) but its consume/cleanup paths are still plain app_user SQL, not
-- DEFINER-routed — locking it down needs new code first. Deferred to a
-- separate follow-up; not touched here.

-- ---- Fold invalidate-all-other-codes into verify_and_consume_reset_code ----
-- Extends hatch #7 (V85) — same name, same signature, CREATE OR REPLACE.
-- V85's file is frozen (already committed/applied) and is not edited in place,
-- per this repo's established migration convention (see V68 vs V57 in
-- CLAUDE.md). Previously, invalidating every OTHER outstanding code for the
-- user happened as a separate plain app_user UPDATE in
-- AuthRepository.completePasswordReset() — that statement is removed below
-- from the app-code grant surface by this migration's REVOKE, so the
-- invalidation must happen here instead, inside the same FOR UPDATE-guarded
-- transaction as the match itself.
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
    SELECT u.id, u.tenant_id INTO v_user_id, v_tenant_id
    FROM users u
    WHERE u.email = p_email AND u.active = true;

    IF NOT FOUND THEN
        RETURN;
    END IF;

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

    -- Invalidate every OTHER outstanding code for this user, atomically with the
    -- match above (folded in from AuthRepository.completePasswordReset — app_user
    -- can no longer write this table directly once the REVOKE below lands).
    -- Aliased for the same reason as the two SELECTs above: bare "user_id" is
    -- ambiguous against the RETURNS TABLE(user_id, tenant_id) OUT parameters.
    UPDATE password_reset_codes prc2
    SET consumed_at = now()
    WHERE prc2.user_id = v_user_id
      AND prc2.id <> v_code_id
      AND prc2.consumed_at IS NULL;

    RETURN QUERY SELECT v_user_id, v_tenant_id;
END;
$$;

-- ---- Eighth SECURITY DEFINER escape hatch — APPROVED (Marawan, this build) --
-- The request-throttle check (PasswordResetService.isThrottled) needs to read
-- password_reset_codes, which app_user loses direct SELECT on below. Read-only,
-- scoped to the caller-supplied user_id (always a value PasswordResetService
-- already resolved via auth_lookup_user — never raw request input). Returns
-- exactly what isThrottled() needs: the count of codes issued in the last hour,
-- and the most recent issuance time (the 60s-debounce check stays in Java,
-- unchanged from before — only the data source moves from a plain SELECT to
-- this function).
CREATE OR REPLACE FUNCTION check_password_reset_throttle(p_user_id uuid)
RETURNS TABLE(hour_count bigint, latest_created_at timestamptz)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT COUNT(*) FILTER (WHERE created_at > now() - interval '1 hour') AS hour_count,
           MAX(created_at) AS latest_created_at
    FROM password_reset_codes
    WHERE user_id = p_user_id;
$$;

REVOKE ALL ON FUNCTION check_password_reset_throttle(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION check_password_reset_throttle(uuid) TO app_user;

-- ---- Enforce DEFINER-only access ------------------------------------------
-- INSERT stays granted (via V1's unrevoked default privileges) — issuance for
-- both tables (MagicLinkService.issueMagicLink, PasswordResetService.requestReset)
-- is a plain app_user INSERT and stays that way; nothing else needs to change
-- for either issuance path.
--
-- After this migration, the only direct app_user statement against either
-- table is that INSERT. Every read and every update goes through a named,
-- code-reviewed SECURITY DEFINER function:
--   magic_link_tokens   → consume_magic_link (V16, unchanged)
--   password_reset_codes → verify_and_consume_reset_code (above) for consume,
--                           check_password_reset_throttle (above) for the
--                           request-throttle read.
REVOKE SELECT, UPDATE, DELETE ON magic_link_tokens    FROM app_user;
REVOKE SELECT, UPDATE, DELETE ON password_reset_codes FROM app_user;

-- ---- Correct the now-enforced (previously aspirational) table comments ----
-- V16 and V85's own inline SQL comments already claimed this protection; both
-- files are frozen (already applied) and are not edited in place. COMMENT ON
-- updates the live, queryable description without touching either migration
-- file's checksummed content.
COMMENT ON TABLE magic_link_tokens IS
    'Single-use magic-link sign-in tokens (SHA-256 hash at rest). NOT under '
    'tenant RLS — pre-session surface, no GUC exists before consumption. '
    'app_user has INSERT only (enforced by V86 REVOKE); all reads and the '
    'consume/expire write go through consume_magic_link (SECURITY DEFINER, V16).';

COMMENT ON TABLE password_reset_codes IS
    'Single-use password-reset codes (SHA-256 hash at rest). NOT under tenant '
    'RLS — pre-session surface, no GUC exists before consumption. app_user has '
    'INSERT only (enforced by V86 REVOKE); consume/lockout/invalidate-all goes '
    'through verify_and_consume_reset_code, and the request-throttle read goes '
    'through check_password_reset_throttle (both SECURITY DEFINER, V86).';
