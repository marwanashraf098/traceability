-- V206 — Shopify-native returns / exchanges, S1: MIRROR ONLY (approved 2026-11-05; Step 0 + Q1–Q8 + C1–C3).
--
-- Traced stores what Shopify knows about its own returns, exchanges and fulfillment orders. Nothing reads these
-- tables for a decision yet: no reservation, no committed demand, no Pick & Pack change, no stock write, nothing on
-- a warehouse screen (S2+ do that). Every row is keyed by its Shopify GID, written only by upsert (fetched in full by
-- GraphQL after a webhook signal or by the per-store backfill — never from a thin webhook payload).
--
--   shopify_returns            one Return (name e.g. #247060-R1, status, decline, when Traced first saw CANCELED)
--   shopify_return_lines       its ReturnLineItems (order line, variant, qty, processed qty, reason, notes)
--   shopify_exchange_lines     its ExchangeLineItems (variant, qty, processed qty, the order line GIDs they became)
--   shopify_fulfillment_orders a FulfillmentOrder of an order Traced holds (status, hold reasons, line remaining qty)
--   shopify_rfo_dispositions   a ReverseFulfillmentOrder line's disposition (RESTOCKED / NOT_RESTOCKED / ..., qty,
--                              location), linked to its return line through the fulfillment line item
--
-- order_items.shopify_exchange_line_gid: DATA ONLY — which order line came from which exchange line (matched by
-- ExchangeLineItem.lineItems[].id). Quantities, the freeze rule, committed demand and the pick gate are unchanged.
--
-- stores.returns_access / returns_backfill_*: whether the store granted the returns / fulfillment-order scopes
-- (an ACCESS_DENIED marks it 'not_granted' and stops quietly), and the resumable per-store backfill.
--
-- PII: no customer / address / email field is ever requested. Customer-written text (return line customer note,
-- reason note) and the merchant's decline note to the customer are redacted by customers/redact and shop/redact
-- (pii_redacted_at; shopify_return_raw_redacted() strips them from raw) and kept redacted on every later write;
-- a return of an already-redacted order is born redacted. Every raw column goes through shopify_raw_without_email.
--
-- RLS on every new table (NULLIF pattern). app_user: no DELETE, no TRUNCATE (upsert-only).

-- ── store state ─────────────────────────────────────────────────────────────────────────────────
ALTER TABLE stores
    ADD COLUMN returns_access               text CHECK (returns_access IN ('granted', 'not_granted')),
    ADD COLUMN returns_access_at            timestamptz,
    ADD COLUMN returns_backfill_status      text CHECK (returns_backfill_status IN ('pending', 'running', 'done', 'failed')),
    ADD COLUMN returns_backfill_requested_at timestamptz,
    ADD COLUMN returns_backfill_heartbeat_at timestamptz,
    ADD COLUMN returns_backfill_cursor      text,
    ADD COLUMN returns_backfill_done_at     timestamptz,
    ADD COLUMN returns_backfill_error       text,
    -- Token re-exchange guard: the app's declared scope set Traced last re-exchanged an embedded token for while the
    -- store still hadn't approved all of it, and when. Until the set changes, app/scopes_update clears it, or 24 h
    -- pass, an embedded open doesn't re-exchange again for the scope difference alone.
    ADD COLUMN scopes_awaiting_approval     text,
    ADD COLUMN scopes_awaiting_since        timestamptz;

-- ── exchange line link (data only) ──────────────────────────────────────────────────────────────
ALTER TABLE order_items ADD COLUMN shopify_exchange_line_gid text;

-- ── PII helpers ─────────────────────────────────────────────────────────────────────────────────
-- The customer-written / customer-facing text inside a stored Return (or line) raw: removed at any depth.
CREATE FUNCTION shopify_return_raw_redacted(p jsonb) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    result jsonb;
    k text;
    v jsonb;
