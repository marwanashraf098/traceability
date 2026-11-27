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
  /** U1a: pieces still out on the transfer (neither sold, back, damaged nor lost). */
  pieces_out?: number
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

/** B8 — online stock per bazar: 'remove' = off online stock while at the bazar (default), 'leave' = kept showing online. */
export type OnlineStock = 'remove' | 'leave'

export interface BazarEventDetail extends BazarEventRow {
  transfer_sent_at: string | null
  transfer_shopify_sync_mode: string | null
  /** B8: the location's mode (the bazar's choice until Mark sent). */
  location_shopify_sync_mode?: OnlineStock
  /** B8: the send's snapshot once sent, else the location's. */
  online_stock?: OnlineStock
  /** B8: true once the event isn't a draft or its transfer isn't being prepared — the choice can't change. */
  online_stock_locked?: boolean
  /** U1d: false when Keep showing online isn't available (Main reconcile doesn't write for this store). */
  keep_available?: boolean
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
  /** U1b: a draft whose transfer is preparing and never had a piece scanned out — Delete is offered. */
  deletable?: boolean
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
  /** B6c: 'sale' (a quick sale or a cart line) — a cart in the list is a BazarReceipt. */
  kind?: 'sale'
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
  /** B6c: a cart line — its receipt, place in it, discount share and what it was paid (price − discount). */
  receipt_id?: string | null
  line_no?: number | null
  discount?: string | number
  net_price?: string | number
  replayed?: boolean
}

/** B6c — a completed cart: one B-number, N piece sales, one total, one discount, one payment. */
export interface BazarReceipt {
  kind: 'receipt'
  id: string
  sale_number: string
  sold_at: string
  business_day: string
  status: 'active' | 'undone'
  subtotal: string | number
  discount: string | number
  total: string | number
  /** = total, so a list row can show one amount for either kind. */
  price: string | number
  method: PaymentMethod | null
  seller_id: string
  seller_name: string
  customer_name: string | null
  customer_phone: string | null
  undone_at: string | null
  line_count: number
  lines: BazarSale[]
  replayed?: boolean
}

export type BazarSaleRow = BazarSale | BazarReceipt

export function isReceipt(r: BazarSaleRow): r is BazarReceipt {
  return r.kind === 'receipt'
}

export interface BazarSalesPage {
  items: BazarSaleRow[]
  next_cursor: string | null
}

/** A failure the server answered with its own code (never retried). */
/** The words for an error the server sent no message for — by status, never the code. */
export function fallbackMessage(status: number, lang: 'en' | 'ar'): string {
  if (status === 403) {
    return lang === 'ar' ? 'لا يمكنك القيام بهذا — اطلب من المالك أو المدير.'
      : "You can't do this — ask an owner or manager."
  }
  return lang === 'ar' ? 'حدث خطأ. حاول مرة أخرى.' : 'Something went wrong. Try again.'
}

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
    // B10: a body without a message (a bare 403 from a role check, a proxy error) never shows its code ("HTTP_403").
    this.messageEn = body.message_en ?? fallbackMessage(status, 'en')
    this.messageAr = body.message_ar ?? (body.message_en ? body.message_en : fallbackMessage(status, 'ar'))
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

/** A plain GET with the bazar error contract (B7 catalog). */
export function bazarGet<T>(path: string) {
  return bazarFetch<T>(path)
}

// B7a: a tapped size travels the scan path as "variant:<id>:<nonce>" (bazar/catalog.ts tapRef).
function tappedId(ref: string): string | null {
  if (!ref.startsWith('variant:')) return null
  const rest = ref.slice('variant:'.length)
  const i = rest.indexOf(':')
  return i < 0 ? rest : rest.slice(0, i)
}

/** Cart lines for the API: B6c's {codes} while every line is a scan, B7a's {lines} once one is a tapped size. */
function cartBody(codes: string[]): { codes: string[] } | { lines: ({ code: string } | { variant_id: string })[] } {
  if (!codes.some(c => tappedId(c) !== null)) return { codes }
  return { lines: codes.map(c => { const v = tappedId(c); return v ? { variant_id: v } : { code: c } }) }
}

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
  /** B8 — omitted = 'remove'. */
  shopifySyncMode?: OnlineStock
}

