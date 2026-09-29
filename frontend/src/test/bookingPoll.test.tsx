import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { act } from '@testing-library/react'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import type { BookingStatus, PortalSettings, ReturnRequestDetail, ReturnRequestRow } from '../api'

// The drawer's booking refresh: after Retry / Book now / "It wasn't booked — retry" /
// confirm-by-tracking it shows "Booking…" at once, then refreshes the request (now, then every
// 2 s, at most 23 times, ~45 s) until the booking settles — not 'pending' and either a new attempt
// was recorded (bookingAttemptedAt moved) or it is booked / needs review. Giving up before the job
// started (bookingAttemptedAt unchanged) keeps "Booking…". The Requests list is refreshed at the
// start, when the booking status changes, at the end, and once when the drawer closes mid-refresh;
// the refresh stops when the drawer closes. Fake timers.

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

/** Detail answers for GET /return-requests/rr-1 after the action, in order; the last one repeats. */
let script: ReturnRequestDetail[]
let actionDone = false

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (method === 'POST' && /\/booking\/(retry|not-booked|book-now)$/.test(url)) { actionDone = true; return fakeResponse(null, 202) }
  if (method === 'POST' && url.endsWith('/booking/confirm')) { actionDone = true; return fakeResponse(null, 204) }
  if (url.includes('/return-requests/') && method === 'GET') {
    if (!actionDone) return fakeResponse(detail)
    return fakeResponse(script.length > 1 ? script.shift() : script[0])
  }
  if (url.includes('/return-requests?')) return fakeResponse({ items: [row()], total: 1 })
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

function withBooking(status: ReturnRequestDetail['status'], bookingStatus: BookingStatus | null,
                     extra: Partial<ReturnRequestDetail> = {}): ReturnRequestDetail {
  return {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'refund', status, email: null, note: 'It runs small.',
    createdAt: new Date().toISOString(), deliveredAt: '2026-09-18T10:00:00.000+00:00',
    pickupCity: 'Cairo', pickupZone: 'Nasr City', pickupCityId: 'c', pickupCityName: 'Cairo', pickupDistrictId: 'd1',
    pickupDistrictName: 'Nasr City - 7th District', pickupDistrictNameAr: 'مدينة نصر - الحي السابع',
    bookingStatus, bookingError: null, bostaTrackingNumber: null, bookingAttemptedAt: '2026-09-28T09:00:00Z', bookingVerifiedAt: null,
    decidedAt: '2026-09-24T10:00:00.000+00:00', decidedBy: null, decidedByName: null,
    rejectionReason: null, returnShipmentId: null,
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true }],
    ...extra,
  }
}

const detailGets = () => calls.filter(c => c.method === 'GET' && c.url.endsWith('/return-requests/rr-1')).length
// Step 2 (approved): the list is now the Returns & exchanges list (GET /returns-exchanges?…); its
// counts (/returns-exchanges/counts) are fetched alongside and are not counted here.
const listGets = () => calls.filter(c => c.method === 'GET' && c.url.includes('/returns-exchanges?')).length
/** The list's own fetch — one per refresh. */
const tableGets = () => calls.filter(c => c.method === 'GET' && c.url.includes('/returns-exchanges?')).length

beforeEach(async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  await i18n.changeLanguage('en')
  detail = withBooking('approved', 'failed', { bookingError: 'Bosta answered with a server error (HTTP 500).' })
  script = [detail]
  actionDone = false
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => {
  vi.useRealTimers()
  await i18n.changeLanguage('en')
})

async function openDrawer() {
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  // Step 2: the Requests tab is gone — the drawer opens through the alerts' deep link.
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findByText('RR-7K3F9M')
  return { user, drawer }
}

async function tick(ms = 2000) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

