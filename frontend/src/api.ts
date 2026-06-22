const BASE = '/api/v1'

function token() {
  return localStorage.getItem('token')
}

export async function request<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const res = await fetch(BASE + path, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      ...(token() ? { Authorization: `Bearer ${token()}` } : {}),
      ...opts.headers,
    },
  })
  if (res.status === 401) {
    localStorage.removeItem('token')
    window.location.href = '/login'
    throw new Error('Unauthenticated')
  }
  if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`)
  return res.json()
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
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

export interface ShipmentSummary {
  id: string
  trackingNumber: string | null
  provider: string
  internalState: string
  numberOfAttempts: number
  awbUrl: string | null
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
  shipment: ShipmentSummary | null
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
  const t = token()
  if (!t) return null
  const claims = parseJwtPayload(t)
  const role = claims['role']
  if (role === 'owner' || role === 'manager' || role === 'worker') return role
  return null
}

// ── Auth: signup ──────────────────────────────────────────────────────────────

export function signup(tenantName: string, name: string, email: string, password: string) {
  return request<{ accessToken: string; refreshToken: string }>('/auth/signup', {
    method: 'POST',
    body: JSON.stringify({ tenantName, name, email, password }),
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
  }
}

export function getConnections() {
  return request<ConnectionsStatus>('/connections')
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

// ── Tenant settings (FR-1.4) ──────────────────────────────────────────────────

export interface TenantSettings {
  name: string
  pickupAddress: string | null
  labelSize: '40x25' | '50x25'
  defaultLanguage: 'ar' | 'en'
  timezone: string
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
