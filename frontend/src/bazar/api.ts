import { authFetch, getRoleFromToken, TransferScanResult } from '../api'
import { getAccessToken } from '../auth'

// Bazar POS (B1 + B2 endpoints, B6 screens). Every bazar endpoint answers its failures as
// {code, message_en, message_ar, details?} — the screens show message_en / message_ar AS-IS (BazarError), except the
// phone's sell errors, which have their own short copy (sellErrors.ts).

export type PaymentMethod = 'cash' | 'instapay' | 'card'
export type SellerRole = 'owner' | 'manager' | 'worker'
export type BazarStatus = 'draft' | 'live' | 'ended'

/** The order the method chips are shown in, whatever order the event stored them in. */
export const METHOD_ORDER: PaymentMethod[] = ['cash', 'instapay', 'card']

export function orderedMethods(methods: readonly string[] | null | undefined): PaymentMethod[] {
  const set = new Set(methods ?? [])
  return METHOD_ORDER.filter(m => set.has(m))
}

/** The method a quick sale is recorded with until the seller taps another chip: cash when the event takes it. */
export function defaultMethod(methods: readonly string[] | null | undefined): PaymentMethod {
  return orderedMethods(methods)[0] ?? 'cash'
}

export interface BazarEventRow {
  id: string
  name: string
  starts_on: string
  ends_on: string
  status: BazarStatus
  location_id: string
  location_name: string
  transfer_id: string
  transfer_status: string
  created_at: string
}

export interface BazarToday {
  business_day: string
  by_method: Record<string, string | number>
  total: string | number
  sales: number
}

export interface BazarEventDetail extends BazarEventRow {
  transfer_sent_at: string | null
  transfer_shopify_sync_mode: string | null
  payment_methods: PaymentMethod[]
  seller_roles: SellerRole[]
  discount_pct: string | number | null
  round_to: string | number | null
  started_at: string | null
  ended_at: string | null
  counts: { sent: number; at_bazar: number; sold: number; back: number; lost: number }
  today: BazarToday
}

export interface BazarPriceRow {
  variant_id: string
  sku: string | null
  variant_title: string | null
  product_title: string
  shopify_price: string | number | null
  price: string | number | null
  source: 'default' | 'bulk' | 'override' | null
}

export interface BazarSale {
  id: string
  sale_number: string
  sold_at: string
  business_day: string
  status: 'active' | 'undone'
  price: string | number
  price_source: string
  method: PaymentMethod | null
  piece_id: string
  piece_code: string | null
  variant_id: string
  product_title: string
  variant_title: string | null
  seller_id: string
  seller_name: string
  customer_name: string | null
  /** Masked (010*****678) for a worker — the server decides. */
  customer_phone: string | null
  undone_at: string | null
  replayed?: boolean
}

export interface BazarSalesPage {
  items: BazarSale[]
  next_cursor: string | null
}

/** A failure the server answered with its own code (never retried). */
export class BazarError extends Error {
  code: string
  status: number
  messageEn: string
  messageAr: string
  details: Record<string, unknown> | null
  constructor(status: number, body: { code: string; message_en?: string; message_ar?: string; details?: unknown }) {
    super(body.code)
    this.code = body.code
    this.status = status
    this.messageEn = body.message_en ?? body.code
    this.messageAr = body.message_ar ?? body.message_en ?? body.code
    this.details = body.details && typeof body.details === 'object' && !Array.isArray(body.details)
      ? body.details as Record<string, unknown> : null
  }
  message_(lang: string) { return lang === 'ar' ? this.messageAr : this.messageEn }
}

/**
 * The request never got a definite answer — no response, a timeout, or a 5xx. For a sale that means "maybe it went
 * through": the phone retries with the SAME client_request_id until the server answers.
 */
export class BazarNetworkError extends Error {
  constructor(public reason: 'timeout' | 'network' | 'server') { super(reason) }
}

async function bazarFetch<T>(path: string, init: RequestInit & { timeoutMs?: number } = {}): Promise<T> {
  const { timeoutMs, ...rest } = init
  const ctrl = timeoutMs ? new AbortController() : null
  const timer = ctrl ? setTimeout(() => ctrl.abort(), timeoutMs) : undefined
  let res: Response
  try {
    res = await authFetch(path, {
      ...rest,
      ...(ctrl ? { signal: ctrl.signal } : {}),
      headers: { 'Content-Type': 'application/json', ...(rest.headers as Record<string, string> | undefined) },
    })
  } catch (e) {
    if ((e as Error)?.message === 'Unauthenticated') throw e
    throw new BazarNetworkError(ctrl?.signal.aborted ? 'timeout' : 'network')
  } finally {
    if (timer) clearTimeout(timer)
  }
  if (res.status >= 500) throw new BazarNetworkError('server')
  let body: unknown = null
  try {
    const ct = res.headers.get('content-type') ?? ''
    if (ct.includes('application/json')) body = await res.json()
  } catch {
    if (res.ok) throw new BazarNetworkError('network')
  }
  if (!res.ok) {
    const b = body as { code?: string } | null
    if (b?.code) throw new BazarError(res.status, b as { code: string })
    throw new BazarError(res.status, { code: `HTTP_${res.status}` })
  }
  return body as T
}

const base = (eventId: string) => `/bazar-events/${encodeURIComponent(eventId)}`

// ── B1 — events, send-out, prices, start (owner / manager) ───────────────────

export function listBazarEvents() {
  return bazarFetch<BazarEventRow[]>('/bazar-events')
}

