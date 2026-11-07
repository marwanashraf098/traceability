import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PickupDistrict, PortalConfig } from '../portal/api'

// "Same address" pickup (2026-11-07). Bosta needs a district on the pickup (docs/vendor/bosta-api.yaml), so:
//   known    — the original delivery's district is known and offered for the mode: no city or area field at all;
//              the submission carries that district (and no address field)
//   noChoice — the same without a different-address option: one "Pickup from" line instead of the fields
//   unknown  — no original district: the area is asked (city shown fixed); the chosen one is sent
//   exchange — the original district has no Bosta drop-off: the area is asked from the exchange districts
//   custom   — "A different address" is unchanged: governorate + area + street are asked and sent
// Harness as portalCustomAddress.test.tsx.

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

describe('"Same address" pickup', () => {
  test('known: no city or area field; the original district is sent', async () => {
    const user = await toDetails()
    expect(screen.getByRole('radio', { name: 'Same as my delivery address (Nasr City - 7th District, Cairo)' })).toBeChecked()
    expect(screen.queryByLabelText('Area')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('City')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pickup-place')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    const body = submitted()
    expect(body.districtId).toBe('Iy7-lFD0BE0')
    for (const key of ['addressSource', 'cityId', 'firstLine', 'secondLine', 'buildingNumber', 'floor', 'apartment']) {
      expect(body).not.toHaveProperty(key)
    }
    expect(districtCalls()).toHaveLength(0)
  })

  test('noChoice: one "Pickup from" line instead of the fields (EN + AR)', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, cities: undefined } }
    const user = await toDetails()
    expect(screen.getByTestId('pickup-place')).toHaveTextContent('Pickup from: Nasr City - 7th District, Cairo')
    expect(screen.queryByLabelText('Area')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Switch to Arabic' }))
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'))
    expect(screen.getByTestId('pickup-place')).toHaveTextContent('الاستلام من: مدينة نصر - الحي السابع، القاهرة')
    await user.click(screen.getByRole('button', { name: 'إرسال طلب الإرجاع' }))
    await waitFor(() => expect(submitted()?.districtId).toBe('Iy7-lFD0BE0'))
  })

  test('unknown: the area is asked (city fixed) and the chosen one is sent', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, preselectedDistrictId: null } }
    const user = await toDetails()
    expect(screen.getByLabelText('City')).toHaveValue('Cairo')
    expect(screen.getByLabelText('City')).toHaveAttribute('readonly')
    const area = screen.getByLabelText('Area')
    expect(area).toHaveValue('')
    await user.selectOptions(area, 'D10')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    expect(submitted().districtId).toBe('D10')
  })

  test('unknown: an original district that is not offered (not pickup-available) is asked too', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, preselectedDistrictId: 'gone-district' } }
    await toDetails()
    expect(screen.getByLabelText('Area')).toHaveValue('')
  })

  test('exchange: the original district has no drop-off → the area is asked from the exchange districts', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, exchangeDistrictIds: ['D10'] } }
    const user = await toDetails({ exchange: true })
    const area = screen.getByLabelText('Area') as HTMLSelectElement
    expect(Array.from(area.options).filter(o => o.value).map(o => o.value)).toEqual(['D10'])
    await user.selectOptions(area, 'D10')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await waitFor(() => expect(submitted()?.districtId).toBe('D10'))
  })

  test('custom: "A different address" still asks for governorate, area and street', async () => {
    const user = await toDetails()
    await user.click(screen.getByRole('radio', { name: 'A different address' }))
    expect(screen.getByLabelText('Governorate')).toBeInTheDocument()
    expect(screen.queryByTestId('pickup-place')).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Governorate'), ALEX)
    await waitFor(() => expect(screen.getByLabelText('Area')).toBeEnabled())
    await user.selectOptions(screen.getByLabelText('Area'), 'smouha')
    await user.type(screen.getByLabelText('Street and building number'), '14 Fawzy Moaz St')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    expect(submitted()).toMatchObject({ addressSource: 'custom', cityId: ALEX, districtId: 'smouha', firstLine: '14 Fawzy Moaz St' })
  })
})
