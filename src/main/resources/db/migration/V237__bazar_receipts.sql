-- V237 — Bazar POS slice B6c (approved 2026-11-18): CART MODE — several pieces, one customer, one total, one discount.
-- No Shopify write of any kind (the event's transfer already took the stock out when it was sent).
-- BazarSaleService stays the ONE writer of every table here.
--   - bazar_receipts: one row per completed cart. Its receipt_number is a B-number from the SAME per-tenant counter as
--     quick sales (bazar_sale_counters), so B-numbers never repeat across the two. subtotal = the lines' bazar prices,
--     discount = whole pounds (0 ≤ discount ≤ subtotal), total = subtotal − discount = what the customer paid.
--     client_request_id = the phone's idempotency key for Complete (UNIQUE per tenant). Undo = the whole cart:
--     status 'undone' (+ undone_at / undone_by); app_user has no DELETE.
--   - bazar_sales: a cart LINE is an ordinary bazar sale row (same ledger edge, same transfer piece, same returns /
--     exchanges) with receipt_id + line_no set; it has no sale_number and no client_request_id of its own (the
--     receipt's). discount = the line's share of the receipt's discount (proportional to price, whole pounds,
--     remainder on the dearest line — the lines always add up to the receipt's discount); net_price = price − discount
--     (generated) = what the line was paid = what a later return of the piece refunds and an exchange credits. A quick
--     sale has discount 0, so net_price = price.
--   - bazar_payments: a cart's money is ONE payment row for its total on the RECEIPT (receipt_id; sale_id NULL);
--     returns / exchange differences of a line stay on the line's sale_id. Every payment row now carries event_id
--     (backfilled from its sale, then NOT NULL — every writer sets it explicitly; no trigger) so the money rule reads
--     one table whichever the row belongs to.
-- RLS NULLIF + FORCE on bazar_receipts.

-- 1. Receipts.
CREATE TABLE bazar_receipts (
    id                 uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid          NOT NULL REFERENCES tenants(id),
    event_id           uuid          NOT NULL,
    receipt_number     int           NOT NULL CHECK (receipt_number >= 1),
    sold_by            uuid          NOT NULL REFERENCES users(id),
    sold_at            timestamptz   NOT NULL DEFAULT now(),
    business_day       date          NOT NULL,
    line_count         int           NOT NULL CHECK (line_count >= 1),
    subtotal           numeric(10,2) NOT NULL CHECK (subtotal >= 0),
    discount           numeric(10,2) NOT NULL DEFAULT 0
                                     CHECK (discount >= 0 AND discount = trunc(discount)),
    total              numeric(10,2) NOT NULL CHECK (total >= 0),
    status             text          NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'undone')),
    undone_at          timestamptz,
    undone_by          uuid          REFERENCES users(id),
    client_request_id  text          NOT NULL CHECK (length(client_request_id) BETWEEN 1 AND 100),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    CHECK (discount <= subtotal),
    CHECK (total = subtotal - discount),
    CHECK ((status = 'undone') = (undone_at IS NOT NULL)),
    CHECK ((status = 'undone') = (undone_by IS NOT NULL)),
    UNIQUE (tenant_id, client_request_id),
    UNIQUE (tenant_id, receipt_number),
    UNIQUE (id, tenant_id),
    FOREIGN KEY (event_id, tenant_id) REFERENCES bazar_events (id, tenant_id)
);

CREATE INDEX bazar_receipts_event_created_idx ON bazar_receipts (event_id, created_at DESC, id DESC);

ALTER TABLE bazar_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE bazar_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bazar_receipts
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
REVOKE DELETE, TRUNCATE ON bazar_receipts FROM app_user;

-- 2. Cart lines on bazar_sales.
ALTER TABLE bazar_sales
    ADD COLUMN receipt_id  uuid,
    ADD COLUMN line_no     smallint CHECK (line_no >= 1),
    ADD COLUMN discount    numeric(10,2) NOT NULL DEFAULT 0;

ALTER TABLE bazar_sales
    ADD COLUMN net_price numeric(10,2) GENERATED ALWAYS AS (price - discount) STORED;

ALTER TABLE bazar_sales ALTER COLUMN sale_number DROP NOT NULL;
ALTER TABLE bazar_sales ALTER COLUMN client_request_id DROP NOT NULL;

ALTER TABLE bazar_sales
    ADD CONSTRAINT bazar_sales_discount_range CHECK (discount >= 0 AND discount <= price),
    ADD CONSTRAINT bazar_sales_receipt_fk
        FOREIGN KEY (receipt_id, tenant_id) REFERENCES bazar_receipts (id, tenant_id),
    -- A quick sale (or an exchange's new sale) has its own number + request id and no discount; a cart line has
    -- neither (the receipt's), a line number, and is never the new half of an exchange.
    ADD CONSTRAINT bazar_sales_line_shape CHECK (
        (receipt_id IS NULL AND sale_number IS NOT NULL AND client_request_id IS NOT NULL
             AND line_no IS NULL AND discount = 0)
     OR (receipt_id IS NOT NULL AND sale_number IS NULL AND client_request_id IS NULL
             AND line_no IS NOT NULL AND exchange_of_sale_id IS NULL));

CREATE UNIQUE INDEX bazar_sales_receipt_line_uniq ON bazar_sales (receipt_id, line_no) WHERE receipt_id IS NOT NULL;

-- 3. Payments: the receipt's row, and event_id on every row.
ALTER TABLE bazar_payments
    ADD COLUMN receipt_id uuid,
    ADD COLUMN event_id   uuid;

ALTER TABLE bazar_payments ALTER COLUMN sale_id DROP NOT NULL;

UPDATE bazar_payments p SET event_id = s.event_id
  FROM bazar_sales s WHERE s.id = p.sale_id AND s.tenant_id = p.tenant_id;

ALTER TABLE bazar_payments ALTER COLUMN event_id SET NOT NULL;

ALTER TABLE bazar_payments
    ADD CONSTRAINT bazar_payments_one_owner CHECK (num_nonnulls(sale_id, receipt_id) = 1),
    ADD CONSTRAINT bazar_payments_receipt_fk
        FOREIGN KEY (receipt_id, tenant_id) REFERENCES bazar_receipts (id, tenant_id),
    ADD CONSTRAINT bazar_payments_event_fk
        FOREIGN KEY (event_id, tenant_id) REFERENCES bazar_events (id, tenant_id);

CREATE INDEX bazar_payments_receipt_idx ON bazar_payments (receipt_id, created_at, id) WHERE receipt_id IS NOT NULL;
CREATE INDEX bazar_payments_event_day_idx ON bazar_payments (event_id, business_day);