export function getBazarEvent(eventId: string) {
  return bazarFetch<BazarEventDetail>(base(eventId))
}

export interface CreateBazarEvent {
  name: string
  startsOn: string
  endsOn: string
  locationId?: string | null
  locationName?: string | null
  paymentMethods: PaymentMethod[]
  sellerRoles: SellerRole[]
}

export function createBazarEvent(req: CreateBazarEvent) {
  return bazarFetch<BazarEventDetail>('/bazar-events', { method: 'POST', body: JSON.stringify(req) })
}

/** Like the transfer scan-out: 200 with success / code. A coded refusal (event ended, …) comes back the same way. */
export function bazarScanOut(eventId: string, barcode: string, relayEventId?: string): Promise<TransferScanResult> {
  return bazarFetch<TransferScanResult>(`${base(eventId)}/scan-out`, {
    method: 'POST', body: JSON.stringify(relayEventId ? { barcode, relayEventId } : { barcode }),
  }).catch(e => {
    if (!(e instanceof BazarError)) throw e
    return { success: false, code: e.code, message_en: e.messageEn, message_ar: e.messageAr, pieceId: null,
             barcode, variantId: null, lineId: null, qtyOut: 0 }
  })
}

export function markBazarSent(eventId: string) {
  return bazarFetch<BazarEventDetail>(`${base(eventId)}/mark-sent`, { method: 'POST', body: '{}' })
}

export function startBazarEvent(eventId: string) {
  return bazarFetch<BazarEventDetail>(`${base(eventId)}/start`, { method: 'POST', body: '{}' })
}

export function listBazarPrices(eventId: string) {
  return bazarFetch<BazarPriceRow[]>(`${base(eventId)}/prices`)
}

/** Both null clears the rule. */
export function setBazarBulkRule(eventId: string, discountPct: number | null, roundTo: number | null) {
  return bazarFetch<BazarPriceRow[]>(`${base(eventId)}/bulk-rule`, {
    method: 'PUT', body: JSON.stringify({ discountPct, roundTo }),
  })
}

export function setBazarPrice(eventId: string, variantId: string, price: number) {
  return bazarFetch<BazarPriceRow[]>(`${base(eventId)}/prices/${encodeURIComponent(variantId)}`, {
    method: 'PUT', body: JSON.stringify({ price }),
  })
}

export function clearBazarPrice(eventId: string, variantId: string) {
  return bazarFetch<BazarPriceRow[]>(`${base(eventId)}/prices/${encodeURIComponent(variantId)}/override`, {
    method: 'DELETE',
  })
}

// ── B2 — selling (any seller role) ────────────────────────────────────────────

/** One attempt. A definite server answer resolves / throws BazarError; anything else throws BazarNetworkError. */
export function sellOnce(eventId: string, code: string, clientRequestId: string, method: PaymentMethod,
                         timeoutMs: number) {
  return bazarFetch<BazarSale>(`${base(eventId)}/sell`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({ code, client_request_id: clientRequestId, method }),
  })
}

export function undoBazarSale(eventId: string, saleId: string) {
  return bazarFetch<BazarSale>(`${base(eventId)}/sales/${encodeURIComponent(saleId)}/undo`, {
    method: 'POST', body: '{}',
  })
}

export function changeBazarPayment(eventId: string, saleId: string, method: PaymentMethod) {
  return bazarFetch<BazarSale>(`${base(eventId)}/sales/${encodeURIComponent(saleId)}/payment`, {
    method: 'PUT', body: JSON.stringify({ method }),
  })
}

export function setBazarCustomer(eventId: string, saleId: string, name: string | null, phone: string | null) {
  return bazarFetch<BazarSale>(`${base(eventId)}/sales/${encodeURIComponent(saleId)}/customer`, {
    method: 'PUT', body: JSON.stringify({ name, phone }),
  })
}

export function listBazarSales(eventId: string, limit = 50, cursor?: string | null) {
  const q = new URLSearchParams({ limit: String(limit) })
  if (cursor) q.set('cursor', cursor)
  return bazarFetch<BazarSalesPage>(`${base(eventId)}/sales?${q}`)
}

/**
 * Every sale of {@code businessDay} (newest first): pages through the list (created_at DESC) until it reaches an
 * earlier day. Undone sales included — callers filter.
 */
export async function listBazarSalesOfDay(eventId: string, businessDay: string, maxPages = 10): Promise<BazarSale[]> {
  const out: BazarSale[] = []
  let cursor: string | null = null
  for (let i = 0; i < maxPages; i++) {
    const page: BazarSalesPage = await listBazarSales(eventId, 200, cursor)
    for (const s of page.items) {
      if (s.business_day < businessDay) return out
      if (s.business_day === businessDay) out.push(s)
    }
    if (!page.next_cursor) return out
    cursor = page.next_cursor
  }
  return out
}

// ── Who am I (from the access token) ──────────────────────────────────────────

export function currentUserId(): string | null {
  const t = getAccessToken()
  if (!t) return null
  try {
    const payload = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return typeof payload.sub === 'string' ? payload.sub : null
  } catch {
    return null
  }
}

export function managesBazar(): boolean {
  const r = getRoleFromToken()
  return r === 'owner' || r === 'manager'
}

/** A fresh idempotency key for one sale (the same key is re-sent on every retry of that sale). */
export function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return 'r-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12)
}

/** The seller's own undo window (B2 UNDO_WINDOW_SECONDS); owners / managers may undo any time while live. */
export const SELLER_UNDO_WINDOW_MS = 120_000
