import { clearAccessToken, getAccessToken, setAccessToken } from './auth'

const BASE = '/api/v1'

// De-duplication guard: all concurrent 401s wait on the same refresh call so
// we never fire more than one refresh at a time.
let refreshPromise: Promise<string> | null = null

async function doRefresh(): Promise<string> {
  const res = await fetch(BASE + '/auth/refresh', { method: 'POST', credentials: 'include' })
  if (!res.ok) {
    clearAccessToken()
    throw new Error('refresh_failed')
  }
  const data: { accessToken: string } = await res.json()
  setAccessToken(data.accessToken)
  return data.accessToken
}

// Symbol flag on retried requests so the interceptor never loops.
const RETRY_FLAG = Symbol('retry')
type RetryOpts = RequestInit & { [RETRY_FLAG]?: true }

export async function request<T>(path: string, opts: RetryOpts = {}): Promise<T> {
  const token = getAccessToken()
  const res = await fetch(BASE + path, {
    ...opts,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...opts.headers,
    },
  })

  if (res.status === 401) {
    // If this IS the retry, the refresh itself failed → real logout (no loop).
    if (opts[RETRY_FLAG]) {
      clearAccessToken()
      window.location.href = '/login'
      throw new Error('Unauthenticated')
    }
    // Kick off one shared refresh, or join an already in-flight one.
    try {
      if (!refreshPromise) {
        refreshPromise = doRefresh().finally(() => { refreshPromise = null })
      }
      await refreshPromise
    } catch {
      clearAccessToken()
      window.location.href = '/login'
      throw new Error('Unauthenticated')
    }
    // Retry the original request exactly once with the new access token.
    return request<T>(path, { ...opts, [RETRY_FLAG]: true })
  }

  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`)
  if (res.status === 204 || res.headers.get('content-length') === '0') return null as T
  const ct = res.headers.get('content-type') ?? ''
  if (!ct.includes('application/json')) return null as T
  return res.json()
}

export interface LoginResponse {
  accessToken: string
}

export function login(email: string, password: string) {
  return request<LoginResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
}

// FR-7/FR-11 — single derived headline (see OrderStatusDeriver on the backend).
// tone mirrors the backend's Tone enum verbatim; keep in sync.
export type DerivedTone = 'NEUTRAL' | 'INFO' | 'SUCCESS' | 'WARN' | 'DANGER'

export interface DerivedStatusChip {
  key: string
  tone: DerivedTone
  count: number | null
}

export interface DerivedHistoricalNote {
  key: string
  count: number
}

export interface DerivedOrderStatus {
  primaryKey: string
  tone: DerivedTone
  healthChips: DerivedStatusChip[]
  historicalNote: DerivedHistoricalNote | null
  conflictKey: string | null
  notTraced: boolean
  // Status-split fix — a shipment existing is not proof packing happened (see
  // OrderStatusDeriver.packedConfirmed doc comment on the backend). Not consumed by the
  // stepper (OrderDetail.tsx computes its own equivalent client-side from already-fetched
  // fields) — included here for wire-contract completeness.
  packedConfirmed: boolean
  // Orders-rebuild pass (a) — fulfillment facet, orthogonal to primaryKey/tone (the
  // delivery/composite facet). Render straight through i18n, same as primaryKey/tone —
  // no client-side re-derivation.
  fulfillmentKey: string
  fulfillmentTone: DerivedTone
}

export interface OrderSummary {
  id: string
  number: string
  customerName: string | null
  customerPhone: string | null
  status: string
  onHold: boolean
  codAmount: number | null
  placedAt: string | null
  trackingNumber: string | null
  deliveryState: string | null
  exceptionReason: string | null
  bostaLinkStatus: string | null
  failedDeliveryAttempts: number
  isDelayed: boolean | null
  slaBreached: boolean | null
  notTracedAt: string | null
  isExchange: boolean
  derivedStatus: DerivedOrderStatus
}

export interface OrderPage {
  items: OrderSummary[]
  page: number
  size: number
  total: number
}

export interface AllocatedPiece {
  pieceId: string
  barcode: string
  status: string
}

export interface OrderItem {
  id: string
  productTitle: string
  variantTitle: string
  sku: string | null
  quantity: number
  imageUrl: string | null
  allocatedPieces: AllocatedPiece[]
}

export interface DeliveryHistoryEntry {
  state: string
  providerState: number | null
  exceptionCode: number | null
  exceptionReason: string | null
  occurredAt: string
}

export interface AttemptEntry {
  attemptDate: string | null
  type: string | null
  succeeded: boolean
  courierName: string | null
  courierPhone: string | null
  failureReason: string | null
}

export interface ShipmentDetail {
  id: string
  trackingNumber: string | null
  provider: string
  internalState: string
  shipmentLeg: string
  numberOfAttempts: number
  failedDeliveryAttempts: number
  awbUrl: string | null
  exceptionCode: number | null
  exceptionReason: string | null
  isDelayed: boolean | null
  slaBreached: boolean | null
  scheduledAt: string | null
  courierName: string | null
  courierPhone: string | null
  lastFailureReason: string | null
  attempts: AttemptEntry[]
  deliveryHistory: DeliveryHistoryEntry[]
  // A3.1 — leg-scoped badge from this shipment's OWN internal_state only (no order-level
  // precedence). Render for the return leg ONLY; the forward leg's status lives solely in
  // the order header (OrderStatus / derivedStatus).
  legStatus: { primaryKey: string; tone: DerivedTone }
}

export interface OrderDetail {
  id: string
  number: string
  customerName: string | null
  customerPhone: string | null
  address: Record<string, unknown> | null
  paymentMethod: string | null
  codAmount: number | null
  status: string
  onHold: boolean
  holdReason: string | null
  placedAt: string | null
  createdAt: string
  items: OrderItem[]
  shipments: ShipmentDetail[]
  bostaLinkStatus: string | null
  notTracedAt: string | null
  isExchange: boolean
  derivedStatus: DerivedOrderStatus
  // Orders-rebuild pass (a), drawer "View in Shopify" — null whenever the backend
  // couldn't resolve a shop domain + numeric order id; omit the button in that case.
  shopifyOrderUrl: string | null
}

export interface OrderListParams {
  status?: string
  // Filters on the shipment's own internal_state (LATERAL-joined), not orders.status —
  // orders.status is never written to 'with_courier'/'returned' by the app, only the
  // shipment row reflects courier-pipeline state. Use this for delivery-facet filters.
  deliveryState?: string
  q?: string
  tracking?: string
  page?: number
  size?: number
}

export function listOrders(params: OrderListParams = {}) {
  const q = new URLSearchParams()
  if (params.status)        q.set('status', params.status)
  if (params.deliveryState) q.set('deliveryState', params.deliveryState)
  if (params.q)        q.set('q', params.q)
  if (params.tracking) q.set('tracking', params.tracking)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  return request<OrderPage>(`/orders?${q}`)
}

export function getOrder(id: string) {
  return request<OrderDetail>(`/orders/${id}`)
}

// ── Trace timeline (Orders rebuild pass (b)) ────────────────────────────────────
// Separate endpoint, separate fetch from getOrder() — the drawer must not gate the
// order header/items on this, and a timeline-fetch failure must not blank/hang the
// rest of the drawer. eventKey/kind are the wire contract with GET
// /orders/{id}/timeline; kind is backend-computed and rendered as-is, never
// recomputed client-side.
export interface TimelineItem {
  occurredAt: string
  eventKey: string
  actorName: string | null
  locationName: string | null
  detail: string | null
  kind: 'done' | 'now' | 'fail'
}

export function getOrderTimeline(id: string) {
  return request<TimelineItem[]>(`/orders/${id}/timeline`)
}

export interface DayCount { date: string; count: number }
export function getOrderDailyCounts(days = 30) {
  return request<DayCount[]>(`/orders/daily-counts?days=${days}`)
}

// ── Gather list (FR-8.7) ────────────────────────────────────────────────────

export interface GatherRow {
  variantId: string
  name: string
  sku: string | null
  displayName: string
  needed: number
  availableCount: number
  shortage: boolean
  orderNumbers: string[]
}

export interface GatherListResponse {
  generatedAt: string
  orderCount: number
  rows: GatherRow[]
}

export function getGatherList(limit?: number) {
  return request<GatherListResponse>(`/fulfill/gather${limit ? `?limit=${limit}` : ''}`)
}

export interface PieceCounts {
  available: number
  reserved: number
  packed: number
  awaiting_pickup: number
  with_courier: number
  delivered: number
  return_in_transit: number
  return_pending_inspection: number
  damaged: number
  lost: number
  destroyed: number
  out_on_transfer: number
  sold: number
  total: number
}

export interface CatalogVariant {
  id: string
  title: string
  sku: string | null
  price: number | null
  pieceCounts: PieceCounts
  committed: number
  available: number
}

export interface CatalogProduct {
  id: string
  title: string
  status: string
  imageUrl: string | null
  variants: CatalogVariant[]
}

export interface CatalogResponse {
  products: CatalogProduct[]
}

export function getCatalog() {
  return request<CatalogResponse>('/catalog')
}

export interface ShopifyStore {
  id: string
  shop_domain: string
  status: string
  import_status: string
  last_sync_at: string | null
}

export function listShopifyStores() {
  return request<ShopifyStore[]>('/shopify/stores')
}

export function syncShopifyStore(storeId: string) {
  return request<void>(`/shopify/stores/${storeId}/sync`, { method: 'POST' })
}

// ── Lookup (FR-14) ────────────────────────────────────────────────────────────

export interface TimelineEvent {
  id: number
  eventType: string
  phraseKey: string
  actor: string
  isSystem: boolean
  fromStatus: string | null
  toStatus: string | null
  orderNumber: string | null
  orderId: string | null
  trackingNumber: string | null
  locationName: string | null
  metadata: unknown
  occurredAt: string
}

export interface LookupVariant {
  id: string
  title: string
  sku: string | null
  productTitle: string
}

export interface LookupOrder {
  id: string
  number: string | null
  status: string
  customerName?: string | null
  customerPhone?: string | null
}

export interface LookupShipment {
  id: string
  trackingNumber: string
  internalState: string
}

export interface LookupSession {
  id: string
  locationName: string | null
}

export interface PieceLookupResult {
  type: 'piece'
  id: string
  barcode: string
  status: string
  receivedAt: string
  variant: LookupVariant
  currentLocation: { id: string; name: string } | null
  currentOrder: LookupOrder | null
  currentShipment: LookupShipment | null
  receivingSession: LookupSession | null
  timeline: TimelineEvent[]
}

export interface TrackingLookupResult {
  type: 'tracking'
  trackingNumber: string
  shipmentId: string
  orderId: string
  orderNumber: string | null
  internalState: string
  pieces: Array<{ pieceId: string; barcode: string; status: string }>
}

export type LookupResult = PieceLookupResult | TrackingLookupResult

export function lookup(q: string) {
  return request<LookupResult>(`/lookup?q=${encodeURIComponent(q)}`)
}

// ── JWT role helper ───────────────────────────────────────────────────────────

function parseJwtPayload(jwtToken: string): Record<string, unknown> {
  try {
    const payload = jwtToken.split('.')[1]
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
  } catch {
    return {}
  }
}

export function getRoleFromToken(): 'owner' | 'manager' | 'worker' | null {
  const t = getAccessToken()
  if (!t) return null
  const claims = parseJwtPayload(t)
  const role = claims['role']
  if (role === 'owner' || role === 'manager' || role === 'worker') return role
  return null
}

// ── Auth: signup ──────────────────────────────────────────────────────────────

export function signup(tenantName: string, name: string, email: string, password: string, consent: boolean) {
  return request<{ accessToken: string }>('/auth/signup', {
    method: 'POST',
    body: JSON.stringify({ tenantName, name, email, password, consent }),
  })
}

// ── Connections status ────────────────────────────────────────────────────────

export interface ConnectionsStatus {
  shopify: {
    connected: boolean
    shopDomain: string | null
    importStatus: string | null
    lastSyncAt: string | null
  }
  bosta: {
    connected: boolean
    businessName: string | null
    pickupMode: string | null
    awbFormat: 'A4' | 'A6' | null
    awbLang: string | null
  }
  shopifyCustomApp: {
    connected: boolean
    shopDomain: string | null
    importStatus: string | null
    lastSyncAt: string | null
  }
  customAppAvailable: boolean
}

export function getConnections() {
  return request<ConnectionsStatus>('/connections')
}

export function bostaUpdateSettings(settings: { awbFormat?: 'A4' | 'A6'; awbLang?: string }) {
  return request<void>('/bosta/settings', {
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}

// ── FR-17 v2: fulfillment activation (deliveryProfileUpdate) ─────────────────
//
// Joins the Traced Main Warehouse to the shop's default delivery profile's location
// group — the step that makes it live to the storefront. Explicit, owner-triggered,
// never automatic (see LocationController.activateShopifyFulfillment's javadoc).

export interface ShopifyReconcileRow {
  variantId: string
  sku: string
  title: string
  tracedOnHand: number
  shopifyAvailable: number
  action: 'seed' | 'skip_nonzero' | 'noop'
}

export interface ShopifyReconcileReport {
  tracedLocationGid: string
  rows: ShopifyReconcileRow[]
}

/** Read-only — used here only to gate the activation checklist item on "nothing left to seed". */
export function getShopifyInventoryReconcileReport() {
  return request<ShopifyReconcileReport>('/shopify/inventory/reconcile')
}

export interface FulfillmentActivationResult {
  status: 'activated'
  locationGid: string
  deliveryProfileId: string
  locationGroupId: string
  alreadyMember: boolean
}

/**
 * Same {code, message_en, message_ar} error contract as TransferException (see
 * ApiExceptionHandler.handleFulfillmentActivation) — reuses transferCommandRequest's
 * generic error-body sniffing (and throws the same TransferCommandError) rather than
 * duplicating that logic for one more call site.
 */
export function activateShopifyFulfillment() {
  return transferCommandRequest<FulfillmentActivationResult>(
    '/locations/shopify/activate-fulfillment', { method: 'POST' })
}

// ── Shopify custom-app connect (DEV/pilot only) ───────────────────────────────

export function shopifyCustomConnect(shopDomain: string, clientId: string, clientSecret: string) {
  return request<{ storeId: string; importStatus: string }>('/shopify/custom-connect', {
    method: 'POST',
    body: JSON.stringify({ shopDomain, clientId, clientSecret }),
  })
}

// ── Shopify OAuth: initiate install flow ──────────────────────────────────────

export function shopifyInitiate(shop: string) {
  return request<{ consentUrl: string }>('/shopify/oauth/initiate', {
    method: 'POST',
    body: JSON.stringify({ shop }),
  })
}

// ── Bosta connect ─────────────────────────────────────────────────────────────

export function bostaConnect(apiKey: string) {
  return request<{ accountId: string; webhookSecret: string }>('/bosta/connect', {
    method: 'POST',
    body: JSON.stringify({ apiKey }),
  })
}

export function bostaRegenerateSecret() {
  return request<{ accountId: string | null; webhookSecret: string }>('/bosta/regenerate-secret', {
    method: 'POST',
  })
}

// ── Bosta backfill sync ───────────────────────────────────────────────────────

export interface BostaBackfillStatus {
  lastBackfillAt: string | null
  lastBackfillTotal: number
  lastBackfillEnqueued: number
}

export function bostaSync(maxPages?: number) {
  return request<{ jobId: string; message: string }>('/bosta/sync', {
    method: 'POST',
    body: JSON.stringify({ maxPages: maxPages ?? null }),
  })
}

export function bostaGetSyncStatus() {
  return request<BostaBackfillStatus>('/bosta/sync/status')
}

// ── Onboarding checklist (FR-1.2) ────────────────────────────────────────────

export interface OnboardingStep {
  key: 'connect_shopify' | 'connect_bosta' | 'initial_import' | 'test_label' | 'first_receiving'
  label: string
  status: 'done' | 'pending'
}

export interface OnboardingStatus {
  steps: OnboardingStep[]
  allDone: boolean
  dismissed: boolean
}

export function getOnboardingStatus() {
  return request<OnboardingStatus>('/onboarding/status')
}

export function dismissOnboarding() {
  return request<void>('/onboarding/dismiss', { method: 'POST' })
}

// ── Tenant settings (FR-1.4) ──────────────────────────────────────────────────

export interface TenantSettings {
  name: string
  pickupAddress: string | null
  labelSize: '40x25' | '50x25'
  defaultLanguage: 'ar' | 'en'
  timezone: string
  consentPrivacyVersion: string | null
  consentTermsVersion: string | null
  consentAcceptedAt: string | null
}

export function getTenantSettings() {
  return request<TenantSettings>('/tenant/settings')
}

export function updateTenantSettings(settings: Partial<TenantSettings>) {
  return request<void>('/tenant/settings', {
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}

// ── User management (FR-2.2) ──────────────────────────────────────────────────

export interface User {
  id: string
  name: string
  email: string | null
  role: 'owner' | 'manager' | 'worker'
  active: boolean
  created_at: string
}

export function listUsers() {
  return request<User[]>('/users')
}

export function createUser(payload: {
  name: string
  email?: string
  role: string
  password?: string
  pin?: string
}) {
  return request<{ id: string; name: string; role: string }>('/users', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateUser(id: string, payload: { name?: string; role?: string }) {
  return request<void>(`/users/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  })
}

