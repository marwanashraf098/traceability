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
import ProductsPage from '../pages/analytics/ProductsPage'
import StockPage from '../pages/analytics/StockPage'
import CustomersPage from '../pages/analytics/CustomersPage'
import { AnalyticsDrawers } from '../pages/analytics/drawers'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'

const TOKEN = `h.${btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`

function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}
let calls: Array<{ url: string; opts?: RequestInit }> = []
function useFixture(f: AnalyticsFixture) {
  calls = []
  const urls: string[] = []
  const base = analyticsFetch(f, urls)
  stubFetchWithShellDefaults(((url: string, opts?: RequestInit) => {
    calls.push({ url, opts })
    if (url.includes('/analytics/settings') && opts?.method === 'PUT') {
      return Promise.resolve({ ok: true, status: 200, headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
        json: async () => ({ ...JSON.parse(String(opts.body)), defaults: false }) })
    }
    return base(url)
  }) as (url: string) => unknown)
}
const renderAt = (ui: ReactElement, path: string) =>
  renderWithProviders(<><Layout>{ui}<AnalyticsDrawers /></Layout><LocationProbe /></>, { initialEntries: [path] })
const loc = () => screen.getByTestId('location').textContent ?? ''
const firstCells = (table: HTMLElement) => within(table).getAllByRole('row').slice(1).map(r => r.querySelector('td')!.textContent)

beforeEach(() => {
  clearAnalyticsCache()
  setAccessToken(TOKEN)
  vi.stubEnv('VITE_ANALYTICS_ENABLED', 'true')
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  clearAccessToken()
  document.documentElement.setAttribute('dir', 'ltr')
})

// ── Products & SKUs ─────────────────────────────────────────────────────────

describe('products', () => {
  test('pd1 — BROEK: every SKU sold, sorted by revenue, stock from Shopify, true net asks for costs; columns sort; a row opens the SKU', async () => {
    useFixture(BROEK)
    const user = userEvent.setup()
    renderAt(<ProductsPage />, '/analytics/products')
    const table = await screen.findByTestId('sku-table')
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(7))
    expect(firstCells(table)[0]).toContain('Heavyweight Hoodie')                     // realized desc
    expect(screen.getByTestId('card-all-skus')).toHaveTextContent("Stock: Shopify's count")
    expect(screen.getByTestId('low-trust-banner')).toBeInTheDocument()
    expect(screen.getByTestId('kpi-best')).toHaveTextContent('Add cost per item in Shopify')
    expect(screen.getByTestId('kpi-losing')).toHaveTextContent('—')
    const boxy = within(table).getAllByRole('row').find(r => r.textContent!.includes('Black / L'))!
    expect(within(boxy).getByText('A')).toBeInTheDocument()                           // ABC class
    expect(boxy).toHaveTextContent('3 days')                                          // stock left, warning
    await user.click(within(within(table).getByRole('columnheader', { name: /Units/ })).getByRole('button'))
    expect(firstCells(table)[0]).toContain('Boxy Tee')                                // 212 units
    await user.click(within(table).getAllByRole('row')[1])
    expect(loc()).toMatch(/sku=/)
    expect(await screen.findByTestId('sku-drawer')).toBeInTheDocument()
  })

  test('pd2 — Femine (costed, no pieces): best SKU and losing count from true net; stock columns hidden with a note; restock says no pieces', async () => {
    useFixture(FEMINE)
    renderAt(<ProductsPage />, '/analytics/products')
    await waitFor(() => expect(screen.getByTestId('kpi-best')).toHaveTextContent('Satin Abaya · Black / M'))
    expect(screen.getByTestId('kpi-losing')).toHaveTextContent('0')
    const table = await screen.findByTestId('sku-table')
    await waitFor(() => expect(within(table).queryByRole('columnheader', { name: /Sell-through/ })).toBeNull())
    expect(screen.getByTestId('card-all-skus')).toHaveTextContent('Stock columns (sell-through, per day, stock left, age, exchanges) appear once pieces are tracked')
    await waitFor(() => expect(screen.getByTestId('card-restock')).toHaveTextContent('Stock health needs pieces scanned into Traced'))
  })

  test('pd3 — restock settings: saving lead time and cover sends them; invalid days block saving', async () => {
    useFixture(BROEK)
    const user = userEvent.setup()
    renderAt(<ProductsPage />, '/analytics/products')
    const form = await screen.findByTestId('restock-settings')
    const [lead, cover] = within(form).getAllByRole('textbox')
    await user.clear(cover)
    expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled()
    await user.type(cover, '45')
    await user.clear(lead)
    await user.type(lead, '14')
    await user.click(within(form).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.some(c => c.opts?.method === 'PUT')).toBe(true))
    const put = calls.find(c => c.opts?.method === 'PUT')!
    expect(put.url).toContain('/analytics/settings')
    expect(JSON.parse(String(put.opts!.body))).toEqual({ supplierLeadDays: 14, coverDays: 45 })
    expect(await within(form).findByRole('status')).toHaveTextContent('Saved')
    expect(await screen.findByTestId('restock-table')).toHaveTextContent('120')
  })
})

