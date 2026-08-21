-- V76 — Overview trends (FR-Overview §2): index-only, no RLS policy change
-- needed (shipments has carried tenant_isolation since V1).
--
-- Backs the new GET /overview/trends "Shipments" series (first linked/
-- dispatched that day = shipments.created_at, forward leg). shipments only
-- had (tenant_id) and (order_id) indexes before this — neither serves a
-- tenant-wide created_at range scan.

CREATE INDEX shipments_tenant_created_at_idx ON shipments (tenant_id, created_at);
