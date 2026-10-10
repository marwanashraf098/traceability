-- ============================================================
-- V163 — Returns portal P4a: pre-connect orders (schema + the merchant_orders view).
--
-- P4 lets a customer start a return for an order placed BEFORE the store connected to Traced
-- (before stores.orders_ingest_from). Such an order is fetched on demand from Shopify (P4c) and
-- stored as a real orders row with origin = 'portal_pre_connect', so every returns path (portal,
-- Requests, Scan returns, booking, refunds, GDPR) works unchanged. It must NEVER appear anywhere
-- else — Orders, Pick & Pack, Lookup, analytics, Overview order metrics, forward alerts, Bosta
-- forward linking, committed stock.
--
-- origin              'shopify' (every existing row and every row ingest writes) or
--                     'portal_pre_connect'.
-- shopify_order_gid   the Shopify order GID of a portal row (its external_id is
--                     'internal:portal:<uuid>', so webhooks / reconcile / import — which all match
--                     by external_id — can never touch it). UNIQUE per tenant: one portal row per
--                     Shopify order, and the on-demand insert's race guard.
-- portal_fetched_at   when P4c fetched it.
-- portal_delivered_at Bosta's delivered time of the original delivery (a portal row has no
--                     forward shipment in Traced; P4b's eligibility reads this).
-- portal_delivery     the original Bosta delivery's tracking number, drop-off address and receiver
--                     (pickup area + booking read it, P4b). Customer PII — redacted with the order.
--
-- merchant_orders: every order EXCEPT portal rows. security_invoker, so RLS on orders applies as
--   the querying role (app_user); the WHERE also filters on BYPASSRLS owner pools and in tests.
--   It is a simple view, so UPDATE through it works and can only touch visible rows. Every
--   merchant-facing query reads and writes merchant_orders; only the classified allowlist in
--   OrdersTableAccessGuardTest may name the orders table directly.
--
-- RULE (CLAUDE.md): Postgres freezes "SELECT *" when the view is created. Any later migration
--   that adds, drops, renames or retypes an orders column must re-create merchant_orders in the
--   same migration (MerchantOrdersViewColumnsTest fails otherwise; a retype / drop fails outright
--   while the view exists).
--
-- No row is written here, and no P4a code path can create a portal row.
-- ============================================================

ALTER TABLE orders
    ADD COLUMN origin              text        NOT NULL DEFAULT 'shopify',
    ADD COLUMN shopify_order_gid   text,
    ADD COLUMN portal_fetched_at   timestamptz,
    ADD COLUMN portal_delivered_at timestamptz,
    ADD COLUMN portal_delivery     jsonb;

ALTER TABLE orders
    ADD CONSTRAINT orders_origin_check
        CHECK (origin IN ('shopify', 'portal_pre_connect')),
    ADD CONSTRAINT orders_portal_identity_check
        CHECK (origin <> 'portal_pre_connect'
               OR (shopify_order_gid IS NOT NULL AND external_id LIKE 'internal:portal:%'));

CREATE UNIQUE INDEX ux_orders_tenant_shopify_order_gid
    ON orders (tenant_id, shopify_order_gid)
    WHERE shopify_order_gid IS NOT NULL;

CREATE VIEW merchant_orders WITH (security_invoker = true) AS
    SELECT * FROM orders WHERE origin <> 'portal_pre_connect';

-- Same privileges app_user has on orders (V1 grants SELECT, INSERT, UPDATE, DELETE).
GRANT SELECT, INSERT, UPDATE, DELETE ON merchant_orders TO app_user;
