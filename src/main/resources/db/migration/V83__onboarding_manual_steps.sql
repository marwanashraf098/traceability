-- V83 — onboarding manual-override steps.
--
-- Lets an owner/manager manually check off an onboarding step whose auto-signal
-- doesn't cover it, or override one once they're confident it's actually done.
-- Stored as a JSON array of step keys, e.g. ["location","test_label"]. tenants
-- already carries the tenant_isolation RLS policy (V1) — purely additive column
-- on an already-covered table, no new policy needed.
ALTER TABLE tenants
    ADD COLUMN onboarding_manual_steps jsonb NOT NULL DEFAULT '[]'::jsonb;
