-- V157 — Analytics slice 10: Shopify cost and FRESH Shopify stock, read-only.
--
-- READ COLUMNS ONLY. Nothing here is ever used to decide or send an inventory write: the
-- increment-only Shopify sync (FR-17 v2, ShopifyInventoryService and the named decrement set)
-- never reads these columns, and the reader that fills them (ShopifyInventoryReader) issues no
-- write mutation (ShopifyInventoryReadGuardTest).
--
-- variants:
--   stock_available_shopify         Shopify "available", summed over ALL the store's locations
--   stock_available_shopify_traced  Shopify "available" at the Traced Main Warehouse location
--   stock_synced_at                 when the two were last read (sync pass or inventory_levels/update)
--   cost_source                     'shopify' (written by the sync) | 'manual' (anything else). A cost
--                                   with no source is treated as manual: never overwritten.
--   cost_synced_at                  when the Shopify cost was last read
--   shopify_cost_flag               why Shopify's cost was NOT stored, e.g. 'non_egp:USD'
--   (unit_cost — V70 — holds the cost itself; EGP only)
ALTER TABLE variants
    ADD COLUMN stock_available_shopify        integer,
    ADD COLUMN stock_available_shopify_traced integer,
    ADD COLUMN stock_synced_at                timestamptz,
    ADD COLUMN cost_source                    text CHECK (cost_source IN ('shopify', 'manual')),
    ADD COLUMN cost_synced_at                 timestamptz,
    ADD COLUMN shopify_cost_flag              text;

-- Per location, so an inventory_levels/update (one location) keeps the total right.
CREATE TABLE variant_shopify_levels (
    tenant_id     uuid        NOT NULL REFERENCES tenants(id),
    variant_id    uuid        NOT NULL REFERENCES variants(id) ON DELETE CASCADE,
    location_id   text        NOT NULL,          -- Shopify's numeric location id
    available     integer     NOT NULL,
    synced_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (variant_id, location_id)
);
CREATE INDEX variant_shopify_levels_tenant_idx ON variant_shopify_levels (tenant_id);
ALTER TABLE variant_shopify_levels ENABLE ROW LEVEL SECURITY;
ALTER TABLE variant_shopify_levels FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON variant_shopify_levels
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

-- One row per tenant: the last sync pass and what Shopify allowed.
CREATE TABLE analytics_inventory_sync (
    tenant_id          uuid        PRIMARY KEY REFERENCES tenants(id),
    requested_at       timestamptz,                -- the owner's last "run now" (rate limit)
    started_at         timestamptz,
    finished_at        timestamptz,
    trigger_kind       text,                       -- 'manual' | 'daily'
    mode               text,                       -- 'bulk' | 'paged'
    variants_seen      integer     NOT NULL DEFAULT 0,
    cost_written       integer     NOT NULL DEFAULT 0,
    cost_kept_manual   integer     NOT NULL DEFAULT 0,
    cost_non_egp       integer     NOT NULL DEFAULT 0,
    stock_written      integer     NOT NULL DEFAULT 0,
    cost_status        text        NOT NULL DEFAULT 'never'
                       CHECK (cost_status IN ('never', 'ok', 'access_denied', 'error')),
    stock_status       text        NOT NULL DEFAULT 'never'
                       CHECK (stock_status IN ('never', 'ok', 'access_denied', 'error')),
    shop_currency      text,
    last_error         text,
    updated_at         timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE analytics_inventory_sync ENABLE ROW LEVEL SECURITY;
ALTER TABLE analytics_inventory_sync FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON analytics_inventory_sync
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON analytics_inventory_sync FROM app_user;
