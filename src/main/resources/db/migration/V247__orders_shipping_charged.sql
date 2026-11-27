-- V247 — Analytics slice 4 (approved 2026-11-27, decision (a)): the shipping the customer actually paid.
--
-- orders.shipping_charged = Shopify's current_shipping_price_set (shop money) — the shipping price after
-- shipping discounts, edits and refunds (prod 2026-11-27: on 62 of the 68 non-cancelled orders with a
-- shipping refund it already nets the refund out; the other 6 are COD orders whose payment was voided —
-- nothing was collected, none delivered). NULL when the key is missing (GraphQL-imported raw, no raw):
-- "shipping unknown", never 0. Read by Order finances (Total = lines + shipping) and Profit (shipping
-- income); a STORED generated column (V149's pattern) so no request decompresses raw.
--
-- RULE (V149): a generated expression that throws breaks Shopify ingest. analytics_num (V149) is the
-- guarded numeric cast: IMMUTABLE, regex-checked, NULL on anything that isn't a plain decimal.
--
-- Adding a column to orders: merchant_orders and warehouse_orders freeze their `*` at creation, so both
-- are re-created here with V216's definitions unchanged (CREATE OR REPLACE appends the new last column
-- and keeps grants and dependants; MerchantOrdersViewColumnsTest / WarehouseOrdersViewTest).
--
-- Rewrites orders (ACCESS EXCLUSIVE for the rewrite; prod 2026-11-27: 41,398 rows, 295 MB with TOAST).

ALTER TABLE orders
    ADD COLUMN shipping_charged numeric
        GENERATED ALWAYS AS (analytics_num(raw #>> '{current_shipping_price_set,shop_money,amount}')) STORED;

CREATE OR REPLACE VIEW merchant_orders WITH (security_invoker = true) AS
    SELECT * FROM orders
    WHERE external_id NOT LIKE 'internal:portal:%'
      AND external_id NOT LIKE 'internal:shopify-exchange:%';

CREATE OR REPLACE VIEW warehouse_orders WITH (security_invoker = true) AS
    SELECT * FROM orders WHERE external_id NOT LIKE 'internal:portal:%';
