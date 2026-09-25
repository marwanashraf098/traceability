-- ============================================================
-- V106 — Returns portal Step 4c-3: book the Bosta customer return pickup (type 25) on
-- approval (MODE B AMENDMENT #2 — create type 25 only, from an approved return request,
-- claim-before-call; no terminate, no edit).
--
-- return_requests.booking_status — the claim row lives on the request itself:
--   NULL             never attempted
--   pending          claimed; the one Bosta POST is in flight (or its answer is being written)
--   booked           Bosta answered with a tracking number
--   failed           definitely NOT created in Bosta (precondition, 4xx, 429) — safe to retry
--   failed_ambiguous may or may not have been created (5xx, timeout, reset, stuck pending) —
--                    NEVER re-POSTed automatically; a person checks in Bosta
--   needs_review     booked, but reading it back from Bosta showed different details
-- The claim is a conditional UPDATE to 'pending' only from NULL or 'failed' (the
-- ShopifyInventoryService.claim() pattern), committed before the HTTP call.
--
-- bosta_tracking_number is unique when set: two requests can never claim one Bosta delivery.
--
-- tenants.portal_pickup_booking_since — when the merchant last switched booking ON. The
-- sweeper only books orphaned approvals decided after it, so turning booking on never books
-- older approved requests (which the merchant may already have booked by hand in Bosta).
-- ============================================================

ALTER TABLE return_requests
    ADD COLUMN booking_status        text
        CONSTRAINT return_requests_booking_status_check
        CHECK (booking_status IN ('pending', 'booked', 'failed', 'failed_ambiguous', 'needs_review')),
    ADD COLUMN booking_attempted_at  timestamptz,
    ADD COLUMN booking_error         text,
    ADD COLUMN bosta_delivery_id     text,
    ADD COLUMN bosta_tracking_number text,
    ADD COLUMN booking_verified_at   timestamptz;

CREATE UNIQUE INDEX return_requests_bosta_tracking_unique
    ON return_requests (bosta_tracking_number) WHERE bosta_tracking_number IS NOT NULL;

CREATE INDEX return_requests_booking_status_idx
    ON return_requests (tenant_id, booking_status) WHERE booking_status IS NOT NULL;

ALTER TABLE tenants
    ADD COLUMN portal_pickup_booking_since timestamptz;

UPDATE tenants SET portal_pickup_booking_since = now()
WHERE portal_pickup_booking AND portal_pickup_booking_since IS NULL;