export function deactivateUser(id: string) {
  return request<void>(`/users/${id}/deactivate`, { method: 'POST' })
}

// ── Self (shell identity) ─────────────────────────────────────────────────────

export interface Me {
  name: string
  email: string | null
  role: 'owner' | 'manager' | 'worker'
}

export function getMe() {
  return request<Me>('/me')
}

// ── Open-exceptions count (shell notification bell) ───────────────────────────
// count is unchanged — the shell nav badge keeps reading it as before. critical/
// warning are a backward-compatible addition for the Overview dashboard's
// severity-split widget (4-tier severity collapsed to 2 buckets server-side).

export function getExceptionsCount() {
  return request<{ count: number; critical: number; warning: number }>('/exceptions/count')
}

// ── Inventory summary (FR-15.1) ───────────────────────────────────────────────

export interface InventoryStatusCount {
  status: string
  count: number
}

export interface InventorySummary {
  groupA: InventoryStatusCount[]
  groupB: InventoryStatusCount[]
}

export function getInventorySummary() {
  return request<InventorySummary>('/inventory/summary')
}

// ── Overview dashboard (FR-Overview) ────────────────────────────────────────
// Live per-status totals — a pure per-status GROUP BY, distinct from the
// summary() groupA/groupB above (groupB is a 30-day window, not live).
// All 13 piece_status values are present as keys, zero-filled.

