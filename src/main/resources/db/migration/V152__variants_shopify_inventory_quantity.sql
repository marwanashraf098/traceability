-- V152 — Analytics slice 4 (stock trust): the Shopify stock figure we already hold, as STORED
-- generated columns (the V149 rule: total functions, no read-time raw parsing).
--
-- variants.raw carries Shopify's REST variant `inventory_quantity` — available units summed over
-- ALL of the store's locations — when the variant last came through a products/* webhook. Variants
-- written only by the GraphQL import have no such field (NULL here: "no Shopify figure").
-- shopify_variant_updated_at is that payload's own updated_at: the figure is no fresher than it
-- (an inventory change alone doesn't always send a products/update).
ALTER TABLE variants
    ADD COLUMN shopify_inventory_quantity integer GENERATED ALWAYS AS (
        CASE WHEN jsonb_typeof(raw -> 'inventory_quantity') = 'number'
             THEN analytics_int(raw ->> 'inventory_quantity') END) STORED,
    ADD COLUMN shopify_variant_updated_at timestamptz GENERATED ALWAYS AS (
        CASE WHEN (raw ->> 'updated_at') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T'
             THEN analytics_ts(raw ->> 'updated_at') END) STORED;
