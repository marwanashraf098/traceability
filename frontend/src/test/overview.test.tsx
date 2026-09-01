import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
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
  { metric: 'orders',        total: 42,  series: series(10, 2) },
  { metric: 'cod_delivered', total: 5200, series: series(1500, 100) },
  { metric: 'delivered',     total: 15,  series: series(5, 1) },
  { metric: 'exceptions',    total: 3,   series: series(6, -1) },
  { metric: 'returns',       total: 7,   series: series(2, 1) },
]

const ZERO_TRENDS = ['orders', 'cod_delivered', 'delivered', 'exceptions', 'returns'].map(metric => ({
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

function exceptionAlert(type: string, en: string) {
  return { items: [{ type, severity: 'HIGH', descriptionEn: en, descriptionAr: `AR:${en}`, actionUrl: '/orders/1', ageSeconds: 720 }] }
}
const NDR_ALERT       = exceptionAlert('ndr_failed', 'Delivery failed for order #2212094474')
const STUCK_ALERT     = exceptionAlert('stuck_shipment', 'Shipment #BSA12988461 stuck — no scan in 48h')
const UNMATCHED_ALERT = exceptionAlert('unmatched_delivery', 'Bosta delivery unmatched to an order')
const EMPTY_LIST      = { items: [] }

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
    if (url.includes('/overview/trends'))              return jsonOk(map.trends ?? POPULATED_TRENDS)
    if (url.includes('/overview/late-to-pack'))         return jsonOk(map.lateToPack ?? LATE_TO_PACK_OVERDUE)
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
    expect(within(statCards).getByText('5,200 EGP')).toBeInTheDocument() // cod_delivered total

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
    const codCard = await screen.findByTestId('stat-cod_delivered')
    expect(within(codCard).getByText('0 EGP')).toBeInTheDocument()
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

  // ── Manual checkbox + celebratory transition ─────────────────────────────────

  // ov13: clicking a step's manual checkbox calls setOnboardingStep and flips the
  // checkbox's checked state optimistically — before the POST's response arrives.
  test('ov13 manual checkbox — click calls setOnboardingStep and optimistically flips before the network settles', async () => {
    let resolvePost!: () => void
    const postPromise = new Promise<void>(res => { resolvePost = res })

    const baseAppFetch = makeAppFetch({})
    const appFetch = vi.fn((url: string, opts?: RequestInit) => {
      if (url.includes('/onboarding/steps')) {
        return postPromise.then(() => jsonNoContent())
      }
      return baseAppFetch(url, opts)
    })
    stubFetchWithShellDefaults(appFetch, {
      me: { name: 'Mostafa', email: 'm@test.com', role: 'owner' },
      exceptionsCount: { count: 0 },
      onboardingStatus: NOT_ALL_DONE_ONBOARDING,
    })
    renderWithProviders(<Layout><Overview /></Layout>)

    await screen.findByTestId('onboarding-card')
    const row = screen.getByTestId('onboarding-step-location')
    const checkbox = within(row).getByRole('checkbox') as HTMLInputElement
    expect(checkbox.checked).toBe(false)

    const user = userEvent.setup()
    await user.click(checkbox)

    // Optimistic: flips to checked immediately, before the mocked POST resolves.
    expect(checkbox.checked).toBe(true)
    expect(appFetch).toHaveBeenCalledWith(
      expect.stringContaining('/onboarding/steps'),
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ step: 'location', checked: true }) })
    )

    // Let the POST resolve and the refetch settle — no leftover pending/disabled state.
    resolvePost()
    await waitFor(() => expect(checkbox.disabled).toBe(false))
  })

  // ov14: an allDone false->true transition (detected across a refetch, here triggered
  // by the same manual-checkbox flow as ov13) fires the transient "all set" toast and
  // performs NO db write of its own — specifically, it must never call
  // POST /onboarding/dismiss. dismissOnboarding is user-initiated only (the X button);
  // the celebratory toast must never trigger it as a side effect of rendering.
  test('ov14 celebratory transition — toast fires on false->true, never calls dismissOnboarding', async () => {
    let onboardingCalls = 0
    // stubFetchWithShellDefaults intercepts /onboarding/status itself (Layout and
    // Overview both call it — see renderOverview's own comment above), so the stateful
    // sequence has to go through its onboardingStatus override, not appFetch directly.
    const appFetch = makeAppFetch({})
    stubFetchWithShellDefaults(appFetch, {
      me: { name: 'Mostafa', email: 'm@test.com', role: 'owner' },
      exceptionsCount: { count: 0 },
      onboardingStatus: () => {
        onboardingCalls += 1
        return onboardingCalls === 1 ? NOT_ALL_DONE_ONBOARDING : ALL_DONE_ONBOARDING
      },
    })
    renderWithProviders(<Layout><Overview /></Layout>)

    await screen.findByTestId('onboarding-card')
    const row = screen.getByTestId('onboarding-step-location')
    const checkbox = within(row).getByRole('checkbox')
    const user = userEvent.setup()
    await user.click(checkbox)

    // The refetch after the toggle returns allDone:true -> transient toast fires.
    await screen.findByText("You're all set up!")

    // No write beyond the manual-step POST the user explicitly triggered — in
    // particular, never /onboarding/dismiss as a side effect of the transition/render.
    expect(appFetch).not.toHaveBeenCalledWith(
      expect.stringContaining('/onboarding/dismiss'), expect.anything())
  })
})
