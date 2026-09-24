-- ============================================================
-- V102 — Returns portal Step 4b: customer-facing request reference + note
--
-- reference: what the customer is told ("RR-7K3PQ9"), unique per tenant. Alphabet has no
-- 0/O/1/I so it can be read out over the phone without ambiguity. Generated in
-- PortalService (6 characters); the CHECK accepts 4+ so the length can grow later.
-- customer_note: optional free text from the portal, max 300 characters.
--
-- return_requests is new in V100 and nothing wrote to it before this step, but any row that
-- exists gets a deterministic reference from its id first, so SET NOT NULL can't fail:
-- md5 → uppercase hex, with 0/1 mapped to 8/9 (A–F and 2–9 are all in the alphabet).
-- ============================================================

ALTER TABLE return_requests
    ADD COLUMN reference     text,
    ADD COLUMN customer_note text
        CONSTRAINT return_requests_customer_note_length CHECK (char_length(customer_note) <= 300);

UPDATE return_requests
SET    reference = 'RR-' || translate(upper(substr(md5(id::text), 1, 6)), '01', '89')
WHERE  reference IS NULL;

ALTER TABLE return_requests
    ALTER COLUMN reference SET NOT NULL,
    ADD CONSTRAINT return_requests_reference_format CHECK (reference ~ '^RR-[2-9A-HJ-NP-Z]{4,}$'),
    ADD CONSTRAINT return_requests_tenant_reference_unique UNIQUE (tenant_id, reference);
