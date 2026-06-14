const BASE = '/api/v1'

function token() {
  return localStorage.getItem('token')
}

async function request<T>(path: string, opts: RequestInit = {}): Promise<T> {
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
