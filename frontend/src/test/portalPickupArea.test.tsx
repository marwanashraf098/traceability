import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { render, screen, waitFor, within } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import type { LookupResult, PortalConfig } from '../portal/api'

// Returns portal Step 4c-2 — P3 pickup area. Fakes match PortalService: lookup's `pickup` is
// {cityId, cityName, cityNameAr, districts:[{id,name,nameAr,zoneName,zoneNameAr}],
// preselectedDistrictId} (districts sorted by zone) or null; submit takes `districtId`.

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 30, reasonCodes: ['wrong_size', 'other'],
  logoUrl: null, brandColor: '#1F5C4A', policyText: null, autoApprove: false, pickupBooking: true,
}

const PICKUP = {
  cityId: 'FceDyHXwpSYYF9zGW', cityName: 'Cairo', cityNameAr: 'القاهرة',
  districts: [
    { id: 'Iy7-lFD0BE0', name: 'Nasr City - 7th District', nameAr: 'مدينة نصر - الحي السابع', zoneName: 'Nasr City', zoneNameAr: 'مدينة نصر' },
    { id: 'wY_JL43TilR', name: '1st Settlement - District 10', nameAr: 'التجمع الاول - الحي 10', zoneName: 'New Cairo', zoneNameAr: 'القاهره الجديده' },
    { id: 'D12', name: '1st Settlement - District 12', nameAr: 'التجمع الاول - الحي 12', zoneName: 'New Cairo', zoneNameAr: 'القاهره الجديده' },
  ],
  preselectedDistrictId: null as string | null,
}

function lookupWith(pickup: LookupResult['pickup']): LookupResult {
  return {
    token: 'tok.sig', orderNumber: '#1047', deliveredAt: '2026-09-18T10:00:00Z',
    lines: [{ variantId: 'v-shirt', productTitle: 'Linen Shirt', variantTitle: 'White · M', imageUrl: null,
      deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false }],
    pickup,
  }
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

let lookupBody: LookupResult
let config: PortalConfig
let calls: Array<{ url: string; init: RequestInit }>

beforeEach(() => {
  lookupBody = lookupWith(PICKUP)
  config = CONFIG
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

async function toDetails() {
  const user = userEvent.setup()
  render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
  await screen.findByRole('heading', { name: 'Start a return' })
  await user.type(screen.getByLabelText('Order number'), '#1047')
  await user.type(screen.getByLabelText('Phone number'), '010 1234 5678')
  await user.click(screen.getByRole('button', { name: 'Find my order' }))
  await screen.findByRole('heading', { name: 'What would you like to return?' })
  const shirt = screen.getByText('Linen Shirt').closest('section')!
  await user.click(within(shirt).getByRole('button', { name: 'Increase quantity' }))
  await user.selectOptions(within(shirt).getByLabelText('Why are you returning it?'), 'wrong_size')
  await user.click(screen.getByRole('button', { name: 'Continue' }))
  await screen.findByRole('heading', { name: 'Almost done' })
  return user
}

function submitted() {
  const call = calls.find(c => c.url.endsWith('/requests'))
  return call ? JSON.parse(call.init.body as string) : null
}

describe('P3 pickup area', () => {
  test('City is read-only; Area is required, grouped by zone; Send is blocked until one is chosen', async () => {
    const user = await toDetails()

    const city = screen.getByLabelText('City')
    expect(city).toHaveValue('Cairo')
    expect(city).toHaveAttribute('readonly')

    const area = screen.getByLabelText('Area') as HTMLSelectElement
    expect(area).toBeRequired()
    expect(area).toHaveValue('')
    const groups = Array.from(area.querySelectorAll('optgroup'))
    expect(groups.map(g => g.label)).toEqual(['Nasr City', 'New Cairo'])
    expect(Array.from(groups[1].querySelectorAll('option')).map(o => o.textContent))
      .toEqual(['1st Settlement - District 10', '1st Settlement - District 12'])
    expect(within(area).getByRole('option', { name: 'Choose your area' })).toBeDisabled()

    const send = screen.getByRole('button', { name: 'Send return request' })
    expect(send).toBeDisabled()
    await user.click(send)
    expect(submitted()).toBeNull()

    await user.selectOptions(area, 'wY_JL43TilR')
    expect(send).toBeEnabled()
    await user.click(send)
    await screen.findByTestId('reference')
    expect(submitted()).toEqual({
      lines: [{ variantId: 'v-shirt', quantity: 1, reasonCode: 'wrong_size' }],
      districtId: 'wY_JL43TilR',
    })
  })

  test('the preselected district is chosen already, so Send is available', async () => {
    lookupBody = lookupWith({ ...PICKUP, preselectedDistrictId: 'Iy7-lFD0BE0' })
    const user = await toDetails()
    expect(screen.getByLabelText('Area')).toHaveValue('Iy7-lFD0BE0')
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByTestId('reference')
    expect(submitted().districtId).toBe('Iy7-lFD0BE0')
  })

  test('Arabic: city, zone groups and districts use the Arabic names', async () => {
    const user = await toDetails()
    await user.click(screen.getByRole('button', { name: 'Switch to Arabic' }))
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'))

    expect(screen.getByLabelText('المدينة')).toHaveValue('القاهرة')
    const area = screen.getByLabelText('المنطقة') as HTMLSelectElement
    expect(Array.from(area.querySelectorAll('optgroup')).map(g => g.label)).toEqual(['مدينة نصر', 'القاهره الجديده'])
    expect(within(area).getByRole('option', { name: 'مدينة نصر - الحي السابع' })).toBeInTheDocument()
    expect(within(area).getByRole('option', { name: 'اختر منطقتك' })).toBeInTheDocument()
  })

  test('no pickup offered → no City/Area fields, the previous copy, and no districtId sent', async () => {
    lookupBody = lookupWith(null)
    config = { ...CONFIG, pickupBooking: false }
    const user = await toDetails()
    expect(screen.queryByLabelText('Area')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('City')).not.toBeInTheDocument()
    expect(screen.getByText('The store will arrange a courier to collect the item from the address this order was delivered to.'))
      .toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByTestId('reference')
    expect(submitted()).not.toHaveProperty('districtId')
  })
})