export function createBazarEvent(req: CreateBazarEvent) {
  return bazarFetch<BazarEventDetail>('/bazar-events', { method: 'POST', body: JSON.stringify(req) })
}

/** U1d — what the New bazar form may offer before any bazar exists: Keep showing online or not. */
export function getBazarOnlineStockOptions() {
  return bazarFetch<{ keep_available: boolean }>('/bazar-events/online-stock')
}

/** B8 — change the online stock choice; only while the event is a draft and nothing was sent (409 after). */
export function setBazarOnlineStock(eventId: string, mode: OnlineStock) {
  return bazarFetch<BazarEventDetail>(`${base(eventId)}/online-stock`, { method: 'PUT', body: JSON.stringify({ mode }) })
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

/** B7d — a count send / a removal from the send (TransferService.CountResult). */
export interface BazarCountResult {
  success: boolean
  code: string
  variantId: string | null
  count: number
  pieceIds: string[]
  qtyOut: number
  replayed: boolean
  /** U1b: the label codes of the removed pieces that carry one (what to put back). Never on a send by count (U1c). */
  labelled_codes?: string[]
  /** U1c: a send by count found fewer pieces without a label than asked (code SENT_PARTIAL) — what was asked, the
   *  labelled pieces of the size at Main, and the server's message. */
  requested?: number
  labelled_at_main?: number
  message_en?: string | null
  message_ar?: string | null
}

/** B7d — send `quantity` of a size; Traced picks the pieces at Main. One attempt; retried with the SAME request id. */
export function sendByCountOnce(eventId: string, variantId: string, quantity: number, clientRequestId: string,
                                timeoutMs: number) {
  return bazarFetch<BazarCountResult>(`${base(eventId)}/scan-out-by-count`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({ variant_id: variantId, quantity, client_request_id: clientRequestId }),
  })
}

/** B7d — take a scanned piece (`code`) or `quantity` of a size off the send, before Mark sent. Same retry contract. */
export function removeFromSendOnce(eventId: string, what: { code: string } | { variantId: string; quantity: number },
                                   clientRequestId: string, timeoutMs: number) {
  const body = 'code' in what
    ? { code: what.code, client_request_id: clientRequestId }
    : { variant_id: what.variantId, quantity: what.quantity, client_request_id: clientRequestId }
  return bazarFetch<BazarCountResult>(`${base(eventId)}/remove-from-send`, {
    method: 'POST', timeoutMs, body: JSON.stringify(body),
  })
}

