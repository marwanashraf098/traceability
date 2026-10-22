-- Non-Bosta couriers G4b (2026-10-21) — returns-portal EXCHANGES for orders shipped with another courier.
--
-- Option B (approved 2026-10-21): no exchanges row for a generic exchange. The replacement is an internal order
-- 'internal:exchange:rr:<request id>' that the request names here. Nothing on exchanges or any Bosta column;
-- a Bosta request keeps replacement_order_id NULL (its replacement is named by exchanges.outbound_order_id).

-- 1. orders gets (id, tenant_id) as a key so the request can point at its replacement with a composite FK — a
--    request can only ever name an order of its own tenant. A constraint, not a column: merchant_orders unchanged.
ALTER TABLE orders ADD CONSTRAINT orders_id_tenant_unique UNIQUE (id, tenant_id);

-- 2. The generic exchange's replacement order (GenericExchanges.attachGenericForRequest; one per request, one
--    request per replacement).
ALTER TABLE return_requests
    ADD COLUMN replacement_order_id uuid,
    ADD CONSTRAINT return_requests_replacement_order_fk
        FOREIGN KEY (replacement_order_id, tenant_id) REFERENCES orders (id, tenant_id);

CREATE UNIQUE INDEX ux_return_requests_replacement_order
    ON return_requests (replacement_order_id) WHERE replacement_order_id IS NOT NULL;
