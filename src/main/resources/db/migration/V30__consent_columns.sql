-- ============================================================
-- V30 — Signup consent gate (FR-1.1)
-- ============================================================
-- Adds three nullable columns to the owner row created at signup.
-- Nullable so Shopify-provisioned users (V14 path) can have NULL
-- until they complete a consent flow. No default — NULL = not given.
--
-- RLS is already enforced on users (V1 ENABLE + FORCE). The existing
-- tenant_isolation policy covers these columns automatically.
-- No new GRANTs needed: V1 ALTER DEFAULT PRIVILEGES already covers
-- SELECT, INSERT, UPDATE on all present and future tables.
-- ============================================================

ALTER TABLE users
    ADD COLUMN accepted_privacy_version text,
    ADD COLUMN accepted_terms_version   text,
    ADD COLUMN accepted_at              timestamptz;
