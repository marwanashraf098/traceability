import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PortalConfig } from '../portal/api'

// Step 5b — portal exchange requests (X1 → X2 → P3 step 3 of 3 → X3). Fakes match PortalService:
// config carries `exchangesEnabled: true` only when the tenant has exchanges on; lookup lines
// then carry {optionAxes, currentOptions, exchangeOptions:[{variantId,title,options,inStock}]};
// submit takes {mode:'exchange', lines:[1 × qty 1], replacementVariantId, refundFallbackOk}.

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

describe('X1 — Return / Exchange choice', () => {
  test('hidden when exchanges are not enabled: the refund step is unchanged', async () => {
    config = { ...CONFIG, exchangesEnabled: undefined }
    await toItems()
    await screen.findByRole('heading', { name: 'What would you like to return?' })
    expect(screen.queryByRole('radio', { name: /Exchange/ })).toBeNull()
    expect(screen.queryByText('Another size or colour')).toBeNull()
  })

  test('enabled: Return is chosen first; Exchange lists one-item radios and marks lines with no sibling', async () => {
    const user = await toItems()
    await screen.findByRole('heading', { name: 'What would you like to do?' })
    expect(screen.getByRole('radio', { name: /Return/ })).toBeChecked()

    await user.click(screen.getByRole('radio', { name: /Exchange/ }))
    expect(screen.getByText('Step 1 of 3')).toBeInTheDocument()
    expect(screen.getByText('Which item?')).toBeInTheDocument()
    const lines = screen.getAllByTestId('exchange-line')
    expect(within(lines[1]).getByText(/Can't be exchanged/)).toBeInTheDocument()
    expect(within(lines[1]).queryByRole('radio')).toBeNull()
    expect(screen.getByText(/One item per exchange/)).toBeInTheDocument()

    const next = screen.getByRole('button', { name: 'Continue' })
    expect(next).toBeDisabled()
    await user.click(within(lines[0]).getByRole('radio'))
    expect(next).toBeDisabled()                                  // reason still missing
    await user.selectOptions(screen.getByLabelText('Why are you exchanging it?'), 'wrong_size')
    expect(next).toBeEnabled()
  })
})

describe('X2 → P3 → X3', () => {
  async function toVariant() {
    const user = await toItems()
    await screen.findByRole('heading', { name: 'What would you like to do?' })
    await user.click(screen.getByRole('radio', { name: /Exchange/ }))
    await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
    await user.selectOptions(screen.getByLabelText('Why are you exchanging it?'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Choose your new size or colour' })
    return user
  }

  test('chips: own variant "yours" and out of stock are disabled; fallback pre-ticked; summary on choice', async () => {
    const user = await toVariant()
    expect(screen.getByTestId('exchange-have')).toHaveTextContent('You have: White · M')

    const colour = screen.getByRole('group', { name: 'Colour' })
    const size = screen.getByRole('group', { name: 'Size' })
    expect(within(colour).getByRole('radio', { name: 'White' })).toBeChecked()
    expect(within(size).getByRole('radio', { name: 'M · yours' })).toBeDisabled()
    expect(within(size).getByRole('radio', { name: 'S · out of stock' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: /refund instead/ })).toBeChecked()

    const next = screen.getByRole('button', { name: 'Continue' })
    expect(next).toBeDisabled()
    expect(screen.queryByTestId('exchange-summary')).toBeNull()
    await user.click(within(size).getByRole('radio', { name: 'L' }))
    expect(screen.getByTestId('exchange-summary')).toHaveTextContent('Linen Shirt · White · L')
    expect(screen.getByTestId('exchange-summary')).toHaveTextContent('In stock · same price, nothing extra to pay')
    expect(next).toBeEnabled()
  })

  test('full flow: step 3 of 3, exchange payload, X3 confirmation', async () => {
    const user = await toVariant()
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('checkbox', { name: /refund instead/ }))     // untick
    await user.click(screen.getByRole('button', { name: 'Continue' }))

    await screen.findByRole('heading', { name: 'Almost done' })
    expect(screen.getByText('Step 3 of 3')).toBeInTheDocument()
    expect(screen.getByText('Exchanging')).toBeInTheDocument()
    expect(screen.getByTestId('summary-line')).toHaveTextContent('Linen Shirt · White · M → White · L')

    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Exchange requested' })
    expect(submitted()).toEqual({
      mode: 'exchange',
      lines: [{ variantId: 'v-white-m', quantity: 1, reasonCode: 'wrong_size' }],
      replacementVariantId: 'v-white-l',
      refundFallbackOk: false,
    })
    expect(screen.getByTestId('sent-lead')).toHaveTextContent('Linen Shirt · White · M → White · L')
    expect(screen.getByText('Nour Studio reviews your request.')).toBeInTheDocument()
    expect(screen.getByText(/One Bosta courier brings your new size/)).toBeInTheDocument()
  })

  test('Arabic: X1 and X2 copy', async () => {
    const user = await toItems('ar')
    await screen.findByRole('heading', { name: 'ماذا تريد أن تفعل؟' })
    await user.click(screen.getByRole('radio', { name: /استبدال/ }))
    expect(screen.getByText('أي منتج؟')).toBeInTheDocument()
    await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
    await user.selectOptions(screen.getByLabelText('لماذا تريد استبداله؟'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'متابعة' }))
    await screen.findByRole('heading', { name: 'اختر المقاس أو اللون الجديد' })
    expect(within(screen.getByRole('group', { name: 'المقاس' })).getByRole('radio', { name: 'M · الحالي' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: /استرداد المبلغ بدلاً منه/ })).toBeChecked()
  })
})
