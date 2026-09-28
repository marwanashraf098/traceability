import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import SettingsPage from '../pages/settings/SettingsPage'
import type { PortalSettings, ReturnRequestDetail, ReturnRequestRow } from '../api'
import i18n from '../i18n'

// Step 5c — Traced books the Bosta exchange: Settings "Allow exchanges", the X4 approve copy,
// the X5 progress (derived from detail.exchange + the request), Book now, "Exchange failed",
// and the list pills. Fakes match PortalSettingsService (exchangesEnabled / exchangesSince, 409
// {field:'exchangesEnabled'}) and ReturnRequestService.detail (exchange: {trackingNumber,
// exchangeStatus, orderId, orderNumber, orderStatus, shipmentState, deliveredAt, withCourierAt},
// bookNowAvailable); POST /booking/book-now → 202; POST /close {reason:'exchange_failed'}.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

let settings: PortalSettings
let detail: ReturnRequestDetail
let rows: ReturnRequestRow[]
let calls: Array<{ method: string; url: string; body?: unknown }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: status === 409 ? 'Conflict' : 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.includes('/tenant/portal-settings') && method === 'PUT') {
    const booking = body.pickupBooking ?? settings.portalPickupBooking
    const exchanges = booking ? (body.exchangesEnabled ?? settings.exchangesEnabled) : false
    settings = { ...settings, ...body, pickupBooking: booking, portalPickupBooking: booking, exchangesEnabled: exchanges }
    return fakeResponse(settings)
  }
  if (url.includes('/tenant/portal-settings')) return fakeResponse(settings)
  if (url.includes('/tenant/bosta/return-locations')) {
    return fakeResponse([{ id: 'loc-1', name: 'Maadi Warehouse', isDefault: true, cityName: 'Cairo' }])
  }
  if (url.includes('/variants?')) return fakeResponse({ items: [], total: 0 })
  if (url.endsWith('/booking/book-now') && method === 'POST') {
    detail = { ...detail, bookNowAvailable: false, bookingStatus: 'pending' }
    return fakeResponse(null, 202)
  }
  if (url.endsWith('/close') && method === 'POST') {
    detail = { ...detail, status: 'closed', closeReason: body.reason }
    return fakeResponse(null, 204)
  }
  if (url.endsWith('/approve') && method === 'POST') return fakeResponse(null, 204)
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) return fakeResponse({ items: rows, total: rows.length })
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

const ROW: ReturnRequestRow = {
  id: 'rr-1', reference: 'RR-8J4M2Q', orderNumber: '#1047', customerName: 'Mariam Saleh', itemCount: 1,
  reasonCodes: ['wrong_size'], status: 'pickup_booked', createdAt: new Date().toISOString(), type: 'exchange',
}

function exchangeDetail(over: Partial<ReturnRequestDetail> = {}): ReturnRequestDetail {
  return {
    id: 'rr-1', reference: 'RR-8J4M2Q', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'exchange', status: 'pickup_booked', email: null, note: null,
    createdAt: '2026-09-26T12:00:00Z', deliveredAt: '2026-09-18T10:00:00Z', pickupCity: 'Cairo', pickupZone: 'Nasr City',
    pickupCityId: null, pickupCityName: null, pickupDistrictId: null, pickupDistrictName: null, pickupDistrictNameAr: null,
    bookingStatus: 'booked', bostaTrackingNumber: '5519026734', bookingError: null,
    decidedAt: '2026-09-26T13:01:00Z', decidedBy: 'u1', decidedByName: 'Mohamed A.', rejectionReason: null, returnShipmentId: null,
    refundFallbackOk: true,
    history: [{ type: 'pickup_booked', actorId: null, actorName: null, occurredAt: '2026-09-26T13:01:30Z', metadata: null }],
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White · M', imageUrl: null, reasonCode: 'wrong_size', active: true, itemStatus: 'awaiting',
      replacementVariantId: 'v-2', replacementVariantTitle: 'White · L' }],
    exchange: { trackingNumber: '5519026734', exchangeStatus: 'matched', orderId: 'o-exc', orderNumber: 'EXC-5519026734',
      orderStatus: 'new', shipmentState: 'created', deliveredAt: null, withCourierAt: null },
    bookNowAvailable: false,
    ...over,
  }
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  settings = {
    slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
    policyText: null, pickupBooking: true, portalPickupBooking: true, returnLocationId: 'loc-1',
    returnLocationName: 'Maadi Warehouse', bostaConnected: true, exchangesEnabled: false, exchangesSince: null,
  }
  detail = exchangeDetail()
  rows = [ROW]
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
  return { user, drawer }
}

