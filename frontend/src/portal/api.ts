/**
 * Returns portal — the only network code the customer portal ships. Three public endpoints,
 * no cookies (credentials: 'omit'), no token refresh. The lookup token is sent as a Bearer
 * header on submit only. Every response is classified into a small outcome set; a body that
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
}

export interface LookupLine {
  variantId: string
  productTitle: string
  variantTitle: string | null
  imageUrl: string | null
  deliveredQuantity: number
  returnableQuantity: number
  nonReturnable: boolean
}

export interface PickupDistrict {
  id: string
  name: string
  nameAr: string | null
  zoneName: string | null
  zoneNameAr: string | null
}

/** Offered only when the store books Bosta pickups and the delivery city is known. */
export interface PickupOffer {
  cityId: string
  cityName: string
  cityNameAr: string | null
  districts: PickupDistrict[]
  preselectedDistrictId: string | null
}

export interface LookupResult {
  token: string
  orderNumber: string
  deliveredAt: string
  lines: LookupLine[]
  pickup: PickupOffer | null
}

export interface SubmitLine {
  variantId: string
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

export async function submit(
  slug: string,
  token: string,
  request: { lines: SubmitLine[]; email?: string; note?: string; districtId?: string },
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
