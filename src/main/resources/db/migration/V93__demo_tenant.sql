-- ============================================================
-- V93 — Demo tenant flag + demo_leads (public lead-capture table)
-- ============================================================
-- FR-DEMO Day 1: backend spine for a single shared, lead-gated demo tenant
-- that a scheduled job (DemoSeeder.reseed(), see application code) resets
-- to a golden fixture on a schedule.
--
-- This migration only adds the flag column and the lead-capture table.
-- The demo tenant row itself, its owner, and its workers are NOT seeded
-- here — they are bootstrapped by DemoSeeder (application code), reusing
-- the same seam as real signup (AuthRepository.createTenantWithOwner +
-- UserService.create), not a hand-rolled second tenant-creation path.

ALTER TABLE tenants ADD COLUMN is_demo boolean NOT NULL DEFAULT false;

-- demo_leads is deliberately NOT tenant-scoped — marketing lead capture
-- happens on the public landing page, before any tenant or session exists.
-- Same shape as magic_link_tokens (V16) / shopify_oauth_state (V13): RLS is
-- meaningless pre-session (no app.current_tenant GUC can exist yet), so
-- instead of a policy that can never evaluate correctly, app_user is
-- grant-restricted to INSERT only. Lead review is an ops/admin task done
-- from a superuser connection outside the app — there is no in-app read
-- path for this table.
CREATE TABLE demo_leads (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name         text        NOT NULL,
    email        text        NOT NULL,
    phone        text        NOT NULL,
    ip           inet,
    source       text        NOT NULL DEFAULT 'landing_demo',
    consented_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- Explicit GRANT INSERT (V1's ALTER DEFAULT PRIVILEGES would already cover
-- this for any new table, but V86's audit of magic_link_tokens/
-- password_reset_codes showed that relying on the implicit default without
-- a same-migration REVOKE leaves the "DEFINER/admin-only" intent aspirational
-- rather than enforced — so both the GRANT and the REVOKE are explicit here).
GRANT INSERT ON demo_leads TO app_user;
REVOKE SELECT, UPDATE, DELETE ON demo_leads FROM app_user;

COMMENT ON TABLE demo_leads IS
    'Public demo lead capture (name/email/phone/consent). NOT under tenant '
    'RLS — pre-session surface, no tenant exists yet at capture time. '
    'app_user has INSERT only (enforced by the REVOKE above); every other '
    'access is an ops/admin connection outside the app.';

COMMENT ON COLUMN tenants.is_demo IS
    'True for exactly one shared, lead-gated demo tenant. Reset on a schedule '
    'by DemoSeeder.reseed() (BYPASSRLS). Guards throughout the app (email, '
    'Shopify writes, Bosta registration, exception notifications) must treat '
    'is_demo=true tenants as a no-op sink for real-world side effects.';