export interface StatusTotals {
  statusCounts: Record<string, number>
}

export function getStatusTotals() {
  return request<StatusTotals>('/inventory/status-totals')
}

// Low-stock count + inventory value — deliberately separate from status-totals
// (different join, different cost). variantsCosted/variantsTotal let the
// caller show a "based on N of M variants costed" caveat instead of a
// misleading "EGP 0" when nothing is costed yet.
export interface Valuation {
  lowStockCount: number
  inventoryValue: number
  variantsCosted: number
  variantsTotal: number
}

export function getValuation() {
  return request<Valuation>('/inventory/valuation')
}

// Fulfillment funnel — today, bucketed via the SAME OrderStatusDeriver every
// other order-status surface uses server-side. Never re-derive this client-side.
export interface FunnelCounts {
  newCount: number
  picking: number
  packed: number
  courier: number
  delivered: number
}

export function getOrdersFunnel() {
  return request<FunnelCounts>('/orders/funnel')
}

// All-time orders summary (Orders list tile row) — bucketed server-side via the SAME
// OrderStatusDeriver every other order-status surface uses. Never re-derive this client-side.
export interface OrderSummaryCounts {
  total: number
  processing: number
  withCourier: number
  delivered: number
  returned: number
}

export function getOrdersSummary() {
  return request<OrderSummaryCounts>('/orders/summary')
}

