-- =============================================================================================
-- Encryption key rotation K2b — backup, verification and (at K3) drop of the stored ciphertexts.
--
-- Run as the database owner (postgres) with psql; pick ONE step with -v step=…:
--
--   -v step=backup   creates ops_backup_20261128_ciphertexts: one row per stores / courier_accounts /
--                    return_requests row holding any encrypted value (id, tenant_id, every encrypted
--                    column, and courier_accounts.key_auth_fail_key_hash), REVOKE ALL from app_user and
--                    PUBLIC, RLS on with no policy. Refuses if the table already exists. Run it right
--                    BEFORE the K2b apply.
--   -v step=verify   count-only: per column, v2 vs legacy (and shopify_pending_links, which K2b leaves
--                    to expire), plus the backup table's row count and app_user's privileges on it.
--                    Read-only; safe any time.
--   -v step=drop     K3 only — drops the backup table. Do NOT run before K3 is decided.
--
--   docker run --rm -e PGUSER -e PGPASSWORD -v ~/traceability/scripts/ops:/ops postgres:16-alpine \
--     psql "$PGURI" -v ON_ERROR_STOP=1 -v step=verify -f /ops/2026-11-28-k2-reencryption.sql
--
-- The backup holds LEGACY ciphertexts, i.e. values sealed with the zero key: anyone who can read the
-- table can decrypt them. Same exposure as the live columns before K2b — keep it owner-only and drop it
-- at K3. Never SELECT the value columns; counts only.
--
-- Restore (only if the V2 key were lost after K2b — never otherwise; legacy values open with the K1
-- image as long as APP_ENCRYPTION_KEY is unchanged): see the commented block at the end.
-- Not a Flyway migration.
-- =============================================================================================

\if :{?step}
\else
  \echo 'Pass -v step=backup | verify | drop'
  \quit
\endif

SELECT :'step' = 'backup' AS is_backup, :'step' = 'verify' AS is_verify, :'step' = 'drop' AS is_drop \gset

\if :is_backup
BEGIN;
DO $$
BEGIN
    IF to_regclass('public.ops_backup_20261128_ciphertexts') IS NOT NULL THEN
        RAISE EXCEPTION 'ops_backup_20261128_ciphertexts already exists — not overwriting it';
    END IF;
END $$;

CREATE TABLE ops_backup_20261128_ciphertexts AS
SELECT 'stores'::text AS source_table, s.id, s.tenant_id,
       s.access_token_encrypted, s.refresh_token_encrypted, s.client_id_encrypted, s.api_secret_encrypted,
       NULL::text AS api_key_encrypted, NULL::text AS key_auth_fail_key_hash, NULL::text AS refund_details_encrypted,
       now() AS backed_up_at
  FROM stores s
 WHERE s.access_token_encrypted IS NOT NULL OR s.refresh_token_encrypted IS NOT NULL
    OR s.client_id_encrypted IS NOT NULL OR s.api_secret_encrypted IS NOT NULL
UNION ALL
SELECT 'courier_accounts', c.id, c.tenant_id, NULL, NULL, NULL, NULL,
       c.api_key_encrypted, c.key_auth_fail_key_hash, NULL, now()
  FROM courier_accounts c
 WHERE c.api_key_encrypted IS NOT NULL
UNION ALL
SELECT 'return_requests', r.id, r.tenant_id, NULL, NULL, NULL, NULL, NULL, NULL,
       r.refund_details_encrypted, now()
  FROM return_requests r
 WHERE r.refund_details_encrypted IS NOT NULL;

ALTER TABLE ops_backup_20261128_ciphertexts ADD PRIMARY KEY (source_table, id);
-- Default privileges may grant app_user on new tables: take everything away, and RLS with no policy on top.
REVOKE ALL ON ops_backup_20261128_ciphertexts FROM PUBLIC;
REVOKE ALL ON ops_backup_20261128_ciphertexts FROM app_user;
ALTER TABLE ops_backup_20261128_ciphertexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops_backup_20261128_ciphertexts FORCE ROW LEVEL SECURITY;
COMMENT ON TABLE ops_backup_20261128_ciphertexts IS
    'K2b (2026-11-28) pre-re-encryption copy of every encrypted column. Owner only. Legacy values are '
    'zero-key ciphertexts (readable by whoever holds this table). Drop at K3 (scripts/ops/2026-11-28-k2-reencryption.sql step=drop).';

DO $$
BEGIN
    IF has_table_privilege('app_user', 'ops_backup_20261128_ciphertexts', 'SELECT')
       OR has_table_privilege('app_user', 'ops_backup_20261128_ciphertexts', 'INSERT')
       OR has_table_privilege('app_user', 'ops_backup_20261128_ciphertexts', 'UPDATE')
       OR has_table_privilege('app_user', 'ops_backup_20261128_ciphertexts', 'DELETE') THEN
        RAISE EXCEPTION 'app_user still has a privilege on the backup table';
    END IF;
END $$;

SELECT source_table, count(*) AS rows_backed_up
  FROM ops_backup_20261128_ciphertexts GROUP BY source_table ORDER BY source_table;
COMMIT;
\endif

\if :is_verify
-- Count-only verification, per column. After K2b: legacy = 0 everywhere except shopify_pending_links.
-- shopify_pending_links has FORCE ROW LEVEL SECURITY: its counts are real only when this role bypasses RLS
-- (role_bypasses_rls = t below); the other tables are not forced, so their owner sees every row.
SELECT current_user AS role, (SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname = current_user) AS role_bypasses_rls;
SELECT 'stores' AS table_name, 'access_token_encrypted' AS column_name,
       count(*) FILTER (WHERE access_token_encrypted LIKE 'v2:%')     AS v2,
       count(*) FILTER (WHERE access_token_encrypted NOT LIKE 'v2:%') AS legacy
  FROM stores
