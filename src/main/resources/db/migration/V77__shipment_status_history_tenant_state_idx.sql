-- V77 — Overview trends (FR-Overview §2): index-only, no RLS policy change
-- needed (shipment_status_history has carried tenant_isolation since V40).
--
-- Backs the new GET /overview/trends "Delivered" series (shipment_status_
-- history.occurred_at WHERE internal_state='delivered') — a tenant-wide scan
-- across every shipment's history rows. The existing shipment_status_
-- history_shipment_idx (shipment_id, occurred_at) only serves a single
-- shipment's timeline lookup, not this tenant-wide aggregate.

CREATE INDEX shipment_status_history_tenant_state_idx
    ON shipment_status_history (tenant_id, internal_state, occurred_at);
