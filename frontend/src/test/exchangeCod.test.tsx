import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { FeeQuote, LookupLine, LookupResult, PortalConfig } from '../portal/api'
import { renderWithProviders } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import { BROEK, analyticsFetch } from './analyticsFixtures'
import FeeBreakdown from '../pages/exchangesRefunds/FeeBreakdown'
import ManualCourierPanel from '../pages/exchangesRefunds/ManualCourierPanel'
import OrdersPage from '../pages/analytics/OrdersPage'
import Layout from '../components/Layout'
import { setAccessToken, clearAccessToken } from '../auth'
import { clearAnalyticsCache } from '../analytics/useAnalyticsQuery'
import type { RequestAmounts, ReturnRequestDetail } from '../api'

// Returns portal fees F4 — the Bosta type 30 carries what the customer pays as COD.
//   portal   — a Bosta exchange (quote codBy 'bosta'): "Bosta will collect EGP X…"; without it: today's copy
//   drawer   — who collects: Bosta (booked COD) / Bosta once booked / "Ask your courier…" / collect it yourself
//   manual   — ManualCourierPanel (another courier): "Ask your courier to collect EGP X"
//   finances — the Orders table shows the exchange COD beside the net (never in it); absent = unchanged

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body),
    text: async () => JSON.stringify(body ?? ''),
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

// ── portal ────────────────────────────────────────────────────────────────────

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 30, reasonCodes: ['wrong_size', 'other'],
  logoUrl: null, brandColor: '#1F5C4A', policyText: null, autoApprove: false, pickupBooking: false,
  exchangesEnabled: true,
}
const SHIRT: LookupLine = {
  variantId: 'v-white-m', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null,
  deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
  optionAxes: [{ name: null, kind: 'colour' }, { name: null, kind: 'size' }],
  currentOptions: ['White', 'M'],
  exchangeOptions: [{ variantId: 'v-white-l', title: 'White / L', options: ['White', 'L'], inStock: true, price: '1100.00' }],
}
const QUOTE: FeeQuote = {
  applies: true, kind: 'exchange', currency: 'EGP', itemValue: '900.00', approximate: false, fee: '0.00', feeWaived: false,
  waiveReason: null, mode: null as unknown as 'fixed', percent: null, refundDue: null, collect: '200.00', nothingOwed: false,
  paidPrice: '900.00', replacementPrice: '1100.00', priceDifference: '200.00', customerOwed: '0.00',
}

async function toReview(quote: FeeQuote) {
  const lookup: LookupResult = { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines: [SHIRT],
    pickup: null, exchangePricing: { anyProduct: false, freeVariantSwaps: false } }
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.endsWith('/config')) return respond(200, CONFIG)
    if (url.endsWith('/lookup')) return respond(200, lookup)
    if (url.endsWith('/quote')) return respond(200, quote)
    return respond(404)
  }))
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
  await screen.findByRole('heading', { name: 'Start a return' })
  await user.type(screen.getByLabelText('Order number'), '#1047')
  await user.type(screen.getByLabelText('Phone number'), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: 'Find my order' }))
  await screen.findByRole('heading', { name: 'What would you like to do?' })
  await user.click(screen.getByRole('radio', { name: /Exchange/ }))
  await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
  await user.selectOptions(screen.getByLabelText('Why are you exchanging it?'), 'wrong_size')
  await user.click(screen.getByRole('button', { name: 'Continue' }))
  await screen.findByRole('heading', { name: 'Choose your new size or colour' })
  await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
  await user.click(screen.getByRole('checkbox', { name: /refund instead/ }))
  await user.click(screen.getByRole('button', { name: 'Continue' }))
  return screen.findByTestId('fee-summary')
}

describe('portal copy', () => {
  test('portal: a Bosta exchange says Bosta will collect what the customer pays', async () => {
    const box = await toReview({ ...QUOTE, codBy: 'bosta' })
    expect(within(box).getByTestId('fee-pay-note')).toHaveTextContent('Bosta will collect EGP 200 when the courier brings your new item.')
    expect(box).not.toHaveTextContent('The store will tell you how to pay')
  })

  test('portal: without codBy (another courier, pre-connect) the copy is unchanged', async () => {
    const box = await toReview(QUOTE)
    expect(within(box).getByTestId('fee-pay-note')).toHaveTextContent('The store will tell you how to pay this fee.')
    expect(box).not.toHaveTextContent('Bosta will collect')
  })
})

