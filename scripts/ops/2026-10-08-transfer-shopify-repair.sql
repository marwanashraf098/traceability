-- =============================================================================================
-- 2026-10-08 — transfers → Shopify repair (Issue 2). Deploy V157 first.
--
-- Before V157 no transfer ever touched Shopify. This script brings the stock that is OUT on a
-- transfer today in line with the new model (sync by custody, mode 'remove' — every existing
-- non-main location defaults to it). It never calls Shopify: it only writes transfer_shopify_syncs
-- claim rows ('queued', source 'repair') that the app's TransferShopifySweepJob sends through
-- pushTransferOut (single attempt, ambiguous never re-sent). Report-only for everything else.
--
-- Scope: tenants with a main warehouse linked to Shopify, EXCEPT Jumi (being purged) and every demo
-- tenant. Pieces now out_on_transfer / transferred_out at a non-main location, by the transfer that
-- took them OUT of the main warehouse (the latest send-and-back or permanent move holding them).
--
-- THE SEED RULE (why most pieces need nothing): the tenant's initial Shopify seed counted only the
-- pieces sitting in the main warehouse at that moment. A piece that left BEFORE the seed was never in
-- Shopify's count — decrementing it would subtract it twice. Decisions per piece:
--   queue_minus_1     left the main warehouse AFTER the seed (counted, then left, never decremented)
--   seed_excluded     left BEFORE the seed — nothing to do now; its return gets +1 (RETURN_COUNTED_SQL)
--   not_seeded        main warehouse linked but never seeded — the seed will exclude it; nothing now
--   not_yet_sent      the send-and-back is still 'preparing' — markSent will claim it
--   mode_leave        the location is set to 'leave' — Shopify unchanged
-- Report only (no correction): pieces SOLD / LOST at a destination that left AFTER the seed — Shopify
-- still counts them (permanently too high); fix by hand. Before the seed → Shopify never counted them.
--
-- Idempotent: one claim per transfer (UNIQUE transfer_id, ON CONFLICT DO NOTHING).
-- psql as the database owner. Dry run by default (ROLLBACK):
--   psql "<conn>" -v ON_ERROR_STOP=1 -f scripts/ops/2026-10-08-transfer-shopify-repair.sql
--   psql "<conn>" -v ON_ERROR_STOP=1 -v commit=yes -f scripts/ops/2026-10-08-transfer-shopify-repair.sql
-- =============================================================================================

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE tr_tenants ON COMMIT DROP AS
SELECT t.id AS tenant_id, t.name AS tenant, l.id AS main_id,
       (l.shopify_sync_status = 'linked' AND l.shopify_location_id IS NOT NULL) AS linked,
       (SELECT MIN(a.created_at) FROM shopify_inventory_adjustments a
         WHERE a.tenant_id = t.id AND a.trigger_type = 'initial_seed' AND a.status = 'applied') AS seed_at
FROM tenants t
JOIN locations l ON l.tenant_id = t.id AND l.is_fulfillment
WHERE NOT t.is_demo
  AND t.id NOT IN ('07fc572c-2158-412d-ae31-ec61e22378b7',   -- Jumi Worldwide (being purged)
                   '91c6027e-0b23-4c56-84a6-2a769315ed2d');  -- public demo (DemoSeeder)

-- Pieces out now, with the transfer that took them out of the main warehouse.
CREATE TEMP TABLE tr_out ON COMMIT DROP AS
SELECT tt.tenant, tt.tenant_id, tt.linked, tt.seed_at, p.id AS piece_id, p.variant_id, v.sku,
       p.status::text AS status, loc.name AS at_location, loc.shopify_sync_mode AS mode,
       dep.transfer_id, dep.transfer_mode, dep.transfer_status, dep.departed_at
FROM pieces p
JOIN tr_tenants tt ON tt.tenant_id = p.tenant_id
JOIN locations loc ON loc.id = p.current_location_id AND NOT loc.is_fulfillment
JOIN variants v ON v.id = p.variant_id
CROSS JOIN LATERAL (
    SELECT tr.id AS transfer_id, tr.transfer_mode, tr.status AS transfer_status,
           -- departure time: TransferShopifySync.departedAtSql, verbatim
           (CASE WHEN tr.transfer_mode = 'relocate_out' THEN tr.closed_at
                 ELSE COALESCE(tr.sent_at, tr.reconcile_started_at, tr.created_at) END) AS departed_at
    FROM transfer_pieces tp JOIN transfers tr ON tr.id = tp.transfer_id
    WHERE tp.piece_id = p.id AND tp.tenant_id = p.tenant_id
      AND tr.transfer_mode IN ('round_trip', 'relocate_out')
      AND (tp.from_location_id IS NULL OR tp.from_location_id = tt.main_id)
    ORDER BY tp.created_at DESC, tp.id DESC LIMIT 1) dep
