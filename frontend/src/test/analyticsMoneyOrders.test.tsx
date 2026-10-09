import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import type { ReactElement } from 'react'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { renderWithProviders } from './renderWithProviders'
import { BROEK, FEMINE, HIGH_LINE, analyticsFetch, type AnalyticsFixture } from './analyticsFixtures'
import Layout from '../components/Layout'
import MoneyPage from '../pages/analytics/MoneyPage'
import OrdersPage from '../pages/analytics/OrdersPage'
import SummaryPage from '../pages/analytics/SummaryPage'
import { AnalyticsDrawers } from '../pages/analytics/drawers'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'

const TOKEN = `h.${btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`
const BOXY_L = '00000000-0000-4000-8000-000000000001'
const ABAYA = '00000000-0000-4000-8000-000000000011'

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}
let calls: string[] = []
function useFixture(f: AnalyticsFixture, override?: (url: string, opts?: RequestInit) => Promise<unknown> | undefined) {
  calls = []
  const base = analyticsFetch(f, calls)
  stubFetchWithShellDefaults(((url: string, opts?: RequestInit) => override?.(url, opts) ?? base(url)) as (url: string) => unknown)
}
const renderAt = (ui: ReactElement, path: string) =>
  renderWithProviders(<><Layout>{ui}<AnalyticsDrawers /></Layout><LocationProbe /></>, { initialEntries: [path] })
const loc = () => screen.getByTestId('location').textContent ?? ''

beforeEach(() => {
  clearAnalyticsCache()
  setAccessToken(TOKEN)
  vi.stubEnv('VITE_ANALYTICS_ENABLED', 'true')
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  clearAccessToken()
  document.documentElement.setAttribute('dir', 'ltr')
})

// ── Bosta & payouts ─────────────────────────────────────────────────────────

describe('money — BROEK', () => {
  beforeEach(() => useFixture(BROEK))

  test('mo1 — fee KPIs from /money/fees; contribution profit asks for costs', async () => {
    renderAt(<MoneyPage />, '/analytics/money')
    await waitFor(() => expect(screen.getByTestId('kpi-shipping')).toHaveTextContent('EGP 80,578'))
    expect(screen.getByTestId('kpi-shipping')).toHaveTextContent('952 shipments')
    expect(screen.getByTestId('kpi-extra')).toHaveTextContent('EGP 26,859') // failed + exchange + return
    expect(screen.getByTestId('kpi-per-success')).toHaveTextContent('EGP 85')
    expect(screen.getByTestId('kpi-per-failed')).toHaveTextContent('EGP 45')
    expect(screen.getByTestId('kpi-per-failed')).toHaveTextContent('20 failed · EGP 16,116 total')
    await waitFor(() => expect(screen.getByTestId('kpi-contribution')).toHaveTextContent('Add cost per item in Shopify'))
    expect(screen.getByTestId('card-where')).toHaveTextContent('Total Bosta cost: EGP 107,437.')
    expect(screen.getByTestId('card-anomalies')).toHaveTextContent('V2')
  })

  test('mo2 — extra-shipping drill-down: by SKU, then by shipment; a SKU row opens the SKU drawer over it', async () => {
    const user = userEvent.setup()
    renderAt(<MoneyPage />, '/analytics/money')
    const extra = await screen.findByTestId('kpi-extra')
    await user.click(within(extra).getByRole('button', { name: 'Drill down →' }))
    expect(loc()).toContain('drill=extra')
    const drill = await screen.findByTestId('drill-extra')
    await waitFor(() => expect(within(drill).getByTestId('drill-total')).toHaveTextContent('EGP 26,859'))
    const skuTable = await within(drill).findByTestId('drill-sku-table')
    expect(within(skuTable).getAllByTestId('drill-sku-row')[0]).toHaveTextContent('Boxy Tee')
    await user.click(within(drill).getByRole('button', { name: 'By shipment (AWB)' }))
    expect(loc()).toContain('view=awb')
    const awb = await within(drill).findByTestId('drill-awb-table')
    expect(within(awb).getAllByTestId('drill-awb-row')).toHaveLength(5)
    expect(awb).toHaveTextContent('estimated')
    await user.click(within(drill).getByRole('button', { name: 'By SKU' }))
    await user.click(within(await within(drill).findByTestId('drill-sku-table')).getAllByTestId('drill-sku-row')[0])
    expect(loc()).toContain(`sku=${BOXY_L}`)
    expect(await screen.findByTestId('sku-drawer')).toBeInTheDocument()
    expect(screen.queryByTestId('drill-extra')).toBeNull() // hidden while the SKU drawer is open
  })

  test('mo3 — unsuccessful-delivery drill-down from the URL: cost per failure and cost by reason', async () => {
    renderAt(<MoneyPage />, '/analytics/money?drill=failed')
    const drill = await screen.findByTestId('drill-failed')
    await waitFor(() => expect(within(drill).getByTestId('drill-cost-per-failed')).toHaveTextContent('EGP 45'))
    expect(within(drill).getByTestId('drill-total')).toHaveTextContent('EGP 16,116')
    await waitFor(() => expect(drill).toHaveTextContent('Customer refused'))
    expect(drill).toHaveTextContent('EGP 2,655') // 59 refusals × EGP 45
  })

  test('mo4 — payout check: matching batch, differing batch (never "short"), batch not reported', async () => {
    renderAt(<MoneyPage />, '/analytics/money')
    const table = await screen.findByTestId('payouts-table')
    const rows = within(table).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('All in Traced')
    expect(rows[3]).toHaveTextContent('Differs by EGP 2,150')
    expect(rows[4]).toHaveTextContent('Batch total not reported')
    expect(table.textContent).not.toMatch(/short/i)
    expect(within(table).getAllByRole('columnheader').map(h => h.textContent)).toEqual(
      ['Date', 'Reference', 'Traced orders', 'Traced amount', 'Bosta batch total', 'Status'])
  })

  test('mo5 — stuck shipments: cash at risk, tabs per kind, the alert link opens its tab', async () => {
    const user = userEvent.setup()
    renderAt(<MoneyPage />, '/analytics/money?view=stuck&kind=delivered_not_paid')
    const card = await screen.findByTestId('card-stuck')
    await waitFor(() => expect(within(card).getByTestId('stuck-at-risk')).toHaveTextContent('EGP 6,850'))
    expect(within(card).getByTestId('stuck-tab-delivered_not_paid')).toHaveAttribute('aria-pressed', 'true')
    expect(within(card).getByTestId('stuck-table')).toHaveTextContent('7139220400')
    await user.click(within(card).getByTestId('stuck-tab-stuck_with_bosta'))
    expect(loc()).toContain('kind=stuck_with_bosta')
    expect(within(within(card).getByTestId('stuck-table')).getAllByRole('row')).toHaveLength(4)
  })
})

