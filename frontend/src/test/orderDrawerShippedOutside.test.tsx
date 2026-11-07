import { test, expect, afterEach, vi } from 'vitest'
import i18next from 'i18next'
import { initReactI18next, I18nextProvider } from 'react-i18next'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import en from '../locales/en.json'
import ar from '../locales/ar.json'
import OrderDrawer from '../components/OrderDrawer'
import type { OrderDetail } from '../api'

afterEach(() => vi.unstubAllGlobals())

function jsonOk(data: unknown) {
  return Promise.resolve({
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k === 'content-type' ? 'application/json' : null) },
    json: async () => structuredClone(data),
  })
}

// Mirrors ApiExceptionHandler's bare-ResponseStatusException handler, which returns
// status only, no body — request() never gets a JSON message to read for this case.
function jsonConflict() {
  return Promise.resolve({
    ok: false,
    status: 409,
    statusText: 'Conflict',
    headers: { get: () => null },
    json: async () => ({}),
  })
}

function makeOrderDetail(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    id: 'order-1', number: '#2212094474', customerName: 'Habiba Ali', customerPhone: '01211000010',
    address: null, paymentMethod: 'cod', codAmount: 990, status: 'awaiting_pickup', onHold: false,
    holdReason: null, placedAt: '2026-08-11T10:42:00Z', createdAt: '2026-08-11T10:42:00Z',
    items: [{ id: 'item-1', productTitle: 'Bikini Pink', variantTitle: 'M', sku: 'SN-BIK-PNK-M', quantity: 1, imageUrl: null, allocatedPieces: [] }],
    shipments: [],
    bostaLinkStatus: 'linked', notTracedAt: null, isExchange: false, shopifyOrderUrl: null,
    derivedStatus: {
      primaryKey: 'status.in_transit', tone: 'INFO', healthChips: [], historicalNote: null,
      conflictKey: null, notTraced: false, packedConfirmed: true,
      fulfillmentKey: 'status.fulfilled', fulfillmentTone: 'SUCCESS',
    },
    physicallyWithCourier: false,
    ...overrides,
  }
}

// 2026-11-07 — "Shipped outside Traced" in the order drawer: the card shows only with the backend's offer; confirming
// POSTs /orders/{id}/shipped-outside-traced and reloads (the offer then gone); a 409 says nothing is left to move.

function stub(initial: OrderDetail, status: 200 | 409 = 200) {
  let order = initial
  const posts: string[] = []
  const fn = vi.fn((url: string, init?: RequestInit) => {
    if (url.includes('/shipped-outside-traced') && init?.method === 'POST') {
      posts.push(url)
      if (status === 409) return jsonConflict()
      order = { ...order, shippedOutsideTraced: undefined }
      return jsonOk({ pieces: 1, target: 'with_courier' })
    }
    if (url.includes('/notes')) return jsonOk([])
    if (url.includes('/timeline')) return jsonOk([])
    if (url.includes('/orders/')) return jsonOk(order)
    return jsonOk({})
  })
  return { fn, posts }
}

function renderDrawer(fetchImpl: ReturnType<typeof vi.fn>, lang: 'en' | 'ar' = 'en') {
  const testI18n = i18next.createInstance()
  testI18n.use(initReactI18next).init({
    lng: lang, fallbackLng: 'en',
    resources: { en: { translation: en }, ar: { translation: ar } },
    interpolation: { escapeValue: false },
  })
  vi.stubGlobal('fetch', fetchImpl)
  return render(
    <MemoryRouter>
      <I18nextProvider i18n={testI18n}>
        <OrderDrawer orderId="order-1" onClose={() => {}} />
      </I18nextProvider>
    </MemoryRouter>
  )
}

test('no offer → no card', async () => {
  renderDrawer(stub(makeOrderDetail()).fn)
  await screen.findByText('#2212094474')
  expect(screen.queryByTestId('shipped-outside')).toBeNull()
})

test('offer → card; confirming moves the pieces and the card goes', async () => {
  const { fn, posts } = stub(makeOrderDetail({ shippedOutsideTraced: { pieces: 2, target: 'with_courier' } }))
  const user = userEvent.setup()
  renderDrawer(fn)
  const card = await screen.findByTestId('shipped-outside')
  expect(card).toHaveTextContent('2 pieces are still held in Traced')
  await user.click(within(card).getByRole('button', { name: 'Shipped outside Traced' }))
  expect(await screen.findByText('The 2 pieces are marked as with the courier, like any shipped order. Nothing changes in Shopify.')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Mark shipped' }))
  await waitFor(() => expect(posts).toEqual(['/api/v1/orders/order-1/shipped-outside-traced']))
  await waitFor(() => expect(screen.queryByTestId('shipped-outside')).toBeNull())
})

test('409 → "nothing to move"; Arabic copy', async () => {
  const { fn } = stub(makeOrderDetail({ shippedOutsideTraced: { pieces: 1, target: 'delivered' } }), 409)
  const user = userEvent.setup()
  renderDrawer(fn, 'ar')
  const card = await screen.findByTestId('shipped-outside')
  expect(card).toHaveTextContent('قطعة واحدة ما زالت محجوزة في Traced')
  await user.click(within(card).getByRole('button', { name: 'تم الشحن خارج Traced' }))
  expect(await screen.findByText('تُسجَّل القطعة كمُسلَّمة كما تُبلغ شركة الشحن. لا يتغير شيء في شوبيفاي.')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'تسجيل كمشحون' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('لم يعد هناك ما يُنقل')
})
