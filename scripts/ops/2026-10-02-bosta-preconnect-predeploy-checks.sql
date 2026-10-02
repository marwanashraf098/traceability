-- =============================================================================================
-- READ-ONLY pre-deploy checks for the Bosta pre-connect filter + cleanup (2026-10-02).
-- SELECTs only. Run against prod BEFORE deploying.
-- =============================================================================================

-- (a) What the pre-connect filter (PreConnectDeliveryFilter) would do with every UNRESOLVED
--     unlinked delivery and every non-request exchanges row, per tenant. Mirrors the Java rules:
--     tenant cutoff = MIN(stores.orders_ingest_from), no filtering when any store's is NULL;
--     never Traced-owned; reference resolves (as sent, '#'-stripped/-prefixed, and the parts
--     before and after a ':') → keep; else ignore when Bosta createdAt < cutoff,
--     or the order-number part (before ':', '#'-stripped) has the same prefix+digits+suffix shape
--     as the tenant's orders and its number is below their lowest.
--     expect (prod 2026-10-02): IGNORE = BROEK 21 unlinked + exchange 277dc26d (7577553206),
--             Femine 23 (the Part 3 ids in the cleanup script), PLUS blnco 10 (5542–5551, all
--             created before blnco's cutoff, references "blncoeg:#5159xx" < first order #515932)
--             and The Snouts 1 (5470, RTO for #2212026474 < first order #2212029474).
--             keep = the 7 post-cutoff NULL-reference rows (5616, 5627, 5615, 5628, 5631, 5648, 5657 —
--             not in the cleanup script), BROEK's 4 EXC- exchanges
--             (they have shipments until the cleanup runs), Jumi (no cutoff), everything else.
--     Already-stored rows are NOT changed by the deploy; IGNORE only means a future event for
--     that tracking number writes nothing.
WITH cut AS (
    SELECT tenant_id,
           CASE WHEN bool_or(orders_ingest_from IS NULL) THEN NULL ELSE MIN(orders_ingest_from) END AS cutoff
    FROM stores GROUP BY tenant_id
),
d AS (
    SELECT 'unlinked'::text AS kind, u.id::text AS row_id, u.tenant_id, u.tracking_number,
           u.business_reference AS ref, u.raw
    FROM unlinked_bosta_deliveries u WHERE NOT u.resolved
    UNION ALL
    SELECT 'exchange', e.id::text, e.tenant_id, e.tracking_number, e.raw->>'businessReference', e.raw
    FROM exchanges e WHERE e.return_request_id IS NULL
),
x AS (
    SELECT d.*, c.cutoff,
           CASE
             WHEN d.raw->>'createdAt' ~ '^\d{4}-' THEN (d.raw->>'createdAt')::timestamptz
             WHEN d.raw->>'createdAt' ~ '^\w{3} \w{3} \d{2} \d{4} \d{2}:\d{2}:\d{2} GMT\+0000'
               THEN to_timestamp(substring(d.raw->>'createdAt' from '^\w{3} (\w{3} \d{2} \d{4} \d{2}:\d{2}:\d{2})'),
                                 'Mon DD YYYY HH24:MI:SS')::timestamp AT TIME ZONE 'UTC'
           END AS bosta_created,
           ltrim(trim(split_part(d.ref, ':', 1)), '#') AS num_part,
           EXISTS (SELECT 1 FROM shipments s WHERE s.tenant_id = d.tenant_id AND s.tracking_number = d.tracking_number)
        OR EXISTS (SELECT 1 FROM exchanges e2 WHERE e2.tenant_id = d.tenant_id AND e2.tracking_number = d.tracking_number
                     AND e2.return_request_id IS NOT NULL)
        OR EXISTS (SELECT 1 FROM return_requests r WHERE r.tenant_id = d.tenant_id AND r.bosta_tracking_number = d.tracking_number)
             AS traced_owned,
           EXISTS (SELECT 1 FROM orders o WHERE o.tenant_id = d.tenant_id AND (
                     o.number IN (trim(d.ref), ltrim(trim(d.ref), '#'), '#' || ltrim(trim(d.ref), '#'),
                                  trim(split_part(d.ref, ':', 1)), ltrim(trim(split_part(d.ref, ':', 1)), '#'),
                                  '#' || ltrim(trim(split_part(d.ref, ':', 1)), '#'),
                                  trim(substr(d.ref, strpos(d.ref, ':') + 1)),
                                  ltrim(trim(substr(d.ref, strpos(d.ref, ':') + 1)), '#'),
                                  '#' || ltrim(trim(substr(d.ref, strpos(d.ref, ':') + 1)), '#'))
                  OR o.external_id IN (trim(d.ref), trim(split_part(d.ref, ':', 1)),
                                       'gid://shopify/Order/' || coalesce(d.raw->'shopifyInfo'->>'orderId', d.raw->>'shopifyOrderId'))))
             AS ref_resolves
    FROM d LEFT JOIN cut c ON c.tenant_id = d.tenant_id
),
y AS (
    SELECT x.*, m[1] AS pfx, m[2] AS digits, m[3] AS sfx
    FROM x LEFT JOIN LATERAL regexp_match(x.num_part, '^([^0-9]*)([0-9]+)([^0-9]*)$') m ON true
),
z AS (
    SELECT y.*,
      (SELECT MIN(substr(ltrim(o.number, '#'), length(y.pfx) + 1,
                         length(ltrim(o.number, '#')) - length(y.pfx) - length(y.sfx))::numeric)
       FROM orders o
       WHERE o.tenant_id = y.tenant_id AND o.external_id NOT LIKE 'internal:%'
         AND y.digits IS NOT NULL
         AND length(ltrim(o.number, '#')) > length(y.pfx) + length(y.sfx)
         AND left(ltrim(o.number, '#'), length(y.pfx)) = y.pfx
         AND right(ltrim(o.number, '#'), length(y.sfx)) = y.sfx
         AND substr(ltrim(o.number, '#'), length(y.pfx) + 1,
                    length(ltrim(o.number, '#')) - length(y.pfx) - length(y.sfx)) ~ '^[0-9]+$') AS lowest
    FROM y
)
SELECT t.name AS tenant, z.kind, z.row_id, z.tracking_number, z.ref,
       to_char(z.bosta_created AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS created_utc,
       to_char(z.cutoff AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') AS cutoff_utc, z.lowest,
       CASE
         WHEN z.cutoff IS NULL THEN 'keep: no cutoff'
         WHEN z.traced_owned  THEN 'keep: traced-owned'
         WHEN z.ref_resolves  THEN 'keep: reference resolves'
         WHEN z.bosta_created < z.cutoff THEN 'IGNORE: created before cutoff'
         WHEN z.lowest IS NOT NULL AND z.digits::numeric < z.lowest THEN 'IGNORE: number below lowest order'
         ELSE 'keep: post-connect / unknown'
       END AS verdict
FROM z JOIN tenants t ON t.id = z.tenant_id
ORDER BY t.name, verdict, z.kind, z.row_id;

-- (a2) Summary of (a): paste the CTEs above and replace the final SELECT with
--      SELECT tenant, verdict, count(*) ... GROUP BY 1, 2  — or just count the rows by eye.

-- (a3) Linked deliveries are never touched: the filter's first check is "already a shipment".
--      Sanity: forward/return shipments whose Bosta reference does NOT resolve to their tenant's
--      orders (these are still safe — the shipments row short-circuits the filter).
SELECT t.name, COUNT(*) AS shipments_with_unresolvable_ref
FROM shipments s JOIN tenants t ON t.id = s.tenant_id
WHERE s.raw->>'businessReference' IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.tenant_id = s.tenant_id
                    AND (o.number IN (s.raw->>'businessReference', ltrim(s.raw->>'businessReference', '#'),
                                      '#' || ltrim(s.raw->>'businessReference', '#'),
                                      split_part(s.raw->>'businessReference', ':', 1))
                         OR o.external_id = s.raw->>'businessReference'))
GROUP BY 1;

-- (b) Cleanup-script targets match prod (expect exactly the ids hard-coded in the script).
SELECT 'exc_order' AS what, o.id::text, o.number, o.external_id,
       e.id::text AS exchange_id, e.status, e.return_request_id::text, e.matched_order_id::text
FROM orders o LEFT JOIN exchanges e ON e.outbound_order_id = o.id
WHERE o.tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d' AND o.external_id LIKE 'internal:exchange:%'
UNION ALL
SELECT 'exchange_no_order', e.id::text, e.tracking_number, NULL, NULL, e.status, e.return_request_id::text, e.matched_order_id::text
FROM exchanges e
WHERE e.tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d' AND e.outbound_order_id IS NULL
ORDER BY 1, 3;

SELECT s.id::text AS shipment_id, s.order_id::text, s.tracking_number,
       (SELECT string_agg(oi.id::text, ',') FROM order_items oi WHERE oi.order_id = s.order_id) AS order_items,
       (SELECT COUNT(*) FROM shipment_status_history h WHERE h.shipment_id = s.id) AS history
FROM shipments s JOIN orders o ON o.id = s.order_id
WHERE o.tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d' AND o.external_id LIKE 'internal:exchange:%';

SELECT id, exception_type, subject_key FROM exception_notifications
WHERE tenant_id = 'd6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d' AND exception_type LIKE 'exchange%' ORDER BY id;

SELECT t.name, string_agg(u.id::text, ',' ORDER BY u.id) AS unresolved_ids, COUNT(*)
FROM unlinked_bosta_deliveries u JOIN tenants t ON t.id = u.tenant_id
WHERE NOT u.resolved AND u.tenant_id IN ('d6e1ffe7-cdb4-4e57-9287-f6f9a6322d2d', 'a7d6fab9-828d-4b3a-8ab6-71e6bef3d197')
GROUP BY 1;
