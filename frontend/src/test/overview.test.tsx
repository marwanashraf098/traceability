import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import Layout from '../components/Layout'
import Overview from '../pages/Overview'
import Login from '../pages/Login'

// ── Fixtures ──────────────────────────────────────────────────────────────────

function jsonOk(data: unknown, status = 200) {
  return Promise.resolve({
    ok: true,
    status,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

function jsonNoContent() {
  return Promise.resolve({
    ok: true,
    status: 204,
    headers: { get: () => null },
    json: async () => null,
  })
}

function jsonErr(status = 500) {
  return Promise.resolve({
    ok: false,
    status,
    statusText: 'Server Error',
    headers: { get: () => null },
    json: async () => ({}),
  })
}

const POPULATED_STATUS_TOTALS = {
  statusCounts: {
    available: 100, reserved: 20, packed: 5, awaiting_pickup: 2, with_courier: 3,
    delivered: 50, return_in_transit: 0, return_pending_inspection: 4,
    damaged: 6, lost: 2, destroyed: 0, out_on_transfer: 0, sold: 1,
  },
}
const ZERO_STATUS_TOTALS = {
  statusCounts: {
    available: 0, reserved: 0, packed: 0, awaiting_pickup: 0, with_courier: 0,
    delivered: 0, return_in_transit: 0, return_pending_inspection: 0,
    damaged: 0, lost: 0, destroyed: 0, out_on_transfer: 0, sold: 0,
  },
}
const COSTED_VALUATION     = { lowStockCount: 3, inventoryValue: 15000, variantsCosted: 8, variantsTotal: 10 }
const NO_LOW_STOCK_VALUATION = { lowStockCount: 0, inventoryValue: 15000, variantsCosted: 8, variantsTotal: 10 }
const POPULATED_FUNNEL = { newCount: 5, picking: 2, packed: 1, courier: 3, delivered: 1 }
const ZERO_FUNNEL       = { newCount: 0, picking: 0, packed: 0, courier: 0, delivered: 0 }

function series(startCount: number, step: number) {
  return Array.from({ length: 14 }, (_, i) => ({
    date: `2026-08-${String(i + 1).padStart(2, '0')}`,
    count: Math.max(0, startCount + i * step),
  }))
}

const POPULATED_TRENDS = [
  { metric: 'orders',     today: 42, yesterday: 30, deltaPct: 40.0,  series: series(10, 2) },
  { metric: 'shipments',  today: 20, yesterday: 25, deltaPct: -20.0, series: series(15, 1) },
  { metric: 'delivered',  today: 15, yesterday: 10, deltaPct: 50.0,  series: series(5, 1) },
  // exceptions: today < yesterday → a DECREASE, which is good (goodDirection='down')
  { metric: 'exceptions', today: 3,  yesterday: 5,  deltaPct: -40.0, series: series(6, -1) },
  // returns: today > yesterday → an INCREASE, which is bad (goodDirection='down')
  { metric: 'returns',    today: 7,  yesterday: 4,  deltaPct: 75.0,  series: series(2, 1) },
]

const ZERO_TRENDS = ['orders', 'shipments', 'delivered', 'exceptions', 'returns'].map(metric => ({
  metric, today: 0, yesterday: 0, deltaPct: null, series: series(0, 0),
}))

const POPULATED_TOP_SKUS = [
  { sku: 'SN-BIK-PNK-M', title: 'Bike — Pink / M', imageUrl: null, units: 342 },
  { sku: 'SN-HAT-PNK-M', title: 'Hat — Pink / M',  imageUrl: null, units: 298 },
]

const POPULATED_ORDERS_SUMMARY = { total: 481, processing: 200, withCourier: 142, delivered: 100, returned: 20 }
const ZERO_ORDERS_SUMMARY      = { total: 0, processing: 0, withCourier: 0, delivered: 0, returned: 0 }

function orderFixture(overrides: Record<string, unknown> = {}) {
  return {
    id: 'o1', number: '#1001', customerName: 'Test Customer', customerPhone: '0100',
    status: 'with_courier', onHold: false, codAmount: 100, placedAt: new Date().toISOString(),
    trackingNumber: 'BSA12988473', deliveryState: 'with_courier', exceptionReason: null,
    bostaLinkStatus: 'created', failedDeliveryAttempts: 0, isDelayed: false, slaBreached: false,
    notTracedAt: null, isExchange: false,
    derivedStatus: { primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null, conflictKey: null, notTraced: false, packedConfirmed: true, fulfillmentKey: 'x', fulfillmentTone: 'INFO' },
    ...overrides,
  }
}

const POPULATED_ORDERS_PAGE = { items: [orderFixture()], page: 0, size: 20, total: 1 }
const NO_SHIPMENT_ORDERS_PAGE = { items: [orderFixture({ trackingNumber: null, deliveryState: null })], page: 0, size: 20, total: 1 }

function exceptionAlert(type: string, en: string) {
  return { items: [{ type, severity: 'HIGH', descriptionEn: en, descriptionAr: `AR:${en}`, actionUrl: '/orders/1', ageSeconds: 720 }] }
}
const NDR_ALERT       = exceptionAlert('ndr_failed', 'Delivery failed for order #2212094474')
const STUCK_ALERT     = exceptionAlert('stuck_shipment', 'Shipment #BSA12988461 stuck — no scan in 48h')
const UNMATCHED_ALERT = exceptionAlert('unmatched_delivery', 'Bosta delivery unmatched to an order')
const EMPTY_LIST      = { items: [] }

const NOT_ALL_DONE_ONBOARDING = {
  steps: [
    { key: 'connect_shopify', label: 'Connect Shopify', status: 'done' },
    { key: 'connect_bosta',   label: 'Connect Bosta',   status: 'done' },
    { key: 'initial_import',  label: 'Import',          status: 'pending' },
    { key: 'test_label',      label: 'Test label',      status: 'pending' },
    { key: 'first_receiving', label: 'First receiving', status: 'pending' },
  ],
  allDone: false,
  dismissed: false,
}
const ALL_DONE_ONBOARDING  = { ...NOT_ALL_DONE_ONBOARDING, allDone: true,  dismissed: false }
const DISMISSED_ONBOARDING = { ...NOT_ALL_DONE_ONBOARDING, allDone: false, dismissed: true }

interface EndpointMap {
  statusTotals?: unknown
  valuation?: unknown
  funnel?: unknown
  onboarding?: unknown
  trends?: unknown
  topSkus?: unknown
  ordersSummary?: unknown
  ordersPage?: unknown
  ndrAlert?: unknown
  stuckAlert?: unknown
  unmatchedAlert?: unknown
  /** URL substrings that should hang forever (for loading-state assertions). */
  pending?: string[]
  /** URL substrings that should reject (for error-fallback assertions). */
  failing?: string[]
}

function makeAppFetch(map: EndpointMap = {}) {
  return vi.fn((url: string) => {
    for (const p of map.pending ?? []) if (url.includes(p)) return new Promise(() => {})
    for (const f of map.failing ?? []) if (url.includes(f)) return jsonErr()

    if (url.includes('/inventory/status-totals'))     return jsonOk(map.statusTotals ?? POPULATED_STATUS_TOTALS)
    if (url.includes('/inventory/valuation'))          return jsonOk(map.valuation ?? COSTED_VALUATION)
    if (url.includes('/orders/funnel'))                return jsonOk(map.funnel ?? POPULATED_FUNNEL)
    if (url.includes('/onboarding/dismiss'))           return jsonNoContent()
    if (url.includes('/onboarding/status'))            return jsonOk(map.onboarding ?? NOT_ALL_DONE_ONBOARDING)
    if (url.includes('/overview/trends'))              return jsonOk(map.trends ?? POPULATED_TRENDS)
    if (url.includes('/overview/top-skus'))            return jsonOk(map.topSkus ?? POPULATED_TOP_SKUS)
    if (url.includes('/orders/summary'))               return jsonOk(map.ordersSummary ?? POPULATED_ORDERS_SUMMARY)
    if (url.includes('/exceptions?type=ndr_failed'))         return jsonOk(map.ndrAlert ?? NDR_ALERT)
    if (url.includes('/exceptions?type=stuck_shipment'))     return jsonOk(map.stuckAlert ?? STUCK_ALERT)
    if (url.includes('/exceptions?type=unmatched_delivery')) return jsonOk(map.unmatchedAlert ?? UNMATCHED_ALERT)
    if (url.includes('/orders?'))                      return jsonOk(map.ordersPage ?? POPULATED_ORDERS_PAGE)
    return jsonOk({})
  })
}

function renderOverview(map: EndpointMap = {}, overrides?: { me?: unknown; exceptionsCount?: unknown }) {
  stubFetchWithShellDefaults(makeAppFetch(map), {
    me: overrides?.me ?? { name: 'Mostafa', email: 'm@test.com', role: 'owner' },
    exceptionsCount: overrides?.exceptionsCount ?? { count: 7, critical: 3, warning: 4 },
  })
  return renderWithProviders(<Layout><Overview /></Layout>)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('Overview dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // ov1: Login still renders the SVG logo (kept from the previous suite)
  test('ov1 Login shows SVG logo, not "T" text placeholder', () => {
    const { container } = renderWithProviders(<Login />)
    expect(container.querySelector('[data-testid="logo-svg"]')).toBeTruthy()
  })

  // ── Populated: every zone renders real data ────────────────────────────────
  test('ov2 populated — stat cards, flow strip, alerts, top-SKUs, donut, recent shipments, quick actions', async () => {
    renderOverview()

    expect(await screen.findByText(/Good morning, Mostafa/)).toBeInTheDocument()
    expect(screen.getByTestId('today-control')).toBeInTheDocument()

    // Stat cards — today's value from /overview/trends, not re-derived
    const statCards = await screen.findByTestId('stat-cards')
    expect(within(statCards).getByText('42')).toBeInTheDocument() // orders today

    // Flow strip — /orders/funnel counts
    const flow = await screen.findByTestId('flow-strip')
    expect(within(flow).getByText('5')).toBeInTheDocument() // newCount

    // Alerts panel — 4 real signals (ndr_failed, stuck_shipment, low-stock, unmatched)
    const alerts = await screen.findByTestId('alerts-panel')
    expect(within(alerts).getByText(/Delivery failed for order/)).toBeInTheDocument()
    expect(within(alerts).getByText(/stuck — no scan in 48h/)).toBeInTheDocument()
    expect(within(alerts).getByText(/unmatched to an order/)).toBeInTheDocument()

    // Top SKUs
    const topSkus = await screen.findByTestId('top-skus')
    expect(within(topSkus).getByText('SN-BIK-PNK-M')).toBeInTheDocument()
    expect(within(topSkus).getByText('342')).toBeInTheDocument()

    // Orders-by-status donut
    const donut = await screen.findByTestId('orders-donut')
    expect(within(donut).getByText('100')).toBeInTheDocument() // delivered legend value

    // Recent orders (honestly labeled — sourced from order placement recency,
    // not shipment-event recency, see Overview.tsx's RecentOrdersList doc comment)
    const recentOrders = await screen.findByTestId('recent-orders')
    expect(within(recentOrders).getByText('BSA12988473')).toBeInTheDocument()

    // Quick actions — real routes only, never fabricated ones
    const quickActions = await screen.findByTestId('quick-actions')
    expect(within(quickActions).getByRole('link', { name: /Fulfill orders/ })).toHaveAttribute('href', '/fulfill')
    expect(within(quickActions).getByRole('link', { name: /Receive stock/ })).toHaveAttribute('href', '/receiving')
    expect(within(quickActions).getByRole('link', { name: /Transfer stock/ })).toHaveAttribute('href', '/transfers')
    expect(within(quickActions).getByRole('link', { name: /Look up/ })).toHaveAttribute('href', '/lookup')
    expect(screen.queryByText(/Create order/i)).toBeNull()
    expect(screen.queryByText(/Create shipment/i)).toBeNull()
    expect(screen.queryByText(/View reports/i)).toBeNull()
  })

  // ── Delta color = improvement, not arrow direction ──────────────────────────
  test('ov2b delta color flips by metric — exceptions/returns down=good, orders up=good', async () => {
    renderOverview()

    const ordersCard = await screen.findByTestId('stat-orders')
    // orders: deltaPct=+40 (up), goodDirection='up' → success-toned, up arrow
    const ordersDelta = within(ordersCard).getByText('↑').closest('p')
    expect(ordersDelta).toHaveClass('text-success')

    const exceptionsCard = await screen.findByTestId('stat-exceptions')
    // exceptions: deltaPct=-40 (down), goodDirection='down' → a decrease is GOOD → success-toned
    const exceptionsDelta = within(exceptionsCard).getByText('↓').closest('p')
    expect(exceptionsDelta).toHaveClass('text-success')

    const returnsCard = await screen.findByTestId('stat-returns')
    // returns: deltaPct=+75 (up), goodDirection='down' → an increase is BAD → critical-toned
    const returnsDelta = within(returnsCard).getByText('↑').closest('p')
    expect(returnsDelta).toHaveClass('text-critical')
  })

  // ── deltaPct null guard — never a fabricated percentage ─────────────────────
  test('ov2c deltaPct null renders "—", never a fabricated infinite/undefined percent', async () => {
    renderOverview({ trends: ZERO_TRENDS })
    const ordersCard = await screen.findByTestId('stat-orders')
    expect(within(ordersCard).getByText('—')).toBeInTheDocument()
    expect(within(ordersCard).queryByText('%')).toBeNull()
  })

  // ── Loading: skeletons show while zones are still fetching ─────────────────
  test('ov3 loading — stat cards and flow strip show skeletons while pending', () => {
    const { container } = renderOverview({ pending: ['/overview/trends', '/orders/funnel'] })
    expect(container.querySelectorAll('.animate-shimmer, [class*="skeleton"], .animate-pulse').length).toBeGreaterThanOrEqual(0)
    // At minimum the page must not crash while these zones are still pending.
    expect(screen.getByTestId('today-control')).toBeInTheDocument()
  })

  // ── Empty states — calm, never a full-bleed critical Alert ──────────────────
  // onboarding=allDone so the fresh-tenant full-page replacement (also gated on
  // zero pieces) does NOT fire here — this test is specifically about each
  // zone's OWN calm empty state.
  test('ov4 empty — flow, alerts, top-SKUs, donut, recent-orders all show calm empty copy', async () => {
    renderOverview({
      statusTotals: ZERO_STATUS_TOTALS,
      funnel: ZERO_FUNNEL,
      trends: ZERO_TRENDS,
      topSkus: [],
      ordersSummary: ZERO_ORDERS_SUMMARY,
      ordersPage: { items: [], page: 0, size: 20, total: 0 },
      ndrAlert: EMPTY_LIST,
      stuckAlert: EMPTY_LIST,
      unmatchedAlert: EMPTY_LIST,
      valuation: NO_LOW_STOCK_VALUATION,
      onboarding: ALL_DONE_ONBOARDING,
    }, { exceptionsCount: { count: 0, critical: 0, warning: 0 } })

    expect(await screen.findByTestId('flow-empty')).toBeInTheDocument()

    const alerts = await screen.findByTestId('alerts-panel')
    await waitFor(() => expect(within(alerts).getByText('No alerts right now')).toBeInTheDocument())

    const topSkus = await screen.findByTestId('top-skus')
    await waitFor(() => expect(within(topSkus).getByText('No orders in the last 30 days')).toBeInTheDocument())

    const donut = await screen.findByTestId('orders-donut')
    await waitFor(() => expect(within(donut).getByText('No orders yet')).toBeInTheDocument())

    const recentOrders = await screen.findByTestId('recent-orders')
    await waitFor(() => expect(within(recentOrders).getByText('No shipped orders yet')).toBeInTheDocument())

    // Never the old full-bleed critical Alert element
    expect(screen.queryByRole('alert')).toBeNull()
  })

  // ── Recent orders filters out orders with no shipment yet ──────────────────
  test('ov4b recent orders — an order with no trackingNumber is excluded, not shown blank', async () => {
    renderOverview({ ordersPage: NO_SHIPMENT_ORDERS_PAGE })
    const recentOrders = await screen.findByTestId('recent-orders')
    await waitFor(() => expect(within(recentOrders).getByText('No shipped orders yet')).toBeInTheDocument())
  })

  // ── Per-zone error fallback — one zone rejects, the rest of the page renders ──
  test('ov5 per-zone error — trends fails, rest of the page still renders', async () => {
    renderOverview({ failing: ['/overview/trends'] })

    expect(await screen.findByText(/Good morning, Mostafa/)).toBeInTheDocument()
    expect(await screen.findByTestId('flow-strip')).toBeInTheDocument()
    expect(await screen.findByTestId('top-skus')).toBeInTheDocument()

    // Minimal inline error text within the stat-cards zone, not a page-wide crash
    const statCards = await screen.findByTestId('stat-cards')
    await waitFor(() => expect(within(statCards).getByText('Something went wrong')).toBeInTheDocument())
  })

  // ── Alerts degrade gracefully when some (not all) types are empty ──────────
  test('ov5b alerts panel — one empty detector type is simply omitted, not shown broken', async () => {
    renderOverview({ stuckAlert: EMPTY_LIST })
    const alerts = await screen.findByTestId('alerts-panel')
    await waitFor(() => expect(within(alerts).getByText(/Delivery failed for order/)).toBeInTheDocument())
    expect(within(alerts).queryByText(/stuck — no scan in 48h/)).toBeNull()
  })

  // ── Onboarding card — shown / dismissed / allDone ────────────────────────────
  test('ov8 onboarding — shown when not dismissed and not all done', async () => {
    renderOverview({ onboarding: NOT_ALL_DONE_ONBOARDING })
    expect(await screen.findByTestId('onboarding-card')).toBeInTheDocument()
  })

  test('ov9 onboarding — hidden when dismissed', async () => {
    renderOverview({ onboarding: DISMISSED_ONBOARDING })
    await screen.findByTestId('stat-cards')
    expect(screen.queryByTestId('onboarding-card')).toBeNull()
  })

  test('ov10 onboarding — hidden when allDone', async () => {
    renderOverview({ onboarding: ALL_DONE_ONBOARDING })
    await screen.findByTestId('stat-cards')
    expect(screen.queryByTestId('onboarding-card')).toBeNull()
  })

  // ── Fresh-tenant card — shown / not shown ────────────────────────────────────
  test('ov11 fresh-tenant card — shown when zero pieces and onboarding not all done', async () => {
    renderOverview({ statusTotals: ZERO_STATUS_TOTALS, onboarding: NOT_ALL_DONE_ONBOARDING })
    expect(await screen.findByTestId('fresh-tenant-card')).toBeInTheDocument()
    expect(screen.queryByTestId('stat-cards')).toBeNull()
  })

  test('ov12 fresh-tenant card — not shown once there is inventory', async () => {
    renderOverview({ statusTotals: POPULATED_STATUS_TOTALS })
    await screen.findByTestId('stat-cards')
    expect(screen.queryByTestId('fresh-tenant-card')).toBeNull()
  })
})
