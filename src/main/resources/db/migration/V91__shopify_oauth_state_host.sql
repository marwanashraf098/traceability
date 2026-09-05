-- ============================================================
-- V91 — Capture the Shopify `host` param at OAuth install time
-- ============================================================
-- Fix 2.3.3 (App Store review): ShopifyOAuthController.callback() previously
-- assumed `host` would be echoed back by Shopify on the OAuth callback, which
-- is not guaranteed for a fresh install (host is not a documented parameter
-- of the classic authorization-code-grant callback). This adds a nullable
-- `host` column so that if `host` IS present at /auth/shopify/install (e.g. a
-- re-authorization triggered from an already-open embedded session), it is
-- persisted alongside the state nonce and read back at callback() time,
-- instead of relying on Shopify to round-trip it through the OAuth redirect.
--
-- This is NOT the primary fix for a cold/fresh install (host is normally
-- absent there too, with nothing to capture) — see
-- ShopifyOAuthService.buildAdminAppUrl()'s shop-derived fallback for that.
-- This column only improves the re-authorization case where host WAS
-- available at install time.

ALTER TABLE shopify_oauth_state ADD COLUMN host text NULL;

COMMENT ON COLUMN shopify_oauth_state.host IS
    'Shopify''s base64-encoded host param, captured at /auth/shopify/install '
    'time if present (NOT guaranteed — absent on most fresh/cold installs). '
    'Read back at callback() via consume_shopify_oauth_state() to build a '
    'precise admin app URL; NULL falls back to the shop-derived admin URL '
    '(ShopifyOAuthService.buildAdminAppUrl()).';

-- consume_shopify_oauth_state (9th SECURITY DEFINER hatch, V87) now also
-- returns host — same FOR UPDATE row, no new query, no new hatch. Every
-- other guard (not-found / consumed / expired / shop-mismatch → single empty
-- result, no oracle) is unchanged from V87.
-- Postgres refuses CREATE OR REPLACE across an OUT-parameter/return-type
-- change ("cannot change return type of existing function") — DROP first.
DROP FUNCTION consume_shopify_oauth_state(text, text);

CREATE FUNCTION consume_shopify_oauth_state(p_nonce text, p_callback_shop text)
RETURNS TABLE(tenant_id uuid, shop_domain text, host text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_tenant_id  uuid;
    v_shop       text;
    v_host       text;
    v_created_at timestamptz;
    v_consumed   timestamptz;
BEGIN
    SELECT sos.tenant_id, sos.shop_domain, sos.host, sos.created_at, sos.consumed_at
    INTO   v_tenant_id, v_shop, v_host, v_created_at, v_consumed
    FROM   shopify_oauth_state sos
    WHERE  sos.nonce = p_nonce
    FOR UPDATE;

    IF NOT FOUND
       OR v_consumed IS NOT NULL
       OR v_created_at < now() - interval '600 seconds'
       OR v_shop <> p_callback_shop THEN
        RETURN; -- not-found / replay / expired / shop-mismatch — all identical, no oracle
    END IF;

    UPDATE shopify_oauth_state sos2 SET consumed_at = now() WHERE sos2.nonce = p_nonce;

    -- v_tenant_id and v_host may both be SQL NULL — RETURN QUERY passes them through unchanged.
    RETURN QUERY SELECT v_tenant_id, v_shop, v_host;
END;
$$;

REVOKE ALL ON FUNCTION consume_shopify_oauth_state(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION consume_shopify_oauth_state(text, text) TO app_user;