BEGIN
    IF p IS NULL OR jsonb_typeof(p) NOT IN ('object', 'array') THEN
        RETURN p;
    END IF;
    IF jsonb_typeof(p) = 'array' THEN
        SELECT COALESCE(jsonb_agg(shopify_return_raw_redacted(e) ORDER BY i), '[]'::jsonb)
          INTO result FROM jsonb_array_elements(p) WITH ORDINALITY AS a(e, i);
        RETURN result;
    END IF;
    result := '{}'::jsonb;
    FOR k, v IN SELECT key, value FROM jsonb_each(p) LOOP
        IF k IN ('customerNote', 'customer_note', 'returnReasonNote', 'return_reason_note', 'note') THEN
            CONTINUE;
        END IF;
        result := result || jsonb_build_object(k, shopify_return_raw_redacted(v));
    END LOOP;
    RETURN result;
END $$;

-- ── tables ──────────────────────────────────────────────────────────────────────────────────────
CREATE TABLE shopify_returns (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid        NOT NULL REFERENCES tenants(id),
    store_id            uuid        NOT NULL REFERENCES stores(id),
    return_gid          text        NOT NULL,
    order_id            uuid        NOT NULL,
    order_gid           text        NOT NULL,
    name                text,
    status              text        NOT NULL,
    total_quantity      integer,
    decline_reason      text,
    decline_note        text,
    shopify_created_at  timestamptz,
    request_approved_at timestamptz,
    closed_at           timestamptz,
    cancelled_seen_at   timestamptz,
    lines_truncated     boolean     NOT NULL DEFAULT false,
    raw                 jsonb       NOT NULL,
    fetched_at          timestamptz NOT NULL DEFAULT now(),
    pii_redacted_at     timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, return_gid),
    UNIQUE (id, tenant_id),
    -- composite: a row can only point at an order of its own tenant (FK checks ignore RLS)
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id)
);
CREATE INDEX shopify_returns_order_idx ON shopify_returns (tenant_id, order_id);

CREATE TABLE shopify_return_lines (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            uuid        NOT NULL REFERENCES tenants(id),
    return_id            uuid        NOT NULL,
    line_gid             text        NOT NULL,
    order_line_gid       text,
    fulfillment_line_gid text,
    variant_gid          text,
    variant_id           uuid        REFERENCES variants(id),
    quantity             integer     NOT NULL,
    processed_quantity   integer     NOT NULL DEFAULT 0,
    reason               text,
    reason_note          text,
    customer_note        text,
    raw                  jsonb       NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, line_gid),
    FOREIGN KEY (return_id, tenant_id) REFERENCES shopify_returns (id, tenant_id)
);
CREATE INDEX shopify_return_lines_return_idx ON shopify_return_lines (tenant_id, return_id);

CREATE TABLE shopify_exchange_lines (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id),
    return_id          uuid        NOT NULL,
    line_gid           text        NOT NULL,
    variant_gid        text,
    variant_id         uuid        REFERENCES variants(id),
    quantity           integer     NOT NULL,
    processed_quantity integer     NOT NULL DEFAULT 0,
    order_line_gids    text[]      NOT NULL DEFAULT '{}',
    raw                jsonb       NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, line_gid),
    FOREIGN KEY (return_id, tenant_id) REFERENCES shopify_returns (id, tenant_id)
);
CREATE INDEX shopify_exchange_lines_return_idx ON shopify_exchange_lines (tenant_id, return_id);
CREATE INDEX shopify_exchange_lines_order_lines_idx ON shopify_exchange_lines USING gin (order_line_gids);

CREATE TABLE shopify_fulfillment_orders (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id),
    store_id           uuid        NOT NULL REFERENCES stores(id),
    fo_gid             text        NOT NULL,
    order_id           uuid        NOT NULL,
    order_gid          text        NOT NULL,
    status             text        NOT NULL,
    request_status     text,
    hold_reasons       text[]      NOT NULL DEFAULT '{}',
    -- [{ "foLineGid", "lineItemGid", "variantGid", "remainingQuantity", "totalQuantity" }]
    line_items         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    lines_truncated    boolean     NOT NULL DEFAULT false,
    shopify_created_at timestamptz,
    shopify_updated_at timestamptz,
    raw                jsonb       NOT NULL,
    fetched_at         timestamptz NOT NULL DEFAULT now(),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, fo_gid),
    FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id)
);
CREATE INDEX shopify_fulfillment_orders_order_idx ON shopify_fulfillment_orders (tenant_id, order_id);

