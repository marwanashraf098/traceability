-- V232 — Bazar POS slice B2 (approved 2026-11-16): quick sell, undo, edit. Backend only; NO Shopify write of any kind
-- (remove mode: the −N already happened when the event's transfer was marked sent).
--   - bazar_sales: one row per piece sold at a bazar. The sale itself is the InventoryLedger edge
--     out_on_transfer → sold (event 'bazar_sold') + that piece's transfer_pieces outcome 'sold' on the event's linked
--     transfer, in the same transaction (BazarSaleService only). Undo is the edge sold → out_on_transfer
--     (event 'bazar_sale_undone'): the row is kept, status 'undone'. price is a snapshot of bazar_event_prices at
--     sale time. sale_number is per tenant (bazar_sale_counters), shown as B-0001. business_day = the Africa/Cairo
--     date of sold_at. customer_name / customer_phone are optional personal data (phone stored as 01XXXXXXXXX).
--     client_request_id is the phone's idempotency key (UNIQUE per tenant): a retry returns the first sale.
--   - bazar_payments: APPEND-ONLY money rows (like V109 return_refunds): app_user has INSERT / SELECT only. kind
--     'payment' | 'refund' | 'void'; a void row points at the row it cancels (voids_payment_id, once per row).
--     Changing a sale's method = a void row + a new payment row; undo voids the sale's live rows.
--   - bazar_sale_counters: the per-tenant sale number counter (one row per tenant).
-- RLS NULLIF + FORCE on all three.

-- 1. Sale-number counter.
CREATE TABLE bazar_sale_counters (
    tenant_id    uuid PRIMARY KEY REFERENCES tenants(id),
    last_number  int  NOT NULL CHECK (last_number >= 1)
);

ALTER TABLE bazar_sale_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_sale_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_sale_counters
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON bazar_sale_counters FROM app_user;

-- 2. Sales.
CREATE TABLE bazar_sales (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid          NOT NULL REFERENCES tenants(id),
    event_id           uuid          NOT NULL,
    transfer_piece_id  uuid          NOT NULL REFERENCES transfer_pieces(id),
    piece_id           text          NOT NULL REFERENCES pieces(id),
    variant_id         uuid          NOT NULL REFERENCES variants(id),
    price              numeric(10,2) NOT NULL CHECK (price >= 0),
    price_source       text          NOT NULL CHECK (price_source IN ('default', 'bulk', 'override')),
    sale_number        int           NOT NULL CHECK (sale_number >= 1),
    sold_by            uuid          NOT NULL REFERENCES users(id),
    sold_at            timestamptz   NOT NULL DEFAULT now(),
    business_day       date          NOT NULL,
    status             text          NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'undone')),
    undone_at          timestamptz,
    undone_by          uuid          REFERENCES users(id),
    customer_name      text          CHECK (customer_name IS NULL OR length(btrim(customer_name)) BETWEEN 1 AND 80),
    customer_phone     text          CHECK (customer_phone IS NULL OR customer_phone ~ '^01[0125][0-9]{8}$'),
    client_request_id  text          NOT NULL CHECK (length(client_request_id) BETWEEN 1 AND 100),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CHECK ((status = 'undone') = (undone_at IS NOT NULL)),
    CHECK ((status = 'undone') = (undone_by IS NOT NULL)),
    UNIQUE (tenant_id, client_request_id),
    UNIQUE (tenant_id, sale_number),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (event_id, tenant_id) REFERENCES bazar_events (id, tenant_id)
);

-- Backstop: a piece has at most one live sale (the ledger edge is the referee).
CREATE UNIQUE INDEX bazar_sales_one_active_per_piece ON bazar_sales (piece_id) WHERE status = 'active';
CREATE INDEX bazar_sales_event_created_idx ON bazar_sales (event_id, created_at DESC, id DESC);
CREATE INDEX bazar_sales_event_day_idx ON bazar_sales (event_id, business_day);

ALTER TABLE bazar_sales ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_sales FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_sales
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON bazar_sales FROM app_user;

-- 3. Payments — append-only.
CREATE TABLE bazar_payments (
    id                uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid          NOT NULL REFERENCES tenants(id),
    sale_id           uuid          NOT NULL,
    method            text          NOT NULL CHECK (method IN ('cash', 'card', 'instapay')),
    amount            numeric(10,2) NOT NULL CHECK (amount >= 0),
    kind              text          NOT NULL CHECK (kind IN ('payment', 'refund', 'void')),
    voids_payment_id  uuid,
    created_by        uuid          NOT NULL REFERENCES users(id),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CHECK ((kind = 'void') = (voids_payment_id IS NOT NULL)),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (sale_id, tenant_id) REFERENCES bazar_sales (id, tenant_id),
    FOREIGN KEY (voids_payment_id, tenant_id) REFERENCES bazar_payments (id, tenant_id)
);

CREATE UNIQUE INDEX bazar_payments_void_once ON bazar_payments (voids_payment_id) WHERE voids_payment_id IS NOT NULL;
CREATE INDEX bazar_payments_sale_idx ON bazar_payments (sale_id, created_at, id);

ALTER TABLE bazar_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_payments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_payments
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE UPDATE, DELETE, TRUNCATE ON bazar_payments FROM app_user;
