-- V149 — Analytics slice 8: read-time raw parsing moved into STORED generated columns.
--
-- Every analytics query used to decompress and parse orders.raw / order_items.raw /
-- shipments.raw (and products / variants raw) on each request. The fields they read are
-- row-local, so they become STORED generated columns over IMMUTABLE functions: Postgres
-- recomputes them on every INSERT / UPDATE of the row (after BEFORE triggers — so the V143
-- GDPR redaction and V144 email strip are applied first), existing rows fill when this
-- migration rewrites the table, and NO ingest code changes.
--
-- RULE: a generated expression that throws makes the row's INSERT / UPDATE fail, i.e. it would
-- break Shopify / Bosta ingest. Every function here is total: casts are regex-guarded or checked
-- with pg_input_is_valid and return NULL on anything unexpected. NO plpgsql EXCEPTION blocks: each
-- one opens a subtransaction per call (a table rewrite of 60k orders took >10 minutes) and makes
-- the function unusable in a parallel plan.
--
-- Mapping functions (channel, payment, failure reason) are the SQL twins of the Java tables in
-- com.traceability.analytics.AnalyticsMappings; AnalyticsSqlParityTest asserts identical output.
--
-- Not row-local, so NOT here (kept in SQL at read time): variants.price fallback for lines with no
-- raw price, order outcomes (shipments + status history), customer returns (piece events, return
-- requests), settlement (V148 columns), the store's analytics floor.

-- ── helpers ────────────────────────────────────────────────────────────────────