describe('money — merchant types', () => {
  test('mo6 — Femine (costed): contribution profit with its coverage', async () => {
    useFixture(FEMINE)
    renderAt(<MoneyPage />, '/analytics/money')
    await waitFor(() => expect(screen.getByTestId('kpi-contribution')).toHaveTextContent('EGP 425k'))
    expect(screen.getByTestId('contribution-cost-note')).toHaveTextContent('Costed: 4 of 6 sold SKUs')
  })

  test('mo7 — High line (no Bosta): Connect Bosta and no money requests', async () => {
    useFixture(HIGH_LINE)
    renderAt(<MoneyPage />, '/analytics/money')
    expect(await screen.findByTestId('connect-bosta')).toBeInTheDocument()
    expect(calls.filter(u => u.includes('/money/') || u.includes('/profit/'))).toEqual([])
  })
})

// ── Order finances ──────────────────────────────────────────────────────────

describe('order finances', () => {
  test('or1 — chips carry the counts; a chip filters through the URL and the request', async () => {
    useFixture(FEMINE)
    const user = userEvent.setup()
    renderAt(<OrdersPage />, '/analytics/orders')
    const all = await screen.findByTestId('chip-all')
    await waitFor(() => expect(all).toHaveTextContent('All10'))
    expect(screen.getByTestId('chip-other_carrier')).toHaveTextContent('Other carrier2')
    await user.click(screen.getByTestId('chip-lost'))
    expect(loc()).toContain('status=lost')
    await waitFor(() => expect(calls.some(u => u.includes('/analytics/orders?') && u.includes('status=lost'))).toBe(true))
    await waitFor(() => expect(within(screen.getByTestId('orders-table')).getAllByRole('row')).toHaveLength(2))
    expect(screen.getByTestId('orders-table')).toHaveTextContent('Refused, returning')
  })

  test('or2 — search (order, tracking number or customer) is debounced into the URL and the request', async () => {
    useFixture(BROEK)
    const user = userEvent.setup()
    renderAt(<OrdersPage />, '/analytics/orders')
    const box = await screen.findByPlaceholderText('Order, tracking number or customer')
    await user.type(box, 'salma')
    await waitFor(() => expect(loc()).toContain('q=salma'))
    await waitFor(() => expect(calls.some(u => u.includes('q=salma'))).toBe(true))
    await waitFor(() => expect(within(screen.getByTestId('orders-table')).getAllByRole('row')).toHaveLength(2))
  })

  test('or3 — sortable columns reorder the rows (total, both directions)', async () => {
    useFixture(BROEK)
    const user = userEvent.setup()
    renderAt(<OrdersPage />, '/analytics/orders')
    const table = await screen.findByTestId('orders-table')
    await waitFor(() => expect(within(table).getAllByRole('row').length).toBeGreaterThan(2))
    const total = within(table).getByRole('columnheader', { name: /Total/ })
    await user.click(within(total).getByRole('button'))
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('EGP 2,550')
    await user.click(within(total).getByRole('button'))
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('EGP 950')
  })

  test('or4 — Export CSV downloads the current view with the bearer token and says when it was cut short', async () => {
    let exportCall: { url: string; opts?: RequestInit } | null = null
    useFixture(BROEK, (url, opts) => {
      if (!url.includes('export.csv')) return undefined
      exportCall = { url, opts }
      return Promise.resolve(new Response('a,b\r\n', { status: 200, headers: { 'content-type': 'text/csv', 'x-export-truncated': 'true', 'content-disposition': 'attachment; filename="orders.csv"' } }))
    })
    URL.createObjectURL = vi.fn(() => 'blob:x')
    URL.revokeObjectURL = vi.fn()
    const user = userEvent.setup()
    renderAt(<OrdersPage />, '/analytics/orders?status=paid&period=7d')
    await screen.findByTestId('orders-table')
    await user.click(screen.getByTestId('export-csv'))
    await waitFor(() => expect(exportCall).not.toBeNull())
    expect(exportCall!.url).toContain('status=paid')
    expect(exportCall!.url).toContain('period=7d')
    expect((exportCall!.opts?.headers as Record<string, string>).Authorization).toBe(`Bearer ${TOKEN}`)
    expect(await screen.findByText(/The export hit its row limit/)).toBeInTheDocument()
  })

  test('or5 — High line (no Bosta): no fees, net pending', async () => {
    useFixture(HIGH_LINE)
    renderAt(<OrdersPage />, '/analytics/orders')
    const table = await screen.findByTestId('orders-table')
    await waitFor(() => expect(within(table).getAllByRole('row')[1]).toHaveTextContent('pending'))
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Expected')
  })
})