// ── drawer ────────────────────────────────────────────────────────────────────

const AMOUNTS: RequestAmounts = {
  kind: 'exchange', currency: 'EGP', itemValue: '900.00', approximate: false, fee: '0.00', feeWaived: false, waiveReason: null,
  calculatedFee: '0.00', mode: null, percent: null, areaSource: null, refundDue: null, collect: '600.00', nothingOwed: false,
  overriddenAt: null, overriddenByName: null, canOverride: false,
  paidPrice: '900.00', replacementPrice: '1500.00', priceDifference: '600.00', customerOwed: '0.00',
}

function detail(amounts: RequestAmounts, extra: Record<string, unknown> = {}): ReturnRequestDetail {
  return { id: 'rr-9', reference: 'RR-EXCH', type: 'exchange', status: 'approved', items: [], refunds: [], amounts,
    refundMethod: null, ...extra } as unknown as ReturnRequestDetail
}

describe('drawer copy', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(() => respond(200, [])))
  })

  test.each([
    ['bosta', '600.00', 'Bosta will collect EGP 600 when it delivers the replacement.'],
    ['bosta_pending', null, 'Bosta will collect EGP 600 when it delivers the replacement, once the exchange is booked.'],
    ['courier', null, 'Ask your courier to collect EGP 600 when they deliver the replacement.'],
    ['merchant', null, "It wasn't added to Bosta's collection — collect it yourself."],
  ] as const)('drawer: collector %s', (collector, bookedCod, text) => {
    renderWithProviders(<FeeBreakdown detail={detail({ ...AMOUNTS, collector, bookedCod })} onChanged={async () => {}} />)
    expect(screen.getByTestId('fee-collect')).toHaveTextContent(text)
  })

  test('drawer: no collector (pre-F4 shape) keeps the F2/F3 copy', () => {
    renderWithProviders(<FeeBreakdown detail={detail(AMOUNTS)} onChanged={async () => {}} />)
    expect(screen.getByTestId('fee-collect')).toHaveTextContent('collect it yourself')
  })

  test('manual: ManualCourierPanel asks the merchant to have their courier collect', () => {
    const d = detail({ ...AMOUNTS, collector: 'courier' }, {
      bookingStatus: 'manual', otherCourier: { carrierName: 'Aramex', forwardCarrierName: 'Aramex', replacement: null, pickup: { name: null, phone: null, addressLines: [] } },
    })
    renderWithProviders(<ManualCourierPanel detail={d} onChanged={async () => {}} />)
    expect(screen.getByTestId('manual-courier-collect')).toHaveTextContent('Ask your courier to collect EGP 600')
  })

  test('manual: nothing to collect → no line', () => {
    const d = detail({ ...AMOUNTS, collect: '0.00' }, {
      bookingStatus: 'manual', otherCourier: { carrierName: 'Aramex', forwardCarrierName: 'Aramex', replacement: null, pickup: { name: null, phone: null, addressLines: [] } },
    })
    renderWithProviders(<ManualCourierPanel detail={d} onChanged={async () => {}} />)
    expect(screen.queryByTestId('manual-courier-collect')).toBeNull()
  })
})

// ── finances ──────────────────────────────────────────────────────────────────

describe('order finances', () => {
  const TOKEN = `h.${btoa(JSON.stringify({ role: 'owner', exp: Math.floor(Date.now() / 1000) + 3600 }))}.s`
  beforeEach(() => {
    clearAnalyticsCache()
    setAccessToken(TOKEN)
    vi.stubEnv('VITE_ANALYTICS_ENABLED', 'true')
  })
  afterEach(() => clearAccessToken())

  test('finances: the exchange COD shows beside the net of its original order only', async () => {
    const first = BROEK.orders.orders[0]
    const f = { ...BROEK, orders: { ...BROEK.orders, orders: BROEK.orders.orders.map((o, i) => (i === 0 ? { ...o, exchangeCod: 560 } : o)) } }
    stubFetchWithShellDefaults(analyticsFetch(f, []) as (url: string) => unknown)
    renderWithProviders(<Layout><OrdersPage /></Layout>, { initialEntries: ['/analytics/orders'] })
    const cods = await screen.findAllByTestId('order-exchange-cod')
    expect(cods).toHaveLength(1)
    expect(cods[0]).toHaveTextContent('+ EGP 560 collected on an exchange')
    expect(cods[0].closest('tr')).toHaveTextContent(first.name)
  })
})
