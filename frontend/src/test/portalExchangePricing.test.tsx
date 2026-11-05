import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within, waitFor } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { FeeQuote, LookupLine, LookupResult, PortalConfig } from '../portal/api'

// Returns portal fees F3 — exchange to a different product and the price difference.
//   swapsOff  — free variant swaps off: no "same price" promise; the size shows its price; the review step shows
//               the difference and what the customer pays
//   other     — "Choose a different product": the catalogue, pick a variant, the summary, the submission
//   owed      — the replacement costs less: "You'll get back"; with refund methods the customer is asked how
//   blocked   — a capped choice: the reason, Send disabled
//   approx    — only today's price known: the catalogue says other sizes / colours only
//   unchanged — no exchangePricing key: today's copy, no catalogue button, no quote call

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

describe('exchange pricing', () => {
  test('swapsOff: the size shows its price, the review step shows the difference and what to pay', async () => {
    const user = await toVariant()
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    const summary = screen.getByTestId('exchange-summary')
    expect(summary).not.toHaveTextContent('same price')
    expect(screen.getByTestId('exchange-summary-price')).toHaveTextContent('In stock · EGP 1,100')
    await user.click(screen.getByRole('checkbox', { name: /refund instead/ }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    const box = await screen.findByTestId('fee-summary')
    expect(within(box).getByTestId('fee-difference')).toHaveTextContent('+ EGP 200')
    expect(within(box).getByText("You'll pay")).toBeInTheDocument()
    expect(within(box).getByTestId('fee-collect')).toHaveTextContent('EGP 200')
    expect(JSON.parse(quoteCalls()[0].init.body as string)).toMatchObject({ mode: 'exchange', replacementVariantId: 'v-white-l' })
  })

  test('other: choose a different product from the catalogue and send it', async () => {
    quoteBody = { ...QUOTE, replacementPrice: '1500.00', priceDifference: '600.00', collect: '600.00' }
    const user = await toVariant()
    await user.click(screen.getByTestId('other-products-open'))
    const list = await screen.findByTestId('other-products')
    expect(calls.find(c => c.url.includes('/catalogue?'))!.url).toContain('variantId=v-white-m')
    await user.click(within(list).getByRole('radio', { name: /M · EGP 1,500/ }))
    expect(screen.getByTestId('exchange-summary-other')).toHaveTextContent('Trousers · M')
    await user.click(screen.getByRole('checkbox', { name: /refund instead/ }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByTestId('fee-summary')
    expect(screen.getByTestId('summary-line')).toHaveTextContent('Linen Shirt · White · M → Trousers · M')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Exchange requested' })
    expect(submitted()).toMatchObject({ mode: 'exchange', replacementVariantId: 'v-trousers-m' })
  })

  test("owed: the replacement costs less — \"You'll get back\", and the store's refund method is asked", async () => {
    config = { ...CONFIG, refundMethods: ['cash'] }
    quoteBody = { ...QUOTE, replacementPrice: '700.00', priceDifference: '-200.00', collect: '0.00', customerOwed: '200.00' }
    const user = await toVariant()
    await user.click(screen.getByTestId('other-products-open'))
    await user.click(within(await screen.findByTestId('other-products')).getByRole('radio', { name: /S · EGP 700/ }))
    await user.click(screen.getByRole('checkbox', { name: /refund instead/ }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    const box = await screen.findByTestId('fee-summary')
    expect(within(box).getByText("You'll get back")).toBeInTheDocument()
    expect(within(box).getByTestId('fee-owed')).toHaveTextContent('EGP 200')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'How should we pay you back?' })
    expect(screen.getByText(/owes you EGP 200/)).toBeInTheDocument()
    expect(calls.some(c => c.url.endsWith('/requests'))).toBe(false)
  })

  test('blocked: the reason is shown and Send is disabled', async () => {
    quoteBody = { ...QUOTE, blocked: 'max_difference' }
    const user = await toVariant()
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('checkbox', { name: /refund instead/ }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByTestId('fee-blocked')).toHaveTextContent('more than this store allows')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Send return request' })).toBeDisabled())
  })

  test('approx: only other sizes or colours when the price paid is unknown', async () => {
    catalogueBody = { blocked: 'approximate_price' }
    const user = await toVariant()
    await user.click(screen.getByTestId('other-products-open'))
    expect(await screen.findByTestId('other-products-blocked')).toHaveTextContent('another size or colour')
    expect(screen.queryByRole('radio', { name: /EGP/ })).toBeNull()
  })

  test('unchanged: without exchangePricing, today\'s copy, no catalogue, no quote', async () => {
    delete lookupBody.exchangePricing
    lookupBody = { ...lookupBody, lines: [{ ...SHIRT, exchangeOptions: [{ ...SHIRT.exchangeOptions![0], price: undefined }] }] }
    const user = await toVariant()
    expect(screen.queryByTestId('other-products-open')).toBeNull()
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    expect(screen.getByTestId('exchange-summary')).toHaveTextContent('In stock · same price, nothing extra to pay')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('button', { name: 'Send return request' })
    expect(quoteCalls()).toHaveLength(0)
  })
})
