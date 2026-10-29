import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { screen, within } from '@testing-library/react'
import { renderWithProviders, waitFor } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import * as api from '../api'
import i18n from '../i18n'
import LookupPage from '../pages/Lookup'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { ReturnRequestDetail } from '../api'

// C2 — Lookup Exchange, EN and AR:
//   Lookup — "Exchange" on an available piece (server's canExchange only, owner / manager); the dialog searches the
//            original order, picks what comes back (piece or untracked unit), optional reason / courier / waybills,
//            POSTs /pieces/{id}/exchange; server refusals shown as sent; a reserved piece names its exchange
//   drawer — "Reserved for this exchange — no waybill yet", Cancel exchange (short confirm), nothing to cancel once
//            it left; the waybill save says it hands the piece to the courier; outbound still asked on 'exchanged'

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return {
    ...actual,
    lookup:           vi.fn(),
    getRoleFromToken: vi.fn(() => 'owner' as const),
  }
})

const ULID = '01JC8ZQ4M7T2V9X3K5N6P8R0SW'

function piece(overrides: Partial<api.PieceLookupResult> = {}): api.PieceLookupResult {
  return {
    type: 'piece', id: ULID, barcode: 'PC-' + ULID, shortCode: 'P1048327', status: 'available', canReprint: true,
    condition: 'good', receivedAt: '2026-10-01T09:00:00Z',
    variant: { id: 'v-m', productTitle: 'Linen Shirt', title: 'White / M', sku: 'LIN-WHT-M' },
    currentLocation: { id: 'loc1', name: 'Main Warehouse' }, currentOrder: null, currentShipment: null,
    receivingSession: null, timeline: [], ...overrides,
  }
}

function fake(data: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data) })
}

let calls: Array<{ method: string; url: string; body?: unknown }>
let createReply: () => Promise<unknown>

const ORDERS: api.LookupExchangeOrder[] = [
  { id: 'o-1', number: '#16443', customerName: 'Mona Adel', deliveredAt: '2026-10-21T10:00:00Z', carrierName: 'QP Express', itemCount: 2 },
]
const RETURNABLES: api.LookupReturnable[] = [
  { kind: 'piece', pieceId: 'p-old', shortCode: 'P1047710', variantId: 'v-l', productTitle: 'Linen Shirt', variantTitle: 'White / L' },
  { kind: 'unit', orderItemId: 'oi-2', unitNo: 1, variantId: 'v-p', productTitle: 'Cotton Pants', variantTitle: 'Beige / 32' },
]

function lookupBackend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  calls.push({ method, url, body: opts.body ? JSON.parse(opts.body as string) : undefined })
  if (url.includes('/lookup-exchange/orders?q=')) return fake(ORDERS)
  if (url.includes('/returnables')) return fake(RETURNABLES)
  if (url.endsWith('/exchange') && method === 'POST') return createReply()
  return fake({})
}

async function search(code = 'P1048327') {
  renderWithProviders(<I18nextProvider i18n={i18n}><LookupPage /></I18nextProvider>)
  await userEvent.type(screen.getByRole('textbox'), code)
  await userEvent.keyboard('{Enter}')
}

