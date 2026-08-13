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
const COSTED_VALUATION    = { lowStockCount: 3, inventoryValue: 15000, variantsCosted: 8, variantsTotal: 10 }
const NOT_SET_UP_VALUATION = { lowStockCount: 0, inventoryValue: 0, variantsCosted: 0, variantsTotal: 10 }
const POPULATED_FUNNEL = { newCount: 5, picking: 2, packed: 1, courier: 3, delivered: 1 }
const ZERO_FUNNEL       = { newCount: 0, picking: 0, packed: 0, courier: 0, delivered: 0 }
const POPULATED_THROUGHPUT = [
  { date: '2026-08-01', received: 10, shipped: 4 },
  { date: '2026-08-02', received: 0,  shipped: 6 },
]
const ZERO_THROUGHPUT = [
  { date: '2026-08-01', received: 0, shipped: 0 },
  { date: '2026-08-02', received: 0, shipped: 0 },
]
const POPULATED_ACTIVITY = [
  { type: 'receiving',  refId: 'r1',  refCode: 'REC-0092', actorName: 'Aya Farouk',  locationName: 'Main Warehouse', occurredAt: new Date().toISOString(), pieceCount: 240 },
  { type: 'stock_take', refId: 'st1', refCode: 'ST-014',   actorName: 'Karim Adel',  locationName: null,             occurredAt: new Date().toISOString(), pieceCount: null },
]
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
const ALL_DONE_ONBOARDING    = { ...NOT_ALL_DONE_ONBOARDING, allDone: true,  dismissed: false }
const DISMISSED_ONBOARDING   = { ...NOT_ALL_DONE_ONBOARDING, allDone: false, dismissed: true }
const POPULATED_EXCEPTIONS_LIST = {
  items: [{ type: 'lost', severity: 'CRITICAL', descriptionEn: 'Order #1077 — tracking conflict', descriptionAr: 'طلب #1077 — تعارض', actionUrl: '/orders/1' }],
}
const EMPTY_LIST = { items: [] }

interface EndpointMap {
  statusTotals?: unknown
  valuation?: unknown
  fulfillQueue?: unknown[]
  returnsPending?: unknown
  funnel?: unknown
  throughput?: unknown[]
  activity?: unknown[]
  onboarding?: unknown
  exceptions?: unknown
  /** URL substrings that should hang forever (for loading-state assertions). */
  pending?: string[]
  /** URL substrings that should reject (for error-fallback assertions). */
  failing?: string[]
}

