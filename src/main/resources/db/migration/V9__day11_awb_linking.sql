-- ============================================================
-- V9 — Day 11: Mode B fulfillment linking (AWB scan at pack)
-- ============================================================

-- Fast lookup for delivery-to-order matching by businessReference.
-- The Bosta plugin sets businessReference to the Shopify order number when
-- creating the delivery. This partial index is used in tryMatchDelivery()
-- to resolve unlinked deliveries to their orders without a full table scan.
CREATE INDEX unlinked_bosta_business_ref_idx
    ON unlinked_bosta_deliveries (tenant_id, business_reference)
    WHERE resolved = false AND business_reference IS NOT NULL;