UNION ALL SELECT 'stores', 'refresh_token_encrypted',
       count(*) FILTER (WHERE refresh_token_encrypted LIKE 'v2:%'),
       count(*) FILTER (WHERE refresh_token_encrypted NOT LIKE 'v2:%') FROM stores
UNION ALL SELECT 'stores', 'client_id_encrypted',
       count(*) FILTER (WHERE client_id_encrypted LIKE 'v2:%'),
       count(*) FILTER (WHERE client_id_encrypted NOT LIKE 'v2:%') FROM stores
UNION ALL SELECT 'stores', 'api_secret_encrypted',
       count(*) FILTER (WHERE api_secret_encrypted LIKE 'v2:%'),
       count(*) FILTER (WHERE api_secret_encrypted NOT LIKE 'v2:%') FROM stores
UNION ALL SELECT 'courier_accounts', 'api_key_encrypted',
       count(*) FILTER (WHERE api_key_encrypted LIKE 'v2:%'),
       count(*) FILTER (WHERE api_key_encrypted NOT LIKE 'v2:%') FROM courier_accounts
UNION ALL SELECT 'return_requests', 'refund_details_encrypted',
       count(*) FILTER (WHERE refund_details_encrypted LIKE 'v2:%'),
       count(*) FILTER (WHERE refund_details_encrypted NOT LIKE 'v2:%') FROM return_requests
UNION ALL SELECT 'shopify_pending_links (left to expire)', 'access_token_encrypted',
       count(*) FILTER (WHERE access_token_encrypted LIKE 'v2:%'),
       count(*) FILTER (WHERE access_token_encrypted NOT LIKE 'v2:%') FROM shopify_pending_links
UNION ALL SELECT 'shopify_pending_links (left to expire)', 'refresh_token_encrypted',
       count(*) FILTER (WHERE refresh_token_encrypted LIKE 'v2:%'),
       count(*) FILTER (WHERE refresh_token_encrypted NOT LIKE 'v2:%') FROM shopify_pending_links;

-- Bosta key health carried over: every known-bad row's hash matches its CURRENT ciphertext.
SELECT count(*) FILTER (WHERE key_auth_bad_at IS NOT NULL)                                       AS known_bad_rows,
       count(*) FILTER (WHERE key_auth_bad_at IS NOT NULL AND key_auth_fail_key_hash
                              = encode(sha256(convert_to(api_key_encrypted, 'UTF8')), 'hex'))   AS known_bad_hash_matches
  FROM courier_accounts;

SELECT to_regclass('public.ops_backup_20261128_ciphertexts') IS NOT NULL AS backup_exists;
SELECT to_regclass('public.ops_backup_20261128_ciphertexts') IS NOT NULL AS has_backup \gset
\if :has_backup
SELECT source_table, count(*) AS rows FROM ops_backup_20261128_ciphertexts GROUP BY source_table ORDER BY source_table;
SELECT has_table_privilege('app_user', 'ops_backup_20261128_ciphertexts', 'SELECT') AS app_user_can_select;
\endif
\endif

\if :is_drop
BEGIN;
DROP TABLE IF EXISTS ops_backup_20261128_ciphertexts;
SELECT to_regclass('public.ops_backup_20261128_ciphertexts') IS NULL AS backup_dropped;
COMMIT;
\endif

-- ── Restore — ONLY if the V2 key were lost after K2b. Not run by any step. ─────────────────
-- Puts back a backed-up legacy value only where the column now holds a v2 value the lost key can't open.
-- BEGIN;
-- UPDATE stores s SET
--   access_token_encrypted  = CASE WHEN s.access_token_encrypted  LIKE 'v2:%' THEN b.access_token_encrypted  ELSE s.access_token_encrypted  END,
--   refresh_token_encrypted = CASE WHEN s.refresh_token_encrypted LIKE 'v2:%' THEN b.refresh_token_encrypted ELSE s.refresh_token_encrypted END,
--   client_id_encrypted     = CASE WHEN s.client_id_encrypted     LIKE 'v2:%' THEN b.client_id_encrypted     ELSE s.client_id_encrypted     END,
--   api_secret_encrypted    = CASE WHEN s.api_secret_encrypted    LIKE 'v2:%' THEN b.api_secret_encrypted    ELSE s.api_secret_encrypted    END
--   FROM ops_backup_20261128_ciphertexts b WHERE b.source_table = 'stores' AND b.id = s.id;
-- UPDATE courier_accounts c SET api_key_encrypted = b.api_key_encrypted, key_auth_fail_key_hash = b.key_auth_fail_key_hash
--   FROM ops_backup_20261128_ciphertexts b WHERE b.source_table = 'courier_accounts' AND b.id = c.id AND c.api_key_encrypted LIKE 'v2:%';
-- UPDATE return_requests r SET refund_details_encrypted = b.refund_details_encrypted
--   FROM ops_backup_20261128_ciphertexts b WHERE b.source_table = 'return_requests' AND b.id = r.id AND r.refund_details_encrypted LIKE 'v2:%';
-- COMMIT;
-- (A token the app rotated AFTER the backup is lost either way — Shopify then needs a reconnect.)
