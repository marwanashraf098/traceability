-- V156 — an order payload is never made poorer in customer data (fix/order-payload-downgrade).
--
-- UPSERT_ORDER replaced orders.raw with whatever payload arrived last. The GraphQL order import
-- (connect / reconnect / OAuth upgrade re-import, the reconcile catch-up) re-reads the last 30 days
-- (shopify.import.lookback-days); before Build B (2026-10-06) its query asked for no customer /
-- address fields at all, and since then it asks at the store's PII tier. Each re-import overwrote
-- the REST webhook payloads — which carry customer, shipping and billing — with a payload without
-- them: prod 2026-10-08, The Snouts 99 orders, Jumi 37.
--
-- 1. shopify_order_raw_keep_customer(stored, incoming) — THE rule, used by the import path of
--    UPSERT_ORDER (webhook payloads still replace raw as before) and by the restore below. For each
--    customer-data group — customer; shipping (REST shipping_address / GraphQL shippingAddress);
--    billing (billing_address / billingAddress); the REST top-level phone — when the incoming payload
--    has none of the group's keys with a non-null value, the stored payload's non-null keys of that
--    group are kept. Everything else comes from the incoming payload. IMMUTABLE, INVOKER, total.
--    Email is never a group (it is not approved data; the V144 trigger strips it from every write).
-- 2. shopify_order_updated_at(raw) — the payload's own Shopify updated time (REST updated_at /
--    GraphQL updatedAt), NULL when absent or malformed. The import writes an existing order only when
--    the incoming one is strictly newer, or the stored payload has none (UPSERT_ORDER_IMPORT).
-- 3. One-off restore, from the latest stored orders/* webhook payload per order (any tenant, never a
--    GDPR-redacted order):
--      FULL     — the order's payload is an import's GraphQL node (lineItems, no line_items) and the
--                 webhook payload's updated time is >= the node's (or the node has none): the whole
--                 webhook payload comes back — refunds, fulfillments, discount allocations,
--                 source_name… — still never poorer in customer data than the node;
--      CUSTOMER — otherwise: only the customer groups the payload lacks (rule 1).
--    The fill-only PII columns are filled the way UPSERT_ORDER fills them (never overwriting a value).
--    Idempotent: a restored payload is a webhook payload, so a second run changes nothing.

CREATE FUNCTION shopify_order_raw_keep_customer(stored jsonb, incoming jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN stored IS NULL OR jsonb_typeof(stored) <> 'object'
          OR incoming IS NULL OR jsonb_typeof(incoming) <> 'object' THEN incoming
        ELSE incoming || COALESCE((
            SELECT jsonb_object_agg(k, stored -> k)
            FROM (VALUES ('customer',         ARRAY['customer']),
                         ('shipping_address', ARRAY['shipping_address', 'shippingAddress']),
                         ('shippingAddress',  ARRAY['shipping_address', 'shippingAddress']),
                         ('billing_address',  ARRAY['billing_address', 'billingAddress']),
                         ('billingAddress',   ARRAY['billing_address', 'billingAddress']),
                         ('phone',            ARRAY['phone'])) AS g(k, grp)
            WHERE stored ? k AND stored -> k <> 'null'::jsonb
              AND NOT EXISTS (SELECT 1 FROM unnest(g.grp) gk
                              WHERE incoming ? gk AND incoming -> gk <> 'null'::jsonb)
        ), '{}'::jsonb)
    END
$$;

CREATE FUNCTION shopify_order_updated_at(raw jsonb) RETURNS timestamptz
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN COALESCE(raw ->> 'updated_at', raw ->> 'updatedAt') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
                THEN analytics_ts(COALESCE(raw ->> 'updated_at', raw ->> 'updatedAt')) END
$$;

WITH wh AS (
    SELECT DISTINCT ON (w.tenant_id, w.payload_raw ->> 'admin_graphql_api_id')
           w.tenant_id, w.payload_raw ->> 'admin_graphql_api_id' AS gid, w.payload_raw AS p
    FROM shopify_webhook_events w
    WHERE w.topic LIKE 'orders/%' AND w.payload_raw ? 'admin_graphql_api_id'
    ORDER BY w.tenant_id, w.payload_raw ->> 'admin_graphql_api_id', w.received_at DESC, w.id DESC
),
fix AS (
    SELECT o.id,
           CASE WHEN o.raw ? 'lineItems' AND NOT (o.raw ? 'line_items')
                 AND shopify_order_updated_at(wh.p) IS NOT NULL
                 AND (shopify_order_updated_at(o.raw) IS NULL
                      OR shopify_order_updated_at(wh.p) >= shopify_order_updated_at(o.raw))
                THEN shopify_order_raw_keep_customer(o.raw, wh.p)      -- FULL: the webhook payload
                ELSE shopify_order_raw_keep_customer(wh.p, o.raw)      -- CUSTOMER groups only
           END AS raw
    FROM orders o
    JOIN wh ON wh.tenant_id = o.tenant_id AND wh.gid = o.external_id
    WHERE o.pii_redacted_at IS NULL
)
UPDATE orders o
SET raw             = fix.raw,
    customer_name   = COALESCE(o.customer_name,   shopify_order_pii_name(fix.raw)),
    customer_phone  = COALESCE(o.customer_phone,  shopify_order_pii_phone(fix.raw)),
    shopify_address = COALESCE(o.shopify_address, shopify_order_pii_address(fix.raw)),
    pii_source      = CASE WHEN o.pii_source IS NULL
                            AND ((o.customer_name  IS NULL AND shopify_order_pii_name(fix.raw)  IS NOT NULL)
                              OR (o.customer_phone IS NULL AND shopify_order_pii_phone(fix.raw) IS NOT NULL))
                           THEN 'shopify' ELSE o.pii_source END
FROM fix
WHERE fix.id = o.id AND fix.raw IS DISTINCT FROM o.raw;