describe('Lookup — Exchange', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    vi.clearAllMocks()
    calls = []
    createReply = () => fake({ requestId: 'rr-1', reference: 'RR-7K3F9M', replacementOrderNumber: 'EXC-RR-7K3F9M',
      dispatched: false }, 201)
    vi.mocked(api.getRoleFromToken).mockReturnValue('owner')
    vi.mocked(api.lookup).mockResolvedValue(piece({ canExchange: true }))
    stubFetchWithShellDefaults(vi.fn(lookupBackend))
  })
  afterEach(async () => { await i18n.changeLanguage('en'); vi.unstubAllGlobals() })

  test('lx1 — no Exchange without the server flag, or for a worker', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece())
    await search()
    await screen.findByTestId('sold-open-btn')
    expect(screen.queryByTestId('exchange-open-btn')).toBeNull()
  })

  test('lx1b — worker sees no Exchange', async () => {
    vi.mocked(api.getRoleFromToken).mockReturnValue('worker')
    await search()
    await screen.findByTestId('piece-short-code')
    expect(screen.queryByTestId('exchange-open-btn')).toBeNull()
  })

  test('lx2 — search the order, pick the piece coming back, create with a waybill', async () => {
    const user = userEvent.setup()
    await search()
    await user.click(await screen.findByTestId('exchange-open-btn'))
    const dialog = screen.getByTestId('lookup-exchange-dialog')
    expect(dialog).toHaveTextContent('Linen Shirt — White / M · P1048327')
    expect(within(dialog).getByTestId('lookup-exchange-submit')).toBeDisabled()

    await user.type(within(dialog).getByTestId('lookup-exchange-search'), 'Mona')
    const orders = await within(dialog).findByTestId('lookup-exchange-orders')
    expect(orders).toHaveTextContent('#16443 · Mona Adel')
    expect(orders).toHaveTextContent('2 items · QP Express')
    await user.click(within(orders).getByRole('button'))

    const rets = await within(dialog).findByTestId('lookup-exchange-returnables')
    expect(rets).toHaveTextContent('Cotton Pants — Beige / 32 · unit 1')
    await user.click(within(rets).getAllByRole('radio')[0])
    await user.selectOptions(within(dialog).getByLabelText(/Reason/), 'wrong_size')
    await user.type(within(dialog).getByTestId('lookup-exchange-outbound'), '7712 0048 3391')
    expect(within(dialog).getByTestId('lookup-exchange-summary')).toHaveTextContent('hands this piece to the courier now')
    await user.click(within(dialog).getByTestId('lookup-exchange-submit'))

    await waitFor(() => {
      const post = calls.find(c => c.method === 'POST' && c.url.endsWith(`/pieces/${ULID}/exchange`))
      expect(post?.body).toEqual({ orderId: 'o-1', returningPieceId: 'p-old', reasonCode: 'wrong_size',
        outboundTrackingNumber: '7712 0048 3391' })
    })
    await waitFor(() => expect(screen.queryByTestId('lookup-exchange-dialog')).toBeNull())
    await waitFor(() => expect(api.lookup).toHaveBeenCalledTimes(2))
  })

  test('lx3 — an untracked unit comes back; without a waybill the summary says it can be added later', async () => {
    const user = userEvent.setup()
    await search()
    await user.click(await screen.findByTestId('exchange-open-btn'))
    const dialog = screen.getByTestId('lookup-exchange-dialog')
    await user.type(within(dialog).getByTestId('lookup-exchange-search'), '16443')
    await user.click(within(await within(dialog).findByTestId('lookup-exchange-orders')).getByRole('button'))
    await user.click(within(await within(dialog).findByTestId('lookup-exchange-returnables')).getAllByRole('radio')[1])
    expect(within(dialog).getByTestId('lookup-exchange-summary')).toHaveTextContent('Enter the tracking number when')
    await user.click(within(dialog).getByTestId('lookup-exchange-submit'))
    await waitFor(() => {
      const post = calls.find(c => c.method === 'POST' && c.url.endsWith('/exchange'))
      expect(post?.body).toEqual({ orderId: 'o-1', returningOrderItemId: 'oi-2', returningUnitNo: 1, reasonCode: 'other' })
    })
  })

  test('lx4 — a refusal shows the server message (EN and AR)', async () => {
    createReply = () => fake({ code: 'NOT_AT_MAIN_WAREHOUSE',
      message_en: 'Move this piece to your main warehouse first (Transfers), then start the exchange.',
      message_ar: 'انقل هذه القطعة إلى مخزنك الرئيسي أولاً (التحويلات)، ثم ابدأ الاستبدال.' }, 409)
    const user = userEvent.setup()
    await search()
    await user.click(await screen.findByTestId('exchange-open-btn'))
    const dialog = screen.getByTestId('lookup-exchange-dialog')
    await user.type(within(dialog).getByTestId('lookup-exchange-search'), 'Mona')
    await user.click(within(await within(dialog).findByTestId('lookup-exchange-orders')).getByRole('button'))
    await user.click(within(await within(dialog).findByTestId('lookup-exchange-returnables')).getAllByRole('radio')[0])
    await user.click(within(dialog).getByTestId('lookup-exchange-submit'))
    expect(await within(dialog).findByTestId('lookup-exchange-error'))
      .toHaveTextContent('Move this piece to your main warehouse first (Transfers), then start the exchange.')

    await i18n.changeLanguage('ar')
    await user.click(within(dialog).getByTestId('lookup-exchange-submit'))
    await waitFor(() => expect(within(dialog).getByTestId('lookup-exchange-error'))
      .toHaveTextContent('انقل هذه القطعة إلى مخزنك الرئيسي أولاً'))
  })

  test('lx5 — a piece reserved to a Lookup exchange names it and links to the request', async () => {
    vi.mocked(api.lookup).mockResolvedValue(piece({ status: 'reserved', lookupExchange: { requestId: 'rr-9', reference: 'RR-ABCD23' } }))
    await search()
    const note = await screen.findByTestId('lookup-exchange-note')
    expect(note).toHaveTextContent('Reserved for exchange RR-ABCD23')
    expect(within(note).getByRole('link', { name: 'Open RR-ABCD23' }))
      .toHaveAttribute('href', '/exchanges?tab=requests&request=rr-9')
  })

  test('lx6 — Arabic: the button and the dialog title', async () => {
    await i18n.changeLanguage('ar')
    const user = userEvent.setup()
    await search()
    await user.click(await screen.findByRole('button', { name: 'استبدال' }))
    expect(screen.getByRole('heading', { name: 'استبدال هذه القطعة' })).toBeInTheDocument()
    expect(screen.getByTestId('lookup-exchange-dialog')).toHaveTextContent('١. ابحث عن الطلب الأصلي')
  })
})

