import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PickupDistrict, PortalConfig } from '../portal/api'

// "Same address" when the original delivery had NO district (lookup pickup.districtOptional, 2026-11-07): no city or
// area field, no district sent (EN + AR); the area question stays for "A different address". Harness as
// portalSameAddress.test.tsx.

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
  exchangeOptions: [{ variantId: 'v-white-l', title: 'White / L', options: ['White', 'L'], inStock: true }],
}

const CAIRO = 'FceDyHXwpSYYF9zGW', ALEX = 'Jrb6X6ucjiYgMP4T7'

const PICKUP = {
  cityId: CAIRO, cityName: 'Cairo', cityNameAr: 'القاهرة',
  districts: [
    { id: 'Iy7-lFD0BE0', name: 'Nasr City - 7th District', nameAr: 'مدينة نصر - الحي السابع', zoneName: 'Nasr City', zoneNameAr: 'مدينة نصر' },
    { id: 'D10', name: '1st Settlement - District 10', nameAr: 'التجمع الاول - الحي 10', zoneName: 'New Cairo', zoneNameAr: 'القاهره الجديده' },
  ],
  preselectedDistrictId: 'Iy7-lFD0BE0',
  exchangeDistrictIds: ['Iy7-lFD0BE0'],
  cities: [
    { id: ALEX, name: 'Alexandria', nameAr: 'الإسكندرية' },
    { id: CAIRO, name: 'Cairo', nameAr: 'القاهرة' },
  ],
}

const ALEX_DISTRICTS: PickupDistrict[] = [
  { id: 'smouha', name: 'Smouha', nameAr: 'سموحة', zoneName: 'East', zoneNameAr: 'شرق' },
  { id: 'sidi-gaber', name: 'Sidi Gaber', nameAr: 'سيدي جابر', zoneName: 'East', zoneNameAr: 'شرق' },
  { id: 'agami', name: 'Agami', nameAr: 'العجمي', zoneName: 'West', zoneNameAr: 'غرب' },
]

function respond(status: number, body?: unknown) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body),
    text: async () => JSON.stringify(body ?? ''),
  })
}

let lookupBody: LookupResult
let calls: Array<{ url: string; init: RequestInit }>
let districtsStatus: number

beforeEach(() => {
  lookupBody = { token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z', lines: [SHIRT], pickup: PICKUP }
  calls = []
  districtsStatus = 200
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    if (url.endsWith('/config')) return respond(200, CONFIG)
    if (url.endsWith('/lookup')) return respond(200, lookupBody)
    if (url.includes('/districts?')) {
      if (districtsStatus !== 200) return respond(districtsStatus, { message: 'x' })
      const exchange = url.includes('mode=exchange')
      return respond(200, { cityId: ALEX, districts: exchange ? ALEX_DISTRICTS.slice(0, 1) : ALEX_DISTRICTS })
    }
    if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'requested' })
    return respond(404)
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  document.documentElement.dir = 'ltr'
  document.documentElement.lang = 'en'
})

async function toDetails(opts: { lang?: 'en' | 'ar'; exchange?: boolean } = {}) {
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
  await screen.findByRole('heading', { name: 'Start a return' })
  await user.type(screen.getByLabelText('Order number'), '#1047')
  await user.type(screen.getByLabelText('Phone number'), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: 'Find my order' }))
  await screen.findByRole('heading', { name: 'What would you like to do?' })
  if (opts.exchange) {
    await user.click(screen.getByRole('radio', { name: /Exchange/ }))
    await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
    await user.selectOptions(screen.getByLabelText('Why are you exchanging it?'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Choose your new size or colour' })
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
  } else {
    const shirt = screen.getByText('Linen Shirt').closest('section')!
    await user.click(within(shirt).getByRole('button', { name: 'Increase quantity' }))
    await user.selectOptions(within(shirt).getByLabelText('Why are you returning it?'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
  }
  await screen.findByRole('heading', { name: 'Almost done' })
  if (opts.lang === 'ar') {
    await user.click(screen.getByRole('button', { name: 'Switch to Arabic' }))
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'))
  }
  return user
}

function submitted() {
  const call = calls.find(c => c.url.endsWith('/requests'))
  return call ? JSON.parse(call.init.body as string) : null
}

const districtCalls = () => calls.filter(c => c.url.includes('/districts?'))

describe('"Same address" without an original district', () => {
  test('no area field; nothing about the area is sent', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, preselectedDistrictId: null, districtOptional: true } }
    const user = await toDetails()
    expect(screen.getByRole('radio', { name: 'Same as my delivery address (Cairo)' })).toBeChecked()
    expect(screen.queryByLabelText('Area')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('City')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    const body = submitted()
    for (const key of ['districtId', 'addressSource', 'cityId', 'firstLine']) expect(body).not.toHaveProperty(key)
  })

  test('no address choice: one "Pickup from" line with the city (EN + AR)', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, preselectedDistrictId: null, districtOptional: true, cities: undefined } }
    const user = await toDetails()
    expect(screen.getByTestId('pickup-place')).toHaveTextContent('Pickup from: Cairo')
    await user.click(screen.getByRole('button', { name: 'Switch to Arabic' }))
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'))
    expect(screen.getByTestId('pickup-place')).toHaveTextContent('الاستلام من: القاهرة')
  })

  test('exchange mode: no area asked either', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, preselectedDistrictId: null, districtOptional: true } }
    const user = await toDetails({ exchange: true })
    expect(screen.queryByLabelText('Area')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await waitFor(() => expect(submitted()).not.toBeNull())
    expect(submitted()).not.toHaveProperty('districtId')
  })

  test('"A different address" still asks for the area', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, preselectedDistrictId: null, districtOptional: true } }
    const user = await toDetails()
    await user.click(screen.getByRole('radio', { name: 'A different address' }))
    await user.selectOptions(screen.getByLabelText('Governorate'), ALEX)
    await waitFor(() => expect(screen.getByLabelText('Area')).toBeEnabled())
    await user.type(screen.getByLabelText('Street and building number'), '14 Fawzy Moaz St')
    expect(screen.getByRole('button', { name: 'Send return request' })).toBeDisabled()
  })
})
