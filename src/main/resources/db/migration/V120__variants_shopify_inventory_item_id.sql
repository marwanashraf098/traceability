-- Shopify InventoryItem GID per variant (gid://shopify/InventoryItem/<n>), captured by the
-- catalog import (and the products webhook) so activation, increments, the stock-take push and
-- the seed stop resolving it one variant at a time. NULL = not captured yet: consumers resolve it
-- from Shopify on a miss and write it back. Column only — the variants RLS policy is unchanged.
ALTER TABLE variants ADD COLUMN shopify_inventory_item_id text NULL;
