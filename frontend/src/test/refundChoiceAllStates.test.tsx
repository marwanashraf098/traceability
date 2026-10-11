import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import { renderWithProviders, screen, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import i18n from '../i18n'
import { getRoleFromToken } from '../api'
import type { PortalSettings, ReturnRequestDetail, ReturnRequestRow } from '../api'

// Prod RR-AK9HPN (The Snouts): a request with refund_method instapay showed nothing in the Requested
// state — "Customer asked for" was only rendered next to the refund form. It is shown in EVERY state
// that has a refund method (refund requests and exchanges with a refund fallback); Show details only
// for owner / manager — a worker sees the method and the hint.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

const SETTINGS: PortalSettings = {
  slug: 'thesnouts', enabled: true, autoApprove: false, returnWindowDays: 14, logoUrl: null, brandColor: null,
  policyText: null, pickupBooking: false, portalPickupBooking: false, returnLocationId: null, returnLocationName: null,
}

let detail: ReturnRequestDetail

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: '',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

function row(): ReturnRequestRow {
  return { id: 'rr-1', reference: 'RR-AK9HPN', orderNumber: '#1188', customerName: 'Mona Adel', itemCount: 1,
    reasonCodes: ['wrong_size'], status: detail.status, createdAt: new Date().toISOString(), type: detail.type }
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  if (url.includes('/refund-suggestion')) return fakeResponse({ amount: null, currency: 'EGP', source: null, approximate: false })
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) return fakeResponse({ items: [row()], total: 1 })
  if (url.includes('/tenant/portal-settings')) return fakeResponse(SETTINGS)
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

const ITEM = { id: 'item-1', pieceId: 'piece-1', shortCode: 'P000245', variantId: 'v-1', productTitle: 'Linen Shirt',
  variantTitle: 'White / M', imageUrl: null, reasonCode: 'wrong_size', active: true }

function refundRequest(status: ReturnRequestDetail['status'], extra: Partial<ReturnRequestDetail> = {}): ReturnRequestDetail {
  return {
    id: 'rr-1', reference: 'RR-AK9HPN', orderId: 'order-1', orderNumber: '#1188', customerName: 'Mona Adel',
    customerPhone: '01012345678', type: 'refund', status, email: null, note: null, createdAt: new Date().toISOString(),
    deliveredAt: '2026-10-08T10:00:00Z', pickupCity: 'Cairo', pickupZone: 'Nasr City', bookingStatus: null,
    decidedAt: status === 'requested' ? null : '2026-10-10T10:00:00Z', decidedBy: null, decidedByName: null,
    rejectionReason: status === 'rejected' ? 'Worn.' : null, returnShipmentId: null,
    items: [{ ...ITEM, itemStatus: 'awaiting' }], refunds: [], refundTotal: '0.00', currency: 'EGP',
    refundMethod: 'instapay', refundHint: 'mona•••', refundDetailsAvailable: true, refundDetailsPurgedAt: null,
    ...extra,
  } as ReturnRequestDetail
}

function exchangeRequest(status: ReturnRequestDetail['status'], extra: Partial<ReturnRequestDetail> = {}): ReturnRequestDetail {
  return refundRequest(status, {
    type: 'exchange', refundFallbackOk: true, bookingStatus: status === 'requested' ? null : 'booked',
    bostaTrackingNumber: status === 'requested' ? null : '5519026734', history: [],
    items: [{ ...ITEM, variantTitle: 'White · M', itemStatus: 'awaiting', replacementVariantId: 'v-2',
      replacementVariantTitle: 'White · L', replacementAvailable: 3, replacementInStock: true }],
    exchange: status === 'requested' ? null : { trackingNumber: '5519026734', exchangeStatus: 'matched', orderId: 'o-exc',
      orderNumber: 'EXC-5519026734', orderStatus: 'new', shipmentState: 'created', deliveredAt: null, withCourierAt: null },
    bookNowAvailable: false,
    ...extra,
  } as Partial<ReturnRequestDetail>)
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  vi.mocked(getRoleFromToken).mockReturnValue('owner')
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => {
  vi.unstubAllGlobals()
  await i18n.changeLanguage('en')
})

async function openDrawer() {
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  const drawer = screen.getByTestId('return-request-drawer')
  await within(drawer).findAllByText(/RR-AK9HPN/)
  return drawer
}

describe('"Customer asked for" in every state with a refund method', () => {
  test('Requested: under the items, above Reject / Approve — method, hint, Show details', async () => {
    detail = refundRequest('requested')
    const drawer = await openDrawer()
    const block = await within(drawer).findByTestId('customer-refund-choice')
    expect(block).toHaveTextContent('Customer asked for')
    expect(within(block).getByTestId('refund-choice-summary')).toHaveTextContent('InstaPay · mona•••')
    expect(within(block).getByRole('button', { name: 'Show details' })).toBeInTheDocument()
    // Order: items, then the block; the decision footer comes after it.
    const items = within(drawer).getAllByTestId('request-item')[0]
    const footer = within(drawer).getByTestId('request-footer')
    expect(items.compareDocumentPosition(block) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(block.compareDocumentPosition(footer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  test('Requested — Arabic', async () => {
    await i18n.changeLanguage('ar')
    detail = refundRequest('requested')
    const drawer = await openDrawer()
    const block = await within(drawer).findByTestId('customer-refund-choice')
    expect(block).toHaveTextContent('طلب العميل')
    expect(within(block).getByRole('button', { name: 'عرض البيانات' })).toBeInTheDocument()
  })

  for (const status of ['approved', 'pickup_booked', 'rejected', 'closed'] as const) {
    test(`${status} (nothing arrived yet): the block is there`, async () => {
      detail = refundRequest(status, status === 'closed' ? { closeReason: 'no_refund', closeNote: null } : {})
      const drawer = await openDrawer()
      expect(await within(drawer).findByTestId('refund-choice-summary')).toHaveTextContent('InstaPay · mona•••')
    })
  }

  test('received with the refund form: still there, exactly once', async () => {
    detail = refundRequest('refund_pending', { items: [{ ...ITEM, itemStatus: 'done' }] } as Partial<ReturnRequestDetail>)
    const drawer = await openDrawer()
    expect(await within(drawer).findAllByTestId('customer-refund-choice')).toHaveLength(1)
    expect(await within(drawer).findByTestId('refund-form')).toBeInTheDocument()
  })

  test('refunded: the block is there', async () => {
    detail = refundRequest('refunded', { items: [{ ...ITEM, itemStatus: 'done' }],
      refunds: [{ id: 'f1', kind: 'refund', method: 'instapay', amount: '640.00', currency: 'EGP', refundedOn: '2026-10-11',
        reference: null, note: null, voided: false, createdAt: '2026-10-11T10:00:00Z', createdByName: 'Owner' }],
      refundTotal: '640.00' } as Partial<ReturnRequestDetail>)
    const drawer = await openDrawer()
    expect(await within(drawer).findAllByTestId('customer-refund-choice')).toHaveLength(1)
  })

  test('removed details: method + removed note, no Show details', async () => {
    detail = refundRequest('requested', { refundDetailsAvailable: false, refundDetailsPurgedAt: '2026-10-11T10:00:00Z' })
    const drawer = await openDrawer()
    const block = await within(drawer).findByTestId('customer-refund-choice')
    expect(within(block).getByTestId('refund-choice-removed')).toBeInTheDocument()
    expect(within(block).queryByRole('button', { name: 'Show details' })).not.toBeInTheDocument()
  })

  test('no refund method: no block', async () => {
    detail = refundRequest('requested', { refundMethod: null, refundHint: null, refundDetailsAvailable: false })
    const drawer = await openDrawer()
    await within(drawer).findByTestId('request-footer')
    expect(within(drawer).queryByTestId('customer-refund-choice')).not.toBeInTheDocument()
  })

  test('exchange with a refund fallback, awaiting a decision: the block is there', async () => {
    detail = exchangeRequest('requested')
    const drawer = await openDrawer()
    expect(await within(drawer).findByTestId('refund-choice-summary')).toHaveTextContent('InstaPay · mona•••')
  })

  test('exchange in progress: the block is there', async () => {
    detail = exchangeRequest('pickup_booked')
    const drawer = await openDrawer()
    expect(await within(drawer).findByTestId('refund-choice-summary')).toHaveTextContent('InstaPay · mona•••')
  })
})

describe('worker: method + hint, never Show details', () => {
  test('Requested and with the refund form', async () => {
    vi.mocked(getRoleFromToken).mockReturnValue('worker')
    detail = refundRequest('requested')
    const drawer = await openDrawer()
    const block = await within(drawer).findByTestId('customer-refund-choice')
    expect(within(block).getByTestId('refund-choice-summary')).toHaveTextContent('InstaPay · mona•••')
    expect(within(block).queryByRole('button', { name: 'Show details' })).not.toBeInTheDocument()
  })

  test('Arabic', async () => {
    vi.mocked(getRoleFromToken).mockReturnValue('worker')
    await i18n.changeLanguage('ar')
    detail = refundRequest('approved')
    const drawer = await openDrawer()
    const block = await within(drawer).findByTestId('customer-refund-choice')
    expect(block).toHaveTextContent('طلب العميل')
    expect(within(block).queryByRole('button', { name: 'عرض البيانات' })).not.toBeInTheDocument()
  })
})
