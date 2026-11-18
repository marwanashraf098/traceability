-- V236 — Exchange reference S3a (approved 2026-11-18): multi-item dashboard exchanges.
--   exchange_items: what an exchange sends OUT (the replacement order's lines) and takes back IN (the old items), one row
--   per mapped line, quantity >= 1. Written ONLY by ExchangeService.mapItems (the N-row mapping screen) — the single
--   item paths (map(out, in), tryAutoMap, attachForRequest) are unchanged and write no rows here; an exchange mapped
--   with exactly one 'out' and one 'in' row also keeps exchanges.outbound_variant_id / inbound_variant_id filled exactly
--   as before, so the existing single-item readers don't change.
--   - direction 'out': variant_id NOT NULL (every outbound row is mapped before the exchange leaves needs_mapping);
--     order_item_id = the internal:exchange: order's line it became (overrideOutboundVariant changes that line + row).
--   - direction 'in': variant_id may stay NULL ("don't know yet", as for single-item exchanges today); order_item_id
--     always NULL.
--   - description_hint: the courier text the person kept for the row (prefilled from Bosta's description).
--   - mapped_by / mapped_at: who mapped it, when.
-- No delete: app_user has SELECT / INSERT / UPDATE only. RLS NULLIF + FORCE.

CREATE TABLE exchange_items (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL REFERENCES tenants(id),
    exchange_id      uuid        NOT NULL REFERENCES exchanges(id),
    direction        text        NOT NULL CHECK (direction IN ('out', 'in')),
    variant_id       uuid        REFERENCES variants(id),
    quantity         integer     NOT NULL CHECK (quantity >= 1 AND quantity <= 99),
    description_hint text        CHECK (description_hint IS NULL OR length(description_hint) <= 500),
    order_item_id    uuid        REFERENCES order_items(id),
    mapped_by        uuid        REFERENCES users(id),
    mapped_at        timestamptz NOT NULL DEFAULT now(),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (direction = 'in' OR variant_id IS NOT NULL),
    CHECK (direction = 'out' OR order_item_id IS NULL)
);

CREATE INDEX exchange_items_exchange_idx ON exchange_items (tenant_id, exchange_id, direction);
CREATE UNIQUE INDEX exchange_items_order_item_uidx ON exchange_items (order_item_id) WHERE order_item_id IS NOT NULL;

ALTER TABLE exchange_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE exchange_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exchange_items
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON exchange_items TO app_user;
REVOKE DELETE, TRUNCATE ON exchange_items FROM app_user;
