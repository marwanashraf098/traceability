import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within, waitFor } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { FeeQuote, LookupLine, LookupResult, PortalConfig } from '../portal/api'

// V223 + refund method (2026-11-07) in the portal.
//   floored — a cheaper same-product swap the store doesn't refund: difference 0, "You'll pay" the fee, and a note
//   method  — an exchange the customer pays for, with "refund instead" ticked: no refund-method step, none sent
// Harness as portalExchangePricing.test.tsx.

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
  exchangeOptions: [
    { variantId: 'v-white-l', title: 'White / L', options: ['White', 'L'], inStock: true, price: '1100.00' },
  ],
}

const CATALOGUE = {
  paidPrice: '900.00',
  products: [{ productId: 'p-trousers', title: 'Trousers', imageUrl: null, variants: [
    { variantId: 'v-trousers-s', title: 'S', options: ['S'], price: '700.00', priceDifference: '-200.00' },
    { variantId: 'v-trousers-m', title: 'M', options: ['M'], price: '1500.00', priceDifference: '600.00' },
  ] }],
}

const QUOTE: FeeQuote = {
  applies: true, kind: 'exchange', currency: 'EGP', itemValue: '900.00', approximate: false, fee: '0.00', feeWaived: false,
  waiveReason: null, mode: null as unknown as 'fixed', percent: null, refundDue: null, collect: '200.00', nothingOwed: false,
  paidPrice: '900.00', replacementPrice: '1100.00', priceDifference: '200.00', customerOwed: '0.00',
}

let config: PortalConfig
let lookupBody: LookupResult
let quoteBody: FeeQuote
let catalogueBody: unknown
let calls: Array<{ url: string; init: RequestInit }>

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body),
    text: async () => JSON.stringify(body ?? ''),
  })
}

beforeEach(() => {
  config = CONFIG
  lookupBody = { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines: [SHIRT], pickup: null,
    exchangePricing: { anyProduct: true, freeVariantSwaps: false } }
  quoteBody = QUOTE
  catalogueBody = CATALOGUE
  calls = []
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    if (url.endsWith('/config')) return respond(200, config)
    if (url.endsWith('/lookup')) return respond(200, lookupBody)
    if (url.endsWith('/quote')) return respond(200, quoteBody)
    if (url.includes('/catalogue?')) return respond(200, catalogueBody)
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'requested' })
    return respond(404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

async function toVariant() {
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
  return user
}

const submitted = () => JSON.parse(calls.find(c => c.url.endsWith('/requests'))!.init.body as string)
const quoteCalls = () => calls.filter(c => c.url.endsWith('/quote'))

describe('same-product swap refunds + refund method', () => {
  test('floored: difference 0, the fee is paid, a note says the cheaper swap is not refunded (EN + AR)', async () => {
    quoteBody = { ...QUOTE, fee: '10.00', mode: 'fixed', paidPrice: '720.00', replacementPrice: '550.00', priceDifference: '0.00',
      collect: '10.00', customerOwed: '0.00' }
    const user = await toVariant()
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    const box = await screen.findByTestId('fee-summary')
    expect(within(box).getByTestId('fee-difference')).toHaveTextContent('EGP 0')
    expect(within(box).getByTestId('fee-collect')).toHaveTextContent('EGP 10')
    expect(within(box).getByTestId('fee-difference-floored')).toHaveTextContent("A cheaper size or colour of the same item isn't refunded.")
    await user.click(screen.getByRole('button', { name: 'Switch to Arabic' }))
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'))
    expect(screen.getByTestId('fee-difference-floored')).toHaveTextContent('لا يُسترد فرق السعر')
  })

  test('floored: no note when the difference really is 0 (same price)', async () => {
    quoteBody = { ...QUOTE, paidPrice: '900.00', replacementPrice: '900.00', priceDifference: '0.00', collect: '0.00' }
    const user = await toVariant()
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByTestId('fee-summary')
    expect(screen.queryByTestId('fee-difference-floored')).toBeNull()
  })

  test('method: a paid exchange with "refund instead" ticked is not asked how to be refunded', async () => {
    config = { ...CONFIG, refundMethods: ['cash'] }
    const user = await toVariant()
    expect(screen.getByRole('checkbox', { name: /refund instead/ })).toBeChecked()
    expect(screen.getByText('Step 2 of 3')).toBeInTheDocument()
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    await screen.findByTestId('fee-summary')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByTestId('reference')
    const body = submitted()
    expect(body.refundFallbackOk).toBe(true)
    expect(body).not.toHaveProperty('refundMethod')
    expect(body).not.toHaveProperty('refundDetails')
  })
})
