-- ============================================================
-- V109 — Returns Step 4d-2: refunds recorded against a return request, alert windows.
--
-- return_refunds — append-only money ledger for a return request. A mistake is corrected
-- by a 'void' row pointing at the refund it cancels (voids_refund_id), never by UPDATE or
-- DELETE: app_user has INSERT + SELECT only. A refund can be voided once (partial UNIQUE).
-- Traced records refunds the merchant made elsewhere (cash, InstaPay, wallet, bank
-- transfer); it never moves money and never writes a refund to Shopify.
--
-- ON DELETE CASCADE from return_requests, like return_request_events: the app never deletes
-- requests; fixture/test cleanup does, and the ledger goes with them.
-- ============================================================

CREATE TABLE return_refunds (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid          NOT NULL REFERENCES tenants(id),
    request_id      uuid          NOT NULL REFERENCES return_requests(id) ON DELETE CASCADE,
    kind            text          NOT NULL CHECK (kind IN ('refund', 'void')),
    voids_refund_id uuid          REFERENCES return_refunds(id),
    method          text          CHECK (method IN ('cash', 'instapay', 'wallet', 'bank_transfer', 'other')),
    amount          numeric(12,2) CHECK (amount > 0),
    currency        text          NOT NULL DEFAULT 'EGP',
    refunded_on     date,
    reference       text          CHECK (char_length(reference) <= 100),
    note            text          CHECK (char_length(note) <= 300),
    recorded_by     uuid          REFERENCES users(id),
    created_at      timestamptz   NOT NULL DEFAULT clock_timestamp(),
    -- A refund row carries the money; a void row only points at the refund it cancels.
    CONSTRAINT return_refunds_shape CHECK (
        (kind = 'refund' AND voids_refund_id IS NULL AND method IS NOT NULL
             AND amount IS NOT NULL AND refunded_on IS NOT NULL)
     OR (kind = 'void' AND voids_refund_id IS NOT NULL))
);
CREATE UNIQUE INDEX return_refunds_void_once ON return_refunds (voids_refund_id) WHERE kind = 'void';
CREATE INDEX return_refunds_request_idx ON return_refunds (request_id, created_at);

ALTER TABLE return_refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE return_refunds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON return_refunds
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

REVOKE UPDATE, DELETE, TRUNCATE ON return_refunds FROM app_user;

-- 4d-1 did not add these.
ALTER TABLE return_requests
    ADD COLUMN refunded_at timestamptz,
    ADD COLUMN refunded_by uuid REFERENCES users(id);

-- Alert windows (no settings UI yet).
ALTER TABLE tenants
    ADD COLUMN refund_pending_window_days integer NOT NULL DEFAULT 5,
    ADD COLUMN return_arrival_window_days integer NOT NULL DEFAULT 10;