// Throughput — fixed 30d window (received vs handed-to-courier), from piece_events.
export interface ThroughputDay {
  date: string
  received: number
  shipped: number
}

export function getThroughput() {
  return request<ThroughputDay[]>('/inventory/throughput')
}

// Recent activity — receiving + stock-take only (pickups excluded, no actor
// column). Rendered through i18n templates client-side, never stored English.
export interface ActivityItem {
  type: 'receiving' | 'stock_take'
  refId: string
  refCode: string | null
  actorName: string | null
  locationName: string | null
  occurredAt: string
  pieceCount: number | null
}

export function getRecentActivity(limit?: number) {
  return request<ActivityItem[]>(`/activity/recent${limit ? `?limit=${limit}` : ''}`)
}

// Returns-pending total — the awaiting-inspection tile reads .total, never a
// page's .items.length (page length undercounts past the first page).
export interface ReturnsPendingPage {
  items: unknown[]
  total: number
}

export function getReturnsPendingTotal() {
  return request<ReturnsPendingPage>('/returns/pending?size=1')
}

// ── Overview trends + top-selling SKUs (FR-Overview §2) ────────────────────
// trends(): 5 metrics × 14 zero-filled Cairo-days, live-aggregated server-side —
// never re-derive today/yesterday/deltaPct client-side. deltaPct is null (not 0,
// not a fabricated %) whenever yesterday=0. "exceptions" here is a FLOW count
// (opened today, 12 event-based detectors only) — distinct from the shell's
// notification badge (getExceptionsCount(), a STOCK count of currently-open
// exceptions). The two numbers are expected to diverge; never reconcile them.

export interface TrendPoint {
  date: string
  count: number
}

export interface MetricTrend {
  metric: 'orders' | 'shipments' | 'delivered' | 'exceptions' | 'returns'
  today: number
  yesterday: number
  deltaPct: number | null
  series: TrendPoint[]
}

export function getOverviewTrends() {
  return request<MetricTrend[]>('/overview/trends')
}

export interface TopSku {
  sku: string | null
  title: string
  imageUrl: string | null
  units: number
}

export function getOverviewTopSkus() {
  return request<TopSku[]>('/overview/top-skus')
}

