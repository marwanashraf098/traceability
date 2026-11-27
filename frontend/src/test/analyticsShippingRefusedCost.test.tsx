import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { I18nextProvider, initReactI18next } from 'react-i18next'
import i18next from 'i18next'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { renderWithProviders } from './renderWithProviders'
import { BROEK, analyticsFetch, type AnalyticsFixture } from './analyticsFixtures'
import Layout from '../components/Layout'
import OrdersPage from '../pages/analytics/OrdersPage'
import RevenuePage from '../pages/analytics/RevenuePage'
import MoneyPage from '../pages/analytics/MoneyPage'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'
import type { OrderRow, OrdersPage as OrdersPageData, ProfitSummary } from '../analyticsApi'

/**
 * Analytics slices 4–6 (2026-11-27). Slice 4: Order finances' Total = lines + shipping (the shipping part on its
 * own line, "shipping unknown" when Shopify's order carries none); shipping income is its own line in Profit.
 * Slice 5: Customers to watch say how many refusals used Shopify's total. Slice 6: a cost of 0 is "cost unknown".
 */

const TOKEN = `h.${btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`
const RANGE = { from: '2026-11-01', to: '2026-11-30', tz: 'Africa/Cairo' }

const row = (name: string, lines: number, shipping: number | null, fees: number, net: number | null, payment: string): OrderRow => ({
  orderId: `id-${name}`, name, placedAt: '2026-11-10T10:00:00Z', customer: 'Mona A.',
  governorate: { key: 'cairo', label: 'Cairo', labelAr: 'القاهرة' }, items: 1,
  total: lines + (shipping ?? 0), lines, shipping, paymentGroup: payment,
  deliveryStatus: { key: 'delivered', label: 'Delivered' }, financialStatus: 'paid',
  bostaFees: fees, feesEstimated: false, netToYou: net, refundAmountUnknown: false, trackingNumbers: ['7300000001'],
})

const ORDERS: OrdersPageData = {
  range: RANGE, asOf: '2026-11-27T09:40:00Z', page: 0, size: 50, total: 2,
  counts: { other_carrier: 0, lost: 0, refunded: 0, paid: 2, overdue: 0, awaiting_payout: 0, expected: 0 },
  orders: [row('BRK-46818-EG', 1299, 85, 92.34, 1291.66, 'Card'), row('BRK-46900-EG', 650, null, 70, 580, 'COD')],
}

const PROFIT: ProfitSummary = {
  range: RANGE,
  coverage: { variantsSold: 6, variantsCosted: 4, keptUnits: 720, costedUnits: 560, realizedTotal: 1033500, realizedCosted: 820000,
    costedRevenueShare: 0.79, costUnknownVariants: 2, costUnknownUnits: 160 },
  cogs: 311000, grossProfit: 509000, grossMargin: 0.62, feesTotal: 107437, feesOnCosted: 84000,
  contributionProfit: 498000, contributionMargin: 0.607, shippingIncome: 81000, shippingIncomeOnCosted: 73000,
}


const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))

function useFixture(f: AnalyticsFixture) {
  const base = analyticsFetch(f, [])
  stubFetchWithShellDefaults(((url: string) =>
    /\/analytics\/orders(\?|$)/.test(url) ? json(ORDERS)
      : url.includes('/analytics/profit/summary') ? json(PROFIT)
      : base(url)) as (url: string) => unknown)
}

function arabic() {
  const arI18n = i18next.createInstance()
  arI18n.use(initReactI18next).init({
    lng: 'ar', fallbackLng: 'en', initImmediate: false,
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  document.documentElement.setAttribute('dir', 'rtl')
  return arI18n
}

function renderAr(ui: React.ReactElement, path: string) {
  const arI18n = arabic()
  return render(<Layout>{ui}</Layout>, {
    wrapper: ({ children }) => (
      <StationProvider><MemoryRouter initialEntries={[path]}><I18nextProvider i18n={arI18n}>{children}</I18nextProvider></MemoryRouter></StationProvider>
    ),
  })
}

beforeEach(() => {
  clearAnalyticsCache()
  setAccessToken(TOKEN)
  vi.stubEnv('VITE_ANALYTICS_ENABLED', 'true')
  useFixture(BROEK)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  clearAccessToken()
  document.documentElement.setAttribute('dir', 'ltr')
})

describe('slice 4 — shipping', () => {
  test('sr1 — Order finances: Total includes shipping, shown on its own line; unknown when missing', async () => {
    renderWithProviders(<Layout><OrdersPage /></Layout>, { initialEntries: ['/analytics/orders'] })
    const totals = await screen.findAllByTestId('order-total')
    expect(totals.map(t => t.textContent)).toEqual(['EGP 1,384', 'EGP 650'])
    const ship = screen.getAllByTestId('order-shipping').map(s => s.textContent)
    expect(ship).toEqual(['incl. EGP 85 shipping', 'shipping unknown'])
  })

  test('sr2 — Order finances in Arabic, right to left', async () => {
    renderAr(<OrdersPage />, '/analytics/orders')
    await waitFor(() => expect(screen.getAllByTestId('order-shipping')).toHaveLength(2))
    const ship = screen.getAllByTestId('order-shipping').map(s => s.textContent ?? '')
    expect(ship[0]).toContain('شامل شحن')
    expect(ship[1]).toBe('الشحن غير معروف')
  })

  test('sr3 — Revenue: shipping income is its own line; Money: contribution says it includes it', async () => {
    renderWithProviders(<Layout><RevenuePage /></Layout>, { initialEntries: ['/analytics/revenue'] })
    const kpi = await screen.findByTestId('kpi-shipping-income')
    await waitFor(() => expect(kpi).toHaveTextContent('EGP 81k'))
    expect(kpi).toHaveTextContent('Shipping income')
    expect(kpi).toHaveTextContent('counted in profit, not revenue')
  })

  test('sr4 — Money: contribution profit includes the shipping customers paid', async () => {
    renderWithProviders(<Layout><MoneyPage /></Layout>, { initialEntries: ['/analytics/money'] })
    const ship = await screen.findByTestId('contribution-shipping')
    expect(ship).toHaveTextContent('Includes EGP 73,000 shipping customers paid.')
  })
})

describe('slices 5 + 6', () => {})
