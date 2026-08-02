-- FR-21 Step 5: stock_take_shopify_syncs.status was defined in V62 with a 'shadow_logged'
-- value that never had a real use — no shadow gate exists in this codebase (FR-17 v2 is
-- live-only). The actual states needed are pending | pushed | failed | failed_ambiguous:
-- failed_ambiguous is the non-idempotent-decrement retry rule's third outcome (a genuinely
-- unconfirmed Shopify response — timeout, connection reset — that must NOT be auto-retried,
-- distinct from a definitive 'failed' rejection which is safe to retry).
ALTER TABLE stock_take_shopify_syncs
    DROP CONSTRAINT stock_take_shopify_syncs_status_check;

ALTER TABLE stock_take_shopify_syncs
    ADD CONSTRAINT stock_take_shopify_syncs_status_check
    CHECK (status IN ('pending', 'pushed', 'failed', 'failed_ambiguous'));

-- error text, matching the sibling shopify_inventory_adjustments.error column — without it
-- "surface for manual verification" (failed_ambiguous) has no message to show a manager.
ALTER TABLE stock_take_shopify_syncs
    ADD COLUMN error text;