// ── Inventory screen (Phase A backend) ────────────────────────────────────────
// Every field named committed/available/onHand below is exactly what
// VariantStockService computed — no client-side re-derivation. committed is
// nullable by design (Phase A location-scoping rule): it's order demand, which
// has no location until a piece is scanned, so it's null whenever a query is
// location-scoped rather than tenant-wide.

export type ShopifySyncStatus = 'synced' | 'pending' | 'failed' | 'none'

export interface InventoryStockVariant {
  id: string
  title: string
  sku: string | null
  price: number | null
  onHand: number
  committed: number | null
  available: number
  shopifySync: ShopifySyncStatus
}

export interface InventoryStockProduct {
  id: string
  title: string
  imageUrl: string | null
  onHand: number
  committed: number | null
  available: number
  variants: InventoryStockVariant[]
}

export interface InventoryStockPage {
  items: InventoryStockProduct[]
  nextCursor: string | null
}

export function getInventoryStock(params: {
  q?: string
  locationId?: string
  lowStockOnly?: boolean
  cursor?: string
  size?: number
}) {
  const q = new URLSearchParams()
  if (params.q)            q.set('q', params.q)
  if (params.locationId)   q.set('locationId', params.locationId)
  if (params.lowStockOnly) q.set('lowStockOnly', 'true')
  if (params.cursor)       q.set('cursor', params.cursor)
  if (params.size != null) q.set('size', String(params.size))
  return request<InventoryStockPage>(`/inventory/stock?${q}`)
}

export interface InventoryLocationStock {
  locationId: string
  locationName: string
  available: number
  onHand: number
}

export interface InventoryVariantMovement {
  id: string
  triggerType: string
  delta: number | null
  status: string
  locationName: string
  createdAt: string
  appliedAt: string | null
}

export interface InventoryVariantBreakdown {
  variantId: string
  onHand: number
  committed: number
  available: number
  locations: InventoryLocationStock[]
  recentMovements: InventoryVariantMovement[]
}

export function getInventoryVariantBreakdown(variantId: string) {
  return request<InventoryVariantBreakdown>(`/inventory/variants/${variantId}/breakdown`)
}

export interface InventoryPhaseCounts {
  inWarehouse: number
  onTheWayOut: number
  delivered: number
  comingBack: number
  problem: number
}

export function getInventoryBreakdown() {
  return request<InventoryPhaseCounts>('/inventory/breakdown')
}

export interface InventoryPieceRow {
  id: string
  barcode: string
  variantTitle: string
  sku: string | null
  productTitle: string
  orderNumber: string | null
  trackingNumber: string | null
  locationName: string | null
  lastEventAt: string | null
}

export interface InventoryPiecePage {
  items: InventoryPieceRow[]
  nextCursor: string | null
}

export function getInventoryPieces(params: {
  status: string
  variantId?: string
  locationId?: string
  cursor?: string
  size?: number
}) {
  const q = new URLSearchParams()
  q.set('status', params.status)
  if (params.variantId)    q.set('variantId', params.variantId)
  if (params.locationId)   q.set('locationId', params.locationId)
  if (params.cursor)       q.set('cursor', params.cursor)
  if (params.size != null) q.set('size', String(params.size))
  return request<InventoryPiecePage>(`/inventory/pieces?${q}`)
}

/** 'stock_take' rows are session-grain — variant/sku/variantTitle/productTitle are
 *  null by design (per-variant payload decomposition is a documented backend
 *  fast-follow). Render at session grain, not a fake per-variant row. */
export interface InventoryMovementRow {
  id: string
  source: 'adjustment' | 'stock_take'
  triggerType: string | null
  variantId: string | null
  sku: string | null
  variantTitle: string | null
  productTitle: string | null
  locationName: string | null
  delta: number | null
  syncStatus: 'synced' | 'pending' | 'failed'
  createdAt: string
  appliedAt: string | null
}

export interface InventoryMovementPage {
  items: InventoryMovementRow[]
  nextCursor: string | null
}

export function getInventoryMovements(params: { cursor?: string; size?: number } = {}) {
  const q = new URLSearchParams()
  if (params.cursor)        q.set('cursor', params.cursor)
  if (params.size != null)  q.set('size', String(params.size))
  return request<InventoryMovementPage>(`/inventory/movements?${q}`)
}

// ── Manual adjustments (FR-13) ────────────────────────────────────────────────

export type AdjustReason =
  | 'cycle_count_missing'
  | 'damaged_in_storage'
  | 'sample_giveaway'
  | 'theft_suspected'
  | 'receiving_correction'
  | 'other'

export const ADJUST_REASONS: AdjustReason[] = [
  'cycle_count_missing',
  'damaged_in_storage',
  'sample_giveaway',
  'theft_suspected',
  'receiving_correction',
  'other',
]

export interface PieceCommittedError {
  error: 'PIECE_COMMITTED'
  orderId: string
  orderNumber: string
}

export function adjustPiece(
  pieceId: string,
  toStatus: 'lost' | 'damaged' | 'destroyed' | 'available',
  reason: AdjustReason,
  note?: string,
) {
  return request<void>(`/pieces/${pieceId}/adjust`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ toStatus, reason, note }),
  })
}

export function releasePieceForAdjust(pieceId: string) {
  return request<void>(`/pieces/${pieceId}/release-for-adjust`, { method: 'POST' })
}

// ── Blocklist (FR-7.9) ────────────────────────────────────────────────────────

export interface BlocklistEntry {
  id: string
  phoneCanonical: string
  reason: string
  source: 'manual' | 'bosta_rejected'
  createdBy: string | null
  createdAt: string
}

export function listBlocklist() {
  return request<BlocklistEntry[]>('/blocklist')
}

