-- ============================================================
-- V97 — FR-EXCHANGE outbound auto-commit: review marker
--
-- Additive NOT NULL DEFAULT false column on the existing `exchanges` table (V74) —
-- existing rows are unaffected (all backfill to false, a constant default on a
-- boolean column is a metadata-only change on PG11+, no table rewrite). No RLS
-- policy change: `exchanges` already has ENABLE/FORCE ROW LEVEL SECURITY + the
-- tenant_isolation policy from V74, and that policy is row-scoped (keyed on
-- tenant_id), so it already covers this new column with no additional grant or
-- policy needed — confirmed by RlsCoverageTest staying green with no edits here.
--
-- Set true only by ExchangeService's auto-commit path (ExchangeMatchService.
-- resolveOutboundVariant() classifying EXACT) — never by the human map() path, and
-- flipped back to false the moment a merchant overrides the auto-pick
-- (overrideOutboundVariant()), since at that point it reflects a human decision like
-- any other mapped exchange and no longer needs the "review me" marker.
-- ============================================================

ALTER TABLE exchanges ADD COLUMN auto_matched boolean NOT NULL DEFAULT false;
