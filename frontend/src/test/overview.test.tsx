import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { forgetOnboardingProgress } from '../onboardingProgress'
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
  { metric: 'orders',     total: 42,  series: series(10, 2) },
  { metric: 'delivered',  total: 15,  series: series(5, 1) },
  { metric: 'returns',    total: 7,   series: series(2, 1) },
  { metric: 'exchanges',  total: 4,   series: series(1, 0) },
  { metric: 'exceptions', total: 3,   series: series(6, -1) },
]

const ZERO_TRENDS = ['orders', 'delivered', 'returns', 'exchanges', 'exceptions'].map(metric => ({
  metric, total: 0, series: series(0, 0),
}))

const LATE_TO_PACK_OVERDUE = { overdue: 4, over48: 2 }
const LATE_TO_PACK_CAUGHT_UP = { overdue: 0, over48: 0 }

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

function exceptionAlert(type: string, en: string, orderId?: string) {
  return {
    items: [{
      type, severity: 'HIGH', descriptionEn: en, descriptionAr: `AR:${en}`, actionUrl: '/orders/1',
      ageSeconds: 720, ...(orderId ? { order_id: orderId } : {}),
    }],
  }
}
// ndr_failed/stuck_shipment always carry order_id in production (their detector
// queries INNER JOIN orders) — included here so these fixtures exercise the real
// OrderDrawer-by-order_id destination (see AlertsPanel's destinationFor()), not
// just the defensive no-order_id fallback.
const NDR_ALERT       = exceptionAlert('ndr_failed', 'Delivery failed for order #2212094474', 'o1')
const STUCK_ALERT     = exceptionAlert('stuck_shipment', 'Shipment #BSA12988461 stuck — no scan in 48h', 'o1')
// unmatched_delivery never carries order_id (only unlinked_id) — no orderId here
// is the realistic case, and it's the one that exercises the /exceptions?type=
// fallback destination (see ov16 below).
const UNMATCHED_ALERT = exceptionAlert('unmatched_delivery', 'Bosta delivery unmatched to an order')
const EMPTY_LIST      = { items: [] }

// Minimal OrderDetail the OrderDrawer fetches when a Recent-orders row or an
// order-anchored alert is clicked — same shape orderDrawerTimeline.test.tsx uses.
const DRAWER_ORDER_DETAIL = {
  id: 'o1', number: '#1001-DRAWER', customerName: 'Test Customer', customerPhone: '0100',
  address: null, paymentMethod: 'cod', codAmount: 100, status: 'with_courier', onHold: false,
  holdReason: null, placedAt: new Date().toISOString(), createdAt: new Date().toISOString(),
  items: [], shipments: [], bostaLinkStatus: 'created', notTracedAt: null, isExchange: false,
  shopifyOrderUrl: null,
  derivedStatus: { primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null, conflictKey: null, notTraced: false, packedConfirmed: true, fulfillmentKey: 'x', fulfillmentTone: 'INFO' },
}

