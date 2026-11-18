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

/** Money that moved (payments − refunds) and revenue (sales − returns); B3 adds returns / exchanges / revenue. */
export interface BazarTotals {
  by_method: Record<string, string | number>
  total: string | number
  sales: number
  returns?: number
  exchanges?: number
  revenue?: string | number
}

export interface BazarToday extends BazarTotals {
  business_day: string
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
  /** The whole event (B3). */
  totals?: BazarTotals
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
  status: 'active' | 'undone' | 'returned'
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
  /** B3: a returned sale (exchanged when exchanged_for_* is set), and the new half of an exchange. */
  returned_at?: string | null
  return_condition?: 'good' | 'damaged' | null
  returned_by_name?: string | null
  exchange_of_sale_id?: string | null
  exchange_of_sale_number?: string | null
  exchanged_for_sale_id?: string | null
  exchanged_for_sale_number?: string | null
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

/**
 * A field left out stays as it is; a field sent as null (or "") is cleared. A worker sees the phone masked, so they
 * send the name alone and the stored number is kept.
 */
export function setBazarCustomer(eventId: string, saleId: string,
                                 fields: { name?: string | null; phone?: string | null }) {
  const body: Record<string, string | null> = {}
  if ('name' in fields) body.name = fields.name ?? null
  if ('phone' in fields) body.phone = fields.phone ?? null
  return bazarFetch<BazarSale>(`${base(eventId)}/sales/${encodeURIComponent(saleId)}/customer`, {
    method: 'PUT', body: JSON.stringify(body),
  })
}

/** Today's numbers from the server (active sales only) — never summed from the paged sales list. */
export interface BazarTodaySummary {
  business_day: string
  total: string | number
  sale_count: number
  returns?: number
  exchanges?: number
  revenue?: string | number
  by_method: Record<string, string | number>
  per_seller: { seller_id: string; seller_name: string; sale_count: number; total: string | number }[]
}

// ── B3 — return / exchange (any seller role) ──────────────────────────────────

export type ReturnCondition = 'good' | 'damaged'

/** What a scanned piece is at this event — the Return / Exchange screens' preview (nothing is written). */
export interface BazarPieceLookup {
  piece_id: string
  piece_code: string | null
  product_title: string
  variant_title: string | null
  /** The bazar price; null when it has none. */
  price: string | number | null
  state: 'sold' | 'for_sale' | 'returned' | 'not_here'
  sale: BazarSale | null
  /** B4: the piece came back damaged at this bazar — it can't be sold (or given in an exchange). */
  damaged?: boolean
}

export function lookupBazarPiece(eventId: string, code: string) {
  return bazarFetch<BazarPieceLookup>(`${base(eventId)}/piece?${new URLSearchParams({ code })}`)
}

export interface BazarReturnResult {
  sale: BazarSale
  refund: { method: PaymentMethod; amount: string | number } | null
  replayed?: boolean
}

/** One attempt (same contract as sellOnce): retried by the caller with the SAME client_request_id. */
export function returnOnce(eventId: string, req: { code: string; condition: ReturnCondition; method: PaymentMethod },
                           clientRequestId: string, timeoutMs: number) {
  return bazarFetch<BazarReturnResult>(`${base(eventId)}/return`, {
    method: 'POST', timeoutMs, body: JSON.stringify({ ...req, client_request_id: clientRequestId }),
  })
}

export interface BazarExchangeResult {
  old_sale: BazarSale
  new_sale: BazarSale
  /** new − old: positive = the customer pays it, negative = they get it back. */
  difference: string | number
  money: { kind: 'payment' | 'refund'; method: PaymentMethod; amount: string | number } | null
  replayed?: boolean
}

export function exchangeOnce(eventId: string,
                             req: { oldCode: string; newCode: string; method: PaymentMethod | null; condition: ReturnCondition },
                             clientRequestId: string, timeoutMs: number) {
  return bazarFetch<BazarExchangeResult>(`${base(eventId)}/exchange`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({ old_code: req.oldCode, new_code: req.newCode, condition: req.condition,
                           ...(req.method ? { method: req.method } : {}), client_request_id: clientRequestId }),
  })
}

