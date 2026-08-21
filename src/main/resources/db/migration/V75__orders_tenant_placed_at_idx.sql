-- V75 — Overview trends (FR-Overview §2): index-only, no RLS policy change
-- needed (orders has carried tenant_isolation since V1).
--
-- Backs the new GET /overview/trends "Orders" series — a 14-Cairo-day range
-- scan over orders.placed_at per tenant. The only existing placed_at index,
-- orders_bosta_not_created_idx (tenant_id, placed_at DESC), is PARTIAL
-- (WHERE bosta_link_status = 'not_created') and cannot serve a general scan.

CREATE INDEX orders_tenant_placed_at_idx ON orders (tenant_id, placed_at);
