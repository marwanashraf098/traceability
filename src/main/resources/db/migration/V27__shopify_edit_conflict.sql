-- FR-3.6: signal columns for Shopify line-item edit conflicts.
-- shopify_edit_conflict_at  — set when an orders/updated webhook changes line items on an
--                             in-progress order; drives ExceptionService detector.
-- shopify_edit_conflict_diff — JSON diff { removed, reduced, added, increased } for display
--                              in the exceptions center description.
-- Both columns are nullable; NULL means no pending edit conflict.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shopify_edit_conflict_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS shopify_edit_conflict_diff JSONB;