// ── B4 — close the day (owner / manager) ──────────────────────────────────────

export type BazarDayStatus = 'open' | 'closed_balanced' | 'closed_short' | 'changed_after_close'
type Money = string | number

/** One method's line of a close: expected vs entered; difference = entered − expected (negative = short). */
export interface BazarCloseMethodRow {
  method: PaymentMethod
  expected: Money
  entered: Money
  difference: Money
  state: 'even' | 'short' | 'over' | 'not_received'
  /** InstaPay only: ticked = received in full. */
  received?: boolean
}

export interface BazarCloseResult {
  opening_float: Money
  cash_kept: Money
  take_home: Money
  not_received_total: Money
  not_received: { cash: Money; card: Money; instapay: Money }
  over_total: Money
  methods: BazarCloseMethodRow[]
}

export interface BazarDay {
  business_day: string
  day_number: number
  is_today: boolean
  money: BazarTotals
  status: BazarDayStatus
  close: {
    id: string
    closed_at: string
    closed_by_name: string
    note: string | null
    supersedes_id: string | null
    result: BazarCloseResult
  } | null
  close_count: number
  /** previous_close: yesterday's cash kept; this_day_close: a re-close keeps the day's own float; none: ask it. */
  opening_float_default: { amount: Money | null; source: 'previous_close' | 'this_day_close' | 'none' }
  replayed?: boolean
}

export interface BazarDays {
  today: string
  payment_methods: PaymentMethod[]
  days: BazarDay[]
}

export interface BazarCloseEntry {
  cash: number | null
  card: number | null
  instapay: boolean
  kept: number | null
  openingFloat: number | null
}

export interface BazarClosePreview {
  business_day: string
  status: BazarDayStatus
  money: BazarTotals
  opening_float_source: 'entered' | 'previous_close' | 'this_day_close' | 'none'
  needs_opening_float: boolean
  result: BazarCloseResult
  problems: 'kept_more_than_counted'[]
}

const dayPath = (eventId: string, day: string) => `${base(eventId)}/days/${encodeURIComponent(day)}`

export function listBazarDays(eventId: string) {
  return bazarFetch<BazarDays>(`${base(eventId)}/days`)
}

export function previewBazarClose(eventId: string, day: string, e: BazarCloseEntry) {
  const q = new URLSearchParams({ instapay: String(e.instapay) })
  if (e.cash != null) q.set('cash', String(e.cash))
  if (e.card != null) q.set('card', String(e.card))
  if (e.kept != null) q.set('kept', String(e.kept))
  if (e.openingFloat != null) q.set('float', String(e.openingFloat))
  return bazarFetch<BazarClosePreview>(`${dayPath(eventId, day)}/close-preview?${q}`)
}

/** One attempt (same contract as sellOnce): retried by the caller with the SAME client_request_id. */
export function closeBazarDayOnce(eventId: string, day: string, e: BazarCloseEntry, note: string | null,
                                  clientRequestId: string, timeoutMs: number) {
  return bazarFetch<BazarDay>(`${dayPath(eventId, day)}/close`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({
      cash_counted: e.cash, card_settlement: e.card, instapay_received: e.instapay, cash_kept: e.kept,
      ...(e.openingFloat != null ? { opening_float: e.openingFloat } : {}),
      ...(note ? { note } : {}), client_request_id: clientRequestId,
    }),
  })
}

// ── B5 — end event, leftovers, report (owner / manager) ──────────────────────

export interface BazarLeftoverPiece {
  piece_id: string
  piece_code: string | null
  product_title: string
  variant_title: string | null
  sku: string | null
}

export interface BazarLeftovers {
  event_id: string
  event_status: BazarStatus
  transfer_id: string
  transfer_status: string
  counts: { sent: number; sold: number; back: number; damaged: number; damaged_at_bazar: number; lost: number;
            outstanding: number }
  /** sent = sold + back + damaged + lost — the transfer can close. */
  balanced: boolean
  outstanding: BazarLeftoverPiece[]
  damaged_to_bring_home: BazarLeftoverPiece[]
  replayed?: boolean
}