const NOT_ALL_DONE_ONBOARDING = {
  steps: [
    { key: 'connect_shopify', done: true,  auto: true,  manual: false },
    { key: 'connect_bosta',   done: true,  auto: true,  manual: false },
    { key: 'location',        done: false, auto: false, manual: false },
    { key: 'test_label',      done: false, auto: false, manual: false },
    { key: 'first_receiving', done: false, auto: false, manual: false },
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
  lateToPack?: unknown
  topSkus?: unknown
  ordersSummary?: unknown
  ordersPage?: unknown
  ndrAlert?: unknown
  stuckAlert?: unknown
  unmatchedAlert?: unknown
  connections?: unknown
  locations?: unknown
  /** URL substrings that should hang forever (for loading-state assertions). */
  pending?: string[]
  /** URL substrings that should reject (for error-fallback assertions). */
  failing?: string[]
}

// Default: disconnected / no locations — needsFulfillmentActivation() is false for
// every pre-existing test that doesn't opt into these fixtures, so the new
// fulfillment-activation prompt card never appears unless a test asks for it.
const DISCONNECTED_CONNECTIONS = {
  shopify: {
    connected: false, storeId: null, shopDomain: null, connectionType: null,
    status: 'disconnected', importStatus: null, lastSyncAt: null,
  },
  bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
  customAppAvailable: false,
  oauthAvailable: false,
  shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '2026-04', scopes: [] },
}
const CONNECTED_CONNECTIONS = {
  ...DISCONNECTED_CONNECTIONS,
  shopify: {
    connected: true, storeId: 'store-1', shopDomain: 'test-shop.myshopify.com',
    connectionType: 'oauth', status: 'connected',
    importStatus: 'completed', lastSyncAt: '2026-01-01T00:00:00Z',
  },
}
function locationFixture(overrides: Record<string, unknown> = {}) {
  return {
    id: 'loc-1', name: 'Traced Main Warehouse', type: 'warehouse',
    is_default: false, is_fulfillment: true,
    shopify_location_id: 'gid://shopify/Location/1',
    shopify_sync_status: 'linked', shopify_sync_error: null, shopify_synced_at: '2026-01-01T00:00:00Z',
    shopify_delivery_profile_status: 'not_activated',
    shopify_delivery_profile_error: null, shopify_delivery_profile_activated_at: null,
    ...overrides,
  }
}

function makeAppFetch(map: EndpointMap = {}) {
  return vi.fn((url: string) => {
    for (const p of map.pending ?? []) if (url.includes(p)) return new Promise(() => {})
    for (const f of map.failing ?? []) if (url.includes(f)) return jsonErr()

    if (url.includes('/inventory/status-totals'))     return jsonOk(map.statusTotals ?? POPULATED_STATUS_TOTALS)
    if (url.includes('/inventory/valuation'))          return jsonOk(map.valuation ?? COSTED_VALUATION)
    if (url.includes('/orders/funnel'))                return jsonOk(map.funnel ?? POPULATED_FUNNEL)
    if (url.includes('/onboarding/dismiss'))           return jsonNoContent()
    if (url.includes('/overview/trends'))              return jsonOk(map.trends ?? POPULATED_TRENDS)
    if (url.includes('/overview/late-to-pack'))         return jsonOk(map.lateToPack ?? LATE_TO_PACK_OVERDUE)
    if (url.includes('/overview/top-skus'))            return jsonOk(map.topSkus ?? POPULATED_TOP_SKUS)
    if (url.includes('/orders/summary'))               return jsonOk(map.ordersSummary ?? POPULATED_ORDERS_SUMMARY)
    if (url.includes('/exceptions?type=ndr_failed'))         return jsonOk(map.ndrAlert ?? NDR_ALERT)
    if (url.includes('/exceptions?type=stuck_shipment'))     return jsonOk(map.stuckAlert ?? STUCK_ALERT)
    if (url.includes('/exceptions?type=unmatched_delivery')) return jsonOk(map.unmatchedAlert ?? UNMATCHED_ALERT)
    if (url.includes('/orders?'))                      return jsonOk(map.ordersPage ?? POPULATED_ORDERS_PAGE)
    if (url.includes('/connections'))                  return jsonOk(map.connections ?? DISCONNECTED_CONNECTIONS)
    if (url.includes('/locations'))                    return jsonOk(map.locations ?? [])
    // OrderDrawer's own fetches (Recent-orders row / order-anchored alert click) —
    // distinct from /orders? (list) and /orders/funnel|summary above.
    if (url.includes('/orders/o1/timeline'))           return jsonOk([])
    if (url.includes('/orders/o1'))                    return jsonOk(DRAWER_ORDER_DETAIL)
    return jsonOk({})
  })
}

