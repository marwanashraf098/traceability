-- ============================================================
-- V144 — Build B: customer name, phone and address on Shopify orders
-- ============================================================
-- Protected customer data is approved for NAME, PHONE and ADDRESS only. EMAIL IS NOT APPROVED:
-- no email column, ever, and email is stripped from every stored Shopify payload.
--
-- 1. orders.shopify_address (jsonb, Shopify shape: address1, address2, city, province, zip,
--    country). orders.address stays Bosta-owned — Shopify never writes it.
-- 2. The ONE precedence definition, as SQL (IMMUTABLE, SECURITY INVOKER — not escape hatches), used by
--    ingest (ShopifySyncService.UPSERT_ORDER, webhook and import paths) and by the backfill alike.
--    They read both payload shapes: REST webhooks (snake_case) and the GraphQL import (camelCase).
--      name    shipping address name → customer first + last → billing address name
--      phone   shipping address phone → customer phone (GraphQL: defaultPhoneNumber.phoneNumber) → billing
--              address phone
--      address the shipping address block
--    First non-empty value, trimmed.
-- 3. shopify_raw_without_email(jsonb): drops every key named "email" or "*_email" holding a string
--    or null, at any depth (prod has email, contact_email, customer.email; customer.verified_email is
--    a boolean and stays). BEFORE INSERT/UPDATE OF raw on orders and BEFORE INSERT on
--    shopify_webhook_events apply it to every write — after HMAC verification, which runs on the
--    raw request bytes before the row is inserted.
-- 4. shopify_pii_backfill(batch): strips email from existing rows of both tables and fills
--    customer_name / customer_phone / shopify_address (fill-only) from stored REST raw — never a
--    redacted order, never an order placed before its store's floor (orders_ingest_from; Jumi,
--    whose cutoff is NULL by design, uses its connect date 2026-07-02). Keyset batches with a
--    COMMIT each, so it must be CALLed outside a transaction (V145 is non-transactional). Idempotent.
--    Owner-only: EXECUTE is revoked from PUBLIC / app_user.

-- ---- 1. Column ---------------------------------------------------------------

ALTER TABLE orders ADD COLUMN shopify_address jsonb;

-- ---- 2. Precedence -------------------------------------------------------------

