-- =============================================================================================
-- 2026-11-05 — Shopify-native returns S1: READ-ONLY checks (nothing is written; ends with ROLLBACK).
--
--   1. per connected store: granted the new scopes? returns access? backfill state? webhook topic version?
--   2. per store: what the mirror holds (returns / lines / exchange lines / dispositions / fulfillment orders,
--      exchange lines linked to an order line, the newest return seen)
--   3. per store: order lines Shopify says are fulfilled by an app (REST line_items[].fulfillment_service other
--      than 'manual', last 90 days, webhook-shaped raws only) — non-zero means the store uses a fulfillment app and
--      read_third_party_fulfillment_orders would be needed to read those fulfillment orders.
--
-- Run as postgres / the owner role (NOT app_user: RLS would hide every row):
--   psql "<connection string>" -X -v ON_ERROR_STOP=1 -f scripts/ops/2026-11-05-shopify-returns-s1-check.sql
-- Off-peak; check the JobRunr heartbeat afterwards.
-- =============================================================================================

BEGIN READ ONLY;
SET LOCAL statement_timeout = '60s';

-- 1. Stores
SELECT t.name AS tenant, s.shop_domain, s.connection_type,
       (s.access_token_scopes ~ '(^|,)\s*(read|write)_returns\s*(,|$)')                           AS has_returns_scope,
       (s.access_token_scopes ~ '(^|,)\s*read_merchant_managed_fulfillment_orders\s*(,|$)')         AS has_fo_scope,
       s.returns_access, s.returns_access_at,
       s.returns_backfill_status, s.returns_backfill_requested_at, s.returns_backfill_done_at, s.returns_backfill_error,
       s.webhook_topics_version
FROM stores s JOIN tenants t ON t.id = s.tenant_id
WHERE s.status <> 'disconnected'
ORDER BY t.name;

-- 2. What the mirror holds
SELECT t.name AS tenant,
       (SELECT count(*) FROM shopify_returns r WHERE r.tenant_id = t.id)                         AS returns,
       (SELECT count(*) FROM shopify_returns r WHERE r.tenant_id = t.id AND r.status = 'OPEN')   AS returns_open,
       (SELECT count(*) FROM shopify_return_lines l WHERE l.tenant_id = t.id)                    AS return_lines,
       (SELECT count(*) FROM shopify_exchange_lines x WHERE x.tenant_id = t.id)                  AS exchange_lines,
       (SELECT count(*) FROM order_items oi WHERE oi.tenant_id = t.id
           AND oi.shopify_exchange_line_gid IS NOT NULL)                                          AS order_lines_linked,
       (SELECT count(*) FROM shopify_rfo_dispositions d WHERE d.tenant_id = t.id)                AS dispositions,
       (SELECT count(*) FROM shopify_rfo_dispositions d WHERE d.tenant_id = t.id
           AND d.type = 'RESTOCKED')                                                              AS restocked_dispositions,
       (SELECT count(*) FROM shopify_fulfillment_orders f WHERE f.tenant_id = t.id)              AS fulfillment_orders,
       (SELECT count(*) FROM shopify_fulfillment_orders f WHERE f.tenant_id = t.id
           AND f.status = 'ON_HOLD')                                                              AS fo_on_hold,
       (SELECT max(r.fetched_at) FROM shopify_returns r WHERE r.tenant_id = t.id)                AS newest_fetch
FROM tenants t
WHERE EXISTS (SELECT 1 FROM stores s WHERE s.tenant_id = t.id AND s.status <> 'disconnected')
ORDER BY t.name;

-- 3. Fulfillment apps (decides whether read_third_party_fulfillment_orders is needed)
SELECT t.name AS tenant, li ->> 'fulfillment_service' AS fulfillment_service, count(*) AS lines
FROM orders o
JOIN tenants t ON t.id = o.tenant_id
CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(o.raw -> 'line_items') = 'array'
                                             THEN o.raw -> 'line_items' ELSE '[]'::jsonb END) li
WHERE o.created_at > now() - interval '90 days'
  AND o.external_id LIKE 'gid://shopify/Order/%'
  AND li ? 'fulfillment_service'
GROUP BY 1, 2
ORDER BY 1, 3 DESC;

ROLLBACK;
