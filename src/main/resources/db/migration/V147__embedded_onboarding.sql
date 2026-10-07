-- V147 — Build D: onboarding inside the embedded Shopify app (2026-10-07).
--
-- 1. shopify_pending_links — "I already have a Traced account". The embedded onboarding endpoint
--    (verified session token, shop not linked to any tenant) exchanges the session token for an
--    offline token and parks it here under a single-use random nonce for 15 minutes. The browser's
--    top-level window then goes to app.tracedtech.com/connect/shopify?link=<nonce>; a signed-in
--    owner confirms there and the store is linked to THEIR tenant. Only the SHA-256 of the nonce
--    is stored; the token is AES-encrypted (EncryptionService) and cleared when the link is used.
--
--    No tenant exists when the row is written, so tenant RLS can't scope it. Instead the row is
--    visible only to a transaction that has proved it knows the nonce: the caller sets
--    app.pending_link (SET LOCAL, via set_config(..., true)) to the nonce hash, and the policy
--    matches exactly that row. Same NULLIF pattern as every tenant policy: an unset / reset GUC
--    ('' after ROLLBACK) matches nothing. NOT a SECURITY DEFINER hatch — plain app_user + RLS.
--    app_user: SELECT, INSERT, and UPDATE of ONLY the columns the consume writes (consumed_at,
--    consumed_by_tenant, and the two token columns it nulls in the same statement). No DELETE —
--    expired / consumed rows go through purge_onboarding_artifacts() (hatch #16, below).
CREATE TABLE shopify_pending_links (
    id                        uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    nonce_hash                text        NOT NULL UNIQUE,
    shop_domain               text        NOT NULL,
    access_token_encrypted    text,
    access_token_expires_at   timestamptz,
    refresh_token_encrypted   text,
    refresh_token_expires_at  timestamptz,
    access_token_scopes       text,
    created_at                timestamptz NOT NULL DEFAULT now(),
    expires_at                timestamptz NOT NULL,
    consumed_at               timestamptz,
    consumed_by_tenant        uuid        REFERENCES tenants(id),
    -- An unused link carries a token; a used one never does.
    CONSTRAINT shopify_pending_links_token_cleared
        CHECK (consumed_at IS NULL OR (access_token_encrypted IS NULL AND refresh_token_encrypted IS NULL))
);

ALTER TABLE shopify_pending_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE shopify_pending_links FORCE ROW LEVEL SECURITY;
CREATE POLICY pending_link_by_nonce ON shopify_pending_links
    USING (nonce_hash = NULLIF(current_setting('app.pending_link', true), ''))
    WITH CHECK (nonce_hash = NULLIF(current_setting('app.pending_link', true), ''));

REVOKE UPDATE, DELETE, TRUNCATE ON shopify_pending_links FROM app_user;
GRANT UPDATE (consumed_at, consumed_by_tenant, access_token_encrypted, refresh_token_encrypted)
    ON shopify_pending_links TO app_user;

-- 2. embedded_onboarding_attempts — rate-limit ledger for /api/v1/embedded/onboarding/**.
--    One row per attempt per bucket: 'shop:<shop>.myshopify.com' or 'ip:<HMAC-SHA256 of the IP>'.
--    No tenant (the shop isn't linked yet), so no RLS; no PII (a shop domain is the merchant's
--    store address, the IP is keyed-hashed and never stored in clear). app_user: SELECT, INSERT only;
--    rows older than 24 hours go through purge_onboarding_artifacts() (hatch #16).
CREATE TABLE embedded_onboarding_attempts (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    bucket        text        NOT NULL,
    attempted_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX embedded_onboarding_attempts_bucket_idx
    ON embedded_onboarding_attempts (bucket, attempted_at);

REVOKE UPDATE, DELETE, TRUNCATE ON embedded_onboarding_attempts FROM app_user;

-- 3. Hatch #16 (approved 2026-10-07, Build D): the nightly purge. No parameters — its scope is fixed:
--    pending links that are expired OR consumed (never a live one), and rate-limit rows older than
--    24 hours. SECURITY DEFINER because app_user has no DELETE on either table and a live pending
--    link is visible to nobody without its nonce. Called only by OnboardingPurgeJob (JobRunr, nightly).
CREATE OR REPLACE FUNCTION purge_onboarding_artifacts()
RETURNS TABLE(pending_links_deleted integer, attempts_deleted integer)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_links    integer;
    v_attempts integer;
BEGIN
    DELETE FROM public.shopify_pending_links
     WHERE expires_at < now() OR consumed_at IS NOT NULL;
    GET DIAGNOSTICS v_links = ROW_COUNT;

    DELETE FROM public.embedded_onboarding_attempts
     WHERE attempted_at < now() - interval '24 hours';
    GET DIAGNOSTICS v_attempts = ROW_COUNT;

    RETURN QUERY SELECT v_links, v_attempts;
END;
$$;

REVOKE ALL ON FUNCTION purge_onboarding_artifacts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_onboarding_artifacts() TO app_user;

-- 4. Hatch #5 retired: provision_tenant_from_shopify (V14) has had no application caller since Build D
--    (embedded signup creates tenant + owner + store under app_user + RLS with a Java-generated
--    tenant id). Dropped so the hatch no longer exists. Approved 2026-10-07.
DROP FUNCTION IF EXISTS provision_tenant_from_shopify(text, text, text, text, text);
