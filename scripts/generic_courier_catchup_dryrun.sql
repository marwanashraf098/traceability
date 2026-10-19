-- Non-Bosta couriers G2 — READ-ONLY dry run of the OPTIONAL switch-on catch-up, per tenant.
--
-- How many generic shipments GenericCatchUp.run would create for each tenant if "I ship with other couriers"
-- were switched on now WITH "also include orders from the last N days" (-v days=N, 1–7, default 3). The default
-- switch-on ("start from now") creates nothing. Mirrors GenericCatchUp.dryRun (GenericCatchUpDryRunScriptTest keeps them equal):
--   open post-connect merchant orders (new / ready_to_pick, placed at/after the store's orders_ingest_from — NULL
--   never filters), no active forward shipment; stored fulfillment rows not cancelled, with a Shopify
--   fulfillment id; carrier class recomputed like FulfillmentTrackingCapture.carrierClass ('bosta' if company or
--   URL mentions bosta, 'other_known' if lower(trim(company)) is in other_known_carriers(), else 'unknown');
--   fulfillment tracking captured inside the window (first_seen_at >= now() - N days);
--   eligible = other_known, or unknown while the tenant has no active Bosta account; the earliest row per order
--   (first_seen_at, id) with a non-empty key whose key isn't already another generic shipment's.
-- Key: GenericTrackingKey for a plain number (digits folded, A–Z0–9, upper case). A stored tracking number that is
-- a URL is counted in url_rows and NOT in would_create — GenericTrackingKey parses URLs in Java only.
--
-- RUN (prod, read-only, a few seconds):
--   psql "$PROD_DATABASE_URL" -X -q -A -F '|' -v days=3 -f scripts/generic_courier_catchup_dryrun.sql
-- Connection: the owner / postgres role (sees every tenant). Session pooler :5432, never :6543.

\set ON_ERROR_STOP on
\if :{?days}
\else
  \set days 3
\endif
BEGIN READ ONLY;
SET LOCAL statement_timeout = '20s';
SET LOCAL lock_timeout = '2s';

WITH rows AS (
    SELECT o.tenant_id, o.id AS order_id, t.id AS row_id, t.first_seen_at, t.tracking_number,
           nullif(trim(t.carrier_raw), '') AS carrier,
           CASE
               WHEN lower(coalesce(trim(t.carrier_raw), '')) LIKE '%bosta%'
                 OR lower(coalesce(t.tracking_url, '')) LIKE '%bosta%' THEN 'bosta'
               WHEN lower(trim(coalesce(t.carrier_raw, ''))) = ANY (other_known_carriers()) THEN 'other_known'
               ELSE 'unknown'
           END AS cls,
           (t.tracking_number ~* '^\s*(https?://|www\.)') AS is_url,
           upper(regexp_replace(translate(t.tracking_number, '٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹', '01234567890123456789'),
                                '[^A-Za-z0-9]', '', 'g')) AS key
    FROM orders o
    JOIN stores st ON st.id = o.store_id
    JOIN order_fulfillment_tracking t ON t.order_id = o.id AND t.tenant_id = o.tenant_id
    WHERE o.external_id NOT LIKE 'internal:portal:%'                        -- merchant_orders
      AND o.status IN ('new', 'ready_to_pick')
      AND (st.orders_ingest_from IS NULL OR o.placed_at IS NULL OR o.placed_at >= st.orders_ingest_from)
      AND NOT EXISTS (SELECT 1 FROM shipments s WHERE s.order_id = o.id AND s.tenant_id = o.tenant_id
                        AND s.shipment_leg = 'forward' AND s.internal_state NOT IN ('terminated', 'cancelled'))
      AND lower(coalesce(t.fulfillment_status, '')) <> 'cancelled'
      AND t.shopify_fulfillment_id IS NOT NULL
      AND t.first_seen_at >= now() - ((:'days')::int * interval '1 day')
),
eligible AS (
    SELECT r.* FROM rows r
    WHERE r.cls = 'other_known'
       OR (r.cls = 'unknown' AND NOT EXISTS (SELECT 1 FROM courier_accounts ca WHERE ca.tenant_id = r.tenant_id
                                               AND ca.provider = 'bosta' AND ca.status = 'active'))
),
keyed AS (
    SELECT e.*, (e.key <> '') AS has_key,
           EXISTS (SELECT 1 FROM shipments g WHERE g.tenant_id = e.tenant_id AND g.provider = 'generic'
                     AND g.tracking_key = e.key) AS key_in_use
    FROM eligible e
),
first_per_order AS (
    SELECT DISTINCT ON (k.order_id) k.*
    FROM keyed k
    WHERE NOT k.is_url AND k.has_key AND NOT k.key_in_use
    ORDER BY k.order_id, k.first_seen_at, k.row_id
)
SELECT t.name AS tenant, t.id AS tenant_id, t.generic_courier_enabled AS switched_on,
       (SELECT count(*) FROM first_per_order f WHERE f.tenant_id = t.id) AS would_create,
       (SELECT count(DISTINCT k.order_id) FROM keyed k WHERE k.tenant_id = t.id AND k.key_in_use) AS key_in_use_orders,
       (SELECT count(*) FROM keyed k WHERE k.tenant_id = t.id AND k.is_url) AS url_rows,
       coalesce((SELECT string_agg(c.carrier || '=' || c.n, ', ' ORDER BY c.carrier)
                 FROM (SELECT coalesce(f.carrier, '(no carrier name)') AS carrier, count(*) AS n
                       FROM first_per_order f WHERE f.tenant_id = t.id GROUP BY 1) c), '') AS by_carrier
FROM tenants t
WHERE EXISTS (SELECT 1 FROM rows r WHERE r.tenant_id = t.id)
ORDER BY t.name, t.id;

ROLLBACK;
