-- =============================================================================================
-- Build A (2026-10-05) — revoke the refresh tokens the PIN switch left behind before V142.
--
-- Why: the refresh cookie's path is /api/v1/auth/refresh, so a browser never sent it to
-- /api/v1/auth/pin and PinService could never revoke the token the tablet held before the
-- switch. Every PIN switch left that token live (up to 30 days). Since Build A the switch ends it
-- by the access token's sid (reason 'pin_switch'); this script cleans up what is already there.
--
-- What it revokes (revoked_reason = 'orphan_cleanup') — ONLY the provably superseded ones:
--   live WORKER tokens that are not the newest live token of that worker. A worker only ever gets
--   a token from a station PIN switch, and the tablet's cookie is replaced by each new one, so an
--   older live token of the same worker is a token no device holds any more.
--   (Edge case accepted: one worker signed in on TWO tablets at once — the older tablet would go
--   back to its PIN gate on its next refresh, never out of station mode: Build A's /login silent
--   refresh fails, the owner signs in, the gate shows.)
--
-- What it does NOT touch: owner / manager tokens. A pre-switch owner token on a tablet looks the
-- same as a live owner session on a phone or laptop — revoking by guess would log real devices
-- out. Those orphans expire on their own within 30 days of issue; nothing new is created.
--
-- Prod dry-run count, read-only, 2026-10-05: 6 tokens (The Snouts, workers; oldest 2026-09-06).
--
-- Run AFTER V142 is deployed (it writes revoked_reason), as the database owner (postgres):
--   psql "<connection string>" -v ON_ERROR_STOP=1 -f scripts/ops/refresh-token-orphan-cleanup.sql
--        # dry run: prints the rows and the count, ends with ROLLBACK
--   psql "<connection string>" -v ON_ERROR_STOP=1 -v commit=yes -f scripts/ops/refresh-token-orphan-cleanup.sql
-- Demo tenants are excluded (DemoSeeder owns their rows).
-- =============================================================================================

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE orphan_tokens ON COMMIT DROP AS
SELECT id, tenant_id, user_id, issued_at
FROM (
    SELECT rt.id, rt.tenant_id, rt.user_id, rt.issued_at,
           row_number() OVER (PARTITION BY rt.user_id ORDER BY rt.issued_at DESC, rt.id DESC) AS rn
    FROM refresh_tokens rt
    JOIN users   u ON u.id = rt.user_id AND u.tenant_id = rt.tenant_id
    JOIN tenants t ON t.id = rt.tenant_id
    WHERE rt.revoked_at IS NULL
      AND rt.expires_at > now()
      AND u.role = 'worker'
      AND NOT t.is_demo
) ranked
WHERE rn > 1;

\echo 'refresh-token-orphan-cleanup: tokens to revoke (tenant, user, token id prefix, issued):'
SELECT t.name AS tenant, left(o.user_id::text, 8) AS user8, left(o.id::text, 8) AS token8, o.issued_at
FROM orphan_tokens o JOIN tenants t ON t.id = o.tenant_id
ORDER BY t.name, o.user_id, o.issued_at;

UPDATE refresh_tokens rt
SET revoked_at = now(), revoked_reason = 'orphan_cleanup'
FROM orphan_tokens o
WHERE rt.id = o.id AND rt.revoked_at IS NULL;

-- Proof: every worker keeps exactly its newest live token; no owner / manager token was touched.
DO $$
DECLARE
    revoked int;
    planned int;
    left_over int;
BEGIN
    SELECT count(*) INTO planned FROM orphan_tokens;
    SELECT count(*) INTO revoked FROM refresh_tokens WHERE revoked_reason = 'orphan_cleanup'
                                                       AND id IN (SELECT id FROM orphan_tokens);
    SELECT count(*) INTO left_over FROM (
        SELECT rt.user_id FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id
        JOIN tenants t ON t.id = rt.tenant_id
        WHERE rt.revoked_at IS NULL AND rt.expires_at > now() AND u.role = 'worker' AND NOT t.is_demo
        GROUP BY rt.user_id HAVING count(*) > 1) x;
    IF revoked <> planned THEN
        RAISE EXCEPTION 'revoked % of % planned tokens', revoked, planned;
    END IF;
    IF left_over > 0 THEN
        RAISE EXCEPTION '% worker(s) still hold more than one live token', left_over;
    END IF;
    IF EXISTS (SELECT 1 FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id
               WHERE rt.revoked_reason = 'orphan_cleanup' AND u.role <> 'worker') THEN
        RAISE EXCEPTION 'an owner / manager token was revoked';
    END IF;
    RAISE NOTICE 'refresh-token-orphan-cleanup: % token(s) revoked', revoked;
END $$;

\if :{?commit}
\if :commit
COMMIT;
\echo 'refresh-token-orphan-cleanup: COMMITTED.'
\else
ROLLBACK;
\echo 'refresh-token-orphan-cleanup: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
\else
ROLLBACK;
\echo 'refresh-token-orphan-cleanup: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
