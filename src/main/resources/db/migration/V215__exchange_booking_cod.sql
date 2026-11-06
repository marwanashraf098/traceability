-- Returns portal fees F4 (Mode B amendment #3, COD, approved 2026-11-05): the COD Traced SENT on a request's
-- Bosta type 30 exchange, written in the same conditional UPDATE that claims the booking (claim-before-call) —
-- the frozen return_request_amounts.collect_cod at that moment. The read-back compares Bosta's cod with it, and the
-- drawer / portal say "Bosta will collect EGP X" from it. NULL = no COD sent (every type 25, every pre-F4 booking,
-- an exchange with nothing to collect, fees switched off) — the read-back then expects cod 0, exactly as before.
-- Never negative, never above Bosta's 30,000 limit, never on a refund (type 25 stays cod 0).
ALTER TABLE return_requests
    ADD COLUMN booking_cod numeric(12,2)
        CONSTRAINT return_requests_booking_cod CHECK (booking_cod IS NULL OR (type = 'exchange' AND booking_cod > 0 AND booking_cod <= 30000));
