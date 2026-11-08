import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PickupDistrict, PortalConfig } from '../portal/api'

// "Same address" area guess (2026-11-08): the original delivery had no district (Bosta still needs one) — the area is
// asked (city fixed) and pre-selected from lookup's suggestedDistrictId with a "check it" hint (EN + AR); changing it
// removes the hint; no suggestion → nothing selected and Send waits; in exchange mode a suggestion Bosta can't serve
// both ways is not pre-selected. Harness as portalSameAddress.test.tsx.

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

describe('"Same address" area guess', () => {
  const NO_DISTRICT = { ...PICKUP, preselectedDistrictId: null, exchangeDistrictIds: ['Iy7-lFD0BE0'] }

  test('suggested: pre-selected with a hint; sent as chosen', async () => {
    lookupBody = { ...lookupBody, pickup: { ...NO_DISTRICT, suggestedDistrictId: 'D10' } }
    const user = await toDetails()
    expect(screen.getByLabelText('City')).toHaveValue('Cairo')
    expect(screen.getByLabelText('Area')).toHaveValue('D10')
    expect(screen.getByTestId('area-guessed')).toHaveTextContent("We picked this from your delivery address — change it if it's wrong.")
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    expect(submitted().districtId).toBe('D10')
  })

  test('changed by the customer: the hint goes, their choice is sent', async () => {
    lookupBody = { ...lookupBody, pickup: { ...NO_DISTRICT, suggestedDistrictId: 'D10' } }
    const user = await toDetails()
    await user.selectOptions(screen.getByLabelText('Area'), 'Iy7-lFD0BE0')
    expect(screen.queryByTestId('area-guessed')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    expect(submitted().districtId).toBe('Iy7-lFD0BE0')
  })

  test('no suggestion: nothing selected, Send waits for an area', async () => {
    lookupBody = { ...lookupBody, pickup: NO_DISTRICT }
    await toDetails()
    expect(screen.getByLabelText('Area')).toHaveValue('')
    expect(screen.queryByTestId('area-guessed')).toBeNull()
    expect(screen.getByRole('button', { name: 'Send return request' })).toBeDisabled()
  })

  test('exchange: a suggestion without Bosta drop-off is not pre-selected', async () => {
    lookupBody = { ...lookupBody, pickup: { ...NO_DISTRICT, suggestedDistrictId: 'D10' } }
    await toDetails({ exchange: true })
    expect(screen.getByLabelText('Area')).toHaveValue('')
    expect(screen.queryByTestId('area-guessed')).toBeNull()
  })

  test('Arabic hint', async () => {
    lookupBody = { ...lookupBody, pickup: { ...NO_DISTRICT, suggestedDistrictId: 'D10' } }
    await toDetails({ lang: 'ar' })
    expect(screen.getByTestId('area-guessed')).toHaveTextContent('اخترناها من عنوان التوصيل')
  })
})
