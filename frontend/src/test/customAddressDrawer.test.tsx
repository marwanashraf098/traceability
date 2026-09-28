import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import type { BookingStatus, PortalSettings, ReturnRequestDetail, ReturnRequestRow } from '../api'

// V117 — the drawer shows the pickup address a customer typed in the portal ("A different
// address") under Pickup, labelled "Customer entered a new address"; nothing extra for the
// delivery address; a note once removed after a privacy request. Harness as
// returnRequestBooking.test.tsx.

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
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>)
  await user.click(await screen.findByRole('button', { name: /Requests/ }))
  await user.click(await screen.findByText('RR-7K3F9M'))
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findByText('Linen Shirt')
  return { user, drawer }
}

const CUSTOM = { firstLine: '12 Fouad Street', secondLine: 'Behind the mosque', buildingNumber: '4B', floor: '3',
  apartment: '9', redacted: false }

describe('Drawer → custom pickup address', () => {
  test('custom: label + street, building · floor · apartment, landmark under the pickup area', async () => {
    withBooking('requested', null, { pickupCityName: 'Alexandria', pickupDistrictName: 'Smouha', pickupDistrictNameAr: 'سموحة',
      pickupAddressSource: 'custom', customAddress: CUSTOM })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('request-pickup')).toHaveTextContent('Alexandria · Smouha')
    const block = within(drawer).getByTestId('custom-address')
    expect(block).toHaveTextContent('Customer entered a new address')
    expect(block).toHaveTextContent('12 Fouad Street')
    expect(block).toHaveTextContent('Building 4B · Floor 3 · Apt 9')
    expect(block).toHaveTextContent('Behind the mosque')
    expect(within(drawer).getByRole('button', { name: 'Change area' })).toBeInTheDocument()
  })

  test('delivery address: no custom block', async () => {
    withBooking('requested', null, { pickupAddressSource: 'order' })
    const { drawer } = await openDrawer()
    expect(within(drawer).queryByTestId('custom-address')).not.toBeInTheDocument()
  })

  test('redacted: the label stays, the address is gone; one line says the details were removed', async () => {
    withBooking('requested', null, { piiRedacted: true, email: null, note: null, pickupAddressSource: 'custom', customAddress: {
      firstLine: null, secondLine: null, buildingNumber: null, floor: null, apartment: null, redacted: true } })
    const { drawer } = await openDrawer()
    const block = within(drawer).getByTestId('custom-address')
    expect(block).toHaveTextContent('The address was removed after a privacy request.')
    expect(block).not.toHaveTextContent('Fouad')

    expect(within(drawer).getByTestId('pii-removed'))
      .toHaveTextContent("The customer's email, note and any typed pickup address were removed after a privacy request.")
  })

  test('no privacy note when not redacted', async () => {
    withBooking('requested', null, { piiRedacted: false })
    const { drawer } = await openDrawer()
    expect(within(drawer).queryByTestId('pii-removed')).not.toBeInTheDocument()
  })

  test('privacy note on a redacted delivery-address request, in Arabic too', async () => {
    withBooking('requested', null, { piiRedacted: true, email: null, note: null, pickupAddressSource: 'order' })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('pii-removed')).toBeInTheDocument()
    await i18n.changeLanguage('ar')
    await waitFor(() => expect(within(drawer).getByTestId('pii-removed'))
      .toHaveTextContent('حُذف البريد الإلكتروني للعميل وملاحظته وأي عنوان استلام كتبه بعد طلب خصوصية.'))
  })

  test('exchange requests show it too', async () => {
    withBooking('requested', null, { type: 'exchange', pickupCityName: 'Alexandria', pickupDistrictName: 'Smouha',
      pickupAddressSource: 'custom', customAddress: CUSTOM,
      items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
        variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true,
        replacementVariantId: 'v-2', replacementVariantTitle: 'White / L' }] })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('custom-address')).toHaveTextContent('12 Fouad Street')
  })

  test('Arabic', async () => {
    withBooking('requested', null, { pickupAddressSource: 'custom', customAddress: CUSTOM })
    const { drawer } = await openDrawer()
    await i18n.changeLanguage('ar')
    await waitFor(() => expect(within(drawer).getByTestId('custom-address')).toHaveTextContent('أدخل العميل عنوانًا جديدًا'))
    expect(within(drawer).getByTestId('custom-address')).toHaveTextContent('عقار 4B · الدور 3 · شقة 9')
  })
})
