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
import SummaryPage from '../pages/analytics/SummaryPage'
import { StationProvider } from '../components/StationProvider'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'
import type { Payouts, Pipeline } from '../analyticsApi'

/**
 * Analytics payout fixes (2026-11-27) — slice 1: the payout check shows Traced's net and its breakdown, never Bosta's batch.
 * Slice 3: the Summary pipeline's expected figures are net of Bosta fees ("estimated" when a fee is a quote) and it
 * shows the fees still to be deducted.
 */

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

const PIPELINE: Pipeline = {
  range: RANGE, asOf: '2026-11-27T09:40:00Z',
  notFulfilled: { count: 4, value: 1000, expected: 144.6, fees: 125.4, feesEstimated: 2, prepaid: 2 },
  inTransit: { count: 3, value: 1150, expected: 473.4, fees: 216.6, feesEstimated: 3, prepaid: 1 },
  awaitingPayout: { count: 2, deposited: 372.48, nextCashoutDate: '2026-12-02', deliveredNotYetSettled: 0, deliveredNotYetSettledEstimate: 0 },
  feesToDeduct: { count: 7, amount: 485.64, returning: { count: 3, amount: 159.6 }, customerReturns: { count: 1, amount: 106.02 },
    finishedUnsettled: { count: 3, amount: 220.02 } },
  inYourBank: { shipments: 6, deposited: 1055.34, payouts: 1, lastTransferDate: '2026-11-18' },
  openOrderDays: 60,
}

const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))

function useFixture(f: AnalyticsFixture) {
  const base = analyticsFetch(f, [])
  stubFetchWithShellDefaults(((url: string) =>
    url.includes('/analytics/money/payouts') ? json(PAYOUTS)
      : url.includes('/analytics/money/pipeline') ? json(PIPELINE)
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
      ['Date', 'Reference', 'Traced orders', 'COD collected', 'Bosta fees', 'Traced net'])
    const rows = within(table).getAllByRole('row')
    const nov18 = rows[2]
    expect(within(nov18).getByTestId('payout-cod')).toHaveTextContent('EGP 1,590')
    expect(within(nov18).getByTestId('payout-fees')).toHaveTextContent('−EGP 535')
    expect(within(nov18).getByTestId('payout-negative')).toHaveTextContent('incl. 3 negative: −EGP 254')
    expect(within(nov18).getByTestId('payout-net')).toHaveTextContent('EGP 1,055')
    expect(within(rows[1]).queryByTestId('payout-negative')).toBeNull()
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

  test('pf3 — Summary: expected net of Bosta fees, marked estimated; fees still to be deducted', async () => {
    renderWithProviders(<Layout><SummaryPage /></Layout>, { initialEntries: ['/analytics/summary'] })
    const transit = await screen.findByTestId('stage-in-transit')
    expect(transit).toHaveTextContent('EGP 473')
    expect(transit).toHaveTextContent("Expected using each governorate's success rate, after Bosta fees")
    expect(within(transit).getByTestId('estimated-chip')).toHaveTextContent('estimated')
    const nf = screen.getByTestId('stage-not-fulfilled')
    expect(nf).toHaveTextContent('EGP 145')
    expect(nf).toHaveTextContent("for prepaid orders, just Bosta's fee")
    expect(within(nf).getByTestId('estimated-chip')).toBeInTheDocument()
    const fees = screen.getByTestId('fees-to-deduct')
    expect(fees).toHaveTextContent('Bosta fees still to be deducted: −EGP 486')
    expect(fees).toHaveTextContent('returns to origin EGP 160 · return pickups EGP 106 · finished, not settled yet EGP 220')
  })

  test('pf4 — Summary in Arabic, right to left', async () => {
    const arI18n = i18next.createInstance()
    arI18n.use(initReactI18next).init({
      lng: 'ar', fallbackLng: 'en', initImmediate: false,
      resources: { en: { translation: en }, ar: { translation: ar } },
      interpolation: { escapeValue: false },
    })
    document.documentElement.setAttribute('dir', 'rtl')
    render(<Layout><SummaryPage /></Layout>, {
      wrapper: ({ children }) => (
        <StationProvider><MemoryRouter initialEntries={['/analytics/summary']}><I18nextProvider i18n={arI18n}>{children}</I18nextProvider></MemoryRouter></StationProvider>
      ),
    })
    const fees = await screen.findByTestId('fees-to-deduct')
    expect(fees).toHaveTextContent('رسوم بوسطة التي ستُخصم لاحقًا')
    expect(screen.getByTestId('stage-in-transit')).toHaveTextContent('بعد رسوم بوسطة')
  })
})
