-- V36: consignee PII columns for Bosta-sourced customer data
--
-- pii_source: records which system populated customer PII.
--   'bosta'  = receiver data from Bosta delivery
--   'shopify' = customer data from Shopify PCD (future)
--   NULL     = not yet populated
--
-- pii_redacted_at: GDPR guard. Set to now() when customers/redact or shop/redact
--   erases customer PII. Non-null means permanently redacted — populate-on-link
--   MUST check this column and skip if set. Never re-populate after redaction.

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS pii_source      TEXT,
    ADD COLUMN IF NOT EXISTS pii_redacted_at TIMESTAMPTZ;
