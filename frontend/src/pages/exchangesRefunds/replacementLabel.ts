import type { ReturnRequestItem } from '../../api'

/**
 * The replacement an exchange sends out, always named in full: "Linen Shirt · White / L" (2026-11-13).
 * A replacement from ANOTHER product (F3 / Lookup) carries replacementProductTitle; a same-product swap
 * doesn't, and is named with the returned item's own product. Shopify's "Default Title" is never shown.
 */
export function replacementProduct(item: Pick<ReturnRequestItem, 'productTitle' | 'replacementProductTitle'>): string {
  return item.replacementProductTitle ?? item.productTitle
}

export function replacementLabel(
  item: Pick<ReturnRequestItem, 'productTitle' | 'replacementProductTitle' | 'replacementVariantTitle'>,
): string {
  const product = replacementProduct(item)
  const variant = item.replacementVariantTitle
  return variant && variant !== 'Default Title' ? `${product} · ${variant}` : product
}

/** The image to show beside the replacement: its own product's image, else (same product) the returned item's. */
export function replacementImage(
  item: Pick<ReturnRequestItem, 'imageUrl' | 'replacementProductTitle' | 'replacementImageUrl'>,
): string | null {
  return item.replacementProductTitle != null ? item.replacementImageUrl ?? null : item.imageUrl
}
