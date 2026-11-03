-- Main reconcile R2 (V203, write mode) — READ ONLY: SELECTs only, no writes.
-- Run as the owner (psql via the one-off postgres:16-alpine container, or the Supabase SQL editor).
-- Every query carries the statement_timeout below; none locks anything.

SET statement_timeout = '30s';

-- (i) Differences per store WITH THE R2 FIXES APPLIED — each variant's newest diff written by R2 code
--     (unfulfilled_shipped is set only by R2 runs; fix 1's cutoff and fix 2's unfulfilled term are in its target).
--     Run after the first R2 sweep (every 4 h, :15 past) — before it, a store has no R2 rows yet.
WITH cur AS (
    SELECT DISTINCT ON (d.tenant_id, d.variant_id) d.*
    FROM main_reconcile_diffs d
    WHERE d.unfulfilled_shipped IS NOT NULL
    ORDER BY d.tenant_id, d.variant_id, d.created_at DESC)
SELECT t.name AS store, s.shop_domain,
       COUNT(*) FILTER (WHERE c.status IN ('unconfirmed', 'confirmed'))           AS differences,
       COUNT(*) FILTER (WHERE c.status = 'confirmed')                             AS confirmed,
       COALESCE(SUM(c.delta) FILTER (WHERE c.status = 'confirmed' AND c.delta > 0), 0)  AS confirmed_units_up,
       COALESCE(-SUM(c.delta) FILTER (WHERE c.status = 'confirmed' AND c.delta < 0), 0) AS confirmed_units_down,
       COUNT(*) FILTER (WHERE c.status = 'skipped')                               AS skipped,
       COUNT(*) FILTER (WHERE c.note = 'unfulfilled_capped')                      AS unfulfilled_capped,
       COALESCE(SUM(c.unfulfilled_shipped), 0)                                    AS unfulfilled_units_in_target,
       MAX(c.created_at)                                                          AS newest
FROM cur c
JOIN tenants t ON t.id = c.tenant_id
LEFT JOIN stores s ON s.tenant_id = c.tenant_id AND s.status <> 'disconnected'
GROUP BY t.name, s.shop_domain
ORDER BY confirmed DESC, differences DESC, t.name;

-- (i-b) Part 1 claims per store and status (ignored_pre_cutoff = fix 1; queued / pending / applied = write mode).
SELECT t.name AS store, c.status, COUNT(*) AS claims, SUM(c.quantity) AS units,
       MIN(c.fulfillment_created_at) AS oldest_fulfillment, MAX(c.fulfillment_created_at) AS newest_fulfillment
FROM main_reconcile_part1_claims c JOIN tenants t ON t.id = c.tenant_id
GROUP BY 1, 2 ORDER BY 1, 2;

-- (ii) Every Shopify write in the last 24 h, per store: reconcile writes (named write 9) and Part 1 sends (8).
SELECT t.name AS store, 'reconcile' AS kind, w.created_at AS at, v.sku, w.status AS result,
       w.shopify_on_hand AS shopify_before, w.fresh_on_hand, w.change_from_quantity, w.target,
       w.custody_at_main, w.away_counted, w.fulfilled_from_main, w.part1_owed, w.unfulfilled_shipped,
       w.delta, w.reason, w.id AS claim_id, jsonb_array_length(w.settled) AS settled_claims, w.error
FROM main_reconcile_writes w
JOIN tenants t ON t.id = w.tenant_id
LEFT JOIN variants v ON v.id = w.variant_id AND v.tenant_id = w.tenant_id
WHERE w.created_at > now() - interval '24 hours'
UNION ALL
SELECT t.name, 'part1', c.send_started_at, v.sku, c.status,
       NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
       -c.quantity, 'shipped_by_traced_fulfilled_elsewhere', c.id, NULL, c.error
FROM main_reconcile_part1_claims c
JOIN tenants t ON t.id = c.tenant_id
LEFT JOIN variants v ON v.id = c.variant_id AND v.tenant_id = c.tenant_id
WHERE c.send_started_at > now() - interval '24 hours'
ORDER BY 1, 3 DESC;

