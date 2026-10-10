import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import type { BookingStatus, PortalSettings, ReturnRequestDetail, ReturnRequestRow } from '../api'

// P4b — a request on a portal pre-connect order (fetched for a purchase made before the store
// connected) shows an "Ordered before Traced" badge next to the order number, EN + AR; the order
// number stays plain text (no order-page link — the order isn't in the Orders list by design).
// Harness as customAddressDrawer.test.tsx.

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
  await within(drawer).findByText('Linen Shirt')
  return { user, drawer }
}

describe('Drawer → ordered before Traced', () => {
  test('portal pre-connect order: badge next to the order number, no link', async () => {
    withBooking('approved', null, { orderedBeforeTraced: true })
    const { drawer } = await openDrawer()
    const badge = within(drawer).getByTestId('ordered-before-traced')
    expect(badge).toHaveTextContent('Ordered before Traced')
    expect(badge.parentElement).toHaveTextContent('#1047')
    expect(within(drawer).queryByRole('link', { name: /#1047/ })).not.toBeInTheDocument()
  })

  test('Arabic', async () => {
    await i18n.changeLanguage('ar')
    withBooking('approved', null, { orderedBeforeTraced: true })
    renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
      { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
    const drawer = screen.getByTestId('return-request-drawer')
    await within(drawer).findByText('Linen Shirt')
    expect(within(drawer).getByTestId('ordered-before-traced')).toHaveTextContent('تم الطلب قبل Traced')
  })

  test('a normal order: no badge', async () => {
    withBooking('approved', null, { orderedBeforeTraced: false })
    const { drawer } = await openDrawer()
    expect(within(drawer).queryByTestId('ordered-before-traced')).not.toBeInTheDocument()
  })
})
