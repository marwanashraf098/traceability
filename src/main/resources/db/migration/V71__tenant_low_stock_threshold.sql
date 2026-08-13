-- V71 — add tenants.low_stock_threshold: default variant-level low-stock cutoff,
-- for the Overview dashboard's needs-attention tile (FR-Overview D2).
--
-- A variant is "low stock" when its live available count < this threshold. One
-- tenant-wide default (5), no per-variant override this pass — matches the existing
-- pattern for tenant-level scalar settings living directly on `tenants` (see
-- default_language/timezone/pickup_address from V25, never_received_window_days /
-- stuck_shipment_days used by ExceptionService).
--
-- Purely additive column on an already RLS-covered table (tenants has had the
-- tenant_isolation policy since V1) — no new policy needed here.

ALTER TABLE tenants ADD COLUMN low_stock_threshold integer NOT NULL DEFAULT 5;
