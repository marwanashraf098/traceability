import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { screen, waitFor, within } from '@testing-library/react'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { renderWithProviders } from './renderWithProviders'
import { BROEK, analyticsFetch } from './analyticsFixtures'
import Layout from '../components/Layout'
import MoneyPage from '../pages/analytics/MoneyPage'
import OrdersPage from '../pages/analytics/OrdersPage'
import { AnalyticsDrawers } from '../pages/analytics/drawers'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'
import type { ExtraFees, Stuck } from '../analyticsApi'

/**
 * 2026-11-12 — Bosta exchange trips (type 30) on the customer's original order:
 *  - Orders: the order's exchange-trip fee is its own line under the Bosta fees (never in them or in the net);
 *  - Money › extra fees (by shipment) and the stuck lists: an exchange trip shows its order number with an
 *    "Exchange" tag (the original's, or EXC-… when none was found); other shipments are unchanged.
 */

const TOKEN = `h.${btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`

function json(body: unknown) {
  return Promise.resolve({
    ok: true, status: 200,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(body),
  })
}

function useFixture(f: typeof BROEK, override?: (url: string) => Promise<unknown> | undefined) {
  const base = analyticsFetch(f, [])
  stubFetchWithShellDefaults(((url: string) => override?.(url) ?? base(url)) as (url: string) => unknown)
}

beforeEach(() => {
  clearAnalyticsCache()
  setAccessToken(TOKEN)
  vi.stubEnv('VITE_ANALYTICS_ENABLED', 'true')
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  clearAccessToken()
})

describe('orders', () => {
  test('the exchange-trip fee shows under its original order\'s Bosta fees, beside its COD', async () => {
    const first = BROEK.orders.orders[0]
    useFixture({ ...BROEK, orders: { ...BROEK.orders, orders: BROEK.orders.orders.map((o, i) =>
      (i === 0 ? { ...o, bostaFees: 92, exchangeFees: 101, exchangeCod: 85 } : o)) } })
    renderWithProviders(<Layout><OrdersPage /></Layout>, { initialEntries: ['/analytics/orders'] })
    const fees = await screen.findAllByTestId('order-exchange-fees')
    expect(fees).toHaveLength(1)
    expect(fees[0]).toHaveTextContent('+ EGP 101 exchange trip fee')
    const row = fees[0].closest('tr')!
    expect(row).toHaveTextContent(first.name)
    expect(row).toHaveTextContent('EGP 92')
    expect(within(row).getByTestId('order-exchange-cod')).toHaveTextContent('+ EGP 85 collected on an exchange')
  })

  test('no exchange trip: no line', async () => {
    useFixture(BROEK)
    renderWithProviders(<Layout><OrdersPage /></Layout>, { initialEntries: ['/analytics/orders'] })
    await screen.findByTestId('orders-table')
    await waitFor(() => expect(screen.getAllByTestId('order-link').length).toBeGreaterThan(0))
    expect(screen.queryByTestId('order-exchange-fees')).toBeNull()
  })
})

describe('money', () => {
  const EXTRA: ExtraFees = {
    range: { from: '2026-09-01', to: '2026-09-30' } as ExtraFees['range'], groupBy: 'awb', skus: null, total: 218.46, estimatedCount: 0,
    shipments: [
      { trackingNumber: '4324098769', orderNumber: 'BRK-46870-EG', type: 'exchange', fee: 101.46, estimated: false, reason: null,
        date: '2026-09-09', city: 'Cairo', skus: ['SKU-A'] },
      { trackingNumber: '1111111111', orderNumber: 'EXC-1111111111', type: 'exchange', fee: 57, estimated: false, reason: null,
        date: '2026-09-08', city: 'Cairo', skus: ['SKU-R'] },
      { trackingNumber: '2222222222', orderNumber: 'BRK-40000-EG', type: 'failed', fee: 60, estimated: false, reason: 'Customer refused',
        date: '2026-09-07', city: 'Giza', skus: ['SKU-B'] },
    ],
  }

  test('extra fees by shipment: an exchange trip names its order with an Exchange tag; a failed delivery has none', async () => {
    const user = userEvent.setup()
    useFixture(BROEK, url => (url.includes('/money/fees/extra') && url.includes('groupBy=awb') ? json(EXTRA) : undefined))
    renderWithProviders(<Layout><MoneyPage /><AnalyticsDrawers /></Layout>, { initialEntries: ['/analytics/money?drill=extra'] })
    const drill = await screen.findByTestId('drill-extra')
    await user.click(within(drill).getByRole('button', { name: 'By shipment (AWB)' }))
    const rows = within(await within(drill).findByTestId('drill-awb-table')).getAllByTestId('drill-awb-row')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByTestId('drill-exchange-tag')).toHaveTextContent('Exchange')
    expect(rows[0]).toHaveTextContent('BRK-46870-EG')
    expect(within(rows[1]).getByTestId('drill-exchange-tag')).toHaveTextContent('Exchange')
    expect(rows[1]).toHaveTextContent('EXC-1111111111')
    expect(within(rows[2]).queryByTestId('drill-exchange-tag')).toBeNull()
  })

  test('stuck: an exchange trip names its order with an Exchange tag', async () => {
    const STUCK: Stuck = {
      asOf: '2026-10-09T09:40:00Z', neverPickedUp: [], deliveredNotPaid: [], payoutWeekday: 3, unresolved: 0,
      stuckWithBosta: [
        { trackingNumber: '4324098769', orderNumber: 'BRK-46870-EG', lastStatus: 'In transit', days: 9, cod: 85, exchange: true },
        { trackingNumber: '7200001000', orderNumber: '#4701', lastStatus: 'In transit', days: 12, cod: 1250 },
      ],
    }
    useFixture(BROEK, url => (url.includes('/money/stuck') ? json(STUCK) : undefined))
    renderWithProviders(<Layout><MoneyPage /></Layout>, { initialEntries: ['/analytics/money?view=stuck&kind=stuck_with_bosta'] })
    const table = await screen.findByTestId('stuck-table')
    const tags = within(table).getAllByTestId('stuck-exchange-tag')
    expect(tags).toHaveLength(1)
    expect(tags[0]).toHaveTextContent('Exchange')
    expect(tags[0].closest('tr')).toHaveTextContent('BRK-46870-EG')
  })
})

test('EN + AR copy for the new text', () => {
  expect(en.analytics.orders.exchangeFees).toBe('+ {{amount}} exchange trip fee')
  expect(ar.analytics.orders.exchangeFees).toContain('{{amount}}')
  expect(ar.analytics.orders.exchangeFees).toContain('استبدال')
  expect(en.analytics.money.exchangeTag).toBe('Exchange')
  expect(ar.analytics.money.exchangeTag).toBe('استبدال')
})
