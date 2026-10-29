-- Lookup Exchange (C2, approved 2026-10-28): an owner / manager exchanges a piece from Lookup on a store that ships
-- with its own courier (generic_courier_enabled, no active Bosta account). It creates a merchant return request
-- (type 'exchange', auto-approved, booking 'manual') on the G4b generic lifecycle; the Lookup piece is reserved to
-- the internal replacement order, and entering the outbound tracking number hands it to the courier at once.
--
-- Nothing here changes an existing row's meaning: every existing request is 'portal'; RLS and the tenant_isolation
-- policies of both tables are unchanged.

-- 1. Where a return request came from. 'portal' — the customer's returns portal (every request so far, and the
--    default); 'lookup' — created by the merchant from Lookup's Exchange. Set once, at INSERT.
ALTER TABLE return_requests ADD COLUMN origin text NOT NULL DEFAULT 'portal'
    CONSTRAINT return_requests_origin_check CHECK (origin IN ('portal', 'lookup'));

-- 2. The history source of a Lookup exchange's replacement leg: tracking entered → with_courier at once
--    (GenericLegTransitions.dispatchLookupReplacement). V173's list plus 'lookup_dispatch'; V173 is not edited.
ALTER TABLE shipment_status_history DROP CONSTRAINT shipment_status_history_source_check;
ALTER TABLE shipment_status_history ADD CONSTRAINT shipment_status_history_source_check
    CHECK (source IS NULL OR source IN ('pickup_scan', 'inferred_handover', 'shopify', 'manual', 'return_scan',
                                        'lookup_dispatch'));
