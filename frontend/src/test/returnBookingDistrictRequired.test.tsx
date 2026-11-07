import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import type { BookingStatus, PortalSettings, ReturnRequestDetail, ReturnRequestRow } from '../api'

// 2026-11-07 — a "Same address" booking sent without a district that Bosta refused for the area
// (bookingFailureReason 'district_required'): "Bosta needs an area for this pickup", the request city's areas, and
// "Book again" = PUT …/pickup-area then POST …/booking/retry (never automatic). Any other failure keeps Retry.
// Harness as returnRequestBooking.test.tsx.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: true, portalPickupBooking: true, returnLocationId: 'loc', returnLocationName: 'Maadi',
  bostaConnected: true,
}

let detail: ReturnRequestDetail
let confirmStatus: number
let calls: Array<{ method: string; url: string; body?: unknown }>

function row(): ReturnRequestRow {
  return { id: 'rr-1', reference: 'RR-7K3F9M', orderNumber: '#1047', customerName: 'Mariam Saleh', itemCount: 1,
    reasonCodes: ['wrong_size'], status: detail.status, bookingStatus: detail.bookingStatus, createdAt: new Date().toISOString() }
}

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/pickup-areas')) return fakeResponse({ cityId: 'c', cityName: 'Cairo', cityNameAr: 'القاهرة', selectedDistrictId: null,
    editable: true, districts: [{ id: 'd10', name: '1st Settlement - District 10', nameAr: 'التجمع الاول - الحي 10', zoneName: 'New Cairo', zoneNameAr: null }] })
  if (url.endsWith('/pickup-area') && method === 'PUT') return fakeResponse(null, 204)
  if (url.endsWith('/booking/retry')) { detail = { ...detail, bookingStatus: 'pending', bookingError: null }; return fakeResponse(null, 202) }
  if (url.endsWith('/booking/not-booked')) { detail = { ...detail, bookingStatus: 'pending', bookingError: null }; return fakeResponse(null, 202) }
  if (url.endsWith('/booking/confirm')) {
    if (confirmStatus !== 204) return fakeResponse(null, confirmStatus)
    detail = { ...detail, status: 'pickup_booked', bookingStatus: 'booked', bostaTrackingNumber: body.trackingNumber, bookingError: null }
    return fakeResponse(null, 204)
  }
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) return fakeResponse({ items: [row()], total: 1 })
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

function withBooking(status: ReturnRequestDetail['status'], bookingStatus: BookingStatus | null,
                     extra: Partial<ReturnRequestDetail> = {}) {
  detail = {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'refund', status, email: null, note: 'It runs small.',
    createdAt: new Date().toISOString(), deliveredAt: '2026-09-18T10:00:00.000+00:00',
    pickupCity: 'Cairo', pickupZone: 'Nasr City', pickupCityId: 'c', pickupCityName: 'Cairo', pickupDistrictId: 'd1',
    pickupDistrictName: 'Nasr City - 7th District', pickupDistrictNameAr: 'مدينة نصر - الحي السابع',
    bookingStatus, bookingError: null, bostaTrackingNumber: null, bookingAttemptedAt: null, bookingVerifiedAt: null,
    decidedAt: status === 'requested' ? null : '2026-09-24T10:00:00.000+00:00', decidedBy: null, decidedByName: null,
    rejectionReason: null, returnShipmentId: null,
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true }],
    ...extra,
  }
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  withBooking('approved', null)
  confirmStatus = 204
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => { await i18n.changeLanguage('en') })

async function openDrawer() {
  const user = userEvent.setup()
  // Step 2: the Requests tab is gone — the drawer opens through the alerts' deep link.
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findByText('It runs small.')
  return { user, drawer }
}

describe('Drawer → Bosta needs an area', () => {
  test('district_required: message + area + Book again (area saved, then retry)', async () => {
    withBooking('approved', 'failed', { pickupDistrictId: null, pickupDistrictName: null, pickupDistrictNameAr: null,
      bookingFailureReason: 'district_required', bookingError: 'Bosta needs an area for this pickup. Choose the area and book again.' })
    const { user, drawer } = await openDrawer()
    const box = await within(drawer).findByTestId('booking-district-required')
    expect(box).toHaveTextContent("Bosta needs an area for this pickup. Choose the customer's area and book again.")
    expect(within(box).queryByRole('button', { name: 'Retry' })).toBeNull()
    const area = await within(box).findByLabelText(/Pickup area/)
    const book = within(box).getByRole('button', { name: 'Book again' })
    expect(book).toBeDisabled()
    await user.selectOptions(area, 'd10')
    await user.click(book)
    await waitFor(() => expect(calls.filter(c => c.method !== 'GET').map(c => c.url.replace(/^.*\/return-requests\/rr-1/, '')))
      .toEqual(['/pickup-area', '/booking/retry']))
    expect(calls.find(c => c.url.endsWith('/pickup-area'))!.body).toEqual({ districtId: 'd10' })
  })

  test('any other failure: the usual Retry, no area picker', async () => {
    withBooking('approved', 'failed', { bookingError: 'Invalid receiver phone.' })
    const { drawer } = await openDrawer()
    expect(await within(drawer).findByTestId('booking-failed')).toHaveTextContent('Invalid receiver phone.')
    expect(within(drawer).queryByTestId('booking-district-required')).toBeNull()
  })

  test('Arabic copy', async () => {
    await i18n.changeLanguage('ar')
    withBooking('approved', 'failed', { pickupDistrictId: null, bookingFailureReason: 'district_required' })
    const { drawer } = await openDrawer()
    expect(await within(drawer).findByTestId('booking-district-required')).toHaveTextContent('تحتاج بوسطة إلى المنطقة لهذا الاستلام')
  })
})
