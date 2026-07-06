-- ============================================================
-- V37 — Bosta state mapping fixes + exception storage
-- ============================================================

-- 1. Exception code/reason columns on shipments (state 47 NDR data)
ALTER TABLE shipments
    ADD COLUMN IF NOT EXISTS exception_code    INTEGER,
    ADD COLUMN IF NOT EXISTS exception_reason  TEXT;

-- 2. State 60 "Returned to stock" is TERMINAL — fix from with_courier → returned
--    so BostaStatusPollJob stops polling these shipments.
UPDATE bosta_state_mappings
SET internal_shipment_state = 'returned',
    piece_status_after       = 'return_pending_inspection',
    notes                    = 'Terminal: Bosta returned parcel to their stock. Starts FR-12.4 clock.'
WHERE state_code = 60;

-- 3. State 11 "Waiting for route" — new state between 10 (pickup requested) and 20 (route assigned)
INSERT INTO bosta_state_mappings
    (state_code, applies_to_order_type, bosta_state,
     internal_shipment_state, piece_status_after, notes)
VALUES
(11, 'ALL', 'Waiting for route',
     'created', NULL,
     'Pre-transit; between pickup-requested and route-assigned'),

-- 4. State 41 additional type variants (beyond SEND and RTO)
--    SEND/FXF_SEND = out for delivery; EXCHANGE/CRP = out for return
(41, 'FXF_SEND',  'Out for delivery (fulfillment)',
     'with_courier', NULL,
     'Fulfillment-type delivery; same piece effect as SEND'),
(41, 'EXCHANGE',  'Out for return (exchange)',
     'returning', 'return_in_transit',
     'Exchange pickup out; same piece effect as RTO'),
(41, 'CRP',       'Out for return (customer return pickup)',
     'returning', 'return_in_transit',
     'Customer return pickup; same piece effect as RTO');

-- 5. NDR codes 100 and 101 exist for BOTH forward and return contexts.
--    The original schema used code as the sole PK which blocks duplicate code numbers
--    across categories. Widen the PK to (code, category) first.
ALTER TABLE ndr_codes DROP CONSTRAINT ndr_codes_pkey;
ALTER TABLE ndr_codes ADD PRIMARY KEY (code, category);

INSERT INTO ndr_codes (code, category, description, severity)
VALUES
(100, 'forward', 'Bad weather',           'normal'),
(101, 'forward', 'Suspicious consignee',  'normal'),
(100, 'return',  'Bad weather',           'normal'),
(101, 'return',  'Suspicious consignee',  'normal');
