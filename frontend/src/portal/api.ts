/**
 * Returns portal — the only network code the customer portal ships. Four public endpoints,
 * no cookies (credentials: 'omit'), no token refresh. The lookup token is sent as a Bearer
 * header on submit and on the districts list (V117). Every response is classified into a small outcome set; a body that
 * isn't JSON (e.g. nginx's own error page) never throws.
 */

export interface PortalConfig {
  storeName: string
  returnWindowDays: number
  reasonCodes: string[]
  logoUrl: string | null
  brandColor: string | null
  policyText: string | null
  autoApprove: boolean
  pickupBooking: boolean
  /** Step 5b — present (true) only when the store offers size/colour exchanges. */
  exchangesEnabled?: boolean
  /** P1 — the store's portal font (both languages); absent from older backends → the default. */
  font?: string
}

/** Step 5b — one axis of a product's options (colour, size, …). */
export interface OptionAxis {
  name: string | null
  kind: 'colour' | 'size' | 'option'
}

/** Step 5b — another variant of the same product a line could be exchanged for. */
export interface ExchangeOption {
  variantId: string
  title: string
  options: string[]
  inStock: boolean
}

export interface LookupLine {
  variantId: string
  /** Step 6a — present only on a line Traced didn't track: submit it by orderItemId. */
  orderItemId?: string
  tracked?: false
  productTitle: string
  variantTitle: string | null
  imageUrl: string | null
  deliveredQuantity: number
  returnableQuantity: number
  nonReturnable: boolean
  /** Step 5b — only when the store offers exchanges. */
  optionAxes?: OptionAxis[]
  currentOptions?: string[]
  exchangeOptions?: ExchangeOption[]
}

export interface PickupDistrict {
  id: string
  name: string
  nameAr: string | null
  zoneName: string | null
  zoneNameAr: string | null
}

/** V117 — a Bosta city (governorate) with at least one pickup-available area. */
export interface PickupCity {
  id: string
  name: string
  nameAr: string | null
}

/** Offered only when the store books Bosta pickups and the delivery city is known. */
export interface PickupOffer {
  cityId: string
  cityName: string
  cityNameAr: string | null
  districts: PickupDistrict[]
  preselectedDistrictId: string | null
  /** Step 5c — present when the store allows exchanges: the districts Bosta both delivers to and collects from. */
  exchangeDistrictIds?: string[]
  /** V117 — the cities a customer can choose for a different pickup address. */
  cities?: PickupCity[]
}

/** V117 — a different pickup address typed by the customer (never shown back by the portal). */
export interface CustomAddressRequest {
  addressSource: 'custom'
  cityId: string
  districtId: string
  firstLine: string
  secondLine?: string
  buildingNumber?: string
  floor?: string
  apartment?: string
}

export interface LookupResult {
  token: string
  orderNumber: string
  deliveredAt: string
  lines: LookupLine[]
  pickup: PickupOffer | null
}

/** A tracked line by variantId, or (Step 6a) an untracked line by orderItemId. */
export interface SubmitLine {
  variantId?: string
  orderItemId?: string
  quantity: number
  reasonCode: string
}

export interface SubmitResult {
  reference: string
  status: 'requested' | 'approved'
}

export type Failure = 'notFound' | 'throttled' | 'unauthorized' | 'invalid' | 'conflict' | 'error'

export type Outcome<T> = { ok: true; data: T } | { ok: false; failure: Failure; status: number }

const BASE = '/api/v1/portal'

async function call(path: string, init: RequestInit): Promise<{ status: number; body: unknown }> {
  const res = await fetch(BASE + path, { ...init, credentials: 'omit', cache: 'no-store' })
  const type = res.headers.get('content-type') ?? ''
  let body: unknown = null
  if (type.includes('application/json')) {
    try { body = await res.json() } catch { body = null }
  }
  return { status: res.status, body }
}

function fail<T>(failure: Failure, status: number): Outcome<T> {
  return { ok: false, failure, status }
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null
}

export async function getConfig(slug: string): Promise<Outcome<PortalConfig>> {
  try {
    const { status, body } = await call(`/${encodeURIComponent(slug)}/config`, {
      headers: { Accept: 'application/json' },
    })
    if (status === 200 && isObject(body) && typeof body.storeName === 'string') return { ok: true, data: body as unknown as PortalConfig }
    if (status === 404) return fail('notFound', status)
    if (status === 429) return fail('throttled', status)
    return fail('error', status)
  } catch {
    return fail('error', 0)
  }
}

export async function lookup(slug: string, orderNumber: string, phone: string): Promise<Outcome<LookupResult>> {
  try {
    const { status, body } = await call(`/${encodeURIComponent(slug)}/lookup`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ orderNumber, phone }),
    })
    if (status === 200 && isObject(body) && typeof body.token === 'string' && Array.isArray(body.lines)) {
      return { ok: true, data: body as unknown as LookupResult }
    }
    if (status === 404) return fail('notFound', status)
    // 429 from the app (JSON) or from nginx's rate limit (JSON or HTML) — same screen.
    if (status === 429) return fail('throttled', status)
    return fail('error', status)
  } catch {
    return fail('error', 0)
  }
}

/**
 * V117 — a city's areas for a different pickup address (the lookup token as a Bearer header).
 * mode 'exchange' keeps only areas Bosta can both deliver to and collect from.
 */
export async function getDistricts(
  slug: string, token: string, cityId: string, exchange: boolean,
): Promise<Outcome<{ districts: PickupDistrict[] }>> {
  try {
    const q = `cityId=${encodeURIComponent(cityId)}${exchange ? '&mode=exchange' : ''}`
    const { status, body } = await call(`/${encodeURIComponent(slug)}/districts?${q}`, {
      headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
    })
    if (status === 200 && isObject(body) && Array.isArray(body.districts)) {
      return { ok: true, data: { districts: body.districts as PickupDistrict[] } }
    }
    if (status === 401) return fail('unauthorized', status)
    if (status === 404) return fail('notFound', status)
    if (status === 429) return fail('throttled', status)
    return fail('error', status)
  } catch {
    return fail('error', 0)
  }
}

export async function submit(
  slug: string,
  token: string,
  request: {
    lines: SubmitLine[]; email?: string; note?: string; districtId?: string
    mode?: 'refund' | 'exchange'; replacementVariantId?: string; refundFallbackOk?: boolean
  } & (Partial<CustomAddressRequest>),
): Promise<Outcome<SubmitResult>> {
  try {
    const { status, body } = await call(`/${encodeURIComponent(slug)}/requests`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(request),
    })
    if (status === 201 && isObject(body) && typeof body.reference === 'string') {
      return { ok: true, data: body as unknown as SubmitResult }
    }
    if (status === 401) return fail('unauthorized', status)
    if (status === 409) return fail('conflict', status)
    if (status === 400) return fail('invalid', status)
    if (status === 429) return fail('throttled', status)
    return fail('error', status)
  } catch {
    return fail('error', 0)
  }
}
