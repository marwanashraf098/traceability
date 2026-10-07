-- Analytics slice 8 benchmark tenant: 60k orders / 120k lines / ~60k shipments / ~180k history rows.
-- Raw payloads carry the fields analytics read plus random hex padding sized to prod's TOASTed
-- footprint (orders.raw ≈ 5.6 KB compressed, shipments.raw ≈ 8 KB, order_items.raw ≈ 1.3 KB) so
-- decompression costs are realistic. Dates are relative to now(): ~400 days of history.
-- Run once on an empty, migrated database as postgres.

SELECT setseed(0.42);

INSERT INTO tenants (id, name) VALUES ('b0000000-0000-0000-0000-000000000001', 'Bench');
INSERT INTO users (id, tenant_id, name, email, password_hash, role)
VALUES ('b0000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000001', 'Owner',
        'bench-owner@bench.test', 'x', 'owner');
INSERT INTO stores (id, tenant_id, platform, shop_domain, status, orders_ingest_from)
VALUES ('b0000000-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-000000000001', 'shopify',
        'bench.myshopify.com', 'connected', now() - interval '500 days');
INSERT INTO courier_accounts (tenant_id, provider, api_key_encrypted, webhook_secret, status)
VALUES ('b0000000-0000-0000-0000-000000000001', 'bosta', 'x', 'bench-h', 'active');
INSERT INTO bosta_districts (district_id, city_id, city_name, city_name_ar, district_name)
SELECT 'bd-' || c.n, 'bcity-' || c.n, c.name, c.name || ' (ar)', 'District ' || c.n
FROM (VALUES (1, 'Cairo'), (2, 'Giza'), (3, 'Alexandria'), (4, 'Dakahlia'), (5, 'El Kalioubia'),
             (6, 'Sharqia'), (7, 'Gharbia'), (8, 'Monufia')) c(n, name)
ON CONFLICT DO NOTHING;

CREATE TEMP TABLE pad AS
SELECT string_agg(md5(random()::text), '') AS p FROM generate_series(1, 400);

