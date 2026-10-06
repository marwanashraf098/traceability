-- ============================================================
-- V146 — Build C: custom-app → official OAuth upgrade, recorded on the store
-- ============================================================
-- When an OAuth callback re-links a store whose row is a custom app (custom_app_cc / custom_app),
-- ShopifyOAuthService first deletes the OLD app's webhook subscriptions that point at Traced's endpoints —
-- using the old app's own credentials (LegacyWebhookCleanup) — then swaps the token, flips
-- connection_type to 'oauth' and clears api_secret_encrypted / client_id_encrypted. What happened is kept
-- here so ops can see it per store:
--   oauth_upgraded_from            the connection_type the store had before ('custom_app_cc' | 'custom_app')
--   oauth_upgraded_at              when the flip happened
--   legacy_webhook_cleanup_status  'done' (every matching subscription deleted, or none existed) |
--                                  'failed' (the upgrade still completed — see detail)
--   legacy_webhook_cleanup_detail  counts, or the reason it failed (≤ 500 chars, no secrets)
--   legacy_webhook_cleanup_at      when the cleanup ran
-- NULL on every store that never went through this upgrade. No grants needed (V1 defaults; stores RLS).
ALTER TABLE stores
    ADD COLUMN oauth_upgraded_from text CHECK (oauth_upgraded_from IN ('custom_app_cc', 'custom_app')),
    ADD COLUMN oauth_upgraded_at timestamptz,
    ADD COLUMN legacy_webhook_cleanup_status text CHECK (legacy_webhook_cleanup_status IN ('done', 'failed')),
    ADD COLUMN legacy_webhook_cleanup_detail text CHECK (char_length(legacy_webhook_cleanup_detail) <= 500),
    ADD COLUMN legacy_webhook_cleanup_at timestamptz;
