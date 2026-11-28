import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import FeeBreakdown from '../pages/exchangesRefunds/FeeBreakdown'
import { renderWithProviders } from './renderWithProviders'
import type { FeeQuote, LookupLine, LookupResult, PortalConfig } from '../portal/api'
import type { RequestAmounts, ReturnRequestDetail } from '../api'

// Q5 (2026-11-27) — an exchange whose price paid isn't known: no difference charged or paid back.
//   portal  — the quote says priceDifferenceUnknown: the fee-only layout, "We couldn't work out a price difference…"
//   drawer  — the frozen amounts say so: "Price difference couldn't be calculated"
//   known   — a known difference: no such line (portal and drawer)

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

const UNKNOWN: FeeQuote = {
  applies: true, kind: 'exchange', currency: 'EGP', itemValue: '0.00', approximate: true, fee: '0.00', feeWaived: false,
  waiveReason: null, mode: null as unknown as 'fixed', percent: null, refundDue: null, collect: '0.00', nothingOwed: false,
  paidPrice: null, replacementPrice: '1100.00', priceDifference: null, customerOwed: '0.00', priceDifferenceUnknown: true,
}

let quoteBody: FeeQuote

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body),
    text: async () => JSON.stringify(body ?? ''),
  })
}

beforeEach(() => {
  localStorage.clear()
  quoteBody = UNKNOWN
  const lookupBody: LookupResult = { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines: [SHIRT],
    pickup: null, exchangePricing: { anyProduct: false, freeVariantSwaps: false } }
  vi.stubGlobal('fetch', vi.fn((url: string) => {
    if (url.endsWith('/config')) return respond(200, CONFIG)
    if (url.endsWith('/lookup')) return respond(200, lookupBody)
    if (url.endsWith('/quote')) return respond(200, quoteBody)
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'requested' })
    return respond(404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

async function toReview() {
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
  await user.click(screen.getByRole('button', { name: 'Continue' }))
  return within(await screen.findByTestId('fee-summary'))
}

const AMOUNTS: RequestAmounts = {
  kind: 'exchange', currency: 'EGP', itemValue: '0.00', approximate: true, fee: '0.00', feeWaived: false, waiveReason: null,
  calculatedFee: '0.00', mode: null, percent: null, areaSource: null, refundDue: null, collect: '0.00', nothingOwed: false,
  overriddenAt: null, overriddenByName: null, canOverride: false,
  paidPrice: null, replacementPrice: '1100.00', priceDifference: null, customerOwed: '0.00', priceDifferenceUnknown: true,
}

function detail(amounts: RequestAmounts): ReturnRequestDetail {
  return { id: 'rr-9', reference: 'RR-EXCH', type: 'exchange', status: 'requested', items: [], refunds: [], amounts } as unknown as ReturnRequestDetail
}

describe('price paid unknown (Q5)', () => {
  test('portal: no difference added, the customer is told so', async () => {
    const box = await toReview()
    expect(box.getByTestId('fee-difference-unknown')).toHaveTextContent("We couldn't work out a price difference for this item, so none is added.")
    expect(box.queryByTestId('fee-difference')).toBeNull()
    expect(box.getByTestId('fee-collect')).toHaveTextContent('EGP 0')
  })

  test('portal: a known difference says nothing of the kind', async () => {
    quoteBody = { ...UNKNOWN, approximate: false, paidPrice: '900.00', priceDifference: '200.00', collect: '200.00', priceDifferenceUnknown: undefined }
    const box = await toReview()
    expect(box.getByTestId('fee-difference')).toHaveTextContent('+ EGP 200')
    expect(box.queryByTestId('fee-difference-unknown')).toBeNull()
  })

  test("drawer: \"Price difference couldn't be calculated\"", () => {
    renderWithProviders(<FeeBreakdown detail={detail(AMOUNTS)} onChanged={async () => {}} />)
    expect(screen.getByTestId('fee-difference-unknown')).toHaveTextContent("Price difference couldn't be calculated")
  })

  test('drawer: a known difference — no such line', () => {
    const known = { ...AMOUNTS, approximate: false, paidPrice: '900.00', priceDifference: '200.00', collect: '200.00', priceDifferenceUnknown: undefined }
    renderWithProviders(<FeeBreakdown detail={detail(known)} onChanged={async () => {}} />)
    expect(screen.queryByTestId('fee-difference-unknown')).toBeNull()
    expect(screen.getByTestId('fee-difference')).toHaveTextContent('+')
  })
})
