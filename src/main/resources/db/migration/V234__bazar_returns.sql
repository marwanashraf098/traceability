-- V234 — Bazar POS slice B3 (approved 2026-11-17): return + exchange at a live bazar. NO Shopify write of any kind (the
-- piece is still away on the event's transfer). BazarSaleService stays the ONE writer of these tables.
--   - bazar_sales.status gains 'returned': the sale was taken back (a piece sold at THIS event, while it is live). The
--     ledger edge is the approved sold → out_on_transfer (event 'bazar_returned'); a damaged return then goes
--     out_on_transfer → damaged (the existing edge, event 'bazar_return_damaged') in the same transaction. The row
--     stays, with returned_at / returned_by / return_condition ('good' | 'damaged') and return_client_request_id
--     (the phone's idempotency key for the return, UNIQUE per tenant).
--   - bazar_sales.exchange_of_sale_id: an exchange is the old sale 'returned' + a NEW sale of the new piece that
--     points at it (UNIQUE: a sale is exchanged at most once). The new sale's client_request_id is the exchange's.
--   - Money: no new payment kind. A return writes one 'refund' row on the returned sale (amount = its price). An
--     exchange credits the old price: only the difference is a money row, on the NEW sale — 'payment' when the new
--     piece costs more, 'refund' when it costs less, nothing when even.

ALTER TABLE bazar_sales DROP CONSTRAINT bazar_sales_status_check;
ALTER TABLE bazar_sales ADD CONSTRAINT bazar_sales_status_check
    CHECK (status IN ('active', 'undone', 'returned'));

ALTER TABLE bazar_sales
    ADD COLUMN returned_at               timestamptz,
    ADD COLUMN returned_by               uuid REFERENCES users(id),
    ADD COLUMN return_condition          text CHECK (return_condition IN ('good', 'damaged')),
    ADD COLUMN return_client_request_id  text CHECK (length(return_client_request_id) BETWEEN 1 AND 100),
    ADD COLUMN exchange_of_sale_id       uuid;

ALTER TABLE bazar_sales
    ADD CONSTRAINT bazar_sales_returned_at   CHECK ((status = 'returned') = (returned_at IS NOT NULL)),
    ADD CONSTRAINT bazar_sales_returned_by   CHECK ((status = 'returned') = (returned_by IS NOT NULL)),
    ADD CONSTRAINT bazar_sales_return_cond   CHECK ((status = 'returned') = (return_condition IS NOT NULL)),
    ADD CONSTRAINT bazar_sales_return_crid   CHECK (return_client_request_id IS NULL OR status = 'returned'),
    ADD CONSTRAINT bazar_sales_not_own_exchange CHECK (exchange_of_sale_id IS NULL OR exchange_of_sale_id <> id),
    ADD CONSTRAINT bazar_sales_exchange_of_fk
        FOREIGN KEY (exchange_of_sale_id, tenant_id) REFERENCES bazar_sales (id, tenant_id);

CREATE UNIQUE INDEX bazar_sales_return_crid_uniq ON bazar_sales (tenant_id, return_client_request_id)
    WHERE return_client_request_id IS NOT NULL;
CREATE UNIQUE INDEX bazar_sales_exchanged_once ON bazar_sales (exchange_of_sale_id)
    WHERE exchange_of_sale_id IS NOT NULL;
-- Today's returns (B3 /today, B4 close day).
CREATE INDEX bazar_sales_event_returned_idx ON bazar_sales (event_id, returned_at) WHERE returned_at IS NOT NULL;
