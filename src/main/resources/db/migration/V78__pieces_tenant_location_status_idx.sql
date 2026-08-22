-- V78 — Phase A inventory backend: index-only, no RLS policy change needed
-- (pieces has carried tenant_isolation since V1).
--
-- Backs GET /inventory/stock's location-scoped on_hand query and GET /inventory/pieces'
-- status+location-filtered drill-down — both filter pieces by (tenant_id,
-- current_location_id, status) together. The existing pieces_tenant_variant_status
-- (tenant_id, variant_id, status) and pieces_tenant_status (tenant_id, status) indexes
-- don't cover current_location_id, so a location-scoped query would fall back to a
-- tenant-wide scan filtered in memory.

CREATE INDEX pieces_tenant_location_status_idx
    ON pieces (tenant_id, current_location_id, status);
