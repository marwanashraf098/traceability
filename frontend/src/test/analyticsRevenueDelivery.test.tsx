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
import RevenuePage from '../pages/analytics/RevenuePage'
import DeliveryPage from '../pages/analytics/DeliveryPage'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'

function fakeJwt(role: 'owner'): string {
  return `h.${btoa(JSON.stringify({ role, exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`
}
function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location">{loc.pathname + loc.search}</div>
}

let calls: string[] = []
function useFixture(f: AnalyticsFixture, override?: (url: string) => Promise<unknown> | undefined) {
  calls = []
  stubFetchWithShellDefaults(analyticsFetch(f, calls, override) as (url: string) => unknown)
}
function ok(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) }, json: async () => structuredClone(body) })
}
const renderAt = (ui: ReactElement, path: string) => renderWithProviders(<><Layout>{ui}</Layout><LocationProbe /></>, { initialEntries: [path] })

beforeEach(() => {
  clearAnalyticsCache()
  setAccessToken(fakeJwt('owner'))
  vi.stubEnv('VITE_ANALYTICS_ENABLED', 'true')
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  clearAccessToken()
  document.documentElement.setAttribute('dir', 'ltr')
})

// ── Revenue ─────────────────────────────────────────────────────────────────

describe('revenue — BROEK (Bosta, no costs)', () => {
  beforeEach(() => useFixture(BROEK))

  test('rv1 — KPIs with deltas; gross margin says to add costs; waterfall from gross to net realized; funnel conversions', async () => {
    renderAt(<RevenuePage />, '/analytics/revenue')
    await waitFor(() => expect(screen.getByTestId('kpi-gross')).toHaveTextContent('EGP 1.69M'))
    expect(screen.getByTestId('kpi-booked')).toHaveTextContent('EGP 1.60M')
    expect(screen.getByTestId('kpi-realized')).toHaveTextContent('EGP 1.03M')
    expect(screen.getByTestId('kpi-realized')).toHaveTextContent('▲ 12.4%')
    expect(screen.getByTestId('kpi-realized-share')).toHaveTextContent('64.7%')
    await waitFor(() => expect(screen.getByTestId('kpi-gross-margin')).toHaveTextContent('Add cost per item in Shopify'))
    expect(screen.getByTestId('kpi-gross-margin')).toHaveTextContent('—')
    const wf = await screen.findByTestId('card-waterfall')
    for (const k of ['gross', 'discounts', 'pipeline', 'failed', 'returns', 'net']) expect(within(wf).getByTestId(`wf-${k}`)).toBeInTheDocument()
    expect(within(wf).queryByTestId('wf-otherCarrier')).toBeNull() // nothing shipped elsewhere
    expect(screen.getByTestId('funnel-conv-fulfilled')).toHaveTextContent('93.0% of orders continue')
  })

  test('rv2 — breakdowns follow the revenue basis with delivery-success pills; switching mode makes no new requests', async () => {
    const user = userEvent.setup()
    renderAt(<RevenuePage />, '/analytics/revenue')
    const ch = await screen.findByTestId('card-by-channel')
    await waitFor(() => expect(ch).toHaveTextContent('Instagram'))
    expect(ch).toHaveTextContent('EGP 430k') // realized: 655,400 × 0.841 × 0.78
    expect(ch).toHaveTextContent('84.1%')
    expect(screen.getByTestId('card-by-payment')).toHaveTextContent('Cash on delivery')
    expect(screen.getByTestId('card-by-governorate')).toHaveTextContent('Sohag')
    expect(screen.getByTestId('card-by-productType')).toHaveTextContent('No product type')
    const before = calls.filter(u => u.includes('/revenue/breakdown')).length
    expect(before).toBe(4)
    await user.click(screen.getByRole('button', { name: 'Booked' }))
    expect(ch).toHaveTextContent('EGP 655k')
    expect(calls.filter(u => u.includes('/revenue/breakdown')).length).toBe(before)
  })

  test('rv3 — discount codes with the automatic row and revenue per cost', async () => {
    renderAt(<RevenuePage />, '/analytics/revenue')
    const table = await screen.findByTestId('discount-codes')
    expect(within(table).getByText('WELCOME10')).toBeInTheDocument()
    expect(within(table).getByTestId('discount-automatic')).toHaveTextContent('Automatic discounts')
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('10.0×')
  })

  test('rv4 — heatmap rows start on Saturday; customer classes add up with their share', async () => {
    renderAt(<RevenuePage />, '/analytics/revenue')
    const heat = await screen.findByTestId('heatmap')
    const dayLabels = [...heat.children].filter((_, i) => i % 25 === 0 && i > 0).map(e => e.textContent)
    expect(dayLabels).toEqual(['Sat', 'Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri'])
    const classes = await screen.findByTestId('card-new-returning')
    await waitFor(() => expect(within(classes).getByTestId('class-new')).toHaveTextContent('New customers'))
    expect(within(classes).getByTestId('class-returning')).toHaveTextContent('94% delivered')
  })

  test('rv5 — an other-carrier share shows its own waterfall bar', async () => {
    const summary = structuredClone(BROEK.revenue)
    summary.current.waterfall.otherCarrier = 52000
    useFixture(BROEK, url => (url.includes('/revenue/summary') ? ok(summary) : undefined))
    renderAt(<RevenuePage />, '/analytics/revenue')
    const wf = await screen.findByTestId('card-waterfall')
    expect(await within(wf).findByTestId('wf-otherCarrier')).toBeInTheDocument()
    expect(wf).toHaveTextContent('Shipped elsewhere')
  })
})

