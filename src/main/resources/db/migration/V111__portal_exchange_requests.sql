-- ============================================================
-- V111 — Step 5b: portal exchange requests (same product, another size / colour, same price)
--
-- return_requests.type gains 'exchange'. refund_fallback_ok = the customer agreed to a
-- refund instead if the new size / colour sells out before the merchant approves.
-- return_request_items.replacement_variant_id = the variant the customer wants in exchange
-- (NULL for refunds, and cleared when an exchange is switched to a refund).
-- tenants.portal_exchanges_enabled gates the whole feature (no Settings switch yet — 5c).
-- No Bosta and no Shopify behaviour changes here.
-- ============================================================

ALTER TABLE return_requests DROP CONSTRAINT return_requests_type_check;
ALTER TABLE return_requests
    ADD CONSTRAINT return_requests_type_check CHECK (type IN ('refund', 'exchange')),
    ADD COLUMN refund_fallback_ok boolean NOT NULL DEFAULT false;

ALTER TABLE return_request_items
    ADD COLUMN replacement_variant_id uuid REFERENCES variants(id);

ALTER TABLE tenants
    ADD COLUMN portal_exchanges_enabled boolean NOT NULL DEFAULT false;
