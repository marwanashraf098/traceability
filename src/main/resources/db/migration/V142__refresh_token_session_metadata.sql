-- ============================================================
-- V142 — Refresh-token session metadata (Build A: device-scoped logout, rotation grace)
-- ============================================================
-- Diagnosis 2026-10-05: a warehouse tablet dropped out of station mode whenever its owner
-- logged out on another device (logout revoked EVERY refresh token of the user), and prod
-- could not show which device a token belonged to, how it was made or why it ended.
--
-- created_via    how the token was minted. NULL = minted before V142.
-- revoked_reason why it ended. NULL while live, and NULL on rows revoked before V142.
-- user_agent     the User-Agent of the request that minted it (diagnostics only; ≤ 512 chars).
-- replaced_by    the successor minted when this token was rotated — lets a token presented
--                again within the rotation grace (30 s) be answered with that same successor
--                instead of a 401 (AuthRepository.rotate).
--
-- No new grants: app_user already has SELECT/INSERT/UPDATE on refresh_tokens (V1 defaults),
-- and the RLS policy from V3 covers the new columns. lookup_refresh_token (hatch #3) is NOT
-- changed — the new columns are read afterwards, under RLS, with the tenant it returned.

ALTER TABLE refresh_tokens
    ADD COLUMN created_via text
        CHECK (created_via IN ('login', 'refresh', 'pin', 'signup', 'magic_link')),
    ADD COLUMN revoked_reason text
        CHECK (revoked_reason IN ('logout_device', 'logout_all', 'password_reset',
                                  'rotated', 'pin_switch', 'orphan_cleanup')),
    ADD COLUMN user_agent text
        CHECK (char_length(user_agent) <= 512),
    ADD COLUMN replaced_by uuid REFERENCES refresh_tokens(id) ON DELETE SET NULL;
