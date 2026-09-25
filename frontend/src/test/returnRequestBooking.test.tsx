import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import type { BookingStatus, PortalSettings, ReturnRequestDetail, ReturnRequestRow } from '../api'

// Returns portal Step 4c-3 — the drawer's "Bosta pickup" row, its actions, the list's
// Attention badge and the booking footer copy. Fakes match ReturnRequestService /
// ReturnsPortalAdminController: detail carries bookingStatus/bookingError/bostaTrackingNumber;
// POST …/booking/retry and …/booking/not-booked → 202; POST …/booking/confirm → 204, 400 or 409.

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
  await within(drawer).findByText('It runs small.')
  return { user, drawer }
}

describe('Drawer → Bosta pickup', () => {
  test('requested: the approve footer says approving books a pickup from the customer area', async () => {
    withBooking('requested', null)
    const { drawer } = await openDrawer()
    expect(await within(drawer).findByTestId('approve-helper'))
      .toHaveTextContent("Approving books a Bosta pickup from the customer's area.")
    expect(within(drawer).queryByTestId('booking-row')).not.toBeInTheDocument()
  })

  test('approved, not yet attempted → Booking…; Change area still offered', async () => {
    const { drawer } = await openDrawer()
    expect(await within(drawer).findByTestId('booking-pending')).toHaveTextContent('Booking…')
    expect(within(drawer).getByRole('button', { name: 'Change area' })).toBeInTheDocument()
  })

  test('booked → Booked · tracking; Change area gone', async () => {
    withBooking('pickup_booked', 'booked', { bostaTrackingNumber: '5100000001' })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('booking-booked')).toHaveTextContent('Booked · 5100000001')
    expect(within(drawer).queryByRole('button', { name: 'Change area' })).not.toBeInTheDocument()
  })

  test('failed → reason + Retry, which calls the retry endpoint', async () => {
    withBooking('approved', 'failed', { bookingError: 'Invalid district for the given city' })
    const { user, drawer } = await openDrawer()
    expect(within(drawer).getByTestId('booking-failed')).toHaveTextContent('Failed: Invalid district for the given city')
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/booking/retry'))).toBe(true))
    expect(await within(drawer).findByTestId('booking-pending')).toBeInTheDocument()
  })

  test("check in Bosta → \"It wasn't booked — retry\" calls not-booked", async () => {
    withBooking('approved', 'failed_ambiguous', { bookingError: 'No answer from Bosta. Check in Bosta before retrying.' })
    const { user, drawer } = await openDrawer()
    expect(within(drawer).getByTestId('booking-ambiguous')).toHaveTextContent("Check in Bosta: we couldn't confirm")
    await user.click(within(drawer).getByRole('button', { name: "It wasn't booked — retry" }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/booking/not-booked'))).toBe(true))
  })

  test('check in Bosta → enter tracking number: 400 shows why; a valid number → booked', async () => {
    withBooking('approved', 'failed_ambiguous')
    confirmStatus = 400
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'It was booked — enter tracking number' }))
    const input = within(drawer).getByLabelText('Bosta tracking number')
    await user.type(input, ' 5100 000009 ')
    await user.click(within(drawer).getByRole('button', { name: 'Confirm' }))
    expect(await within(drawer).findByRole('alert')).toHaveTextContent("Bosta doesn't show that number as a return pickup")
    expect(calls.find(c => c.url.endsWith('/booking/confirm'))?.body).toEqual({ trackingNumber: '5100000009' })

    confirmStatus = 204
    await user.click(within(drawer).getByRole('button', { name: 'Confirm' }))
    expect(await within(drawer).findByTestId('booking-booked')).toHaveTextContent('Booked · 5100000009')
  })

  test('needs review → the fields that differ', async () => {
    withBooking('pickup_booked', 'needs_review', {
      bostaTrackingNumber: '5100000002', bookingError: "Bosta's delivery differs from the request: itemsCount, district.",
    })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('booking-review'))
      .toHaveTextContent("Check in Bosta: Bosta's delivery differs from the request: itemsCount, district.")
  })

  test('Arabic: booked row reads in Arabic', async () => {
    withBooking('pickup_booked', 'booked', { bostaTrackingNumber: '5100000001' })
    const { drawer } = await openDrawer()
    await i18n.changeLanguage('ar')
    await waitFor(() => expect(within(drawer).getByTestId('booking-booked')).toHaveTextContent('تم الحجز · 5100000001'))
  })
})

describe('Requests list', () => {
  test('Attention badge for failed / failed_ambiguous / needs_review only', async () => {
    for (const [status, shown] of [['failed', true], ['failed_ambiguous', true], ['needs_review', true], ['booked', false], [null, false]] as const) {
      withBooking('approved', status)
      const view = renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>)
      await userEvent.setup().click(await screen.findByRole('button', { name: /Requests/ }))
      await screen.findByText('RR-7K3F9M')
      if (shown) expect(screen.getByTestId('attention-badge')).toHaveTextContent('Attention')
      else expect(screen.queryByTestId('attention-badge')).not.toBeInTheDocument()
      view.unmount()
    }
  })
})
