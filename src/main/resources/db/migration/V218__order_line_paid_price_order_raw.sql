-- V218 — order_line_paid_unit_price: a third stored tier, the ORDER's REST line (returns portal fees prod fix,
-- 2026-11-06).
--
-- Why: a line first imported through GraphQL (before F0) keeps its GraphQL-shaped raw (no REST 'price', no
-- 'paid_unit_price' key) even after later orders/* webhooks replaced the ORDER's raw with Shopify's REST payload —
-- so V208 answered NULL although orders.raw.line_items carries the line's price. Prod 2026-11-06 (The Snouts):
-- 82 of 92 lines in 90 days unpriced, 74 of them priced by this tier; no other tenant's line changes.
--
-- Tiers, first that answers (the first two exactly as V208, so no line that had a price changes value):
--   1. V149's net_unit_price (the line's own REST raw);
--   2. the line raw's 'paid_unit_price' key (GraphQL import / pre-connect lookup / the live read's write-back);
--   3. NEW — the order's REST line_items[] element whose admin_graphql_api_id is the line's external_id, with
--      the SAME math as net_unit_price: price − analytics_line_discount(line) / COALESCE(line quantity, oi.quantity).
-- Floored at 0, NULL when none answers. Still the ONE definition — every reader of a paid price uses it.
--
-- Reads the line's own order (one row by primary key, under the caller's RLS) — STABLE, INVOKER, not a hatch.
-- MUST_INCLUDE: it names orders, not merchant_orders, like OrderLinePaidPrices.LOAD_SQL (a pre-connect order's
-- raw is GraphQL-shaped and has no line_items, so tier 3 never answers for it). Nothing is written: order_items
-- raw->>'price' and V149's generated analytics columns are untouched. Same signature — CREATE OR REPLACE.

CREATE OR REPLACE FUNCTION order_line_paid_unit_price(oi order_items) RETURNS numeric
    LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN p IS NULL THEN NULL WHEN p < 0 THEN 0 ELSE p END
    FROM (SELECT COALESCE(
              oi.net_unit_price,
              analytics_num(oi.raw ->> 'paid_unit_price'),
              (SELECT analytics_num(e ->> 'price')
                      - analytics_line_discount(e)
                        / NULLIF(COALESCE(analytics_int(e ->> 'quantity'), oi.quantity), 0)
                 FROM orders o
                 CROSS JOIN LATERAL jsonb_array_elements(
                     CASE WHEN jsonb_typeof(o.raw -> 'line_items') = 'array' THEN o.raw -> 'line_items' END) e
                WHERE o.id = oi.order_id AND o.tenant_id = oi.tenant_id
                  AND oi.external_id LIKE 'gid://shopify/LineItem/%'
                  AND e ->> 'admin_graphql_api_id' = oi.external_id
                LIMIT 1)
          ) AS p) x
$$;
