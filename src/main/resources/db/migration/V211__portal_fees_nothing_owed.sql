-- V211 — Returns portal fees F2: a refund whose frozen due is 0 (the customer fee was ≥ the item value) closes as
-- 'nothing_owed' (Marawan, Q7: allow the request, close it as nothing owed). return_refunds keeps amount > 0, so such
-- a request can never be marked refunded; the merchant closes it with this reason instead (ReturnRequestLifecycle
-- checks the frozen due is 0 before allowing it). Nothing else changes.
ALTER TABLE return_requests DROP CONSTRAINT return_requests_close_reason_check;
ALTER TABLE return_requests
    ADD CONSTRAINT return_requests_close_reason_check
        CHECK (close_reason IN ('no_refund', 'rest_not_coming', 'other', 'exchange_failed', 'nothing_owed'));
