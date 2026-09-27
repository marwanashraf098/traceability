import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PortalConfig } from '../portal/api'

// Step 6b — untracked order lines in the portal. Fakes match 6a's PortalService: an untracked line
// carries orderItemId + tracked:false (tracked lines keep today's keys) and is submitted by
// orderItemId; a tracked line by variantId. The customer sees no difference between the two.

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
    { variantId: 'v-white-s', title: 'White / S', options: ['White', 'S'], inStock: false },
    { variantId: 'v-white-l', title: 'White / L', options: ['White', 'L'], inStock: true },
    { variantId: 'v-black-m', title: 'Black / M', options: ['Black', 'M'], inStock: true },
  ],
}

const TOTE: LookupLine = {
  variantId: 'v-tote', productTitle: 'Canvas Tote', variantTitle: 'Sand', imageUrl: null,
  deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
  optionAxes: [{ name: null, kind: 'option' }], currentOptions: ['Sand'], exchangeOptions: [],
}

function lookupWith(lines: LookupLine[]): LookupResult {
  return { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines, pickup: null }
}

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body),
    text: async () => JSON.stringify(body ?? ''),
  })
}

let config: PortalConfig
let lookupBody: LookupResult
let calls: Array<{ url: string; init: RequestInit }>

beforeEach(() => {
  config = CONFIG
  lookupBody = lookupWith([SHIRT, TOTE])
  calls = []
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    if (url.endsWith('/config')) return respond(200, config)
    if (url.endsWith('/lookup')) return respond(200, lookupBody)
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'requested' })
    return respond(404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

async function toItems(lang: 'en' | 'ar' = 'en') {
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
  await screen.findByRole('heading', { name: 'Start a return' })
  await user.type(screen.getByLabelText('Order number'), '#1047')
  await user.type(screen.getByLabelText('Phone number'), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: 'Find my order' }))
  if (lang === 'ar') {
    await user.click(await screen.findByRole('button', { name: 'Switch to Arabic' }))
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'))
  }
  return user
}

function submitted() {
  const call = calls.find(c => c.url.endsWith('/requests'))
  return call ? JSON.parse(call.init.body as string) : null
}

const SHIRT_UNTRACKED: LookupLine = { ...SHIRT, orderItemId: 'oi-shirt', tracked: false }

describe('Untracked lines (Step 6b)', () => {
  test('an untracked line is selected like any other and submitted by orderItemId', async () => {
    config = { ...CONFIG, exchangesEnabled: undefined }
    lookupBody = lookupWith([SHIRT_UNTRACKED])
    const user = await toItems()
    await screen.findByRole('heading', { name: 'What would you like to return?' })
    expect(screen.queryByText(/not tracked/i)).toBeNull()
    const line = screen.getAllByTestId('portal-line')[0]
    await user.click(within(line).getByRole('button', { name: 'Increase quantity' }))
    await user.selectOptions(within(line).getByLabelText('Why are you returning it?'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByTestId('reference')
    expect(submitted()).toEqual({ lines: [{ orderItemId: 'oi-shirt', quantity: 1, reasonCode: 'wrong_size' }] })
  })

  test('a mixed order: a tracked and an untracked line of the SAME variant are chosen separately', async () => {
    config = { ...CONFIG, exchangesEnabled: undefined }
    lookupBody = lookupWith([SHIRT, SHIRT_UNTRACKED])
    const user = await toItems()
    await screen.findByRole('heading', { name: 'What would you like to return?' })
    const [tracked, untracked] = screen.getAllByTestId('portal-line')
    await user.click(within(tracked).getByRole('button', { name: 'Increase quantity' }))
    await user.selectOptions(within(tracked).getByLabelText('Why are you returning it?'), 'wrong_size')
    await user.click(within(untracked).getByRole('button', { name: 'Increase quantity' }))
    await user.selectOptions(within(untracked).getByLabelText('Why are you returning it?'), 'other')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    expect(screen.getAllByTestId('summary-line')).toHaveLength(2)
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByTestId('reference')
    expect(submitted().lines).toEqual([
      { variantId: 'v-white-m', quantity: 1, reasonCode: 'wrong_size' },
      { orderItemId: 'oi-shirt', quantity: 1, reasonCode: 'other' },
    ])
  })

  test('exchange mode with an untracked line', async () => {
    lookupBody = lookupWith([SHIRT_UNTRACKED])
    const user = await toItems()
    await screen.findByRole('heading', { name: 'What would you like to do?' })
    await user.click(screen.getByRole('radio', { name: /Exchange/ }))
    await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
    await user.selectOptions(screen.getByLabelText('Why are you exchanging it?'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Choose your new size or colour' })
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Exchange requested' })
    expect(submitted()).toEqual({
      mode: 'exchange',
      lines: [{ orderItemId: 'oi-shirt', quantity: 1, reasonCode: 'wrong_size' }],
      replacementVariantId: 'v-white-l',
      refundFallbackOk: true,
    })
  })
})