// ── SKU drawer ──────────────────────────────────────────────────────────────

describe('SKU drawer', () => {
  test('sk1 — BROEK: KPIs, money without costs (net before cost of goods), the most-travelled piece, recent orders', async () => {
    useFixture(BROEK)
    renderAt(<SummaryPage />, `/analytics/summary?sku=${BOXY_L}`)
    const d = await screen.findByTestId('sku-drawer')
    await waitFor(() => expect(within(d).getByTestId('sku-title')).toHaveTextContent('Boxy Tee · Black / L'))
    expect(within(d).getByTestId('sku-units')).toHaveTextContent('212')
    await waitFor(() => expect(within(d).getByTestId('sku-true-net')).toHaveTextContent('Add cost per item in Shopify'))
    expect(within(d).getByTestId('sku-true-net')).not.toHaveTextContent('EGP') // no cost → never a money figure
    await waitFor(() => expect(within(d).getByTestId('sku-money')).toHaveTextContent('Net before cost of goods'))
    expect(within(d).getByTestId('sku-stock')).toHaveTextContent("Shopify's count")
    expect(d).toHaveTextContent('ABC class A')
    const piece = within(d).getByTestId('sku-piece')
    await waitFor(() => expect(piece).toHaveTextContent('Piece history · BXT-0148')) // 5 trips, the most travelled
    expect(within(piece).getAllByTestId('trip')).toHaveLength(3)
    expect(piece).toHaveTextContent('Pieces of this SKU with 4+ trips: 1.')
    expect(await within(d).findByTestId('sku-orders')).toHaveTextContent('#4871')
  })

  test('sk2 — Femine (costed, no pieces): true net shown, no stock tile, no piece history', async () => {
    useFixture(FEMINE)
    renderAt(<SummaryPage />, `/analytics/summary?sku=${ABAYA}`)
    const d = await screen.findByTestId('sku-drawer')
    await waitFor(() => expect(within(d).getByTestId('sku-true-net')).toHaveTextContent('of realized'))
    await waitFor(() => expect(within(d).queryByTestId('sku-stock')).toBeNull())
    await waitFor(() => expect(within(d).getByTestId('sku-piece')).toHaveTextContent('No pieces of this SKU are tracked'))
  })

  test('sk3 — a Top SKU row on Summary opens the drawer; closing removes it from the URL', async () => {
    useFixture(BROEK)
    const user = userEvent.setup()
    renderAt(<SummaryPage />, '/analytics/summary')
    const rows = await screen.findAllByTestId('top-sku-row')
    await user.click(rows[0])
    expect(loc()).toMatch(/sku=/)
    expect(await screen.findByTestId('sku-drawer')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Close' }))
    expect(loc()).not.toMatch(/sku=/)
  })
})

// ── Arabic ──────────────────────────────────────────────────────────────────

const arI18n = i18next.createInstance()
arI18n.use(initReactI18next).init({
  lng: 'ar', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } },
  interpolation: { escapeValue: false },
})

describe('Arabic', () => {
  test('ar1 — Order finances: Arabic statuses, governorates and money', async () => {
    useFixture(BROEK)
    document.documentElement.setAttribute('dir', 'rtl')
    render(<Layout><OrdersPage /></Layout>, {
      wrapper: ({ children }) => (
        <StationProvider><MemoryRouter initialEntries={['/analytics/orders']}><I18nextProvider i18n={arI18n}>{children}</I18nextProvider></MemoryRouter></StationProvider>
      ),
    })
    const table = await screen.findByTestId('orders-table')
    await waitFor(() => expect(table).toHaveTextContent('مدفوع'))
    expect(table).toHaveTextContent('القاهرة')
    expect(table).toHaveTextContent('1,300 ج.م')
    expect(screen.getByPlaceholderText('الطلب أو رقم التتبع أو العميل')).toBeInTheDocument()
  })
})
