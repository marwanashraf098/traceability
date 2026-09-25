import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { PortalSettings, ReturnRequestDetail, ReturnRequestPickupAreas, ReturnRequestRow } from '../api'
import i18n from '../i18n'

// Returns portal Step 4c-2 — the drawer's Pickup (request snapshot, falling back to the order
// address) and "Change area". Fakes match ReturnRequestService: detail carries pickupCityName /
// pickupDistrictName(+Ar); GET /pickup-areas {cityId, cityName, cityNameAr, districts,
// selectedDistrictId, editable}; PUT /pickup-area {districtId} → 204, 409 when not editable.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const ROW: ReturnRequestRow = {
  id: 'rr-1', reference: 'RR-7K3F9M', orderNumber: '#1047', customerName: 'Mariam Saleh', itemCount: 1,
  reasonCodes: ['wrong_size'], status: 'requested', createdAt: new Date().toISOString(),
}

const AREAS: ReturnRequestPickupAreas = {
  cityId: 'FceDyHXwpSYYF9zGW', cityName: 'Cairo', cityNameAr: 'القاهرة',
  districts: [
    { id: 'Iy7-lFD0BE0', name: 'Nasr City - 7th District', nameAr: 'مدينة نصر - الحي السابع', zoneName: 'Nasr City', zoneNameAr: 'مدينة نصر' },
    { id: 'wY_JL43TilR', name: '1st Settlement - District 10', nameAr: 'التجمع الاول - الحي 10', zoneName: 'New Cairo', zoneNameAr: 'القاهره الجديده' },
  ],
  selectedDistrictId: null,
  editable: true,
}

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
}

let detail: ReturnRequestDetail
let putStatus: number
let calls: Array<{ method: string; url: string; body?: unknown }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 409 ? 'Conflict' : 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
    text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/pickup-area') && method === 'PUT') {
    if (putStatus !== 204) return fakeResponse(null, putStatus)
    const d = AREAS.districts.find(x => x.id === body.districtId)!
    detail = { ...detail, pickupCityName: 'Cairo', pickupDistrictId: d.id, pickupDistrictName: d.name, pickupDistrictNameAr: d.nameAr }
    return fakeResponse(null, 204)
  }
  if (url.endsWith('/pickup-areas')) return fakeResponse({ ...AREAS, selectedDistrictId: detail.pickupDistrictId })
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) return fakeResponse({ items: [{ ...ROW, status: detail.status }], total: 1 })
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  detail = {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'refund', status: 'requested', email: null, note: 'It runs small.',
    createdAt: ROW.createdAt, deliveredAt: '2026-09-18T10:00:00.000+00:00',
    pickupCity: 'Cairo', pickupZone: 'Nasr City',
    pickupCityId: null, pickupCityName: null, pickupDistrictId: null, pickupDistrictName: null, pickupDistrictNameAr: null,
    decidedAt: null, decidedBy: null, decidedByName: null, rejectionReason: null, returnShipmentId: null,
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true }],
  }
  putStatus = 204
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

async function openDrawer() {
  const user = userEvent.setup()
  // The app's own i18n instance (with Arabic), nested inside the test providers — as returnsCourierReturn.test does.
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>)
  await user.click(await screen.findByRole('button', { name: /Requests/ }))
  await user.click(await screen.findByText('RR-7K3F9M'))
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findByText('It runs small.')
  return { user, drawer }
}

afterEach(async () => { await i18n.changeLanguage('en') })

describe('Drawer → pickup area', () => {
  test('falls back to the order address when no area was chosen', async () => {
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('request-pickup')).toHaveTextContent('Cairo · Nasr City')
  })

  test('Change area: grouped select, save sends the district, the drawer shows the new area', async () => {
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Change area' }))
    const select = await within(drawer).findByLabelText(/Pickup area/) as HTMLSelectElement
    expect(Array.from(select.querySelectorAll('optgroup')).map(g => g.label)).toEqual(['Nasr City', 'New Cairo'])
    const save = within(drawer).getByRole('button', { name: 'Save area' })
    expect(save).toBeDisabled()

    await user.selectOptions(select, 'wY_JL43TilR')
    await user.click(save)

    await waitFor(() => expect(within(drawer).getByTestId('request-pickup')).toHaveTextContent('Cairo · 1st Settlement - District 10'))
    expect(calls.find(c => c.method === 'PUT' && c.url.endsWith('/return-requests/rr-1/pickup-area'))?.body)
      .toEqual({ districtId: 'wY_JL43TilR' })
    expect(within(drawer).queryByTestId('area-editor')).not.toBeInTheDocument()
  })

  test('a 409 (no longer editable) tells the user and reloads', async () => {
    putStatus = 409
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Change area' }))
    await user.selectOptions(await within(drawer).findByLabelText(/Pickup area/), 'Iy7-lFD0BE0')
    await user.click(within(drawer).getByRole('button', { name: 'Save area' }))
    expect(await screen.findByText("The area can't be changed at this stage.")).toBeInTheDocument()
  })

  test('not offered once the request is rejected; Arabic shows the Arabic district name', async () => {
    detail = { ...detail, status: 'rejected', pickupCityName: 'Cairo', pickupDistrictName: 'Nasr City - 7th District',
      pickupDistrictNameAr: 'مدينة نصر - الحي السابع', rejectionReason: 'Worn' }
    const { drawer } = await openDrawer()
    expect(within(drawer).queryByRole('button', { name: 'Change area' })).not.toBeInTheDocument()
    expect(within(drawer).getByTestId('request-pickup')).toHaveTextContent('Cairo · Nasr City - 7th District')
    await i18n.changeLanguage('ar')
    await waitFor(() => expect(within(drawer).getByTestId('request-pickup')).toHaveTextContent('مدينة نصر - الحي السابع'))
  })
})
