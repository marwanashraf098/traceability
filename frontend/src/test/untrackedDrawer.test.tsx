import { test, expect, describe, vi, beforeEach, afterEach } from 'vitest'
import { I18nextProvider } from 'react-i18next'
import userEvent from '@testing-library/user-event'
import { renderWithProviders, screen, waitFor, within } from './renderWithProviders'
import { stubFetchWithShellDefaults } from './mockShellFetch'
import ExchangesRefunds from '../pages/ExchangesRefunds'
import type { ReturnRequestDetail, ReturnRequestItem, ReturnRequestRow } from '../api'
import i18n from '../i18n'

// Step 6b — untracked request items in the drawer (owners / managers): "Arrived · sellable" /
// "Arrived · damaged" while awaited, then the outcome + Undo. Fakes match 6a's shapes: detail
// items carry tracked:false, orderItemId, unitNo, pieceId/shortCode null, arrivedCondition;
// POST /return-requests/{id}/items/{itemId}/arrived {condition} and …/arrived/undo → 204.

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getRoleFromToken: vi.fn(() => 'owner') }
})

let detail: ReturnRequestDetail
let calls: Array<{ method: string; url: string; body?: unknown }>

function fakeResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300, status, statusText: 'OK',
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data), text: async () => JSON.stringify(data),
  })
}

const UNTRACKED: ReturnRequestItem = {
  id: 'item-u', pieceId: null, shortCode: null, variantId: 'v-1', productTitle: 'Linen Shirt', variantTitle: 'White · M',
  imageUrl: null, reasonCode: 'wrong_size', active: true, itemStatus: 'awaiting', tracked: false, orderItemId: 'oi-1',
  unitNo: 1, arrivedCondition: null,
}

function withItem(d: ReturnRequestDetail, over: Partial<ReturnRequestItem>): ReturnRequestDetail {
  return { ...d, items: d.items.map(i => (i.id === 'item-u' ? { ...i, ...over } : i)) }
}

function backend(url: string, opts: RequestInit = {}) {
  const method = (opts.method ?? 'GET').toUpperCase()
  const body = opts.body ? JSON.parse(opts.body as string) : undefined
  calls.push({ method, url, body })
  if (url.endsWith('/items/item-u/arrived') && method === 'POST') {
    detail = withItem({ ...detail, status: detail.type === 'exchange' ? 'exchanged' : 'refund_pending' },
      { itemStatus: 'done', active: false, arrivedCondition: body.condition, arrivedAt: '2026-09-27T10:00:00Z' })
    return fakeResponse(null, 204)
  }
  if (url.endsWith('/items/item-u/arrived/undo') && method === 'POST') {
    detail = withItem({ ...detail, status: 'pickup_booked' }, { itemStatus: 'awaiting', active: true, arrivedCondition: null, arrivedAt: null })
    return fakeResponse(null, 204)
  }
  if (url.includes('/refund-suggestion')) return fakeResponse({ amount: null, currency: 'EGP', source: null, approximate: false, lines: [] })
  if (url.includes('/return-requests/') && method === 'GET') return fakeResponse(detail)
  if (url.includes('/return-requests?')) {
    const row: ReturnRequestRow = { id: 'rr-1', reference: 'RR-6B0001', orderNumber: '#6501', customerName: 'Mona S', itemCount: 1,
      reasonCodes: ['wrong_size'], status: detail.status, createdAt: new Date().toISOString(), type: detail.type }
    return fakeResponse({ items: [row], total: 1 })
  }
  if (url.includes('/tenant/portal-settings')) return fakeResponse({ pickupBooking: true })
  if (url.includes('/exchanges') || url.includes('/refunds')) return fakeResponse([])
  return fakeResponse({})
}

function refundDetail(): ReturnRequestDetail {
  return {
    id: 'rr-1', reference: 'RR-6B0001', orderId: 'o-1', orderNumber: '#6501', customerName: 'Mona S', customerPhone: null,
    type: 'refund', status: 'pickup_booked', email: null, note: null, createdAt: '2026-09-26T10:00:00Z', deliveredAt: null,
    pickupCity: 'Cairo', pickupZone: 'Nasr City', pickupCityId: null, pickupCityName: null, pickupDistrictId: null,
    pickupDistrictName: null, pickupDistrictNameAr: null, bookingStatus: 'booked', bostaTrackingNumber: '7300000001',
    decidedAt: '2026-09-26T11:00:00Z', decidedBy: 'u', decidedByName: 'Owner', rejectionReason: null, returnShipmentId: 'leg-1',
    items: [UNTRACKED], refunds: [], history: [],
  }
}

