-- V20: AWB label settings + Bosta pickup config on courier_accounts;
--      AWB print failure tracking on shipments;
--      COD total cache on pickups.

-- courier_accounts: AWB label format/lang + Bosta pickup scheduling config.
-- Onboarding UI must collect: pickup_business_location_id, contact_person, pickup_mode.
ALTER TABLE courier_accounts
    ADD COLUMN pickup_mode                 text NOT NULL DEFAULT 'BOSTA_MANAGED',
    ADD COLUMN pickup_business_location_id text,
    ADD COLUMN contact_person              jsonb,
    ADD COLUMN awb_format                  text NOT NULL DEFAULT 'A4',
    ADD COLUMN awb_lang                    text NOT NULL DEFAULT 'ar';

-- shipments: per-shipment AWB print failure reason + timestamp.
-- Populated by BostaAwbService when a shipment is excluded from printing
-- (UNLINKED, NON_PRINTABLE_STATE:*, NON_PRINTABLE_TYPE:*, BOSTA_REJECTED:*).
-- ExceptionService.detectMissingAwb() queries this column.
ALTER TABLE shipments
    ADD COLUMN awb_print_failed_reason text,
    ADD COLUMN awb_print_failed_at     timestamptz;

-- pickups: cache total expected COD for manifest retrieval without re-joining.
ALTER TABLE pickups
    ADD COLUMN total_cod_amount numeric(12,2);
