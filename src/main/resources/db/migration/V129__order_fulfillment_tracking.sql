-- V129 — Shopify fulfillment tracking capture (2026-10-02).
--
-- One row per (order, tracking number) seen in a Shopify orders/updated REST payload's
-- fulfillments[] (tracking_numbers[] and tracking_number). Store only: nothing reads this
-- table yet — no linking, no reconcile, no counts, no pickability effect. The GraphQL
-- import / reconcile path never writes it. Rows are never deleted; a fulfillment Shopify
-- cancels keeps its row with fulfillment_status = 'cancelled'. (ON DELETE CASCADE only follows
-- an order row being deleted — nothing in the application deletes orders.)
--
--   tracking_number  Bosta (carrier_class 'bosta'): bare, via TrackingNumberNormalizer (a value
--                    it can't reduce to digits is kept with whitespace removed); every other
--                    carrier: as sent, whitespace removed only ("WJ-12345" stays "WJ-12345")
--   carrier_raw      tracking_company exactly as Shopify sent it (may be NULL)
--   carrier_class    'bosta'        tracking_company contains "bosta" (any case) or the
--                                   tracking URL contains "bosta"
--                    'other_known'  an explicit list of non-Bosta carriers (Wijha)
--                    'unknown'      everything else — including "Other" and NULL (Jumi's Bosta
--                                   fulfillments say "Other")

CREATE TABLE order_fulfillment_tracking (
    id                     bigserial    PRIMARY KEY,
    tenant_id              uuid         NOT NULL REFERENCES tenants(id),
    order_id               uuid         NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    tracking_number        text         NOT NULL,
    carrier_raw            text,
    tracking_url           text,
    carrier_class          text         NOT NULL
        CHECK (carrier_class IN ('bosta', 'other_known', 'unknown')),
    shopify_fulfillment_id text,
    fulfillment_status     text,
    first_seen_at          timestamptz  NOT NULL DEFAULT now(),
    last_seen_at           timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, order_id, tracking_number)
);

CREATE INDEX order_fulfillment_tracking_tracking
    ON order_fulfillment_tracking (tenant_id, tracking_number);

ALTER TABLE order_fulfillment_tracking ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_fulfillment_tracking FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON order_fulfillment_tracking
    USING  (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- Never deleted (see above).
REVOKE DELETE, TRUNCATE ON order_fulfillment_tracking FROM app_user;