describe('revenue — merchant types', () => {
  test('rv6 — Femine (costed): gross margin with coverage, margin by product type, unknown-customer note, other-carrier banner', async () => {
    useFixture(FEMINE)
    renderAt(<RevenuePage />, '/analytics/revenue')
    expect(await screen.findByTestId('other-carrier-banner')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('kpi-gross-margin')).toHaveTextContent('62.0%'))
    expect(screen.getByTestId('margin-cost-note')).toHaveTextContent('Costed: 4 of 6 sold SKUs · 79% of realized revenue')
    const types = await screen.findByTestId('card-margin-type')
    await waitFor(() => expect(types).toHaveTextContent('61.2%'))
    expect(types).not.toHaveTextContent('Hoodies') // no costed units → no margin bar
    expect(await screen.findByText(/34% of customers in this period have no Shopify customer record/)).toBeInTheDocument()
  })

  test("rv7 — High line (no Bosta): realized can't be measured, figures use booked, the cost note explains Shopify's access denial", async () => {
    useFixture(HIGH_LINE)
    renderAt(<RevenuePage />, '/analytics/revenue')
    expect(await screen.findByTestId('connect-bosta')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('kpi-realized')).toHaveTextContent('Realized counts Bosta deliveries'))
    expect(screen.queryByTestId('kpi-realized-share')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Realized' })).toBeNull()
    expect(screen.queryByTestId('card-waterfall')).toBeNull()
    await waitFor(() => expect(screen.getByTestId('kpi-gross-margin')).toHaveTextContent("Shopify didn't let Traced read cost per item"))
    const ch = await screen.findByTestId('card-by-channel')
    await waitFor(() => expect(ch).toHaveTextContent('EGP 52k')) // booked: 655,400 × 0.08
  })
})

// ── Delivery ────────────────────────────────────────────────────────────────

