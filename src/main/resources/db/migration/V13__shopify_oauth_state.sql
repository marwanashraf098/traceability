-- SECURITY NOTE: shopify_oauth_state is intentionally NOT under tenant RLS.
-- Path-2 states (merchant-initiated installs from the Shopify App Store) have
-- no tenant_id until the resolve-or-create decision runs on callback (Day 2).
-- This is a pre-tenant surface, reviewed with the same scrutiny as the four
-- enumerated SECURITY DEFINER escape hatches. Any cross-tenant read on this
-- table must go through a named code-reviewed function; no bare reads in app code.
--
-- Cleanup predicate: states older than 1 hour are dead (expired TTL or consumed)
-- and are safe to purge by a maintenance sweep. The 10-minute TTL is enforced
-- in application code at consume time; 1-hour is the maximum DB retention window.

CREATE TABLE shopify_oauth_state (
    nonce        text        PRIMARY KEY,
    tenant_id    uuid        NULL,     -- NULL for Path-2 (pre-tenant merchant install)
    shop_domain  text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    consumed_at  timestamptz NULL
);

COMMENT ON TABLE shopify_oauth_state IS
    'Single-use OAuth state nonces. Intentionally NOT tenant-RLS-scoped — '
    'Path-2 states have no tenant_id until resolve-or-create on callback (Day 2). '
    'States older than 1 hour are eligible for cleanup (TTL expired or consumed).';

COMMENT ON COLUMN shopify_oauth_state.tenant_id IS
    'NULL for Path-2 (new merchant with no existing account). '
    'Set to the authenticated owner''s tenant_id for Path-1 (logged-in merchant connects).';

-- Index for periodic cleanup sweeps (TTL > 1h)
CREATE INDEX shopify_oauth_state_created_at_idx
    ON shopify_oauth_state (created_at);
