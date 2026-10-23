-- Flexible labeling slice 4 — packing labels are looked up by order (Pick & Pack line status, the
-- "Labels for this order" counter, reuse on reprint and the label PDF in order sequence).
-- label_codes is empty in production (0 rows, checked before V181), so a plain index is fine — no CONCURRENTLY.
CREATE INDEX ix_label_codes_order ON label_codes (tenant_id, order_id, order_item_id) WHERE order_id IS NOT NULL;
