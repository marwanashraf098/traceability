import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n, PortalLang } from '../portal/i18n'
import type { FeeQuote, LookupLine, LookupResult, PortalConfig } from '../portal/api'

// Returns portal fees F2 — the fee and final amount on the review step, before submit.
//   shown    — a lookup with fees → the review step asks /quote (choices only: no email / note) and shows
//              items, fee, "You'll receive"; the submission is unchanged
//   nothing  — fee ≥ value: "you'd receive EGP 0" and the request can still be sent
//   waived   — merchant-fault reason: "Waived"
//   absent   — no fees key → no /quote call, no fee block (today's portal)
//   arabic   — the Arabic copy

const BASE: PortalConfig = {
  storeName: 'The Snouts', returnWindowDays: 14, reasonCodes: ['wrong_size', 'damaged', 'other'],
  logoUrl: null, brandColor: '#0F766E', policyText: null, autoApprove: false, pickupBooking: false,
}

const SHIRT: LookupLine = {
  variantId: 'v-white-m', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null,
  deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
}

const QUOTE: FeeQuote = {
  applies: true, kind: 'refund', currency: 'EGP', itemValue: '900.00', approximate: false, fee: '75.00',
  feeWaived: false, waiveReason: null, mode: 'fixed', percent: null, refundDue: '825.00', collect: '0.00', nothingOwed: false,
}

let lookupBody: LookupResult
let quoteBody: FeeQuote | { applies: false }
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
  lookupBody = { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines: [SHIRT], pickup: null,
    fees: { refund: true, exchange: false } }
  quoteBody = QUOTE
  calls = []
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    if (url.endsWith('/config')) return respond(200, BASE)
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

async function toReview(lang: PortalLang = 'en', reason = 'wrong_size') {
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n(lang)}><PortalApp slug="thesnouts" /></I18nextProvider>)
  const en = lang === 'en'
  await screen.findByRole('heading', { name: en ? 'Start a return' : 'ابدأ الإرجاع' })
  await user.type(screen.getByLabelText(en ? 'Order number' : 'رقم الطلب'), '#1047')
  await user.type(screen.getByLabelText(en ? 'Phone number' : 'رقم الهاتف'), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: en ? 'Find my order' : 'ابحث عن طلبي' }))
  await screen.findByRole('heading', { name: en ? 'What would you like to return?' : undefined })
  const card = screen.getByText('Linen Shirt').closest('section')!
  await user.click(within(card).getAllByRole('button')[1])
  await user.selectOptions(within(card).getByRole('combobox'), reason)
  await user.click(screen.getByRole('button', { name: en ? 'Continue' : 'متابعة' }))
  return user
}

const quoteCalls = () => calls.filter(c => c.url.endsWith('/quote'))

describe('portal fees', () => {
  test('shown: the review step quotes the choices and shows what the customer receives', async () => {
    const user = await toReview()
    const box = await screen.findByTestId('fee-summary')
    expect(within(box).getByTestId('fee-item-value')).toHaveTextContent('EGP 900')
    expect(within(box).getByTestId('fee-amount')).toHaveTextContent('− EGP 75')
    expect(within(box).getByTestId('fee-due')).toHaveTextContent('EGP 825')
    expect(within(box).getByText("You'll receive")).toBeInTheDocument()
    const q = quoteCalls()
    expect(q.length).toBeGreaterThan(0)
    expect(JSON.parse(q[0].init.body as string)).toEqual({ lines: [{ variantId: 'v-white-m', quantity: 1, reasonCode: 'wrong_size' }] })
    expect((q[0].init.headers as Record<string, string>).Authorization).toBe('Bearer tok.sig')

    await user.type(screen.getByLabelText(/Email/), 'a@b.co')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByText('RR-7K3F9M')
    const sent = JSON.parse(calls.find(c => c.url.endsWith('/requests'))!.init.body as string)
    expect(sent).toEqual({ lines: [{ variantId: 'v-white-m', quantity: 1, reasonCode: 'wrong_size' }], email: 'a@b.co' })
  })

  test('nothing owed: the fee is more than the item value — EGP 0, still sendable', async () => {
    quoteBody = { ...QUOTE, fee: '1000.00', refundDue: '0.00', nothingOwed: true }
    await toReview()
    expect(await screen.findByTestId('fee-nothing-owed')).toHaveTextContent("you'd receive EGP 0")
    expect(screen.getByTestId('fee-due')).toHaveTextContent('EGP 0')
    expect(screen.getByRole('button', { name: 'Send return request' })).toBeEnabled()
  })

  test('waived: a merchant-fault reason shows no fee', async () => {
    quoteBody = { ...QUOTE, fee: '0.00', feeWaived: true, waiveReason: 'merchant_fault', refundDue: '900.00' }
    await toReview('en', 'damaged')
    expect(await screen.findByTestId('fee-amount')).toHaveTextContent('Waived')
    expect(screen.getByTestId('fee-due')).toHaveTextContent('EGP 900')
  })

  test('absent: no fees in the lookup → no quote call and no fee block', async () => {
    delete lookupBody.fees
    await toReview()
    await screen.findByRole('button', { name: 'Send return request' })
    expect(quoteCalls()).toHaveLength(0)
    expect(screen.queryByTestId('fee-summary')).toBeNull()
  })

  test('arabic', async () => {
    await toReview('ar')
    const box = await screen.findByTestId('fee-summary')
    expect(within(box).getByText('ستسترد')).toBeInTheDocument()
    expect(within(box).getByTestId('fee-due')).toHaveTextContent('825 ج.م')
  })
})
