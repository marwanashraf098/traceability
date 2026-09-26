import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import userEvent from '@testing-library/user-event'
import { act } from '@testing-library/react'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import type {
  PortalSettings, RefundSuggestion, ReturnRefund, ReturnRequestDetail, ReturnRequestEvent, ReturnRequestItem, ReturnRequestRow,
} from '../api'
import { todayIso } from '../pages/exchangesRefunds/requestFormat'

// Returns Step 4d-2 — the lifecycle screens (mockups R1–R6). Fakes match the real shapes of
// ReturnRequestService.detail() / list() and RefundSuggestionService.suggest(); POST
// …/refunds → 201 {id}, …/void, …/mark-refunded, …/close, …/rest-not-coming, …/link-leg → 204.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const SETTINGS: PortalSettings = {
  slug: 'nourstudio', enabled: true, autoApprove: false, returnWindowDays: 30, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
  bostaConnected: true,
}

const SHIRT: ReturnRequestItem = {
  id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
  variantTitle: 'White · M', imageUrl: null, reasonCode: 'wrong_size', active: false,
  itemStatus: 'done', disposition: 'restocked', damageReason: null,
}
const PANTS: ReturnRequestItem = {
  id: 'item-2', pieceId: 'piece-2', shortCode: 'P000112', variantId: 'v-2', productTitle: 'Flipped Pants',
  variantTitle: 'Black · XL', imageUrl: null, reasonCode: 'damaged', active: false,
  itemStatus: 'done', disposition: 'damaged', damageReason: 'Torn seam',
}

const HISTORY: ReturnRequestEvent[] = [
  { type: 'refund_pending', actorId: null, actorName: 'Operator', occurredAt: '2026-09-24T09:26:00Z', metadata: { items: 2 } },
  { type: 'item_done', actorId: null, actorName: 'Operator', occurredAt: '2026-09-24T09:25:00Z', metadata: {} },
  { type: 'received', actorId: 'u-op', actorName: 'Operator', occurredAt: '2026-09-24T09:20:00Z', metadata: { items: 2 } },
  { type: 'leg_linked', actorId: null, actorName: null, occurredAt: '2026-09-22T13:02:00Z', metadata: { source: 'traced_booking', tracking_number: '8987563471' } },
  { type: 'pickup_booked', actorId: null, actorName: null, occurredAt: '2026-09-22T13:02:00Z', metadata: { source: 'traced_booking', tracking_number: '8987563471' } },
  { type: 'approved', actorId: 'u-mo', actorName: 'Mohamed A.', occurredAt: '2026-09-22T13:01:00Z', metadata: null },
  { type: 'requested', actorId: null, actorName: null, occurredAt: '2026-09-22T12:14:00Z', metadata: { items: 2 } },
]

const SUGGESTION: RefundSuggestion = {
  amount: '1150.00', currency: 'EGP', source: 'shopify', approximate: false,
  lines: [
    { variantId: 'v-2', productTitle: 'Flipped Pants', variantTitle: 'Black · XL', quantity: 1, unitPrice: '300.00', lineTotal: '300.00' },
    { variantId: 'v-1', productTitle: 'Linen Shirt', variantTitle: 'White · M', quantity: 1, unitPrice: '850.00', lineTotal: '850.00' },
  ],
}

const REFUND: ReturnRefund = {
  id: 'ref-1', method: 'instapay', amount: '1150.00', currency: 'EGP', refundedOn: '2026-09-26', reference: '88431207',
  note: null, recordedByName: 'Mohamed A.', createdAt: '2026-09-26T08:05:00Z', voided: false, voidedAt: null,
  voidedByName: null, voidNote: null,
}

let detail: ReturnRequestDetail
let suggestion: RefundSuggestion | Record<string, never>
let rows: ReturnRequestRow[]
let calls: Array<{ method: string; url: string; body?: Record<string, unknown> }>