function makeAppFetch(map: EndpointMap = {}) {
  return vi.fn((url: string) => {
    for (const p of map.pending ?? []) if (url.includes(p)) return new Promise(() => {})
    for (const f of map.failing ?? []) if (url.includes(f)) return jsonErr()

    if (url.includes('/inventory/status-totals')) return jsonOk(map.statusTotals ?? POPULATED_STATUS_TOTALS)
    if (url.includes('/inventory/valuation'))      return jsonOk(map.valuation ?? COSTED_VALUATION)
    if (url.includes('/fulfill/queue'))             return jsonOk(map.fulfillQueue ?? [{ id: '1' }, { id: '2' }])
    if (url.includes('/returns/pending'))           return jsonOk(map.returnsPending ?? { items: [{ id: 'p1' }], total: 9 })
    if (url.includes('/orders/funnel'))             return jsonOk(map.funnel ?? POPULATED_FUNNEL)
    if (url.includes('/inventory/throughput'))      return jsonOk(map.throughput ?? POPULATED_THROUGHPUT)
    if (url.includes('/activity/recent'))           return jsonOk(map.activity ?? POPULATED_ACTIVITY)
    if (url.includes('/onboarding/dismiss'))        return jsonNoContent()
    if (url.includes('/onboarding/status'))         return jsonOk(map.onboarding ?? NOT_ALL_DONE_ONBOARDING)
    if (url.includes('/exceptions?size=5'))         return jsonOk(map.exceptions ?? POPULATED_EXCEPTIONS_LIST)
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
  test('ov2 populated — every zone renders real numbers, greeting, and the Today control', async () => {
    renderOverview()

    expect(await screen.findByText(/Good morning, Mostafa/)).toBeInTheDocument()
    expect(screen.getByTestId('today-control')).toBeInTheDocument()

    // Needs attention
    const needsAttention = await screen.findByTestId('needs-attention')
    expect(within(needsAttention).getByText('2')).toBeInTheDocument()   // fulfill queue length
    expect(within(needsAttention).getByText('9')).toBeInTheDocument()   // returns pending total

    // Inventory health — total = available+reserved+damaged+lost = 100+20+6+2=128
    const invHealth = await screen.findByTestId('inventory-health')
    expect(within(invHealth).getByText('128')).toBeInTheDocument()
    expect(within(invHealth).getByText('100')).toBeInTheDocument()

    // Inventory value tile — costed
    const valueTile = await screen.findByTestId('inventory-value')
    expect(within(valueTile).getByText(/15,000/)).toBeInTheDocument()
    expect(within(valueTile).getByText(/8/)).toBeInTheDocument() // caveat "8 of 10"

    // At a glance
    expect(await screen.findByTestId('donut')).toBeInTheDocument()
    expect(await screen.findByTestId('funnel')).toBeInTheDocument()
    expect(await screen.findByTestId('severity')).toBeInTheDocument()

    // Throughput + activity
    expect(await screen.findByTestId('throughput')).toBeInTheDocument()
    const activity = await screen.findByTestId('activity')
    expect(within(activity).getByText(/240/)).toBeInTheDocument()
    expect(within(activity).getByText(/Aya Farouk/)).toBeInTheDocument()
    expect(within(activity).getByText(/ST-014/)).toBeInTheDocument()

    // Recent exceptions
    expect(await screen.findByText(/tracking conflict/)).toBeInTheDocument()
  })

  // ── Loading: skeletons show while zones are still fetching ─────────────────
  test('ov3 loading — needs-attention and inventory-health show skeletons while pending', () => {
    const { container } = renderOverview({ pending: ['/inventory/status-totals', '/fulfill/queue'] })
    expect(container.querySelectorAll('.animate-shimmer').length).toBeGreaterThan(0)
  })

  // ── Empty states — calm, never a full-bleed critical Alert ──────────────────
  // onboarding=allDone so the fresh-tenant full-page replacement (also gated on
  // zero pieces) does NOT fire here — this test is specifically about each
  // zone's OWN calm empty state, not the separate fresh-tenant card.
  test('ov4 empty — donut, funnel, severity, throughput, activity all show calm empty copy', async () => {
    renderOverview({
      statusTotals: ZERO_STATUS_TOTALS,
      funnel: ZERO_FUNNEL,
      throughput: ZERO_THROUGHPUT,
      activity: [],
      onboarding: ALL_DONE_ONBOARDING,
    }, { exceptionsCount: { count: 0, critical: 0, warning: 0 } })

    expect(await screen.findByTestId('donut-empty')).toBeInTheDocument()
    expect(await screen.findByTestId('funnel-empty')).toBeInTheDocument()
    expect(await screen.findByTestId('severity-empty')).toBeInTheDocument()
    expect(await screen.findByTestId('throughput-empty')).toBeInTheDocument()
    expect(await screen.findByTestId('activity-empty')).toBeInTheDocument()

    // Never the old full-bleed critical Alert element
    expect(screen.queryByRole('alert')).toBeNull()
  })

  // ── Per-zone error fallback — one zone rejects, the rest of the page renders ──
  test('ov5 per-zone error — throughput fails, rest of the page still renders', async () => {
    renderOverview({ failing: ['/inventory/throughput'] })

    expect(await screen.findByText(/Good morning, Mostafa/)).toBeInTheDocument()
    expect(await screen.findByTestId('donut')).toBeInTheDocument()
    expect(await screen.findByTestId('needs-attention')).toBeInTheDocument()

    // Minimal inline error text, not a page-wide crash
    await waitFor(() => expect(screen.getByText('Something went wrong')).toBeInTheDocument())
  })

  // ── Inventory value — M=0 not-set-up vs M>0 with caveat ─────────────────────
  test('ov6 inventory value — zero variants costed shows "not set up", never EGP 0', async () => {
    renderOverview({ valuation: NOT_SET_UP_VALUATION })
    const tile = await screen.findByTestId('inventory-value-not-set-up')
    expect(within(tile).queryByText(/EGP/)).toBeNull()
  })

  test('ov7 inventory value — costed variants show EGP amount + caveat', async () => {
    renderOverview({ valuation: COSTED_VALUATION })
    const tile = await screen.findByTestId('inventory-value')
    expect(within(tile).getByText(/EGP/)).toBeInTheDocument()
    expect(within(tile).getByText(/15,000/)).toBeInTheDocument()
  })

  // ── Onboarding card — shown / dismissed / allDone ────────────────────────────
  test('ov8 onboarding — shown when not dismissed and not all done', async () => {
    renderOverview({ onboarding: NOT_ALL_DONE_ONBOARDING })
    expect(await screen.findByTestId('onboarding-card')).toBeInTheDocument()
  })

  test('ov9 onboarding — hidden when dismissed', async () => {
    renderOverview({ onboarding: DISMISSED_ONBOARDING })
    await screen.findByTestId('needs-attention')
    expect(screen.queryByTestId('onboarding-card')).toBeNull()
  })

  test('ov10 onboarding — hidden when allDone', async () => {
    renderOverview({ onboarding: ALL_DONE_ONBOARDING })
    await screen.findByTestId('needs-attention')
    expect(screen.queryByTestId('onboarding-card')).toBeNull()
  })

  // ── Fresh-tenant card — shown / not shown ────────────────────────────────────
  test('ov11 fresh-tenant card — shown when zero pieces and onboarding not all done', async () => {
    renderOverview({ statusTotals: ZERO_STATUS_TOTALS, onboarding: NOT_ALL_DONE_ONBOARDING })
    expect(await screen.findByTestId('fresh-tenant-card')).toBeInTheDocument()
    expect(screen.queryByTestId('needs-attention')).toBeNull()
  })

  test('ov12 fresh-tenant card — not shown once there is inventory', async () => {
    renderOverview({ statusTotals: POPULATED_STATUS_TOTALS })
    await screen.findByTestId('needs-attention')
    expect(screen.queryByTestId('fresh-tenant-card')).toBeNull()
  })
})
