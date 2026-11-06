-- ============================================================
-- V216 — Shopify-native returns S3a: room for exchange shadow orders (views + identity CHECK).
--
-- S3 keeps the replacement of an exchange made IN Shopify aside and packs it through Traced, on a
-- shadow order: external_id 'internal:shopify-exchange:<id>', number '#<original>-X1'. A shadow is a
-- warehouse job, never a sale (decision C1, 2026-11-01): Pick & Pack, Pickups, waybills, committed
-- demand and warehouse alerts must see it; Orders, Analytics, Overview order counts, the embedded
-- app, the portal lookup and Main reconcile must not. No shadow row is created by S3a (S3b writes them),
-- so nothing anyone sees changes here.
--
-- origin               + 'shopify_exchange'. The identity CHECK makes it equivalent to the
--                      'internal:shopify-exchange:' prefix, both ways (the V163 pattern), so the views
--                      can filter on external_id, an early cheap column, never on origin.
-- merchant_orders      every order except portal pre-connect rows AND shadow orders — the default for
--                      every query (sales screens, counts, analytics, reconcile).
-- warehouse_orders     every order except portal pre-connect rows (what merchant_orders was until now):
--                      only the classified members in OrdersTableAccessGuardTest.WAREHOUSE_ALLOWLIST
--                      may name it (Pick & Pack, pack / print / labels, pickups, waybill resolution,
--                      shipment linking, committed demand, warehouse alerts).
-- Both are security_invoker simple views (RLS on orders applies as app_user; UPDATE through them works
-- and can only touch rows they show). The partial index for Overview's order counts is re-created on the
-- new merchant_orders predicate in V217 (CONCURRENTLY, its own migration).
--
-- RULE (CLAUDE.md): a migration that adds, drops, renames or retypes an orders column must re-create
-- BOTH views in the same migration (MerchantOrdersViewColumnsTest).
-- ============================================================

ALTER TABLE orders DROP CONSTRAINT orders_origin_check;
ALTER TABLE orders
    ADD CONSTRAINT orders_origin_check
        CHECK (origin IN ('shopify', 'portal_pre_connect', 'shopify_exchange')),
    ADD CONSTRAINT orders_shopify_exchange_identity_check
        CHECK ((origin = 'shopify_exchange') = (external_id LIKE 'internal:shopify-exchange:%'));

-- Same columns as before (SELECT * of the same table), so CREATE OR REPLACE keeps the grants and no
-- dependent object has to be dropped.
CREATE OR REPLACE VIEW merchant_orders WITH (security_invoker = true) AS
    SELECT * FROM orders
    WHERE external_id NOT LIKE 'internal:portal:%'
      AND external_id NOT LIKE 'internal:shopify-exchange:%';

CREATE VIEW warehouse_orders WITH (security_invoker = true) AS
    SELECT * FROM orders WHERE external_id NOT LIKE 'internal:portal:%';

-- Same privileges app_user has on orders (V1 grants SELECT, INSERT, UPDATE, DELETE).
GRANT SELECT, INSERT, UPDATE, DELETE ON warehouse_orders TO app_user;