describe('Drawer → booking refresh after an action', () => {
  test('Retry: "Booking…" at once, stale "failed" is ignored, refreshes every 2 s until booked, then stops', async () => {
    const stale = detail
    const booked = withBooking('pickup_booked', 'booked', { bostaTrackingNumber: '5100000009', bookingAttemptedAt: '2026-09-28T09:05:00Z' })
    script = [stale, stale, withBooking('approved', 'pending', { bookingAttemptedAt: '2026-09-28T09:05:00Z' }), booked]
    const { user, drawer } = await openDrawer()
    const listBefore = listGets()
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }))
    expect(await within(drawer).findByTestId('booking-pending')).toHaveTextContent('Booking…')
    await waitFor(() => expect(listGets()).toBeGreaterThan(listBefore))    // the list row refreshed at the start

    const after = detailGets()
    await tick()   // stale failed → still Booking…
    expect(within(drawer).getByTestId('booking-pending')).toBeInTheDocument()
    await tick()   // pending
    expect(within(drawer).getByTestId('booking-pending')).toBeInTheDocument()
    await tick()   // booked
    expect(await within(drawer).findByTestId('booking-booked')).toHaveTextContent('Booked · 5100000009')
    const settled = detailGets()
    expect(settled - after).toBe(3)
    const listAtEnd = listGets()
    await tick(10000)
    expect(detailGets()).toBe(settled)                                     // stops once settled
    expect(listAtEnd).toBeGreaterThanOrEqual(listBefore + 2)              // … and refreshed the list again at the end
  })

  test('stays pending → gives up after 23 refreshes (~45 s)', async () => {
    script = [withBooking('approved', 'pending')]
    const { user, drawer } = await openDrawer()
    const before = detailGets()
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }))
    await within(drawer).findByTestId('booking-pending')
    await tick(60000)
    expect(detailGets() - before).toBe(23)
    await tick(10000)
    expect(detailGets() - before).toBe(23)
  })

  test('gives up before the job started → still Booking…, never the pre-retry failure', async () => {
    script = [detail]   // the stale 'failed' with the same bookingAttemptedAt: the job never ran
    const { user, drawer } = await openDrawer()
    const before = detailGets()
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }))
    await within(drawer).findByTestId('booking-pending')
    await tick(60000)
    expect(detailGets() - before).toBe(23)
    expect(within(drawer).getByTestId('booking-pending')).toHaveTextContent('Booking…')
    expect(within(drawer).queryByTestId('booking-failed')).toBeNull()
    expect(within(drawer).queryByText(/HTTP 500/)).toBeNull()
  })

  test('closing the drawer stops the refresh', async () => {
    script = [detail]   // stays the stale 'failed' — never settles by itself
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }))
    await within(drawer).findByTestId('booking-pending')
    await tick()
    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(within(drawer).queryByText('It runs small.')).toBeNull())
    const atClose = detailGets()
    await tick(20000)
    expect(detailGets()).toBe(atClose)
  })

  test('closing the drawer mid-refresh refreshes the Requests list once', async () => {
    script = [detail]
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }))
    await within(drawer).findByTestId('booking-pending')
    await tick()
    const tableBeforeClose = tableGets()
    await user.click(within(drawer).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(tableGets()).toBe(tableBeforeClose + 1))
    await tick(20000)
    expect(tableGets()).toBe(tableBeforeClose + 1)
  })

  test('the Requests list refreshes when the booking status changes, not on every refresh', async () => {
    const pending = withBooking('approved', 'pending', { bookingAttemptedAt: '2026-09-28T09:05:00Z' })
    script = [detail, detail, pending, pending, pending,
      withBooking('pickup_booked', 'booked', { bostaTrackingNumber: '5100000011', bookingAttemptedAt: '2026-09-28T09:05:00Z' })]
    const { user, drawer } = await openDrawer()
    const tableBefore = tableGets()
    await user.click(within(drawer).getByRole('button', { name: 'Retry' }))
    await within(drawer).findByTestId('booking-pending')
    await waitFor(() => expect(tableGets()).toBe(tableBefore + 1))           // start
    await tick()                                                          // still the stale 'failed'
    expect(tableGets()).toBe(tableBefore + 1)
    await tick()                                                          // failed → pending: the job started
    await waitFor(() => expect(tableGets()).toBe(tableBefore + 2))
    await tick()
    await tick()                                                          // still pending
    expect(tableGets()).toBe(tableBefore + 2)
    await tick()                                                          // booked: the end refresh
    expect(await within(drawer).findByTestId('booking-booked')).toHaveTextContent('Booked · 5100000011')
    await waitFor(() => expect(tableGets()).toBe(tableBefore + 3))
    await tick(10000)
    expect(tableGets()).toBe(tableBefore + 3)
  })

  test('"It wasn\'t booked — retry": Booking… until the new attempt fails, then its reason', async () => {
    detail = withBooking('approved', 'failed_ambiguous', { bookingError: 'No answer from Bosta. Check in Bosta before retrying.' })
    script = [withBooking('approved', 'failed', { bookingError: 'Checked in Bosta: it was not booked.' }),
      withBooking('approved', 'failed', { bookingError: 'Invalid district for the given city (Bosta error 3004)',
        bookingAttemptedAt: '2026-09-28T09:06:00Z' })]
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: "It wasn't booked — retry" }))
    expect(await within(drawer).findByTestId('booking-pending')).toBeInTheDocument()
    await tick()
    expect(await within(drawer).findByTestId('booking-failed')).toHaveTextContent('Invalid district for the given city (Bosta error 3004)')
  })

  test('confirm-by-tracking: Booking… then booked on the first refresh', async () => {
    detail = withBooking('approved', 'failed_ambiguous')
    script = [withBooking('pickup_booked', 'booked', { bostaTrackingNumber: '5100000010' })]
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'It was booked — enter tracking number' }))
    await user.type(within(drawer).getByLabelText('Bosta tracking number'), '5100000010')
    await user.click(within(drawer).getByRole('button', { name: 'Confirm' }))
    expect(await within(drawer).findByTestId('booking-booked')).toHaveTextContent('Booked · 5100000010')
  })

  test('exchange Book now: Booking… then the booked trip', async () => {
    detail = withBooking('approved', null, { type: 'exchange', bookingAttemptedAt: null, bookNowAvailable: true,
      items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
        variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true, replacementVariantTitle: 'White / L' }],
      exchange: null })
    script = [{ ...detail, bookNowAvailable: false }, withBooking('pickup_booked', 'booked', { type: 'exchange',
      bostaTrackingNumber: '7400000001', bookingAttemptedAt: '2026-09-28T09:07:00Z', items: detail.items, exchange: null })]
    const { user, drawer } = await openDrawer()
    await user.click(await within(drawer).findByRole('button', { name: 'Book now' }))
    expect(await within(drawer).findByTestId('booking-pending')).toHaveTextContent('Booking…')
    await tick()
    await waitFor(() => expect(within(drawer).getByTestId('exchange-trip-line')).toHaveTextContent('AWB 7400000001'))
    expect(within(drawer).queryByTestId('booking-pending')).toBeNull()
  })

  test('Arabic: Booking…', async () => {
    await i18n.changeLanguage('ar')
    script = [withBooking('approved', 'pending')]
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'إعادة المحاولة' }))
    expect(await within(drawer).findByTestId('booking-pending')).toHaveTextContent('جارٍ الحجز…')
  })
})