/** U1b — delete a draft bazar nothing was ever scanned out for (204). */
export function deleteBazarEvent(eventId: string) {
  return bazarFetch<null>(base(eventId), { method: 'DELETE' })
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

/**
 * One attempt. A definite server answer resolves / throws BazarError; anything else throws BazarNetworkError.
 * {@code code}: a scanned code, or a B7a tapped-size ref (the server picks the piece).
 */
export function sellOnce(eventId: string, code: string, clientRequestId: string, method: PaymentMethod,
                         timeoutMs: number) {
  return bazarFetch<BazarSale>(`${base(eventId)}/sell`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({ ...(tappedId(code) ? { variant_id: tappedId(code) } : { code }),
                           client_request_id: clientRequestId, method }),
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
  /** Sales = receipts (a quick sale is its own); pieces = lines (B6c). */
  sale_count: number
  pieces?: number
  discounts?: string | number
  returns?: number
  exchanges?: number
  revenue?: string | number
  by_method: Record<string, string | number>
  per_seller: { seller_id: string; seller_name: string; sale_count: number; total: string | number }[]
}

// ── B6c — cart (any seller role) ──────────────────────────────────────────────

/** A scanned code as a cart line: the piece and its bazar price, or why it can't be sold (nothing is reserved). */
export interface BazarCartLine {
  index: number
  /** The scanned code; null on a B7a tapped-size line (its piece is picked at Complete). */
  code: string | null
  piece_id?: string
  piece_code?: string | null
  variant_id?: string
  product_title?: string
  variant_title?: string | null
  price?: string | number
  error: { code: string; message_en: string; message_ar: string; details?: Record<string, unknown> } | null
}

export interface BazarCartPreview {
  lines: BazarCartLine[]
  subtotal: string | number
  ok: boolean
}

export function previewBazarCart(eventId: string, codes: string[]) {
  return bazarFetch<BazarCartPreview>(`${base(eventId)}/cart/preview`, {
    method: 'POST', body: JSON.stringify(cartBody(codes)),
  })
}

export interface BazarCartRequest {
  codes: string[]
  discount: number
  method: PaymentMethod
  customer: { name: string | null; phone: string | null } | null
}

/** One attempt (useRetryingCommand re-sends the SAME client_request_id until the server answers). */
export function completeCartOnce(eventId: string, req: BazarCartRequest, clientRequestId: string, timeoutMs: number) {
  return bazarFetch<BazarReceipt>(`${base(eventId)}/cart/complete`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({ ...req, codes: undefined, ...cartBody(req.codes), client_request_id: clientRequestId }),
  })
}

export function undoBazarReceipt(eventId: string, receiptId: string) {
  return bazarFetch<BazarReceipt>(`${base(eventId)}/receipts/${encodeURIComponent(receiptId)}/undo`, {
    method: 'POST', body: '{}',
  })
}

export function changeBazarReceiptPayment(eventId: string, receiptId: string, method: PaymentMethod) {
  return bazarFetch<BazarReceipt>(`${base(eventId)}/receipts/${encodeURIComponent(receiptId)}/payment`, {
    method: 'PUT', body: JSON.stringify({ method }),
  })
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
export function returnOnce(eventId: string,
                           req: { code?: string; saleId?: string; condition: ReturnCondition; method: PaymentMethod },
                           clientRequestId: string, timeoutMs: number) {
  const { saleId, ...rest } = req
  // B7c: no label — the sale found by product + size goes as sale_id (a scan still sends its code).
  return bazarFetch<BazarReturnResult>(`${base(eventId)}/return`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({ ...rest, ...(saleId ? { sale_id: saleId } : {}), client_request_id: clientRequestId }),
  })
}

export interface BazarFoundSales {
  variant_id: string
  /** Every match; {@code sales} shows the newest 10. */
  count: number
  sales: BazarSale[]
}

/**
 * B7c: the active sales of a size at this event (newest first), optionally by the customer's phone or receipt.
 * A POST: the phone travels in the body, never in the URL (access logs).
 */
export function findBazarSales(eventId: string, variantId: string, filters: { phone?: string; receipt?: string } = {}) {
  const body: Record<string, string> = { variant_id: variantId }
  if (filters.phone?.trim()) body.phone = filters.phone.trim()
  if (filters.receipt?.trim()) body.receipt = filters.receipt.trim()
  return bazarFetch<BazarFoundSales>(`${base(eventId)}/sales/find`, { method: 'POST', body: JSON.stringify(body) })
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
                             req: { oldCode?: string; oldSaleId?: string; newCode?: string; newVariantId?: string;
                                    method: PaymentMethod | null; condition: ReturnCondition },
                             clientRequestId: string, timeoutMs: number) {
  // B7c: the piece coming back by its sale (no label) and / or the new piece as a tapped size.
  return bazarFetch<BazarExchangeResult>(`${base(eventId)}/exchange`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({
      ...(req.oldSaleId ? { old_sale_id: req.oldSaleId } : { old_code: req.oldCode }),
      ...(req.newVariantId ? { new_variant_id: req.newVariantId } : { new_code: req.newCode }),
      condition: req.condition, ...(req.method ? { method: req.method } : {}), client_request_id: clientRequestId }),
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
  /** U1a: open or changed after its close, with money — the day still needs closing. */
  needs_close?: boolean
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

/** B7d-2 — N leftovers of a size without a barcode back good. */
export interface BazarBringBackResult {
  result: 'back_good'
  count: number
  piece_ids: string[]
  variant_id: string
  leftovers: BazarLeftovers
  replayed: boolean
}

/** One attempt; retried by the caller with the SAME client_request_id. */
export function bringBackByCountOnce(eventId: string, variantId: string, quantity: number, clientRequestId: string,
                                     timeoutMs: number) {
  return bazarFetch<BazarBringBackResult>(`${base(eventId)}/bring-back-by-count`, {
    method: 'POST', timeoutMs,
    body: JSON.stringify({ variant_id: variantId, quantity, client_request_id: clientRequestId }),
  })
}

/** B7d-2 — a damaged leftover without a barcode brought home (scan-back's result shape). Same retry contract. */
export function bringHomeDamagedOnce(eventId: string, pieceId: string, clientRequestId: string, timeoutMs: number) {
  return bazarFetch<BazarScanBackResult>(`${base(eventId)}/bring-home-damaged`, {
    method: 'POST', timeoutMs, body: JSON.stringify({ piece_id: pieceId, client_request_id: clientRequestId }),
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
           ended_at: string | null; location_name: string; online_stock?: OnlineStock }
  revenue: Money2
  money_in: Money2
  pieces_sold: number
  pieces_sent: number
  /** 0..1, null when nothing was sent. */
  sell_through: Money2 | null
  average_sale: Money2 | null
  /** B6c: sales = receipts (a quick sale is its own); average_sale = revenue ÷ sales. */
  sales?: number
  /** B6c: cart discounts on the pieces still sold. */
  discounts?: Money2
  returns: number
  exchanges: number
  sales_by_day: { business_day: string; day_number: number; sales: number; revenue: Money2; money_in: Money2;
                  status: BazarDayStatus }[]
  /** U1b: kept sales only, every hour from the first sale's to the last one's (0 when quiet). */
  busiest_hours: { hour: number; sales: number; pieces?: number }[]
  /** U1b: products sent that sold nothing. */
  unsold_products?: { product_title: string; sold: number; sent: number }[]
  /** U1b: false when no product sent has sizes. */
  has_sizes?: boolean
  top_products: { product_title: string; sold: number; sent: number; sell_through: Money2 | null; revenue: Money2 }[]
  /** U1c: "What sold", one row per variant; variant_title only when the product has real variants (never
   *  "Default Title"). Absent on older servers — the card falls back to top_products. */
  top_items?: { product_title: string; variant_title?: string; sold: number; sent: number; sell_through: Money2 | null;
                revenue: Money2 }[]
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
  return downloadBazarFile(`${base(eventId)}/report/customers.csv`, fileName)
}

/** U1b — the orders CSV (every piece sold; audit-logged). */
export async function downloadBazarOrders(eventId: string, fileName: string) {
  return downloadBazarFile(`${base(eventId)}/report/orders.csv`, fileName)
}

/** U1b — the report PDF (page 1: money by day, by payment method, pieces sold per hour). */
export async function downloadBazarReportPdf(eventId: string, fileName: string, lang: string) {
  return downloadBazarFile(`${base(eventId)}/report.pdf?lang=${encodeURIComponent(lang)}`, fileName)
}

async function downloadBazarFile(path: string, fileName: string) {
  const res = await authFetch(path)
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
export async function listRecentBazarSales(eventId: string, businessDay: string, limit = 20): Promise<BazarSaleRow[]> {
  const page = await listBazarSales(eventId, limit)
  return page.items.filter(s => s.business_day === businessDay)
}

// ── B6b — sellers (owner / manager), the invited seller's first open, Switch seller ─────────────────

export interface BazarSeller {
  user_id: string
  name: string
  phone: string | null
  has_pin: boolean
  added_at: string
  last_invited_at?: string | null
  invite_expires_at?: string | null
  /** Remove turns the account off: "Add seller" created them and this is their last bazar that hasn't ended. */
  remove_turns_off_account?: boolean
}

export interface BazarSellers {
  event_name: string
  event_status: BazarStatus
  workers_allowed: boolean
  sellers: BazarSeller[]
}

export interface BazarSellerLink {
  link: string
  expires_at: string
  event_name: string
}

export function listBazarSellers(eventId: string) {
  return bazarFetch<BazarSellers>(`${base(eventId)}/sellers`)
}

export function listBazarSellerCandidates(eventId: string) {
  return bazarFetch<{ user_id: string; name: string; phone: string | null; has_pin: boolean }[]>(`${base(eventId)}/sellers/candidates`)
}

/** A new worker ({name, phone?}) or an existing one ({user_id}) — never a role. */
export function addBazarSeller(eventId: string, req: { name: string; phone?: string | null } | { user_id: string }) {
  return bazarFetch<BazarSellerLink & { seller: BazarSeller; workers_allowed: boolean }>(`${base(eventId)}/sellers`, {
    method: 'POST', body: JSON.stringify(req),
  })
}

export function newBazarSellerLink(eventId: string, userId: string) {
  return bazarFetch<BazarSellerLink>(`${base(eventId)}/sellers/${encodeURIComponent(userId)}/link`, { method: 'POST', body: '{}' })
}

export function removeBazarSeller(eventId: string, userId: string) {
  return bazarFetch<{ sessions_revoked: number }>(`${base(eventId)}/sellers/${encodeURIComponent(userId)}`, { method: 'DELETE' })
}

export function allowBazarWorkers(eventId: string) {
  return bazarFetch<{ workers_allowed: boolean }>(`${base(eventId)}/sellers/allow-workers`, { method: 'POST', body: '{}' })
}

export interface BazarSellerMe {
  event_id: string
  event_name: string
  event_status: BazarStatus
  name: string
  needs_pin: boolean
  removed: boolean
  can_sell: boolean
}

export function getBazarSellerMe(eventId: string) {
  return bazarFetch<BazarSellerMe>(`${base(eventId)}/sellers/me`)
}

export function setBazarSellerPin(pin: string) {
  return bazarFetch<{ pin_set: boolean }>('/bazar-sellers/me/pin', { method: 'POST', body: JSON.stringify({ pin }) })
}

export function getBazarSellerRoster(eventId: string) {
  return bazarFetch<{ id: string; name: string; locked: boolean; lockedUntil: string | null }[]>(`${base(eventId)}/sellers/roster`)
}

// ── B10 — opening a seller invite (no sign-in needed): the landing page's check, then "Start selling" ───────────

/** valid · used · expired · replaced (a newer link was sent) · removed · unknown · legacy (a link from before B10). */
export type SellerInviteStatus = 'valid' | 'used' | 'expired' | 'replaced' | 'removed' | 'unknown' | 'legacy'

export interface SellerInviteCheck {
  status: SellerInviteStatus
  store_name?: string
  event_name?: string
  seller_name?: string
  seller_id?: string
}

async function invitePost<T>(path: 'check' | 'redeem', t: string | null, token: string): Promise<T> {
  const access = getAccessToken()
  let res: Response
  try {
    res = await fetch(`/api/v1/auth/seller-invite/${path}`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(access ? { Authorization: `Bearer ${access}` } : {}) },
      body: JSON.stringify(t ? { t, token } : { token }),
    })
  } catch {
    throw new BazarNetworkError('network')
  }
  if (res.status >= 500) throw new BazarNetworkError('server')
  let body: unknown = null
  try { body = await res.json() } catch { /* empty */ }
  if (!res.ok) {
    const b = body as { code?: string } | null
    throw new BazarError(res.status, b?.code ? b as { code: string } : { code: `HTTP_${res.status}` })
  }
  return body as T
}

/** Reads only — what the link is and whose it is. */
export function checkSellerInvite(t: string | null, token: string) {
  return invitePost<SellerInviteCheck>('check', t, token)
}

/**
 * Uses the link up and signs this browser in as the seller (the refresh cookie is set; this browser's previous
 * session is ended). The caller stores the access token and opens /bazar/join/{event_id}.
 */
export function redeemSellerInvite(t: string | null, token: string) {
  return invitePost<{ access_token: string; event_id: string }>('redeem', t, token)
}

/** The refusal reason of a BAZAR_INVITE_REFUSED error, if it is one. */
export function inviteRefusal(e: unknown): string | null {
  if (!(e instanceof BazarError) || e.code !== 'BAZAR_INVITE_REFUSED') return null
  const r = e.details?.reason
  return typeof r === 'string' ? r : 'unknown'
}

/** wa.me with the invite text prefilled; an Egyptian mobile (01…) goes straight to that chat. */
export function whatsAppUrl(text: string, phone?: string | null): string {
  const digits = (phone ?? '').replace(/\D/g, '')
  const to = /^01\d{9}$/.test(digits) ? '2' + digits : /^201\d{9}$/.test(digits) ? digits : ''
  return `https://wa.me/${to}?text=${encodeURIComponent(text)}`
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

// ── B9b — guided bazar setup ("Set up from new stock") ───────────────────────
// Every /bazar-setup endpoint answers 404 unless bazar is on for the store (wizard entry, 2026-11-26 — before that it
// also needed BAZAR_SIGNUP_ENABLED and a bazar signup) — the screens then show nothing of it (getBazarSetup → null).

export interface BazarSetupBatch {
  eventId: string
  eventName: string | null
  eventStatus: BazarStatus | null
  labelledReceiptId: string | null
  countOnlyReceiptId: string | null
  pieces: number
  receivedAt: string | null
  lowered: boolean
  loweredAt: string | null
  /** B9c — the bazar's send: 'preparing' until Mark sent, then 'sent' (later states once the bazar ends). */
  transferStatus?: string | null
  /** B9c — pieces of the batch on the bazar's send. */
  onSend?: number
  /** B9c — pieces of the batch still at Traced Main to put on the send. */
  leftAtMain?: number
}

export interface BazarSetupStatus {
  on: true
  canSetUp: boolean
  batch: BazarSetupBatch | null
  /** Wizard entry — pieces available at Traced Main (the store's own stock: plan a bazar and send them instead). */
  mainAvailable?: number
  /** Wizard entry — 'main_not_counting': Traced Main is linked but Shopify isn't counting there yet and the store has
   *  pieces — the wizard can't receive ("Finish setting up Traced Main first"). */
  blocked?: 'main_not_counting' | null
  /** Wizard entry — false: don't show the /bazar "Bring your products" card (the store didn't come for bazars). */
  suggestStart?: boolean
}

/** null when the guided setup isn't on for this store (404) — or can't be reached (403). */
export async function getBazarSetup(): Promise<BazarSetupStatus | null> {
  try {
    return await bazarFetch<BazarSetupStatus>('/bazar-setup')
  } catch (e) {
    if (e instanceof BazarError && (e.status === 404 || e.status === 403)) return null
    throw e
  }
}

export interface BazarSetupVariant {
  id: string
  /** null = the product's only size ("Default Title"). */
  title: string | null
  sku: string | null
  price: number | string | null
  /** Shopify's total minus Traced Main; null = unknown (the read failed or stopped early). */
  otherLocations: number | null
  /** Wizard entry — pieces of this size already available at Traced Main (a warning when picked, never a block). */
  atMain?: number
}

export interface BazarSetupProduct {
  id: string
  title: string
  imageUrl: string | null
  variants: BazarSetupVariant[]
}

export function getBazarSetupProducts() {
  return bazarFetch<{ products: BazarSetupProduct[]; stockKnown: boolean }>('/bazar-setup/products')
}

export interface BazarSetupLine { variantId: string; quantity: number; labels: boolean }

/** Receive the pick into Traced Main (labelled + count-only receipts). The same requestId again returns the same batch. */
export function receiveBazarSetup(eventId: string, requestId: string, lines: BazarSetupLine[]) {
  return bazarFetch<BazarSetupStatus>('/bazar-setup/receive', {
    method: 'POST', body: JSON.stringify({ eventId, requestId, lines }),
  })
}

export interface BazarLowerRow {
  variantId: string
  productTitle: string
  variantTitle: string | null
  sku: string | null
  quantity: number
}

export function getBazarLowerList() {
  return bazarFetch<{ rows: BazarLowerRow[]; total: number; lowered: boolean; loweredAt: string | null }>('/bazar-setup/lower')
}

export function markBazarLowered() {
  return bazarFetch<BazarSetupStatus>('/bazar-setup/lower-done', { method: 'POST' })
}

/**
 * B9c — put the lowered batch's pieces on the bazar's send (exactly the batch's pieces, through the same send as a scan).
 * The same requestId again sends nothing more; {@code left} > 0 → call again with a new id for the rest.
 */
export function sendBazarSetupBatch(requestId: string) {
  return bazarFetch<{ sent: number; left: number; replayed: boolean; status: BazarSetupStatus }>('/bazar-setup/send-batch', {
    method: 'POST', body: JSON.stringify({ requestId }),
  })
}

/** The "lower these" list as a CSV file (headers in the screen's language). */
export async function downloadBazarLowerCsv(lang: string) {
  const res = await authFetch(`/bazar-setup/lower.csv?lang=${lang.startsWith('ar') ? 'ar' : 'en'}`)
  if (!res.ok) throw new BazarError(res.status, { code: `HTTP_${res.status}` })
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = 'bazar-lower-these.csv'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/**
 * The labelled receipt's labels (the existing Receiving labels PDF), opened in a new tab to print. No size is sent, so the
 * store's own label size applies (LabelSizes.forTenant — e.g. a jewelry store's 60 × 12).
 */
export async function openBazarSetupLabels(receiptId: string) {
  const res = await authFetch(`/receiving/sessions/${encodeURIComponent(receiptId)}/labels`)
  if (!res.ok) throw new BazarError(res.status, { code: `HTTP_${res.status}` })
  window.open(URL.createObjectURL(await res.blob()), '_blank')
}

// ── Customer details on request (find / export / delete one customer's name and phone) ─────────────────────────
// Every call is a POST with a JSON body: a phone or a name never goes in a URL.

export interface BazarCustomerMatch {
  /** Their phone (01XXXXXXXXX), else "name:" + their name in lower case — what export / delete take. */
  key: string
  name: string | null
  /** Every name saved with this key. */
  names: string[]
  phone: string | null
  bazars: number
  /** Every sale row held (undone and returned ones too). */
  sales: number
  /** Pieces they kept. */
  pieces: number
  total: string | number
  last_visit: string
}

export interface BazarCustomerDeleteCounts { rows: number; receipts: number; bazars: number }

/** Owner / manager: a phone in any usual shape, or 2+ letters of a name. */
export function searchBazarCustomers(q: string) {
  return bazarFetch<{ customers: BazarCustomerMatch[]; more: boolean }>('/bazar-customers/search',
    { method: 'POST', body: JSON.stringify({ q }) })
}

/** Owner / manager: every bazar sale row of these customers as CSV (audit-logged) — downloaded as a file. */
export async function exportBazarCustomers(keys: string[], fileName: string) {
  const res = await authFetch('/bazar-customers/export', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ keys }),
  })
  if (!res.ok) {
    let b: { code?: string } | null = null
    try { b = await res.json() } catch { /* not JSON */ }
    throw new BazarError(res.status, b?.code ? (b as { code: string }) : { code: `HTTP_${res.status}` })
  }
  const url = URL.createObjectURL(await res.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** Owner only: what a delete would clear. Writes nothing. */
export function previewBazarCustomerDelete(keys: string[]) {
  return bazarFetch<BazarCustomerDeleteCounts>('/bazar-customers/delete-preview',
    { method: 'POST', body: JSON.stringify({ keys }) })
}

/** Owner only: clears their name and phone on every bazar sale. Can't be undone. 409 when the preview is stale. */
export function deleteBazarCustomers(keys: string[], expectedRows: number) {
  return bazarFetch<BazarCustomerDeleteCounts>('/bazar-customers/delete',
    { method: 'POST', body: JSON.stringify({ keys, expected_rows: expectedRows }) })
}