function baseDetail(extra: Partial<ReturnRequestDetail> = {}): ReturnRequestDetail {
  return {
    id: 'rr-1', reference: 'RR-7K3F9M', orderId: 'order-1', orderNumber: '#1047', customerName: 'Mariam Saleh',
    customerPhone: '01012345678', type: 'refund', status: 'refund_pending', email: null, note: 'It runs small.',
    createdAt: '2026-09-22T12:14:00Z', deliveredAt: '2026-09-18T10:00:00Z',
    pickupCity: 'Cairo', pickupZone: 'Nasr City', pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
    pickupDistrictName: null, pickupDistrictNameAr: null,
    bookingStatus: 'booked', bookingError: null, bostaTrackingNumber: '8987563471', bookingAttemptedAt: null, bookingVerifiedAt: null,
    decidedAt: '2026-09-22T13:01:00Z', decidedBy: 'u-mo', decidedByName: 'Mohamed A.', rejectionReason: null,
    returnShipmentId: 'ship-1', closeReason: null, closeNote: null, closedAt: null, closedByName: null,
    items: [SHIRT, PANTS], receivedAt: '2026-09-24T09:20:00Z', history: HISTORY, refunds: [], refundTotal: '0.00',
    currency: 'EGP', refundedAt: null, refundedByName: null, returnTrackingNumber: '8987563471', unexpectedItems: [],
    linkableParcels: [],
    ...extra,
  }
}

function row(id: string, reference: string, status: ReturnRequestRow['status'], extra: Partial<ReturnRequestRow> = {}): ReturnRequestRow {
  return {
    id, reference, orderNumber: '#1047', customerName: 'Mariam Saleh', itemCount: 2, reasonCodes: ['wrong_size'],
    status, bookingStatus: null, createdAt: new Date().toISOString(), arrivedCount: 0, awaitingCount: 0,
    closeReason: null, currency: 'EGP', refundTotal: '0.00', refundOverdueDays: null, unexpectedItem: false, ...extra,
  }
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
  if (method === 'POST' && url.endsWith('/refunds')) {
    detail = { ...detail, refunds: [...(detail.refunds ?? []), { ...REFUND, id: 'ref-new', amount: body.amount, method: body.method }],
      refundTotal: body.amount }
    return fakeResponse({ id: 'ref-new' }, 201)
  }
  if (method === 'POST' && url.endsWith('/void')) {
    detail = { ...detail, refunds: (detail.refunds ?? []).map(r => ({ ...r, voided: true, voidedByName: 'Mohamed A.' })), refundTotal: '0.00' }
    return fakeResponse(null, 204)
  }
  if (method === 'POST' && url.endsWith('/mark-refunded')) { detail = { ...detail, status: 'refunded' }; return fakeResponse(null, 204) }
  if (method === 'POST' && url.endsWith('/close')) { detail = { ...detail, status: 'closed', closeReason: body.reason }; return fakeResponse(null, 204) }
  if (method === 'POST' && url.endsWith('/rest-not-coming')) { return fakeResponse(null, 204) }
  if (method === 'POST' && url.endsWith('/link-leg')) { detail = { ...detail, linkableParcels: [] }; return fakeResponse(null, 204) }
  if (url.includes('/refund-suggestion')) return fakeResponse(suggestion)
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) {
    const status = new URL(url, 'http://x').searchParams.get('status')
    const items = rows.filter(r => !status || r.status === status)
    return fakeResponse({ items, total: items.length })
  }
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  detail = baseDetail()
  suggestion = SUGGESTION
  rows = [row('rr-1', 'RR-7K3F9M', 'refund_pending')]
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => { await i18n.changeLanguage('en') })

async function openDrawer(initialEntries?: string[]) {
  const user = userEvent.setup()
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>, { initialEntries })
  if (!initialEntries) {
    await user.click(await screen.findByRole('button', { name: /Requests/ }))
    await user.click(await screen.findByText('RR-7K3F9M'))
  }
  const drawer = await screen.findByTestId('return-request-drawer')
  await within(drawer).findByTestId('return-request-drawer-body')
  return { user, drawer }
}

async function toArabic() {
  await act(async () => { await i18n.changeLanguage('ar') })
}

