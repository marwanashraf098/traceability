-- ============================================================
-- V19 — Mode-B phone+COD fallback: concurrency guard + diagnostics
-- ============================================================

-- 1. match_reason: records WHY a delivery landed in unlinked_bosta_deliveries.
--    Populated by matchByPhoneAndCod() on all new rows; NULL on pre-V19 rows.
--    Values: NO_MATCH, AMBIGUOUS_MULTI, COD_ONLY_AMBIGUOUS.
ALTER TABLE unlinked_bosta_deliveries ADD COLUMN match_reason text;

-- 2. At-most-one ACTIVE shipment per order.
--    "Active" = any internal_state that Bosta has NOT explicitly abandoned.
--    "terminated" (code 48) and "cancelled" (code 49/104) are the only states where
--    Bosta gives up on a delivery and a replacement shipment is operationally valid.
--    The partial predicate keeps old terminated/cancelled rows out of the index so
--    a legitimate re-ship (new AWB after Bosta cancels the first) is still allowed.
--    This closes the phone+COD concurrent-double-link race without a transaction lock:
--    the losing INSERT fails with 23505 and is routed to unlinked_bosta_deliveries.
CREATE UNIQUE INDEX ux_active_shipment_per_order
    ON shipments (order_id)
    WHERE internal_state NOT IN ('terminated', 'cancelled');

-- 3. Functional index for phone canonicalization at match time.
--    Expression mirrors the SQL used in matchByPhoneAndCod():
--      '0' || RIGHT(REGEXP_REPLACE(customer_phone, '[^0-9]', '', 'g'), 10)
--    Handles stored forms: '01XXXXXXXXX', '+20XXXXXXXXXX', '00201XXXXXXXXX', etc.
--    The index has no entries today (all customer_phone are NULL pre-PCD) but makes
--    the phone+COD query index-seekable the moment PCD approval lands and phones
--    are written. No query change is needed at that point.
CREATE INDEX orders_customer_phone_canonical
    ON orders (('0' || RIGHT(REGEXP_REPLACE(customer_phone, '[^0-9]', '', 'g'), 10)))
    WHERE customer_phone IS NOT NULL;
