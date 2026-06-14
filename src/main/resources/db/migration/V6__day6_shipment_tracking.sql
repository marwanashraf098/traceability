-- ============================================================
-- V6 — Day 6: unlinked Mode-B delivery tracking
-- ============================================================

-- Records Bosta deliveries received via webhook before the corresponding
-- shipment row exists in our DB (Mode-B: Bosta plugin may create the delivery
-- before we ingest / match it to a Shopify order). The operator resolves these
-- on the unlinked-deliveries screen by manually matching tracking_number to an
-- order; resolved is set to true after a successful match.
--
-- Idempotency note: each distinct webhook event produces at most one row here
-- because BostaWebhookJob marks the webhook_event processed (with idemKey) after
-- inserting. A re-delivered identical event is caught by the external_event_id
-- dedup check and never reaches this path again.
CREATE TABLE unlinked_bosta_deliveries (
    id                 bigserial    PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants(id),
    tracking_number    text         NOT NULL,
    business_reference text,
    bosta_state_code   integer      NOT NULL,
    bosta_order_type   text         NOT NULL,
    raw                jsonb,
    webhook_event_id   bigint       REFERENCES webhook_events(id),
    first_seen_at      timestamptz  NOT NULL DEFAULT now(),
    last_seen_at       timestamptz  NOT NULL DEFAULT now(),
    resolved           boolean      NOT NULL DEFAULT false
);

-- Efficient lookup for the unlinked-shipments operator screen (FR-4.4).
CREATE INDEX unlinked_bosta_tenant_tracking_idx
    ON unlinked_bosta_deliveries (tenant_id, tracking_number)
    WHERE resolved = false;

ALTER TABLE unlinked_bosta_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE unlinked_bosta_deliveries FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON unlinked_bosta_deliveries
    USING  (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Note: no explicit GRANT needed — V1 ALTER DEFAULT PRIVILEGES already covers
-- all future tables and sequences for app_user.
