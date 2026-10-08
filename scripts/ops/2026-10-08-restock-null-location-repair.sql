-- =============================================================================================
-- 2026-10-08 — repair for the restock NULL-location bug (diagnosis Issue 3).
--
-- Until the fix, Scan returns sent locationId:null and ReturnService.restock wrote it into
-- pieces.current_location_id. Every restocked piece lost its location: it vanished from stock
-- counts and from the Shopify seed, and its +1 was skipped (the restock location wasn't the main
-- warehouse). This script repairs what the code fix can't reach (the fix only stops new cases).
--
-- Scope: pieces whose current_location_id IS NULL and that have a 'restocked' event written with a
-- NULL location (only the buggy restock path ever writes that — so the NULL came from a restock).
--
--   Group A — still in the warehouse (available, reserved, packed, awaiting_pickup, on_hold, damaged,
--             return_pending_inspection): location set to the tenant's main warehouse, each with a
--             'location_corrected' piece event (actor NULL = system, from = to = current status,
--             metadata names this repair) — auditable, no status change, no ledger transition.
--   Group B — since left the warehouse (with_courier, delivered, lost, …): NO change, listed only.
--
--   Shopify +1 — one per buggy restock that happened AT/AFTER the tenant's main warehouse was linked
--             to Shopify (locations.shopify_synced_at; restocks before that are the seed's business —
--             see the shortfall report). Each is first run through the double-count guard (V150's
--             shopify_refund_restocked_units against Traced restocks already counted for the same
--             order + variant, in restock order): a unit the merchant already restocked through a
--             Shopify refund is recorded 'skipped_shopify_restocked'; every other one is queued as a
--             'failed' / never_sent increment claim due now — the app's existing increment retry job
--             sends it (same claim key, attempts, backoff and alerts as any increment). This script
--             never calls Shopify itself and never decrements anything.
--   Seed shortfall — REPORT ONLY: per linked tenant and variant, Group A pieces that were sellable at
--             seed time but carried no location then (so the seed didn't count them). No correction.
--
-- Idempotent: the location update only touches NULL locations; claim rows use a deterministic
-- trigger_id (piece_id ':' md5-uuid of the restock event) with ON CONFLICT DO NOTHING.
--
-- Run as the database owner (postgres) with psql. Dry run by default (ROLLBACK):
--   psql "<conn>" -v ON_ERROR_STOP=1 -f scripts/ops/2026-10-08-restock-null-location-repair.sql
--   psql "<conn>" -v ON_ERROR_STOP=1 -v commit=yes -f scripts/ops/2026-10-08-restock-null-location-repair.sql
-- Deploy V150 first (the claim statuses, source_order_id and the guard function come from it).
-- =============================================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ---------------------------------------------------------------------------------------------
-- 1. The buggy restocks and the pieces they left without a location
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE rr_restocks ON COMMIT DROP AS
SELECT pe.id AS event_id, pe.tenant_id, pe.piece_id, p.variant_id, pe.occurred_at AS restocked_at,
       -- the order the piece came back from: the newest return_received before the restock
       (SELECT r.order_id FROM piece_events r
         WHERE r.piece_id = pe.piece_id AND r.tenant_id = pe.tenant_id
           AND r.event_type = 'return_received' AND r.order_id IS NOT NULL
           AND (r.occurred_at, r.id) < (pe.occurred_at, pe.id)
         ORDER BY r.occurred_at DESC, r.id DESC LIMIT 1) AS order_id
FROM piece_events pe
JOIN pieces p ON p.id = pe.piece_id
WHERE pe.event_type = 'restocked' AND pe.location_id IS NULL;

CREATE TEMP TABLE rr_main ON COMMIT DROP AS
SELECT l.tenant_id, l.id AS main_id, l.name AS main_name,
       (l.shopify_sync_status = 'linked' AND l.shopify_location_id IS NOT NULL) AS linked,
       l.shopify_synced_at AS linked_at,
       (SELECT MIN(a.created_at) FROM shopify_inventory_adjustments a
         WHERE a.tenant_id = l.tenant_id AND a.trigger_type = 'initial_seed' AND a.status = 'applied') AS seed_at
FROM locations l WHERE l.is_fulfillment;

CREATE TEMP TABLE rr_pieces ON COMMIT DROP AS
SELECT p.id AS piece_id, p.tenant_id, t.name AS tenant, p.variant_id, v.sku,
       pr.title || COALESCE(' / ' || v.title, '') AS item, p.status::text AS status,
       m.main_id, m.main_name,
       (SELECT MAX(r.restocked_at) FROM rr_restocks r WHERE r.piece_id = p.id) AS last_restock_at,
       CASE WHEN p.status::text IN ('available', 'reserved', 'packed', 'awaiting_pickup', 'on_hold',
                                    'damaged', 'return_pending_inspection') THEN 'A' ELSE 'B' END AS grp
FROM pieces p
JOIN tenants t   ON t.id = p.tenant_id
JOIN variants v  ON v.id = p.variant_id
JOIN products pr ON pr.id = v.product_id
LEFT JOIN rr_main m ON m.tenant_id = p.tenant_id
WHERE p.current_location_id IS NULL
  AND EXISTS (SELECT 1 FROM rr_restocks r WHERE r.piece_id = p.id);

DO $$
DECLARE n int;
BEGIN
    SELECT COUNT(*) INTO n FROM rr_pieces WHERE grp = 'A' AND main_id IS NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'restock-repair: % Group A piece(s) belong to a tenant with no main warehouse — aborting', n;
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 2. Shopify +1 candidates, with the double-count guard decision
-- ---------------------------------------------------------------------------------------------
CREATE TEMP TABLE rr_push ON COMMIT DROP AS
SELECT x.*,
       CASE WHEN x.already_claimed THEN 'already_claimed'
            WHEN x.order_id IS NOT NULL AND x.shopify_units > x.traced_before THEN 'skip_shopify_restocked'
            ELSE 'push' END AS decision
FROM (
    SELECT r.event_id, r.tenant_id, t.name AS tenant, r.piece_id, r.variant_id, v.sku, r.restocked_at, r.order_id,
           o.number AS order_number, m.main_id,
           r.piece_id || ':' || md5('restock-repair:' || r.event_id)::uuid AS trigger_id,
           COALESCE(shopify_refund_restocked_units(o.raw, v.external_id), 0) AS shopify_units,
           (SELECT COUNT(*) FROM shopify_inventory_adjustments a
             WHERE a.tenant_id = r.tenant_id AND a.trigger_type = 'return_inspection'
               AND a.source_order_id = r.order_id AND a.variant_id = r.variant_id
               AND a.trigger_id <> r.piece_id || ':' || md5('restock-repair:' || r.event_id)::uuid)
             + ROW_NUMBER() OVER (PARTITION BY r.tenant_id, r.order_id, r.variant_id
                                  ORDER BY r.restocked_at, r.event_id) - 1 AS traced_before,
           EXISTS (SELECT 1 FROM shopify_inventory_adjustments a
                    WHERE a.trigger_type = 'return_inspection'
                      AND a.trigger_id = r.piece_id || ':' || md5('restock-repair:' || r.event_id)::uuid) AS already_claimed
    FROM rr_restocks r
    JOIN tenants t  ON t.id = r.tenant_id
    JOIN rr_main m  ON m.tenant_id = r.tenant_id AND m.linked AND r.restocked_at >= m.linked_at
    JOIN variants v ON v.id = r.variant_id
    LEFT JOIN orders o ON o.id = r.order_id
) x;

-- ---------------------------------------------------------------------------------------------
-- 3. Report
-- ---------------------------------------------------------------------------------------------
\echo '== Group A — still in the warehouse: location → main warehouse'
SELECT tenant, piece_id, sku, item, status, main_name AS new_location, last_restock_at
FROM rr_pieces WHERE grp = 'A' ORDER BY tenant, last_restock_at;

\echo '== Group B — left the warehouse since: NO change'
SELECT tenant, piece_id, sku, item, status, last_restock_at
FROM rr_pieces WHERE grp = 'B' ORDER BY tenant, last_restock_at;

\echo '== Shopify +1 candidates (restocks after the main warehouse was linked) and the guard result'
SELECT tenant, piece_id, sku, order_number, restocked_at, shopify_units, traced_before, decision
FROM rr_push ORDER BY tenant, restocked_at;

\echo '== Seed shortfall — REPORT ONLY (sellable at seed time, no location then, so not counted)'
SELECT p.tenant, p.sku, p.item, COUNT(*) AS units_missing_from_seed, MIN(m.seed_at) AS seed_at
FROM rr_pieces p
JOIN rr_main m ON m.tenant_id = p.tenant_id AND m.linked AND m.seed_at IS NOT NULL
WHERE p.grp = 'A'
  AND p.last_restock_at < m.linked_at                -- later restocks get their +1 above instead
  AND p.last_restock_at < m.seed_at
  AND (SELECT e.to_status::text FROM piece_events e
        WHERE e.piece_id = p.piece_id AND e.occurred_at <= m.seed_at
        ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1)
      IN ('available', 'reserved', 'packed', 'awaiting_pickup')
GROUP BY p.tenant, p.sku, p.item ORDER BY p.tenant, p.sku;

-- ---------------------------------------------------------------------------------------------
-- 4. Apply (inside the transaction; rolled back unless -v commit=yes)
-- ---------------------------------------------------------------------------------------------
INSERT INTO piece_events (tenant_id, piece_id, event_type, actor_user_id, location_id, from_status, to_status, metadata)
SELECT p.tenant_id, p.piece_id, 'location_corrected', NULL, p.main_id,
       p.status::piece_status, p.status::piece_status,
       jsonb_build_object('reason', 'restock_null_location_repair', 'repair', '2026-10-08', 'previous_location', NULL)
FROM rr_pieces p
WHERE p.grp = 'A'
  AND EXISTS (SELECT 1 FROM pieces x WHERE x.id = p.piece_id AND x.current_location_id IS NULL);

UPDATE pieces x SET current_location_id = p.main_id
FROM rr_pieces p
WHERE x.id = p.piece_id AND p.grp = 'A' AND x.current_location_id IS NULL;

INSERT INTO shopify_inventory_adjustments
    (tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, payload, status,
     failure_class, attempt_count, first_attempt_at, last_attempt_at, next_attempt_at, error, source_order_id)
SELECT c.tenant_id, gen_random_uuid(), c.variant_id, c.main_id, 1, 'return_inspection', c.trigger_id,
       jsonb_build_object('reason', 'restock', 'delta', 1, 'repair', '2026-10-08'),
       'failed', 'never_sent', 1, now(), now(), now(),
       'Repair 2026-10-08: this restock''s +1 was never sent (restocked with no location) — queued for the retry job',
       c.order_id
FROM rr_push c WHERE c.decision = 'push'
ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING;

INSERT INTO shopify_inventory_adjustments
    (tenant_id, batch_id, variant_id, location_id, delta, trigger_type, trigger_id, payload, status, error, source_order_id)
SELECT c.tenant_id, gen_random_uuid(), c.variant_id, c.main_id, 1, 'return_inspection', c.trigger_id,
       jsonb_build_object('reason', 'restock', 'delta', 1, 'repair', '2026-10-08'),
       'skipped_shopify_restocked',
       'Repair 2026-10-08: already restocked in Shopify by a refund — nothing sent',
       c.order_id
FROM rr_push c WHERE c.decision = 'skip_shopify_restocked'
ON CONFLICT (trigger_type, trigger_id, variant_id, location_id) DO NOTHING;

DO $$
DECLARE left_null int;
BEGIN
    SELECT COUNT(*) INTO left_null FROM pieces x JOIN rr_pieces p ON p.piece_id = x.id
     WHERE p.grp = 'A' AND x.current_location_id IS NULL;
    IF left_null > 0 THEN
        RAISE EXCEPTION 'restock-repair: % Group A piece(s) still have no location — aborting', left_null;
    END IF;
    RAISE NOTICE 'restock-repair: % Group A located, % Group B untouched, % +1 queued, % recorded as already restocked in Shopify',
        (SELECT COUNT(*) FROM rr_pieces WHERE grp = 'A'), (SELECT COUNT(*) FROM rr_pieces WHERE grp = 'B'),
        (SELECT COUNT(*) FROM rr_push WHERE decision = 'push'),
        (SELECT COUNT(*) FROM rr_push WHERE decision = 'skip_shopify_restocked');
END $$;

-- ---------------------------------------------------------------------------------------------
-- 5. Commit only with -v commit=yes.
-- ---------------------------------------------------------------------------------------------
\if :{?commit}
\if :commit
COMMIT;
\echo 'restock-repair: COMMITTED — the queued +1s go out with the next increment retry run.'
\else
ROLLBACK;
\echo 'restock-repair: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
\else
ROLLBACK;
\echo 'restock-repair: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
