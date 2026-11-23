-- V242 — Bazar B10 (approved 2026-11-22): a seller invite is opened in two steps, so a link preview can't use it up.
--   GET /auth/seller-invite only sends the browser to the landing page (nothing is read or written); the landing page
--   asks POST /api/v1/auth/seller-invite/check (reads only) and its "Start selling" button POSTs …/redeem, which
--   consumes the token through hatch #6 (consume_magic_link — unchanged, no new hatch) and stamps redeemed_at here.
--   - bazar_seller_invites.redeemed_at: when the link was used. app_user may UPDATE this column only (V238 revoked
--     UPDATE on the table; the row stays append-only otherwise). RLS policy unchanged (NULLIF, FORCE — V238).
--   - The link's state has ONE definition, SellerInviteService.STATE_SQL: used (redeemed_at) / replaced (a newer invite
--     for the same event + seller) / removed / expired / valid.
--   - embedded_onboarding_attempts now also holds the seller-invite per-IP limit (bucket 'seller-invite-ip:<HMAC>');
--     same table, same nightly purge (hatch #16), no PII.

ALTER TABLE bazar_seller_invites ADD COLUMN redeemed_at timestamptz;
GRANT UPDATE (redeemed_at) ON bazar_seller_invites TO app_user;

COMMENT ON TABLE embedded_onboarding_attempts IS
    'Rate-limit ledger, no tenant, no PII: /api/v1/embedded/onboarding/** (shop: / ip: buckets, Build D) and the '
    'Bazar seller-invite check / redeem (seller-invite-ip: bucket, B10). The IP is only stored as an HMAC. '
    'app_user SELECT / INSERT only; rows older than 24 h are purged nightly by purge_onboarding_artifacts().';
