-- Main reconcile (V200, R1 report-only) — summary of the first runs, per store. READ ONLY: SELECTs only, no writes.
-- Run as the owner (psql or the Supabase SQL editor); every query carries its own statement_timeout.
-- "Latest sweep" = each tenant's most recent completed full sweep (the per-variant runs only see what changed).

SET statement_timeout = '30s';

-- 1. Runs per store: how many, of which kind, how they ended (skipped = store-level reason, e.g. location_inactive).
SELECT t.name AS store, r.kind, r.status, r.skip_reason, COUNT(*) AS runs,
       MIN(r.started_at) AS first_run, MAX(r.started_at) AS last_run,
       ROUND(AVG(EXTRACT(EPOCH FROM r.finished_at - r.started_at))::numeric, 1) AS avg_seconds,
       MAX(r.error) FILTER (WHERE r.status = 'failed') AS a_failure
FROM main_reconcile_runs r JOIN tenants t ON t.id = r.tenant_id
GROUP BY 1, 2, 3, 4 ORDER BY 1, 2, 3;

-- 2. Latest sweep per store: variants checked / in sync / skipped (by reason) / differences / confirmed / units.
WITH latest AS (
    SELECT DISTINCT ON (tenant_id) * FROM main_reconcile_runs
    WHERE kind = 'sweep' AND status = 'completed' ORDER BY tenant_id, started_at DESC)
SELECT t.name AS store, l.started_at, l.variants_checked, l.variants_in_sync, l.variants_skipped,
       l.skipped_by_reason, l.differences, l.confirmed, l.confirmed_units_up, l.confirmed_units_down,
       l.damaged_mismatches
FROM latest l JOIN tenants t ON t.id = l.tenant_id
ORDER BY l.differences DESC, t.name;

-- 3. Skipped by reason, one row per (store, reason), latest sweep.
WITH latest AS (
    SELECT DISTINCT ON (tenant_id) * FROM main_reconcile_runs
    WHERE kind = 'sweep' AND status = 'completed' ORDER BY tenant_id, started_at DESC)
SELECT t.name AS store, k.reason, k.n::int AS variants
FROM latest l JOIN tenants t ON t.id = l.tenant_id
CROSS JOIN LATERAL jsonb_each_text(l.skipped_by_reason) AS k(reason, n)
ORDER BY 1, 3 DESC;

-- 4. Confirmed differences per store — each variant's CURRENT state (its newest diff row in any run), so a
--    variant confirmed by a per-variant run after the sweep counts too. units_up = Shopify Main below Traced.
WITH cur AS (
    SELECT DISTINCT ON (d.tenant_id, d.variant_id) d.*
    FROM main_reconcile_diffs d JOIN main_reconcile_runs r ON r.id = d.run_id
    ORDER BY d.tenant_id, d.variant_id, d.created_at DESC)
SELECT t.name AS store,
       COUNT(*) FILTER (WHERE c.status = 'confirmed') AS confirmed_variants,
       COALESCE(SUM(c.delta) FILTER (WHERE c.status = 'confirmed' AND c.delta > 0), 0) AS units_up,
       COALESCE(-SUM(c.delta) FILTER (WHERE c.status = 'confirmed' AND c.delta < 0), 0) AS units_down,
       COUNT(*) FILTER (WHERE c.status = 'unconfirmed') AS unconfirmed_variants,
       COUNT(*) FILTER (WHERE c.status = 'skipped') AS skipped_variants
FROM cur c JOIN tenants t ON t.id = c.tenant_id
WHERE NOT EXISTS (   -- a variant checked again since and found in sync has no newer diff row: drop it
    SELECT 1 FROM main_reconcile_variant_state s
    WHERE s.tenant_id = c.tenant_id AND s.variant_id = c.variant_id AND s.last_checked_at > c.created_at)
GROUP BY 1 ORDER BY units_up + units_down DESC;

-- 5. Part 1 — would-be subtracts at Main (lines Traced shipped that Shopify fulfilled from another location),
--    and the outcome counts of the latest sweep.
SELECT t.name AS store, COUNT(*) AS claims, SUM(c.quantity) AS units, COUNT(DISTINCT c.order_id) AS orders,
       COUNT(DISTINCT c.variant_id) AS variants, MIN(c.created_at) AS first, MAX(c.created_at) AS last
FROM main_reconcile_part1_claims c JOIN tenants t ON t.id = c.tenant_id
WHERE c.status = 'report_only'
GROUP BY 1 ORDER BY units DESC;

WITH latest AS (
    SELECT DISTINCT ON (tenant_id) * FROM main_reconcile_runs
    WHERE kind = 'sweep' AND status = 'completed' ORDER BY tenant_id, started_at DESC)
SELECT t.name AS store, l.part1_summary
FROM latest l JOIN tenants t ON t.id = l.tenant_id
WHERE l.part1_summary <> '{}'::jsonb ORDER BY 1;

-- 6. The 20 biggest current differences (confirmed or not), with what makes up Traced's target.
WITH cur AS (
    SELECT DISTINCT ON (d.tenant_id, d.variant_id) d.*
    FROM main_reconcile_diffs d
    WHERE d.status IN ('confirmed', 'unconfirmed')
    ORDER BY d.tenant_id, d.variant_id, d.created_at DESC)
SELECT t.name AS store, v.sku, pr.title AS product, v.title AS variant, c.status, c.delta,
       c.target, c.shopify_on_hand, c.shopify_available, c.shopify_committed,
       c.custody_at_main, c.away_counted, c.fulfilled_from_main, c.part1_owed,
       c.shopify_damaged, c.traced_damaged, c.delta_since, c.created_at AS seen_at
FROM cur c
JOIN tenants t ON t.id = c.tenant_id
JOIN variants v ON v.id = c.variant_id
JOIN products pr ON pr.id = v.product_id
WHERE NOT EXISTS (
    SELECT 1 FROM main_reconcile_variant_state s
    WHERE s.tenant_id = c.tenant_id AND s.variant_id = c.variant_id AND s.last_checked_at > c.created_at)
ORDER BY abs(c.delta) DESC, t.name
LIMIT 20;

-- 7. Health: the debounce queue (variants waiting) and the oldest waiting one, per store.
SELECT t.name AS store, COUNT(*) FILTER (WHERE s.pending_since IS NOT NULL) AS queued,
       MIN(s.pending_since) AS oldest_queued, MAX(s.last_checked_at) AS last_checked
FROM main_reconcile_variant_state s JOIN tenants t ON t.id = s.tenant_id
GROUP BY 1 ORDER BY queued DESC;
