-- ============================================================
-- V14 — Fifth SECURITY DEFINER escape hatch:
--        provision_tenant_from_shopify
-- ============================================================
--
-- Path-2 Shopify-first installs (App Store → new merchant) arrive with
-- no tenant context. Creating tenant + owner + store atomically is
-- impossible under app_user + RLS because:
--
--   1. INSERT INTO tenants requires GUC = the new tenant's UUID —
--      which is unknown until after the INSERT. Chicken-and-egg.
--   2. INSERT INTO users (tenant_id = new_uuid) is blocked by the same
--      policy: GUC is still empty when we try to write the owner row.
--   3. There is no safe SET LOCAL trick to bootstrap a row whose own
--      generated UUID must simultaneously be the authorizing GUC value.
--
-- A SECURITY DEFINER function running as the Flyway owner (postgres)
-- bypasses all RLS for its own inserts. The CALLER'S transaction is the
-- atomicity boundary: a 23505 on the stores INSERT propagates outward,
-- rolls back ALL three rows (tenant + user + store) committed by the
-- caller's BEGIN, and the application layer catches DuplicateKeyException,
-- re-resolves via resolve_tenant_by_shop_domain (the winner's row is
-- now committed), and idempotently links.
--
-- Fifth approved escape hatch (2026-06-19). Prior four:
--   auth_lookup_user (V1), resolve_tenant_by_shop_domain (V1),
--   lookup_refresh_token (V3), resolve_tenant_by_webhook_secret (V5).
--
-- Any further cross-tenant DML requires a new named function reviewed
-- with equal scrutiny. No bare BYPASSRLS connections in application code.
--
-- What this function touches — ONLY these three inserts:
--   • INSERT INTO tenants:  exactly one row (name = shop name)
--   • INSERT INTO users:    exactly one row (owner role, no password —
--                           magic-link auth wired on Day 4)
--   • INSERT INTO stores:   exactly one row (connected, import pending)
-- No locations, events, webhooks, or other defaults are created here.
-- (The "Main Warehouse" location per FR-1.3 is created by the import job
-- or a future tenant-setup flow — not at OAuth provisioning time.)
-- ============================================================

CREATE OR REPLACE FUNCTION provision_tenant_from_shopify(
    p_shop_domain            text,
    p_owner_email            text,
    p_shop_name              text,
    p_timezone               text,
    p_access_token_encrypted text
) RETURNS TABLE(tenant_id uuid, owner_user_id uuid, store_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_tenant_id  uuid;
    v_owner_id   uuid;
    v_store_id   uuid;
BEGIN
    -- 1. Tenant row.
    INSERT INTO public.tenants (name)
        VALUES (p_shop_name)
        RETURNING id INTO v_tenant_id;

    -- 2. Owner user — no password_hash: Path-2 owners authenticate via
    --    magic-link (Day 4). email is the Shopify shop owner email (not
    --    customer PII; no PCD gate required for the Shop resource).
    INSERT INTO public.users (tenant_id, name, email, role)
        VALUES (v_tenant_id, p_shop_name, p_owner_email, 'owner')
        RETURNING id INTO v_owner_id;

    -- 3. Store row — 23505 here propagates to the caller's transaction and
    --    rolls back both rows above. Zero orphan tenants / users possible.
    INSERT INTO public.stores (
        tenant_id, shop_domain, platform,
        access_token_encrypted, status, import_status)
    VALUES (
        v_tenant_id, p_shop_domain, 'shopify',
        p_access_token_encrypted, 'connected', 'pending')
    RETURNING id INTO v_store_id;

    RETURN QUERY SELECT v_tenant_id, v_owner_id, v_store_id;
END;
$$;

REVOKE ALL ON FUNCTION provision_tenant_from_shopify(text, text, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION provision_tenant_from_shopify(text, text, text, text, text) TO app_user;
