-- V72 — add tenants.onboarding_dismissed_at: manual dismiss for the Overview
-- dashboard's condensed onboarding card (FR-Overview D3).
--
-- Per-tenant (not per-user) — onboarding is a tenant-level setup checklist, matching
-- how GET /api/v1/onboarding/status already computes `allDone` tenant-wide, not
-- per-login. NULL = not dismissed. The card hides on EITHER this being set OR
-- allDone=true — two independent conditions, both derived on read.
--
-- Purely additive column on an already RLS-covered table (tenants has had the
-- tenant_isolation policy since V1) — no new policy needed here.

ALTER TABLE tenants ADD COLUMN onboarding_dismissed_at timestamptz;
