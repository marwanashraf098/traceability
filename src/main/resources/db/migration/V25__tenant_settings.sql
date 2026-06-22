-- FR-1.4: Tenant settings — language, timezone, pickup address.
-- label_width_mm / label_height_mm already on tenants from V7.
-- Bosta-specific fields (awb_format, awb_lang, pickup_business_location_id) stay on courier_accounts.

ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS default_language text NOT NULL DEFAULT 'ar',
    ADD COLUMN IF NOT EXISTS timezone         text NOT NULL DEFAULT 'Africa/Cairo',
    ADD COLUMN IF NOT EXISTS pickup_address   text;