describe('delivery — BROEK', () => {
  beforeEach(() => useFixture(BROEK))

  test('dl1 — KPIs: success with points delta, failed with lost sales, speeds in days', async () => {
    renderAt(<DeliveryPage />, '/analytics/delivery')
    await waitFor(() => expect(screen.getByTestId('kpi-success')).toHaveTextContent('85.0%'))
    expect(screen.getByTestId('kpi-success')).toHaveTextContent('952 delivered of 1,120')
    expect(screen.getByTestId('kpi-success')).toHaveTextContent('▲ 2.4 pts')
    expect(screen.getByTestId('kpi-failed')).toHaveTextContent('EGP 151,200 in lost sales')
    expect(screen.getByTestId('kpi-to-handed')).toHaveTextContent('1.3 days')
    expect(screen.getByTestId('kpi-to-delivered')).toHaveTextContent('Cairo & Giza 1.7 d · other 2.9 d')
  })

  test('dl2 — governorate table sorts by orders, and by success when that header is clicked', async () => {
    const user = userEvent.setup()
    renderAt(<DeliveryPage />, '/analytics/delivery')
    const table = await screen.findByTestId('governorate-table')
    const firstCell = () => within(table).getAllByRole('row')[1].querySelector('td')!.textContent
    expect(firstCell()).toBe('Cairo')
    const success = within(table).getByRole('columnheader', { name: /Success/ })
    await user.click(within(success).getByRole('button'))
    expect(success).toHaveAttribute('aria-sort', 'descending')
    expect(firstCell()).toBe('Cairo')
    await user.click(within(success).getByRole('button'))
    expect(success).toHaveAttribute('aria-sort', 'ascending')
    expect(firstCell()).toBe('Sohag')
  })

  test('dl3 — failure reasons with Bosta coverage; most-failed products by rate, 20+ orders only', async () => {
    renderAt(<DeliveryPage />, '/analytics/delivery')
    const reasons = await screen.findByTestId('card-reasons')
    await waitFor(() => expect(reasons).toHaveTextContent('Customer refused'))
    expect(reasons).toHaveTextContent('Bosta gave a reason for 144 of 168 failed deliveries (86%).')
    const failed = await screen.findByTestId('card-most-failed')
    await waitFor(() => expect(failed).toHaveTextContent('Denim Jacket'))
    const labels = [...failed.querySelectorAll('span.truncate')].map(e => e.textContent)
    expect(labels).toEqual(['Denim Jacket', 'Wide-Leg Cargo', 'Heavyweight Hoodie'])
    expect(failed).not.toHaveTextContent('Small Tote') // 12 orders: under the minimum
  })

  test('dl4 — customers to watch: refused pills, blocklist link, already blocked; fulfillment speed note', async () => {
    renderAt(<DeliveryPage />, '/analytics/delivery')
    const table = await screen.findByTestId('watch-table')
    const rows = within(table).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('3 refused')
    expect(within(rows[1]).getByRole('link', { name: 'Blocklist →' })).toHaveAttribute('href', '/blocklist')
    expect(rows[3]).toHaveTextContent('Already blocked')
    const speed = await screen.findByTestId('card-speed')
    await waitFor(() => expect(speed).toHaveTextContent('Orders handed over the same day deliver at 88% vs 79% for 3+ days.'))
  })
})

describe('delivery — merchant types', () => {
  test('dl5 — High line (no Bosta): Connect Bosta and no delivery requests at all', async () => {
    useFixture(HIGH_LINE)
    renderAt(<DeliveryPage />, '/analytics/delivery')
    expect(await screen.findByTestId('connect-bosta')).toBeInTheDocument()
    expect(calls.some(u => u.includes('/delivery/') || u.includes('/customers/watch'))).toBe(false)
    expect(screen.queryByTestId('kpi-success')).toBeNull()
  })
})

// ── Arabic ──────────────────────────────────────────────────────────────────

const arI18n = i18next.createInstance()
arI18n.use(initReactI18next).init({
  lng: 'ar', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } },
  interpolation: { escapeValue: false },
})
function renderRtl(ui: ReactElement, path: string) {
  document.documentElement.setAttribute('dir', 'rtl')
  return render(ui, {
    wrapper: ({ children }) => (
      <StationProvider>
        <MemoryRouter initialEntries={[path]}>
          <I18nextProvider i18n={arI18n}>{children}</I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    ),
  })
}

describe('Arabic', () => {
  test('ar1 — Delivery: Arabic governorate and reason names, Latin digits', async () => {
    useFixture(BROEK)
    renderRtl(<Layout><DeliveryPage /></Layout>, '/analytics/delivery')
    const table = await screen.findByTestId('governorate-table')
    await waitFor(() => expect(table).toHaveTextContent('القاهرة'))
    expect(await screen.findByTestId('card-reasons')).toHaveTextContent('رفض العميل')
    expect(screen.getByTestId('kpi-success')).toHaveTextContent('85.0%')
  })

  test('ar2 — Revenue: waterfall runs right to left (gross on the right)', async () => {
    useFixture(BROEK)
    renderRtl(<Layout><RevenuePage /></Layout>, '/analytics/revenue')
    const wf = await screen.findByTestId('card-waterfall')
    const x = (k: string) => Number(within(wf).getByTestId(`wf-${k}`).getAttribute('x'))
    await waitFor(() => expect(x('gross')).toBeGreaterThan(x('net')))
  })
})
