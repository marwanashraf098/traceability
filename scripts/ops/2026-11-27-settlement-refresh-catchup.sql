-- =============================================================================================
-- Settlement refresh catch-up (2026-11-27, analytics payout fixes slice 2) — ONE-TIME, DRY RUN FIRST.
--
-- Until slice 2, SettlementRefreshJob re-read a 'deposited' Bosta leg only on the day after the
-- tenant's single most frequent payout weekday (8-day safety net otherwise), so tenants Bosta pays on
-- other or several weekdays kept legs 'deposited' long after Bosta had paid them. Slice 2 reads a
-- deposited leg every day once its own next_cashout_date has passed — those legs are picked up by the
-- job by themselves after the deploy (column "due_by_new_rule" below), drained at most 100 per tenant
-- and 300 per hourly run at the Bosta limiter's BACKGROUND (lowest) priority.
--
-- This script reports, per tenant, what the job will re-read, the Bosta calls that costs and how
-- long it takes at the shared 0.75 req/s, and the number of hourly runs the caps imply.
-- With -v commit=yes it ALSO marks every other eligible deposited leg (no or a future
-- next_cashout_date, read within the last 8 days) as due now — settlement_refreshed_at = NULL, the
-- column the cadence reads; nothing else is written — so every deposited leg is checked once. It
-- never calls Bosta: the job does the reading, through its normal caps and limiter.
--
-- Eligible = SettlementRefreshJob.ELIGIBLE (keep in step): Bosta leg with a tracking number Bosta
-- still knows, terminal state, its order a merchant order (not portal pre-connect, not a shadow) that
-- isn't cancelled before Bosta's pickup (rule (c)), placed on / after the analytics floor (stores.orders_ingest_from, else the
-- analytics.floor-overrides day — prod: mmi24e-fx.myshopify.com=2026-07-02), tenant with an active
-- Bosta account; not a zero cash cycle.
--
-- Run as the DB owner (psql in a one-off postgres:16-alpine container on the app host):
--   psql "<owner conn>" -v ON_ERROR_STOP=1 -f /ops/2026-11-27-settlement-refresh-catchup.sql
--   psql "<owner conn>" -v ON_ERROR_STOP=1 -v commit=yes -f /ops/2026-11-27-settlement-refresh-catchup.sql
-- Dry run by default — ends with ROLLBACK unless -v commit=yes.
-- =============================================================================================

\set ON_ERROR_STOP on
SET statement_timeout = '60s';
BEGIN;

CREATE TEMP TABLE catchup_legs ON COMMIT DROP AS
SELECT s.id, s.tenant_id, s.next_cashout_date, s.settlement_refreshed_at,
       (s.settlement_refreshed_at IS NULL
        OR (s.next_cashout_date < (now() AT TIME ZONE 'Africa/Cairo')::date
            AND s.settlement_refreshed_at < now() - interval '20 hours')
        OR s.settlement_refreshed_at < now() - interval '8 days') AS due_by_new_rule
FROM shipments s
JOIN orders fo ON fo.id = s.order_id AND fo.tenant_id = s.tenant_id
              AND fo.external_id NOT LIKE 'internal:portal:%'
              AND fo.external_id NOT LIKE 'internal:shopify-exchange:%'
