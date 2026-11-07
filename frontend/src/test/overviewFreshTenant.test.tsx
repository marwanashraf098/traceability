import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { renderWithProviders, screen } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { forgetOnboardingProgress } from '../onboardingProgress'
import Layout from '../components/Layout'
import Overview from '../pages/Overview'

/**
 * Overview for a fresh tenant (2026-09-25, App Store review): the reviewer's tenant had 11 Shopify orders and a
 * connected store but zero received pieces, and Overview showed "Nothing to show yet" instead of their orders.
 * Onboarding O3 retired the fresh-tenant card: the Get started card sits ABOVE the dashboard, which always renders.
 *   C1 — orders with zero pieces → the dashboard (their orders); zero orders and zero pieces → the dashboard too,
 *        with Get started above it, never a card that replaces it.
 *   C2 — the Shopify step reflects GET /onboarding/progress: done ✓ when connected, else "Connect Shopify →".
 * URL-routed fetch fake, real response shapes.
 */

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

const ZERO_STATUS_TOTALS = {
  statusCounts: {
    available: 0, reserved: 0, packed: 0, awaiting_pickup: 0, with_courier: 0,
    delivered: 0, return_in_transit: 0, return_pending_inspection: 0,
    damaged: 0, lost: 0, destroyed: 0, out_on_transfer: 0, sold: 0,
  },
}
const summary = (total: number) => ({ total, processing: total, withCourier: 0, delivered: 0, returned: 0 })

// Reviewer tenant shape: Bosta not connected, no test label, no receipt yet; Shopify as the test says.
function progress(shopifyConnected: boolean) {
  return {
    steps: [
      { key: 'shopify', done: shopifyConnected, skippable: false, skipped: false },
      { key: 'address', done: true, skippable: false, skipped: false },
      { key: 'first_receiving', done: false, skippable: false, skipped: false },
      { key: 'courier', done: false, skippable: true, skipped: false },
      { key: 'test_label', done: false, skippable: true, skipped: false },
    ],
    tasks: [{ key: 'receive', tried: false }],
    card: 'full', cardChosen: false, setupCompletedAt: new Date().toISOString(), bostaConnected: false,
    pickPackChosen: false, labelingPace: null,
  }
}

function connections(shopifyConnected: boolean) {
  return {
    shopify: shopifyConnected
      ? { connected: true, storeId: 'store-1', shopDomain: '8mqr0k-qs.myshopify.com', connectionType: 'oauth',
          status: 'connected', importStatus: 'completed', lastSyncAt: '2026-09-25T13:52:13Z' }
      : { connected: false, storeId: null, shopDomain: null, connectionType: null,
          status: 'disconnected', importStatus: null, lastSyncAt: null },
    bosta: { connected: false, businessName: null, pickupMode: null, awbFormat: null, awbLang: null },
    customAppAvailable: false,
    oauthAvailable: true,
    shopifySetup: { appUrl: '', redirectUrl: '', webhookApiVersion: '2026-04', scopes: [] },
  }
}

const ORDER_ROW = {
  id: 'o1', number: '#1299', customerName: null, customerPhone: null, status: 'new',
  codAmount: null, placedAt: '2026-09-24T18:24:47Z', trackingNumber: null, deliveryState: null,
  isExchange: false, notTracedAt: null,
  derivedStatus: { primaryKey: 'status.new', tone: 'NEUTRAL', healthChips: [], historicalNote: null,
                   conflictKey: null, notTraced: false, packedConfirmed: false,
                   fulfillmentKey: 'status.new', fulfillmentTone: 'NEUTRAL' },
}

function render(opts: { orders: number; shopifyConnected: boolean }) {
  const app = vi.fn((url: string) => {
    if (url.includes('/inventory/status-totals')) return jsonOk(ZERO_STATUS_TOTALS)
    if (url.includes('/inventory/valuation'))     return jsonOk({ lowStockCount: 0, inventoryValue: 0, variantsCosted: 0, variantsTotal: 0 })
    if (url.includes('/orders/funnel'))           return jsonOk({ newCount: 0, picking: 0, packed: 0, courier: 0, delivered: 0 })
    if (url.includes('/overview/trends'))         return jsonOk([])
    if (url.includes('/overview/late-to-pack'))   return jsonOk({ overdue: 0, over48: 0 })
    if (url.includes('/overview/top-skus'))       return jsonOk([])
    if (url.includes('/orders/summary'))          return jsonOk(summary(opts.orders))
    if (url.includes('/exceptions?type='))        return jsonOk({ items: [] })
    if (url.includes('/orders?'))                 return jsonOk({ items: opts.orders ? [ORDER_ROW] : [], page: 0, size: 20, total: opts.orders })
    if (url.includes('/connections'))             return jsonOk(connections(opts.shopifyConnected))
    if (url.includes('/locations'))               return jsonOk([])
    return jsonOk({})
  })
  stubFetchWithShellDefaults(app, {
    me: { name: 'Reviewer', email: 'reviewer2@tracedtech.com', role: 'owner' },
    exceptionsCount: { count: 0, critical: 0, warning: 0 },
    onboardingProgress: progress(opts.shopifyConnected),
  })
  return renderWithProviders(<Layout><Overview /></Layout>)
}

describe('Overview — fresh tenant', () => {
  beforeEach(() => {
    forgetOnboardingProgress()
    vi.stubGlobal('localStorage', { getItem: vi.fn().mockReturnValue(null), setItem: vi.fn(), removeItem: vi.fn() })
  })
  afterEach(() => vi.unstubAllGlobals())

  test('C1: orders > 0 with zero pieces → dashboard, not the fresh-tenant card', async () => {
    render({ orders: 11, shopifyConnected: true })
    await screen.findByTestId('stat-cards')
    expect(screen.queryByTestId('fresh-tenant-card')).toBeNull()
  })

  test('C1 control: zero orders and zero pieces → the dashboard with Get started above it', async () => {
    render({ orders: 0, shopifyConnected: false })
    expect(await screen.findByTestId('get-started-card')).toBeInTheDocument()
    expect(screen.getByTestId('stat-cards')).toBeInTheDocument()
    expect(screen.queryByTestId('fresh-tenant-card')).toBeNull()
  })

  test('C2: Shopify connected → the Shopify step shows done, no "Connect Shopify" link', async () => {
    render({ orders: 0, shopifyConnected: true })
    const step = await screen.findByTestId('get-started-step-shopify')
    expect(step).toHaveAttribute('data-done', 'true')
    expect(step).toHaveTextContent('Shopify')
    expect(screen.queryByRole('link', { name: /Connect Shopify/ })).toBeNull()
  })

  test('C2 control: Shopify not connected → the step is the "Connect Shopify" link', async () => {
    render({ orders: 0, shopifyConnected: false })
    const step = await screen.findByTestId('get-started-step-shopify')
    expect(step).toHaveAttribute('data-done', 'false')
    const link = screen.getByRole('link', { name: /Connect Shopify/ })
    expect(link).toHaveAttribute('href', '/settings?tab=connections')
  })
})
