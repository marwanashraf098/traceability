-- ============================================================
-- V143 — GDPR build A: redaction that sticks, and real customers/data_request
-- ============================================================
-- Step 0 diagnosis (2026-10-05): Shopify order webhooks already store full customer PII in
-- orders.raw and shopify_webhook_events.payload_raw, and Bosta copies the receiver (name, phone,
-- street address) into shipments.raw, exchanges.raw and unlinked_bosta_deliveries.raw.
-- customers/redact and shop/redact cleared only the orders columns and orders.raw, and every one
-- of those raw columns is rewritten wholesale by later events (raw = EXCLUDED.raw, Bosta status
-- refreshes), so a redaction did not stick.
--
-- 1. Two pure SQL functions (SECURITY INVOKER, IMMUTABLE — not escape hatches) are the ONE
--    definition of "the PII part of a raw payload":
--      shopify_order_raw_redacted(jsonb) — a Shopify order payload (REST or GraphQL shape)
--      bosta_raw_redacted(jsonb)         — a Bosta delivery (receiver, notes, street-level
--                                          address fields; city / zone / district names stay,
--                                          like the return request's area snapshot)
-- 2. shipments, exchanges and unlinked_bosta_deliveries get pii_redacted_at. While it is set,
--    a BEFORE INSERT/UPDATE trigger strips the raw again, whatever wrote it (webhook, status poll,
--    ShipmentRawRefresher, ingest upsert). A new shipment on a redacted order is born redacted.
-- 3. A Shopify order webhook stored for a redacted order is stripped on INSERT (trigger) — the
--    payload never lands with PII.
-- 4. customer_data_requests — one row per customers/data_request webhook; the export is built at
--    download time (never stored), owner-only, while expires_at (created + 30 days) is ahead.
--    No email is ever stored (email is not part of Traced's approved protected customer data).
--
-- All triggers are SECURITY INVOKER (like V130/V132): run by app_user they read under RLS, and the
-- row's own WITH CHECK already forces NEW.tenant_id to the GUC tenant.

-- ---- 1. The PII definitions ------------------------------------------------

CREATE FUNCTION shopify_order_raw_redacted(p jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN p IS NULL OR jsonb_typeof(p) <> 'object' THEN p
                ELSE p - ARRAY['customer', 'shipping_address', 'billing_address', 'email', 'contact_email',
                               'phone', 'client_details', 'note_attributes', 'browser_ip',
                               'shippingAddress', 'billingAddress']::text[]
           END
$$;

CREATE FUNCTION bosta_raw_redacted(p jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN p IS NULL OR jsonb_typeof(p) <> 'object' THEN p
                ELSE (p - ARRAY['receiver', 'notes']::text[])
                    #- '{dropOffAddress,firstLine}'    #- '{dropOffAddress,secondLine}'
                    #- '{dropOffAddress,buildingNumber}' #- '{dropOffAddress,floor}'
                    #- '{dropOffAddress,apartment}'    #- '{dropOffAddress,geoLocation}'
                    #- '{pickupAddress,firstLine}'     #- '{pickupAddress,secondLine}'
                    #- '{pickupAddress,buildingNumber}' #- '{pickupAddress,floor}'
                    #- '{pickupAddress,apartment}'     #- '{pickupAddress,geoLocation}'
                    #- '{returnAddress,firstLine}'     #- '{returnAddress,secondLine}'
                    #- '{returnAddress,buildingNumber}' #- '{returnAddress,floor}'
                    #- '{returnAddress,apartment}'     #- '{returnAddress,geoLocation}'
           END
$$;

-- ---- 2. Bosta copies stay redacted ------------------------------------------

ALTER TABLE shipments                 ADD COLUMN pii_redacted_at timestamptz;
ALTER TABLE exchanges                 ADD COLUMN pii_redacted_at timestamptz;
ALTER TABLE unlinked_bosta_deliveries ADD COLUMN pii_redacted_at timestamptz;

CREATE FUNCTION shipments_keep_raw_redacted() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.pii_redacted_at IS NULL
       AND EXISTS (SELECT 1 FROM orders o WHERE o.id = NEW.order_id AND o.pii_redacted_at IS NOT NULL) THEN
        NEW.pii_redacted_at := now();
    END IF;
    IF NEW.pii_redacted_at IS NOT NULL THEN
        NEW.raw := bosta_raw_redacted(NEW.raw);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER shipments_keep_raw_redacted
    BEFORE INSERT OR UPDATE ON shipments
    FOR EACH ROW EXECUTE FUNCTION shipments_keep_raw_redacted();

-- exchanges and unlinked deliveries: once marked, every later write is stripped too.
CREATE FUNCTION bosta_copy_keep_raw_redacted() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.pii_redacted_at IS NOT NULL THEN
        NEW.raw := bosta_raw_redacted(NEW.raw);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER exchanges_keep_raw_redacted
    BEFORE INSERT OR UPDATE ON exchanges
    FOR EACH ROW EXECUTE FUNCTION bosta_copy_keep_raw_redacted();

CREATE TRIGGER unlinked_bosta_deliveries_keep_raw_redacted
    BEFORE INSERT OR UPDATE ON unlinked_bosta_deliveries
    FOR EACH ROW EXECUTE FUNCTION bosta_copy_keep_raw_redacted();

-- ---- 3. Shopify order webhooks for a redacted order arrive stripped ---------

-- Small partial index: only redacted orders, looked up by the payload's GID.
CREATE INDEX orders_redacted_by_external_id
    ON orders (tenant_id, external_id) WHERE pii_redacted_at IS NOT NULL;

CREATE FUNCTION shopify_event_keep_order_redacted() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.topic LIKE 'orders/%'
       AND EXISTS (SELECT 1 FROM orders o
                   WHERE o.tenant_id = NEW.tenant_id
                     AND o.external_id = NEW.payload_raw ->> 'admin_graphql_api_id'
                     AND o.pii_redacted_at IS NOT NULL) THEN
        NEW.payload_raw := shopify_order_raw_redacted(NEW.payload_raw);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER shopify_webhook_events_keep_order_redacted
    BEFORE INSERT ON shopify_webhook_events
    FOR EACH ROW EXECUTE FUNCTION shopify_event_keep_order_redacted();

-- ---- 4. customers/data_request ----------------------------------------------

-- webhook_event_id has no FK on purpose: shopify_webhook_events rows are pruned / test-cleaned,
-- and the request must outlive its event. UNIQUE keeps a redelivered webhook to one request.
CREATE TABLE customer_data_requests (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid        NOT NULL REFERENCES tenants(id),
    webhook_event_id     uuid        NOT NULL UNIQUE,
    shop_domain          text        NOT NULL,
    shopify_customer_id  text,
    customer_phone       text,
    orders_requested     text[]      NOT NULL DEFAULT '{}',
    status               text        NOT NULL DEFAULT 'ready' CHECK (status IN ('ready', 'downloaded')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    expires_at           timestamptz NOT NULL DEFAULT now() + interval '30 days',
    notified_at          timestamptz,
    downloaded_at        timestamptz,
    downloaded_by        uuid        REFERENCES users(id) ON DELETE SET NULL,
    pii_redacted_at      timestamptz,
    CHECK (expires_at > created_at)
);

CREATE INDEX customer_data_requests_tenant_created
    ON customer_data_requests (tenant_id, created_at DESC);

ALTER TABLE customer_data_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_data_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_data_requests
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- A request is a record of what was asked: app_user never deletes one.
REVOKE DELETE, TRUNCATE ON customer_data_requests FROM app_user;
