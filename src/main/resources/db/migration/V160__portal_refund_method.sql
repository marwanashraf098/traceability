-- ============================================================
-- V160 — Returns portal P2: how the customer wants their refund.
--
-- tenants.portal_refund_methods: the methods the merchant offers on the portal (Settings →
--   Returns portal → Refund methods). Empty = the portal never asks.
--
-- return_requests.refund_method: the customer's choice (refund requests, and exchanges with the
--   refund fallback ticked). NULL when the portal didn't ask.
-- return_requests.refund_details_encrypted: ONE JSON blob (holder / bank / IBAN or account /
--   InstaPay / wallet provider + number), AES-256-GCM via EncryptionService with associated data
--   "tenant_id|request_id" — a ciphertext copied onto another row doesn't decrypt. Only the
--   refund-details endpoint and the GDPR export decrypt it. NULL for cash.
-- return_requests.refund_details_hint: "••••" + the last 4 of the number / IBAN / phone — what the
--   drawer shows without decrypting. Stays after the 30-day purge; removed by customers/redact.
-- return_requests.refund_details_purged_at: when the encrypted details were removed (30 days after
--   the request ended, or a privacy request). The method stays.
--
-- No new table: return_requests already has RLS + the tenant_isolation policy (V100).
-- ============================================================

ALTER TABLE tenants
    ADD COLUMN portal_refund_methods text[] NOT NULL DEFAULT '{}'
        CONSTRAINT tenants_portal_refund_methods_known
        CHECK (portal_refund_methods <@ ARRAY['bank_transfer', 'instapay', 'wallet', 'cash']::text[]);

ALTER TABLE return_requests
    ADD COLUMN refund_method text
        CONSTRAINT return_requests_refund_method_known
        CHECK (refund_method IN ('bank_transfer', 'instapay', 'wallet', 'cash')),
    ADD COLUMN refund_details_encrypted text,
    ADD COLUMN refund_details_hint text CHECK (char_length(refund_details_hint) <= 40),
    ADD COLUMN refund_details_purged_at timestamptz,
    ADD CONSTRAINT return_requests_refund_details_need_method
        CHECK (refund_details_encrypted IS NULL OR refund_method IS NOT NULL);

-- The nightly purge's scan: requests that still hold details.
CREATE INDEX return_requests_refund_details_held_idx
    ON return_requests (tenant_id) WHERE refund_details_encrypted IS NOT NULL;
