-- ============================================================
-- V100 — Returns portal foundation (Step 4a)
--
-- Data model for customer-initiated return requests, the public order lookup,
-- and hatch #14 (resolve_tenant_by_portal_slug). No pieces move here — the
-- portal only reads; InventoryLedger remains the sole piece-status writer.
-- ============================================================

-- 1. Tenant portal settings. No slug backfill — a merchant opts in explicitly.
ALTER TABLE tenants
    ADD COLUMN portal_slug          text,
    ADD COLUMN portal_enabled       boolean NOT NULL DEFAULT false,
    ADD COLUMN portal_auto_approve  boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT tenants_portal_slug_format CHECK (portal_slug ~ '^[a-z0-9-]{3,40}$'),
    ADD CONSTRAINT tenants_portal_slug_unique UNIQUE (portal_slug);

-- 2. Per-variant non-returnable flag.
ALTER TABLE variants
    ADD COLUMN non_returnable boolean NOT NULL DEFAULT false;

-- 3. Forward-leg delivered timestamp (set once at ingest from now on — see
--    BostaWebhookJob.applyMappedState()). Backfill forward legs only, earliest source:
--    the first 'delivered' shipment_status_history row; else the first
--    piece_event to_status='delivered' for a piece of that order at/after the
--    shipment's created_at.
ALTER TABLE shipments
    ADD COLUMN delivered_at timestamptz;

UPDATE shipments s
SET    delivered_at = src.first_delivered
FROM (
    SELECT s2.id,
           COALESCE(
             (SELECT MIN(h.occurred_at) FROM shipment_status_history h
               WHERE h.shipment_id = s2.id AND h.internal_state = 'delivered'),
             (SELECT MIN(pe.occurred_at) FROM piece_events pe
               WHERE pe.tenant_id = s2.tenant_id AND pe.order_id = s2.order_id
                 AND pe.to_status = 'delivered' AND pe.occurred_at >= s2.created_at)
           ) AS first_delivered
    FROM   shipments s2
    WHERE  s2.shipment_leg = 'forward'
) src
WHERE  s.id = src.id
  AND  s.delivered_at IS NULL
  AND  src.first_delivered IS NOT NULL;

-- 4. Portal lookup by customer-facing order number.
CREATE INDEX orders_tenant_number_idx ON orders (tenant_id, number);

-- 5. Return requests.
CREATE TYPE return_request_status AS ENUM (
    'requested', 'approved', 'rejected', 'pickup_booked',
    'received', 'refund_pending', 'refunded', 'cancelled');

CREATE TABLE return_requests (
    id                  uuid                  PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           uuid                  NOT NULL REFERENCES tenants(id),
    order_id            uuid                  NOT NULL REFERENCES orders(id),
    type                text                  NOT NULL DEFAULT 'refund' CHECK (type IN ('refund')),
    status              return_request_status NOT NULL DEFAULT 'requested',
    customer_email      text,
    created_at          timestamptz           NOT NULL DEFAULT now(),
    decided_at          timestamptz,
    decided_by          uuid                  REFERENCES users(id),
    rejection_reason    text,
    return_shipment_id  uuid                  REFERENCES shipments(id)
);
CREATE INDEX return_requests_tenant_order_idx ON return_requests (tenant_id, order_id);

ALTER TABLE return_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_requests
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- 6. Return request items — one row per requested piece.
CREATE TABLE return_request_items (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    request_id  uuid        NOT NULL REFERENCES return_requests(id),
    piece_id    text        NOT NULL REFERENCES pieces(id),
    variant_id  uuid        NOT NULL REFERENCES variants(id),
    reason_code text        NOT NULL CHECK (reason_code IN
                    ('wrong_size', 'damaged', 'not_as_pictured', 'wrong_item', 'changed_mind', 'other')),
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now()
);
-- A piece can be in at most one live request.
CREATE UNIQUE INDEX return_request_items_one_active_per_piece
    ON return_request_items (piece_id) WHERE active;
CREATE INDEX return_request_items_request_idx ON return_request_items (request_id);

ALTER TABLE return_request_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_request_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_request_items
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- 7. Portal lookup attempts — throttle ledger. No IP, no phone.
CREATE TABLE portal_lookup_attempts (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL REFERENCES tenants(id),
    order_key     text        NOT NULL,
    attempted_at  timestamptz NOT NULL DEFAULT now(),
    success       boolean     NOT NULL
);
CREATE INDEX portal_lookup_attempts_throttle_idx
    ON portal_lookup_attempts (tenant_id, order_key, attempted_at);

ALTER TABLE portal_lookup_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE portal_lookup_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portal_lookup_attempts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- 9. Hatch #14 (approved 2026-09-23): the public portal is pre-session — the caller has
--    only a slug, no GUC. Returns tenant_id only (nullable, read-only); every subsequent
--    portal query runs under TenantContext.runAs(tenantId) + app_user RLS.
CREATE OR REPLACE FUNCTION resolve_tenant_by_portal_slug(p_slug text)
RETURNS uuid
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT id FROM tenants WHERE portal_slug = lower(p_slug) AND portal_enabled;
$$;

REVOKE ALL ON FUNCTION resolve_tenant_by_portal_slug(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolve_tenant_by_portal_slug(text) TO app_user;
