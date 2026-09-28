-- ============================================================
-- V117 — Portal: the customer chooses the delivery address or a different pickup address
-- (refunds and exchanges). Approved 2026-09-28.
--
-- pickup_address_source:
--   'order'  — today's behaviour: the booking sends the street address from the order's
--              delivered forward leg (raw.dropOffAddress).
--   'custom' — the customer typed a new address in the portal; the booking sends the custom_*
--              columns instead. Only the address block changes (type 25: pickupAddress, type 30:
--              dropOffAddress); everything else in both payloads is unchanged.
--
-- The existing pickup_city_* / pickup_district_* snapshot columns keep holding the chosen
-- city/area for BOTH sources.
--
-- custom_* is customer PII: never returned by a public endpoint, never logged, and cleared by
-- the GDPR customers/redact and shop/redact handlers — together with customer_email and
-- customer_note — which stamp pii_redacted_at (the drawer says the details were removed).
-- A 'custom' row therefore has a street longer than 5 characters, unless it was redacted (then
-- the street is NULL).
-- ============================================================

ALTER TABLE return_requests
    ADD COLUMN pickup_address_source      text NOT NULL DEFAULT 'order',
    ADD COLUMN custom_first_line          text,
    ADD COLUMN custom_second_line         text,
    ADD COLUMN custom_building_number     text,
    ADD COLUMN custom_floor               text,
    ADD COLUMN custom_apartment           text,
    ADD COLUMN pii_redacted_at            timestamptz;

ALTER TABLE return_requests
    ADD CONSTRAINT return_requests_pickup_address_source_check
        CHECK (pickup_address_source IN ('order', 'custom')),
    ADD CONSTRAINT return_requests_custom_first_line_len
        CHECK (custom_first_line IS NULL OR char_length(custom_first_line) <= 250),
    ADD CONSTRAINT return_requests_custom_second_line_len
        CHECK (custom_second_line IS NULL OR char_length(custom_second_line) <= 250),
    ADD CONSTRAINT return_requests_custom_building_number_len
        CHECK (custom_building_number IS NULL OR char_length(custom_building_number) <= 20),
    ADD CONSTRAINT return_requests_custom_floor_len
        CHECK (custom_floor IS NULL OR char_length(custom_floor) <= 20),
    ADD CONSTRAINT return_requests_custom_apartment_len
        CHECK (custom_apartment IS NULL OR char_length(custom_apartment) <= 20),
    -- 'order' rows carry no custom street; 'custom' rows need one longer than 5 characters
    -- (the same minimum the booking applies to the order's address), unless redacted.
    ADD CONSTRAINT return_requests_custom_address_shape CHECK (
        (pickup_address_source = 'order'
            AND custom_first_line IS NULL AND custom_second_line IS NULL AND custom_building_number IS NULL
            AND custom_floor IS NULL AND custom_apartment IS NULL)
        OR (pickup_address_source = 'custom'
            AND (char_length(btrim(custom_first_line)) > 5
                 OR (pii_redacted_at IS NOT NULL AND custom_first_line IS NULL
                     AND custom_second_line IS NULL AND custom_building_number IS NULL
                     AND custom_floor IS NULL AND custom_apartment IS NULL)))
    );
