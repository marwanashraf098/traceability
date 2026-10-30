-- =============================================================================================
-- 2026-10-30 — un-tag the 6 "Not Traced" false positives found by
-- scripts/ops/2026-10-30-not-traced-false-positives.sql (approved by Marawan 2026-10-30).
--
-- Before the returned-pieces fix (merge 16f4312), NotTracedTagger tagged an order once none of its
-- allocations was live; ReturnService.restock() releases the allocation of a piece that came back, so
-- the next Bosta event on a returned order stamped not_traced_at — one-way, never cleared. The fixed
-- tagger skips such orders. RUN THIS ONLY AFTER 16f4312 IS LIVE: the old tagger would re-tag these
-- orders on their next Bosta event.
--
-- Target = the read-only script's pattern, tightened:
--   not_traced_at IS NOT NULL
--   AND one of the order's allocations' pieces has a return_received piece event for THIS order
--   AND not_traced_at > that order's first return_received (the tag came from the released allocation)
--   AND a merchant order (never a portal pre-connect row).
-- Expected exactly these 6 (tenant name match is case-insensitive, by fragment):
--   Nuvira      #3146  #3149  #3245
--   The Snouts  #2212068474  #2212031474
--   BROEK       BRK-46507-EG
-- Anything else — a different count, a different order, a column other than not_traced_at changing —
-- RAISEs and the whole transaction rolls back.
--
-- What it writes: orders.not_traced_at := NULL for those 6 ids. Nothing else. not_traced_at has one
-- writer in the app (NotTracedTagger.maybeTagNotTraced, which only ever sets it while NULL) and two
-- one-time Flyway backfills (V57, V68 — already applied, never re-run); it is display-only (the Not
-- Traced chip / stepper via OrderStatusDeriver.notTraced) and gates nothing (FulfillService's pick
-- guards deliberately ignore it). The two triggers on orders: orders_strip_email_from_raw fires only on
-- UPDATE OF raw (not fired here); orders_keep_portal_delivery_redacted fires on every UPDATE but only
-- nulls portal_delivery on a redacted row — the before/after row check below proves it changed nothing.
-- No ledger, no piece_events, no audit rows, no Shopify / Bosta call.
--
-- Run as postgres (BYPASSRLS — reads every tenant). Dry run by default — ends with ROLLBACK unless
-- -v commit=yes:
--   psql "<connection string>" -f scripts/ops/2026-10-30-untag-not-traced-false-positives.sql
--   psql "<connection string>" -v commit=yes -f scripts/ops/2026-10-30-untag-not-traced-false-positives.sql
-- =============================================================================================

\set ON_ERROR_STOP on

BEGIN;

-- 1. The matches. orders, not merchant_orders: merchant_orders is a view over orders (V163); the portal
--    filter is applied here explicitly instead.
CREATE TEMP TABLE untag_targets ON COMMIT DROP AS
SELECT o.id AS order_id, o.tenant_id, t.name AS tenant, o.number, o.status::text AS order_status,
       o.not_traced_at, r.first_return_received_at, r.returned_pieces
FROM orders o
JOIN tenants t ON t.id = o.tenant_id
JOIN LATERAL (
    SELECT MIN(e.occurred_at) AS first_return_received_at, COUNT(DISTINCT a.piece_id) AS returned_pieces
    FROM order_items oi
    JOIN allocations a  ON a.order_item_id = oi.id
    JOIN piece_events e ON e.piece_id = a.piece_id
                       AND e.order_id = o.id
                       AND e.event_type = 'return_received'
    WHERE oi.order_id = o.id
) r ON r.first_return_received_at IS NOT NULL
WHERE o.not_traced_at IS NOT NULL
  AND o.not_traced_at > r.first_return_received_at
  AND o.external_id NOT LIKE 'internal:portal:%';

\echo '== Matches (expected: the 6 listed in the header)'
SELECT tenant, number, order_status, not_traced_at, first_return_received_at, returned_pieces, order_id
FROM untag_targets ORDER BY tenant, number;

-- 2. They must be exactly the expected 6 — same count, same (tenant, number) pairs.
DO $$
DECLARE
    expected_n int := 6;
    matched_n  int;
    missing    text;
    extra      text;
BEGIN
    CREATE TEMP TABLE untag_expected (tenant_fragment text, number text) ON COMMIT DROP;
    INSERT INTO untag_expected VALUES
        ('nuvira', '#3146'), ('nuvira', '#3149'), ('nuvira', '#3245'),
        ('snouts', '#2212068474'), ('snouts', '#2212031474'),
        ('broek',  'BRK-46507-EG');

    SELECT count(*) INTO matched_n FROM untag_targets;

    SELECT string_agg(x.tenant_fragment || ' ' || x.number, ', ') INTO missing
    FROM untag_expected x
    WHERE NOT EXISTS (SELECT 1 FROM untag_targets u
                      WHERE u.number = x.number AND u.tenant ILIKE '%' || x.tenant_fragment || '%');

    SELECT string_agg(u.tenant || ' ' || u.number, ', ') INTO extra
    FROM untag_targets u
    WHERE NOT EXISTS (SELECT 1 FROM untag_expected x
                      WHERE u.number = x.number AND u.tenant ILIKE '%' || x.tenant_fragment || '%');

    IF matched_n <> expected_n OR missing IS NOT NULL OR extra IS NOT NULL THEN
        RAISE EXCEPTION 'untag: matches differ from the expected 6 — matched %, missing [%], unexpected [%]. Nothing changed.',
            matched_n, coalesce(missing, ''), coalesce(extra, '');
    END IF;
END $$;

-- 3. Snapshot every other column of the 6 rows, to prove only not_traced_at changes.
CREATE TEMP TABLE untag_before ON COMMIT DROP AS
SELECT o.id, to_jsonb(o) - 'not_traced_at' AS rest
FROM orders o
WHERE o.id IN (SELECT order_id FROM untag_targets);

-- 4. The write — exactly 6 rows or everything rolls back.
DO $$
DECLARE
    n int;
BEGIN
    UPDATE orders o
    SET not_traced_at = NULL
    WHERE o.id IN (SELECT order_id FROM untag_targets)
      AND o.not_traced_at IS NOT NULL
      AND o.external_id NOT LIKE 'internal:portal:%';
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 6 THEN
        RAISE EXCEPTION 'untag: UPDATE touched % rows, expected exactly 6. Nothing changed.', n;
    END IF;
END $$;

-- 5. Nothing but not_traced_at changed (a trigger rewriting any other column would show here).
DO $$
DECLARE
    changed text;
BEGIN
    SELECT string_agg(b.id::text, ', ') INTO changed
    FROM untag_before b
    JOIN orders o ON o.id = b.id
    WHERE (to_jsonb(o) - 'not_traced_at') IS DISTINCT FROM b.rest;
    IF changed IS NOT NULL THEN
        RAISE EXCEPTION 'untag: a column other than not_traced_at changed on [%]. Nothing changed.', changed;
    END IF;
END $$;

\echo '== After: the 6 orders, not_traced_at must be NULL'
SELECT u.tenant, o.number, o.not_traced_at
FROM orders o
JOIN untag_targets u ON u.order_id = o.id
ORDER BY u.tenant, o.number;

-- 6. Commit only with -v commit=yes.
\if :{?commit}
\if :commit
COMMIT;
\echo 'untag: COMMITTED — 6 orders un-tagged.'
\else
ROLLBACK;
\echo 'untag: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
\else
ROLLBACK;
\echo 'untag: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
