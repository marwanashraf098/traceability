-- ============================================================
-- V113 — Step 5c: Traced books the Bosta exchange (type 30) for an approved exchange request
--
-- tenants.portal_exchanges_since: stamped when "Allow exchanges" flips off → on (same
--   CASE pattern as portal_pickup_booking_since). The booking sweeper books orphaned
--   approved exchanges only when they were decided at or after it, so switching exchanges
--   on never books older approvals by itself ("Book now" does, on purpose).
-- exchanges.return_request_id: the exchange request whose Traced booking created this
--   Bosta exchange (NULL for exchanges made in Bosta's dashboard). One exchange per request.
-- return_requests.close_reason: 'exchange_failed' (exchange requests only — enforced in code).
-- ============================================================

ALTER TABLE tenants
    ADD COLUMN portal_exchanges_since timestamptz;

ALTER TABLE exchanges
    ADD COLUMN return_request_id uuid REFERENCES return_requests(id);

CREATE UNIQUE INDEX exchanges_return_request_unique
    ON exchanges (return_request_id) WHERE return_request_id IS NOT NULL;

ALTER TABLE return_requests DROP CONSTRAINT return_requests_close_reason_check;
ALTER TABLE return_requests
    ADD CONSTRAINT return_requests_close_reason_check
        CHECK (close_reason IN ('no_refund', 'rest_not_coming', 'other', 'exchange_failed'));
