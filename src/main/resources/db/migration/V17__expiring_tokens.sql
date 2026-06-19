-- FR-3: expiring offline token support
-- Adds refresh token + expiry columns to stores, and the needs_reauth status value.
--
-- access_token_expires_at NULL  = legacy non-expiring install (token already blocked by Shopify
--                                 for apps created after 2026-04-01); store must reinstall.
-- refresh_token_encrypted NULL  = same; no refresh possible.
-- needs_reauth status           = token refresh permanently rejected; merchant must reinstall.

ALTER TYPE store_status ADD VALUE IF NOT EXISTS 'needs_reauth';

ALTER TABLE stores
    ADD COLUMN refresh_token_encrypted  text,
    ADD COLUMN access_token_expires_at  timestamptz,
    ADD COLUMN refresh_token_expires_at timestamptz;