JOIN stores fst ON fst.id = fo.store_id
CROSS JOIN LATERAL (
    SELECT COALESCE(fst.orders_ingest_from,
                    CASE WHEN lower(fst.shop_domain) = 'mmi24e-fx.myshopify.com'
                         THEN '2026-07-02'::timestamp AT TIME ZONE 'Africa/Cairo' END) AS floor_at
) fl
WHERE s.provider = 'bosta' AND s.tracking_number IS NOT NULL AND s.provider_not_found_at IS NULL
  AND s.internal_state IN ('delivered', 'returned', 'lost', 'terminated')
  -- CancelledOrders.countsSql (rule (c)): not cancelled, or cancelled only after Bosta picked it up
  AND (NOT (fo.status = 'cancelled'::order_status OR fo.raw_cancelled) OR COALESCE((
        SELECT analytics_ts(fo.raw ->> 'cancelled_at') IS NULL OR analytics_ts(fo.raw ->> 'cancelled_at') >= MIN(pk.at)
        FROM shipments cs
        CROSS JOIN LATERAL (SELECT LEAST(cs.collected_from_business_at,
            (SELECT MIN(ch.occurred_at) FROM shipment_status_history ch WHERE ch.shipment_id = cs.id
             AND ch.internal_state IN ('with_courier', 'returning', 'returned', 'delivered', 'lost'))) AS at) pk
        WHERE cs.order_id = fo.id AND cs.provider = 'bosta' AND cs.shipment_leg = 'forward'
          AND COALESCE(cs.type_code, '10') NOT IN ('25', '30')
        HAVING MIN(pk.at) IS NOT NULL), false))
  AND (fl.floor_at IS NULL OR fo.placed_at >= fl.floor_at)
  AND s.settlement_status = 'deposited'
  AND NOT (s.cash_cycle_id IS NOT NULL AND s.deposited_amt = 0 AND s.cashout_txn_id IS NULL)
  AND s.tenant_id IN (SELECT tenant_id FROM courier_accounts WHERE provider = 'bosta' AND status = 'active');

\echo '--- per tenant: deposited legs, due by the new rule, extra marked due with commit=yes'
SELECT t.name, c.tenant_id,
       COUNT(*)                                                     AS deposited,
       COUNT(*) FILTER (WHERE c.due_by_new_rule)                    AS due_by_new_rule,
       COUNT(*) FILTER (WHERE NOT c.due_by_new_rule
                          AND (c.settlement_refreshed_at IS NULL
                               OR c.settlement_refreshed_at < now() - interval '20 hours')) AS extra_with_commit,
       COUNT(*) FILTER (WHERE c.next_cashout_date IS NULL)          AS no_next_cashout_date,
       CEIL(COUNT(*) FILTER (WHERE c.due_by_new_rule) / 100.0)::int AS runs_at_100_per_tenant
FROM catchup_legs c JOIN tenants t ON t.id = c.tenant_id
GROUP BY t.name, c.tenant_id
ORDER BY deposited DESC, t.name;

\echo '--- totals: Bosta calls (one v0 GET per leg) and time at 0.75 req/s, hourly runs at 300 per run'
SELECT COUNT(*) FILTER (WHERE due_by_new_rule)                                         AS calls_new_rule,
       ROUND(COUNT(*) FILTER (WHERE due_by_new_rule) / 0.75 / 60.0, 1)                AS minutes_new_rule,
       CEIL(COUNT(*) FILTER (WHERE due_by_new_rule) / 300.0)::int                     AS hourly_runs_new_rule,
       COUNT(*) FILTER (WHERE due_by_new_rule OR settlement_refreshed_at IS NULL
                          OR settlement_refreshed_at < now() - interval '20 hours')    AS calls_with_commit,
       ROUND(COUNT(*) FILTER (WHERE due_by_new_rule OR settlement_refreshed_at IS NULL
                          OR settlement_refreshed_at < now() - interval '20 hours') / 0.75 / 60.0, 1) AS minutes_with_commit,
       CEIL(COUNT(*) FILTER (WHERE due_by_new_rule OR settlement_refreshed_at IS NULL
                          OR settlement_refreshed_at < now() - interval '20 hours') / 300.0)::int AS hourly_runs_with_commit
FROM catchup_legs;

-- The write (kept inside the transaction; rolled back on a dry run): every eligible deposited leg not
-- read in the last 20 h and not already due becomes due now.
UPDATE shipments s SET settlement_refreshed_at = NULL
FROM catchup_legs c
WHERE s.id = c.id AND NOT c.due_by_new_rule
  AND c.settlement_refreshed_at < now() - interval '20 hours';

\if :{?commit}
\if :commit
COMMIT;
\echo 'settlement-refresh-catchup: COMMITTED — the next hourly runs re-read these legs.'
\else
ROLLBACK;
\echo 'settlement-refresh-catchup: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
\else
ROLLBACK;
\echo 'settlement-refresh-catchup: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
