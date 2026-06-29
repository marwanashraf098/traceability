-- V15: shopify_webhook_events — raw persist table for Shopify webhooks
-- Separate from the Bosta webhook_events table to keep Shopify's per-topic
-- schema clean and to simplify idempotency (UNIQUE on webhook_id, not composite).
-- RLS is enforced in the same migration — every table with tenant_id gets a policy.

CREATE TABLE shopify_webhook_events (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES tenants(id),
    topic        text        NOT NULL,
    shop_domain  text        NOT NULL,
    webhook_id   text        NOT NULL,
    payload_raw  jsonb       NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    process_error text,
    UNIQUE (webhook_id)
);

-- processed_at IS NULL = pending; the processor sweep queries this index.
CREATE INDEX shopify_webhook_events_processed_at_idx
    ON shopify_webhook_events (processed_at)
    WHERE processed_at IS NULL;

ALTER TABLE shopify_webhook_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_webhook_events FORCE ROW LEVEL SECURITY;

-- RLS pattern: NULLIF guards against '' (empty string after ROLLBACK in PostgreSQL).
CREATE POLICY tenant_isolation ON shopify_webhook_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
