-- V224 — "Same address" pickups without a district (2026-11-07).
--
-- Prod: 95% of recent Bosta forward deliveries carry a city but no district, so a "Same address" return / exchange of
-- such an order is booked with the original's city + lines and NO district. If Bosta refuses it for the missing area,
-- the booking is a definite failure ('failed', never retried automatically) with this reason, and the merchant picks
-- the area in the request drawer and books again. Set only by ReturnPickupBookingService; cleared by the next claim.
ALTER TABLE return_requests ADD COLUMN booking_failure_reason text
    CHECK (booking_failure_reason IS NULL OR booking_failure_reason = 'district_required');