CREATE TABLE shopify_rfo_dispositions (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenants(id),
    return_id        uuid        NOT NULL,
    disposition_gid  text        NOT NULL,
    rfo_gid          text        NOT NULL,
    rfo_line_gid     text        NOT NULL,
    return_line_gid  text,
    fulfillment_line_gid text,
    type             text        NOT NULL,
    quantity         integer     NOT NULL,
    location_gid     text,
    raw              jsonb       NOT NULL,
    first_seen_at    timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, disposition_gid),
    FOREIGN KEY (return_id, tenant_id) REFERENCES shopify_returns (id, tenant_id)
);
CREATE INDEX shopify_rfo_dispositions_return_idx ON shopify_rfo_dispositions (tenant_id, return_id);

-- ── raw hygiene triggers ────────────────────────────────────────────────────────────────────────
-- Every raw: email stripped. A redacted return (or a line / disposition of one): its notes and raw stay redacted on
-- every later write. A return of an order already redacted (customers/redact or shop/redact) is born redacted.
CREATE FUNCTION shopify_returns_raw_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.raw := shopify_raw_without_email(NEW.raw);
    IF NEW.pii_redacted_at IS NULL AND TG_OP = 'UPDATE' AND OLD.pii_redacted_at IS NOT NULL THEN
        NEW.pii_redacted_at := OLD.pii_redacted_at;
    END IF;
    IF NEW.pii_redacted_at IS NULL
       AND EXISTS (SELECT 1 FROM orders o WHERE o.id = NEW.order_id AND o.pii_redacted_at IS NOT NULL) THEN
        NEW.pii_redacted_at := now();
    END IF;
    IF NEW.pii_redacted_at IS NOT NULL THEN
        NEW.decline_note := NULL;
        NEW.raw := shopify_return_raw_redacted(NEW.raw);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER shopify_returns_raw_guard BEFORE INSERT OR UPDATE ON shopify_returns
    FOR EACH ROW EXECUTE FUNCTION shopify_returns_raw_guard();

CREATE FUNCTION shopify_return_child_raw_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.raw := shopify_raw_without_email(NEW.raw);
    IF EXISTS (SELECT 1 FROM shopify_returns r WHERE r.id = NEW.return_id AND r.pii_redacted_at IS NOT NULL) THEN
        IF TG_TABLE_NAME = 'shopify_return_lines' THEN
            NEW.customer_note := NULL;
            NEW.reason_note := NULL;
        END IF;
        NEW.raw := shopify_return_raw_redacted(NEW.raw);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER shopify_return_lines_raw_guard BEFORE INSERT OR UPDATE ON shopify_return_lines
    FOR EACH ROW EXECUTE FUNCTION shopify_return_child_raw_guard();
CREATE TRIGGER shopify_exchange_lines_raw_guard BEFORE INSERT OR UPDATE ON shopify_exchange_lines
    FOR EACH ROW EXECUTE FUNCTION shopify_return_child_raw_guard();
CREATE TRIGGER shopify_rfo_dispositions_raw_guard BEFORE INSERT OR UPDATE ON shopify_rfo_dispositions
    FOR EACH ROW EXECUTE FUNCTION shopify_return_child_raw_guard();

CREATE FUNCTION shopify_fulfillment_orders_raw_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.raw := shopify_raw_without_email(NEW.raw);
    RETURN NEW;
END $$;
CREATE TRIGGER shopify_fulfillment_orders_raw_guard BEFORE INSERT OR UPDATE ON shopify_fulfillment_orders
    FOR EACH ROW EXECUTE FUNCTION shopify_fulfillment_orders_raw_guard();

-- ── RLS ─────────────────────────────────────────────────────────────────────────────────────────
ALTER TABLE shopify_returns ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_returns FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_returns
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON shopify_returns FROM app_user;

ALTER TABLE shopify_return_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_return_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_return_lines
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON shopify_return_lines FROM app_user;

ALTER TABLE shopify_exchange_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_exchange_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_exchange_lines
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON shopify_exchange_lines FROM app_user;

ALTER TABLE shopify_fulfillment_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_fulfillment_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_fulfillment_orders
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON shopify_fulfillment_orders FROM app_user;

ALTER TABLE shopify_rfo_dispositions ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_rfo_dispositions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_rfo_dispositions
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON shopify_rfo_dispositions FROM app_user;
