-- ============================================================
-- V87 — Lock down shopify_oauth_state (last pre-session credential table)
-- ============================================================
-- Same gap as V86 closed for magic_link_tokens / password_reset_codes: V13's
-- own comment claimed "any cross-tenant read must go through a named
-- code-reviewed function," but the consume path (ShopifyOAuthService.
-- consumeState()) was plain app_user SQL, and V13 never issued a REVOKE — so
-- V1's unrevoked default privileges left app_user with full SELECT/UPDATE/
-- DELETE. Confirmed empirically before this fix: a live app_user session
-- under tenant A's GUC could SELECT tenant B's shop_domain, and the Path-2
-- (tenant_id IS NULL) rows, directly. Neither is RLS'd — both are pre-session
-- surfaces by design.
--
-- Unlike the other two tables, this one also has an UNSCOPED cleanup DELETE
-- (ShopifyStateCleanupJob, hourly sweep across all tenants) — that's the
-- second hatch here, so app_user ends up INSERT-only with zero standing
-- privileges beyond that, same end state as magic_link_tokens/password_reset_codes.

-- ---- Ninth SECURITY DEFINER escape hatch — APPROVED (Marawan, this build) --
-- Mirrors consume_magic_link (V16) / verify_and_consume_reset_code (V85/V86):
-- FOR UPDATE on the unguessable nonce PK, one empty result for every invalid
-- sub-condition (not-found / already-consumed / expired / shop-mismatch) — no
-- oracle for the caller. tenant_id is NULL for Path-2 (pre-tenant merchant
-- install) and MUST pass through as SQL NULL, never coerced — the
-- resolve-or-create decision tree in ShopifyOAuthService.linkOrProvision()
-- branches directly on tenant_id being null vs non-null.
--
-- Every column reference below is qualified via the "sos" alias. The RETURNS
-- TABLE(tenant_id, shop_domain) OUT parameters are in scope for the whole
-- function body — a bare "tenant_id" or "shop_domain" reference would be
-- ambiguous against them, exactly the bug that hit V85's first draft (fixed
-- there) and V86's first draft (fixed there) before either shipped. Not
-- letting it happen a third time.
CREATE OR REPLACE FUNCTION consume_shopify_oauth_state(p_nonce text, p_callback_shop text)
RETURNS TABLE(tenant_id uuid, shop_domain text)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_tenant_id  uuid;
    v_shop       text;
    v_created_at timestamptz;
    v_consumed   timestamptz;
BEGIN
    SELECT sos.tenant_id, sos.shop_domain, sos.created_at, sos.consumed_at
    INTO   v_tenant_id, v_shop, v_created_at, v_consumed
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

    -- v_tenant_id may be SQL NULL (Path-2) — RETURN QUERY passes it through unchanged.
    RETURN QUERY SELECT v_tenant_id, v_shop;
END;
$$;

REVOKE ALL ON FUNCTION consume_shopify_oauth_state(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION consume_shopify_oauth_state(text, text) TO app_user;

-- ---- Tenth SECURITY DEFINER escape hatch — APPROVED (Marawan, this build) --
-- The hourly cleanup sweep (ShopifyStateCleanupJob) is deliberately unscoped —
-- it deletes every tenant's (and every Path-2 null-tenant's) expired rows in
-- one pass, by design (no per-tenant GUC exists for this job; see the job's
-- own class comment). No auth logic, no branching, no security-relevant
-- decision — a pure janitorial DELETE. Wrapping it in DEFINER (rather than
-- granting app_user a standing DELETE) keeps app_user's privilege on this
-- table at zero beyond INSERT — the same uniform "INSERT-only, everything
-- else via a named function" end state as the other two locked-down tables.
CREATE OR REPLACE FUNCTION purge_expired_shopify_oauth_state()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    v_deleted integer;
BEGIN
    DELETE FROM shopify_oauth_state WHERE created_at < now() - interval '1 hour';
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    RETURN v_deleted;
END;
$$;

REVOKE ALL ON FUNCTION purge_expired_shopify_oauth_state() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION purge_expired_shopify_oauth_state() TO app_user;

-- ---- Enforce DEFINER-only access ------------------------------------------
-- INSERT stays granted (V1's unrevoked default privileges) — ShopifyOAuthService.
-- initiateOAuth() is a plain app_user INSERT and stays that way; nothing else
-- needs to change for issuance.
--
-- After this migration, the only direct app_user statement against this table
-- is that INSERT. Every read, the consume/expire write, and the cleanup
-- delete go through a named, code-reviewed SECURITY DEFINER function.
REVOKE SELECT, UPDATE, DELETE ON shopify_oauth_state FROM app_user;

-- ---- Correct the now-enforced (previously aspirational) table comment ----
-- V13's own inline SQL comment already claimed this protection; that file is
-- frozen (already applied) and is not edited in place. COMMENT ON updates the
-- live, queryable description without touching V13's checksummed content.
COMMENT ON TABLE shopify_oauth_state IS
    'Single-use OAuth state nonces (Shopify install/callback CSRF guard). NOT '
    'under tenant RLS — pre-session surface, no GUC exists before consumption, '
    'and tenant_id is NULL for Path-2 (pre-tenant merchant-initiated install) '
    'until the resolve-or-create decision runs. app_user has INSERT only '
    '(enforced by V87 REVOKE); consume goes through consume_shopify_oauth_state '
    'and the hourly cleanup sweep through purge_expired_shopify_oauth_state '
    '(both SECURITY DEFINER, V87).';
