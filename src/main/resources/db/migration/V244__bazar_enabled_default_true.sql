-- Bazar on by default (2026-11-26): every tenant has the bazar POS. Replaces V231's default false.
-- The UPDATE was already done by hand in prod (all tenants on); it stays so a fresh database matches.
ALTER TABLE tenants ALTER COLUMN bazar_enabled SET DEFAULT true;
UPDATE tenants SET bazar_enabled = true WHERE bazar_enabled = false;