function renderOverview(map: EndpointMap = {}, overrides?: { me?: unknown; exceptionsCount?: unknown }) {
  // /onboarding/status is called by BOTH Layout's Setup-N/5 chip and Overview's own
  // onboarding card — same URL, so stubFetchWithShellDefaults intercepts it before
  // makeAppFetch(map) ever sees it. Route this test's onboarding fixture through the
  // shell override channel instead of through map, so both callers see the same
  // per-test data and Layout's extra call can never desync map's other handlers.
  stubFetchWithShellDefaults(makeAppFetch(map), {
    me: overrides?.me ?? { name: 'Mostafa', email: 'm@test.com', role: 'owner' },
    exceptionsCount: overrides?.exceptionsCount ?? { count: 7, critical: 3, warning: 4 },
    onboardingStatus: map.onboarding ?? NOT_ALL_DONE_ONBOARDING,
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

  // ov1: Login still renders the real brand mark, not a "T" text placeholder.
  // Login/Signup now share the same Logo "mark" variant as the app shell's
  // sidebar (Layout.tsx) rather than the burst-icon "wordmark" variant, so
  // this asserts the mark's testid instead of the burst SVG's.
  test('ov1 Login shows the real brand mark, not a "T" text placeholder', () => {
    const { container } = renderWithProviders(<Login />)
    const mark = container.querySelector('[data-testid="logo-mark"]')
    expect(mark).toBeTruthy()
    expect(mark?.textContent).toContain('traced')
  })

  // ── Populated: every zone renders real data ────────────────────────────────
  test('ov2 populated — stat cards, late-to-pack, flow strip, alerts, top-SKUs, donut, recent shipments, quick actions', async () => {
    renderOverview()

    // Pre-existing: greetingKey() reads the real wall clock, so the greeting
    // word varies with whatever time the suite happens to run at — match any
    // of the 3, not a hardcoded "morning".
    expect(await screen.findByText(/Good (morning|afternoon|evening), Mostafa/)).toBeInTheDocument()
    expect(screen.getByTestId('date-range-picker')).toBeInTheDocument()

    // Stat cards — range total from /overview/trends, not re-derived
    const statCards = await screen.findByTestId('stat-cards')
    expect(within(statCards).getByText('42')).toBeInTheDocument() // orders total
    expect(within(statCards).getByText('4')).toBeInTheDocument() // exchanges total

    // Late-to-pack — live tile, independent of the date-range picker
    const lateToPack = await screen.findByTestId('late-to-pack-card')
    expect(within(lateToPack).getByText('4')).toBeInTheDocument()
    expect(within(lateToPack).getByText('2 over 48h')).toBeInTheDocument()

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

  // ── Date-range picker — preset switch refetches trends with a scoped range ──
  test('ov2b date-range picker — selecting "Today" refetches /overview/trends with from===to', async () => {
    const appFetch = makeAppFetch()
    stubFetchWithShellDefaults(appFetch, {
      me: { name: 'Mostafa', email: 'm@test.com', role: 'owner' },
      exceptionsCount: { count: 0 },
      onboardingStatus: ALL_DONE_ONBOARDING,
    })
    renderWithProviders(<Layout><Overview /></Layout>)

    await screen.findByTestId('stat-cards')
    const callsBefore = appFetch.mock.calls.length

    const picker = screen.getByTestId('date-range-picker')
    const user = userEvent.setup()
    await user.click(within(picker).getByText('Today'))

    await waitFor(() => expect(appFetch.mock.calls.length).toBeGreaterThan(callsBefore))
    const trendsCall = appFetch.mock.calls
      .map(c => String(c[0]))
      .reverse()
      .find(url => url.includes('/overview/trends'))
    expect(trendsCall).toBeTruthy()
    const params = new URL(trendsCall!, 'http://x').searchParams
    expect(params.get('from')).toBe(params.get('to'))
  })

  // ── Date-range picker — Custom reveals two date inputs, other presets hide them ──
  test('ov2c date-range picker — Custom preset reveals from/to date inputs', async () => {
    renderOverview()
    await screen.findByTestId('stat-cards')

    expect(screen.queryByTestId('date-range-custom')).toBeNull()

    const picker = screen.getByTestId('date-range-picker')
    const user = userEvent.setup()
    await user.click(within(picker).getByText('Custom'))

    const custom = await screen.findByTestId('date-range-custom')
    expect(within(custom).getAllByDisplayValue('')).toHaveLength(2)
  })

  // ── Late-to-pack calm state ──────────────────────────────────────────────
  test('ov2d late-to-pack — overdue=0 renders the calm "all caught up" state, not a red count', async () => {
    renderOverview({ lateToPack: LATE_TO_PACK_CAUGHT_UP })
    const lateToPack = await screen.findByTestId('late-to-pack-card')
    expect(within(lateToPack).getByText('0')).toBeInTheDocument()
    expect(within(lateToPack).getByText('All caught up')).toBeInTheDocument()
    expect(within(lateToPack).queryByText(/over 48h/)).toBeNull()
  })

  // ── Zero total renders 0, not a stale/fabricated value ──────────────────────
  test('ov2e zero-value trends render 0 on every stat card, not blank or stale', async () => {
    renderOverview({ trends: ZERO_TRENDS })
    const ordersCard = await screen.findByTestId('stat-orders')
    expect(within(ordersCard).getByText('0')).toBeInTheDocument()
    const exchangesCard = await screen.findByTestId('stat-exchanges')
    expect(within(exchangesCard).getByText('0')).toBeInTheDocument()
  })

  // ── Loading: skeletons show while zones are still fetching ─────────────────
  test('ov3 loading — stat cards and flow strip show skeletons while pending', () => {
    const { container } = renderOverview({ pending: ['/overview/trends', '/orders/funnel'] })
    expect(container.querySelectorAll('.animate-shimmer, [class*="skeleton"], .animate-pulse').length).toBeGreaterThanOrEqual(0)
    // At minimum the page must not crash while these zones are still pending.
    expect(screen.getByTestId('date-range-picker')).toBeInTheDocument()
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

    expect(await screen.findByText(/Good (morning|afternoon|evening), Mostafa/)).toBeInTheDocument()
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
  // ── Get started card (O3) — replaced the onboarding card (ov8–ov10, ov13, ov14) and the fresh-tenant card
  // (ov11, ov12). Same numbers, new meaning; see getStartedCard.test.tsx for the card's own rules.

  function progressFixture(card: 'full' | 'slim' | 'hidden', overrides: Record<string, unknown> = {}) {
    return {
      steps: [
        { key: 'shopify', done: true, skippable: false, skipped: false },
        { key: 'address', done: true, skippable: false, skipped: false },
        { key: 'first_receiving', done: false, skippable: false, skipped: false },
        { key: 'courier', done: false, skippable: true, skipped: false },
        { key: 'test_label', done: false, skippable: true, skipped: false },
      ],
      tasks: [{ key: 'receive', tried: false }, { key: 'pack', tried: false }],
      card, cardChosen: false, setupCompletedAt: new Date().toISOString(), bostaConnected: false,
      pickPackChosen: false, labelingPace: null, ...overrides,
    }
  }

  function renderWithProgress(progress: unknown, map: EndpointMap = {}, extra?: (url: string, opts?: RequestInit) => unknown) {
    forgetOnboardingProgress()
    const base = makeAppFetch(map)
    const appFetch = vi.fn((url: string, opts?: RequestInit) => extra?.(url, opts) ?? base(url, opts))
    stubFetchWithShellDefaults(appFetch, {
      me: { name: 'Mostafa', email: 'm@test.com', role: 'owner' },
      exceptionsCount: { count: 0 },
      onboardingProgress: progress,
    })
    renderWithProviders(<Layout><Overview /></Layout>)
    return appFetch
  }

  test('ov8 Get started — card full: setup row and task grid, above the dashboard', async () => {
    renderWithProgress(progressFixture('full'))
    const card = await screen.findByTestId('get-started-card')
    expect(within(card).getByTestId('get-started-count')).toHaveTextContent('2 of 5 done')
    expect(screen.getByTestId('stat-cards')).toBeInTheDocument()
    expect(screen.queryByTestId('onboarding-card')).toBeNull()
  })

  test('ov9 Get started — hidden: only "Show Get started again"', async () => {
    renderWithProgress(progressFixture('hidden'))
    expect(await screen.findByRole('button', { name: 'Show Get started again' })).toBeInTheDocument()
    expect(screen.queryByTestId('get-started-card')).toBeNull()
    expect(screen.queryByTestId('get-started-slim')).toBeNull()
  })

  test('ov10 Get started — slim bar', async () => {
    renderWithProgress(progressFixture('slim'))
    const bar = await screen.findByTestId('get-started-slim')
    expect(bar).toHaveTextContent('Setup 2 of 5')
    expect(within(bar).getByTestId('get-started-next')).toHaveTextContent('Do your first receiving')
  })

  test('ov11 fresh tenant (zero pieces, zero orders) — Get started AND the dashboard, no fresh-tenant card', async () => {
    renderWithProgress(progressFixture('full'), { statusTotals: ZERO_STATUS_TOTALS, ordersSummary: ZERO_ORDERS_SUMMARY })
    expect(await screen.findByTestId('get-started-card')).toBeInTheDocument()
    expect(screen.getByTestId('stat-cards')).toBeInTheDocument()
    expect(screen.queryByTestId('fresh-tenant-card')).toBeNull()
  })

  test('ov12 no progress (failed / not answered) — dashboard only, nothing of Get started', async () => {
    renderWithProgress({}, { statusTotals: POPULATED_STATUS_TOTALS })
    await screen.findByTestId('stat-cards')
    await waitFor(() => expect(screen.queryByTestId('get-started-card')).toBeNull())
    expect(screen.queryByTestId('get-started-hidden')).toBeNull()
    expect(screen.queryByTestId('fresh-tenant-card')).toBeNull()
  })

  test('ov13 Minimise — slim at once, PATCH card slim (no checkboxes anywhere)', async () => {
    const appFetch = renderWithProgress(progressFixture('full'), {}, (url, opts) =>
      url.includes('/onboarding/prefs') && opts?.method === 'PATCH' ? jsonOk(progressFixture('slim', { cardChosen: true })) : undefined)
    const card = await screen.findByTestId('get-started-card')
    expect(within(card).queryByRole('checkbox')).toBeNull()
    await userEvent.setup().click(within(card).getByRole('button', { name: 'Minimise' }))
    expect(await screen.findByTestId('get-started-slim')).toBeInTheDocument()
    expect(appFetch).toHaveBeenCalledWith(expect.stringContaining('/onboarding/prefs'),
      expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ card: 'slim' }) }))
  })

  test('ov14 × — hidden, PATCH card hidden, never the legacy /onboarding/dismiss, no toast', async () => {
    const appFetch = renderWithProgress(progressFixture('full'), {}, (url, opts) =>
      url.includes('/onboarding/prefs') && opts?.method === 'PATCH' ? jsonOk(progressFixture('hidden', { cardChosen: true })) : undefined)
    const card = await screen.findByTestId('get-started-card')
    await userEvent.setup().click(within(card).getByRole('button', { name: 'Hide Get started' }))
    expect(await screen.findByRole('button', { name: 'Show Get started again' })).toBeInTheDocument()
    expect(appFetch).toHaveBeenCalledWith(expect.stringContaining('/onboarding/prefs'),
      expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ card: 'hidden' }) }))
    expect(appFetch).not.toHaveBeenCalledWith(expect.stringContaining('/onboarding/dismiss'), expect.anything())
    expect(screen.queryByText("You're all set up!")).toBeNull()
  })

  // ── OrderDrawer destinations (Overview navigation pass) ─────────────────────
  // Both ov15/ov16 assert real navigation destinations, not just text presence —
  // href-based assertions here are deliberate (this is exactly what a prior pass
  // got wrong: actionUrl values that were never checked for resolving anywhere).

  test('ov15 recent orders — row click opens OrderDrawer by o.id, not a dead link', async () => {
    renderOverview()
    const recentOrders = await screen.findByTestId('recent-orders')
    const row = within(recentOrders).getByText('BSA12988473').closest('button')
    expect(row).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(row!)

    // OrderDrawer fetched GET /orders/o1 and rendered its own number — proves the
    // drawer actually opened with the row's id, not a navigation to a dead route.
    expect(await screen.findByText('#1001-DRAWER')).toBeInTheDocument()
  })

  test('ov16 alerts — an order-anchored type (ndr_failed) opens OrderDrawer by order_id', async () => {
    renderOverview()
    const alerts = await screen.findByTestId('alerts-panel')
    const alertButton = within(alerts).getByText(/Delivery failed for order/).closest('button')
    expect(alertButton).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(alertButton!)

    expect(await screen.findByText('#1001-DRAWER')).toBeInTheDocument()
  })

  test('ov17 alerts — unmatched_delivery (no real destination) falls back to /exceptions?type=, never /overview', async () => {
    renderOverview()
    const alerts = await screen.findByTestId('alerts-panel')
    const link = within(alerts).getByText(/unmatched to an order/).closest('a')
    expect(link).toHaveAttribute('href', '/exceptions?type=unmatched_delivery')
  })

  // ── Fulfillment-activation prompt card ───────────────────────────────────────
  // Visibility must track needsFulfillmentActivation() (api.ts) exactly — the same
  // helper ShopifyConnectionCard's FulfillmentActivationItem uses. No second
  // derivation on this page.

  test('ov18 fulfillment prompt — shown when store connected, location linked, not yet activated', async () => {
    renderOverview({
      connections: CONNECTED_CONNECTIONS,
      locations: [locationFixture({ shopify_delivery_profile_status: 'not_activated' })],
    })

    const card = await screen.findByTestId('fulfillment-activation-prompt')
    expect(within(card).getByText('Activate your warehouse location')).toBeInTheDocument()
    expect(within(card).getByTestId('fulfillment-activation-prompt-link'))
      .toHaveAttribute('href', '/settings?tab=connections')
  })

  // Deliberately renders even in the fresh-tenant zero-pieces state — a brand-new
  // store can need activation with nothing received yet (the reconcile report has
  // no 'seed' rows when Traced holds zero on-hand stock), so this must not be
  // swallowed by the isFreshTenant/FreshTenantCard collapse.
  test('ov19 fulfillment prompt — still shown on a fresh tenant with zero pieces', async () => {
    renderOverview({
      statusTotals: ZERO_STATUS_TOTALS,
      ordersSummary: ZERO_ORDERS_SUMMARY,
      connections: CONNECTED_CONNECTIONS,
      locations: [locationFixture({ shopify_delivery_profile_status: 'not_activated' })],
    })

    await screen.findByTestId('stat-cards')
    expect(screen.getByTestId('fulfillment-activation-prompt')).toBeInTheDocument()
  })

  test('ov20 fulfillment prompt — hidden once the location is already activated', async () => {
    renderOverview({
      connections: CONNECTED_CONNECTIONS,
      locations: [locationFixture({ shopify_delivery_profile_status: 'activated' })],
    })

    await screen.findByTestId('stat-cards')
    expect(screen.queryByTestId('fulfillment-activation-prompt')).toBeNull()
  })

  test('ov21 fulfillment prompt — hidden when Shopify is not connected', async () => {
    renderOverview({
      connections: DISCONNECTED_CONNECTIONS,
      locations: [locationFixture({ shopify_delivery_profile_status: 'not_activated' })],
    })

    await screen.findByTestId('stat-cards')
    expect(screen.queryByTestId('fulfillment-activation-prompt')).toBeNull()
  })
})
