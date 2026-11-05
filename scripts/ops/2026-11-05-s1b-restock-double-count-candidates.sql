-- =============================================================================================
-- 2026-11-05 — S1b: READ-ONLY. Traced +1s (return inspection restock / Back to good) since 2026-10-08 on an order +
-- variant that now has a Shopify RESTOCKED disposition (a processed Shopify return) at the Traced Main location —
-- candidates for a MANUAL −1 in Shopify (Traced never decrements for this).
--
-- Works before or after V212 is deployed: the Shopify side is inlined as GREATEST(refund restocks at Main (V201
-- function), RESTOCKED dispositions at Main) — the S1b rule (one unit can carry both records; Step 0, #1007).
-- double_counted = LEAST(traced +1s applied, shopify restocked − units the guard already left to Shopify) — the same
-- formula as the restocked_twice alert. Only rows with double_counted > 0 are listed.
--
-- Run as postgres / the owner role (NOT app_user: RLS would hide every row):
--   psql "<connection string>" -X -v ON_ERROR_STOP=1 -f scripts/ops/2026-11-05-s1b-restock-double-count-candidates.sql
-- Off-peak; check the JobRunr heartbeat afterwards. Nothing is written; ends with ROLLBACK.
-- =============================================================================================

BEGIN READ ONLY;
SET LOCAL statement_timeout = '120s';

WITH main AS (
    SELECT tenant_id, shopify_location_id AS gid
    FROM locations WHERE is_fulfillment AND shopify_location_id IS NOT NULL
), traced AS (
    SELECT sia.tenant_id, sia.source_order_id AS order_id, sia.variant_id,
           COUNT(*) FILTER (WHERE sia.status = 'applied' AND sia.applied_at >= '2026-10-08') AS plus_ones,
           COUNT(*) FILTER (WHERE sia.status = 'skipped_shopify_restocked')                AS left_to_shopify,
           MIN(sia.applied_at) FILTER (WHERE sia.status = 'applied')                        AS first_plus_one,
           MAX(sia.applied_at) FILTER (WHERE sia.status = 'applied')                        AS last_plus_one
    FROM shopify_inventory_adjustments sia
    WHERE sia.trigger_type IN ('return_inspection', 'damaged_restore_increment')
      AND sia.source_order_id IS NOT NULL
    GROUP BY 1, 2, 3
    HAVING COUNT(*) FILTER (WHERE sia.status = 'applied' AND sia.applied_at >= '2026-10-08') > 0
), disp AS (
    SELECT r.tenant_id, r.order_id, rl.variant_gid, SUM(d.quantity) AS units,
           MIN(d.first_seen_at) AS first_seen, string_agg(DISTINCT r.name, ', ') AS returns
    FROM shopify_returns r
    JOIN shopify_rfo_dispositions d ON d.tenant_id = r.tenant_id AND d.return_id = r.id
    JOIN shopify_return_lines rl    ON rl.tenant_id = d.tenant_id AND rl.return_id = d.return_id
                                   AND rl.fulfillment_line_gid = d.fulfillment_line_gid
    JOIN main m ON m.tenant_id = r.tenant_id
    WHERE d.type = 'RESTOCKED' AND d.quantity > 0
      AND (d.location_gid IS NULL OR d.location_gid = m.gid)
    GROUP BY 1, 2, 3
)
SELECT t.name AS tenant, s.shop_domain, o.number AS order_number, v.sku,
       p.title AS product, v.title AS variant,
       tr.plus_ones, tr.left_to_shopify,
       shopify_refund_restocked_units(o.raw, v.external_id, m.gid) AS refund_restocked_at_main,
       ds.units AS disposition_restocked_at_main, ds.returns,
       LEAST(tr.plus_ones,
             GREATEST(shopify_refund_restocked_units(o.raw, v.external_id, m.gid), ds.units) - tr.left_to_shopify)
           AS double_counted,
       tr.first_plus_one, tr.last_plus_one, ds.first_seen AS disposition_first_seen,
       o.id AS order_id, v.id AS variant_id
FROM traced tr
JOIN orders o   ON o.id = tr.order_id AND o.tenant_id = tr.tenant_id
JOIN variants v ON v.id = tr.variant_id AND v.tenant_id = tr.tenant_id
JOIN products p ON p.id = v.product_id
JOIN tenants t  ON t.id = tr.tenant_id
JOIN main m     ON m.tenant_id = tr.tenant_id
JOIN disp ds    ON ds.tenant_id = tr.tenant_id AND ds.order_id = tr.order_id AND ds.variant_gid = v.external_id
LEFT JOIN stores s ON s.id = o.store_id
WHERE LEAST(tr.plus_ones,
            GREATEST(shopify_refund_restocked_units(o.raw, v.external_id, m.gid), ds.units) - tr.left_to_shopify) > 0
ORDER BY t.name, tr.last_plus_one;

ROLLBACK;
