-- V69 — add products.image_url: the Shopify featured-image CDN URL, metadata only.
--
-- One image per PRODUCT (first/featured), not per variant. We store Shopify's CDN URL
-- verbatim — never download/re-host — and the frontend requests thumbnails via Shopify's
-- native `?width=` query param. Nullable: products may have no image.
--
-- Purely additive column on an already RLS-covered table (products has had the
-- tenant_isolation policy since V1) — no new policy needed here.
--
-- Existing rows start NULL; a one-time tenant-scoped backfill job (ProductImageBackfillJob)
-- fetches and fills image_url for already-synced products via the Shopify API. New/updated
-- products capture it going forward at the two existing sync points (GraphQL catalog import
-- and the products/create|update webhook) — see ShopifySyncService.

ALTER TABLE products ADD COLUMN image_url text;