export function addToBlocklist(phone: string, reason: string) {
  return request<BlocklistEntry>('/blocklist', {
    method: 'POST',
    body: JSON.stringify({ phone, reason }),
  })
}

export function removeFromBlocklist(id: string) {
  return request<void>(`/blocklist/${id}`, { method: 'DELETE' })
}

// ── FR-7.4 / FR-7.8a: hold management ────────────────────────────────────────

/** FR-7.4 — Manually hold an order with a required reason (OWNER/MANAGER). */
export function holdOrder(orderId: string, reason: string) {
  return request<void>(`/fulfill/${orderId}/hold`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  })
}

/** FR-7.4 / FR-7.8a — Release any hold (manual or blocked-customer) (OWNER/MANAGER). */
export function releaseOrderHold(orderId: string) {
  return request<void>(`/fulfill/${orderId}/release-hold`, { method: 'POST' })
}

// ── FR-7.5: COD editing ───────────────────────────────────────────────────────

/** FR-7.5 — Update COD amount while order is new/ready_to_pick (OWNER/MANAGER). */
export function updateOrderCod(orderId: string, amount: number) {
  return request<void>(`/fulfill/${orderId}/cod`, {
    method: 'PATCH',
    body: JSON.stringify({ amount }),
  })
}

export function cancelOrder(orderId: string) {
  return request<{ status: string; message: string }>(`/orders/${orderId}/cancel`, {
    method: 'POST',
  })
}

// ── Variant search (Receiving's autocomplete, reused by stock-take) ────────
// GET /receiving/variants/search — despite the /receiving namespace this is a
// generic active-variant search (SKU/title/product), not receiving-scoped.
// Receiving.tsx itself still uses its own inline fetch helper (not this
// function) — this wrapper exists for stock-take's variant-subset picker,
// per the decision to route all stock-take calls through api.ts.

export interface VariantMatch {
  id: string
  title: string
  sku: string | null
  product_title: string
}

export function searchVariants(query: string) {
  return request<VariantMatch[]>(`/receiving/variants/search?q=${encodeURIComponent(query)}`)
}

// ── FR-21: Stock Taking ────────────────────────────────────────────────────

export type StockTakeStatus = 'open' | 'finalized' | 'cancelled'
export type StockTakeScopeType = 'all' | 'variant_subset'
export type StockTakeCondition = 'good' | 'damaged'
export type StockTakeClassification =
  | 'match' | 'condition_mismatch' | 'unexpected_resurfaced' | 'out_of_scope' | 'unknown'
export type StockTakeResolveAction = 'found' | 'lost' | 'mark_damaged'
export type StockTakeSyncStatus = 'pending' | 'pushed' | 'failed' | 'failed_ambiguous'

export interface StockTakeSessionSummary {
  sessionId: string
  status: StockTakeStatus
  scopeType: StockTakeScopeType
  openedBy: string
  openedByName: string | null
  openedAt: string
  coveragePercent: number
  counted: number
  expected: number
}

export function listStockTakeSessions() {
  return request<StockTakeSessionSummary[]>('/stock-takes/sessions')
}

export interface StockTakeSummaryCounts {
  countsThisMonth: number
  piecesWrittenOff: number
  avgVariancePercent: number | null
  openSessions: number
}

export function getStockTakeSummary() {
  return request<StockTakeSummaryCounts>('/stock-takes/summary')
}

export interface StockTakeSyncDelta {
  variantId: string
  variantTitle: string
  sku: string
  delta: number
}

export interface StockTakeShopifySync {
  status: StockTakeSyncStatus
  deltas: StockTakeSyncDelta[]
  referenceDocumentUri: string
  pushedAt: string | null
  error: string | null
}

export interface StockTakeSessionDetail {
  sessionId: string
  status: StockTakeStatus
  scopeType: StockTakeScopeType
  locationId: string
  completeCount: boolean
  openedBy: string
  openedByName: string | null
  openedAt: string
  finalizedBy: string | null
  finalizedByName: string | null
  finalizedAt: string | null
  note: string | null
  shopifySync: StockTakeShopifySync | null
}

export function getStockTakeSession(sessionId: string) {
  return request<StockTakeSessionDetail>(`/stock-takes/sessions/${sessionId}`)
}

export interface OpenStockTakeSessionResult {
  sessionId: string
  locationId: string
  scopeType: StockTakeScopeType
  piecesSnapshotted: number
}