function steps(drawer: HTMLElement) {
  return Object.fromEntries(within(drawer).getAllByRole('listitem')
    .filter(li => li.dataset.testid?.startsWith('exchange-step-'))
    .map(li => [li.dataset.testid!.replace('exchange-step-', ''), li.dataset.state]))
}

describe('Settings → Allow exchanges', () => {
  async function loadedSettings() {
    const user = userEvent.setup()
    renderWithProviders(
      <Routes><Route path="/settings" element={<SettingsPage />} /></Routes>,
      { initialEntries: ['/settings?tab=portal'] },
    )
    await screen.findByDisplayValue('nourstudio')
    return user
  }
  const SWITCH = { name: 'Allow exchanges' }

  test('pickup booking off → disabled with the reason', async () => {
    settings = { ...settings, pickupBooking: false, portalPickupBooking: false }
    await loadedSettings()
    expect(screen.getByRole('switch', SWITCH)).toBeDisabled()
    expect(screen.getByTestId('exchanges-note')).toHaveTextContent('Turn on "Book Bosta pickups when I approve" and save first.')
  })

  test('Bosta not connected → disabled, says to connect Bosta', async () => {
    settings = { ...settings, bostaConnected: false, pickupBooking: false, portalPickupBooking: false }
    await loadedSettings()
    expect(screen.getByRole('switch', SWITCH)).toBeDisabled()
    expect(screen.getByTestId('exchanges-note')).toHaveTextContent('Connect Bosta first.')
  })

  test('booking on, Bosta, location → enabled; turning it on sends exchangesEnabled; booking off turns it off too', async () => {
    const user = await loadedSettings()
    const sw = screen.getByRole('switch', SWITCH)
    expect(sw).toBeEnabled()
    await user.click(sw)
    await user.click(screen.getByRole('button', { name: /Save/ }))
    await waitFor(() => expect(calls.some(c => c.method === 'PUT' && (c.body as { exchangesEnabled?: boolean }).exchangesEnabled === true)).toBe(true))
    await waitFor(() => expect(screen.getByRole('switch', SWITCH)).toHaveAttribute('aria-checked', 'true'))

    await user.click(screen.getByRole('switch', { name: 'Book Bosta pickups when I approve' }))
    expect(screen.getByRole('switch', SWITCH)).toHaveAttribute('aria-checked', 'false')
  })
})

describe('Drawer — X4 approve copy', () => {
  test('a requested exchange says approving books one Bosta exchange trip', async () => {
    detail = exchangeDetail({ status: 'requested', bookingStatus: null, bostaTrackingNumber: null, exchange: null, decidedAt: null,
      items: [{ ...exchangeDetail().items[0], replacementAvailable: 3, replacementInStock: true }] })
    rows = [{ ...ROW, status: 'requested' }]
    const { drawer } = await openDrawer()
    expect(await within(drawer).findByTestId('exchange-approve-helper'))
      .toHaveTextContent('Approving books one Bosta exchange trip and adds the new size to Pick & Pack.')
  })
})

