-- V169 — Non-Bosta couriers, G1 (2026-10-18): generic shipment legs.
--
-- A 'generic' shipment is a forward leg shipped with a carrier Traced has no API for (Mylerz,
-- Wijha, J&T, …), created from the order's Shopify fulfillment (GenericShipments, gated per tenant
-- by tenants.generic_courier_enabled — default false for every tenant, so nothing here changes
-- behaviour until a tenant is switched on by SQL). Bosta rows are untouched:
--   * carrier_name, tracking_url, source, shopify_fulfillment_id, tracking_key are NULL on every
--     existing (Bosta) row and Bosta code never writes them;
--   * Bosta keeps GLOBAL tracking_number uniqueness (same rule, now a partial index on bosta);
--   * a generic number is unique per tenant by its tracking_key (GenericTrackingKey: uppercase
--     letters and digits only, written by the app — never a Bosta row's).

-- 1. shipments: generic-leg columns.
ALTER TABLE shipments
    ADD COLUMN carrier_name           text,
    ADD COLUMN tracking_url           text,
    ADD COLUMN source                 text
        CHECK (source IN ('shopify_fulfillment', 'merchant_entered', 'portal_request')),
    ADD COLUMN shopify_fulfillment_id text,
    ADD COLUMN tracking_key           text;

-- tracking_key belongs to generic rows only, and is always in its canonical form there.
ALTER TABLE shipments
    ADD CONSTRAINT shipments_tracking_key_generic_only
        CHECK (CASE WHEN provider = 'generic'
                    THEN tracking_key IS NOT NULL AND tracking_key ~ '^[A-Z0-9]+$'
                    ELSE tracking_key IS NULL END);

-- 2. Uniqueness: build the new indexes first, then drop V1's global UNIQUE(tracking_number).
CREATE UNIQUE INDEX ux_shipments_bosta_tracking_number
    ON shipments (tracking_number) WHERE provider = 'bosta';
CREATE UNIQUE INDEX ux_shipments_generic_tracking_key
    ON shipments (tenant_id, tracking_key) WHERE provider = 'generic';
-- One generic shipment per Shopify fulfillment (the capture's idempotency key).
CREATE UNIQUE INDEX ux_shipments_generic_fulfillment
    ON shipments (tenant_id, shopify_fulfillment_id)
    WHERE provider = 'generic' AND shopify_fulfillment_id IS NOT NULL;

DO $$
DECLARE c text;
BEGIN
    SELECT con.conname INTO c
    FROM pg_constraint con
    WHERE con.conrelid = 'shipments'::regclass AND con.contype = 'u'
      AND con.conkey = ARRAY[(SELECT attnum FROM pg_attribute
                              WHERE attrelid = 'shipments'::regclass AND attname = 'tracking_number')]::int2[];
    IF c IS NULL THEN
        RAISE EXCEPTION 'V169: the V1 UNIQUE (tracking_number) constraint on shipments was not found';
    END IF;
    EXECUTE format('ALTER TABLE shipments DROP CONSTRAINT %I', c);
END $$;

-- 3. The V132 reserved-range guard (777 + 10 digits = simulated Bosta numbers) applies to Bosta rows
--    only: a real carrier's number may legitimately look like that. Bosta rows: unchanged.
CREATE OR REPLACE FUNCTION forbid_reserved_tracking_for_real_tenant() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.provider = 'bosta'
       AND NEW.tracking_number ~ '^777[0-9]{10}$'
       AND NOT EXISTS (SELECT 1 FROM tenant_courier_simulation WHERE tenant_id = NEW.tenant_id) THEN
        RAISE EXCEPTION 'shipments: reserved simulated tracking number % on a real tenant', NEW.tracking_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

-- 4. tenants: the per-tenant switch. Default OFF for every tenant; no UI in G1 — set by SQL only.
ALTER TABLE tenants ADD COLUMN generic_courier_enabled boolean NOT NULL DEFAULT false;

-- 5. order_fulfillment_tracking: Shopify's fulfillment shipment_status (confirmed, in_transit,
--    delivered, failure, …) captured from the webhook payload. Stored only — G1 acts on nothing.
ALTER TABLE order_fulfillment_tracking ADD COLUMN shipment_status text;

-- 6. Named non-Bosta carriers — ONE SQL definition, mirrored by
--    FulfillmentTrackingCapture.OTHER_KNOWN_CARRIERS (GenericCarrierListParityTest keeps them equal).
--    Compared with lower(trim(tracking_company)). Prod 2026-10-18 has only "Wijha" of these; the
--    rest are named so the class is right the day a merchant uses them.
CREATE FUNCTION other_known_carriers() RETURNS text[]
LANGUAGE sql IMMUTABLE AS $$
    SELECT ARRAY['wijha', 'mylerz', 'j&t', 'j&t express', 'jt express', 'aramex', 'r2s',
                 'smsa', 'smsa express', 'shipblu', 'turuq']::text[]
$$;

-- shipping_carrier_of (V139): the named list now comes from other_known_carriers(); every other
-- branch is V139's text unchanged (no stored orders row changes: prod has no fulfillment with any of the
-- newly named carriers; the column is recomputed on the order's next update as before).
CREATE OR REPLACE FUNCTION shipping_carrier_of(p_order uuid)
RETURNS TABLE (cls text, name text)
LANGUAGE sql STABLE AS $$
    WITH f AS (
        SELECT t.carrier_class AS cls, nullif(trim(t.carrier_raw), '') AS name
        FROM order_fulfillment_tracking t
        WHERE t.order_id = p_order AND lower(coalesce(t.fulfillment_status, '')) <> 'cancelled'
        UNION ALL
        SELECT CASE
                   WHEN lower(coalesce(x->>'tracking_company', '')) LIKE '%bosta%'
                     OR lower(coalesce(x->>'tracking_url', x->'tracking_urls'->>0, '')) LIKE '%bosta%' THEN 'bosta'
                   WHEN lower(trim(coalesce(x->>'tracking_company', ''))) = ANY (other_known_carriers()) THEN 'other_known'
                   ELSE 'unknown'
               END,
               nullif(trim(x->>'tracking_company'), '')
        FROM orders o,
             jsonb_array_elements(CASE WHEN jsonb_typeof(o.raw->'fulfillments') = 'array'
                                       THEN o.raw->'fulfillments' ELSE '[]'::jsonb END) x
        WHERE o.id = p_order AND lower(coalesce(x->>'status', '')) <> 'cancelled'
        UNION ALL
        SELECT 'bosta', 'Bosta'
        FROM shipments s
        WHERE s.order_id = p_order AND s.shipment_leg = 'forward' AND s.provider = 'bosta'
          AND s.internal_state NOT IN ('cancelled', 'terminated')
    )
    SELECT cls, name FROM f
    ORDER BY CASE cls WHEN 'bosta' THEN 0 WHEN 'other_known' THEN 1 ELSE 2 END, name NULLS LAST
    LIMIT 1
$$;