-- 200 products × 10 variants, sizes in option1.
INSERT INTO products (id, tenant_id, store_id, external_id, title, image_url, raw)
SELECT ('b1000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'b0000000-0000-0000-0000-000000000001',
       'b0000000-0000-0000-0000-000000000003', 'BP-' || g, 'Product ' || g, 'https://img/' || g,
       jsonb_build_object('product_type', (ARRAY['Jeans', 'T-Shirts', 'Shoes', 'Hoodies', '', 'Dresses', 'Bags', ' '])[1 + g % 8],
                          'options', jsonb_build_array(jsonb_build_object('name', 'Size', 'position', 1)),
                          'body_html', substr((SELECT p FROM pad), 1 + (g % 1000), 2000))
FROM generate_series(1, 200) g;

INSERT INTO variants (id, tenant_id, product_id, external_id, sku, title, price, raw)
SELECT ('b2000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'b0000000-0000-0000-0000-000000000001',
       ('b1000000-0000-0000-0000-' || lpad((1 + (g - 1) / 10)::text, 12, '0'))::uuid, 'BV-' || g, 'SKU-' || g,
       s.size, 200 + (g % 13) * 100,
       jsonb_build_object('option1', s.size)
FROM generate_series(1, 2000) g
CROSS JOIN LATERAL (SELECT (ARRAY['XS', 'S', 'M', 'L', 'XL', '2XL', '38', '40', '42', 'XL-XXL'])[1 + g % 10] AS size) s;

-- Orders.
INSERT INTO orders (id, tenant_id, store_id, external_id, number, status, placed_at, raw, shipping_carrier_class)
SELECT ('b3000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'b0000000-0000-0000-0000-000000000001',
       'b0000000-0000-0000-0000-000000000003', 'BO-' || g, '#B' || g,
       (CASE WHEN g % 100 = 0 THEN 'cancelled' ELSE 'new' END)::order_status,
       now() - (g * interval '577 seconds'),           -- 60k orders over ~400 days
       jsonb_build_object(
           'source_name', CASE WHEN g % 10 = 0 THEN 'shopify_draft_order' ELSE 'web' END,
           'referring_site', (ARRAY['https://instagram.com/', 'https://l.instagram.com/', 'https://m.facebook.com/',
                                    'https://www.google.com/', NULL, 'https://l.wl.co/x', 'https://bench-store.com/cart'])[1 + g % 7],
           'landing_site', '/products/p?utm_source=' || (ARRAY['facebook', 'ig', 'faceb', 'google', 'jeans%20summer'])[1 + g % 5],
           'order_status_url', 'https://bench-store.com/1/orders/' || md5(g::text) || '/authenticate',
           'payment_gateway_names', CASE g % 6 WHEN 0 THEN '["Paymob - Native Checkout for Debit/Credit Cards","Paymob"]'::jsonb
                                               WHEN 1 THEN '["manual"]'::jsonb
                                               WHEN 2 THEN '["Cash on Delivery (COD)","manual"]'::jsonb
                                               ELSE '["Cash on Delivery (COD)"]'::jsonb END,
           'shipping_address', jsonb_build_object('province_code', (ARRAY['C', 'GZ', 'ALX', 'DK', 'KB', 'SHR', 'XX'])[1 + g % 7],
                                                  'address1', md5((g * 7)::text), 'phone', '+20 10' || lpad((g % 99999999)::text, 8, '0')),
           'customer', jsonb_build_object('id', 7000000 + g % 30000, 'phone', '010' || lpad((g % 99999999)::text, 8, '0')),
           'discount_applications', jsonb_build_array(
                jsonb_build_object('type', 'discount_code', 'code', 'SAVE' || (g % 5)),
                jsonb_build_object('type', 'automatic', 'title', 'B2G1')),
           'fulfillments', CASE WHEN g % 3 <> 0 THEN jsonb_build_array(jsonb_build_object(
                'created_at', to_char((now() - (g * interval '577 seconds') + interval '1 day') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'status', 'success', 'line_items', jsonb_build_array(jsonb_build_object('id', g * 2)))) END,
           'refunds', CASE WHEN g % 10 = 0 THEN jsonb_build_array(jsonb_build_object(
                'created_at', to_char((now() - (g * interval '577 seconds') + interval '5 days') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                'refund_line_items', jsonb_build_array(jsonb_build_object('line_item_id', g * 2, 'quantity', 1,
                    'restock_type', CASE WHEN g % 20 = 0 THEN 'return' ELSE 'no_restock' END)))) END,
           'note', substr((SELECT p FROM pad), 1 + (g % 1500), 11000)),
       CASE WHEN g % 20 = 1 THEN 'other_known' WHEN g % 20 = 2 THEN NULL ELSE 'bosta' END
FROM generate_series(1, 60000) g;

-- Two lines per order.
INSERT INTO order_items (id, tenant_id, order_id, variant_id, quantity, external_id, raw)
SELECT ('b4000000-0000-0000-0000-' || lpad((g * 2 + k)::text, 12, '0'))::uuid, 'b0000000-0000-0000-0000-000000000001',
       ('b3000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid,
       ('b2000000-0000-0000-0000-' || lpad((1 + (g * 7 + k * 13) % 2000)::text, 12, '0'))::uuid,
       1 + (g + k) % 2, 'gid://shopify/LineItem/' || (g * 2 + k),
       jsonb_build_object('id', g * 2 + k, 'price', (300 + (g % 9) * 100)::text || '.00', 'quantity', 1 + (g + k) % 2,
                          'current_quantity', CASE WHEN g % 10 = 0 AND k = 0 THEN (1 + (g + k) % 2) - 1 ELSE 1 + (g + k) % 2 END,
                          'discount_allocations', jsonb_build_array(jsonb_build_object('amount', ((g % 7) * 10)::text || '.00',
                                                                                       'discount_application_index', k)),
                          'title', 'Item ' || g, 'properties', substr((SELECT p FROM pad), 1 + (g % 2000), 1300))
FROM generate_series(1, 60000) g
CROSS JOIN generate_series(0, 1) k
WHERE g % 50 <> 7 OR k = 0;                            -- a few single-line orders

-- A forward leg for every Bosta order.
INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw, provider_state,
                       created_at, delivered_at, returned_at, last_failure_reason, exception_reason)
SELECT ('b5000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'b0000000-0000-0000-0000-000000000001',
       ('b3000000-0000-0000-0000-' || lpad(g::text, 12, '0'))::uuid, 'B' || (10000000 + g),
       st.state::shipment_internal_state, 'forward',
       jsonb_build_object('type', jsonb_build_object('code', st.type_code, 'value', st.type_value), 'state', jsonb_build_object('code', st.code, 'value', st.value),
                          'cod', 600 + (g % 9) * 100, 'shipmentFees', 60 + g % 30,
                          'dropOffAddress', jsonb_build_object('city', jsonb_build_object('_id', 'bcity-' || (1 + g % 8),
                              'name', (ARRAY['Cairo', 'Giza', 'Alexandria', 'Dakahlia', 'El Kalioubia', 'Sharqia', 'Gharbia', 'Monufia'])[1 + g % 8])),
                          'collectedFromBusiness', to_char((now() - (g * interval '577 seconds') + interval '20 hours') AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                          'timeline', substr((SELECT p FROM pad), 1 + (g % 900), 12000)),
       st.code, now() - (g * interval '577 seconds') + interval '2 hours',
       CASE WHEN st.state = 'delivered' THEN now() - (g * interval '577 seconds') + interval '2 days' END,
       CASE WHEN st.state = 'returned' THEN now() - (g * interval '577 seconds') + interval '5 days' END,
       CASE WHEN st.state = 'returned' THEN (ARRAY['Cancellation - the customer refuses to receive the shipment.',
                                                  'Cancellation - product issue', NULL])[1 + g % 3] END,
       CASE WHEN st.state IN ('lost', 'returned') AND g % 2 = 0 THEN 'Postponed - the customer requested postponement for another day.' END
FROM generate_series(1, 60000) g
CROSS JOIN LATERAL (
    SELECT CASE WHEN g < 300 THEN 'created' WHEN g < 1500 THEN 'with_courier'
                WHEN g % 100 < 65 THEN 'delivered' WHEN g % 100 < 82 THEN 'returned'
                WHEN g % 100 < 85 THEN 'lost' WHEN g % 100 < 87 THEN 'terminated' ELSE 'delivered' END AS state
) s0
CROSS JOIN LATERAL (
    SELECT s0.state,
           CASE WHEN s0.state = 'returned' THEN 20 ELSE 10 END AS type_code,
           CASE WHEN s0.state = 'returned' THEN 'Return to Origin' ELSE 'Send' END AS type_value,
           CASE s0.state WHEN 'created' THEN 10 WHEN 'with_courier' THEN 24 WHEN 'delivered' THEN 45 WHEN 'returned' THEN 46
                         WHEN 'lost' THEN 100 ELSE 48 END AS code,
           s0.state AS value
) st
WHERE g % 20 NOT IN (1, 2);

-- Customer return pickups on some delivered orders (return legs).
INSERT INTO shipments (id, tenant_id, order_id, tracking_number, internal_state, shipment_leg, raw, provider_state, created_at, delivered_at)
SELECT ('b6000000-0000-0000-0000-' || lpad(s.g::text, 12, '0'))::uuid, s.tenant_id, s.order_id, 'R' || (10000000 + s.g), 'delivered', 'return',
       jsonb_build_object('type', jsonb_build_object('code', 25), 'state', jsonb_build_object('code', 46), 'shipmentFees', 90,
                          'timeline', substr((SELECT p FROM pad), 1, 8000)),
       46, s.created_at + interval '6 days', s.created_at + interval '8 days'
FROM (SELECT row_number() OVER () AS g, tenant_id, order_id, created_at FROM shipments
      WHERE tenant_id = 'b0000000-0000-0000-0000-000000000001' AND internal_state = 'delivered') s
WHERE s.g % 12 = 0;

-- History: with_courier then the end state.
INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at)
SELECT tenant_id, id, 'with_courier', created_at + interval '18 hours' FROM shipments
WHERE tenant_id = 'b0000000-0000-0000-0000-000000000001' AND internal_state <> 'created';
INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at)
SELECT tenant_id, id, internal_state, created_at + interval '2 days' FROM shipments
WHERE tenant_id = 'b0000000-0000-0000-0000-000000000001' AND internal_state NOT IN ('created', 'with_courier');
INSERT INTO shipment_status_history (tenant_id, shipment_id, internal_state, occurred_at)
SELECT tenant_id, id, 'returning', created_at + interval '3 days' FROM shipments
WHERE tenant_id = 'b0000000-0000-0000-0000-000000000001' AND internal_state = 'returned';

-- Settlement: delivered / returned legs older than 10 days settled; older than 20 days paid on Wednesdays.
UPDATE shipments s SET
    settlement_status = CASE WHEN s.created_at < now() - interval '20 days' THEN 'paid' ELSE 'deposited' END,
    deposited_at = s.created_at + interval '4 days',
    deposited_amt = CASE WHEN s.internal_state = 'delivered' THEN 600 ELSE -80 END,
    cod_settled = CASE WHEN s.internal_state = 'delivered' THEN 690 ELSE 0 END,
    bosta_fees = 90, shipping_fees = 70, vat = 10, opening_package_fees = 10, promotion_discount = 0,
    shipment_fees_quoted = 70,
    cash_cycle_id = 'cc' || substr(s.id::text, 25),
    cashout_txn_id = CASE WHEN s.created_at < now() - interval '20 days'
                          THEN 'WEDCOD' || to_char(date_trunc('week', s.created_at + interval '9 days')::date + 2, 'DDMONYY') END,
    cashout_date = CASE WHEN s.created_at < now() - interval '20 days'
                        THEN date_trunc('week', s.created_at + interval '9 days')::date + 2 END,
    settlement_refreshed_at = now() - interval '1 hour'
WHERE s.tenant_id = 'b0000000-0000-0000-0000-000000000001' AND s.internal_state IN ('delivered', 'returned')
  AND s.created_at < now() - interval '10 days';
UPDATE shipments SET shipment_fees_quoted = 70
WHERE tenant_id = 'b0000000-0000-0000-0000-000000000001' AND shipment_fees_quoted IS NULL;

-- A thousand scanned returns (return_received from delivered) on delivered orders.
INSERT INTO pieces (id, tenant_id, variant_id, barcode, short_code, status)
SELECT 'BPC' || g, 'b0000000-0000-0000-0000-000000000001', oi.variant_id, 'PC-BPC' || g, 'B' || lpad(g::text, 6, '0'),
       'return_pending_inspection'
FROM (SELECT row_number() OVER () AS g, oi.* FROM order_items oi
      JOIN shipments s ON s.order_id = oi.order_id AND s.internal_state = 'delivered' AND s.shipment_leg = 'forward'
      WHERE oi.tenant_id = 'b0000000-0000-0000-0000-000000000001' LIMIT 30000) oi
WHERE oi.g % 30 = 0;
INSERT INTO allocations (tenant_id, order_item_id, piece_id, status)
SELECT p.tenant_id, oi.id, p.id, 'packed'
FROM pieces p JOIN LATERAL (SELECT oi.id FROM order_items oi
                            JOIN shipments s ON s.order_id = oi.order_id AND s.internal_state = 'delivered' AND s.shipment_leg = 'forward'
                            WHERE oi.tenant_id = p.tenant_id AND oi.variant_id = p.variant_id LIMIT 1) oi ON true
WHERE p.tenant_id = 'b0000000-0000-0000-0000-000000000001';
INSERT INTO piece_events (tenant_id, piece_id, event_type, order_id, from_status, to_status, metadata)
SELECT a.tenant_id, a.piece_id, 'return_received', oi.order_id, 'delivered', 'return_pending_inspection',
       '{"return_kind":"customer_after_delivery"}'::jsonb
FROM allocations a JOIN order_items oi ON oi.id = a.order_item_id
WHERE a.tenant_id = 'b0000000-0000-0000-0000-000000000001';

ANALYZE;
