-- V70 — add variants.unit_cost: per-variant landed cost in EGP, for the Overview
-- dashboard's inventory-value tile (FR-Overview D1).
--
-- Nullable — most variants start uncosted (day-one state for both pilots). Inventory
-- value is computed as SUM(available * unit_cost) over costed variants ONLY; the
-- frontend shows a "based on N of M variants costed" caveat, never a false "EGP 0"
-- when nothing is costed yet. This is a unit COST, not the existing sale price on
-- variants — never conflate the two in any query.
--
-- Purely additive column on an already RLS-covered table (variants has had the
-- tenant_isolation policy since V1) — no new policy needed here.

ALTER TABLE variants ADD COLUMN unit_cost numeric(12,2);
