-- Non-Bosta couriers G4a (2026-10-21) — returns-portal REFUNDS for orders shipped with another courier.
--
-- Nothing here changes what a Bosta order or a tenant with the new switch off sees: one tenant switch
-- (default false), one nullable column only generic requests write, and one new value in each of two
-- CHECKs that no Bosta path ever writes.

-- 1. The per-tenant switch (Settings → Returns portal, owner only, shown only while generic_courier_enabled).
--    Off → a generic order stays out of the portal exactly as in G3. _since is stamped when switched on.
ALTER TABLE tenants
    ADD COLUMN portal_generic_returns_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN portal_generic_returns_since   timestamptz;

-- 2. return_requests.booking_status 'manual': an approved request on a generic order — the merchant books
--    their own courier. Every Bosta path skips it with no change to its SQL: the sweeper / Book now want
--    NULL, retry wants 'failed', confirm / not-booked want 'failed_ambiguous', pickup_booking_problem wants
--    failed / failed_ambiguous / needs_review, linkCandidateSql wants NULL or 'failed'.
ALTER TABLE return_requests DROP CONSTRAINT return_requests_booking_status_check;
ALTER TABLE return_requests ADD CONSTRAINT return_requests_booking_status_check
    CHECK (booking_status IN ('pending', 'booked', 'failed', 'failed_ambiguous', 'needs_review', 'manual'));

--    The courier the merchant books the pickup with (generic requests only; NULL on every Bosta request).
ALTER TABLE return_requests ADD COLUMN manual_carrier_name text
    CHECK (manual_carrier_name IS NULL OR (length(btrim(manual_carrier_name)) BETWEEN 1 AND 80));

-- 3. link_source 'merchant_entered': the merchant typed the return's tracking number, which created the
--    generic return leg (shipments.source 'portal_request') this request now holds.
ALTER TABLE return_requests DROP CONSTRAINT return_requests_link_source_check;
ALTER TABLE return_requests ADD CONSTRAINT return_requests_link_source_check
    CHECK (link_source IN ('traced_booking', 'auto_matched', 'merchant_selected', 'merchant_entered'));
