import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PortalConfig } from '../portal/api'

// Step 5c — the P3 area list in exchange mode: only districts Bosta both delivers to and collects
// from (lookup pickup.exchangeDistrictIds, present when the store allows exchanges). Refund mode
// keeps every pickup-available district. Harness as portalExchanges.test.tsx.

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 30, reasonCodes: ['wrong_size', 'other'],
  logoUrl: null, brandColor: '#1F5C4A', policyText: null, autoApprove: false, pickupBooking: true,
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

const PICKUP = {
  cityId: 'FceDyHXwpSYYF9zGW', cityName: 'Cairo', cityNameAr: 'القاهرة',
  districts: [
    { id: 'Iy7-lFD0BE0', name: 'Nasr City - 7th District', nameAr: 'مدينة نصر - الحي السابع', zoneName: 'Nasr City', zoneNameAr: 'مدينة نصر' },
    { id: 'PickupOnly01', name: 'Pickup Only District', nameAr: 'استلام فقط', zoneName: 'Nasr City', zoneNameAr: 'مدينة نصر' },
  ],
  preselectedDistrictId: 'PickupOnly01',
  exchangeDistrictIds: ['Iy7-lFD0BE0'],
}

function lookupWith(lines: LookupLine[]): LookupResult {
  return { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines, pickup: PICKUP }
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

describe('P3 area in exchange mode', () => {
  function areaOptions() {
    return Array.from((screen.getByLabelText('Area') as HTMLSelectElement).querySelectorAll('option'))
      .filter(o => o.value).map(o => o.value)
  }

  test('exchange: only districts that allow pickup AND drop-off; a pickup-only preselection is not kept', async () => {
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

    expect(areaOptions()).toEqual(['Iy7-lFD0BE0'])
    expect(screen.getByLabelText('Area')).toHaveValue('')
    expect(screen.getByText(/One Bosta courier brings your new size and collects the old item/)).toBeInTheDocument()
    const send = screen.getByRole('button', { name: 'Send return request' })
    expect(send).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Area'), 'Iy7-lFD0BE0')
    await user.click(send)
    await screen.findByRole('heading', { name: 'Exchange requested' })
    expect(submitted().districtId).toBe('Iy7-lFD0BE0')
  })

  test('refund: every pickup-available district, preselection kept', async () => {
    const user = await toItems()
    await screen.findByRole('heading', { name: 'What would you like to do?' })
    const shirt = screen.getByText('Linen Shirt').closest('section')!
    await user.click(within(shirt).getByRole('button', { name: 'Increase quantity' }))
    await user.selectOptions(within(shirt).getByLabelText('Why are you returning it?'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    expect(areaOptions()).toEqual(['Iy7-lFD0BE0', 'PickupOnly01'])
    expect(screen.getByLabelText('Area')).toHaveValue('PickupOnly01')
  })
})
