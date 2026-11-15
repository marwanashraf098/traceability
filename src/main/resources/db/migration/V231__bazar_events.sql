-- V231 — Bazar POS slice B1 (approved 2026-11-15): a bazar event, its ONE linked send-out-and-back transfer, and
-- its per-event prices. Backend only; nothing here is written to Shopify.
--   - tenants.bazar_enabled: rollout flag (default off, set by ops — no merchant switch in B1). Off → every bazar
--     endpoint answers 404.
--   - transfers.transfer_type gains 'bazar' (V64's CHECK widened). A bazar transfer is always round_trip (CHECK
--     below) and is LOCKED from the generic Transfers actions (TransferService refuses BAZAR_TRANSFER_LOCKED) —
--     only the bazar service drives it.
--   - bazar_events: one per event. location_id is a location of type 'event' (V1 enum, never a Shopify location);
--     transfer_id is the linked round_trip 'bazar' transfer Main → that location (UNIQUE: one transfer per event,
--     one event per transfer). status draft → live → ended (ended is written from B5). At most ONE live event per
--     location (partial UNIQUE). payment_methods / seller_roles are what the event allows (subsets, never empty).
--     Bulk price rule: discount_pct (0 < pct < 100) and round_to (> 0), both optional.
--   - bazar_event_prices: the event's price per variant. source: 'default' (the variant's Shopify price,
--     variants.price), 'bulk' (the bulk rule applied to it) or 'override' (set by hand; the rule never touches it).
--     Never sent to Shopify.
-- RLS NULLIF on both tables; app_user has no DELETE / TRUNCATE (an event is ended, never deleted; a price row is
-- updated in place, an override is cleared by recomputing the row).

-- 1. Rollout flag.
ALTER TABLE tenants ADD COLUMN bazar_enabled boolean NOT NULL DEFAULT false;

-- 2. Transfer type 'bazar' (V64's inline CHECK is transfers_transfer_type_check).
ALTER TABLE transfers DROP CONSTRAINT transfers_transfer_type_check;
ALTER TABLE transfers ADD CONSTRAINT transfers_transfer_type_check
    CHECK (transfer_type IN ('showroom', 'dryclean', 'repair', 'other', 'bazar'));
ALTER TABLE transfers ADD CONSTRAINT transfers_bazar_round_trip
    CHECK (transfer_type <> 'bazar' OR transfer_mode = 'round_trip');

-- 3. Events.
CREATE TABLE bazar_events (
    id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid          NOT NULL REFERENCES tenants(id),
    name             text          NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    starts_on        date          NOT NULL,
    ends_on          date          NOT NULL,
    location_id      uuid          NOT NULL,
    transfer_id      uuid          NOT NULL UNIQUE REFERENCES transfers(id),
    status           text          NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'live', 'ended')),
    payment_methods  text[]        NOT NULL DEFAULT ARRAY['cash']::text[]
        CHECK (cardinality(payment_methods) >= 1
               AND payment_methods <@ ARRAY['cash', 'card', 'instapay']::text[]),
    seller_roles     text[]        NOT NULL DEFAULT ARRAY['owner', 'manager']::text[]
        CHECK (cardinality(seller_roles) >= 1
               AND seller_roles <@ ARRAY['owner', 'manager', 'worker']::text[]),
    discount_pct     numeric(5,2)  CHECK (discount_pct IS NULL OR (discount_pct > 0 AND discount_pct < 100)),
    round_to         numeric(10,2) CHECK (round_to IS NULL OR round_to > 0),
    created_by       uuid          NOT NULL REFERENCES users(id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    started_at       timestamptz,
    started_by       uuid          REFERENCES users(id),
    ended_at         timestamptz,
    ended_by         uuid          REFERENCES users(id),
    CHECK (ends_on >= starts_on),
    CHECK ((status = 'draft') = (started_at IS NULL)),
    CHECK ((status = 'ended') = (ended_at IS NOT NULL)),
    UNIQUE (id, tenant_id),
    -- FK checks don't go through RLS: the composite key keeps an event on its own tenant's location.
    FOREIGN KEY (location_id, tenant_id) REFERENCES locations (id, tenant_id)
);

CREATE UNIQUE INDEX bazar_events_one_live_per_location ON bazar_events (location_id) WHERE status = 'live';
CREATE INDEX bazar_events_tenant_created_idx ON bazar_events (tenant_id, created_at DESC, id DESC);

ALTER TABLE bazar_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_events
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON bazar_events FROM app_user;

-- 4. Per-event prices.
CREATE TABLE bazar_event_prices (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid          NOT NULL REFERENCES tenants(id),
    event_id    uuid          NOT NULL,
    variant_id  uuid          NOT NULL REFERENCES variants(id),
    price       numeric(10,2) NOT NULL CHECK (price >= 0),
    source      text          NOT NULL CHECK (source IN ('default', 'bulk', 'override')),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  uuid          REFERENCES users(id),
    UNIQUE (event_id, variant_id),
    FOREIGN KEY (event_id, tenant_id) REFERENCES bazar_events (id, tenant_id)
);

ALTER TABLE bazar_event_prices ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_event_prices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_event_prices
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON bazar_event_prices FROM app_user;
