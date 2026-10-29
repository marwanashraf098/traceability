-- Flexible labeling rollout (2026-10-29): on for every tenant, existing and new. New tenants get it from the
-- column default (signup, embedded onboarding and ops inserts never name the column). The switch stays a
-- per-tenant, admin-only kill switch — no API path writes it; ops turns a tenant off with a direct UPDATE.
-- A tenant with no untagged stock sees today's flows: "Print & stick" pre-selected, piece labels at 0.
-- Constant default: metadata-only in Postgres 11+; the UPDATE touches one small table.
ALTER TABLE tenants ALTER COLUMN flexible_labeling_enabled SET DEFAULT true;
UPDATE tenants SET flexible_labeling_enabled = true WHERE flexible_labeling_enabled = false;