WHERE p.status IN ('out_on_transfer', 'transferred_out');

CREATE TEMP TABLE tr_decision ON COMMIT DROP AS
SELECT o.*,
       CASE WHEN NOT o.linked THEN 'unlinked_report_only'
            WHEN o.mode = 'leave' THEN 'mode_leave'
            WHEN o.transfer_status = 'preparing' OR o.departed_at IS NULL THEN 'not_yet_sent'
            WHEN o.seed_at IS NULL THEN 'not_seeded'
            WHEN o.departed_at < o.seed_at THEN 'seed_excluded'
            ELSE 'queue_minus_1' END AS decision
FROM tr_out o;

\echo '== Pieces out on a transfer now — decision per piece'
SELECT tenant, at_location, piece_id, sku, status, transfer_mode, transfer_status, departed_at, seed_at, decision
FROM tr_decision ORDER BY tenant, at_location, departed_at;

\echo '== Claims that would be queued (one per transfer, main warehouse −N per variant)'
SELECT tenant, transfer_id, COUNT(*) AS units, jsonb_object_agg(sku, n) AS per_sku
FROM (SELECT tenant, transfer_id, sku, COUNT(*) AS n FROM tr_decision WHERE decision = 'queue_minus_1'
      GROUP BY tenant, transfer_id, sku) x
GROUP BY tenant, transfer_id ORDER BY tenant;

\echo '== REPORT ONLY — sold / lost at a destination (fix by hand where Shopify is too high)'
SELECT tt.tenant, v.sku, p.status::text AS status, COUNT(*) AS units,
       CASE WHEN NOT tt.linked THEN 'unlinked — Traced never wrote this tenant''s Shopify stock'
            WHEN tt.seed_at IS NULL THEN 'not seeded — the seed will not count them'
            WHEN MAX(dep.departed_at) < tt.seed_at THEN 'left before the seed — Shopify never counted them, nothing to fix'
            ELSE 'left after the seed — Shopify still counts them: lower by hand' END AS shopify
FROM pieces p
JOIN tr_tenants tt ON tt.tenant_id = p.tenant_id
JOIN variants v ON v.id = p.variant_id
JOIN transfer_pieces tp ON tp.piece_id = p.id AND tp.outcome IN ('sold', 'lost')
CROSS JOIN LATERAL (SELECT (CASE WHEN tr.transfer_mode = 'relocate_out' THEN tr.closed_at
                          ELSE COALESCE(tr.sent_at, tr.reconcile_started_at, tr.created_at) END) AS departed_at
                    FROM transfers tr WHERE tr.id = tp.transfer_id) dep
WHERE p.status IN ('sold', 'lost')
GROUP BY tt.tenant, tt.linked, tt.seed_at, v.sku, p.status ORDER BY tt.tenant, v.sku;

-- ── Apply: queue the −1 claims (the app sends them) ─────────────────────────────────────────
INSERT INTO transfer_shopify_syncs (tenant_id, transfer_id, location_id, status, source, deltas, piece_ids)
SELECT d.tenant_id, d.transfer_id, tt.main_id, 'queued', 'repair',
       (SELECT jsonb_object_agg(variant_id::text, n) FROM (SELECT variant_id, COUNT(*) AS n FROM tr_decision d2
         WHERE d2.transfer_id = d.transfer_id AND d2.decision = 'queue_minus_1' GROUP BY variant_id) x),
       jsonb_agg(d.piece_id)
FROM tr_decision d JOIN tr_tenants tt ON tt.tenant_id = d.tenant_id
WHERE d.decision = 'queue_minus_1'
GROUP BY d.tenant_id, d.transfer_id, tt.main_id
ON CONFLICT (transfer_id) DO NOTHING;

\if :{?commit}
\if :commit
COMMIT;
\echo 'transfer-repair: COMMITTED — TransferShopifySweepJob sends the queued claims within 10 minutes.'
\else
ROLLBACK;
\echo 'transfer-repair: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
\else
ROLLBACK;
\echo 'transfer-repair: dry run — ROLLED BACK (pass -v commit=yes to apply).'
\endif