-- Java's AnalyticsMappings.trim: drop zero-width characters, then String.trim (chars ≤ U+0020).
CREATE FUNCTION analytics_trim(t text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT regexp_replace(regexp_replace(t, '[​-‍﻿]', '', 'g'), '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g')
$$;

-- java.lang.String.trim only (chars ≤ U+0020).
CREATE FUNCTION analytics_java_trim(t text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT regexp_replace(t, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g')
$$;

CREATE FUNCTION analytics_num(t text) RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN t ~ '^\s*-?[0-9]+(\.[0-9]+)?\s*$' THEN t::numeric END
$$;

CREATE FUNCTION analytics_int(t text) RETURNS int LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN t ~ '^\s*-?[0-9]{1,9}\s*$' THEN t::int END
$$;

-- A timestamp that won't parse is NULL, never an error. Shopify / Bosta timestamps carry an
-- offset or Z, so the result does not depend on the session time zone.
CREATE FUNCTION analytics_ts(t text) RETURNS timestamptz LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN t IS NOT NULL AND t <> '' AND pg_input_is_valid(t, 'timestamptz') THEN t::timestamptz END
$$;

-- java.net.URLDecoder.decode(t, UTF-8) as far as channel classification can tell: '+' → space,
-- %XX below 0x80 → that ASCII character, any byte from 0x80 (and %00) → U+FFFD; NULL where Java
-- throws (a malformed escape) so the caller falls back to the raw text, as the Java code does. The
-- platform rules only test ASCII prefixes, so a multi-byte character decoding to one character in
-- Java and to several U+FFFD here classifies the same (AnalyticsSqlParityTest).
CREATE FUNCTION analytics_url_decode(t text) RETURNS text LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
    out text := '';
    i int := 1;
    n int;
    c text;
    b int;
BEGIN
    IF t IS NULL THEN RETURN NULL; END IF;
    IF position('%' IN t) = 0 AND position('+' IN t) = 0 THEN RETURN t; END IF;
    n := length(t);
    WHILE i <= n LOOP
        c := substr(t, i, 1);
        IF c = '+' THEN
            out := out || ' ';
        ELSIF c = '%' THEN
            IF i + 2 > n OR substr(t, i + 1, 2) !~ '^[0-9A-Fa-f]{2}$' THEN RETURN NULL; END IF;
            b := ('x' || substr(t, i + 1, 2))::bit(8)::int;
            out := out || CASE WHEN b BETWEEN 1 AND 127 THEN chr(b) ELSE chr(65533) END;
            i := i + 2;
        ELSE
            out := out || c;
        END IF;
        i := i + 1;
    END LOOP;
    RETURN out;
END
$$;

-- ── channel (AnalyticsMappings.channel) ────────────────────────────────────────

CREATE FUNCTION analytics_referrer_host(url text) RETURNS text LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
    u text := lower(analytics_trim(url));
    rest text;
    host text;
    k int;
    e int;
BEGIN
    IF u IS NULL OR u = '' THEN RETURN NULL; END IF;
    k := position('://' IN u);
    rest := CASE WHEN k > 0 THEN substr(u, k + 3) ELSE u END;
    e := length(rest) + 1;
    IF position('/' IN rest) > 0 THEN e := LEAST(e, position('/' IN rest)); END IF;
    IF position('?' IN rest) > 0 THEN e := LEAST(e, position('?' IN rest)); END IF;
    IF position('#' IN rest) > 0 THEN e := LEAST(e, position('#' IN rest)); END IF;
    host := substr(rest, 1, e - 1);
    IF position('@' IN host) > 0 THEN host := regexp_replace(host, '^.*@', ''); END IF;
    IF position(':' IN host) > 0 THEN host := substr(host, 1, position(':' IN host) - 1); END IF;
    RETURN NULLIF(host, '');
END
$$;

CREATE FUNCTION analytics_strip_www(h text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN h LIKE 'www.%' THEN substr(h, 5) ELSE h END
$$;

CREATE FUNCTION analytics_platform_of_host(h text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN h IS NULL THEN NULL
        WHEN strpos(h, 'instagram') > 0 THEN 'Instagram'
        WHEN strpos(h, 'facebook') > 0 OR h = 'fb.com' OR h LIKE '%.fb.com' OR h = 'fb.me'
             OR strpos(h, 'messenger') > 0 OR h IN ('com.facebook.katana', 'com.facebook.orca') THEN 'Facebook'
        WHEN strpos(h, 'tiktok') > 0 OR strpos(h, 'musically') > 0 THEN 'TikTok'
        WHEN h = 'google' OR h LIKE 'google.%' OR strpos(h, '.google.') > 0
             OR h LIKE 'com.google.android.googlequicksearchbox%' THEN 'Google'
    END
$$;

-- utm_source of a landing path: first [?&]utm_source=…, URL-decoded (raw on a malformed escape),
-- String.trim, lower-case.
CREATE FUNCTION analytics_utm_source(landing text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT lower(analytics_java_trim(COALESCE(analytics_url_decode(m), m)))
    FROM (SELECT substring(landing FROM '(?i)[?&]utm_source=([^&#]*)') AS m) x
$$;

CREATE FUNCTION analytics_platform_of_utm(u text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN u IS NULL OR u = '' THEN NULL
        WHEN u = 'ig' OR u = 'instagram' OR u LIKE 'instagram%' THEN 'Instagram'
        WHEN u = 'fb' OR (length(u) >= 2 AND left('facebook', length(u)) = u) OR u LIKE 'facebook%' THEN 'Facebook'
        WHEN u = 'tt' OR u LIKE 'tiktok%' THEN 'TikTok'
        WHEN u LIKE 'google%' THEN 'Google'
    END
$$;

-- own_hosts: the store's own hosts without www (the order status page's host). Any
-- *.myshopify.com host is the store too.
CREATE FUNCTION analytics_channel(source_name text, has_source_fields boolean, referring_site text,
                                  landing_site text, own_hosts text[]) RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
    host text;
    p text;
BEGIN
    IF lower(analytics_trim(source_name)) = 'shopify_draft_order' THEN RETURN 'Manual / DM'; END IF;
    IF NOT COALESCE(has_source_fields, false) THEN RETURN 'Unknown'; END IF;
    host := analytics_referrer_host(referring_site);
    p := analytics_platform_of_host(host);
    IF p IS NOT NULL THEN RETURN p; END IF;
    p := analytics_platform_of_utm(analytics_utm_source(landing_site));
    IF p IS NOT NULL THEN RETURN p; END IF;
    IF host IS NOT NULL AND host <> ''
       AND NOT COALESCE(analytics_strip_www(host) = ANY (own_hosts), false)
       AND host NOT LIKE '%.myshopify.com' THEN
        RETURN 'Other referral';
    END IF;
    RETURN 'Direct';
END
$$;

CREATE FUNCTION analytics_order_channel(raw jsonb) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT analytics_channel(
        raw ->> 'source_name',
        (raw -> 'source_name') IS NOT NULL OR (raw -> 'referring_site') IS NOT NULL OR (raw -> 'landing_site') IS NOT NULL,
        raw ->> 'referring_site', raw ->> 'landing_site',
        ARRAY[analytics_strip_www(analytics_referrer_host(raw ->> 'order_status_url'))])
$$;

-- ── payment (AnalyticsMappings.payment) ────────────────────────────────────────

CREATE FUNCTION analytics_gateway_kind(g text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN t IS NULL OR t = '' THEN NULL
        WHEN strpos(t, 'cash on delivery') > 0 OR t = 'cod' OR strpos(t, '(cod)') > 0 THEN 'COD'
        WHEN t = 'manual' THEN 'Manual'
        WHEN strpos(t, 'gift') > 0 THEN 'Other'
        WHEN t ~ '(paymob|kashier|card|valu|fawry|instapay|wallet)' THEN 'Card'
        ELSE 'Other'
    END
    FROM (SELECT lower(analytics_trim(g)) AS t) x
$$;

-- gateways: a JSON array of names (non-strings ignored) or one string.
CREATE FUNCTION analytics_payment(gateways jsonb) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN COUNT(DISTINCT k) = 0 THEN 'Other' WHEN COUNT(DISTINCT k) > 1 THEN 'Mixed' ELSE MIN(k) END
    FROM (
        SELECT analytics_gateway_kind(e #>> '{}') AS k
        FROM jsonb_array_elements(CASE jsonb_typeof(gateways) WHEN 'array' THEN gateways
                                                              WHEN 'string' THEN jsonb_build_array(gateways)
                                                              ELSE '[]'::jsonb END) e
        WHERE jsonb_typeof(e) = 'string'
    ) kinds
$$;

-- ── failure reason (AnalyticsMappings.failureReason) ───────────────────────────

CREATE FUNCTION analytics_failure_category(reason text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN r IS NULL OR r = '' THEN NULL
        WHEN strpos(r, 'refus') > 0 OR strpos(r, 'reject') > 0 OR strpos(r, 'doesn''t want') > 0
             OR strpos(r, 'does not want') > 0 OR strpos(r, 'customer cancel') > 0 THEN 'Customer refused'
        WHEN strpos(r, 'not in the address') > 0 OR strpos(r, 'not home') > 0 OR strpos(r, 'not available') > 0
             OR strpos(r, 'phone') > 0 OR strpos(r, 'unreachable') > 0 OR strpos(r, 'not answer') > 0
             OR strpos(r, 'no answer') > 0 OR strpos(r, 'closed') > 0 OR strpos(r, 'switched off') > 0
             THEN 'Phone unreachable / not home'
        WHEN strpos(r, 'address') > 0 OR strpos(r, 'location') > 0 OR strpos(r, 'wrong area') > 0
             OR strpos(r, 'out of zone') > 0 THEN 'Wrong or incomplete address'
        WHEN strpos(r, 'postpon') > 0 OR strpos(r, 'reschedul') > 0 OR strpos(r, 'another day') > 0
             OR strpos(r, 'later date') > 0 THEN 'Postponed / rescheduled'
        WHEN strpos(r, 'product') > 0 OR strpos(r, 'damaged') > 0 OR strpos(r, 'wrong item') > 0
             OR strpos(r, 'size') > 0 OR strpos(r, 'quality') > 0 OR strpos(r, 'open package') > 0 THEN 'Product issue'
        ELSE 'Other'
    END
    FROM (SELECT lower(analytics_trim(reason)) AS r) x
$$;

-- ── customer key ───────────────────────────────────────────────────────────────

-- Egyptian mobile to 01XXXXXXXXX: digits only, +20 / 0020 dropped; other numbers kept as digits
-- (8+), shorter → NULL.
CREATE FUNCTION analytics_canonical_phone(p text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN d IS NULL OR d = '' THEN NULL
        WHEN length(d2) = 10 AND d2 LIKE '1%' THEN '0' || d2
        WHEN length(d2) >= 8 THEN d2
    END
    FROM (SELECT regexp_replace(p, '[^0-9]', '', 'g') AS d) a
    CROSS JOIN LATERAL (SELECT CASE WHEN a.d LIKE '0020%' THEN substr(a.d, 5)
                                    WHEN a.d LIKE '20%' AND length(a.d) = 12 THEN substr(a.d, 3)
                                    ELSE a.d END AS d2) b
$$;

-- 'c:<Shopify customer id>' else 'p:<canonical phone>'. Built only from customer / phone /
-- address fields, which GDPR redaction (shopify_order_raw_redacted, V143) removes from raw — so a
-- redacted order's key becomes NULL when the redaction rewrites raw.
CREATE FUNCTION analytics_customer_key(raw jsonb) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE
        WHEN cid IS NOT NULL AND cid <> '' THEN 'c:' || cid
        WHEN ph IS NOT NULL THEN 'p:' || ph
    END
    FROM (SELECT regexp_replace(COALESCE(raw #>> '{customer,id}', ''), '^gid://shopify/Customer/', '') AS cid,
                 analytics_canonical_phone(COALESCE(raw #>> '{customer,phone}', raw #>> '{shipping_address,phone}',
                     raw ->> 'phone', raw #>> '{billing_address,phone}', raw #>> '{shippingAddress,phone}',
                     raw #>> '{customer,defaultPhoneNumber,phoneNumber}', raw #>> '{billingAddress,phone}')) AS ph) x
$$;

-- ── orders: discounts, fulfillment, refunds ────────────────────────────────────

-- Type of each discount application, by index (NULL when there is no array).
CREATE FUNCTION analytics_discount_types(raw jsonb) RETURNS text[] LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN jsonb_typeof(raw -> 'discount_applications') = 'array' THEN
        ARRAY(SELECT e ->> 'type' FROM jsonb_array_elements(raw -> 'discount_applications') WITH ORDINALITY a(e, i) ORDER BY i)
    END
$$;

-- Code (else title), upper-cased and trimmed, of each discount application, by index.
CREATE FUNCTION analytics_discount_labels(raw jsonb) RETURNS text[] LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN jsonb_typeof(raw -> 'discount_applications') = 'array' THEN
        ARRAY(SELECT upper(btrim(COALESCE(e ->> 'code', e ->> 'title')))
              FROM jsonb_array_elements(raw -> 'discount_applications') WITH ORDINALITY a(e, i) ORDER BY i)
    END
$$;

-- The distinct discount codes the order used (type discount_code), sorted.
CREATE FUNCTION analytics_discount_codes(raw jsonb) RETURNS text[] LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN jsonb_typeof(raw -> 'discount_applications') = 'array' THEN
        ARRAY(SELECT DISTINCT upper(btrim(COALESCE(e ->> 'code', e ->> 'title')))
              FROM jsonb_array_elements(raw -> 'discount_applications') e
              WHERE e ->> 'type' = 'discount_code' AND COALESCE(e ->> 'code', e ->> 'title') IS NOT NULL
              ORDER BY 1)
    END
$$;

CREATE FUNCTION analytics_shopify_fulfilled(raw jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(raw -> 'fulfillments') = 'array'
                                                           THEN raw -> 'fulfillments' ELSE '[]'::jsonb END) f
                   WHERE COALESCE(f ->> 'status', '') NOT IN ('cancelled', 'error', 'failure'))
        OR COALESCE(raw ->> 'displayFulfillmentStatus' IN ('FULFILLED', 'PARTIALLY_FULFILLED'), false)
$$;

-- Per refunded Shopify line id: [added_back, shopify_returned, unverified_no_restock] — the slice-2
-- refund add-back rule (SalesAnalyticsService.soldLines), from the order's own raw only:
--   added_back = refunded units with restock 'return' / 'no_restock' whose refund came AFTER a
--                non-cancelled fulfillment containing the line; no 'fulfillments' key → 'return' only;
--   shopify_returned = the added-back 'return' units;
--   unverified_no_restock = 'no_restock' refund lines on an order with no 'fulfillments' key.
-- NULL when the order has no refund lines. A key whose value is JSON null counts as absent.
CREATE FUNCTION analytics_refund_lines(raw jsonb) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
    refunds jsonb := raw -> 'refunds';
    fs      jsonb := raw -> 'fulfillments';
    has_f   boolean;
    r       jsonb;
    rli     jsonb;
    rt      timestamptz;
    line    text;
    units   int;
    restock text;
    counts  boolean;
    cur     jsonb;
    acc     jsonb := '{}'::jsonb;
    seen    boolean := false;
BEGIN
    IF jsonb_typeof(refunds) IS DISTINCT FROM 'array' THEN RETURN NULL; END IF;
    has_f := fs IS NOT NULL AND jsonb_typeof(fs) <> 'null';
    IF jsonb_typeof(fs) IS DISTINCT FROM 'array' THEN fs := '[]'::jsonb; END IF;
    FOR r IN SELECT e FROM jsonb_array_elements(refunds) e LOOP
        CONTINUE WHEN jsonb_typeof(r -> 'refund_line_items') IS DISTINCT FROM 'array';
        rt := analytics_ts(r ->> 'created_at');
        FOR rli IN SELECT e FROM jsonb_array_elements(r -> 'refund_line_items') e LOOP
            line := rli ->> 'line_item_id';
            CONTINUE WHEN line IS NULL;
            units := COALESCE(analytics_int(rli ->> 'quantity'), 0);
            restock := rli ->> 'restock_type';
            counts := false;
            IF restock IN ('return', 'no_restock') THEN
                IF has_f THEN
                    counts := EXISTS (
                        SELECT 1 FROM jsonb_array_elements(fs) f
                        WHERE COALESCE(f ->> 'status', '') NOT IN ('cancelled', 'error', 'failure')
                          AND analytics_ts(f ->> 'created_at') < rt
                          AND EXISTS (SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(f -> 'line_items') = 'array'
                                                                              THEN f -> 'line_items' ELSE '[]'::jsonb END) fl
                                      WHERE fl ->> 'id' = line));
                ELSE
                    counts := restock = 'return';
                END IF;
            END IF;
            cur := COALESCE(acc -> line, '[0, 0, 0]'::jsonb);
            acc := jsonb_set(acc, ARRAY[line], jsonb_build_array(
                (cur ->> 0)::int + CASE WHEN counts THEN units ELSE 0 END,
                (cur ->> 1)::int + CASE WHEN counts AND restock = 'return' THEN units ELSE 0 END,
                (cur ->> 2)::int + CASE WHEN restock = 'no_restock' AND NOT has_f THEN 1 ELSE 0 END));
            seen := true;
        END LOOP;
    END LOOP;
    RETURN CASE WHEN seen THEN acc END;
END
$$;

-- ── order_items: allocations ───────────────────────────────────────────────────

CREATE FUNCTION analytics_alloc_amounts(raw jsonb) RETURNS numeric[] LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN jsonb_typeof(raw -> 'discount_allocations') = 'array' THEN
        ARRAY(SELECT analytics_num(d ->> 'amount') FROM jsonb_array_elements(raw -> 'discount_allocations') WITH ORDINALITY a(d, i) ORDER BY i)
    END
$$;

CREATE FUNCTION analytics_alloc_indexes(raw jsonb) RETURNS int[] LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN jsonb_typeof(raw -> 'discount_allocations') = 'array' THEN
        ARRAY(SELECT analytics_int(d ->> 'discount_application_index') FROM jsonb_array_elements(raw -> 'discount_allocations') WITH ORDINALITY a(d, i) ORDER BY i)
    END
$$;

-- Sum of the line's discount allocations; 0 when there are none.
CREATE FUNCTION analytics_line_discount(raw jsonb) RETURNS numeric LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT COALESCE((SELECT SUM(analytics_num(d ->> 'amount'))
                     FROM jsonb_array_elements(CASE WHEN jsonb_typeof(raw -> 'discount_allocations') = 'array'
                                                    THEN raw -> 'discount_allocations' ELSE '[]'::jsonb END) d), 0)
$$;

-- ── products / shipments ───────────────────────────────────────────────────────

-- Position of the product option named like "size" (first by position; NULL when none or when it
-- has no position).
CREATE FUNCTION analytics_size_position(raw jsonb) RETURNS int LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT analytics_int(opt ->> 'position')
    FROM jsonb_array_elements(CASE WHEN jsonb_typeof(raw -> 'options') = 'array' THEN raw -> 'options' ELSE '[]'::jsonb END) opt
    WHERE opt ->> 'name' ILIKE '%size%' OR opt ->> 'name' LIKE '%مقاس%'
    ORDER BY analytics_int(opt ->> 'position') NULLS LAST
    LIMIT 1
$$;

-- ── the columns ────────────────────────────────────────────────────────────────

ALTER TABLE orders
    ADD COLUMN raw_cancelled      boolean GENERATED ALWAYS AS ((raw ->> 'cancelled_at') IS NOT NULL) STORED,
    ADD COLUMN is_cancelled       boolean GENERATED ALWAYS AS (status = 'cancelled' OR (raw ->> 'cancelled_at') IS NOT NULL) STORED,
    ADD COLUMN source_name        text    GENERATED ALWAYS AS (raw ->> 'source_name') STORED,
    ADD COLUMN channel            text    GENERATED ALWAYS AS (analytics_order_channel(raw)) STORED,
    ADD COLUMN payment_group      text    GENERATED ALWAYS AS (analytics_payment(COALESCE(NULLIF(raw -> 'payment_gateway_names', 'null'::jsonb),
                                                                                       raw -> 'paymentGatewayNames'))) STORED,
    ADD COLUMN customer_key       text    GENERATED ALWAYS AS (analytics_customer_key(raw)) STORED,
    ADD COLUMN ship_province      text    GENERATED ALWAYS AS (COALESCE(raw #>> '{shipping_address,province_code}',
                                                                        raw #>> '{shippingAddress,provinceCode}')) STORED,
    ADD COLUMN shopify_fulfilled  boolean GENERATED ALWAYS AS (analytics_shopify_fulfilled(raw)) STORED,
    ADD COLUMN discount_types     text[]  GENERATED ALWAYS AS (analytics_discount_types(raw)) STORED,
    ADD COLUMN discount_labels    text[]  GENERATED ALWAYS AS (analytics_discount_labels(raw)) STORED,
    ADD COLUMN discount_codes     text[]  GENERATED ALWAYS AS (analytics_discount_codes(raw)) STORED,
    ADD COLUMN refund_lines       jsonb   GENERATED ALWAYS AS (analytics_refund_lines(raw)) STORED;

ALTER TABLE order_items
    ADD COLUMN line_key           text    GENERATED ALWAYS AS (substring(external_id FROM '^gid://shopify/LineItem/(.*)$')) STORED,
    ADD COLUMN unit_price         numeric GENERATED ALWAYS AS (analytics_num(raw ->> 'price')) STORED,
    ADD COLUMN price_is_raw       boolean GENERATED ALWAYS AS (analytics_num(raw ->> 'price') IS NOT NULL) STORED,
    ADD COLUMN original_qty       int     GENERATED ALWAYS AS (analytics_int(raw ->> 'quantity')) STORED,
    ADD COLUMN current_qty        int     GENERATED ALWAYS AS (analytics_int(raw ->> 'current_quantity')) STORED,
    ADD COLUMN line_discount      numeric GENERATED ALWAYS AS (analytics_line_discount(raw)) STORED,
    ADD COLUMN net_unit_price     numeric GENERATED ALWAYS AS (analytics_num(raw ->> 'price')
                                                               - analytics_line_discount(raw)
                                                                 / NULLIF(COALESCE(analytics_int(raw ->> 'quantity'), quantity), 0)) STORED,
    ADD COLUMN alloc_amounts      numeric[] GENERATED ALWAYS AS (analytics_alloc_amounts(raw)) STORED,
    ADD COLUMN alloc_indexes      int[]   GENERATED ALWAYS AS (analytics_alloc_indexes(raw)) STORED;

ALTER TABLE shipments
    ADD COLUMN type_code          text    GENERATED ALWAYS AS (raw #>> '{type,code}') STORED,
    ADD COLUMN state_value        text    GENERATED ALWAYS AS (raw #>> '{state,value}') STORED,
    ADD COLUMN city_id            text    GENERATED ALWAYS AS (raw #>> '{dropOffAddress,city,_id}') STORED,
    ADD COLUMN city_name          text    GENERATED ALWAYS AS (raw #>> '{dropOffAddress,city,name}') STORED,
    ADD COLUMN raw_cod            numeric GENERATED ALWAYS AS (CASE WHEN (raw ->> 'cod') ~ '^-?[0-9]+(\.[0-9]+)?$'
                                                                    THEN (raw ->> 'cod')::numeric END) STORED,
    ADD COLUMN collected_from_business_at timestamptz GENERATED ALWAYS AS (
        CASE WHEN (raw ->> 'collectedFromBusiness') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
             THEN analytics_ts(raw ->> 'collectedFromBusiness') END) STORED,
    ADD COLUMN last_failure_category text GENERATED ALWAYS AS (
        analytics_failure_category(COALESCE(last_failure_reason, exception_reason))) STORED;

ALTER TABLE products
    ADD COLUMN product_type_norm  text    GENERATED ALWAYS AS (COALESCE(NULLIF(btrim(raw ->> 'product_type'), ''),
                                                                        NULLIF(btrim(raw ->> 'productType'), ''))) STORED,
    ADD COLUMN size_position      int     GENERATED ALWAYS AS (analytics_size_position(raw)) STORED;

ALTER TABLE variants
    ADD COLUMN option1            text    GENERATED ALWAYS AS (raw ->> 'option1') STORED,
    ADD COLUMN option2            text    GENERATED ALWAYS AS (raw ->> 'option2') STORED,
    ADD COLUMN option3            text    GENERATED ALWAYS AS (raw ->> 'option3') STORED;

-- ── indexes for the money paths ────────────────────────────────────────────────
-- Already covered: orders (tenant_id, placed_at) — orders_tenant_placed_at_idx; order_items
-- (order_id); shipments (order_id), (tenant_id, returned_at), (tenant_id, created_at), the V148
-- settlement queue / cashout indexes; shipment_status_history (shipment_id).
-- New: finished legs by their delivered date, and the few finished legs with neither a delivered
-- nor a returned stamp (their terminal date comes from history) — /money/fees* filters on these.
CREATE INDEX shipments_tenant_delivered_at_idx ON shipments (tenant_id, delivered_at) WHERE delivered_at IS NOT NULL;
CREATE INDEX shipments_terminal_unstamped_idx ON shipments (tenant_id)
    WHERE delivered_at IS NULL AND returned_at IS NULL
      AND internal_state IN ('delivered', 'returned', 'lost', 'terminated');
-- Legs still moving / waiting (pipeline in transit, stuck lists).
CREATE INDEX shipments_tenant_open_idx ON shipments (tenant_id, internal_state)
    WHERE internal_state IN ('created', 'with_courier', 'returning', 'exception');