// ── Stock health ────────────────────────────────────────────────────────────

describe('stock health', () => {
  test('st1 — BROEK: trust banner, KPIs, age buckets, running low, dead stock with cash, returns by size', async () => {
    useFixture(BROEK)
    renderAt(<StockPage />, '/analytics/stock')
    expect(await screen.findByTestId('low-trust-banner')).toHaveTextContent('Only 22% of your orders are packed through Traced')
    await waitFor(() => expect(screen.getByTestId('kpi-pieces')).toHaveTextContent('640'))
    expect(screen.getByTestId('kpi-value-price')).toHaveTextContent('EGP 512k')
    expect(screen.getByTestId('kpi-value-cost')).toHaveTextContent('Add cost per item in Shopify')
    const age = screen.getByTestId('age-buckets')
    expect(age).toHaveTextContent('0–30 days')
    expect(age).toHaveTextContent('90+ days')
    expect(screen.getByTestId('card-age')).toHaveTextContent('50 pieces have been in stock for more than 90 days')
    await waitFor(() => expect(screen.getAllByTestId('low-row')).toHaveLength(1))
    expect(screen.getByTestId('low-row')).toHaveTextContent('Boxy Tee · Black / L')
    expect(await screen.findByTestId('dead-table')).toHaveTextContent('Track Pants')
    expect(screen.getByTestId('dead-table')).toHaveTextContent('at price')
    expect(screen.getByTestId('card-returned')).toHaveTextContent('Size too small')
    const bySize = screen.getByTestId('card-by-size')
    await waitFor(() => expect(bySize).toHaveTextContent('13.3%'))
  })

  test('st2 — pieces moved 4+ times: the list from /analytics/pieces, then one piece, then its SKU', async () => {
    useFixture(BROEK)
    const user = userEvent.setup()
    renderAt(<StockPage />, '/analytics/stock')
    const moved = await screen.findByTestId('kpi-moved')
    await waitFor(() => expect(moved).toHaveTextContent('3'))
    await user.click(within(moved).getByRole('button', { name: 'See the pieces →' }))
    expect(loc()).toContain('pieces=4')
    const list = await screen.findByTestId('pieces-table')
    expect(within(list).getAllByTestId('pieces-row')).toHaveLength(3)
    expect(within(list).getAllByTestId('pieces-row')[0]).toHaveTextContent('6 trips')
    expect(calls.some(c => c.url.includes('/analytics/pieces?minTrips=4'))).toBe(true)
    await user.click(within(list).getAllByTestId('pieces-row')[0])
    expect(loc()).toContain('piece=wlc-0042')
    const piece = await screen.findByTestId('piece-drawer')
    expect(await within(piece).findAllByTestId('trip')).toHaveLength(3)
    await user.click(within(piece).getByRole('button', { name: 'Open the SKU →' }))
    expect(loc()).toMatch(/sku=/)
    expect(loc()).not.toContain('piece=')
    expect(await screen.findByTestId('sku-drawer')).toBeInTheDocument()
  })

  test('st3 — Femine (no pieces): one card pointing to Receiving, nothing else', async () => {
    useFixture(FEMINE)
    renderAt(<StockPage />, '/analytics/stock')
    const card = await screen.findByTestId('no-pieces')
    expect(within(card).getByRole('link', { name: 'Go to Receiving →' })).toHaveAttribute('href', '/receiving')
    expect(screen.queryByTestId('kpi-pieces')).toBeNull()
  })
})

