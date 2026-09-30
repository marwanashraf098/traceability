-- Failed increment recovery (Part D). Columns on the existing claim row; the RLS policy is unchanged.
--   failure_class          why the last attempt failed: never_sent (the adjust never reached Shopify),
--                          rejected (Shopify answered and did not apply it), ambiguous (sent, outcome unknown)
--   change_from_quantity   the changeFromQuantity actually sent — an ambiguous claim may only be resent
--                          IDENTICALLY (same key, same baseline)
--   sent_idempotency_key / sent_key_first_at   the key the last SENT attempt used and when that key was
--                          first sent — an ambiguous resend reuses exactly that key, and only while
--                          Shopify still remembers it (we allow 20 h of Shopify's 24 h)
--   attempt_count          attempts made (the original counts as 1); the retry job stops at 5
--   first_attempt_at / last_attempt_at / next_attempt_at   backoff bookkeeping (10 min, 1 h, 6 h, 24 h)
--   legacy                 failed before this migration: never retried automatically, reconciled by hand
ALTER TABLE shopify_inventory_adjustments
    ADD COLUMN failure_class        text NULL
        CHECK (failure_class IN ('never_sent', 'rejected', 'ambiguous')),
    ADD COLUMN change_from_quantity int NULL,
    ADD COLUMN sent_idempotency_key text NULL,
    ADD COLUMN sent_key_first_at    timestamptz NULL,
    ADD COLUMN attempt_count        int NOT NULL DEFAULT 0,
    ADD COLUMN first_attempt_at     timestamptz NULL,
    ADD COLUMN last_attempt_at      timestamptz NULL,
    ADD COLUMN next_attempt_at      timestamptz NULL,
    ADD COLUMN legacy               boolean NOT NULL DEFAULT false;

-- The backlog at deploy time: every failed increment claim is legacy — never auto-retried
-- (Shopify may have been corrected by hand since), surfaced once per tenant for reconciliation.
UPDATE shopify_inventory_adjustments
   SET legacy = true
 WHERE status = 'failed'
   AND trigger_type IN ('receiving_session', 'return_inspection', 'hold_exit');

CREATE INDEX idx_sia_increment_retry_due
    ON shopify_inventory_adjustments (tenant_id, next_attempt_at)
 WHERE status = 'failed' AND NOT legacy;