-- (ii-b) The same, totals per store and result.
SELECT t.name AS store, x.kind, x.result, COUNT(*) AS writes, SUM(x.delta) AS net_units
FROM (
    SELECT tenant_id, 'reconcile' AS kind, status AS result, delta FROM main_reconcile_writes
    WHERE created_at > now() - interval '24 hours'
    UNION ALL
    SELECT tenant_id, 'part1', status, -quantity FROM main_reconcile_part1_claims
    WHERE send_started_at > now() - interval '24 hours') x
JOIN tenants t ON t.id = x.tenant_id
GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;

-- (iii) Open "Delivered but not fulfilled in Shopify" alerts (MEDIUM, delivered_not_fulfilled_in_shopify).
--       Alerts are computed live (no table); this is MainReconcileRules.DELIVERED_NOT_FULFILLED_SQL for every
--       tenant at once with the defaults (7 days, 90-day lookback), minus resolved ones. Simulated tenants and
--       tenants with no connected store are left out, as in the app.
WITH out_p AS (
    SELECT DISTINCT ON (p.id) p.id AS piece_id, p.tenant_id, p.variant_id, e.order_id, e.occurred_at AS left_at
    FROM pieces p JOIN piece_events e ON e.piece_id = p.id AND e.tenant_id = p.tenant_id
    WHERE p.status = 'delivered' AND e.to_status::text IN ('with_courier', 'delivered') AND e.order_id IS NOT NULL
    ORDER BY p.id, e.occurred_at DESC, e.id DESC),
fl AS (
    SELECT o.*, CASE WHEN jsonb_typeof(o.raw->'fulfillments') = 'array' THEN o.raw->'fulfillments' ELSE '[]'::jsonb END AS fs
    FROM merchant_orders o)
SELECT t.name AS store, o.number AS order_number, MIN(x.left_at) AS delivered_at, COUNT(*) AS pieces,
       'delivered_not_fulfilled_in_shopify:' || o.id AS subject_key
FROM out_p x
JOIN fl o ON o.id = x.order_id AND o.tenant_id = x.tenant_id
JOIN variants vv ON vv.id = x.variant_id AND vv.tenant_id = x.tenant_id
JOIN tenants t ON t.id = x.tenant_id
WHERE o.external_id NOT LIKE 'internal:%' AND NOT COALESCE(o.is_cancelled, false) AND o.status <> 'cancelled'
  AND NOT (lower(COALESCE(o.raw->>'fulfillment_status', '')) = 'fulfilled'
           OR upper(COALESCE(o.raw->>'displayFulfillmentStatus', '')) = 'FULFILLED')
  AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(o.fs) f
                  WHERE NOT (f->>'location_id' IS NOT NULL AND jsonb_typeof(f->'line_items') = 'array'))
  AND x.left_at < now() - interval '7 days' AND x.left_at > now() - interval '90 days'
  AND GREATEST(0,
        COALESCE((SELECT SUM(oi.quantity) FROM order_items oi
                  WHERE oi.tenant_id = o.tenant_id AND oi.order_id = o.id AND oi.variant_id = vv.id), 0)
      - COALESCE((SELECT SUM((li->>'quantity')::int)
                  FROM jsonb_array_elements(o.fs) f,
                       jsonb_array_elements(CASE WHEN jsonb_typeof(f->'line_items') = 'array' THEN f->'line_items' ELSE '[]'::jsonb END) li
                  WHERE f->>'location_id' IS NOT NULL AND lower(f->>'status') = 'success'
                    AND 'gid://shopify/ProductVariant/' || (li->>'variant_id') = vv.external_id
                    AND li->>'quantity' ~ '^[0-9]+$'), 0)) > 0
  AND NOT EXISTS (SELECT 1 FROM tenant_courier_simulation sim WHERE sim.tenant_id = x.tenant_id)
  AND EXISTS (SELECT 1 FROM stores s WHERE s.tenant_id = x.tenant_id AND s.status <> 'disconnected')
  AND NOT EXISTS (SELECT 1 FROM exception_resolutions er WHERE er.tenant_id = x.tenant_id
                  AND er.exception_type = 'delivered_not_fulfilled_in_shopify'
                  AND er.subject_key = 'delivered_not_fulfilled_in_shopify:' || o.id)
GROUP BY t.name, o.id, o.number
ORDER BY t.name, delivered_at;
