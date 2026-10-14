-- =============================================================================================
-- 2026-10-14 — repair the decrements skipped as 'not_seeded'. Deploy V167 (the counting gate) FIRST.
--
-- Before V167, "Shopify counts the main warehouse" meant "the tenant has an applied initial seed".
-- Every tenant onboarded the normal way (connect with 0, receive into Traced) was never seeded, yet
-- its receiving +1s applied — so every Lookup departure (void / hold / damage / write-off) and every
-- transfer send was skipped 'not_seeded' and Shopify stayed too high. V167's gate
-- (shopify_counting_source) counts an applied receiving claim too; this script re-sends what the old
-- gate skipped, through the EXISTING claim rows. It never calls Shopify: it reopens rows to 'queued'
-- and the app's sweeps send them (PieceShopifySweepJob → pushVoidCorrection / pushHoldEnter /
-- moveAvailableToDamaged / pushPieceWriteOff; TransferShopifySweepJob → pushTransferOut) — single
-- attempt, failed_ambiguous never re-sent, Traced Main Warehouse GID only, no new call site.
--
-- Everything is computed at run time from the 'skipped' / 'not_seeded' rows. Per unit (one piece of
-- one claim row) the decision is 'reopen' or the reason it stays skipped:
--   tenant_not_counting          Shopify still isn't counting for this tenant — nothing of its is there
--   return_claim_never_reopened  a +1 / reverse move skipped not_seeded: its departure was never sent
--   not_at_main_check_by_hand    a Lookup departure of a piece away from the main warehouse
--   arrival_not_counted          the piece's own receiving +1 never reached Shopify (PieceShopifyRules
--                                .arrival: receiving claim applied / superseded_by_seed, or received
--                                before the seed — inlined below)
--   came_back_available          the Lookup-departed piece is back to available since (hold → release)
--   came_back_to_main            the transfer piece came back good to main (round trip, or a later
--                                bring-back) — its +1 was skipped too; net zero
--   shopify_at_or_below_traced   per variant: Shopify available − N would be below Traced available
--                                at main (someone already fixed it by hand) — the variant's rows stay
-- An in-flight bring-back (sent, not received) stays IN the −N: the pushed claim gives its +1 on arrival.
--
-- LEVELS: per variant, a variant_shopify_levels row for the Traced location synced AFTER the
-- variant's latest piece event is required. Missing / stale → the script prints them and ABORTS the
-- whole run (nothing written): run the inventory level sync ("Sync now"), then run this again.
--
-- Reopened rows keep their skip reason and error; payload.repair records {from, reason, error, at,
-- by, script}; transfer rows also get deltas / piece_ids rebuilt and source 'repair'. Rows that stay
-- skipped get payload.repair = {decision, at, script}. Re-running never re-sends: only rows still
-- 'skipped' + 'not_seeded' are considered, and a reopened row is 'queued' (or further along).
--
-- psql as the database owner. Dry run by default (ROLLBACK); optional -v tenant_id=<uuid>:
--   psql "<conn>" -v ON_ERROR_STOP=1 -f scripts/ops/2026-10-14-not-seeded-repair.sql
--   psql "<conn>" -v ON_ERROR_STOP=1 -v commit=yes -f scripts/ops/2026-10-14-not-seeded-repair.sql
-- =============================================================================================

\set ON_ERROR_STOP on
\if :{?tenant_id}
\else
\set tenant_id ''
\endif

BEGIN;

DO $$
BEGIN
    IF to_regprocedure('shopify_counting_source(uuid)') IS NULL THEN
        RAISE EXCEPTION 'not-seeded-repair: V167 (shopify_counting_source) is not applied — deploy the counting gate first';
    END IF;
END $$;

