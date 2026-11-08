import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PickupDistrict, PortalConfig } from '../portal/api'

// "Same address" area guess, ZONE_ONLY (2026-11-08 evening): the original address names a Bosta zone with too many
// areas to pick one — lookup sends suggestedZoneDistrictIds; the area picker opens filtered to them with a hint (EN +
// AR), nothing pre-selected, "Show all areas" clears the filter; in exchange mode the filter keeps only districts Bosta
// serves both ways (none left → no filter); no key → no filter. Harness as portalAreaGuess.test.tsx.

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
    { id: 'D12', name: '5th Settlement', nameAr: 'التجمع الخامس', zoneName: 'New Cairo', zoneNameAr: 'القاهره الجديده' },
    { id: 'D13', name: 'Rehab City', nameAr: 'الرحاب', zoneName: 'New Cairo', zoneNameAr: 'القاهره الجديده' },
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

const areaOptions = () => Array.from((screen.getByLabelText('Area') as HTMLSelectElement).options)
  .map(o => o.value).filter(v => v !== '')

describe('"Same address" area guess — zone only', () => {
  const NO_DISTRICT = { ...PICKUP, preselectedDistrictId: null, exchangeDistrictIds: ['Iy7-lFD0BE0'] }
  const ZONE = { ...NO_DISTRICT, suggestedZoneDistrictIds: ['D10', 'D12', 'D13'] }

  test('filtered to the zone, nothing pre-selected; the chosen area is sent', async () => {
    lookupBody = { ...lookupBody, pickup: ZONE }
    const user = await toDetails()
    expect(screen.getByLabelText('Area')).toHaveValue('')
    expect(areaOptions()).toEqual(['D10', 'D12', 'D13'])
    expect(screen.getByTestId('area-zone-filter')).toHaveTextContent('Showing areas in New Cairo, from your delivery address.')
    expect(screen.queryByTestId('area-guessed')).toBeNull()
    expect(screen.getByRole('button', { name: 'Send return request' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Area'), 'D12')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    expect(submitted().districtId).toBe('D12')
  })

  test('"Show all areas" clears the filter', async () => {
    lookupBody = { ...lookupBody, pickup: ZONE }
    const user = await toDetails()
    await user.click(screen.getByRole('button', { name: 'Show all areas' }))
    expect(areaOptions()).toEqual(['Iy7-lFD0BE0', 'D10', 'D12', 'D13'])
    expect(screen.queryByTestId('area-zone-filter')).toBeNull()
    await user.selectOptions(screen.getByLabelText('Area'), 'Iy7-lFD0BE0')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    expect(submitted().districtId).toBe('Iy7-lFD0BE0')
  })

  test('Arabic hint names the zone in Arabic', async () => {
    lookupBody = { ...lookupBody, pickup: ZONE }
    const user = await toDetails({ lang: 'ar' })
    expect(screen.getByTestId('area-zone-filter')).toHaveTextContent('نعرض مناطق القاهره الجديده حسب عنوان التوصيل.')
    expect(within(screen.getByTestId('area-zone-filter')).getByRole('button')).toHaveTextContent('عرض كل المناطق')
    void user
  })

  test('exchange: no zone district served both ways → no filter', async () => {
    lookupBody = { ...lookupBody, pickup: ZONE }
    await toDetails({ exchange: true })
    expect(areaOptions()).toEqual(['Iy7-lFD0BE0'])
    expect(screen.queryByTestId('area-zone-filter')).toBeNull()
  })

  test('no key → the full list, no hint', async () => {
    lookupBody = { ...lookupBody, pickup: NO_DISTRICT }
    await toDetails()
    expect(areaOptions()).toEqual(['Iy7-lFD0BE0', 'D10', 'D12', 'D13'])
    expect(screen.queryByTestId('area-zone-filter')).toBeNull()
  })
})
