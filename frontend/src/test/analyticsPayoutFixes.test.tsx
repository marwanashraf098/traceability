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
import MoneyPage from '../pages/analytics/MoneyPage'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'
import type { Payouts } from '../analyticsApi'

/** Analytics payout fixes (2026-11-27) — slice 1: the payout check shows Traced's net and its breakdown, never Bosta's batch. */

const TOKEN = `h.${btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`
const RANGE = { from: '2026-11-01', to: '2026-11-30', tz: 'Africa/Cairo' }

const PAYOUTS: Payouts = {
  range: RANGE,
  payouts: [
    { transactionId: 'WEDCOD25NOV26', date: '2026-11-25', trackedShipments: 1, trackedDeposited: 231.6,
      codCollected: 300, bostaFees: 68.4, negativeDeposits: 0, negativeDepositsAmount: 0 },
    { transactionId: 'WEDCOD18NOV26', date: '2026-11-18', trackedShipments: 6, trackedDeposited: 1055.34,
      codCollected: 1590, bostaFees: 534.66, negativeDeposits: 3, negativeDepositsAmount: -254.22 },
  ],
}

function useFixture(f: AnalyticsFixture) {
  const base = analyticsFetch(f, [])
  stubFetchWithShellDefaults(((url: string) =>
    url.includes('/analytics/money/payouts')
      ? Promise.resolve(new Response(JSON.stringify(PAYOUTS), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      : base(url)) as (url: string) => unknown)
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

describe('payout check', () => {
  test('pf1 — net per payout with COD, fees and negative deposits; no Bosta batch total', async () => {
    renderWithProviders(<Layout><MoneyPage /></Layout>, { initialEntries: ['/analytics/money'] })
    const table = await screen.findByTestId('payouts-table')
    expect(within(table).getAllByRole('columnheader').map(h => h.textContent)).toEqual(
      ['Date', 'Reference', 'Traced orders', 'COD collected', 'Bosta fees', 'Negative deposits', 'Traced net', 'Status'])
    const rows = within(table).getAllByRole('row')
    const nov18 = rows[2]
    expect(within(nov18).getByTestId('payout-cod')).toHaveTextContent('EGP 1,590')
    expect(within(nov18).getByTestId('payout-fees')).toHaveTextContent('−EGP 535')
    expect(within(nov18).getByTestId('payout-negative')).toHaveTextContent('3 · −EGP 254')
    expect(within(nov18).getByTestId('payout-net')).toHaveTextContent('EGP 1,055')
    expect(within(rows[1]).getByTestId('payout-negative')).toHaveTextContent('—')
    expect(table.textContent).not.toMatch(/batch/i)
    expect(screen.queryByTestId('payout-untracked-note')).toBeNull()
    expect(screen.getByTestId('card-payouts')).toHaveTextContent(
      "Calculated per shipment from Bosta's data; may differ slightly from your Bosta statement (e.g. deliveries Traced doesn't track).")
  })

  test('pf2 — Arabic, right to left', async () => {
    const arI18n = i18next.createInstance()
    arI18n.use(initReactI18next).init({
      lng: 'ar', fallbackLng: 'en', initImmediate: false,
      resources: { en: { translation: en }, ar: { translation: ar } },
      interpolation: { escapeValue: false },
    })
    document.documentElement.setAttribute('dir', 'rtl')
    render(<Layout><MoneyPage /></Layout>, {
      wrapper: ({ children }) => (
        <StationProvider><MemoryRouter initialEntries={['/analytics/money']}><I18nextProvider i18n={arI18n}>{children}</I18nextProvider></MemoryRouter></StationProvider>
      ),
    })
    const table = await screen.findByTestId('payouts-table')
    await waitFor(() => expect(within(table).getAllByRole('columnheader').map(h => h.textContent)).toContain('صافي Traced'))
    expect(within(table).getAllByRole('columnheader').map(h => h.textContent)).toContain('رسوم بوسطة')
    expect(screen.getByTestId('card-payouts')).toHaveTextContent('محسوب لكل شحنة من بيانات بوسطة')
  })
})