CREATE TEMP TABLE nsr_units ON COMMIT DROP AS
-- >>> PLAN (one query; also run read-only as the prod dry run)
WITH tt AS (
    SELECT t.id AS tenant_id, t.name AS tenant, l.id AS main_id,
           regexp_replace(l.shopify_location_id, '^gid://shopify/Location/', '') AS level_location,
           shopify_counting_source(t.id) AS counting,
           (SELECT MIN(a.created_at) FROM shopify_inventory_adjustments a
             WHERE a.tenant_id = t.id AND a.trigger_type = 'initial_seed' AND a.status = 'applied') AS seed_at
    FROM tenants t
    JOIN locations l ON l.tenant_id = t.id AND l.is_fulfillment
    WHERE NOT t.is_demo
      AND (:'tenant_id' = '' OR t.id::text = :'tenant_id')
),
units AS (
    SELECT sia.tenant_id, 'piece'::text AS kind, sia.id::text AS row_id, NULL::uuid AS transfer_id,
           sia.trigger_type AS detail, split_part(sia.trigger_id, ':', 1) AS piece_id, sia.variant_id,
           sia.created_at AS departed_at, NULL::text AS outcome
    FROM shopify_inventory_adjustments sia JOIN tt ON tt.tenant_id = sia.tenant_id
    WHERE sia.status = 'skipped' AND sia.skip_reason = 'not_seeded'
    UNION ALL
    SELECT y.tenant_id, 'transfer', y.id::text, y.transfer_id, tr.transfer_mode, tp.piece_id, p.variant_id,
           -- departure time: TransferShopifySync.departedAtSql, verbatim
           (CASE WHEN tr.transfer_mode = 'relocate_out' THEN tr.closed_at
                 ELSE COALESCE(tr.sent_at, tr.reconcile_started_at, tr.created_at) END),
           tp.outcome
    FROM transfer_shopify_syncs y
    JOIN tt ON tt.tenant_id = y.tenant_id
    JOIN transfers tr ON tr.id = y.transfer_id
    -- the pieces that left the MAIN warehouse on this trip (claimSend's set)
    JOIN transfer_pieces tp ON tp.transfer_id = y.transfer_id AND tp.tenant_id = y.tenant_id
                           AND tp.from_location_id = tt.main_id
    JOIN pieces p ON p.id = tp.piece_id AND p.tenant_id = tp.tenant_id
    WHERE y.status = 'skipped' AND y.reason = 'not_seeded'
),
decided AS (
    SELECT u.*, tt.tenant, tt.main_id, tt.level_location,
           CASE
             WHEN tt.counting IS NULL THEN 'tenant_not_counting'
             WHEN u.kind = 'piece'
                  AND u.detail NOT IN ('void_correction', 'hold_enter', 'damage_move', 'piece_write_off')
               THEN 'return_claim_never_reopened'
             WHEN u.kind = 'piece' AND p.current_location_id IS DISTINCT FROM tt.main_id THEN 'not_at_main_check_by_hand'
             -- PieceShopifyRules.arrival, inlined: the piece's receiving claim at main applied or was
             -- superseded by the seed, or it was received before the seed
             WHEN NOT (EXISTS (SELECT 1 FROM shopify_inventory_adjustments r
                               WHERE r.tenant_id = p.tenant_id AND r.trigger_type = 'receiving_session'
                                 AND r.trigger_id = p.receipt_id::text AND r.variant_id = p.variant_id
                                 AND r.location_id = tt.main_id AND r.status IN ('applied', 'superseded_by_seed'))
                       OR (tt.seed_at IS NOT NULL AND p.created_at < tt.seed_at))
               THEN 'arrival_not_counted'
             WHEN u.kind = 'piece' AND EXISTS (SELECT 1 FROM piece_events e
                                               WHERE e.piece_id = p.id AND e.tenant_id = p.tenant_id
                                                 AND e.to_status = 'available' AND e.occurred_at > u.departed_at)
               THEN 'came_back_available'
             WHEN u.kind = 'transfer' AND u.outcome = 'returned_good' THEN 'came_back_to_main'
             WHEN u.kind = 'transfer' AND EXISTS (SELECT 1 FROM transfer_pieces tb JOIN transfers b ON b.id = tb.transfer_id
                                                  WHERE tb.piece_id = u.piece_id AND tb.tenant_id = u.tenant_id
                                                    AND b.transfer_mode = 'relocate_return' AND tb.outcome = 'returned_good'
                                                    AND b.created_at > u.departed_at)
               THEN 'came_back_to_main'
           END AS unit_reason
    FROM units u
    JOIN tt ON tt.tenant_id = u.tenant_id
    JOIN pieces p ON p.id = u.piece_id AND p.tenant_id = u.tenant_id
),
per_variant AS (
    SELECT d.tenant_id, d.variant_id, COUNT(*) AS n,
           MAX(lvl.available) AS shopify_now, MAX(lvl.synced_at) AS synced_at,
           (SELECT COUNT(*) FROM pieces p WHERE p.tenant_id = d.tenant_id AND p.variant_id = d.variant_id
               AND p.current_location_id = d.main_id AND p.status = 'available') AS traced,
           (SELECT MAX(e.occurred_at) FROM piece_events e JOIN pieces p ON p.id = e.piece_id AND p.tenant_id = e.tenant_id
             WHERE e.tenant_id = d.tenant_id AND p.variant_id = d.variant_id) AS last_event
    FROM decided d
    LEFT JOIN variant_shopify_levels lvl ON lvl.tenant_id = d.tenant_id AND lvl.variant_id = d.variant_id
                                         AND lvl.location_id = d.level_location
    WHERE d.unit_reason IS NULL
    GROUP BY d.tenant_id, d.variant_id, d.main_id
)
SELECT d.tenant, d.tenant_id, d.kind, d.row_id, d.transfer_id, d.detail, d.piece_id, d.variant_id, v.sku,
       d.departed_at, pv.n AS variant_n, pv.shopify_now, pv.traced, pv.synced_at, pv.last_event,
       (pv.variant_id IS NOT NULL AND (pv.synced_at IS NULL OR pv.last_event IS NULL OR pv.synced_at <= pv.last_event)) AS stale,
       COALESCE(d.unit_reason,
                CASE WHEN pv.shopify_now IS NOT NULL AND pv.shopify_now - pv.n < pv.traced
                     THEN 'shopify_at_or_below_traced' END,
                'reopen') AS decision
FROM decided d
JOIN variants v ON v.id = d.variant_id
LEFT JOIN per_variant pv ON pv.tenant_id = d.tenant_id AND pv.variant_id = d.variant_id
-- <<< PLAN
;

\echo '== Per tenant, per SKU — units to re-send (−N), Shopify available now → after, Traced available at main'
SELECT tenant, sku, COUNT(*) AS minus_n, MAX(shopify_now) AS shopify_now, MAX(shopify_now) - COUNT(*) AS shopify_after,
       MAX(traced) AS traced, MAX(synced_at) AS level_synced_at, MAX(last_event) AS last_piece_event, bool_or(stale) AS stale
FROM nsr_units WHERE decision = 'reopen'
GROUP BY tenant, tenant_id, sku ORDER BY tenant, sku;

\echo '== Per tenant — total units to re-send'
SELECT tenant, COUNT(*) AS minus_n FROM nsr_units WHERE decision = 'reopen' GROUP BY tenant ORDER BY tenant;

\echo '== Excluded — stays skipped (why)'
SELECT tenant, kind, row_id, detail, piece_id, sku, decision,
       CASE WHEN decision = 'shopify_at_or_below_traced'
            THEN 'Shopify ' || shopify_now || ', Traced ' || traced || ', would send ' || variant_n END AS detail_levels
FROM nsr_units WHERE decision <> 'reopen' ORDER BY tenant, decision, sku;

\echo '== Stale or missing Shopify levels (any row here aborts the run — sync levels first)'
SELECT tenant, sku, shopify_now, synced_at AS level_synced_at, last_event AS last_piece_event
FROM nsr_units WHERE stale GROUP BY tenant, sku, shopify_now, synced_at, last_event ORDER BY tenant, sku;

DO $$
DECLARE n int;
BEGIN
    SELECT COUNT(DISTINCT (tenant_id, variant_id)) INTO n FROM nsr_units WHERE stale;
    IF n > 0 THEN
        RAISE EXCEPTION 'not-seeded-repair: % variant(s) have no Shopify level synced after their latest piece event — run the inventory level sync, then run this again. Nothing written.', n;
    END IF;
END $$;

-- ── Apply: reopen the rows (the app's sweeps send them) ──────────────────────────────────────

-- Lookup departures: one unit per row.
UPDATE shopify_inventory_adjustments sia
SET status = 'queued',
    payload = COALESCE(sia.payload, '{}'::jsonb) || jsonb_build_object('repair', jsonb_build_object(
        'from', 'not_seeded', 'reason', sia.skip_reason, 'error', sia.error, 'at', now(), 'by', 'ops',
        'script', '2026-10-14-not-seeded-repair'))
FROM nsr_units u
WHERE u.kind = 'piece' AND u.decision = 'reopen' AND sia.id::text = u.row_id
  AND sia.status = 'skipped' AND sia.skip_reason = 'not_seeded';

UPDATE shopify_inventory_adjustments sia
SET payload = COALESCE(sia.payload, '{}'::jsonb) || jsonb_build_object('repair', jsonb_build_object(
        'decision', u.decision, 'at', now(), 'script', '2026-10-14-not-seeded-repair'))
FROM nsr_units u
WHERE u.kind = 'piece' AND u.decision <> 'reopen' AND sia.id::text = u.row_id
  AND sia.status = 'skipped' AND sia.skip_reason = 'not_seeded'
  AND sia.payload->'repair'->>'decision' IS DISTINCT FROM u.decision;   -- a re-run leaves an unchanged note alone

-- Transfer sends: deltas / piece_ids rebuilt from the units that are re-sent.
UPDATE transfer_shopify_syncs y
SET status = 'queued', source = 'repair', deltas = r.deltas, piece_ids = r.piece_ids,
    payload = y.payload || jsonb_build_object('repair', jsonb_build_object(
        'from', 'not_seeded', 'reason', y.reason, 'error', y.error, 'at', now(), 'by', 'ops',
        'script', '2026-10-14-not-seeded-repair', 'excluded', r.excluded))
FROM (SELECT row_id,
             (SELECT jsonb_object_agg(variant_id::text, k) FROM (SELECT variant_id, COUNT(*) AS k FROM nsr_units x
                 WHERE x.row_id = u.row_id AND x.kind = 'transfer' AND x.decision = 'reopen' GROUP BY variant_id) g) AS deltas,
             jsonb_agg(piece_id ORDER BY piece_id) FILTER (WHERE decision = 'reopen') AS piece_ids,
             COALESCE(jsonb_agg(jsonb_build_object('piece_id', piece_id, 'variant_id', variant_id, 'reason', decision))
                      FILTER (WHERE decision <> 'reopen'), '[]'::jsonb) AS excluded
      FROM nsr_units u WHERE kind = 'transfer' GROUP BY row_id
      HAVING bool_or(decision = 'reopen')) r
WHERE y.id::text = r.row_id AND y.status = 'skipped' AND y.reason = 'not_seeded';

UPDATE transfer_shopify_syncs y
SET payload = y.payload || jsonb_build_object('repair', jsonb_build_object(
        'decision', 'nothing_to_send', 'at', now(), 'script', '2026-10-14-not-seeded-repair',
        'excluded', r.excluded))
FROM (SELECT row_id, jsonb_agg(jsonb_build_object('piece_id', piece_id, 'variant_id', variant_id, 'reason', decision)) AS excluded
      FROM nsr_units WHERE kind = 'transfer' GROUP BY row_id HAVING NOT bool_or(decision = 'reopen')) r
WHERE y.id::text = r.row_id AND y.status = 'skipped' AND y.reason = 'not_seeded'
  AND y.payload->'repair'->'excluded' IS DISTINCT FROM r.excluded;      -- a re-run leaves an unchanged note alone

\echo '== Reopened'
SELECT 'piece claims' AS what, COUNT(*) FROM shopify_inventory_adjustments
 WHERE status = 'queued' AND payload->'repair'->>'script' = '2026-10-14-not-seeded-repair'
UNION ALL
SELECT 'transfer claims', COUNT(*) FROM transfer_shopify_syncs
 WHERE status = 'queued' AND payload->'repair'->>'script' = '2026-10-14-not-seeded-repair';

\if :{?commit}
\if :commit
COMMIT;
\echo 'not-seeded-repair: COMMITTED — the piece and transfer sweeps send the queued claims within 10 minutes.'
\else
ROLLBACK;
\echo 'not-seeded-repair: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
\else
ROLLBACK;
\echo 'not-seeded-repair: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