// ── Drawer ────────────────────────────────────────────────────────────────────

let detail: ReturnRequestDetail
let manualReply: () => Promise<unknown>
let cancelReply: () => Promise<unknown>

function lookupDetail(extra: Partial<ReturnRequestDetail> = {}, replacement: Record<string, unknown> = {},
                      lx: Partial<api.LookupExchangeDrawer> = {}) {
  detail = {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#16443', customerName: 'Mona Adel',
    customerPhone: '01012345678', type: 'exchange', status: 'approved', email: null, note: null,
    createdAt: new Date().toISOString(), deliveredAt: '2026-10-21T10:00:00.000+00:00',
    pickupCity: null, pickupZone: null, pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
    pickupDistrictName: null, pickupDistrictNameAr: null,
    bookingStatus: 'manual', bookingError: null, bostaTrackingNumber: null, bookingAttemptedAt: null, bookingVerifiedAt: null,
    decidedAt: '2026-10-28T10:00:00.000+00:00', decidedBy: null, decidedByName: null, rejectionReason: null, returnShipmentId: null,
    items: [{ id: 'item-1', pieceId: 'p-old', shortCode: 'P1047710', variantId: 'v-l', productTitle: 'Linen Shirt',
      variantTitle: 'White / L', imageUrl: null, reasonCode: 'other', active: true, itemStatus: 'awaiting',
      replacementVariantId: 'v-m', replacementVariantTitle: 'White / M' }],
    otherCourier: {
      forwardCarrierName: 'QP Express', carrierName: 'QP Express',
      pickup: { name: 'Mona Adel', phone: '01012345678', addressLines: ['5 Tahrir St', 'Cairo'] },
      replacement: { orderId: 'rep-1', orderNumber: 'EXC-RR-7K3F9M', outboundTrackingNumber: null, outboundState: null,
        lookupExchange: { pieceId: ULID, pieceShortCode: 'P1048327', pieceStatus: 'reserved', left: false, cancelled: false,
          canCancel: true, ...lx },
        ...replacement },
    },
    exchange: { trackingNumber: (replacement.outboundTrackingNumber as string) ?? null, exchangeStatus: null, orderId: 'rep-1',
      orderNumber: 'EXC-RR-7K3F9M', orderStatus: 'confirmed', shipmentState: null, deliveredAt: null, withCourierAt: null },
    ...extra,
  } as ReturnRequestDetail
}

function drawerBackend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  calls.push({ method, url, body: opts.body ? JSON.parse(opts.body as string) : undefined })
  if (url.endsWith('/manual-courier')) return manualReply()
  if (url.endsWith('/cancel-exchange')) return cancelReply()
  if (url.includes('/generic-shipments/carriers')) return fake([{ carrier: 'QP Express', withCourier: 1 }])
  if (url.includes('/return-requests/') && method === 'GET') return fake(detail)
  if (url.includes('/return-requests?')) return fake({ items: [], total: 0 })
  if (url.includes('/tenant/portal-settings')) return fake({ slug: 'x', enabled: true, autoApprove: false, returnWindowDays: 30,
    logoUrl: null, brandColor: null, policyText: null, pickupBooking: false, portalPickupBooking: false,
    returnLocationId: null, returnLocationName: null, bostaConnected: false })
  if (url.includes('/returns-exchanges')) return fake({ items: [], nextCursor: null, stages: {}, tiles: {} })
  return fake({})
}

