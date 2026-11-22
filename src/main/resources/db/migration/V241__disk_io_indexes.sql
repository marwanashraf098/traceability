-- V241 — Supabase Disk IO budget (2026-11-22). Three indexes for the three biggest readers on prod (pg_stat_statements):
--
-- 1. jobrunr_jobs (recurringjobid, createdat DESC) and (recurringjobid, state, updatedat DESC): the watchdog's
--    liveness check (WatchdogDbProbe, every 60 s) now asks for the newest created / PROCESSING / SUCCEEDED / FAILED
--    job of one recurring job (ORDER BY … DESC LIMIT 1). With these each answer is one index-only descent instead of
--    an aggregate over every row of that recurring job (~1,400–1,750 blocks a call on prod; bench 2,457 → 16).
--    Partial (recurringjobid IS NOT NULL): one-off jobs (webhook processors — most of the table's writes) never
--    touch them.
-- 2. orders (tenant_id, created_at): Main reconcile Part 1 reads the tenant's orders created in its 30-day window
--    every queue run (MainReconcilePart1Lines). There was no index on created_at; the plan was a seq scan or the
--    primary key.
-- 3. shopify_webhook_events (processed_at) WHERE processed_at IS NOT NULL: the watchdog's "was any OTHER webhook
--    processed in the last few minutes" check (WatchdogDbProbe, only while events are stuck) had no usable index —
--    the existing processed_at index is partial on IS NULL. It seq-scanned the table, and read ALL of it exactly
--    when nothing had been processed (a stall): bench 24,940 blocks a call, every 60 s while stuck.
--
-- Non-transactional (V241__disk_io_indexes.sql.conf): CREATE INDEX CONCURRENTLY never blocks writers on orders or
-- jobrunr_jobs (V240 pattern; spring.flyway.postgresql.transactional-lock: false). Re-runnable after a failure
-- (`flyway repair`): a leftover (possibly INVALID) build is dropped first.

DROP INDEX CONCURRENTLY IF EXISTS jobrunr_jobs_rci_created_idx;
CREATE INDEX CONCURRENTLY jobrunr_jobs_rci_created_idx
    ON jobrunr_jobs (recurringjobid, createdat DESC)
    WHERE recurringjobid IS NOT NULL;

DROP INDEX CONCURRENTLY IF EXISTS jobrunr_jobs_rci_state_updated_idx;
CREATE INDEX CONCURRENTLY jobrunr_jobs_rci_state_updated_idx
    ON jobrunr_jobs (recurringjobid, state, updatedat DESC)
    WHERE recurringjobid IS NOT NULL;

DROP INDEX CONCURRENTLY IF EXISTS orders_tenant_created_at_idx;
CREATE INDEX CONCURRENTLY orders_tenant_created_at_idx
    ON orders (tenant_id, created_at);

DROP INDEX CONCURRENTLY IF EXISTS shopify_webhook_events_processed_recent_idx;
CREATE INDEX CONCURRENTLY shopify_webhook_events_processed_recent_idx
    ON shopify_webhook_events (processed_at)
    WHERE processed_at IS NOT NULL;
