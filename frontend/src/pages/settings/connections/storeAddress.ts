// "Find your store" — frontend PREVIEW of ShopDomainNormalizer (backend), for instant "recognised as …"
// feedback while typing. The backend is the source of truth: POST /shopify/resolve-store and
// /shopify/oauth/initiate normalise and validate again. Keep the two in step.

export type StoreSource = 'myshopify' | 'admin_link'
export interface RecognisedStore { shopDomain: string; source: StoreSource }

const HANDLE = '[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?'
const MYSHOPIFY_HOST = new RegExp(`^(${HANDLE})\\.myshopify\\.com$`)
const ADMIN_STORE_PATH = new RegExp(`^/store/(${HANDLE})(?:[/?#].*)?$`)
// Format characters (zero-width, bidi marks, BOM, soft hyphen) and every kind of space.
const INVISIBLE_OR_SPACE = /[\p{Cf}\p{Z}\s­]/gu

export function recogniseStoreAddress(input: string): RecognisedStore | null {
  let s = input.replace(INVISIBLE_OR_SPACE, '').toLowerCase().replace(/^[a-z][a-z0-9+.-]*:\/\//, '')
  if (!s) return null
  const cut = s.search(/[/?#]/)
  let host = cut < 0 ? s : s.slice(0, cut)
  const rest = cut < 0 ? '' : s.slice(cut)
  if (host.includes('@') || host.includes(':')) return null
  if (host.endsWith('.')) host = host.slice(0, -1)
  const m = MYSHOPIFY_HOST.exec(host)
  if (m) {
    const admin = rest === '/admin' || rest.startsWith('/admin/') || rest.startsWith('/admin?')
    return { shopDomain: `${m[1]}.myshopify.com`, source: admin ? 'admin_link' : 'myshopify' }
  }
  if (host === 'admin.shopify.com') {
    const p = ADMIN_STORE_PATH.exec(rest)
    if (p) return { shopDomain: `${p[1]}.myshopify.com`, source: 'admin_link' }
  }
  return null
}

/** Typed something domain-like (another website) rather than free text — picks the error wording. */
export function looksLikeDomain(input: string): boolean {
  const s = input.replace(INVISIBLE_OR_SPACE, '').toLowerCase().replace(/^[a-z][a-z0-9+.-]*:\/\//, '')
  return /^[a-z0-9-]+(\.[a-z0-9-]+)+([/?#].*)?$/.test(s)
}
