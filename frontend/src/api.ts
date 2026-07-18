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
}

export interface OrderListParams {
  status?: string
  q?: string
  tracking?: string
  page?: number
  size?: number
}

export function listOrders(params: OrderListParams = {}) {
  const q = new URLSearchParams()
  if (params.status)   q.set('status', params.status)
  if (params.q)        q.set('q', params.q)
  if (params.tracking) q.set('tracking', params.tracking)
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  return request<OrderPage>(`/orders?${q}`)
}

export function getOrder(id: string) {
  return request<OrderDetail>(`/orders/${id}`)
}

export interface DayCount { date: string; count: number }
export function getOrderDailyCounts(days = 30) {
  return request<DayCount[]>(`/orders/daily-counts?days=${days}`)
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
  total: number
}

export interface CatalogVariant {
  id: string
  title: string
  sku: string | null
  price: number | null
  pieceCounts: PieceCounts
}

export interface CatalogProduct {
  id: string
  title: string
  status: string
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
}

export function getOnboardingStatus() {
  return request<OnboardingStatus>('/onboarding/status')
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

export interface PieceSummary {
  id: string
  barcode: string
  status: string
  variantTitle: string
  sku: string | null
  productTitle: string
  locationName: string | null
  lastEventAt: string | null
}

export interface PiecePage {
  items: PieceSummary[]
  total: number
  page: number
  size: number
}

export function listPieces(params: {
  status: string
  within30d?: boolean
  page?: number
  size?: number
}) {
  const q = new URLSearchParams()
  q.set('status', params.status)
  if (params.within30d) q.set('within30d', 'true')
  if (params.page  != null) q.set('page',  String(params.page))
  if (params.size  != null) q.set('size',  String(params.size))
  return request<PiecePage>(`/pieces?${q}`)
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