CREATE FUNCTION shopify_order_pii_name(r jsonb) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(
        NULLIF(btrim(COALESCE(r #>> '{shipping_address,name}', r #>> '{shippingAddress,name}')), ''),
        NULLIF(btrim(concat_ws(' ',
            NULLIF(btrim(COALESCE(r #>> '{customer,first_name}', r #>> '{customer,firstName}')), ''),
            NULLIF(btrim(COALESCE(r #>> '{customer,last_name}',  r #>> '{customer,lastName}')),  ''))), ''),
        NULLIF(btrim(COALESCE(r #>> '{billing_address,name}', r #>> '{billingAddress,name}')), ''))
$$;

CREATE FUNCTION shopify_order_pii_phone(r jsonb) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(
        NULLIF(btrim(COALESCE(r #>> '{shipping_address,phone}', r #>> '{shippingAddress,phone}')), ''),
        NULLIF(btrim(COALESCE(r #>> '{customer,phone}', r #>> '{customer,defaultPhoneNumber,phoneNumber}')), ''),
        NULLIF(btrim(COALESCE(r #>> '{billing_address,phone}', r #>> '{billingAddress,phone}')), ''))
$$;

CREATE FUNCTION shopify_order_pii_address(r jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT NULLIF(jsonb_strip_nulls(jsonb_build_object(
        'address1', NULLIF(btrim(COALESCE(r #>> '{shipping_address,address1}', r #>> '{shippingAddress,address1}')), ''),
        'address2', NULLIF(btrim(COALESCE(r #>> '{shipping_address,address2}', r #>> '{shippingAddress,address2}')), ''),
        'city',     NULLIF(btrim(COALESCE(r #>> '{shipping_address,city}',     r #>> '{shippingAddress,city}')),     ''),
        'province', NULLIF(btrim(COALESCE(r #>> '{shipping_address,province}', r #>> '{shippingAddress,province}')), ''),
        'zip',      NULLIF(btrim(COALESCE(r #>> '{shipping_address,zip}',      r #>> '{shippingAddress,zip}')),      ''),
        'country',  NULLIF(btrim(COALESCE(r #>> '{shipping_address,country}',  r #>> '{shippingAddress,country}')),  ''))),
        '{}'::jsonb)
$$;

-- ---- 3. Email never stored -------------------------------------------------------

CREATE FUNCTION shopify_raw_without_email(p jsonb) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    result jsonb;
    k text;
    v jsonb;
BEGIN
    IF p IS NULL OR jsonb_typeof(p) NOT IN ('object', 'array') THEN
        RETURN p;
    END IF;
    -- Fast path: no key that could end in "email" anywhere below.
    IF position('mail"' IN p::text) = 0 THEN
        RETURN p;
    END IF;
    IF jsonb_typeof(p) = 'array' THEN
        SELECT COALESCE(jsonb_agg(shopify_raw_without_email(e) ORDER BY i), '[]'::jsonb)
          INTO result FROM jsonb_array_elements(p) WITH ORDINALITY AS a(e, i);
        RETURN result;
    END IF;
    result := '{}'::jsonb;
    FOR k, v IN SELECT key, value FROM jsonb_each(p) LOOP
        IF (lower(k) = 'email' OR lower(k) LIKE '%\_email') AND jsonb_typeof(v) IN ('string', 'null') THEN
            CONTINUE;
        END IF;
        result := result || jsonb_build_object(k, shopify_raw_without_email(v));
    END LOOP;
    RETURN result;
END $$;

CREATE FUNCTION orders_strip_email_from_raw() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.raw := shopify_raw_without_email(NEW.raw);
    RETURN NEW;
END $$;

CREATE TRIGGER orders_strip_email_from_raw
    BEFORE INSERT OR UPDATE OF raw ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_strip_email_from_raw();

CREATE FUNCTION shopify_event_strip_email() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.payload_raw := shopify_raw_without_email(NEW.payload_raw);
    RETURN NEW;
END $$;

CREATE TRIGGER shopify_webhook_events_strip_email
    BEFORE INSERT OR UPDATE OF payload_raw ON shopify_webhook_events
    FOR EACH ROW EXECUTE FUNCTION shopify_event_strip_email();

-- ---- 4. Backfill -------------------------------------------------------------------

-- The floor below which an order's PII is never backfilled ("orders placed after connect only").
CREATE FUNCTION shopify_pii_backfill_floor(s stores) RETURNS timestamptz
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(s.orders_ingest_from,
                    CASE WHEN s.shop_domain = 'mmi24e-fx.myshopify.com'
                         THEN '2026-07-02 00:00 Africa/Cairo'::timestamptz END)
$$;

CREATE PROCEDURE shopify_pii_backfill(p_batch integer DEFAULT 500)
LANGUAGE plpgsql AS $$
DECLARE
    zero   constant uuid := '00000000-0000-0000-0000-000000000000';
    -- A key shopify_raw_without_email() removes, as it appears in jsonb's text form: "email": "…" / null,
    -- "contact_email": … — so rows already clean (customer.verified_email is a boolean) are skipped on a re-run.
    EMAIL_KEY_TEXT constant text := '"([a-z0-9_]*_)?email": ("|null)';
    last   uuid;
    ids    uuid[];
    filled integer := 0;
    n      integer;
BEGIN
    -- (a) Email out of orders.raw — every order, redacted or not.
    last := zero;
    LOOP
        SELECT array_agg(id ORDER BY id) INTO ids
          FROM (SELECT id FROM orders WHERE id > last ORDER BY id LIMIT p_batch) b;
        EXIT WHEN ids IS NULL;
        UPDATE orders SET raw = raw
         WHERE id = ANY(ids) AND raw IS NOT NULL AND raw::text ~* EMAIL_KEY_TEXT;           -- trigger strips
        last := ids[array_length(ids, 1)];
        COMMIT;
    END LOOP;

    -- (b) Email out of stored Shopify webhook payloads.
    last := zero;
    LOOP
        SELECT array_agg(id ORDER BY id) INTO ids
          FROM (SELECT id FROM shopify_webhook_events WHERE id > last ORDER BY id LIMIT p_batch) b;
        EXIT WHEN ids IS NULL;
        UPDATE shopify_webhook_events SET payload_raw = payload_raw
         WHERE id = ANY(ids) AND payload_raw::text ~* EMAIL_KEY_TEXT;                       -- trigger strips
        last := ids[array_length(ids, 1)];
        COMMIT;
    END LOOP;

    -- (c) Fill-only name / phone / shopify_address from stored REST raw.
    last := zero;
    LOOP
        SELECT array_agg(id ORDER BY id) INTO ids
          FROM (SELECT id FROM orders WHERE id > last ORDER BY id LIMIT p_batch) b;
        EXIT WHEN ids IS NULL;
        UPDATE orders o
           SET customer_name   = COALESCE(o.customer_name,   shopify_order_pii_name(o.raw)),
               customer_phone  = COALESCE(o.customer_phone,  shopify_order_pii_phone(o.raw)),
               shopify_address = COALESCE(o.shopify_address, shopify_order_pii_address(o.raw)),
               pii_source      = CASE WHEN o.pii_source IS NULL
                                       AND ((o.customer_name  IS NULL AND shopify_order_pii_name(o.raw)  IS NOT NULL)
                                         OR (o.customer_phone IS NULL AND shopify_order_pii_phone(o.raw) IS NOT NULL))
                                      THEN 'shopify' ELSE o.pii_source END
          FROM stores s
         WHERE o.id = ANY(ids)
           AND s.id = o.store_id
           AND o.pii_redacted_at IS NULL
           AND o.external_id LIKE 'gid://shopify/Order/%'
           AND jsonb_exists(o.raw, 'admin_graphql_api_id')              -- REST (webhook) raw only
           AND o.placed_at >= shopify_pii_backfill_floor(s)             -- NULL floor → never
           AND ((o.customer_name   IS NULL AND shopify_order_pii_name(o.raw)    IS NOT NULL)
             OR (o.customer_phone  IS NULL AND shopify_order_pii_phone(o.raw)   IS NOT NULL)
             OR (o.shopify_address IS NULL AND shopify_order_pii_address(o.raw) IS NOT NULL));
        GET DIAGNOSTICS n = ROW_COUNT;
        filled := filled + n;
        last := ids[array_length(ids, 1)];
        COMMIT;
    END LOOP;
    RAISE NOTICE 'shopify_pii_backfill: % order(s) filled', filled;
END $$;

REVOKE ALL ON PROCEDURE shopify_pii_backfill(integer) FROM PUBLIC;
