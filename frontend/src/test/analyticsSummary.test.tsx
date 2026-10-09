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
import { BROEK, FEMINE, HIGH_LINE, analyticsFetch, errResponse, type AnalyticsFixture } from './analyticsFixtures'
import Layout from '../components/Layout'
import SummaryPage from '../pages/analytics/SummaryPage'
import App from '../App'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'

function fakeJwt(role: 'owner' | 'manager' | 'worker'): string {
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

function renderSummary(path = '/analytics/summary') {
  return renderWithProviders(<><Layout><SummaryPage /></Layout><LocationProbe /></>, { initialEntries: [path] })
}

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

// ── Flag + owner-only routing (the real App) ────────────────────────────────

describe('routing', () => {
  function renderAppAt(path: string) {
    window.history.pushState({}, '', path)
    return render(<App />)
  }

  test('r1 — flag off: /analytics/summary is not a route (falls to /overview) and the nav has no Analytics group', async () => {
    vi.stubEnv('VITE_ANALYTICS_ENABLED', 'false')
    useFixture(BROEK)
    renderAppAt('/analytics/summary')
    await waitFor(() => expect(window.location.pathname).toBe('/overview'))
    expect(screen.queryByTestId('nav-analytics')).toBeNull()
    expect(screen.queryByTestId('analytics-summary')).toBeNull()
  })

  test('r2 — flag on, owner: Summary renders inside the shell with the Analytics nav group', async () => {
    useFixture(BROEK)
    renderAppAt('/analytics/summary?period=7d')
    expect(await screen.findByTestId('analytics-summary')).toBeInTheDocument()
    const nav = screen.getByTestId('nav-analytics')
    // App takes its i18n from main.tsx, so labels here are keys — check the links by href.
    const links = within(nav).getAllByRole('link').map(a => a.getAttribute('href'))
    expect(links).toEqual(['/analytics/summary?period=7d', '/analytics/revenue?period=7d', '/analytics/delivery?period=7d']) // pages not built yet are not linked
  })

  test('r3 — a manager is sent to /overview and sees no Analytics group', async () => {
    setAccessToken(fakeJwt('manager'))
    useFixture(BROEK)
    renderAppAt('/analytics/summary')
    await waitFor(() => expect(window.location.pathname).toBe('/overview'))
    expect(screen.queryByTestId('nav-analytics')).toBeNull()
  })

  test('r4 — a worker is sent to /worker-home', async () => {
    setAccessToken(fakeJwt('worker'))
    useFixture(BROEK)
    renderAppAt('/analytics/summary')
    await waitFor(() => expect(window.location.pathname).toBe('/worker-home'))
  })

  test('r5 — an unbuilt page lands on Summary and keeps the period', async () => {
    useFixture(BROEK)
    renderAppAt('/analytics/money?period=today')
    await waitFor(() => expect(window.location.pathname + window.location.search).toBe('/analytics/summary?period=today'))
  })
})

// ── Summary: BROEK (Bosta, no costs, low trust) ─────────────────────────────

describe('summary — BROEK', () => {
  beforeEach(() => useFixture(BROEK))

  test('b1 — pipeline: four stages with expected values, the next payout and the bank', async () => {
    renderSummary()
    const pipe = await screen.findByTestId('pipeline')
    expect(within(pipe).getByTestId('stage-not-fulfilled')).toHaveTextContent('EGP 84,400')
    expect(within(pipe).getByTestId('stage-not-fulfilled')).toHaveTextContent('88 orders · face value EGP 101,200')
    expect(within(pipe).getByTestId('stage-in-transit')).toHaveTextContent('EGP 174,600')
    expect(within(pipe).getByTestId('stage-awaiting')).toHaveTextContent('EGP 196,650')
    expect(within(pipe).getByTestId('stage-awaiting')).toHaveTextContent('next payout Wed, Oct 14')
    expect(within(pipe).getByTestId('stage-awaiting')).toHaveTextContent('+ 6 delivered, not settled yet')
    expect(within(pipe).getByTestId('stage-bank')).toHaveTextContent('EGP 898,150')
    // No "payout short" figure anywhere — the backend never computes one.
    expect(pipe.textContent).not.toMatch(/short/i)
  })

  test('b2 — KPIs with deltas vs the previous period; cost per delivery is all fees ÷ delivered, marked estimated', async () => {
    renderSummary()
    const rev = await screen.findByTestId('kpi-revenue')
    await waitFor(() => expect(rev).toHaveTextContent('EGP 1.03M'))
    expect(rev).toHaveTextContent('Net revenue (realized)')
    expect(rev).toHaveTextContent('▲ 12.4%')
    expect(screen.getByTestId('kpi-orders')).toHaveTextContent('1,390')
    expect(screen.getByTestId('kpi-orders')).toHaveTextContent('▲ 8.1%')
    await waitFor(() => expect(screen.getByTestId('kpi-success')).toHaveTextContent('85.0%'))
    expect(screen.getByTestId('kpi-success')).toHaveTextContent('▲ 2.4 pts')
    const cost = screen.getByTestId('kpi-cost-per-delivery')
    await waitFor(() => expect(cost).toHaveTextContent('EGP 113')) // 107,437 ÷ 952
    expect(cost).toHaveTextContent('estimated')
    expect(cost.textContent).not.toMatch(/▲|▼/) // fees have no previous period → no delta
    await waitFor(() => expect(screen.getByTestId('kpi-payout-lag')).toHaveTextContent('4.2 days'))
  })

  test('b3 — Needs attention lists only open alerts; no link while the target page is not built', async () => {
    renderSummary()
    const list = await screen.findByTestId('alerts')
    expect(within(list).getByTestId('alert-stuck_with_bosta')).toHaveTextContent('5 shipments stuck with Bosta for more than 7 days')
    expect(within(list).getByTestId('alert-stuck_with_bosta')).toHaveTextContent('EGP 6,850 in COD')
    expect(within(list).getByTestId('alert-low_success_governorates')).toHaveTextContent('Sohag 60.5% · Assiut 63.8%')
    expect(within(list).getByTestId('alert-sells_out_soon')).toHaveTextContent('Wide-Leg Cargo Olive / 32')
    expect(within(list).queryByTestId('alert-never_picked_up')).toBeNull()
    // Only alerts whose page is built link; the governorate alert opens Delivery.
    expect(within(list).getAllByRole('link').map(a => a.getAttribute('href'))).toEqual(['/analytics/delivery?by=governorate'])
  })

  test("b4 — low stock trust: banner with the packed share, Shopify's count named on the stock column", async () => {
    renderSummary()
    const card = await screen.findByTestId('card-top-skus')
    await waitFor(() => expect(within(card).getByTestId('low-trust-banner')).toHaveTextContent(
      "Only 22% of your orders are packed through Traced; stock figures use Shopify's count."))
    const table = await within(card).findByTestId('top-skus')
    expect(within(table).getByText("Shopify's count")).toBeInTheDocument()
    const boxy = within(table).getAllByRole('row').find(r => r.textContent!.includes('Black / L'))!
    expect(boxy).toHaveTextContent('3 days') // under a week of cover → warning pill
    expect(within(boxy).getByText('3 days').className).toContain('rounded-full')
  })

  test('b5 — Top SKUs follow the revenue basis; switching to booked needs no new revenue request', async () => {
    const user = userEvent.setup()
    renderSummary()
    const table = await screen.findByTestId('top-skus')
    // Realized: Hoodie 97 kept × 1,450 leads; the row after it is the Cargo, 88 kept × 1,250.
    await waitFor(() => expect(within(table).getAllByRole('row')[1]).toHaveTextContent('Heavyweight HoodieGrey / L'))
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('EGP 141k')
    expect(within(table).getAllByRole('row')[2]).toHaveTextContent('EGP 110k')
    const before = calls.filter(u => u.includes('/revenue/summary')).length
    await user.click(screen.getByRole('button', { name: 'Booked' }))
    expect(screen.getByTestId('location')).toHaveTextContent('mode=booked')
    expect(screen.getByTestId('kpi-revenue')).toHaveTextContent('Net revenue (booked)')
    expect(screen.getByTestId('kpi-revenue')).toHaveTextContent('EGP 1.60M')
    expect(within(table).getAllByRole('row')[1]).toHaveTextContent('EGP 190k') // booked: 131 × 1,450
    expect(within(table).getAllByRole('row')[2]).toHaveTextContent('EGP 148k') // 118 × 1,250
    expect(calls.filter(u => u.includes('/revenue/summary')).length).toBe(before)
  })

  test('b6 — sparklines come from the daily endpoint for the top five only', async () => {
    renderSummary()
    await screen.findByTestId('top-skus')
    await waitFor(() => expect(calls.some(u => u.includes('/sales/variants/daily'))).toBe(true))
    const daily = calls.find(u => u.includes('/sales/variants/daily'))!
    expect(decodeURIComponent(daily).split('ids=')[1].split('&')[0].split(',')).toHaveLength(5)
  })

  test('b7 — expected cash in: three buckets from awaiting payout + in transit', async () => {
    renderSummary()
    const card = await screen.findByTestId('card-cash-in')
    await waitFor(() => expect(card).toHaveTextContent('EGP 241,300'))
    expect(card).toHaveTextContent('Next 7 days')
    expect(card).toHaveTextContent('8–14 days')
    expect(card).toHaveTextContent('4.2-day median')
  })
})

// ── URL state ───────────────────────────────────────────────────────────────

describe('summary — URL state', () => {
  beforeEach(() => useFixture(BROEK))

  test('u1 — the period in the URL goes to every request; a preset click rewrites the URL and refetches', async () => {
    const user = userEvent.setup()
    renderSummary('/analytics/summary?period=7d')
    await screen.findByTestId('pipeline')
    expect(calls.filter(u => u.includes('/analytics/') && !u.includes('cash-forecast') && !u.includes('stock/variants'))
      .every(u => u.includes('period=7d') || u.includes('ids='))).toBe(true)
    await user.click(screen.getByRole('button', { name: 'Yesterday' }))
    expect(screen.getByTestId('location').textContent).toBe('/analytics/summary?period=yesterday')
    await waitFor(() => expect(calls.some(u => u.includes('/money/pipeline?from='))).toBe(true))
  })

  test('u2 — past 92 days a Compare control appears; turning it on asks the backend for the previous period', async () => {
    const user = userEvent.setup()
    renderSummary('/analytics/summary?from=2026-01-01&to=2026-06-30')
    await screen.findByTestId('pipeline')
    expect(calls.find(u => u.includes('/revenue/summary'))).not.toContain('compare=true')
    await user.click(screen.getByRole('button', { name: 'Compare with the previous period' }))
    expect(screen.getByTestId('location')).toHaveTextContent('compare=1')
    await waitFor(() => expect(calls.some(u => u.includes('/revenue/summary') && u.includes('compare=true'))).toBe(true))
  })

  test('u3 — under 92 days there is no Compare control', async () => {
    renderSummary()
    await screen.findByTestId('pipeline')
    expect(screen.queryByRole('button', { name: /Compare/ })).toBeNull()
  })
})

// ── Merchant types + per-card states ────────────────────────────────────────

describe('summary — merchant types', () => {
  test('m1 — Femine: the other-carrier banner, no stock column (no pieces), no trust banner', async () => {
    useFixture(FEMINE)
    renderSummary()
    expect(await screen.findByTestId('other-carrier-banner')).toHaveTextContent('41% of orders in this period (128) shipped outside Bosta')
    const table = await screen.findByTestId('top-skus')
    await waitFor(() => expect(calls.some(u => u.includes('/stock/variants'))).toBe(true))
    expect(within(table).queryByText('Stock left')).toBeNull()
    expect(screen.queryByTestId('low-trust-banner')).toBeNull()
    expect(screen.queryByTestId('alert-sells_out_soon')).toBeNull()
  })

  test('m2 — High line (no Bosta): only the not-fulfilled stage plus Connect Bosta; Bosta KPIs hidden', async () => {
    useFixture(HIGH_LINE)
    renderSummary()
    expect(await screen.findByTestId('connect-bosta')).toHaveTextContent('Connect Bosta')
    expect(screen.getByTestId('stage-not-fulfilled')).toHaveTextContent('EGP 71,300')
    expect(screen.getByTestId('stage-not-fulfilled')).toHaveTextContent('Face value (no success rate yet)')
    expect(screen.queryByTestId('stage-in-transit')).toBeNull()
    expect(screen.queryByTestId('kpi-success')).toBeNull()
    expect(screen.queryByTestId('kpi-cost-per-delivery')).toBeNull()
    expect(screen.queryByTestId('kpi-payout-lag')).toBeNull()
    expect(screen.getByTestId('card-cash-in')).toHaveTextContent('Connect Bosta to see')
    // Realized needs Bosta outcomes: never a green EGP 0, and no realized line on the chart.
    await waitFor(() => expect(screen.getByTestId('kpi-revenue')).toHaveTextContent('Realized counts Bosta deliveries'))
    expect(screen.getByTestId('kpi-revenue')).not.toHaveTextContent('EGP 0')
    expect(screen.getByTestId('card-per-day').querySelectorAll('path[stroke]')).toHaveLength(1)
    expect(await screen.findByText('Nothing needs attention right now.')).toBeInTheDocument()
    // No previous period from the backend → no delta, never a fake 0%.
    await waitFor(() => expect(screen.getByTestId('kpi-orders')).toHaveTextContent('96'))
    expect(screen.getByTestId('kpi-orders').textContent).not.toMatch(/▲|▼|–|0\.0%|vs previous/)
    expect(screen.getByTestId('kpi-revenue').textContent).not.toMatch(/▲|▼|–|0\.0%|vs previous/)
  })

  test('m3 — one failing endpoint shows a retry on its own card only; retry recovers it', async () => {
    const user = userEvent.setup()
    let fail = true
    useFixture(BROEK, url => (fail && url.includes('/analytics/alerts') ? errResponse(500) : undefined))
    renderSummary()
    const card = await screen.findByTestId('card-attention')
    await within(card).findByRole('alert')
    expect(screen.getByTestId('pipeline')).toBeInTheDocument()
    fail = false
    await user.click(within(card).getByRole('button', { name: 'Retry' }))
    expect(await within(card).findByTestId('alerts')).toBeInTheDocument()
  })

  test('m4 — while loading, cards show skeletons, not zeros', async () => {
    stubFetchWithShellDefaults(() => new Promise(() => {}))
    renderSummary()
    expect(screen.getByTestId('kpi-orders').textContent).not.toContain('0')
    expect(screen.getByTestId('card-attention').querySelector('[aria-busy="true"]')).not.toBeNull()
  })
})

// ── Arabic / RTL ────────────────────────────────────────────────────────────

const arI18n = i18next.createInstance()
arI18n.use(initReactI18next).init({
  lng: 'ar', fallbackLng: 'en', initImmediate: false,
  resources: { en: { translation: en }, ar: { translation: ar } },
  interpolation: { escapeValue: false },
})

function renderRtl(ui: ReactElement) {
  document.documentElement.setAttribute('dir', 'rtl')
  return render(ui, {
    wrapper: ({ children }) => (
      <StationProvider>
        <MemoryRouter initialEntries={['/analytics/summary']}>
          <I18nextProvider i18n={arI18n}>{children}</I18nextProvider>
        </MemoryRouter>
      </StationProvider>
    ),
  })
}

describe('summary — Arabic', () => {
  test('a1 — Arabic copy, Latin digits, "ج.م" money, Arabic governorate names, right-to-left chart', async () => {
    useFixture(BROEK)
    renderRtl(<Layout><SummaryPage /></Layout>)
    expect(await screen.findByRole('heading', { name: 'الملخص' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('stage-bank')).toHaveTextContent('898,150 ج.م'))
    expect(screen.getByTestId('stage-bank')).toHaveTextContent('في حسابك البنكي')
    expect(await screen.findByTestId('alert-low_success_governorates')).toHaveTextContent('سوهاج')
    const chart = (await screen.findByTestId('card-per-day')).querySelector('path[stroke]')!
    const xs = [...chart.getAttribute('d')!.matchAll(/[ML]([\d.]+),/g)].map(m => Number(m[1]))
    expect(xs[0]).toBeGreaterThan(xs[xs.length - 1])
  })
})