describe('R1 — record the refund', () => {
  test('items with outcomes, the Shopify suggestion, the prefilled amount and the restock warning', async () => {
    const { drawer } = await openDrawer()
    const suggestionLine = await within(drawer).findByTestId('refund-suggestion')
    expect(suggestionLine).toHaveTextContent('Suggested EGP 1,150: Flipped Pants EGP 300 + Linen Shirt EGP 850, after discounts. Prices from Shopify.')
    expect(within(drawer).queryByTestId('refund-approximate')).not.toBeInTheDocument()
    await waitFor(() => expect(within(drawer).getByLabelText('Amount')).toHaveValue('1150'))
    const outcomes = within(drawer).getAllByTestId('item-outcome').map(e => e.textContent)
    expect(outcomes).toEqual(['Restocked', 'Damaged'])
    expect(within(drawer).getByText(/Torn seam/)).toBeInTheDocument()
    expect(within(drawer).getByText('Customer')).toBeInTheDocument()
    expect(within(drawer).getByText('Returned')).toBeInTheDocument()
    expect(within(drawer).getByTestId('restock-warning')).toHaveTextContent('Traced already restocked the Linen Shirt.')
    expect(within(drawer).getByLabelText('Date')).toHaveValue(todayIso())
    expect(within(drawer).getByTestId('close-footer')).toHaveTextContent('Not refunding this one?')
  })

  test('method is required; saving posts method, amount, date and reference', async () => {
    const { user, drawer } = await openDrawer()
    await waitFor(() => expect(within(drawer).getByLabelText('Amount')).toHaveValue('1150'))
    await user.click(within(drawer).getByRole('button', { name: 'Save refund' }))
    expect(within(drawer).getByRole('alert')).toHaveTextContent('Choose how you refunded.')
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/refunds'))).toBe(false)

    await user.click(within(drawer).getByRole('radio', { name: 'InstaPay' }))
    await user.type(within(drawer).getByLabelText(/Reference/), '88431207')
    await user.click(within(drawer).getByRole('button', { name: 'Save refund' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/refunds'))).toBe(true))
    const post = calls.find(c => c.method === 'POST' && c.url.endsWith('/refunds'))!
    expect(post.body).toEqual({ method: 'instapay', amount: '1150', refundedOn: todayIso(), reference: '88431207' })
    expect(await within(drawer).findByTestId('refunds-list')).toBeInTheDocument()
  })

  test('amount validation: empty and zero are rejected', async () => {
    suggestion = {}
    const { user, drawer } = await openDrawer()
    expect(within(drawer).queryByTestId('refund-suggestion')).not.toBeInTheDocument()
    expect(within(drawer).getByLabelText('Amount')).toHaveValue('')
    await user.click(within(drawer).getByRole('radio', { name: 'Cash' }))
    await user.click(within(drawer).getByRole('button', { name: 'Save refund' }))
    expect(within(drawer).getByRole('alert')).toHaveTextContent('Enter an amount greater than 0')
    await user.type(within(drawer).getByLabelText('Amount'), '0')
    await user.click(within(drawer).getByRole('button', { name: 'Save refund' }))
    expect(within(drawer).getByRole('alert')).toHaveTextContent('Enter an amount greater than 0')
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/refunds'))).toBe(false)
  })

  test('catalog prices are flagged approximate', async () => {
    suggestion = { ...SUGGESTION, source: 'catalog', approximate: true, amount: '1350.00' }
    const { drawer } = await openDrawer()
    expect(await within(drawer).findByTestId('refund-approximate')).toHaveTextContent('Approximate')
    expect(within(drawer).getByTestId('refund-suggestion')).toHaveTextContent("today's catalog prices, before any discount.")
  })

  test('AR: the form and items in Arabic', async () => {
    const { drawer } = await openDrawer()
    await within(drawer).findByTestId('refund-suggestion')
    await toArabic()
    expect(within(drawer).getByText('تسجيل الاسترداد')).toBeInTheDocument()
    expect(within(drawer).getByRole('radio', { name: 'تحويل بنكي' })).toBeInTheDocument()
    expect(within(drawer).getAllByTestId('item-outcome').map(e => e.textContent)).toEqual(['أُعيد للمخزون', 'تالف'])
    expect(within(drawer).getByTestId('refund-suggestion')).toHaveTextContent('بعد الخصومات. الأسعار من Shopify.')
    expect(within(drawer).getByRole('button', { name: 'حفظ الاسترداد' })).toBeInTheDocument()
  })
})

describe('R4 — close without refund', () => {
  test('Close without refund… opens the dialog and posts the reason and note', async () => {
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Close without refund…' }))
    const dialog = screen.getByTestId('close-dialog')
    expect(dialog).toHaveTextContent('Close RR-7K3F9M')
    expect(within(dialog).getByRole('radio', { name: /No refund/ })).toBeChecked()
    await user.click(within(dialog).getByRole('radio', { name: /Other/ }))
    await user.type(within(dialog).getByLabelText(/Note/), 'Agreed on WhatsApp')
    await user.click(within(dialog).getByRole('button', { name: 'Close request' }))
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/return-requests/rr-1/close'))).toBe(true))
    expect(calls.find(c => c.url.endsWith('/close'))!.body).toEqual({ reason: 'other', note: 'Agreed on WhatsApp' })
    await waitFor(() => expect(screen.queryByTestId('close-dialog')).not.toBeInTheDocument())
  })
})

describe('R2 — refunds recorded and history', () => {
  beforeEach(() => {
    detail = baseDetail({
      refunds: [REFUND], refundTotal: '1150.00',
      history: [{ type: 'refund_recorded', actorId: 'u-mo', actorName: 'Mohamed A.', occurredAt: '2026-09-26T08:05:00Z',
        metadata: { amount: '1150.00', currency: 'EGP', method: 'instapay' } }, ...HISTORY],
    })
  })

  test('refunds list with total vs suggestion, history newest first, Mark as refunded', async () => {
    const { user, drawer } = await openDrawer()
    const list = await within(drawer).findByTestId('refunds-list')
    await waitFor(() => expect(within(list).getByTestId('refunds-total')).toHaveTextContent('Total EGP 1,150 of 1,150 suggested'))
    const entry = within(list).getByTestId('refund-entry')
    expect(entry).toHaveTextContent('EGP 1,150 · InstaPay')
    expect(entry).toHaveTextContent('ref 88431207')
    expect(entry).toHaveTextContent('recorded by Mohamed A.')
    const history = within(drawer).getAllByTestId('history-entry').map(e => e.textContent ?? '')
    expect(history[0]).toContain('Refund recorded · EGP 1,150 InstaPay')
    expect(history[1]).toContain('Refund pending')
    expect(history[1]).toContain('2 items decided')
    expect(history[2]).toContain('scanned by Operator')
    expect(history[3]).toContain('Courier booked · AWB 8987563471')
    expect(history[history.length - 1]).toContain('customer, via the returns portal')
    expect(history.some(h => h.includes('Parcel linked'))).toBe(false)

    await user.click(within(drawer).getByRole('button', { name: 'Mark as refunded' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/mark-refunded'))).toBe(true))
    await waitFor(() => expect(within(drawer).queryByRole('button', { name: 'Mark as refunded' })).not.toBeInTheDocument())
  })

  test('void asks to confirm, then posts the void', async () => {
    const { user, drawer } = await openDrawer()
    const list = await within(drawer).findByTestId('refunds-list')
    await user.click(within(list).getByRole('button', { name: 'Void' }))
    expect(within(list).getByTestId('void-confirm')).toHaveTextContent('Void this refund? It stays in the history.')
    expect(calls.some(c => c.url.endsWith('/void'))).toBe(false)
    await user.click(within(list).getByRole('button', { name: 'Void refund' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/refunds/ref-1/void'))).toBe(true))
    expect(await within(drawer).findByTestId('refund-voided')).toHaveTextContent('Voided by Mohamed A.')
  })

  test('+ Add another refund shows the form again', async () => {
    const { user, drawer } = await openDrawer()
    await within(drawer).findByTestId('refunds-list')
    expect(within(drawer).queryByTestId('refund-form')).not.toBeInTheDocument()
    await user.click(within(drawer).getByTestId('add-refund'))
    expect(within(drawer).getByTestId('refund-form')).toBeInTheDocument()
  })

  test('AR: refunds, history and the mark button in Arabic', async () => {
    const { drawer } = await openDrawer()
    await within(drawer).findByTestId('refunds-list')
    await toArabic()
    expect(within(drawer).getByText('المبالغ المستردة')).toBeInTheDocument()
    expect(within(drawer).getByText('السجل')).toBeInTheDocument()
    expect(within(drawer).getAllByTestId('history-entry')[0]).toHaveTextContent('تم تسجيل استرداد')
    expect(within(drawer).getByRole('button', { name: 'تحديد كمُسترد' })).toBeInTheDocument()
  })
})

describe('R3 — partly received', () => {
  beforeEach(() => {
    detail = baseDetail({
      status: 'pickup_booked', receivedAt: null, refunds: [], history: [],
      returnTrackingNumber: '6602246280', note: 'The pants have a broken zip.',
      items: [
        { ...SHIRT, itemStatus: 'arrived', disposition: 'pending', active: true },
        { ...PANTS, itemStatus: 'awaiting', disposition: null, damageReason: null, active: true },
      ],
      unexpectedItems: [{ pieceId: 'piece-9', shortCode: 'P000198', productTitle: 'Wool Wrap', variantTitle: 'Grey' }],
    })
    rows = [row('rr-1', 'RR-7K3F9M', 'pickup_booked', { arrivedCount: 1, awaitingCount: 1, unexpectedItem: true })]
  })

  test('item states, the parcel, the different-product flag and the partial actions', async () => {
    const { user, drawer } = await openDrawer()
    expect(within(drawer).getAllByText('Partly received').length).toBeGreaterThan(0)
    expect(within(drawer).getByText('Items (1 of 2 arrived)')).toBeInTheDocument()
    expect(within(drawer).getAllByTestId('item-outcome').map(e => e.textContent)).toEqual(['Arrived · to inspect', 'Not arrived'])
    expect(within(drawer).getByText('Parcel')).toBeInTheDocument()
    expect(within(drawer).getByText('6602246280')).toBeInTheDocument()
    expect(within(drawer).getByTestId('unexpected-item')).toHaveTextContent(
      "A different product came back in this parcelWool Wrap · Grey · P000198 isn't part of this request. Check it before refunding.")
    expect(within(drawer).queryByTestId('refund-form')).not.toBeInTheDocument()
    expect(within(drawer).getByTestId('partial-footer')).toHaveTextContent('Items that arrive later can still be scanned in.')

    await user.click(within(drawer).getByRole('button', { name: "The rest isn't coming" }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-1/rest-not-coming'))).toBe(true))

    await user.click(within(drawer).getByRole('button', { name: 'Close request…' }))
    expect(screen.getByTestId('close-dialog')).toBeInTheDocument()
  })

  test('AR: partly received in Arabic', async () => {
    const { drawer } = await openDrawer()
    await toArabic()
    expect(within(drawer).getAllByText('وصل جزء منه').length).toBeGreaterThan(0)
    expect(within(drawer).getByText('المنتجات (وصل 1 من 2)')).toBeInTheDocument()
    expect(within(drawer).getByTestId('unexpected-item')).toHaveTextContent('وصل منتج مختلف في هذا الطرد')
    expect(within(drawer).getByRole('button', { name: 'الباقي لن يصل' })).toBeInTheDocument()
  })
})

describe('R5 — link a parcel to its request', () => {
  const PARCEL = {
    shipmentId: 'ship-9', trackingNumber: '6602246280', itemsCount: 1, description: 'Linen Shirt in White - M x 1', descriptionAr: null,
    candidates: [
      { id: 'rr-1', reference: 'RR-7K3F9M', decidedAt: '2026-09-22T13:01:00Z', itemCount: 1, itemSummary: 'Linen Shirt · White · M' },
      { id: 'rr-2', reference: 'RR-H4NP7Z', decidedAt: '2026-09-23T10:00:00Z', itemCount: 1, itemSummary: 'Flipped Pants · Black · XL' },
    ],
  }

  beforeEach(() => {
    detail = baseDetail({ status: 'approved', receivedAt: null, returnShipmentId: null, returnTrackingNumber: null,
      bookingStatus: null, items: [{ ...SHIRT, itemStatus: 'awaiting', disposition: null, active: true }], linkableParcels: [PARCEL] })
    rows = [row('rr-1', 'RR-7K3F9M', 'approved')]
  })

  test('from the drawer: pick the request and link', async () => {
    const { user, drawer } = await openDrawer()
    await user.click(within(drawer).getByRole('button', { name: 'Link parcel…' }))
    const dialog = screen.getByTestId('link-parcel-dialog')
    expect(dialog).toHaveTextContent('Which request is this parcel for?')
    expect(dialog).toHaveTextContent('AWB 6602246280 · order #1047')
    expect(dialog).toHaveTextContent("Bosta's note: 1 item — Linen Shirt in White - M x 1")
    expect(within(dialog).getByRole('radio', { name: /RR-7K3F9M/ })).toBeChecked()
    await user.click(within(dialog).getByRole('radio', { name: /RR-H4NP7Z/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Link parcel' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/return-requests/rr-2/link-leg'))).toBe(true))
    expect(calls.find(c => c.url.endsWith('/link-leg'))!.body).toEqual({ shipmentId: 'ship-9' })
  })

  test("from the exception's link: the dialog opens by itself", async () => {
    await openDrawer(['/exchanges?tab=requests&request=rr-1&parcel=ship-9'])
    expect(await screen.findByTestId('link-parcel-dialog')).toBeInTheDocument()
  })
})

describe('R6 — Requests list', () => {
  beforeEach(() => {
    rows = [
      row('rr-a', 'RR-M4ZP6W', 'refund_pending', { refundOverdueDays: 7 }),
      row('rr-b', 'RR-7K3F9M', 'refund_pending'),
      row('rr-c', 'RR-Q2WX8T', 'pickup_booked', { arrivedCount: 1, awaitingCount: 1, unexpectedItem: true }),
      row('rr-d', 'RR-D8KT3B', 'refunded', { refundTotal: '640.00' }),
      row('rr-e', 'RR-X9KD3F', 'closed', { closeReason: 'no_refund' }),
    ]
  })

  function rowOf(reference: string) {
    return screen.getByText(reference, { selector: 'td bdi' }).closest('tr') as HTMLElement
  }

  test('status pills, badges and the awaiting-refund tab badge', async () => {
    const user = userEvent.setup()
    renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>)
    expect(await screen.findByTestId('requests-refund-badge')).toHaveTextContent('2 awaiting refund')
    await user.click(screen.getByRole('button', { name: /Requests/ }))
    await screen.findByText('RR-M4ZP6W')
    expect(within(rowOf('RR-M4ZP6W')).getByText('Refund pending')).toBeInTheDocument()
    expect(within(rowOf('RR-M4ZP6W')).getByTestId('overdue-badge')).toHaveTextContent('Overdue · 7d')
    expect(within(rowOf('RR-7K3F9M')).queryByTestId('overdue-badge')).not.toBeInTheDocument()
    expect(within(rowOf('RR-Q2WX8T')).getByText('Partly received')).toBeInTheDocument()
    expect(within(rowOf('RR-Q2WX8T')).getByTestId('check-item-badge')).toHaveTextContent('Check item')
    expect(within(rowOf('RR-D8KT3B')).getByText('Refunded · EGP 640')).toBeInTheDocument()
    expect(within(rowOf('RR-X9KD3F')).getByText('Closed · no refund')).toBeInTheDocument()
  })

  test('AR: pills and the tab badge in Arabic', async () => {
    const user = userEvent.setup()
    renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>)
    await user.click(await screen.findByRole('button', { name: /Requests/ }))
    await screen.findByText('RR-M4ZP6W')
    await toArabic()
    expect(screen.getByTestId('requests-refund-badge')).toHaveTextContent('2 بانتظار الاسترداد')
    expect(within(rowOf('RR-M4ZP6W')).getByTestId('overdue-badge')).toHaveTextContent('متأخر · 7 يوم')
    expect(within(rowOf('RR-Q2WX8T')).getByText('وصل جزء منه')).toBeInTheDocument()
    expect(within(rowOf('RR-D8KT3B')).getByText('تم الاسترداد · EGP 640')).toBeInTheDocument()
    expect(within(rowOf('RR-X9KD3F')).getByText('مغلق · بدون استرداد')).toBeInTheDocument()
  })
})
