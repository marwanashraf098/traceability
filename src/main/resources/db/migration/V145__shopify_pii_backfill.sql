-- ============================================================
-- V145 — Build B data migration: strip stored email, backfill Shopify PII (runs on deploy)
-- ============================================================
-- Non-transactional (V145__shopify_pii_backfill.sql.conf): shopify_pii_backfill COMMITs after every
-- batch of 500 rows, so no tenant's orders are locked for the whole run. Idempotent — a re-run strips
-- nothing more and fills nothing more. See V144 for the rules.
--
-- Supabase sets statement_timeout = 2min server-wide (configuration file), and it applies to the postgres role
-- Flyway uses; the whole CALL is ONE statement. Lift it for this session only (this script runs on its own,
-- non-transactional connection) and restore it afterwards. Batches already committed stay committed if the
-- run is interrupted; a re-run skips them.
SET statement_timeout = 0;
CALL shopify_pii_backfill(500);
RESET statement_timeout;
