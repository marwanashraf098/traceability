-- V31: custom-app connection type
-- Adds connection_type and api_secret_encrypted to stores.
-- All existing rows default to 'oauth' — untouched.
ALTER TABLE stores
    ADD COLUMN connection_type       text NOT NULL DEFAULT 'oauth',
    ADD COLUMN api_secret_encrypted  text;

-- Sixth approved SECURITY DEFINER escape hatch (see blueprint.md §16.1):
-- upgrade_custom_app_to_oauth — called when a shop_domain already connected
-- as custom_app installs via public OAuth. Atomically:
--   1. Creates new tenant + owner user
--   2. Updates the existing store row in-place (same store_id, same child data)
--   3. Re-assigns all tenant-scoped child records to the new tenant
-- This is Option-B of the custom_app → OAuth migration path.
-- Option-A (disconnect + reinstall) is the current operational procedure;
-- this function is implemented now so we are not blocked later.
--
-- Return columns are prefixed with out_ to avoid plpgsql name clash with
-- identically-named table columns inside the function body.

CREATE OR REPLACE FUNCTION upgrade_custom_app_to_oauth(
    p_shop_domain                text,
    p_owner_email                text,
    p_shop_name                  text,
    p_timezone                   text,
    p_access_token_encrypted     text,
    p_access_token_expires_at    timestamptz,
    p_refresh_token_encrypted    text,
    p_refresh_token_expires_at   timestamptz
) RETURNS TABLE(out_tenant_id uuid, out_owner_user_id uuid, out_store_id uuid)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_old_tenant_id  uuid;
    v_new_tenant_id  uuid;
    v_new_owner_id   uuid;
    v_store_id       uuid;
BEGIN
    -- Resolve the existing custom_app store.
    SELECT s.id, s.tenant_id
      INTO v_store_id, v_old_tenant_id
      FROM public.stores s
     WHERE s.shop_domain = p_shop_domain
       AND s.connection_type = 'custom_app'
     LIMIT 1;

    IF v_store_id IS NULL THEN
        RAISE EXCEPTION 'No custom_app store found for domain %', p_shop_domain;
    END IF;

    -- 1. New tenant.
    INSERT INTO public.tenants (name)
        VALUES (p_shop_name)
        RETURNING id INTO v_new_tenant_id;

    -- 2. New owner user (magic-link auth, no password_hash).
    INSERT INTO public.users (tenant_id, name, email, role)
        VALUES (v_new_tenant_id, p_shop_name, p_owner_email, 'owner')
        RETURNING id INTO v_new_owner_id;

    -- 3. Upgrade the existing store row in-place (same store_id → child FK refs preserved).
    UPDATE public.stores s SET
        tenant_id                = v_new_tenant_id,
        access_token_encrypted   = p_access_token_encrypted,
        access_token_expires_at  = p_access_token_expires_at,
        refresh_token_encrypted  = p_refresh_token_encrypted,
        refresh_token_expires_at = p_refresh_token_expires_at,
        api_secret_encrypted     = NULL,
        connection_type          = 'oauth',
        status                   = 'connected',
        import_status            = 'pending'
    WHERE s.id = v_store_id;

    -- 4. Re-assign all tenant-scoped child data to the new tenant.
    -- IMPORTANT: audit this list before using Option-B in production —
    -- add any new tenant-scoped tables created after V31.
    UPDATE public.orders o
       SET tenant_id = v_new_tenant_id
     WHERE o.tenant_id = v_old_tenant_id
       AND o.store_id  = v_store_id;

    UPDATE public.products prod
       SET tenant_id = v_new_tenant_id
     WHERE prod.tenant_id = v_old_tenant_id
       AND prod.store_id  = v_store_id;

    UPDATE public.shipments sh
       SET tenant_id = v_new_tenant_id
     WHERE sh.tenant_id = v_old_tenant_id;

    UPDATE public.unlinked_bosta_deliveries ubd
       SET tenant_id = v_new_tenant_id
     WHERE ubd.tenant_id = v_old_tenant_id;

    UPDATE public.shopify_webhook_events swe
       SET tenant_id = v_new_tenant_id
     WHERE swe.tenant_id = v_old_tenant_id;

    UPDATE public.locations loc
       SET tenant_id = v_new_tenant_id
     WHERE loc.tenant_id = v_old_tenant_id;

    -- variants and order_items inherit tenant_id from products/orders; update for RLS correctness.
    UPDATE public.variants v
        SET tenant_id = v_new_tenant_id
       FROM public.products p
      WHERE v.product_id = p.id
        AND p.store_id   = v_store_id
        AND v.tenant_id  = v_old_tenant_id;

    UPDATE public.order_items oi
        SET tenant_id = v_new_tenant_id
       FROM public.orders o
      WHERE oi.order_id  = o.id
        AND o.store_id   = v_store_id
        AND oi.tenant_id = v_old_tenant_id;

    RETURN QUERY SELECT v_new_tenant_id, v_new_owner_id, v_store_id;
END;
$$;

REVOKE ALL ON FUNCTION upgrade_custom_app_to_oauth(text, text, text, text, text, timestamptz, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION upgrade_custom_app_to_oauth(text, text, text, text, text, timestamptz, text, timestamptz) TO app_user;