beforeEach(async () => {
  await i18n.changeLanguage('en')
  detail = refundDetail()
  calls = []
  stubFetchWithShellDefaults(vi.fn(backend))
})

afterEach(async () => { await i18n.changeLanguage('en') })

async function openDrawer() {
  const user = userEvent.setup()
  // Step 2: the Requests tab is gone — the drawer opens through the alerts' deep link.
  renderWithProviders(<I18nextProvider i18n={i18n}><ExchangesRefunds /></I18nextProvider>,
    { initialEntries: ['/exchanges?tab=requests&request=rr-1'] })
  return { user, drawer: screen.getByTestId('return-request-drawer') }
}

describe('Drawer — untracked items (Step 6b)', () => {
  test('refund: "Not tracked" + both buttons; Arrived · sellable posts, then the outcome and Undo', async () => {
    const { user, drawer } = await openDrawer()
    const controls = await within(drawer).findByTestId('untracked-controls-item-u')
    expect(within(drawer).getByTestId('not-tracked')).toHaveTextContent('Not tracked')
    await user.click(within(controls).getByRole('button', { name: 'Arrived · sellable' }))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/rr-1/items/item-u/arrived'))?.body).toEqual({ condition: 'sellable' }))
    const outcome = await within(drawer).findByText('Arrived · to receive')
    expect(outcome).toBeInTheDocument()

    await user.click(within(drawer).getByRole('button', { name: 'Undo arrival' }))
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/rr-1/items/item-u/arrived/undo'))).toBe(true))
    expect(await within(drawer).findByRole('button', { name: 'Arrived · damaged' })).toBeInTheDocument()
  })

  test('no Undo once a refund was recorded', async () => {
    detail = withItem({ ...refundDetail(), status: 'refund_pending',
      refunds: [{ id: 'rf', method: 'cash', amount: '450.00', currency: 'EGP', refundedOn: '2026-09-27', reference: null, note: null,
        recordedByName: 'Owner', createdAt: '2026-09-27T12:00:00Z', voided: false } as never] },
      { itemStatus: 'done', active: false, arrivedCondition: 'damaged' })
    const { drawer } = await openDrawer()
    await within(drawer).findByText('RR-6B0001')
    await waitFor(() => expect(calls.some(c => c.method === 'GET' && c.url.endsWith('/return-requests/rr-1'))).toBe(true))
    expect(within(drawer).queryByRole('button', { name: 'Undo arrival' })).toBeNull()
    expect(within(drawer).queryByTestId('untracked-controls-item-u')).toBeNull()
  })

  test('exchange: the untracked old item is marked Arrived from the drawer', async () => {
    detail = { ...refundDetail(), type: 'exchange',
      items: [{ ...UNTRACKED, replacementVariantId: 'v-2', replacementVariantTitle: 'White · L' }],
      exchange: { trackingNumber: '7400000001', exchangeStatus: 'matched', orderId: 'oe', orderNumber: 'EXC-7400000001',
        orderStatus: 'delivered', shipmentState: 'delivered', deliveredAt: '2026-09-27T09:00:00Z', withCourierAt: '2026-09-26T15:00:00Z' } }
    const { user, drawer } = await openDrawer()
    const old = await within(drawer).findByTestId('exchange-untracked-old-item')
    expect(old).toHaveTextContent('Old item (not tracked)')
    await user.click(within(old).getByRole('button', { name: 'Arrived · damaged' }))
    await waitFor(() => expect(calls.find(c => c.url.endsWith('/rr-1/items/item-u/arrived'))?.body).toEqual({ condition: 'damaged' }))
    await waitFor(() => expect(within(drawer).getByTestId('exchange-step-exchanged').dataset.state).toBe('done'))
  })

  test('Arabic: the buttons', async () => {
    await i18n.changeLanguage('ar')
    const { drawer } = await openDrawer()
    const controls = await within(drawer).findByTestId('untracked-controls-item-u')
    expect(within(controls).getByRole('button', { name: 'وصل · صالح للبيع' })).toBeInTheDocument()
    expect(within(controls).getByRole('button', { name: 'وصل · تالف' })).toBeInTheDocument()
    expect(within(drawer).getByTestId('not-tracked')).toHaveTextContent('غير متتبَّع')
  })
})
