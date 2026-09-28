import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupLine, LookupResult, PickupDistrict, PortalConfig } from '../portal/api'

// V117 — P3 "Pickup address": the delivery address (default, unchanged submission) or a different
// address — governorate (lookup pickup.cities) → areas from GET /districts (Bearer lookup token,
// mode=exchange in exchange mode, grouped by zone) → street (required, > 5 characters) + optional
// building / floor / apartment / landmark. Send stays disabled until the address is complete.
// Harness as portalExchangeAreas.test.tsx.

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

describe('P3 pickup address', () => {
  test('default: same as the delivery address (area, city) — the submission is unchanged', async () => {
    const user = await toDetails()
    const same = screen.getByRole('radio', { name: 'Same as my delivery address (Nasr City - 7th District, Cairo)' })
    expect(same).toBeChecked()
    expect(screen.getByRole('radio', { name: 'A different address' })).not.toBeChecked()
    expect(screen.getByLabelText('Area')).toHaveValue('Iy7-lFD0BE0')
    expect(screen.queryByLabelText('Governorate')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    const body = submitted()
    expect(body.districtId).toBe('Iy7-lFD0BE0')
    for (const key of ['addressSource', 'cityId', 'firstLine', 'secondLine', 'buildingNumber', 'floor', 'apartment']) {
      expect(body).not.toHaveProperty(key)
    }
    expect(districtCalls()).toHaveLength(0)
  })

  test('different address: governorate → areas (by zone) → street validation → sends the custom address', async () => {
    const user = await toDetails()
    await user.click(screen.getByRole('radio', { name: 'A different address' }))
    expect(screen.getByText('A Bosta courier collects the item from the address you enter below.')).toBeInTheDocument()
    const send = screen.getByRole('button', { name: 'Send return request' })
    expect(send).toBeDisabled()
    expect(screen.getByLabelText('Area')).toBeDisabled()

    const gov = screen.getByLabelText('Governorate') as HTMLSelectElement
    expect(Array.from(gov.options).filter(o => o.value).map(o => o.textContent)).toEqual(['Alexandria', 'Cairo'])
    await user.selectOptions(gov, ALEX)
    await waitFor(() => expect(screen.getByLabelText('Area')).toBeEnabled())
    const req = districtCalls()[0]
    expect(req.url).toBe(`/api/v1/portal/nourstudio/districts?cityId=${ALEX}`)
    expect((req.init.headers as Record<string, string>).Authorization).toBe('Bearer tok.sig')

    const area = screen.getByLabelText('Area') as HTMLSelectElement
    expect(Array.from(area.querySelectorAll('optgroup')).map(g => g.label)).toEqual(['East', 'West'])
    await user.selectOptions(area, 'sidi-gaber')
    expect(send).toBeDisabled()

    const street = screen.getByLabelText('Street and building number')
    await user.type(street, '12 St')
    await user.tab()
    expect(screen.getByText('Add a little more detail, at least 6 characters.')).toBeInTheDocument()
    expect(street).toHaveAttribute('aria-invalid', 'true')
    expect(send).toBeDisabled()
    await user.clear(street)
    expect(screen.getByText('Enter the street and building number.')).toBeInTheDocument()
    await user.type(street, '  12 Fouad Street  ')
    expect(screen.queryByText('Enter the street and building number.')).not.toBeInTheDocument()
    expect(send).toBeEnabled()

    await user.type(screen.getByLabelText(/^Building/), '4B')
    await user.type(screen.getByLabelText(/^Apartment/), '9')
    await user.type(screen.getByLabelText(/^Landmark/), 'Behind the mosque')
    await user.click(send)
    await screen.findByRole('heading', { name: 'Request sent' })
    const body = submitted()
    expect(body).toMatchObject({
      addressSource: 'custom', cityId: ALEX, districtId: 'sidi-gaber', firstLine: '12 Fouad Street',
      buildingNumber: '4B', apartment: '9', secondLine: 'Behind the mosque',
    })
    expect(body).not.toHaveProperty('floor')
  })

  test('changing the governorate clears the area; switching back to the delivery address sends it as before', async () => {
    const user = await toDetails()
    await user.click(screen.getByRole('radio', { name: 'A different address' }))
    await user.selectOptions(screen.getByLabelText('Governorate'), ALEX)
    await waitFor(() => expect(screen.getByLabelText('Area')).toBeEnabled())
    await user.selectOptions(screen.getByLabelText('Area'), 'smouha')
    await user.selectOptions(screen.getByLabelText('Governorate'), CAIRO)
    await waitFor(() => expect(districtCalls()).toHaveLength(2))
    expect(districtCalls()[1].url).toContain(`cityId=${CAIRO}`)
    expect(screen.getByLabelText('Area')).toHaveValue('')

    await user.click(screen.getByRole('radio', { name: /Same as my delivery address/ }))
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Request sent' })
    expect(submitted()).not.toHaveProperty('addressSource')
    expect(submitted().districtId).toBe('Iy7-lFD0BE0')
  })

  test('exchange mode asks for areas Bosta both delivers to and collects from', async () => {
    const user = await toDetails({ exchange: true })
    await user.click(screen.getByRole('radio', { name: 'A different address' }))
    expect(screen.getByText(/One Bosta courier brings your new size and collects the old item from the address you enter below/))
      .toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Governorate'), ALEX)
    await waitFor(() => expect(screen.getByLabelText('Area')).toBeEnabled())
    expect(districtCalls()[0].url).toBe(`/api/v1/portal/nourstudio/districts?cityId=${ALEX}&mode=exchange`)
    const options = Array.from((screen.getByLabelText('Area') as HTMLSelectElement).options).filter(o => o.value).map(o => o.value)
    expect(options).toEqual(['smouha'])

    await user.selectOptions(screen.getByLabelText('Area'), 'smouha')
    await user.type(screen.getByLabelText('Street and building number'), '12 Fouad Street')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Exchange requested' })
    expect(submitted()).toMatchObject({ mode: 'exchange', addressSource: 'custom', cityId: ALEX, districtId: 'smouha' })
  })

  test('areas failed to load → message and Try again; an expired token goes back to the start', async () => {
    districtsStatus = 500
    const user = await toDetails()
    await user.click(screen.getByRole('radio', { name: 'A different address' }))
    await user.selectOptions(screen.getByLabelText('Governorate'), ALEX)
    expect(await screen.findByText(/We couldn't load the areas\./)).toBeInTheDocument()
    districtsStatus = 200
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(screen.getByLabelText('Area')).toBeEnabled())
    expect(districtCalls()).toHaveLength(2)

    districtsStatus = 401
    await user.selectOptions(screen.getByLabelText('Governorate'), CAIRO)
    expect(await screen.findByRole('heading', { name: 'Start a return' })).toBeInTheDocument()
    expect(screen.getByTestId('start-banner')).toHaveTextContent('Your session has expired')
  })

  test('no cities in the offer → no address choice (as before)', async () => {
    lookupBody = { ...lookupBody, pickup: { ...PICKUP, cities: undefined } }
    await toDetails()
    expect(screen.queryByRole('radio', { name: 'A different address' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Area')).toHaveValue('Iy7-lFD0BE0')
  })

  test('Arabic: RTL, labels and names in Arabic', async () => {
    const user = await toDetails({ lang: 'ar' })
    expect(screen.getByRole('radio', { name: 'نفس عنوان التوصيل (مدينة نصر - الحي السابع، القاهرة)' })).toBeChecked()
    await user.click(screen.getByRole('radio', { name: 'عنوان آخر' }))
    const gov = screen.getByLabelText('المحافظة') as HTMLSelectElement
    expect(Array.from(gov.options).filter(o => o.value).map(o => o.textContent)).toEqual(['الإسكندرية', 'القاهرة'])
    await user.selectOptions(gov, ALEX)
    await waitFor(() => expect(screen.getByLabelText('المنطقة')).toBeEnabled())
    expect(Array.from((screen.getByLabelText('المنطقة') as HTMLSelectElement).querySelectorAll('optgroup')).map(g => g.label))
      .toEqual(['شرق', 'غرب'])
    const street = screen.getByLabelText('الشارع ورقم العقار')
    await user.type(street, 'abc')
    await user.tab()
    expect(screen.getByText('أضف تفاصيل أكثر، 6 أحرف على الأقل.')).toBeInTheDocument()
    expect(screen.getByLabelText(/^الدور/)).toBeInTheDocument()
    expect(screen.getByLabelText(/^علامة مميزة/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'إرسال طلب الإرجاع' })).toBeDisabled()
  })
})
