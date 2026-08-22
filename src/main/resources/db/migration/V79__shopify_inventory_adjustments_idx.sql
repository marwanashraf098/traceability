-- V79 — index-only, no RLS policy change needed (shopify_inventory_adjustments has
-- carried tenant_isolation since V48).
--
-- shopify_inventory_adjustments had NO index beyond its PK and the
-- UNIQUE(trigger_type, trigger_id, variant_id, location_id) claim-row constraint —
-- every tenant-scoped read against it has been a sequential scan since V48. That gap
-- predates this migration (GET /api/v1/shopify-inventory/adjustments has had it all
-- along) and now also affects three new Phase A Inventory query paths:
--   - GET /inventory/movements' adjustment arm — WHERE tenant_id = ? ORDER BY created_at DESC
--   - GET /inventory/stock's per-page shopifySync rollup — WHERE tenant_id = ? AND variant_id = ANY(?)
--   - GET /inventory/variants/{id}/breakdown's recent-movements list — WHERE tenant_id = ? AND variant_id = ?

CREATE INDEX shopify_inventory_adjustments_tenant_created_idx
    ON shopify_inventory_adjustments (tenant_id, created_at);

CREATE INDEX shopify_inventory_adjustments_tenant_variant_idx
    ON shopify_inventory_adjustments (tenant_id, variant_id);

-- stock_take_shopify_syncs deliberately left unindexed — one row per finalized
-- stock-take session (a manual, low-frequency operator action), correctly low-priority
-- relative to shopify_inventory_adjustments which grows with every receiving/return/
-- damage/seed event.