export function openStockTakeSession(params: {
  scopeType: StockTakeScopeType
  variantIds?: string[]
  locationId?: string
  note?: string
}) {
  return request<OpenStockTakeSessionResult>('/stock-takes/sessions', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export interface StockTakeScanResult {
  sessionId: string
  barcode: string
  pieceId: string | null
  classification: StockTakeClassification
  alreadyScanned?: boolean
}

export function scanStockTakePiece(sessionId: string, barcode: string, condition: StockTakeCondition) {
  return request<StockTakeScanResult>(`/stock-takes/sessions/${sessionId}/scan`, {
    method: 'POST',
    body: JSON.stringify({ barcode, condition }),
  })
}

/** Idempotent: unscanning a piece that was never scanned is a 204 no-op, not an error. */
export function unscanStockTakePiece(sessionId: string, pieceId: string) {
  return request<void>(`/stock-takes/sessions/${sessionId}/scan/${pieceId}`, { method: 'DELETE' })
}

export interface StockTakePieceRow {
  pieceId: string
  variantId: string
  variantTitle: string
  sku: string | null
  productTitle: string
  liveStatus: string
  orderId?: string
  orderNumber?: string
  shipmentId?: string
  trackingNumber?: string
  flag?: string
  reason?: string
}

export interface StockTakeVariantRollup {
  variantId: string
  variantTitle: string
  sku: string | null
  totalKnown: number
  expectedOnShelf: number
  counted: number
  variance: number
  committed: number
  gone: number
  damagedCount: number
}

export interface StockTakeReconciliation {
  sessionId: string
  status: StockTakeStatus
  completeCount: boolean
  coveragePercent: number
  buckets: Record<string, StockTakePieceRow[]>
  variantRollup: StockTakeVariantRollup[]
}

export function getStockTakeReconciliation(sessionId: string) {
  return request<StockTakeReconciliation>(`/stock-takes/sessions/${sessionId}/reconciliation`)
}

export interface StockTakeResolveItem {
  pieceId: string
  action: StockTakeResolveAction
}

export interface StockTakeResolveResult {
  pieceId: string
  action: string
  result: string
}

/**
 * POST /resolve — batch. On a `lost` action against a committed piece the backend
 * throws PieceCommittedException (409, same body shape as the existing FR-13 one).
 *
 * Does its own fetch rather than going through the shared request() helper: request()
 * only preserves `${status}: ${statusText}` on a non-2xx response, never the JSON body —
 * which is also why PieceCommittedError parsing in Lookup.tsx's AdjustPanel
 * (JSON.parse(err.message.replace(/^409: /, ''))) can never actually succeed over a
 * real network call today; it only "works" in adjust.test.tsx because that test mocks
 * adjustPiece() directly and never exercises request() at all. Widening request()'s
 * error contract for every caller in the app is a bigger change than this endpoint
 * needs — this function parses its own 409 body instead, scoped to stock-take alone.
 */
export async function resolveStockTake(
  sessionId: string,
  items: StockTakeResolveItem[],
): Promise<StockTakeResolveResult[]> {
  const token = getAccessToken()
  const res = await fetch(`${BASE}/stock-takes/sessions/${sessionId}/resolve`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(items),
  })
  if (res.status === 409) {
    const body: PieceCommittedError = await res.json()
    throw new StockTakeCommittedError(body)
  }
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`)
  if (res.status === 204 || res.headers.get('content-length') === '0') return []
  return res.json()
}

export class StockTakeCommittedError extends Error {
  body: PieceCommittedError
  constructor(body: PieceCommittedError) {
    super('PIECE_COMMITTED')
    this.body = body
  }
}

export function attestStockTakeComplete(sessionId: string) {
  return request<{ sessionId: string; completeCount: boolean }>(
    `/stock-takes/sessions/${sessionId}/attest-complete`, { method: 'POST' })
}

export interface FinalizeStockTakeResult {
  sessionId: string
  status: string
  variantDeltas: Array<{ variant_id: string; qty: number }>
}

export function finalizeStockTake(sessionId: string) {
  return request<FinalizeStockTakeResult>(
    `/stock-takes/sessions/${sessionId}/finalize`, { method: 'POST' })
}

export function cancelStockTake(sessionId: string) {
  return request<void>(`/stock-takes/sessions/${sessionId}/cancel`, { method: 'POST' })
}

export function markStockTakeSyncResolved(sessionId: string) {
  return request<{ sessionId: string; status: string }>(
    `/stock-takes/sessions/${sessionId}/sync/mark-resolved`, { method: 'POST' })
}

export function repushStockTakeSync(sessionId: string) {
  return request<{ sessionId: string; status: string }>(
    `/stock-takes/sessions/${sessionId}/sync/repush`, { method: 'POST' })
}

// ── FR-22: Transfers & External Custody ──────────────────────────────────────
//
// Two response families, per TransferController's own javadoc:
//  - "scan" family (scan-out/scan-back): always 200, {success, code, message_en,
//    message_ar, ...} — routed through the shared request() helper like any other
//    2xx JSON endpoint; success:false is a normal, non-throwing outcome.
//  - "command" family (create/begin-reconcile/classify/close): thrown server-side as
//    TransferException, mapped to a real HTTP status + {code, message_en, message_ar}
//    body. request() only preserves "<status>: <statusText>" on non-2xx (same
//    limitation noted above resolveStockTake), so these go through their own fetch
//    that parses the body and throws TransferCommandError — one shared helper here
//    since (unlike stock-take's single call site) there are four.
//
// The frontend must render message_en/message_ar AS-IS, never re-derive text from
// `code` — one source of truth, no double-localization (explicit build requirement).

export class TransferCommandError extends Error {
  code: string
  messageEn: string
  messageAr: string
  constructor(body: { code: string; message_en: string; message_ar: string }) {
    super(body.code)
    this.code = body.code
    this.messageEn = body.message_en
    this.messageAr = body.message_ar
  }
}

async function transferCommandRequest<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const token = getAccessToken()
  const res = await fetch(`${BASE}${path}`, {
    ...opts,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(opts.headers as Record<string, string> ?? {}),
    },
  })
  if (!res.ok) {
    const body = await res.json().catch(() => null) as
      { code?: string; message_en?: string; message_ar?: string } | null
    if (body?.code && body.message_en != null && body.message_ar != null) {
      throw new TransferCommandError(body as { code: string; message_en: string; message_ar: string })
    }
    throw new Error(`${res.status}: ${res.statusText}`)
  }
  if (res.status === 204 || res.headers.get('content-length') === '0') return null as T
  return res.json()
}

export type TransferStatus = 'open' | 'reconciling' | 'closed'
export type TransferType = 'showroom' | 'dryclean' | 'repair' | 'other'
export const TRANSFER_TYPES: TransferType[] = ['showroom', 'dryclean', 'repair', 'other']
export type TransferCondition = 'good' | 'condemned'

export interface LocationOption {
  id: string
  name: string
  is_fulfillment: boolean
}

/** Non-fulfillment locations only — the valid destination set for createTransfer(). */
export async function listTransferDestinations(): Promise<LocationOption[]> {
  const all = await request<LocationOption[]>('/locations')
  return all.filter(l => !l.is_fulfillment)
}

export interface LocationRow {
  id: string
  name: string
  type: string
  is_default: boolean
  is_fulfillment: boolean
  shopify_location_id: string | null
  shopify_sync_status: 'unsynced' | 'pending' | 'linked' | 'error'
  shopify_sync_error: string | null
  shopify_synced_at: string | null
  shopify_delivery_profile_status: 'not_activated' | 'activated' | 'error'
  shopify_delivery_profile_error: string | null
  shopify_delivery_profile_activated_at: string | null
}

export function listLocations() {
  return request<LocationRow[]>('/locations')
}

export function createTransfer(params: {
  transferType: TransferType
  destinationLocationId: string
  expectedReturnAt?: string | null
  note?: string | null
}) {
  return transferCommandRequest<{ id: string }>('/transfers', {
    method: 'POST',
    body: JSON.stringify(params),
  })
}

export interface TransferScanResult {
  success: boolean
  code: string
  message_en: string | null
  message_ar: string | null
  pieceId: string | null
  barcode: string | null
  variantId: string | null
  lineId: string | null
  qtyOut: number
}

export function scanOutTransferPiece(transferId: string, barcode: string) {
  return request<TransferScanResult>(`/transfers/${transferId}/scan-out`, {
    method: 'POST',
    body: JSON.stringify({ barcode }),
  })
}

export interface TransferScanBackResult {
  success: boolean
  code: string
  message_en: string | null
  message_ar: string | null
  pieceId: string | null
  barcode: string | null
  variantId: string | null
  outcome: string | null
}

export function scanBackTransferPiece(transferId: string, barcode: string, condition: TransferCondition) {
  return request<TransferScanBackResult>(`/transfers/${transferId}/scan-back`, {
    method: 'POST',
    body: JSON.stringify({ barcode, condition }),
  })
}

export interface TransferSummary {
  id: string
  transfer_type: TransferType
  status: TransferStatus
  note: string | null
  expected_return_at: string | null
  created_by: string
  created_at: string
  destination_location_id: string
  destination_location_name: string
  outstanding_count: number
}

export function listOpenTransfers() {
  return request<TransferSummary[]>('/transfers')
}

export interface TransferLine {
  id: string
  variant_id: string
  sku: string | null
  variant_title: string
  product_title: string
  qty_out: number
  qty_returned_good: number
  qty_condemned: number
  qty_sold: number
  qty_lost: number
}

export interface TransferDetail {
  id: string
  transfer_type: TransferType
  status: TransferStatus
  note: string | null
  expected_return_at: string | null
  created_by: string
  created_at: string
  closed_by: string | null
  closed_at: string | null
  destination_location_id: string
  destination_location_name: string
  lines: TransferLine[]
  outstandingCount: number
}

export function getTransfer(transferId: string) {
  return request<TransferDetail>(`/transfers/${transferId}`)
}

export function beginReconcileTransfer(transferId: string) {
  return transferCommandRequest<void>(`/transfers/${transferId}/begin-reconcile`, { method: 'POST' })
}

export function classifyTransferShortfall(
  transferId: string,
  lineId: string,
  counts: { sold: number; lost: number; condemnedNotReturned: number },
) {
  return transferCommandRequest<void>(`/transfers/${transferId}/classify`, {
    method: 'POST',
    body: JSON.stringify({ lineId, ...counts }),
  })
}

export function closeTransferSession(transferId: string) {
  return transferCommandRequest<void>(`/transfers/${transferId}/close`, { method: 'POST' })
}

/** PDF blob download — same window.open(URL.createObjectURL(...)) pattern as Receiving/Returns. */
export async function reprintTransferOutstanding(transferId: string): Promise<void> {
  const token = getAccessToken()
  const res = await fetch(`${BASE}/transfers/${transferId}/reprint-outstanding`, {
    method: 'POST',
    credentials: 'include',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!res.ok) {
    const body = await res.json().catch(() => null) as
      { code?: string; message_en?: string; message_ar?: string } | null
    if (body?.code && body.message_en != null && body.message_ar != null) {
      throw new TransferCommandError(body as { code: string; message_en: string; message_ar: string })
    }
    throw new Error(`${res.status}: ${res.statusText}`)
  }
  const blob = await res.blob()
  window.open(URL.createObjectURL(blob), '_blank')
}

// ── FR-EXCHANGE Phase 2 — mapping step ───────────────────────────────────────
// Raw column-labelled response (snake_case), same convention as the exception
// detector payloads (see Exceptions.tsx) — not a hand-built camelCase DTO.

export interface ExchangeSummary {
  id: string
  tracking_number: string
  outbound_description: string | null
  inbound_description: string | null
  inbound_description_ar: string | null
  cod: number | null
  goods_value: number | null
  outbound_items_count: number | null
  inbound_items_count: number | null
  customer_name: string | null
  customer_phone: string | null
}

/** Omit status to fetch every exchange for the tenant (e.g. re-visiting a since-mapped one). */
export function getExchanges(status?: string) {
  return request<ExchangeSummary[]>(`/exchanges${status ? `?status=${status}` : ''}`)
}

export interface MapExchangeResult {
  exchangeId: string
  orderId: string
  status: string
}

export function mapExchange(id: string, outboundVariantId: string, inboundVariantId: string) {
  return request<MapExchangeResult>(`/exchanges/${id}/map`, {
    method: 'POST',
    body: JSON.stringify({ outboundVariantId, inboundVariantId }),
  })
}