export interface BazarScanBackResult {
  result: 'back_good' | 'back_damaged'
  piece: { piece_id: string; piece_code: string | null; product_title: string; variant_title: string | null }
  leftovers: BazarLeftovers
  replayed: boolean
}

export function endBazarEvent(eventId: string) {
  return bazarFetch<BazarLeftovers>(`${base(eventId)}/end`, { method: 'POST', body: '{}' })
}

export function getBazarLeftovers(eventId: string) {
  return bazarFetch<BazarLeftovers>(`${base(eventId)}/leftovers`)
}

/** One attempt (same contract as sellOnce): retried by the caller with the SAME client_request_id. */
export function scanBackOnce(eventId: string, code: string, clientRequestId: string, timeoutMs: number) {
  return bazarFetch<BazarScanBackResult>(`${base(eventId)}/scan-back`, {
    method: 'POST', timeoutMs, body: JSON.stringify({ code, client_request_id: clientRequestId }),
  })
}

export function markBazarLost(eventId: string, pieceIds: string[]) {
  return bazarFetch<BazarLeftovers>(`${base(eventId)}/mark-lost`, {
    method: 'POST', body: JSON.stringify({ piece_ids: pieceIds }),
  })
}

export function closeBazarTransfer(eventId: string) {
  return bazarFetch<BazarLeftovers>(`${base(eventId)}/close-transfer`, { method: 'POST', body: '{}' })
}

type Money2 = string | number

export interface BazarReport {
  event: { id: string; name: string; status: BazarStatus; starts_on: string; ends_on: string; started_at: string | null;
           ended_at: string | null; location_name: string }
  revenue: Money2
  money_in: Money2
  pieces_sold: number
  pieces_sent: number
  /** 0..1, null when nothing was sent. */
  sell_through: Money2 | null
  average_sale: Money2 | null
  returns: number
  exchanges: number
  sales_by_day: { business_day: string; day_number: number; sales: number; revenue: Money2; money_in: Money2;
                  status: BazarDayStatus }[]
  busiest_hours: { hour: number; sales: number }[]
  top_products: { product_title: string; sold: number; sent: number; sell_through: Money2 | null; revenue: Money2 }[]
  top_sizes: { product_title: string; variant_title: string; sold: number; sent: number; sell_through: Money2 | null;
               revenue: Money2 }[]
  payment_split: { method: PaymentMethod; amount: Money2; share: Money2 | null }[]
  money_not_received: { cash_short: Money2; cash_over: Money2; card_short: Money2; card_over: Money2;
                        instapay_not_received: Money2; total: Money2; days_closed: number; days_not_closed: number }
  per_seller: { seller_name: string; pieces: number; total: Money2 }[]
  customers_saved: number
  transfer: BazarLeftovers['counts'] & { transfer_id: string; status: string; balanced: boolean }
}

export function getBazarReport(eventId: string) {
  return bazarFetch<BazarReport>(`${base(eventId)}/report`)
}

/** The customers CSV (owner / manager; audit-logged) — downloaded as a file. */
export async function downloadBazarCustomers(eventId: string, fileName: string) {
  const res = await authFetch(`${base(eventId)}/report/customers.csv`)
  if (!res.ok) throw new BazarError(res.status, { code: `HTTP_${res.status}` })
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export function getBazarToday(eventId: string) {
  return bazarFetch<BazarTodaySummary>(`${base(eventId)}/today`)
}

export function listBazarSales(eventId: string, limit = 50, cursor?: string | null) {
  const q = new URLSearchParams({ limit: String(limit) })
  if (cursor) q.set('cursor', cursor)
  return bazarFetch<BazarSalesPage>(`${base(eventId)}/sales?${q}`)
}

/** The newest sales of {@code businessDay} (one page) — the Sell screen's recent list, not its totals. */
export async function listRecentBazarSales(eventId: string, businessDay: string, limit = 20): Promise<BazarSale[]> {
  const page = await listBazarSales(eventId, limit)
  return page.items.filter(s => s.business_day === businessDay)
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