describe('Drawer — X5 progress', () => {
  test('booked: summary with AWB and replacement order; approved + booked done, packing current', async () => {
    const { drawer } = await openDrawer()
    await within(drawer).findByTestId('exchange-progress-body')
    expect(within(drawer).getByTestId('exchange-summary-card')).toHaveTextContent('Linen Shirt · White · M → White · L')
    expect(within(drawer).getByTestId('exchange-trip-line')).toHaveTextContent('Exchange trip AWB 5519026734 · replacement order EXC-5519026734')
    expect(steps(drawer)).toEqual({ approved: 'done', booked: 'done', packing: 'current', withCourier: 'todo',
      swapped: 'todo', scanned: 'todo', exchanged: 'todo' })
    expect(within(drawer).getByTestId('exchange-step-packing')).toHaveTextContent('In Pick & Pack now')
    expect(within(drawer).getByTestId('exchange-step-approved')).toHaveTextContent('Mohamed A.')
    expect(within(drawer).getByText('Exchange booked')).toBeInTheDocument()
  })

  test('with the courier: packing and hand-over done, the swap is next', async () => {
    detail = exchangeDetail({ exchange: { ...exchangeDetail().exchange!, orderStatus: 'awaiting_pickup', shipmentState: 'with_courier',
      withCourierAt: '2026-09-27T09:00:00Z' } })
    const first = await openDrawer()
    await within(first.drawer).findByTestId('exchange-progress-body')
    expect(steps(first.drawer)).toMatchObject({ packing: 'done', withCourier: 'done', swapped: 'current' })
  })

  test('exchanged: every step done, no close button', async () => {
    detail = exchangeDetail({ status: 'exchanged',
      exchange: { ...exchangeDetail().exchange!, orderStatus: 'delivered', shipmentState: 'delivered',
        withCourierAt: '2026-09-27T09:00:00Z', deliveredAt: '2026-09-27T12:00:00Z', exchangeStatus: 'return_received' },
      items: [{ ...exchangeDetail().items[0], itemStatus: 'done', arrivedAt: '2026-09-28T10:00:00Z' }] })
    rows = [{ ...ROW, status: 'exchanged' }]
    const { drawer } = await openDrawer()
    await within(drawer).findByTestId('exchange-progress-body')
    expect(Object.values(steps(drawer)).every(s => s === 'done')).toBe(true)
    expect(within(drawer).queryByTestId('close-footer')).toBeNull()
  })

  test('a failed booking shows the reused booking row with Retry', async () => {
    detail = exchangeDetail({ status: 'approved', bookingStatus: 'failed', bostaTrackingNumber: null, exchange: null,
      bookingError: 'The replacement is out of stock.' })
    const { drawer } = await openDrawer()
    const row = await within(drawer).findByTestId('exchange-booking')
    expect(row).toHaveTextContent('Bosta exchange trip')
    expect(within(row).getByTestId('booking-failed')).toHaveTextContent('The replacement is out of stock.')
    expect(within(row).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(steps(drawer)).toMatchObject({ approved: 'done', booked: 'current' })
  })

  test('Book now for an older approval posts book-now', async () => {
    detail = exchangeDetail({ status: 'approved', bookingStatus: null, bostaTrackingNumber: null, exchange: null, bookNowAvailable: true })
    const { user, drawer } = await openDrawer()
    await user.click(await within(drawer).findByRole('button', { name: 'Book now' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/rr-1/booking/book-now'))).toBe(true))
    await waitFor(() => expect(within(drawer).queryByTestId('book-now')).toBeNull())
  })

  test('closing an exchange offers "Exchange failed" and posts it', async () => {
    const { user, drawer } = await openDrawer()
    await user.click(await within(drawer).findByRole('button', { name: 'Close request…' }))
    const dialog = await screen.findByTestId('close-dialog')
    expect(within(dialog).queryByText('No refund')).toBeNull()
    expect(within(dialog).getByRole('radio', { name: /Exchange failed/ })).toBeChecked()
    await user.click(within(dialog).getByRole('button', { name: 'Close request' }))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/rr-1/close'))?.body).toMatchObject({ reason: 'exchange_failed' }))
    expect(await within(drawer).findByTestId('request-close-reason')).toHaveTextContent('Exchange failed')
  })

  test('Arabic: progress labels', async () => {
    await i18n.changeLanguage('ar')
    const { drawer } = await openDrawer()
    await within(drawer).findByTestId('exchange-progress-body')
    expect(within(drawer).getByTestId('exchange-step-booked')).toHaveTextContent('تم حجز رحلة الاستبدال')
    expect(within(drawer).getByTestId('exchange-step-packing')).toHaveTextContent('في التجهيز والتغليف الآن')
    expect(within(drawer).getByText('التقدم')).toBeInTheDocument()
  })
})

