-- V150 — returns restock → Shopify (2026-10-08 diagnosis, Issue 3).
--
-- 1. Two new claim statuses for the restock (+1, trigger 'return_inspection') path — both are
--    recorded instead of the old silent, row-less skip:
--      skipped_not_fulfillment_location — the restock location isn't the Traced Main Warehouse,
--                                         so nothing was sent (logged at WARN too).
--      skipped_shopify_restocked        — the merchant already restocked this unit through a
--                                         Shopify refund (restock_type return / legacy_restock);
--                                         sending +1 would count it twice.
-- 2. source_order_id — the order the restocked piece came back from (set only on restock claims).
--    The double-count guard and the restocked_twice detector count a restock against its order
--    with it; it survives every reclaim (the claim's ON CONFLICT never touches it).
-- 3. Restock trigger_id shape: '<piece_id>:<restock_event_id>' (one claim per restock, like
--    hold_enter), or the bare piece_id of a claim made before V150 / for a piece with no restock
--    event. Piece ids never contain ':'.
-- 4. shopify_refund_restocked_units(raw, variant_gid) — the ONE definition of "units of this variant
--    the merchant restocked through Shopify refunds on this order", read from the stored REST order
--    payload. Used by ShopifyInventoryService (guard), ExceptionService (restocked_twice) and the
--    repair script. IMMUTABLE, INVOKER, total (a malformed payload counts 0, never errors).

ALTER TABLE shopify_inventory_adjustments DROP CONSTRAINT shopify_inventory_adjustments_status_check;
ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_status_check
    CHECK (status IN ('shadow', 'pending', 'applied', 'failed', 'skipped', 'superseded_by_seed',
                      'skipped_shopify_restocked', 'skipped_not_fulfillment_location'));

ALTER TABLE shopify_inventory_adjustments ADD COLUMN source_order_id uuid REFERENCES orders(id) ON DELETE SET NULL;

ALTER TABLE shopify_inventory_adjustments ADD CONSTRAINT shopify_inventory_adjustments_restock_trigger_id_check
    CHECK (trigger_type <> 'return_inspection'
           OR trigger_id ~ '^[^:]+(:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$');

-- Only restock claims carry an order; the guard counts per (tenant, order, variant).
CREATE INDEX ix_sia_restock_order_variant
    ON shopify_inventory_adjustments (tenant_id, source_order_id, variant_id)
    WHERE trigger_type = 'return_inspection' AND source_order_id IS NOT NULL;

CREATE OR REPLACE FUNCTION shopify_refund_restocked_units(raw jsonb, variant_gid text)
RETURNS bigint
LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT COALESCE(SUM(
               CASE WHEN rli->>'quantity' ~ '^[0-9]+$' THEN (rli->>'quantity')::bigint ELSE 0 END), 0)
    FROM jsonb_array_elements(
             CASE WHEN jsonb_typeof(raw->'refunds') = 'array' THEN raw->'refunds' ELSE '[]'::jsonb END) r,
         jsonb_array_elements(
             CASE WHEN jsonb_typeof(r->'refund_line_items') = 'array' THEN r->'refund_line_items' ELSE '[]'::jsonb END) rli
    WHERE rli->>'restock_type' IN ('return', 'legacy_restock')
      AND variant_gid IS NOT NULL
      AND 'gid://shopify/ProductVariant/' || (rli->'line_item'->>'variant_id') = variant_gid
$$;
