import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { render, screen, within } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import PortalApp from '../portal/PortalApp'
import { createPortalI18n } from '../portal/i18n'
import i18n from '../i18n'
import type { PortalSettings, ReturnRequestDetail } from '../api'
import type { LookupLine, LookupResult, PortalConfig } from '../portal/api'

// Non-Bosta couriers G4b — exchanges for orders shipped with another courier, EN and AR:
//   portal — the Return / Exchange choice is offered; the exchange copy names no Bosta courier
//   drawer — "Book the exchange with <carrier>": the replacement's outbound waybill (required to ship) and an
//            optional return waybill; refusals shown; the progress view has no Bosta booking row

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

// ── Portal ────────────────────────────────────────────────────────────────────

const CONFIG: PortalConfig = {
  storeName: 'Nour Studio', returnWindowDays: 30, reasonCodes: ['wrong_size'], logoUrl: null, brandColor: '#1F5C4A',
  policyText: null, autoApprove: false, pickupBooking: true, exchangesEnabled: true, otherCouriers: true,
}

const SHIRT: LookupLine = {
  variantId: 'v-white-m', productTitle: 'Linen Shirt', variantTitle: 'White / M', imageUrl: null,
  deliveredQuantity: 1, returnableQuantity: 1, nonReturnable: false,
  optionAxes: [{ name: null, kind: 'colour' }, { name: null, kind: 'size' }],
  currentOptions: ['White', 'M'],
  exchangeOptions: [{ variantId: 'v-white-l', title: 'White / L', options: ['White', 'L'], inStock: true }],
}

const LOOKUP: LookupResult = {
  token: 'tok.sig', orderNumber: '#6001', deliveredAt: '2026-10-18T10:00:00Z', lines: [SHIRT], pickup: null,
  otherCourier: { carrierName: 'Aramex' },
}

let portalCalls: Array<{ url: string; init: RequestInit }>

function respond(status: number, body?: unknown) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status,
    headers: { get: (k: string) => (k.toLowerCase() === 'content-type' && body !== undefined ? 'application/json' : null) },
    json: async () => structuredClone(body), text: async () => JSON.stringify(body ?? '') })
}

