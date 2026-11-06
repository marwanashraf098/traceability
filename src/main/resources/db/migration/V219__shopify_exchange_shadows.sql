-- ============================================================
-- V219 — Shopify-native returns S3b: exchange shadow orders + reservation from approval.
--
-- For an exchange made IN Shopify (a Shopify Return with exchange lines, store returns access granted, the original
-- order already out of Traced custody), Traced keeps the replacement aside on a SHADOW order (V216: external_id
-- 'internal:shopify-exchange:<return gid>', origin 'shopify_exchange', number '#<original>-X1', status 'confirmed' —
-- outside every pick status until S3c). The shadow's order_items carry the LIVE quantity per exchange line (what Traced
-- still has to send) and are committed demand (VariantStockService.SHADOW_COMMITTED_SQL), by count — never a pinned
-- piece, never On Hold, never a Shopify write.
--
-- shopify_exchange_shadows       one per Shopify return (UNIQUE return): the shadow order, its original, its X index,
--                                the approval time (oldest-first for Short by N), state live | ended + why.
-- shopify_exchange_shadow_lines  one per exchange line: the shadow order line, the exchange quantity, the live
--                                quantity, the units Shopify fulfilled itself, and why a line stopped reserving.
-- ShopifyShadowOrders is the ONLY writer of both tables and of shadow orders / their lines (reconcileForOrder, under a
-- per-order advisory lock, reading the mirror's CURRENT state — mirror rows are never deleted). Upsert only:
-- app_user has no DELETE. RLS NULLIF; order FKs composite (order_id, tenant_id).
-- ============================================================

CREATE TABLE shopify_exchange_shadows (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id),
    store_id           uuid        NOT NULL REFERENCES stores(id),
    return_id          uuid        NOT NULL REFERENCES shopify_returns(id),
    order_id           uuid        NOT NULL,                -- the shadow order
    original_order_id  uuid        NOT NULL,
    x_index            integer     NOT NULL CHECK (x_index >= 1),
    approved_at        timestamptz NOT NULL,
    state              text        NOT NULL DEFAULT 'live' CHECK (state IN ('live', 'ended')),
    end_reason         text        CHECK (end_reason IN ('declined', 'cancelled', 'all_lines_ended')),
    ended_at           timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT shopify_exchange_shadows_return_unique UNIQUE (tenant_id, return_id),
    CONSTRAINT shopify_exchange_shadows_order_unique UNIQUE (order_id),
    CONSTRAINT shopify_exchange_shadows_x_unique UNIQUE (original_order_id, x_index),
    CONSTRAINT shopify_exchange_shadows_ended CHECK ((state = 'ended') = (end_reason IS NOT NULL AND ended_at IS NOT NULL)),
    CONSTRAINT shopify_exchange_shadows_order_fk FOREIGN KEY (order_id, tenant_id) REFERENCES orders (id, tenant_id),
    CONSTRAINT shopify_exchange_shadows_original_fk FOREIGN KEY (original_order_id, tenant_id) REFERENCES orders (id, tenant_id)
);
CREATE INDEX shopify_exchange_shadows_original_idx ON shopify_exchange_shadows (tenant_id, original_order_id);

CREATE TABLE shopify_exchange_shadow_lines (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL REFERENCES tenants(id),
    shadow_id          uuid        NOT NULL REFERENCES shopify_exchange_shadows(id),
    exchange_line_id   uuid        NOT NULL REFERENCES shopify_exchange_lines(id),
    order_item_id      uuid        NOT NULL REFERENCES order_items(id),
    variant_id         uuid        NOT NULL REFERENCES variants(id),
    quantity           integer     NOT NULL CHECK (quantity >= 0),        -- the exchange line's quantity
    live_quantity      integer     NOT NULL CHECK (live_quantity >= 0),   -- still to send from Traced (= order_items.quantity)
    shipped_in_shopify integer     NOT NULL DEFAULT 0 CHECK (shipped_in_shopify >= 0),
    stop_reason        text        CHECK (stop_reason IN ('shipped_in_shopify', 'fo_cancelled', 'stood_down',
                                                          'declined', 'cancelled', 'closed_in_shopify')),
    stopped_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT shopify_exchange_shadow_lines_unique UNIQUE (shadow_id, exchange_line_id),
    CONSTRAINT shopify_exchange_shadow_lines_item_unique UNIQUE (order_item_id)
);

ALTER TABLE shopify_exchange_shadows ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_exchange_shadows FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_exchange_shadows
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON shopify_exchange_shadows FROM app_user;

ALTER TABLE shopify_exchange_shadow_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_exchange_shadow_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shopify_exchange_shadow_lines
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON shopify_exchange_shadow_lines FROM app_user;
