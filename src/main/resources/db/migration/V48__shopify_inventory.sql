-- FR-17 Phase 1: Shopify inventory shadow sync
-- Shadow mode: rows are inserted but no Shopify mutation is called until
-- stores.shopify_inventory_sync_enabled = true and write_inventory scope is granted.

-- ── Stores flag ──────────────────────────────────────────────────────────────
ALTER TABLE stores
    ADD COLUMN shopify_inventory_sync_enabled boolean NOT NULL DEFAULT false;

-- ── Locations sync columns ───────────────────────────────────────────────────
ALTER TABLE locations
    ADD COLUMN shopify_location_id  text,
    ADD COLUMN shopify_sync_status  text NOT NULL DEFAULT 'unsynced'
        CHECK (shopify_sync_status IN ('unsynced', 'pending', 'linked', 'error')),
    ADD COLUMN shopify_sync_error   text,
    ADD COLUMN shopify_synced_at    timestamptz;

-- Prevent two locations in the same tenant from mapping to the same Shopify location.
CREATE UNIQUE INDEX locations_shopify_id_uniq
    ON locations (tenant_id, shopify_location_id)
    WHERE shopify_location_id IS NOT NULL;

-- ── Shadow inventory adjustments ─────────────────────────────────────────────
CREATE TABLE shopify_inventory_adjustments (
    id                        bigserial    PRIMARY KEY,
    tenant_id                 uuid         NOT NULL REFERENCES tenants(id),
    batch_id                  uuid         NOT NULL,
    variant_id                uuid         NOT NULL REFERENCES variants(id),
    location_id               uuid         NOT NULL REFERENCES locations(id),
    shopify_inventory_item_id text,
    shopify_location_id       text,
    delta                     int          NOT NULL,
    quantity_before           int,
    trigger_type              text         NOT NULL
        CHECK (trigger_type IN ('receiving_session', 'return_inspection')),
    trigger_id                text         NOT NULL,
    payload                   jsonb,
    status                    text         NOT NULL DEFAULT 'shadow'
        CHECK (status IN ('shadow', 'pending', 'applied', 'failed')),
    shopify_response          jsonb,
    error                     text,
    created_at                timestamptz  NOT NULL DEFAULT now(),
    applied_at                timestamptz,

    -- Idempotency: one row per (trigger, variant, location).
    UNIQUE (trigger_type, trigger_id, variant_id, location_id)
);

ALTER TABLE shopify_inventory_adjustments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_inventory_adjustments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON shopify_inventory_adjustments TO app_user;
GRANT USAGE, SELECT ON SEQUENCE shopify_inventory_adjustments_id_seq TO app_user;
