-- ============================================================
-- V95 — FR-EXCHANGE Step 3 Part A: exchange inbound (old-item) matching
--
-- Adds the matched-original-order link exchanges never had (Step 2 Part C
-- precondition finding: outbound_order_id is the NEW replacement item's synthetic
-- Model-A order, not the customer's original item coming back — there was no column
-- for that at all). matched_order_id/match_method/matched_at are populated by the
-- Part B fuzzy matcher (auto phone match) or Part D's manual search-attach action.
--
-- Status home decision (Part 0.3): "unmatched" lives on exchanges.status, not
-- unlinked_bosta_deliveries. unlinked_bosta_deliveries answers "which order does this
-- Bosta DELIVERY belong to" — already resolved for every exchange by the time this
-- matters (outbound_order_id + forward shipment exist from Phase 1/2). matched_order_id
-- answers a different question entirely — "which of the customer's PRIOR orders is the
-- physical old item being returned from" — that has no existing home. Reusing
-- unlinked_bosta_deliveries here would conflate two different kinds of "unmatched"
-- into one table (a third parallel concept), which the task explicitly rules out.
-- ============================================================

ALTER TABLE exchanges
    ADD COLUMN matched_order_id uuid REFERENCES orders(id),
    ADD COLUMN match_method     text,
    ADD COLUMN matched_at       timestamptz;

ALTER TABLE exchanges
    ADD CONSTRAINT exchanges_match_method_check
        CHECK (match_method IS NULL OR match_method IN ('reference', 'phone', 'manual', 'bare'));

-- Extend status: add only the 6 new values this phase introduces
-- (needs_confirmation, matched, unmatched, bare_return, dismissed, return_received).
-- The 7 existing values are untouched — outbound_in_fulfillment/out_for_exchange/
-- return_pending/reconciled remain declared-but-unwritten placeholders for a future
-- phase, same as before this migration; needs_mapping/mapped/cancelled are the only
-- ones any code writes today (Part 0.1 finding).
ALTER TABLE exchanges DROP CONSTRAINT exchanges_status_check;
ALTER TABLE exchanges ADD CONSTRAINT exchanges_status_check
    CHECK (status IN (
        'needs_mapping', 'mapped', 'outbound_in_fulfillment', 'out_for_exchange',
        'return_pending', 'reconciled', 'cancelled',
        'needs_confirmation', 'matched', 'unmatched', 'bare_return', 'dismissed',
        'return_received'
    ));

-- Unmatched/needs_confirmation are the two statuses the Step 4 "Exchanges & Refunds"
-- tab's action surface (Part D) will list and filter by — same discipline as the
-- existing exchanges_needs_mapping_idx (V74).
CREATE INDEX exchanges_unmatched_idx ON exchanges (tenant_id, status)
    WHERE status IN ('unmatched', 'needs_confirmation');