// ── Customers ───────────────────────────────────────────────────────────────

describe('customers', () => {
  test('cu1 — BROEK: KPIs, top customers, repeat by governorate, cohorts (incomplete months starred), watch list → blocklist', async () => {
    useFixture(BROEK)
    renderAt(<CustomersPage />, '/analytics/customers')
    await waitFor(() => expect(screen.getByTestId('kpi-customers')).toHaveTextContent('1,108'))
    expect(screen.getByTestId('kpi-repeat')).toHaveTextContent('18.0%')
    expect(screen.getByTestId('kpi-days-between')).toHaveTextContent('34 days')
    expect(await screen.findByTestId('top-customers')).toHaveTextContent('Salma R.')
    expect(screen.getByTestId('top-customers')).toHaveTextContent('EGP 11,850')
    expect(await screen.findByTestId('card-repeat-gov')).toHaveTextContent('27.1%')
    const cohorts = await screen.findByTestId('cohorts')
    const rows = within(cohorts).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('24%')
    expect(rows[1]).toHaveTextContent('11%*')                                         // month 3 still running
    expect(rows[3]).toHaveTextContent('26%*')
    const watch = await screen.findByTestId('watch-table')
    expect(within(watch).getAllByRole('link', { name: 'Blocklist →' })[0]).toHaveAttribute('href', '/blocklist')
    expect(screen.getByTestId('card-customer-detail')).toHaveTextContent('V2')
    expect(screen.queryByTestId('unknown-banner')).toBeNull()                         // 8% unknown: under the banner line
  })

  test('cu2 — Femine: a third of customers have no Shopify record → the unknown share is shown', async () => {
    useFixture(FEMINE)
    renderAt(<CustomersPage />, '/analytics/customers')
    expect(await screen.findByTestId('unknown-banner')).toHaveTextContent('34% of customers in this period have no Shopify customer record')
  })

  test('cu3 — High line (no Bosta): no realized money for customers, the watch list needs Bosta and is never requested', async () => {
    useFixture(HIGH_LINE)
    renderAt(<CustomersPage />, '/analytics/customers')
    const top = await screen.findByTestId('top-customers')
    expect(within(top).getAllByRole('row')[1]).toHaveTextContent('—')
    expect(within(top).getAllByRole('row')[1]).not.toHaveTextContent('EGP')
    await waitFor(() => expect(screen.getByTestId('card-watch')).toHaveTextContent('connect Bosta'))
    expect(calls.some(c => c.url.includes('/customers/watch'))).toBe(false)
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
  test('ar1 — Customers: Arabic governorates and month names, Latin digits', async () => {
    useFixture(BROEK)
    document.documentElement.setAttribute('dir', 'rtl')
    render(<Layout><CustomersPage /></Layout>, {
      wrapper: ({ children }) => (
        <StationProvider><MemoryRouter initialEntries={['/analytics/customers']}><I18nextProvider i18n={arI18n}>{children}</I18nextProvider></MemoryRouter></StationProvider>
      ),
    })
    expect(await screen.findByTestId('card-repeat-gov')).toHaveTextContent('القاهرة')
    const cohorts = await screen.findByTestId('cohorts')
    expect(cohorts).toHaveTextContent('يوليو')
    expect(cohorts).toHaveTextContent('24%')
    expect(screen.getByRole('heading', { name: 'العملاء' })).toBeInTheDocument()
  })
})