describe('Portal — an exchange for an order shipped with another courier', () => {
  beforeEach(() => {
    portalCalls = []
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn((url: string, init: RequestInit = {}) => {
      portalCalls.push({ url, init })
      if (url.endsWith('/config')) return respond(200, CONFIG)
      if (url.endsWith('/lookup')) return respond(200, LOOKUP)
      if (url.endsWith('/requests')) return respond(201, { reference: 'RR-7K3F9M', status: 'requested' })
      return respond(404)
    }))
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    document.documentElement.dir = 'ltr'
    document.documentElement.lang = 'en'
  })

  test('EN: Exchange offered, courier-neutral P3 and X3, no Bosta, exchange payload sent', async () => {
    const user = userEvent.setup()
    render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
    await screen.findByRole('heading', { name: 'Start a return' })
    await user.type(screen.getByLabelText('Order number'), '#6001')
    await user.type(screen.getByLabelText('Phone number'), '010 1234 5678')
    await user.click(screen.getByRole('button', { name: 'Find my order' }))
    await screen.findByRole('heading', { name: 'What would you like to do?' })
    await user.click(screen.getByRole('radio', { name: /Exchange/ }))
    await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
    await user.selectOptions(screen.getByLabelText('Why are you exchanging it?'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Choose your new size or colour' })
    await user.click(within(screen.getByRole('group', { name: 'Size' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    await screen.findByRole('heading', { name: 'Almost done' })
    expect(screen.getByText(/Nour Studio sends your new size and arranges collecting the old item/)).toBeInTheDocument()
    expect(screen.queryByTestId('address-choice')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Send return request' }))
    await screen.findByRole('heading', { name: 'Exchange requested' })
    expect(screen.getByText('Nour Studio sends your new size and arranges collecting the old one.')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('Bosta')
    const sent = JSON.parse(portalCalls.find(c => c.url.endsWith('/requests'))!.init.body as string)
    expect(sent).toMatchObject({ mode: 'exchange', replacementVariantId: 'v-white-l' })
    expect(sent.districtId).toBeUndefined()
  })

  test('AR: Exchange offered, no بوسطة', async () => {
    const user = userEvent.setup()
    render(<I18nextProvider i18n={createPortalI18n('en')}><PortalApp slug="nourstudio" /></I18nextProvider>)
    await screen.findByRole('heading', { name: 'Start a return' })
    await user.type(screen.getByLabelText('Order number'), '#6001')
    await user.type(screen.getByLabelText('Phone number'), '010 1234 5678')
    await user.click(screen.getByRole('button', { name: 'Find my order' }))
    await user.click(await screen.findByRole('button', { name: 'Switch to Arabic' }))
    await waitFor(() => expect(document.documentElement.dir).toBe('rtl'))
    await user.click(await screen.findByRole('radio', { name: /استبدال/ }))
    await user.click(within(screen.getAllByTestId('exchange-line')[0]).getByRole('radio'))
    await user.selectOptions(screen.getByLabelText('لماذا تريد استبداله؟'), 'wrong_size')
    await user.click(screen.getByRole('button', { name: 'متابعة' }))
    await screen.findByRole('heading', { name: 'اختر المقاس أو اللون الجديد' })
    await user.click(within(screen.getByRole('group', { name: 'المقاس' })).getByRole('radio', { name: 'L' }))
    await user.click(screen.getByRole('button', { name: 'متابعة' }))
    expect(await screen.findByText(/يرسل Nour Studio مقاسك الجديد ويرتّب استلام المنتج القديم/)).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('بوسطة')
  })
})

// ── Drawer ────────────────────────────────────────────────────────────────────

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: true, portalPickupBooking: true, returnLocationId: 'loc', returnLocationName: 'Maadi',
  bostaConnected: true,
}

let detail: ReturnRequestDetail
let calls: Array<{ method: string; url: string; body?: unknown }>
let manualReply: () => Promise<unknown>

function fake(data: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data) })
}

function exchangeDetail(extra: Partial<ReturnRequestDetail> = {}, replacement: Record<string, unknown> = {}) {
  detail = {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#6001', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'exchange', status: 'approved', email: null, note: null,
    createdAt: new Date().toISOString(), deliveredAt: '2026-10-18T10:00:00.000+00:00',
    pickupCity: null, pickupZone: null, pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
    pickupDistrictName: null, pickupDistrictNameAr: null,
    bookingStatus: 'manual', bookingError: null, bostaTrackingNumber: null, bookingAttemptedAt: null, bookingVerifiedAt: null,
    decidedAt: '2026-10-21T10:00:00.000+00:00', decidedBy: null, decidedByName: null, rejectionReason: null, returnShipmentId: null,
    items: [{ id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
      variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true, itemStatus: 'awaiting',
      replacementVariantId: 'v-2', replacementVariantTitle: 'White / L' }],
    otherCourier: {
      forwardCarrierName: 'Aramex', carrierName: 'Aramex',
      pickup: { name: 'Mariam Saleh', phone: '01012345678', addressLines: ['5 Tahrir St', 'Cairo'] },
      replacement: { orderId: 'rep-1', orderNumber: 'EXC-RR-7K3F9M', outboundTrackingNumber: null, outboundState: null, ...replacement },
    },
    exchange: { trackingNumber: (replacement.outboundTrackingNumber as string) ?? null, exchangeStatus: null, orderId: 'rep-1',
      orderNumber: 'EXC-RR-7K3F9M', orderStatus: 'new', shipmentState: replacement.outboundTrackingNumber ? 'created' : null,
      deliveredAt: null, withCourierAt: null },
    ...extra,
  } as ReturnRequestDetail
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/manual-courier')) return manualReply()
  if (url.includes('/generic-shipments/carriers')) return fake([{ carrier: 'Aramex', withCourier: 2 }])
  if (url.includes('/return-requests/') && method === 'GET') return fake(detail)
  if (url.includes('/return-requests?')) return fake({ items: [], total: 0 })
  if (url.includes('/tenant/portal-settings')) return fake(SETTINGS)
  if (url.includes('/returns-exchanges')) return fake({ items: [], nextCursor: null, stages: {}, tiles: {} })
  return fake({})
}

async function openDrawer() {
  const user = userEvent.setup()
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findByTestId('exchange-manual')
  return { user, drawer }
}

describe('Drawer — Book the exchange with <carrier>', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    calls = []
    manualReply = () => fake({ outcome: 'LEGS_CREATED', carrierName: 'Aramex', trackingNumber: 'AR-RET-1',
      returnShipmentId: 's-2', outboundTrackingNumber: 'AR-OUT-1' })
    exchangeDetail()
    stubFetchWithShellDefaults(vi.fn(backend))
  })
  afterEach(async () => { await i18n.changeLanguage('en'); vi.unstubAllGlobals() })

  test('outbound + return waybill in one save; no Bosta booking row; progress names the replacement order', async () => {
    const { user, drawer } = await openDrawer()
    expect(within(drawer).getByTestId('manual-courier-title')).toHaveTextContent('Book the exchange with Aramex')
    expect(within(drawer).queryByTestId('exchange-booking')).toBeNull()
    expect(within(drawer).getByTestId('exchange-order-number')).toHaveTextContent('EXC-RR-7K3F9M')
    expect(drawer).not.toHaveTextContent('Bosta')
    await user.type(within(drawer).getByLabelText('Replacement waybill (going out)'), 'AR-OUT-1')
    await user.type(within(drawer).getByLabelText(/Return waybill \(old item\)/), 'AR-RET-1')
    await user.click(within(drawer).getByRole('button', { name: 'Save and send to Pick & Pack' }))
    await waitFor(() => {
      const post = calls.find(c => c.method === 'POST' && c.url.endsWith('/manual-courier'))
      expect(post?.body).toEqual({ carrier: 'Aramex', trackingNumber: 'AR-RET-1', outboundTrackingNumber: 'AR-OUT-1' })
    })
  })

  test('the same number both ways is refused with the server message', async () => {
    manualReply = () => fake({ code: 'SAME_AS_OUTBOUND',
      message_en: "The return waybill must be different from the replacement's outbound waybill.",
      message_ar: 'يجب أن تختلف بوليصة الإرجاع عن بوليصة إرسال البديل.' }, 409)
    const { user, drawer } = await openDrawer()
    await user.type(within(drawer).getByLabelText('Replacement waybill (going out)'), 'AR-X')
    await user.type(within(drawer).getByLabelText(/Return waybill \(old item\)/), 'AR-X')
    await user.click(within(drawer).getByRole('button', { name: 'Save and send to Pick & Pack' }))
    expect(await within(drawer).findByTestId('manual-courier-error')).toHaveTextContent('must be different')
  })

  test('outbound already entered: shown, only the return waybill is still asked; booked step names the courier', async () => {
    exchangeDetail({ status: 'pickup_booked' }, { outboundTrackingNumber: 'AR-OUT-1', outboundState: 'created' })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('manual-courier-outbound')).toHaveTextContent('Replacement with Aramex · AR-OUT-1')
    expect(within(drawer).queryByLabelText('Replacement waybill (going out)')).toBeNull()
    expect(within(drawer).getByLabelText(/Return waybill \(old item\)/)).toBeInTheDocument()
    expect(within(drawer).getByTestId('exchange-step-booked')).toHaveTextContent('Replacement booked with the courier')
    expect(within(drawer).getByTestId('exchange-step-booked')).toHaveAttribute('data-state', 'done')
  })

  test('Arabic: title', async () => {
    await i18n.changeLanguage('ar')
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('manual-courier-title')).toHaveTextContent('احجز الاستبدال مع Aramex')
  })
})
