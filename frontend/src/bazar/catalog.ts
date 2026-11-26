import { useCallback, useEffect, useState } from 'react'
import { authFetch } from '../api'
import { BazarError, bazarGet } from './api'
import { variantName } from './format'

// Bazar POS B7a / B7b — selling without barcodes: the event's catalog (products by #number with sizes, bazar prices
// and what's left), the short-number rule, tapped-size refs, and the A4 price sheet.

export interface BazarCatalogSize {
  variant_id: string
  title: string | null
  /** The size option value; null when the product has none (a scarf in one size). */
  size: string | null
  price: string | number | null
  left: number
  /** B7d send stock only: online orders' committed demand for this size (as Inventory shows it). */
  committed?: number
  /** U1b (event catalog): of `left`, the pieces with no label. A tap sells one of these first. U1c (send stock): the
   *  only pieces a send by count takes. */
  left_unlabelled?: number
}

export interface BazarCatalogProduct {
  number: number | null
  product_id: string
  title: string
  colour: string | null
  image_url: string | null
  category: string | null
  left: number
  price_min: string | number | null
  price_max: string | number | null
  sizes: BazarCatalogSize[]
}

export interface BazarCatalog {
  event_id: string
  event_name: string
  event_status: 'draft' | 'live' | 'ended'
  categories: { name: string; count: number }[]
  products: BazarCatalogProduct[]
}

/** B7d — what the main warehouse can send (labelled and untagged), the catalog's shape; `left` = at Main. */
export function getBazarSendStock(eventId: string) {
  return bazarGet<BazarCatalog>(`/bazar-events/${encodeURIComponent(eventId)}/send-stock`)
}

/** B7d-2 — the event's leftovers without a barcode still out, the catalog's shape; `left` = still out. */
export function getBazarBringBackStock(eventId: string) {
  return bazarGet<BazarCatalog>(`/bazar-events/${encodeURIComponent(eventId)}/bring-back-stock`)
}

export function getBazarCatalog(eventId: string) {
  return bazarGet<BazarCatalog>(`/bazar-events/${encodeURIComponent(eventId)}/catalog`)
}

/** The catalog of this event, reloaded on demand (after a sale, so "N left" stays right). */
export function useBazarCatalog(eventId: string) {
  const [catalog, setCatalog] = useState<BazarCatalog | null>(null)
  const [failed, setFailed] = useState(false)
  const reload = useCallback(() => getBazarCatalog(eventId)
    .then(c => { setCatalog(c); setFailed(false) })
    .catch(() => setFailed(true)), [eventId])
  useEffect(() => { reload() }, [reload])
  return { catalog, failed, reload }
}

/**
 * The price sheet's product number in what was scanned or typed — "12" or "#12", Arabic / Persian digits folded,
 * invisibles and spaces dropped — or null. 1–3 digits only: never a piece code (P + 6 or more digits, ULID) or a
 * tracking number. Mirrors ScanInputNormalizer.productNumber (shared vectors: the productNumber key of
 * shared/scan-normalization-vectors.json).
 */
export function productNumber(raw: string): number | null {
  const s = raw
    .replace(/[\p{Cf}\p{Cc}]/gu, '')
    .replace(/[\s\p{Zs}]/gu, '')
    .replace(/[٠-٩]/g, d => String(d.charCodeAt(0) - 0x0660))
    .replace(/[۰-۹]/g, d => String(d.charCodeAt(0) - 0x06f0))
  const m = /^#?([0-9]{1,3})$/.exec(s)
  return m ? Number(m[1]) : null
}

// A tapped size travels the scan path as a ref: "variant:<id>:<nonce>" — the nonce keeps two taps of one size two
// sales (the quick-sale queue drops a ref already in flight, as it does a piece code being sold).
const TAP = 'variant:'
let nonce = 0

export function tapRef(variantId: string): string {
  nonce += 1
  return `${TAP}${variantId}:${Date.now().toString(36)}${nonce}`
}

/** The variant of a tapped ref, else null (a scanned code). */
export function tappedVariant(ref: string): string | null {
  if (!ref.startsWith(TAP)) return null
  const rest = ref.slice(TAP.length)
  const i = rest.indexOf(':')
  return i < 0 ? rest : rest.slice(0, i)
}

/** "White / M", "M", "Grey" — what a size button / a tapped line is called ("" for a product without sizes). */
export function sizeName(p: BazarCatalogProduct, s: BazarCatalogSize): string {
  return [p.colour, s.size].filter(Boolean).join(' / ') || variantName(s.title) || ''
}

/** "Linen shirt · White / M", or just "Mug" when the product has no sizes. */
export function productSizeName(p: BazarCatalogProduct, s: BazarCatalogSize): string {
  const n = sizeName(p, s)
  return n ? `${p.title} · ${n}` : p.title
}

/**
 * U1b: only LABELLED pieces of this size are left at the bazar — a tap would let Traced choose which labelled piece
 * is sold (and the one in the customer's hand would then not sell), so the screen asks for a scan of it instead.
 */
export function needsScan(s: BazarCatalogSize): boolean {
  return s.left > 0 && s.left_unlabelled === 0
}

/** Search: name, colour or "#12" / "12" (the number matches exactly). */
export function matchesQuery(p: BazarCatalogProduct, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  const n = productNumber(q)
  if (n !== null) return p.number === n
  return p.title.toLowerCase().includes(q) || (p.colour ?? '').toLowerCase().includes(q)
}

/** A tapped variant's product + size from the catalog (for the cart line / the sending card). */
export function findVariant(catalog: BazarCatalog | null, variantId: string) {
  for (const p of catalog?.products ?? []) {
    const s = p.sizes.find(x => x.variant_id === variantId)
    if (s) return { product: p, size: s }
  }
  return null
}

/**
 * The A4 price sheet (any seller of the event): on a phone, shared as a PDF file (Web Share API); otherwise — or when
 * sharing a file isn't supported — downloaded.
 */
export async function getPriceSheet(eventId: string, lang: string, share: boolean): Promise<'shared' | 'downloaded'> {
  const res = await authFetch(`/bazar-events/${encodeURIComponent(eventId)}/price-sheet.pdf?lang=${lang === 'ar' ? 'ar' : 'en'}`)
  if (!res.ok) throw new BazarError(res.status, { code: `HTTP_${res.status}` })
  const blob = await res.blob()
  const name = 'bazar-price-sheet.pdf'
  if (share && typeof navigator !== 'undefined' && typeof navigator.share === 'function'
      && typeof File !== 'undefined') {
    const file = new File([blob], name, { type: 'application/pdf' })
    if (!navigator.canShare || navigator.canShare({ files: [file] })) {
      try {
        await navigator.share({ files: [file], title: name })
        return 'shared'
      } catch (e) {
        if ((e as Error)?.name === 'AbortError') return 'shared'   // the seller closed the share sheet
      }
    }
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
  return 'downloaded'
}
