-- V239 — Bazar POS slice B7a (approved 2026-11-20): sell without barcodes — the short product numbers (#12) of an
-- event. No Shopify write.
--   - One row per (event, product, colour): colour_key = the variant's non-size option values (ExchangeOptions
--     axes) joined " / ", '' when the product has no colour option (one number per product). colour = the same
--     text for display (NULL when none).
--   - number 1..999, unique per event: assigned once (BazarCatalogService.ensureNumbers — first by product title,
--     then colour), never changed or reused; a product that appears mid-event gets the next free number.
--   - Append-only for app_user (INSERT / SELECT): numbers are stable for the whole event.
-- RLS NULLIF + FORCE.

CREATE TABLE bazar_event_product_numbers (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid        NOT NULL REFERENCES tenants(id),
    event_id    uuid        NOT NULL,
    product_id  uuid        NOT NULL REFERENCES products(id),
    colour_key  text        NOT NULL DEFAULT '',
    colour      text,
    number      int         NOT NULL CHECK (number BETWEEN 1 AND 999),
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK ((colour_key = '') = (colour IS NULL)),
    UNIQUE (event_id, number),
    UNIQUE (event_id, product_id, colour_key),
    FOREIGN KEY (event_id, tenant_id) REFERENCES bazar_events (id, tenant_id)
);

ALTER TABLE bazar_event_product_numbers ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_event_product_numbers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_event_product_numbers
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON bazar_event_product_numbers FROM app_user;