async function openDrawer() {
  const user = userEvent.setup()
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findByTestId('lookup-exchange')
  return { user, drawer }
}

describe('Drawer — an exchange made in Lookup', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    calls = []
    manualReply = () => fake({ outcome: 'OUTBOUND_CREATED', carrierName: 'QP Express', trackingNumber: null,
      returnShipmentId: null, outboundTrackingNumber: '7712' })
    cancelReply = () => Promise.resolve({ ok: true, status: 204, statusText: '', headers: { get: () => null },
      json: async () => null, text: async () => '' })
    lookupDetail()
    stubFetchWithShellDefaults(vi.fn(drawerBackend))
  })
  afterEach(async () => { await i18n.changeLanguage('en'); vi.unstubAllGlobals() })

  test('dx1 — reserved, no waybill yet: the warning, the hand-over save, Cancel exchange after a confirm', async () => {
    const { user, drawer } = await openDrawer()
    expect(within(drawer).getByTestId('lookup-exchange-reserved')).toHaveTextContent('Reserved for this exchange — no waybill yet')
    expect(within(drawer).getByTestId('lookup-exchange')).toHaveTextContent('EXC-RR-7K3F9M · P1048327')
    expect(within(drawer).getByTestId('manual-courier-form')).toHaveTextContent('handed to the courier at once')
    expect(within(drawer).queryByTestId('exchange-step-packing')).toBeNull()
    expect(within(drawer).getByTestId('exchange-step-booked')).toBeInTheDocument()

    await user.click(within(drawer).getByTestId('lookup-exchange-cancel'))
    const confirm = within(drawer).getByTestId('lookup-exchange-cancel-confirm')
    expect(confirm).toHaveTextContent('Piece P1048327 goes back to available')
    expect(calls.some(c => c.url.endsWith('/cancel-exchange'))).toBe(false)
    await user.click(within(drawer).getByTestId('lookup-exchange-cancel-confirm-btn'))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/cancel-exchange'))).toBe(true))
  })

  test('dx2 — the waybill save posts the outbound number', async () => {
    const { user, drawer } = await openDrawer()
    await user.type(within(drawer).getByLabelText('Replacement waybill (going out)'), '7712')
    await user.click(within(drawer).getByRole('button', { name: 'Save and hand to the courier' }))
    await waitFor(() => {
      const post = calls.find(c => c.method === 'POST' && c.url.endsWith('/manual-courier'))
      expect(post?.body).toEqual({ carrier: 'QP Express', trackingNumber: null, outboundTrackingNumber: '7712' })
    })
  })

  test('dx3 — once it left: no warning, no Cancel exchange', async () => {
    lookupDetail({ status: 'pickup_booked' }, { outboundTrackingNumber: '7712', outboundState: 'with_courier' },
      { pieceStatus: 'with_courier', left: true, canCancel: false })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('lookup-exchange-left')).toHaveTextContent('left with the courier')
    expect(within(drawer).queryByTestId('lookup-exchange-reserved')).toBeNull()
    expect(within(drawer).queryByTestId('lookup-exchange-cancel')).toBeNull()
  })

  test('dx4 — old item already back (exchanged): only the outbound waybill is asked', async () => {
    lookupDetail({ status: 'exchanged' })
    const { drawer } = await openDrawer()
    expect(within(drawer).getByLabelText('Replacement waybill (going out)')).toBeInTheDocument()
    expect(within(drawer).queryByLabelText(/Return waybill \(old item\)/)).toBeNull()
  })

  test('dx5 — a cancel refusal shows the server message', async () => {
    cancelReply = () => fake({ code: 'REPLACEMENT_LEFT', message_en: 'The replacement already left with the courier — this exchange can\'t be cancelled.',
      message_ar: 'خرج البديل بالفعل مع شركة الشحن — لا يمكن إلغاء هذا الاستبدال.' }, 409)
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByTestId('lookup-exchange-cancel'))
    await user.click(within(drawer).getByTestId('lookup-exchange-cancel-confirm-btn'))
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('already left with the courier')
  })

  test('dx6 — Arabic: the warning and Cancel exchange', async () => {
    await i18n.changeLanguage('ar')
    const { drawer } = await openDrawer()
    expect(within(drawer).getByTestId('lookup-exchange-reserved')).toHaveTextContent('محجوزة لهذا الاستبدال — لا توجد بوليصة بعد')
    expect(within(drawer).getByTestId('lookup-exchange-cancel')).toHaveTextContent('إلغاء الاستبدال')
  })
})
